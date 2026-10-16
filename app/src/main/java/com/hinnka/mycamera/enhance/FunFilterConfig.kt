package com.hinnka.mycamera.enhance

/**
 * 趣味滤镜种类。`NONE` 表示不启用（管线会跳过整个 [EnhanceStage.FUN_FILTER] 阶段）。
 */
enum class FunFilterKind { NONE, TILT_SHIFT, SKETCH, OIL_PAINT }

/**
 * 移轴滤镜配置（渐变模糊带 + 饱和度提升）。
 *
 * 思路参考 noeltock/tiltShift.js（Star≈497，jQuery/CSS3 前端玩具项目，**仅参考思路不采代码**）：
 * 以一条可旋转的「清晰带」为中心，向两侧按 [transition] 平滑过渡到全模糊，并对清晰带提饱和，
 * 营造微缩景观效果。本实现为端侧像素级版本。
 */
data class TiltShiftConfig(
    val enabled: Boolean = true,
    /** 清晰带中心（画面高度归一化 0..1）。范围 [0, 1]。 */
    val centerY: Float = 0.5f,
    /** 清晰带总宽度（画面高度归一化）。范围 [0.02, 0.6]。 */
    val bandWidth: Float = 0.18f,
    /** 清晰带边缘到全模糊的过渡宽度（归一化）。范围 [0.01, 0.5]。 */
    val transition: Float = 0.18f,
    /** 清晰带旋转角（度）。范围 [-90, 90]。 */
    val angleDegrees: Float = 0f,
    /** 模糊半径（像素）。范围 [1, 32]。 */
    val blurRadius: Int = 8,
    /** 模糊强度（带外最大混合系数）。范围 [0, 1]。 */
    val blurStrength: Float = 1f,
    /** 清晰带饱和度提升量。范围 [0, 1]；0 = 不提饱和。 */
    val saturationBoost: Float = 0.35f,
) {
    companion object {
        const val CENTER_Y_MIN = 0f
        const val CENTER_Y_MAX = 1f
        const val BAND_WIDTH_MIN = 0.02f
        const val BAND_WIDTH_MAX = 0.6f
        const val TRANSITION_MIN = 0.01f
        const val TRANSITION_MAX = 0.5f
        const val ANGLE_MIN = -90f
        const val ANGLE_MAX = 90f
        const val BLUR_RADIUS_MIN = 1
        const val BLUR_RADIUS_MAX = 32
        const val BLUR_STRENGTH_MIN = 0f
        const val BLUR_STRENGTH_MAX = 1f
        const val SATURATION_MIN = 0f
        const val SATURATION_MAX = 1f

        fun clamp(config: TiltShiftConfig): TiltShiftConfig = config.copy(
            centerY = finite(config.centerY, 0.5f).coerceIn(CENTER_Y_MIN, CENTER_Y_MAX),
            bandWidth = finite(config.bandWidth, 0.18f).coerceIn(BAND_WIDTH_MIN, BAND_WIDTH_MAX),
            transition = finite(config.transition, 0.18f).coerceIn(TRANSITION_MIN, TRANSITION_MAX),
            angleDegrees = finite(config.angleDegrees, 0f).coerceIn(ANGLE_MIN, ANGLE_MAX),
            blurRadius = config.blurRadius.coerceIn(BLUR_RADIUS_MIN, BLUR_RADIUS_MAX),
            blurStrength = finite(config.blurStrength, 1f).coerceIn(BLUR_STRENGTH_MIN, BLUR_STRENGTH_MAX),
            saturationBoost = finite(config.saturationBoost, 0.35f)
                .coerceIn(SATURATION_MIN, SATURATION_MAX),
        )
    }
}

/**
 * 素描滤镜配置：Sobel 边缘 → 阈值门控 → 白纸黑线（可反相）。
 *
 * 思路参考 opencv/opencv（Apache-2.0）`imgproc` 的 Sobel / threshold 组合，
 * 以及低 star 开源「pencil sketch」教程（仅参考思路，不采代码）。
 */
data class SketchConfig(
    val enabled: Boolean = true,
    /** 边缘增益，越大线条越多越粗。范围 [0.1, 4]。 */
    val edgeStrength: Float = 1.2f,
    /** 边缘阈值：梯度模小于该值视为白纸。范围 [0, 0.5]。 */
    val threshold: Float = 0.06f,
    /** true = 反相（黑纸白线）。 */
    val invert: Boolean = false,
    /** 与原图的彩色混合：0 = 纯黑白线稿，>0 保留原色与线条叠加。范围 [0, 1]。 */
    val colorMix: Float = 0f,
) {
    companion object {
        const val EDGE_STRENGTH_MIN = 0.1f
        const val EDGE_STRENGTH_MAX = 4f
        const val THRESHOLD_MIN = 0f
        const val THRESHOLD_MAX = 0.5f
        const val COLOR_MIX_MIN = 0f
        const val COLOR_MIX_MAX = 1f

        fun clamp(config: SketchConfig): SketchConfig = config.copy(
            edgeStrength = finite(config.edgeStrength, 1.2f)
                .coerceIn(EDGE_STRENGTH_MIN, EDGE_STRENGTH_MAX),
            threshold = finite(config.threshold, 0.06f).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
            colorMix = finite(config.colorMix, 0f).coerceIn(COLOR_MIX_MIN, COLOR_MIX_MAX),
        )
    }
}

/**
 * 卡通 / 油画滤镜配置：快速双边平滑 → 色域量化 → 边缘压暗。
 *
 * 思路参考低 star 开源「cartoon/oil painting」实现（bilateral + quantize + edge overlay），
 * 仅参考公开思路、不采代码。双边平滑复用本模块的快速双边实现。
 */
data class OilPaintConfig(
    val enabled: Boolean = true,
    /** 色域量化级数（越大越接近原图）。范围 [2, 64]。 */
    val levels: Int = 8,
    /** 平滑用的双边半径（像素）。范围 [1, 12]。 */
    val bilateralRadius: Int = 3,
    /** 平滑用的双边值域 σ（0..1）。范围 [0.01, 1]。 */
    val sigmaColor: Float = 0.15f,
    /** 边缘压暗强度。范围 [0, 3]。 */
    val edgeStrength: Float = 0.8f,
    /** 边缘阈值，抑制噪点成边。范围 [0, 0.5]。 */
    val edgeThreshold: Float = 0.05f,
    /** 滤镜整体混合系数（0 = 原图）。范围 [0, 1]。 */
    val paintMix: Float = 1f,
) {
    companion object {
        const val LEVELS_MIN = 2
        const val LEVELS_MAX = 64
        const val BILATERAL_RADIUS_MIN = 1
        const val BILATERAL_RADIUS_MAX = 12
        const val SIGMA_COLOR_MIN = 0.01f
        const val SIGMA_COLOR_MAX = 1f
        const val EDGE_STRENGTH_MIN = 0f
        const val EDGE_STRENGTH_MAX = 3f
        const val EDGE_THRESHOLD_MIN = 0f
        const val EDGE_THRESHOLD_MAX = 0.5f
        const val PAINT_MIX_MIN = 0f
        const val PAINT_MIX_MAX = 1f

        fun clamp(config: OilPaintConfig): OilPaintConfig = config.copy(
            levels = config.levels.coerceIn(LEVELS_MIN, LEVELS_MAX),
            bilateralRadius = config.bilateralRadius
                .coerceIn(BILATERAL_RADIUS_MIN, BILATERAL_RADIUS_MAX),
            sigmaColor = finite(config.sigmaColor, 0.15f).coerceIn(SIGMA_COLOR_MIN, SIGMA_COLOR_MAX),
            edgeStrength = finite(config.edgeStrength, 0.8f)
                .coerceIn(EDGE_STRENGTH_MIN, EDGE_STRENGTH_MAX),
            edgeThreshold = finite(config.edgeThreshold, 0.05f)
                .coerceIn(EDGE_THRESHOLD_MIN, EDGE_THRESHOLD_MAX),
            paintMix = finite(config.paintMix, 1f).coerceIn(PAINT_MIX_MIN, PAINT_MIX_MAX),
        )
    }
}

/**
 * 趣味滤镜聚合配置（[EnhanceStage.FUN_FILTER] 的实际载荷）。
 * 三个子滤镜各自独立存档，切 [kind] 时互不丢失参数。
 */
data class FunFilterConfig(
    val enabled: Boolean = true,
    val kind: FunFilterKind = FunFilterKind.NONE,
    val tiltShift: TiltShiftConfig = TiltShiftConfig(),
    val sketch: SketchConfig = SketchConfig(),
    val oilPaint: OilPaintConfig = OilPaintConfig(),
) {
    /** 是否真的会执行（kind=NONE 或总开关关时调用方可跳过）。 */
    fun isActive(): Boolean = enabled && kind != FunFilterKind.NONE

    /** 全量 clamp：返回修正后的配置 + 被修正项描述。 */
    fun clampWithReport(): Pair<FunFilterConfig, List<String>> {
        val reports = mutableListOf<String>()
        fun note(name: String, before: Any?, after: Any?) {
            if (before != after) reports += "$name: $before -> $after"
        }

        val clampedTilt = TiltShiftConfig.clamp(tiltShift)
        note("funFilter.tiltShift.centerY", tiltShift.centerY, clampedTilt.centerY)
        note("funFilter.tiltShift.bandWidth", tiltShift.bandWidth, clampedTilt.bandWidth)
        note("funFilter.tiltShift.transition", tiltShift.transition, clampedTilt.transition)
        note("funFilter.tiltShift.angleDegrees", tiltShift.angleDegrees, clampedTilt.angleDegrees)
        note("funFilter.tiltShift.blurRadius", tiltShift.blurRadius, clampedTilt.blurRadius)
        note("funFilter.tiltShift.blurStrength", tiltShift.blurStrength, clampedTilt.blurStrength)
        note("funFilter.tiltShift.saturationBoost", tiltShift.saturationBoost, clampedTilt.saturationBoost)

        val clampedSketch = SketchConfig.clamp(sketch)
        note("funFilter.sketch.edgeStrength", sketch.edgeStrength, clampedSketch.edgeStrength)
        note("funFilter.sketch.threshold", sketch.threshold, clampedSketch.threshold)
        note("funFilter.sketch.colorMix", sketch.colorMix, clampedSketch.colorMix)

        val clampedOil = OilPaintConfig.clamp(oilPaint)
        note("funFilter.oilPaint.levels", oilPaint.levels, clampedOil.levels)
        note("funFilter.oilPaint.bilateralRadius", oilPaint.bilateralRadius, clampedOil.bilateralRadius)
        note("funFilter.oilPaint.sigmaColor", oilPaint.sigmaColor, clampedOil.sigmaColor)
        note("funFilter.oilPaint.edgeStrength", oilPaint.edgeStrength, clampedOil.edgeStrength)
        note("funFilter.oilPaint.edgeThreshold", oilPaint.edgeThreshold, clampedOil.edgeThreshold)
        note("funFilter.oilPaint.paintMix", oilPaint.paintMix, clampedOil.paintMix)

        return FunFilterConfig(
            enabled = enabled,
            kind = kind,
            tiltShift = clampedTilt,
            sketch = clampedSketch,
            oilPaint = clampedOil,
        ) to reports
    }

    companion object {
        val DEFAULT = FunFilterConfig()

        fun fromPreferences(values: Map<String, Any?>): FunFilterConfig {
            val base = DEFAULT
            return FunFilterConfig(
                enabled = EnhancePrefs.bool(values, KEY_ENABLED, base.enabled),
                kind = EnhancePrefs.enum(values, KEY_KIND, base.kind),
                tiltShift = base.tiltShift.copy(
                    enabled = EnhancePrefs.bool(values, KEY_TILT_ENABLED, base.tiltShift.enabled),
                    centerY = EnhancePrefs.float(values, KEY_TILT_CENTER_Y, base.tiltShift.centerY),
                    bandWidth = EnhancePrefs.float(values, KEY_TILT_BAND_WIDTH, base.tiltShift.bandWidth),
                    transition = EnhancePrefs.float(values, KEY_TILT_TRANSITION, base.tiltShift.transition),
                    angleDegrees = EnhancePrefs.float(values, KEY_TILT_ANGLE, base.tiltShift.angleDegrees),
                    blurRadius = EnhancePrefs.int(values, KEY_TILT_BLUR_RADIUS, base.tiltShift.blurRadius),
                    blurStrength = EnhancePrefs.float(
                        values, KEY_TILT_BLUR_STRENGTH, base.tiltShift.blurStrength
                    ),
                    saturationBoost = EnhancePrefs.float(
                        values, KEY_TILT_SATURATION, base.tiltShift.saturationBoost
                    ),
                ),
                sketch = base.sketch.copy(
                    enabled = EnhancePrefs.bool(values, KEY_SKETCH_ENABLED, base.sketch.enabled),
                    edgeStrength = EnhancePrefs.float(
                        values, KEY_SKETCH_EDGE_STRENGTH, base.sketch.edgeStrength
                    ),
                    threshold = EnhancePrefs.float(values, KEY_SKETCH_THRESHOLD, base.sketch.threshold),
                    invert = EnhancePrefs.bool(values, KEY_SKETCH_INVERT, base.sketch.invert),
                    colorMix = EnhancePrefs.float(values, KEY_SKETCH_COLOR_MIX, base.sketch.colorMix),
                ),
                oilPaint = base.oilPaint.copy(
                    enabled = EnhancePrefs.bool(values, KEY_OIL_ENABLED, base.oilPaint.enabled),
                    levels = EnhancePrefs.int(values, KEY_OIL_LEVELS, base.oilPaint.levels),
                    bilateralRadius = EnhancePrefs.int(
                        values, KEY_OIL_BILATERAL_RADIUS, base.oilPaint.bilateralRadius
                    ),
                    sigmaColor = EnhancePrefs.float(values, KEY_OIL_SIGMA_COLOR, base.oilPaint.sigmaColor),
                    edgeStrength = EnhancePrefs.float(
                        values, KEY_OIL_EDGE_STRENGTH, base.oilPaint.edgeStrength
                    ),
                    edgeThreshold = EnhancePrefs.float(
                        values, KEY_OIL_EDGE_THRESHOLD, base.oilPaint.edgeThreshold
                    ),
                    paintMix = EnhancePrefs.float(values, KEY_OIL_PAINT_MIX, base.oilPaint.paintMix),
                ),
            ).let { it.clampWithReport().first }
        }

        const val KEY_ENABLED = "enhance_fun_enabled"
        const val KEY_KIND = "enhance_fun_kind"

        const val KEY_TILT_ENABLED = "enhance_fun_tilt_enabled"
        const val KEY_TILT_CENTER_Y = "enhance_fun_tilt_center_y"
        const val KEY_TILT_BAND_WIDTH = "enhance_fun_tilt_band_width"
        const val KEY_TILT_TRANSITION = "enhance_fun_tilt_transition"
        const val KEY_TILT_ANGLE = "enhance_fun_tilt_angle"
        const val KEY_TILT_BLUR_RADIUS = "enhance_fun_tilt_blur_radius"
        const val KEY_TILT_BLUR_STRENGTH = "enhance_fun_tilt_blur_strength"
        const val KEY_TILT_SATURATION = "enhance_fun_tilt_saturation"

        const val KEY_SKETCH_ENABLED = "enhance_fun_sketch_enabled"
        const val KEY_SKETCH_EDGE_STRENGTH = "enhance_fun_sketch_edge_strength"
        const val KEY_SKETCH_THRESHOLD = "enhance_fun_sketch_threshold"
        const val KEY_SKETCH_INVERT = "enhance_fun_sketch_invert"
        const val KEY_SKETCH_COLOR_MIX = "enhance_fun_sketch_color_mix"

        const val KEY_OIL_ENABLED = "enhance_fun_oil_enabled"
        const val KEY_OIL_LEVELS = "enhance_fun_oil_levels"
        const val KEY_OIL_BILATERAL_RADIUS = "enhance_fun_oil_bilateral_radius"
        const val KEY_OIL_SIGMA_COLOR = "enhance_fun_oil_sigma_color"
        const val KEY_OIL_EDGE_STRENGTH = "enhance_fun_oil_edge_strength"
        const val KEY_OIL_EDGE_THRESHOLD = "enhance_fun_oil_edge_threshold"
        const val KEY_OIL_PAINT_MIX = "enhance_fun_oil_paint_mix"
    }
}
