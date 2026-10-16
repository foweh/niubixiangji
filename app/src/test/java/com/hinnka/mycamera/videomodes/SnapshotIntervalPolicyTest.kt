package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §12 边录边拍快照：抓帧间隔 100~600000ms 边界、背压丢帧、异常时钟与输出上限。 */
class SnapshotIntervalPolicyTest {

    @Test
    fun intervalIsClampedToOpenRange() {
        assertEquals(VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN, SnapshotIntervalPolicy.normalizeIntervalMs(0L))
        assertEquals(VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN, SnapshotIntervalPolicy.normalizeIntervalMs(-1L))
        assertEquals(VideoModesRanges.SNAPSHOT_INTERVAL_MS_MAX, SnapshotIntervalPolicy.normalizeIntervalMs(700_000L))
        assertEquals(1000L, SnapshotIntervalPolicy.normalizeIntervalMs(1000L))
    }

    @Test
    fun disabledNeverGrabs() {
        val decision = SnapshotIntervalPolicy.resolve(
            SnapshotDuringRecordingConfig(enabled = false),
            elapsedMs = 10_000L,
            lastGrabElapsedMs = SnapshotIntervalPolicy.NO_GRAB_YET,
        )
        assertFalse(decision.shouldGrab)
        assertTrue(decision.reason.contains("未开启"))
    }

    @Test
    fun firstGrabHappensImmediatelyThenRespectsInterval() {
        val config = SnapshotDuringRecordingConfig(enabled = true, intervalMs = 1000L)
        assertTrue(
            SnapshotIntervalPolicy.shouldGrab(
                config,
                elapsedMs = 0L,
                lastGrabElapsedMs = SnapshotIntervalPolicy.NO_GRAB_YET,
            ),
        )
        assertFalse(SnapshotIntervalPolicy.shouldGrab(config, elapsedMs = 999L, lastGrabElapsedMs = 0L))
        assertTrue(SnapshotIntervalPolicy.shouldGrab(config, elapsedMs = 1000L, lastGrabElapsedMs = 0L))
    }

    @Test
    fun backpressureDropsFrameWithoutBlocking() {
        val config = SnapshotDuringRecordingConfig(enabled = true, intervalMs = 1000L, maxPending = 3)
        val decision = SnapshotIntervalPolicy.resolve(
            config,
            elapsedMs = 5000L,
            lastGrabElapsedMs = 4000L,
            pendingCount = 3,
        )
        assertFalse(decision.shouldGrab)
        assertTrue(decision.droppedByBackpressure)
        assertTrue(decision.reason.contains("排队已满"))
    }

    @Test
    fun clockRollbackDoesNotBurstGrab() {
        val config = SnapshotDuringRecordingConfig(enabled = true, intervalMs = 1000L)
        val decision = SnapshotIntervalPolicy.resolve(
            config,
            elapsedMs = 100L,
            lastGrabElapsedMs = 5000L,
        )
        assertFalse(decision.shouldGrab)
        assertFalse(decision.droppedByBackpressure)
        assertTrue(decision.reason.contains("时钟回拨"))
    }

    @Test
    fun negativeElapsedIsTreatedAsZero() {
        val config = SnapshotDuringRecordingConfig(enabled = true, intervalMs = 1000L)
        assertTrue(
            SnapshotIntervalPolicy.shouldGrab(
                config,
                elapsedMs = -500L,
                lastGrabElapsedMs = SnapshotIntervalPolicy.NO_GRAB_YET,
            ),
        )
    }

    @Test
    fun configClampAppliesAllOpenRanges() {
        val (clamped, corrections) = SnapshotDuringRecordingConfig(
            enabled = true,
            intervalMs = 1L,
            jpegQuality = 10,
            maxPending = 0,
            maxLongEdge = 100,
        ).clampReport()
        assertEquals(VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN, clamped.intervalMs)
        assertEquals(VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MIN, clamped.jpegQuality)
        assertEquals(VideoModesRanges.SNAPSHOT_MAX_PENDING_MIN, clamped.maxPending)
        assertEquals(SNAPSHOT_LONG_EDGE_MIN, clamped.maxLongEdge)
        assertEquals(4, corrections.size)

        val high = SnapshotDuringRecordingConfig(jpegQuality = 200, maxLongEdge = 99_999).clamp()
        assertEquals(VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MAX, high.jpegQuality)
        assertEquals(SNAPSHOT_LONG_EDGE_MAX, high.maxLongEdge)
    }

    @Test
    fun estimatedCountNeverAssumesLimits() {
        val config = SnapshotDuringRecordingConfig(enabled = true, intervalMs = 1000L)
        assertEquals(10, SnapshotIntervalPolicy.estimatedSnapshotCount(config, recordingDurationMs = 10_000L))
        assertEquals(0, SnapshotIntervalPolicy.estimatedSnapshotCount(config, recordingDurationMs = 0L))
        assertEquals(
            0,
            SnapshotIntervalPolicy.estimatedSnapshotCount(
                SnapshotDuringRecordingConfig(enabled = false),
                recordingDurationMs = 10_000L,
            ),
        )
    }
}
