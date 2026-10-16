package com.hinnka.mycamera.longexposure

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 工作分辨率的线性 RGB 平面（交错存储）。
 *
 * 约定：`samples` 长度 = `width * height * 3`，每样本为线性域 12bit（0..[SAMPLE_MAX]）。
 * 这是长曝光模块内部唯一的帧表示，便于：
 *  - 流式累加（一次只驻留 1 帧 + 1 个累加器）；
 *  - 分片落盘（[com.hinnka.mycamera.longexposure.LongExposureJobRunner.FrameSource] 可逐帧读回）；
 *  - 纯 JVM 单测（不依赖 android.graphics.Bitmap）。
 */
class LinearRgbPlane(
    val width: Int,
    val height: Int,
    val samples: ShortArray,
) {
    init {
        require(width > 0 && height > 0) { "invalid plane size: ${width}x$height" }
        require(samples.size == width * height * 3) {
            "sample count ${samples.size} != ${width}x$height x3"
        }
    }

    val sampleCount: Int get() = samples.size
    val pixelCount: Int get() = width * height

    /** 取第 [px] 个像素的归一化亮度（0..1，Rec.709 权重）。 */
    fun luminanceAt(px: Int): Float {
        val base = px * 3
        return (
            0.2126f * samples[base] +
                0.7152f * samples[base + 1] +
                0.0722f * samples[base + 2]
            ) / SAMPLE_MAX.toFloat()
    }

    fun copy(): LinearRgbPlane = LinearRgbPlane(width, height, samples.copyOf())

    companion object {
        /** 12bit 线性上限。 */
        const val SAMPLE_MAX = 4095

        /** 分配一块清零平面。 */
        fun create(width: Int, height: Int): LinearRgbPlane =
            LinearRgbPlane(width, height, ShortArray(width * height * 3))

        /** 用给定样本构造（校验长度）。 */
        fun of(width: Int, height: Int, samples: ShortArray): LinearRgbPlane =
            LinearRgbPlane(width, height, samples)
    }
}

/**
 * 线性 RGB 重采样（盒平均下采样 + 最近邻上采样）。
 *
 * 用于「内存守卫失败时降工作分辨率」（PATTERN_SPEC §4：降级而非失败）。
 */
object LongExposureResampler {

    /**
     * 盒平均下采样。目标尺寸不小于 1；相同尺寸时直接返回副本。
     * 支持非整数倍缩放（按面积加权，避免摩尔纹跳变）。
     */
    fun downsample(src: ShortArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ShortArray {
        require(src.size == srcW * srcH * 3) { "invalid source plane" }
        val w = dstW.coerceAtLeast(1)
        val h = dstH.coerceAtLeast(1)
        if (w == srcW && h == srcH) return src.copyOf()
        val out = ShortArray(w * h * 3)
        val scaleX = srcW.toDouble() / w
        val scaleY = srcH.toDouble() / h
        for (y in 0 until h) {
            val y0 = (y * scaleY).toInt().coerceIn(0, srcH - 1)
            val y1 = (((y + 1) * scaleY).toInt()).coerceIn(y0 + 1, srcH)
            for (x in 0 until w) {
                val x0 = (x * scaleX).toInt().coerceIn(0, srcW - 1)
                val x1 = (((x + 1) * scaleX).toInt()).coerceIn(x0 + 1, srcW)
                var r = 0L
                var g = 0L
                var b = 0L
                var count = 0
                for (sy in y0 until y1) {
                    var base = (sy * srcW + x0) * 3
                    for (sx in x0 until x1) {
                        r += src[base]
                        g += src[base + 1]
                        b += src[base + 2]
                        base += 3
                        count++
                    }
                }
                val inv = 1.0 / count.coerceAtLeast(1)
                val o = (y * w + x) * 3
                out[o] = (r * inv).roundToInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
                out[o + 1] = (g * inv).roundToInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
                out[o + 2] = (b * inv).roundToInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
            }
        }
        return out
    }

    /** 最近邻放大（仅用于预览放大显示，不参与成片）。 */
    fun upsampleNearest(src: ShortArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ShortArray {
        require(src.size == srcW * srcH * 3) { "invalid source plane" }
        val w = dstW.coerceAtLeast(1)
        val h = dstH.coerceAtLeast(1)
        val out = ShortArray(w * h * 3)
        for (y in 0 until h) {
            val sy = (y.toLong() * srcH / h).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until w) {
                val sx = (x.toLong() * srcW / w).toInt().coerceIn(0, srcW - 1)
                val s = (sy * srcW + sx) * 3
                val o = (y * w + x) * 3
                out[o] = src[s]
                out[o + 1] = src[s + 1]
                out[o + 2] = src[s + 2]
            }
        }
        return out
    }
}

/**
 * 色温 → RGB 增益（光源画笔色温的落地实现）。
 *
 * 算法：Tanner Helland 的黑体色温近似（后续被 Neil Bartlett 等广泛引用的经典实现）。
 * 说明：该近似面向 sRGB 显示值，用于**创作性色偏**足够；本模块只取其**相对通道比例**，
 * 并归一化到「最大通道 = 1」，因此**只会衰减通道、不会把高光推爆**。
 *
 * 来源标注：公式为公开经典算法（非拷贝某个仓库代码），
 * 参考实现：https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html
 */
object ColorTemperatureGains {

    /** 色温下限/上限（Kelvin），与 [LightPaintingConfig] 的 clamp 范围一致。 */
    const val MIN_KELVIN = 1000
    const val MAX_KELVIN = 12000

    /**
     * 是否"中性"（无需做色偏），用于跳过整幅增益遍历。
     *
     * 中性判定：6500K（日光中性）+ tilt 0 + gain 1。
     * 注意：**3200K 不是中性**——它是刻意的暖调画笔色温，会被真正应用。
     */
    fun isNeutral(kelvin: Int, tint: Float, gain: Float): Boolean =
        kelvin == NEUTRAL_KELVIN && tint == 0f && gain == 1f

    /** 中性色温（日光的近似，6500K）。 */
    const val NEUTRAL_KELVIN = 6500

    /**
     * 计算归一化通道增益（最大通道 = 1）。
     *
     * @param kelvin 色温（越低调越暖/偏橙，越高越冷/偏蓝）。
     * @param tint 绿-品红偏移，范围 -1..1；正 = 偏品红（R/B 增、G 减），负 = 偏绿。
     * @return 长度 3 的增益数组（R、G、B），每项 ∈ [0,1]，至少一项 = 1。
     */
    fun gainsFor(kelvin: Int, tint: Float): FloatArray {
        val rgb = kelvinToRgb(kelvin)
        val t = if (tint.isFinite()) tint.coerceIn(-1f, 1f) else 0f
        if (t != 0f) {
            val magenta = t
            rgb[0] *= 1f + 0.25f * magenta
            rgb[1] *= 1f - 0.50f * magenta
            rgb[2] *= 1f + 0.25f * magenta
        }
        var max = 0f
        for (c in rgb) if (c > max) max = c
        if (max <= 0f) return floatArrayOf(1f, 1f, 1f)
        // 归一化后最大通道恰为 1.0 → 增益只会衰减通道，不会推爆高光。
        for (i in rgb.indices) rgb[i] = (rgb[i] / max).coerceIn(0f, 1f)
        return rgb
    }

    /**
     * Tanner Helland 黑体近似：返回 0..1 的 R/G/B。
     * 非法/NaN 色温回退到 6500K（中性）。
     */
    fun kelvinToRgb(kelvin: Int): FloatArray {
        val k = if (kelvin in MIN_KELVIN..MAX_KELVIN) kelvin else 6500
        val temp = (k / 100.0).coerceIn(1.0, 400.0)

        val red = if (temp <= 66.0) {
            255.0
        } else {
            329.698727446 * (temp - 60.0).pow(-0.1332047592)
        }
        val green = if (temp <= 66.0) {
            99.4708025861 * ln(temp) - 161.1195681661
        } else {
            288.1221695283 * (temp - 60.0).pow(-0.0755148492)
        }
        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 0.0
            else -> 138.5177312231 * ln(temp - 10.0) - 305.0447927307
        }
        return floatArrayOf(
            (red / 255.0).coerceIn(0.0, 1.0).toFloat(),
            (green / 255.0).coerceIn(0.0, 1.0).toFloat(),
            (blue / 255.0).coerceIn(0.0, 1.0).toFloat(),
        )
    }

    /**
     * 把通道增益应用到线性 RGB 平面（返回新数组，不修改入参）。
     * 结果 clamp 到 [0, [LinearRgbPlane.SAMPLE_MAX]]。
     */
    fun applyGains(samples: ShortArray, gains: FloatArray): ShortArray {
        require(gains.size == 3) { "gains must be length 3" }
        val out = ShortArray(samples.size)
        for (i in samples.indices step 3) {
            out[i] = scale(samples[i], gains[0])
            out[i + 1] = scale(samples[i + 1], gains[1])
            out[i + 2] = scale(samples[i + 2], gains[2])
        }
        return out
    }

    private fun scale(sample: Short, gain: Float): Short {
        val g = if (gain.isFinite()) gain.coerceAtLeast(0f) else 1f
        return (sample * g).roundToInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
    }
}
