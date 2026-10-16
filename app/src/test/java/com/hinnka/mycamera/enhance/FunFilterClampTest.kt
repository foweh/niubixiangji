package com.hinnka.mycamera.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趣味滤镜参数 clamp 单测：**正常 / 边界 / 异常（越界、NaN、Inf、错误类型）** 三类全覆盖。
 *
 * 契约（PATTERN_SPEC 第 1.6 节）：任何误设都不得崩溃，clamp 后必须落在合法区间；
 * `null` 表示「用预设」，不合法输入一律回退默认值。
 */
class FunFilterClampTest {

    // ---------------------------------------------------------------- 移轴

    @Test
    fun tiltShiftClampKeepsInRangeValuesUnchanged() {
        val config = TiltShiftConfig(
            centerY = 0.4f,
            bandWidth = 0.2f,
            transition = 0.1f,
            angleDegrees = 15f,
            blurRadius = 10,
            blurStrength = 0.5f,
            saturationBoost = 0.2f,
        )
        assertEquals(config, TiltShiftConfig.clamp(config))
    }

    @Test
    fun tiltShiftClampKeepsBoundaryValuesUnchanged() {
        val config = TiltShiftConfig(
            centerY = TiltShiftConfig.CENTER_Y_MAX,
            bandWidth = TiltShiftConfig.BAND_WIDTH_MIN,
            transition = TiltShiftConfig.TRANSITION_MAX,
            angleDegrees = TiltShiftConfig.ANGLE_MIN,
            blurRadius = TiltShiftConfig.BLUR_RADIUS_MAX,
            blurStrength = TiltShiftConfig.BLUR_STRENGTH_MIN,
            saturationBoost = TiltShiftConfig.SATURATION_MAX,
        )
        assertEquals(config, TiltShiftConfig.clamp(config))
    }

    @Test
    fun tiltShiftClampPullsOutOfRangeValuesBack() {
        val clamped = TiltShiftConfig.clamp(
            TiltShiftConfig(
                centerY = 5f,
                bandWidth = -1f,
                transition = 99f,
                angleDegrees = -900f,
                blurRadius = 500,
                blurStrength = 3f,
                saturationBoost = -4f,
            )
        )
        assertEquals(TiltShiftConfig.CENTER_Y_MAX, clamped.centerY, 0f)
        assertEquals(TiltShiftConfig.BAND_WIDTH_MIN, clamped.bandWidth, 0f)
        assertEquals(TiltShiftConfig.TRANSITION_MAX, clamped.transition, 0f)
        assertEquals(TiltShiftConfig.ANGLE_MIN, clamped.angleDegrees, 0f)
        assertEquals(TiltShiftConfig.BLUR_RADIUS_MAX, clamped.blurRadius)
        assertEquals(TiltShiftConfig.BLUR_STRENGTH_MAX, clamped.blurStrength, 0f)
        assertEquals(TiltShiftConfig.SATURATION_MIN, clamped.saturationBoost, 0f)
    }

    @Test
    fun tiltShiftClampRejectsNonFiniteValues() {
        val clamped = TiltShiftConfig.clamp(
            TiltShiftConfig(
                centerY = Float.NaN,
                bandWidth = Float.POSITIVE_INFINITY,
                transition = Float.NEGATIVE_INFINITY,
                angleDegrees = Float.NaN,
                blurStrength = Float.NaN,
                saturationBoost = Float.POSITIVE_INFINITY,
            )
        )
        assertEquals(0.5f, clamped.centerY, 0f)
        assertEquals(0.18f, clamped.bandWidth, 0f)
        assertEquals(0.18f, clamped.transition, 0f)
        assertEquals(0f, clamped.angleDegrees, 0f)
        assertEquals(1f, clamped.blurStrength, 0f)
        assertEquals(0.35f, clamped.saturationBoost, 0f)
    }

    // ---------------------------------------------------------------- 素描

    @Test
    fun sketchClampKeepsNormalAndBoundaryValues() {
        val normal = SketchConfig(edgeStrength = 2f, threshold = 0.1f, colorMix = 0.5f)
        assertEquals(normal, SketchConfig.clamp(normal))
        val boundary = SketchConfig(
            edgeStrength = SketchConfig.EDGE_STRENGTH_MIN,
            threshold = SketchConfig.THRESHOLD_MAX,
            colorMix = SketchConfig.COLOR_MIX_MAX,
        )
        assertEquals(boundary, SketchConfig.clamp(boundary))
    }

    @Test
    fun sketchClampPullsOutOfRangeAndNonFiniteValuesBack() {
        val clamped = SketchConfig.clamp(
            SketchConfig(
                edgeStrength = Float.NEGATIVE_INFINITY,
                threshold = -3f,
                colorMix = 77f,
            )
        )
        assertEquals(1.2f, clamped.edgeStrength, 0f)
        assertEquals(SketchConfig.THRESHOLD_MIN, clamped.threshold, 0f)
        assertEquals(SketchConfig.COLOR_MIX_MAX, clamped.colorMix, 0f)

        val high = SketchConfig.clamp(SketchConfig(edgeStrength = 1000f, threshold = Float.NaN))
        assertEquals(SketchConfig.EDGE_STRENGTH_MAX, high.edgeStrength, 0f)
        assertEquals(0.06f, high.threshold, 0f)
    }

    // ---------------------------------------------------------------- 油画

    @Test
    fun oilPaintClampKeepsNormalAndBoundaryValues() {
        val normal = OilPaintConfig(levels = 12, bilateralRadius = 4, sigmaColor = 0.2f)
        assertEquals(normal, OilPaintConfig.clamp(normal))
        val boundary = OilPaintConfig(
            levels = OilPaintConfig.LEVELS_MAX,
            bilateralRadius = OilPaintConfig.BILATERAL_RADIUS_MIN,
            sigmaColor = OilPaintConfig.SIGMA_COLOR_MAX,
            edgeStrength = OilPaintConfig.EDGE_STRENGTH_MAX,
            edgeThreshold = OilPaintConfig.EDGE_THRESHOLD_MIN,
            paintMix = OilPaintConfig.PAINT_MIX_MIN,
        )
        assertEquals(boundary, OilPaintConfig.clamp(boundary))
    }

    @Test
    fun oilPaintClampHandlesOutOfRangeAndNonFiniteValues() {
        val clamped = OilPaintConfig.clamp(
            OilPaintConfig(
                levels = 0,
                bilateralRadius = -7,
                sigmaColor = Float.NaN,
                edgeStrength = 99f,
                edgeThreshold = 9f,
                paintMix = -2f,
            )
        )
        assertEquals(OilPaintConfig.LEVELS_MIN, clamped.levels)
        assertEquals(OilPaintConfig.BILATERAL_RADIUS_MIN, clamped.bilateralRadius)
        assertEquals(0.15f, clamped.sigmaColor, 0f)
        assertEquals(OilPaintConfig.EDGE_STRENGTH_MAX, clamped.edgeStrength, 0f)
        assertEquals(OilPaintConfig.EDGE_THRESHOLD_MAX, clamped.edgeThreshold, 0f)
        assertEquals(OilPaintConfig.PAINT_MIX_MIN, clamped.paintMix, 0f)

        // 非有限值走的是「回落到默认值」约定，而不是「夹到上限」（全模块统一口径）
        val nonFinite = OilPaintConfig.clamp(
            OilPaintConfig(
                sigmaColor = Float.NEGATIVE_INFINITY,
                edgeStrength = Float.POSITIVE_INFINITY,
                edgeThreshold = Float.NaN,
                paintMix = Float.POSITIVE_INFINITY,
            )
        )
        assertEquals(0.15f, nonFinite.sigmaColor, 0f)
        assertEquals(0.8f, nonFinite.edgeStrength, 0f)
        assertEquals(0.05f, nonFinite.edgeThreshold, 0f)
        assertEquals(1f, nonFinite.paintMix, 0f)
    }

    // ---------------------------------------------------------------- 聚合

    @Test
    fun funFilterClampReportsEveryCorrection() {
        val (clamped, reports) = FunFilterConfig(
            kind = FunFilterKind.OIL_PAINT,
            tiltShift = TiltShiftConfig(centerY = 9f),
            sketch = SketchConfig(threshold = 99f),
            oilPaint = OilPaintConfig(levels = 9999),
        ).clampWithReport()

        assertTrue("clamp must report corrections: $reports", reports.size >= 3)
        assertEquals(TiltShiftConfig.CENTER_Y_MAX, clamped.tiltShift.centerY, 0f)
        assertEquals(SketchConfig.THRESHOLD_MAX, clamped.sketch.threshold, 0f)
        assertEquals(OilPaintConfig.LEVELS_MAX, clamped.oilPaint.levels)
    }

    @Test
    fun funFilterClampReportsNothingForCleanConfig() {
        val (_, reports) = FunFilterConfig(kind = FunFilterKind.SKETCH).clampWithReport()
        assertTrue("clean config must not report: $reports", reports.isEmpty())
    }

    @Test
    fun funFilterIsActiveRequiresKindAndEnabled() {
        assertFalse(FunFilterConfig(kind = FunFilterKind.NONE).isActive())
        assertFalse(FunFilterConfig(enabled = false, kind = FunFilterKind.TILT_SHIFT).isActive())
        assertTrue(FunFilterConfig(enabled = true, kind = FunFilterKind.TILT_SHIFT).isActive())
    }

    @Test
    fun funFilterFromPreferencesReadsValues() {
        val config = FunFilterConfig.fromPreferences(
            mapOf(
                FunFilterConfig.KEY_ENABLED to true,
                FunFilterConfig.KEY_KIND to "oil_paint",
                FunFilterConfig.KEY_OIL_LEVELS to 16,
                FunFilterConfig.KEY_TILT_CENTER_Y to 0.25f,
                FunFilterConfig.KEY_SKETCH_INVERT to "true",
            )
        )
        assertEquals(FunFilterKind.OIL_PAINT, config.kind)
        assertEquals(16, config.oilPaint.levels)
        assertEquals(0.25f, config.tiltShift.centerY, 0f)
        assertTrue(config.sketch.invert)
    }

    @Test
    fun funFilterFromPreferencesIgnoresGarbageAndClamps() {
        val config = FunFilterConfig.fromPreferences(
            mapOf(
                FunFilterConfig.KEY_KIND to 42,
                FunFilterConfig.KEY_OIL_LEVELS to "not-a-number",
                FunFilterConfig.KEY_TILT_CENTER_Y to 8f,
                FunFilterConfig.KEY_SKETCH_THRESHOLD to "abc",
                FunFilterConfig.KEY_TILT_ANGLE to Float.NaN,
            )
        )
        assertEquals(FunFilterKind.NONE, config.kind) // 类型不符 → 默认
        assertEquals(8, config.oilPaint.levels) // 非法数字 → 默认 8
        assertEquals(TiltShiftConfig.CENTER_Y_MAX, config.tiltShift.centerY, 0f) // 越界被 clamp
        assertEquals(0.06f, config.sketch.threshold, 0f)
        assertEquals(0f, config.tiltShift.angleDegrees, 0f)
    }

    @Test
    fun enumPreferenceFallsBackForUnknownName() {
        val values: Map<String, Any?> = mapOf(FunFilterConfig.KEY_KIND to "not_a_kind")
        assertEquals(FunFilterKind.NONE, FunFilterConfig.fromPreferences(values).kind)
    }
}
