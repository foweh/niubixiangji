package com.hinnka.mycamera.focusstack

import com.hinnka.mycamera.focusstack.FocusStackTestSupport.argb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.blurArgb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.blurRegionArgb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.checkerArgb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.identical
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.luma
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.maxChannelDiff
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.noiseArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 融合单测：清晰度量化、加权累加、掩模驱动的「选清晰」行为、融合结果一致性、平移对齐。
 */
class FocusStackFusionTest {

    private val width = 32
    private val height = 32

    // ------------------------------------------------------------ 清晰度量化

    @Test
    fun quantizeClarity_clampsAndRounds() {
        val quantized = FocusStackFusion.quantizeClarity(
            floatArrayOf(0f, 0.5f, 1f, 2f, -1f, Float.NaN),
        )
        assertEquals(0, quantized[0].toInt() and 0xFF)
        assertEquals(128, quantized[1].toInt() and 0xFF)
        assertEquals(255, quantized[2].toInt() and 0xFF)
        assertEquals(255, quantized[3].toInt() and 0xFF)
        assertEquals(0, quantized[4].toInt() and 0xFF)
        assertEquals(0, quantized[5].toInt() and 0xFF)
    }

    @Test
    fun quantizeRoundTrip_isWithinOneOver255() {
        val source = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val restored = FocusStackFusion.dequantizeClarity(FocusStackFusion.quantizeClarity(source))
        for (i in source.indices) {
            assertEquals(source[i], restored[i], 1f / 255f + 1e-6f)
        }
    }

    // ------------------------------------------------------------ 加权累加

    @Test
    fun accumulateWeighted_knownValues() {
        val acc = FloatArray(3)
        FocusStackFusion.accumulateWeighted(acc, floatArrayOf(0.5f), intArrayOf(argb(16, 32, 48)), 1)
        assertEquals(8f, acc[0], 1e-4f)
        assertEquals(16f, acc[1], 1e-4f)
        assertEquals(24f, acc[2], 1e-4f)
    }

    @Test
    fun accumulateWeighted_ignoresZeroAndNaNAndOutOfRange() {
        val acc = FloatArray(3)
        FocusStackFusion.accumulateWeighted(acc, floatArrayOf(0f), intArrayOf(argb(10, 10, 10)), 1)
        FocusStackFusion.accumulateWeighted(acc, floatArrayOf(Float.NaN), intArrayOf(argb(10, 10, 10)), 1)
        for (v in acc) assertEquals(0f, v, 0f)
        // count 大于实际数组长度时不越界、不抛异常。
        FocusStackFusion.accumulateWeighted(acc, floatArrayOf(1f, 1f, 1f), intArrayOf(0xFF010101.toInt()), 3)
        assertEquals(1f, acc[0], 1e-4f)
    }

    @Test
    fun finalizeArgb_appliesStrengthAndClamps() {
        // 无基准帧：直接用累加值。
        val fused = FocusStackFusion.finalizeArgb(floatArrayOf(10f, 20f, 30f), 1, 1f, null)
        assertEquals(10, (fused[0] ushr 16) and 0xFF)
        assertEquals(20, (fused[0] ushr 8) and 0xFF)
        assertEquals(30, fused[0] and 0xFF)
        assertEquals(255, (fused[0] ushr 24) and 0xFF)

        // 融合强度 0：完全取基准帧。
        val base = intArrayOf(argb(1, 2, 3))
        val baseOnly = FocusStackFusion.finalizeArgb(floatArrayOf(200f, 200f, 200f), 1, 0f, base)
        assertEquals(1, (baseOnly[0] ushr 16) and 0xFF)
        assertEquals(2, (baseOnly[0] ushr 8) and 0xFF)
        assertEquals(3, baseOnly[0] and 0xFF)

        // 溢出 / NaN 都被钳位。
        val clamped = FocusStackFusion.finalizeArgb(floatArrayOf(10000f, Float.NaN, -5f), 1, 1f, null)
        assertEquals(255, (clamped[0] ushr 16) and 0xFF)
        assertEquals(0, (clamped[0] ushr 8) and 0xFF)
        assertEquals(0, clamped[0] and 0xFF)
    }

    @Test
    fun finalizeArgb_strengthHalfIsInterpolation() {
        val base = intArrayOf(argb(100, 100, 100))
        val out = FocusStackFusion.finalizeArgb(floatArrayOf(200f, 200f, 200f), 1, 0.5f, base)
        assertEquals(150, (out[0] ushr 16) and 0xFF)
    }

    // ------------------------------------------------------------ 掩模 × 累加 的数学一致性

    @Test
    fun fusion_matchesManualWeightedAverage() {
        val w = 4
        val h = 4
        val frames = listOf(
            IntArray(w * h) { argb(10, 20, 30) },
            IntArray(w * h) { argb(200, 100, 0) },
            IntArray(w * h) { argb(0, 250, 255) },
        )
        val masks = FocusClarityMap.softMask(
            listOf(FloatArray(w * h) { 0.1f }, FloatArray(w * h) { 0.9f }, FloatArray(w * h) { 0.5f }),
            w, h, exponent = 4f, smoothRadius = 0, consistency = 0,
        )
        val acc = FloatArray(w * h * 3)
        for (k in frames.indices) {
            FocusStackFusion.accumulateWeighted(acc, masks[k], frames[k], w * h)
        }
        val fused = FocusStackFusion.finalizeArgb(acc, w * h, 1f, null)

        val index = 5
        for (shift in intArrayOf(16, 8, 0)) {
            var expected = 0f
            for (k in frames.indices) {
                expected += masks[k][index] * ((frames[k][index] ushr shift) and 0xFF)
            }
            val actual = (fused[index] ushr shift) and 0xFF
            assertEquals(expected, actual.toFloat(), 1.5f)
        }
    }

    // ------------------------------------------------------------ 融合结果一致性

    @Test
    fun fusion_identicalFramesReproduceInput() {
        val frame = checkerArgb(width, height, 2)
        val frames = List(6) { frame.copyOf() }
        val fused = fuseChain(frames, exponent = 4f, smoothRadius = 2, consistency = 0)
        assertEquals("完全相同帧的融合应还原原图", 0, maxChannelDiff(frame, fused))
    }

    @Test
    fun fusion_identicalFrames_twoAndTwoHundredBoundaries() {
        val frame = checkerArgb(8, 8, 2)
        for (count in intArrayOf(2, 200)) {
            val frames = List(count) { frame.copyOf() }
            val fused = fuseChain(frames, exponent = 4f, smoothRadius = 0, consistency = 0)
            assertEquals("帧数=$count", 0, maxChannelDiff(frame, fused))
        }
    }

    @Test
    fun fusion_selection_usesSharpRegionOfEachFrame() {
        val base = checkerArgb(width, height, 2)
        val half = width / 2
        // frame0：左半清晰、右半失焦；frame1：左半失焦、右半清晰。
        val frame0 = blurRegionArgb(base, width, height, half, width, radius = 3)
        val frame1 = blurRegionArgb(base, width, height, 0, half, radius = 3)

        val fused = fuseChain(listOf(frame0, frame1), exponent = 4f, smoothRadius = 2, consistency = 0)

        // 左半内部：应取 frame0（= 清晰原图）。
        val leftIndex = 16 * width + 3
        assertTrue(
            "左半应取 frame0: fused=${(fused[leftIndex] ushr 16) and 0xFF} frame0=${(frame0[leftIndex] ushr 16) and 0xFF}",
            maxChannelDiff(intArrayOf(fused[leftIndex]), intArrayOf(frame0[leftIndex])) <= 40,
        )
        // 右半内部：应取 frame1。
        val rightIndex = 16 * width + (width - 4)
        assertTrue(
            "右半应取 frame1: fused=${(fused[rightIndex] ushr 16) and 0xFF} frame1=${(frame1[rightIndex] ushr 16) and 0xFF}",
            maxChannelDiff(intArrayOf(fused[rightIndex]), intArrayOf(frame1[rightIndex])) <= 40,
        )
    }

    @Test
    fun fusion_strengthZero_returnsBaseFrame() {
        val base = checkerArgb(width, height, 2)
        val blurred = blurArgb(base, width, height, 3)
        val fused = fuseChain(listOf(base, blurred), exponent = 4f, smoothRadius = 2, consistency = 0, strength = 0f)
        // 基准帧 = 清晰度均值最高者 = base。
        assertTrue(maxChannelDiff(base, fused) <= 4)
    }

    @Test
    fun fuseUniform_isAverage() {
        val a = IntArray(4) { argb(0, 0, 0) }
        val b = IntArray(4) { argb(255, 255, 255) }
        val fused = FocusStackFusion.fuseUniform(listOf(a, b), 4)
        for (c in fused) assertEquals(128, (c ushr 16) and 0xFF)

        val same = IntArray(4) { argb(9, 8, 7) }
        val single = FocusStackFusion.fuseUniform(listOf(same), 4)
        assertTrue(identical(same, single))
        assertTrue(FocusStackFusion.fuseUniform(emptyList(), 4).isEmpty())
    }

    // ------------------------------------------------------------ 平移对齐

    @Test
    fun estimateShift_recoversKnownTranslation() {
        val refPixels = noiseArgb(width, height, seed = 4242L)
        val ref = luma(refPixels)
        // tgt(x,y) = ref(x-3, y+2)：目标帧相对参考帧平移。
        val tgtPixels = FocusStackFusion.shiftArgb(refPixels, width, height, 3, -2)
        val tgt = luma(tgtPixels)

        val shift = FocusStackFusion.estimateShift(ref, tgt, width, height, maxShift = 6)
        assertEquals(-3, shift[0])
        assertEquals(2, shift[1])

        // 用估计量回代：内部区域应与参考帧一致。
        val aligned = FocusStackFusion.shiftArgb(tgtPixels, width, height, shift[0], shift[1])
        for (y in 5 until height - 5) {
            for (x in 5 until width - 5) {
                val i = y * width + x
                assertEquals("($x,$y) 应对齐", refPixels[i], aligned[i])
            }
        }
    }

    @Test
    fun estimateShift_zeroOrInvalidShiftReturnsOriginal() {
        val ref = luma(noiseArgb(width, height))
        assertEquals(0, FocusStackFusion.estimateShift(ref, ref, width, height, 0)[0])
        assertEquals(0, FocusStackFusion.estimateShift(ref, ref, width, height, -5)[1])
        assertEquals(0, FocusStackFusion.estimateShift(FloatArray(0), FloatArray(0), 0, 0, 8)[0])
    }

    @Test
    fun estimateShift_identicalImagesGiveZeroShift() {
        val ref = luma(noiseArgb(width, height, seed = 7L))
        val shift = FocusStackFusion.estimateShift(ref, ref, width, height, maxShift = 4)
        assertEquals(0, shift[0])
        assertEquals(0, shift[1])
    }

    @Test
    fun shiftArgb_edgeClampingAndIdentity() {
        val pixels = IntArray(width * height) { argb(it % 256, (it / 4) % 256, 0) }
        assertTrue(FocusStackFusion.shiftArgb(pixels, width, height, 0, 0).contentEquals(pixels))

        val shifted = FocusStackFusion.shiftArgb(pixels, width, height, 2, 1)
        // 左上角越界处按钳位复制：取源 (0,0)。
        assertEquals(pixels[0], shifted[0])
        // 正常内部位置：out(x,y) = src(x-2, y-1)。
        assertEquals(pixels[5 * width + 7], shifted[6 * width + 9])
    }

    @Test
    fun shiftFloat_andDownsampleAreConsistent() {
        val src = FloatArray(width * height) { it.toFloat() }
        val same = FocusStackFusion.shiftFloat(src, width, height, 0, 0)
        assertTrue(same.contentEquals(src))

        val down = FocusStackFusion.downsampleFloat(src, width, height, 8, 8)
        assertEquals(64, down.size)
        for (v in down) assertTrue(v.isFinite())
        // 全常量图降采样后仍是常量。
        val constant = FloatArray(width * height) { 0.5f }
        val downConstant = FocusStackFusion.downsampleFloat(constant, width, height, 8, 8)
        for (v in downConstant) assertEquals(0.5f, v, 1e-6f)
    }

    // ------------------------------------------------------------ 端到端纯算法链路

    /** 纯 Kotlin 融合链路（不含 PLog / Android 依赖）：清晰度 → 软掩模 → 累加 → 落像素。 */
    private fun fuseChain(
        frames: List<IntArray>,
        exponent: Float,
        smoothRadius: Int,
        consistency: Int,
        strength: Float = 1f,
    ): IntArray {
        val size = width * height
        val clarities = frames.map { frame ->
            FocusClarityMap.computeClarity(
                luma(frame), width, height, ClarityMetric.LAPLACIAN_VARIANCE,
                FocusStackConfig.DEFAULT_CLARITY_RADIUS,
            )
        }
        val masks = FocusClarityMap.softMask(clarities, width, height, exponent, smoothRadius, consistency)
        val acc = FloatArray(size * 3)
        var baseIndex = FocusClarityMap.argMaxMean(clarities)
        var basePixels: IntArray? = null
        for (k in frames.indices) {
            val frame = frames[k]
            val frameSize = minOf(size, frame.size)
            val mask = if (masks[k].size >= frameSize) masks[k] else masks[k].copyOf(frameSize)
            FocusStackFusion.accumulateWeighted(acc, mask, frame, frameSize)
            if (k == baseIndex) basePixels = frame.copyOf()
        }
        assertFalse(basePixels == null)
        return FocusStackFusion.finalizeArgb(acc, size, strength, basePixels)
    }
}
