package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大图预览翻页（滑动切换）状态机测试（相册第 34 项）。
 *
 * 覆盖任务书指定项：**滑动切换状态机**；并补齐阈值/速度/边界/放大互斥/视口非法等分支。
 * 纯 JVM。
 */
class AppGalleryPreviewPagerTest {

    private val config = AppGalleryPreviewConfig.DEFAULT

    private fun resolve(
        dxPx: Float,
        velocityDpPerSec: Float = 0f,
        viewportWidthPx: Float = 1000f,
        isZoomedIn: Boolean = false,
        hasPrevious: Boolean = true,
        hasNext: Boolean = true,
        cfg: AppGalleryPreviewConfig = config,
    ): AppGalleryPreviewSwipeOutcome = AppGalleryPreviewPagerMath.resolveSwipe(
        dxPx = dxPx,
        velocityDpPerSec = velocityDpPerSec,
        viewportWidthPx = viewportWidthPx,
        isZoomedIn = isZoomedIn,
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        config = cfg,
    )

    @Test
    fun noMovementDoesNotSwitch() {
        val outcome = resolve(dxPx = 0f)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.BELOW_THRESHOLD, outcome.blockReason)
        assertFalse(outcome.switched)
    }

    @Test
    fun dragRightSwitchesToPrevious() {
        val outcome = resolve(dxPx = 300f)
        assertEquals(AppGalleryPreviewSwipeDecision.PREVIOUS, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeTrigger.DISTANCE, outcome.trigger)
        assertTrue(outcome.switched)
    }

    @Test
    fun dragLeftSwitchesToNext() {
        val outcome = resolve(dxPx = -300f)
        assertEquals(AppGalleryPreviewSwipeDecision.NEXT, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeTrigger.DISTANCE, outcome.trigger)
    }

    @Test
    fun belowDistanceThresholdDoesNotSwitch() {
        // 阈值 0.22；100/1000 = 0.1 未达阈值。
        val outcome = resolve(dxPx = 100f)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.BELOW_THRESHOLD, outcome.blockReason)
    }

    @Test
    fun exactlyAtThresholdSwitches() {
        // 边界为"大于等于"：220/1000 = 0.22 即触发。
        assertEquals(AppGalleryPreviewSwipeDecision.PREVIOUS, resolve(dxPx = 220f).decision)
        assertEquals(AppGalleryPreviewSwipeDecision.NEXT, resolve(dxPx = -220f).decision)
    }

    @Test
    fun justBelowThresholdDoesNotSwitch() {
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, resolve(dxPx = 219f).decision)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, resolve(dxPx = -219f).decision)
    }

    @Test
    fun highVelocityOverridesShortDistance() {
        val outcome = resolve(dxPx = 10f, velocityDpPerSec = -400f)
        // 速度触发时方向取自位移（正=上一张），因此这里仍是上一张，但 trigger 为 VELOCITY。
        assertEquals(AppGalleryPreviewSwipeDecision.PREVIOUS, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeTrigger.VELOCITY, outcome.trigger)
    }

    @Test
    fun velocityAloneSwitchesWhenDragIsZero() {
        val outcome = resolve(dxPx = 0f, velocityDpPerSec = 500f)
        assertEquals(AppGalleryPreviewSwipeDecision.PREVIOUS, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeTrigger.VELOCITY, outcome.trigger)
    }

    @Test
    fun velocityBelowThresholdIsIgnored() {
        val outcome = resolve(dxPx = 10f, velocityDpPerSec = 319f)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
    }

    @Test
    fun zoomedInBlocksPaging() {
        val outcome = resolve(dxPx = -400f, isZoomedIn = true)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.ZOOMED_IN, outcome.blockReason)
    }

    @Test
    fun firstItemBlocksPrevious() {
        val outcome = resolve(dxPx = 400f, hasPrevious = false)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.AT_EDGE, outcome.blockReason)
    }

    @Test
    fun lastItemBlocksNext() {
        val outcome = resolve(dxPx = -400f, hasNext = false)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.AT_EDGE, outcome.blockReason)
    }

    @Test
    fun disabledSwipeIsBlocked() {
        val outcome = resolve(dxPx = -400f, cfg = config.copy(swipeSwitchEnabled = false))
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(AppGalleryPreviewSwipeBlockReason.SWIPE_DISABLED, outcome.blockReason)
    }

    @Test
    fun invalidViewportIsBlocked() {
        assertEquals(
            AppGalleryPreviewSwipeBlockReason.INVALID_VIEWPORT,
            resolve(dxPx = -400f, viewportWidthPx = 0f).blockReason,
        )
        assertEquals(
            AppGalleryPreviewSwipeBlockReason.INVALID_VIEWPORT,
            resolve(dxPx = -400f, viewportWidthPx = Float.NaN).blockReason,
        )
    }

    @Test
    fun nonFiniteDragIsTreatedAsNoMovement() {
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, resolve(dxPx = Float.NaN).decision)
    }

    @Test
    fun followOffsetIsDampedAndViewportBounded() {
        assertEquals(850f, AppGalleryPreviewPagerMath.followOffsetPx(1000f, 1000f, config), 1e-3f)
        assertEquals(-850f, AppGalleryPreviewPagerMath.followOffsetPx(-1000f, 1000f, config), 1e-3f)
        // 极端拖动不得越出视口。
        assertEquals(1000f, AppGalleryPreviewPagerMath.followOffsetPx(100000f, 1000f, config), 1e-3f)
        assertEquals(0f, AppGalleryPreviewPagerMath.followOffsetPx(100f, 0f, config), 1e-3f)
    }

    @Test
    fun stateMachineSettlesBetweenItems() {
        val pager = AppGalleryPreviewPagerState(count = 5, initialIndex = 2, config = config)
        assertEquals(2, pager.currentIndex)
        assertTrue(pager.hasPrevious)
        assertTrue(pager.hasNext)

        val nextOutcome = pager.settle(dragPx = -400f, velocityDpPerSec = 0f, viewportWidthPx = 1000f, isZoomedIn = false)
        assertEquals(AppGalleryPreviewSwipeDecision.NEXT, nextOutcome.decision)
        assertEquals(3, pager.currentIndex)
        assertEquals(0f, pager.followOffsetPx, 1e-3f)

        val prevOutcome = pager.settle(dragPx = 400f, velocityDpPerSec = 0f, viewportWidthPx = 1000f, isZoomedIn = false)
        assertEquals(AppGalleryPreviewSwipeDecision.PREVIOUS, prevOutcome.decision)
        assertEquals(2, pager.currentIndex)
    }

    @Test
    fun stateMachineStopsAtFirstItem() {
        val pager = AppGalleryPreviewPagerState(count = 3, initialIndex = 0, config = config)
        assertFalse(pager.hasPrevious)
        val outcome = pager.settle(dragPx = 400f, velocityDpPerSec = 0f, viewportWidthPx = 1000f, isZoomedIn = false)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(0, pager.currentIndex)
    }

    @Test
    fun stateMachineStopsAtLastItem() {
        val pager = AppGalleryPreviewPagerState(count = 3, initialIndex = 2, config = config)
        assertFalse(pager.hasNext)
        val outcome = pager.settle(dragPx = -400f, velocityDpPerSec = 0f, viewportWidthPx = 1000f, isZoomedIn = false)
        assertEquals(AppGalleryPreviewSwipeDecision.NONE, outcome.decision)
        assertEquals(2, pager.currentIndex)
    }

    @Test
    fun goToClampsToRange() {
        val pager = AppGalleryPreviewPagerState(count = 5, initialIndex = 0, config = config)
        assertEquals(4, pager.goTo(99))
        assertEquals(0, pager.goTo(-7))
        assertEquals(2, pager.goTo(2))
        assertTrue(pager.hasPrevious)
        assertTrue(pager.hasNext)
    }

    @Test
    fun goNextAndGoPreviousReportAvailability() {
        val pager = AppGalleryPreviewPagerState(count = 2, initialIndex = 0, config = config)
        assertTrue(pager.goNext())
        assertFalse(pager.goNext())
        assertEquals(1, pager.currentIndex)
        assertTrue(pager.goPrevious())
        assertFalse(pager.goPrevious())
    }

    @Test
    fun emptyPagerIsSafe() {
        val pager = AppGalleryPreviewPagerState(count = 0, initialIndex = 3, config = config)
        assertTrue(pager.isEmpty)
        assertFalse(pager.hasPrevious)
        assertFalse(pager.hasNext)
        assertEquals(0, pager.currentIndex)
        assertEquals(0, pager.goTo(3))
        assertEquals(
            AppGalleryPreviewSwipeDecision.NONE,
            pager.settle(dragPx = -500f, velocityDpPerSec = 0f, viewportWidthPx = 1000f, isZoomedIn = false).decision,
        )
    }

    @Test
    fun initialIndexIsClampedIntoRange() {
        assertEquals(4, AppGalleryPreviewPagerState(count = 5, initialIndex = 99, config = config).currentIndex)
        assertEquals(0, AppGalleryPreviewPagerState(count = 5, initialIndex = -3, config = config).currentIndex)
    }

    @Test
    fun onDragUpdatesFollowOffset() {
        val pager = AppGalleryPreviewPagerState(count = 3, initialIndex = 1, config = config)
        assertEquals(425f, pager.onDrag(dragPx = 500f, viewportWidthPx = 1000f), 1e-3f)
        pager.applyOutcome(AppGalleryPreviewSwipeOutcome())
        assertEquals(0f, pager.followOffsetPx, 1e-3f)
    }
}
