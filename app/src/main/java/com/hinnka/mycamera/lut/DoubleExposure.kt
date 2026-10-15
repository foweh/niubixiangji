package com.hinnka.mycamera.lut

/**
 * 双重曝光（Double Exposure）—— OldRoll 式两帧叠加 blend。
 *
 * 复古编辑工具落地对照（参考 OldRoll，优先复用现有管线）：
 *  - 分通道色调曲线：已由 ColorRecipe.kt 的 red/orange/yellow/green/cyan/blue/purple/magenta
 *    各自 Hue/Chroma/Lightness + skinHue/Chroma/Lightness 提供（per-hue 调色，数据驱动），
 *    经 LutImageProcessor 实时应用——**复用，不重造**。
 *  - 高光/阴影 split-tone：已由 ColorRecipe.kt 的 gradingShadow/Midtone/Highlight Hue+Amount
 *    + ThreeWayColorGradingShader（shadow/midtone/highlight 三带加权）提供——**复用**。
 *  - 柔焦 soft glow：已由 softLight / bloom + HighlightDiffusionGl 提供——**复用**。
 *  - 双重曝光：缺口。本文件给出可数据驱动的 blend 模式与公式，供照片/画廊编辑管线
 *    （processor/ 与 ui/，归其它子代理）按名接入；UI 入口由 B1 在 FilterManagementScreen 数据驱动呈现。
 *
 * 公式均在 display-referred [0,1] 域，b = base(底图), t = top(叠图), weight = 0..1。
 * 纯函数、无 GL 依赖、可单测；接入管线时可逐像素用 GLSL 等价实现。
 */
enum class DoubleExposureBlend(val displayName: String) {
    /** 线性相加（经典双重曝光：两图亮度叠加，高光溢出）。 */
    ADD("Add"),
    /** 滤色 screen：亮部叠加、暗部保留底图。 */
    SCREEN("Screen"),
    /** 变亮 lighten：逐像素取较亮者。 */
    LIGHTEN("Lighten"),
    /** 正片叠底 multiply：压暗、留细节。 */
    MULTIPLY("Multiply"),
    /** 平均 average：两图等权混合。 */
    AVERAGE("Average"),
}

object DoubleExposure {

    /** 单通道 blend 公式（[0,1] 域）。 */
    fun blendChannel(base: Float, top: Float, mode: DoubleExposureBlend): Float {
        return when (mode) {
            DoubleExposureBlend.ADD -> base + top
            DoubleExposureBlend.SCREEN -> 1f - (1f - base) * (1f - top)
            DoubleExposureBlend.LIGHTEN -> maxOf(base, top)
            DoubleExposureBlend.MULTIPLY -> base * top
            DoubleExposureBlend.AVERAGE -> (base + top) * 0.5f
        }.coerceIn(0f, 1f)
    }

    /**
     * 逐通道 blend，再按 weight 在底图与叠图结果间混合（0=只底图，1=完全叠图效果）。
     * base/top 为长度 3 的 RGB（0..1）。返回新 RGB。
     */
    fun blend(
        base: FloatArray,
        top: FloatArray,
        mode: DoubleExposureBlend,
        weight: Float,
    ): FloatArray {
        val w = weight.coerceIn(0f, 1f)
        return FloatArray(3) { i ->
            val mixed = blendChannel(base[i], top[i], mode)
            (base[i] * (1f - w) + mixed * w).coerceIn(0f, 1f)
        }
    }
}
