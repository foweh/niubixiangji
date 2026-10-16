package com.hinnka.mycamera.scenerecog

/**
 * 场景识别配置（开放度：全部可调参数有默认值 + 取值范围 + clamp）。
 *
 * 取值范围（[clamp] 保障）：
 *  - portraitSkinFraction ∈ [0.01, 0.60]
 *  - textScoreThreshold ∈ [0.10, 0.90]
 *  - nightMaxLuma ∈ [0.05, 0.50]
 *  - landscapeMinLuma ∈ [0.30, 0.95]
 *  - landscapeMinSaturation ∈ [0.0, 0.8]
 *  - landscapeMaxWarmth ∈ [-0.6, 0.6]
 *  - streetMinWarmth ∈ [0.0, 0.6]
 *  - streetMinEdgeDensity ∈ [0.02, 0.80]
 *  - sampleStride ∈ [1, 64]
 *
 * 默认阈值与既有 `lut/LutRecommender.classifyScene` 的阈值口径保持一致
 * （skin 0.12 / night 0.25 / landscape 0.55 与 -0.05 / street 0.15），
 * 因此「场景识别」打开了也不会与既有 AI LUT 推荐产生相反结论。
 */
data class SceneRecognitionConfig(
    val enabled: Boolean = false,
    val portraitSkinFraction: Float = DEFAULT_PORTRAIT_SKIN_FRACTION,
    val textScoreThreshold: Float = DEFAULT_TEXT_SCORE_THRESHOLD,
    val nightMaxLuma: Float = DEFAULT_NIGHT_MAX_LUMA,
    val landscapeMinLuma: Float = DEFAULT_LANDSCAPE_MIN_LUMA,
    val landscapeMinSaturation: Float = DEFAULT_LANDSCAPE_MIN_SATURATION,
    val landscapeMaxWarmth: Float = DEFAULT_LANDSCAPE_MAX_WARMTH,
    val streetMinWarmth: Float = DEFAULT_STREET_MIN_WARMTH,
    val streetMinEdgeDensity: Float = DEFAULT_STREET_MIN_EDGE_DENSITY,
    val sampleStride: Int = DEFAULT_SAMPLE_STRIDE,
    /** 是否优先走远端 API（端点未配置时自动回落端侧规则分类）。 */
    val useRemoteApi: Boolean = false,
) {
    companion object {
        const val SKIN_MIN = 0.01f
        const val SKIN_MAX = 0.60f
        const val TEXT_MIN = 0.10f
        const val TEXT_MAX = 0.90f
        const val NIGHT_MIN = 0.05f
        const val NIGHT_MAX = 0.50f
        const val LANDSCAPE_LUMA_MIN = 0.30f
        const val LANDSCAPE_LUMA_MAX = 0.95f
        const val SATURATION_MIN = 0.0f
        const val SATURATION_MAX = 0.80f
        const val WARMTH_MIN = -0.60f
        const val WARMTH_MAX = 0.60f
        const val EDGE_MIN = 0.02f
        const val EDGE_MAX = 0.80f
        const val SAMPLE_STRIDE_MIN = 1
        const val SAMPLE_STRIDE_MAX = 64

        const val DEFAULT_PORTRAIT_SKIN_FRACTION = 0.12f
        const val DEFAULT_TEXT_SCORE_THRESHOLD = 0.45f
        const val DEFAULT_NIGHT_MAX_LUMA = 0.25f
        const val DEFAULT_LANDSCAPE_MIN_LUMA = 0.55f
        const val DEFAULT_LANDSCAPE_MIN_SATURATION = 0.18f
        const val DEFAULT_LANDSCAPE_MAX_WARMTH = -0.05f
        const val DEFAULT_STREET_MIN_WARMTH = 0.15f
        const val DEFAULT_STREET_MIN_EDGE_DENSITY = 0.22f
        const val DEFAULT_SAMPLE_STRIDE = 4

        /** 人像判定的亮度有效区间（过暗/过曝时肤色统计不可信）。 */
        const val PORTRAIT_MIN_LUMA = 0.15f
        const val PORTRAIT_MAX_LUMA = 0.85f

        /** 街头判定的亮度有效区间。 */
        const val STREET_MIN_LUMA = 0.20f
        const val STREET_MAX_LUMA = 0.75f

        val DEFAULT = SceneRecognitionConfig()

        fun clamp(config: SceneRecognitionConfig): SceneRecognitionConfig = clampWithReport(config).config

        /** clamp + 被修正项说明（调用方自行 log）。 */
        fun clampWithReport(config: SceneRecognitionConfig): SceneRecognitionClamp {
            val reports = ArrayList<String>(10)

            fun range(value: Float, min: Float, max: Float, name: String): Float {
                val coerced = value.coerceIn(min, max)
                if (coerced != value) reports += "$name $value->$coerced"
                return coerced
            }

            val portraitSkin = range(config.portraitSkinFraction, SKIN_MIN, SKIN_MAX, "portraitSkinFraction")
            val textScore = range(config.textScoreThreshold, TEXT_MIN, TEXT_MAX, "textScoreThreshold")
            val nightLuma = range(config.nightMaxLuma, NIGHT_MIN, NIGHT_MAX, "nightMaxLuma")
            val landscapeLuma = range(config.landscapeMinLuma, LANDSCAPE_LUMA_MIN, LANDSCAPE_LUMA_MAX, "landscapeMinLuma")
            val landscapeSat = range(config.landscapeMinSaturation, SATURATION_MIN, SATURATION_MAX, "landscapeMinSaturation")
            val landscapeWarmth = range(config.landscapeMaxWarmth, WARMTH_MIN, WARMTH_MAX, "landscapeMaxWarmth")
            val streetWarmth = range(config.streetMinWarmth, WARMTH_MIN, WARMTH_MAX, "streetMinWarmth")
            val streetEdge = range(config.streetMinEdgeDensity, EDGE_MIN, EDGE_MAX, "streetMinEdgeDensity")
            val stride = config.sampleStride.coerceIn(SAMPLE_STRIDE_MIN, SAMPLE_STRIDE_MAX)
            if (stride != config.sampleStride) reports += "sampleStride ${config.sampleStride}->$stride"

            return SceneRecognitionClamp(
                config = config.copy(
                    portraitSkinFraction = portraitSkin,
                    textScoreThreshold = textScore,
                    nightMaxLuma = nightLuma,
                    landscapeMinLuma = landscapeLuma,
                    landscapeMinSaturation = landscapeSat,
                    landscapeMaxWarmth = landscapeWarmth,
                    streetMinWarmth = streetWarmth,
                    streetMinEdgeDensity = streetEdge,
                    sampleStride = stride,
                ),
                reports = reports,
            )
        }
    }
}

/** clamp 结果：被修正项说明。 */
data class SceneRecognitionClamp(
    val config: SceneRecognitionConfig,
    val reports: List<String>,
)
