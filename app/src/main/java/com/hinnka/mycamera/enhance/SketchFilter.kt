package com.hinnka.mycamera.enhance

import kotlin.math.min

/**
 * 素描滤镜：Sobel 边缘 → 阈值门控 → 白纸黑线（可反相 / 可混原色）。
 *
 * 思路参考 opencv/opencv（Apache-2.0，Star≈91191，已核实）`imgproc` 的
 * `Sobel` + `threshold` 组合（pencil-sketch 教程的经典做法），不采其代码。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object SketchFilter {

    /** 按配置渲染素描效果，返回新数组（不改入参）。 */
    fun process(pixels: IntArray, width: Int, height: Int, config: SketchConfig): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = SketchConfig.clamp(config)
        val out = IntArray(pixels.size)
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val lines = lineIntensity(image, width, height, clamped)
        val n = minOf(image.count, width * height)
        for (i in 0 until n) {
            // 白纸黑线：纸 = 1，线条强度越大越黑
            val paper = (1f - lines[i]).coerceIn(0f, 1f)
            val value = if (clamped.invert) 1f - paper else paper
            if (clamped.colorMix > 0f) {
                image.r[i] = PlanarRgb.mix(value, image.r[i], clamped.colorMix)
                image.g[i] = PlanarRgb.mix(value, image.g[i], clamped.colorMix)
                image.b[i] = PlanarRgb.mix(value, image.b[i], clamped.colorMix)
            } else {
                image.r[i] = value
                image.g[i] = value
                image.b[i] = value
            }
        }
        image.toArgb(out, pixels)
        return out
    }

    /**
     * 线条强度（0 = 白纸，1 = 最深线条）：`min(1, sobel·edgeStrength)`，
     * 但 sobel 小于 [SketchConfig.threshold] 的位置强制归 0（抑制噪点成线）。
     */
    internal fun lineIntensity(
        image: PlanarRgb,
        width: Int,
        height: Int,
        config: SketchConfig,
    ): FloatArray {
        val n = width * height
        val luma = FloatArray(n) { image.luma(it) }
        val gradient = BoxBlur.sobelMagnitude(luma, width, height)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val g = if (gradient[i].isFinite()) gradient[i] else 0f
            out[i] = if (g < config.threshold) 0f else min(1f, g * config.edgeStrength)
        }
        return out
    }
}
