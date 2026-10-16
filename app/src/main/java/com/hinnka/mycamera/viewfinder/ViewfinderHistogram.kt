package com.hinnka.mycamera.viewfinder

/** 直方图通道。 */
enum class ViewfinderHistogramChannel { LUMA, RED, GREEN, BLUE }

/**
 * 取景器实时直方图数据（亮度 + RGB 三通道，bin 数可配）。
 *
 * 纯数据容器，不依赖 Android 框架，便于单测。
 */
class ViewfinderHistogram private constructor(
    val binCount: Int,
    val luminance: IntArray,
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    /** 实际参与统计的采样像素数。 */
    val sampleCount: Int,
) {
    fun binsFor(channel: ViewfinderHistogramChannel): IntArray = when (channel) {
        ViewfinderHistogramChannel.LUMA -> luminance
        ViewfinderHistogramChannel.RED -> red
        ViewfinderHistogramChannel.GREEN -> green
        ViewfinderHistogramChannel.BLUE -> blue
    }

    /** 单个 bin 的样本计数上限（用于归一化显示，0 表示无样本）。 */
    fun peakCount(): Int = maxOf(
        luminance.maxOrNull() ?: 0,
        red.maxOrNull() ?: 0,
        green.maxOrNull() ?: 0,
        blue.maxOrNull() ?: 0,
    )

    /** 归一化到 0f..1f 的显示高度（相对四通道峰值）。 */
    fun normalized(channel: ViewfinderHistogramChannel): FloatArray {
        val bins = binsFor(channel)
        val peak = peakCount()
        if (peak <= 0) return FloatArray(bins.size)
        return FloatArray(bins.size) { index ->
            (bins[index].toFloat() / peak.toFloat()).coerceIn(0f, 1f)
        }
    }

    /** 亮度分布加权均值（0f..255f），空数据返回 0f，可用于曝光趋势提示。 */
    fun meanLumaValue(): Float {
        var total = 0.0
        var weighted = 0.0
        val scale = 255.0 / (binCount - 1).coerceAtLeast(1).toDouble()
        for (index in luminance.indices) {
            val count = luminance[index]
            if (count == 0) continue
            total += count
            weighted += count * (index * scale)
        }
        if (total <= 0.0) return 0f
        return (weighted / total).toFloat()
    }

    /** 亮度分布中位 bin 的中心值（0f..255f）。 */
    fun medianLumaValue(): Float {
        val total = luminance.sum()
        if (total <= 0) return 0f
        val target = total / 2
        var running = 0
        for (index in luminance.indices) {
            running += luminance[index]
            if (running >= target) {
                val scale = 255.0 / (binCount - 1).coerceAtLeast(1).toDouble()
                return (index * scale).toFloat()
            }
        }
        return 255f
    }

    companion object {
        fun create(binCount: Int, luminance: IntArray, red: IntArray, green: IntArray, blue: IntArray, sampleCount: Int): ViewfinderHistogram {
            val bins = ViewfinderParamRanges.snapBinCount(binCount)
            return ViewfinderHistogram(
                binCount = bins,
                luminance = if (luminance.size == bins) luminance else IntArray(bins),
                red = if (red.size == bins) red else IntArray(bins),
                green = if (green.size == bins) green else IntArray(bins),
                blue = if (blue.size == bins) blue else IntArray(bins),
                sampleCount = sampleCount.coerceAtLeast(0),
            )
        }

        fun empty(binCount: Int = ViewfinderParamRanges.HISTOGRAM_BIN_DEFAULT): ViewfinderHistogram {
            val bins = ViewfinderParamRanges.snapBinCount(binCount)
            return ViewfinderHistogram(
                binCount = bins,
                luminance = IntArray(bins),
                red = IntArray(bins),
                green = IntArray(bins),
                blue = IntArray(bins),
                sampleCount = 0,
            )
        }

        /**
         * 任意 bin 数 → N bin 的分桶降采样（用于复用 256-bin 既有结果）。
         *
         * 桶边界按 `index * N / 原bin数` 归属，空桶保留 0；纯函数，可单测。
         */
        fun downsample(source: IntArray, targetBins: Int): IntArray {
            val bins = ViewfinderParamRanges.snapBinCount(targetBins)
            val result = IntArray(bins)
            if (source.isEmpty()) return result
            for (index in source.indices) {
                val target = (index.toLong() * bins / source.size.toLong())
                    .toInt()
                    .coerceIn(0, bins - 1)
                result[target] += source[index]
            }
            return result
        }
    }
}

/**
 * 直方图显示 EMA 平滑（避免逐帧跳变）。
 *
 * `smoothing` = 0 表示不平滑（直接采用新值），越大越平滑。
 * 平滑结果按 bin 数缓存，bin 数变化时自动重置。
 */
class ViewfinderHistogramSmoother(
    smoothing: Float = ViewfinderParamRanges.HISTOGRAM_SMOOTHING_DEFAULT,
) {
    private var cachedBinCount = 0
    private val cachedLuma = LinkedHashMap<ViewfinderHistogramChannel, FloatArray>()

    /** 平滑系数（构造与赋值都 clamp 到 0f..0.95f；NaN 归 0f）。 */
    var smoothingFactor: Float = clampSmoothing(smoothing)
        set(value) {
            field = clampSmoothing(value)
        }

    /** 用最新一帧刷新并返回平滑后的显示值。 */
    fun update(source: ViewfinderHistogram): Map<ViewfinderHistogramChannel, FloatArray> {
        if (source.binCount != cachedBinCount) {
            cachedLuma.clear()
            cachedBinCount = source.binCount
        }
        val alpha = 1f - smoothingFactor
        val result = LinkedHashMap<ViewfinderHistogramChannel, FloatArray>()
        for (channel in ViewfinderHistogramChannel.entries) {
            val incoming = source.normalized(channel)
            val previous = cachedLuma[channel]
            val next = if (previous == null || previous.size != incoming.size || alpha >= 1f) {
                incoming
            } else {
                FloatArray(incoming.size) { index ->
                    previous[index] + (incoming[index] - previous[index]) * alpha
                }
            }
            cachedLuma[channel] = next
            result[channel] = next
        }
        return result
    }

    fun reset() {
        cachedLuma.clear()
        cachedBinCount = 0
    }

    private fun clampSmoothing(value: Float): Float = if (value.isFinite()) {
        value.coerceIn(
            ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MIN,
            ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MAX,
        )
    } else {
        ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MIN
    }
}
