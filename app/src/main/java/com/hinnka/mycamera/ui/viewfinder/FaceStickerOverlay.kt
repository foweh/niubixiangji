package com.hinnka.mycamera.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hinnka.mycamera.viewfinder.FaceStickerVariant
import com.hinnka.mycamera.viewfinder.ResolvedSticker
import com.hinnka.mycamera.viewfinder.StickerConfig
import com.hinnka.mycamera.viewfinder.StickerPlacement

/**
 * AR 趣味贴纸叠加层（基于 FaceMesh 人脸锚定）。
 *
 * 全部内置贴纸都是**程序化矢量绘制**（Path/Oval，无 Emoji、无二进制素材、无授权风险），
 * 因此任意分辨率不糊、体积为零。若 [ResolvedSticker.External] 命中外部素材且调用方提供了
 * [externalImage]，则改用位图绘制（IO 由调用方负责，本组件不做任何磁盘/网络访问）。
 *
 * @param placements 每张脸一个最终贴位（由 `FaceStickerAnchorMapper.placementFor` 换算）
 * @param resolved 贴纸解析结果（内置矢量 / 外部位图）
 * @param aspectRatio 预览宽高比（与网格、水平仪、直方图共用同一套裁切算法）
 * @param config 贴纸配置（开关/透明度/镜像）
 * @param externalImage 外部素材位图（仅 [ResolvedSticker.External] 时需要）
 */
@Composable
fun FaceStickerOverlay(
    placements: List<StickerPlacement>,
    resolved: ResolvedSticker,
    aspectRatio: Float,
    config: StickerConfig,
    modifier: Modifier = Modifier,
    externalImage: ImageBitmap? = null,
) {
    if (!config.enabled || placements.isEmpty()) return
    val variant = when (resolved) {
        is ResolvedSticker.BuiltIn -> resolved.definition.variant
        is ResolvedSticker.External -> resolved.definition.variant
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            if (!aspectRatio.isFinite() || aspectRatio <= 0f) return@Canvas

            val width = minOf(size.width, size.height * aspectRatio)
            val height = minOf(size.height, size.width / aspectRatio)
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f

            clipRect(left, top, left + width, top + height) {
                for (placement in placements) {
                    val centerX = left + placement.centerX.coerceIn(0f, 1f) * width
                    val centerY = top + placement.centerY.coerceIn(0f, 1f) * height
                    val stickerWidth = (placement.widthFraction * width).coerceAtLeast(4f)
                    withTransform({
                        translate(left = centerX, top = centerY)
                        if (placement.rollDegrees != 0f) {
                            rotate(placement.rollDegrees, pivot = Offset.Zero)
                        }
                        if (placement.mirrored) {
                            scale(-1f, 1f, pivot = Offset.Zero)
                        }
                    }) {
                        if (variant == FaceStickerVariant.EXTERNAL_BITMAP && externalImage != null) {
                            drawExternalBitmap(externalImage, stickerWidth, placement.alpha)
                        } else {
                            drawBuiltInSticker(variant, stickerWidth, placement.alpha)
                        }
                    }
                }
            }
        }
    }
}

/** 外部位图：等比缩放到贴纸宽度，垂直居中，水平居中。 */
private fun DrawScope.drawExternalBitmap(image: ImageBitmap, widthPx: Float, alpha: Float) {
    if (image.width <= 0 || image.height <= 0) return
    val ratio = image.height.toFloat() / image.width.toFloat()
    val drawWidth = widthPx.toInt().coerceAtLeast(1)
    val drawHeight = (widthPx * ratio).toInt().coerceAtLeast(1)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((-drawWidth / 2), (-drawHeight / 2)),
        dstSize = IntSize(drawWidth, drawHeight),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * 内置矢量贴纸绘制。局部坐标：原点 = 贴纸中心，向上为负 y，整体宽度约 [-0.5w, 0.5w]。
 */
private fun DrawScope.drawBuiltInSticker(variant: FaceStickerVariant, widthPx: Float, alpha: Float) {
    val w = widthPx
    val a = alpha.coerceIn(0f, 1f)
    when (variant) {
        FaceStickerVariant.CAT_EARS -> {
            drawPath(
                path = triangle(
                    Offset(-0.44f * w, -0.02f * w),
                    Offset(-0.10f * w, -0.02f * w),
                    Offset(-0.36f * w, -0.50f * w),
                ),
                color = Color(0xFF2E2A2B).copy(alpha = a),
                style = Fill,
            )
            drawPath(
                path = triangle(
                    Offset(0.10f * w, -0.02f * w),
                    Offset(0.44f * w, -0.02f * w),
                    Offset(0.36f * w, -0.50f * w),
                ),
                color = Color(0xFF2E2A2B).copy(alpha = a),
                style = Fill,
            )
            drawPath(
                path = triangle(
                    Offset(-0.34f * w, -0.10f * w),
                    Offset(-0.18f * w, -0.10f * w),
                    Offset(-0.31f * w, -0.38f * w),
                ),
                color = Color(0xFFFF8AAB).copy(alpha = a),
                style = Fill,
            )
            drawPath(
                path = triangle(
                    Offset(0.18f * w, -0.10f * w),
                    Offset(0.34f * w, -0.10f * w),
                    Offset(0.31f * w, -0.38f * w),
                ),
                color = Color(0xFFFF8AAB).copy(alpha = a),
                style = Fill,
            )
        }

        FaceStickerVariant.BUNNY_EARS -> {
            drawRotatedOval(-0.17f * w, -0.36f * w, 0.09f * w, 0.34f * w, -9f, Color(0xFFF3EEEA), a)
            drawRotatedOval(0.17f * w, -0.36f * w, 0.09f * w, 0.34f * w, 9f, Color(0xFFF3EEEA), a)
            drawRotatedOval(-0.17f * w, -0.34f * w, 0.045f * w, 0.24f * w, -9f, Color(0xFFFF9EB8), a)
            drawRotatedOval(0.17f * w, -0.34f * w, 0.045f * w, 0.24f * w, 9f, Color(0xFFFF9EB8), a)
        }

        FaceStickerVariant.HEART_CROWN -> {
            drawPath(
                buildCrownBand(w),
                color = Color(0xFFFFC107).copy(alpha = a),
                style = Fill,
            )
            drawPath(heartPath(-0.26f * w, -0.30f * w, 0.20f * w), Color(0xFFFF5C7A).copy(alpha = a), style = Fill)
            drawPath(heartPath(0f, -0.40f * w, 0.24f * w), Color(0xFFFF3B63).copy(alpha = a), style = Fill)
            drawPath(heartPath(0.26f * w, -0.30f * w, 0.20f * w), Color(0xFFFF5C7A).copy(alpha = a), style = Fill)
        }

        FaceStickerVariant.ROUND_GLASSES -> {
            val lensColor = Color(0xFF16181C).copy(alpha = 0.86f * a)
            val frameColor = Color(0xFFEDEFF2).copy(alpha = 0.9f * a)
            drawCircle(lensColor, radius = 0.20f * w, center = Offset(-0.22f * w, 0f))
            drawCircle(lensColor, radius = 0.20f * w, center = Offset(0.22f * w, 0f))
            drawCircle(frameColor, radius = 0.20f * w, center = Offset(-0.22f * w, 0f), style = Stroke(0.035f * w))
            drawCircle(frameColor, radius = 0.20f * w, center = Offset(0.22f * w, 0f), style = Stroke(0.035f * w))
            drawLine(
                color = frameColor,
                start = Offset(-0.02f * w, -0.02f * w),
                end = Offset(0.02f * w, -0.02f * w),
                strokeWidth = 0.035f * w,
            )
            drawLine(
                color = frameColor,
                start = Offset(-0.42f * w, -0.04f * w),
                end = Offset(-0.50f * w, -0.12f * w),
                strokeWidth = 0.03f * w,
            )
            drawLine(
                color = frameColor,
                start = Offset(0.42f * w, -0.04f * w),
                end = Offset(0.50f * w, -0.12f * w),
                strokeWidth = 0.03f * w,
            )
        }

        FaceStickerVariant.FLOWER_HALO -> {
            drawArc(
                color = Color(0xFF8BC34A).copy(alpha = 0.85f * a),
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(-0.46f * w, -0.52f * w),
                size = androidx.compose.ui.geometry.Size(0.92f * w, 0.72f * w),
                style = Stroke(width = 0.045f * w),
            )
            val petalCount = 9
            for (index in 0 until petalCount) {
                val fraction = index / (petalCount - 1).toFloat()
                val angle = Math.toRadians((200f + 140f * fraction).toDouble())
                val radiusX = 0.46f * w
                val radiusY = 0.36f * w
                val centerX = (radiusX * kotlin.math.cos(angle)).toFloat()
                val centerY = -0.16f * w + (radiusY * kotlin.math.sin(angle)).toFloat()
                val petalColor = if (index % 2 == 0) Color(0xFFFFC1DA) else Color(0xFFFFF1A8)
                drawCircle(petalColor.copy(alpha = 0.95f * a), radius = 0.07f * w, center = Offset(centerX, centerY))
                drawCircle(Color.White.copy(alpha = 0.7f * a), radius = 0.026f * w, center = Offset(centerX, centerY))
            }
        }

        FaceStickerVariant.EXTERNAL_BITMAP -> {
            // 未提供位图时的兜底：画一个虚线外框，明确提示"素材缺失"，绝不静默空白。
            drawRoundRect(
                color = Color.White.copy(alpha = 0.6f * a),
                topLeft = Offset(-0.4f * w, -0.4f * w),
                size = androidx.compose.ui.geometry.Size(0.8f * w, 0.8f * w),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.1f * w, 0.1f * w),
                style = Stroke(width = 0.03f * w),
            )
        }
    }
}

private fun DrawScope.drawRotatedOval(
    centerX: Float,
    centerY: Float,
    radiusX: Float,
    radiusY: Float,
    rotationDegrees: Float,
    color: Color,
    alpha: Float,
) {
    withTransform({ rotate(rotationDegrees, pivot = Offset(centerX, centerY)) }) {
        drawOval(
            color = color.copy(alpha = alpha),
            topLeft = Offset(centerX - radiusX, centerY - radiusY),
            size = androidx.compose.ui.geometry.Size(radiusX * 2f, radiusY * 2f),
        )
    }
}

private fun triangle(a: Offset, b: Offset, c: Offset): Path = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    close()
}

private fun heartPath(centerX: Float, centerY: Float, size: Float): Path = Path().apply {
    val s = size
    moveTo(centerX, centerY + s * 0.36f)
    cubicTo(
        centerX - s * 0.92f, centerY - s * 0.26f,
        centerX - s * 0.36f, centerY - s * 0.88f,
        centerX, centerY - s * 0.30f,
    )
    cubicTo(
        centerX + s * 0.36f, centerY - s * 0.88f,
        centerX + s * 0.92f, centerY - s * 0.26f,
        centerX, centerY + s * 0.36f,
    )
    close()
}

/** 皇冠底座：一条略有起伏的横带。 */
private fun buildCrownBand(w: Float): Path = Path().apply {
    moveTo(-0.34f * w, -0.06f * w)
    lineTo(-0.34f * w, -0.20f * w)
    lineTo(-0.17f * w, -0.10f * w)
    lineTo(0f, -0.24f * w)
    lineTo(0.17f * w, -0.10f * w)
    lineTo(0.34f * w, -0.20f * w)
    lineTo(0.34f * w, -0.06f * w)
    close()
}
