package com.hinnka.mycamera.galleryedit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 水印 / 日期戳的 Canvas 绘制层（Android 侧）。
 *
 * 几何全部委托 [GeWatermarkLayout]（纯逻辑、可单测），本层只负责：
 * sp→px 换算、Paint 构造、透明度与描边、平铺时的整体旋转与画布外扩。
 *
 * **自研说明**：参考 zhensherlock/watermark-js-plus（585★, MIT）的排版与日期戳能力，
 * 但该库是浏览器端 JS 实现，Android 侧不存在同等 star 的现成等价库（见 changelog 的来源总述），
 * 故绘图层自研，仅复用其排版语义。
 */
object GeWatermarkRenderer {

    /** 平铺时的画布外扩系数（旋转后仍要盖满整图，按对角线长度折算）。 */
    private const val TILE_EXPAND_FACTOR = 0.5f

    /**
     * 在 [canvas] 上绘制文本水印。
     *
     * @param container 目标图像尺寸（像素）。
     * @param config 水印配置（内部会 clamp）。
     * @param density 显示密度（sp→px）。
     * @return 是否实际绘制（关闭 / 空文案时返回 false）。
     */
    fun drawWatermark(
        canvas: Canvas,
        container: GeSize,
        config: GeWatermarkConfig,
        density: Float = 1f,
    ): Boolean {
        val safe = config.clamp().first
        if (!safe.isActive || !container.isValid) return false
        val paint = buildPaint(
            fontSizeSp = safe.fontSizeSp,
            opacity = safe.opacity,
            colorArgb = safe.colorArgb,
            density = density,
            shadowEnabled = safe.shadowEnabled,
        )
        val content = measure(safe.text, paint)
        if (content.width <= 0) return false

        if (safe.isTiled) {
            drawTiled(canvas, container, safe, paint, content, density)
        } else {
            val rotated = GeWatermarkLayout.rotatedBounds(content, safe.rotationDegrees)
            val point = GeWatermarkLayout.anchor(container, rotated, safe.placement, safe.marginFraction)
            drawText(canvas, safe.text, paint, point, content, safe.rotationDegrees)
        }
        return true
    }

    /**
     * 在 [canvas] 上绘制日期戳。
     *
     * @param text 已格式化好的时间文案（由 [GeDateStampFormat.format] 产出）。
     */
    fun drawDateStamp(
        canvas: Canvas,
        container: GeSize,
        config: GeDateStampConfig,
        text: String,
        density: Float = 1f,
    ): Boolean {
        val safe = config.clamp().first
        if (!safe.isActive || !container.isValid || text.isBlank()) return false
        val paint = buildPaint(
            fontSizeSp = safe.fontSizeSp,
            opacity = safe.opacity,
            colorArgb = safe.colorArgb,
            density = density,
            shadowEnabled = true,
        )
        val content = measure(text, paint)
        if (content.width <= 0) return false
        val point = GeWatermarkLayout.anchor(container, content, safe.placement, safe.marginFraction)
        drawText(canvas, text, paint, point, content, rotationDegrees = 0f)
        return true
    }

    /** 测量文字外框（基线对齐用 ascent，避免文字被裁切）。 */
    fun measure(text: String, paint: Paint): GeSize {
        if (text.isEmpty()) return GeSize(0, 0)
        val width = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
        return GeSize(width, height)
    }

    private fun buildPaint(
        fontSizeSp: Float,
        opacity: Float,
        colorArgb: Int,
        density: Float,
        shadowEnabled: Boolean,
    ): Paint {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colorArgb
            this.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            textSize = (fontSizeSp.coerceAtLeast(1f) * safeDensity)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isSubpixelText = true
            if (shadowEnabled) {
                setShadowLayer(2f * safeDensity, 0f, 1f * safeDensity, Color.BLACK)
            }
        }
    }

    /** [point] 为文字外框左上角；绘制时按基线（baseline = top + -ascent）落笔。 */
    private fun drawText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        point: GePoint,
        content: GeSize,
        rotationDegrees: Float,
    ) {
        val baseline = point.y - paint.fontMetrics.ascent
        val rotate = rotationDegrees.isFinite() && rotationDegrees != 0f
        if (rotate) {
            val cx = point.x + content.width / 2f
            val cy = point.y + content.height / 2f
            canvas.save()
            canvas.rotate(rotationDegrees, cx, cy)
            canvas.drawText(text, point.x, baseline, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, point.x, baseline, paint)
        }
    }

    /**
     * 平铺绘制：先把画布整体旋转，再在**外扩后**的网格上铺满，
     * 保证旋转后容器四角依然被覆盖（不会出现空白三角）。
     */
    private fun drawTiled(
        canvas: Canvas,
        container: GeSize,
        config: GeWatermarkConfig,
        paint: Paint,
        content: GeSize,
        density: Float,
    ) {
        val rotation = config.rotationDegrees
        val diagonal = ceil(
            sqrt(
                (container.width.toFloat() * container.width) +
                    (container.height.toFloat() * container.height)
            )
        ).toInt().coerceAtLeast(container.width).coerceAtLeast(container.height)

        val expanded = GeSize(diagonal, diagonal)
        val spacing = config.tileSpacingFactor
        val points = GeWatermarkLayout.tile(
            container = expanded,
            content = content,
            spacingFactor = spacing,
        )
        if (points.isEmpty()) return

        canvas.save()
        canvas.rotate(rotation, container.width / 2f, container.height / 2f)
        // 外扩网格相对容器中心的偏移补偿
        val offsetX = (container.width - diagonal) / 2f
        val offsetY = (container.height - diagonal) / 2f
        for (point in points) {
            val anchored = GePoint(point.x + offsetX, point.y + offsetY)
            // 逐点做一次可见性快速剔除，省掉画布外的无效绘制
            if (!isVisible(anchored, content, container, TILE_EXPAND_FACTOR)) continue
            val baseline = anchored.y - paint.fontMetrics.ascent
            canvas.drawText(config.text, anchored.x, baseline, paint)
        }
        canvas.restore()
    }

    /** 旋转场景下的保守可见性判断（外扩 [factor] 倍容器高度容忍倾斜越界）。 */
    private fun isVisible(point: GePoint, content: GeSize, container: GeSize, factor: Float): Boolean {
        val marginX = container.width * factor
        val marginY = max(container.height * factor, content.height.toFloat())
        return point.x + content.width >= -marginX &&
            point.x <= container.width + marginX &&
            point.y + content.height >= -marginY &&
            point.y <= container.height + marginY
    }
}
