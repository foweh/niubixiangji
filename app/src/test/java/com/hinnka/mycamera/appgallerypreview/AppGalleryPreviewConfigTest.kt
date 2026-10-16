package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览域配置 clamp 与 codec 往返测试（第 34-42 项公共基础设施）。
 *
 * 关注点：所有开放参数"误设不崩溃"，以及键值表往返不丢字段/不抛异常。纯 JVM。
 */
class AppGalleryPreviewConfigTest {

    private val codec = AppGalleryPreviewConfigCodec

    @Test
    fun defaultsAreAlreadySafe() {
        val result = AppGalleryPreviewConfig().appGalleryPreviewSafe()
        assertFalse("默认值不应触发任何修正", result.hasCorrection)
        assertTrue(result.correctedFields.isEmpty())
    }

    @Test
    fun zoomKeysAreClampedIntoFloors() {
        val result = AppGalleryPreviewConfig(
            minZoomFactor = -100f,
            maxZoomFactor = 99999f,
            doubleTapZoomFactor = -1f,
            doubleTapZoomFactorSecondary = -1f,
        ).appGalleryPreviewSafe()
        assertEquals(AppGalleryPreviewConfig.ZOOM_MIN_FLOOR, result.config.minZoomFactor, 1e-6f)
        assertEquals(AppGalleryPreviewConfig.ZOOM_MAX_CEIL, result.config.maxZoomFactor, 1e-6f)
        assertTrue(result.config.doubleTapZoomFactor >= result.config.minZoomFactor)
        assertTrue(result.config.doubleTapZoomFactor <= result.config.maxZoomFactor)
        assertTrue(result.config.doubleTapZoomFactorSecondary >= result.config.doubleTapZoomFactor)
    }

    @Test
    fun swipeAndSlideshowKeysAreClamped() {
        val result = AppGalleryPreviewConfig(
            swipeSwitchThresholdFraction = -2f,
            swipeVelocityThresholdDpPerSec = 1f,
            swipeFollowFactor = 10f,
            slideshowIntervalMs = 1L,
            slideshowShuffleSeed = -5L,
        ).appGalleryPreviewSafe()
        assertEquals(0.05f, result.config.swipeSwitchThresholdFraction, 1e-6f)
        assertEquals(80f, result.config.swipeVelocityThresholdDpPerSec, 1e-6f)
        assertEquals(1f, result.config.swipeFollowFactor, 1e-6f)
        assertEquals(1000L, result.config.slideshowIntervalMs)
        assertEquals(0L, result.config.slideshowShuffleSeed)
        assertTrue(result.correctedFields.contains("slideshowIntervalMs"))
        assertTrue(result.correctedFields.contains("swipeSwitchThresholdFraction"))
    }

    @Test
    fun rawAndCompareKeysAreClamped() {
        val result = AppGalleryPreviewConfig(
            rawRenderWorkingScale = 8f,
            rawHistogramBins = 1,
            rawExportJpegQuality = 0,
            rawExportChunkRows = 0,
            rawExportMaxDimension = 1,
            rawOverexposureWarnRatio = 5f,
        ).appGalleryPreviewSafe()
        assertEquals(1f, result.config.rawRenderWorkingScale, 1e-6f)
        assertEquals(32, result.config.rawHistogramBins)
        assertEquals(1, result.config.rawExportJpegQuality)
        assertEquals(16, result.config.rawExportChunkRows)
        assertEquals(512, result.config.rawExportMaxDimension)
        assertEquals(1f, result.config.rawOverexposureWarnRatio, 1e-6f)
    }

    @Test
    fun playbackGestureKeysAreClamped() {
        val result = AppGalleryPreviewConfig(
            doubleTapSeekMs = 1L,
            dragSeekSensitivityMsPerDp = 99999f,
            verticalDragVolumeSensitivity = 0f,
            gestureIndicatorTimeoutMs = 0L,
        ).appGalleryPreviewSafe()
        assertEquals(1000L, result.config.doubleTapSeekMs)
        assertEquals(2000f, result.config.dragSeekSensitivityMsPerDp, 1e-6f)
        assertEquals(0.05f, result.config.verticalDragVolumeSensitivity, 1e-6f)
        assertEquals(200L, result.config.gestureIndicatorTimeoutMs)
    }

    @Test
    fun layoutAndBackgroundKeysAreClamped() {
        val result = AppGalleryPreviewConfig(
            darkCanvasColorArgb = -1L,
            chromeAutoHideMs = 999_999L,
            bgChunkPixels = 1,
            bgMaxHeapMb = -100,
        ).appGalleryPreviewSafe()
        assertEquals(0L, result.config.darkCanvasColorArgb)
        assertEquals(30000L, result.config.chromeAutoHideMs)
        assertEquals(4096, result.config.bgChunkPixels)
        assertEquals(-1, result.config.bgMaxHeapMb)
    }

    @Test
    fun codecUsesNamespacedKeys() {
        val encoded = codec.encode(AppGalleryPreviewConfig(maxZoomFactor = 6f))
        assertTrue(encoded.isNotEmpty())
        assertTrue(encoded.keys.all { it.startsWith(AppGalleryPreviewConfigCodec.KEY_PREFIX) })
        assertEquals("6.0", encoded["agp_max_zoom"])
    }

    @Test
    fun codecSkipsDefaultValues() {
        val encoded = codec.encode(AppGalleryPreviewConfig())
        assertTrue("默认配置不应写入任何键", encoded.isEmpty())
    }

    @Test
    fun codecRoundTripPreservesModifiedFields() {
        val source = AppGalleryPreviewConfig(
            minZoomFactor = 0.5f,
            maxZoomFactor = 6f,
            doubleTapZoomFactor = 3f,
            doubleTapZoomFactorSecondary = 5f,
            swipeSwitchThresholdFraction = 0.3f,
            swipeFollowFactor = 0.6f,
            decodeTargetMaxPx = 4096,
            previewScaleRenderer = PreviewScaleRenderer.COMPOSE_GESTURE,
            slideshowIntervalMs = 5000L,
            slideshowLoop = false,
            slideshowShuffle = true,
            slideshowShuffleSeed = 7L,
            slideshowAutoStart = true,
            rawExportMaxDimension = 2048,
            rawExportJpegQuality = 80,
            rawShowHistogram = false,
            compareSliderDefaultRatio = 0.25f,
            compareShowLabels = false,
            gifLoop = false,
            videoAutoPlay = true,
            videoBookmarkSeekToleranceMs = 800L,
            playbackGestureEnabled = false,
            doubleTapSeekMs = 20000L,
            bgChunkPixels = 131072,
            bgMaxHeapMb = 128,
            orientationLock = PreviewOrientationLock.PORTRAIT,
            followSystemDarkTheme = false,
            immersiveChrome = false,
            wideLayoutToolbarOnSide = false,
            showMediaCounter = false,
        )
        val decoded = codec.decode(codec.encode(source))

        assertEquals(0.5f, decoded.minZoomFactor, 1e-6f)
        assertEquals(6f, decoded.maxZoomFactor, 1e-6f)
        assertEquals(3f, decoded.doubleTapZoomFactor, 1e-6f)
        assertEquals(5f, decoded.doubleTapZoomFactorSecondary, 1e-6f)
        assertEquals(0.3f, decoded.swipeSwitchThresholdFraction, 1e-6f)
        assertEquals(0.6f, decoded.swipeFollowFactor, 1e-6f)
        assertEquals(4096, decoded.decodeTargetMaxPx)
        assertEquals(PreviewScaleRenderer.COMPOSE_GESTURE, decoded.previewScaleRenderer)
        assertEquals(5000L, decoded.slideshowIntervalMs)
        assertFalse(decoded.slideshowLoop)
        assertTrue(decoded.slideshowShuffle)
        assertEquals(7L, decoded.slideshowShuffleSeed)
        assertTrue(decoded.slideshowAutoStart)
        assertEquals(2048, decoded.rawExportMaxDimension)
        assertEquals(80, decoded.rawExportJpegQuality)
        assertFalse(decoded.rawShowHistogram)
        assertEquals(0.25f, decoded.compareSliderDefaultRatio, 1e-6f)
        assertFalse(decoded.compareShowLabels)
        assertFalse(decoded.gifLoop)
        assertTrue(decoded.videoAutoPlay)
        assertEquals(800L, decoded.videoBookmarkSeekToleranceMs)
        assertFalse(decoded.playbackGestureEnabled)
        assertEquals(20000L, decoded.doubleTapSeekMs)
        assertEquals(131072, decoded.bgChunkPixels)
        assertEquals(128, decoded.bgMaxHeapMb)
        assertEquals(PreviewOrientationLock.PORTRAIT, decoded.orientationLock)
        assertFalse(decoded.followSystemDarkTheme)
        assertFalse(decoded.immersiveChrome)
        assertFalse(decoded.wideLayoutToolbarOnSide)
        assertFalse(decoded.showMediaCounter)
    }

    @Test
    fun codecIgnoresForeignKeysAndGarbage() {
        val decoded = codec.decode(
            mapOf(
                "other_max_zoom" to "12",
                "agp_max_zoom" to "not-a-number",
                "agp_slideshow_interval_ms" to "",
                "agp_scale_renderer" to "NOT_A_RENDERER",
            ),
        )
        assertEquals(AppGalleryPreviewConfig().maxZoomFactor, decoded.maxZoomFactor, 1e-6f)
        assertEquals(AppGalleryPreviewConfig().slideshowIntervalMs, decoded.slideshowIntervalMs)
        assertEquals(AppGalleryPreviewConfig().previewScaleRenderer, decoded.previewScaleRenderer)
    }

    @Test
    fun codecAcceptsLowerCaseEnumNames() {
        val decoded = codec.decode(mapOf("agp_scale_renderer" to "compose_gesture"))
        assertEquals(PreviewScaleRenderer.COMPOSE_GESTURE, decoded.previewScaleRenderer)
    }

    @Test
    fun decodeSafeClampsOutOfRangeValues() {
        val result = codec.decodeSafe(
            mapOf(
                "agp_max_zoom" to "999",
                "agp_slideshow_interval_ms" to "5",
                "agp_raw_export_quality" to "-5",
            ),
        )
        assertEquals(32f, result.config.maxZoomFactor, 1e-6f)
        assertEquals(1000L, result.config.slideshowIntervalMs)
        assertEquals(1, result.config.rawExportJpegQuality)
        assertTrue(result.correctedFields.isNotEmpty())
    }

    @Test
    fun codecRoundTripOfSafeConfigIsStable() {
        val original = AppGalleryPreviewConfig(maxZoomFactor = 5f, slideshowIntervalMs = 4000L)
        val once = codec.decode(codec.encode(original))
        val twice = codec.decode(codec.encode(once))
        assertEquals(once.maxZoomFactor, twice.maxZoomFactor, 1e-6f)
        assertEquals(once.slideshowIntervalMs, twice.slideshowIntervalMs)
        assertNotEquals(0f, twice.maxZoomFactor, 1e-6f)
    }
}
