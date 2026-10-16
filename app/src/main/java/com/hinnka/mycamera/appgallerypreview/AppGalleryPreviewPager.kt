package com.hinnka.mycamera.appgallerypreview

/** 滑动切页的判定结果。 */
enum class AppGalleryPreviewSwipeDecision { NONE, PREVIOUS, NEXT }

/** 触发切页的条件（用于诊断与手势仲裁调参）。 */
enum class AppGalleryPreviewSwipeTrigger { NONE, DISTANCE, VELOCITY }

/** 未切页的原因（便于排查"为什么滑不动"）。 */
enum class AppGalleryPreviewSwipeBlockReason {
    NONE,
    /** 配置关闭了滑动切页。 */
    SWIPE_DISABLED,
    /** 当前处于放大状态，手势让位给平移。 */
    ZOOMED_IN,
    /** 已到首/尾，无相邻项。 */
    AT_EDGE,
    /** 位移与速度都未达阈值。 */
    BELOW_THRESHOLD,
    /** 视口宽度非法（<=0）。 */
    INVALID_VIEWPORT,
}

/** 一次滑动的完整判定结果。 */
data class AppGalleryPreviewSwipeOutcome(
    val decision: AppGalleryPreviewSwipeDecision = AppGalleryPreviewSwipeDecision.NONE,
    val trigger: AppGalleryPreviewSwipeTrigger = AppGalleryPreviewSwipeTrigger.NONE,
    val blockReason: AppGalleryPreviewSwipeBlockReason = AppGalleryPreviewSwipeBlockReason.NONE,
) {
    val switched: Boolean get() = decision != AppGalleryPreviewSwipeDecision.NONE
}

/**
 * 滑动切页纯数学（自研）。
 *
 * 规则（全部可配置）：
 *  1. 放大状态（`isZoomedIn`）下不切页，手势用于平移 —— 与 PhotoView/各系统相册一致；
 *  2. 快速抛掷（dp/s 超阈值）优先按方向切页；
 *  3. 慢速拖动需超过视口宽度的 [AppGalleryPreviewConfig.swipeSwitchThresholdFraction] 比例；
 *  4. 首/尾越界不切页（返回 AT_EDGE），由 UI 做回弹。
 */
object AppGalleryPreviewPagerMath {

    /**
     * 判定一次滑动是否切页。
     *
     * @param dxPx 位移（px，正=向右，对应上一张）
     * @param velocityDpPerSec 松手速度（dp/s，与 dxPx 同号）
     * @param viewportWidthPx 视口宽度（px）
     * @param isZoomedIn 当前是否处于放大状态
     * @param hasPrevious / @param hasNext 是否存在相邻项
     */
    fun resolveSwipe(
        dxPx: Float,
        velocityDpPerSec: Float,
        viewportWidthPx: Float,
        isZoomedIn: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewSwipeOutcome {
        val safe = config.appGalleryPreviewSafe().config
        if (!viewportWidthPx.isFinite() || viewportWidthPx <= 0f) {
            return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.INVALID_VIEWPORT)
        }
        if (!safe.swipeSwitchEnabled) {
            return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.SWIPE_DISABLED)
        }
        if (isZoomedIn) {
            return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.ZOOMED_IN)
        }
        if (!dxPx.isFinite() || dxPx == 0f) {
            // 速度单独超阈值也算切页（极短促的快速甩动）。
            if (velocityDpPerSec.isFinite() && kotlin.math.abs(velocityDpPerSec) >= safe.swipeVelocityThresholdDpPerSec) {
                return resolveByDirection(
                    dx = velocityDpPerSec,
                    trigger = AppGalleryPreviewSwipeTrigger.VELOCITY,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                )
            }
            return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.BELOW_THRESHOLD)
        }

        val velocityHit = velocityDpPerSec.isFinite() &&
            kotlin.math.abs(velocityDpPerSec) >= safe.swipeVelocityThresholdDpPerSec
        if (velocityHit) {
            return resolveByDirection(
                dx = dxPx,
                trigger = AppGalleryPreviewSwipeTrigger.VELOCITY,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
            )
        }

        val distanceFraction = kotlin.math.abs(dxPx) / viewportWidthPx
        if (distanceFraction >= safe.swipeSwitchThresholdFraction) {
            return resolveByDirection(
                dx = dxPx,
                trigger = AppGalleryPreviewSwipeTrigger.DISTANCE,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
            )
        }
        return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.BELOW_THRESHOLD)
    }

    /** 按方向落到具体决策，并做首/尾边界检查（dx>0 → 上一张）。 */
    fun resolveByDirection(
        dx: Float,
        trigger: AppGalleryPreviewSwipeTrigger,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): AppGalleryPreviewSwipeOutcome {
        if (dx > 0f) {
            return if (hasPrevious) {
                AppGalleryPreviewSwipeOutcome(AppGalleryPreviewSwipeDecision.PREVIOUS, trigger)
            } else {
                AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.AT_EDGE)
            }
        }
        if (dx < 0f) {
            return if (hasNext) {
                AppGalleryPreviewSwipeOutcome(AppGalleryPreviewSwipeDecision.NEXT, trigger)
            } else {
                AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.AT_EDGE)
            }
        }
        return AppGalleryPreviewSwipeOutcome(blockReason = AppGalleryPreviewSwipeBlockReason.BELOW_THRESHOLD)
    }

    /** 拖动中的跟手位移（px），按 [AppGalleryPreviewConfig.swipeFollowFactor] 衰减并限制在视口内。 */
    fun followOffsetPx(dragPx: Float, viewportWidthPx: Float, config: AppGalleryPreviewConfig): Float {
        if (!dragPx.isFinite() || !viewportWidthPx.isFinite() || viewportWidthPx <= 0f) return 0f
        val safe = config.appGalleryPreviewSafe().config
        val scaled = dragPx * safe.swipeFollowFactor
        return scaled.coerceIn(-viewportWidthPx, viewportWidthPx)
    }
}

/**
 * 大图预览翻页状态机（纯 JVM，可单测）。
 *
 * 负责：当前序号 + 相邻可用性 + 拖动跟手位移 + 松手结算；
 * 与 [AppGalleryPreviewZoomState] 协作：放大时不切页。
 */
class AppGalleryPreviewPagerState(
    count: Int,
    initialIndex: Int = 0,
    val config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
) {
    private val safe = config.appGalleryPreviewSafe().config

    /** 项数；<0 视为 0。 */
    val count: Int = count.coerceAtLeast(0)

    private var indexInternal: Int = when {
        this.count == 0 -> 0
        else -> initialIndex.coerceIn(0, this.count - 1)
    }

    /** 当前序号（0-based，必在 [0, count-1]；count=0 时为 0）。 */
    val currentIndex: Int get() = indexInternal

    /** 拖动中的跟手位移（px），松手后归零。 */
    var followOffsetPx: Float = 0f
        private set

    /** 最近一次结算的完整结果（诊断用）。 */
    var lastOutcome: AppGalleryPreviewSwipeOutcome = AppGalleryPreviewSwipeOutcome()
        private set

    val hasPrevious: Boolean get() = count > 1 && indexInternal > 0
    val hasNext: Boolean get() = count > 1 && indexInternal < count - 1
    val isEmpty: Boolean get() = count == 0

    /** 拖动更新跟手位移。 */
    fun onDrag(dragPx: Float, viewportWidthPx: Float): Float {
        followOffsetPx = AppGalleryPreviewPagerMath.followOffsetPx(dragPx, viewportWidthPx, safe)
        return followOffsetPx
    }

    /**
     * 松手结算：应用切页决策并复位跟手位移。
     * @return 决策结果（NONE 表示回弹）
     */
    fun settle(
        dragPx: Float,
        velocityDpPerSec: Float,
        viewportWidthPx: Float,
        isZoomedIn: Boolean,
    ): AppGalleryPreviewSwipeOutcome {
        val outcome = AppGalleryPreviewPagerMath.resolveSwipe(
            dxPx = dragPx,
            velocityDpPerSec = velocityDpPerSec,
            viewportWidthPx = viewportWidthPx,
            isZoomedIn = isZoomedIn,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            config = safe,
        )
        applyOutcome(outcome)
        return outcome
    }

    /** 应用一次判定（内部使用，也便于测试直接驱动）。 */
    fun applyOutcome(outcome: AppGalleryPreviewSwipeOutcome) {
        when (outcome.decision) {
            AppGalleryPreviewSwipeDecision.PREVIOUS -> if (hasPrevious) indexInternal--
            AppGalleryPreviewSwipeDecision.NEXT -> if (hasNext) indexInternal++
            AppGalleryPreviewSwipeDecision.NONE -> Unit
        }
        followOffsetPx = 0f
        lastOutcome = outcome
    }

    /** 直接跳到指定序号，越界 clamp。 */
    fun goTo(target: Int): Int {
        if (count == 0) {
            indexInternal = 0
        } else {
            indexInternal = target.coerceIn(0, count - 1)
        }
        followOffsetPx = 0f
        return indexInternal
    }

    fun goNext(): Boolean = if (hasNext) {
        indexInternal++
        followOffsetPx = 0f
        true
    } else {
        false
    }

    fun goPrevious(): Boolean = if (hasPrevious) {
        indexInternal--
        followOffsetPx = 0f
        true
    } else {
        false
    }

    /** 页数变化（例如删除一张后刷新）时保持当前项尽量不变。 */
    fun updateCount(newCount: Int) {
        val target = if (newCount <= 0) 0 else indexInternal.coerceIn(0, newCount - 1)
        // count 为 val，这里通过重建序号保证不变式；调用方应重建实例以获得新 count。
        indexInternal = target
    }
}
