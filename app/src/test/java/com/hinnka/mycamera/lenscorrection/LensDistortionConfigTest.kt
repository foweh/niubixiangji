package com.hinnka.mycamera.lenscorrection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 畸变配置单测：clamp 三态（正常/边界/异常）、override×strength 语义、三维度设置与 DataStore 解析。
 */
class LensDistortionConfigTest {

    // -------------------------------------------------------------- clamp 三态

    @Test
    fun clampKeepsNormalValuesUntouched() {
        val config = LensDistortionConfig(
            enabled = true,
            dimension = LensCameraDimension.MACRO,
            strength = 0.6f,
            k1Override = 0.2f,
            k2Override = -0.03f,
            k3Override = 0.004f,
            centerX = 0.48f,
            centerY = 0.52f,
            cropScale = 1.1f,
            edgePolicy = DistortionEdgePolicy.TRANSPARENT_BLACK,
        )
        assertEquals(config, LensDistortionConfig.clamp(config))
    }

    @Test
    fun clampKeepsBoundaryValuesUntouched() {
        val config = LensDistortionConfig(
            enabled = true,
            strength = LensDistortionConfig.STRENGTH_MAX,
            k1Override = LensDistortionConfig.COEFFICIENT_MIN,
            k2Override = LensDistortionConfig.COEFFICIENT_MAX,
            k3Override = 0f,
            centerX = LensDistortionConfig.CENTER_MIN,
            centerY = LensDistortionConfig.CENTER_MAX,
            cropScale = LensDistortionConfig.CROP_SCALE_MAX,
        )
        assertEquals(config, LensDistortionConfig.clamp(config))
    }

    @Test
    fun clampPullsOutOfRangeValuesBack() {
        val clamped = LensDistortionConfig.clamp(
            LensDistortionConfig(
                strength = 7f,
                k1Override = 9f,
                k2Override = -9f,
                k3Override = 4f,
                centerX = -3f,
                centerY = 8f,
                cropScale = 99f,
            )
        )
        assertEquals(LensDistortionConfig.STRENGTH_MAX, clamped.strength, 0f)
        assertEquals(LensDistortionConfig.COEFFICIENT_MAX, clamped.k1Override!!, 0f)
        assertEquals(LensDistortionConfig.COEFFICIENT_MIN, clamped.k2Override!!, 0f)
        assertEquals(LensDistortionConfig.COEFFICIENT_MAX, clamped.k3Override!!, 0f)
        assertEquals(LensDistortionConfig.CENTER_MIN, clamped.centerX, 0f)
        assertEquals(LensDistortionConfig.CENTER_MAX, clamped.centerY, 0f)
        assertEquals(LensDistortionConfig.CROP_SCALE_MAX, clamped.cropScale, 0f)

        val tiny = LensDistortionConfig.clamp(LensDistortionConfig(strength = -1f, cropScale = 0.1f))
        assertEquals(LensDistortionConfig.STRENGTH_MIN, tiny.strength, 0f)
        assertEquals(LensDistortionConfig.CROP_SCALE_MIN, tiny.cropScale, 0f)
    }

    @Test
    fun clampHandlesNonFiniteValues() {
        val clamped = LensDistortionConfig.clamp(
            LensDistortionConfig(
                strength = Float.NaN,
                k1Override = Float.NaN,
                k2Override = Float.POSITIVE_INFINITY,
                k3Override = Float.NEGATIVE_INFINITY,
                centerX = Float.NaN,
                centerY = Float.POSITIVE_INFINITY,
                cropScale = Float.NaN,
            )
        )
        // 标量：非有限 → 回默认值
        assertEquals(1f, clamped.strength, 0f)
        assertEquals(0.5f, clamped.centerX, 0f)
        assertEquals(0.5f, clamped.centerY, 0f)
        assertEquals(1f, clamped.cropScale, 0f)
        // override：非有限 → 置 null（= 回到该维度预设，而不是塞一个假系数）
        assertNull(clamped.k1Override)
        assertNull(clamped.k2Override)
        assertNull(clamped.k3Override)
    }

    // -------------------------------------------------------------- 系数语义

    @Test
    fun effectiveCoefficientsUsePresetWhenNoOverride() {
        val config = LensDistortionConfig(dimension = LensCameraDimension.MAIN, strength = 1f)
        val preset = LensProfileDefaults.presetFor(LensCameraDimension.MAIN)
        assertEquals(preset.k1, config.effectiveK1(), 1e-7f)
        assertEquals(preset.k2, config.effectiveK2(), 1e-7f)
        assertEquals(preset.k3, config.effectiveK3(), 1e-7f)
    }

    @Test
    fun strengthScalesCoefficientsLinearly() {
        val half = LensDistortionConfig(dimension = LensCameraDimension.ULTRA_WIDE, strength = 0.5f)
        val preset = LensProfileDefaults.presetFor(LensCameraDimension.ULTRA_WIDE)
        assertEquals(preset.k1 * 0.5f, half.effectiveK1(), 1e-7f)
        assertEquals(preset.k2 * 0.5f, half.effectiveK2(), 1e-7f)
    }

    @Test
    fun overrideTakesPrecedenceOverPreset() {
        val config = LensDistortionConfig(
            dimension = LensCameraDimension.ULTRA_WIDE,
            strength = 0.5f,
            k1Override = 0.4f,
            k2Override = 0f,
        )
        assertEquals(0.2f, config.effectiveK1(), 1e-7f)
        assertEquals(0f, config.effectiveK2(), 1e-7f)
    }

    @Test
    fun zeroStrengthYieldsIdentityModel() {
        val config = LensDistortionConfig(enabled = true, strength = 0f)
        assertTrue(config.model().isIdentity)
        assertFalse("强度为 0 时不应认为需要跑校正", config.isActive())
    }

    @Test
    fun isActiveRequiresEnabledNonZeroStrengthAndNonTrivialModel() {
        assertFalse(LensDistortionConfig(enabled = false).isActive())
        assertTrue(LensDistortionConfig(enabled = true, strength = 1f, dimension = LensCameraDimension.ULTRA_WIDE).isActive())
        assertFalse(
            LensDistortionConfig(
                enabled = true,
                strength = 1f,
                k1Override = 0f,
                k2Override = 0f,
                k3Override = 0f,
            ).isActive(),
        )
    }

    @Test
    fun disabledConfigStillCarriesPresetCoefficients() {
        // 这是恒等网格必须显式把 k 覆盖为 0 的原因：enabled=false 不会把预设变成恒等
        val config = LensDistortionConfig(enabled = false, dimension = LensCameraDimension.ULTRA_WIDE)
        assertFalse(config.model().isIdentity)
        assertFalse(config.isActive())
    }

    // -------------------------------------------------------------- 三维度设置

    @Test
    fun defaultSettingsCoverAllThreeDimensionsDisabled() {
        val settings = LensCorrectionSettings.DEFAULT
        assertEquals(3, settings.perDimension.size)
        for (dimension in LensCameraDimension.entries) {
            val config = settings.forDimension(dimension)
            assertFalse("默认必须关闭，等用户显式开启", config.enabled)
            assertEquals(dimension, config.dimension)
            assertEquals(0.5f, config.centerX, 0f)
        }
        assertEquals(3, LensProfileDefaults.ALL.size)
    }

    @Test
    fun forDimensionFallsBackToPresetDefaultsWhenMissing() {
        val settings = LensCorrectionSettings(perDimension = emptyMap())
        val config = settings.forDimension(LensCameraDimension.MACRO)
        assertEquals(LensCameraDimension.MACRO, config.dimension)
        assertEquals(1f, config.strength, 0f)
        assertFalse(config.enabled)
    }

    @Test
    fun withDimensionReplacesAndClamps() {
        val settings = LensCorrectionSettings.DEFAULT.withDimension(
            LensDistortionConfig(
                enabled = true,
                dimension = LensCameraDimension.MAIN,
                strength = 5f,
                cropScale = 9f,
            )
        )
        val stored = settings.forDimension(LensCameraDimension.MAIN)
        assertTrue(stored.enabled)
        assertEquals(1f, stored.strength, 0f)
        assertEquals(LensDistortionConfig.CROP_SCALE_MAX, stored.cropScale, 0f)
        // 其它维度不受影响
        assertFalse(settings.forDimension(LensCameraDimension.MACRO).enabled)
    }

    @Test
    fun settingsClampWithReportFixesEachDimension() {
        val bad = LensCorrectionSettings(
            perDimension = mapOf(
                LensCameraDimension.MAIN to LensDistortionConfig(
                    dimension = LensCameraDimension.MAIN, strength = 9f, centerX = -1f,
                ),
            )
        )
        val (fixed, reports) = bad.clampWithReport()
        assertEquals(1f, fixed.forDimension(LensCameraDimension.MAIN).strength, 0f)
        assertEquals(0f, fixed.forDimension(LensCameraDimension.MAIN).centerX, 0f)
        assertTrue("必须报告被修正的维度: $reports", reports.any { it.startsWith("MAIN") })
        assertEquals(3, fixed.perDimension.size)
    }

    @Test
    fun cleanSettingsClampSilently() {
        val (fixed, reports) = LensCorrectionSettings.DEFAULT.clampWithReport()
        assertEquals(LensCorrectionSettings.DEFAULT, fixed)
        assertTrue("干净配置不应产生报告: $reports", reports.isEmpty())
    }

    // -------------------------------------------------------------- DataStore 解析

    @Test
    fun fromPreferencesReadsPerDimensionKeys() {
        val settings = LensCorrectionSettings.fromPreferences(
            mapOf(
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "enabled") to true,
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "strength") to 0.5f,
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "k1") to 0.4f,
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "k2") to "-0.05",
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "center_x") to 0.45f,
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "crop_scale") to 1.1f,
                LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "edge_policy") to "transparent_black",
            )
        )
        val ultraWide = settings.forDimension(LensCameraDimension.ULTRA_WIDE)
        assertTrue(ultraWide.enabled)
        assertEquals(0.5f, ultraWide.strength, 0f)
        // 生效系数 = override × strength
        assertEquals(0.4f * 0.5f, ultraWide.effectiveK1(), 1e-5f)
        assertEquals(-0.05f * 0.5f, ultraWide.effectiveK2(), 1e-5f)
        assertEquals(0.45f, ultraWide.centerX, 0f)
        assertEquals(1.1f, ultraWide.cropScale, 0f)
        assertEquals(DistortionEdgePolicy.TRANSPARENT_BLACK, ultraWide.edgePolicy)
        // 未配置的维度保持默认
        assertFalse(settings.forDimension(LensCameraDimension.MAIN).enabled)
        assertEquals(0.5f, settings.forDimension(LensCameraDimension.MAIN).centerX, 0f)
    }

    @Test
    fun fromPreferencesIgnoresGarbageAndStillClamps() {
        val settings = LensCorrectionSettings.fromPreferences(
            mapOf(
                LensCorrectionSettings.keyOf(LensCameraDimension.MAIN, "enabled") to "maybe",
                LensCorrectionSettings.keyOf(LensCameraDimension.MAIN, "strength") to 42f,
                LensCorrectionSettings.keyOf(LensCameraDimension.MAIN, "k1") to "abc",
                LensCorrectionSettings.keyOf(LensCameraDimension.MAIN, "center_y") to null,
                LensCorrectionSettings.keyOf(LensCameraDimension.MAIN, "crop_scale") to Float.NaN,
                LensCorrectionSettings.keyOf(LensCameraDimension.MACRO, "edge_policy") to 7,
            )
        )
        val main = settings.forDimension(LensCameraDimension.MAIN)
        assertFalse(main.enabled)
        assertEquals(1f, main.strength, 0f) // 42 → clamp 到 1
        assertNull(main.k1Override) // 非法 → 用预设
        assertEquals(0.5f, main.centerY, 0f)
        assertEquals(1f, main.cropScale, 0f)
        assertEquals(DistortionEdgePolicy.CLAMP, settings.forDimension(LensCameraDimension.MACRO).edgePolicy)
    }

    @Test
    fun keyNamesFollowDocumentedPrefix() {
        assertEquals(
            "lens_correction_ULTRA_WIDE_strength",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "strength"),
        )
        assertEquals("lens_correction_", LensCorrectionSettings.KEY_PREFIX)
    }
}
