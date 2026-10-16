package com.hinnka.mycamera.appgallerypreview

/**
 * 大图预览的缩放/平移变换（纯数据，无 Android 依赖，可 JVM 单测）。
 *
 * [scale] 为相对"适配屏幕"基准的倍率（1f = 适配），[offsetX]/[offsetY] 为内容中心相对视口中心的
 * 像素偏移。所有进入本结构的值都应先经 [AppGalleryPreviewZoomMath] 钳制。
 */
data class AppGalleryPreviewTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 是否已被放大（相对 min 有明显增量），用于翻页手势仲裁。 */
    fun isZoomedIn(minScale: Float, epsilon: Float = 0.01f): Boolean = scale > minScale + epsilon
}

/**
 * 缩放/平移纯数学（自研，思路参考 Baseflow/PhotoView 的 scale 钳制 / 边界回弹 / 双击档位算法）。
 *
 * 之所以自研而非直接移植：PhotoView 是 View 体系（Java，MIT/Apache 双许可），与工程 Compose
 * 体系不兼容；工程已依赖的 Telephoto 是 Compose 组件但其缩放范围不可配置、且状态机无法单测。
 * 本类把可配置范围（1x~8x，可调）与边界规则抽成纯函数，满足"参数全开放 + 可单测"硬约束。
 */
object AppGalleryPreviewZoomMath {

    /** 缩放钳制：把任意倍率收进 [minScale, maxScale]，非有限值回退到 min。 */
    fun clampScale(scale: Float, minScale: Float, maxScale: Float): Float {
        val lo = minScale.coerceAtLeast(AppGalleryPreviewConfig.ZOOM_MIN_FLOOR)
        val hi = maxScale.coerceAtLeast(lo)
        if (scale.isNaN()) return lo
        if (scale == Float.POSITIVE_INFINITY) return hi
        if (scale == Float.NEGATIVE_INFINITY) return lo
        return scale.coerceIn(lo, hi)
    }

    /**
     * 捏合手势：在当前倍率上乘以相对倍率因子。
     * @param pinchFactor 本次手势相对值（>0），<=0 视为无效手势，返回原值。
     */
    fun applyPinch(
        currentScale: Float,
        pinchFactor: Float,
        minScale: Float,
        maxScale: Float,
    ): Float {
        if (!pinchFactor.isFinite() || pinchFactor <= 0f) return clampScale(currentScale, minScale, maxScale)
        return clampScale(currentScale * pinchFactor, minScale, maxScale)
    }

    /**
     * 双击目标倍率：三档循环 base(min) → 档位1 → 档位2 → base。
     *
     * 判定使用相对容差，避免浮点误差导致档位抖动。
     * @param currentScale 当前倍率
     * @param minScale 基准（双击回到该档）
     * @param level1 第一档目标
     * @param level2 第二档目标
     * @param epsilon 档位判定容差（相对 minScale 比较）
     */
    fun doubleTapTargetScale(
        currentScale: Float,
        minScale: Float,
        level1: Float,
        level2: Float,
        epsilon: Float = 0.05f,
    ): Float {
        val base = clampScale(minScale, minScale, maxOf(level2, level1))
        val l1 = clampScale(level1, base, maxOf(level2, level1)).coerceAtLeast(base)
        val l2 = clampScale(level2, l1, maxOf(level2, l1))
        val current = currentScale
        // 用"当前档位 + 容差"判定所处档位，容差随倍率缩放以保持手势直觉一致。
        val nearBase = current <= base * (1f + epsilon)
        val nearL1 = kotlin.math.abs(current - l1) <= l1 * epsilon
        return when {
            nearBase -> l1
            nearL1 -> l2
            else -> base
        }
    }

    /**
     * 平移钳制：内容在视口内的可移动范围。
     *
     * 内容尺寸 = viewport * scale；可移动范围 = (content - viewport) / 2，最小 0。
     * 允许 [overscrollFactor] 比例的越界拖拽（0 = 硬边界）。
     */
    fun clampOffset(
        offset: Float,
        viewportSize: Float,
        contentSize: Float,
        overscrollFactor: Float,
    ): Float {
        if (!offset.isFinite() || viewportSize <= 0f || contentSize <= 0f) return 0f
        val margin = ((contentSize - viewportSize) / 2f).coerceAtLeast(0f)
        val slack = margin * overscrollFactor.coerceIn(0f, 1f)
        val limit = margin + slack
        return offset.coerceIn(-limit, limit)
    }

    /** 平移钳制（二维）。 */
    fun clampOffset(
        transform: AppGalleryPreviewTransform,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        overscrollFactor: Float,
    ): AppGalleryPreviewTransform = transform.copy(
        offsetX = clampOffset(transform.offsetX, viewportWidth, contentWidth, overscrollFactor),
        offsetY = clampOffset(transform.offsetY, viewportHeight, contentHeight, overscrollFactor),
    )

    /**
     * 缩放后内容尺寸。scale 已钳制；viewport<=0 时返回 0 避免出现 Infinity。
     */
    fun scaledContentSize(viewportSize: Float, scale: Float): Float =
        if (viewportSize <= 0f || !scale.isFinite()) 0f else viewportSize * scale

    /** 缩放百分比（用于 UI 显示），1x=100%。 */
    fun zoomPercent(scale: Float, minScale: Float): Int {
        val safeMin = minScale.takeIf { it > 0f && it.isFinite() } ?: 1f
        val ratio = (scale / safeMin) * 100f
        return if (ratio.isFinite()) ratio.toInt().coerceAtLeast(0) else 0
    }
}

/**
 * 大图预览缩放状态机（纯 JVM，可单测）。
 *
 * 生命周期：预览页切换图片时调用 [resetForPage]（是否复位由配置决定）；
 * 手势事件经 [onPinch] / [onDoubleTap] / [onPan] 进入，一律经过 clamp，误设不崩溃。
 */
class AppGalleryPreviewZoomState(
    /** 是否已被用户放大；翻页仲裁依据。 */
    initialTransform: AppGalleryPreviewTransform = AppGalleryPreviewTransform(),
    val config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
) {
    private val safe = config.appGalleryPreviewSafe().config

    var transform: AppGalleryPreviewTransform = initialTransform
        private set

    /** 最近一次被 clamp 修正的字段（便于诊断，不影响主流程）。 */
    var lastClampedField: String? = null
        private set

    val minScale: Float get() = safe.safeZoomRange.start
    val maxScale: Float get() = safe.safeZoomRange.endInclusive

    val isZoomedIn: Boolean get() = transform.isZoomedIn(minScale)

    /** 当前倍率是否在允许范围内（不变式校验）。 */
    val isScaleInRange: Boolean get() = transform.scale in safe.safeZoomRange

    /** 捏合：返回新的倍率，并记录是否发生钳制。 */
    fun onPinch(pinchFactor: Float): Float {
        val next = AppGalleryPreviewZoomMath.applyPinch(transform.scale, pinchFactor, minScale, maxScale)
        lastClampedField = if (next != transform.scale * pinchFactor) "scale" else null
        transform = transform.copy(scale = next)
        return next
    }

    /** 双击：在 base/档位1/档位2 之间循环。 */
    fun onDoubleTap(): Float {
        val target = AppGalleryPreviewZoomMath.doubleTapTargetScale(
            currentScale = transform.scale,
            minScale = minScale,
            level1 = safe.doubleTapZoomFactor,
            level2 = safe.doubleTapZoomFactorSecondary,
        )
        transform = if (target <= minScale) {
            AppGalleryPreviewTransform(scale = minScale)
        } else {
            transform.copy(scale = target)
        }
        lastClampedField = null
        return transform.scale
    }

    /** 直接设置倍率（滑杆/程序化），越界 clamp。 */
    fun setScale(scale: Float): Float {
        val next = AppGalleryPreviewZoomMath.clampScale(scale, minScale, maxScale)
        lastClampedField = if (next != scale) "scale" else null
        transform = transform.copy(scale = next)
        return next
    }

    /** 平移增量（像素），越界按橡皮筋比例钳制。 */
    fun onPan(dx: Float, dy: Float, viewportWidth: Float, viewportHeight: Float): AppGalleryPreviewTransform {
        val raw = transform.copy(offsetX = transform.offsetX + dx, offsetY = transform.offsetY + dy)
        val clamped = AppGalleryPreviewZoomMath.clampOffset(
            transform = raw,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = AppGalleryPreviewZoomMath.scaledContentSize(viewportWidth, transform.scale),
            contentHeight = AppGalleryPreviewZoomMath.scaledContentSize(viewportHeight, transform.scale),
            overscrollFactor = safe.panOverscrollFactor,
        )
        lastClampedField = when {
            clamped.offsetX != raw.offsetX -> "offsetX"
            clamped.offsetY != raw.offsetY -> "offsetY"
            else -> null
        }
        transform = clamped
        return transform
    }

    /** 复位到适配状态。 */
    fun reset() {
        transform = AppGalleryPreviewTransform(scale = minScale)
        lastClampedField = null
    }

    /** 翻页：按配置决定是否复位缩放/平移。 */
    fun resetForPage() {
        if (safe.zoomResetOnPageChange) reset() else transform = transform.copy(offsetX = 0f, offsetY = 0f)
    }
}
