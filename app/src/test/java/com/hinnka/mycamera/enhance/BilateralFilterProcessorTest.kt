package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.argb
import com.hinnka.mycamera.enhance.EnhanceTestSupport.channel
import com.hinnka.mycamera.enhance.EnhanceTestSupport.fill
import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.noisy
import com.hinnka.mycamera.enhance.EnhanceTestSupport.step
import com.hinnka.mycamera.enhance.EnhanceTestSupport.variance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速双边降噪单测：恒等性、平坦图不变、噪声方差下降、阶跃边缘保持、行带分片一致性。
 */
class BilateralFilterProcessorTest {

    @Test
    fun strengthZeroIsIdentity() {
        val pixels = noisy(16, 16, 128, 40)
        val out = BilateralFilterProcessor.process(pixels, 16, 16, BilateralConfig(strength = 0f))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun flatImageIsUnchanged() {
        val pixels = fill(16, 16, 100, 150, 200)
        val out = BilateralFilterProcessor.process(
            pixels, 16, 16, BilateralConfig(radius = 3, sigmaSpace = 8f, sigmaColor = 0.2f, strength = 1f),
        )
        assertTrue(
            "flat image must be untouched: maxDiff=${EnhanceTestSupport.maxChannelDiff(pixels, out)}",
            EnhanceTestSupport.maxChannelDiff(pixels, out) <= 1,
        )
    }

    @Test
    fun noiseVarianceIsReduced() {
        val width = 24
        val height = 24
        val pixels = noisy(width, height, base = 120, amplitude = 50, seed = 5L)
        val out = BilateralFilterProcessor.process(
            pixels, width, height,
            BilateralConfig(radius = 3, sigmaSpace = 8f, sigmaColor = 0.25f, strength = 1f),
        )
        assertTrue(
            "bilateral should reduce noise variance: ${variance(pixels)} -> ${variance(out)}",
            variance(out) < variance(pixels) * 0.6,
        )
    }

    @Test
    fun stepEdgeIsPreserved() {
        val width = 16
        val height = 16
        val pixels = step(width, height, 20, 220)
        val config = BilateralConfig(radius = 3, sigmaSpace = 8f, sigmaColor = 0.05f, strength = 1f)
        val out = BilateralFilterProcessor.process(pixels, width, height, config)
        val edgeLeft = out[8 * width + (width / 2 - 1)]
        val edgeRight = out[8 * width + (width / 2)]
        // 值域 σ 很小 → 跨边缘权重≈0 → 阶跃保持（允许 2 LSB 误差）
        assertTrue("left of edge: ${channel(edgeLeft, 16)}", channel(edgeLeft, 16) <= 22)
        assertTrue("right of edge: ${channel(edgeRight, 16)}", channel(edgeRight, 16) >= 218)
    }

    @Test
    fun bandedProcessingMatchesWholeImage() {
        val width = 12
        val height = 12
        val pixels = noisy(width, height, 128, 40, seed = 9L)
        val config = BilateralConfig(radius = 2, sigmaSpace = 6f, sigmaColor = 0.3f, strength = 0.7f)
        val whole = BilateralFilterProcessor.process(pixels, width, height, config, bandHeight = 0)
        val banded = BilateralFilterProcessor.process(pixels, width, height, config, bandHeight = 3)
        assertTrue("banded result must equal whole-image result", identical(whole, banded))
    }

    @Test
    fun chunkCallbackIsInvokedOncePerBand() {
        var chunks = 0
        BilateralFilterProcessor.process(
            pixels = noisy(8, 8, 128, 20),
            width = 8,
            height = 8,
            config = BilateralConfig(radius = 1, strength = 1f),
            bandHeight = 2,
            onChunk = { chunks++ },
        )
        assertEquals(4, chunks)
    }

    @Test
    fun radiusAboveMaximumIsClamped() {
        val pixels = noisy(16, 16, 128, 30)
        val clampedByConfig = BilateralFilterProcessor.process(
            pixels, 16, 16, BilateralConfig(radius = 12, strength = 1f),
        )
        val clampedFromHuge = BilateralFilterProcessor.process(
            pixels, 16, 16, BilateralConfig(radius = 9999, strength = 1f),
        )
        assertTrue(identical(clampedByConfig, clampedFromHuge))
    }

    @Test
    fun preservesAlpha() {
        val pixels = IntArray(64) { argb(90, 110, 130, a = 44) }
        val out = BilateralFilterProcessor.process(
            pixels, 8, 8, BilateralConfig(radius = 2, strength = 1f),
        )
        for (p in out) assertEquals(44, channel(p, 24))
    }

    @Test
    fun processRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            BilateralFilterProcessor.process(IntArray(4), 2, 0, BilateralConfig())
        }
    }

    @Test
    fun processClampsOutOfRangeConfigInsteadOfCrashing() {
        val out = BilateralFilterProcessor.process(
            noisy(8, 8, 128, 20), 8, 8,
            BilateralConfig(
                radius = -3,
                sigmaSpace = Float.NaN,
                sigmaColor = Float.POSITIVE_INFINITY,
                strength = 9f,
            ),
        )
        assertEquals(64, out.size)
    }
}
