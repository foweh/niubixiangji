package com.hinnka.mycamera.multiframesr

import kotlin.math.max

/**
 * 一次任务的内存决策结果。
 *
 * @param guardSatisfied 内存守卫是否放行（false 表示即使降到最低档位、且已启用流式/落盘，
 *   一次性分配仍超出守卫阈值 → 走流式累加，**不拒绝任务**）。
 */
data class MfsrMemoryDecision(
    val resolution: MfsrWorkingResolution,
    val workWidth: Int,
    val workHeight: Int,
    val hrWidth: Int,
    val hrHeight: Int,
    /** HR 累加 + 估计 + IBP 缓冲的一次性峰值需求（字节）。 */
    val peakBytes: Long,
    /** 可用于常驻帧缓存的内存（字节，可能为 0 → 全部落盘）。 */
    val residentFrameBudgetBytes: Long,
    /** 相对用户请求档位下调了几级（0 = 未降级）。 */
    val degradedSteps: Int,
    /** 是否启用了落盘/流式回退。 */
    val streamingFallback: Boolean,
    val guardSatisfied: Boolean,
) {
    val degraded: Boolean get() = degradedSteps > 0 || streamingFallback
}

/**
 * 内存策略：把「帧数开放」与「3.9G 内存会 OOM」这对矛盾化解为**降级**而非拒绝。
 *
 * 阶梯：用户请求档位 → 0.75x → 0.5x；仍不满足则保持最低档 + 帧缓存落盘 + 流式累加。
 * 任何输入都不会抛异常，也永远不会因为帧数多而拒绝任务。
 */
object MfsrMemoryPolicy {

    /** 每个浮点样本的字节数。 */
    private const val BYTES_PER_FLOAT = 4L

    /**
     * 估算一次任务在给定工作尺寸/倍率下的一次性峰值内存。
     *
     * 构成：HR 累加器（sum+weight，2×）+ HR 估计（1×）+ IBP 误差缓冲（err+wsum，2×）
     * + 一张工作分辨率 LR 帧（1×）。
     * 注意：**与帧数无关** —— 帧数据是流式的，常驻量由 [MfsrFrameStore] 单独控制。
     */
    fun estimatePeakBytes(workWidth: Int, workHeight: Int, scale: Float): Long {
        val hrW = hrSize(workWidth, scale)
        val hrH = hrSize(workHeight, scale)
        val hrPixels = hrW.toLong() * hrH.toLong()
        val lrPixels = workWidth.toLong() * workHeight.toLong()
        return BYTES_PER_FLOAT * (5L * hrPixels + lrPixels)
    }

    /** HR 边长：工作边长 × 倍率，四舍五入，至少 1。 */
    fun hrSize(workSize: Int, scale: Float): Int {
        val s = if (scale.isFinite()) scale.coerceIn(MfsrConfig.SCALE_MIN, MfsrConfig.SCALE_MAX) else 1f
        return max(1, Math.round(workSize * s))
    }

    /**
     * 决定工作分辨率档位。
     *
     * @param guard 内存守卫（生产环境传 `{ job.guardMemory(it) }`；测试可传常量）。
     * @return 决策结果；`guardSatisfied=false` 表示走流式回退，但不拒绝。
     */
    fun decide(
        lrWidth: Int,
        lrHeight: Int,
        scale: Float,
        requested: MfsrWorkingResolution,
        budgetBytes: Long,
        guard: (Long) -> Boolean,
        spillAvailable: Boolean,
    ): MfsrMemoryDecision {
        val w0 = max(1, lrWidth)
        val h0 = max(1, lrHeight)
        val startIdx = MfsrWorkingResolution.LADDER.indexOf(requested).coerceAtLeast(0)
        var last: MfsrMemoryDecision? = null
        for (idx in startIdx until MfsrWorkingResolution.LADDER.size) {
            val tier = MfsrWorkingResolution.LADDER[idx]
            val w = workSize(w0, tier)
            val h = workSize(h0, tier)
            val peak = estimatePeakBytes(w, h, scale)
            val decision = build(tier, w, h, scale, peak, budgetBytes, idx - startIdx, streaming = false, satisfied = true)
            if (peak <= budgetBytes && guard(peak)) return decision
            last = decision
        }
        // 全部档位都不满足 → 保持最低档 + 流式/落盘，永不拒绝。
        val tier = MfsrWorkingResolution.LADDER.last()
        val w = workSize(w0, tier)
        val h = workSize(h0, tier)
        val peak = estimatePeakBytes(w, h, scale)
        return build(
            resolution = tier,
            workWidth = w,
            workHeight = h,
            scale = scale,
            peak = peak,
            budgetBytes = budgetBytes,
            degradedSteps = MfsrWorkingResolution.LADDER.size - 1 - startIdx,
            streaming = spillAvailable || last != null,
            satisfied = false,
        )
    }

    private fun build(
        resolution: MfsrWorkingResolution,
        workWidth: Int,
        workHeight: Int,
        scale: Float,
        peak: Long,
        budgetBytes: Long,
        degradedSteps: Int,
        streaming: Boolean,
        satisfied: Boolean,
    ): MfsrMemoryDecision = MfsrMemoryDecision(
        resolution = resolution,
        workWidth = workWidth,
        workHeight = workHeight,
        hrWidth = hrSize(workWidth, scale),
        hrHeight = hrSize(workHeight, scale),
        peakBytes = peak,
        residentFrameBudgetBytes = max(0L, budgetBytes - peak),
        degradedSteps = degradedSteps,
        streamingFallback = streaming,
        guardSatisfied = satisfied,
    )

    /** 工作边长 = 原始边长 × 档位系数（至少 8px，避免出现退化尺寸）。 */
    fun workSize(size: Int, tier: MfsrWorkingResolution): Int =
        max(8, Math.round(max(1, size) * tier.factor))

    /**
     * 帧数准入判定：**永远放行**（帧数开放是硬需求）。
     *
     * 只有 0 帧这种没有任何输入的调用才会得到 false —— 那是"没有任务"而不是"帧数过多"。
     */
    fun frameCountAccepted(frameCount: Int): Boolean = frameCount >= 1
}
