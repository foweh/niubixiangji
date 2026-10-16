package com.hinnka.mycamera.appgallerypreview

/**
 * 预览域配置的持久化编解码（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 存储键统一前缀 `agp_`，供 DataStore/SharedPreferences 挂点使用（挂点位置见补丁 CONFIG 节）。
 * 解码遵循"缺失即默认、非法即默认"原则，绝不因脏数据抛异常。
 */
object AppGalleryPreviewConfigCodec {

    const val KEY_PREFIX = "agp_"

    /** 编码为可持久化的字符串表（仅写非默认值，减小存储体积）。 */
    fun encode(config: AppGalleryPreviewConfig): Map<String, String> {
        val defaults = AppGalleryPreviewConfig()
        val out = LinkedHashMap<String, String>()
        fun put(key: String, value: Any, default: Any) {
            if (value != default) out[KEY_PREFIX + key] = value.toString()
        }
        put("min_zoom", config.minZoomFactor, defaults.minZoomFactor)
        put("max_zoom", config.maxZoomFactor, defaults.maxZoomFactor)
        put("tap_zoom", config.doubleTapZoomFactor, defaults.doubleTapZoomFactor)
        put("tap_zoom2", config.doubleTapZoomFactorSecondary, defaults.doubleTapZoomFactorSecondary)
        put("pan_overscroll", config.panOverscrollFactor, defaults.panOverscrollFactor)
        put("zoom_reset_on_page", config.zoomResetOnPageChange, defaults.zoomResetOnPageChange)
        put("swipe_switch", config.swipeSwitchEnabled, defaults.swipeSwitchEnabled)
        put("swipe_threshold", config.swipeSwitchThresholdFraction, defaults.swipeSwitchThresholdFraction)
        put("swipe_velocity", config.swipeVelocityThresholdDpPerSec, defaults.swipeVelocityThresholdDpPerSec)
        put("swipe_follow", config.swipeFollowFactor, defaults.swipeFollowFactor)
        put("decode_max_px", config.decodeTargetMaxPx, defaults.decodeTargetMaxPx)
        put("scale_renderer", config.previewScaleRenderer.name, defaults.previewScaleRenderer.name)

        put("slideshow_interval_ms", config.slideshowIntervalMs, defaults.slideshowIntervalMs)
        put("slideshow_loop", config.slideshowLoop, defaults.slideshowLoop)
        put("slideshow_shuffle", config.slideshowShuffle, defaults.slideshowShuffle)
        put("slideshow_seed", config.slideshowShuffleSeed, defaults.slideshowShuffleSeed)
        put("slideshow_autostart", config.slideshowAutoStart, defaults.slideshowAutoStart)
        put("slideshow_keep_screen_on", config.slideshowKeepScreenOn, defaults.slideshowKeepScreenOn)
        put("slideshow_progress", config.slideshowShowProgress, defaults.slideshowShowProgress)

        put("raw_preview_max_dim", config.rawPreviewMaxDimension, defaults.rawPreviewMaxDimension)
        put("raw_work_scale", config.rawRenderWorkingScale, defaults.rawRenderWorkingScale)
        put("raw_hist_bins", config.rawHistogramBins, defaults.rawHistogramBins)
        put("raw_over_threshold", config.rawOverexposureThreshold, defaults.rawOverexposureThreshold)
        put("raw_under_threshold", config.rawUnderexposureThreshold, defaults.rawUnderexposureThreshold)
        put("raw_over_warn_ratio", config.rawOverexposureWarnRatio, defaults.rawOverexposureWarnRatio)
        put("raw_show_hist", config.rawShowHistogram, defaults.rawShowHistogram)
        put("raw_show_over_hint", config.rawShowOverexposureHint, defaults.rawShowOverexposureHint)
        put("raw_export_max_dim", config.rawExportMaxDimension, defaults.rawExportMaxDimension)
        put("raw_export_quality", config.rawExportJpegQuality, defaults.rawExportJpegQuality)
        put("raw_export_keep_exif", config.rawExportPreserveExif, defaults.rawExportPreserveExif)
        put("raw_export_use_embedded", config.rawExportUseEmbeddedPreview, defaults.rawExportUseEmbeddedPreview)
        put("raw_export_chunk_rows", config.rawExportChunkRows, defaults.rawExportChunkRows)

        put("compare_default_ratio", config.compareSliderDefaultRatio, defaults.compareSliderDefaultRatio)
        put("compare_touch_slop_dp", config.compareHandleTouchSlopDp, defaults.compareHandleTouchSlopDp)
        put("compare_require_both", config.compareRequireBothVariants, defaults.compareRequireBothVariants)
        put("compare_show_labels", config.compareShowLabels, defaults.compareShowLabels)

        put("gif_autoplay", config.gifAutoPlay, defaults.gifAutoPlay)
        put("gif_loop", config.gifLoop, defaults.gifLoop)
        put("gif_max_dim", config.gifMaxDecodeDimensionPx, defaults.gifMaxDecodeDimensionPx)
        put("video_autoplay", config.videoAutoPlay, defaults.videoAutoPlay)
        put("video_loop", config.videoLoop, defaults.videoLoop)
        put("video_controls", config.videoShowControls, defaults.videoShowControls)
        put("video_bookmark_tolerance", config.videoBookmarkSeekToleranceMs, defaults.videoBookmarkSeekToleranceMs)
        put("video_bookmark_loop_end", config.videoBookmarkLoopOnEnd, defaults.videoBookmarkLoopOnEnd)

        put("gesture_enabled", config.playbackGestureEnabled, defaults.playbackGestureEnabled)
        put("gesture_double_tap_seek", config.doubleTapSeekMs, defaults.doubleTapSeekMs)
        put("gesture_drag_seek", config.dragSeekSensitivityMsPerDp, defaults.dragSeekSensitivityMsPerDp)
        put("gesture_volume", config.verticalDragVolumeSensitivity, defaults.verticalDragVolumeSensitivity)
        put("gesture_brightness", config.verticalDragBrightnessSensitivity, defaults.verticalDragBrightnessSensitivity)
        put("gesture_tap_toggle", config.singleTapTogglesPlayback, defaults.singleTapTogglesPlayback)
        put("gesture_indicator_ms", config.gestureIndicatorTimeoutMs, defaults.gestureIndicatorTimeoutMs)

        put("bg_throttle_ms", config.bgThrottleMs, defaults.bgThrottleMs)
        put("bg_chunk_px", config.bgChunkPixels, defaults.bgChunkPixels)
        put("bg_max_heap_mb", config.bgMaxHeapMb, defaults.bgMaxHeapMb)
        put("bg_max_batch", config.bgMaxFrameBatch, defaults.bgMaxFrameBatch)

        put("orientation_lock", config.orientationLock.name, defaults.orientationLock.name)
        put("follow_dark", config.followSystemDarkTheme, defaults.followSystemDarkTheme)
        put("dark_canvas_argb", config.darkCanvasColorArgb, defaults.darkCanvasColorArgb)
        put("immersive", config.immersiveChrome, defaults.immersiveChrome)
        put("chrome_auto_hide_ms", config.chromeAutoHideMs, defaults.chromeAutoHideMs)
        put("wide_toolbar_side", config.wideLayoutToolbarOnSide, defaults.wideLayoutToolbarOnSide)
        put("show_counter", config.showMediaCounter, defaults.showMediaCounter)
        return out
    }

    /**
     * 从键值表解码；缺失键取默认，非法值取默认（不抛异常）。
     * 返回值未 clamp，调用方应再走 [AppGalleryPreviewConfig.appGalleryPreviewSafe]。
     */
    fun decode(values: Map<String, String?>): AppGalleryPreviewConfig {
        val d = AppGalleryPreviewConfig()
        fun str(key: String): String? = values[KEY_PREFIX + key]?.trim()?.takeIf { it.isNotEmpty() }
        fun f(key: String, default: Float) = str(key)?.toFloatOrNull() ?: default
        fun i(key: String, default: Int) = str(key)?.toIntOrNull() ?: default
        fun l(key: String, default: Long) = str(key)?.toLongOrNull() ?: default
        fun b(key: String, default: Boolean) = str(key)?.toBooleanStrictOrNull() ?: default
        fun <T : Enum<T>> e(key: String, valuesOf: (String) -> T?, default: T): T =
            str(key)?.let(valuesOf) ?: default

        return AppGalleryPreviewConfig(
            minZoomFactor = f("min_zoom", d.minZoomFactor),
            maxZoomFactor = f("max_zoom", d.maxZoomFactor),
            doubleTapZoomFactor = f("tap_zoom", d.doubleTapZoomFactor),
            doubleTapZoomFactorSecondary = f("tap_zoom2", d.doubleTapZoomFactorSecondary),
            panOverscrollFactor = f("pan_overscroll", d.panOverscrollFactor),
            zoomResetOnPageChange = b("zoom_reset_on_page", d.zoomResetOnPageChange),
            swipeSwitchEnabled = b("swipe_switch", d.swipeSwitchEnabled),
            swipeSwitchThresholdFraction = f("swipe_threshold", d.swipeSwitchThresholdFraction),
            swipeVelocityThresholdDpPerSec = f("swipe_velocity", d.swipeVelocityThresholdDpPerSec),
            swipeFollowFactor = f("swipe_follow", d.swipeFollowFactor),
            decodeTargetMaxPx = i("decode_max_px", d.decodeTargetMaxPx),
            previewScaleRenderer = e(
                "scale_renderer",
                { runCatching { PreviewScaleRenderer.valueOf(it.uppercase()) }.getOrNull() },
                d.previewScaleRenderer,
            ),

            slideshowIntervalMs = l("slideshow_interval_ms", d.slideshowIntervalMs),
            slideshowLoop = b("slideshow_loop", d.slideshowLoop),
            slideshowShuffle = b("slideshow_shuffle", d.slideshowShuffle),
            slideshowShuffleSeed = l("slideshow_seed", d.slideshowShuffleSeed),
            slideshowAutoStart = b("slideshow_autostart", d.slideshowAutoStart),
            slideshowKeepScreenOn = b("slideshow_keep_screen_on", d.slideshowKeepScreenOn),
            slideshowShowProgress = b("slideshow_progress", d.slideshowShowProgress),

            rawPreviewMaxDimension = i("raw_preview_max_dim", d.rawPreviewMaxDimension),
            rawRenderWorkingScale = f("raw_work_scale", d.rawRenderWorkingScale),
            rawHistogramBins = i("raw_hist_bins", d.rawHistogramBins),
            rawOverexposureThreshold = f("raw_over_threshold", d.rawOverexposureThreshold),
            rawUnderexposureThreshold = f("raw_under_threshold", d.rawUnderexposureThreshold),
            rawOverexposureWarnRatio = f("raw_over_warn_ratio", d.rawOverexposureWarnRatio),
            rawShowHistogram = b("raw_show_hist", d.rawShowHistogram),
            rawShowOverexposureHint = b("raw_show_over_hint", d.rawShowOverexposureHint),
            rawExportMaxDimension = i("raw_export_max_dim", d.rawExportMaxDimension),
            rawExportJpegQuality = i("raw_export_quality", d.rawExportJpegQuality),
            rawExportPreserveExif = b("raw_export_keep_exif", d.rawExportPreserveExif),
            rawExportUseEmbeddedPreview = b("raw_export_use_embedded", d.rawExportUseEmbeddedPreview),
            rawExportChunkRows = i("raw_export_chunk_rows", d.rawExportChunkRows),

            compareSliderDefaultRatio = f("compare_default_ratio", d.compareSliderDefaultRatio),
            compareHandleTouchSlopDp = f("compare_touch_slop_dp", d.compareHandleTouchSlopDp),
            compareRequireBothVariants = b("compare_require_both", d.compareRequireBothVariants),
            compareShowLabels = b("compare_show_labels", d.compareShowLabels),

            gifAutoPlay = b("gif_autoplay", d.gifAutoPlay),
            gifLoop = b("gif_loop", d.gifLoop),
            gifMaxDecodeDimensionPx = i("gif_max_dim", d.gifMaxDecodeDimensionPx),
            videoAutoPlay = b("video_autoplay", d.videoAutoPlay),
            videoLoop = b("video_loop", d.videoLoop),
            videoShowControls = b("video_controls", d.videoShowControls),
            videoBookmarkSeekToleranceMs = l("video_bookmark_tolerance", d.videoBookmarkSeekToleranceMs),
            videoBookmarkLoopOnEnd = b("video_bookmark_loop_end", d.videoBookmarkLoopOnEnd),

            playbackGestureEnabled = b("gesture_enabled", d.playbackGestureEnabled),
            doubleTapSeekMs = l("gesture_double_tap_seek", d.doubleTapSeekMs),
            dragSeekSensitivityMsPerDp = f("gesture_drag_seek", d.dragSeekSensitivityMsPerDp),
            verticalDragVolumeSensitivity = f("gesture_volume", d.verticalDragVolumeSensitivity),
            verticalDragBrightnessSensitivity = f("gesture_brightness", d.verticalDragBrightnessSensitivity),
            singleTapTogglesPlayback = b("gesture_tap_toggle", d.singleTapTogglesPlayback),
            gestureIndicatorTimeoutMs = l("gesture_indicator_ms", d.gestureIndicatorTimeoutMs),

            bgThrottleMs = l("bg_throttle_ms", d.bgThrottleMs),
            bgChunkPixels = i("bg_chunk_px", d.bgChunkPixels),
            bgMaxHeapMb = i("bg_max_heap_mb", d.bgMaxHeapMb),
            bgMaxFrameBatch = i("bg_max_batch", d.bgMaxFrameBatch),

            orientationLock = e(
                "orientation_lock",
                { runCatching { PreviewOrientationLock.valueOf(it.uppercase()) }.getOrNull() },
                d.orientationLock,
            ),
            followSystemDarkTheme = b("follow_dark", d.followSystemDarkTheme),
            darkCanvasColorArgb = l("dark_canvas_argb", d.darkCanvasColorArgb),
            immersiveChrome = b("immersive", d.immersiveChrome),
            chromeAutoHideMs = l("chrome_auto_hide_ms", d.chromeAutoHideMs),
            wideLayoutToolbarOnSide = b("wide_toolbar_side", d.wideLayoutToolbarOnSide),
            showMediaCounter = b("show_counter", d.showMediaCounter),
        )
    }

    /** 解码并 clamp 的便捷入口。 */
    fun decodeSafe(values: Map<String, String?>): AppGalleryPreviewClampResult =
        decode(values).appGalleryPreviewSafe()
}
