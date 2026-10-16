package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §15 防闪烁（Camera2 anti-banding）：档位映射、未上报时的保守行为、自动回退。
 *
 * 取值事实：`CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF/_50HZ/_60HZ/_AUTO` = 0/1/2/3。
 */
class AntiFlickerPolicyTest {

    @Test
    fun metadataValuesMatchCamera2Constants() {
        assertEquals(0, AntiFlickerMode.OFF.metadataValue)
        assertEquals(1, AntiFlickerMode.HZ_50.metadataValue)
        assertEquals(2, AntiFlickerMode.HZ_60.metadataValue)
        assertEquals(3, AntiFlickerMode.AUTO.metadataValue)
        assertEquals(AntiFlickerMode.HZ_50, AntiFlickerMode.fromMetadata(1))
        assertEquals(AntiFlickerMode.AUTO, AntiFlickerMode.fromName("auto"))
        assertEquals(AntiFlickerMode.AUTO, AntiFlickerMode.fromName("不存在的档位"))
    }

    @Test
    fun unavailableMetadataKeepsHalDefault() {
        val decision = AntiFlickerPolicy.resolve(AntiFlickerConfig(), emptySet())
        assertFalse(decision.applyToRequest)
        assertEquals(AntiFlickerMode.AUTO, decision.mode)
        assertTrue(decision.reason.contains("未上报"))
    }

    @Test
    fun requestedModeIsAppliedWhenAdvertised() {
        val decision = AntiFlickerPolicy.resolve(
            AntiFlickerConfig(mode = AntiFlickerMode.HZ_50),
            setOf(0, 1, 2, 3),
        )
        assertTrue(decision.applyToRequest)
        assertEquals(AntiFlickerMode.HZ_50, decision.mode)
    }

    @Test
    fun autoModeOnlyWritesWhenHalOffersAuto() {
        assertTrue(
            AntiFlickerPolicy.resolve(AntiFlickerConfig(), setOf(3)).applyToRequest,
        )
        assertFalse(
            AntiFlickerPolicy.resolve(AntiFlickerConfig(), setOf(1, 2)).applyToRequest,
        )
    }

    @Test
    fun unsupportedRequestFallsBackWithinSameGrid() {
        val decision = AntiFlickerPolicy.resolve(
            AntiFlickerConfig(mode = AntiFlickerMode.HZ_50, use50HzGrid = false),
            setOf(0, 2),
        )
        assertEquals(AntiFlickerMode.HZ_60, decision.mode)
        assertTrue(decision.applyToRequest)
        assertTrue(decision.reason.contains("已回退"))
    }

    @Test
    fun unsupportedRequestFallsBackToAutoWhenNoGridOption() {
        val decision = AntiFlickerPolicy.resolve(
            AntiFlickerConfig(mode = AntiFlickerMode.HZ_50),
            setOf(3),
        )
        assertEquals(AntiFlickerMode.AUTO, decision.mode)
        assertTrue(decision.applyToRequest)
    }

    @Test
    fun autoFallbackCanBeDisabled() {
        val decision = AntiFlickerPolicy.resolve(
            AntiFlickerConfig(mode = AntiFlickerMode.HZ_60, resolutionAutoFallback = false),
            setOf(3),
        )
        assertFalse(decision.applyToRequest)
        assertTrue(decision.reason.contains("未开启自动回退"))
    }

    @Test
    fun rawModesAreNormalized() {
        assertEquals(setOf(0, 1), AntiFlickerPolicy.normalizeAvailableModes(intArrayOf(0, 1)))
        assertTrue(AntiFlickerPolicy.normalizeAvailableModes(null).isEmpty())
        assertEquals(AntiFlickerMode.HZ_50, AntiFlickerMode.autoDefaultForRegion(use50HzGrid = true))
        assertEquals(AntiFlickerMode.HZ_60, AntiFlickerMode.autoDefaultForRegion(use50HzGrid = false))
    }
}
