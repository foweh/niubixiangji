package com.hinnka.mycamera.aiwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 白平衡参数开放度与 clamp（边界值：越界 / 顺序颠倒 / 极值）。
 */
class AiWhiteBalanceConfigTest {

    @Test
    fun `默认值在合法区间内`() {
        val defaults = AiWhiteBalanceConfig.DEFAULT
        assertEquals(AiWhiteBalanceConfig.DEFAULT, AiWhiteBalanceConfig.clamp(defaults))
        assertFalse(defaults.enabled)
        assertFalse(defaults.useRemoteApi)
        assertTrue(defaults.strength in AiWhiteBalanceConfig.STRENGTH_MIN..AiWhiteBalanceConfig.STRENGTH_MAX)
        assertTrue(defaults.minKelvin <= defaults.maxKelvin)
    }

    @Test
    fun `强度与权重被 clamp 到 0 到 1`() {
        val clamped = AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(strength = 5f, highlightWeight = -3f),
        )
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MAX, clamped.strength)
        assertEquals(AiWhiteBalanceConfig.HIGHLIGHT_WEIGHT_MIN, clamped.highlightWeight)

        val clampedLow = AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(strength = -0.5f, highlightWeight = 9f),
        )
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MIN, clampedLow.strength)
        assertEquals(AiWhiteBalanceConfig.HIGHLIGHT_WEIGHT_MAX, clampedLow.highlightWeight)
    }

    @Test
    fun `色温上下限被 clamp 且顺序颠倒时交换`() {
        val tooSmall = AiWhiteBalanceConfig.clamp(AiWhiteBalanceConfig(minKelvin = 10, maxKelvin = 10))
        assertEquals(AiWhiteBalanceConfig.KELVIN_MIN, tooSmall.minKelvin)
        assertEquals(AiWhiteBalanceConfig.KELVIN_MIN, tooSmall.maxKelvin)

        val tooBig = AiWhiteBalanceConfig.clamp(AiWhiteBalanceConfig(minKelvin = 99_999, maxKelvin = 99_999))
        assertEquals(AiWhiteBalanceConfig.KELVIN_MAX, tooBig.minKelvin)
        assertEquals(AiWhiteBalanceConfig.KELVIN_MAX, tooBig.maxKelvin)

        val swapped = AiWhiteBalanceConfig.clamp(AiWhiteBalanceConfig(minKelvin = 9_000, maxKelvin = 2_500))
        assertEquals(2_500, swapped.minKelvin)
        assertEquals(9_000, swapped.maxKelvin)
    }

    @Test
    fun `增益上限与高光阈值与采样步长被 clamp`() {
        val clamped = AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(maxGain = 0.2f, highlightLumaThreshold = 0.01f, sampleStride = 0),
        )
        assertEquals(AiWhiteBalanceConfig.MAX_GAIN_MIN, clamped.maxGain)
        assertEquals(AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MIN, clamped.highlightLumaThreshold)
        assertEquals(AiWhiteBalanceConfig.SAMPLE_STRIDE_MIN, clamped.sampleStride)

        val clampedHigh = AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(maxGain = 99f, highlightLumaThreshold = 3f, sampleStride = 99_999),
        )
        assertEquals(AiWhiteBalanceConfig.MAX_GAIN_MAX, clampedHigh.maxGain)
        assertEquals(AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MAX, clampedHigh.highlightLumaThreshold)
        assertEquals(AiWhiteBalanceConfig.SAMPLE_STRIDE_MAX, clampedHigh.sampleStride)
    }

    @Test
    fun `clamp 幂等`() {
        val once = AiWhiteBalanceConfig.clamp(AiWhiteBalanceConfig(strength = 42f, minKelvin = 4, maxKelvin = 1))
        assertEquals(once, AiWhiteBalanceConfig.clamp(once))
    }

    @Test
    fun `clampWithReport 汇报被修正项`() {
        val report = AiWhiteBalanceConfig.clampWithReport(
            AiWhiteBalanceConfig(strength = 3f, minKelvin = 9_000, maxKelvin = 2_000),
        )
        assertTrue(report.reports.isNotEmpty())
        assertEquals(2_000, report.config.minKelvin)
        assertEquals(9_000, report.config.maxKelvin)
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MAX, report.config.strength)
    }

    @Test
    fun `合法输入不产生修正记录`() {
        val clean = AiWhiteBalanceConfig(strength = 0.4f, minKelvin = 3_000, maxKelvin = 7_000)
        assertTrue(AiWhiteBalanceConfig.clampWithReport(clean).reports.isEmpty())
    }
}
