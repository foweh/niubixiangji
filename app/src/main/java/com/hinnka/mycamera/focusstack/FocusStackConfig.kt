package com.hinnka.mycamera.focusstack

import kotlin.math.roundToInt

/**
 * 清晰度度量方式（逐帧清晰度图的算法选择）。
 *
 * - [LAPLACIAN_VARIANCE]：拉普拉斯响应的**局部方差**。对细节最敏感，微距花纹/绒毛/货币凹印
 *   这类高频纹理表现最好；缺点是低照噪点也会被计入（用 [FocusStackConfig.clarityRadius] 平滑抑制）。
 * - [GRADIENT_ENERGY]：Sobel 梯度能量（Tenengrad 变体的局部均值）。噪声鲁棒性略好，
 *   对强边缘为主的样本（叶片边缘、电路板走线）更稳。
 *
 * 两者都输出「逐像素清晰度图」，后续归一化 + 高斯平滑 + 软掩模逐帧加权。
 * 算法思路来源：PetteriAimonen/focus-stack（MIT，Star≈670，C++）的细节能量度量与小波细节融合，
 * 本工程按其思路实现拉普拉斯方差 / 梯度能量两种可切换度量（见 changelog 的复用标注）。
 */
enum class ClarityMetric {
    LAPLACIAN_VARIANCE,
    GRADIENT_ENERGY,
}

/**
 * 工作分辨率档位（掩模/清晰度图的计算分辨率；融合输出分辨率另由
 * [FocusStackConfig.outputFullResolution] 决定）。
 *
 * 内存不足时由 [FocusStackConfig.effectiveWorkingScale] + `BgJob.guardMemory` 自动向下一档降级，
 * **不限制用户帧数**（PATTERN_SPEC §1.5 / §4）。
 */
enum class FocusWorkingResolution(val scale: Float, val label: String) {
    FULL(1.0f, "全分辨率"),
    HALF(0.5f, "1/2"),
    QUARTER(0.25f, "1/4"),
    EIGHTH(0.125f, "1/8"),
    SIXTEENTH(0.0625f, "1/16"),
    ;

    companion object {
        /** 按最近档位解析（用于 DataStore 脏值 / 字符串档位名）。 */
        fun fromLabelOrScale(value: String?): FocusWorkingResolution {
            val text = value?.trim().orEmpty()
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) }?.let { return it }
            val numeric = text.toFloatOrNull()
            if (numeric != null && numeric.isFinite() && numeric > 0f) {
                return entries.minByOrNull { kotlin.math.abs(it.scale - numeric) } ?: FULL
            }
            return FULL
        }
    }
}

/**
 * 微距焦点堆叠（Focus Stacking）配置 —— 全参数开放 + 默认值 + 范围 + clamp。
 *
 * 开放度约定（PATTERN_SPEC §1.6 / §5）：**每一个**可调参数都有默认值、取值范围与越界 clamp，
 * 越界 / NaN / Inf 一律被 [clampWithReport] 修正并记录，调用方必须使用 clamp 后的配置。
 * 帧数不做总量硬上限（§4）：[frameCount] 的 clamp 上界是 [FRAME_ABSOLUTE_MAX]（仅防溢出/误设），
 * 真正的资源治理交给「工作分辨率降级 + 分片 + guardMemory」。
 *
 * 读取路径：DataStore（`user_preferences`）以 `focusstack_` 前缀存值，经 [fromPreferences] 解析；
 * 键清单见补丁 `delivery/patches/focusstack_patch.md` 的 CONFIG 节。
 * UI 挂点：`focusstack/FocusStackSettingsCatalog.kt`（Pro 设置页，模块 id `macrostack`）。
 *
 * @property enabled 模块总开关。
 * @property frameCount 参与融合的帧数（= 捕获帧数）。默认 6，范围 [2, 4096]。
 * @property stepCount 对焦距离步进数（可为不同步进段数，默认与 [frameCount] 一致）。范围 [2, 4096]。
 * @property autoNearToFar true = 按「近 → 远」自动铺满整段焦点范围；false = 使用
 *   [focusStartDiopters] / [focusEndDiopters] 指定的起止。
 * @property focusStartDiopters 起始对焦距离（屈光度，0 = 无穷远）。范围 [0, 40]。
 * @property focusEndDiopters 结束对焦距离（屈光度）。**负值表示「自动」**＝镜头最小对焦距离。
 * @property clarityMetric 清晰度度量方式。
 * @property clarityRadius 清晰度统计邻域半径（像素），越大越抗噪但越糊。范围 [1, 32]。
 * @property maskSmoothRadius 软掩模高斯平滑半径（像素），抑制掩模噪点与融合接缝。范围 [0, 64]。
 * @property maskConsistency 邻域一致性滤波级别（0 = 关，1 = 1 轮，2 = 2 轮）。范围 [0, 2]。
 * @property fusionStrength 融合强度（对基准帧的混合比）。范围 [0, 1]；1 = 完全用融合结果。
 * @property alignEnabled 是否做平移对齐（微距静态题材默认关；手持/风动时开）。
 * @property alignMaxShiftPx 对齐最大平移量（像素）。范围 [0, 512]。
 * @property alignMaxLongEdge 对齐使用的工作分辨率长边（0 = 全分辨率）。范围 [0, 8192]。
 * @property clarityExponent 掩模权重指数（越大越「赢家通吃」）。范围 [1, 16]。
 * @property workingResolution 清晰度/掩模计算的工作分辨率档位。
 * @property outputFullResolution true = 融合输出全分辨率；false = 输出工作分辨率（更快）。
 * @property jpegQuality 输出 JPEG 质量。范围 [60, 100]。
 */
data class FocusStackConfig(
    val enabled: Boolean = true,
    val frameCount: Int = DEFAULT_FRAME_COUNT,
    val stepCount: Int = DEFAULT_STEP_COUNT,
    val autoNearToFar: Boolean = true,
    val focusStartDiopters: Float = 0f,
    val focusEndDiopters: Float = AUTO_FOCUS_DIOPTERS,
    val clarityMetric: ClarityMetric = ClarityMetric.LAPLACIAN_VARIANCE,
    val clarityRadius: Int = DEFAULT_CLARITY_RADIUS,
    val maskSmoothRadius: Int = DEFAULT_MASK_SMOOTH_RADIUS,
    val maskConsistency: Int = DEFAULT_MASK_CONSISTENCY,
    val fusionStrength: Float = DEFAULT_FUSION_STRENGTH,
    val alignEnabled: Boolean = DEFAULT_ALIGN_ENABLED,
    val alignMaxShiftPx: Int = DEFAULT_ALIGN_MAX_SHIFT_PX,
    val alignMaxLongEdge: Int = DEFAULT_ALIGN_MAX_LONG_EDGE,
    val clarityExponent: Float = DEFAULT_CLARITY_EXPONENT,
    val workingResolution: FocusWorkingResolution = FocusWorkingResolution.FULL,
    val outputFullResolution: Boolean = true,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    /** 有效工作缩放（档位 scale 直接 clamp 到 [WORKING_SCALE_MIN, 1]）。 */
    fun effectiveWorkingScale(): Float =
        if (workingResolution.scale.isFinite()) {
            workingResolution.scale.coerceIn(WORKING_SCALE_MIN, WORKING_SCALE_MAX)
        } else {
            WORKING_SCALE_MAX
        }

    /** 实际生效的步进数：默认与帧数一致（步进数与帧数是同一数量级的两套参数）。 */
    fun effectiveStepCount(): Int = stepCount.coerceAtLeast(FRAME_MIN)

    /**
     * 全量 clamp：返回修正后的配置 + 被修正项的描述列表（供 PLog 记录）。
     *
     * 覆盖**全部**开放参数（自检清单要求 100% 覆盖）；非有限值先回退默认再 clamp。
     */
    fun clampWithReport(): Pair<FocusStackConfig, List<String>> {
        val reports = mutableListOf<String>()
        fun note(name: String, before: Any?, after: Any?) {
            if (before != after) reports += "$name: $before -> $after"
        }

        val frames = frameCount.coerceIn(FRAME_MIN, FRAME_ABSOLUTE_MAX)
        note("frameCount", frameCount, frames)

        val steps = stepCount.coerceIn(FRAME_MIN, FRAME_ABSOLUTE_MAX)
        note("stepCount", stepCount, steps)

        val start = fsFinite(focusStartDiopters, 0f).coerceIn(FOCUS_DIOPTERS_MIN, FOCUS_DIOPTERS_MAX)
        note("focusStartDiopters", focusStartDiopters, start)

        // 负值 = 自动（保持负值语义，不 clamp 成 0）；非有限值同样回退「自动」；
        // 其余非负值收敛到合法区间。
        val end = when {
            !focusEndDiopters.isFinite() -> AUTO_FOCUS_DIOPTERS
            focusEndDiopters < 0f -> AUTO_FOCUS_DIOPTERS
            else -> focusEndDiopters.coerceIn(FOCUS_DIOPTERS_MIN, FOCUS_DIOPTERS_MAX)
        }
        note("focusEndDiopters", focusEndDiopters, end)

        val radius = clarityRadius.coerceIn(CLARITY_RADIUS_MIN, CLARITY_RADIUS_MAX)
        note("clarityRadius", clarityRadius, radius)

        val smooth = maskSmoothRadius.coerceIn(MASK_SMOOTH_RADIUS_MIN, MASK_SMOOTH_RADIUS_MAX)
        note("maskSmoothRadius", maskSmoothRadius, smooth)

        val consistency = maskConsistency.coerceIn(MASK_CONSISTENCY_MIN, MASK_CONSISTENCY_MAX)
        note("maskConsistency", maskConsistency, consistency)

        val strength = fsFinite(fusionStrength, DEFAULT_FUSION_STRENGTH).coerceIn(STRENGTH_MIN, STRENGTH_MAX)
        note("fusionStrength", fusionStrength, strength)

        val shift = alignMaxShiftPx.coerceIn(ALIGN_MAX_SHIFT_MIN, ALIGN_MAX_SHIFT_MAX)
        note("alignMaxShiftPx", alignMaxShiftPx, shift)

        val alignEdge = alignMaxLongEdge.coerceIn(ALIGN_MAX_LONG_EDGE_MIN, ALIGN_MAX_LONG_EDGE_MAX)
        note("alignMaxLongEdge", alignMaxLongEdge, alignEdge)

        val exponent = fsFinite(clarityExponent, DEFAULT_CLARITY_EXPONENT)
            .coerceIn(CLARITY_EXPONENT_MIN, CLARITY_EXPONENT_MAX)
        note("clarityExponent", clarityExponent, exponent)

        val quality = jpegQuality.coerceIn(JPEG_QUALITY_MIN, JPEG_QUALITY_MAX)
        note("jpegQuality", jpegQuality, quality)

        val resolution = workingResolution.takeIf { it.scale.isFinite() } ?: FocusWorkingResolution.FULL
        if (resolution != workingResolution) reports += "workingResolution: $workingResolution -> $resolution"

        return FocusStackConfig(
            enabled = enabled,
            frameCount = frames,
            stepCount = steps,
            autoNearToFar = autoNearToFar,
            focusStartDiopters = start,
            focusEndDiopters = end,
            clarityMetric = clarityMetric,
            clarityRadius = radius,
            maskSmoothRadius = smooth,
            maskConsistency = consistency,
            fusionStrength = strength,
            alignEnabled = alignEnabled,
            alignMaxShiftPx = shift,
            alignMaxLongEdge = alignEdge,
            clarityExponent = exponent,
            workingResolution = resolution,
            outputFullResolution = outputFullResolution,
            jpegQuality = quality,
        ) to reports
    }

    companion object {
        // ----------------------------------------------------------- 范围常量
        /** 帧数下限（2 帧才构成「不同焦点」的融合）。 */
        const val FRAME_MIN = 2

        /** 帧数 UI 推荐上限（滑杆刻度顶值；**不是**算法总量上限）。 */
        const val FRAME_RECOMMENDED_MAX = 200

        /** 帧数绝对安全上限：仅防误设/溢出，资源问题交给工作分辨率降级 + 分片。 */
        const val FRAME_ABSOLUTE_MAX = 4096

        const val FOCUS_DIOPTERS_MIN = 0f
        const val FOCUS_DIOPTERS_MAX = 40f

        /** 对焦距离「自动」哨兵：负值表示由镜头最小对焦距离决定。 */
        const val AUTO_FOCUS_DIOPTERS = -1f

        const val CLARITY_RADIUS_MIN = 1
        const val CLARITY_RADIUS_MAX = 32
        const val MASK_SMOOTH_RADIUS_MIN = 0
        const val MASK_SMOOTH_RADIUS_MAX = 64
        const val MASK_CONSISTENCY_MIN = 0
        const val MASK_CONSISTENCY_MAX = 2
        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 1f
        const val ALIGN_MAX_SHIFT_MIN = 0
        const val ALIGN_MAX_SHIFT_MAX = 512
        const val ALIGN_MAX_LONG_EDGE_MIN = 0
        const val ALIGN_MAX_LONG_EDGE_MAX = 8192
        const val CLARITY_EXPONENT_MIN = 1f
        const val CLARITY_EXPONENT_MAX = 16f
        const val JPEG_QUALITY_MIN = 60
        const val JPEG_QUALITY_MAX = 100

        const val WORKING_SCALE_MIN = 0.0625f
        const val WORKING_SCALE_MAX = 1f

        // ----------------------------------------------------------- 默认值
        const val DEFAULT_FRAME_COUNT = 6
        const val DEFAULT_STEP_COUNT = 6
        const val DEFAULT_CLARITY_RADIUS = 2
        const val DEFAULT_MASK_SMOOTH_RADIUS = 4
        const val DEFAULT_MASK_CONSISTENCY = 0
        const val DEFAULT_FUSION_STRENGTH = 1f

        /**
         * 默认不做平移对齐：微距堆叠的典型工作流是「机身/被摄物固定、只推进会焦点」，
         * 与 focus-stack 的 `--no-align` 默认思路一致；手持/有风时才打开开关。
         */
        const val DEFAULT_ALIGN_ENABLED = false
        const val DEFAULT_ALIGN_MAX_SHIFT_PX = 24

        /** 对齐工作分辨率长边默认 2048（复用 focus-stack `--full-resolution-align` 的默认上限）。 */
        const val DEFAULT_ALIGN_MAX_LONG_EDGE = 2048
        const val DEFAULT_CLARITY_EXPONENT = 4f
        const val DEFAULT_JPEG_QUALITY = 95

        val DEFAULT = FocusStackConfig()

        // ----------------------------------------------------------- DataStore key
        const val KEY_ENABLED = "focusstack_enabled"
        const val KEY_FRAME_COUNT = "focusstack_frame_count"
        const val KEY_STEP_COUNT = "focusstack_step_count"
        const val KEY_AUTO_NEAR_TO_FAR = "focusstack_auto_near_to_far"
        const val KEY_FOCUS_START_DIOPTERS = "focusstack_focus_start_diopters"
        const val KEY_FOCUS_END_DIOPTERS = "focusstack_focus_end_diopters"
        const val KEY_CLARITY_METRIC = "focusstack_clarity_metric"
        const val KEY_CLARITY_RADIUS = "focusstack_clarity_radius"
        const val KEY_MASK_SMOOTH_RADIUS = "focusstack_mask_smooth_radius"
        const val KEY_MASK_CONSISTENCY = "focusstack_mask_consistency"
        const val KEY_FUSION_STRENGTH = "focusstack_fusion_strength"
        const val KEY_ALIGN_ENABLED = "focusstack_align_enabled"
        const val KEY_ALIGN_MAX_SHIFT_PX = "focusstack_align_max_shift_px"
        const val KEY_ALIGN_MAX_LONG_EDGE = "focusstack_align_max_long_edge"
        const val KEY_CLARITY_EXPONENT = "focusstack_clarity_exponent"
        const val KEY_WORKING_RESOLUTION = "focusstack_working_resolution"
        const val KEY_OUTPUT_FULL_RESOLUTION = "focusstack_output_full_resolution"
        const val KEY_JPEG_QUALITY = "focusstack_jpeg_quality"

        /**
         * 从「key → 值」映射构建配置（DataStore 读取后由集成层转换；也用于后台任务参数）。
         * 缺失的 key 用默认值；类型不符或非有限值直接忽略（回默认），**不抛异常**。
         */
        fun fromPreferences(values: Map<String, Any?>): FocusStackConfig {
            val base = DEFAULT
            return FocusStackConfig(
                enabled = FocusPrefs.bool(values, KEY_ENABLED, base.enabled),
                frameCount = FocusPrefs.int(values, KEY_FRAME_COUNT, base.frameCount),
                stepCount = FocusPrefs.int(values, KEY_STEP_COUNT, base.stepCount),
                autoNearToFar = FocusPrefs.bool(values, KEY_AUTO_NEAR_TO_FAR, base.autoNearToFar),
                focusStartDiopters = FocusPrefs.float(
                    values, KEY_FOCUS_START_DIOPTERS, base.focusStartDiopters,
                ),
                focusEndDiopters = FocusPrefs.float(
                    values, KEY_FOCUS_END_DIOPTERS, base.focusEndDiopters,
                ),
                clarityMetric = FocusPrefs.enumOf(values, KEY_CLARITY_METRIC, base.clarityMetric),
                clarityRadius = FocusPrefs.int(values, KEY_CLARITY_RADIUS, base.clarityRadius),
                maskSmoothRadius = FocusPrefs.int(
                    values, KEY_MASK_SMOOTH_RADIUS, base.maskSmoothRadius,
                ),
                maskConsistency = FocusPrefs.int(values, KEY_MASK_CONSISTENCY, base.maskConsistency),
                fusionStrength = FocusPrefs.float(values, KEY_FUSION_STRENGTH, base.fusionStrength),
                alignEnabled = FocusPrefs.bool(values, KEY_ALIGN_ENABLED, base.alignEnabled),
                alignMaxShiftPx = FocusPrefs.int(
                    values, KEY_ALIGN_MAX_SHIFT_PX, base.alignMaxShiftPx,
                ),
                alignMaxLongEdge = FocusPrefs.int(
                    values, KEY_ALIGN_MAX_LONG_EDGE, base.alignMaxLongEdge,
                ),
                clarityExponent = FocusPrefs.float(values, KEY_CLARITY_EXPONENT, base.clarityExponent),
                workingResolution = FocusWorkingResolution.fromLabelOrScale(
                    FocusPrefs.string(values, KEY_WORKING_RESOLUTION, base.workingResolution.name),
                ),
                outputFullResolution = FocusPrefs.bool(
                    values, KEY_OUTPUT_FULL_RESOLUTION, base.outputFullResolution,
                ),
                jpegQuality = FocusPrefs.int(values, KEY_JPEG_QUALITY, base.jpegQuality),
            ).let { it.clampWithReport().first }
        }
    }
}

/** 把非有限值替换为回退值（NaN 的 coerceIn 结果仍是 NaN，必须先过滤）。 */
internal fun fsFinite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

/**
 * DataStore 值解析工具：类型不符 / 非有限值一律回退默认值，绝不抛异常。
 * 纯 Kotlin（无 Android 依赖），可直接单测。
 */
internal object FocusPrefs {

    fun float(values: Map<String, Any?>, key: String, fallback: Float): Float {
        val raw = values[key] ?: return fallback
        val parsed = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            is Boolean -> if (raw) 1f else 0f
            else -> null
        } ?: return fallback
        return if (parsed.isFinite()) parsed else fallback
    }

    fun int(values: Map<String, Any?>, key: String, fallback: Int): Int {
        val raw = values[key] ?: return fallback
        val parsed = when (raw) {
            is Number -> raw.toFloat().takeIf { it.isFinite() }?.roundToInt()
            is String -> raw.toFloatOrNull()?.takeIf { it.isFinite() }?.roundToInt()
            is Boolean -> if (raw) 1 else 0
            else -> null
        }
        return parsed ?: fallback
    }

    fun bool(values: Map<String, Any?>, key: String, fallback: Boolean): Boolean {
        val raw = values[key] ?: return fallback
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toFloat().takeIf { it.isFinite() }?.let { it != 0f } ?: fallback
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> fallback
            }
            else -> fallback
        }
    }

    fun string(values: Map<String, Any?>, key: String, fallback: String): String {
        val raw = values[key] ?: return fallback
        return when (raw) {
            is String -> raw.trim().ifEmpty { fallback }
            is Number -> raw.toString()
            else -> fallback
        }
    }

    /** 枚举按 name 解析（大小写不敏感）；无法解析回退默认，避免旧数据导致崩溃。 */
    inline fun <reified T : Enum<T>> enumOf(values: Map<String, Any?>, key: String, fallback: T): T {
        val raw = values[key] as? String ?: return fallback
        return enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback
    }
}
