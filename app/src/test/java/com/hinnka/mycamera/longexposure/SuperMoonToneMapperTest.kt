package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月亮局部色调映射单测（纯 JVM）。
 *
 * 覆盖用户可感知的四条硬性约束（[SuperMoonToneMapper] 的设计契约）：
 *  1. **高光不截断**：满量程输入被压到 1 以内且仍远离 0（月面环形山细节保留）；
 *  2. **严格单调**：亮度递增序列输出不减（纹理相对次序完整）；
 *  3. **纯黑保持纯黑**：天空不被提亮成灰雾；
 *  4. **保持色相**：逐通道按同一亮度比例缩放，通道比值不变（无彩边）。
 */
class SuperMoonToneMapperTest {

    private val gainOnly = LocalToneMapConfig(
        enabled = true,
        highlightThreshold = 0.80f,
        highlightCompression = 1.20f,
        shadowLift = 0.15f,
        gamma = 1.0f,
        localRadius = 24,
        localAmount = 0f,
    )

    @Test
    fun disabledConfigReturnsUnchangedCopy() {
        val samples = shortArrayOf(10, 200, 4000)
        val config = gainOnly.copy(enabled = false)
        assertArrayEquals(samples, SuperMoonToneMapper.toneMap(samples, 1, 1, config))
        assertArrayEquals("输入不得被就地修改", shortArrayOf(10, 200, 4000), samples)
    }

    @Test
    fun fullScaleHighlightIsCompressedWithoutClipping() {
        val samples = shortArrayOf(
            LinearRgbPlane.SAMPLE_MAX.toShort(),
            LinearRgbPlane.SAMPLE_MAX.toShort(),
            LinearRgbPlane.SAMPLE_MAX.toShort(),
        )
        val before = SuperMoonToneMapper.clippedHighlightRatio(samples, 0.99f)
        val mapped = SuperMoonToneMapper.toneMap(samples, 1, 1, gainOnly)
        val after = SuperMoonToneMapper.clippedHighlightRatio(mapped, 0.99f)

        assertTrue("满量程必须被压下来", mapped[0].toInt() < LinearRgbPlane.SAMPLE_MAX)
        assertTrue("但必须仍显著亮于中灰（细节保留）", mapped[0].toInt() > LinearRgbPlane.SAMPLE_MAX / 2)
        assertTrue("近顶样本比例应下降：$before -> $after", after < before)
    }

    @Test
    fun tonalCurveIsStrictlyMonotonic() {
        val width = 256
        val samples = ShortArray(width * 3)
        for (x in 0 until width) {
            val value = (x * 16).coerceAtMost(LinearRgbPlane.SAMPLE_MAX).toShort()
            samples[x * 3] = value
            samples[x * 3 + 1] = value
            samples[x * 3 + 2] = value
        }
        val mapped = SuperMoonToneMapper.toneMap(samples, width, 1, gainOnly)
        for (x in 1 until width) {
            assertTrue(
                "亮度序列必须单调不减：index $x",
                mapped[x * 3].toInt() >= mapped[(x - 1) * 3].toInt(),
            )
        }
    }

    @Test
    fun pureBlackStaysPureBlack() {
        val samples = shortArrayOf(0, 0, 0, 100, 100, 100)
        val mapped = SuperMoonToneMapper.toneMap(samples, 2, 1, gainOnly)
        assertArrayEquals(shortArrayOf(0, 0, 0), mapped.copyOfRange(0, 3))
    }

    @Test
    fun channelRatiosArePreservedSoHueDoesNotShift() {
        val samples = shortArrayOf(2000, 1000, 500)
        val mapped = SuperMoonToneMapper.toneMap(samples, 1, 1, gainOnly)
        val originalRedGreen = 2000.0 / 1000.0
        val originalGreenBlue = 1000.0 / 500.0
        assertEquals("R/G 比值应保持", originalRedGreen, mapped[0].toDouble() / mapped[1], 0.02)
        assertEquals("G/B 比值应保持", originalGreenBlue, mapped[1].toDouble() / mapped[2], 0.02)
    }

    @Test
    fun localContrastFlatFieldStaysFlatWithoutEdgeArtifacts() {
        // 平坦场：局部对比项 detail = 0，整幅必须逐像素一致（验证盒模糊 clamp-to-edge 无边界伪影）。
        val width = 16
        val height = 16
        val samples = ShortArray(width * height * 3) { 2048 }
        val mapped = SuperMoonToneMapper.toneMap(
            samples,
            width,
            height,
            gainOnly.copy(localRadius = 3, localAmount = 0.35f),
        )
        val first = mapped[0]
        for (i in mapped.indices) {
            // 允许 ±1 的浮点尾差（盒模糊的累加次序在边界处可能差最后一位）。
            assertTrue(
                "平坦场不应出现边界伪影（index $i: ${mapped[i]} vs $first）",
                kotlin.math.abs(mapped[i].toInt() - first.toInt()) <= 1,
            )
        }
    }

    @Test
    fun localContrastAddsDetailInMidtonesWithoutOverflow() {
        val width = 8
        val samples = ShortArray(width * 3)
        for (x in 0 until width) {
            // 中灰底 + 一格亮斑，制造局部对比
            val value = if (x == 4) 3000 else 1500
            samples[x * 3] = value.toShort()
            samples[x * 3 + 1] = value.toShort()
            samples[x * 3 + 2] = value.toShort()
        }
        val mapped = SuperMoonToneMapper.toneMap(
            samples,
            width,
            1,
            gainOnly.copy(localRadius = 2, localAmount = 1.0f),
        )
        assertTrue("亮斑必须仍是最亮", mapped[4 * 3].toInt() > mapped[0].toInt())
        assertTrue(mapped.all { it.toInt() in 0..LinearRgbPlane.SAMPLE_MAX })
    }

    @Test
    fun shadowLiftDoesNotLiftPureBlackButLiftsNearBlack() {
        val samples = shortArrayOf(0, 0, 0, 40, 40, 40)
        val lifted = SuperMoonToneMapper.toneMap(samples, 2, 1, gainOnly.copy(shadowLift = 1.0f))
        assertEquals(0, lifted[0].toInt())
        assertTrue("近黑应被抬起", lifted[3].toInt() >= 40)
    }

    @Test
    fun planeOverloadKeepsDimensions() {
        val plane = LinearRgbPlane(2, 2, ShortArray(12) { 1000 })
        val mapped = SuperMoonToneMapper.toneMap(plane, gainOnly)
        assertEquals(2, mapped.width)
        assertEquals(2, mapped.height)
        assertEquals(12, mapped.samples.size)
    }

    @Test
    fun clippedHighlightRatioCountsNearTopSamples() {
        val samples = shortArrayOf(4095, 4095, 100, 0, 100, 100)
        assertEquals(2f / 6f, SuperMoonToneMapper.clippedHighlightRatio(samples, 0.99f), 1e-6f)
        assertEquals(0f, SuperMoonToneMapper.clippedHighlightRatio(ShortArray(0)), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistentSampleCountIsRejected() {
        SuperMoonToneMapper.toneMap(ShortArray(5), 2, 2, gainOnly)
    }
}
