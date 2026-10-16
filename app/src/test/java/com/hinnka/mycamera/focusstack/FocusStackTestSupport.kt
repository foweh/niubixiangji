package com.hinnka.mycamera.focusstack

import kotlin.math.abs

/**
 * 焦点堆叠单测的合成素材工具（纯 Kotlin，无 Android 依赖）。
 *
 * 不依赖任何真实图片：用可复现的合成图案（棋盘/噪声/纯色 + 可控模糊）来验证
 * 清晰度图、掩模与融合的数值行为。
 */
object FocusStackTestSupport {

    /** 纯色图。 */
    fun solidArgb(width: Int, height: Int, argb: Int): IntArray =
        IntArray(width * height) { argb }

    /** 棋盘（高频细节，[cell] 为格子边长）；清晰度判据的理想素材。 */
    fun checkerArgb(width: Int, height: Int, cell: Int = 2): IntArray {
        val c = cell.coerceAtLeast(1)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val on = ((x / c) + (y / c)) % 2 == 0
            argb(if (on) 255 else 0, if (on) 255 else 0, if (on) 255 else 0)
        }
    }

    /** 确定性伪随机噪声（LCG），SAD 对齐有唯一极小值，避免周期图案的对齐歧义。 */
    fun noiseArgb(width: Int, height: Int, seed: Long = 20261016L): IntArray {
        var state = seed
        return IntArray(width * height) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val v = ((state ushr 33) and 0xFF).toInt()
            argb(v, v, v)
        }
    }

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** 全图盒式模糊（模拟「失焦」的一帧）。 */
    fun blurArgb(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels.copyOf()
        val r = IntArray(pixels.size); val g = IntArray(pixels.size); val b = IntArray(pixels.size)
        for (i in pixels.indices) {
            r[i] = (pixels[i] ushr 16) and 0xFF
            g[i] = (pixels[i] ushr 8) and 0xFF
            b[i] = pixels[i] and 0xFF
        }
        val br = boxBlur(r, width, height, radius)
        val bg = boxBlur(g, width, height, radius)
        val bb = boxBlur(b, width, height, radius)
        return IntArray(pixels.size) { i ->
            argb(br[i], bg[i], bb[i])
        }
    }

    /** 只对 [x0, x1) 列区间模糊（构造「左清晰 / 右失焦」的一帧）。 */
    fun blurRegionArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        x1: Int,
        radius: Int,
    ): IntArray {
        val blurred = blurArgb(pixels, width, height, radius)
        val out = pixels.copyOf()
        for (y in 0 until height) {
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(width)) {
                out[y * width + x] = blurred[y * width + x]
            }
        }
        return out
    }

    /** 通道均值（模糊核用；与 [FocusClarityMap.boxBlur] 同口径）。 */
    private fun boxBlur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val asFloat = FloatArray(src.size) { src[it].toFloat() }
        val blurred = FocusClarityMap.boxBlur(asFloat, width, height, radius)
        return IntArray(blurred.size) { blurred[it].toInt().coerceIn(0, 255) }
    }

    /** 逐像素亮度（与算法同口径）。 */
    fun luma(pixels: IntArray): FloatArray = FocusClarityMap.lumaFromArgb(pixels, pixels.size)

    /** 两张图是否逐像素完全一致。 */
    fun identical(a: IntArray, b: IntArray): Boolean = a.contentEquals(b)

    /** 逐像素最大绝对差（通道级）。 */
    fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) {
            if (i >= b.size) break
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a[i] ushr shift) and 0xFF) - ((b[i] ushr shift) and 0xFF))
                if (d > worst) worst = d
            }
        }
        return worst
    }

    /** 指定像素位置的通道均值（便于区域断言）。 */
    fun channelAt(pixels: IntArray, index: Int): Triple<Int, Int, Int> {
        val c = pixels.getOrElse(index) { 0 }
        return Triple((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)
    }

    /** 生成 [count] 帧：第 0 帧清晰、其余逐帧加重模糊（模拟焦点扫描序列）。 */
    fun defocusSequence(width: Int, height: Int, count: Int, cell: Int = 2): List<IntArray> {
        val base = checkerArgb(width, height, cell)
        return List(count) { k -> if (k == 0) base.copyOf() else blurArgb(base, width, height, k) }
    }
}
