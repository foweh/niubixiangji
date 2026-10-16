package com.hinnka.mycamera.lut

/**
 * AI LUT 场景推荐的开放参数（aiplatform_patch.md 注入）。
 *
 * **默认值 = 既有代码里的魔法数，行为零变化**：
 *  肤色占比 > 0.12 → 人像；亮度 < 0.25 → 夜景；亮度 > 0.55 且冷暖 < -0.05 → 风光；冷暖 > 0.15 → 街景。
 */
data class LutRecommenderConfig(
    val skinFractionThreshold: Float = 0.12f,   // [0.00, 0.60]
    val nightLumaMax: Float = 0.25f,            // [0.05, 0.45]
    val landscapeLumaMin: Float = 0.55f,        // [0.30, 0.95]
    val landscapeWarmthMax: Float = -0.05f,     // [-0.40, 0.00]
    val streetWarmthMin: Float = 0.15f,         // [0.00, 0.60]
) {
    companion object {
        val DEFAULT = LutRecommenderConfig()

        const val SKIN_MIN = 0f;        const val SKIN_MAX = 0.60f
        const val NIGHT_MIN = 0.05f;    const val NIGHT_MAX = 0.45f
        const val LAND_LUMA_MIN = 0.30f; const val LAND_LUMA_MAX = 0.95f
        const val LAND_WARM_MIN = -0.40f; const val LAND_WARM_MAX = 0.00f
        const val STREET_MIN = 0f;      const val STREET_MAX = 0.60f

        /** 越界收敛到安全范围（误设不崩溃）。 */
        fun clamp(c: LutRecommenderConfig) = c.copy(
            skinFractionThreshold = c.skinFractionThreshold.coerceIn(SKIN_MIN, SKIN_MAX),
            nightLumaMax = c.nightLumaMax.coerceIn(NIGHT_MIN, NIGHT_MAX),
            landscapeLumaMin = c.landscapeLumaMin.coerceIn(LAND_LUMA_MIN, LAND_LUMA_MAX),
            landscapeWarmthMax = c.landscapeWarmthMax.coerceIn(LAND_WARM_MIN, LAND_WARM_MAX),
            streetWarmthMin = c.streetWarmthMin.coerceIn(STREET_MIN, STREET_MAX),
        )
    }
}
