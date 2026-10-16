package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §11 低光 / 夜景视频：能力门控、ISO/快门按传感器范围夹取、时域降噪档位复用。 */
class NightVideoPolicyTest {

    @Test
    fun disabledIsInactive() {
        val decision = NightVideoPolicy.resolve(NightVideoConfig(enabled = false), NightVideoCapability())
        assertFalse(decision.active)
        assertEquals(0f, decision.gainBoostEv, 0.0001f)
        assertFalse(decision.denoiseConfig.enabled)
        assertTrue(decision.reason.contains("未开启"))
    }

    @Test
    fun unsupportedDeviceHidesFeature() {
        val decision = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true),
            NightVideoCapability(
                supportsManualIso = false,
                supportsManualShutter = false,
                supportsTemporalDenoise = false,
            ),
        )
        assertFalse(decision.active)
        assertTrue(decision.reason.contains("自动隐藏"))
    }

    @Test
    fun softwareTemporalDenoiseAloneEnablesFeature() {
        val decision = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true),
            NightVideoCapability(supportsTemporalDenoise = true),
        )
        assertTrue(decision.active)
        assertTrue(decision.denoiseConfig.enabled)
        assertEquals(VideoDenoiseMode.TEMPORAL, decision.denoiseConfig.mode)
        // 不支持手动 ISO/快门 → 不介入，交给 AE。
        assertEquals(0, decision.targetIso)
        assertEquals(0L, decision.maxShutterNs)
    }

    @Test
    fun manualIsoIsClampedToSensorRange() {
        val decision = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true, maxIso = 12_800),
            NightVideoCapability(
                supportsManualIso = true,
                isoRange = 100..3200,
                supportsTemporalDenoise = true,
            ),
        )
        assertEquals(3200, decision.targetIso)
    }

    @Test
    fun shutterCapIsOnlyAppliedWhenAllowedAndSupported() {
        val supported = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true, allowLongShutter = true),
            NightVideoCapability(
                supportsManualShutter = true,
                shutterRangeNs = 1_000_000L..100_000_000L,
                supportsTemporalDenoise = true,
            ),
        )
        assertEquals(VideoModesRanges.DEFAULT_NIGHT_MAX_SHUTTER_NS, supported.maxShutterNs)

        val notAllowed = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true, allowLongShutter = false),
            NightVideoCapability(
                supportsManualShutter = true,
                shutterRangeNs = 1_000_000L..100_000_000L,
                supportsTemporalDenoise = true,
            ),
        )
        assertEquals(0L, notAllowed.maxShutterNs)
    }

    @Test
    fun zeroTemporalStrengthDisablesOnlyDenoise() {
        val decision = NightVideoPolicy.resolve(
            NightVideoConfig(enabled = true, temporalStrength = 0f),
            NightVideoCapability(supportsTemporalDenoise = true),
        )
        assertTrue(decision.active)
        assertFalse(decision.denoiseConfig.enabled)
    }

    @Test
    fun paramsAreClampedToOpenRange() {
        val (clamped, corrections) = NightVideoConfig(
            gainBoostEv = 99f,
            temporalStrength = 5f,
            maxIso = 1,
        ).clampReport()
        assertEquals(VideoModesRanges.NIGHT_GAIN_BOOST_EV_MAX, clamped.gainBoostEv, 0.0001f)
        assertEquals(VideoModesRanges.NIGHT_TEMPORAL_STRENGTH_MAX, clamped.temporalStrength, 0.0001f)
        assertTrue(clamped.maxIso >= VideoModesRanges.NIGHT_MAX_ISO_MIN)
        assertEquals(3, corrections.size)
    }
}
