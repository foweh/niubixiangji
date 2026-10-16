package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §10 视频降噪：强度/半径/窗口 clamp、时域窗口边界、内存不足降分片。 */
class VideoDenoisePlanTest {

    @Test
    fun strengthAndRadiusAreClamped() {
        val (clamped, corrections) = VideoDenoiseConfig(enabled = true, strength = 5f, radius = 99).clampReport()
        assertEquals(VideoModesRanges.DENOISE_STRENGTH_MAX, clamped.strength, 0.0001f)
        assertEquals(VideoModesRanges.DENOISE_RADIUS_MAX, clamped.radius)
        assertEquals(2, corrections.size)
    }

    @Test
    fun temporalFramesAreClamped() {
        val low = VideoDenoiseConfig(temporalFrames = 0).clamp()
        val high = VideoDenoiseConfig(temporalFrames = 1000).clamp()
        assertEquals(VideoModesRanges.DENOISE_TEMPORAL_FRAMES_MIN, low.temporalFrames)
        assertTrue(high.temporalFrames <= VideoModesRanges.DENOISE_TEMPORAL_FRAMES_ABSOLUTE_MAX)
    }

    @Test
    fun effectiveRequiresEnabledAndNonZeroStrength() {
        assertTrue(VideoDenoiseConfig(enabled = true, strength = 0.5f).effective)
        assertTrue(!VideoDenoiseConfig(enabled = false, strength = 0.5f).effective)
        assertTrue(!VideoDenoiseConfig(enabled = true, strength = 0f).effective)
    }

    @Test
    fun windowCountCoversEveryFrame() {
        assertEquals(10, VideoDenoisePlanner.windowCount(totalFrames = 10, temporalFrames = 3))
        assertEquals(0, VideoDenoisePlanner.windowCount(totalFrames = 0, temporalFrames = 3))
    }

    @Test
    fun windowRangeShrinksAtBoundaries() {
        assertEquals(0..1, VideoDenoisePlanner.windowRange(centerIndex = 0, temporalFrames = 3, totalFrames = 10))
        assertEquals(8..9, VideoDenoisePlanner.windowRange(centerIndex = 9, temporalFrames = 3, totalFrames = 10))
        assertEquals(IntRange.EMPTY, VideoDenoisePlanner.windowRange(centerIndex = 0, temporalFrames = 3, totalFrames = 0))
    }

    @Test
    fun planSplitsIntoChunksWithoutTotalLimit() {
        val plan = VideoDenoisePlanner.plan(
            config = VideoDenoiseConfig(enabled = true, mode = VideoDenoiseMode.TEMPORAL_SPATIAL),
            totalFrames = 16,
        )
        assertEquals(16, plan.totalFrames)
        assertEquals(VideoDenoiseMode.TEMPORAL_SPATIAL, plan.mode)
        assertEquals(16, plan.chunks.sumOf { it.last - it.first + 1 })
        assertEquals(2, plan.chunks.size)
    }

    @Test
    fun memoryPressureShrinksChunkInsteadOfFailing() {
        val plan = VideoDenoisePlanner.plan(
            config = VideoDenoiseConfig(enabled = true, chunkFrames = 8),
            totalFrames = 16,
            guardMemory = { false },
            perFrameBytes = 1024L,
        )
        assertEquals(1, plan.chunkFrames)
        assertEquals(16, plan.chunks.size)
        assertEquals(16, plan.chunks.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun modeNameParsingFallsBackToTemporal() {
        assertEquals(VideoDenoiseMode.SPATIAL, VideoDenoiseMode.fromName("spatial"))
        assertEquals(VideoDenoiseMode.TEMPORAL, VideoDenoiseMode.fromName("unknown"))
        assertEquals(VideoDenoiseMode.TEMPORAL, VideoDenoiseMode.fromName(null))
    }
}
