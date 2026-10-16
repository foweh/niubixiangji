package com.hinnka.mycamera.superres

import android.graphics.Bitmap

/**
 * Bitmap ↔ 张量浮点缓冲的编解码（纯像素搬运，不含推理）。
 *
 * 张量布局约定为 **NCHW / RGB / 0..1**（Real-ESRGAN、fast-neural-style 系 ONNX 的通用布局）；
 * 输出侧同时支持两种常见导出形态：
 *  ① 模型内已含 PixelShuffle → 输出通道数 3，尺寸 (H*s, W*s)；
 *  ② 模型外置 PixelShuffle → 输出通道数 3*s²，尺寸 (H, W)，需按子像素重排（本类处理）。
 *
 * 拆开的原因：像素搬运是纯运算，推理会话/Android 上下文都不需要，便于单元测试与复用。
 */
object SuperResBitmapCodec {

    /** 输出通道数按 `3*s²` 编码（像素重排）时使用的最大倍率，防止畸形模型导致超大缓冲。 */
    private const val MAX_SUPPORTED_SCALE = 8

    /**
     * Bitmap → NCHW 平面浮点（RGB，0..1）。
     *
     * 需要缩放时用双线性（[Bitmap.createScaledBitmap] 的滤波），保证 tiled 输入与整图一致。
     *
     * @param outWidth/outHeight 目标工作尺寸（通常由 `maxWorkingPixels` 决定）。
     * @return 长度 = 3*outWidth*outHeight 的平面缓冲：R 段 → G 段 → B 段。
     */
    fun decodeRgbPlanar(bitmap: Bitmap, outWidth: Int, outHeight: Int): FloatArray {
        val w = outWidth.coerceAtLeast(1)
        val h = outHeight.coerceAtLeast(1)
        val src = if (bitmap.width == w && bitmap.height == h) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = FloatArray(w * h * 3)
        val planeSize = w * h
        for (i in pixels.indices) {
            val c = pixels[i]
            plane[i] = ((c shr 16) and 0xFF) / 255f
            plane[planeSize + i] = ((c shr 8) and 0xFF) / 255f
            plane[planeSize * 2 + i] = (c and 0xFF) / 255f
        }
        if (src !== bitmap) src.recycle()
        return plane
    }

    /** Bitmap → HWC 交错浮点（RGB，0..1），长度 = w*h*3。用于风格迁移/修复的 NHWC 模型。 */
    fun decodeRgbInterleaved(bitmap: Bitmap, outWidth: Int, outHeight: Int): FloatArray {
        val planar = decodeRgbPlanar(bitmap, outWidth, outHeight)
        return planarToInterleaved(planar)
    }

    /** 提取掩膜：alpha < 阈值 或 亮度 < 阈值 视为「待修复」（1f），其余 0f。 */
    fun decodeMask(bitmap: Bitmap, outWidth: Int, outHeight: Int, threshold: Float): FloatArray {
        val w = outWidth.coerceAtLeast(1)
        val h = outHeight.coerceAtLeast(1)
        val src = if (bitmap.width == w && bitmap.height == h) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val inside = SuperResMaskFeather.maskFromArgb(pixels, w, h, threshold)
        val mask = FloatArray(inside.size)
        for (i in inside.indices) mask[i] = if (inside[i]) 1f else 0f
        if (src !== bitmap) src.recycle()
        return mask
    }

    /** NCHW 平面 → HWC 交错。 */
    fun planarToInterleaved(planar: FloatArray): FloatArray {
        val planeSize = planar.size / 3
        if (planeSize <= 0) return FloatArray(0)
        val out = FloatArray(planeSize * 3)
        for (i in 0 until planeSize) {
            out[i * 3] = planar[i]
            out[i * 3 + 1] = planar[planeSize + i]
            out[i * 3 + 2] = planar[planeSize * 2 + i]
        }
        return out
    }

    /**
     * 推理输出 → 交错 RGB（0..1），自动适配上面两种输出形态。
     *
     * @param output 模型原始输出（长度 = 3*(H*s)*(W*s) 或 3*s²*H*W）。
     * @param inWidth/inHeight 输入（未放大）尺寸。
     * @param outInterleaved 复用缓冲（长度需 >= inWidth*s*inHeight*s*3），可传 null 新建。
     */
    fun upscaledOutputToInterleaved(
        output: FloatArray,
        inWidth: Int,
        inHeight: Int,
        scale: Int,
        outInterleaved: FloatArray? = null,
    ): FloatArray {
        val s = scale.coerceIn(1, MAX_SUPPORTED_SCALE)
        val inPx = inWidth.coerceAtLeast(1) * inHeight.coerceAtLeast(1)
        val outW = inWidth.coerceAtLeast(1) * s
        val outH = inHeight.coerceAtLeast(1) * s
        val needed = outW * outH * 3
        val out = if (outInterleaved != null && outInterleaved.size >= needed) {
            outInterleaved
        } else {
            FloatArray(needed)
        }

        val directSize = 3 * outW * outH
        val shuffleSize = 3 * s * s * inPx
        when {
            // 形态①：模型内已含 PixelShuffle，通道数 3。
            output.size >= directSize -> {
                val planeSize = outW * outH
                for (i in 0 until planeSize) {
                    out[i * 3] = output[i]
                    out[i * 3 + 1] = output[planeSize + i]
                    out[i * 3 + 2] = output[planeSize * 2 + i]
                }
            }
            // 形态②：3*s² 通道，按 (c = ch*s² + subY*s + subX) 子像素重排。
            output.size >= shuffleSize -> {
                val planeSize = inPx
                for (ch in 0 until 3) {
                    for (sy in 0 until s) {
                        for (sx in 0 until s) {
                            val srcPlane = ((ch * s * s) + (sy * s) + sx) * planeSize
                            for (y in 0 until inHeight) {
                                val dstRow = (y * s + sy) * outW
                                val srcRow = srcPlane + y * inWidth
                                for (x in 0 until inWidth) {
                                    val idx = (dstRow + x * s + sx) * 3 + ch
                                    if (idx < out.size && srcRow + x < output.size) {
                                        out[idx] = output[srcRow + x]
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 形态③：尺寸对不上（畸形/不匹配模型）→ 直接顺序拷贝，交由上层按比例裁剪而不是崩。
            else -> {
                val n = minOf(output.size, out.size)
                for (i in 0 until n) out[i] = output[i]
            }
        }
        return out
    }

    /** 交错 RGB（0..1）→ Bitmap；[keepAlpha] 为真且给了 [alphaSource] 时保留原 alpha。 */
    fun encodeToBitmap(
        interleaved: FloatArray,
        width: Int,
        height: Int,
        alphaSource: Bitmap? = null,
        keepAlpha: Boolean = true,
    ): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val count = w * h
        val pixels = IntArray(count)
        for (i in 0 until count) {
            val base = i * 3
            val r = if (base < interleaved.size) toByte(interleaved[base]) else 0
            val g = if (base + 1 < interleaved.size) toByte(interleaved[base + 1]) else 0
            val b = if (base + 2 < interleaved.size) toByte(interleaved[base + 2]) else 0
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (keepAlpha && alphaSource != null) applyAlpha(out, alphaSource)
        return out
    }

    /** 把 [alphaSource] 的 alpha 通道（双线性缩放后）逐像素写进 [target]，不动 RGB。 */
    private fun applyAlpha(target: Bitmap, alphaSource: Bitmap) {
        val scaled = if (alphaSource.width == target.width && alphaSource.height == target.height) {
            alphaSource
        } else {
            Bitmap.createScaledBitmap(alphaSource, target.width, target.height, true)
        }
        val w = target.width
        val h = target.height
        val count = w * h
        val alpha = IntArray(count)
        scaled.getPixels(alpha, 0, w, 0, 0, w, h)
        val dst = IntArray(count)
        target.getPixels(dst, 0, w, 0, 0, w, h)
        for (i in 0 until count) {
            val a = (alpha[i] ushr 24) and 0xFF
            dst[i] = (a shl 24) or (dst[i] and 0x00FFFFFF)
        }
        target.setPixels(dst, 0, w, 0, 0, w, h)
        if (scaled !== alphaSource) scaled.recycle()
    }

    /** 浮点 0..1 → 字节，含 NaN 保护（NaN 会让整数转换产出 0，这里显式兜底为 0）。 */
    fun toByte(value: Float): Int {
        if (!value.isFinite()) return 0
        return (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
