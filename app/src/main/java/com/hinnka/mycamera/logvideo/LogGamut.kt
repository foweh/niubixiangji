package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.raw.ColorSpace

/**
 * Log 曲线对应的原生色域（白点统一 D65）。
 *
 * 为什么单独定义：工程的 [ColorSpace] 覆盖了 V-Gamut / AWG4 / BT.2020 / AppleLog2 / RED，
 * 但**缺少 ARRI Wide Gamut 3（AWG3）**——而 LogC3 的原生色域正是 AWG3。
 * 为了让本模块在补丁生效前后都能工作：
 *  - [projectColorSpace] 为可用的工程枚举（直接复用，零自研）；
 *  - 缺少对应项时给出明确色度坐标（本模块自持），并把工程枚举补充交给补丁
 *    （见 `delivery/patches/logvideo_patch.md` 的 `ColorSpace.ARRI3` 条目）。
 *
 * 色度数据来源：ARRI ALEXA / AWG3·AWG4 官方规格、Panasonic V-Gamut 规格、
 * RED WIDE GAMUT 规格、Apple Log 规格（均为公开规格文档，本模块只按其数值计算）。
 */
enum class LogGamut(
    val xr: Float, val yr: Float,
    val xg: Float, val yg: Float,
    val xb: Float, val yb: Float,
    /** 工程既有 [ColorSpace] 中的同规格项；null = 工程枚举缺失（补丁新增后由映射表接入）。 */
    val projectColorSpace: ColorSpace?,
) {
    /** ARRI Wide Gamut 3：LogC3 原生色域。工程 ColorSpace 目前只有 ARRI4 → 补丁新增 ARRI3 后回填。 */
    AWRI_WIDE_GAMUT_3(0.6840f, 0.3130f, 0.2210f, 0.8480f, 0.0861f, -0.1020f, null),

    /** ARRI Wide Gamut 4：LogC4 原生色域。 */
    AWRI_WIDE_GAMUT_4(0.7347f, 0.2653f, 0.1424f, 0.8576f, 0.0991f, -0.0308f, ColorSpace.ARRI4),

    /** Panasonic V-Gamut：V-Log / V-Log L 原生色域。 */
    V_GAMUT(0.730f, 0.280f, 0.165f, 0.840f, 0.100f, -0.030f, ColorSpace.VGamut),

    /** ITU-R BT.2020：HLG / F-Log2 / L-Log 的原生（或容器）色域。 */
    BT2020(0.708f, 0.292f, 0.170f, 0.797f, 0.131f, 0.046f, ColorSpace.BT2020),

    /** Apple Log（Log 2）原生色域。 */
    APPLE_LOG(0.7250f, 0.3010f, 0.2210f, 0.8140f, 0.0680f, -0.0760f, ColorSpace.AppleLog2),

    /** RED Wide Gamut RGB：Log3G10 原生色域。 */
    RED_WIDE_GAMUT(0.780308f, 0.304253f, 0.121595f, 1.493994f, 0.095612f, -0.084589f, ColorSpace.RED),

    /** Rec.709 / sRGB：Log→Rec709 的目标（输出）色域。 */
    REC709(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f, ColorSpace.SRGB)

    ;

    /** 主/次色度数组，便于矩阵计算（顺序 R,G,B）。 */
    val primaries: FloatArray
        get() = floatArrayOf(xr, yr, xg, yg, xb, yb)

    companion object {
        /** D65 白点（全部 Log 曲线的白点，统一使用）。 */
        const val WHITE_X = 0.3127f
        const val WHITE_Y = 0.3290f

        /** 由工程 [ColorSpace] 反查本模块色域；无对应项返回 null（调用方走自持色度）。 */
        fun fromProjectColorSpace(colorSpace: ColorSpace?): LogGamut? {
            if (colorSpace == null) return null
            return entries.firstOrNull { it.projectColorSpace == colorSpace }
        }
    }
}
