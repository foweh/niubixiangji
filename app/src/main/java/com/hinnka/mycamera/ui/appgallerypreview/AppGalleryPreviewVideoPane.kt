package com.hinnka.mycamera.ui.appgallerypreview

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hinnka.mycamera.R
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBookmark
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBookmarkMath
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewGestureResult
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGestureMath
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 视频预览播放面板（相册第 38 + 41 项）。
 *
 * 复用说明：播放器复用工程既有依赖 `androidx.media3:media3-exoplayer:1.10.0` / `media3-ui:1.10.0`
 * （Apache-2.0，与 `ui/gallery/GalleryDetailScreen.kt` 中既有 `ExoPlayer.Builder(context).build()`
 * + `PlayerView` 用法一致），零新增依赖。
 *
 * 手势（全部可配置，语义由 [AppGalleryPreviewPlaybackGestureMath] 纯函数计算，可单测）：
 *  - 单击：播放/暂停；
 *  - 双击左/右半屏：快退/快进（步长可配，默认 10s）；
 *  - 横向拖动：进度拖动（松手生效）；
 *  - 竖向拖动：左半屏亮度、右半屏音量（上行增大）。
 *
 * 书签：书签数据由画廊编辑模块经 `AppGalleryPreviewBookmarkSource` 提供（本模块只做播放器层跳转）。
 */
@Composable
fun AppGalleryPreviewVideoPane(
    mediaUri: String,
    config: AppGalleryPreviewConfig,
    bookmarks: List<AppGalleryPreviewBookmark>,
    isActive: Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    /** 是否展示书签条（由"书签"快捷操作开关控制）。 */
    showBookmarks: Boolean = true,
    /** 外部请求切换播放/暂停的计数（0 表示无请求；每次 +1 触发一次切换）。 */
    playToggleTicks: Int = 0,
    /** 亮度增量（-1..1）交由宿主应用（需要 Activity window，本组件不持有）。 */
    onBrightnessDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safe = config.appGalleryPreviewSafe().config
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val exoPlayer = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            repeatMode = if (safe.videoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = safe.videoAutoPlay
        }
    }

    DisposableEffect(mediaUri) {
        onDispose { runCatching { exoPlayer.release() } }
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            runCatching { exoPlayer.pause() }
        }
    }

    // 外部（快捷操作栏"播放/暂停"）请求切换：以计数作为触发键，>0 才生效。
    LaunchedEffect(playToggleTicks) {
        if (playToggleTicks > 0) {
            runCatching { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }
        }
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(AppGalleryPreviewBookmarkMath.DURATION_UNKNOWN) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var hintResult by remember { mutableStateOf<AppGalleryPreviewGestureResult?>(null) }
    val density = LocalDensity.current.density

    // 播放进度轮询（250ms；协程随组件销毁自动取消）。
    LaunchedEffect(exoPlayer) {
        while (true) {
            durationMs = exoPlayer.duration.takeIf { it > 0L } ?: AppGalleryPreviewBookmarkMath.DURATION_UNKNOWN
            val playing = exoPlayer.isPlaying
            if (playing != isPlaying) {
                isPlaying = playing
                onPlayingChanged(playing)
            }
            delay(250L)
        }
    }

    // 提示浮层自动消失（延时来自配置）。
    LaunchedEffect(hintResult) {
        if (hintResult != null) {
            delay(safe.gestureIndicatorTimeoutMs)
            hintResult = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = safe.videoShowControls
                    player = exoPlayer
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.useController = safe.videoShowControls
            },
        )

        if (safe.playbackGestureEnabled) {
            // 单击/双击（播放暂停、快进快退）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mediaUri, viewport) {
                        detectTapGestures(
                            onTap = {
                                val result = AppGalleryPreviewPlaybackGestureMath.resolveSingleTap(isPlaying, safe)
                                result.togglePlaying?.let { target ->
                                    runCatching {
                                        if (target) exoPlayer.play() else exoPlayer.pause()
                                    }
                                }
                            },
                            onDoubleTap = { offset ->
                                val ratio = if (viewport.width > 0) {
                                    offset.x / viewport.width.toFloat()
                                } else {
                                    0.5f
                                }
                                val result = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(
                                    currentMs = exoPlayer.currentPosition,
                                    tapXRatio = ratio,
                                    durationMs = durationMs,
                                    config = safe,
                                )
                                applySeek(exoPlayer, result)
                                hintResult = result
                            },
                        )
                    },
            )
            // 横向拖动：进度（松手生效）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mediaUri, durationMs) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                seekPreviewMs?.let { runCatching { exoPlayer.seekTo(it) } }
                                seekPreviewMs = null
                                hintResult = null
                            },
                            onDragCancel = {
                                seekPreviewMs = null
                                hintResult = null
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                val result = AppGalleryPreviewPlaybackGestureMath.resolveHorizontalDrag(
                                    startMs = seekPreviewMs ?: exoPlayer.currentPosition,
                                    dxPx = dragAmount,
                                    durationMs = durationMs,
                                    density = density,
                                    config = safe,
                                )
                                result.seekTargetMs?.let {
                                    seekPreviewMs = it
                                    hintResult = result
                                }
                            },
                        )
                    },
            )
            // 竖向拖动：左亮度 / 右音量
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mediaUri, durationMs) {
                        var startXRatio = 0.5f
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                startXRatio = if (viewport.width > 0) {
                                    offset.x / viewport.width.toFloat()
                                } else {
                                    0.5f
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val result = AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(
                                    startXRatio = startXRatio,
                                    dyPx = dragAmount,
                                    viewportHeightPx = viewport.height.toFloat(),
                                    config = safe,
                                )
                                applyVerticalGesture(result, audioManager, onBrightnessDelta)
                                hintResult = result
                            },
                        )
                    },
            )
        }

        // 手势提示浮层（文本在组合期生成，手势回调里只保存结果，避免在非组合作用域调用 @Composable）
        val hintLabel = hintResult?.let { gestureHintLabel(it) }
        if (hintLabel != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.66f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            ) {
                Text(
                    text = hintLabel,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 书签时间点跳转
        val normalizedBookmarks = remember(bookmarks, durationMs) {
            AppGalleryPreviewBookmarkMath.normalize(bookmarks, durationMs)
        }
        if (showBookmarks && normalizedBookmarks.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                items(normalizedBookmarks, key = { it.id }) { bookmark ->
                    Surface(
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.pointerInput(bookmark.id) {
                            detectTapGestures {
                                runCatching {
                                    exoPlayer.seekTo(
                                        AppGalleryPreviewBookmarkMath.clampSeek(bookmark.positionMs, durationMs),
                                    )
                                }
                            }
                        },
                    ) {
                        Text(
                            text = bookmark.label
                                ?: formatBookmarkTime(bookmark.positionMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 应用一次 seek 结果（仅当结果带有目标时间）。 */
private fun applySeek(player: ExoPlayer, result: AppGalleryPreviewGestureResult) {
    result.seekTargetMs?.let { target ->
        runCatching { player.seekTo(target) }
    }
}

/** 应用竖向拖动结果：音量直接改系统流音量，亮度交给宿主。 */
private fun applyVerticalGesture(
    result: AppGalleryPreviewGestureResult,
    audioManager: AudioManager?,
    onBrightnessDelta: (Float) -> Unit,
) {
    val volumeDelta = result.volumeDelta
    if (volumeDelta != null && audioManager != null) {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                val currentFraction = current.toFloat() / max.toFloat()
                val next = AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(currentFraction, volumeDelta)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (next * max).roundToInt().coerceIn(0, max),
                    0,
                )
            }
        }
    }
    result.brightnessDelta?.let(onBrightnessDelta)
}

/** 手势提示文案（时间用 mm:ss 展示增量）。 */
@Composable
private fun gestureHintLabel(result: AppGalleryPreviewGestureResult): String? =
    when (result.gesture) {
        com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD,
        com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_BACKWARD,
        com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGesture.HORIZONTAL_SEEK,
        -> {
            val delta = result.seekDeltaMs ?: 0L
            val sign = if (delta >= 0) "+" else "-"
            stringResource(R.string.agp_gesture_seek, "$sign${formatBookmarkTime(kotlin.math.abs(delta))}")
        }

        com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGesture.VERTICAL_VOLUME ->
            stringResource(R.string.agp_gesture_volume)

        com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGesture.VERTICAL_BRIGHTNESS ->
            stringResource(R.string.agp_gesture_brightness)

        else -> null
    }

/** 毫秒 → mm:ss。 */
internal fun formatBookmarkTime(positionMs: Long): String {
    val safe = positionMs.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
