package com.hinnka.mycamera.logvideo

/**
 * Log 曲线配方（Log 视频 20 项 · 第 1 项「曲线参数黑电平/峰值/斜率可配」）。
 *
 * 参数定义（全部为**开放可调**参数，默认值 = 各档位的预设原生行为，零回归）：
 *  - [blackLevel]：**代码域加性黑电平**，单位 = Log 代码值。0 = 保持预设原生黑位；
 *    正值抬黑（灰片更"奶"，暗部细节更多），负值压黑。
 *  - [peak]：**场景白点**（输入归一化分母）。曲线输入取 `x / peak`；
 *    1.0 = 预设默认（场景白点即线性 1.0）；<1 让高光更早进入 Log 肩部，>1 保留更多高光余量。
 *  - [slope]：**代码域斜率**（乘性，绕黑电平缩放）。1.0 = 预设原生对比；>1 拉开 Log 反差，
 *    <1 压平（更利于后期）。
 *  - [tableSize]：查表分辨率（越高中灰过渡越平滑，成本线性增长）。
 *
 * 数学定义（**可被单测逐点验证**）：
 * ```
 * encode(x) = clamp01( preset.encodeCode(clamp01(x / peak)) * slope + blackLevel )
 * decode(c) = clamp01( preset.decodeLinear(clamp01((c - blackLevel) / slope)) * peak )
 * ```
 * 即：默认配方 `blackLevel=0, peak=1, slope=1` 时 `encode ≡ preset.encodeCode`，
 * 与工程既有 Log 视频链路（shader 内 `applyLutCurve`）行为完全一致。
 *
 * 越界一律 clamp（PATTERN_SPEC §1.6），clamp 后被修正的项经 [clampReport] 上报，
 * 由调用方 `PLog.w` 记录；[encode]/[decode] 自身再兜底 clamp，保证任何脏值都不会崩溃。
 */
data class LogCurveRecipe(
    val preset: LogCurvePreset = LogCurvePreset.LOG_C3,
    val blackLevel: Float = DEFAULT_BLACK_LEVEL,
    val peak: Float = DEFAULT_PEAK,
    val slope: Float = DEFAULT_SLOPE,
    val tableSize: Int = DEFAULT_TABLE_SIZE,
) {
    /** 场景线性 → Log 代码（含 peak 归一化、slope/黑电平微调）。 */
    fun encode(sceneLinear: Float): Float {
        val normalized = (sceneLinear / peak.coerceIn(PEAK_MIN, PEAK_MAX)).coerceIn(0f, 1f)
        val code = preset.encodeCode(normalized) * slope + blackLevel
        return code.coerceIn(0f, 1f)
    }

    /** Log 代码 → 场景线性（[encode] 的解码半边，Log→Rec709 烘焙使用）。 */
    fun decode(logCode: Float): Float {
        val shifted = (logCode - blackLevel) / slope.coerceIn(SLOPE_MIN, SLOPE_MAX)
        val linear = preset.decodeLinear(shifted.coerceIn(0f, 1f)) *
            peak.coerceIn(PEAK_MIN, PEAK_MAX)
        return linear.coerceIn(0f, 1f)
    }

    /** 是否为本档位的原生默认配方（用于跳过重烘焙：true 时可直接用预置 LUT）。 */
    fun isPresetNative(): Boolean =
        blackLevel == DEFAULT_BLACK_LEVEL && peak == DEFAULT_PEAK &&
            slope == DEFAULT_SLOPE

    /** 一句话描述（日志 / SelfCheck / .cube 注释共用）。 */
    fun describe(): String =
        "recipe(preset=${preset.name}, gamut=${preset.gamut}, black=$blackLevel, " +
            "peak=$peak, slope=$slope, table=$tableSize)"

    /** clamp 到安全范围（不抛异常、不崩溃）。 */
    fun clamped(): LogCurveRecipe = LogCurveRecipe(
        preset = preset,
        blackLevel = blackLevel.coerceIn(BLACK_LEVEL_MIN, BLACK_LEVEL_MAX),
        peak = if (peak.isFinite()) peak.coerceIn(PEAK_MIN, PEAK_MAX) else DEFAULT_PEAK,
        slope = if (slope.isFinite()) slope.coerceIn(SLOPE_MIN, SLOPE_MAX) else DEFAULT_SLOPE,
        tableSize = tableSize.coerceIn(TABLE_SIZE_MIN, TABLE_SIZE_MAX),
    )

    /**
     * 被 clamp 修正的字段清单（供调用方 `PLog.w` 记录）；
     * 未被修正时返回空列表（正常路径零日志噪音）。
     */
    fun clampReport(): List<String> = buildList {
        if (!blackLevel.isFinite() || blackLevel !in BLACK_LEVEL_MIN..BLACK_LEVEL_MAX) {
            add("blackLevel=$blackLevel → ${blackLevel.takeIf { it.isFinite() }?.coerceIn(BLACK_LEVEL_MIN, BLACK_LEVEL_MAX) ?: DEFAULT_BLACK_LEVEL}")
        }
        if (!peak.isFinite() || peak !in PEAK_MIN..PEAK_MAX) {
            add("peak=$peak → ${peak.takeIf { it.isFinite() }?.coerceIn(PEAK_MIN, PEAK_MAX) ?: DEFAULT_PEAK}")
        }
        if (!slope.isFinite() || slope !in SLOPE_MIN..SLOPE_MAX) {
            add("slope=$slope → ${slope.takeIf { it.isFinite() }?.coerceIn(SLOPE_MIN, SLOPE_MAX) ?: DEFAULT_SLOPE}")
        }
        if (tableSize !in TABLE_SIZE_MIN..TABLE_SIZE_MAX) {
            add("tableSize=$tableSize → ${tableSize.coerceIn(TABLE_SIZE_MIN, TABLE_SIZE_MAX)}")
        }
    }

    companion object {
        /** 黑电平范围（Log 代码值）：默认 0 = 预设原生黑位。 */
        const val BLACK_LEVEL_MIN = -0.25f
        const val BLACK_LEVEL_MAX = 0.25f
        const val DEFAULT_BLACK_LEVEL = 0f

        /** 场景白点范围：默认 1.0 = 预设原生（线性 1.0 = 场景白）。 */
        const val PEAK_MIN = 0.2f
        const val PEAK_MAX = 8f
        const val DEFAULT_PEAK = 1f

        /** 代码域斜率范围：默认 1.0 = 预设原生对比。 */
        const val SLOPE_MIN = 0.25f
        const val SLOPE_MAX = 4f
        const val DEFAULT_SLOPE = 1f

        /** 查表分辨率范围：64（省内存）..8192（最平滑），默认 1024。 */
        const val TABLE_SIZE_MIN = 64
        const val TABLE_SIZE_MAX = 8192
        const val DEFAULT_TABLE_SIZE = 1024

        val DEFAULT = LogCurveRecipe()

        /** 便捷构造：指定档位 + 原生默认微调。 */
        fun ofPreset(preset: LogCurvePreset): LogCurveRecipe = LogCurveRecipe(preset = preset)

        /**
         * 从配置表恢复（后台任务/DataStore 读取路径）；**读取即 clamp**，
         * 键名见 `delivery/patches/logvideo_patch.md` 的 CONFIG 段。
         */
        fun fromPreferences(values: Map<String, Any?>): LogCurveRecipe {
            val preset = LogCurvePreset.fromPersistedName(values["log_curve_preset"] as? String)
            val raw = LogCurveRecipe(
                preset = preset,
                blackLevel = (values["log_curve_black_level"] as? Number)?.toFloat()
                    ?: DEFAULT_BLACK_LEVEL,
                peak = (values["log_curve_peak"] as? Number)?.toFloat() ?: DEFAULT_PEAK,
                slope = (values["log_curve_slope"] as? Number)?.toFloat() ?: DEFAULT_SLOPE,
                tableSize = (values["log_curve_table_size"] as? Number)?.toInt()
                    ?: DEFAULT_TABLE_SIZE,
            )
            return raw.clamped()
        }
    }
}
