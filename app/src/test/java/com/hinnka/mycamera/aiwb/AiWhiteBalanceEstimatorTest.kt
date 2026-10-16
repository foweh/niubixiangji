package com.hinnka.mycamera.aiwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 白平衡估计：灰世界 + 高光混合、增益边界（clamp / 强度）、色温映射边界、
 * 置信度、异常输入（空统计 / 极值）。
 */
class AiWhiteBalanceEstimatorTest {

    // ---------------------------------------------------------------- 像素统计

    @Test
    fun `空像素返回不可用统计`() {
        val stats = computeAiWbStats(IntArray(0))
        assertEquals(AiWbStats.EMPTY, stats)
        assertFalse(stats.isUsable)
    }

    @Test
    fun `纯白与纯黑`() {
        val white = computeAiWbStats(IntArray(4) { 0xFFFFFFFF.toInt() })
        assertEquals(4, white.sampleCount)
        assertEquals(1f, white.meanR, 1e-4f)
        assertEquals(1f, white.meanG, 1e-4f)
        assertEquals(1f, white.meanB, 1e-4f)
        assertEquals(4, white.highlightCount)
        assertEquals(1f, white.meanLuma, 1e-4f)

        val black = computeAiWbStats(IntArray(4) { 0xFF000000.toInt() })
        assertEquals(0f, black.meanLuma, 1e-4f)
        assertEquals(0, black.highlightCount)
        // 全黑画面无法估计（增益会发散）
        assertFalse(black.isUsable)
    }

    @Test
    fun `通道均值与高光阈值边界`() {
        // 0xD0 = 208 → 0.8157 < 0.85：不算高光
        val below = computeAiWbStats(intArrayOf(0xFFD0D0D0.toInt()), highlightLumaThreshold = 0.85f)
        assertEquals(0, below.highlightCount)
        // 0xE6 = 230 → 0.902 ≥ 0.85：算高光
        val above = computeAiWbStats(intArrayOf(0xFFE6E6E6.toInt()), highlightLumaThreshold = 0.85f)
        assertEquals(1, above.highlightCount)
        // 阈值落在中灰两侧：0x808080 亮度约 0.502
        val justBelow = computeAiWbStats(intArrayOf(0xFF808080.toInt()), highlightLumaThreshold = 0.51f)
        assertEquals(0, justBelow.highlightCount)
        val justAbove = computeAiWbStats(intArrayOf(0xFF808080.toInt()), highlightLumaThreshold = 0.50f)
        assertEquals(1, justAbove.highlightCount)
    }

    @Test
    fun `跳采样只改变样本量不改变统计口径`() {
        val pixels = IntArray(8) { 0xFF404040.toInt() }
        val full = computeAiWbStats(pixels, stride = 1)
        val sampled = computeAiWbStats(pixels, stride = 4)
        assertEquals(8, full.sampleCount)
        assertEquals(2, sampled.sampleCount)
        assertEquals(full.meanG, sampled.meanG, 1e-4f)
        // stride 非法值被夹到 1
        assertEquals(8, computeAiWbStats(pixels, stride = 0).sampleCount)
    }

    // ---------------------------------------------------------------- 估计

    @Test
    fun `不可用统计返回恒等估计`() {
        val estimate = AiWhiteBalanceEstimator.estimate(AiWbStats.EMPTY)
        assertTrue(estimate.isIdentity)
        assertEquals(AiWhiteBalanceEstimator.NEUTRAL_KELVIN, estimate.kelvin)
        assertEquals(0f, estimate.confidence, 1e-4f)
        assertEquals(0f, estimate.tint, 1e-4f)
    }

    @Test
    fun `中性画面增益为一且色温中性`() {
        val stats = AiWbStats.fromAverages(0.5f, 0.5f, 0.5f, sampleCount = 1_000)
        val estimate = AiWhiteBalanceEstimator.estimate(stats)

        assertTrue(estimate.isIdentity)
        assertEquals(1f, estimate.gainR, 1e-4f)
        assertEquals(1f, estimate.gainG, 1e-4f)
        assertEquals(1f, estimate.gainB, 1e-4f)
        assertEquals(AiWhiteBalanceEstimator.NEUTRAL_KELVIN, estimate.kelvin)
        assertEquals(0f, estimate.tint, 1e-4f)
        assertTrue(estimate.confidence > 0f)
        assertEquals(AiWhiteBalanceEstimator.SOURCE_LOCAL, estimate.source)
    }

    @Test
    fun `冷色偏画面提升红增益并给出高色温`() {
        // 蓝通道最强 → 需要压蓝、提红；场景偏冷 → 报告色温高于中性
        val stats = AiWbStats.fromAverages(0.40f, 0.50f, 0.60f, sampleCount = 1_000)
        val estimate = AiWhiteBalanceEstimator.estimate(stats)

        assertTrue(estimate.gainR > 1f)
        assertTrue(estimate.gainB < 1f)
        assertEquals(1f, estimate.gainG, 1e-4f)
        assertTrue("色温应高于中性：${estimate.kelvin}", estimate.kelvin > AiWhiteBalanceEstimator.NEUTRAL_KELVIN)
        assertTrue(estimate.kelvin in 7_000..9_000)
    }

    @Test
    fun `暖色偏画面提升蓝增益并给出低色温`() {
        val stats = AiWbStats.fromAverages(0.60f, 0.50f, 0.40f, sampleCount = 1_000)
        val estimate = AiWhiteBalanceEstimator.estimate(stats)

        assertTrue(estimate.gainB > 1f)
        assertTrue(estimate.gainR < 1f)
        assertTrue("色温应低于中性：${estimate.kelvin}", estimate.kelvin < AiWhiteBalanceEstimator.NEUTRAL_KELVIN)
        assertTrue(estimate.kelvin in 3_000..4_500)
    }

    @Test
    fun `色温被限制在配置区间内`() {
        val cool = AiWbStats.fromAverages(0.10f, 0.50f, 0.90f, sampleCount = 1_000)
        val config = AiWhiteBalanceConfig(minKelvin = 3_000, maxKelvin = 6_000)
        val estimate = AiWhiteBalanceEstimator.estimate(cool, config)
        assertTrue(estimate.kelvin in 3_000..6_000)
        assertEquals(6_000, estimate.kelvin)
    }

    @Test
    fun `强度为零时增益恒等但色温仍然报告`() {
        val warm = AiWbStats.fromAverages(0.60f, 0.50f, 0.40f, sampleCount = 1_000)
        val estimate = AiWhiteBalanceEstimator.estimate(warm, AiWhiteBalanceConfig(strength = 0f))

        assertTrue(estimate.isIdentity)
        // 报告值反映场景真实偏差，便于 UI 展示/用户手动微调
        assertTrue(estimate.kelvin < AiWhiteBalanceEstimator.NEUTRAL_KELVIN)
    }

    @Test
    fun `强度被 clamp 且增益不超过 maxGain`() {
        val extreme = AiWbStats.fromAverages(0.05f, 0.50f, 0.05f, sampleCount = 1_000)
        val bound = AiWhiteBalanceEstimator.estimate(
            extreme,
            AiWhiteBalanceConfig(strength = 1f, maxGain = 2f),
        )
        assertTrue(bound.gainR <= 2f)
        assertTrue(bound.gainB <= 2f)
        assertTrue(bound.gainR >= 0.5f)
        assertTrue(bound.gainB >= 0.5f)

        // strength 越界被 clamp 到 1（等价于满强度）
        val overStrength = AiWhiteBalanceEstimator.estimate(extreme, AiWhiteBalanceConfig(strength = 9f, maxGain = 2f))
        assertEquals(bound.gainR, overStrength.gainR, 1e-4f)

        // strength = 0 时完全不动
        val zero = AiWhiteBalanceEstimator.estimate(extreme, AiWhiteBalanceConfig(strength = -1f))
        assertTrue(zero.isIdentity)
    }

    @Test
    fun `高光估计在样本充足时参与混合`() {
        // 灰世界中性，但高光偏暖 → 加权重后应给出低于中性的色温
        val stats = AiWbStats.fromAverages(
            meanR = 0.5f,
            meanG = 0.5f,
            meanB = 0.5f,
            highlightR = 0.90f,
            highlightG = 0.80f,
            highlightB = 0.60f,
            sampleCount = 4_096,
            highlightCount = 512,
        )
        val grayWorldOnly = AiWhiteBalanceEstimator.estimate(stats, AiWhiteBalanceConfig(highlightWeight = 0f))
        val highlightMixed = AiWhiteBalanceEstimator.estimate(stats, AiWhiteBalanceConfig(highlightWeight = 1f))

        assertEquals(AiWhiteBalanceEstimator.NEUTRAL_KELVIN, grayWorldOnly.kelvin)
        assertTrue(highlightMixed.kelvin < grayWorldOnly.kelvin)
        assertTrue(highlightMixed.confidence > grayWorldOnly.confidence)
    }

    @Test
    fun `高光样本不足时退化为纯灰世界`() {
        val stats = AiWbStats.fromAverages(
            meanR = 0.5f,
            meanG = 0.5f,
            meanB = 0.5f,
            highlightR = 0.95f,
            highlightG = 0.5f,
            highlightB = 0.2f,
            sampleCount = 1_000,
            highlightCount = AiWhiteBalanceEstimator.MIN_HIGHLIGHT_SAMPLES - 1,
        )
        val estimate = AiWhiteBalanceEstimator.estimate(stats, AiWhiteBalanceConfig(highlightWeight = 1f))
        assertEquals(AiWhiteBalanceEstimator.NEUTRAL_KELVIN, estimate.kelvin)
        assertTrue(estimate.isIdentity)
    }

    @Test
    fun `置信度随采样量单调上升且有上限`() {
        val small = AiWhiteBalanceEstimator.estimate(AiWbStats.fromAverages(0.4f, 0.5f, 0.6f, sampleCount = 64))
        val large = AiWhiteBalanceEstimator.estimate(AiWbStats.fromAverages(0.4f, 0.5f, 0.6f, sampleCount = 1_000_000))
        assertTrue(large.confidence >= small.confidence)
        assertTrue(large.confidence <= 1f)
        assertTrue(small.confidence >= 0f)
    }

    @Test
    fun `绿偏画面 tint 为正`() {
        // 绿通道显著偏强 → tint 应为正（提示加品红）
        val stats = AiWbStats.fromAverages(0.35f, 0.60f, 0.35f, sampleCount = 1_000)
        val estimate = AiWhiteBalanceEstimator.estimate(stats)
        assertTrue("tint 应为正：${estimate.tint}", estimate.tint > 0f)
        assertTrue(estimate.tint <= 1f)
    }

    @Test
    fun `极端单色画面不产生 NaN 或无穷`() {
        val estimate = AiWhiteBalanceEstimator.estimate(
            AiWbStats.fromAverages(0f, 1f, 0f, sampleCount = 10),
        )
        assertFalse(estimate.gainR.isNaN())
        assertFalse(estimate.gainB.isNaN())
        assertTrue(estimate.gainR.isFinite())
        assertTrue(estimate.gainB.isFinite())
        assertTrue(estimate.tint in -1f..1f)
    }

    // ---------------------------------------------------------------- 色温映射边界

    @Test
    fun `增益比为一时映射到中性色温`() {
        assertEquals(
            AiWhiteBalanceEstimator.NEUTRAL_KELVIN,
            AiWhiteBalanceEstimator.kelvinFromGainRatio(1f, 1f, 1_500, 15_000),
        )
    }

    @Test
    fun `增益比到色温单调且被区间夹住`() {
        val warm = AiWhiteBalanceEstimator.kelvinFromGainRatio(0.5f, 1f, 1_500, 15_000)
        val neutral = AiWhiteBalanceEstimator.kelvinFromGainRatio(1f, 1f, 1_500, 15_000)
        val cool = AiWhiteBalanceEstimator.kelvinFromGainRatio(2f, 1f, 1_500, 15_000)
        assertTrue(warm < neutral)
        assertTrue(neutral < cool)

        assertEquals(9_000, AiWhiteBalanceEstimator.kelvinFromGainRatio(2f, 1f, 2_500, 9_000))
        assertEquals(2_500, AiWhiteBalanceEstimator.kelvinFromGainRatio(0.1f, 1f, 2_500, 9_000))
        // min/max 传反也能工作
        assertEquals(9_000, AiWhiteBalanceEstimator.kelvinFromGainRatio(2f, 1f, 9_000, 2_500))
    }

    @Test
    fun `零增益不产生异常`() {
        val kelvin = AiWhiteBalanceEstimator.kelvinFromGainRatio(0f, 0f, 2_500, 9_000)
        assertTrue(kelvin in 2_500..9_000)
    }
}
