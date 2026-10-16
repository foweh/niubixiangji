package com.hinnka.mycamera.galleryedit

import kotlin.math.roundToInt

/**
 * 亮度 / 对比度 / 饱和度 / 色温的**纯 Kotlin 像素运算核心**（不依赖 Android 框架，可直接 JVM 单测）。
 *
 * 全部参数区间 -100..100，默认 0，越界 clamp（见 [GePixelAdjustParams.clamp]）。
 * 数学模型按"亮度 → 对比度 → 饱和度 → 色温"顺序串行作用于 0..255 色阶：
 * - 亮度：整体加减色阶（±100 → ±[GeEditRanges.BRIGHTNESS_MAX_LEVELS]）；
 * - 对比度：以中灰 [GeEditRanges.TONE_PIVOT] 为枢轴做线性拉伸（±100 → 0.5x..1.5x）；
 * - 饱和度：以 Rec.709 亮度权重把像素向灰度/偏离灰度插值（-100 全灰，+100 双倍饱和）；
 * - 色温：正值暖（R 增益 / B 衰减），负值冷（反向），最大 ±25% 单通道增益。
 *
 * 组合结果表达成 4x5 颜色矩阵（0..255 定义域，与 Android ColorMatrix / Canvas 对齐），
 * 因此同一个数学模型既能走纯 Kotlin 逐像素循环，也能交给 Canvas 硬件绘制。
 */
data class GePixelAdjustParams(
    val brightness: Int = GeEditRanges.ADJUST_DEFAULT,
    val contrast: Int = GeEditRanges.ADJUST_DEFAULT,
    val saturation: Int = GeEditRanges.ADJUST_DEFAULT,
    val temperature: Int = GeEditRanges.ADJUST_DEFAULT,
) {
    /** 四项全为 0 时可直接跳过整条像素管线。 */
    val isIdentity: Boolean
        get() = brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0

    /** 越界收敛；返回修正后的参数与被修正项。 */
    fun clamp(): Pair<GePixelAdjustParams, GeClampReport> {
        val corrections = buildList {
            GeClampReport.of("brightness", brightness.toDouble(), GeEditRanges.clampAdjust(brightness).toDouble())
                ?.let(::add)
            GeClampReport.of("contrast", contrast.toDouble(), GeEditRanges.clampAdjust(contrast).toDouble())
                ?.let(::add)
            GeClampReport.of("saturation", saturation.toDouble(), GeEditRanges.clampAdjust(saturation).toDouble())
                ?.let(::add)
            GeClampReport.of("temperature", temperature.toDouble(), GeEditRanges.clampAdjust(temperature).toDouble())
                ?.let(::add)
        }
        return copy(
            brightness = GeEditRanges.clampAdjust(brightness),
            contrast = GeEditRanges.clampAdjust(contrast),
            saturation = GeEditRanges.clampAdjust(saturation),
            temperature = GeEditRanges.clampAdjust(temperature),
        ) to GeClampReport(corrections)
    }
}

/** 4x5 颜色矩阵（行主序 20 元素，0..255 定义域，与 Android ColorMatrix 一致）。 */
class GeColorMatrix(val values: FloatArray) {

    init {
        require(values.size == MATRIX_SIZE) { "color matrix must have $MATRIX_SIZE elements" }
    }

    /** 单位矩阵（无效果）。 */
    fun isIdentity(): Boolean = IDENTITY_VALUES.indices.all { GeEditRanges.nearlyEqual(values[it], IDENTITY_VALUES[it]) }

    /** 直接作用于单个 RGB 分量（纯 Kotlin 逐像素路径），结果 clamp 到 0..255。 */
    fun applyToChannel(input: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        require(input.size >= 3) { "input must have at least 3 channels" }
        val r = input[0]
        val g = input[1]
        val b = input[2]
        out[0] = (values[0] * r + values[1] * g + values[2] * b + values[4]).coerceIn(0f, 255f)
        out[1] = (values[5] * r + values[6] * g + values[7] * b + values[9]).coerceIn(0f, 255f)
        out[2] = (values[10] * r + values[11] * g + values[12] * b + values[14]).coerceIn(0f, 255f)
        return out
    }

    /** 便捷版：整数 RVG 就地运算。 */
    fun applyToRgb(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val out = applyToChannel(
            floatArrayOf(
                r.coerceIn(0, 255).toFloat(),
                g.coerceIn(0, 255).toFloat(),
                b.coerceIn(0, 255).toFloat(),
            )
        )
        return Triple(out[0].roundToInt(), out[1].roundToInt(), out[2].roundToInt())
    }

    /**
     * 矩阵组合：返回"先应用 [this]，再应用 [after]"的等价矩阵。
     *
     * 4x5 矩阵的仿射语义：out = M * [r, g, b, 1]，因此 after ∘ this 的乘法为
     * `after × this`（这里把 4x5 视作 5x5 且最后一行 [0,0,0,0,1] 参与运算）。
     */
    fun compose(after: GeColorMatrix): GeColorMatrix {
        val lhs = after.values
        val rhs = values
        val out = FloatArray(MATRIX_SIZE)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += lhs[row * 5 + k] * rhs[k * 5 + col]
                }
                // 第 5 列（常数项）需补上 after 自身的常数项
                if (col == 4) sum += lhs[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return GeColorMatrix(out)
    }

    fun copy(): GeColorMatrix = GeColorMatrix(values.copyOf())

    companion object {
        const val MATRIX_SIZE = 20

        /** 单位矩阵元素。 */
        val IDENTITY_VALUES = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        fun identity(): GeColorMatrix = GeColorMatrix(IDENTITY_VALUES.copyOf())
    }
}

object GePixelAdjustMath {

    /** Rec.709 亮度权重（与 lut/ 管线保持一致的观感基准）。 */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** 亮度矩阵：整体加减色阶。 */
    fun brightnessMatrix(brightness: Int): GeColorMatrix {
        val offset = GeEditRanges.clampAdjust(brightness) / 100f * GeEditRanges.BRIGHTNESS_MAX_LEVELS
        val v = GeColorMatrix.IDENTITY_VALUES.copyOf()
        v[4] = offset
        v[9] = offset
        v[14] = offset
        return GeColorMatrix(v)
    }

    /** 对比度矩阵：绕 [GeEditRanges.TONE_PIVOT] 的中灰枢轴线性拉伸。 */
    fun contrastMatrix(contrast: Int): GeColorMatrix {
        val gain = 1f + GeEditRanges.clampAdjust(contrast) / 100f * GeEditRanges.CONTRAST_MAX_GAIN
        val offset = GeEditRanges.TONE_PIVOT * (1f - gain)
        val v = GeColorMatrix.IDENTITY_VALUES.copyOf()
        v[0] = gain; v[4] = offset
        v[6] = gain; v[9] = offset
        v[12] = gain; v[14] = offset
        return GeColorMatrix(v)
    }

    /** 饱和度矩阵：向 Rec.709 亮度灰度插值；-100 全灰，+100 双倍饱和。 */
    fun saturationMatrix(saturation: Int): GeColorMatrix {
        val s = 1f + GeEditRanges.clampAdjust(saturation) / 100f * GeEditRanges.SATURATION_MAX_GAIN
        val invSat = 1f - s
        return GeColorMatrix(
            floatArrayOf(
                invSat * LUMA_R + s, invSat * LUMA_G, invSat * LUMA_B, 0f, 0f,
                invSat * LUMA_R, invSat * LUMA_G + s, invSat * LUMA_B, 0f, 0f,
                invSat * LUMA_R, invSat * LUMA_G, invSat * LUMA_B + s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }

    /** 色温矩阵：正暖（提 R 压 B）负冷（压 R 提 B），单通道增益最大 ±25%。 */
    fun temperatureMatrix(temperature: Int): GeColorMatrix {
        val gain = GeEditRanges.clampAdjust(temperature) / 100f * GeEditRanges.TEMPERATURE_MAX_GAIN
        val v = GeColorMatrix.IDENTITY_VALUES.copyOf()
        v[0] = 1f + gain
        v[12] = 1f - gain
        return GeColorMatrix(v)
    }

    /**
     * 组合四项调节为单个矩阵：亮度 → 对比度 → 饱和度 → 色温。
     *
     * @return 合计矩阵；输入为默认（全 0）时返回单位矩阵，调用方可用 [GeColorMatrix.isIdentity] 跳过。
     */
    fun buildMatrix(params: GePixelAdjustParams): GeColorMatrix {
        val safe = params.clamp().first
        if (safe.isIdentity) return GeColorMatrix.identity()
        var result = brightnessMatrix(safe.brightness)
        result = result.compose(contrastMatrix(safe.contrast))
        result = result.compose(saturationMatrix(safe.saturation))
        result = result.compose(temperatureMatrix(safe.temperature))
        return result
    }

    /** 逐像素应用（纯 Kotlin 路径，供无法走 Canvas 的场景兜底）。 */
    fun applyToRgb(params: GePixelAdjustParams, r: Int, g: Int, b: Int): Triple<Int, Int, Int> =
        buildMatrix(params).applyToRgb(r, g, b)
}
