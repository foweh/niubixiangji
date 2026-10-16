package com.hinnka.mycamera.galleryedit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 尺寸（像素）。 */
data class GeSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
    val shortEdge: Int get() = min(width, height)
    val aspect: Float get() = if (isValid) width.toFloat() / height.toFloat() else 1f
}

/** 整数矩形（裁剪结果的像素表达）。 */
data class GeIntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * 归一化裁剪矩形：left/top/right/bottom 均为 0..1 的**比例**坐标。
 *
 * 之所以不复用 `android.graphics.RectF`：本类需要在纯 JVM 单测里验证边界与比例约束，
 * 不能依赖 Android 框架（PATTERN_SPEC §7.4）。UI 层用 [GeCropMath.toPixels] 转换后再交给 RectF。
 */
data class GeCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun isFinite(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    fun isValid(): Boolean = isFinite() && width > 0f && height > 0f

    companion object {
        /** 整图（无裁剪）。 */
        val FULL = GeCropRect(0f, 0f, 1f, 1f)

        fun ofCorners(x0: Float, y0: Float, x1: Float, y1: Float): GeCropRect =
            GeCropRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

/** 裁剪比例档位（比例开放可配，见 [GeCropRatioSpec]）。 */
enum class GeCropRatio { FREE, ORIGINAL, SQUARE, RATIO_3_4, RATIO_4_3, RATIO_9_16, RATIO_16_9, CUSTOM }

/**
 * 裁剪比例描述。
 *
 * @param mode 档位；CUSTOM 时使用 [customWidth] / [customHeight]。
 * @param customWidth 自定义比例宽（>0，非有限值回落到 1）。
 * @param customHeight 自定义比例高（>0，非有限值回落到 1）。
 */
data class GeCropRatioSpec(
    val mode: GeCropRatio = GeCropRatio.FREE,
    val customWidth: Float = 1f,
    val customHeight: Float = 1f,
) {
    /** 该比例在**像素空间**下的宽高比（w/h）；自由/原图返回 0 表示不约束。 */
    val pixelAspect: Float
        get() = when (mode) {
            GeCropRatio.FREE, GeCropRatio.ORIGINAL -> 0f
            GeCropRatio.SQUARE -> 1f
            GeCropRatio.RATIO_3_4 -> 3f / 4f
            GeCropRatio.RATIO_4_3 -> 4f / 3f
            GeCropRatio.RATIO_9_16 -> 9f / 16f
            GeCropRatio.RATIO_16_9 -> 16f / 9f
            GeCropRatio.CUSTOM -> sanitizedCustomAspect()
        }

    /** 是否受固定比例约束。 */
    val hasFixedRatio: Boolean get() = pixelAspect > 0f

    private fun sanitizedCustomAspect(): Float {
        if (!customWidth.isFinite() || !customHeight.isFinite()) return 1f
        if (customWidth <= 0f || customHeight <= 0f) return 1f
        return (customWidth / customHeight).coerceIn(MIN_CUSTOM_ASPECT, MAX_CUSTOM_ASPECT)
    }

    /** 越界/异常收敛；返回修正后的比例描述与被修正项。 */
    fun clamp(): Pair<GeCropRatioSpec, GeClampReport> {
        val corrections = buildList {
            if (mode == GeCropRatio.CUSTOM) {
                val safe = sanitizedCustomAspect()
                val requested =
                    if (customHeight != 0f && customHeight.isFinite() && customWidth.isFinite()) {
                        customWidth / customHeight
                    } else {
                        Float.NaN
                    }
                if (!requested.isFinite() || abs(requested - safe) > 1e-4f) {
                    add(GeClampCorrection("crop.customAspect", requested.toDouble(), safe.toDouble()))
                }
            }
        }
        return copy(
            customWidth = if (customWidth.isFinite()) customWidth else 1f,
            customHeight = if (customHeight.isFinite()) customHeight else 1f,
        ) to GeClampReport(corrections)
    }

    companion object {
        /** 自定义比例的极端值限制，避免长条形画布导致裁剪框不可用。 */
        const val MIN_CUSTOM_ASPECT = 0.05f
        const val MAX_CUSTOM_ASPECT = 20f

        val FREE = GeCropRatioSpec(GeCropRatio.FREE)
        val SQUARE = GeCropRatioSpec(GeCropRatio.SQUARE)
        val RATIO_3_4 = GeCropRatioSpec(GeCropRatio.RATIO_3_4)
        val RATIO_9_16 = GeCropRatioSpec(GeCropRatio.RATIO_9_16)

        fun fromRatio(width: Float, height: Float): GeCropRatioSpec =
            GeCropRatioSpec(GeCropRatio.CUSTOM, width, height)
    }
}

/** 调整裁剪框时被拖动的手柄（四角 + 四边 + 整体移动）。 */
enum class GeCropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, NONE }

/** 比例收敛时的参考锚点。 */
enum class GeCropAnchor { CENTER, TOP, BOTTOM, LEFT, RIGHT }

/**
 * 自研裁剪几何核心（参考 Yalantis/uCrop 的交互语义：自由/固定比例切换、手柄拖拽、
 * 边界吸附、最小尺寸约束、三分构图线；不引入 uCrop 的 View/Activity 依赖）。
 *
 * 全部输入输出为归一化坐标，与像素/显示尺寸解耦；所有函数对异常输入（NaN、反向、
 * 退化源尺寸）一律修正并返回合法矩形，不抛异常。
 */
object GeCropMath {

    /** 异常矩形 → 整图。 */
    fun sanitize(rect: GeCropRect): GeCropRect {
        if (!rect.isFinite() || !rect.isValid()) return GeCropRect.FULL
        return clampToUnit(rect)
    }

    /** 收敛到单位方框内（保持尺寸，超出时整体平移回来）。 */
    fun clampToUnit(rect: GeCropRect): GeCropRect {
        val safe = if (rect.isFinite()) rect else GeCropRect.FULL
        val w = safe.width.coerceIn(0f, 1f)
        val h = safe.height.coerceIn(0f, 1f)
        if (w <= 0f || h <= 0f) return GeCropRect.FULL
        var left = max(0f, safe.left)
        var top = max(0f, safe.top)
        if (left + w > 1f) left = 1f - w
        if (top + h > 1f) top = 1f - h
        return GeCropRect(left, top, left + w, top + h)
    }

    /** 横向/纵向最小归一化边长：以源图**短边**的 [minFraction] 折算为像素后换成比例。 */
    fun minFractionFor(source: GeSize, minFraction: Float, horizontal: Boolean): Float {
        val clamped = GeEditRanges.clampFloat(
            minFraction,
            GeEditRanges.CROP_MIN_FRACTION,
            GeEditRanges.CROP_MIN_FRACTION_LIMIT_MAX,
            GeEditRanges.CROP_MIN_FRACTION,
        )
        if (!source.isValid) return clamped
        val minPx = clamped * source.shortEdge
        val extent = if (horizontal) source.width else source.height
        return (minPx / extent).coerceIn(0f, 1f)
    }

    /** 强制最小边长（按中心扩张，越界后贴边）。 */
    fun enforceMinSize(rect: GeCropRect, minFraction: Float, source: GeSize): GeCropRect {
        val safe = sanitize(rect)
        val minX = minFractionFor(source, minFraction, horizontal = true)
        val minY = minFractionFor(source, minFraction, horizontal = false)
        val w = max(safe.width, minX).coerceIn(0f, 1f)
        val h = max(safe.height, minY).coerceIn(0f, 1f)
        val left = (safe.centerX - w / 2f).coerceIn(0f, 1f - w)
        val top = (safe.centerY - h / 2f).coerceIn(0f, 1f - h)
        return GeCropRect(left, top, left + w, top + h)
    }

    /** 在 [source] 内居中生成指定比例的**最大**裁剪框；[padding] 为四周留白比例（0..0.4）。 */
    fun fitRatioCentered(
        source: GeSize,
        spec: GeCropRatioSpec,
        padding: Float = 0f,
    ): GeCropRect {
        if (!source.isValid) return GeCropRect.FULL
        val pad = GeEditRanges.clampFloat(padding, 0f, 0.4f, 0f)
        val usable = 1f - 2f * pad
        val targetAspect = spec.pixelAspect
        if (targetAspect <= 0f) {
            return GeCropRect(pad, pad, pad + usable, pad + usable)
        }
        // 归一化坐标下 w/h = targetAspect * srcH / srcW（因为像素宽 = w*srcW）
        val normalizedAspect = normalizedAspect(targetAspect, source)
        if (!normalizedAspect.isFinite() || normalizedAspect <= 0f) {
            return GeCropRect(pad, pad, pad + usable, pad + usable)
        }
        var w = usable
        var h = w / normalizedAspect
        if (h > usable) {
            h = usable
            w = h * normalizedAspect
        }
        val left = (1f - w) / 2f
        val top = (1f - h) / 2f
        return clampToUnit(GeCropRect(left, top, left + w, top + h))
    }

    /**
     * 按固定比例收敛已有裁剪框（尽量保持面积与中心）。
     *
     * @param anchor 收敛时保持不动的参考点，默认居中。
     */
    fun applyRatio(
        rect: GeCropRect,
        spec: GeCropRatioSpec,
        source: GeSize,
        anchor: GeCropAnchor = GeCropAnchor.CENTER,
    ): GeCropRect {
        val safe = sanitize(rect)
        val targetAspect = spec.pixelAspect
        if (targetAspect <= 0f || !source.isValid) return safe
        val normalizedAspect = normalizedAspect(targetAspect, source)
        if (!normalizedAspect.isFinite() || normalizedAspect <= 0f) return safe

        var h = sqrt(safe.area / normalizedAspect)
        var w = h * normalizedAspect
        if (w > 1f) {
            w = 1f
            h = w / normalizedAspect
        }
        if (h > 1f) {
            h = 1f
            w = h * normalizedAspect
        }
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return safe

        val left = when (anchor) {
            GeCropAnchor.CENTER -> safe.centerX - w / 2f
            GeCropAnchor.LEFT -> safe.left
            GeCropAnchor.RIGHT -> safe.right - w
            GeCropAnchor.TOP, GeCropAnchor.BOTTOM -> safe.centerX - w / 2f
        }
        val top = when (anchor) {
            GeCropAnchor.CENTER -> safe.centerY - h / 2f
            GeCropAnchor.TOP -> safe.top
            GeCropAnchor.BOTTOM -> safe.bottom - h
            GeCropAnchor.LEFT, GeCropAnchor.RIGHT -> safe.centerY - h / 2f
        }
        return clampToUnit(GeCropRect(left, top, left + w, top + h))
    }

    /** 整体平移（保持尺寸），越界时贴边而非缩小。 */
    fun move(rect: GeCropRect, deltaX: Float, deltaY: Float): GeCropRect {
        val safe = sanitize(rect)
        val dx = if (deltaX.isFinite()) deltaX else 0f
        val dy = if (deltaY.isFinite()) deltaY else 0f
        val w = safe.width
        val h = safe.height
        val left = (safe.left + dx).coerceIn(0f, 1f - w)
        val top = (safe.top + dy).coerceIn(0f, 1f - h)
        return GeCropRect(left, top, left + w, top + h)
    }

    /**
     * 手柄拖拽：按 [handle] 调整对应边；固定比例时保持"手柄对角不动"并同步缩放另一边。
     *
     * @param minFraction 最小边长（归一化，短边基准）。
     * @param anchorSpan 保留参数：true 时四边手柄也以中心对称扩张（默认 false=按拖拽边单向）。
     */
    fun resize(
        rect: GeCropRect,
        handle: GeCropHandle,
        deltaX: Float,
        deltaY: Float,
        source: GeSize,
        spec: GeCropRatioSpec = GeCropRatioSpec.FREE,
        anchorSpan: Boolean = false,
        minFraction: Float = GeEditRanges.CROP_MIN_FRACTION,
    ): GeCropRect {
        if (handle == GeCropHandle.NONE) return sanitize(rect)
        val safe = sanitize(rect)
        val minX = minFractionFor(source, minFraction, horizontal = true)
        val minY = minFractionFor(source, minFraction, horizontal = false)
        val dx = if (deltaX.isFinite()) deltaX else 0f
        val dy = if (deltaY.isFinite()) deltaY else 0f

        val movesLeft = handle == GeCropHandle.LEFT ||
            handle == GeCropHandle.TOP_LEFT || handle == GeCropHandle.BOTTOM_LEFT
        val movesRight = handle == GeCropHandle.RIGHT ||
            handle == GeCropHandle.TOP_RIGHT || handle == GeCropHandle.BOTTOM_RIGHT
        val movesTop = handle == GeCropHandle.TOP ||
            handle == GeCropHandle.TOP_LEFT || handle == GeCropHandle.TOP_RIGHT
        val movesBottom = handle == GeCropHandle.BOTTOM ||
            handle == GeCropHandle.BOTTOM_LEFT || handle == GeCropHandle.BOTTOM_RIGHT

        // 锚点：手柄**对角/对面**那条边保持不动
        val anchorX = if (movesLeft) safe.right else safe.left
        val anchorY = if (movesTop) safe.bottom else safe.top

        var left = safe.left
        var right = safe.right
        var top = safe.top
        var bottom = safe.bottom
        if (movesLeft) left = (left + dx).coerceIn(0f, right - minX)
        if (movesRight) right = (right + dx).coerceIn(left + minX, 1f)
        if (movesTop) top = (top + dy).coerceIn(0f, bottom - minY)
        if (movesBottom) bottom = (bottom + dy).coerceIn(top + minY, 1f)

        val dragged = GeCropRect(left, top, right, bottom)
        val targetAspect = spec.pixelAspect
        val result = if (targetAspect <= 0f || !source.isValid) {
            dragged
        } else {
            val normalizedAspect = normalizedAspect(targetAspect, source)
            if (!normalizedAspect.isFinite() || normalizedAspect <= 0f) {
                dragged
            } else {
                // 以"相对最小尺寸的变化倍数"决定由哪条边驱动，避免抖动的来回跳
                val driveByWidth = (dragged.width / minX.coerceAtLeast(1e-4f)) >=
                    (dragged.height / minY.coerceAtLeast(1e-4f))
                var w = if (driveByWidth) dragged.width else dragged.height * normalizedAspect
                var h = if (driveByWidth) dragged.width / normalizedAspect else dragged.height
                w = w.coerceIn(minX, 1f)
                h = h.coerceIn(minY, 1f)
                if (driveByWidth) {
                    h = (w / normalizedAspect).coerceIn(minY, 1f)
                } else {
                    w = (h * normalizedAspect).coerceIn(minX, 1f)
                }
                val newLeft = if (movesLeft) anchorX - w else anchorX
                val newTop = if (movesTop) {
                    anchorY - h
                } else if (!movesBottom) {
                    // 纯横向手柄：纵向居中扩张，手腕更自然
                    safe.centerY - h / 2f
                } else {
                    anchorY
                }
                GeCropRect(newLeft, newTop, newLeft + w, newTop + h)
            }
        }

        val constrained = enforceMinSize(clampToUnit(result), minFraction, source)
        if (!anchorSpan) return constrained
        // 对称扩张模式：以原框中心重新定位，四边均匀变化（用于"中心缩放"手势）
        val w = constrained.width
        val h = constrained.height
        val centeredLeft = (safe.centerX - w / 2f).coerceIn(0f, 1f - w)
        val centeredTop = (safe.centerY - h / 2f).coerceIn(0f, 1f - h)
        return GeCropRect(centeredLeft, centeredTop, centeredLeft + w, centeredTop + h)
    }

    /**
     * 归一化矩形 → 像素矩形。
     *
     * 保证：结果完全落在 [source] 内、宽高至少 1px（退化输入回落到整图）。
     */
    fun toPixels(rect: GeCropRect, source: GeSize): GeIntRect {
        if (!source.isValid) return GeIntRect(0, 0, 0, 0)
        val safe = sanitize(rect)
        var left = (safe.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        var top = (safe.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        var right = (safe.right * source.width).roundToInt().coerceIn(left + 1, source.width)
        var bottom = (safe.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
        if (right <= left) right = min(left + 1, source.width)
        if (bottom <= top) bottom = min(top + 1, source.height)
        if (right <= left) left = max(0, right - 1)
        if (bottom <= top) top = max(0, bottom - 1)
        return GeIntRect(left, top, right, bottom)
    }

    /** 像素矩形 → 归一化（用于把外部裁剪结果反写回本模块状态）。 */
    fun fromPixels(rect: GeIntRect, source: GeSize): GeCropRect {
        if (!source.isValid) return GeCropRect.FULL
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        return sanitize(
            GeCropRect(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)
        )
    }

    /** 组合：先按比例收敛，再强制最小尺寸（UI 每次手势结束调一次即可）。 */
    fun settle(
        rect: GeCropRect,
        spec: GeCropRatioSpec,
        source: GeSize,
        minFraction: Float = GeEditRanges.CROP_MIN_FRACTION,
    ): GeCropRect {
        val ratioApplied = if (spec.hasFixedRatio) applyRatio(rect, spec, source) else sanitize(rect)
        return enforceMinSize(ratioApplied, minFraction, source)
    }

    /** 固定比例在两个坐标系（像素 / 归一化）之间的换算：w/h = targetAspect * srcH / srcW。 */
    fun normalizedAspect(targetAspect: Float, source: GeSize): Float {
        if (!source.isValid) return 0f
        if (!targetAspect.isFinite() || targetAspect <= 0f) return 0f
        return targetAspect * source.height.toFloat() / source.width.toFloat()
    }
}

/** 裁剪面板开放配置（默认值 + 范围 + clamp）。 */
data class GeCropConfig(
    val ratio: GeCropRatioSpec = GeCropRatioSpec.FREE,
    /** 最小边长（占源图短边比例）。范围 [GeEditRanges.CROP_MIN_FRACTION, CROP_MIN_FRACTION_LIMIT_MAX]。 */
    val minFraction: Float = GeEditRanges.CROP_MIN_FRACTION,
    /** 手柄触摸半径（dp），范围 12..96。 */
    val handleTouchRadiusDp: Float = 28f,
    /** 是否在超出图像时把裁剪框吸附到内边界。 */
    val snapToBounds: Boolean = true,
    /** 是否显示三分构图线。 */
    val showGridLines: Boolean = true,
    /** 初始留白比例，范围 0..0.4。 */
    val initialPadding: Float = 0f,
) {
    fun clamp(): Pair<GeCropConfig, GeClampReport> {
        val (safeRatio, ratioReport) = ratio.clamp()
        val safeMin = GeEditRanges.clampFloat(
            minFraction,
            GeEditRanges.CROP_MIN_FRACTION,
            GeEditRanges.CROP_MIN_FRACTION_LIMIT_MAX,
            GeEditRanges.CROP_MIN_FRACTION,
        )
        val safeTouch = GeEditRanges.clampFloat(handleTouchRadiusDp, 12f, 96f, 28f)
        val safePad = GeEditRanges.clampFloat(initialPadding, 0f, 0.4f, 0f)
        val corrections = ratioReport.corrections.toMutableList()
        GeClampReport.of("crop.minFraction", minFraction.toDouble(), safeMin.toDouble())?.let(corrections::add)
        GeClampReport.of("crop.handleTouchRadiusDp", handleTouchRadiusDp.toDouble(), safeTouch.toDouble())
            ?.let(corrections::add)
        GeClampReport.of("crop.initialPadding", initialPadding.toDouble(), safePad.toDouble())?.let(corrections::add)
        return copy(
            ratio = safeRatio,
            minFraction = safeMin,
            handleTouchRadiusDp = safeTouch,
            initialPadding = safePad,
        ) to GeClampReport(corrections)
    }
}
