package com.hinnka.mycamera.logvideo

/**
 * GL 侧 Log 曲线微调三元组（Log 视频 20 项 · 第 1 项「曲线参数可配」的 GPU 通道）。
 *
 * 三个值直接对应 shader 的 `uVideoLogBlack / uVideoLogSlope / uVideoLogPeak` 三个 uniform
 * （见 `delivery/patches/logvideo_patch.md` 的 SHADER 段）：
 * ```
 * linear /= max(uVideoLogPeak, 0.05);                       // 峰值（场景白点）
 * logCode  = applyLutCurve(prepared, uVideoLogCurve);
 * color.rgb = sanitize(logCode * uVideoLogSlope + uVideoLogBlack);   // 斜率 + 黑电平
 * ```
 *
 * [IDENTITY]（0 / 1 / 1）时 shader 行为与工程现状**逐位一致**——这是「默认零回归」的关键：
 * 未调参时不需要任何分支，也就不存在画质回退风险。
 */
data class LogCurveTrim(
    val blackLevel: Float,
    val slope: Float,
    val peak: Float,
) {
    /** 是否为恒等（调用方可据此跳过 uniform 上传）。 */
    val isIdentity: Boolean
        get() = blackLevel == 0f && slope == 1f && peak == 1f

    fun clamped(): LogCurveTrim = LogCurveTrim(
        blackLevel = blackLevel.coerceIn(LogCurveRecipe.BLACK_LEVEL_MIN, LogCurveRecipe.BLACK_LEVEL_MAX),
        slope = slope.coerceIn(LogCurveRecipe.SLOPE_MIN, LogCurveRecipe.SLOPE_MAX),
        peak = peak.coerceIn(LogCurveRecipe.PEAK_MIN, LogCurveRecipe.PEAK_MAX),
    )

    companion object {
        val IDENTITY = LogCurveTrim(0f, 1f, 1f)

        /**
         * 由配方导出 GPU 微调值。
         *
         * @param enabled false（未开启 Log / 关闭 GPU 微调）时返回 [IDENTITY]，shader 走原始路径。
         */
        fun from(recipe: LogCurveRecipe, enabled: Boolean = true): LogCurveTrim {
            if (!enabled) return IDENTITY
            val safe = recipe.clamped()
            return LogCurveTrim(
                blackLevel = safe.blackLevel,
                slope = safe.slope,
                peak = safe.peak,
            )
        }

        /** 由总配置导出（含 `gpuCurveTrimEnabled` 开关与档位原生短路）。 */
        fun from(config: LogVideoConfig): LogCurveTrim =
            from(config.curve, enabled = config.appliesGpuTrim)
    }
}
