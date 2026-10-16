package com.hinnka.mycamera.funshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视频抽帧参数边界：正常 / 边界 / 异常（任务书要求的「视频抽帧参数边界」覆盖）。 */
class GifVideoFramePlanTest {

    private fun config(
        frameRate: Int = 12,
        startMs: Long = 0L,
        endMs: Long = 0L,
        targetWidth: Int = 480,
        maxFrames: Int = 120,
        holdLastFrameMs: Int = 0,
    ) = GifConfig(
        videoFrameRate = frameRate,
        videoStartMs = startMs,
        videoEndMs = endMs,
        videoTargetWidth = targetWidth,
        videoMaxFrames = maxFrames,
        holdLastFrameMs = holdLastFrameMs,
    )

    // ------------------------------------------------------------------ 正常
    @Test
    fun normalPlanSpacesFramesAcrossWholeVideo() {
        val plan = FunVideoFrameSampler.plan(1920, 1080, 5_000L, config())
        assertNull(plan.error)
        assertEquals(61, plan.size)
        assertEquals(0L, plan.frames.first().timestampMs)
        assertEquals(5_000L, plan.frames.last().timestampMs)
        assertEquals(480, plan.frames.first().outWidth)
        assertEquals(270, plan.frames.first().outHeight)
        assertEquals(8, plan.frames.first().delayCentis)
        assertEquals(8, plan.frames.last().delayCentis)
        assertMonotonic(plan)
    }

    @Test
    fun frameCountIsEvenlyResampledWhenExceedingMaxFrames() {
        val plan = FunVideoFrameSampler.plan(1920, 1080, 5_000L, config(maxFrames = 10))
        assertNull(plan.error)
        assertEquals(10, plan.size)
        assertEquals(0L, plan.frames.first().timestampMs)
        assertEquals(5_000L, plan.frames.last().timestampMs)
        assertEquals(56, plan.frames.first().delayCentis)
        assertMonotonic(plan)
    }

    // ------------------------------------------------------------------ 边界
    @Test
    fun startAndEndBoundariesAreRespected() {
        val plan = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(startMs = 2_000L, endMs = 4_000L))
        assertNull(plan.error)
        assertEquals(25, plan.size)
        assertEquals(2_000L, plan.frames.first().timestampMs)
        assertEquals(4_000L, plan.frames.last().timestampMs)
        for (frame in plan.frames) {
            assertTrue(frame.timestampMs in 2_000L..4_000L)
        }
    }

    @Test
    fun zeroOrOversizedEndMeansVideoTail() {
        val fromZero = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(endMs = 0L))
        assertEquals(5_000L, fromZero.frames.last().timestampMs)

        val oversized = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(endMs = 999_999L))
        assertEquals(5_000L, oversized.frames.last().timestampMs)
        assertEquals(fromZero.size, oversized.size)
    }

    @Test
    fun reversedRangeDegradesToSingleFrame() {
        val plan = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(startMs = 3_000L, endMs = 2_000L))
        assertNull(plan.error)
        assertEquals(1, plan.size)
        assertEquals(3_000L, plan.frames.first().timestampMs)
        assertEquals(2, plan.frames.first().delayCentis)
    }

    @Test
    fun singleFrameRangeKeepsMinimumDelay() {
        val plan = FunVideoFrameSampler.plan(1280, 720, 100L, config(frameRate = 50))
        assertNull(plan.error)
        assertTrue(plan.size >= 1)
        for (frame in plan.frames) {
            assertTrue("delay ${frame.delayCentis}", frame.delayCentis >= 2)
        }
    }

    @Test
    fun holdLastFrameExtendsOnlyTheLastDelay() {
        val plan = FunVideoFrameSampler.plan(1280, 720, 2_000L, config(frameRate = 10, holdLastFrameMs = 500))
        assertNull(plan.error)
        assertEquals(21, plan.size)
        assertEquals(10, plan.frames.first().delayCentis)
        assertEquals(60, plan.frames.last().delayCentis)
    }

    @Test
    fun outputSizeFollowsTargetWidthAndClampsToMinimum() {
        val narrow = FunVideoFrameSampler.plan(1920, 1080, 5_000L, config(targetWidth = 320))
        assertEquals(320, narrow.frames.first().outWidth)
        assertEquals(180, narrow.frames.first().outHeight)

        val tiny = FunVideoFrameSampler.plan(1920, 1080, 5_000L, config(targetWidth = 8))
        assertTrue(tiny.frames.first().outWidth >= 32)
        assertTrue(tiny.frames.first().outHeight >= 32)
    }

    @Test
    fun frameRateClampStillProducesFrames() {
        val slow = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(frameRate = 0))
        assertNull(slow.error)
        assertEquals(6, slow.size)

        val fast = FunVideoFrameSampler.plan(1280, 720, 5_000L, config(frameRate = 99))
        assertNull(fast.error)
        assertTrue(fast.size <= 120)
    }

    // ------------------------------------------------------------------ 异常
    @Test
    fun invalidMetadataReturnsErrorInsteadOfThrowing() {
        val badSize = FunVideoFrameSampler.plan(0, 1080, 5_000L, config())
        assertNotNull(badSize.error)
        assertTrue(badSize.isEmpty)

        val badDuration = FunVideoFrameSampler.plan(1920, 1080, 0L, config())
        assertNotNull(badDuration.error)
        assertTrue(badDuration.isEmpty)

        val negativeEverything = FunVideoFrameSampler.plan(-1, -1, -1L, config())
        assertNotNull(negativeEverything.error)
        assertTrue(negativeEverything.isEmpty)
    }

    @Test
    fun planIsEmptyButValidForDegenerateClampedConfig() {
        // videoMaxFrames 被 clamp 到 >=1，因此即使配置为 0 也会产出单帧计划。
        val plan = FunVideoFrameSampler.plan(1280, 720, 1_000L, config(maxFrames = 0))
        assertNull(plan.error)
        assertEquals(1, plan.size)
    }

    private fun assertMonotonic(plan: FunVideoSamplePlan) {
        assertTrue(plan.size >= 1)
        for (i in 1 until plan.size) {
            assertTrue(plan.frames[i].timestampMs >= plan.frames[i - 1].timestampMs)
        }
    }
}
