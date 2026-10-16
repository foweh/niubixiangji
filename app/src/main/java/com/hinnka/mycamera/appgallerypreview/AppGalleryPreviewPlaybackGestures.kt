package com.hinnka.mycamera.appgallerypreview

/** 播放页手势类型（第 41 项）。 */
enum class AppGalleryPreviewPlaybackGesture {
    NONE,
    /** 单击：切换播放/暂停。 */
    TAP_TOGGLE,
    /** 双击左侧：快退。 */
    DOUBLE_TAP_BACKWARD,
    /** 双击右侧：快进。 */
    DOUBLE_TAP_FORWARD,
    /** 横向拖动：进度拖动。 */
    HORIZONTAL_SEEK,
    /** 竖向拖动（右半屏）：音量。 */
    VERTICAL_VOLUME,
    /** 竖向拖动（左半屏）：亮度。 */
    VERTICAL_BRIGHTNESS,
    /** 手势被配置关闭或因数据非法被忽略。 */
    IGNORED,
}

/**
 * 一次手势解析结果（纯数据）。
 *
 * @param seekTargetMs 需要 seek 到的绝对时间（毫秒）；无关手势为 null
 * @param seekDeltaMs 相对增量（用于提示 "+10s"）；无关手势为 null
 * @param volumeDelta 音量增量（-1..1）；无关手势为 null
 * @param brightnessDelta 亮度增量（-1..1）；无关手势为 null
 */
data class AppGalleryPreviewGestureResult(
    val gesture: AppGalleryPreviewPlaybackGesture = AppGalleryPreviewPlaybackGesture.NONE,
    val seekTargetMs: Long? = null,
    val seekDeltaMs: Long? = null,
    val volumeDelta: Float? = null,
    val brightnessDelta: Float? = null,
    val togglePlaying: Boolean? = null,
)

/**
 * 播放手势纯映射（自研，纯 JVM，可单测）。
 *
 * 自研理由：media3 的 `PlayerView` 只处理点击显示控制条，不提供"双击快进 / 竖滑调音量亮度"
 * 这类手势语义；且灵敏度、步长都必须可配置（硬约束），第三方组件无法满足。
 * 与 ExoPlayer 的接线只在 Compose 侧调用 `player.seekTo(...)`，本类保持无 Android 依赖。
 */
object AppGalleryPreviewPlaybackGestureMath {

    /** 单击解析：是否切换播放状态。 */
    fun resolveSingleTap(
        isPlaying: Boolean,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewGestureResult {
        val safe = config.appGalleryPreviewSafe().config
        if (!safe.playbackGestureEnabled || !safe.singleTapTogglesPlayback) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        return AppGalleryPreviewGestureResult(
            gesture = AppGalleryPreviewPlaybackGesture.TAP_TOGGLE,
            togglePlaying = !isPlaying,
        )
    }

    /**
     * 双击解析：按半屏位置快进/快退。
     *
     * @param tapXRatio 点击位置在视口内的横向比例 0..1（<0.5 视为左半屏）
     */
    fun resolveDoubleTap(
        currentMs: Long,
        tapXRatio: Float,
        durationMs: Long,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewGestureResult {
        val safe = config.appGalleryPreviewSafe().config
        if (!safe.playbackGestureEnabled) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        val ratio = if (tapXRatio.isFinite()) tapXRatio.coerceIn(0f, 1f) else 0.5f
        val base = if (currentMs < 0L) 0L else currentMs
        return if (ratio < 0.5f) {
            val target = AppGalleryPreviewBookmarkMath.clampSeek(base - safe.doubleTapSeekMs, durationMs)
            AppGalleryPreviewGestureResult(
                gesture = AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_BACKWARD,
                seekTargetMs = target,
                seekDeltaMs = target - base,
            )
        } else {
            val target = AppGalleryPreviewBookmarkMath.clampSeek(base + safe.doubleTapSeekMs, durationMs)
            AppGalleryPreviewGestureResult(
                gesture = AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD,
                seekTargetMs = target,
                seekDeltaMs = target - base,
            )
        }
    }

    /**
     * 横向拖动解析：按 dp 灵敏度把位移换算成时间增量。
     *
     * @param dxPx 位移（px，右=正=前进）
     * @param density dp↔px 缩放（由调用方从 DisplayMetrics 提供；<=0 视为 1）
     */
    fun resolveHorizontalDrag(
        startMs: Long,
        dxPx: Float,
        durationMs: Long,
        density: Float,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewGestureResult {
        val safe = config.appGalleryPreviewSafe().config
        if (!safe.playbackGestureEnabled) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        if (!dxPx.isFinite() || dxPx == 0f) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        val d = if (density.isFinite() && density > 0f) density else 1f
        val base = if (startMs < 0L) 0L else startMs
        val deltaMs = (dxPx / d * safe.dragSeekSensitivityMsPerDp).toLong()
        val target = AppGalleryPreviewBookmarkMath.clampSeek(base + deltaMs, durationMs)
        return AppGalleryPreviewGestureResult(
            gesture = AppGalleryPreviewPlaybackGesture.HORIZONTAL_SEEK,
            seekTargetMs = target,
            seekDeltaMs = target - base,
        )
    }

    /**
     * 竖向拖动解析：左侧调亮度、右侧调音量。
     *
     * @param dyPx 位移（px，下=正；上行增大）。
     * @param startXRatio 起始位置的横向比例（<0.5 为左半屏 → 亮度）
     */
    fun resolveVerticalDrag(
        startXRatio: Float,
        dyPx: Float,
        viewportHeightPx: Float,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewGestureResult {
        val safe = config.appGalleryPreviewSafe().config
        if (!safe.playbackGestureEnabled) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        if (!dyPx.isFinite() || dyPx == 0f) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        if (!viewportHeightPx.isFinite() || viewportHeightPx <= 0f) {
            return AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.IGNORED)
        }
        val ratio = if (startXRatio.isFinite()) startXRatio.coerceIn(0f, 1f) else 0.5f
        // 上行（dy<0）为增大，故取负号。
        val normalized = (-dyPx / viewportHeightPx).coerceIn(-1f, 1f)
        return if (ratio < 0.5f) {
            AppGalleryPreviewGestureResult(
                gesture = AppGalleryPreviewPlaybackGesture.VERTICAL_BRIGHTNESS,
                brightnessDelta = (normalized * safe.verticalDragBrightnessSensitivity).coerceIn(-1f, 1f),
            )
        } else {
            AppGalleryPreviewGestureResult(
                gesture = AppGalleryPreviewPlaybackGesture.VERTICAL_VOLUME,
                volumeDelta = (normalized * safe.verticalDragVolumeSensitivity).coerceIn(-1f, 1f),
            )
        }
    }

    /** 把增量应用到起始值并 clamp 到 0..1（音量/亮度）。 */
    fun applyClampedDelta(start: Float, delta: Float): Float {
        val s = if (start.isFinite()) start.coerceIn(0f, 1f) else 0f
        val d = if (delta.isFinite()) delta else 0f
        return (s + d).coerceIn(0f, 1f)
    }

    /** 手势提示文案键（供 strings 拼接）。 */
    fun indicatorKey(result: AppGalleryPreviewGestureResult): String? = when (result.gesture) {
        AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD -> "agp_gesture_forward"
        AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_BACKWARD -> "agp_gesture_backward"
        AppGalleryPreviewPlaybackGesture.VERTICAL_VOLUME -> "agp_gesture_volume"
        AppGalleryPreviewPlaybackGesture.VERTICAL_BRIGHTNESS -> "agp_gesture_brightness"
        AppGalleryPreviewPlaybackGesture.HORIZONTAL_SEEK -> "agp_gesture_seek"
        else -> null
    }
}
