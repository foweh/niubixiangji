package com.hinnka.mycamera.longexposure

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置开放度 / clamp / 帧数边界的单测（纯 JVM）。
 *
 * 设计目标（PATTERN_SPEC §5）：**每个开放参数都有默认值、范围、clamp**，
 * 越界或非法输入只会被修正并记录，绝不崩溃。
 *
 * 注意：本文件只调用 `xxx.clampWithReport()` / `clamped()`（不触碰 `PLog`），
 * 因此可在"未 mock android.util.Log"的纯 JVM 单测里运行；
 * [LongExposureConfigCodec.clampAll] 与 [LongExposureConfigCodec.setFrameCountLimit]
 * 在**发生修正**时会写日志，需要 `testOptions.unitTests.returnDefaultValues = true`
 * 或 Robolectric 才能覆盖（见自检报告"未覆盖项"）。
 */
class LongExposureConfigTest {

    private val originalLimit = LongExposureLimits.userFrameCountLimit

    @After
    fun restoreFrameCountLimit() {
        LongExposureLimits.userFrameCountLimit = originalLimit
    }

    // ------------------------------------------------------------ 帧数边界

    @Test
    fun frameCountNormalAndBoundaryValuesArePreserved() {
        assertEquals(30, LightPaintingConfig(frameCount = 30).clamped().frameCount) // 默认
        assertEquals(2, LightPaintingConfig(frameCount = 2).clamped().frameCount) // 下限
        assertEquals(1000, LightPaintingConfig(frameCount = 1000).clamped().frameCount) // 推荐上限
    }

    @Test
    fun frameCountBelowMinimumIsRaisedToMinimum() {
        val (config, reports) = LightPaintingConfig(frameCount = 1).clampWithReport()
        assertEquals(LongExposureLimits.FRAME_MIN, config.frameCount)
        assertTrue("越界应产生修正说明", reports.any { it.contains("frameCount") })
        assertEquals(2, LightPaintingConfig(frameCount = -5).clamped().frameCount)
        assertEquals(2, LightPaintingConfig(frameCount = 0).clamped().frameCount)
    }

    @Test
    fun frameCountAboveRuntimeLimitIsClampedToThatLimit() {
        assertEquals(1000, LightPaintingConfig(frameCount = 99_999).clamped().frameCount)
        LongExposureLimits.userFrameCountLimit = 2000
        assertEquals(2000, LightPaintingConfig(frameCount = 99_999).clamped().frameCount)
        assertEquals(2000, FireworksConfig(frameCount = 5000).clamped().frameCount)
        assertEquals(2000, SuperMoonConfig(frameCount = 5000).clamped().frameCount)
    }

    @Test
    fun frameCountLimitItselfIsClampedToAbsoluteRange() {
        LongExposureLimits.userFrameCountLimit = -100
        assertEquals(LongExposureLimits.FRAME_MIN, LongExposureLimits.frameCountLimit())
        LongExposureLimits.userFrameCountLimit = 1_000_000
        assertEquals(LongExposureLimits.ABSOLUTE_FRAME_MAX, LongExposureLimits.frameCountLimit())
        LongExposureLimits.userFrameCountLimit = 777
        assertEquals(777, LongExposureLimits.frameCountLimit())
    }

    // ------------------------------------------------------------ 光绘

    @Test
    fun lightPaintingClampsEveryOpenParameter() {
        val (config, reports) = LightPaintingConfig(
            frameCount = 1,
            brushColorTemperatureK = 500,
            brushTint = 5f,
            brushGain = 99f,
            previewIntervalFrames = 0,
            previewMaxLongEdge = 10,
            previewThrottleMs = 1L,
            workScale = 0.01f,
            workLongEdge = 99,
        ).clampWithReport()

        assertEquals(2, config.frameCount)
        assertEquals(LightPaintingConfig.COLOR_TEMP_MIN, config.brushColorTemperatureK)
        assertEquals(LightPaintingConfig.TINT_MAX, config.brushTint, 0f)
        assertEquals(LightPaintingConfig.BRUSH_GAIN_MAX, config.brushGain, 0f)
        assertEquals(LightPaintingConfig.PREVIEW_INTERVAL_MIN, config.previewIntervalFrames)
        assertEquals(LightPaintingConfig.PREVIEW_EDGE_MIN, config.previewMaxLongEdge)
        assertEquals(LightPaintingConfig.PREVIEW_THROTTLE_MIN, config.previewThrottleMs)
        assertEquals(LongExposureLimits.MIN_WORK_SCALE, config.workScale, 0f)
        assertEquals(320, config.workLongEdge) // 过小值被抬到工作长边下限（320px）
        assertTrue("每项越界都应有修正说明", reports.size >= 6)
    }

    @Test
    fun lightPaintingUpperBoundsAreClamped() {
        val config = LightPaintingConfig(
            brushColorTemperatureK = 99_999,
            brushTint = -9f,
            brushGain = -9f,
            previewIntervalFrames = 9999,
            previewMaxLongEdge = 99999,
            previewThrottleMs = 99999L,
            workScale = 8f,
            workLongEdge = 99_999,
        ).clamped()

        assertEquals(LightPaintingConfig.COLOR_TEMP_MAX, config.brushColorTemperatureK)
        assertEquals(LightPaintingConfig.TINT_MIN, config.brushTint, 0f)
        assertEquals(LightPaintingConfig.BRUSH_GAIN_MIN, config.brushGain, 0f)
        assertEquals(LightPaintingConfig.PREVIEW_INTERVAL_MAX, config.previewIntervalFrames)
        assertEquals(LightPaintingConfig.PREVIEW_EDGE_MAX, config.previewMaxLongEdge)
        assertEquals(LightPaintingConfig.PREVIEW_THROTTLE_MAX, config.previewThrottleMs)
        assertEquals(LongExposureLimits.MAX_WORK_SCALE, config.workScale, 0f)
        assertEquals(8192, config.workLongEdge)
    }

    @Test
    fun nonFiniteFloatsFallBackToDefaultsInsteadOfCrashing() {
        val config = LightPaintingConfig(
            brushTint = Float.NaN,
            brushGain = Float.NaN,
            workScale = Float.NEGATIVE_INFINITY,
        ).clamped()
        assertEquals(0f, config.brushTint, 0f)
        assertEquals(1f, config.brushGain, 0f)
        assertEquals(LongExposureLimits.MAX_WORK_SCALE, config.workScale, 0f)
    }

    // ------------------------------------------------------------ 暗帧

    @Test
    fun darkFrameConfigClampsFrameCountAndGain() {
        val (low, lowReports) = DarkFrameConfig(frameCount = 0, subtractGain = -1f).clampWithReport()
        assertEquals(DarkFrameConfig.FRAME_MIN, low.frameCount)
        assertEquals(DarkFrameConfig.GAIN_MIN, low.subtractGain, 0f)
        assertTrue(lowReports.isNotEmpty())

        val (high, _) = DarkFrameConfig(frameCount = 99_999, subtractGain = 42f, hotPixelThreshold = 99_999)
            .clampWithReport()
        assertEquals(DarkFrameConfig.FRAME_MAX, high.frameCount)
        assertEquals(DarkFrameConfig.GAIN_MAX, high.subtractGain, 0f)
        assertEquals(LinearRgbPlane.SAMPLE_MAX, high.hotPixelThreshold)
    }

    @Test
    fun darkFrameRejectsNonFiniteGainAndNegativeThreshold() {
        val config = DarkFrameConfig(
            frameCount = 8,
            subtractGain = Float.NaN,
            hotPixelThreshold = -100,
        ).clamped()
        assertEquals(1f, config.subtractGain, 0f)
        assertEquals(0, config.hotPixelThreshold)
    }

    // ------------------------------------------------------------ 烟火

    @Test
    fun fireworksClampsShutterIsoAndPreBurst() {
        val (fast, fastReports) = FireworksConfig(
            frameCount = 1,
            burstShutterSpeedNs = 1L,
            iso = 1,
            preBurstFrames = -3,
        ).clampWithReport()
        assertEquals(2, fast.frameCount)
        assertEquals(FireworksConfig.SHUTTER_MIN_NS, fast.burstShutterSpeedNs)
        assertEquals(FireworksConfig.ISO_MIN, fast.iso)
        assertEquals(0, fast.preBurstFrames)
        assertTrue(fastReports.isNotEmpty())

        val slow = FireworksConfig(
            frameCount = 999_999,
            burstShutterSpeedNs = Long.MAX_VALUE,
            iso = 999_999,
            preBurstFrames = 999,
        ).clamped()
        assertEquals(LongExposureLimits.frameCountLimit(), slow.frameCount)
        assertEquals(FireworksConfig.SHUTTER_MAX_NS, slow.burstShutterSpeedNs)
        assertEquals(FireworksConfig.ISO_MAX, slow.iso)
        assertEquals(FireworksConfig.PRE_BURST_MAX, slow.preBurstFrames)
    }

    // ------------------------------------------------------------ 超级月亮

    @Test
    fun superMoonClampsSigmaAndToneMap() {
        val (low, reports) = SuperMoonConfig(
            sigmaClipSigma = 0.1f,
            toneMap = LocalToneMapConfig(highlightThreshold = 0f, gamma = 0f),
        ).clampWithReport()
        assertEquals(SuperMoonConfig.SIGMA_MIN, low.sigmaClipSigma, 1e-6f)
        assertEquals(LocalToneMapConfig.THRESHOLD_MIN, low.toneMap.highlightThreshold, 0f)
        assertEquals(LocalToneMapConfig.GAMMA_MIN, low.toneMap.gamma, 1e-6f)
        assertTrue(reports.isNotEmpty())

        val high = SuperMoonConfig(
            sigmaClipSigma = 99f,
            toneMap = LocalToneMapConfig(
                highlightThreshold = 9f,
                highlightCompression = 99f,
                shadowLift = 9f,
                gamma = 9f,
                localRadius = 99_999,
                localAmount = 99f,
            ),
        ).clamped()
        assertEquals(SuperMoonConfig.SIGMA_MAX, high.sigmaClipSigma, 1e-6f)
        assertEquals(LocalToneMapConfig.THRESHOLD_MAX, high.toneMap.highlightThreshold, 0f)
        assertEquals(LocalToneMapConfig.COMPRESSION_MAX, high.toneMap.highlightCompression, 0f)
        assertEquals(LocalToneMapConfig.LIFT_MAX, high.toneMap.shadowLift, 0f)
        assertEquals(LocalToneMapConfig.GAMMA_MAX, high.toneMap.gamma, 0f)
        assertEquals(LocalToneMapConfig.RADIUS_MAX, high.toneMap.localRadius)
        assertEquals(LocalToneMapConfig.AMOUNT_MAX, high.toneMap.localAmount, 0f)
    }

    // ------------------------------------------------------------ 慢门预设

    @Test
    fun slowShutterPresetClampsIdNameShutterIsoAndFrames() {
        val (fixed, reports) = SlowShutterPreset(
            id = "   ",
            displayName = "",
            shutterSpeedNs = 1L,
            iso = 1,
            frameCount = 1,
            stackMode = LongExposureStackMode.MAX,
        ).clampWithReport()

        assertEquals(SlowShutterPreset.FALLBACK_ID, fixed.id)
        assertEquals(SlowShutterPreset.FALLBACK_ID, fixed.displayName)
        assertEquals(SlowShutterPreset.SHUTTER_MIN_NS, fixed.shutterSpeedNs)
        assertEquals(SlowShutterPreset.ISO_MIN, fixed.iso)
        assertEquals(LongExposureLimits.FRAME_MIN, fixed.frameCount)
        assertTrue(fixed.isShutterInRange())
        assertEquals("id/名称/曝光/ISO/帧数 共 5 项被修正", 5, reports.size)

        val longExposure = SlowShutterPreset(
            id = "long",
            displayName = "长时间",
            shutterSpeedNs = Long.MAX_VALUE,
            iso = 999_999,
            frameCount = 999_999,
            stackMode = LongExposureStackMode.MEAN,
        ).clamped()
        assertEquals(SlowShutterPreset.SHUTTER_MAX_NS, longExposure.shutterSpeedNs)
        assertEquals(SlowShutterPreset.ISO_MAX, longExposure.iso)
        assertEquals(LongExposureLimits.frameCountLimit(), longExposure.frameCount)
        assertTrue(longExposure.isShutterInRange())
    }

    // ------------------------------------------------------------ 会话规格

    @Test
    fun sessionSpecClampKeepsFrameCountAndDarkFrameInRange() {
        val spec = LongExposureSessionSpec(
            frameCountTarget = -1,
            darkFrame = DarkFrameConfig(frameCount = 0, subtractGain = 9f),
            stackMode = LongExposureStackMode.MEAN,
        ).clamped()

        assertEquals(LongExposureLimits.FRAME_MIN, spec.frameCountTarget)
        assertEquals(DarkFrameConfig.FRAME_MIN, spec.darkFrame.frameCount)
        assertEquals(DarkFrameConfig.GAIN_MAX, spec.darkFrame.subtractGain, 0f)
        assertEquals(LongExposureStackMode.MEAN, spec.stackMode)
    }

    @Test
    fun sessionSpecsDeriveFromEachMode() {
        val fromLightPainting = LongExposureSessionSpec.fromLightPainting(
            LightPaintingConfig(frameCount = 45, stackMode = LongExposureStackMode.MAX),
        )
        assertEquals(45, fromLightPainting.frameCountTarget)
        assertEquals(LongExposureStackMode.MAX, fromLightPainting.stackMode)

        val fromWaterfall = LongExposureSessionSpec.fromPreset(SlowShutterPresets.SILK_WATERFALL)
        assertEquals(SlowShutterPresets.SILK_WATERFALL.frameCount, fromWaterfall.frameCountTarget)
        assertEquals(LongExposureStackMode.MEAN, fromWaterfall.stackMode)
        assertFalse("瀑布预设默认不要暗帧", fromWaterfall.darkFrame.enabled)

        val fromFireworks = LongExposureSessionSpec.fromFireworks(FireworksConfig(frameCount = 12))
        assertEquals(12, fromFireworks.frameCountTarget)
        assertEquals(LongExposureStackMode.MAX, fromFireworks.stackMode)
        assertTrue(fromFireworks.darkFrame.enabled)
    }

    @Test
    fun preferencesRoundTripFallsBackToDefaultsWhenKeysMissing() {
        val configs = LongExposureConfigCodec.fromPreferences { null }
        assertEquals(LightPaintingConfig.DEFAULT.frameCount, configs.lightPainting.frameCount)
        assertEquals(FireworksConfig.DEFAULT.frameCount, configs.fireworks.frameCount)
        assertEquals(SuperMoonConfig.DEFAULT.frameCount, configs.superMoon.frameCount)
        assertTrue(configs.reports.isEmpty())
    }

    @Test
    fun preferencesRespectProvidedValuesInRange() {
        val values = mapOf(
            LongExposureConfigCodec.KEY_LP_FRAMES to 55,
            LongExposureConfigCodec.KEY_LP_STACK_MODE to "MEAN",
            LongExposureConfigCodec.KEY_LP_BRUSH_TEMPERATURE to 4200,
            LongExposureConfigCodec.KEY_LP_PREVIEW to false,
            LongExposureConfigCodec.KEY_FW_FRAMES to 8,
            LongExposureConfigCodec.KEY_SM_FRAMES to 64,
            LongExposureConfigCodec.KEY_SM_ALIGN to false,
        )
        val configs = LongExposureConfigCodec.fromPreferences { values[it] }
        assertEquals(55, configs.lightPainting.frameCount)
        assertEquals(LongExposureStackMode.MEAN, configs.lightPainting.stackMode)
        assertEquals(4200, configs.lightPainting.brushColorTemperatureK)
        assertFalse(configs.lightPainting.incrementalPreviewEnabled)
        assertEquals(8, configs.fireworks.frameCount)
        assertEquals(64, configs.superMoon.frameCount)
        assertFalse(configs.superMoon.align)
        assertTrue(configs.reports.isEmpty())
    }

    @Test
    fun malformedPreferenceValuesFallBackToDefaults() {
        val configs = LongExposureConfigCodec.fromPreferences { key ->
            when (key) {
                LongExposureConfigCodec.KEY_LP_STACK_MODE -> "NOT_A_MODE"
                LongExposureConfigCodec.KEY_LP_PREVIEW -> "yes" // 类型不符
                else -> null
            }
        }
        assertEquals(LightPaintingConfig.DEFAULT.stackMode, configs.lightPainting.stackMode)
        assertEquals(
            LightPaintingConfig.DEFAULT.incrementalPreviewEnabled,
            configs.lightPainting.incrementalPreviewEnabled,
        )
    }

    @Test
    fun dataStoreKeysAreUniqueAndNamespaced() {
        val keys = listOf(
            LongExposureConfigCodec.KEY_LP_FRAMES,
            LongExposureConfigCodec.KEY_LP_STACK_MODE,
            LongExposureConfigCodec.KEY_LP_BRUSH_TEMPERATURE,
            LongExposureConfigCodec.KEY_LP_BRUSH_TINT,
            LongExposureConfigCodec.KEY_LP_BRUSH_GAIN,
            LongExposureConfigCodec.KEY_LP_PREVIEW,
            LongExposureConfigCodec.KEY_LP_PREVIEW_INTERVAL,
            LongExposureConfigCodec.KEY_LP_DARK_ENABLED,
            LongExposureConfigCodec.KEY_LP_DARK_FRAMES,
            LongExposureConfigCodec.KEY_LP_DARK_GAIN,
            LongExposureConfigCodec.KEY_FW_FRAMES,
            LongExposureConfigCodec.KEY_FW_STACK_MODE,
            LongExposureConfigCodec.KEY_FW_SHUTTER_NS,
            LongExposureConfigCodec.KEY_FW_ISO,
            LongExposureConfigCodec.KEY_SM_FRAMES,
            LongExposureConfigCodec.KEY_SM_ALIGN,
            LongExposureConfigCodec.KEY_SM_TONEMAP,
            LongExposureConfigCodec.KEY_SM_HIGHLIGHT_THRESHOLD,
            LongExposureConfigCodec.KEY_SM_LOCAL_AMOUNT,
            LongExposureConfigCodec.KEY_FRAME_COUNT_LIMIT,
        )
        assertEquals("key 数量应与补丁 CONFIG 段一致", 20, keys.size)
        assertEquals("key 不得重复", keys.size, keys.distinct().size)
        assertTrue("key 必须带命名空间前缀", keys.all { it.startsWith("long_exposure_") })
        assertNotNull(keys.firstOrNull { it == "long_exposure_frame_count_limit" })
    }
}
