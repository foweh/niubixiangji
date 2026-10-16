package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_STRENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3 EIS 参数强化：开放参数 clamp + **零回归**（默认值经既有 normalizer 后与原行为一致）。
 */
class EisTuningPolicyTest {

    @Test
    fun defaultsKeepExistingBehaviour() {
        val tuning = EisTuningPolicy.resolve(EisTuningConfig())
        assertEquals(DEFAULT_VIDEO_STABILIZATION_STRENGTH, tuning.engineStrength, 0.0001f)
        assertEquals(1f, tuning.cropFactor, 0.0001f)
        assertEquals(DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD, tuning.engineLookaheadFrames)
        assertEquals(DEFAULT_VIDEO_STABILIZATION_STRENGTH, tuning.defaultStrength, 0.0001f)
        assertEquals(DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD, tuning.defaultLookaheadFrames)
        assertTrue(tuning.corrections.isEmpty())
        assertFalse(tuning.strengthBeyondEngine)
        assertFalse(tuning.lookaheadBeyondEngine)
        assertEquals(7, EisTuningPolicy.NATIVE_LOOKAHEAD_DEPTH)
    }

    @Test
    fun cropFactorIsClampedToOpenRange() {
        val high = EisTuningConfig(cropFactor = 9f).clamp()
        val low = EisTuningConfig(cropFactor = -3f).clamp()
        assertEquals(VideoModesRanges.EIS_CROP_FACTOR_MAX, high.cropFactor, 0.0001f)
        assertEquals(VideoModesRanges.EIS_CROP_FACTOR_MIN, low.cropFactor, 0.0001f)
    }

    @Test
    fun strengthAndLookaheadAreClamped() {
        val (clamped, corrections) = EisTuningConfig(strength = 5f, lookaheadFrames = 1000).clampReport()
        assertTrue(clamped.strength <= VideoModesRanges.EIS_STRENGTH_MAX)
        assertTrue(clamped.strength >= VideoModesRanges.EIS_STRENGTH_MIN)
        assertTrue(clamped.lookaheadFrames <= VideoModesRanges.EIS_LOOKAHEAD_ABSOLUTE_MAX)
        assertTrue(clamped.lookaheadFrames >= VideoModesRanges.EIS_LOOKAHEAD_MIN)
        assertEquals(2, corrections.size)
    }

    @Test
    fun engineCapsAreReportedWithoutLosingUserIntent() {
        val tuning = EisTuningPolicy.resolve(EisTuningConfig(strength = 2f, lookaheadFrames = 64))
        assertEquals(EisTuningPolicy.engineStrengthMax(), tuning.engineStrength, 0.0001f)
        assertEquals(EisTuningPolicy.engineLookaheadMax(), tuning.engineLookaheadFrames)
        assertEquals(2f, tuning.requestedStrength, 0.0001f)
        assertTrue(tuning.strengthBeyondEngine)
        assertTrue(tuning.lookaheadBeyondEngine)
        // 裁剪系数不参与引擎计算，只做输出缩放，故应与请求值一致。
        assertEquals(1f, tuning.effectiveZoomRatio, 0.0001f)
    }

    @Test
    fun disabledKeepsParametersForNextEnable() {
        val tuning = EisTuningPolicy.resolve(EisTuningConfig(enabled = false, strength = 0.5f))
        assertFalse(tuning.enabled)
        assertEquals(0.5f, tuning.engineStrength, 0.0001f)
    }
}
