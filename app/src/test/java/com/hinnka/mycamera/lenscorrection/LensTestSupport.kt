package com.hinnka.mycamera.lenscorrection

/** 镜头畸变测试用的小工具（确定性，无随机）。 */
internal object LensTestSupport {

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    fun channel(pixel: Int, shift: Int): Int = (pixel ushr shift) and 0xFF

    fun alpha(pixel: Int): Int = channel(pixel, 24)

    /** 水平灰度渐变。 */
    fun ramp(width: Int, height: Int, lo: Int, hi: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = if (width == 1) 0f else x.toFloat() / (width - 1)
                val v = (lo + (hi - lo) * t).toInt().coerceIn(0, 255)
                out[y * width + x] = argb(v, v, v)
            }
        }
        return out
    }

    /** 确定性棋盘格（大格 4px），保证双线性采样有真实梯度。 */
    fun checker(width: Int, height: Int, cell: Int = 4): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val v = if (((x / cell) + (y / cell)) % 2 == 0) 40 else 210
            argb(v, v, v)
        }

    fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var maxDiff = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            for (shift in intArrayOf(24, 16, 8, 0)) {
                maxDiff = maxOf(maxDiff, kotlin.math.abs(channel(a[i], shift) - channel(b[i], shift)))
            }
        }
        return maxDiff
    }

    fun countTransparent(pixels: IntArray): Int = pixels.count { alpha(it) == 0 }

    fun uniform(width: Int, height: Int, r: Int, g: Int, b: Int, a: Int = 255): IntArray =
        IntArray(width * height) { argb(r, g, b, a) }
}
