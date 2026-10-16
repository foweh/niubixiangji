package com.hinnka.mycamera.focusstack

import com.hinnka.mycamera.focusstack.FocusStackTestSupport.argb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.blurArgb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.checkerArgb
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.luma
import com.hinnka.mycamera.focusstack.FocusStackTestSupport.solidArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清晰度图单测：度量正确性、归一化、平滑、掩模生成边界与缩放一致性。
 */
class FocusClarityMapTest {

    private val width = 32
    private val height = 24

    // ------------------------------------------------------------ 亮度

    @Test
    fun lumaFromArgb_matchesKnownValues() {
        assertEquals(1f, luma(intArrayOf(argb(255, 255, 255)))[0], 1e-4f)
        assertEquals(0f, luma(intArrayOf(argb(0, 0, 0)))[0], 1e-4f)
        // Rec.709：纯绿比纯红亮。
        val red = luma(intArrayOf(argb(255, 0, 0)))[0]
        val green = luma(intArrayOf(argb(0, 255, 0)))[0]
        assertTrue(green > red)
    }

    @Test
    fun lumaFromArgb_respectsCountAndEmptyInput() {
        val pixels = intArrayOf(argb(255, 255, 255), argb(0, 0, 0))
        assertEquals(1, FocusClarityMap.lumaFromArgb(pixels, 1).size)
        assertEquals(0, FocusClarityMap.lumaFromArgb(IntArray(0)).size)
    }

    // ------------------------------------------------------------ 细节响应

    @Test
    fun laplacianResponse_constantImageIsZero() {
        val flat = FocusClarityMap.lumaFromArgb(solidArgb(width, height, argb(128, 128, 128)))
        val response = FocusClarityMap.laplacianResponse(flat, width, height)
        for (v in response) assertEquals(0f, v, 1e-5f)
    }

    @Test
    fun laplacianResponse_highFrequencyPatternIsNonZero() {
        val checker = FocusClarityMap.lumaFromArgb(checkerArgb(width, height, 2))
        val response = FocusClarityMap.laplacianResponse(checker, width, height)
        var nonZero = 0
        for (v in response) if (kotlin.math.abs(v) > 0.1f) nonZero++
        assertTrue("棋盘图案应有大量非零拉普拉斯响应", nonZero > width * height / 4)
    }

    @Test
    fun gradientMagnitude_constantZero_edgeNonZero() {
        val flat = FocusClarityMap.lumaFromArgb(solidArgb(width, height, argb(90, 90, 90)))
        for (v in FocusClarityMap.gradientMagnitude(flat, width, height)) assertEquals(0f, v, 1e-5f)

        // 左黑右白：仅在分界处有梯度。
        val step = IntArray(width * height) { i ->
            if (i % width < width / 2) argb(0, 0, 0) else argb(255, 255, 255)
        }
        val magnitude = FocusClarityMap.gradientMagnitude(luma(step), width, height)
        val edgeIndex = 0 * width + (width / 2 - 1)
        assertTrue("边界处梯度应显著", magnitude[edgeIndex] > 1f)
        assertEquals(0f, magnitude[width / 2 + 2], 1e-4f)
    }

    // ------------------------------------------------------------ 局部统计 / 平滑

    @Test
    fun boxBlur_preservesConstantImage() {
        val flat = FloatArray(9) { 0.4f }
        val blurred = FocusClarityMap.boxBlur(flat, 3, 3, 1)
        for (v in blurred) assertEquals(0.4f, v, 1e-5f)
    }

    @Test
    fun boxBlur_spreadsImpulseAndKeepsEnergyNearby() {
        val impulse = FloatArray(25)
        impulse[12] = 1f
        val blurred = FocusClarityMap.boxBlur(impulse, 5, 5, 1)
        assertTrue(blurred[12] < 1f)
        assertTrue(blurred[11] > 0f)
        // 归一化核：3x3 窗口内均值 = 1/9。
        assertEquals(1f / 9f, blurred[12], 1e-4f)
    }

    @Test
    fun gaussianBlur_zeroRadiusCopies_andReducesVariance() {
        val noise = FloatArray(width * height) { i -> if (i % 3 == 0) 1f else 0f }
        val copied = FocusClarityMap.gaussianBlur(noise, width, height, 0)
        assertTrue(copied.contentEquals(noise))

        val smoothed = FocusClarityMap.gaussianBlur(noise, width, height, 4)
        assertTrue(variance(smoothed) < variance(noise))
    }

    @Test
    fun localVariance_constantIsZero_varyingIsPositive() {
        val flat = FloatArray(16) { 0.5f }
        for (v in FocusClarityMap.localVariance(flat, 4, 4, 1)) assertEquals(0f, v, 1e-6f)

        val varying = FloatArray(16) { if (it % 2 == 0) 0f else 1f }
        val map = FocusClarityMap.localVariance(varying, 4, 4, 1)
        assertTrue(map.max() > 0.1f)
    }

    // ------------------------------------------------------------ 度量对比（核心正确性）

    @Test
    fun computeClarity_sharpImageScoresHigherThanBlurred() {
        val sharp = checkerArgb(width, height, 2)
        val blurred = blurArgb(sharp, width, height, 3)

        for (metric in ClarityMetric.entries) {
            val sharpMap = FocusClarityMap.computeClarity(
                luma(sharp), width, height, metric, 2,
            )
            val blurredMap = FocusClarityMap.computeClarity(
                luma(blurred), width, height, metric, 2,
            )
            val sharpMean = FocusClarityMap.mean(sharpMap)
            val blurredMean = FocusClarityMap.mean(blurredMap)
            assertTrue(
                "$metric: 清晰帧($sharpMean) 应高于失焦帧($blurredMean)",
                sharpMean > blurredMean * 1.5f,
            )
        }
    }

    @Test
    fun computeClarity_degenerateInputsAreSafe() {
        assertEquals(0, FocusClarityMap.computeClarity(FloatArray(0), 0, 0, ClarityMetric.LAPLACIAN_VARIANCE, 2).size)
        val tiny = FocusClarityMap.computeClarity(FloatArray(1) { 0.5f }, 1, 1, ClarityMetric.GRADIENT_ENERGY, 4)
        assertEquals(1, tiny.size)
        assertTrue(tiny[0].isFinite())
    }

    @Test
    fun computeClarity_radiusIsClampedInternally() {
        val sharp = checkerArgb(width, height, 2)
        val veryLarge = FocusClarityMap.computeClarity(luma(sharp), width, height, ClarityMetric.GRADIENT_ENERGY, 9999)
        val maxRadius = FocusClarityMap.computeClarity(
            luma(sharp), width, height, ClarityMetric.GRADIENT_ENERGY, FocusStackConfig.CLARITY_RADIUS_MAX,
        )
        assertTrue(veryLarge.contentEquals(maxRadius))
    }

    // ------------------------------------------------------------ 归一化

    @Test
    fun normalize_outputIsWithinUnitRange() {
        val map = FloatArray(100) { it.toFloat() }
        val normalized = FocusClarityMap.normalize(map)
        for (v in normalized) {
            assertTrue(v >= 0f && v <= 1f)
        }
        assertEquals(0f, normalized[0], 1e-6f)
    }

    @Test
    fun normalize_allZeroReturnsAllZero() {
        val zeros = FocusClarityMap.normalize(FloatArray(16))
        for (v in zeros) assertEquals(0f, v, 0f)
    }

    @Test
    fun normalize_robustToOutliers() {
        // 99% 像素为 0.5，1% 为 1.0：按 99% 分位定标后主体应接近 1.0（不被离群值压暗）。
        val map = FloatArray(1000) { if (it < 990) 0.5f else 1f }
        val normalized = FocusClarityMap.normalize(map)
        assertTrue("主体应接近饱和: ${normalized[0]}", normalized[0] > 0.9f)
        assertTrue(normalized[0] <= 1f)
    }

    @Test
    fun normalize_handlesNaNAndNegative() {
        val map = floatArrayOf(Float.NaN, -1f, 0.5f, 1f)
        val normalized = FocusClarityMap.normalize(map)
        for (v in normalized) assertTrue(v.isFinite() && v >= 0f && v <= 1f)
    }

    // ------------------------------------------------------------ 掩模生成边界

    @Test
    fun softMask_singleFrameIsAllOnes() {
        val clarity = floatArrayOf(0.1f, 0.9f, 0.2f, 0.7f)
        val masks = FocusClarityMap.softMask(listOf(clarity), 2, 2, exponent = 4f, smoothRadius = 0, consistency = 0)
        assertEquals(1, masks.size)
        for (v in masks[0]) assertEquals(1f, v, 1e-5f)
    }

    @Test
    fun softMask_allZeroClarityIsUniform() {
        val masks = FocusClarityMap.softMask(
            listOf(FloatArray(4), FloatArray(4), FloatArray(4)),
            2, 2, exponent = 4f, smoothRadius = 0, consistency = 0,
        )
        assertEquals(3, masks.size)
        for (mask in masks) for (v in mask) assertEquals(1f / 3f, v, 1e-5f)
    }

    @Test
    fun softMask_weightsSumToOneEverywhere() {
        val sharp = FocusClarityMap.computeClarity(
            luma(checkerArgb(width, height, 2)), width, height, ClarityMetric.LAPLACIAN_VARIANCE, 2,
        )
        val blurred = FocusClarityMap.computeClarity(
            luma(blurArgb(checkerArgb(width, height, 2), width, height, 3)),
            width, height, ClarityMetric.LAPLACIAN_VARIANCE, 2,
        )
        val smoothed = FocusClarityMap.gaussianBlur(blurred, width, height, 2)
        val masks = FocusClarityMap.softMask(
            listOf(sharp, smoothed), width, height, exponent = 4f, smoothRadius = 3, consistency = 1,
        )
        assertEquals(2, masks.size)
        for (i in 0 until width * height) {
            val sum = masks[0][i] + masks[1][i]
            assertEquals("像素 $i 掩模和应为 1", 1f, sum, 1e-4f)
            assertTrue(masks[0][i] >= -1e-6f && masks[1][i] >= -1e-6f)
        }
    }

    @Test
    fun softMask_frameCountBoundaries_twoAndTwoHundred() {
        val w = 4
        val h = 4
        for (count in intArrayOf(2, 200)) {
            val clarities = List(count) { k -> FloatArray(w * h) { i -> (k + i + 1).toFloat() } }
            val masks = FocusClarityMap.softMask(clarities, w, h, exponent = 2f, smoothRadius = 0, consistency = 0)
            assertEquals(count, masks.size)
            for (i in 0 until w * h) {
                var sum = 0f
                for (k in 0 until count) sum += masks[k][i]
                assertEquals("帧数=$count 像素 $i", 1f, sum, 1e-3f)
            }
        }
    }

    @Test
    fun softMask_exponentClampedInternally() {
        val clarities = listOf(floatArrayOf(0.2f, 0.8f), floatArrayOf(0.8f, 0.2f))
        val low = FocusClarityMap.softMask(clarities, 2, 1, exponent = -5f, smoothRadius = 0, consistency = 0)
        val high = FocusClarityMap.softMask(clarities, 2, 1, exponent = 100f, smoothRadius = 0, consistency = 0)
        // 指数越大越「赢家通吃」：高指数下 0.8 一侧权重更大。
        assertTrue(high[0][1] >= low[0][1])
    }

    // ------------------------------------------------------------ 一致性滤波边界

    @Test
    fun consistencyRefine_suppressesIsolatedSpike() {
        val w = 3
        val h = 3
        val mask = FloatArray(w * h) { 0.2f }
        mask[4] = 1f // 中心孤立尖峰
        val refined = FocusClarityMap.consistencyRefineSingle(mask, w, h, rounds = 1)
        assertEquals("尖峰应被压回邻域最大值", 0.2f, refined[4], 1e-5f)
        assertEquals(0.2f, refined[0], 1e-5f)
    }

    @Test
    fun consistencyRefine_zeroRoundsIsIdentity_andClamped() {
        val mask = FloatArray(4) { 0.5f }
        assertTrue(FocusClarityMap.consistencyRefineSingle(mask, 2, 2, 0).contentEquals(mask))
        // 越界轮数被 clamp，不抛异常。
        assertTrue(FocusClarityMap.consistencyRefineSingle(mask, 2, 2, 99).size == 4)
        assertTrue(FocusClarityMap.consistencyRefineSingle(mask, 2, 2, -3).size == 4)
    }

    @Test
    fun consistencyRefine_smoothRegionIsUntouched() {
        val w = 3
        val h = 3
        val mask = FloatArray(w * h) { 0.4f }
        val refined = FocusClarityMap.consistencyRefineSingle(mask, w, h, rounds = 2)
        for (v in refined) assertEquals(0.4f, v, 1e-5f)
    }

    // ------------------------------------------------------------ 缩放

    @Test
    fun downsampleArgb_solidColorIsPreserved() {
        val color = argb(12, 200, 77)
        val src = solidArgb(64, 48, color)
        val down = FocusClarityMap.downsampleArgb(src, 64, 48, 16, 12)
        for (c in down) {
            assertEquals((color ushr 16) and 0xFF, (c ushr 16) and 0xFF)
            assertEquals((color ushr 8) and 0xFF, (c ushr 8) and 0xFF)
            assertEquals(color and 0xFF, c and 0xFF)
        }
    }

    @Test
    fun upsampleFloat_sameSizeIsIdentity_andClampsToUnit() {
        val src = floatArrayOf(0f, 0.5f, 1f, 0.25f)
        assertTrue(FocusClarityMap.upsampleFloat(src, 2, 2, 2, 2).contentEquals(src))

        val up = FocusClarityMap.upsampleFloat(src, 2, 2, 4, 4)
        assertEquals(16, up.size)
        for (v in up) assertTrue(v >= 0f && v <= 1f)
    }

    @Test
    fun argMaxMean_picksSharpestFrame() {
        val masks = listOf(FloatArray(4) { 0.1f }, FloatArray(4) { 0.9f }, FloatArray(4) { 0.4f })
        assertEquals(1, FocusClarityMap.argMaxMean(masks))
        assertEquals(0, FocusClarityMap.argMaxMean(emptyList()))
    }

    private fun variance(values: FloatArray): Float {
        val mean = FocusClarityMap.mean(values)
        var sum = 0.0
        for (v in values) {
            val d = (v - mean).toDouble()
            sum += d * d
        }
        return (sum / values.size).toFloat()
    }
}
