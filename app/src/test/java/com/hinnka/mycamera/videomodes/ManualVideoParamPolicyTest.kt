package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §7 手动参数视频档：快门/ISO/色温/对焦 clamp、自动哨兵与录制锁定。 */
class ManualVideoParamPolicyTest {

    private val fullProbe = ManualVideoProbe(
        shutterRangeNs = 1_000_000L..100_000_000L,
        isoRange = 100..3200,
        awbRangeK = IntRange.EMPTY,
        maxFocusDiopters = 10f,
        manualExposureSupported = true,
        lockSupported = true,
    )

    @Test
    fun unavailableManualSupportFallsBackToAuto() {
        val resolved = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(shutterSpeedNs = 20_000_000L, iso = 1600),
            ManualVideoProbe(manualExposureSupported = false, lockSupported = false),
        )
        assertEquals(VideoModesRanges.MANUAL_SHUTTER_NS_AUTO, resolved.shutterSpeedNs)
        assertEquals(VideoModesRanges.MANUAL_ISO_AUTO, resolved.iso)
        assertTrue(resolved.isShutterAuto)
        assertTrue(resolved.isIsoAuto)
        assertFalse(resolved.manualEnabled)
    }

    @Test
    fun defaultsRemainAuto() {
        val resolved = ManualVideoParamPolicy.resolve(ManualVideoParamsConfig(), fullProbe)
        assertEquals(VideoModesRanges.MANUAL_SHUTTER_NS_AUTO, resolved.shutterSpeedNs)
        assertEquals(VideoModesRanges.MANUAL_ISO_AUTO, resolved.iso)
        assertEquals(VideoModesRanges.MANUAL_AWB_K_AUTO, resolved.awbTemperatureK)
        assertTrue(resolved.isFocusAuto)
        assertTrue(resolved.corrections.isEmpty())
    }

    @Test
    fun manualValuesAreClampedToSensorRange() {
        val resolved = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(
                shutterSpeedNs = 500_000_000L,
                iso = 6400,
                awbTemperatureK = 5000,
                focusDistanceDiopters = 20f,
            ),
            fullProbe,
        )
        assertEquals(100_000_000L, resolved.shutterSpeedNs)
        assertEquals(3200, resolved.iso)
        assertEquals(5000, resolved.awbTemperatureK)
        assertEquals(10f, resolved.focusDistanceDiopters, 0.0001f)
        assertTrue(resolved.corrections.size >= 3)
    }

    @Test
    fun temperatureRangeFromSensorIsApplied() {
        val resolved = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(awbTemperatureK = 9000),
            fullProbe.copy(awbRangeK = 2500..6500),
        )
        assertEquals(6500, resolved.awbTemperatureK)
        assertFalse(resolved.isAwbAuto)
    }

    @Test
    fun locksOnlyApplyWhenHalSupportsThem() {
        val enabled = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(lockFocus = true, lockExposure = true, lockWhiteBalance = true),
            fullProbe,
        )
        assertTrue(enabled.lockEffective)
        assertTrue(enabled.lockFocus)
        assertTrue(enabled.lockExposure)
        assertTrue(enabled.lockWhiteBalance)

        val unsupported = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(lockFocus = true),
            fullProbe.copy(lockSupported = false),
        )
        assertFalse(unsupported.lockEffective)
        assertFalse(unsupported.lockFocus)
        assertTrue(unsupported.corrections.any { it.contains("录制锁定不可用") })
    }

    @Test
    fun fixedFocusLensStaysAuto() {
        val resolved = ManualVideoParamPolicy.resolve(
            ManualVideoParamsConfig(focusDistanceDiopters = 5f),
            fullProbe.copy(maxFocusDiopters = 0f),
        )
        assertTrue(resolved.isFocusAuto)
        assertEquals(VideoModesRanges.MANUAL_FOCUS_DIOPTER_AUTO, resolved.focusDistanceDiopters, 0.0001f)
    }
}
