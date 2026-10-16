package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 光绘 / 烟火处理管线单测（纯 JVM，同步参考实现）。
 *
 * 覆盖：暗帧扣除、MAX/MEAN 叠加、烟火"发射前基线播种"、帧数开放（超过目标帧数的输入
 * 不再累加但也不报错）、任意分辨率输入的自动重采样、进度回调、空输入兜底。
 */
class LongExposureProcessorsTest {

    @Before
    fun useKotlinPath() {
        LongExposureNative.forceKotlinForTest()
    }

    private fun plane(width: Int, height: Int, fill: Int): LinearRgbPlane =
        LinearRgbPlane(width, height, ShortArray(width * height * 3) { fill.toShort() })

    private val neutralLightPainting = LightPaintingConfig(
        frameCount = 30,
        stackMode = LongExposureStackMode.MAX,
        darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
        brushColorTemperatureK = ColorTemperatureGains.NEUTRAL_KELVIN,
        brushTint = 0f,
        brushGain = 1f,
    )

    // ------------------------------------------------------------ 光绘

    @Test
    fun lightPaintingTakesPerSampleMaximum() {
        val result = LightPaintingProcessor.process(
            frames = listOf(
                LinearRgbPlane(1, 1, shortArrayOf(100, 200, 300)),
                LinearRgbPlane(1, 1, shortArrayOf(150, 100, 250)),
            ),
            config = neutralLightPainting,
        )
        assertNotNull(result)
        assertArrayEquals(shortArrayOf(150, 200, 300), result!!.samples)
    }

    @Test
    fun lightPaintingAppliesWarmBrushColourTemperature() {
        val result = LightPaintingProcessor.process(
            frames = listOf(plane(1, 1, 4000)),
            config = neutralLightPainting.copy(
                frameCount = 2,
                brushColorTemperatureK = 3200,
            ),
        )
        assertNotNull(result)
        assertEquals(4000, result!!.samples[0].toInt())
        assertTrue("暖调必须真的压蓝通道", result.samples[2].toInt() < 4000)
    }

    @Test
    fun lightPaintingSubtractsAverageDarkFrame() {
        val result = LightPaintingProcessor.process(
            frames = listOf(plane(1, 1, 100), plane(1, 1, 120)),
            dark = shortArrayOf(10, 10, 10),
            config = neutralLightPainting.copy(darkFrame = DarkFrameConfig.DEFAULT),
        )
        assertNotNull(result)
        // (100-10) 与 (120-10) 取最大 = 110
        assertArrayEquals(shortArrayOf(110, 110, 110), result!!.samples)
    }

    @Test
    fun lightPaintingStopsAtConfiguredFrameCountButAcceptsMoreFrames() {
        val frames = listOf(plane(1, 1, 10), plane(1, 1, 20), plane(1, 1, 30), plane(1, 1, 40))
        val result = LightPaintingProcessor.process(
            frames = frames,
            config = neutralLightPainting.copy(frameCount = 2),
        )
        assertNotNull(result)
        // 只用前 2 帧：max(10, 20) = 20；后续帧被忽略而不是崩溃。
        assertArrayEquals(shortArrayOf(20, 20, 20), result!!.samples)
    }

    @Test
    fun lightPaintingResamplesMismatchedFramesInsteadOfFailing() {
        val small = plane(2, 2, 1000)
        val large = plane(4, 4, 2000)
        val result = LightPaintingProcessor.process(
            frames = listOf(small, large),
            config = neutralLightPainting,
        )
        assertNotNull(result)
        assertEquals(2, result!!.width)
        assertEquals(2, result.height)
        assertTrue(result.samples.all { it.toInt() == 2000 })
    }

    @Test
    fun lightPaintingReportsMonotonicProgress() {
        val seen = ArrayList<Pair<Int, Int>>()
        LightPaintingProcessor.process(
            frames = listOf(plane(1, 1, 5), plane(1, 1, 6), plane(1, 1, 7)),
            config = neutralLightPainting.copy(frameCount = 3),
            onProgress = { done, total -> seen.add(done to total) },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun lightPaintingReturnsNullOnEmptyInput() {
        assertNull(LightPaintingProcessor.process(emptyList(), config = neutralLightPainting))
        assertNull(FireworksProcessor.process(emptyList()))
    }

    @Test
    fun colourGainsAreSkippedWhenNeutralAndAppliedWhenWarm() {
        assertNull(LightPaintingProcessor.colorGains(neutralLightPainting))
        val warm = LightPaintingProcessor.colorGains(neutralLightPainting.copy(brushColorTemperatureK = 3200))
        assertNotNull(warm)
        assertEquals(1f, warm!![0], 1e-4f)
        assertTrue(warm[2] < 1f)
    }

    @Test
    fun colourGainsClampBrushGainToAvoidBlowingHighlights() {
        val boosted = LightPaintingProcessor.colorGains(
            neutralLightPainting.copy(brushGain = 99f),
        )
        assertNotNull(boosted)
        assertTrue(boosted!!.all { it <= 8f })
    }

    // ------------------------------------------------------------ 烟火

    @Test
    fun fireworksWithoutBaselineUsesMaximumAccumulation() {
        val result = FireworksProcessor.process(
            frames = listOf(
                LinearRgbPlane(1, 1, shortArrayOf(100, 200, 300)),
                LinearRgbPlane(1, 1, shortArrayOf(150, 100, 250)),
            ),
            config = FireworksConfig(
                frameCount = 20,
                stackMode = LongExposureStackMode.MAX,
                darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
                preBurstFrames = 0,
            ),
        )
        assertNotNull(result)
        assertArrayEquals(shortArrayOf(150, 200, 300), result!!.samples)
    }

    @Test
    fun fireworksPreBurstBaselinePreventsDimmingAfterMax() {
        // 前 2 帧建立底色基线（MEAN，暗），第 3 帧更暗仍不得把底色拉低。
        val result = FireworksProcessor.process(
            frames = listOf(plane(1, 1, 10), plane(1, 1, 20), plane(1, 1, 5)),
            config = FireworksConfig(
                frameCount = 20,
                stackMode = LongExposureStackMode.MAX,
                darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
                preBurstFrames = 2,
            ),
        )
        assertNotNull(result)
        // 基线 = mean(10, 20) = 15，且 MAX 后仍为 15。
        assertArrayEquals(shortArrayOf(15, 15, 15), result!!.samples)
    }

    @Test
    fun fireworksMeanModeSkipsPreBurstBaseline() {
        val result = FireworksProcessor.process(
            frames = listOf(plane(1, 1, 10), plane(1, 1, 20), plane(1, 1, 30)),
            config = FireworksConfig(
                frameCount = 20,
                stackMode = LongExposureStackMode.MEAN,
                darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
                preBurstFrames = 3,
            ),
        )
        assertNotNull(result)
        assertArrayEquals(shortArrayOf(20, 20, 20), result!!.samples)
    }

    @Test
    fun fireworksSubtractsDarkFrameAndHonoursFrameCount() {
        val seen = ArrayList<Int>()
        val result = FireworksProcessor.process(
            frames = listOf(plane(1, 1, 500), plane(1, 1, 500), plane(1, 1, 500)),
            dark = shortArrayOf(20, 20, 20),
            config = FireworksConfig(
                frameCount = 2,
                stackMode = LongExposureStackMode.MAX,
                darkFrame = DarkFrameConfig.DEFAULT.copy(frameCount = 4),
                preBurstFrames = 0,
            ),
            onProgress = { done, _ -> seen.add(done) },
        )
        assertNotNull(result)
        assertArrayEquals(shortArrayOf(480, 480, 480), result!!.samples)
        assertEquals("只处理配置的帧数", listOf(1, 2), seen)
    }

    @Test
    fun fireworksPreBurstIsCappedBelowTargetFrames() {
        // preBurstFrames 大于 (目标帧数 - 1) 时必须收敛，不能让基线段吃掉全部帧。
        val result = FireworksProcessor.process(
            frames = listOf(plane(1, 1, 10), plane(1, 1, 100)),
            config = FireworksConfig(
                frameCount = 2,
                stackMode = LongExposureStackMode.MAX,
                darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
                preBurstFrames = 64,
            ),
        )
        assertNotNull(result)
        // 基线段 1 帧（= 10），第 2 帧 MAX 后 = 100。
        assertArrayEquals(shortArrayOf(100, 100, 100), result!!.samples)
    }

    @Test
    fun fireworksHandlesMixedResolutionBurst() {
        val result = FireworksProcessor.process(
            frames = listOf(plane(2, 2, 100), plane(8, 8, 900)),
            config = FireworksConfig(
                frameCount = 20,
                stackMode = LongExposureStackMode.MAX,
                darkFrame = DarkFrameConfig.DEFAULT.copy(enabled = false),
                preBurstFrames = 0,
            ),
        )
        assertNotNull(result)
        assertEquals(2, result!!.width)
        assertEquals(2, result.height)
        assertTrue(result.samples.all { it.toInt() == 900 })
    }
}
