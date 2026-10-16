package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 增量叠加数学与**帧数边界**单测（纯 JVM）。
 *
 * 覆盖：
 *  - MAX 叠加：逐样本取最大、首帧建基线、亮部只增不减；
 *  - MEAN 叠加：逐帧平均、IntArray 求和无溢出（4096 帧 × 12bit 满量程）；
 *  - 帧数边界：正常（30）/ 边界（2 = FRAME_MIN、1000 = 推荐上限、4096 = 绝对上限）/ 异常（尺寸不符）；
 *  - Kotlin 兜底 [LongExposureNative.kotlinSubtractMax] 与
 *    `DarkFrameSubtractor.subtractInto` + [MaxAccumulator] 两条路径**逐位一致**
 *    （这是 C++ 融合路径正确性的等价性前提）。
 */
class MaxAccumulatorTest {

    @Before
    fun useKotlinPath() {
        LongExposureNative.forceKotlinForTest()
    }

    private val threeSamples = { values: IntArray -> ShortArray(values.size) { values[it].toShort() } }

    // ------------------------------------------------------------ MAX 数学

    @Test
    fun maxAccumulatorKeepsPerSampleMaximum() {
        val accumulator = MaxAccumulator(2, 1) // 6 个样本
        accumulator.addFrame(shortArrayOf(10, 20, 30, 40, 50, 60))
        accumulator.addFrame(shortArrayOf(15, 5, 25, 5, 55, 5))
        assertEquals(2, accumulator.frameCount)
        assertArrayEquals(shortArrayOf(15, 20, 30, 40, 55, 60), accumulator.snapshot())
    }

    @Test
    fun maxAccumulatorIsMonotonicAndNeverDecreases() {
        val accumulator = MaxAccumulator(1, 1)
        accumulator.addFrame(shortArrayOf(100, 100, 100))
        val afterFirst = accumulator.snapshot()
        accumulator.addFrame(shortArrayOf(0, 0, 0))
        assertArrayEquals("MAX 叠加遇到更暗的帧不得回退", afterFirst, accumulator.snapshot())
        assertTrue(accumulator.snapshot()[0] >= afterFirst[0])
    }

    @Test
    fun firstFrameSeedsBaselineExactly() {
        val accumulator = MaxAccumulator(1, 1)
        assertFalse(accumulator.isSeeded)
        accumulator.addFrame(shortArrayOf(7, 8, 9))
        assertTrue(accumulator.isSeeded)
        assertEquals(1, accumulator.frameCount)
        assertArrayEquals(shortArrayOf(7, 8, 9), accumulator.snapshot())
    }

    // ------------------------------------------------------------ MEAN 数学

    @Test
    fun meanAccumulatorAveragesFramesAndTruncatesTowardZero() {
        val accumulator = MeanAccumulator(1, 1)
        accumulator.addFrame(shortArrayOf(10, 20, 30))
        accumulator.addFrame(shortArrayOf(11, 21, 31))
        accumulator.addFrame(shortArrayOf(12, 22, 32))
        // 33/3 = 11、63/3 = 21、93/3 = 31
        assertArrayEquals(shortArrayOf(11, 21, 31), accumulator.snapshot())
        // 除法为整数除法（截断），与既有 PsStackType.MEAN 语义一致。
        accumulator.addFrame(shortArrayOf(0, 0, 0))
        assertArrayEquals(shortArrayOf(8, 15, 23), accumulator.snapshot())
    }

    @Test
    fun meanAccumulatorSnapshotOfEmptyAccumulatorIsZeroed() {
        val accumulator = MeanAccumulator(1, 1)
        assertEquals(0, accumulator.frameCount)
        assertArrayEquals(ShortArray(3), accumulator.snapshot())
    }

    // ------------------------------------------------------------ 帧数边界

    @Test
    fun frameCountNormalCaseThirtyFrames() {
        val frames = 30
        val max = MaxAccumulator(1, 1)
        val mean = MeanAccumulator(1, 1)
        for (i in 0 until frames) {
            val value = (i * 10).coerceAtMost(LinearRgbPlane.SAMPLE_MAX)
            val frame = threeSamples(intArrayOf(value, value, value))
            max.addFrame(frame)
            mean.addFrame(frame)
        }
        assertEquals(frames, max.frameCount)
        assertEquals(frames, mean.frameCount)
        assertEquals(290.toShort(), max.snapshot()[0])
        // sum(0,10,...,290) = 4350；4350 / 30 = 145
        assertEquals(145.toShort(), mean.snapshot()[0])
    }

    @Test
    fun frameCountBoundaryMinimumTwoFrames() {
        assertEquals(2, LongExposureLimits.FRAME_MIN)
        val accumulator = MaxAccumulator(1, 1)
        accumulator.addFrame(shortArrayOf(1, 1, 1))
        accumulator.addFrame(shortArrayOf(2, 2, 2))
        assertArrayEquals(shortArrayOf(2, 2, 2), accumulator.snapshot())
    }

    @Test
    fun frameCountBoundaryRecommendedThousandFrames() {
        assertEquals(1000, LongExposureLimits.RECOMMENDED_FRAME_MAX)
        val accumulator = MaxAccumulator(1, 1)
        val frame = shortArrayOf(100, 200, 300)
        repeat(LongExposureLimits.RECOMMENDED_FRAME_MAX) { accumulator.addFrame(frame) }
        assertEquals(1000, accumulator.frameCount)
        assertArrayEquals(frame, accumulator.snapshot())
    }

    @Test
    fun meanAccumulatorDoesNotOverflowAtAbsoluteFrameCap() {
        assertEquals(4096, LongExposureLimits.ABSOLUTE_FRAME_MAX)
        val accumulator = MeanAccumulator(1, 1)
        val saturated = shortArrayOf(
            LinearRgbPlane.SAMPLE_MAX.toShort(),
            LinearRgbPlane.SAMPLE_MAX.toShort(),
            LinearRgbPlane.SAMPLE_MAX.toShort(),
        )
        repeat(LongExposureLimits.ABSOLUTE_FRAME_MAX) { accumulator.addFrame(saturated) }
        // 4095 × 4096 = 16_773_120 « Int.MAX_VALUE：平均结果必须是满量程而不是溢出值。
        assertArrayEquals(saturated, accumulator.snapshot())
    }

    @Test
    fun frameCountBoundaryAboveAbsoluteCapIsAnErrorNotACrashLoop() {
        // 帧数超过 4096 属"异常输入"：由配置 clamp 修正（不在算法层崩溃），
        // 这里验证 clamp 链条本身能把 100000 拉回绝对上限。
        val clamped = FireworksConfig(frameCount = 100_000).clamped()
        assertTrue(clamped.frameCount <= LongExposureLimits.ABSOLUTE_FRAME_MAX)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedFrameSizeIsRejected() {
        MaxAccumulator(1, 1).addFrame(shortArrayOf(1, 2))
    }

    // ------------------------------------------------------------ 等价性

    @Test
    fun kotlinNativeFallbackMatchesSeparateSubtractThenMax() {
        val sampleCount = 9
        val dark = ShortArray(sampleCount) { (it * 37).toShort() }
        val frames = listOf(
            ShortArray(sampleCount) { (it * 111 + 200).toShort() },
            ShortArray(sampleCount) { (it * 53 + 900).toShort() },
            ShortArray(sampleCount) { (it * 7 + 40).toShort() },
        )
        val gain = 1.25f
        val threshold = 300

        // 参考路径：DarkFrameSubtractor.subtractInto + MaxAccumulator（分步，Kotlin）
        val reference = MaxAccumulator(3, 1)
        val scratch = ShortArray(sampleCount)
        var referenceHot = 0
        for (frame in frames) {
            referenceHot += DarkFrameSubtractor.subtractInto(
                frame = frame,
                dark = dark,
                out = scratch,
                gain = gain,
                hotPixelThreshold = threshold,
                hotPixelMode = HotPixelMode.ZERO,
            )
            reference.addFrame(scratch)
        }

        // 融合路径：LongExposureStacker → LongExposureNative.subtractMaxAccumulate
        // （单测无 native 库 → 自动回退 kotlinSubtractMax，两条路径必须逐位一致）
        val fused = LongExposureStacker(3, 1, LongExposureStackMode.MAX)
        for (frame in frames) {
            fused.addFrame(
                frame = frame,
                dark = dark,
                darkConfig = DarkFrameConfig(
                    enabled = true,
                    subtractGain = gain,
                    hotPixelThreshold = threshold,
                    hotPixelMode = HotPixelMode.ZERO,
                ),
            )
        }

        assertArrayEquals(
            "融合路径（暗帧减法 + MAX）必须与分步实现逐位一致",
            reference.snapshot(),
            fused.snapshot(),
        )
        assertEquals(reference.frameCount, fused.accumulatedFrameCount)
        assertEquals(referenceHot, fused.hotPixelCount)
    }

    @Test
    fun kotlinFallbackHonoursHotPixelMaskAndGainClamp() {
        val accum = ShortArray(3)
        val hot = LongExposureNative.kotlinSubtractMax(
            frame = shortArrayOf(100, 200, 300),
            dark = shortArrayOf(4000, 10, 10),
            accum = accum,
            gain = 99f, // clamp 到 2.0
            hotThreshold = 1000,
            maskHotPixels = true,
            seeded = false,
        )
        assertEquals(1, hot)
        assertArrayEquals(shortArrayOf(0, 180, 280), accum)
    }

    @Test
    fun incrementalAccumulatorsFactoryMatchesRequestedMode() {
        assertTrue(IncrementalAccumulators.create(LongExposureStackMode.MAX, 1, 1) is MaxAccumulator)
        assertTrue(IncrementalAccumulators.create(LongExposureStackMode.MEAN, 1, 1) is MeanAccumulator)
    }
}
