package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幻灯片放映测试（相册第 35 项）。
 *
 * 覆盖任务书指定项：**幻灯片间隔边界**；并补齐循环/不循环、暂停续播、时间回拨、
 * 空集、随机顺序确定性、seek 等分支。时间由外部注入（nowMs），因此可在纯 JVM 下确定性地测边界。
 */
class AppGalleryPreviewSlideshowTest {

    private val config = AppGalleryPreviewConfig.DEFAULT

    @Test
    fun intervalBoundaryTriggersExactlyAtInterval() {
        val slideshow = AppGalleryPreviewSlideshow(count = 3, startIndex = 0, config = config)
        assertEquals(AppGalleryPreviewSlideshowState.IDLE, slideshow.state)
        slideshow.play(nowMs = 0L)
        assertEquals(AppGalleryPreviewSlideshowState.PLAYING, slideshow.state)
        assertEquals(3000L, slideshow.intervalMs)

        assertNull("间隔前一刻不推进", slideshow.onTick(2999L))
        assertEquals("到达间隔即刻推进", 1, slideshow.onTick(3000L))
        assertNull(slideshow.onTick(5999L))
        assertEquals(2, slideshow.onTick(6000L))
    }

    @Test
    fun intervalIsClampedToConfiguredBounds() {
        // 配置范围 1s~30s。
        val tooShort = AppGalleryPreviewSlideshow(3, 0, config.copy(slideshowIntervalMs = 10L))
        assertEquals(1000L, tooShort.intervalMs)

        val tooLong = AppGalleryPreviewSlideshow(3, 0, config.copy(slideshowIntervalMs = 600_000L))
        assertEquals(30000L, tooLong.intervalMs)

        val eightSeconds = AppGalleryPreviewSlideshow(3, 0, config.copy(slideshowIntervalMs = 8000L))
        assertEquals(8000L, eightSeconds.intervalMs)
    }

    @Test
    fun loopWrapsBackToFirstItem() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config)
        assertEquals(1, slideshow.advance())
        assertEquals(2, slideshow.advance())
        assertEquals(0, slideshow.advance())
        assertEquals(AppGalleryPreviewSlideshowState.IDLE, slideshow.state)
    }

    @Test
    fun withoutLoopFinishesAtLastItem() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config.copy(slideshowLoop = false))
        assertEquals(1, slideshow.advance())
        assertEquals(2, slideshow.advance())
        assertNull(slideshow.advance())
        assertEquals(AppGalleryPreviewSlideshowState.FINISHED, slideshow.state)
    }

    @Test
    fun replayingAfterFinishRestartsFromHead() {
        val slideshow = AppGalleryPreviewSlideshow(2, 0, config.copy(slideshowLoop = false))
        assertEquals(1, slideshow.advance())
        assertNull(slideshow.advance())
        assertEquals(AppGalleryPreviewSlideshowState.FINISHED, slideshow.state)
        slideshow.play(nowMs = 0L)
        assertEquals(AppGalleryPreviewSlideshowState.PLAYING, slideshow.state)
        assertEquals(1, slideshow.advance())
    }

    @Test
    fun emptyCountGoesStraightToFinished() {
        val slideshow = AppGalleryPreviewSlideshow(0, 0, config)
        slideshow.play(nowMs = 0L)
        assertEquals(AppGalleryPreviewSlideshowState.FINISHED, slideshow.state)
        assertNull(slideshow.onTick(10_000L))
        assertNull(slideshow.advance())
        assertEquals(0L, slideshow.remainingMs(10_000L))
    }

    @Test
    fun pauseFreezesAndResumeRestartsInterval() {
        val slideshow = AppGalleryPreviewSlideshow(4, 0, config)
        slideshow.play(nowMs = 0L)
        assertEquals(1, slideshow.onTick(3000L))

        slideshow.pause()
        assertEquals(AppGalleryPreviewSlideshowState.PAUSED, slideshow.state)
        assertNull("暂停期间不推进", slideshow.onTick(30_000L))
        assertEquals(0L, slideshow.remainingMs(30_000L))

        slideshow.play(nowMs = 30_000L)
        assertEquals(AppGalleryPreviewSlideshowState.PLAYING, slideshow.state)
        assertNull(slideshow.onTick(32_000L))
        assertEquals(2, slideshow.onTick(33_000L))
    }

    @Test
    fun toggleSwitchesBetweenPlayingAndPaused() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config)
        slideshow.toggle(nowMs = 0L)
        assertEquals(AppGalleryPreviewSlideshowState.PLAYING, slideshow.state)
        slideshow.toggle(nowMs = 1000L)
        assertEquals(AppGalleryPreviewSlideshowState.PAUSED, slideshow.state)
        slideshow.toggle(nowMs = 2000L)
        assertEquals(AppGalleryPreviewSlideshowState.PLAYING, slideshow.state)
    }

    @Test
    fun stopReturnsToIdleAndRewinds() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config)
        slideshow.play(nowMs = 0L)
        assertEquals(1, slideshow.onTick(3000L))
        slideshow.stop()
        assertEquals(AppGalleryPreviewSlideshowState.IDLE, slideshow.state)
        slideshow.play(nowMs = 10_000L)
        assertEquals(1, slideshow.advance())
    }

    @Test
    fun backwardClockIsAbsorbedNotLooped() {
        val slideshow = AppGalleryPreviewSlideshow(4, 0, config)
        slideshow.play(nowMs = 10_000L)
        assertNull("时间回拨只重新起算", slideshow.onTick(5000L))
        assertNull("重新起算后按新基准等待一个间隔", slideshow.onTick(7999L))
        assertEquals(1, slideshow.onTick(8000L))
    }

    @Test
    fun remainingAndProgressTrackInterval() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config)
        slideshow.play(nowMs = 0L)
        assertEquals(3000L, slideshow.remainingMs(0L))
        assertEquals(1500L, slideshow.remainingMs(1500L))
        assertEquals(0f, slideshow.progressFraction(0L), 1e-3f)
        assertEquals(0.5f, slideshow.progressFraction(1500L), 1e-3f)
        assertEquals(1f, slideshow.progressFraction(3000L), 1e-3f)
        assertEquals(1f, slideshow.progressFraction(99_999L), 1e-3f)
    }

    @Test
    fun retreatWrapsOnlyWhenLooping() {
        val looping = AppGalleryPreviewSlideshow(3, 0, config)
        assertEquals(2, looping.retreat())

        val single = AppGalleryPreviewSlideshow(3, 0, config.copy(slideshowLoop = false))
        assertEquals(0, single.retreat())
    }

    @Test
    fun seekToClampsAndKeepsPositionOnMiss() {
        val slideshow = AppGalleryPreviewSlideshow(4, 0, config)
        assertEquals(2, slideshow.seekTo(2))
        assertEquals(3, slideshow.seekTo(999))
        assertEquals(0, slideshow.seekTo(-5))
    }

    @Test
    fun shuffleOrderIsDeterministicForSameSeed() {
        val shuffled = config.copy(slideshowShuffle = true, slideshowShuffleSeed = 42L)
        val first = AppGalleryPreviewSlideshow(6, 0, shuffled)
        val second = AppGalleryPreviewSlideshow(6, 0, shuffled)
        assertEquals(first.order, second.order)
        assertEquals("必须是全量置换，不丢项", (0 until 6).toList(), first.order.sorted())
    }

    @Test
    fun naturalOrderIsIdentityWhenShuffleOff() {
        val slideshow = AppGalleryPreviewSlideshow(4, 0, config.copy(slideshowShuffle = false))
        assertEquals(listOf(0, 1, 2, 3), slideshow.order)
        assertEquals(0, slideshow.currentIndex)
        assertEquals(1, slideshow.position)
    }

    @Test
    fun startIndexDrivesInitialCursor() {
        val slideshow = AppGalleryPreviewSlideshow(4, startIndex = 2, config = config)
        assertEquals(2, slideshow.currentIndex)
        assertEquals(3, slideshow.position)
        assertEquals(3, slideshow.advance())
    }

    @Test
    fun stateIsObservableAfterPlay() {
        val slideshow = AppGalleryPreviewSlideshow(3, 0, config)
        assertFalse(slideshow.isPlaying)
        slideshow.play(nowMs = 0L)
        assertTrue(slideshow.isPlaying)
        assertNotNull(slideshow.onTick(3000L))
    }
}
