package com.hinnka.mycamera.appgallerypreview

/** 对比所需的两个版本来源（原图 / 处理后）。 */
data class AppGalleryPreviewCompareVariants(
    val originalUri: String? = null,
    val processedUri: String? = null,
) {
    val hasOriginal: Boolean get() = !originalUri.isNullOrBlank()
    val hasProcessed: Boolean get() = !processedUri.isNullOrBlank()
}

/** 不可对比的原因（用于给出精确提示，而不是静默禁用）。 */
enum class AppGalleryPreviewCompareUnavailableReason {
    NONE,

    /** 缺少原图（PhotoProcessor 处理前未保留）。 */
    MISSING_ORIGINAL,

    /** 缺少处理后版本（仅 RAW/未导出）。 */
    MISSING_PROCESSED,

    /** 两个版本指向同一资源，对比无意义。 */
    SAME_SOURCE,

    /** 显式声明加载失败。 */
    LOAD_FAILED,
}

/** 对比可用性判定结果。 */
data class AppGalleryPreviewCompareAvailability(
    val available: Boolean,
    val reason: AppGalleryPreviewCompareUnavailableReason = AppGalleryPreviewCompareUnavailableReason.NONE,
    /** 判定时采用的初始滑杆位置（已 clamp）。 */
    val initialRatio: Float = 0.5f,
)

/**
 * 前后/原图对比的纯映射与可用性判定（自研，纯 JVM，可单测）。
 *
 * 自研理由：需要与工程既有的"处理前原图保留"逻辑（`gallery/GalleryManager.getOriginalImageFile`
 * 一类的原图/成品双版本约定）直接对齐，且滑杆位置↔比例映射要与配置化手柄热区绑定；
 * 现有库均为整块 UI 组件，无法满足"参数全开放 + 可单测"。
 */
object AppGalleryPreviewCompareMapping {

    /**
     * 滑杆位置 → 比例（0..1）。
     *
     * 位置区间为 [handleHalfPx, widthPx - handleHalfPx]，保证手柄不越出画面；
     * 区间退化（width <= 2*handleHalf 或 width <= 0）时返回 0.5（居中，安全值）。
     */
    fun ratioForPosition(xPx: Float, widthPx: Float, handleHalfPx: Float = 0f): Float {
        if (!widthPx.isFinite() || widthPx <= 0f || !xPx.isFinite()) return 0.5f
        val half = if (handleHalfPx.isFinite()) handleHalfPx.coerceAtLeast(0f) else 0f
        val left = half
        val right = widthPx - half
        val span = right - left
        if (span <= 0f) return 0.5f
        return ((xPx - left) / span).coerceIn(0f, 1f)
    }

    /** 比例（0..1）→ 滑杆位置（px）。 */
    fun positionForRatio(ratio: Float, widthPx: Float, handleHalfPx: Float = 0f): Float {
        if (!widthPx.isFinite() || widthPx <= 0f) return 0f
        val half = if (handleHalfPx.isFinite()) handleHalfPx.coerceAtLeast(0f) else 0f
        val left = half
        val right = widthPx - half
        val span = right - left
        if (span <= 0f) return widthPx / 2f
        return left + clampRatio(ratio) * span
    }

    /** 比例 clamp（NaN → 0.5）。 */
    fun clampRatio(ratio: Float): Float = if (!ratio.isFinite()) 0.5f else ratio.coerceIn(0f, 1f)

    /** 手柄命中测试：在 [touchSlopPx] 热区内视为拖动开始。 */
    fun hitTestHandle(xPx: Float, handleCenterPx: Float, touchSlopPx: Float): Boolean {
        if (!xPx.isFinite() || !handleCenterPx.isFinite()) return false
        val slop = if (touchSlopPx.isFinite()) touchSlopPx.coerceAtLeast(0f) else 0f
        return kotlin.math.abs(xPx - handleCenterPx) <= slop
    }

    /** 拖动增量 → 目标比例（先按视口宽度归一）。视口非法时保持原比例。 */
    fun draggedRatio(startRatio: Float, dxPx: Float, widthPx: Float): Float {
        if (!widthPx.isFinite() || widthPx <= 0f || !dxPx.isFinite()) return clampRatio(startRatio)
        return clampRatio(clampRatio(startRatio) + dxPx / widthPx)
    }

    /** 判定对比是否可用（需两种版本都在，可由配置放宽）。 */
    fun availability(
        variants: AppGalleryPreviewCompareVariants,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
        loadFailed: Boolean = false,
        sameSourceEquals: Boolean = true,
    ): AppGalleryPreviewCompareAvailability {
        val safe = config.appGalleryPreviewSafe().config
        val initial = clampRatio(safe.compareSliderDefaultRatio)
        if (loadFailed) {
            return AppGalleryPreviewCompareAvailability(
                available = false,
                reason = AppGalleryPreviewCompareUnavailableReason.LOAD_FAILED,
                initialRatio = initial,
            )
        }
        val missingOriginal = !variants.hasOriginal
        val missingProcessed = !variants.hasProcessed
        if (missingOriginal || missingProcessed) {
            if (safe.compareRequireBothVariants) {
                val reason = when {
                    missingOriginal && missingProcessed -> AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL
                    missingOriginal -> AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL
                    else -> AppGalleryPreviewCompareUnavailableReason.MISSING_PROCESSED
                }
                return AppGalleryPreviewCompareAvailability(false, reason, initial)
            }
        }
        if (sameSourceEquals && variants.hasOriginal && variants.hasProcessed &&
            variants.originalUri == variants.processedUri
        ) {
            return AppGalleryPreviewCompareAvailability(
                available = false,
                reason = AppGalleryPreviewCompareUnavailableReason.SAME_SOURCE,
                initialRatio = initial,
            )
        }
        return AppGalleryPreviewCompareAvailability(true, AppGalleryPreviewCompareUnavailableReason.NONE, initial)
    }

    /** 对比所需的最小手势消耗：返回 true 表示该次横向拖动应交给滑杆而非翻页。 */
    fun shouldConsumeHorizontalDrag(isComparing: Boolean, handleHit: Boolean): Boolean =
        isComparing && handleHit
}
