package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewfinderHistogramAnalyzer] / [ViewfinderHistogram] 的纯 JVM 单测。
 *
 * 覆盖：正常统计正确性、bin 边界、64/128/256 三档 bins、空/非法输入、采样步长抽稀、
 * 归一化与均值/中位数、显示平滑、降采样。
 */
class ViewfinderHistogramAnalyzerTest {

    private val delta = 1e-4f

    // ---------------------------------------------------------- bin 下标边界

    @Test
    fun binIndex_mapsChannelValuesToBins() {
        // 256 bins：一一对应
        assertEquals(0, ViewfinderHistogramAnalyzer.binIndexOf(0, 256))
        assertEquals(128, ViewfinderHistogramAnalyzer.binIndexOf(128, 256))
        assertEquals(255, ViewfinderHistogramAnalyzer.binIndexOf(255, 256))
        // 128 bins：亮度 128 → 64
        assertEquals(64, ViewfinderHistogramAnalyzer.binIndexOf(128, 128))
        assertEquals(127, ViewfinderHistogramAnalyzer.binIndexOf(255, 128))
        // 64 bins：亮度 128 → 32，255 → 63
        assertEquals(32, ViewfinderHistogramAnalyzer.binIndexOf(128, 64))
        assertEquals(63, ViewfinderHistogramAnalyzer.binIndexOf(255, 64))
    }

    @Test
    fun binIndex_clampsOutOfRangeValues() {
        assertEquals(0, ViewfinderHistogramAnalyzer.binIndexOf(-40, 256))
        assertEquals(255, ViewfinderHistogramAnalyzer.binIndexOf(9999, 256))
        assertEquals(0, ViewfinderHistogramAnalyzer.binIndexOf(-1, 64))
        assertEquals(63, ViewfinderHistogramAnalyzer.binIndexOf(300, 64))
        // 非法 bin 数（0 / 负数）不崩溃
        assertEquals(0, ViewfinderHistogramAnalyzer.binIndexOf(10, 0))
        assertEquals(0, ViewfinderHistogramAnalyzer.binIndexOf(10, -8))
    }

    // ------------------------------------------------------------ 统计正确性

    @Test
    fun analyze_allBlack_landsInFirstBin() {
        val pixels = IntArray(4) { 0xFF000000.toInt() }
        val histogram = ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 64)

        assertEquals(64, histogram.binCount)
        assertEquals(4, histogram.sampleCount)
        assertEquals(4, histogram.luminance[0])
        assertEquals(4, histogram.red[0])
        assertEquals(4, histogram.green[0])
        assertEquals(4, histogram.blue[0])
        // 其余桶必须为空
        assertEquals(4, histogram.luminance.sum())
        assertEquals(0f, histogram.meanLumaValue(), delta)
        assertEquals(0f, histogram.medianLumaValue(), delta)
    }

    @Test
    fun analyze_allWhite_landsInLastBin() {
        val pixels = IntArray(4) { 0xFFFFFFFF.toInt() }
        val histogram = ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 64)

        assertEquals(4, histogram.luminance[63])
        assertEquals(0, histogram.luminance[0])
        assertEquals(4, histogram.luminance.sum())
        assertEquals(255f, histogram.meanLumaValue(), delta)
        assertEquals(255f, histogram.medianLumaValue(), delta)
    }

    @Test
    fun analyze_pureRed_splitsPerChannel() {
        val pixels = intArrayOf(0xFFFF0000.toInt())
        val histogram = ViewfinderHistogramAnalyzer.analyze(pixels, 1, 1, 256)

        // Rec.709 亮度：0.2126 * 255 = 54.2 → 54
        assertEquals(1, histogram.luminance[54])
        assertEquals(1, histogram.red[255])
        assertEquals(1, histogram.green[0])
        assertEquals(1, histogram.blue[0])
        assertEquals(1, histogram.sampleCount)
    }

    @Test
    fun analyze_binCountSnapping_acceptsAnyValue() {
        val pixels = IntArray(4) { 0xFFFFFFFF.toInt() }
        // 100 → 最近的 128
        assertEquals(128, ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 100).binCount)
        // 200 → 最近的 256
        assertEquals(256, ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 200).binCount)
        // 0 → 64
        assertEquals(64, ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 0).binCount)
    }

    // ------------------------------------------------------- 空 / 非法输入

    @Test
    fun analyze_emptyOrInvalidInput_returnsZeroFilledHistogram() {
        val empty = ViewfinderHistogramAnalyzer.analyze(IntArray(0), 0, 0, 64)
        assertEquals(64, empty.binCount)
        assertEquals(0, empty.sampleCount)
        assertEquals(0, empty.luminance.sum())

        val zeroWidth = ViewfinderHistogramAnalyzer.analyze(IntArray(16), 0, 4, 128)
        assertEquals(128, zeroWidth.binCount)
        assertEquals(0, zeroWidth.sampleCount)

        val negativeHeight = ViewfinderHistogramAnalyzer.analyze(IntArray(16), 4, -3, 256)
        assertEquals(0, negativeHeight.sampleCount)
    }

    @Test
    fun analyze_pixelArrayShorterThanDimensions_neverReadsOutOfBounds() {
        // 声明 10x10 但只给了 1 个像素：必须只统计 1 个采样点，不越界。
        val histogram = ViewfinderHistogramAnalyzer.analyze(
            pixels = intArrayOf(0xFFFFFFFF.toInt()),
            width = 10,
            height = 10,
            binCount = 64,
        )
        assertEquals(1, histogram.sampleCount)
        assertEquals(1, histogram.luminance.sum())
    }

    @Test
    fun analyze_largeImage_usesStrideSampling() {
        // 长边 2048 > MAX_SAMPLE_EDGE(512) → 整数 stride = 4（偏移 2）
        val width = 2048
        val height = 512
        val pixels = IntArray(width * height) { 0xFFFFFFFF.toInt() }
        val histogram = ViewfinderHistogramAnalyzer.analyze(pixels, width, height, 64)

        // x 采样 512 个、y 采样 128 个 → 65536
        assertEquals(512 * 128, histogram.sampleCount)
        assertTrue("抽稀后应远少于总像素", histogram.sampleCount < width * height)
        // 所有采样点都是白色 → 只有一个桶非空且等于采样数
        assertEquals(1, histogram.luminance.count { it > 0 })
        assertEquals(histogram.sampleCount, histogram.luminance[63])
    }

    // ------------------------------------------------------------ 归一化/峰值

    @Test
    fun normalized_relativeToFourChannelPeak() {
        val pixels = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
        )
        val histogram = ViewfinderHistogramAnalyzer.analyze(pixels, 2, 2, 64)
        val normalized = histogram.normalized(ViewfinderHistogramChannel.LUMA)

        assertEquals(3, histogram.peakCount())
        assertEquals(1f, normalized[63], delta)
        assertEquals(1f / 3f, normalized[0], delta)
    }

    @Test
    fun normalized_emptyHistogram_returnsZeroes() {
        val normalized = ViewfinderHistogram.empty(64).normalized(ViewfinderHistogramChannel.RED)
        assertEquals(64, normalized.size)
        assertTrue(normalized.all { it == 0f })
    }

    @Test
    fun binsFor_allChannelsHaveSameSize() {
        val histogram = ViewfinderHistogram.empty(256)
        for (channel in ViewfinderHistogramChannel.entries) {
            assertEquals(256, histogram.binsFor(channel).size)
        }
    }

    // ---------------------------------------------------------------- 平滑

    @Test
    fun smoother_zeroSmoothing_passesThrough() {
        val smoother = ViewfinderHistogramSmoother(0f)
        val first = smoother.update(histogramWithSumAt(bin = 0, count = 10, bins = 64))
        assertEquals(1f, first.getValue(ViewfinderHistogramChannel.LUMA)[0], delta)

        val second = smoother.update(histogramWithSumAt(bin = 10, count = 10, bins = 64))
        // smoothing = 0 → 直接采用新值
        assertEquals(0f, second.getValue(ViewfinderHistogramChannel.LUMA)[0], delta)
        assertEquals(1f, second.getValue(ViewfinderHistogramChannel.LUMA)[10], delta)
    }

    @Test
    fun smoother_halfSmoothing_blendsPreviousAndIncoming() {
        val smoother = ViewfinderHistogramSmoother(0.5f)
        smoother.update(histogramWithSumAt(bin = 0, count = 10, bins = 64))
        val blended = smoother.update(histogramWithSumAt(bin = 1, count = 10, bins = 64))

        val luma = blended.getValue(ViewfinderHistogramChannel.LUMA)
        assertEquals(0.5f, luma[0], delta)
        assertEquals(0.5f, luma[1], delta)
    }

    @Test
    fun smoother_binCountChange_resetsCache() {
        val smoother = ViewfinderHistogramSmoother(0.9f)
        smoother.update(ViewfinderHistogram.empty(64))
        val result = smoother.update(histogramWithSumAt(bin = 3, count = 5, bins = 256))
        assertEquals(256, result.getValue(ViewfinderHistogramChannel.LUMA).size)
        // bin 数变化后不存在旧缓存，直接采用新值
        assertEquals(1f, result.getValue(ViewfinderHistogramChannel.LUMA)[3], delta)
    }

    @Test
    fun smoother_smoothingFactorClampedAndResettable() {
        val smoother = ViewfinderHistogramSmoother(99f)
        assertEquals(0.95f, smoother.smoothingFactor, delta)
        smoother.smoothingFactor = -5f
        assertEquals(0f, smoother.smoothingFactor, delta)
        smoother.reset()
        val result = smoother.update(histogramWithSumAt(bin = 2, count = 4, bins = 64))
        assertEquals(1f, result.getValue(ViewfinderHistogramChannel.LUMA)[2], delta)
    }

    // -------------------------------------------------------------- 降采样

    @Test
    fun downsample_mergesBucketsAndKeepsTotal() {
        val source = IntArray(256) { 1 }
        val target = ViewfinderHistogram.downsample(source, 64)
        assertEquals(64, target.size)
        assertTrue(target.all { it == 4 })
        assertEquals(256, target.sum())
    }

    @Test
    fun downsample_directedMappingAndEmptySource() {
        val source = IntArray(256)
        source[0] = 7
        val target = ViewfinderHistogram.downsample(source, 128)
        assertEquals(7, target[0])
        assertEquals(7, target.sum())

        val empty = ViewfinderHistogram.downsample(IntArray(0), 64)
        assertEquals(64, empty.size)
        assertEquals(0, empty.sum())
    }

    @Test
    fun downsample_neverProducesIllegalBinCount() {
        assertEquals(64, ViewfinderHistogram.downsample(IntArray(256), 5).size)
        assertEquals(256, ViewfinderHistogram.downsample(IntArray(256), 1000).size)
    }

    // ------------------------------------------------------------ 通道枚举

    @Test
    fun channelEnum_exposesFourChannels() {
        assertEquals(4, ViewfinderHistogramChannel.entries.size)
        assertTrue(ViewfinderHistogramChannel.entries.contains(ViewfinderHistogramChannel.LUMA))
        assertTrue(ViewfinderHistogramChannel.entries.contains(ViewfinderHistogramChannel.BLUE))
    }

    private fun histogramWithSumAt(bin: Int, count: Int, bins: Int): ViewfinderHistogram {
        val luminance = IntArray(bins)
        luminance[bin] = count
        return ViewfinderHistogram.create(
            binCount = bins,
            luminance = luminance,
            red = IntArray(bins),
            green = IntArray(bins),
            blue = IntArray(bins),
            sampleCount = count,
        )
    }
}
