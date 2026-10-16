package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §5 视频超分：倍率/步长 clamp、抽帧计数、内存不足时「降档不失败」。 */
class VideoSuperResolutionPlanTest {

    @Test
    fun scaleIsClampedToOpenRange() {
        val low = VideoSrConfig(scale = 0.5f).clamp()
        val high = VideoSrConfig(scale = 99f).clamp()
        assertEquals(VideoModesRanges.SR_SCALE_MIN, low.scale, 0.0001f)
        assertTrue(high.scale <= VideoModesRanges.SR_SCALE_ABSOLUTE_MAX)
    }

    @Test
    fun frameStrideAndChunkFramesAreClamped() {
        val (clamped, corrections) = VideoSrConfig(frameStride = 0, chunkFrames = 0).clampReport()
        assertEquals(VideoModesRanges.SR_FRAME_STRIDE_MIN, clamped.frameStride)
        assertEquals(VideoModesRanges.SR_CHUNK_FRAMES_MIN, clamped.chunkFrames)
        assertEquals(2, corrections.size)
    }

    @Test
    fun processedFrameCountFollowsStride() {
        assertEquals(5, VideoSuperResolutionPlanner.processedFrameCount(totalFrames = 10, frameStride = 2))
        assertEquals(10, VideoSuperResolutionPlanner.processedFrameCount(totalFrames = 10, frameStride = 0))
        assertEquals(0, VideoSuperResolutionPlanner.processedFrameCount(totalFrames = 0, frameStride = 2))
        assertEquals(6, VideoSuperResolutionPlanner.sourceFrameIndex(processedIndex = 3, frameStride = 2))
    }

    @Test
    fun planSplitsChunksWithoutTotalFrameLimit() {
        val plan = VideoSuperResolutionPlanner.plan(
            config = VideoSrConfig(enabled = true, scale = 2f, frameStride = 1),
            totalFrames = 100,
        )
        assertEquals(100, plan.processedFrames)
        assertEquals(2f, plan.scale, 0.0001f)
        assertEquals(8, plan.chunkFrames)
        assertEquals(13, plan.chunks.size)
        assertEquals(100, plan.chunks.sumOf { it.last - it.first + 1 })
        assertTrue(plan.degradations.isEmpty())
    }

    @Test
    fun strideReducesProcessedFramesButKeepsAllChunksCovered() {
        val plan = VideoSuperResolutionPlanner.plan(
            config = VideoSrConfig(enabled = true, frameStride = 4),
            totalFrames = 100,
        )
        assertEquals(25, plan.processedFrames)
        assertEquals(25, plan.chunks.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun memoryPressureDegradesInsteadOfFailing() {
        val plan = VideoSuperResolutionPlanner.plan(
            config = VideoSrConfig(enabled = true, scale = 4f, chunkFrames = 8),
            totalFrames = 100,
            guardMemory = { false },
            perFrameBytes = 1024L,
        )
        // 内存永远不足 → 逐级降倍率到阶梯最低档，再逐级降分片到 1 帧，任务仍然继续。
        assertEquals(VideoSuperResolutionPlanner.SCALE_LADDER.last(), plan.scale, 0.0001f)
        assertEquals(1, plan.chunkFrames)
        assertEquals(100, plan.chunks.size)
        assertTrue(plan.degradations.isNotEmpty())
    }

    @Test
    fun maxScaleIsConfigurableWithinOverflowGuard() {
        val original = VideoSrLimits.userMaxScale
        try {
            VideoSrLimits.userMaxScale = 3f
            assertEquals(3f, VideoSrLimits.maxScale(), 0.0001f)
            VideoSrLimits.userMaxScale = 99f
            assertEquals(VideoSrLimits.ABSOLUTE_MAX_SCALE, VideoSrLimits.maxScale(), 0.0001f)
        } finally {
            VideoSrLimits.userMaxScale = original
        }
    }
}
