package com.hinnka.mycamera.aiwb

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 轻量图像统计（灰世界 + 高光估计的输入）。
 * 纯数据 + 纯函数，可 JVM 单测。
 *
 * @param sampleCount 参与统计的像素数
 * @param meanR/meanG/meanB 通道均值（0..1）
 * @param highlightCount 命中高光阈值的像素数
 * @param highlightR/highlightG/highlightB 高光像素通道均值（0..1；无高光时为 0）
 */
data class AiWbStats(
    val sampleCount: Int = 0,
    val meanR: Float = 0f,
    val meanG: Float = 0f,
    val meanB: Float = 0f,
    val highlightCount: Int = 0,
    val highlightR: Float = 0f,
    val highlightG: Float = 0f,
    val highlightB: Float = 0f,
) {
    /** 是否可用于估计（通道均值必须为正，否则增益会发散）。 */
    val isUsable: Boolean get() = sampleCount > 0 && meanR > 0f && meanG > 0f && meanB > 0f

    /** 全局平均亮度（Rec.709 权重）。 */
    val meanLuma: Float get() = LUMA_R * meanR + LUMA_G * meanG + LUMA_B * meanB

    companion object {
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f

        val EMPTY = AiWbStats()

        /** 由已知均值直接构造（供远端口径 / 单测使用）。 */
        fun fromAverages(
            meanR: Float,
            meanG: Float,
            meanB: Float,
            highlightR: Float = 0f,
            highlightG: Float = 0f,
            highlightB: Float = 0f,
            sampleCount: Int = 1,
            highlightCount: Int = 0,
        ) = AiWbStats(
            sampleCount = sampleCount,
            meanR = meanR,
            meanG = meanG,
            meanB = meanB,
            highlightCount = highlightCount,
            highlightR = highlightR,
            highlightG = highlightG,
            highlightB = highlightB,
        )
    }
}

/**
 * 从 ARGB 像素数组统计灰世界 + 高光均值。
 *
 * @param pixels ARGB_8888 像素（Bitmap.getPixels 输出）
 * @param highlightLumaThreshold 高光判定阈值（luma ≥ 阈值记入高光集）
 * @param stride 采样步长（≥1；>1 时跳采样降 CPU 峰值，统计口径不变）
 */
fun computeAiWbStats(
    pixels: IntArray,
    highlightLumaThreshold: Float = AiWhiteBalanceConfig.DEFAULT_HIGHLIGHT_LUMA_THRESHOLD,
    stride: Int = 1,
): AiWbStats {
    if (pixels.isEmpty()) return AiWbStats.EMPTY
    val step = stride.coerceAtLeast(1)
    val threshold = highlightLumaThreshold.coerceIn(
        AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MIN,
        AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MAX,
    )

    var count = 0
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var highlightCount = 0
    var highlightSumR = 0L
    var highlightSumG = 0L
    var highlightSumB = 0L

    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        count++
        sumR += r
        sumG += g
        sumB += b

        // 亮度用 0..1 归一口径判断，避免整数比较的边界歧义。
        val luma = (AiWbStats.LUMA_R * r + AiWbStats.LUMA_G * g + AiWbStats.LUMA_B * b) / 255f
        if (luma >= threshold) {
            highlightCount++
            highlightSumR += r
            highlightSumG += g
            highlightSumB += b
        }
        index += step
    }

    if (count == 0) return AiWbStats.EMPTY
    val divisor = count * 255f
    return AiWbStats(
        sampleCount = count,
        meanR = sumR / divisor,
        meanG = sumG / divisor,
        meanB = sumB / divisor,
        highlightCount = highlightCount,
        highlightR = if (highlightCount > 0) highlightSumR / (highlightCount * 255f) else 0f,
        highlightG = if (highlightCount > 0) highlightSumG / (highlightCount * 255f) else 0f,
        highlightB = if (highlightCount > 0) highlightSumB / (highlightCount * 255f) else 0f,
    )
}

/**
 * AI 白平衡估计结果。
 *
 * - [gainR] / [gainB] 以绿色（[gainG] = 1）为参考的**相对增益**（灰世界口径）：
 *   画面偏蓝 → gainR > 1；画面偏暖 → gainB > 1；中性场景两者 ≈ 1。
 * - [tint] > 0 表示画面偏绿（需加品红），< 0 表示偏品红；已归一到 [-1, 1]。
 * - [confidence] ∈ [0, 1]：采样量 + 是否有可靠高光共同决定。
 */
data class AiWhiteBalanceEstimate(
    val kelvin: Int,
    val gainR: Float,
    val gainG: Float,
    val gainB: Float,
    val tint: Float,
    val confidence: Float,
    val source: String,
) {
    /** 是否相当于「不做任何改动」。 */
    val isIdentity: Boolean
        get() = abs(gainR - 1f) < 1e-3f && abs(gainG - 1f) < 1e-3f && abs(gainB - 1f) < 1e-3f

    companion object {
        val IDENTITY = AiWhiteBalanceEstimate(
            kelvin = AiWhiteBalanceEstimator.NEUTRAL_KELVIN,
            gainR = 1f,
            gainG = 1f,
            gainB = 1f,
            tint = 0f,
            confidence = 0f,
            source = AiWhiteBalanceEstimator.SOURCE_LOCAL,
        )
    }
}

/**
 * 端侧 AI 白平衡估计（灰世界 + 高光/白点估计的加权混合）。
 *
 * 参考思路（**仅参考算法思路，不采代码**）：
 *  - 灰世界 / shades-of-gray 白平衡 —— OpenCV `xphoto::createGrayworldWB`（Apache-2.0，约 91191★，
 *    但本工程为纯 Kotlin 端侧实现，未移植其 C++ 代码，仅采用公开的灰度世界假设）；
 *  - 高光/白点估计（white-patch）—— 通用计算机视觉教材思路；
 *  - 失败归因 / 降级 —— 走平台层标准 HTTP 语义。
 *
 * 算法（全部可配、可单测）：
 *  1. 灰世界增益：`gR = meanG / meanR`、`gB = meanG / meanB`（绿色参考，gG = 1）；
 *  2. 高光增益：`hR = highlightG / highlightR`、`hB = highlightG / highlightB`（仅当高光样本足够）；
 *  3. 对数域加权混合（增益是乘性量，对数域插值更符合直觉）：`w = highlightWeight`；
 *  4. 由**未加强度的**混合增益反推相关色温（mired 线性映射，ratio = 1 → 5500K）；
 *  5. 强度混合到恒等增益（`strength`），再 clamp 到 `[1/maxGain, maxGain]`。
 *
 * 该实现对「大面积单色场景」（蓝天、绿草）系统性偏色，故用 [AiWhiteBalanceEstimate.confidence]
 * 暴露置信度，UI/管线可据此决定是否应用。
 */
object AiWhiteBalanceEstimator {

    const val SOURCE_LOCAL = "local-gray-world"
    const val SOURCE_REMOTE = "remote"

    /** 中性色温锚点（ratio = 1 时的估计值）。 */
    const val NEUTRAL_KELVIN = 5_500

    /** ratio 每翻一倍（1 stop）对应的 mired 偏移；由「±1 stop ≈ 200 mired」标定（启发式）。 */
    const val MIRED_PER_STOP = 100.0

    /** 高光估计最少需要的样本数（不足则退化为纯灰世界）。 */
    const val MIN_HIGHLIGHT_SAMPLES = 32

    /** 置信度饱和所需的采样量。 */
    const val CONFIDENCE_SAMPLE_TARGET = 4_096f

    private const val EPS = 1e-4f
    private const val TINT_SCALE = 0.5f
    private const val MIN_MIRED = 30.0
    private const val LN2 = 0.6931471805599453

    /** 主入口：统计 + 配置 → 估计结果（纯函数）。 */
    fun estimate(
        stats: AiWbStats,
        config: AiWhiteBalanceConfig = AiWhiteBalanceConfig(),
    ): AiWhiteBalanceEstimate {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        if (!stats.isUsable) {
            return AiWhiteBalanceEstimate.IDENTITY.copy(
                kelvin = NEUTRAL_KELVIN.coerceIn(clamped.minKelvin, clamped.maxKelvin),
            )
        }

        val meanR = max(stats.meanR, EPS)
        val meanG = max(stats.meanG, EPS)
        val meanB = max(stats.meanB, EPS)

        // 1. 灰世界（绿色参考）
        val grayR = meanG / meanR
        val grayB = meanG / meanB

        // 2. 高光 / 白点
        val useHighlight = stats.highlightCount >= MIN_HIGHLIGHT_SAMPLES &&
            stats.highlightR > 0f && stats.highlightB > 0f
        val highlightR = if (useHighlight) max(stats.highlightG, EPS) / max(stats.highlightR, EPS) else grayR
        val highlightB = if (useHighlight) max(stats.highlightG, EPS) / max(stats.highlightB, EPS) else grayB

        // 3. 对数域加权混合
        val weight = if (useHighlight) clamped.highlightWeight.coerceIn(0f, 1f) else 0f
        val fullR = exp((1f - weight) * ln(max(grayR, EPS)) + weight * ln(max(highlightR, EPS)))
        val fullB = exp((1f - weight) * ln(max(grayB, EPS)) + weight * ln(max(highlightB, EPS)))

        // 4. 色温（用未加强度的估计增益反推，strength 只影响增益强度不影响报告色温）
        val kelvin = kelvinFromGainRatio(fullR, fullB, clamped.minKelvin, clamped.maxKelvin)

        // 5. 强度混合 + clamp
        val strength = clamped.strength
        val gainR = blendToIdentity(fullR, strength).coerceIn(1f / clamped.maxGain, clamped.maxGain)
        val gainB = blendToIdentity(fullB, strength).coerceIn(1f / clamped.maxGain, clamped.maxGain)

        // 绿/品红偏差：R、B 增益同向偏离 1 越多，说明画面越偏绿（需加品红）。
        val greenDeviation = sqrt(max(fullR, EPS) * max(fullB, EPS))
        val tint = ((greenDeviation - 1f) / TINT_SCALE).coerceIn(-1f, 1f)

        val sampleTerm = (stats.sampleCount / CONFIDENCE_SAMPLE_TARGET).coerceIn(0f, 1f)
        val highlightTerm = if (useHighlight) 1f else 0f
        val confidence = (0.5f * sampleTerm + 0.5f * highlightTerm * clamped.highlightWeight.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)

        return AiWhiteBalanceEstimate(
            kelvin = kelvin,
            gainR = gainR,
            gainG = 1f,
            gainB = gainB,
            tint = tint,
            confidence = confidence,
            source = SOURCE_LOCAL,
        )
    }

    /**
     * 增益比 → 相关色温（mired 线性映射，启发式标定）。
     * `ratio = gainR / gainB`：> 1 表示场景偏冷（蓝多）→ 高色温；= 1 → [NEUTRAL_KELVIN]。
     */
    fun kelvinFromGainRatio(gainR: Float, gainB: Float, minKelvin: Int, maxKelvin: Int): Int {
        val low = minOf(minKelvin, maxKelvin).coerceIn(AiWhiteBalanceConfig.KELVIN_MIN, AiWhiteBalanceConfig.KELVIN_MAX)
        val high = maxOf(minKelvin, maxKelvin).coerceIn(AiWhiteBalanceConfig.KELVIN_MIN, AiWhiteBalanceConfig.KELVIN_MAX)
        val ratio = max(gainR, EPS) / max(gainB, EPS)
        val neutralMired = 1_000_000.0 / NEUTRAL_KELVIN
        val mired = neutralMired - MIRED_PER_STOP * (ln(ratio.toDouble()) / LN2)
        val kelvin = (1_000_000.0 / mired.coerceAtLeast(MIN_MIRED)).roundToInt()
        return kelvin.coerceIn(low, high)
    }

    private fun blendToIdentity(gain: Float, strength: Float): Float =
        1f + strength.coerceIn(AiWhiteBalanceConfig.STRENGTH_MIN, AiWhiteBalanceConfig.STRENGTH_MAX) * (gain - 1f)
}
