package com.hinnka.mycamera.aiwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 白平衡参数映射（纯函数部分）：默认值 / 非法输入 / 越界 clamp / 往返一致性。
 * 不涉及 DataStore（读写路径由设备验证）。
 */
class AiWhiteBalanceSettingsTest {

    @Test
    fun `键清单唯一且完整`() {
        assertEquals(9, AiWhiteBalanceSettings.keys.size)
        assertEquals(AiWhiteBalanceSettings.keys.size, AiWhiteBalanceSettings.keys.toSet().size)
        AiWhiteBalanceSettings.keys.forEach { assertTrue(it.startsWith("aiwb.")) }
    }

    @Test
    fun `空字典回落默认值`() {
        assertEquals(AiWhiteBalanceConfig.DEFAULT, AiWhiteBalanceSettings.configFrom(emptyMap()))
    }

    @Test
    fun `非法输入回落默认值`() {
        val config = AiWhiteBalanceSettings.configFrom(
            mapOf(
                AiWhiteBalanceSettings.KEY_ENABLED to "yes",
                AiWhiteBalanceSettings.KEY_STRENGTH to "abc",
                AiWhiteBalanceSettings.KEY_MIN_KELVIN to "3.5",
                AiWhiteBalanceSettings.KEY_MAX_GAIN to "",
            ),
        )
        val defaults = AiWhiteBalanceConfig.DEFAULT
        assertEquals(defaults.enabled, config.enabled)
        assertEquals(defaults.strength, config.strength, 1e-6f)
        assertEquals(defaults.minKelvin, config.minKelvin)
        assertEquals(defaults.maxGain, config.maxGain, 1e-6f)
    }

    @Test
    fun `越界输入被 clamp`() {
        val config = AiWhiteBalanceSettings.configFrom(
            mapOf(
                AiWhiteBalanceSettings.KEY_STRENGTH to "5.0",
                AiWhiteBalanceSettings.KEY_MIN_KELVIN to "10",
                AiWhiteBalanceSettings.KEY_MAX_KELVIN to "99999",
                AiWhiteBalanceSettings.KEY_MAX_GAIN to "0.1",
                AiWhiteBalanceSettings.KEY_SAMPLE_STRIDE to "0",
            ),
        )
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MAX, config.strength, 1e-6f)
        assertEquals(AiWhiteBalanceConfig.KELVIN_MIN, config.minKelvin)
        assertEquals(AiWhiteBalanceConfig.KELVIN_MAX, config.maxKelvin)
        assertEquals(AiWhiteBalanceConfig.MAX_GAIN_MIN, config.maxGain, 1e-6f)
        assertEquals(AiWhiteBalanceConfig.SAMPLE_STRIDE_MIN, config.sampleStride)
    }

    @Test
    fun `色温顺序颠倒时被交换而不是报错`() {
        val config = AiWhiteBalanceSettings.configFrom(
            mapOf(
                AiWhiteBalanceSettings.KEY_MIN_KELVIN to "9000",
                AiWhiteBalanceSettings.KEY_MAX_KELVIN to "2500",
            ),
        )
        assertEquals(2500, config.minKelvin)
        assertEquals(9000, config.maxKelvin)
    }

    @Test
    fun `写出读回一致`() {
        val config = AiWhiteBalanceConfig(
            enabled = true,
            strength = 0.35f,
            minKelvin = 3200,
            maxKelvin = 7800,
            maxGain = 3f,
            highlightWeight = 0.5f,
            highlightLumaThreshold = 0.9f,
            sampleStride = 8,
            useRemoteApi = true,
        )
        val entries = AiWhiteBalanceSettings.toEntries(config)
        assertEquals(9, entries.size)
        assertEquals(config, AiWhiteBalanceSettings.configFrom(entries))
    }

    @Test
    fun `写出的值一定是 clamp 后的值`() {
        val entries = AiWhiteBalanceSettings.toEntries(AiWhiteBalanceConfig(strength = 9f, sampleStride = 999))
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MAX.toString(), entries[AiWhiteBalanceSettings.KEY_STRENGTH])
        assertEquals(AiWhiteBalanceConfig.SAMPLE_STRIDE_MAX.toString(), entries[AiWhiteBalanceSettings.KEY_SAMPLE_STRIDE])
    }

    @Test
    fun `状态描述区分开关与端侧远端`() {
        assertEquals("关闭", AiWhiteBalanceSettings.describe(AiWhiteBalanceConfig(enabled = false)))
        val local = AiWhiteBalanceSettings.describe(AiWhiteBalanceConfig(enabled = true))
        val remote = AiWhiteBalanceSettings.describe(AiWhiteBalanceConfig(enabled = true, useRemoteApi = true))
        assertTrue(local.contains("端侧"))
        assertTrue(remote.contains("远端优先"))
        assertFalse(local == remote)
    }
}
