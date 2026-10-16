package com.hinnka.mycamera.enhance

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 卡通 / 油画滤镜：快速双边平滑 → 色域量化 → 边缘压暗。
 *
 * 思路参考多个低 star（<300）开源「cartoon / oil painting」实现（bilateral + quantize +
 * edge overlay 的公开思路），**仅参考思路、不采任何代码**；双边平滑复用本模块
 * [BilateralFilterProcessor]（其依据为 opencv/opencv Apache-2.0 的双边滤波思路）。
 *
 * 分片：双边平滑与最终逐像素合成都按**行带**分片（[bandHeight] + [onChunk]），
 * 量化与 Sobel 为 O(1)/像素的一次性遍历，各算一个分片。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object OilPaintFilter {

    /**
     * 按配置渲染油画/卡通效果，返回新数组（不改入参）。
     *
     * @param bandHeight 行带高度；<= 0 表示不分片（小图/单测）。
     * @param onChunk 每个分片完成后回调（集成层传 `{ job.checkYield() }`）。
     */
    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: OilPaintConfig,
        bandHeight: Int = 0,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = OilPaintConfig.clamp(config)
        val out = IntArray(pixels.size)
        if (clamped.paintMix <= 0f) {
            pixels.copyInto(out)
            onChunk?.invoke()
            return out
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val band = BilateralFilterProcessor.bandHeightOf(bandHeight, height)

        // ① 双边平滑（分片）
        val smoothed = PlanarRgb(width, height)
        val bilateralConfig = BilateralConfig(
            radius = clamped.bilateralRadius,
            sigmaSpace = clamped.bilateralRadius * 2f + 1f,
            sigmaColor = clamped.sigmaColor,
            strength = 1f,
        )
        val clampedBilateral = BilateralConfig.clamp(bilateralConfig)
        for (rowStart in 0 until height step band) {
            BilateralFilterProcessor.filterRows(
                image, clampedBilateral, smoothed, rowStart, minOf(rowStart + band, height),
            )
            onChunk?.invoke()
        }

        // ② 色域量化（O(n) 一次遍历，算一个分片）
        val levels = clamped.levels.coerceAtLeast(2)
        val steps = (levels - 1).toFloat()
        for (c in 0 until 3) {
            val channel = smoothed.channel(c)
            for (i in channel.indices) {
                channel[i] = (channel[i].coerceIn(0f, 1f) * steps).roundToInt() / steps
            }
        }
        onChunk?.invoke()

        // ③ 边缘压暗 + 混合（分片）
        val luma = FloatArray(smoothed.count) { smoothed.luma(it) }
        val gradient = BoxBlur.sobelMagnitude(luma, width, height)
        val n = minOf(image.count, width * height)
        for (rowStart in 0 until height step band) {
            val rowEnd = minOf(rowStart + band, height)
            for (i in (rowStart * width) until minOf(rowEnd * width, n)) {
                val g = if (gradient[i].isFinite()) gradient[i] else 0f
                val edge = if (g < clamped.edgeThreshold) {
                    0f
                } else {
                    min(1f, (g - clamped.edgeThreshold) * clamped.edgeStrength)
                }
                val shade = (1f - edge).coerceIn(0f, 1f)
                for (c in 0 until 3) {
                    val stylized = (smoothed.channel(c)[i] * shade).coerceIn(0f, 1f)
                    val source = image.channel(c)
                    source[i] = PlanarRgb.mix(source[i], stylized, clamped.paintMix)
                }
            }
            onChunk?.invoke()
        }
        image.toArgb(out, pixels)
        return out
    }
}
