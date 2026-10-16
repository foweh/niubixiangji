package com.hinnka.mycamera.enhance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 可分离盒式模糊 / Sobel / 导向滤波的公共底层实现。
 *
 * 盒式滤波用滑动窗口和（O(1)/像素，边界 clamp），不需要积分图，
 * 因此额外内存只需一个输出数组——对 3.9G 内存的目标机很关键。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
internal object BoxBlur {

    /**
     * 单通道盒式模糊，半径 [radius] 像素（>0），边界按 clamp 复制。
     * 分水平/垂直两趟，等价于 (2r+1)×(2r+1) 均值核。
     */
    fun box(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0 || width <= 0 || height <= 0) return src.copyOf()
        val horizontal = FloatArray(src.size)
        val window = 2 * radius + 1
        // 水平趟
        for (y in 0 until height) {
            val base = y * width
            var sum = 0f
            for (k in -radius..radius) sum += src[base + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                horizontal[base + x] = sum / window
                val addIndex = (x + radius + 1).coerceAtMost(width - 1)
                val removeIndex = (x - radius).coerceAtLeast(0)
                sum += src[base + addIndex] - src[base + removeIndex]
            }
        }
        // 垂直趟
        val out = FloatArray(src.size)
        val stride = width
        for (x in 0 until width) {
            var sum = 0f
            for (k in -radius..radius) sum += horizontal[k.coerceIn(0, height - 1) * stride + x]
            for (y in 0 until height) {
                out[y * stride + x] = sum / window
                val addIndex = (y + radius + 1).coerceAtMost(height - 1)
                val removeIndex = (y - radius).coerceAtLeast(0)
                sum += horizontal[addIndex * stride + x] - horizontal[removeIndex * stride + x]
            }
        }
        return out
    }

    /**
     * 三次盒式模糊近似高斯（中心极限定理），用于反锐化掩模的模糊项。
     * 三次迭代的等效半径略大于单次，这里按 [radius] 逐次调用，够用且无额外依赖。
     */
    fun gaussianApprox(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        var acc = box(src, width, height, radius)
        acc = box(acc, width, height, radius)
        return box(acc, width, height, radius)
    }

    /** 对三个通道做同一半径模糊，返回新缓冲（不修改入参）。 */
    fun blurRgb(src: PlanarRgb, radius: Int): PlanarRgb {
        val out = PlanarRgb(src.width, src.height)
        for (c in 0 until 3) {
            box(src.channel(c), src.width, src.height, radius).copyInto(out.channel(c))
        }
        return out
    }

    /**
     * 3×3 Sobel 梯度模长（单通道），边界 clamp。
     * 输出与输入同尺寸；用于素描/油画的边缘提取。
     */
    fun sobelMagnitude(src: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(src.size)
        if (width < 1 || height < 1) return out
        for (y in 0 until height) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y2 = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(width - 1)
                val p00 = src[y0 * width + x0]
                val p01 = src[y0 * width + x]
                val p02 = src[y0 * width + x2]
                val p10 = src[y * width + x0]
                val p12 = src[y * width + x2]
                val p20 = src[y2 * width + x0]
                val p21 = src[y2 * width + x]
                val p22 = src[y2 * width + x2]
                val gx = (p02 + 2f * p12 + p22) - (p00 + 2f * p10 + p20)
                val gy = (p20 + 2f * p21 + p22) - (p00 + 2f * p01 + p02)
                out[y * width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /**
     * 单通道导向滤波（He et al. Guided Filter 思路，参考 opencv/opencv 的 soften/transmission 细化用法）。
     * 用 [guide] 作为引导，对 [input] 做边缘保持平滑；半径 0 时返回输入副本。
     */
    fun guidedFilter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float,
    ): FloatArray {
        if (radius <= 0) return input.copyOf()
        val eps = if (epsilon.isFinite() && epsilon > 0f) epsilon else 1e-4f
        val n = width * height
        val meanGuide = box(guide, width, height, radius)
        val meanInput = box(input, width, height, radius)
        val guideSq = FloatArray(n) { guide[it] * guide[it] }
        val guideInput = FloatArray(n) { guide[it] * input[it] }
        val meanGuideSq = box(guideSq, width, height, radius)
        val meanGuideInput = box(guideInput, width, height, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = meanGuideSq[i] - meanGuide[i] * meanGuide[i]
            val covariance = meanGuideInput[i] - meanGuide[i] * meanInput[i]
            val denom = variance + eps
            val ai = if (denom > 1e-12f) covariance / denom else 0f
            a[i] = ai
            b[i] = meanInput[i] - ai * meanGuide[i]
        }
        val meanA = box(a, width, height, radius)
        val meanB = box(b, width, height, radius)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** 分离式盒式核的整型半径换算（浮点半径 → 最近的整数半径）。 */
    fun radiusFromFloat(radius: Float): Int = when {
        radius.isNaN() -> 0
        radius <= 0f -> 0
        else -> max(1, radius.roundToInt())
    }

    /**
     * 分位估计（0..1 输入域的直方图近似，O(n) 且只占 bins 个 int）。
     *
     * 为什么不用排序：去雾的暗通道/单通道动辄千万像素，`copyOf + sort` 会瞬间多出
     * 数十 MB 内存与 O(n log n) 时间，与「峰值受限」的硬约束冲突。
     * 代价是分位精度 1/(bins-1) ≈ 0.1%，对大气光估计完全够用。
     */
    fun percentile(values: FloatArray, fraction: Float, bins: Int = DEFAULT_PERCENTILE_BINS): Float {
        if (values.isEmpty()) return 0f
        val binCount = bins.coerceAtLeast(2)
        val f = if (fraction.isNaN()) 1f else fraction.coerceIn(0f, 1f)
        val hist = IntArray(binCount)
        var count = 0
        for (v in values) {
            val clamped = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
            hist[(clamped * (binCount - 1)).roundToInt().coerceIn(0, binCount - 1)]++
            count++
        }
        val target = ((count - 1) * f).roundToInt().coerceIn(0, count - 1)
        var accumulated = 0
        for (i in 0 until binCount) {
            accumulated += hist[i]
            if (accumulated > target) return i.toFloat() / (binCount - 1).toFloat()
        }
        return 1f
    }

    /** 分位估计默认 bin 数（1024 档 ≈ 0.1% 精度）。 */
    const val DEFAULT_PERCENTILE_BINS = 1024

    /** 平滑阶跃：edge0→edge1 之间做 3t²-2t³ 插值，边界外取 0/1。 */
    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 逐像素最大差（自检/测试用）。 */
    fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) m = max(m, abs(a[i] - b[i]))
        return m
    }
}
