package com.hinnka.mycamera.scenerecog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景识别参数映射（纯函数部分）：默认值 / 非法输入 / 越界 clamp / 往返一致性。
 */
class SceneRecognitionSettingsTest {

    @Test
    fun `键清单唯一且完整`() {
        assertEquals(11, SceneRecognitionSettings.keys.size)
        assertEquals(SceneRecognitionSettings.keys.size, SceneRecognitionSettings.keys.toSet().size)
        SceneRecognitionSettings.keys.forEach { assertTrue(it.startsWith("scenerecog.")) }
    }

    @Test
    fun `空字典回落默认值`() {
        assertEquals(SceneRecognitionConfig.DEFAULT, SceneRecognitionSettings.configFrom(emptyMap()))
    }

    @Test
    fun `非法输入回落默认值`() {
        val config = SceneRecognitionSettings.configFrom(
            mapOf(
                SceneRecognitionSettings.KEY_ENABLED to "maybe",
                SceneRecognitionSettings.KEY_NIGHT_LUMA to "dark",
                SceneRecognitionSettings.KEY_SAMPLE_STRIDE to "4.5",
            ),
        )
        val defaults = SceneRecognitionConfig.DEFAULT
        assertEquals(defaults.enabled, config.enabled)
        assertEquals(defaults.nightMaxLuma, config.nightMaxLuma, 1e-6f)
        assertEquals(defaults.sampleStride, config.sampleStride)
    }

    @Test
    fun `越界输入被 clamp`() {
        val config = SceneRecognitionSettings.configFrom(
            mapOf(
                SceneRecognitionSettings.KEY_PORTRAIT_SKIN to "9",
                SceneRecognitionSettings.KEY_TEXT_THRESHOLD to "-1",
                SceneRecognitionSettings.KEY_NIGHT_LUMA to "0",
                SceneRecognitionSettings.KEY_LANDSCAPE_LUMA to "5",
                SceneRecognitionSettings.KEY_SAMPLE_STRIDE to "9999",
            ),
        )
        assertEquals(SceneRecognitionConfig.SKIN_MAX, config.portraitSkinFraction, 1e-6f)
        assertEquals(SceneRecognitionConfig.TEXT_MIN, config.textScoreThreshold, 1e-6f)
        assertEquals(SceneRecognitionConfig.NIGHT_MIN, config.nightMaxLuma, 1e-6f)
        assertEquals(SceneRecognitionConfig.LANDSCAPE_LUMA_MAX, config.landscapeMinLuma, 1e-6f)
        assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MAX, config.sampleStride)
    }

    @Test
    fun `写出读回一致`() {
        val config = SceneRecognitionConfig(
            enabled = true,
            portraitSkinFraction = 0.20f,
            textScoreThreshold = 0.55f,
            nightMaxLuma = 0.30f,
            landscapeMinLuma = 0.60f,
            landscapeMinSaturation = 0.25f,
            landscapeMaxWarmth = -0.10f,
            streetMinWarmth = 0.20f,
            streetMinEdgeDensity = 0.30f,
            sampleStride = 2,
            useRemoteApi = true,
        )
        val entries = SceneRecognitionSettings.toEntries(config)
        assertEquals(11, entries.size)
        assertEquals(config, SceneRecognitionSettings.configFrom(entries))
    }

    @Test
    fun `写出的值一定是 clamp 后的值`() {
        val entries = SceneRecognitionSettings.toEntries(SceneRecognitionConfig(nightMaxLuma = 99f))
        assertEquals(
            SceneRecognitionConfig.NIGHT_MAX.toString(),
            entries[SceneRecognitionSettings.KEY_NIGHT_LUMA],
        )
    }

    @Test
    fun `状态描述区分开关与端侧远端`() {
        assertEquals("关闭", SceneRecognitionSettings.describe(SceneRecognitionConfig(enabled = false)))
        assertTrue(SceneRecognitionSettings.describe(SceneRecognitionConfig(enabled = true)).contains("端侧"))
        assertTrue(
            SceneRecognitionSettings.describe(SceneRecognitionConfig(enabled = true, useRemoteApi = true))
                .contains("远端优先"),
        )
    }
}
