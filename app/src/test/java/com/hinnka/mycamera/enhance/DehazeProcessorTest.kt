package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.argb
import com.hinnka.mycamera.enhance.EnhanceTestSupport.channel
import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.meanLuma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暗通道去雾单测：重点覆盖**去雾亮度单调性**、暗通道/最小值滤波边界、透射率取值域。
 */
class DehazeProcessorTest {

    /** 均匀灰雾图（通道值远低于大气光，去雾后必然变暗）。 */
    private fun hazy(width: Int, height: Int, value: Int): IntArray =
        IntArray(width * height) { argb(value, value, value) }

    private val fixedAtmosphere = DehazeConfig(
        enabled = true,
        atmosphereMode = AtmosphereMode.FIXED,
        fixedAtmosphere = 0.9f,
    )

    @Test
    fun strengthZeroIsIdentity() {
        val pixels = hazy(16, 16, 190)
        val out = DehazeProcessor.process(pixels, 16, 16, fixedAtmosphere.copy(strength = 0f))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun dehazeDarkensHazyImageMonotonically() {
        val width = 24
        val height = 24
        val pixels = hazy(width, height, 191) // 0.75
        val strengths = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val means = strengths.map { s ->
            meanLuma(DehazeProcessor.process(pixels, width, height, fixedAtmosphere.copy(strength = s)))
        }
        for (i in 0 until means.size - 1) {
            assertTrue(
                "mean luma must not increase with strength: ${means[i]} -> ${means[i + 1]}",
                means[i] >= means[i + 1] - 1e-4f,
            )
        }
        assertTrue("full strength must darken the hazy image", means.last() < means.first() - 0.2f)
    }

    @Test
    fun darkChannelIsMinimumOverNeighborhood() {
        val width = 5
        val height = 5
        val pixels = IntArray(width * height) { argb(200, 200, 200) }
        val blackIndex = 2 * width + 2
        pixels[blackIndex] = argb(0, 0, 0)
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val dark = DehazeProcessor.darkChannel(image, width, height, radius = 1)
        assertEquals(0f, dark[blackIndex], 1e-6f)
        // (1,1) 的 3×3 窗口覆盖黑点
        assertEquals(0f, dark[1 * width + 1], 1e-6f)
        // (0,0) 的窗口只覆盖 (0..1, 0..1)，不含黑点
        assertEquals(200f / 255f, dark[0], 1e-4f)
    }

    @Test
    fun minFilterClampsAtEdges() {
        val out = DehazeProcessor.minFilter(floatArrayOf(1f, 2f, 3f), 3, 1, radius = 1)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(2f, out[2], 1e-6f)
    }

    @Test
    fun minFilterWithZeroRadiusReturnsCopy() {
        val src = floatArrayOf(0.4f, 0.1f, 0.9f)
        val out = DehazeProcessor.minFilter(src, 3, 1, radius = 0)
        assertEquals(src.toList(), out.toList())
        out[0] = 5f
        assertEquals(0.4f, src[0], 1e-6f)
    }

    @Test
    fun fixedAtmosphereModeReturnsConfiguredValue() {
        val width = 8
        val height = 8
        val pixels = hazy(width, height, 200)
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val dark = DehazeProcessor.darkChannel(image, width, height, 7)
        val atmosphere = DehazeProcessor.estimateAtmosphere(
            image, width, height, dark, fixedAtmosphere.copy(fixedAtmosphere = 0.85f)
        )
        assertEquals(0.85f, atmosphere[0], 1e-6f)
        assertEquals(0.85f, atmosphere[1], 1e-6f)
        assertEquals(0.85f, atmosphere[2], 1e-6f)
    }

    @Test
    fun topDarkestAtmosphereStaysInValidRange() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height) { i ->
            val v = 40 + (i % 16) * 12
            argb(v, v, v)
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val dark = DehazeProcessor.darkChannel(image, width, height, 7)
        val atmosphere = DehazeProcessor.estimateAtmosphere(
            image, width, height, dark, DehazeConfig(atmosphereMode = AtmosphereMode.TOP_DARKEST)
        )
        for (a in atmosphere) {
            assertTrue("atmosphere $a in [0.4, 1]", a in 0.4f..1f)
        }
    }

    @Test
    fun transmissionStaysWithinConfiguredBounds() {
        val width = 16
        val height = 16
        val pixels = hazy(width, height, 210)
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val config = fixedAtmosphere.copy(minTransmission = 0.12f)
        val dark = DehazeProcessor.darkChannel(image, width, height, config.patchRadius)
        val atmosphere = DehazeProcessor.estimateAtmosphere(image, width, height, dark, config)
        val transmission = DehazeProcessor.transmissionMap(image, width, height, atmosphere, config)
        for (t in transmission) {
            assertTrue("t=$t must be clamped to [minTransmission, 1]", t >= config.minTransmission - 1e-6f && t <= 1f + 1e-6f)
        }
    }

    @Test
    fun uniformGrayStaysNeutralAfterDehaze() {
        val width = 12
        val height = 12
        val out = DehazeProcessor.process(hazy(width, height, 205), width, height, fixedAtmosphere)
        for (p in out) {
            assertTrue(channel(p, 16) == channel(p, 8) && channel(p, 8) == channel(p, 0))
        }
    }

    @Test
    fun processPreservesAlphaAndSize() {
        val width = 10
        val height = 6
        val pixels = IntArray(width * height) { argb(180, 180, 180, a = 33) }
        val out = DehazeProcessor.process(pixels, width, height, fixedAtmosphere)
        assertEquals(pixels.size, out.size)
        for (p in out) assertEquals(33, channel(p, 24))
    }

    @Test
    fun processRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            DehazeProcessor.process(IntArray(9), 0, 3, fixedAtmosphere)
        }
    }

    @Test
    fun processClampsOutOfRangeConfigInsteadOfCrashing() {
        val width = 12
        val height = 12
        val pixels = hazy(width, height, 180)
        val out = DehazeProcessor.process(
            pixels, width, height,
            DehazeConfig(
                enabled = true,
                strength = Float.NaN,
                omega = 9f,
                patchRadius = -4,
                atmosphereMode = AtmosphereMode.PERCENTILE,
                atmospherePercentile = Float.POSITIVE_INFINITY,
                minTransmission = 0f,
                refineRadius = 999,
                refineEpsilon = 0f,
            ),
        )
        assertEquals(pixels.size, out.size)
    }
}
