package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大图预览缩放/平移边界测试（相册第 34 项）。
 *
 * 覆盖任务书指定项：**缩放边界（1x/8x/clamp）**，并补齐双击档位循环、平移钳制、
 * 翻页复位、配置误设修正等边界。纯 JVM（不依赖 Android）。
 */
class AppGalleryPreviewZoomTest {

    private val config = AppGalleryPreviewConfig.DEFAULT

    @Test
    fun defaultRangeIsOneToEight() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertEquals(1f, state.minScale, 1e-6f)
        assertEquals(8f, state.maxScale, 1e-6f)
        assertTrue(state.isScaleInRange)
    }

    @Test
    fun setScaleClampsToUpperBound() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertEquals(8f, state.setScale(64f), 1e-6f)
        assertEquals("scale", state.lastClampedField)
        assertTrue(state.isScaleInRange)
    }

    @Test
    fun setScaleClampsToLowerBound() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertEquals(1f, state.setScale(0.01f), 1e-6f)
        assertEquals("scale", state.lastClampedField)
    }

    @Test
    fun setScaleWithNonFiniteFallsBackToRangeBounds() {
        assertEquals(1f, AppGalleryPreviewZoomState(config = config).setScale(Float.NaN), 1e-6f)
        assertEquals(8f, AppGalleryPreviewZoomState(config = config).setScale(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(1f, AppGalleryPreviewZoomState(config = config).setScale(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    @Test
    fun pinchBelowMinimumStopsAtMinimum() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertEquals(1f, state.onPinch(0.001f), 1e-6f)
        assertEquals(1f, state.transform.scale, 1e-6f)
    }

    @Test
    fun pinchAboveMaximumStopsAtMaximum() {
        val state = AppGalleryPreviewZoomState(config = config)
        state.setScale(4f)
        assertEquals(8f, state.onPinch(10f), 1e-6f)
    }

    @Test
    fun invalidPinchFactorKeepsCurrentScale() {
        val state = AppGalleryPreviewZoomState(config = config)
        state.setScale(3f)
        assertEquals(3f, state.onPinch(0f), 1e-6f)
        assertEquals(3f, state.onPinch(Float.NaN), 1e-6f)
        assertEquals(3f, state.onPinch(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    @Test
    fun doubleTapCyclesThreeLevels() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertEquals(2.5f, state.onDoubleTap(), 1e-3f)
        assertEquals(4f, state.onDoubleTap(), 1e-3f)
        assertEquals(1f, state.onDoubleTap(), 1e-3f)
        assertEquals(2.5f, state.onDoubleTap(), 1e-3f)
    }

    @Test
    fun doubleTapLevelsAreClampedByCustomRange() {
        // 自定义 1x..3x：档位 2 应被压到上限 3x，档位 1 压到 2.5x。
        val custom = AppGalleryPreviewConfig.DEFAULT.copy(maxZoomFactor = 3f)
        val state = AppGalleryPreviewZoomState(config = custom)
        assertEquals(2.5f, state.onDoubleTap(), 1e-3f)
        assertEquals(3f, state.onDoubleTap(), 1e-3f)
        assertEquals(1f, state.onDoubleTap(), 1e-3f)
    }

    @Test
    fun panIsBlockedAtFittedScale() {
        val state = AppGalleryPreviewZoomState(config = config)
        val transform = state.onPan(dx = 120f, dy = 80f, viewportWidth = 1000f, viewportHeight = 1000f)
        assertEquals(0f, transform.offsetX, 1e-3f)
        assertEquals(0f, transform.offsetY, 1e-3f)
    }

    @Test
    fun panIsClampedToMarginPlusOverscroll() {
        val state = AppGalleryPreviewZoomState(config = config)
        state.setScale(2f)
        val transform = state.onPan(dx = 5000f, dy = -5000f, viewportWidth = 1000f, viewportHeight = 1000f)
        // 内容 2000 → margin 500；overscroll 0.35 → slack 175；limit = 675。
        assertEquals(675f, transform.offsetX, 1e-2f)
        assertEquals(-675f, transform.offsetY, 1e-2f)
        assertEquals("offsetX", state.lastClampedField)
    }

    @Test
    fun panWithDegenerateViewportFallsBackToZero() {
        val state = AppGalleryPreviewZoomState(config = config)
        state.setScale(4f)
        val transform = state.onPan(dx = 200f, dy = 200f, viewportWidth = 0f, viewportHeight = 0f)
        assertEquals(0f, transform.offsetX, 1e-3f)
        assertEquals(0f, transform.offsetY, 1e-3f)
    }

    @Test
    fun zoomedInFlagFollowsScale() {
        val state = AppGalleryPreviewZoomState(config = config)
        assertFalse("贴合态(1x)不算放大", state.isZoomedIn)
        state.setScale(1.005f)
        assertFalse("容差内不算放大", state.isZoomedIn)
        state.setScale(1f + 0.01f)
        assertFalse("恰好等于 minScale+epsilon 仍不算放大（判定为严格大于）", state.isZoomedIn)
        state.setScale(1.02f)
        assertTrue("超过容差即算放大", state.isZoomedIn)
        state.setScale(1.5f)
        assertTrue(state.isZoomedIn)
    }

    @Test
    fun resetForPageRestoresFittedScale() {
        val state = AppGalleryPreviewZoomState(config = config)
        state.setScale(6f)
        state.onPan(dx = 300f, dy = 0f, viewportWidth = 1000f, viewportHeight = 1000f)
        state.resetForPage()
        assertEquals(1f, state.transform.scale, 1e-6f)
        assertEquals(0f, state.transform.offsetX, 1e-6f)
    }

    @Test
    fun resetForPageKeepsScaleWhenConfigured() {
        val state = AppGalleryPreviewZoomState(config = config.copy(zoomResetOnPageChange = false))
        state.setScale(6f)
        state.resetForPage()
        assertEquals(6f, state.transform.scale, 1e-6f)
    }

    @Test
    fun clampScaleHandlesInvertedRange() {
        assertEquals(2f, AppGalleryPreviewZoomMath.clampScale(5f, 2f, -1f), 1e-6f)
    }

    @Test
    fun zoomPercentUsesFittedScaleAsHundredPercent() {
        assertEquals(100, AppGalleryPreviewZoomMath.zoomPercent(1f, 1f))
        assertEquals(800, AppGalleryPreviewZoomMath.zoomPercent(8f, 1f))
        assertEquals(0, AppGalleryPreviewZoomMath.zoomPercent(Float.NaN, 1f))
    }

    @Test
    fun scaledContentSizeIsSafeForDegenerateInput() {
        assertEquals(0f, AppGalleryPreviewZoomMath.scaledContentSize(0f, 2f), 1e-6f)
        assertEquals(0f, AppGalleryPreviewZoomMath.scaledContentSize(100f, Float.NaN), 1e-6f)
        assertEquals(300f, AppGalleryPreviewZoomMath.scaledContentSize(100f, 3f), 1e-6f)
    }

    @Test
    fun minZoomBelowFloorIsClampedUpAndReported() {
        val result = AppGalleryPreviewConfig(minZoomFactor = 0.01f).appGalleryPreviewSafe()
        assertEquals(0.25f, result.config.minZoomFactor, 1e-6f)
        assertTrue(result.correctedFields.contains("minZoomFactor"))
        assertTrue(result.hasCorrection)
    }

    @Test
    fun maxZoomAboveCeilingIsClampedDownAndReported() {
        val result = AppGalleryPreviewConfig(maxZoomFactor = 1000f).appGalleryPreviewSafe()
        assertEquals(32f, result.config.maxZoomFactor, 1e-6f)
        assertTrue(result.correctedFields.contains("maxZoomFactor"))
    }

    @Test
    fun invertedZoomRangeIsCorrectedToMax() {
        val result = AppGalleryPreviewConfig(minZoomFactor = 6f, maxZoomFactor = 3f).appGalleryPreviewSafe()
        assertEquals(3f, result.config.minZoomFactor, 1e-6f)
        assertEquals(3f, result.config.maxZoomFactor, 1e-6f)
        assertTrue(result.correctedFields.contains("minZoomFactor>maxZoomFactor"))
    }
}
