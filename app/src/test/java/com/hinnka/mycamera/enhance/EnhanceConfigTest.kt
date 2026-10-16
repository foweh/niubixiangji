package com.hinnka.mycamera.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管线配置单测：阶段编排（顺序/去重/开关）、全量 clamp 报告、DataStore 解析容错。
 * 覆盖「正常 / 边界 / 异常」三态。
 */
class EnhanceConfigTest {

    // ------------------------------------------------------- 阶段编排

    @Test
    fun defaultStagesFollowCanonicalOrder() {
        // 默认：降噪/CLAHE/锐化开启；去雾默认关闭；趣味滤镜 kind=NONE 不算阶段
        assertEquals(
            listOf(EnhanceStage.DENOISE, EnhanceStage.CLAHE, EnhanceStage.SHARPEN),
            EnhanceConfig.DEFAULT.stages(),
        )
        assertTrue(EnhanceConfig.DEFAULT.isActive())
    }

    @Test
    fun disabledModuleHasNoStages() {
        assertFalse(EnhanceConfig(enabled = false).stages().isNotEmpty())
        assertFalse(EnhanceConfig(enabled = false).isActive())
    }

    @Test
    fun everyStageCanBeDisabledIndividually() {
        val config = EnhanceConfig(
            clahe = ClaheConfig(enabled = false),
            dehaze = DehazeConfig(enabled = true),
            sharpen = UnsharpMaskConfig(enabled = false),
            denoise = BilateralConfig(enabled = false),
            funFilter = FunFilterConfig(enabled = true, kind = FunFilterKind.SKETCH),
        )
        assertEquals(listOf(EnhanceStage.DEHAZE, EnhanceStage.FUN_FILTER), config.stages())
    }

    @Test
    fun funFilterStageRequiresConcreteKind() {
        val none = EnhanceConfig(funFilter = FunFilterConfig(enabled = true, kind = FunFilterKind.NONE))
        assertFalse(none.stages().contains(EnhanceStage.FUN_FILTER))
        val tilt = EnhanceConfig(funFilter = FunFilterConfig(enabled = true, kind = FunFilterKind.TILT_SHIFT))
        assertTrue(tilt.stages().contains(EnhanceStage.FUN_FILTER))
    }

    @Test
    fun stageOrderIsConfigurableAndDeduplicated() {
        val config = EnhanceConfig(
            stageOrder = listOf(EnhanceStage.SHARPEN, EnhanceStage.SHARPEN, EnhanceStage.DENOISE),
            dehaze = DehazeConfig(enabled = true),
        )
        // 去雾未在自定义顺序中声明 → 不执行；重复项去重；顺序完全跟随配置
        assertEquals(listOf(EnhanceStage.SHARPEN, EnhanceStage.DENOISE), config.stages())
    }

    @Test
    fun emptyStageOrderFallsBackToDefaultWhenClamped() {
        val (clamped, reports) = EnhanceConfig(enabled = true, stageOrder = emptyList()).clampWithReport()
        assertEquals(EnhanceConfig.DEFAULT_STAGE_ORDER, clamped.stageOrder)
        assertTrue("stageOrder fallback must be reported: $reports", reports.any { it.startsWith("stageOrder") })
    }

    // ------------------------------------------------------- clamp

    @Test
    fun clampKeepsCleanConfigUnchangedAndSilent() {
        val (clamped, reports) = EnhanceConfig.DEFAULT.clampWithReport()
        assertEquals(EnhanceConfig.DEFAULT, clamped)
        assertTrue("clean config must not report: $reports", reports.isEmpty())
    }

    @Test
    fun clampPullsOutOfRangeValuesBackAndReports() {
        val config = EnhanceConfig(
            workingScale = 9f,
            clahe = ClaheConfig(tilesX = 999, tilesY = -4, clipLimit = 99f, mix = -1f),
            dehaze = DehazeConfig(strength = 5f, omega = 0.1f, patchRadius = 8, minTransmission = 0f),
            sharpen = UnsharpMaskConfig(radius = 100f, amount = 9f, threshold = 999),
            denoise = BilateralConfig(radius = 99, sigmaSpace = 0f, sigmaColor = 9f, strength = -3f),
        )
        val (clamped, reports) = config.clampWithReport()

        assertEquals(1f, clamped.workingScale, 0f)
        assertEquals(ClaheConfig.TILES_MAX, clamped.clahe.tilesX)
        assertEquals(ClaheConfig.TILES_MIN, clamped.clahe.tilesY)
        assertEquals(ClaheConfig.CLIP_LIMIT_MAX, clamped.clahe.clipLimit, 0f)
        assertEquals(ClaheConfig.MIX_MIN, clamped.clahe.mix, 0f)
        assertEquals(DehazeConfig.STRENGTH_MAX, clamped.dehaze.strength, 0f)
        assertEquals(DehazeConfig.OMEGA_MIN, clamped.dehaze.omega, 0f)
        // patchRadius 8 是偶数 → 强制成奇数 9（暗通道窗口必须奇数边长）
        assertEquals(9, clamped.dehaze.patchRadius)
        assertEquals(DehazeConfig.MIN_TRANSMISSION_MIN, clamped.dehaze.minTransmission, 0f)
        assertEquals(UnsharpMaskConfig.RADIUS_MAX, clamped.sharpen.radius, 0f)
        assertEquals(UnsharpMaskConfig.AMOUNT_MAX, clamped.sharpen.amount, 0f)
        assertEquals(UnsharpMaskConfig.THRESHOLD_MAX, clamped.sharpen.threshold)
        assertEquals(BilateralConfig.RADIUS_MAX, clamped.denoise.radius)
        assertEquals(BilateralConfig.SIGMA_SPACE_MIN, clamped.denoise.sigmaSpace, 0f)
        assertEquals(BilateralConfig.SIGMA_COLOR_MAX, clamped.denoise.sigmaColor, 0f)
        assertEquals(BilateralConfig.STRENGTH_MIN, clamped.denoise.strength, 0f)

        assertTrue("must report corrections: ${reports.size}", reports.size >= 10)
        assertTrue(reports.any { it.startsWith("clahe.tilesX") })
        assertTrue(reports.any { it.startsWith("denoise.radius") })
    }

    @Test
    fun clampIsIdempotent() {
        val bad = EnhanceConfig(
            workingScale = 5f,
            clahe = ClaheConfig(clipLimit = Float.NaN),
            dehaze = DehazeConfig(patchRadius = 4, atmospherePercentile = Float.POSITIVE_INFINITY),
        )
        val once = bad.clampWithReport().first
        val (twice, reports) = once.clampWithReport()
        assertEquals(once, twice)
        assertTrue("second clamp must be silent: $reports", reports.isEmpty())
    }

    // ------------------------------------------------------- DataStore 解析

    @Test
    fun fromPreferencesReadsAndClampsValues() {
        val config = EnhanceConfig.fromPreferences(
            mapOf(
                EnhanceConfig.KEY_ENABLED to true,
                EnhanceConfig.KEY_WORKING_SCALE to 0.5f,
                EnhanceConfig.KEY_CLAHE_TILES_X to 999, // 越界 → 必须被 clamp
                EnhanceConfig.KEY_CLAHE_MIX to 0.25f,
                EnhanceConfig.KEY_DEHAZE_ENABLED to true,
                EnhanceConfig.KEY_DEHAZE_STRENGTH to 0.4f,
                EnhanceConfig.KEY_DEHAZE_ATMOSPHERE_MODE to "fixed",
                EnhanceConfig.KEY_DEHAZE_FIXED_ATMOSPHERE to 0.7f,
                EnhanceConfig.KEY_SHARPEN_AMOUNT to 1.5f,
                EnhanceConfig.KEY_DENOISE_RADIUS to 5,
                EnhanceConfig.KEY_DENOISE_STRENGTH to 0.3f,
            )
        )
        assertTrue(config.enabled)
        assertEquals(0.5f, config.workingScale, 0f)
        assertEquals(ClaheConfig.TILES_MAX, config.clahe.tilesX)
        assertEquals(0.25f, config.clahe.mix, 0f)
        assertTrue(config.dehaze.enabled)
        assertEquals(0.4f, config.dehaze.strength, 0f)
        assertEquals(AtmosphereMode.FIXED, config.dehaze.atmosphereMode)
        assertEquals(0.7f, config.dehaze.fixedAtmosphere, 0f)
        assertEquals(1.5f, config.sharpen.amount, 0f)
        assertEquals(5, config.denoise.radius)
        assertEquals(0.3f, config.denoise.strength, 0f)
    }

    @Test
    fun fromPreferencesIgnoresGarbageWithoutThrowing() {
        val config = EnhanceConfig.fromPreferences(
            mapOf(
                EnhanceConfig.KEY_ENABLED to "not-a-bool",
                EnhanceConfig.KEY_WORKING_SCALE to listOf(1, 2, 3),
                EnhanceConfig.KEY_CLAHE_TILES_X to "abc",
                EnhanceConfig.KEY_DEHAZE_ATMOSPHERE_MODE to 42,
                EnhanceConfig.KEY_SHARPEN_RADIUS to null,
                EnhanceConfig.KEY_DENOISE_SIGMA_COLOR to Float.NaN,
            )
        )
        assertEquals(EnhanceConfig.DEFAULT.enabled, config.enabled)
        assertEquals(EnhanceConfig.DEFAULT.workingScale, config.workingScale, 0f)
        assertEquals(EnhanceConfig.DEFAULT.clahe.tilesX, config.clahe.tilesX)
        assertEquals(AtmosphereMode.TOP_DARKEST, config.dehaze.atmosphereMode)
        assertEquals(EnhanceConfig.DEFAULT.sharpen.radius, config.sharpen.radius, 0f)
        assertEquals(EnhanceConfig.DEFAULT.denoise.sigmaColor, config.denoise.sigmaColor, 0f)
    }

    @Test
    fun fromPreferencesParsesBooleansFromStringsAndNumbers() {
        assertFalse(
            EnhanceConfig.fromPreferences(mapOf(EnhanceConfig.KEY_ENABLED to "false")).enabled
        )
        assertFalse(
            EnhanceConfig.fromPreferences(mapOf(EnhanceConfig.KEY_ENABLED to 0)).enabled
        )
        assertTrue(
            EnhanceConfig.fromPreferences(mapOf(EnhanceConfig.KEY_ENABLED to "ON")).enabled
        )
    }

    @Test
    fun fromPreferencesOnEmptyMapYieldsDefaults() {
        assertEquals(EnhanceConfig.DEFAULT, EnhanceConfig.fromPreferences(emptyMap()))
    }

    @Test
    fun prefsIntRoundsAndRejectsNonFinite() {
        assertEquals(5, EnhancePrefs.int(mapOf("k" to 4.6f), "k", 0))
        assertEquals(3, EnhancePrefs.int(mapOf("k" to "3.4"), "k", 0))
        assertEquals(0, EnhancePrefs.int(mapOf("k" to Float.NaN), "k", 0))
        assertEquals(0, EnhancePrefs.int(mapOf("k" to Float.POSITIVE_INFINITY), "k", 0))
        assertEquals(0, EnhancePrefs.int(emptyMap(), "k", 0))
    }

    @Test
    fun prefsFloatRejectsNonFiniteKeepsNumericStrings() {
        assertEquals(1.5f, EnhancePrefs.float(mapOf("k" to 1.5), "k", 0f), 1e-6f)
        assertEquals(2.25f, EnhancePrefs.float(mapOf("k" to "2.25"), "k", 0f), 1e-6f)
        assertEquals(1f, EnhancePrefs.float(mapOf("k" to true), "k", 0f), 1e-6f)
        assertEquals(9f, EnhancePrefs.float(mapOf("k" to Float.NaN), "k", 9f), 1e-6f)
        assertEquals(9f, EnhancePrefs.float(mapOf("k" to "abc"), "k", 9f), 1e-6f)
    }
}
