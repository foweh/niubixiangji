package com.hinnka.mycamera.galleryedit

import kotlin.math.max
import kotlin.math.roundToInt

/** 水印 / 日期戳在画面上的位置。 */
enum class GeWatermarkPlacement {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
    /** 平铺（全幅重复）。 */
    TILED,
}

/** 二维点（纯数据，避免依赖 Android 的 PointF）。 */
data class GePoint(val x: Float, val y: Float)

/**
 * 水印排版核心（纯 Kotlin，可 JVM 单测）。
 *
 * 参考 zhensherlock/watermark-js-plus（585★, MIT）的排版语义：九宫格定位 + 边距 +
 * 平铺网格 + 旋转；**实现为自研**——该库是浏览器端 JS，Android 侧无同等高 star 的现成实现，
 * 且本模块只需要排版计算 + Canvas 文字绘制，自研约 100 行即可覆盖（PATTERN_SPEC §9）。
 *
 * 本类只负责**几何**，不测量文字：文字尺寸由上层用 Paint/TextMeasurer 量好传入，
 * 因此可在无 Android 环境下完整验证边界。
 */
object GeWatermarkLayout {

    /** 内容左上角锚点。边距按容器短边 * [marginFraction] 计算后四舍五入。 */
    fun anchor(
        container: GeSize,
        content: GeSize,
        placement: GeWatermarkPlacement,
        marginFraction: Float,
    ): GePoint {
        if (!container.isValid) return GePoint(0f, 0f)
        val margin = marginPx(container, marginFraction)
        val safeContentW = max(0, content.width).toFloat()
        val safeContentH = max(0, content.height).toFloat()
        val cw = container.width.toFloat()
        val ch = container.height.toFloat()

        val x = when (placement) {
            GeWatermarkPlacement.TOP_LEFT, GeWatermarkPlacement.CENTER_LEFT, GeWatermarkPlacement.BOTTOM_LEFT ->
                margin
            GeWatermarkPlacement.TOP_CENTER, GeWatermarkPlacement.CENTER, GeWatermarkPlacement.BOTTOM_CENTER ->
                (cw - safeContentW) / 2f
            GeWatermarkPlacement.TOP_RIGHT, GeWatermarkPlacement.CENTER_RIGHT, GeWatermarkPlacement.BOTTOM_RIGHT ->
                cw - margin - safeContentW
            GeWatermarkPlacement.TILED -> 0f
        }
        val y = when (placement) {
            GeWatermarkPlacement.TOP_LEFT, GeWatermarkPlacement.TOP_CENTER, GeWatermarkPlacement.TOP_RIGHT ->
                margin
            GeWatermarkPlacement.CENTER_LEFT, GeWatermarkPlacement.CENTER, GeWatermarkPlacement.CENTER_RIGHT ->
                (ch - safeContentH) / 2f
            GeWatermarkPlacement.BOTTOM_LEFT, GeWatermarkPlacement.BOTTOM_CENTER, GeWatermarkPlacement.BOTTOM_RIGHT ->
                ch - margin - safeContentH
            GeWatermarkPlacement.TILED -> 0f
        }
        return clampPoint(GePoint(x, y), container, content)
    }

    /** 边距像素（按容器短边，clamp 到 [GeEditRanges.MARGIN_FRACTION_MIN, MAX]）。 */
    fun marginPx(container: GeSize, marginFraction: Float): Float {
        if (!container.isValid) return 0f
        val fraction = GeEditRanges.clampFloat(
            marginFraction,
            GeEditRanges.MARGIN_FRACTION_MIN,
            GeEditRanges.MARGIN_FRACTION_MAX,
            GeEditRanges.MARGIN_FRACTION_DEFAULT,
        )
        return (fraction * container.shortEdge).roundToInt().toFloat().coerceAtLeast(0f)
    }

    /** 把内容锚点夹回容器内（内容大于容器时退化为左上角）。 */
    fun clampPoint(point: GePoint, container: GeSize, content: GeSize): GePoint {
        if (!container.isValid) return GePoint(0f, 0f)
        val maxX = max(0f, container.width - max(0, content.width).toFloat())
        val maxY = max(0f, container.height - max(0, content.height).toFloat())
        val x = if (point.x.isFinite()) point.x.coerceIn(0f, maxX) else 0f
        val y = if (point.y.isFinite()) point.y.coerceIn(0f, maxY) else 0f
        return GePoint(x, y)
    }

    /**
     * 平铺网格：从左上角按 [spacingX] / [spacingY] 倍数步进，覆盖整个容器。
     *
     * @param spacingFactor 间距倍数（1 = 内容之间无间隙）。范围
     *        [GeEditRanges.TILE_SPACING_MIN, TILE_SPACING_MAX]。
     * @return 每个水印内容的左上角锚点；数量上限 [GeEditRanges.MAX_TILE_COUNT]（防超大画布天量绘制）。
     */
    fun tile(
        container: GeSize,
        content: GeSize,
        spacingFactor: Float = GeEditRanges.TILE_SPACING_DEFAULT,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): List<GePoint> {
        if (!container.isValid || content.width <= 0 || content.height <= 0) return emptyList()
        val factor = GeEditRanges.clampFloat(
            spacingFactor,
            GeEditRanges.TILE_SPACING_MIN,
            GeEditRanges.TILE_SPACING_MAX,
            GeEditRanges.TILE_SPACING_DEFAULT,
        )
        val stepX = (content.width * factor).coerceAtLeast(1f)
        val stepY = (content.height * factor).coerceAtLeast(1f)
        val startX = if (offsetX.isFinite()) offsetX % stepX else 0f
        val startY = if (offsetY.isFinite()) offsetY % stepY else 0f
        val cols = ((container.width + stepX) / stepX).toInt().coerceAtLeast(1)
        val rows = ((container.height + stepY) / stepY).toInt().coerceAtLeast(1)
        val result = ArrayList<GePoint>(minOf(GeEditRanges.MAX_TILE_COUNT, cols * rows))
        var row = 0
        while (row < rows) {
            var col = 0
            while (col < cols) {
                if (result.size >= GeEditRanges.MAX_TILE_COUNT) return result
                result.add(GePoint(startX + col * stepX, startY + row * stepY))
                col++
            }
            row++
        }
        return result
    }

    /** 旋转后内容的包围盒尺寸（用于排版时预留空间）。 */
    fun rotatedBounds(content: GeSize, rotationDegrees: Float): GeSize {
        if (content.width <= 0 || content.height <= 0) return content
        val angle = if (rotationDegrees.isFinite()) rotationDegrees else 0f
        val radians = Math.toRadians(angle.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(radians))
        val sin = kotlin.math.abs(kotlin.math.sin(radians))
        val w = content.width * cos + content.height * sin
        val h = content.width * sin + content.height * cos
        return GeSize(kotlin.math.ceil(w).toInt().coerceAtLeast(1), kotlin.math.ceil(h).toInt().coerceAtLeast(1))
    }
}

/**
 * 文本水印开放配置。
 *
 * @param text 文案（超长截断到 [GeEditRanges.WATERMARK_TEXT_MAX_LENGTH]）。
 * @param fontSizeSp 字号 sp，范围 [GeEditRanges.WATERMARK_FONT_MIN, WATERMARK_FONT_MAX]。
 * @param placement 位置（含平铺）。
 * @param opacity 透明度 0..1。
 * @param marginFraction 边距（容器短边比例）0..0.25。
 * @param tileSpacingFactor 平铺间距倍数 0.5..10。
 * @param rotationDegrees 倾斜角 -90..90（仅在平铺时有明显意义）。
 * @param colorArgb 文字颜色（ARGB，默认白色）。
 * @param stampPermanent true 表示保存时永久写入像素（用户要求：保存时水印永久写入）。
 * @param shadowEnabled 是否加描边/阴影以便在亮背景上可读。
 */
data class GeWatermarkConfig(
    val enabled: Boolean = false,
    val text: String = "",
    val fontSizeSp: Float = GeEditRanges.WATERMARK_FONT_DEFAULT,
    val placement: GeWatermarkPlacement = GeWatermarkPlacement.BOTTOM_RIGHT,
    val opacity: Float = GeEditRanges.WATERMARK_OPACITY_DEFAULT,
    val marginFraction: Float = GeEditRanges.MARGIN_FRACTION_DEFAULT,
    val tileSpacingFactor: Float = GeEditRanges.TILE_SPACING_DEFAULT,
    val rotationDegrees: Float = GeEditRanges.WATERMARK_ROTATION_DEFAULT,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val stampPermanent: Boolean = true,
    val shadowEnabled: Boolean = true,
) {
    /** 是否真的需要绘制（关开关 / 空文案都直接跳过）。 */
    val isActive: Boolean get() = enabled && text.isNotBlank()

    /** 平铺模式。 */
    val isTiled: Boolean get() = placement == GeWatermarkPlacement.TILED

    fun clamp(): Pair<GeWatermarkConfig, GeClampReport> {
        val safeText = if (text.length > GeEditRanges.WATERMARK_TEXT_MAX_LENGTH) {
            text.take(GeEditRanges.WATERMARK_TEXT_MAX_LENGTH)
        } else {
            text
        }
        val safeFont = GeEditRanges.clampFloat(
            fontSizeSp,
            GeEditRanges.WATERMARK_FONT_MIN,
            GeEditRanges.WATERMARK_FONT_MAX,
            GeEditRanges.WATERMARK_FONT_DEFAULT,
        )
        val safeOpacity = GeEditRanges.clampFloat(
            opacity,
            GeEditRanges.OPACITY_MIN,
            GeEditRanges.OPACITY_MAX,
            GeEditRanges.WATERMARK_OPACITY_DEFAULT,
        )
        val safeMargin = GeEditRanges.clampFloat(
            marginFraction,
            GeEditRanges.MARGIN_FRACTION_MIN,
            GeEditRanges.MARGIN_FRACTION_MAX,
            GeEditRanges.MARGIN_FRACTION_DEFAULT,
        )
        val safeSpacing = GeEditRanges.clampFloat(
            tileSpacingFactor,
            GeEditRanges.TILE_SPACING_MIN,
            GeEditRanges.TILE_SPACING_MAX,
            GeEditRanges.TILE_SPACING_DEFAULT,
        )
        val safeRotation = GeEditRanges.clampFloat(
            rotationDegrees,
            GeEditRanges.WATERMARK_ROTATION_MIN,
            GeEditRanges.WATERMARK_ROTATION_MAX,
            GeEditRanges.WATERMARK_ROTATION_DEFAULT,
        )
        val corrections = buildList {
            if (safeText.length != text.length) {
                add(GeClampCorrection("watermark.text.length", text.length.toDouble(), safeText.length.toDouble()))
            }
            GeClampReport.of("watermark.fontSizeSp", fontSizeSp.toDouble(), safeFont.toDouble())?.let(::add)
            GeClampReport.of("watermark.opacity", opacity.toDouble(), safeOpacity.toDouble())?.let(::add)
            GeClampReport.of("watermark.marginFraction", marginFraction.toDouble(), safeMargin.toDouble())?.let(::add)
            GeClampReport.of("watermark.tileSpacingFactor", tileSpacingFactor.toDouble(), safeSpacing.toDouble())
                ?.let(::add)
            GeClampReport.of("watermark.rotationDegrees", rotationDegrees.toDouble(), safeRotation.toDouble())
                ?.let(::add)
        }
        return copy(
            text = safeText,
            fontSizeSp = safeFont,
            opacity = safeOpacity,
            marginFraction = safeMargin,
            tileSpacingFactor = safeSpacing,
            rotationDegrees = safeRotation,
        ) to GeClampReport(corrections)
    }
}

/**
 * 日期戳开放配置。
 *
 * @param enabled 开关（用户可关）。
 * @param pattern 时间格式串（受 [GeDateStampFormat.normalizePattern] 约束）。
 * @param useExifTakenTime true=优先用拍摄时间（EXIF），false=用当前时间。
 */
data class GeDateStampConfig(
    val enabled: Boolean = false,
    val pattern: String = GeEditRanges.DATE_PATTERN_DEFAULT,
    val fontSizeSp: Float = GeEditRanges.WATERMARK_FONT_DEFAULT * 0.75f,
    val placement: GeWatermarkPlacement = GeWatermarkPlacement.BOTTOM_LEFT,
    val opacity: Float = GeEditRanges.DATE_STAMP_OPACITY_DEFAULT,
    val marginFraction: Float = GeEditRanges.MARGIN_FRACTION_DEFAULT,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val useExifTakenTime: Boolean = true,
    val stampPermanent: Boolean = true,
) {
    val isActive: Boolean get() = enabled

    fun clamp(): Pair<GeDateStampConfig, GeClampReport> {
        val safePattern = GeDateStampFormat.normalizePattern(pattern)
        val safeFont = GeEditRanges.clampFloat(
            fontSizeSp,
            GeEditRanges.WATERMARK_FONT_MIN,
            GeEditRanges.WATERMARK_FONT_MAX,
            GeEditRanges.WATERMARK_FONT_DEFAULT * 0.75f,
        )
        val safeOpacity = GeEditRanges.clampFloat(
            opacity,
            GeEditRanges.OPACITY_MIN,
            GeEditRanges.OPACITY_MAX,
            GeEditRanges.DATE_STAMP_OPACITY_DEFAULT,
        )
        val safeMargin = GeEditRanges.clampFloat(
            marginFraction,
            GeEditRanges.MARGIN_FRACTION_MIN,
            GeEditRanges.MARGIN_FRACTION_MAX,
            GeEditRanges.MARGIN_FRACTION_DEFAULT,
        )
        val corrections = buildList {
            if (safePattern != pattern) {
                add(GeClampCorrection("dateStamp.pattern", pattern.length.toDouble(), safePattern.length.toDouble()))
            }
            GeClampReport.of("dateStamp.fontSizeSp", fontSizeSp.toDouble(), safeFont.toDouble())?.let(::add)
            GeClampReport.of("dateStamp.opacity", opacity.toDouble(), safeOpacity.toDouble())?.let(::add)
            GeClampReport.of("dateStamp.marginFraction", marginFraction.toDouble(), safeMargin.toDouble())?.let(::add)
        }
        return copy(
            pattern = safePattern,
            fontSizeSp = safeFont,
            opacity = safeOpacity,
            marginFraction = safeMargin,
        ) to GeClampReport(corrections)
    }
}
