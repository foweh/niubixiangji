package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 暗帧扣除单测（纯 JVM）。
 *
 * 覆盖：暗帧平均、`out = max(0, frame - round(dark * gain))`、增益越界 clamp、
 * 坏点置零掩膜、尺寸不符的**降级而非崩溃**语义。
 */
class DarkFrameSubtractorTest {

    @Before
    fun useKotlinPath() {
        // 单测环境没有 native 库，显式锁定纯 Kotlin 路径（幂等）。
        LongExposureNative.forceKotlinForTest()
    }

    @Test
    fun averageDarkFramesAveragesPerSample() {
        val first = shortArrayOf(10, 20, 30)
        val second = shortArrayOf(20, 30, 40)
        val dark = DarkFrameSubtractor.averageDarkFrames(listOf(first, second), 3)
        assertArrayEquals(shortArrayOf(15, 25, 35), dark)
    }

    @Test
    fun averageDarkFramesIgnoresMismatchedFrames() {
        val frames = listOf(
            shortArrayOf(10, 20, 30),
            shortArrayOf(1, 2, 3, 4), // 长度不符 → 忽略
            shortArrayOf(30, 40, 50),
        )
        val dark = DarkFrameSubtractor.averageDarkFrames(frames, 3)
        assertArrayEquals(shortArrayOf(20, 30, 40), dark)
        assertEquals(1, DarkFrameSubtractor.countRejectedDarkFrames(frames, 3))
    }

    @Test
    fun averageDarkFramesReturnsNullWhenNothingUsable() {
        assertNull(DarkFrameSubtractor.averageDarkFrames(emptyList(), 3))
        assertNull("期望尺寸与帧尺寸不符时无可用帧", DarkFrameSubtractor.averageDarkFrames(listOf(shortArrayOf(1, 2)), 3))
        assertNull("expectedSize 非法时无可用帧", DarkFrameSubtractor.averageDarkFrames(listOf(shortArrayOf(1, 2, 3)), 4))
    }

    @Test
    fun subtractRemovesDarkPatternAtUnitGain() {
        val frame = shortArrayOf(100, 500, 4095)
        val dark = shortArrayOf(10, 20, 30)
        assertArrayEquals(
            shortArrayOf(90, 480, 4065),
            DarkFrameSubtractor.subtract(frame, dark, gain = 1f),
        )
    }

    @Test
    fun subtractRoundsHalfUpLikeKotlinMathRound() {
        // dark * 0.5：3->1.5->2、5->2.5->3、7->3.5->4（Math.round 半值上取整）。
        val frame = shortArrayOf(10, 10, 10)
        val dark = shortArrayOf(3, 5, 7)
        assertArrayEquals(
            shortArrayOf(8, 7, 6),
            DarkFrameSubtractor.subtract(frame, dark, gain = 0.5f),
        )
    }

    @Test
    fun subtractClampsNegativeResultToZero() {
        val frame = shortArrayOf(5, 5)
        val dark = shortArrayOf(100, 100)
        assertArrayEquals(shortArrayOf(0, 0), DarkFrameSubtractor.subtract(frame, dark, gain = 1f))
    }

    @Test
    fun subtractGainIsClampedToDocumentedRange() {
        val frame = shortArrayOf(100, 100)
        val dark = shortArrayOf(50, 50)
        // gain 3.0 -> clamp 到 GAIN_MAX = 2.0：100 - 100 = 0
        assertArrayEquals(shortArrayOf(0, 0), DarkFrameSubtractor.subtract(frame, dark, gain = 3f))
        // gain -1 -> clamp 到 0：不做减法
        assertArrayEquals(shortArrayOf(100, 100), DarkFrameSubtractor.subtract(frame, dark, gain = -1f))
    }

    @Test
    fun nanGainFallsBackToUnitGain() {
        val frame = shortArrayOf(100, 100)
        val dark = shortArrayOf(10, 10)
        assertArrayEquals(shortArrayOf(90, 90), DarkFrameSubtractor.subtract(frame, dark, gain = Float.NaN))
    }

    @Test
    fun hotPixelMaskZeroesSamplesAndCountsThem() {
        val frame = shortArrayOf(100, 200, 300, 400)
        val dark = shortArrayOf(10, 1000, 20, 2000)
        val out = DarkFrameSubtractor.subtract(
            frame = frame,
            dark = dark,
            gain = 1f,
            hotPixelThreshold = 1000,
            hotPixelMode = HotPixelMode.ZERO,
        )
        assertArrayEquals(shortArrayOf(90, 0, 280, 0), out)
        assertEquals(2, DarkFrameSubtractor.countHotPixels(dark, 1000))
    }

    @Test
    fun hotPixelMaskIsInactiveWhenThresholdIsZeroOrMaskOff() {
        val frame = shortArrayOf(1000)
        val dark = shortArrayOf(4000)
        // 阈值 0 → 关闭判定，只做减法。
        assertArrayEquals(
            shortArrayOf(0),
            DarkFrameSubtractor.subtract(frame, dark, gain = 1f, hotPixelThreshold = 0, hotPixelMode = HotPixelMode.ZERO),
        )
        // 掩膜关闭 → 只做减法（不置零，虽然 4000 >= 阈值）。
        assertArrayEquals(
            shortArrayOf(0),
            DarkFrameSubtractor.subtract(frame, dark, gain = 1f, hotPixelThreshold = 100, hotPixelMode = HotPixelMode.OFF),
        )
    }

    @Test
    fun mismatchedDarkSizeIsTreatedAsNoDarkFrame() {
        val frame = shortArrayOf(10, 20, 30)
        val wrongSizeDark = shortArrayOf(1, 2)
        assertArrayEquals(
            shortArrayOf(10, 20, 30),
            DarkFrameSubtractor.subtract(frame, wrongSizeDark, gain = 1f),
        )
        assertArrayEquals(shortArrayOf(10, 20, 30), DarkFrameSubtractor.subtract(frame, null, gain = 1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun subtractIntoRejectsMismatchedOutputBuffer() {
        DarkFrameSubtractor.subtractInto(
            frame = shortArrayOf(1, 2, 3),
            dark = null,
            out = ShortArray(2),
        )
    }

    @Test
    fun subtractIntoRespectsTwelveBitCeiling() {
        val frame = shortArrayOf(4095)
        val dark = null
        val out = DarkFrameSubtractor.subtract(frame, dark, gain = 2f)
        assertNotNull(out)
        assertEquals(4095.toShort(), out[0])
    }
}
