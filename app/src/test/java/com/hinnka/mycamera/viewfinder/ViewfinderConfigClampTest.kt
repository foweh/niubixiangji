package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数取值域与 clamp 单测（正常 / 边界 / 异常三档）。
 *
 * 覆盖全模块：斑马纹、直方图、峰值对焦、AR 贴纸、聚合配置、bin 数吸附、灵敏度→阈值映射。
 * 全部为纯 JVM 断言，不需要 Android 运行时。
 */
class ViewfinderConfigClampTest {

    private val delta = 1e-6f

    // ------------------------------------------------------------ 默认值合法

    @Test
    fun defaultSettings_needNoCorrection() {
        val result = ViewfinderSettings.clamp(ViewfinderSettings())
        assertFalse(result.corrected)
        assertTrue(result.corrections.isEmpty())
        assertEquals(ViewfinderSettings(), result.value)

        // 各项默认值本身必须落在合法区间内
        assertEquals(false, result.value.zebra.enabled)
        assertEquals(false, result.value.histogram.enabled)
        assertEquals(false, result.value.sticker.enabled)
        assertEquals(true, result.value.peaking.enabled)
    }

    // ---------------------------------------------------------------- 斑马纹

    @Test
    fun zebraClamp_normalValuesKeep() {
        val config = ZebraConfig(
            thresholdPercent = 80f,
            stripeWidthPx = 20f,
            stripeDuty = 0.3f,
            stripeAngleDeg = 30f,
            stripeAlpha = 0.5f,
            metric = ZebraMetric.MAX_RGB,
        )
        val result = ZebraConfig.clamp(config)
        assertFalse(result.corrected)
        assertEquals(config, result.value)
    }

    @Test
    fun zebraClamp_boundaryValuesKeep() {
        val min = ZebraConfig(thresholdPercent = 50f, stripeWidthPx = 2f, stripeDuty = 0.1f, stripeAngleDeg = 0f, stripeAlpha = 0f)
        val max = ZebraConfig(thresholdPercent = 120f, stripeWidthPx = 64f, stripeDuty = 0.9f, stripeAngleDeg = 180f, stripeAlpha = 1f)
        assertFalse(ZebraConfig.clamp(min).corrected)
        assertFalse(ZebraConfig.clamp(max).corrected)
    }

    @Test
    fun zebraClamp_outOfRangeIsCorrected() {
        val result = ZebraConfig.clamp(
            ZebraConfig(thresholdPercent = 200f, stripeWidthPx = -5f, stripeDuty = 5f, stripeAngleDeg = 999f, stripeAlpha = 3f),
        )
        assertTrue(result.corrected)
        assertEquals(120f, result.value.thresholdPercent, delta)
        assertEquals(2f, result.value.stripeWidthPx, delta)
        assertEquals(0.9f, result.value.stripeDuty, delta)
        assertEquals(180f, result.value.stripeAngleDeg, delta)
        assertEquals(1f, result.value.stripeAlpha, delta)
        assertEquals(5, result.corrections.size)
    }

    @Test
    fun zebraClamp_nonFiniteFallsBackToMin() {
        val result = ZebraConfig.clamp(
            ZebraConfig(thresholdPercent = Float.NaN, stripeWidthPx = Float.POSITIVE_INFINITY),
        )
        assertEquals(50f, result.value.thresholdPercent, delta)
        assertEquals(2f, result.value.stripeWidthPx, delta)
        assertTrue(result.corrections.any { it.contains("zebra.thresholdPercent") })
    }

    @Test
    fun zebraThresholdDerivedFields() {
        assertEquals(242, ZebraConfig(thresholdPercent = 95f).lumaThreshold)
        assertEquals(242f / 255f, ZebraConfig(thresholdPercent = 95f).normalizedThreshold, delta)
    }

    // ---------------------------------------------------------------- 直方图

    @Test
    fun histogramClamp_binCountSnapsToAllowedValues() {
        assertEquals(64, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 64)).value.binCount)
        assertEquals(128, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 128)).value.binCount)
        assertEquals(256, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 256)).value.binCount)

        // 非法值就近吸附
        assertEquals(128, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 100)).value.binCount)
        assertEquals(256, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 300)).value.binCount)
        assertEquals(64, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 1)).value.binCount)
        assertEquals(64, HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = -1)).value.binCount)

        val snapped = HistogramOverlayConfig.clamp(HistogramOverlayConfig(binCount = 100))
        assertTrue(snapped.corrected)
        assertTrue(snapped.corrections.any { it.contains("binCount") })
    }

    @Test
    fun histogramClamp_intervalAndFractions() {
        val result = HistogramOverlayConfig.clamp(
            HistogramOverlayConfig(
                sampleIntervalMs = 10L,
                marginFraction = -1f,
                widthFraction = 5f,
                heightFraction = 0f,
                alpha = 0f,
                smoothing = 9f,
            ),
        )
        assertEquals(50L, result.value.sampleIntervalMs)
        assertEquals(0f, result.value.marginFraction, delta)
        assertEquals(0.6f, result.value.widthFraction, delta)
        assertEquals(0.04f, result.value.heightFraction, delta)
        assertEquals(0.1f, result.value.alpha, delta)
        assertEquals(0.95f, result.value.smoothing, delta)

        assertEquals(5000L, HistogramOverlayConfig.clamp(
            HistogramOverlayConfig(sampleIntervalMs = 999_999L),
        ).value.sampleIntervalMs)
    }

    @Test
    fun histogramSnapBinCount_tieBreaksToSmallerBin() {
        // 96 距 64 与 128 相同（32）→ 取较小者，行为必须稳定可预期
        assertEquals(64, ViewfinderParamRanges.snapBinCount(96))
        assertEquals(64, ViewfinderParamRanges.snapBinCount(0))
        assertEquals(256, ViewfinderParamRanges.snapBinCount(Int.MAX_VALUE))
        // 极端负值（含 Int 差值溢出的退化路径）也必须回到合法 bin 数，不崩溃
        assertTrue(
            ViewfinderParamRanges.snapBinCount(Int.MIN_VALUE) in
                ViewfinderParamRanges.HISTOGRAM_BIN_COUNTS.toList(),
        )
        // 允许值集合本身固定为 64/128/256
        assertEquals(listOf(64, 128, 256), ViewfinderParamRanges.HISTOGRAM_BIN_COUNTS.toList())
    }

    // ------------------------------------------------------------ 峰值对焦

    @Test
    fun peakingDefault_doesNotRegressExistingHardcodedThreshold() {
        // 默认必须等于既有硬编码 0.8f，集成后观感不变
        assertEquals(0.80f, PeakingEnhanceConfig().resolveEdgeThreshold(), 1e-6f)
        assertTrue(PeakingEnhanceConfig().enabled)
    }

    @Test
    fun peakingSensitivity_mapsLinearlyToThreshold() {
        assertEquals(0.95f, PeakingEnhanceConfig(sensitivity = 0f).resolveEdgeThreshold(), delta)
        assertEquals(0.80f, PeakingEnhanceConfig(sensitivity = 0.5f).resolveEdgeThreshold(), 1e-6f)
        assertEquals(0.65f, PeakingEnhanceConfig(sensitivity = 1f).resolveEdgeThreshold(), delta)
        // 灵敏度越界被推导函数自身 clamp
        assertEquals(0.95f, PeakingEnhanceConfig(sensitivity = -3f).resolveEdgeThreshold(), delta)
        assertEquals(0.65f, PeakingEnhanceConfig(sensitivity = 3f).resolveEdgeThreshold(), delta)
        // 中段单调性
        assertTrue(
            PeakingEnhanceConfig(sensitivity = 0.2f).resolveEdgeThreshold() >
                PeakingEnhanceConfig(sensitivity = 0.8f).resolveEdgeThreshold(),
        )
    }

    @Test
    fun peakingThresholdOverride_isHonoredOnlyWhenLegal() {
        assertEquals(0.42f, PeakingEnhanceConfig(edgeThresholdOverride = 0.42f).resolveEdgeThreshold(), delta)
        // 边界：0.05 / 0.99 合法
        assertEquals(0.05f, PeakingEnhanceConfig(edgeThresholdOverride = 0.05f).resolveEdgeThreshold(), delta)
        assertEquals(0.99f, PeakingEnhanceConfig(edgeThresholdOverride = 0.99f).resolveEdgeThreshold(), delta)
        // 越界 → 回落灵敏度推导（默认 0.80）
        assertEquals(0.80f, PeakingEnhanceConfig(edgeThresholdOverride = 0.995f).resolveEdgeThreshold(), 1e-6f)
        assertEquals(0.80f, PeakingEnhanceConfig(edgeThresholdOverride = 5f).resolveEdgeThreshold(), 1e-6f)
        // 哨兵 -1 = 自动
        assertEquals(0.65f, PeakingEnhanceConfig(edgeThresholdOverride = -1f, sensitivity = 1f).resolveEdgeThreshold(), delta)
        // clamp 之后越界值被压回合法区间，从而真的生效
        assertEquals(0.99f, PeakingEnhanceConfig.clamp(PeakingEnhanceConfig(edgeThresholdOverride = 0.995f)).value.edgeThresholdOverride, delta)
    }

    @Test
    fun peakingClamp_handlesSentinelAndNonFinite() {
        // 哨兵本身合法，不产生修正
        assertFalse(PeakingEnhanceConfig.clamp(PeakingEnhanceConfig(edgeThresholdOverride = -1f)).corrected)
        // NaN → 回落到自动并记录
        val nan = PeakingEnhanceConfig.clamp(PeakingEnhanceConfig(edgeThresholdOverride = Float.NaN))
        assertEquals(-1f, nan.value.edgeThresholdOverride, delta)
        assertTrue(nan.corrected)
        // 其余参数越界
        val others = PeakingEnhanceConfig.clamp(
            PeakingEnhanceConfig(sensitivity = 9f, mixStrength = 0f, stepScale = 99f, colorR = 3f),
        )
        assertEquals(1f, others.value.sensitivity, delta)
        assertEquals(0.1f, others.value.mixStrength, delta)
        assertEquals(4f, others.value.stepScale, delta)
        assertEquals(1f, others.value.colorR, delta)
    }

    // ---------------------------------------------------------------- 贴纸

    @Test
    fun stickerClamp_scaleOffsetAlphaAndCounts() {
        val result = StickerConfig.clamp(
            StickerConfig(scale = 0f, offsetX = -9f, offsetY = 9f, alpha = 5f, maxFaces = 99, detectIntervalMs = 1L, smoothing = 2f),
        )
        assertEquals(0.2f, result.value.scale, delta)
        assertEquals(-1f, result.value.offsetX, delta)
        assertEquals(1f, result.value.offsetY, delta)
        assertEquals(1f, result.value.alpha, delta)
        assertEquals(5, result.value.maxFaces)
        assertEquals(33L, result.value.detectIntervalMs)
        assertEquals(0.95f, result.value.smoothing, delta)
        assertTrue(result.corrected)

        assertEquals(1, StickerConfig.clamp(StickerConfig(maxFaces = 0)).value.maxFaces)
        assertEquals(2000L, StickerConfig.clamp(StickerConfig(detectIntervalMs = 99_999L)).value.detectIntervalMs)
        assertEquals(4f, StickerConfig.clamp(StickerConfig(scale = 100f)).value.scale, delta)
    }

    @Test
    fun stickerClamp_unknownOrBlankIdFallsBack() {
        val unknown = StickerConfig.clamp(StickerConfig(stickerId = "not_a_sticker"))
        assertEquals(FaceStickerCatalog.DEFAULT_STICKER_ID, unknown.value.stickerId)
        assertTrue(unknown.corrected)

        val blank = StickerConfig.clamp(StickerConfig(stickerId = "   "))
        assertEquals(FaceStickerCatalog.DEFAULT_STICKER_ID, blank.value.stickerId)
        assertTrue(blank.corrected)

        // 合法 id 不被改写
        val known = StickerConfig.clamp(StickerConfig(stickerId = "bunny_ears"))
        assertEquals("bunny_ears", known.value.stickerId)
        assertFalse(known.corrected)
    }

    @Test
    fun stickerClamp_blankModelPathKeptAsIs() {
        // 模型路径允许为空（由检测器回落默认），不属于非法值
        val result = StickerConfig.clamp(StickerConfig(modelAssetPath = ""))
        assertEquals("", result.value.modelAssetPath)
        assertFalse(result.corrected)
    }

    // ------------------------------------------------------------ 聚合与健壮性

    @Test
    fun aggregateClamp_collectsAllCorrections() {
        val dirty = ViewfinderSettings(
            zebra = ZebraConfig(thresholdPercent = 999f),
            histogram = HistogramOverlayConfig(binCount = 7, sampleIntervalMs = 1L),
            peaking = PeakingEnhanceConfig(sensitivity = -1f),
            sticker = StickerConfig(stickerId = "nope", maxFaces = 42),
        )
        val result = ViewfinderSettings.clamp(dirty)
        assertTrue(result.corrected)
        assertTrue(result.corrections.size >= 6)
        assertEquals(120f, result.value.zebra.thresholdPercent, delta)
        assertEquals(64, result.value.histogram.binCount)
        assertEquals(50L, result.value.histogram.sampleIntervalMs)
        assertEquals(0f, result.value.peaking.sensitivity, delta)
        assertEquals(FaceStickerCatalog.DEFAULT_STICKER_ID, result.value.sticker.stickerId)
        assertEquals(5, result.value.sticker.maxFaces)
        // 修正说明里要能定位到具体参数名
        assertTrue(result.corrections.any { it.startsWith("zebra.") })
        assertTrue(result.corrections.any { it.startsWith("histogram.") })
        assertTrue(result.corrections.any { it.startsWith("sticker.") })
    }

    @Test
    fun clampIsIdempotent() {
        val once = ViewfinderSettings.clamp(
            ViewfinderSettings(zebra = ZebraConfig(thresholdPercent = 1f), sticker = StickerConfig(scale = -5f)),
        ).value
        val twice = ViewfinderSettings.clamp(once)
        assertFalse(twice.corrected)
        assertEquals(once, twice.value)
    }

    @Test
    fun clampResultCorrectedFlagTracksCorrections() {
        assertFalse(ClampResult(1, emptyList<String>()).corrected)
        assertTrue(ClampResult(1, listOf("x → y")).corrected)
        assertNotEquals(emptyList<String>(), ClampResult(1, listOf("x → y")).corrections)
    }
}
