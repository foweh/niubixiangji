package com.hinnka.mycamera.scenerecog

import kotlin.math.abs

/**
 * 场景特征（轻量规则分类的输入）。
 *
 * 全部为「一次遍历即可得到」的直方图 / 统计量，避免引入 TFLite 重依赖（保轻量）。
 * 纯数据 + 纯函数，可 JVM 单测。
 *
 * @param sampleCount 参与统计的像素数
 * @param meanLuma 平均亮度（Rec.709，0..1）
 * @param warmth 冷暖平衡 `(meanR - meanB) / (meanR + meanB)` ∈ [-1, 1]（>0 偏暖）
 * @param saturation 平均 HSV 饱和度（0..1）
 * @param edgeDensity 平均相邻亮度梯度（0..1，纹理密集/文字画面更高）
 * @param skinFraction 疑似肤色像素占比（YCbCr 经典阈值，0..1）
 * @param darkFraction luma < [DARK_LUMA] 的占比
 * @param brightFraction luma > [BRIGHT_LUMA] 的占比
 * @param textScore 文本画面代理分（高局部对比 + 低饱和 + 亮度双峰，0..1）
 */
data class SceneFeatures(
    val sampleCount: Int = 0,
    val meanLuma: Float = 0f,
    val warmth: Float = 0f,
    val saturation: Float = 0f,
    val edgeDensity: Float = 0f,
    val skinFraction: Float = 0f,
    val darkFraction: Float = 0f,
    val brightFraction: Float = 0f,
    val textScore: Float = 0f,
) {
    val isUsable: Boolean get() = sampleCount > 0

    companion object {
        const val DARK_LUMA = 0.15f
        const val BRIGHT_LUMA = 0.85f

        /** 文本代理分内部参考值（达到该值视为满分）。 */
        const val TEXT_EDGE_REFERENCE = 0.35f
        const val TEXT_SATURATION_REFERENCE = 0.35f

        const val TEXT_EDGE_WEIGHT = 0.45f
        const val TEXT_CHROMA_WEIGHT = 0.35f
        const val TEXT_BIMODAL_WEIGHT = 0.20f

        val EMPTY = SceneFeatures()
    }
}

/**
 * 特征提取（纯 Kotlin；不依赖 Bitmap，便于单测）。
 *
 * 只做一次网格遍历：
 *  - 累计 R/G/B、亮度、饱和度、肤色、暗/亮占比；
 *  - 同时用「右邻 / 下邻」亮度差累计梯度（步长对齐采样网格，避免重复统计）。
 */
object SceneFeatureExtractor {

    /** Rec.709 亮度权重。 */
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    /**
     * @param pixels ARGB_8888 像素（`Bitmap.getPixels` 输出），长度应 ≥ width * height
     * @param step 采样步长（≥1；>1 时跳采样，统计口径不变）
     */
    fun extract(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 1,
    ): SceneFeatures {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return SceneFeatures.EMPTY
        val stride = step.coerceAtLeast(1)
        val size = pixels.size

        var count = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumLuma = 0.0
        var sumSaturation = 0.0
        var darkCount = 0
        var brightCount = 0
        var skinCount = 0
        var gradientSum = 0.0
        var gradientCount = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * width + x
                if (index >= size) break
                val pixel = pixels[index]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                count++
                sumR += r
                sumG += g
                sumB += b

                val luma = (LUMA_R * r + LUMA_G * g + LUMA_B * b) / 255f
                sumLuma += luma
                if (luma < SceneFeatures.DARK_LUMA) darkCount++
                if (luma > SceneFeatures.BRIGHT_LUMA) brightCount++

                val maxChannel = maxOf(r, maxOf(g, b))
                val minChannel = minOf(r, minOf(g, b))
                sumSaturation += if (maxChannel <= 0) 0.0 else (maxChannel - minChannel).toDouble() / maxChannel

                if (isSkinTone(r, g, b)) skinCount++

                val rightIndex = if (x + stride < width) y * width + x + stride else -1
                if (rightIndex in 0 until size) {
                    gradientSum += abs(lumaOf(pixels[rightIndex]) - luma).toDouble()
                    gradientCount++
                }
                val downIndex = if (y + stride < height) (y + stride) * width + x else -1
                if (downIndex in 0 until size) {
                    gradientSum += abs(lumaOf(pixels[downIndex]) - luma).toDouble()
                    gradientCount++
                }

                x += stride
            }
            y += stride
        }

        if (count == 0) return SceneFeatures.EMPTY

        val meanLuma = (sumLuma / count).toFloat()
        val meanR = sumR / count
        val meanB = sumB / count
        val warmthSum = meanR + meanB
        val warmth = if (warmthSum <= 0.0) 0f else ((meanR - meanB) / warmthSum).toFloat()
        val saturation = (sumSaturation / count).toFloat()
        val edgeDensity = if (gradientCount > 0) (gradientSum / gradientCount).toFloat() else 0f
        val skinFraction = skinCount.toFloat() / count
        val darkFraction = darkCount.toFloat() / count
        val brightFraction = brightCount.toFloat() / count

        val edgeTerm = (edgeDensity / SceneFeatures.TEXT_EDGE_REFERENCE).coerceIn(0f, 1f)
        val chromaTerm = (1f - saturation / SceneFeatures.TEXT_SATURATION_REFERENCE).coerceIn(0f, 1f)
        val bimodalTerm = (4f * darkFraction * brightFraction).coerceIn(0f, 1f)
        val textScore = (
            SceneFeatures.TEXT_EDGE_WEIGHT * edgeTerm +
                SceneFeatures.TEXT_CHROMA_WEIGHT * chromaTerm +
                SceneFeatures.TEXT_BIMODAL_WEIGHT * bimodalTerm
            ).coerceIn(0f, 1f)

        return SceneFeatures(
            sampleCount = count,
            meanLuma = meanLuma,
            warmth = warmth,
            saturation = saturation,
            edgeDensity = edgeDensity,
            skinFraction = skinFraction,
            darkFraction = darkFraction,
            brightFraction = brightFraction,
            textScore = textScore,
        )
    }

    /** 单像素亮度（0..1）。 */
    fun lumaOf(pixel: Int): Float {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (LUMA_R * r + LUMA_G * g + LUMA_B * b) / 255f
    }

    /**
     * 经典 YCbCr 肤色判定（公开通用阈值，非任何私有实现）：
     * Cb ∈ [77,127]、Cr ∈ [133,173]、Y > 40。
     */
    fun isSkinTone(r: Int, g: Int, b: Int): Boolean {
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return y > 40.0 && cb >= 77.0 && cb <= 127.0 && cr >= 133.0 && cr <= 173.0
    }
}
