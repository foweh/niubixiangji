package com.hinnka.mycamera.lenscorrection

/**
 * 采样越界策略：畸变校正会把画面边缘的采样点推到原图之外。
 */
enum class DistortionEdgePolicy {
    /** 复制边缘像素（无黑边，但边缘可能「拉丝」）。 */
    CLAMP,

    /** 越界输出透明/黑（几何正确，四角会有黑边，可配合 [LensDistortionConfig.cropScale] 裁掉）。 */
    TRANSPARENT_BLACK,
}

/**
 * 镜头畸变校正配置（径向 k1/k2[/k3] + 强度滑杆 + 光心 + 采样策略）。
 *
 * 开放度：k 系数既可用「相机维度预设」也可用 override 直接给（例如 Camera2
 * `LENS_DISTORTION` 的实时值）；[strength] 是给用户的 0..1 滑杆，
 * 最终系数 = `(override ?: 预设) × strength`。
 *
 * 读取路径：DataStore（`user_preferences`）中以 `lens_correction_` 前缀存储，
 * key 清单见交付补丁 `delivery/patches/enhance_patch.md` 的 CONFIG 节。
 */
data class LensDistortionConfig(
    val enabled: Boolean = false,
    val dimension: LensCameraDimension = LensCameraDimension.ULTRA_WIDE,
    /** 校正强度滑杆（按比例缩放全部 k 系数）。范围 [0, 1]。 */
    val strength: Float = 1f,
    /** 直接指定 k1（null = 用 [dimension] 预设）。范围 [-1, 1]。 */
    val k1Override: Float? = null,
    /** 直接指定 k2（null = 用预设）。范围 [-1, 1]。 */
    val k2Override: Float? = null,
    /** 直接指定 k3（null = 用预设）。范围 [-1, 1]。 */
    val k3Override: Float? = null,
    /** 光心横坐标（宽度归一化）。范围 [0, 1]。 */
    val centerX: Float = 0.5f,
    /** 光心纵坐标（高度归一化）。范围 [0, 1]。 */
    val centerY: Float = 0.5f,
    /** 输出缩放（>1 = 放大裁掉黑边）。范围 [0.5, 1.5]。 */
    val cropScale: Float = 1f,
    val edgePolicy: DistortionEdgePolicy = DistortionEdgePolicy.CLAMP,
) {
    /** 生效的 k1（override 优先，再乘强度滑杆）。 */
    fun effectiveK1(): Float = effective(LensProfileDefaults.presetFor(dimension).k1, k1Override)

    /** 生效的 k2。 */
    fun effectiveK2(): Float = effective(LensProfileDefaults.presetFor(dimension).k2, k2Override)

    /** 生效的 k3。 */
    fun effectiveK3(): Float = effective(LensProfileDefaults.presetFor(dimension).k3, k3Override)

    /** 由当前配置构造畸变模型。 */
    fun model(): RadialDistortionModel = RadialDistortionModel(effectiveK1(), effectiveK2(), effectiveK3())

    /** 是否实际会改变画面（关掉或强度 0 时可跳过整条管线）。 */
    fun isActive(): Boolean = enabled && strength > 0f && !model().isIdentity

    private fun effective(preset: Float, override: Float?): Float {
        val base = override?.takeIf { it.isFinite() } ?: preset
        return base * strength
    }

    companion object {
        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 1f
        const val COEFFICIENT_MIN = -1f
        const val COEFFICIENT_MAX = 1f
        const val CENTER_MIN = 0f
        const val CENTER_MAX = 1f
        const val CROP_SCALE_MIN = 0.5f
        const val CROP_SCALE_MAX = 1.5f

        fun clamp(config: LensDistortionConfig): LensDistortionConfig = config.copy(
            strength = finite(config.strength, 1f).coerceIn(STRENGTH_MIN, STRENGTH_MAX),
            k1Override = clampCoefficient(config.k1Override),
            k2Override = clampCoefficient(config.k2Override),
            k3Override = clampCoefficient(config.k3Override),
            centerX = finite(config.centerX, 0.5f).coerceIn(CENTER_MIN, CENTER_MAX),
            centerY = finite(config.centerY, 0.5f).coerceIn(CENTER_MIN, CENTER_MAX),
            cropScale = finite(config.cropScale, 1f).coerceIn(CROP_SCALE_MIN, CROP_SCALE_MAX),
        )

        /** k 系数超界时**截断而非丢弃**（保留用户/标定的相对量级）。 */
        private fun clampCoefficient(value: Float?): Float? = value
            ?.takeIf { it.isFinite() }
            ?.coerceIn(COEFFICIENT_MIN, COEFFICIENT_MAX)

        /** 非有限值回退（NaN 的 coerceIn 仍是 NaN，必须先过滤）。 */
        private fun finite(value: Float, fallback: Float): Float =
            if (value.isFinite()) value else fallback
    }
}

/**
 * 三个相机维度各自的畸变校正配置（用户硬约束：主摄/超广角/微距分别可配默认）。
 */
data class LensCorrectionSettings(
    val perDimension: Map<LensCameraDimension, LensDistortionConfig> =
        LensProfileDefaults.defaultSettings(),
) {
    /** 取某维度的配置；缺失时回退该维度默认值（不抛异常）。 */
    fun forDimension(dimension: LensCameraDimension): LensDistortionConfig =
        perDimension[dimension] ?: LensDistortionConfig(
            dimension = dimension,
            centerX = LensProfileDefaults.presetFor(dimension).centerX,
            centerY = LensProfileDefaults.presetFor(dimension).centerY,
        )

    /** 替换某维度的配置（自动 clamp）。 */
    fun withDimension(config: LensDistortionConfig): LensCorrectionSettings =
        copy(perDimension = perDimension + (config.dimension to LensDistortionConfig.clamp(config)))

    /** 全量 clamp：返回修正后的设置 + 被修正项描述。 */
    fun clampWithReport(): Pair<LensCorrectionSettings, List<String>> {
        val reports = mutableListOf<String>()
        val clamped = LensCameraDimension.entries.associateWith { dimension ->
            val original = forDimension(dimension)
            val fixed = LensDistortionConfig.clamp(original)
            if (fixed != original) {
                reports += "$dimension: strength=${original.strength}->${fixed.strength} " +
                    "k1=${original.k1Override}->${fixed.k1Override} " +
                    "k2=${original.k2Override}->${fixed.k2Override} " +
                    "k3=${original.k3Override}->${fixed.k3Override} " +
                    "center=(${original.centerX},${original.centerY})->" +
                    "(${fixed.centerX},${fixed.centerY}) " +
                    "cropScale=${original.cropScale}->${fixed.cropScale}"
            }
            fixed
        }
        return LensCorrectionSettings(clamped) to reports
    }

    companion object {
        val DEFAULT = LensCorrectionSettings()

        /** 单维度 key 前缀（后接维度名），如 `lens_correction_ULTRA_WIDE_strength`。 */
        const val KEY_PREFIX = "lens_correction_"

        fun keyOf(dimension: LensCameraDimension, field: String): String =
            "$KEY_PREFIX${dimension.name}_$field"

        /**
         * 从「key → 值」映射构建三维度设置（缺失项用默认，类型不符忽略）。
         * 字段名：enabled / strength / k1 / k2 / k3 / center_x / center_y / crop_scale / edge_policy。
         */
        fun fromPreferences(values: Map<String, Any?>): LensCorrectionSettings {
            var settings = DEFAULT
            for (dimension in LensCameraDimension.entries) {
                val base = settings.forDimension(dimension)
                val config = base.copy(
                    enabled = LensCorrectionPrefs.bool(values, keyOf(dimension, "enabled"), base.enabled),
                    strength = LensCorrectionPrefs.float(values, keyOf(dimension, "strength"), base.strength),
                    k1Override = LensCorrectionPrefs.floatOrNull(values, keyOf(dimension, "k1")),
                    k2Override = LensCorrectionPrefs.floatOrNull(values, keyOf(dimension, "k2")),
                    k3Override = LensCorrectionPrefs.floatOrNull(values, keyOf(dimension, "k3")),
                    centerX = LensCorrectionPrefs.float(values, keyOf(dimension, "center_x"), base.centerX),
                    centerY = LensCorrectionPrefs.float(values, keyOf(dimension, "center_y"), base.centerY),
                    cropScale = LensCorrectionPrefs.float(values, keyOf(dimension, "crop_scale"), base.cropScale),
                    edgePolicy = LensCorrectionPrefs.enum(
                        values, keyOf(dimension, "edge_policy"), base.edgePolicy
                    ),
                )
                settings = settings.withDimension(config)
            }
            return settings.clampWithReport().first
        }
    }
}

/**
 * DataStore 值解析工具（纯 Kotlin，无 Android 依赖，可单测）：
 * 类型不符 / 非有限值一律回退默认，绝不抛异常。
 */
internal object LensCorrectionPrefs {

    fun float(values: Map<String, Any?>, key: String, fallback: Float): Float =
        floatOrNull(values, key) ?: fallback

    /** 缺失或非法返回 null（供 override 字段使用：null 表示「用预设」）。 */
    fun floatOrNull(values: Map<String, Any?>, key: String): Float? {
        val raw = values[key] ?: return null
        val parsed = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            is Boolean -> if (raw) 1f else 0f
            else -> null
        } ?: return null
        return if (parsed.isFinite()) parsed else null
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

    inline fun <reified T : Enum<T>> enum(values: Map<String, Any?>, key: String, fallback: T): T {
        val raw = values[key] as? String ?: return fallback
        return enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback
    }
}
