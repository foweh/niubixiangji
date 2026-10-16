package com.hinnka.mycamera.ui.galleryedit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.galleryedit.GeCropConfig
import com.hinnka.mycamera.galleryedit.GeCropHandle
import com.hinnka.mycamera.galleryedit.GeCropMath
import com.hinnka.mycamera.galleryedit.GeCropRatio
import com.hinnka.mycamera.galleryedit.GeCropRatioSpec
import com.hinnka.mycamera.galleryedit.GeCropRect
import com.hinnka.mycamera.galleryedit.GeSize
import kotlin.math.abs

/**
 * 自研裁剪面板：可拖动裁剪框 + 比例档位 + 左/右 90° 旋转。
 *
 * 交互语义参考 Yalantis/uCrop（12073★）的四角/四边手柄与固定比例对角锁定；
 * **不引入 uCrop 依赖**（其形态是 View + 独立裁剪 Activity，接入需要额外注册与资源，
 * 与"Compose 内联面板 + 最小改动"的目标不符），几何全部由纯逻辑 [GeCropMath] 完成，
 * 因此拖动约束与单测验证的是同一套代码。
 */
@Composable
fun GeCropPanel(
    source: Bitmap?,
    crop: GeCropRect,
    ratio: GeCropRatioSpec,
    config: GeCropConfig,
    onCropChange: (GeCropRect) -> Unit,
    onRatioChange: (GeCropRatioSpec) -> Unit,
    onRotate: (Int) -> Unit,
    labelFree: String,
    labelOriginal: String,
    labelSquare: String,
    label3x4: String,
    label9x16: String,
    labelRotateLeft: String,
    labelRotateRight: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val safeConfig = remember(config) { config.clamp().first }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (source != null && !source.isRecycled) {
                GeCropOverlay(
                    bitmap = source,
                    crop = crop,
                    ratio = ratio,
                    config = safeConfig,
                    onCropChange = onCropChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        GeCropControls(
            ratio = ratio,
            onRatioChange = onRatioChange,
            onRotate = onRotate,
            labelFree = labelFree,
            labelOriginal = labelOriginal,
            labelSquare = labelSquare,
            label3x4 = label3x4,
            label9x16 = label9x16,
            labelRotateLeft = labelRotateLeft,
            labelRotateRight = labelRotateRight,
        )
    }
}

/** 比例档位 + 旋转按钮行。 */
@Composable
fun GeCropControls(
    ratio: GeCropRatioSpec,
    onRatioChange: (GeCropRatioSpec) -> Unit,
    onRotate: (Int) -> Unit,
    labelFree: String,
    labelOriginal: String,
    labelSquare: String,
    label3x4: String,
    label9x16: String,
    labelRotateLeft: String,
    labelRotateRight: String,
    modifier: Modifier = Modifier,
) {
    val specs = listOf(
        GeCropRatioSpec.FREE to labelFree,
        GeCropRatioSpec(GeCropRatio.ORIGINAL) to labelOriginal,
        GeCropRatioSpec.SQUARE to labelSquare,
        GeCropRatioSpec.RATIO_3_4 to label3x4,
        GeCropRatioSpec(GeCropRatio.RATIO_9_16) to label9x16,
    )
    GeChipRow(
        items = specs.map { it.second } + listOf(labelRotateLeft, labelRotateRight),
        selectedIndex = specs.indexOfFirst { it.first == ratio },
        onSelect = { index ->
            when {
                index < specs.size -> onRatioChange(specs[index].first)
                index == specs.size -> onRotate(-90)
                else -> onRotate(90)
            }
        },
        modifier = modifier,
    )
}

/**
 * 裁剪遮罩层：暗化框外 + 白框 + 三分构图线 + 手柄拖动。
 *
 * 触摸半径按 dp→px 换算；命中判定与约束求解委托 [GeCropMath]，与单测同源。
 */
@Composable
fun GeCropOverlay(
    bitmap: Bitmap,
    crop: GeCropRect,
    ratio: GeCropRatioSpec,
    config: GeCropConfig,
    onCropChange: (GeCropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf(GeCropHandle.NONE) }
    val density = LocalDensity.current.density
    val touchRadiusPx = remember(config, density) { config.handleTouchRadiusDp * density }
    val sourceSize = GeSize(bitmap.width, bitmap.height)
    val display = fitDisplayRect(
        containerWidth = containerSize.width,
        containerHeight = containerSize.height,
        sourceWidth = bitmap.width,
        sourceHeight = bitmap.height,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(crop, display, ratio, sourceSize, touchRadiusPx) {
                detectDragGestures(
                    onDragStart = { position ->
                        activeHandle = hitTest(position, crop, display, touchRadiusPx)
                    },
                    onDragEnd = { activeHandle = GeCropHandle.NONE },
                    onDragCancel = { activeHandle = GeCropHandle.NONE },
                ) { change, dragAmount ->
                    if (display.width <= 0f || display.height <= 0f) return@detectDragGestures
                    val dx = dragAmount.x / display.width
                    val dy = dragAmount.y / display.height
                    val next = if (activeHandle == GeCropHandle.NONE) {
                        GeCropMath.move(crop, dx, dy)
                    } else {
                        GeCropMath.resize(
                            rect = crop,
                            handle = activeHandle,
                            deltaX = dx,
                            deltaY = dy,
                            source = sourceSize,
                            spec = ratio,
                            minFraction = config.minFraction,
                        )
                    }
                    onCropChange(if (config.snapToBounds) next else GeCropMath.clampToUnit(next))
                }
            }
            .drawWithContent {
                drawContent()
                if (display.width <= 0f || display.height <= 0f) return@drawWithContent
                val left = display.left + crop.left * display.width
                val top = display.top + crop.top * display.height
                val right = display.left + crop.right * display.width
                val bottom = display.top + crop.bottom * display.height
                val frameW = (right - left).coerceAtLeast(0f)
                val frameH = (bottom - top).coerceAtLeast(0f)
                val mask = Color(0xAA000000)

                // 上 / 下
                drawRect(mask, size = Size(size.width, top.coerceIn(0f, size.height)))
                val bottomTop = bottom.coerceIn(0f, size.height)
                drawRect(
                    color = mask,
                    topLeft = Offset(0f, bottomTop),
                    size = Size(size.width, (size.height - bottomTop).coerceAtLeast(0f)),
                )
                // 左 / 右（仅裁剪框纵向范围内）
                val bandTop = top.coerceIn(0f, size.height)
                val bandHeight = frameH.coerceAtMost(size.height - bandTop)
                if (bandHeight > 0f) {
                    drawRect(
                        color = mask,
                        topLeft = Offset(0f, bandTop),
                        size = Size(left.coerceIn(0f, size.width), bandHeight),
                    )
                    val bandRight = right.coerceIn(0f, size.width)
                    drawRect(
                        color = mask,
                        topLeft = Offset(bandRight, bandTop),
                        size = Size((size.width - bandRight).coerceAtLeast(0f), bandHeight),
                    )
                }
                // 边框
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(frameW, frameH),
                    style = Stroke(width = 2f),
                )
                // 三分构图线
                if (config.showGridLines) {
                    for (i in 1..2) {
                        val x = left + frameW / 3f * i
                        val y = top + frameH / 3f * i
                        drawLine(
                            color = Color(0x66FFFFFF),
                            start = Offset(x, top),
                            end = Offset(x, bottom),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            color = Color(0x66FFFFFF),
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 1f,
                        )
                    }
                }
                // 四角手柄（视觉尺寸固定为 12dp 的一半，不随触摸半径撑大）
                val cornerHalf = 6f * density
                listOf(
                    Offset(left, top),
                    Offset(right, top),
                    Offset(left, bottom),
                    Offset(right, bottom),
                ).forEach { corner ->
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(corner.x - cornerHalf, corner.y - cornerHalf),
                        size = Size(cornerHalf * 2f, cornerHalf * 2f),
                        style = Stroke(width = 3f),
                    )
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (display.width > 0f && display.height > 0f) {
                        clipRect(
                            left = display.left,
                            top = display.top,
                            right = display.right,
                            bottom = display.bottom,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        this@drawWithContent.drawContent()
                    }
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/** 源图在容器内按 Fit 缩放后的实际显示矩形（letterbox 之后）。 */
internal data class GeDisplayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** 计算 Fit 显示矩形；容器或源尺寸非法时返回全零矩形。 */
internal fun fitDisplayRect(
    containerWidth: Int,
    containerHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): GeDisplayRect {
    if (containerWidth <= 0 || containerHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return GeDisplayRect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(
        containerWidth.toFloat() / sourceWidth.toFloat(),
        containerHeight.toFloat() / sourceHeight.toFloat(),
    )
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return GeDisplayRect(left, top, left + width, top + height)
}

/** 触摸命中：优先四角、其次四边，未命中手柄视为框内拖动（整体平移）。 */
internal fun hitTest(
    position: Offset,
    crop: GeCropRect,
    display: GeDisplayRect,
    touchRadiusPx: Float,
): GeCropHandle {
    if (display.width <= 0f || display.height <= 0f) return GeCropHandle.NONE
    val nx = (position.x - display.left) / display.width
    val ny = (position.y - display.top) / display.height
    val radiusX = (touchRadiusPx / display.width).coerceAtLeast(0.005f)
    val radiusY = (touchRadiusPx / display.height).coerceAtLeast(0.005f)
    val nearLeft = abs(nx - crop.left) <= radiusX
    val nearRight = abs(nx - crop.right) <= radiusX
    val nearTop = abs(ny - crop.top) <= radiusY
    val nearBottom = abs(ny - crop.bottom) <= radiusY
    return when {
        nearLeft && nearTop -> GeCropHandle.TOP_LEFT
        nearRight && nearTop -> GeCropHandle.TOP_RIGHT
        nearLeft && nearBottom -> GeCropHandle.BOTTOM_LEFT
        nearRight && nearBottom -> GeCropHandle.BOTTOM_RIGHT
        nearLeft -> GeCropHandle.LEFT
        nearRight -> GeCropHandle.RIGHT
        nearTop -> GeCropHandle.TOP
        nearBottom -> GeCropHandle.BOTTOM
        else -> GeCropHandle.NONE
    }
}
