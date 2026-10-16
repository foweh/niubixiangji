package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §2 延时摄影：参数 clamp、调度边界、分片与「不设总量硬上限」。 */
class TimelapsePlannerTest {

    @Test
    fun configClampAppliesOpenRange() {
        val (clamped, corrections) = TimelapseConfig(
            intervalMs = 100L,
            totalFrames = 1,
            outputFps = 0,
        ).clampReport()
        assertEquals(VideoModesRanges.TIMELAPSE_INTERVAL_MS_MIN, clamped.intervalMs)
        assertEquals(VideoModesRanges.TIMELAPSE_FRAMES_MIN, clamped.totalFrames)
        assertEquals(VideoModesRanges.TIMELAPSE_OUTPUT_FPS_MIN, clamped.outputFps)
        assertEquals(3, corrections.size)
    }

    @Test
    fun intervalUpperBoundIsClamped() {
        val clamped = TimelapseConfig(intervalMs = 9_999_999L).clamp()
        assertEquals(VideoModesRanges.TIMELAPSE_INTERVAL_MS_MAX, clamped.intervalMs)
    }

    @Test
    fun planComputesShootingAndPlaybackDuration() {
        val plan = TimelapsePlanner.plan(
            TimelapseConfig(intervalMs = 1000L, totalFrames = 300, outputFps = 30),
        )
        assertEquals(299_000L, plan.shootingDurationMs)
        assertEquals(10_000L, plan.playbackDurationMs)
        assertTrue(plan.corrections.isEmpty())
    }

    @Test
    fun singleFrameHasNoShootingDuration() {
        assertEquals(0L, TimelapsePlanner.shootingDurationMs(TimelapseConfig(totalFrames = 1)))
        assertEquals(0L, TimelapsePlanner.playbackDurationMs(TimelapseConfig(totalFrames = 0)))
    }

    @Test
    fun frameTimestampClampsIndexToValidRange() {
        val config = TimelapseConfig(intervalMs = 1000L, totalFrames = 10)
        assertEquals(0L, TimelapsePlanner.frameTimestampMs(config, -5))
        assertEquals(9000L, TimelapsePlanner.frameTimestampMs(config, 99))
    }

    @Test
    fun shouldCaptureRespectsInterval() {
        val config = TimelapseConfig(intervalMs = 1000L, totalFrames = 100)
        assertTrue(
            TimelapsePlanner.shouldCaptureAt(
                config,
                elapsedMs = 0L,
                lastCaptureElapsedMs = TimelapsePlanner.NO_CAPTURE_YET,
            ),
        )
        assertFalse(TimelapsePlanner.shouldCaptureAt(config, elapsedMs = 999L, lastCaptureElapsedMs = 0L))
        assertTrue(TimelapsePlanner.shouldCaptureAt(config, elapsedMs = 1000L, lastCaptureElapsedMs = 0L))
    }

    @Test
    fun shouldCaptureIgnoresClockRollback() {
        val config = TimelapseConfig(intervalMs = 1000L, totalFrames = 100)
        assertFalse(TimelapsePlanner.shouldCaptureAt(config, elapsedMs = 500L, lastCaptureElapsedMs = 5_000L))
        assertFalse(TimelapsePlanner.shouldCaptureAt(config, elapsedMs = -1L, lastCaptureElapsedMs = 0L))
    }

    @Test
    fun captureBatchesSplitWithoutTotalFrameLimit() {
        assertEquals(emptyList<IntRange>(), TimelapsePlanner.captureBatches(totalFrames = 0, batchSize = 8))
        assertEquals(
            listOf(0..2, 3..5, 6..8, 9..9),
            TimelapsePlanner.captureBatches(totalFrames = 10, batchSize = 3),
        )
        // 非法批大小退化为单帧批，而不是失败。
        assertEquals(3, TimelapsePlanner.captureBatches(totalFrames = 3, batchSize = 0).size)
        val huge = TimelapsePlanner.captureBatches(totalFrames = 100_000, batchSize = 8)
        assertEquals(100_000, huge.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun maxFramesIsConfigurableButOverflowGuarded() {
        val original = TimelapseLimits.userMaxFrames
        try {
            TimelapseLimits.userMaxFrames = 5000
            assertEquals(5000, TimelapseLimits.maxFrames())

            TimelapseLimits.userMaxFrames = -1
            assertEquals(VideoModesRanges.TIMELAPSE_FRAMES_MIN, TimelapseLimits.maxFrames())

            TimelapseLimits.userMaxFrames = Int.MAX_VALUE
            assertEquals(TimelapseLimits.ABSOLUTE_MAX_FRAMES, TimelapseLimits.maxFrames())
        } finally {
            TimelapseLimits.userMaxFrames = original
        }
    }
}
