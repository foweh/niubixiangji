package com.hinnka.mycamera.appgallerypreview

/**
 * 亮度直方图与曝光判定结果（纯数据，可 JVM 单测）。
 *
 * @param bins 亮度分箱计数（长度为 bins 数，索引 0=最暗）
 * @param rgbBins 分通道分箱（R/G/B 各一维，长度同 [bins]）；未统计时为空
 * @param totalPx 参与统计的像素数
 * @param overexposedPx 亮度 >= 过曝阈值的像素数
 * @param underexposedPx 亮度 <= 欠曝阈值的像素数
 */
data class AppGalleryPreviewHistogram(
    val bins: IntArray,
    val rgbBins: Array<IntArray> = emptyArray(),
    val totalPx: Int = 0,
    val overexposedPx: Int = 0,
    val underexposedPx: Int = 0,
) {
    val binCount: Int get() = bins.size

    /** 过曝像素占比 0..1。 */
    val overexposedRatio: Float
        get() = if (totalPx <= 0) 0f else overexposedPx.toFloat() / totalPx.toFloat()

    /** 欠曝像素占比 0..1。 */
    val underexposedRatio: Float
        get() = if (totalPx <= 0) 0f else underexposedPx.toFloat() / totalPx.toFloat()

    /** 峰值分箱计数（用于 UI 归一化）。 */
    val peak: Int get() = bins.maxOrNull() ?: 0

    /** 归一化到 0..1 的分箱（峰值=1）；全零时返回全 0。 */
    fun normalized(): FloatArray {
        val p = peak
        if (p <= 0) return FloatArray(binCount)
        return FloatArray(binCount) { bins[it].toFloat() / p.toFloat() }
    }

    /** 是否为空结果（无像素或无分箱）。 */
    val isEmpty: Boolean get() = totalPx <= 0 || binCount == 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppGalleryPreviewHistogram) return false
        return bins.contentEquals(other.bins) &&
            totalPx == other.totalPx &&
            overexposedPx == other.overexposedPx &&
            underexposedPx == other.underexposedPx &&
            rgbBins.size == other.rgbBins.size &&
            rgbBins.indices.all { rgbBins[it].contentEquals(other.rgbBins[it]) }
    }

    override fun hashCode(): Int {
        var result = bins.contentHashCode()
        result = 31 * result + totalPx
        result = 31 * result + overexposedPx
        result = 31 * result + underexposedPx
        result = 31 * result + rgbBins.contentDeepHashCode()
        return result
    }
}

/** 曝光判定结论（RAW 查看时的过曝/欠曝提示）。 */
enum class AppGalleryPreviewExposureVerdict {
    /** 无有效数据。 */
    UNKNOWN,

    /** 未触发裁剪。 */
    OK,

    /** 高光裁剪（过曝）。 */
    HIGHLIGHT_CLIPPED,

    /** 暗部裁剪（欠曝）。 */
    SHADOW_CLIPPED,

    /** 两端同时裁剪。 */
    BOTH_CLIPPED,
}

/**
 * 直方图统计（自研，纯 Kotlin 整数运算，无 Android 依赖，可 JVM 单测）。
 *
 * 自研理由：工程内无既存直方图工具；算法为 O(n) 累加，无第三方库价值，且需满足
 * 「分片累加 + 不限制总像素数」的性能硬约束（见 [AppGalleryPreviewHistogramAccumulator]）。
 *
 * 像素格式为 Android ARGB_8888 打包的 Int（A<<24|R<<16|G<<8|B）。
 * 亮度采用 Rec.709 权重（0.2126R + 0.7152G + 0.0722B），与视频域一致。
 */
object AppGalleryPreviewHistogramCalculator {

    /** 默认分箱数（与 [AppGalleryPreviewConfig.rawHistogramBins] 默认值一致）。 */
    const val DEFAULT_BINS = 256

    /**
     * 一次性统计整块像素。
     *
     * @param argb ARGB_8888 像素数组；空数组返回空结果（不抛异常）
     * @param bins 分箱数；越界 clamp 到 32..1024
     * @param overexposureThreshold 过曝阈值 0..1；越界 clamp
     * @param underexposureThreshold 欠曝阈值 0..1；越界 clamp
     * @param withRgb 是否同时统计分通道
     */
    fun compute(
        argb: IntArray,
        bins: Int = DEFAULT_BINS,
        overexposureThreshold: Float = 0.98f,
        underexposureThreshold: Float = 0.02f,
        withRgb: Boolean = false,
    ): AppGalleryPreviewHistogram = compute(argb, 0, argb.size, bins, overexposureThreshold, underexposureThreshold, withRgb)

    /**
     * 分片统计：只统计 [from, until) 区间（用于流式累加，避免整块驻留）。
     * 区间非法（越界/反向）时按空处理，不抛异常。
     */
    fun compute(
        argb: IntArray,
        from: Int,
        until: Int,
        bins: Int = DEFAULT_BINS,
        overexposureThreshold: Float = 0.98f,
        underexposureThreshold: Float = 0.02f,
        withRgb: Boolean = false,
    ): AppGalleryPreviewHistogram {
        val binCount = bins.coerceIn(32, 1024)
        val lo = from.coerceIn(0, argb.size)
        val hi = until.coerceIn(lo, argb.size)
        if (hi - lo <= 0) {
            return AppGalleryPreviewHistogram(
                bins = IntArray(binCount),
                rgbBins = if (withRgb) Array(3) { IntArray(binCount) } else emptyArray(),
            )
        }
        val overT = (overexposureThreshold.coerceIn(0f, 1f) * 255f).toInt()
        val underT = (underexposureThreshold.coerceIn(0f, 1f) * 255f).toInt()
        val acc = AppGalleryPreviewHistogramAccumulator(binCount, overT, underT, withRgb)
        for (i in lo until hi) {
            acc.add(argb[i])
        }
        return acc.toHistogram()
    }
}

/**
 * 流式直方图累加器（分片友好）。
 *
 * BgJobRunner 的每个分片内把像素喂给本累加器，分片末尾调 `job.checkYield()`；
 * 累加器只持有 [bins]/3 维 int 计数（256 箱 ≈ 4KB），与总像素数无关，
 * 因此**不对总像素数设上限**（性能硬约束）。
 */
class AppGalleryPreviewHistogramAccumulator(
    bins: Int = AppGalleryPreviewHistogramCalculator.DEFAULT_BINS,
    /** 过曝阈值，量化后的 0..255。 */
    private val overexposureLevel: Int = 250,
    /** 欠曝阈值，量化后的 0..255。 */
    private val underexposureLevel: Int = 5,
    private val withRgb: Boolean = false,
) {
    private val binCount: Int = bins.coerceIn(32, 1024)
    private val bins = IntArray(binCount)
    private val rgbBins: Array<IntArray> = if (withRgb) Array(3) { IntArray(binCount) } else emptyArray()
    private var total = 0
    private var over = 0
    private var under = 0

    /** 已累计像素数。 */
    val count: Int get() = total

    /** 累加单个 ARGB_8888 像素。 */
    fun add(argb: Int) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luma = lumaOf(r, g, b)
        val index = (luma * binCount) / 256
        bins[index.coerceIn(0, binCount - 1)]++
        total++
        if (luma >= overexposureLevel) over++
        if (luma <= underexposureLevel) under++
        if (withRgb) {
            rgbBins[0][(r * binCount) / 256]++
            rgbBins[1][(g * binCount) / 256]++
            rgbBins[2][(b * binCount) / 256]++
        }
    }

    fun toHistogram(): AppGalleryPreviewHistogram = AppGalleryPreviewHistogram(
        bins = bins.copyOf(),
        rgbBins = if (withRgb) Array(rgbBins.size) { rgbBins[it].copyOf() } else emptyArray(),
        totalPx = total,
        overexposedPx = over,
        underexposedPx = under,
    )

    /**
     * Rec.709 亮度权重。整数运算避免浮点误差：
     * (2126*R + 7152*G + 722*B) / 10000，四舍五入后取 0..255。
     */
    private fun lumaOf(r: Int, g: Int, b: Int): Int {
        val weighted = 2126 * r + 7152 * g + 722 * b
        return ((weighted + 5000) / 10000).coerceIn(0, 255)
    }
}

/**
 * 曝光判定策略（纯函数，可 JVM 单测）。
 */
object AppGalleryPreviewExposurePolicy {

    /**
     * 依据直方图与配置给出曝光结论。
     *
     * @param warnRatio 过曝/欠曝占比超过该值才提示；缺失时用配置值
     */
    fun verdict(
        histogram: AppGalleryPreviewHistogram,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
        warnRatio: Float? = null,
    ): AppGalleryPreviewExposureVerdict {
        if (histogram.isEmpty) return AppGalleryPreviewExposureVerdict.UNKNOWN
        val safe = config.appGalleryPreviewSafe().config
        val threshold = (warnRatio ?: safe.rawOverexposureWarnRatio).coerceIn(0f, 1f)
        val overHit = histogram.overexposedRatio > threshold
        val underHit = histogram.underexposedRatio > threshold
        return when {
            overHit && underHit -> AppGalleryPreviewExposureVerdict.BOTH_CLIPPED
            overHit -> AppGalleryPreviewExposureVerdict.HIGHLIGHT_CLIPPED
            underHit -> AppGalleryPreviewExposureVerdict.SHADOW_CLIPPED
            else -> AppGalleryPreviewExposureVerdict.OK
        }
    }

    /** 建议曝光补偿档数（EV，正=需要更亮）。1 档 = 1EV。 */
    fun suggestedCompensationEv(
        histogram: AppGalleryPreviewHistogram,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): Double {
        if (histogram.isEmpty) return 0.0
        val safe = config.appGalleryPreviewSafe().config
        if (!safe.rawShowOverexposureHint) return 0.0
        return when (verdict(histogram, safe)) {
            AppGalleryPreviewExposureVerdict.HIGHLIGHT_CLIPPED ->
                -maxOf(0.3, histogram.overexposedRatio * 4.0)
            AppGalleryPreviewExposureVerdict.SHADOW_CLIPPED ->
                maxOf(0.3, histogram.underexposedRatio * 4.0)
            else -> 0.0
        }
    }
}
