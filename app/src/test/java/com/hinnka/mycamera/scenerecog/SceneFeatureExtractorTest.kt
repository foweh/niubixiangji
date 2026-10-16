package com.hinnka.mycamera.scenerecog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景特征提取：正常画面、边界（空 / 尺寸 0 / 像素不足 / 跳采样）、异常（全黑 / 全白）。
 * 纯 JVM 测试（不依赖 Bitmap）。
 */
class SceneFeatureExtractorTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val skin = 0xFFE0B090.toInt() // 典型肤色
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun `空像素与非法尺寸返回空特征`() {
        assertEquals(SceneFeatures.EMPTY, SceneFeatureExtractor.extract(IntArray(0), 0, 0))
        assertEquals(SceneFeatures.EMPTY, SceneFeatureExtractor.extract(IntArray(4), 0, 4))
        assertEquals(SceneFeatures.EMPTY, SceneFeatureExtractor.extract(IntArray(4), 4, 0))
        assertFalse(SceneFeatures.EMPTY.isUsable)
    }

    @Test
    fun `纯白画面`() {
        val features = SceneFeatureExtractor.extract(IntArray(16) { white }, 4, 4)

        assertEquals(16, features.sampleCount)
        assertEquals(1f, features.meanLuma, 1e-3f)
        assertEquals(0f, features.edgeDensity, 1e-4f)
        assertEquals(0f, features.saturation, 1e-4f)
        assertEquals(0f, features.warmth, 1e-4f)
        assertEquals(0f, features.skinFraction, 1e-4f)
        assertEquals(0f, features.darkFraction, 1e-4f)
        assertEquals(1f, features.brightFraction, 1e-4f)
        // 无纹理 + 无饱和 → 只有「低饱和」这一项贡献文本分
        assertEquals(SceneFeatures.TEXT_CHROMA_WEIGHT, features.textScore, 1e-3f)
    }

    @Test
    fun `纯黑画面为暗场`() {
        val features = SceneFeatureExtractor.extract(IntArray(9) { black }, 3, 3)
        assertEquals(9, features.sampleCount)
        assertEquals(0f, features.meanLuma, 1e-4f)
        assertEquals(1f, features.darkFraction, 1e-4f)
        assertEquals(0f, features.brightFraction, 1e-4f)
        assertEquals(0f, features.edgeDensity, 1e-4f)
    }

    @Test
    fun `上下黑白分块的梯度与双峰特征`() {
        // 4x4：上两行白、下两行黑
        val pixels = IntArray(16).mapIndexed { index, _ ->
            if (index < 8) white else black
        }.toIntArray()

        val features = SceneFeatureExtractor.extract(pixels, 4, 4)

        assertEquals(16, features.sampleCount)
        assertEquals(0.5f, features.meanLuma, 1e-3f)
        assertEquals(0.5f, features.darkFraction, 1e-4f)
        assertEquals(0.5f, features.brightFraction, 1e-4f)
        // 24 次邻域比较中有 4 次跨行跳变 → 4/24
        assertEquals(4f / 24f, features.edgeDensity, 1e-3f)
        // 0.45*edgeTerm + 0.35*1 + 0.20*1
        assertEquals(0.764f, features.textScore, 5e-3f)
    }

    @Test
    fun `跳采样与全采样口径一致`() {
        val pixels = IntArray(16) { white }
        val full = SceneFeatureExtractor.extract(pixels, 4, 4, step = 1)
        val sampled = SceneFeatureExtractor.extract(pixels, 4, 4, step = 2)

        assertEquals(16, full.sampleCount)
        assertEquals(4, sampled.sampleCount)
        assertEquals(full.meanLuma, sampled.meanLuma, 1e-4f)
        // step 非法值被夹到 1
        assertEquals(16, SceneFeatureExtractor.extract(pixels, 4, 4, step = 0).sampleCount)
    }

    @Test
    fun `像素数量不足时不越界`() {
        val features = SceneFeatureExtractor.extract(IntArray(5) { white }, 4, 4)
        assertTrue(features.sampleCount in 1..5)
    }

    @Test
    fun `肤色判定对典型肤色为真对纯蓝为假`() {
        assertTrue(SceneFeatureExtractor.isSkinTone(224, 176, 144))
        assertFalse(SceneFeatureExtractor.isSkinTone(0, 0, 255))
        assertFalse(SceneFeatureExtractor.isSkinTone(0, 0, 0))

        val allSkin = SceneFeatureExtractor.extract(IntArray(4) { skin }, 2, 2)
        assertEquals(1f, allSkin.skinFraction, 1e-4f)

        val noSkin = SceneFeatureExtractor.extract(IntArray(4) { blue }, 2, 2)
        assertEquals(0f, noSkin.skinFraction, 1e-4f)
    }

    @Test
    fun `单像素画面无梯度`() {
        val features = SceneFeatureExtractor.extract(intArrayOf(white), 1, 1)
        assertEquals(1, features.sampleCount)
        assertEquals(0f, features.edgeDensity, 1e-4f)
    }

    @Test
    fun `亮度函数`() {
        assertEquals(1f, SceneFeatureExtractor.lumaOf(white), 1e-3f)
        assertEquals(0f, SceneFeatureExtractor.lumaOf(black), 1e-3f)
        assertEquals(0.502f, SceneFeatureExtractor.lumaOf(0xFF808080.toInt()), 5e-3f)
    }

    @Test
    fun `冷暖平衡在偏红与偏蓝时符号相反`() {
        val warm = SceneFeatureExtractor.extract(IntArray(4) { 0xFFC08080.toInt() }, 2, 2)
        val cool = SceneFeatureExtractor.extract(IntArray(4) { 0xFF80A0FF.toInt() }, 2, 2)
        assertTrue(warm.warmth > 0f)
        assertTrue(cool.warmth < 0f)
        assertTrue(warm.warmth in -1f..1f)
    }
}
