package com.hinnka.mycamera.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import com.hinnka.mycamera.viewfinder.HistogramAnchor
import com.hinnka.mycamera.viewfinder.HistogramOverlayConfig
import com.hinnka.mycamera.viewfinder.ViewfinderHistogram
import com.hinnka.mycamera.viewfinder.ViewfinderHistogramChannel
import com.hinnka.mycamera.viewfinder.ViewfinderHistogramSmoother

/**
 * 取景器实时直方图叠加层（亮度 + RGB 三通道）。
 *
 * 与既有 `ui/components/HistogramView` 的区别：后者是画廊/曲线编辑器用的**固定尺寸单通道**小图；
 * 本组件是**取景器叠加层**——位置/大小/透明度/通道/平滑全部可配，并跟随预览裁切区对齐。
 *
 * 数据来源是取景器预览帧（[ViewfinderHistogram]），与 `CameraState.histogram` 那套内部曝光统计
 * 直方图无关，互不干扰。
 *
 * @param histogram 最新一帧直方图（null 时不绘制）
 * @param aspectRatio 预览宽高比（与 GridOverlay 同一套裁切算法，保证与网格/水平仪对齐）
 * @param config 叠加层配置（位置/尺寸/透明度/通道开关）
 */
@Composable
fun ViewfinderHistogramOverlay(
    histogram: ViewfinderHistogram?,
    aspectRatio: Float,
    config: HistogramOverlayConfig,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled) return

    val smoother = remember { ViewfinderHistogramSmoother(config.smoothing) }
    var display by remember { mutableStateOf<Map<ViewfinderHistogramChannel, FloatArray>?>(null) }

    LaunchedEffect(histogram, config.smoothing, config.binCount, config.enabled) {
        smoother.smoothingFactor = config.smoothing
        display = histogram?.let { smoother.update(it) }
    }

    val channels = display ?: return

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            if (!aspectRatio.isFinite() || aspectRatio <= 0f) return@Canvas

            val width = minOf(size.width, size.height * aspectRatio)
            val height = minOf(size.height, size.width / aspectRatio)
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f

            val panelWidth = width * config.widthFraction
            val panelHeight = height * config.heightFraction
            val margin = minOf(width, height) * config.marginFraction
            val panelLeft: Float
            val panelTop: Float
            when (config.anchor) {
                HistogramAnchor.TOP_LEFT -> {
                    panelLeft = left + margin
                    panelTop = top + margin
                }

                HistogramAnchor.TOP_CENTER -> {
                    panelLeft = left + (width - panelWidth) / 2f
                    panelTop = top + margin
                }

                HistogramAnchor.TOP_RIGHT -> {
                    panelLeft = left + width - panelWidth - margin
                    panelTop = top + margin
                }

                HistogramAnchor.BOTTOM_LEFT -> {
                    panelLeft = left + margin
                    panelTop = top + height - panelHeight - margin
                }

                HistogramAnchor.BOTTOM_RIGHT -> {
                    panelLeft = left + width - panelWidth - margin
                    panelTop = top + height - panelHeight - margin
                }
            }

            clipRect(left, top, left + width, top + height) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.45f * config.alpha),
                    topLeft = Offset(panelLeft, panelTop),
                    size = androidx.compose.ui.geometry.Size(panelWidth, panelHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.28f * config.alpha),
                    topLeft = Offset(panelLeft, panelTop),
                    size = androidx.compose.ui.geometry.Size(panelWidth, panelHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                    style = Stroke(width = 1.5f),
                )

                if (config.showLuma) {
                    channels[ViewfinderHistogramChannel.LUMA]?.let {
                        drawChannel(it, panelLeft, panelTop, panelWidth, panelHeight, Color.White, config.alpha)
                    }
                }
                if (config.showRed) {
                    channels[ViewfinderHistogramChannel.RED]?.let {
                        drawChannel(it, panelLeft, panelTop, panelWidth, panelHeight, CHANNEL_RED, config.alpha)
                    }
                }
                if (config.showGreen) {
                    channels[ViewfinderHistogramChannel.GREEN]?.let {
                        drawChannel(it, panelLeft, panelTop, panelWidth, panelHeight, CHANNEL_GREEN, config.alpha)
                    }
                }
                if (config.showBlue) {
                    channels[ViewfinderHistogramChannel.BLUE]?.let {
                        drawChannel(it, panelLeft, panelTop, panelWidth, panelHeight, CHANNEL_BLUE, config.alpha)
                    }
                }
            }
        }
    }
}

private val CHANNEL_RED = Color(0xFFFF5252)
private val CHANNEL_GREEN = Color(0xFF69F0AE)
private val CHANNEL_BLUE = Color(0xFF448AFF)

/** 以「填充 + 半透明描边」绘制单通道曲线，三通道叠加时仍能辨别。 */
private fun DrawScope.drawChannel(
    values: FloatArray,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    alpha: Float,
) {
    if (values.isEmpty() || width <= 0f || height <= 0f) return
    val stepX = width / values.size.toFloat()
    val path = Path().apply {
        moveTo(left, top + height)
        for (index in values.indices) {
            val x = left + index * stepX
            val y = top + height - values[index].coerceIn(0f, 1f) * height
            lineTo(x, y)
        }
        lineTo(left + width, top + height)
        close()
    }
    drawPath(path, color = color.copy(alpha = 0.28f * alpha), style = Fill)

    val outline = Path().apply {
        for (index in values.indices) {
            val x = left + index * stepX
            val y = top + height - values[index].coerceIn(0f, 1f) * height
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(outline, color = color.copy(alpha = 0.85f * alpha), style = Stroke(width = 1.5f))
}
