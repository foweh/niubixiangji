package com.hinnka.mycamera.ui.galleryedit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.galleryedit.GeCompareConfig
import com.hinnka.mycamera.galleryedit.GeCompareExporter
import com.hinnka.mycamera.galleryedit.GeEditRanges

/**
 * 前后对比滑杆预览：拖动纵向分割线，左侧显示原图、右侧显示处理图。
 *
 * 分割位置换算复用 [GeCompareExporter.splitXPx]，与导出对比图同源，
 * 保证"预览所见 = 导出所得"。
 */
@Composable
fun GeBeforeAfterSlider(
    before: Bitmap,
    after: Bitmap,
    config: GeCompareConfig,
    onSplitChange: (Float) -> Unit,
    beforeLabel: String,
    afterLabel: String,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableFloatStateOf(config.split) }
    val splitPx = GeCompareExporter.splitXPx(config.split, containerSize.width)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(containerSize.width) {
                detectDragGestures(
                    onDragStart = { offset -> updateSplit(offset, containerSize, onSplitChange) },
                    onDragEnd = { onSplitChange(dragging) },
                    onDragCancel = { onSplitChange(dragging) },
                ) { change, _ -> updateSplit(change.position, containerSize, onSplitChange) }
            },
    ) {
        Image(
            bitmap = before.asImageBitmap(),
            contentDescription = beforeLabel,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        // 处理图：仅绘制分割线右侧部分
        Image(
            bitmap = after.asImageBitmap(),
            contentDescription = afterLabel,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = splitPx.toFloat()) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentScale = ContentScale.Fit,
        )
        // 分割线与拖拽把手
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (splitPx in 0..size.width.toInt()) {
                        val x = splitPx.toFloat()
                        drawLine(
                            color = Color.White,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 2f,
                        )
                    }
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GeCompareTag(text = beforeLabel)
            GeCompareTag(text = afterLabel)
        }
    }
}

@Composable
private fun GeCompareTag(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0x66000000))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun updateSplit(
    position: Offset,
    containerSize: IntSize,
    onSplitChange: (Float) -> Unit,
) {
    if (containerSize.width <= 0) return
    val split = (position.x / containerSize.width)
        .coerceIn(GeEditRanges.OPACITY_MIN, GeEditRanges.OPACITY_MAX)
    onSplitChange(split)
}

/** 竖向分割的细把手（供外层布局使用；宽度固定便于点击）。 */
@Composable
fun GeSplitHandle(modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(2.dp).background(Color.White))
}
