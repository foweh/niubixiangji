package com.hinnka.mycamera.scenerecog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景分类：正常样本、边界值（恰好等于阈值）、异常（无样本 / 极端输入）、
 * 规则优先级与配置生效性。
 */
class SceneClassifierTest {

    private fun features(
        sampleCount: Int = 100,
        meanLuma: Float = 0.50f,
        warmth: Float = 0.05f,
        saturation: Float = 0.10f,
        edgeDensity: Float = 0.05f,
        skinFraction: Float = 0f,
        darkFraction: Float = 0.10f,
        brightFraction: Float = 0.05f,
        textScore: Float = 0.20f,
    ) = SceneFeatures(
        sampleCount = sampleCount,
        meanLuma = meanLuma,
        warmth = warmth,
        saturation = saturation,
        edgeDensity = edgeDensity,
        skinFraction = skinFraction,
        darkFraction = darkFraction,
        brightFraction = brightFraction,
        textScore = textScore,
    )

    // ---------------------------------------------------------------- 正常分类

    @Test
    fun `肤色占比较高判为人像`() {
        val result = SceneClassifier.classify(features(skinFraction = 0.30f, meanLuma = 0.50f))
        assertEquals(SceneLabel.PORTRAIT, result.label)
        assertTrue(result.confidence >= 0.5f)
        assertTrue(result.note.isNotBlank())
    }

    @Test
    fun `暗场判为夜景`() {
        val result = SceneClassifier.classify(features(meanLuma = 0.10f, darkFraction = 0.8f))
        assertEquals(SceneLabel.NIGHT, result.label)
        assertTrue(result.confidence >= 0.5f)
    }

    @Test
    fun `明亮高饱和偏冷判为风光`() {
        val result = SceneClassifier.classify(
            features(meanLuma = 0.70f, saturation = 0.40f, warmth = -0.20f),
        )
        assertEquals(SceneLabel.LANDSCAPE, result.label)
    }

    @Test
    fun `偏暖判为街景`() {
        val result = SceneClassifier.classify(features(warmth = 0.30f, meanLuma = 0.50f))
        assertEquals(SceneLabel.STREET, result.label)
    }

    @Test
    fun `纹理密集且亮度居中判为街景`() {
        val result = SceneClassifier.classify(
            features(warmth = 0.0f, edgeDensity = 0.50f, meanLuma = 0.50f),
        )
        assertEquals(SceneLabel.STREET, result.label)
    }

    @Test
    fun `文本代理分高判为文本`() {
        val result = SceneClassifier.classify(features(textScore = 0.80f))
        assertEquals(SceneLabel.TEXT, result.label)
    }

    @Test
    fun `无显著特征时判为未知`() {
        val result = SceneClassifier.classify(features())
        assertEquals(SceneLabel.UNKNOWN, result.label)
        assertEquals(SceneClassifier.UNKNOWN_CONFIDENCE, result.confidence, 1e-4f)
    }

    // ---------------------------------------------------------------- 边界值

    @Test
    fun `阈值恰好相等时命中`() {
        val config = SceneRecognitionConfig.DEFAULT

        // 肤色恰好等于阈值（>=）
        assertEquals(
            SceneLabel.PORTRAIT,
            SceneClassifier.classify(features(skinFraction = config.portraitSkinFraction), config).label,
        )
        // 亮度恰好等于夜景上限（<=）
        assertEquals(
            SceneLabel.NIGHT,
            SceneClassifier.classify(features(meanLuma = config.nightMaxLuma), config).label,
        )
        // 文本分恰好等于阈值（>=）
        assertEquals(
            SceneLabel.TEXT,
            SceneClassifier.classify(features(textScore = config.textScoreThreshold), config).label,
        )
        // 冷暖恰好等于街景下限（>=），且亮度过低不满足风光的亮度条件
        assertEquals(
            SceneLabel.STREET,
            SceneClassifier.classify(features(warmth = config.streetMinWarmth, meanLuma = 0.50f), config).label,
        )
        // 风光三项条件恰好全满足（>= / >= / <=）
        assertEquals(
            SceneLabel.LANDSCAPE,
            SceneClassifier.classify(
                features(
                    meanLuma = config.landscapeMinLuma,
                    saturation = config.landscapeMinSaturation,
                    warmth = config.landscapeMaxWarmth,
                ),
                config,
            ).label,
        )
    }

    @Test
    fun `阈值内侧一档即不命中`() {
        val config = SceneRecognitionConfig.DEFAULT
        // 肤色略低于阈值 → 不是人像
        val almostPortrait = SceneClassifier.classify(features(skinFraction = config.portraitSkinFraction - 0.01f), config)
        assertTrue(almostPortrait.label != SceneLabel.PORTRAIT)
        // 亮度略高于夜景上限 → 不是夜景
        val almostNight = SceneClassifier.classify(features(meanLuma = config.nightMaxLuma + 0.01f), config)
        assertTrue(almostNight.label != SceneLabel.NIGHT)
    }

    @Test
    fun `置信度随裕度单调上升`() {
        val just = SceneClassifier.classify(features(textScore = 0.46f))
        val strong = SceneClassifier.classify(features(textScore = 0.95f))
        assertEquals(SceneLabel.TEXT, just.label)
        assertEquals(SceneLabel.TEXT, strong.label)
        assertTrue(strong.confidence > just.confidence)
    }

    // ---------------------------------------------------------------- 优先级

    @Test
    fun `人像优先级高于文本与夜景`() {
        val result = SceneClassifier.classify(
            features(skinFraction = 0.50f, meanLuma = 0.20f, textScore = 0.90f),
        )
        assertEquals(SceneLabel.PORTRAIT, result.label)
    }

    @Test
    fun `肤色占比高但画面过暗时不算人像走夜景`() {
        val result = SceneClassifier.classify(features(skinFraction = 0.50f, meanLuma = 0.10f))
        assertEquals(SceneLabel.NIGHT, result.label)
    }

    // ---------------------------------------------------------------- 异常输入

    @Test
    fun `无有效样本返回零置信未知`() {
        val result = SceneClassifier.classify(SceneFeatures.EMPTY)
        assertEquals(SceneLabel.UNKNOWN, result.label)
        assertEquals(0f, result.confidence, 1e-4f)
    }

    @Test
    fun `极端数值不崩溃且结果仍在标签集合内`() {
        val extreme = listOf(
            features(meanLuma = 0f, saturation = 0f, warmth = -1f, edgeDensity = 0f),
            features(meanLuma = 1f, saturation = 1f, warmth = 1f, edgeDensity = 1f, skinFraction = 1f),
            features(meanLuma = 0f, warmth = 1f, saturation = 1f, textScore = 1f, skinFraction = 1f),
        )
        extreme.forEach { sample ->
            val result = SceneClassifier.classify(sample)
            assertTrue(result.label in SceneLabel.entries)
            assertTrue(result.confidence in 0f..1f)
        }
    }

    // ---------------------------------------------------------------- 配置生效

    @Test
    fun `抬高肤色阈值后同一画面不再是肖像`() {
        val sample = features(skinFraction = 0.30f)
        assertEquals(SceneLabel.PORTRAIT, SceneClassifier.classify(sample, SceneRecognitionConfig.DEFAULT).label)
        assertEquals(
            SceneLabel.UNKNOWN,
            SceneClassifier.classify(sample, SceneRecognitionConfig(portraitSkinFraction = 0.50f)).label,
        )
    }

    @Test
    fun `越界配置先被 clamp 再参与判定`() {
        // nightMaxLuma = 5 会被 clamp 到 0.50；亮度 0.40 < 0.50 → 夜景
        val result = SceneClassifier.classify(
            features(meanLuma = 0.40f),
            SceneRecognitionConfig(nightMaxLuma = 5f),
        )
        assertEquals(SceneLabel.NIGHT, result.label)
    }

    // ---------------------------------------------------------------- 下游联动

    @Test
    fun `场景标签映射到计算场景`() {
        assertEquals(
            com.hinnka.mycamera.camera.ComputationalScene.PORTRAIT,
            SceneClassifier.toComputationalScene(SceneLabel.PORTRAIT),
        )
        assertEquals(
            com.hinnka.mycamera.camera.ComputationalScene.NIGHT,
            SceneClassifier.toComputationalScene(SceneLabel.NIGHT),
        )
        listOf(SceneLabel.LANDSCAPE, SceneLabel.STREET, SceneLabel.TEXT, SceneLabel.UNKNOWN).forEach { label ->
            assertEquals(
                com.hinnka.mycamera.camera.ComputationalScene.STANDARD,
                SceneClassifier.toComputationalScene(label),
            )
        }
    }

    @Test
    fun `置信度换算边界`() {
        assertEquals(0.5f, SceneClassifier.confidenceOf(0f), 1e-4f)
        assertEquals(1f, SceneClassifier.confidenceOf(1f), 1e-4f)
        assertEquals(0.5f, SceneClassifier.confidenceOf(-3f), 1e-4f)
        assertEquals(1f, SceneClassifier.confidenceOf(9f), 1e-4f)
    }
}
