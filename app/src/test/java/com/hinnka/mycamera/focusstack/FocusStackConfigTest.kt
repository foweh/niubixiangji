package com.hinnka.mycamera.focusstack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置层单测：全参数 clamp（正常 / 边界 / 异常）+ 帧数 2 / 200 边界 + DataStore 解析容错。
 */
class FocusStackConfigTest {

    // ------------------------------------------------------------ 默认值

    @Test
    fun defaults_areWithinDeclaredRanges() {
        val config = FocusStackConfig.DEFAULT
        assertEquals(6, config.frameCount)
        assertEquals(6, config.stepCount)
        assertEquals(0, config.maskConsistency)
        assertEquals(1f, config.fusionStrength, 0f)
        assertTrue(config.focusEndDiopters < 0f)

        val (clamped, reports) = config.clampWithReport()
        assertEquals(config, clamped)
        assertTrue("默认配置不应需要修正: $reports", reports.isEmpty())
    }

    // ------------------------------------------------------------ 正常值不修正

    @Test
    fun clamp_keepsValidValuesUntouched() {
        val config = FocusStackConfig(
            frameCount = 100,
            stepCount = 40,
            focusStartDiopters = 2f,
            focusEndDiopters = 18f,
            clarityRadius = 8,
            maskSmoothRadius = 12,
            maskConsistency = 2,
            fusionStrength = 0.6f,
            alignMaxShiftPx = 64,
            alignMaxLongEdge = 4096,
            clarityExponent = 6f,
            jpegQuality = 88,
        )
        val (clamped, reports) = config.clampWithReport()
        assertEquals(config, clamped)
        assertTrue("有效值不应产生修正记录: $reports", reports.isEmpty())
    }

    // ------------------------------------------------------------ 帧数边界（2 / 200）

    @Test
    fun frameCount_lowerBoundary_isTwo() {
        assertEquals(2, FocusStackConfig(frameCount = 2).clampWithReport().first.frameCount)
        assertEquals(2, FocusStackConfig(frameCount = 1).clampWithReport().first.frameCount)
        assertEquals(2, FocusStackConfig(frameCount = 0).clampWithReport().first.frameCount)
        assertEquals(2, FocusStackConfig(frameCount = -5).clampWithReport().first.frameCount)
    }

    @Test
    fun frameCount_recommendedUpperBoundary_isTwoHundred() {
        assertEquals(200, FocusStackConfig(frameCount = 200).clampWithReport().first.frameCount)
        // 200 是 UI 推荐上限，不是算法上限：更大的值只要在绝对上界内就原样保留。
        assertEquals(500, FocusStackConfig(frameCount = 500).clampWithReport().first.frameCount)
    }

    @Test
    fun frameCount_absoluteGuard_clampsOverflow() {
        val clamped = FocusStackConfig(frameCount = Int.MAX_VALUE).clampWithReport().first
        assertEquals(FocusStackConfig.FRAME_ABSOLUTE_MAX, clamped.frameCount)
    }

    // ------------------------------------------------------------ 其它参数 clamp

    @Test
    fun clamp_clarityRadius_boundaries() {
        assertEquals(1, FocusStackConfig(clarityRadius = 0).clampWithReport().first.clarityRadius)
        assertEquals(1, FocusStackConfig(clarityRadius = 1).clampWithReport().first.clarityRadius)
        assertEquals(32, FocusStackConfig(clarityRadius = 32).clampWithReport().first.clarityRadius)
        assertEquals(32, FocusStackConfig(clarityRadius = 999).clampWithReport().first.clarityRadius)
    }

    @Test
    fun clamp_miscParams_boundaries() {
        val c = FocusStackConfig(
            maskSmoothRadius = 1000,
            maskConsistency = 9,
            fusionStrength = 3f,
            alignMaxShiftPx = -4,
            alignMaxLongEdge = 99999,
            clarityExponent = 0.1f,
            jpegQuality = 1000,
        ).clampWithReport().first

        assertEquals(FocusStackConfig.MASK_SMOOTH_RADIUS_MAX, c.maskSmoothRadius)
        assertEquals(FocusStackConfig.MASK_CONSISTENCY_MAX, c.maskConsistency)
        assertEquals(FocusStackConfig.STRENGTH_MAX, c.fusionStrength, 0f)
        assertEquals(FocusStackConfig.ALIGN_MAX_SHIFT_MIN, c.alignMaxShiftPx)
        assertEquals(FocusStackConfig.ALIGN_MAX_LONG_EDGE_MAX, c.alignMaxLongEdge)
        assertEquals(FocusStackConfig.CLARITY_EXPONENT_MIN, c.clarityExponent, 1e-6f)
        assertEquals(FocusStackConfig.JPEG_QUALITY_MAX, c.jpegQuality)
    }

    @Test
    fun clamp_negativeFusionStrengthAndJpegQuality_lowerBounds() {
        val c = FocusStackConfig(fusionStrength = -2f, jpegQuality = 1).clampWithReport().first
        assertEquals(0f, c.fusionStrength, 0f)
        assertEquals(FocusStackConfig.JPEG_QUALITY_MIN, c.jpegQuality)
    }

    // ------------------------------------------------------------ 异常值（NaN / Inf）

    @Test
    fun clamp_nonFiniteValues_fallBackToDefaults() {
        val (c, reports) = FocusStackConfig(
            fusionStrength = Float.NaN,
            clarityExponent = Float.POSITIVE_INFINITY,
            focusStartDiopters = Float.NEGATIVE_INFINITY,
        ).clampWithReport()

        assertEquals(FocusStackConfig.DEFAULT_FUSION_STRENGTH, c.fusionStrength, 0f)
        assertEquals(FocusStackConfig.DEFAULT_CLARITY_EXPONENT, c.clarityExponent, 0f)
        assertEquals(0f, c.focusStartDiopters, 0f)
        assertTrue("非有限值应被记录: $reports", reports.isNotEmpty())
    }

    @Test
    fun clamp_neverProducesNaN() {
        val c = FocusStackConfig(
            frameCount = Int.MIN_VALUE,
            focusStartDiopters = Float.NaN,
            focusEndDiopters = Float.NaN,
            fusionStrength = Float.NaN,
            clarityExponent = Float.NaN,
            jpegQuality = Int.MIN_VALUE,
        ).clampWithReport().first

        assertTrue(c.focusStartDiopters.isFinite())
        assertTrue(c.focusEndDiopters.isFinite())
        assertTrue(c.fusionStrength.isFinite())
        assertTrue(c.clarityExponent.isFinite())
        assertTrue(c.effectiveWorkingScale().isFinite())
    }

    @Test
    fun clamp_focusEndDiopters_negativeMeansAuto() {
        // 负值保持「自动」语义，不被压成 0。
        val auto = FocusStackConfig(focusEndDiopters = -1f).clampWithReport().first
        assertEquals(FocusStackConfig.AUTO_FOCUS_DIOPTERS, auto.focusEndDiopters, 0f)

        // 非负值收敛到合法区间。
        val low = FocusStackConfig(focusEndDiopters = 0f).clampWithReport().first
        assertEquals(0f, low.focusEndDiopters, 0f)
        val high = FocusStackConfig(focusEndDiopters = 500f).clampWithReport().first
        assertEquals(FocusStackConfig.FOCUS_DIOPTERS_MAX, high.focusEndDiopters, 0f)
    }

    // ------------------------------------------------------------ 工作分辨率

    @Test
    fun workingResolution_scalesAreWithinDeclaredRange() {
        for (tier in FocusWorkingResolution.entries) {
            assertTrue(tier.scale > 0f)
            assertTrue("${tier.name} scale=${tier.scale}", tier.scale <= FocusStackConfig.WORKING_SCALE_MAX)
            assertTrue(tier.scale >= FocusStackConfig.WORKING_SCALE_MIN)
        }
        assertEquals(FocusStackConfig.WORKING_SCALE_MIN, FocusWorkingResolution.SIXTEENTH.scale, 1e-6f)
    }

    @Test
    fun workingResolutionResolve_acceptsNameAndScale_andFallsBackSafely() {
        assertEquals(FocusWorkingResolution.HALF, FocusWorkingResolution.fromLabelOrScale("half"))
        assertEquals(FocusWorkingResolution.QUARTER, FocusWorkingResolution.fromLabelOrScale("0.25"))
        assertEquals(FocusWorkingResolution.FULL, FocusWorkingResolution.fromLabelOrScale(null))
        assertEquals(FocusWorkingResolution.FULL, FocusWorkingResolution.fromLabelOrScale(""))
        assertEquals(FocusWorkingResolution.FULL, FocusWorkingResolution.fromLabelOrScale("garbage"))
        assertEquals(FocusWorkingResolution.FULL, FocusWorkingResolution.fromLabelOrScale("NaN"))
    }

    // ------------------------------------------------------------ DataStore 解析

    @Test
    fun fromPreferences_emptyMap_givesDefaults() {
        assertEquals(FocusStackConfig.DEFAULT, FocusStackConfig.fromPreferences(emptyMap()))
    }

    @Test
    fun fromPreferences_parsesValidValues_andClamps() {
        val config = FocusStackConfig.fromPreferences(
            mapOf(
                FocusStackConfig.KEY_ENABLED to false,
                FocusStackConfig.KEY_FRAME_COUNT to 24,
                FocusStackConfig.KEY_STEP_COUNT to "30",
                FocusStackConfig.KEY_CLARITY_METRIC to "gradient_energy",
                FocusStackConfig.KEY_CLARITY_RADIUS to 6,
                FocusStackConfig.KEY_FUSION_STRENGTH to 0.75f,
                FocusStackConfig.KEY_ALIGN_ENABLED to true,
                FocusStackConfig.KEY_WORKING_RESOLUTION to "QUARTER",
                FocusStackConfig.KEY_JPEG_QUALITY to 1000,
            ),
        )
        assertEquals(false, config.enabled)
        assertEquals(24, config.frameCount)
        assertEquals(30, config.stepCount)
        assertEquals(ClarityMetric.GRADIENT_ENERGY, config.clarityMetric)
        assertEquals(6, config.clarityRadius)
        assertEquals(0.75f, config.fusionStrength, 1e-6f)
        assertEquals(true, config.alignEnabled)
        assertTrue(config.workingResolution == FocusWorkingResolution.QUARTER)
        assertEquals(FocusStackConfig.JPEG_QUALITY_MAX, config.jpegQuality)
    }

    @Test
    fun fromPreferences_ignoresWrongTypesAndNonFinite() {
        val config = FocusStackConfig.fromPreferences(
            mapOf(
                FocusStackConfig.KEY_FRAME_COUNT to "not-a-number",
                FocusStackConfig.KEY_CLARITY_RADIUS to 12.7,
                FocusStackConfig.KEY_FUSION_STRENGTH to "NaN",
                FocusStackConfig.KEY_CLARITY_METRIC to "unknown_metric",
                FocusStackConfig.KEY_ALIGN_ENABLED to "maybe",
                FocusStackConfig.KEY_ENABLED to null,
            ),
        )
        assertEquals(FocusStackConfig.DEFAULT_FRAME_COUNT, config.frameCount)
        assertEquals(13, config.clarityRadius)
        assertEquals(FocusStackConfig.DEFAULT_FUSION_STRENGTH, config.fusionStrength, 0f)
        assertEquals(ClarityMetric.LAPLACIAN_VARIANCE, config.clarityMetric)
        assertEquals(FocusStackConfig.DEFAULT_ALIGN_ENABLED, config.alignEnabled)
        assertEquals(FocusStackConfig.DEFAULT.enabled, config.enabled)
    }

    @Test
    fun fromPreferences_booleanParsing_variants() {
        assertTrue(FocusStackConfig.fromPreferences(mapOf(FocusStackConfig.KEY_ENABLED to "true")).enabled)
        assertTrue(FocusStackConfig.fromPreferences(mapOf(FocusStackConfig.KEY_ENABLED to 1)).enabled)
        assertEquals(false, FocusStackConfig.fromPreferences(mapOf(FocusStackConfig.KEY_ENABLED to "off")).enabled)
        assertEquals(false, FocusStackConfig.fromPreferences(mapOf(FocusStackConfig.KEY_ENABLED to 0)).enabled)
        assertEquals(
            FocusStackConfig.DEFAULT.enabled,
            FocusStackConfig.fromPreferences(mapOf(FocusStackConfig.KEY_ENABLED to "oops")).enabled,
        )
    }

    @Test
    fun effectiveStepCount_andWorkingScale_neverDegenerate() {
        val config = FocusStackConfig(frameCount = 200, stepCount = 0)
        assertTrue(config.effectiveStepCount() >= FocusStackConfig.FRAME_MIN)
        assertTrue(config.effectiveWorkingScale() in FocusStackConfig.WORKING_SCALE_MIN..FocusStackConfig.WORKING_SCALE_MAX)
    }
}
