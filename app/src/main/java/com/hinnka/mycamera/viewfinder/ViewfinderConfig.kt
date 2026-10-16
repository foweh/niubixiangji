package com.hinnka.mycamera.viewfinder

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 取景器辅助模块（斑马纹 / 实时直方图 / 峰值对焦强化 / AR 趣味贴纸）的统一参数取值域。
 *
 * 设计原则（PATTERN_SPEC §5 开放度）：每一个可调参数都给出**默认值 + 取值范围 + clamp**，
 * 越界一律修正并记录修正项，禁止裸写死魔法数；误设不得崩溃。
 */
object ViewfinderParamRanges {

    // ---------------------------------------------------------------- 斑马纹

    const val ZEBRA_THRESHOLD_PERCENT_DEFAULT = 95f
    const val ZEBRA_THRESHOLD_PERCENT_MIN = 50f
    const val ZEBRA_THRESHOLD_PERCENT_MAX = 120f

    const val ZEBRA_STRIPE_WIDTH_DEFAULT = 12f
    const val ZEBRA_STRIPE_WIDTH_MIN = 2f
    const val ZEBRA_STRIPE_WIDTH_MAX = 64f

    const val ZEBRA_STRIPE_DUTY_DEFAULT = 0.5f
    const val ZEBRA_STRIPE_DUTY_MIN = 0.1f
    const val ZEBRA_STRIPE_DUTY_MAX = 0.9f

    const val ZEBRA_STRIPE_ANGLE_DEFAULT = 45f
    const val ZEBRA_STRIPE_ANGLE_MIN = 0f
    const val ZEBRA_STRIPE_ANGLE_MAX = 180f

    const val ZEBRA_STRIPE_ALPHA_DEFAULT = 0.85f
    const val ZEBRA_STRIPE_ALPHA_MIN = 0f
    const val ZEBRA_STRIPE_ALPHA_MAX = 1f

    const val ZEBRA_CHANNEL_MIN = 0f
    const val ZEBRA_CHANNEL_MAX = 1f

    // ---------------------------------------------------------------- 直方图

    /** 允许的直方图 bin 数；其它取值会就近吸附到这三个之一。 */
    val HISTOGRAM_BIN_COUNTS: IntArray = intArrayOf(64, 128, 256)
    const val HISTOGRAM_BIN_DEFAULT = 256

    const val HISTOGRAM_SAMPLE_INTERVAL_DEFAULT_MS = 300L
    const val HISTOGRAM_SAMPLE_INTERVAL_MIN_MS = 50L
    const val HISTOGRAM_SAMPLE_INTERVAL_MAX_MS = 5000L

    const val HISTOGRAM_MARGIN_DEFAULT = 0.04f
    const val HISTOGRAM_MARGIN_MIN = 0f
    const val HISTOGRAM_MARGIN_MAX = 0.45f

    const val HISTOGRAM_WIDTH_FRACTION_DEFAULT = 0.28f
    const val HISTOGRAM_WIDTH_FRACTION_MIN = 0.05f
    const val HISTOGRAM_WIDTH_FRACTION_MAX = 0.6f

    const val HISTOGRAM_HEIGHT_FRACTION_DEFAULT = 0.14f
    const val HISTOGRAM_HEIGHT_FRACTION_MIN = 0.04f
    const val HISTOGRAM_HEIGHT_FRACTION_MAX = 0.4f

    const val HISTOGRAM_ALPHA_DEFAULT = 0.72f
    const val HISTOGRAM_ALPHA_MIN = 0.1f
    const val HISTOGRAM_ALPHA_MAX = 1f

    const val HISTOGRAM_SMOOTHING_DEFAULT = 0.3f
    const val HISTOGRAM_SMOOTHING_MIN = 0f
    const val HISTOGRAM_SMOOTHING_MAX = 0.95f

    // ------------------------------------------------------------ 峰值对焦

    /**
     * 灵敏度：0f..1f。默认 0.5f 对应阈值 0.80f，与工程既有 focus peaking 的硬编码阈值一致，
     * 因此默认配置不会改变既有观感。
     */
    const val PEAKING_SENSITIVITY_DEFAULT = 0.5f
    const val PEAKING_SENSITIVITY_MIN = 0f
    const val PEAKING_SENSITIVITY_MAX = 1f

    /** 灵敏度线性映射的端点：灵敏度越高 → 阈值越低 → 弱边缘也会被点亮。 */
    const val PEAKING_THRESHOLD_AT_SENSITIVITY_0 = 0.95f
    const val PEAKING_THRESHOLD_AT_SENSITIVITY_1 = 0.65f

    const val PEAKING_THRESHOLD_MIN = 0.05f
    const val PEAKING_THRESHOLD_MAX = 0.99f

    /** 哨兵值：表示「不覆盖阈值，按灵敏度推导」。 */
    const val PEAKING_THRESHOLD_AUTO = -1f

    const val PEAKING_MIX_DEFAULT = 0.9f
    const val PEAKING_MIX_MIN = 0.1f
    const val PEAKING_MIX_MAX = 1f

    const val PEAKING_CHANNEL_MIN = 0f
    const val PEAKING_CHANNEL_MAX = 1f

    const val PEAKING_STEP_SCALE_DEFAULT = 1f
    const val PEAKING_STEP_SCALE_MIN = 1f
    const val PEAKING_STEP_SCALE_MAX = 4f

    // ---------------------------------------------------------------- 贴纸

    const val STICKER_SCALE_DEFAULT = 1f
    const val STICKER_SCALE_MIN = 0.2f
    const val STICKER_SCALE_MAX = 4f

    const val STICKER_OFFSET_DEFAULT = 0f
    const val STICKER_OFFSET_MIN = -1f
    const val STICKER_OFFSET_MAX = 1f

    const val STICKER_ALPHA_DEFAULT = 1f
    const val STICKER_ALPHA_MIN = 0f
    const val STICKER_ALPHA_MAX = 1f

    const val STICKER_MAX_FACES_DEFAULT = 1
    const val STICKER_MAX_FACES_MIN = 1
    const val STICKER_MAX_FACES_MAX = 5

    const val STICKER_INTERVAL_DEFAULT_MS = 100L
    const val STICKER_INTERVAL_MIN_MS = 33L
    const val STICKER_INTERVAL_MAX_MS = 2000L

    const val STICKER_SMOOTHING_DEFAULT = 0.5f
    const val STICKER_SMOOTHING_MIN = 0f
    const val STICKER_SMOOTHING_MAX = 0.95f

    /** 灵敏度 → 边缘阈值（线性）。 */
    fun peakingThresholdFromSensitivity(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(PEAKING_SENSITIVITY_MIN, PEAKING_SENSITIVITY_MAX)
        return (PEAKING_THRESHOLD_AT_SENSITIVITY_0 +
            (PEAKING_THRESHOLD_AT_SENSITIVITY_1 - PEAKING_THRESHOLD_AT_SENSITIVITY_0) * s)
            .coerceIn(PEAKING_THRESHOLD_MIN, PEAKING_THRESHOLD_MAX)
    }

    /** 任意 bin 数吸附到最近的允许值（非法/极小/极大值都能得到合法结果）。 */
    fun snapBinCount(value: Int): Int {
        val allowed = HISTOGRAM_BIN_COUNTS
        var best = allowed[0]
        var bestDistance = abs(value - best)
        for (candidate in allowed) {
            val distance = abs(value - candidate)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * 阈值百分比 → 8bit 亮度阈值。
     *
     * 50% → 128，95% → 242，100% → 255；>100%（超白）在 8bit 预览源上被 clamp 到 255，
     * 即等价于「只在纯白触发」。
     */
    fun zebraThresholdPercentToLuma(percent: Float): Int {
        val p = percent.coerceIn(ZEBRA_THRESHOLD_PERCENT_MIN, ZEBRA_THRESHOLD_PERCENT_MAX)
        return (p / 100f * 255f).roundToInt().coerceIn(0, 255)
    }
}

/**
 * clamp 结果：修正后的配置 + 被修正项的可读说明（供日志与自检使用）。
 */
data class ClampResult<T>(val value: T, val corrections: List<String>) {
    val corrected: Boolean get() = corrections.isNotEmpty()
}

/** 斑马纹过曝判定基准。 */
enum class ZebraMetric {
    /** Rec.709 亮度。 */
    LUMA,

    /** RGB 三通道最大值（更容易在纯色高饱和区域触发）。 */
    MAX_RGB,
}

/** 直方图叠加层在预览内的锚点方位（位置可配）。 */
enum class HistogramAnchor { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * 斑马纹过曝提示配置。
 *
 * @param enabled 开关，默认关（新功能默认不打扰取景）
 * @param thresholdPercent 触发阈值百分比，[ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MIN]..[ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MAX]，默认 95
 * @param stripeWidthPx 条纹宽度（预览像素），2f..64f，默认 12f
 * @param stripeDuty 条纹占空比，0.1f..0.9f，默认 0.5f
 * @param stripeAngleDeg 条纹角度（度），0f..180f，默认 45f
 * @param stripeColorR 条纹色 R，0f..1f，默认 1f
 * @param stripeColorG 条纹色 G，0f..1f，默认 1f
 * @param stripeColorB 条纹色 B，0f..1f，默认 1f
 * @param stripeAlpha 条纹不透明度，0f..1f，默认 0.85f
 * @param metric 判定基准
 */
data class ZebraConfig(
    val enabled: Boolean = false,
    val thresholdPercent: Float = ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_DEFAULT,
    val stripeWidthPx: Float = ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_DEFAULT,
    val stripeDuty: Float = ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_DEFAULT,
    val stripeAngleDeg: Float = ViewfinderParamRanges.ZEBRA_STRIPE_ANGLE_DEFAULT,
    val stripeColorR: Float = 1f,
    val stripeColorG: Float = 1f,
    val stripeColorB: Float = 1f,
    val stripeAlpha: Float = ViewfinderParamRanges.ZEBRA_STRIPE_ALPHA_DEFAULT,
    val metric: ZebraMetric = ZebraMetric.LUMA,
) {
    /** 8bit 亮度阈值（0..255）。 */
    val lumaThreshold: Int
        get() = ViewfinderParamRanges.zebraThresholdPercentToLuma(thresholdPercent)

    /** 归一化亮度阈值（0f..1f），供 GLSL uniform 使用。 */
    val normalizedThreshold: Float
        get() = lumaThreshold / 255f

    companion object {
        fun clamp(config: ZebraConfig): ClampResult<ZebraConfig> {
            val corrections = mutableListOf<String>()
            fun float(name: String, value: Float, min: Float, max: Float): Float {
                val safe = if (value.isFinite()) value.coerceIn(min, max) else min
                if (safe != value) corrections += "$name=$value → $safe（范围 $min..$max）"
                return safe
            }

            val clamped = config.copy(
                thresholdPercent = float(
                    "zebra.thresholdPercent", config.thresholdPercent,
                    ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MIN,
                    ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MAX,
                ),
                stripeWidthPx = float(
                    "zebra.stripeWidthPx", config.stripeWidthPx,
                    ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MIN,
                    ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MAX,
                ),
                stripeDuty = float(
                    "zebra.stripeDuty", config.stripeDuty,
                    ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MIN,
                    ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MAX,
                ),
                stripeAngleDeg = float(
                    "zebra.stripeAngleDeg", config.stripeAngleDeg,
                    ViewfinderParamRanges.ZEBRA_STRIPE_ANGLE_MIN,
                    ViewfinderParamRanges.ZEBRA_STRIPE_ANGLE_MAX,
                ),
                stripeColorR = float(
                    "zebra.stripeColorR", config.stripeColorR,
                    ViewfinderParamRanges.ZEBRA_CHANNEL_MIN, ViewfinderParamRanges.ZEBRA_CHANNEL_MAX,
                ),
                stripeColorG = float(
                    "zebra.stripeColorG", config.stripeColorG,
                    ViewfinderParamRanges.ZEBRA_CHANNEL_MIN, ViewfinderParamRanges.ZEBRA_CHANNEL_MAX,
                ),
                stripeColorB = float(
                    "zebra.stripeColorB", config.stripeColorB,
                    ViewfinderParamRanges.ZEBRA_CHANNEL_MIN, ViewfinderParamRanges.ZEBRA_CHANNEL_MAX,
                ),
                stripeAlpha = float(
                    "zebra.stripeAlpha", config.stripeAlpha,
                    ViewfinderParamRanges.ZEBRA_STRIPE_ALPHA_MIN,
                    ViewfinderParamRanges.ZEBRA_STRIPE_ALPHA_MAX,
                ),
            )
            return ClampResult(clamped, corrections)
        }
    }
}

/**
 * 实时直方图叠加层配置（亮度 + RGB 三通道）。
 *
 * 注意：这是**取景器可视化叠加**用的直方图（接预览帧数据），
 * 与 `Camera2Controller.updateHistogram` / `CameraState.histogram` 那套**内部曝光统计直方图**
 * （32×32 测光网格、仅亮度、供测光使用）是两回事，互不复用也不互相覆盖。
 *
 * @param enabled 开关，默认关
 * @param binCount 64 / 128 / 256，默认 256；非法值就近吸附
 * @param sampleIntervalMs 采样节流间隔，50..5000，默认 300
 * @param anchor 叠加方位
 * @param marginFraction 距边缘留白（屏幕短边比例），0f..0.45f，默认 0.04f
 * @param widthFraction 宽度占预览宽度比例，0.05f..0.6f，默认 0.28f
 * @param heightFraction 高度占预览高度比例，0.04f..0.4f，默认 0.14f
 * @param alpha 整体不透明度，0.1f..1f，默认 0.72f
 * @param smoothing 显示 EMA 平滑系数，0f..0.95f，默认 0.3f（0 = 不平滑）
 * @param showLuma / showRed / showGreen / showBlue 通道开关
 */
data class HistogramOverlayConfig(
    val enabled: Boolean = false,
    val binCount: Int = ViewfinderParamRanges.HISTOGRAM_BIN_DEFAULT,
    val sampleIntervalMs: Long = ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_DEFAULT_MS,
    val anchor: HistogramAnchor = HistogramAnchor.TOP_RIGHT,
    val marginFraction: Float = ViewfinderParamRanges.HISTOGRAM_MARGIN_DEFAULT,
    val widthFraction: Float = ViewfinderParamRanges.HISTOGRAM_WIDTH_FRACTION_DEFAULT,
    val heightFraction: Float = ViewfinderParamRanges.HISTOGRAM_HEIGHT_FRACTION_DEFAULT,
    val alpha: Float = ViewfinderParamRanges.HISTOGRAM_ALPHA_DEFAULT,
    val smoothing: Float = ViewfinderParamRanges.HISTOGRAM_SMOOTHING_DEFAULT,
    val showLuma: Boolean = true,
    val showRed: Boolean = true,
    val showGreen: Boolean = true,
    val showBlue: Boolean = true,
) {
    companion object {
        fun clamp(config: HistogramOverlayConfig): ClampResult<HistogramOverlayConfig> {
            val corrections = mutableListOf<String>()
            fun float(name: String, value: Float, min: Float, max: Float): Float {
                val safe = if (value.isFinite()) value.coerceIn(min, max) else min
                if (safe != value) corrections += "$name=$value → $safe（范围 $min..$max）"
                return safe
            }

            val bins = ViewfinderParamRanges.snapBinCount(config.binCount)
            if (bins != config.binCount) {
                corrections += "histogram.binCount=${config.binCount} → $bins（仅允许 " +
                    ViewfinderParamRanges.HISTOGRAM_BIN_COUNTS.joinToString("/") + "）"
            }
            val interval = config.sampleIntervalMs.coerceIn(
                ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS,
                ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS,
            )
            if (interval != config.sampleIntervalMs) {
                corrections += "histogram.sampleIntervalMs=${config.sampleIntervalMs} → $interval" +
                    "（范围 ${ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS}.." +
                    "${ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS}）"
            }

            val clamped = config.copy(
                binCount = bins,
                sampleIntervalMs = interval,
                marginFraction = float(
                    "histogram.marginFraction", config.marginFraction,
                    ViewfinderParamRanges.HISTOGRAM_MARGIN_MIN,
                    ViewfinderParamRanges.HISTOGRAM_MARGIN_MAX,
                ),
                widthFraction = float(
                    "histogram.widthFraction", config.widthFraction,
                    ViewfinderParamRanges.HISTOGRAM_WIDTH_FRACTION_MIN,
                    ViewfinderParamRanges.HISTOGRAM_WIDTH_FRACTION_MAX,
                ),
                heightFraction = float(
                    "histogram.heightFraction", config.heightFraction,
                    ViewfinderParamRanges.HISTOGRAM_HEIGHT_FRACTION_MIN,
                    ViewfinderParamRanges.HISTOGRAM_HEIGHT_FRACTION_MAX,
                ),
                alpha = float(
                    "histogram.alpha", config.alpha,
                    ViewfinderParamRanges.HISTOGRAM_ALPHA_MIN,
                    ViewfinderParamRanges.HISTOGRAM_ALPHA_MAX,
                ),
                smoothing = float(
                    "histogram.smoothing", config.smoothing,
                    ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MIN,
                    ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MAX,
                ),
            )
            return ClampResult(clamped, corrections)
        }
    }
}

/**
 * 峰值对焦（focus peaking）强化配置。
 *
 * 工程既有 `lut/LutRenderer.renderFocusPeaking` 已实现 Sobel 边缘点亮，但阈值（0.8）、颜色
 * （1.0,0.1,0.1）、混合系数（0.9）均为硬编码。本配置把这些开放为可调项；默认值刻意等于既有
 * 硬编码值，保证默认行为不回归。
 *
 * @param enabled 开关，默认 true（与既有 focusPeakingEnabled 默认一致）
 * @param sensitivity 灵敏度 0f..1f，默认 0.5f → 阈值 0.80f
 * @param edgeThresholdOverride 高级直填阈值；[ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO](-1) 表示按灵敏度推导，
 *        否则需落在 0.05f..0.99f
 * @param colorR / colorG / colorB 峰值色，0f..1f
 * @param mixStrength 混合强度，0.1f..1f，默认 0.9f（需配合增强着色器生效）
 * @param stepScale 采样步长倍数，1f..4f，默认 1f（需配合增强着色器生效）
 */
data class PeakingEnhanceConfig(
    val enabled: Boolean = true,
    val sensitivity: Float = ViewfinderParamRanges.PEAKING_SENSITIVITY_DEFAULT,
    val edgeThresholdOverride: Float = ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO,
    val colorR: Float = 1f,
    val colorG: Float = 0.1f,
    val colorB: Float = 0.1f,
    val mixStrength: Float = ViewfinderParamRanges.PEAKING_MIX_DEFAULT,
    val stepScale: Float = ViewfinderParamRanges.PEAKING_STEP_SCALE_DEFAULT,
) {
    /**
     * 实际生效的边缘阈值：`edgeThresholdOverride` 在合法区间内则用它，否则按灵敏度线性推导。
     */
    fun resolveEdgeThreshold(): Float {
        val override = edgeThresholdOverride
        if (override.isFinite() &&
            override >= ViewfinderParamRanges.PEAKING_THRESHOLD_MIN &&
            override <= ViewfinderParamRanges.PEAKING_THRESHOLD_MAX
        ) {
            return override
        }
        return ViewfinderParamRanges.peakingThresholdFromSensitivity(sensitivity)
    }

    companion object {
        fun clamp(config: PeakingEnhanceConfig): ClampResult<PeakingEnhanceConfig> {
            val corrections = mutableListOf<String>()
            fun float(name: String, value: Float, min: Float, max: Float): Float {
                val safe = if (value.isFinite()) value.coerceIn(min, max) else min
                if (safe != value) corrections += "$name=$value → $safe（范围 $min..$max）"
                return safe
            }

            // 哨兵值 -1 视作合法（表示自动）；只要不是哨兵，就压进有效区间，避免出现中间态。
            var override = config.edgeThresholdOverride
            if (!override.isFinite()) {
                corrections += "peaking.edgeThresholdOverride=$override → " +
                    ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO + "（非法值回落到自动）"
                override = ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO
            } else if (override != ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO &&
                override !in ViewfinderParamRanges.PEAKING_THRESHOLD_MIN..ViewfinderParamRanges.PEAKING_THRESHOLD_MAX
            ) {
                val safe = override.coerceIn(
                    ViewfinderParamRanges.PEAKING_THRESHOLD_MIN,
                    ViewfinderParamRanges.PEAKING_THRESHOLD_MAX,
                )
                corrections += "peaking.edgeThresholdOverride=$override → $safe" +
                    "（合法区间 ${ViewfinderParamRanges.PEAKING_THRESHOLD_MIN}.." +
                    "${ViewfinderParamRanges.PEAKING_THRESHOLD_MAX}，或 -1 表示自动）"
                override = safe
            }

            val clamped = config.copy(
                sensitivity = float(
                    "peaking.sensitivity", config.sensitivity,
                    ViewfinderParamRanges.PEAKING_SENSITIVITY_MIN,
                    ViewfinderParamRanges.PEAKING_SENSITIVITY_MAX,
                ),
                edgeThresholdOverride = override,
                colorR = float(
                    "peaking.colorR", config.colorR,
                    ViewfinderParamRanges.PEAKING_CHANNEL_MIN, ViewfinderParamRanges.PEAKING_CHANNEL_MAX,
                ),
                colorG = float(
                    "peaking.colorG", config.colorG,
                    ViewfinderParamRanges.PEAKING_CHANNEL_MIN, ViewfinderParamRanges.PEAKING_CHANNEL_MAX,
                ),
                colorB = float(
                    "peaking.colorB", config.colorB,
                    ViewfinderParamRanges.PEAKING_CHANNEL_MIN, ViewfinderParamRanges.PEAKING_CHANNEL_MAX,
                ),
                mixStrength = float(
                    "peaking.mixStrength", config.mixStrength,
                    ViewfinderParamRanges.PEAKING_MIX_MIN, ViewfinderParamRanges.PEAKING_MIX_MAX,
                ),
                stepScale = float(
                    "peaking.stepScale", config.stepScale,
                    ViewfinderParamRanges.PEAKING_STEP_SCALE_MIN,
                    ViewfinderParamRanges.PEAKING_STEP_SCALE_MAX,
                ),
            )
            return ClampResult(clamped, corrections)
        }
    }
}

/**
 * AR 趣味贴纸配置（基于 MediaPipe FaceMesh 人脸锚定）。
 *
 * @param enabled 开关，默认关
 * @param stickerId 贴纸 id（见 [FaceStickerCatalog]），未知 id 回落内置默认贴纸
 * @param anchorMode 锚定部位（嘴/鼻/眼/头顶）
 * @param scale 缩放，0.2f..4f，默认 1f
 * @param offsetX / offsetY 相对人脸的归一化偏移，-1f..1f
 * @param mirror 是否水平镜像
 * @param alpha 不透明度，0f..1f
 * @param maxFaces 最多同时贴纸的人脸数，1..5
 * @param detectIntervalMs 检测节流间隔，33..2000
 * @param smoothing 锚点 EMA 平滑，0f..0.95f
 * @param modelAssetPath FaceMesh 模型路径（assets 或私有目录文件名，可配）
 * @param modelDownloadUrl 模型下载地址（可配；留空则仅用内置/已安装模型）
 * @param packDirectory 外部贴纸素材目录名（可配）
 * @param packDownloadUrl 外部贴纸包下载地址（可配；留空则仅用内置矢量贴纸）
 */
data class StickerConfig(
    val enabled: Boolean = false,
    val stickerId: String = FaceStickerCatalog.DEFAULT_STICKER_ID,
    val anchorMode: StickerAnchorMode = StickerAnchorMode.FOREHEAD,
    val scale: Float = ViewfinderParamRanges.STICKER_SCALE_DEFAULT,
    val offsetX: Float = ViewfinderParamRanges.STICKER_OFFSET_DEFAULT,
    val offsetY: Float = ViewfinderParamRanges.STICKER_OFFSET_DEFAULT,
    val mirror: Boolean = false,
    val alpha: Float = ViewfinderParamRanges.STICKER_ALPHA_DEFAULT,
    val maxFaces: Int = ViewfinderParamRanges.STICKER_MAX_FACES_DEFAULT,
    val detectIntervalMs: Long = ViewfinderParamRanges.STICKER_INTERVAL_DEFAULT_MS,
    val smoothing: Float = ViewfinderParamRanges.STICKER_SMOOTHING_DEFAULT,
    val modelAssetPath: String = FaceStickerDetector.DEFAULT_MODEL_ASSET_PATH,
    val modelDownloadUrl: String = FaceStickerDetector.DEFAULT_MODEL_DOWNLOAD_URL,
    val packDirectory: String = StickerPackConfig.DEFAULT_PACK_DIRECTORY,
    val packDownloadUrl: String = "",
) {
    companion object {
        fun clamp(config: StickerConfig): ClampResult<StickerConfig> {
            val corrections = mutableListOf<String>()
            fun float(name: String, value: Float, min: Float, max: Float): Float {
                val safe = if (value.isFinite()) value.coerceIn(min, max) else min
                if (safe != value) corrections += "$name=$value → $safe（范围 $min..$max）"
                return safe
            }

            val faces = config.maxFaces.coerceIn(
                ViewfinderParamRanges.STICKER_MAX_FACES_MIN,
                ViewfinderParamRanges.STICKER_MAX_FACES_MAX,
            )
            if (faces != config.maxFaces) {
                corrections += "sticker.maxFaces=${config.maxFaces} → $faces"
            }
            val interval = config.detectIntervalMs.coerceIn(
                ViewfinderParamRanges.STICKER_INTERVAL_MIN_MS,
                ViewfinderParamRanges.STICKER_INTERVAL_MAX_MS,
            )
            if (interval != config.detectIntervalMs) {
                corrections += "sticker.detectIntervalMs=${config.detectIntervalMs} → $interval"
            }
            val stickerId = config.stickerId.trim().ifEmpty {
                corrections += "sticker.stickerId 为空 → ${FaceStickerCatalog.DEFAULT_STICKER_ID}"
                FaceStickerCatalog.DEFAULT_STICKER_ID
            }
            if (FaceStickerCatalog.find(stickerId).id != stickerId) {
                corrections += "sticker.stickerId=$stickerId 未注册 → " +
                    FaceStickerCatalog.DEFAULT_STICKER_ID
            }

            val clamped = config.copy(
                stickerId = FaceStickerCatalog.find(stickerId).id,
                scale = float(
                    "sticker.scale", config.scale,
                    ViewfinderParamRanges.STICKER_SCALE_MIN, ViewfinderParamRanges.STICKER_SCALE_MAX,
                ),
                offsetX = float(
                    "sticker.offsetX", config.offsetX,
                    ViewfinderParamRanges.STICKER_OFFSET_MIN, ViewfinderParamRanges.STICKER_OFFSET_MAX,
                ),
                offsetY = float(
                    "sticker.offsetY", config.offsetY,
                    ViewfinderParamRanges.STICKER_OFFSET_MIN, ViewfinderParamRanges.STICKER_OFFSET_MAX,
                ),
                alpha = float(
                    "sticker.alpha", config.alpha,
                    ViewfinderParamRanges.STICKER_ALPHA_MIN, ViewfinderParamRanges.STICKER_ALPHA_MAX,
                ),
                smoothing = float(
                    "sticker.smoothing", config.smoothing,
                    ViewfinderParamRanges.STICKER_SMOOTHING_MIN,
                    ViewfinderParamRanges.STICKER_SMOOTHING_MAX,
                ),
                maxFaces = faces,
                detectIntervalMs = interval,
            )
            return ClampResult(clamped, corrections)
        }
    }
}

/** 取景器辅助模块的聚合配置。 */
data class ViewfinderSettings(
    val zebra: ZebraConfig = ZebraConfig(),
    val histogram: HistogramOverlayConfig = HistogramOverlayConfig(),
    val peaking: PeakingEnhanceConfig = PeakingEnhanceConfig(),
    val sticker: StickerConfig = StickerConfig(),
) {
    companion object {
        fun clamp(settings: ViewfinderSettings): ClampResult<ViewfinderSettings> {
            val zebra = ZebraConfig.clamp(settings.zebra)
            val histogram = HistogramOverlayConfig.clamp(settings.histogram)
            val peaking = PeakingEnhanceConfig.clamp(settings.peaking)
            val sticker = StickerConfig.clamp(settings.sticker)
            val corrections = zebra.corrections +
                histogram.corrections +
                peaking.corrections +
                sticker.corrections
            return ClampResult(
                settings.copy(
                    zebra = zebra.value,
                    histogram = histogram.value,
                    peaking = peaking.value,
                    sticker = sticker.value,
                ),
                corrections,
            )
        }
    }
}
