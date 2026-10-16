package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SamplingThrottle] 单测：首次放行、间隔判定边界、等待时长、取值域 clamp、时间回拨、重置。
 */
class SamplingThrottleTest {

    // ---------------------------------------------------------------- 基本语义

    @Test
    fun firstAcquireAlwaysPasses() {
        val throttle = SamplingThrottle(intervalMs = 300L)
        assertTrue(throttle.tryAcquire(0L))
        // 第二次立刻调用被挡
        assertFalse(throttle.tryAcquire(0L))
        assertEquals(0L, throttle.lastSampleTimestampMs)
    }

    @Test
    fun intervalBoundary() {
        val throttle = SamplingThrottle(intervalMs = 300L)
        assertTrue(throttle.tryAcquire(1_000L))
        assertFalse(throttle.tryAcquire(1_299L))
        // 恰好到达间隔即放行（>= 判定）
        assertTrue(throttle.tryAcquire(1_300L))
        assertFalse(throttle.tryAcquire(1_599L))
        assertTrue(throttle.tryAcquire(1_600L))
    }

    @Test
    fun remainingMsCountsDownToZero() {
        val throttle = SamplingThrottle(intervalMs = 300L)
        assertEquals(0L, throttle.remainingMs(0L)) // 尚未采样过 → 立即可采
        throttle.tryAcquire(1_000L)
        assertEquals(300L, throttle.remainingMs(1_000L))
        assertEquals(200L, throttle.remainingMs(1_100L))
        assertEquals(0L, throttle.remainingMs(1_300L))
        assertEquals(0L, throttle.remainingMs(9_999L))
    }

    @Test
    fun timeRewindIsTreatedAsSamplable() {
        val throttle = SamplingThrottle(intervalMs = 300L)
        assertTrue(throttle.tryAcquire(1_000L))
        // 系统改时导致时间回拨：不能永久锁死
        assertTrue(throttle.tryAcquire(500L))
        assertEquals(500L, throttle.lastSampleTimestampMs)
        assertFalse(throttle.tryAcquire(799L))
        assertTrue(throttle.tryAcquire(800L))
    }

    @Test
    fun resetAllowsImmediateSampling() {
        val throttle = SamplingThrottle(intervalMs = 300L)
        throttle.tryAcquire(1_000L)
        throttle.reset()
        assertTrue(throttle.tryAcquire(1_000L))
    }

    // ------------------------------------------------------------ 取值域 clamp

    @Test
    fun intervalIsClampedIntoBounds() {
        val throttle = SamplingThrottle(intervalMs = 0L, minIntervalMs = 50L, maxIntervalMs = 500L)
        assertEquals(50L, throttle.intervalMs)
        assertEquals(50L..500L, throttle.bounds)

        throttle.intervalMs = 99_999L
        assertEquals(500L, throttle.intervalMs)

        throttle.intervalMs = -1L
        assertEquals(50L, throttle.intervalMs)
    }

    @Test
    fun illegalBoundsDoNotProduceInvalidRange() {
        // max < min：内部把上界抬到上界不低于下界，绝不出现空区间
        val throttle = SamplingThrottle(intervalMs = 100L, minIntervalMs = 200L, maxIntervalMs = 50L)
        assertEquals(200L, throttle.intervalMs)
        assertEquals(200L..200L, throttle.bounds)

        // minIntervalMs <= 0 也会被抬到 1，避免除零/每帧都放行
        val zero = SamplingThrottle(intervalMs = 0L, minIntervalMs = 0L, maxIntervalMs = 0L)
        assertEquals(1L, zero.intervalMs)
    }

    @Test
    fun defaultBoundsAreSanityChecked() {
        val unbounded = SamplingThrottle(intervalMs = 10L)
        assertEquals(10L, unbounded.intervalMs)
        assertEquals(1L..Long.MAX_VALUE, unbounded.bounds)
    }

    // ------------------------------------------------------------ 预设构造器

    @Test
    fun histogramPresetUsesHistogramRanges() {
        val throttle = SamplingThrottle.forHistogram()
        assertEquals(ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_DEFAULT_MS, throttle.intervalMs)
        assertEquals(
            ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS..
                ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS,
            throttle.bounds,
        )
        // 越界入参被压进 50..5000
        assertEquals(50L, SamplingThrottle.forHistogram(1L).intervalMs)
        assertEquals(5_000L, SamplingThrottle.forHistogram(999_999L).intervalMs)
    }

    @Test
    fun faceStickerPresetUsesStickerRanges() {
        val throttle = SamplingThrottle.forFaceSticker()
        assertEquals(ViewfinderParamRanges.STICKER_INTERVAL_DEFAULT_MS, throttle.intervalMs)
        assertEquals(
            ViewfinderParamRanges.STICKER_INTERVAL_MIN_MS..ViewfinderParamRanges.STICKER_INTERVAL_MAX_MS,
            throttle.bounds,
        )
        assertEquals(33L, SamplingThrottle.forFaceSticker(1L).intervalMs)
        assertEquals(2_000L, SamplingThrottle.forFaceSticker(99_999L).intervalMs)
    }

    @Test
    fun throttleIsReusableAcrossManyFrames() {
        val throttle = SamplingThrottle.forHistogram(300L)
        var accepted = 0
        // 模拟 60fps 连续 900 帧（15 秒）：应约每 300ms 放行一次
        for (frame in 0 until 900) {
            if (throttle.tryAcquire(frame * 16L)) accepted++
        }
        assertTrue("至少放行若干次", accepted >= 45)
        assertTrue("必须显著少于总帧数", accepted <= 55)
    }
}
