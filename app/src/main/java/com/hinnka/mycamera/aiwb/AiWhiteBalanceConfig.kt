package com.hinnka.mycamera.aiwb

/**
 * AI 白平衡配置（开放度：每个可调参数都有默认值 + 取值范围 + clamp，防误设崩溃）。
 *
 * 取值范围（[clamp] 保障）：
 *  - strength ∈ [0, 1]（0 = 不改动，1 = 完全应用估计增益）
 *  - minKelvin / maxKelvin ∈ [1500, 15000]，且保证 minKelvin ≤ maxKelvin
 *  - maxGain ∈ [1, 8]（单通道增益绝对值上限）
 *  - highlightWeight ∈ [0, 1]（0 = 纯灰世界；1 = 纯高光/白点估计）
 *  - highlightLumaThreshold ∈ [0.30, 1.0]
 *  - sampleStride ∈ [1, 64]（>1 跳采样，降 CPU 峰值）
 *
 * @param enabled 总开关；默认关（与既有 AI 功能一致的「默认关」策略）
 * @param useRemoteApi 是否优先走远端 API（端点未配置时自动回落端侧估计）
 */
data class AiWhiteBalanceConfig(
    val enabled: Boolean = false,
    val strength: Float = DEFAULT_STRENGTH,
    val minKelvin: Int = DEFAULT_MIN_KELVIN,
    val maxKelvin: Int = DEFAULT_MAX_KELVIN,
    val maxGain: Float = DEFAULT_MAX_GAIN,
    val highlightWeight: Float = DEFAULT_HIGHLIGHT_WEIGHT,
    val highlightLumaThreshold: Float = DEFAULT_HIGHLIGHT_LUMA_THRESHOLD,
    val sampleStride: Int = DEFAULT_SAMPLE_STRIDE,
    val useRemoteApi: Boolean = false,
) {

    companion object {
        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 1f
        const val KELVIN_MIN = 1_500
        const val KELVIN_MAX = 15_000
        const val MAX_GAIN_MIN = 1f
        const val MAX_GAIN_MAX = 8f
        const val HIGHLIGHT_WEIGHT_MIN = 0f
        const val HIGHLIGHT_WEIGHT_MAX = 1f
        const val HIGHLIGHT_LUMA_MIN = 0.30f
        const val HIGHLIGHT_LUMA_MAX = 1f
        const val SAMPLE_STRIDE_MIN = 1
        const val SAMPLE_STRIDE_MAX = 64

        const val DEFAULT_STRENGTH = 0.6f
        const val DEFAULT_MIN_KELVIN = 2_500
        const val DEFAULT_MAX_KELVIN = 9_000
        const val DEFAULT_MAX_GAIN = 2.0f
        const val DEFAULT_HIGHLIGHT_WEIGHT = 0.35f
        const val DEFAULT_HIGHLIGHT_LUMA_THRESHOLD = 0.85f
        const val DEFAULT_SAMPLE_STRIDE = 4

        /** 默认配置（关闭态）。 */
        val DEFAULT = AiWhiteBalanceConfig()

        fun clamp(config: AiWhiteBalanceConfig): AiWhiteBalanceConfig = clampWithReport(config).config

        /** clamp + 被修正项说明（调用方自行 log，保持纯函数可单测）。 */
        fun clampWithReport(config: AiWhiteBalanceConfig): AiWhiteBalanceClamp {
            val reports = ArrayList<String>(6)

            var minKelvin = config.minKelvin.coerceIn(KELVIN_MIN, KELVIN_MAX)
            var maxKelvin = config.maxKelvin.coerceIn(KELVIN_MIN, KELVIN_MAX)
            if (minKelvin > maxKelvin) {
                val swapped = minKelvin
                minKelvin = maxKelvin
                maxKelvin = swapped
                reports += "minKelvin/maxKelvin 顺序颠倒，已交换"
            }
            if (minKelvin != config.minKelvin) reports += "minKelvin ${config.minKelvin}->$minKelvin"
            if (maxKelvin != config.maxKelvin) reports += "maxKelvin ${config.maxKelvin}->$maxKelvin"

            val strength = config.strength.coerceIn(STRENGTH_MIN, STRENGTH_MAX)
            if (strength != config.strength) reports += "strength ${config.strength}->$strength"

            val maxGain = config.maxGain.coerceIn(MAX_GAIN_MIN, MAX_GAIN_MAX)
            if (maxGain != config.maxGain) reports += "maxGain ${config.maxGain}->$maxGain"

            val highlightWeight = config.highlightWeight.coerceIn(HIGHLIGHT_WEIGHT_MIN, HIGHLIGHT_WEIGHT_MAX)
            if (highlightWeight != config.highlightWeight) {
                reports += "highlightWeight ${config.highlightWeight}->$highlightWeight"
            }

            val highlightLuma = config.highlightLumaThreshold.coerceIn(HIGHLIGHT_LUMA_MIN, HIGHLIGHT_LUMA_MAX)
            if (highlightLuma != config.highlightLumaThreshold) {
                reports += "highlightLumaThreshold ${config.highlightLumaThreshold}->$highlightLuma"
            }

            val stride = config.sampleStride.coerceIn(SAMPLE_STRIDE_MIN, SAMPLE_STRIDE_MAX)
            if (stride != config.sampleStride) reports += "sampleStride ${config.sampleStride}->$stride"

            return AiWhiteBalanceClamp(
                config = config.copy(
                    strength = strength,
                    minKelvin = minKelvin,
                    maxKelvin = maxKelvin,
                    maxGain = maxGain,
                    highlightWeight = highlightWeight,
                    highlightLumaThreshold = highlightLuma,
                    sampleStride = stride,
                ),
                reports = reports,
            )
        }
    }
}

/** clamp 结果：被修正项说明。 */
data class AiWhiteBalanceClamp(
    val config: AiWhiteBalanceConfig,
    val reports: List<String>,
)
