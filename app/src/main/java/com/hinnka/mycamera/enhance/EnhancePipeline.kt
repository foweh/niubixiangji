package com.hinnka.mycamera.enhance

import kotlin.math.sqrt

/**
 * 经典后处理管线编排（纯逻辑，无 Android 依赖，可直接 JVM 单测）。
 *
 * 职责：
 * ① 按 [EnhanceConfig.stageOrder] 顺序执行启用中的阶段；
 * ② 每完成一个分片回调 [onChunk]（集成层传 `{ job.checkYield() }`，实现暂停/取消/CPU 节流）；
 * ③ 依据内存预算决策工作分辨率缩放（[resolveWorkingScale]）——内存不够就降工作分辨率，
 *    **不对图像尺寸/帧数设硬上限**（PATTERN_SPEC 第 1.5 节）。
 *
 * 分片粒度（诚实说明）：
 * - [EnhanceStage.DENOISE] 与 [EnhanceStage.FUN_FILTER] 的油画分支：按行带分片，可细粒度让出；
 * - [EnhanceStage.DEHAZE] / [EnhanceStage.CLAHE] / [EnhanceStage.SHARPEN]：算法本身需要全局
 *   （tile 网格 / 全局大气光 / 全图模糊核），各自作为**一个分片**整体执行，结束后让出。
 */
object EnhancePipeline {

    /** 工作分辨率允许的最小缩放（再低就没有画质意义）。 */
    const val MIN_WORKING_SCALE = 0.1f

    /** 工作分辨率降级候选档位（从高到低）。 */
    val WORKING_SCALE_STEPS: List<Float> = listOf(1f, 0.75f, 0.5f, 0.35f, 0.25f, 0.15f)

    /**
     * 峰值并存平面数（估算用）：输入平面 RGB(3) + 亮度(1) + 模糊/中间缓冲(2)
     * + 去雾的暗通道/透射率(2) = 8。取保守值，宁可多降一档也不 OOM。
     */
    const val PEAK_PLANES = 8

    /** 每个 32 位浮点元素 4 字节。 */
    const val BYTES_PER_FLOAT = 4L

    /** 估算给定尺寸/缩放下的峰值字节数（= 平面数 × 像素数 × 4）。 */
    fun estimateBytes(width: Int, height: Int, scale: Float = 1f): Long {
        val s = if (scale.isFinite()) scale.coerceIn(MIN_WORKING_SCALE, 1f) else 1f
        val pixels = (width.toLong() * height.toLong()).coerceAtLeast(1L)
        val scaled = (pixels.toFloat() * s * s).coerceAtLeast(1f).toLong()
        return scaled * PEAK_PLANES * BYTES_PER_FLOAT
    }

    /**
     * 决策实际可用的工作分辨率缩放。
     *
     * 优先满足调用方请求的 [requestedScale]；若其峰值超出 [memoryBudgetBytes]，
     * 沿 [WORKING_SCALE_STEPS] 逐档下调；所有档位都不行时按 `sqrt(budget / 全分辨率峰值)`
     * 连续求解，但**不低于 [MIN_WORKING_SCALE]**（不返回 0，不抛异常）。
     */
    fun resolveWorkingScale(
        width: Int,
        height: Int,
        requestedScale: Float,
        memoryBudgetBytes: Long,
    ): Float {
        val requested = if (requestedScale.isFinite()) {
            requestedScale.coerceIn(MIN_WORKING_SCALE, 1f)
        } else {
            1f
        }
        val budget = if (memoryBudgetBytes > 0L) memoryBudgetBytes else Long.MAX_VALUE
        if (estimateBytes(width, height, requested) <= budget) return requested

        val lowerSteps = WORKING_SCALE_STEPS.filter { it < requested }.sortedDescending()
        for (step in lowerSteps) {
            if (estimateBytes(width, height, step) <= budget) return step
        }
        val fullBytes = estimateBytes(width, height, 1f).toDouble()
        if (fullBytes <= 0.0) return MIN_WORKING_SCALE
        val continuous = sqrt(budget.toDouble() / fullBytes).toFloat()
        return continuous.coerceIn(MIN_WORKING_SCALE, requested)
    }

    /**
     * 执行管线。
     *
     * @param pixels ARGB_8888 像素（工作分辨率下），不改入参。
     * @param bandHeight 行带高度；<= 0 表示不分片（小图/单测）。
     * @param onStage 阶段开始回调（stage, 0..1 进度），供 UI/日志用。
     * @param onChunk 分片结束回调（集成层传 `{ job.checkYield() }`）。
     * @return 处理后的新像素数组；无阶段启用时返回输入副本（恒等）。
     */
    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: EnhanceConfig,
        bandHeight: Int = 0,
        onStage: ((EnhanceStage, Float) -> Unit)? = null,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(pixels.size >= width * height) {
            "pixel buffer too small: ${pixels.size} < ${width * height}"
        }
        val clamped = config.clampWithReport().first
        val stages = clamped.stages()
        if (stages.isEmpty()) {
            onChunk?.invoke()
            return pixels.copyOf()
        }

        var current = pixels
        stages.forEachIndexed { index, stage ->
            onStage?.invoke(stage, index.toFloat() / stages.size)
            current = when (stage) {
                EnhanceStage.DENOISE -> BilateralFilterProcessor.process(
                    current, width, height, clamped.denoise, bandHeight, onChunk,
                )

                EnhanceStage.DEHAZE -> DehazeProcessor.process(
                    current, width, height, clamped.dehaze,
                ).also { onChunk?.invoke() }

                EnhanceStage.CLAHE -> ClaheProcessor.process(
                    current, width, height, clamped.clahe,
                ).also { onChunk?.invoke() }

                EnhanceStage.SHARPEN -> UnsharpMaskProcessor.process(
                    current, width, height, clamped.sharpen,
                ).also { onChunk?.invoke() }

                EnhanceStage.FUN_FILTER -> FunFilterProcessor.process(
                    current, width, height, clamped.funFilter, bandHeight, onChunk,
                )
            }
        }
        onStage?.invoke(stages.last(), 1f)
        return current
    }
}
