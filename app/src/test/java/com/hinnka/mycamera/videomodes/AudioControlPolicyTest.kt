package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §8 音频控制：增益 0~300% clamp、静音、麦克风来源降级。 */
class AudioControlPolicyTest {

    @Test
    fun gainPercentIsClampedToOpenRange() {
        assertEquals(0, AudioControlPolicy.clampGainPercent(-50))
        assertEquals(VideoModesRanges.AUDIO_GAIN_PERCENT_MAX, AudioControlPolicy.clampGainPercent(301))
        assertEquals(VideoModesRanges.AUDIO_GAIN_PERCENT_MAX, AudioControlPolicy.clampGainPercent(300))
        assertEquals(100, AudioControlPolicy.clampGainPercent(100))
    }

    @Test
    fun gainLinearFollowsPercent() {
        assertEquals(1f, AudioControlPolicy.gainLinear(100), 0.0001f)
        assertEquals(2f, AudioControlPolicy.gainLinear(200), 0.0001f)
        assertEquals(0f, AudioControlPolicy.gainLinear(-10), 0.0001f)
    }

    @Test
    fun clampReportFlagsOutOfRangeGain() {
        val (clamped, corrections) = AudioControlConfig(gainPercent = 1000).clampReport()
        assertEquals(VideoModesRanges.AUDIO_GAIN_PERCENT_MAX, clamped.gainPercent)
        assertEquals(1, corrections.size)
    }

    @Test
    fun resolveAppliesBoost() {
        val resolved = AudioControlPolicy.resolve(AudioControlConfig(gainPercent = 150), emptySet())
        assertEquals(150, resolved.gainPercent)
        assertEquals(1.5f, resolved.effectiveGainLinear, 0.0001f)
        assertFalse(resolved.muted)
        assertFalse(resolved.degradedToAuto)
    }

    @Test
    fun mutedZeroesEffectiveGainButKeepsConfiguredValue() {
        val resolved = AudioControlPolicy.resolve(
            AudioControlConfig(gainPercent = 300, muted = true),
            emptySet(),
        )
        assertEquals(3f, resolved.gainLinear, 0.0001f)
        assertEquals(0f, resolved.effectiveGainLinear, 0.0001f)
        assertEquals(300, resolved.gainPercent)
        assertTrue(resolved.muted)
    }

    @Test
    fun missingMicrophoneDegradesToAuto() {
        val resolved = AudioControlPolicy.resolve(
            AudioControlConfig(inputId = "type:address:name"),
            setOf("other-device"),
        )
        assertEquals(AudioControlPolicy.AUTO_INPUT_ID, resolved.inputId)
        assertTrue(resolved.degradedToAuto)
    }

    @Test
    fun unknownDeviceListKeepsUserSelection() {
        val resolved = AudioControlPolicy.resolve(
            AudioControlConfig(inputId = "type:address:name"),
            emptySet(),
        )
        assertEquals("type:address:name", resolved.inputId)
        assertFalse(resolved.degradedToAuto)
    }
}
