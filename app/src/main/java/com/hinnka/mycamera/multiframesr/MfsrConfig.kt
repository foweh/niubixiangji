package com.hinnka.mycamera.multiframesr

/**
 * MFSR 全部开放参数（用户 2026-10-16 开放度硬约束：每个可调参数都要有默认值 + 取值范围 + clamp）。
 *
 * ## 参数范围（越界一律 clamp，绝不崩溃）
 * | 参数 | 默认 | 范围 | 说明 |
 * |---|---|---|---|
 * | [frameCount] | 8 | 2 ~ 1000 | 参与融合的帧数。**无总量上限**，只做安全 clamp；内存问题走降级 |
 * | [scale] | 2.0 | 1.25 ~ 3.0 | HR 相对工作分辨率的放大倍率 |
 * | [ibpIterations] | 4 | 3 ~ 8 | 迭代反投影轮数 |
 * | [alignmentEnabled] | true | - | 对齐总开关 |
 * | [alignMethod] | BLOCK_MATCH | 枚举 | 亚像素对齐方法 |
 * | [subpixelRefineIterations] | 8 | 1 ~ 32 | 梯度法亚像素精化的最大迭代 |
 * | [minAlignConfidence] | 0.15 | 0 ~ 1 | 低于此置信度的帧不采纳对齐（按零平移叠加） |
 * | [maxAlignShiftPx] | 32 | 1 ~ 256 | 允许的最大平移（工作分辨率像素），防误设越界 |
 * | [workingResolution] | FULL | 枚举 | 工作分辨率起始档位；内存不足自动降级 |
 * | [strength] | 1.0 | 0 ~ 2 | 总强度：作用于 IBP 步长与最终锐化 |
 * | [denoiseOn] | false | - | 最终双边降噪开关 |
 * | [denoiseRadius] | 2 | 1 ~ 4 | 双边滤波半径 |
 * | [sharpenAmount] | 0.35 | 0 ~ 1 | 反锐化（USM）强度 |
 * | [sharpenSigma] | 1.1 | 0.4 ~ 4 | USM 高斯半径 |
 * | [ibpFrameStride] | 0（自动） | 0 ~ 64 | IBP 每轮的帧采样步长；0=自动（按帧数摊薄）。**不限制总帧数** |
 * | [memoryBudgetBytes] | 512MB | 32MB ~ 2GB | 本任务的帧缓存/中间缓冲内存预算 |
 * | [inputMaxSide] | 2048 | 512 ~ 4096 | 送入帧的长边上限（先缩放再取亮度，控制读图峰值） |
 * | [jpegQuality] | 96 | 60 ~ 100 | 输出 JPEG 质量 |
 *
 * 持久化：[MfsrConfigCodec] 给出稳定 key（供 `MfsrPreferences` 写入 SharedPreferences）。
 */
data class MfsrConfig(
    val frameCount: Int = DEFAULT_FRAME_COUNT,
    val scale: Float = DEFAULT_SCALE,
    val ibpIterations: Int = DEFAULT_IBP_ITERATIONS,
    val alignmentEnabled: Boolean = true,
    val alignMethod: MfsrAlignMethod = MfsrAlignMethod.BLOCK_MATCH,
    val subpixelRefineIterations: Int = DEFAULT_SUBPIXEL_REFINE_ITERATIONS,
    val minAlignConfidence: Float = DEFAULT_MIN_ALIGN_CONFIDENCE,
    val maxAlignShiftPx: Int = DEFAULT_MAX_ALIGN_SHIFT_PX,
    val workingResolution: MfsrWorkingResolution = MfsrWorkingResolution.FULL,
    val strength: Float = DEFAULT_STRENGTH,
    val denoiseOn: Boolean = false,
    val denoiseRadius: Int = DEFAULT_DENOISE_RADIUS,
    val sharpenAmount: Float = DEFAULT_SHARPEN_AMOUNT,
    val sharpenSigma: Float = DEFAULT_SHARPEN_SIGMA,
    val ibpFrameStride: Int = DEFAULT_IBP_FRAME_STRIDE,
    val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
    val inputMaxSide: Int = DEFAULT_INPUT_MAX_SIDE,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    /** 对齐是否真正参与（总开关 + 方法）。 */
    val alignmentActive: Boolean get() = alignmentEnabled && alignMethod != MfsrAlignMethod.OFF

    /** IBP 实际步长：0（自动）时按总帧数摊薄到 ~96 帧/轮，避免千帧任务迭代爆炸。 */
    fun effectiveIbpStride(frameCount: Int): Int {
        if (ibpFrameStride > 0) return ibpFrameStride.coerceIn(1, IBP_STRIDE_MAX)
        val n = frameCount.coerceAtLeast(1)
        return (n / IBP_AUTO_TARGET_FRAMES_PER_ITERATION).coerceAtLeast(1).coerceAtMost(IBP_STRIDE_MAX)
    }

    /** 被 clamp 修正过的字段名（供日志/UI 提示）。 */
    fun coerceWithReport(): Pair<MfsrConfig, List<String>> {
        val fixed = mutableListOf<String>()
        fun <T> chk(name: String, raw: T, out: T): T {
            if (raw != out) fixed += name
            return out
        }
        val c = MfsrConfig(
            frameCount = chk(
                "frameCount", frameCount,
                frameCount.coerceIn(FRAME_MIN, FRAME_MAX),
            ),
            scale = chk("scale", scale, safeFloat(scale, DEFAULT_SCALE).coerceIn(SCALE_MIN, SCALE_MAX)),
            ibpIterations = chk(
                "ibpIterations", ibpIterations,
                ibpIterations.coerceIn(IBP_MIN, IBP_MAX),
            ),
            alignmentEnabled = alignmentEnabled,
            alignMethod = alignMethod,
            subpixelRefineIterations = chk(
                "subpixelRefineIterations", subpixelRefineIterations,
                subpixelRefineIterations.coerceIn(SUBPIXEL_REFINE_MIN, SUBPIXEL_REFINE_MAX),
            ),
            minAlignConfidence = chk(
                "minAlignConfidence", minAlignConfidence,
                safeFloat(minAlignConfidence, DEFAULT_MIN_ALIGN_CONFIDENCE)
                    .coerceIn(MIN_ALIGN_CONFIDENCE_MIN, MIN_ALIGN_CONFIDENCE_MAX),
            ),
            maxAlignShiftPx = chk(
                "maxAlignShiftPx", maxAlignShiftPx,
                maxAlignShiftPx.coerceIn(MAX_ALIGN_SHIFT_MIN, MAX_ALIGN_SHIFT_MAX),
            ),
            workingResolution = workingResolution,
            strength = chk("strength", strength, safeFloat(strength, DEFAULT_STRENGTH).coerceIn(STRENGTH_MIN, STRENGTH_MAX)),
            denoiseOn = denoiseOn,
            denoiseRadius = chk(
                "denoiseRadius", denoiseRadius,
                denoiseRadius.coerceIn(DENOISE_RADIUS_MIN, DENOISE_RADIUS_MAX),
            ),
            sharpenAmount = chk(
                "sharpenAmount", sharpenAmount,
                safeFloat(sharpenAmount, DEFAULT_SHARPEN_AMOUNT).coerceIn(SHARPEN_AMOUNT_MIN, SHARPEN_AMOUNT_MAX),
            ),
            sharpenSigma = chk(
                "sharpenSigma", sharpenSigma,
                safeFloat(sharpenSigma, DEFAULT_SHARPEN_SIGMA).coerceIn(SHARPEN_SIGMA_MIN, SHARPEN_SIGMA_MAX),
            ),
            ibpFrameStride = chk(
                "ibpFrameStride", ibpFrameStride,
                ibpFrameStride.coerceIn(0, IBP_STRIDE_MAX),
            ),
            memoryBudgetBytes = chk(
                "memoryBudgetBytes", memoryBudgetBytes,
                memoryBudgetBytes.coerceIn(MEMORY_BUDGET_MIN_BYTES, MEMORY_BUDGET_MAX_BYTES),
            ),
            inputMaxSide = chk(
                "inputMaxSide", inputMaxSide,
                inputMaxSide.coerceIn(INPUT_MAX_SIDE_MIN, INPUT_MAX_SIDE_MAX),
            ),
            jpegQuality = chk("jpegQuality", jpegQuality, jpegQuality.coerceIn(JPEG_QUALITY_MIN, JPEG_QUALITY_MAX)),
        )
        return c to fixed
    }

    /** clamp 后返回（不抛异常）。 */
    fun coerced(): MfsrConfig = coerceWithReport().first

    companion object {
        const val FRAME_MIN = 2
        /** 开放但安全的上界：2~1000 帧均可跑，无总量硬上限（资源问题走降级）。 */
        const val FRAME_MAX = 1000

        const val DEFAULT_FRAME_COUNT = 8
        const val SCALE_MIN = 1.25f
        const val SCALE_MAX = 3.0f
        const val DEFAULT_SCALE = 2.0f

        const val IBP_MIN = 3
        const val IBP_MAX = 8
        const val DEFAULT_IBP_ITERATIONS = 4

        const val SUBPIXEL_REFINE_MIN = 1
        const val SUBPIXEL_REFINE_MAX = 32
        const val DEFAULT_SUBPIXEL_REFINE_ITERATIONS = 8

        const val MIN_ALIGN_CONFIDENCE_MIN = 0f
        const val MIN_ALIGN_CONFIDENCE_MAX = 1f
        const val DEFAULT_MIN_ALIGN_CONFIDENCE = 0.15f

        const val MAX_ALIGN_SHIFT_MIN = 1
        const val MAX_ALIGN_SHIFT_MAX = 256
        const val DEFAULT_MAX_ALIGN_SHIFT_PX = 32

        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 2f
        const val DEFAULT_STRENGTH = 1f

        const val DENOISE_RADIUS_MIN = 1
        const val DENOISE_RADIUS_MAX = 4

        const val SHARPEN_AMOUNT_MIN = 0f
        const val SHARPEN_AMOUNT_MAX = 1f
        const val DEFAULT_SHARPEN_AMOUNT = 0.35f

        const val SHARPEN_SIGMA_MIN = 0.4f
        const val SHARPEN_SIGMA_MAX = 4f
        const val DEFAULT_SHARPEN_SIGMA = 1.1f

        const val IBP_STRIDE_MAX = 64
        const val DEFAULT_IBP_FRAME_STRIDE = 0
        /** 自动步长的目标：每轮 IBP 采样帧数上限参考值（不是帧数硬上限）。 */
        const val IBP_AUTO_TARGET_FRAMES_PER_ITERATION = 96

        const val MEMORY_BUDGET_MIN_BYTES = 32L * 1024 * 1024
        const val MEMORY_BUDGET_MAX_BYTES = 2048L * 1024 * 1024
        const val DEFAULT_MEMORY_BUDGET_BYTES = 512L * 1024 * 1024

        const val INPUT_MAX_SIDE_MIN = 512
        const val INPUT_MAX_SIDE_MAX = 4096
        const val DEFAULT_INPUT_MAX_SIDE = 2048

        const val JPEG_QUALITY_MIN = 60
        const val JPEG_QUALITY_MAX = 100
        const val DEFAULT_JPEG_QUALITY = 96

        /** NaN / Infinity 一律回落到默认值。 */
        internal fun safeFloat(v: Float, fallback: Float): Float = if (v.isFinite()) v else fallback
    }
}

/**
 * 配置字符串编解码（纯逻辑，可纯 JVM 测试；SharedPreferences 只是它的一个持久化后端）。
 * key 名稳定，不与既有模块冲突（统一 mfsr_ 前缀）。
 */
object MfsrConfigCodec {
    const val KEY_FRAME_COUNT = "mfsr_frame_count"
    const val KEY_SCALE = "mfsr_scale"
    const val KEY_IBP_ITERATIONS = "mfsr_ibp_iterations"
    const val KEY_ALIGN_ENABLED = "mfsr_align_enabled"
    const val KEY_ALIGN_METHOD = "mfsr_align_method"
    const val KEY_SUBPIXEL_REFINE = "mfsr_subpixel_refine_iterations"
    const val KEY_MIN_ALIGN_CONFIDENCE = "mfsr_min_align_confidence"
    const val KEY_MAX_ALIGN_SHIFT = "mfsr_max_align_shift_px"
    const val KEY_WORKING_RESOLUTION = "mfsr_working_resolution"
    const val KEY_STRENGTH = "mfsr_strength"
    const val KEY_DENOISE_ON = "mfsr_denoise_on"
    const val KEY_DENOISE_RADIUS = "mfsr_denoise_radius"
    const val KEY_SHARPEN_AMOUNT = "mfsr_sharpen_amount"
    const val KEY_SHARPEN_SIGMA = "mfsr_sharpen_sigma"
    const val KEY_IBP_STRIDE = "mfsr_ibp_frame_stride"
    const val KEY_MEMORY_BUDGET = "mfsr_memory_budget_bytes"
    const val KEY_INPUT_MAX_SIDE = "mfsr_input_max_side"
    const val KEY_JPEG_QUALITY = "mfsr_jpeg_quality"

    /** 导出为 key→value 字符串表（写入侧）。 */
    fun encode(config: MfsrConfig): Map<String, String> {
        val c = config.coerced()
        return linkedMapOf(
            KEY_FRAME_COUNT to c.frameCount.toString(),
            KEY_SCALE to c.scale.toString(),
            KEY_IBP_ITERATIONS to c.ibpIterations.toString(),
            KEY_ALIGN_ENABLED to c.alignmentEnabled.toString(),
            KEY_ALIGN_METHOD to c.alignMethod.name,
            KEY_SUBPIXEL_REFINE to c.subpixelRefineIterations.toString(),
            KEY_MIN_ALIGN_CONFIDENCE to c.minAlignConfidence.toString(),
            KEY_MAX_ALIGN_SHIFT to c.maxAlignShiftPx.toString(),
            KEY_WORKING_RESOLUTION to c.workingResolution.name,
            KEY_STRENGTH to c.strength.toString(),
            KEY_DENOISE_ON to c.denoiseOn.toString(),
            KEY_DENOISE_RADIUS to c.denoiseRadius.toString(),
            KEY_SHARPEN_AMOUNT to c.sharpenAmount.toString(),
            KEY_SHARPEN_SIGMA to c.sharpenSigma.toString(),
            KEY_IBP_STRIDE to c.ibpFrameStride.toString(),
            KEY_MEMORY_BUDGET to c.memoryBudgetBytes.toString(),
            KEY_INPUT_MAX_SIDE to c.inputMaxSide.toString(),
            KEY_JPEG_QUALITY to c.jpegQuality.toString(),
        )
    }

    /**
     * 从 key→value 表解析（读取侧）。缺失 / 非法值全部回落默认，再统一 clamp，
     * 因此损坏的配置也不会导致崩溃。
     */
    fun decode(values: Map<String, String?>): MfsrConfig {
        val d = MfsrConfig()
        val raw = MfsrConfig(
            frameCount = values[KEY_FRAME_COUNT]?.toIntOrNull() ?: d.frameCount,
            scale = values[KEY_SCALE]?.toFloatOrNull() ?: d.scale,
            ibpIterations = values[KEY_IBP_ITERATIONS]?.toIntOrNull() ?: d.ibpIterations,
            alignmentEnabled = values[KEY_ALIGN_ENABLED]?.toBooleanStrictOrNull() ?: d.alignmentEnabled,
            alignMethod = values[KEY_ALIGN_METHOD]?.let { name ->
                MfsrAlignMethod.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } ?: d.alignMethod,
            subpixelRefineIterations = values[KEY_SUBPIXEL_REFINE]?.toIntOrNull()
                ?: d.subpixelRefineIterations,
            minAlignConfidence = values[KEY_MIN_ALIGN_CONFIDENCE]?.toFloatOrNull()
                ?: d.minAlignConfidence,
            maxAlignShiftPx = values[KEY_MAX_ALIGN_SHIFT]?.toIntOrNull() ?: d.maxAlignShiftPx,
            workingResolution = values[KEY_WORKING_RESOLUTION]?.let { name ->
                MfsrWorkingResolution.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } ?: d.workingResolution,
            strength = values[KEY_STRENGTH]?.toFloatOrNull() ?: d.strength,
            denoiseOn = values[KEY_DENOISE_ON]?.toBooleanStrictOrNull() ?: d.denoiseOn,
            denoiseRadius = values[KEY_DENOISE_RADIUS]?.toIntOrNull() ?: d.denoiseRadius,
            sharpenAmount = values[KEY_SHARPEN_AMOUNT]?.toFloatOrNull() ?: d.sharpenAmount,
            sharpenSigma = values[KEY_SHARPEN_SIGMA]?.toFloatOrNull() ?: d.sharpenSigma,
            ibpFrameStride = values[KEY_IBP_STRIDE]?.toIntOrNull() ?: d.ibpFrameStride,
            memoryBudgetBytes = values[KEY_MEMORY_BUDGET]?.toLongOrNull() ?: d.memoryBudgetBytes,
            inputMaxSide = values[KEY_INPUT_MAX_SIDE]?.toIntOrNull() ?: d.inputMaxSide,
            jpegQuality = values[KEY_JPEG_QUALITY]?.toIntOrNull() ?: d.jpegQuality,
        )
        return raw.coerced()
    }

    /** 单行摘要（写日志 / UI 副标题）。 */
    fun summary(config: MfsrConfig): String {
        val c = config.coerced()
        return "frames=${c.frameCount} scale=${c.scale} ibp=${c.ibpIterations} " +
            "align=${if (c.alignmentActive) c.alignMethod.name else "OFF"} " +
            "work=${c.workingResolution.label} strength=${c.strength}"
    }
}

/**
 * UI 步进阶梯（帧数/倍率/迭代的循环档位）。
 *
 * 帧数阶梯刻意做成**几何递增并一直排到 1000**，保证 UI 能真的把帧数调到上百帧，
 * 不存在「只能到 32 帧」这类隐性上限。
 */
object MfsrUiLadder {
    /** 帧数档位：2 → 1000（几何步进）。 */
    val FRAME_STEPS: IntArray = intArrayOf(
        2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1000,
    )

    /** 倍率档位：1.25 → 3.0。 */
    val SCALE_STEPS: FloatArray = floatArrayOf(1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    /** 迭代档位：3 → 8。 */
    val IBP_STEPS: IntArray = intArrayOf(3, 4, 5, 6, 7, 8)

    /** 强度档位：0 → 2。 */
    val STRENGTH_STEPS: FloatArray = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f)

    /**
     * 帧数步进：到顶停在 1000（**刻意不回卷**，避免误触把上百帧的设置直接掉回 2 帧）。
     * 当前值不在档位时跳到第一个更大的档位；越界值先 clamp。
     */
    fun stepFrameCount(current: Int): Int = stepInt(FRAME_STEPS, current, MfsrConfig.FRAME_MIN, MfsrConfig.FRAME_MAX)

    /** 迭代档位循环：3 → 4 → … → 8 → 3（可来回调，不锁死在最大档）。 */
    fun stepIbpIterations(current: Int): Int {
        val v = current.coerceIn(MfsrConfig.IBP_MIN, MfsrConfig.IBP_MAX)
        val idx = IBP_STEPS.indexOf(v)
        return if (idx < 0) IBP_STEPS.first() else IBP_STEPS[(idx + 1) % IBP_STEPS.size]
    }

    fun stepScale(current: Float): Float {
        val v = MfsrConfig.safeFloat(current, MfsrConfig.DEFAULT_SCALE)
            .coerceIn(MfsrConfig.SCALE_MIN, MfsrConfig.SCALE_MAX)
        val next = SCALE_STEPS.firstOrNull { it > v + 1e-4f } ?: SCALE_STEPS.first()
        return next
    }

    fun stepStrength(current: Float): Float {
        val v = MfsrConfig.safeFloat(current, MfsrConfig.DEFAULT_STRENGTH)
            .coerceIn(MfsrConfig.STRENGTH_MIN, MfsrConfig.STRENGTH_MAX)
        return STRENGTH_STEPS.firstOrNull { it > v + 1e-4f } ?: STRENGTH_STEPS.first()
    }

    /** 工作分辨率档位循环：1.0x → 0.75x → 0.5x → 1.0x。 */
    fun stepWorkingResolution(current: MfsrWorkingResolution): MfsrWorkingResolution {
        val idx = MfsrWorkingResolution.LADDER.indexOf(current)
        return MfsrWorkingResolution.LADDER[(idx + 1) % MfsrWorkingResolution.LADDER.size]
    }

    fun stepAlignMethod(current: MfsrAlignMethod): MfsrAlignMethod {
        val all = MfsrAlignMethod.entries
        return all[(all.indexOf(current) + 1) % all.size]
    }

    private fun stepInt(steps: IntArray, current: Int, min: Int, max: Int): Int {
        val v = current.coerceIn(min, max)
        return steps.firstOrNull { it > v } ?: max
    }
}
