package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §1 高帧率 / 慢动作：能力门控、档位开放与隐藏、慢放速度 clamp。 */
class VideoFpsCapabilityTest {

    @Test
    fun advertisedTiersUnlockHighFrameRates() {
        val capability = FpsCapabilityResolver.resolve(
            advertisedRanges = listOf(30..30, 120..120, 240..240),
            minFrameDurationNs = 4_166_666L,
            requestedFps = 120,
        )
        assertEquals(listOf(24, 30, 48, 60, 120, 240), capability.availableFps)
        assertEquals(240, capability.maxAvailableFps)
        assertTrue(capability.supportsHighFrameRate)
        assertEquals(120, capability.recommendedFps)
    }

    @Test
    fun unsupportedTiersAreHiddenWithReason() {
        val capability = FpsCapabilityResolver.resolve(
            advertisedRanges = listOf(30..30),
            minFrameDurationNs = 16_666_666L,
            requestedFps = 240,
        )
        assertEquals(listOf(120, 240), capability.hiddenTiers.map { it.fps })
        assertTrue(capability.hiddenTiers.all { it.reason.contains("最小帧间隔") })
        assertTrue(capability.availableFps.containsAll(listOf(24, 30, 48, 60)))
        assertFalse(capability.supportsHighFrameRate)
        // 请求档位不可用 → 向下取最高可用档位（不会强锁不支持的 240）。
        assertEquals(60, capability.recommendedFps)
    }

    @Test
    fun missingMetadataOnlyOpensOptimisticTiers() {
        val capability = FpsCapabilityResolver.resolve(
            advertisedRanges = emptyList(),
            minFrameDurationNs = 0L,
            requestedFps = 30,
        )
        assertEquals(listOf(24, 30, 48, 60), capability.availableFps)
        assertEquals(listOf(120, 240), capability.hiddenTiers.map { it.fps })
    }

    @Test
    fun slowMotionSpeedIsClampedAndLaddered() {
        assertEquals(VideoModesRanges.SLOW_MOTION_SPEED_MIN, SlowMotionPolicy.normalizeSpeed(0.01f), 0.0001f)
        assertEquals(
            VideoModesRanges.DEFAULT_SLOW_MOTION_SPEED,
            SlowMotionPolicy.normalizeSpeed(Float.NaN),
            0.0001f,
        )
        assertEquals(1f, SlowMotionPolicy.stepSpeed(1f, forward = true), 0.0001f)
        assertEquals(0.5f, SlowMotionPolicy.stepSpeed(1f, forward = false), 0.0001f)
    }

    @Test
    fun slowMotionRequiresEnoughCaptureFps() {
        assertEquals(60, SlowMotionPolicy.requiredCaptureFps(playbackFps = 30, speed = 0.5f))
        assertTrue(SlowMotionPolicy.isSpeedSupported(captureFps = 120, playbackFps = 30, speed = 0.25f))
        assertFalse(SlowMotionPolicy.isSpeedSupported(captureFps = 60, playbackFps = 30, speed = 0.25f))
        // 60fps 拍摄下 0.5x 可用、0.25x 自动隐藏，1x 永远可用。
        assertEquals(listOf(0.5f, 1f), SlowMotionPolicy.availableSpeeds(captureFps = 60, playbackFps = 30))
    }

    @Test
    fun playbackDurationRespectsSpeed() {
        assertEquals(2000L, SlowMotionPolicy.playbackDurationMs(captureDurationMs = 1000L, speed = 0.5f))
        assertEquals(0L, SlowMotionPolicy.playbackDurationMs(captureDurationMs = 0L, speed = 0.5f))
    }

    @Test
    fun fpsModeConfigClampRejectsOutOfRangeValues() {
        val (clamped, corrections) = FpsModeConfig(targetFps = 1000, slowMotionSpeed = 0.01f).clampReport()
        assertEquals(VideoModesRanges.FPS_MAX, clamped.targetFps)
        assertEquals(VideoModesRanges.SLOW_MOTION_SPEED_MIN, clamped.slowMotionSpeed, 0.0001f)
        assertEquals(2, corrections.size)
    }

    @Test
    fun playbackFeasibilityReportsInconsistentCombination() {
        assertFalse(FpsModeConfig(targetFps = 30, slowMotionEnabled = true, slowMotionSpeed = 0.5f).isPlaybackFeasible())
        assertTrue(FpsModeConfig(targetFps = 60, slowMotionEnabled = true, slowMotionSpeed = 0.5f).isPlaybackFeasible())
        assertTrue(FpsModeConfig(targetFps = 24, slowMotionEnabled = false, slowMotionSpeed = 0.25f).isPlaybackFeasible())
    }
}
