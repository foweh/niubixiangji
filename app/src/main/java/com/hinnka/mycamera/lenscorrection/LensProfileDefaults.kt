package com.hinnka.mycamera.lenscorrection

/**
 * 相机维度：决定使用哪套镜头畸变预设（对应目标机 K50G 的三颗后摄）。
 * 主摄 IMX686 64MP / 超广角 OV8856 定焦 / 微距 GC02M1。
 */
enum class LensCameraDimension { MAIN, ULTRA_WIDE, MACRO }

/**
 * 单个相机维度的镜头畸变档案（畸变模型系数 + 光心 + 建议裁切）。
 *
 * 注意：内置值是**起始估计值**，用于「打开就有明显改善」，不保证逐台设备精确。
 * 真机精确值应当来自 Camera2 的 `LENS_DISTORTION`（API 28+，本机 FULL/LEVEL_3 支持），
 * 通过 [LensDistortionConfig.k1Override] / [LensDistortionConfig.k2Override] /
 * [LensDistortionConfig.k3Override] 传入，或先做一次棋盘格标定。
 */
data class LensProfile(
    val dimension: LensCameraDimension,
    /** 展示名（UI/日志用）。 */
    val label: String,
    /** 第一径向系数（正 = 桶形畸变校正）。 */
    val k1: Float,
    /** 第二径向系数。 */
    val k2: Float,
    /** 第三径向系数（默认 0，Camera2 三系数模型用）。 */
    val k3: Float = 0f,
    /** 光心横坐标（画面宽度归一化 0..1）。 */
    val centerX: Float = 0.5f,
    /** 光心纵坐标（画面高度归一化 0..1）。 */
    val centerY: Float = 0.5f,
    /** 建议输出裁切系数：校正后四角会外扩露出边缘，>1 表示轻微放大以裁掉。 */
    val suggestedCropScale: Float = 1f,
) {
    /** 生成该档案对应的畸变模型（强度由调用方另行缩放）。 */
    fun model(strength: Float = 1f): RadialDistortionModel {
        val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 1f
        return RadialDistortionModel(k1 * s, k2 * s, k3 * s)
    }
}

/**
 * 内置镜头畸变档案 + 按维度取预设。
 *
 * 数值来源：手机超广角定焦模组的典型桶形畸变量级（公开镜头标定资料与 Camera2
 * `LENS_DISTORTION` 的常见取值区间），仅作**默认起点**，不做任何第三方代码移植。
 */
object LensProfileDefaults {

    /** 主摄（IMX686）：畸变很小，只做轻微校正。 */
    val MAIN = LensProfile(
        dimension = LensCameraDimension.MAIN,
        label = "主摄 IMX686",
        k1 = 0.055f,
        k2 = -0.008f,
        suggestedCropScale = 1.02f,
    )

    /** 超广角（OV8856）：典型强桶形畸变，是本模块的主要目标场景。 */
    val ULTRA_WIDE = LensProfile(
        dimension = LensCameraDimension.ULTRA_WIDE,
        label = "超广角 OV8856",
        k1 = 0.32f,
        k2 = -0.06f,
        suggestedCropScale = 1.12f,
    )

    /** 微距（GC02M1）：中等桶形畸变。 */
    val MACRO = LensProfile(
        dimension = LensCameraDimension.MACRO,
        label = "微距 GC02M1",
        k1 = 0.13f,
        k2 = -0.02f,
        suggestedCropScale = 1.05f,
    )

    val ALL: List<LensProfile> = listOf(MAIN, ULTRA_WIDE, MACRO)

    /** 按相机维度取预设；未知维度回退 [ULTRA_WIDE]（畸变校正需求最强的一档）。 */
    fun presetFor(dimension: LensCameraDimension): LensProfile = when (dimension) {
        LensCameraDimension.MAIN -> MAIN
        LensCameraDimension.ULTRA_WIDE -> ULTRA_WIDE
        LensCameraDimension.MACRO -> MACRO
    }

    /** 三套维度"可分别配置"的默认配置表（默认全部 enabled=false，由用户显式开启）。 */
    fun defaultSettings(): Map<LensCameraDimension, LensDistortionConfig> =
        LensCameraDimension.entries.associateWith { dimension ->
            val preset = presetFor(dimension)
            LensDistortionConfig(
                enabled = false,
                dimension = dimension,
                strength = 1f,
                centerX = preset.centerX,
                centerY = preset.centerY,
                cropScale = 1f,
            )
        }
}
