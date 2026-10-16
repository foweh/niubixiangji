package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.argb
import com.hinnka.mycamera.enhance.EnhanceTestSupport.channel
import com.hinnka.mycamera.enhance.EnhanceTestSupport.fill
import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.noisy
import com.hinnka.mycamera.enhance.EnhanceTestSupport.step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反锐化掩模单测：恒等性、平坦图不变、边缘对比度提升、阈值抑制弱差。
 */
class UnsharpMaskProcessorTest {

    @Test
    fun amountZeroIsIdentity() {
        val pixels = step(16, 16, 40, 200)
        val out = UnsharpMaskProcessor.process(pixels, 16, 16, UnsharpMaskConfig(amount = 0f))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun flatImageIsUnchanged() {
        val pixels = fill(16, 16, 128, 128, 128)
        val out = UnsharpMaskProcessor.process(pixels, 16, 16, UnsharpMaskConfig(radius = 3f, amount = 2f))
        assertTrue(
            "flat image must be untouched: maxDiff=${EnhanceTestSupport.maxChannelDiff(pixels, out)}",
            EnhanceTestSupport.maxChannelDiff(pixels, out) == 0,
        )
    }

    @Test
    fun edgeContrastIncreases() {
        val width = 32
        val height = 8
        val pixels = step(width, height, 60, 190)
        val out = UnsharpMaskProcessor.process(
            pixels, width, height, UnsharpMaskConfig(radius = 1f, amount = 1.5f, threshold = 0),
        )
        // 阶跃两侧：亮侧更亮、暗侧更暗（过冲 = 锐化）
        val darkSide = out[4 * width + (width / 2 - 1)]
        val brightSide = out[4 * width + (width / 2)]
        assertTrue("dark side should undershoot: ${channel(darkSide, 16)}", channel(darkSide, 16) < 60)
        assertTrue("bright side should overshoot: ${channel(brightSide, 16)}", channel(brightSide, 16) > 190)
    }

    @Test
    fun thresholdSuppressesSmallDifferences() {
        val width = 16
        val height = 16
        // 振幅 ±1 LSB 的微噪声：低于 threshold=8 的门控，应完全不改
        val pixels = noisy(width, height, base = 128, amplitude = 1, seed = 11L)
        val out = UnsharpMaskProcessor.process(
            pixels, width, height, UnsharpMaskConfig(radius = 2f, amount = 2f, threshold = 8),
        )
        assertTrue(
            "sub-threshold detail must be ignored: maxDiff=${EnhanceTestSupport.maxChannelDiff(pixels, out)}",
            EnhanceTestSupport.maxChannelDiff(pixels, out) <= 1,
        )
    }

    @Test
    fun zeroThresholdAmplifiesDetail() {
        val width = 16
        val height = 16
        val pixels = noisy(width, height, base = 128, amplitude = 20, seed = 3L)
        val out = UnsharpMaskProcessor.process(
            pixels, width, height, UnsharpMaskConfig(radius = 2f, amount = 1.5f, threshold = 0),
        )
        assertTrue(
            "sharpen should increase detail variance",
            EnhanceTestSupport.variance(out) > EnhanceTestSupport.variance(pixels),
        )
    }

    @Test
    fun radiusBelowMinimumIsClampedToMinimum() {
        // radius 下限是 0.5（clamp 后由 radiusFromFloat 变成整数半径 1）；
        // 因此 radius=0 与 radius=RADIUS_MIN 必须给出完全一致的结果（而不是恒等）。
        val pixels = noisy(8, 8, 128, 30)
        val zeroRadius = UnsharpMaskProcessor.process(
            pixels, 8, 8, UnsharpMaskConfig(radius = 0f, amount = 1f),
        )
        val minRadius = UnsharpMaskProcessor.process(
            pixels, 8, 8, UnsharpMaskConfig(radius = UnsharpMaskConfig.RADIUS_MIN, amount = 1f),
        )
        assertTrue(identical(zeroRadius, minRadius))
    }

    @Test
    fun preservesAlpha() {
        val pixels = IntArray(64) { argb(120, 130, 140, a = 12) }
        val out = UnsharpMaskProcessor.process(pixels, 8, 8, UnsharpMaskConfig(radius = 2f, amount = 1f))
        for (p in out) assertEquals(12, channel(p, 24))
    }

    @Test
    fun processRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            UnsharpMaskProcessor.process(IntArray(4), 4, 0, UnsharpMaskConfig())
        }
    }

    @Test
    fun processClampsOutOfRangeConfigInsteadOfCrashing() {
        val pixels = step(16, 16, 20, 220)
        val out = UnsharpMaskProcessor.process(
            pixels, 16, 16,
            UnsharpMaskConfig(radius = 999f, amount = -5f, threshold = Int.MIN_VALUE),
        )
        assertEquals(pixels.size, out.size)
    }
}
