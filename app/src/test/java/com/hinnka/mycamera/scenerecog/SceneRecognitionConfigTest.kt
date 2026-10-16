package com.hinnka.mycamera.scenerecog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景识别配置：默认值、clamp 边界（越界 / 极值）、幂等、修正报告。
 */
class SceneRecognitionConfigTest {

    @Test
    fun `默认配置无需修正`() {
        val defaults = SceneRecognitionConfig.DEFAULT
        assertEquals(defaults, SceneRecognitionConfig.clamp(defaults))
        assertTrue(SceneRecognitionConfig.clampWithReport(defaults).reports.isEmpty())
        assertFalse(defaults.enabled)
        assertFalse(defaults.useRemoteApi)
        // 与既有 LutRecommender 阈值口径保持一致
        assertEquals(0.12f, defaults.portraitSkinFraction, 1e-6f)
        assertEquals(0.25f, defaults.nightMaxLuma, 1e-6f)
        assertEquals(0.55f, defaults.landscapeMinLuma, 1e-6f)
        assertEquals(0.15f, defaults.streetMinWarmth, 1e-6f)
    }

    @Test
    fun `全部阈值在越界时被 clamp 到下限`() {
        val clamped = SceneRecognitionConfig.clamp(
            SceneRecognitionConfig(
                portraitSkinFraction = -5f,
                textScoreThreshold = -5f,
                nightMaxLuma = -5f,
                landscapeMinLuma = -5f,
                landscapeMinSaturation = -5f,
                landscapeMaxWarmth = -99f,
                streetMinWarmth = -99f,
                streetMinEdgeDensity = -5f,
                sampleStride = 0,
            ),
        )
        assertEquals(SceneRecognitionConfig.SKIN_MIN, clamped.portraitSkinFraction)
        assertEquals(SceneRecognitionConfig.TEXT_MIN, clamped.textScoreThreshold)
        assertEquals(SceneRecognitionConfig.NIGHT_MIN, clamped.nightMaxLuma)
        assertEquals(SceneRecognitionConfig.LANDSCAPE_LUMA_MIN, clamped.landscapeMinLuma)
        assertEquals(SceneRecognitionConfig.SATURATION_MIN, clamped.landscapeMinSaturation)
        assertEquals(SceneRecognitionConfig.WARMTH_MIN, clamped.landscapeMaxWarmth)
        assertEquals(SceneRecognitionConfig.WARMTH_MIN, clamped.streetMinWarmth)
        assertEquals(SceneRecognitionConfig.EDGE_MIN, clamped.streetMinEdgeDensity)
        assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MIN, clamped.sampleStride)
    }

    @Test
    fun `全部阈值在越界时被 clamp 到上限`() {
        val clamped = SceneRecognitionConfig.clamp(
            SceneRecognitionConfig(
                portraitSkinFraction = 99f,
                textScoreThreshold = 99f,
                nightMaxLuma = 99f,
                landscapeMinLuma = 99f,
                landscapeMinSaturation = 99f,
                landscapeMaxWarmth = 99f,
                streetMinWarmth = 99f,
                streetMinEdgeDensity = 99f,
                sampleStride = 99_999,
            ),
        )
        assertEquals(SceneRecognitionConfig.SKIN_MAX, clamped.portraitSkinFraction)
        assertEquals(SceneRecognitionConfig.TEXT_MAX, clamped.textScoreThreshold)
        assertEquals(SceneRecognitionConfig.NIGHT_MAX, clamped.nightMaxLuma)
        assertEquals(SceneRecognitionConfig.LANDSCAPE_LUMA_MAX, clamped.landscapeMinLuma)
        assertEquals(SceneRecognitionConfig.SATURATION_MAX, clamped.landscapeMinSaturation)
        assertEquals(SceneRecognitionConfig.WARMTH_MAX, clamped.landscapeMaxWarmth)
        assertEquals(SceneRecognitionConfig.WARMTH_MAX, clamped.streetMinWarmth)
        assertEquals(SceneRecognitionConfig.EDGE_MAX, clamped.streetMinEdgeDensity)
        assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MAX, clamped.sampleStride)
    }

    @Test
    fun `clamp 幂等`() {
        val once = SceneRecognitionConfig.clamp(SceneRecognitionConfig(nightMaxLuma = 9f, sampleStride = -3))
        assertEquals(once, SceneRecognitionConfig.clamp(once))
    }

    @Test
    fun `clampWithReport 汇报每一项修正`() {
        val report = SceneRecognitionConfig.clampWithReport(
            SceneRecognitionConfig(nightMaxLuma = 9f, sampleStride = 0),
        )
        assertEquals(SceneRecognitionConfig.NIGHT_MAX, report.config.nightMaxLuma)
        assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MIN, report.config.sampleStride)
        assertEquals(2, report.reports.size)
    }
}
