package com.hinnka.mycamera.ui.appgallerypreview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hinnka.mycamera.R
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewCompareMapping
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewCompareUnavailableReason
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import kotlin.math.roundToInt

/** 分割线手柄直径（dp）。 */
private const val HANDLE_SIZE_DP = 40f

/**
 * 前后/原图对比滑杆（相册第 37 项）。
 *
 * 展示两个版本：左侧为「处理前原图」、右侧为「处理后」。拖动或点击任意位置移动分界，
 * 位置↔比例映射完全走 [AppGalleryPreviewCompareMapping]（可单测，含正常/边界/异常分支）。
 *
 * 两个版本必须都存在（由调用方用 [AppGalleryPreviewCompareMapping.availability] 判定，
 * 对应工程既有的"处理前保留原文件"约定）；不可用时调用 [AppGalleryPreviewComparePlaceholder]。
 */
@Composable
fun AppGalleryPreviewCompareSlider(
    originalModel: Any?,
    processedModel: Any?,
    config: AppGalleryPreviewConfig,
    modifier: Modifier = Modifier,
) {
    val safe = config.appGalleryPreviewSafe().config
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { (HANDLE_SIZE_DP / 2f).dp.toPx() }
    var ratio by remember(safe.compareSliderDefaultRatio) { mutableFloatStateOf(safe.compareSliderDefaultRatio) }
    var widthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            .pointerInput(originalModel, processedModel) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        ratio = AppGalleryPreviewCompareMapping.ratioForPosition(start.x, widthPx.toFloat(), 0f)
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // 以当前分界位置为基准累加，映射函数负责 clamp 到 0..1。
                        ratio = AppGalleryPreviewCompareMapping.ratioForPosition(
                            xPx = widthPx * ratio + dragAmount,
                            widthPx = widthPx.toFloat(),
                            handleHalfPx = 0f,
                        )
                    },
                )
            }
            .pointerInput(originalModel, processedModel) {
                detectTapGestures { offset ->
                    ratio = AppGalleryPreviewCompareMapping.ratioForPosition(offset.x, widthPx.toFloat(), 0f)
                }
            },
    ) {
        // 底层：处理后版本
        AsyncImage(
            model = processedModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        // 上层：处理前原图，按比例裁剪显示（clipRect 不影响布局，两层像素对齐）
        AsyncImage(
            model = originalModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * ratio) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        // 分割线：精确定位到分界，故不加 align（父 Box 默认 TopStart 对齐）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .offset { IntOffset((widthPx * ratio).roundToInt(), 0) }
                .background(Color.White.copy(alpha = 0.9f)),
        )
        // 手柄：先垂直居中，再在水平方向偏移到分界处
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((widthPx * ratio - handleRadiusPx).roundToInt(), 0) }
                .size(HANDLE_SIZE_DP.dp)
                .background(Color.White.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(1.dp)),
            )
        }
        if (safe.compareShowLabels) {
            AppGalleryPreviewCompareLabel(
                text = stringResource(R.string.agp_label_original),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            AppGalleryPreviewCompareLabel(
                text = stringResource(R.string.agp_label_processed),
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }
}

@Composable
private fun AppGalleryPreviewCompareLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 对比不可用时的占位提示。
 *
 * @param reason 来自 [AppGalleryPreviewCompareMapping.availability]；用 [appGalleryPreviewCompareReasonKey] 转换
 */
@Composable
fun AppGalleryPreviewComparePlaceholder(reason: AppGalleryPreviewCompareUnavailableReason, modifier: Modifier = Modifier) {
    val text = when (reason) {
        AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL ->
            stringResource(R.string.agp_compare_unavailable_missing_original)

        AppGalleryPreviewCompareUnavailableReason.MISSING_PROCESSED ->
            stringResource(R.string.agp_compare_unavailable_missing_processed)

        AppGalleryPreviewCompareUnavailableReason.SAME_SOURCE ->
            stringResource(R.string.agp_compare_unavailable_same_source)

        AppGalleryPreviewCompareUnavailableReason.LOAD_FAILED ->
            stringResource(R.string.agp_compare_unavailable_load_failed)

        AppGalleryPreviewCompareUnavailableReason.NONE ->
            stringResource(R.string.agp_compare_unavailable_generic)
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
    }
}

/** 可用性原因 → 快捷操作禁用原因键（供 chrome 文本拼接复用）。 */
fun appGalleryPreviewCompareReasonKey(reason: AppGalleryPreviewCompareUnavailableReason): String? =
    when (reason) {
        AppGalleryPreviewCompareUnavailableReason.NONE -> null
        AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL -> "needs_two_versions"
        AppGalleryPreviewCompareUnavailableReason.MISSING_PROCESSED -> "needs_two_versions"
        AppGalleryPreviewCompareUnavailableReason.SAME_SOURCE -> "needs_two_versions"
        AppGalleryPreviewCompareUnavailableReason.LOAD_FAILED -> "needs_two_versions"
    }
