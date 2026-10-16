package com.hinnka.mycamera.enhance

import kotlin.math.abs

/**
 * 测试支持工具：造图 / 取通道 / 统计量。
 * 全部为确定性伪随机（LCG），保证测试可重复。
 */
internal object EnhanceTestSupport {

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    fun fill(width: Int, height: Int, r: Int, g: Int, b: Int): IntArray =
        IntArray(width * height) { argb(r, g, b) }

    /** 水平线性渐变（0..255 通道值由 lo 到 hi）。 */
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

    /** 垂直阶跃：左半 lo，右半 hi。 */
    fun step(width: Int, height: Int, lo: Int, hi: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 2) lo else hi
                out[y * width + x] = argb(v, v, v)
            }
        }
        return out
    }

    /** 在 base 上加确定性 ±amplitude 噪声。 */
    fun noisy(width: Int, height: Int, base: Int, amplitude: Int, seed: Long = 7L): IntArray {
        val out = IntArray(width * height)
        var state = seed
        for (i in out.indices) {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val noise = ((state ushr 40) % (2 * amplitude + 1)).toInt() - amplitude
            val v = (base + noise).coerceIn(0, 255)
            out[i] = argb(v, v, v)
        }
        return out
    }

    fun channel(pixel: Int, shift: Int): Int = (pixel ushr shift) and 0xFF

    fun alpha(pixel: Int): Int = channel(pixel, 24)

    fun red(pixel: Int): Int = channel(pixel, 16)

    fun green(pixel: Int): Int = channel(pixel, 8)

    fun blue(pixel: Int): Int = channel(pixel, 0)

    fun meanLuma(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0f
        for (p in pixels) {
            sum += (0.2126f * red(p) + 0.7152f * green(p) + 0.0722f * blue(p)) / 255f
        }
        return sum / pixels.size
    }

    fun meanChannel(pixels: IntArray, shift: Int): Float {
        if (pixels.isEmpty()) return 0f
        var sum = 0f
        for (p in pixels) sum += channel(p, shift)
        return sum / pixels.size
    }

    fun variance(pixels: IntArray, shift: Int = 16): Double {
        if (pixels.isEmpty()) return 0.0
        val mean = meanChannel(pixels, shift).toDouble()
        var acc = 0.0
        for (p in pixels) {
            val d = channel(p, shift) - mean
            acc += d * d
        }
        return acc / pixels.size
    }

    fun range(pixels: IntArray, shift: Int = 16): Int {
        if (pixels.isEmpty()) return 0
        var lo = 255
        var hi = 0
        for (p in pixels) {
            val v = channel(p, shift)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return hi - lo
    }

    /** 逐像素完全相等（含 alpha）。 */
    fun identical(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /** 逐通道最大绝对差（0..255）。 */
    fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var maxDiff = 0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            for (shift in intArrayOf(16, 8, 0)) {
                maxDiff = maxOf(maxDiff, abs(channel(a[i], shift) - channel(b[i], shift)))
            }
        }
        return maxDiff
    }

    /** 指定像素到画面中心（像素坐标）的距离。 */
    fun distanceFromCenter(x: Int, y: Int, width: Int, height: Int): Float {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x + 0.5f - cx
        val dy = y + 0.5f - cy
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
