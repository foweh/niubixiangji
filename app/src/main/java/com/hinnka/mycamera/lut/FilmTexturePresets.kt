package com.hinnka.mycamera.lut

/**
 * LUT → 胶片质感包自动映射（用户实测反馈：让"胶片感"明显，而不是只偏色温）。
 *
 * 规则：
 *  - Film 类（负片/胶片模拟）  → 颗粒 + 轻暗角 + 高光扩散(柔光)，对比略增；
 *  - 黑白（B&W / Ilford / monochrome）→ 更高反差颗粒、无柔光染色；
 *  - Log 还原类               → 低颗粒、无质感（还原优先，保干净）；
 *  - 自定义导入(.cube/.plut)  → 默认"胶片感"包（中等颗粒 + 轻暗角 + 柔光）；
 *  - 其它                     → 关闭。
 *
 * 质感层强度随 [lutIntensity] 联动：LUT 拉强时质感同步增强，LUT 归 0 时质感归零。
 * 默认仍"薄"，不糊细节（原则 A）。
 */
object FilmTexturePresets {

    /**
     * @param category   LUT 分类（config.json 的 category，如 "Film"/"Log"）
     * @param lutId      LUT id，用于识别黑白
     * @param isCustom   是否用户自定义导入
     * @param lutIntensity LUT 全映射强度（0..2），质感按此比例联动
     */
    fun forLut(
        category: String?,
        lutId: String,
        isCustom: Boolean,
        lutIntensity: Float,
    ): FilmTextureParams {
        val k = lutIntensity.coerceIn(0f, 2f) / 1f   // 以 1.0 为基准比例
        val base = when {
            isBlackAndWhite(lutId) -> FilmTextureParams(
                grainAmount = 0.22f, grainSize = 1.0f,
                vignette = 0.18f, lightLeak = 0f, lightLeakWarm = 0f,
                softGlow = 0.0f, sharpen = 0.12f,
            )
            category.equals("Log", ignoreCase = true) -> FilmTextureParams(
                grainAmount = 0.02f, grainSize = 0.9f,
                vignette = 0f, lightLeak = 0f, lightLeakWarm = 0f,
                softGlow = 0f, sharpen = 0.08f,
            )
            category.equals("Film", ignoreCase = true) -> FilmTextureParams(
                grainAmount = 0.14f, grainSize = 0.9f,
                vignette = 0.14f, lightLeak = 0f, lightLeakWarm = 0f,
                softGlow = 0.10f, sharpen = 0.10f,
            )
            isCustom -> FilmTextureParams(
                grainAmount = 0.12f, grainSize = 0.9f,
                vignette = 0.12f, lightLeak = 0f, lightLeakWarm = 0f,
                softGlow = 0.08f, sharpen = 0.10f,
            )
            else -> FilmTextureParams.NONE
        }
        // 强度随 lutIntensity 联动（保持薄）：k<=1 按比例衰减，k>1 轻微增强封顶
        val scale = when {
            k <= 1f -> k
            else -> 1f + (k - 1f) * 0.4f
        }.coerceIn(0f, 1.3f)
        return base.copy(
            grainAmount = (base.grainAmount * scale).coerceIn(0f, 0.5f),
            vignette = (base.vignette * scale).coerceIn(0f, 0.5f),
            softGlow = (base.softGlow * scale).coerceIn(0f, 0.3f),
        )
    }

    private fun isBlackAndWhite(lutId: String): Boolean {
        val id = lutId.lowercase()
        return id.contains("mono") || id.contains("bw") || id.contains("black") ||
            id.contains("ilford") || id.contains("hp5") || id.contains("delta") ||
            id.contains("tri_x") || id.contains("noir")
    }
}
