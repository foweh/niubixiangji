package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 流式叠加器 / 重采样 / 色温增益单测（纯 JVM）。
 *
 * 覆盖：光绘 MAX 叠加（含暗帧）、慢门 MEAN 叠加、预览分辨率上限与色偏、
 * 盒平均下采样数学、色温增益归一化（只衰减不推爆）。
 */
class LongExposureStackerTest {

    @Before
    fun useKotlinPath() {
        LongExposureNative.forceKotlinForTest()
    }

    // ------------------------------------------------------------ MAX 叠加

    @Test
    fun maxModeWithoutDarkFrameKeepsBrightestSample() {
        val stacker = LongExposureStacker(2, 1, LongExposureStackMode.MAX)
        stacker.addFrame(shortArrayOf(10, 20, 30, 40, 50, 60))
        stacker.addFrame(shortArrayOf(15, 5, 25, 5, 55, 5))
        assertEquals(2, stacker.accumulatedFrameCount)
        assertEquals(0, stacker.rejectedFrameCount)
        assertArrayEquals(shortArrayOf(15, 20, 30, 40, 55, 60), stacker.snapshot())
    }

    @Test
    fun maxModeSubtractsDarkFrameBeforeAccumulating() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        val dark = shortArrayOf(10, 10, 10)
        stacker.addFrame(shortArrayOf(100, 5, 4095), dark, DarkFrameConfig.DEFAULT)
        // 5 - 10 -> 0（不为负）
        assertArrayEquals(shortArrayOf(90, 0, 4085), stacker.snapshot())
        stacker.addFrame(shortArrayOf(80, 0, 4095), dark, DarkFrameConfig.DEFAULT)
        assertArrayEquals(shortArrayOf(90, 0, 4085), stacker.snapshot())
    }

    @Test
    fun disabledDarkFrameSkipsSubtractionEntirely() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(
            frame = shortArrayOf(100, 100, 100),
            dark = shortArrayOf(90, 90, 90),
            darkConfig = DarkFrameConfig(enabled = false),
        )
        assertArrayEquals(shortArrayOf(100, 100, 100), stacker.snapshot())
    }

    @Test
    fun hotPixelMaskIsAppliedThroughStackerAndCounted() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(
            frame = shortArrayOf(4000, 4000, 4000),
            dark = shortArrayOf(4000, 10, 10),
            darkConfig = DarkFrameConfig(
                enabled = true,
                subtractGain = 1f,
                hotPixelThreshold = 1000,
                hotPixelMode = HotPixelMode.ZERO,
            ),
        )
        assertEquals(1, stacker.hotPixelCount)
        assertArrayEquals(shortArrayOf(0, 3990, 3990), stacker.snapshot())
    }

    // ------------------------------------------------------------ MEAN 叠加

    @Test
    fun meanModeAveragesAfterDarkSubtraction() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MEAN)
        val dark = shortArrayOf(10, 10, 10)
        stacker.addFrame(shortArrayOf(110, 20, 10), dark, DarkFrameConfig.DEFAULT) // 100, 10, 0
        stacker.addFrame(shortArrayOf(90, 40, 30), dark, DarkFrameConfig.DEFAULT) // 80, 30, 20
        assertArrayEquals(shortArrayOf(90, 20, 10), stacker.snapshot())
    }

    @Test
    fun meanModeRequiresNoDarkFrameToStillWork() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MEAN)
        stacker.addFrame(shortArrayOf(10, 20, 30), null, DarkFrameConfig.DEFAULT)
        stacker.addFrame(shortArrayOf(30, 40, 50), null, DarkFrameConfig.DEFAULT)
        assertArrayEquals(shortArrayOf(20, 30, 40), stacker.snapshot())
    }

    // ------------------------------------------------------------ 异常输入

    @Test
    fun mismatchedFrameIsCountedInsteadOfThrowing() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(shortArrayOf(1, 2)) // 长度不符
        assertEquals(1, stacker.rejectedFrameCount)
        assertEquals(0, stacker.accumulatedFrameCount)
        assertArrayEquals(ShortArray(3), stacker.snapshot())
    }

    @Test
    fun mismatchedDarkFrameIsIgnoredGracefully() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(shortArrayOf(5, 6, 7), shortArrayOf(1, 2), DarkFrameConfig.DEFAULT)
        assertArrayEquals(shortArrayOf(5, 6, 7), stacker.snapshot())
    }

    // ------------------------------------------------------------ 成片 / 预览

    @Test
    fun resultAppliesColourGainsAndKeepsPlaneSize() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(shortArrayOf(1000, 2000, 4000))
        val plane = stacker.result(floatArrayOf(1f, 0.5f, 0.25f))
        assertEquals(1, plane.width)
        assertEquals(1, plane.height)
        assertArrayEquals(shortArrayOf(1000, 1000, 1000), plane.samples)
    }

    @Test
    fun previewPlaneIsBoundedByRequestedLongEdge() {
        val stacker = LongExposureStacker(100, 50, LongExposureStackMode.MAX)
        val frame = ShortArray(100 * 50 * 3) { 4095 }
        stacker.addFrame(frame)
        val preview = stacker.previewPlane(maxLongEdge = 20)
        assertEquals(20, preview.width)
        assertEquals(10, preview.height)
        assertArrayEquals(ShortArray(20 * 10 * 3) { 4095 }, preview.samples)
    }

    @Test
    fun previewPlaneWithoutFramesIsBlackAndDoesNotCrash() {
        val stacker = LongExposureStacker(64, 64, LongExposureStackMode.MAX)
        val preview = stacker.previewPlane(maxLongEdge = 16)
        assertEquals(16, preview.width)
        assertTrue(preview.samples.all { it.toInt() == 0 })
    }

    @Test
    fun previewPlaneAppliesColourGains() {
        val stacker = LongExposureStacker(1, 1, LongExposureStackMode.MAX)
        stacker.addFrame(shortArrayOf(4000, 4000, 4000))
        val preview = stacker.previewPlane(maxLongEdge = 8, colorGains = floatArrayOf(1f, 0.5f, 0f))
        assertArrayEquals(shortArrayOf(4000, 2000, 0), preview.samples)
    }

    // ------------------------------------------------------------ 重采样

    @Test
    fun downsampleAveragesSourceBlocks() {
        // 2x2 -> 1x1，每通道取 4 个样本的盒平均。
        val source = shortArrayOf(
            0, 100, 200,
            400, 500, 600,
            800, 900, 1000,
            1200, 1300, 1400,
        )
        val down = LongExposureResampler.downsample(source, 2, 2, 1, 1)
        assertArrayEquals(shortArrayOf(600, 700, 800), down)
    }

    @Test
    fun downsampleSupportsNonIntegerScaleWithinRange() {
        val source = ShortArray(4 * 4 * 3) { (it % 100).toShort() }
        val down = LongExposureResampler.downsample(source, 4, 4, 3, 3)
        assertEquals(3 * 3 * 3, down.size)
        assertTrue(down.all { it.toInt() in 0..LinearRgbPlane.SAMPLE_MAX })
    }

    @Test
    fun downsampleToSameSizeReturnsCopyNotAlias() {
        val source = shortArrayOf(1, 2, 3)
        val copy = LongExposureResampler.downsample(source, 1, 1, 1, 1)
        assertArrayEquals(source, copy)
        source[0] = 9
        assertEquals(1.toShort(), copy[0])
    }

    @Test
    fun upsampleNearestRepeatsSourcePixels() {
        val source = shortArrayOf(10, 20, 30, 40, 50, 60) // 2x1
        val up = LongExposureResampler.upsampleNearest(source, 2, 1, 4, 1)
        assertArrayEquals(shortArrayOf(10, 20, 30, 10, 20, 30, 40, 50, 60, 40, 50, 60), up)
    }

    // ------------------------------------------------------------ 色温增益

    @Test
    fun warmBrushTemperatureFavoursRedChannel() {
        val gains = ColorTemperatureGains.gainsFor(3200, 0f)
        assertEquals(3, gains.size)
        assertEquals(1f, gains[0], 1e-4f)
        assertTrue("暖色温应压低蓝通道", gains[2] < gains[1])
        assertTrue(gains[1] < gains[0])
    }

    @Test
    fun coolBrushTemperatureFavoursBlueChannel() {
        val gains = ColorTemperatureGains.gainsFor(12000, 0f)
        assertEquals(1f, gains[2], 1e-4f)
        assertTrue("冷色温应压低红通道", gains[0] < gains[2])
        assertTrue(gains.all { it <= 1f })
    }

    @Test
    fun neutralBrushTemperatureIsAlmostUnity() {
        val gains = ColorTemperatureGains.gainsFor(ColorTemperatureGains.NEUTRAL_KELVIN, 0f)
        assertEquals(1f, gains[0], 1e-4f)
        assertTrue("6500K 应接近中性", kotlin.math.abs(gains[1] - 1f) < 0.01f)
        assertTrue(kotlin.math.abs(gains[2] - 1f) < 0.03f)
    }

    @Test
    fun tintShiftsGreenChannel() {
        val magenta = ColorTemperatureGains.gainsFor(6500, 1f)
        val green = ColorTemperatureGains.gainsFor(6500, -1f)
        // 正 tint = 偏品红（压绿），负 tint = 偏绿；两者都归一化到最大通道 = 1。
        assertTrue(magenta[1] <= green[1] || magenta[1] < 1f)
        assertTrue(green[1] == 1f || green[1] > magenta[1])
    }

    @Test
    fun outOfRangeKelvinFallsBackToNeutralInsteadOfCrashing() {
        val gains = ColorTemperatureGains.gainsFor(10, 0f)
        assertEquals(1f, gains[0], 1e-4f)
        assertTrue(gains.size == 3)
    }

    @Test
    fun applyGainsNeverExceedsTwelveBitCeiling() {
        val samples = shortArrayOf(4000, 4000, 4000)
        val out = ColorTemperatureGains.applyGains(samples, floatArrayOf(4f, 4f, 4f))
        assertArrayEquals(shortArrayOf(4095, 4095, 4095), out)
        assertArrayEquals("入参不得被修改", shortArrayOf(4000, 4000, 4000), samples)
    }

    @Test
    fun gainsAreNeutralDetectionMatchesDocumentedRule() {
        assertTrue(ColorTemperatureGains.isNeutral(6500, 0f, 1f))
        assertFalse("3200K 是刻意的暖调，不算中性", ColorTemperatureGains.isNeutral(3200, 0f, 1f))
        assertFalse(ColorTemperatureGains.isNeutral(6500, 0.2f, 1f))
        assertFalse(ColorTemperatureGains.isNeutral(6500, 0f, 2f))
    }

    @Test
    fun colorTemperatureConfigRangeMatchesGainRange() {
        assertEquals(ColorTemperatureGains.MIN_KELVIN, LightPaintingConfig.COLOR_TEMP_MIN)
        assertEquals(ColorTemperatureGains.MAX_KELVIN, LightPaintingConfig.COLOR_TEMP_MAX)
        assertNotNull(ColorTemperatureGains.gainsFor(LightPaintingConfig.COLOR_TEMP_MIN, 0f))
    }

    @Test
    fun planeRejectsInconsistentSampleCount() {
        assertThrows(IllegalArgumentException::class.java) { LinearRgbPlane(2, 2, ShortArray(5)) }
    }

    @Test
    fun stackerRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) {
            LongExposureStacker(0, 4, LongExposureStackMode.MAX)
        }
    }
}
