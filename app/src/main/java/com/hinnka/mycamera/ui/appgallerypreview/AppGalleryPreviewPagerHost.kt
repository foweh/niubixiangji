package com.hinnka.mycamera.ui.appgallerypreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSwipeDecision
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSwipeOutcome
import kotlin.math.abs
import kotlin.math.roundToInt

/** 翻页动画时长（毫秒）。 */
private const val SWIPE_ANIM_MS = 180

/**
 * 大图预览的滑动翻页容器（相册第 34 项的手势部分）。
 *
 * 设计：物理手势由 foundation 的 `draggable` 承担（提供真实松手速度），
 * 但**是否允许翻页、翻到哪一张**完全由本模块的纯状态机决定：
 *  - [onResolve] 用 `AppGalleryPreviewPagerMath.resolveSwipe` 判定（放大时禁止翻页 → 让位给图片平移）；
 *  - [onCommit] 只在动画结束后提交序号变化，避免"内容先跳、动画后补"的割裂感。
 *
 * 拖动过程中会在反向位置渲染相邻项（由 [content] 按 index 渲染），实现"跟手露出下一张"。
 */
@Composable
fun AppGalleryPreviewPagerHost(
    index: Int,
    count: Int,
    isZoomedIn: Boolean,
    config: AppGalleryPreviewConfig,
    /** 纯判定：不修改任何状态。 */
    onResolve: (dragPx: Float, velocityDpPerSec: Float) -> AppGalleryPreviewSwipeOutcome,
    /** 提交判定结果（在动画结束后调用）。 */
    onCommit: (AppGalleryPreviewSwipeOutcome) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    val safe = config.appGalleryPreviewSafe().config
    val density = LocalDensity.current.density
    var widthPx by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }

    // 序号变化（幻灯片/按钮翻页）时复位拖动残留。
    LaunchedEffect(index) {
        offset.snapTo(0f)
    }

    val draggableState = rememberDraggableState { delta ->
        val limit = widthPx.toFloat().coerceAtLeast(1f) * safe.swipeFollowFactor
        offset.snapTo((offset.value + delta).coerceIn(-limit, limit))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { widthPx = it.width }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = safe.swipeSwitchEnabled && count > 1 && !isZoomedIn,
                startDragImmediately = false,
                onDragStopped = { velocityPxPerSec ->
                    val outcome = onResolve(offset.value, velocityPxPerSec / density)
                    val width = widthPx.toFloat()
                    val target = when (outcome.decision) {
                        AppGalleryPreviewSwipeDecision.PREVIOUS -> width
                        AppGalleryPreviewSwipeDecision.NEXT -> -width
                        AppGalleryPreviewSwipeDecision.NONE -> 0f
                    }
                    if (target != 0f) {
                        offset.animateTo(target, tween(durationMillis = SWIPE_ANIM_MS))
                        onCommit(outcome)
                        // 提交后新序号内容就位，立即复位偏移，视觉上无缝。
                        offset.snapTo(0f)
                    } else {
                        offset.animateTo(0f, tween(durationMillis = SWIPE_ANIM_MS))
                    }
                },
            ),
    ) {
        val dragOffset = offset.value
        // 相邻项：拖动时从反向滑入
        if (abs(dragOffset) > 1f && widthPx > 0) {
            val neighborIndex = if (dragOffset < 0f) index + 1 else index - 1
            if (neighborIndex in 0 until count) {
                val neighborOffset = if (dragOffset < 0f) widthPx + dragOffset else -widthPx + dragOffset
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(neighborOffset.roundToInt(), 0) },
                ) {
                    content(neighborIndex)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(dragOffset.roundToInt(), 0) },
        ) {
            content(index)
        }
    }
}
