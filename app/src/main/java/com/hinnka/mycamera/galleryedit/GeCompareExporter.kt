package com.hinnka.mycamera.galleryedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "GeCompareExporter"

/**
 * 前后对比图的合成与导出（编辑面板"前后对比"滑杆预览所用几何同源）。
 *
 * 自研（理由：对比图合成是 20 行级别的尺寸计算 + 两次 drawBitmap，
 * Android 侧无同 star 的现成"前后对比导出"库；uCrop / watermark-js-plus 均不覆盖此能力）。
 */
object GeCompareExporter {

    /**
     * 按 [config] 的 [GeCompareConfig.output] 合成对比图。
     *
     * @param beforeLabel "原图"标签（由 UI 用 stringResource 提供，禁止硬编码文案）。
     * @param afterLabel "处理后"标签。
     * @return 合成结果；输入非法时返回 null。
     */
    fun build(
        before: Bitmap,
        after: Bitmap,
        config: GeCompareConfig = GeCompareConfig(),
        beforeLabel: String? = null,
        afterLabel: String? = null,
        density: Float = 1f,
    ): Bitmap? = when (config.clamp().first.output) {
        GeCompareOutput.SIDE_BY_SIDE -> buildSideBySide(before, after, config, beforeLabel, afterLabel, density)
        GeCompareOutput.SPLIT -> buildSplit(before, after, config, beforeLabel, afterLabel, density)
    }

    /**
     * 左右并排：`[原图][gap][处理后]`，高度取两者较大值（较小者顶部对齐）。
     */
    fun buildSideBySide(
        before: Bitmap,
        after: Bitmap,
        config: GeCompareConfig = GeCompareConfig(),
        beforeLabel: String? = null,
        afterLabel: String? = null,
        density: Float = 1f,
    ): Bitmap? {
        if (!isUsable(before) || !isUsable(after)) {
            PLog.w(TAG, "side-by-side skipped: invalid input bitmaps")
            return null
        }
        val safe = config.clamp().first
        val gap = safe.gapPx.coerceIn(0, GeCompareConfig.MAX_GAP_PX)
        val width = before.width + gap + after.width
        val height = max(before.height, after.height)
        if (width <= 0 || height <= 0) return null
        return try {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(before, 0f, 0f, null)
            canvas.drawBitmap(after, (before.width + gap).toFloat(), 0f, null)
            if (safe.drawLabels) {
                drawLabel(canvas, beforeLabel, Rect(0, 0, before.width, before.height), density)
                drawLabel(
                    canvas,
                    afterLabel,
                    Rect(before.width + gap, 0, width, height),
                    density,
                )
            }
            output
        } catch (e: RuntimeException) {
            PLog.e(TAG, "side-by-side build failed", e)
            null
        }
    }

    /**
     * 分割式：左半原图 + 右半处理图，分割线在 [GeCompareConfig.split] 比例处。
     *
     * `after` 会按 `before` 的尺寸对齐（不一致时拉伸到同尺寸，避免拼接错位）。
     */
    fun buildSplit(
        before: Bitmap,
        after: Bitmap,
        config: GeCompareConfig = GeCompareConfig(),
        beforeLabel: String? = null,
        afterLabel: String? = null,
        density: Float = 1f,
    ): Bitmap? {
        if (!isUsable(before) || !isUsable(after)) {
            PLog.w(TAG, "split skipped: invalid input bitmaps")
            return null
        }
        val safe = config.clamp().first
        val width = before.width
        val height = before.height
        val splitX = (safe.split * width).roundToInt().coerceIn(0, width)
        return try {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val alignPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            // 左半：原图
            canvas.save()
            canvas.clipRect(Rect(0, 0, splitX, height))
            canvas.drawBitmap(before, 0f, 0f, null)
            canvas.restore()
            // 右半：处理后（按原图尺寸对齐绘制，再裁剪右半）
            canvas.save()
            canvas.clipRect(Rect(splitX, 0, width, height))
            canvas.drawBitmap(
                after,
                Rect(0, 0, after.width, after.height),
                Rect(0, 0, width, height),
                alignPaint,
            )
            canvas.restore()
            if (safe.drawLabels) {
                drawLabel(canvas, beforeLabel, Rect(0, 0, splitX, height), density)
                drawLabel(canvas, afterLabel, Rect(splitX, 0, width, height), density)
            }
            output
        } catch (e: RuntimeException) {
            PLog.e(TAG, "split build failed", e)
            null
        }
    }

    /**
     * 预览用的分割比例（滑杆值 → 像素 x）。
     *
     * 与 [buildSplit] 用同一套换算，保证"预览所见 = 导出所得"。
     */
    fun splitXPx(split: Float, width: Int): Int {
        if (width <= 0) return 0
        val safeSplit = GeEditRanges.clampFloat(
            split,
            GeEditRanges.OPACITY_MIN,
            GeEditRanges.OPACITY_MAX,
            GeEditRanges.COMPARE_SPLIT_DEFAULT,
        )
        return (safeSplit * width).roundToInt().coerceIn(0, width)
    }

    private fun isUsable(bitmap: Bitmap): Boolean = !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0

    /**
     * 对比图输出像素上限（防 64MP 原图并排后 OOM）。
     * 超过时按比例同时缩小两张输入图（而不是限制用户分辨率）。
     */
    const val MAX_OUTPUT_PIXELS = 24_000_000L

    /**
     * 需要时按比例缩小两张输入图，使输出落在 [MAX_OUTPUT_PIXELS] 内。
     *
     * 输出像素 ≈ (w1 + w2) * max(h1, h2)；两张同时缩小 scale 后输出像素按 scale² 收敛，
     * 因此 scale = sqrt(MAX / projected)。
     *
     * @return 缩放系数（1f = 无需缩小）。
     */
    fun fitOutputScale(before: Bitmap, after: Bitmap): Float {
        if (!isUsable(before) || !isUsable(after)) return 1f
        val width = (before.width + after.width).toLong()
        val height = max(before.height, after.height).toLong()
        val projected = width * height
        if (projected <= MAX_OUTPUT_PIXELS || projected <= 0L) return 1f
        val scale = kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble() / projected.toDouble())
        return scale.toFloat().coerceIn(0.05f, 1f)
    }

    /** 按 [scale] 缩小（scale >= 1 时原样返回）。 */
    fun scaleForOutput(bitmap: Bitmap, scale: Float): Bitmap {
        if (scale >= 1f || !isUsable(bitmap)) return bitmap
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (e: RuntimeException) {
            PLog.w(TAG, "scaleForOutput failed: ${e.message}")
            bitmap
        }
    }

    /** 左下角标签：半透明底 + 白字，保证在任意画面上可读。 */
    private fun drawLabel(canvas: Canvas, label: String?, area: Rect, density: Float) {
        if (label.isNullOrBlank() || area.width() <= 0 || area.height() <= 0) return
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * safeDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f * safeDensity, 0f, 1f * safeDensity, Color.BLACK)
        }
        val padding = 8f * safeDensity
        val textWidth = textPaint.measureText(label)
        val metrics = textPaint.fontMetrics
        val baseline = area.bottom - padding - metrics.descent
        val left = area.left + padding
        val background = Paint().apply { color = 0x66000000 }
        canvas.drawRect(
            left - padding / 2f,
            baseline + metrics.ascent - padding / 2f,
            left + textWidth + padding / 2f,
            baseline + metrics.descent + padding / 2f,
            background,
        )
        canvas.drawText(label, left, baseline, textPaint)
    }
}
