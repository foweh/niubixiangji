package com.hinnka.mycamera.multiframesr

import kotlin.math.roundToInt

/**
 * 色彩重组（纯逻辑，可在单测里验证）。
 *
 * 思路：MFSR 的超分/降噪增益全部加在**亮度**上（人眼对亮度分辨率最敏感），
 * 色度直接取参考帧并双线性升采样到 HR 尺寸再合并。这样既保留了多帧融合的全部收益，
 * 又避免色度通道亚像素对齐误差带来的彩边。
 */
object MfsrColor {

    /** 8bit YCbCr（BT.601 full-range）三平面。 */
    class YcbcrPlanes(val y: MfsrPlane, val cb: MfsrPlane, val cr: MfsrPlane)

    /** ARGB 整型数组 → YCbCr 三平面。 */
    fun fromArgb(argb: IntArray, width: Int, height: Int): YcbcrPlanes {
        require(argb.size >= width * height) { "argb too small" }
        val y = MfsrPlane(width, height)
        val cb = MfsrPlane(width, height)
        val cr = MfsrPlane(width, height)
        for (i in 0 until width * height) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            y.data[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb.data[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            cr.data[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }
        return YcbcrPlanes(y, cb, cr)
    }

    /**
     * 用 HR 亮度 + HR 尺寸色度重建 ARGB。
     *
     * @param cbHr / [crHr] 必须已升采样到与 [hrLuma] 相同的尺寸。
     * @param out 复用输出缓冲，长度须为 width*height。
     */
    fun combine(hrLuma: FloatArray, cbHr: MfsrPlane, crHr: MfsrPlane, out: IntArray) {
        require(hrLuma.size == cbHr.pixels && hrLuma.size == crHr.pixels) { "size mismatch" }
        require(out.size >= hrLuma.size) { "out too small" }
        for (i in hrLuma.indices) {
            val yv = hrLuma[i].takeIf { it.isFinite() } ?: 0f
            val cb = cbHr.data[i].takeIf { it.isFinite() } ?: 128f
            val cr = crHr.data[i].takeIf { it.isFinite() } ?: 128f
            val y = yv.coerceIn(0f, 255f)
            val r = y + 1.402f * (cr - 128f)
            val g = y - 0.344136f * (cb - 128f) - 0.714136f * (cr - 128f)
            val b = y + 1.772f * (cb - 128f)
            out[i] = 0xFF000000.toInt() or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }

    /** 色度升采样（双线性/区域平均，取决于放大或缩小）。 */
    fun upsampleChroma(plane: MfsrPlane, targetW: Int, targetH: Int): MfsrPlane =
        plane.resampleTo(targetW, targetH)
}
