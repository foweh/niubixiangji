package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §13 循环录制：缓冲时长 1~60s 边界、分段联动、预算与覆盖动作。 */
class LoopRecordingBufferPolicyTest {

    @Test
    fun bufferSecondsIsClampedToOpenRange() {
        assertEquals(VideoModesRanges.LOOP_BUFFER_SECONDS_MIN, LoopRecordingBufferPolicy.normalizeBufferSeconds(0))
        assertEquals(VideoModesRanges.LOOP_BUFFER_SECONDS_MIN, LoopRecordingBufferPolicy.normalizeBufferSeconds(-5))
        assertEquals(VideoModesRanges.LOOP_BUFFER_SECONDS_MAX, LoopRecordingBufferPolicy.normalizeBufferSeconds(61))
        assertEquals(10, LoopRecordingBufferPolicy.normalizeBufferSeconds(10))
    }

    @Test
    fun segmentSecondsIsClampedToOpenRange() {
        assertEquals(VideoModesRanges.LOOP_SEGMENT_SECONDS_MIN, LoopRecordingBufferPolicy.normalizeSegmentSeconds(0))
        assertEquals(VideoModesRanges.LOOP_SEGMENT_SECONDS_MAX, LoopRecordingBufferPolicy.normalizeSegmentSeconds(99))
    }

    @Test
    fun segmentCountCoversWholeBuffer() {
        assertEquals(5, LoopRecordingBufferPolicy.segmentCount(10, 2))
        assertEquals(5, LoopRecordingBufferPolicy.segmentCount(9, 2))
        assertEquals(1, LoopRecordingBufferPolicy.segmentCount(1, 10))
    }

    @Test
    fun clampReportLinksSegmentToBuffer() {
        val (clamped, corrections) = LoopRecordingConfig(bufferSeconds = 0, segmentSeconds = 10).clampReport()
        assertEquals(1, clamped.bufferSeconds)
        assertEquals(1, clamped.segmentSeconds)
        assertEquals(2, corrections.size)
    }

    @Test
    fun planComputesDiskBudget() {
        val plan = LoopRecordingBufferPolicy.plan(LoopRecordingConfig(bufferSeconds = 10), bitrateMbps = 60)
        assertEquals(10, plan.bufferSeconds)
        assertEquals(2, plan.segmentSeconds)
        assertEquals(5, plan.segmentCount)
        // 10s × 60Mbps = 75MB，含 10% 余量 → 82.5MB
        assertEquals(82_500_000L, plan.maxBytes)
        assertTrue(plan.reason.contains("覆盖最旧分段"))
    }

    @Test
    fun planClampsBitrateBeforeBudgeting() {
        val plan = LoopRecordingBufferPolicy.plan(LoopRecordingConfig(bufferSeconds = 1), bitrateMbps = 0)
        // 1s × 1Mbps = 125000B，含 10% 余量
        assertEquals(137_500L, plan.maxBytes)
    }

    @Test
    fun bufferFullActionFollowsConfiguration() {
        assertEquals(
            LoopBufferAction.DISABLED,
            LoopRecordingBufferPolicy.onBufferFull(LoopRecordingConfig(enabled = false)),
        )
        assertEquals(
            LoopBufferAction.OVERWRITE_OLDEST,
            LoopRecordingBufferPolicy.onBufferFull(LoopRecordingConfig(enabled = true, overwriteOldest = true)),
        )
        assertEquals(
            LoopBufferAction.STOP_RECORDING,
            LoopRecordingBufferPolicy.onBufferFull(LoopRecordingConfig(enabled = true, overwriteOldest = false)),
        )
    }

    @Test
    fun evictCountsOnlyExcessSegments() {
        val plan = LoopRecordingBufferPolicy.plan(LoopRecordingConfig(bufferSeconds = 10), bitrateMbps = 60)
        assertEquals(2, LoopRecordingBufferPolicy.segmentsToEvict(storedSegmentCount = 7, plan = plan))
        assertEquals(0, LoopRecordingBufferPolicy.segmentsToEvict(storedSegmentCount = 3, plan = plan))
    }
}
