package com.hinnka.mycamera.ui.appgallerypreview

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaKind
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewZoomState
import com.hinnka.mycamera.appgallerypreview.PreviewScaleRenderer
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * 大图预览面板（相册第 34 项）。
 *
 * 两条渲染路径（由 [AppGalleryPreviewConfig.previewScaleRenderer] 选择，可配置）：
 *  1. [PreviewScaleRenderer.TELEPHOTO]（默认）：复用工程既有依赖
 *     `me.saket.telephoto:zoomable-image-coil:0.18.0`（Apache-2.0，saket/telephoto，1.6k★），
 *     与 `ui/gallery/GalleryDetailScreen.kt` 中既有用法保持一致（零新增依赖）；
 *  2. [PreviewScaleRenderer.COMPOSE_GESTURE]：自研 Compose 手势 + [AppGalleryPreviewZoomState]，
 *     用于需要完全自定义手势仲裁/缩放下限的场景，或 Telephoto 不可用时降级。
 *
 * 两条路径的缩放上限都来自配置（默认 1x~8x），且都经 clamp。
 */
@Composable
fun AppGalleryPreviewZoomablePane(
    mediaUri: String,
    mediaKind: AppGalleryPreviewMediaKind,
    config: AppGalleryPreviewConfig,
    isActive: Boolean,
    /** 是否处于放大状态（供翻页手势仲裁使用）。 */
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** 非空时优先用该位图作为图像模型（RAW/DNG 预览由后台解码得到）。 */
    bitmapModel: Bitmap? = null,
) {
    val imageModel: Any = bitmapModel ?: mediaUri
    when {
        mediaKind == AppGalleryPreviewMediaKind.GIF -> {
            onZoomChange(false)
            AppGalleryPreviewGifPane(
                mediaUri = mediaUri,
                config = config,
                isActive = isActive,
                modifier = modifier,
            )
        }

        config.previewScaleRenderer == PreviewScaleRenderer.TELEPHOTO -> {
            AppGalleryPreviewTelephotoPane(
                imageModel = imageModel,
                config = config,
                onZoomChange = onZoomChange,
                modifier = modifier,
                contentDescription = contentDescription,
            )
        }

        else -> {
            AppGalleryPreviewSelfGesturePane(
                imageModel = imageModel,
                config = config,
                onZoomChange = onZoomChange,
                modifier = modifier,
                contentDescription = contentDescription,
            )
        }
    }
}

/**
 * Telephoto 渲染路径（复用工程既有依赖）。
 *
 * 说明：`ZoomSpec(maxZoomFactor = ...)` 的取值来自配置并且已 clamp；
 * 双击档位由 [DoubleClickToZoomListener.cycle] 在 min 与给定倍率之间循环。
 */
@Composable
private fun AppGalleryPreviewTelephotoPane(
    imageModel: Any,
    config: AppGalleryPreviewConfig,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier,
    contentDescription: String?,
) {
    val context = LocalContext.current
    val safe = config.appGalleryPreviewSafe().config
    val maxZoom = safe.safeZoomRange.endInclusive.coerceAtLeast(1.1f)
    val zoomableState = rememberZoomableImageState(
        zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = maxZoom)),
    )
    val zoomFraction = zoomableState.zoomableState.zoomFraction ?: 0f
    LaunchedEffect(zoomFraction) {
        onZoomChange(zoomFraction > 0.01f)
    }
    val request = remember(imageModel) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .crossfade(false)
            .build()
    }
    Box(modifier = modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            state = zoomableState,
            onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = safe.doubleTapZoomFactor),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 自研 Compose 手势渲染路径。
 *
 * 自研理由（PATTERN_SPEC §9）：需要在同一处完成「1x~8x 可配范围 + 双击三档循环 + 边界回弹 +
 * 与翻页/对比滑杆的手势仲裁」，且状态机必须可 JVM 单测（见 `AppGalleryPreviewZoomStateTest`）。
 * Telephoto 未暴露可配置的缩放范围与可测状态机；PhotoView 系列为 View 体系，与 Compose 不兼容，
 * 因此只参考其算法思路（scale 钳制 / edge bound / 双击档位），实现由本模块自研。
 */
@Composable
private fun AppGalleryPreviewSelfGesturePane(
    imageModel: Any,
    config: AppGalleryPreviewConfig,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier,
    contentDescription: String?,
) {
    val context = LocalContext.current
    val zoomState = remember(imageModel, config) { AppGalleryPreviewZoomState(config = config) }
    var scale by remember(imageModel) { mutableStateOf(zoomState.transform.scale) }
    var offsetX by remember(imageModel) { mutableStateOf(0f) }
    var offsetY by remember(imageModel) { mutableStateOf(0f) }

    fun sync() {
        scale = zoomState.transform.scale
        offsetX = zoomState.transform.offsetX
        offsetY = zoomState.transform.offsetY
    }

    LaunchedEffect(scale) {
        onZoomChange(zoomState.isZoomedIn)
    }

    val request = remember(imageModel) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .crossfade(false)
            .build()
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(imageModel) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomState.onPinch(zoom)
                        zoomState.onPan(pan.x, pan.y, size.width.toFloat(), size.height.toFloat())
                        sync()
                    }
                }
                .pointerInput(imageModel) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoomState.onDoubleTap()
                            sync()
                        },
                    )
                },
        )
    }
}

/**
 * GIF 播放面板（相册第 38 项）。
 *
 * 复用说明：GIF 动图解码复用 **Android 平台** `ImageDecoder` → `AnimatedImageDrawable`
 * （minSdk 30 已满足，零新增依赖）。选择平台解码而非 Coil 的原因：工程使用 Coil 2.7.0，
 * 其动图支持需要额外引入 `io.coil-kt:coil-gif` artifact；为遵守「尽量不加新依赖」的硬约束，
 * 这里直接用平台能力，Coil 仍负责静图路径。
 *
 * 播放/暂停与循环由 [AppGalleryPreviewConfig.gifAutoPlay] / [AppGalleryPreviewConfig.gifLoop] 控制。
 */
@Composable
fun AppGalleryPreviewGifPane(
    mediaUri: String,
    config: AppGalleryPreviewConfig,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val safe = config.appGalleryPreviewSafe().config
    val drawable by produceState<Drawable?>(initialValue = null, mediaUri, safe.gifMaxDecodeDimensionPx) {
        value = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, android.net.Uri.parse(mediaUri))
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                val limit = safe.gifMaxDecodeDimensionPx
                if (longEdge > limit && limit > 0) {
                    val ratio = limit.toDouble() / longEdge.toDouble()
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { imageView ->
            imageView.setImageDrawable(drawable)
            val animated = drawable as? AnimatedImageDrawable
            if (animated != null) {
                animated.repeatCount = if (safe.gifLoop) AnimatedImageDrawable.REPEAT_INFINITE else 0
                if (safe.gifAutoPlay && isActive) {
                    animated.start()
                } else {
                    animated.stop()
                }
            }
        },
    )
}
