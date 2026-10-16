package com.hinnka.mycamera.focusstack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 焦点堆叠融合核 —— 按软掩模逐像素加权合成「全焦」图，并支持平移对齐。
 *
 * 设计要点（遵循 PATTERN_SPEC §1.5「不整批驻留」）：
 * - 清晰度图以 **1 字节/像素/帧** 量化存储（[quantizeClarity]），把掩模常驻内存压到浮点的 1/4；
 * - 融合走**流式累加**（[accumulateWeighted]）：一次只驻留一帧 + 一个输出累加器；
 * - 掩模平滑 / 一致性滤波在 [FocusClarityMap] 内完成，本对象只做「累积 + 落像素 + 对齐」。
 *
 * 算法思路来源：PetteriAimonen/focus-stack（MIT，Star≈670，C++）的
 * 「细节能量 → 逐像素选择/加权 + 邻帧对齐」；本工程实现为软掩模加权 + 可选平移对齐（见 changelog）。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object FocusStackFusion {

    /** 掩模权重下限（避免除零；与 [FocusClarityMap.MASK_EPS] 同口径）。 */
    const val MASK_EPS = FocusClarityMap.MASK_EPS

    /** 单通道上限（8bit ARGB）。 */
    private const val CHANNEL_MAX = 255f

    // ------------------------------------------------------------------ 清晰度量化

    /** 归一化清晰度（0..1）→ 字节（1B/像素/帧）。 */
    fun quantizeClarity(clarity: FloatArray): ByteArray {
        val out = ByteArray(clarity.size)
        for (i in clarity.indices) {
            val v = clarity[i]
            out[i] = if (v.isNaN() || v <= 0f) {
                0
            } else {
                (v.coerceAtMost(1f) * CHANNEL_MAX).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 字节 → 归一化浮点（0..1）。 */
    fun dequantizeClarity(quantized: ByteArray, size: Int = quantized.size): FloatArray {
        val n = size.coerceIn(0, quantized.size)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (quantized[i].toInt() and 0xFF) / CHANNEL_MAX
        return out
    }

    // ------------------------------------------------------------------ 流式加权累加

    /**
     * 加权累加一帧：`acc += mask_i · pixels_i`（逐通道，float 0..255）。
     *
     * @param acc 长度 `3·count` 的交错 RGB 累加器（外部只分配一次，跨帧复用）。
     * @param mask 长度 `count` 的软掩模（Σ≈1）。
     */
    fun accumulateWeighted(acc: FloatArray, mask: FloatArray, pixels: IntArray, count: Int) {
        val n = min(count, min(mask.size, min(pixels.size, acc.size / 3)))
        for (i in 0 until n) {
            val w = mask[i]
            if (w <= 0f || w.isNaN()) continue
            val c = pixels[i]
            val base = i * 3
            acc[base] += w * ((c ushr 16) and 0xFF)
            acc[base + 1] += w * ((c ushr 8) and 0xFF)
            acc[base + 2] += w * (c and 0xFF)
        }
    }

    /**
     * 累加器 → ARGB。
     *
     * @param strength 融合强度：`out = base·(1-s) + fused·s`；[basePixels] 为空时忽略该混合
     *   （等价 s=1）。
     */
    fun finalizeArgb(
        acc: FloatArray,
        count: Int,
        strength: Float = 1f,
        basePixels: IntArray? = null,
    ): IntArray {
        val n = count.coerceIn(0, acc.size / 3)
        val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 1f
        val out = IntArray(n)
        val base = basePixels?.takeIf { it.size >= n }
        for (i in 0 until n) {
            val k = i * 3
            var r = acc[k]; var g = acc[k + 1]; var b = acc[k + 2]
            if (base != null) {
                val c = base[i]
                val inv = 1f - s
                r = r * s + ((c ushr 16) and 0xFF) * inv
                g = g * s + ((c ushr 8) and 0xFF) * inv
                b = b * s + (c and 0xFF) * inv
            } else if (s < 1f) {
                // 无基准帧时强度<1 无意义（无参考可混），保持融合结果。
            }
            out[i] = (0xFF shl 24) or
                (clampChannel(r) shl 16) or (clampChannel(g) shl 8) or clampChannel(b)
        }
        return out
    }

    /** 退化为均值融合（所有帧对焦位置相同时的合理输出，也用于对照测试）。 */
    fun fuseUniform(pixels: List<IntArray>, count: Int): IntArray {
        if (pixels.isEmpty() || count <= 0) return IntArray(0)
        val out = FloatArray(count * 3)
        val uniform = 1f / pixels.size.toFloat()
        for (frame in pixels) {
            for (i in 0 until count) {
                val c = frame.getOrElse(i) { 0xFF000000.toInt() }
                val k = i * 3
                out[k] += uniform * ((c ushr 16) and 0xFF)
                out[k + 1] += uniform * ((c ushr 8) and 0xFF)
                out[k + 2] += uniform * (c and 0xFF)
            }
        }
        return finalizeArgb(out, count, strength = 1f)
    }

    // ------------------------------------------------------------------ 平移对齐

    /**
     * 估计 [tgt] 相对 [ref] 的整数像素平移（返回 `[dx, dy]`）。
     *
     * 两级粗到细：先按 2 的幂降采样到「搜索半径 ≤ 8」，在全图 SAD 上粗搜；
     * 再在原始分辨率上以粗结果为初值做 ±倍数 的细搜。复杂度与图像尺寸线性相关。
     *
     * @param maxShift 最大平移（像素）；0 或负数表示不做对齐（返回 `[0, 0]`）。
     */
    fun estimateShift(
        ref: FloatArray,
        tgt: FloatArray,
        width: Int,
        height: Int,
        maxShift: Int,
    ): IntArray {
        if (maxShift <= 0 || width <= 0 || height <= 0) return intArrayOf(0, 0)
        val limit = maxShift.coerceIn(0, FocusStackConfig.ALIGN_MAX_SHIFT_MAX)
        val size = minOf(ref.size, tgt.size, width * height)
        if (size < 4) return intArrayOf(0, 0)

        // 粗层级：factor = 2^n，使 limit/factor <= 8
        var factor = 1
        while (limit / factor > 8 && factor < 1024) factor *= 2

        // 粗层级用不到（搜索半径本身就小）时，直接在原分辨率上全域搜索；否则先粗搜再细搜。
        val centreX: Int
        val centreY: Int
        val radius: Int
        if (factor > 1) {
            val cw = max(1, width / factor)
            val ch = max(1, height / factor)
            val refC = downsampleFloat(ref, width, height, cw, ch)
            val tgtC = downsampleFloat(tgt, width, height, cw, ch)
            val coarse = searchShift(refC, tgtC, cw, ch, max(0, limit / factor))
            centreX = coarse[0] * factor
            centreY = coarse[1] * factor
            radius = factor
        } else {
            centreX = 0
            centreY = 0
            radius = limit
        }

        val fine = searchShift(ref, tgt, width, height, radius, centreX, centreY, size)
        return intArrayOf(fine[0], fine[1])
    }

    /** 平移浮点图（边界钳位复制；`out(x,y) = src(x-dx, y-dy)`）。 */
    fun shiftFloat(src: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
        val out = FloatArray(src.size)
        if (width <= 0 || height <= 0) return out
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            val rowOut = y * width
            val rowSrc = sy * width
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                out[rowOut + x] = src[rowSrc + sx]
            }
        }
        return out
    }

    /** 平移 ARGB 像素（边界钳位复制）。 */
    fun shiftArgb(src: IntArray, width: Int, height: Int, dx: Int, dy: Int): IntArray {
        if (dx == 0 && dy == 0) return src
        val out = IntArray(src.size)
        if (width <= 0 || height <= 0) return out
        for (y in 0 until height) {
            val sy = (y - dy).coerceIn(0, height - 1)
            val rowOut = y * width
            val rowSrc = sy * width
            for (x in 0 until width) {
                val sx = (x - dx).coerceIn(0, width - 1)
                out[rowOut + x] = src[rowSrc + sx]
            }
        }
        return out
    }

    /** 盒式降采样浮点图（对齐粗搜用）。 */
    fun downsampleFloat(src: FloatArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): FloatArray {
        val dw = dstWidth.coerceAtLeast(1)
        val dh = dstHeight.coerceAtLeast(1)
        val out = FloatArray(dw * dh)
        if (width <= 0 || height <= 0 || src.isEmpty()) return out
        for (y in 0 until dh) {
            val sy0 = (y.toLong() * height / dh).toInt().coerceIn(0, height - 1)
            val sy1 = (((y + 1).toLong() * height / dh).toInt() - 1).coerceIn(sy0, height - 1)
            for (x in 0 until dw) {
                val sx0 = (x.toLong() * width / dw).toInt().coerceIn(0, width - 1)
                val sx1 = (((x + 1).toLong() * width / dw).toInt() - 1).coerceIn(sx0, width - 1)
                var sum = 0f; var count = 0
                for (sy in sy0..sy1) {
                    val row = sy * width
                    for (sx in sx0..sx1) {
                        val idx = row + sx
                        if (idx < src.size) { sum += src[idx]; count++ }
                    }
                }
                out[y * dw + x] = if (count == 0) 0f else sum / count
            }
        }
        return out
    }

    /**
     * 在 ±[radius] 内（可选以 [centreX]/[centreY] 为中心）搜索最小 SAD 的平移量。
     */
    private fun searchShift(
        ref: FloatArray,
        tgt: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        centreX: Int = 0,
        centreY: Int = 0,
        size: Int = minOf(ref.size, tgt.size, width * height),
    ): IntArray {
        var bestDx = centreX
        var bestDy = centreY
        var bestSad = Float.MAX_VALUE
        val r = radius.coerceAtLeast(0)
        // 采样步长：像素过多时抽样求 SAD，保证对齐本身不成为瓶颈。
        val stride = max(1, kotlin.math.sqrt((width * height).toFloat() / 4096f).toInt())
        for (dy in -r..r) {
            for (dx in -r..r) {
                var sad = 0f
                var samples = 0
                var y = 0
                while (y < height) {
                    val sy = y - (dy + centreY)
                    if (sy in 0 until height) {
                        val rowRef = y * width
                        val rowTgt = sy * width
                        var x = 0
                        while (x < width) {
                            val sx = x - (dx + centreX)
                            if (sx in 0 until width) {
                                val a = rowRef + x
                                val b = rowTgt + sx
                                if (a < size && b < size) {
                                    sad += abs(ref[a] - tgt[b])
                                    samples++
                                }
                            }
                            x += stride
                        }
                    }
                    y += stride
                }
                val score = if (samples == 0) Float.MAX_VALUE else sad / samples
                if (score < bestSad) {
                    bestSad = score
                    bestDx = dx + centreX
                    bestDy = dy + centreY
                }
            }
        }
        // 限制在声明的最大平移内（细搜可能超出）。
        return intArrayOf(
            bestDx.coerceIn(-FocusStackConfig.ALIGN_MAX_SHIFT_MAX, FocusStackConfig.ALIGN_MAX_SHIFT_MAX),
            bestDy.coerceIn(-FocusStackConfig.ALIGN_MAX_SHIFT_MAX, FocusStackConfig.ALIGN_MAX_SHIFT_MAX),
        )
    }

    /** 0..255 通道钳位。 */
    fun clampChannel(v: Float): Int = when {
        v.isNaN() -> 0
        v <= 0f -> 0
        v >= CHANNEL_MAX -> 255
        else -> (v + 0.5f).toInt().coerceIn(0, 255)
    }
}
