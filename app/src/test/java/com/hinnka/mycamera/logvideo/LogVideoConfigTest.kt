package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.video.VideoLogLutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log 视频总配置 clamp + LUT 强度混合边界测试
 * （Log 视频 20 项 · 第 2 项「实时 LUT 预览：LUT 档位/强度可配」）。
 *
 * 强度语义（与既有 `VideoColorEffectLayer(lutIntensity)` 的 0..2 约定一致）：
 * `0` = LUT 恒等（完全不着色）、`1` = 完整映射、`2` = 过冲（增强）。
 */
class LogVideoConfigTest {

    // ─────────────────── 强度混合边界 ───────────────────

    @Test
    fun intensityBoundariesAreInclusive() {
        assertEquals(0f, LogVideoConfig(lutIntensity = 0f).clamped().lutIntensity, 1e-6f)
        assertEquals(1f, LogVideoConfig(lutIntensity = 1f).clamped().lutIntensity, 1e-6f)
        assertEquals(2f, LogVideoConfig(lutIntensity = 2f).clamped().lutIntensity, 1e-6f)
    }

    @Test
    fun intensityIsClampedOnBothSides() {
        assertEquals(0f, LogVideoConfig(lutIntensity = -1f).clamped().lutIntensity, 1e-6f)
        assertEquals(0f, LogVideoConfig(lutIntensity = -999f).clamped().lutIntensity, 1e-6f)
        assertEquals(2f, LogVideoConfig(lutIntensity = 2.5f).clamped().lutIntensity, 1e-6f)
        assertEquals(2f, LogVideoConfig(lutIntensity = 999f).clamped().lutIntensity, 1e-6f)
    }

    @Test
    fun nonFiniteIntensityFallsBackToDefault() {
        assertEquals(LogVideoConfig.DEFAULT_LUT_INTENSITY, LogVideoConfig(lutIntensity = Float.NaN).clamped().lutIntensity, 1e-6f)
        assertEquals(LogVideoConfig.DEFAULT_LUT_INTENSITY, LogVideoConfig(lutIntensity = Float.POSITIVE_INFINITY).clamped().lutIntensity, 1e-6f)
    }

    @Test
    fun clampsAreIdempotent() {
        val dirty = LogVideoConfig(
            curve = LogCurveRecipe(preset = LogCurvePreset.HLG, peak = 99f, tableSize = 3),
            lutIntensity = 7f,
            bakedRestoreLutSize = 500,
        ).clamped()
        assertEquals(dirty, dirty.clamped())
        assertTrue(dirty.clampReport().isEmpty())
    }

    @Test
    fun clampReportIsSilentForDefaultsAndLoudForDirtyInput() {
        assertTrue(LogVideoConfig.DEFAULT.clampReport().isEmpty())
        val report = LogVideoConfig(lutIntensity = 5f, bakedRestoreLutSize = 4).clampReport()
        assertTrue(report.any { it.startsWith("lutIntensity=") })
        assertTrue(report.any { it.startsWith("bakedRestoreLutSize=") })
        // 曲线子配方的问题带前缀，便于定位
        val curveReport = LogVideoConfig(
            curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, slope = 77f),
        ).clampReport()
        assertTrue(curveReport.any { it.startsWith("curve.slope=") })
    }

    // ─────────────────── 烘焙 LUT 尺寸边界 ───────────────────

    @Test
    fun bakedLutSizeIsClamped() {
        assertEquals(LogToRec709LutBaker.DEFAULT_SIZE, LogVideoConfig().bakedRestoreLutSize)
        assertEquals(LogToRec709LutBaker.MIN_SIZE, LogVideoConfig(bakedRestoreLutSize = 0).clamped().bakedRestoreLutSize)
        assertEquals(LogToRec709LutBaker.MIN_SIZE, LogVideoConfig(bakedRestoreLutSize = LogToRec709LutBaker.MIN_SIZE).clamped().bakedRestoreLutSize)
        assertEquals(LogToRec709LutBaker.MAX_SIZE, LogVideoConfig(bakedRestoreLutSize = 1024).clamped().bakedRestoreLutSize)
        assertEquals(LogToRec709LutBaker.MAX_SIZE, LogVideoConfig(bakedRestoreLutSize = LogToRec709LutBaker.MAX_SIZE).clamped().bakedRestoreLutSize)
    }

    // ─────────────────── 还原 LUT id 解析（正常/边界/异常） ───────────────────

    @Test
    fun defaultRestoreLutIdsCoverEveryPresetAndExistInProjectAssets() {
        val ids = LogCurvePreset.entries.map { LogVideoConfig.defaultRestoreLutId(it) }
        assertEquals(LogCurvePreset.entries.size, ids.size)
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(ids.size, ids.toSet().size)
        // 与 assets/luts/config.json 的 log 组 id 同名（一键套用靠这个 id 命中）
        listOf("log_restore_apple_log2", "log_restore_flog2", "log_restore_vlog",
            "log_restore_logc4", "log_restore_log3g10", "log_restore_llog")
            .forEach { assertTrue("$it 应在默认映射里", ids.contains(it)) }
    }

    @Test
    fun resolvedRestoreLutIdPrefersExplicitThenBakedThenBuiltIn() {
        val explicit = LogVideoConfig(restoreLutId = "log_restore_vlog", bakeRestoreLutOnDevice = true)
        assertEquals("log_restore_vlog", explicit.resolvedRestoreLutId())

        val baked = LogVideoConfig(curve = LogCurveRecipe(preset = LogCurvePreset.HLG), bakeRestoreLutOnDevice = true)
        assertEquals(
            LogLutPackCatalog.BUILT_IN_BAKED_ID + "_" + LogCurvePreset.HLG.name.lowercase(),
            baked.resolvedRestoreLutId(),
        )

        val builtIn = LogVideoConfig(
            curve = LogCurveRecipe(preset = LogCurvePreset.V_LOG),
            bakeRestoreLutOnDevice = false,
        )
        assertEquals("log_restore_vlog", builtIn.resolvedRestoreLutId())

        // 空白字符串视为未指定（异常输入不落空 id）
        assertEquals(
            "log_restore_logc3",
            LogVideoConfig(restoreLutId = "   ", bakeRestoreLutOnDevice = false, curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3))
                .resolvedRestoreLutId(),
        )
    }

    // ─────────────────── GPU 微调开关 ───────────────────

    @Test
    fun gpuTrimOnlyWhenEnabledAndCurveIsNotPresetNative() {
        assertFalse("默认关闭", LogVideoConfig().appliesGpuTrim)
        assertFalse(
            "开启但曲线为原生默认 → 无需下 uniform",
            LogVideoConfig(enabled = true).appliesGpuTrim,
        )
        assertTrue(
            "开启 + 自定义参数 → 下发 trim",
            LogVideoConfig(enabled = true, curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, slope = 1.5f)).appliesGpuTrim,
        )
        assertFalse(
            "开关关闭 → 即使参数自定义也不下发",
            LogVideoConfig(enabled = true, gpuCurveTrimEnabled = false, curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, slope = 1.5f)).appliesGpuTrim,
        )
    }

    @Test
    fun curveTrimIdentityMatchesShaderFastPath() {
        assertEquals(LogCurveTrim.IDENTITY, LogCurveTrim.from(LogVideoConfig(enabled = false)))
        assertEquals(LogCurveTrim.IDENTITY, LogCurveTrim.from(LogVideoConfig(enabled = true)))
        val trim = LogCurveTrim.from(
            LogVideoConfig(enabled = true, curve = LogCurveRecipe(preset = LogCurvePreset.HLG, blackLevel = 0.05f, peak = 2f, slope = 1.25f)),
        )
        assertEquals(0.05f, trim.blackLevel, 1e-6f)
        assertEquals(2f, trim.peak, 1e-6f)
        assertEquals(1.25f, trim.slope, 1e-6f)
        assertFalse(trim.isIdentity)
    }

    // ─────────────────── 偏好表反序列化（异常数据不崩） ───────────────────

    @Test
    fun fromPreferencesFallsBackToDefaults() {
        val config = LogVideoConfig.fromPreferences(emptyMap())
        assertFalse(config.enabled)
        assertEquals(VideoLogLutMode.MONITOR_ONLY, config.lutMode)
        assertEquals(LogVideoConfig.DEFAULT_LUT_INTENSITY, config.lutIntensity, 1e-6f)
        assertEquals("", config.restoreLutId)
    }

    @Test
    fun fromPreferencesClampsDirtyValuesAndIgnoresUnknownEnums() {
        val config = LogVideoConfig.fromPreferences(
            mapOf(
                "log_video_enabled" to true,
                "log_video_lut_mode" to "NOT_A_MODE",
                "log_video_lut_intensity" to 42,
                "log_video_baked_lut_size" to -3,
                "log_video_restore_lut_id" to "log_restore_hlg",
                "log_curve_preset" to LogCurvePreset.HLG.name,
            ),
        )
        assertTrue(config.enabled)
        assertEquals(VideoLogLutMode.MONITOR_ONLY, config.lutMode)
        assertEquals(LogVideoConfig.LUT_INTENSITY_MAX, config.lutIntensity, 1e-6f)
        assertEquals(LogToRec709LutBaker.MIN_SIZE, config.bakedRestoreLutSize)
        assertEquals("log_restore_hlg", config.resolvedRestoreLutId())
        assertEquals(LogCurvePreset.HLG, config.curve.preset)
    }

    @Test
    fun describeAndClippingFlagsAreStable() {
        val config = LogVideoConfig(
            enabled = true,
            curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3),
            lutMode = VideoLogLutMode.BAKE_IN,
        )
        val text = config.describe()
        assertTrue(text.contains("LOG_C3"))
        assertTrue(text.contains("BAKE_IN"))
        assertTrue(text.contains("livePhoto=true"))
        assertTrue(config.tenBitPreferred)
        assertTrue(config.livePhotoLogEnabled)
        assertTrue(config.stillFrameLogEnabled)
    }
}
