package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.raw.ColorSpace
import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.VideoLogProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LUT 档位选择边界测试（Log 视频 20 项 · 第 2 项）。
 *
 * 三类用例：
 *  - **正常**：BAKE_IN + LUT 与档位匹配 + 已就绪 → LUT 与配方都进片；
 *  - **边界**：MONITOR_ONLY、强度 0 / 2 / 越界 / NaN；
 *  - **异常**：Log 档位未启用、LUT 曲线不匹配、LUT 不可用 → 一律**降级不崩溃**（bake=false + degraded）。
 */
class LogVideoLutDecisionTest {

    private val monitorOnly = LogVideoConfig(enabled = true)
    private val bakeIn = LogVideoConfig(enabled = true, lutMode = VideoLogLutMode.BAKE_IN)

    // ─────────────────── 正常路径 ───────────────────

    @Test
    fun matchingReadyLutIsBakedIntoRecording() {
        val decision = LogVideoLutDecision.decide(
            profile = VideoLogProfile.V_LOG,
            config = bakeIn,
            lutCurve = TransferCurve.VLOG,
            lutColorSpace = ColorSpace.VGamut,
            lutAvailable = true,
        )
        assertTrue(decision.bakeLutIntoRecording)
        assertTrue(decision.bakeRecipeIntoRecording)
        assertFalse(decision.degraded)
        assertEquals(1f, decision.effectiveIntensity, 1e-6f)
        assertTrue(decision.reason.isNotBlank())
    }

    @Test
    fun everyEnabledProfileAcceptsItsOwnRestoreLutTag() {
        VideoLogProfile.entries.filter { it.isEnabled }.forEach { profile ->
            val decision = LogVideoLutDecision.decide(
                profile = profile,
                config = bakeIn,
                lutCurve = profile.logCurve,
                lutColorSpace = profile.colorSpace,
                lutAvailable = true,
            )
            assertTrue("${profile.name} 应接受自家标签", decision.bakeLutIntoRecording)
            assertFalse("${profile.name} 不应降级", decision.degraded)
            assertTrue(profile.matchesLut(profile.logCurve, profile.colorSpace))
        }
    }

    // ─────────────────── 边界 ───────────────────

    @Test
    fun monitorOnlyNeverBakesLutNorRecipe() {
        val decision = LogVideoLutDecision.decide(
            profile = VideoLogProfile.V_LOG,
            config = monitorOnly,
            lutCurve = TransferCurve.VLOG,
            lutColorSpace = ColorSpace.VGamut,
            lutAvailable = true,
        )
        assertFalse(decision.bakeLutIntoRecording)
        assertFalse(decision.bakeRecipeIntoRecording)
        assertFalse("MONITOR_ONLY 是合法选择，不算降级", decision.degraded)
        assertEquals(0f, decision.effectiveIntensity, 1e-6f)
    }

    @Test
    fun intensityZeroMeansLutIdentityButRecipeStillBaked() {
        val decision = LogVideoLutDecision.decide(
            profile = VideoLogProfile.V_LOG,
            config = bakeIn,
            lutCurve = TransferCurve.VLOG,
            lutColorSpace = ColorSpace.VGamut,
            lutAvailable = true,
            requestedIntensity = 0f,
        )
        assertFalse(decision.bakeLutIntoRecording)
        assertTrue(decision.bakeRecipeIntoRecording)
        assertEquals(0f, decision.effectiveIntensity, 1e-6f)
        assertFalse(decision.degraded)
    }

    @Test
    fun intensityIsClampedAtBothEnds() {
        val low = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, TransferCurve.VLOG, ColorSpace.VGamut, true, requestedIntensity = -5f,
        )
        assertEquals(0f, low.effectiveIntensity, 1e-6f)
        assertFalse("负强度收进 0 → LUT 恒等", low.bakeLutIntoRecording)

        val high = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, TransferCurve.VLOG, ColorSpace.VGamut, true, requestedIntensity = 9f,
        )
        assertEquals(LogVideoConfig.LUT_INTENSITY_MAX, high.effectiveIntensity, 1e-6f)
        assertTrue("越界上收进 2 → 过冲（仍进片）", high.bakeLutIntoRecording)
    }

    @Test
    fun nonFiniteIntensityFallsBackToDefault() {
        val decision = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, TransferCurve.VLOG, ColorSpace.VGamut, true, requestedIntensity = Float.NaN,
        )
        assertEquals(LogVideoConfig.DEFAULT_LUT_INTENSITY, decision.effectiveIntensity, 1e-6f)
        assertTrue(decision.bakeLutIntoRecording)
    }

    @Test
    fun previewLutFollowsIntensityBoundary() {
        assertTrue(LogVideoLutDecision.shouldPreviewLut(VideoLogProfile.V_LOG, bakeIn))
        assertTrue(LogVideoLutDecision.shouldPreviewLut(VideoLogProfile.V_LOG, monitorOnly))
        assertFalse(
            "强度 0 → 预览不套 LUT",
            LogVideoLutDecision.shouldPreviewLut(VideoLogProfile.V_LOG, bakeIn.copy(lutIntensity = 0f)),
        )
        assertFalse(
            "总开关关闭 → 预览不套 LUT",
            LogVideoLutDecision.shouldPreviewLut(VideoLogProfile.V_LOG, bakeIn.copy(enabled = false)),
        )
        assertFalse(
            "非 Log 档位 → 预览不套 LUT",
            LogVideoLutDecision.shouldPreviewLut(VideoLogProfile.OFF, bakeIn),
        )
    }

    // ─────────────────── 异常 ───────────────────

    @Test
    fun disabledProfileFallsBackToPlainVideo() {
        val decision = LogVideoLutDecision.decide(
            profile = VideoLogProfile.OFF,
            config = bakeIn,
            lutCurve = TransferCurve.VLOG,
            lutColorSpace = ColorSpace.VGamut,
            lutAvailable = true,
        )
        assertFalse(decision.bakeLutIntoRecording)
        assertTrue("普通视频仍需配方（即默认全通）", decision.bakeRecipeIntoRecording)
        assertFalse(decision.degraded)
        assertEquals(0f, decision.effectiveIntensity, 1e-6f)
    }

    @Test
    fun mismatchedLutDegradesInsteadOfCrashing() {
        // 选了别的档位的还原 LUT：曲线不匹配
        val wrongCurve = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, TransferCurve.HLG, ColorSpace.BT2020, true,
        )
        assertFalse(wrongCurve.bakeLutIntoRecording)
        assertTrue(wrongCurve.degraded)
        assertTrue(wrongCurve.bakeRecipeIntoRecording)

        // 曲线对、色域错
        val wrongGamut = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, TransferCurve.VLOG, ColorSpace.BT2020, true,
        )
        assertTrue(wrongGamut.degraded)

        // 曲线信息缺失（LUT 未解析 / 自定义导入未标注）
        val missing = LogVideoLutDecision.decide(
            VideoLogProfile.V_LOG, bakeIn, null, null, true,
        )
        assertFalse(missing.bakeLutIntoRecording)
        assertTrue(missing.degraded)
    }

    @Test
    fun unavailableLutDegradesInsteadOfCrashing() {
        val decision = LogVideoLutDecision.decide(
            profile = VideoLogProfile.V_LOG,
            config = bakeIn,
            lutCurve = TransferCurve.VLOG,
            lutColorSpace = ColorSpace.VGamut,
            lutAvailable = false,
        )
        assertFalse(decision.bakeLutIntoRecording)
        assertTrue(decision.degraded)
        assertTrue(decision.reason.contains("不可用"))
    }

    @Test
    fun genericImportLutIsRejectedInLogMode() {
        // 用户导入的普通（sRGB/BT709 口味）LUT 在 Log 档位下不能当还原 LUT 用 → 降级而非误用
        val decision = LogVideoLutDecision.decide(
            VideoLogProfile.LOGC4_ARRI4, bakeIn, TransferCurve.SRGB, ColorSpace.SRGB, true,
        )
        assertFalse(decision.bakeLutIntoRecording)
        assertTrue(decision.degraded)

        // OFF 档位的 matchesLut 恒为 true（不参与过滤），其余档位必须精确匹配
        assertTrue(VideoLogProfile.OFF.matchesLut(null, null))
        assertFalse(VideoLogProfile.LOGC4_ARRI4.matchesLut(TransferCurve.SRGB, ColorSpace.SRGB))
    }
    // ─────────────────── 档位解析与推荐 ───────────────────

    @Test
    fun recommendedPresetMapsEveryProjectProfile() {
        assertEquals(LogCurvePreset.APPLE_LOG2, LogVideoLutDecision.recommendedPreset(VideoLogProfile.APPLE_LOG2))
        assertEquals(LogCurvePreset.FLOG2, LogVideoLutDecision.recommendedPreset(VideoLogProfile.FLOG2_BT2020))
        assertEquals(LogCurvePreset.V_LOG, LogVideoLutDecision.recommendedPreset(VideoLogProfile.V_LOG))
        assertEquals(LogCurvePreset.LOGC4, LogVideoLutDecision.recommendedPreset(VideoLogProfile.LOGC4_ARRI4))
        assertEquals(LogCurvePreset.LOG3G10, LogVideoLutDecision.recommendedPreset(VideoLogProfile.LOG3G10_RED))
        assertEquals(LogCurvePreset.LLOG, LogVideoLutDecision.recommendedPreset(VideoLogProfile.LLOG_BT2020))
        assertNull(LogVideoLutDecision.recommendedPreset(VideoLogProfile.OFF))
    }

    @Test
    fun resolveProfileHonoursEnabledFlagAndAvailability() {
        val available = VideoLogProfile.entries.toList()
        assertEquals(
            VideoLogProfile.OFF,
            LogVideoLutDecision.resolveProfile(bakeIn.copy(enabled = false), available),
        )
        assertEquals(
            VideoLogProfile.V_LOG,
            LogVideoLutDecision.resolveProfile(
                bakeIn.copy(curve = LogCurveRecipe(preset = LogCurvePreset.V_LOG)),
                available,
            ),
        )
        // 精确档位不在可用列表里（补丁前 HLG/LogC3 会走到这里）→ 退到列表里第一个可用 Log 档位
        val fallback = LogVideoLutDecision.resolveProfile(
            bakeIn.copy(curve = LogCurveRecipe(preset = LogCurvePreset.HLG)),
            listOf(VideoLogProfile.OFF, VideoLogProfile.V_LOG),
        )
        assertEquals(VideoLogProfile.V_LOG, fallback)
        // 相机完全不支持 Log → OFF（不崩、不报错）
        assertEquals(
            VideoLogProfile.OFF,
            LogVideoLutDecision.resolveProfile(bakeIn, listOf(VideoLogProfile.OFF)),
        )
        assertEquals(VideoLogProfile.OFF, LogVideoLutDecision.resolveProfile(bakeIn, emptyList()))
    }
}
