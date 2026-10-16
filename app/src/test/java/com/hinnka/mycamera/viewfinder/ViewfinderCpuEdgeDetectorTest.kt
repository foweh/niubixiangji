package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 峰值对焦 CPU 路径单测：Sobel 边缘强度、阈值掩码、覆盖率、降采样，以及异常输入的兜底。
 *
 * 校验的两个对象 [ViewfinderCpuEdgeDetector] / [ViewfinderBgTasks.downscale] 都是纯 Kotlin，
 * 不需要 Android 运行时（`ViewfinderBgTasks.submitHeavyEdgeDetection` 走 BgJobRunner 的部分不在此覆盖）。
 */
class ViewfinderCpuEdgeDetectorTest {

    private val delta = 1e-4f

    /** 4×4：左两列黑、右两列白。 */
    private fun verticalEdge4x4(): IntArray {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return intArrayOf(
            black, black, white, white,
            black, black, white, white,
            black, black, white, white,
            black, black, white, white,
        )
    }

    // ------------------------------------------------------------ 边缘强度

    @Test
    fun flatImageHasNoEdges() {
        val pixels = IntArray(16) { 0xFF808080.toInt() }
        val magnitude = ViewfinderCpuEdgeDetector.edgeMagnitude(pixels, 4, 4)
        assertEquals(16, magnitude.size)
        assertTrue(magnitude.all { it == 0f })
        // 覆盖率必然为 0
        assertEquals(0f, ViewfinderCpuEdgeDetector.coverageRatio(ViewfinderCpuEdgeDetector.peakingMask(magnitude, 0.5f)), delta)
    }

    @Test
    fun verticalEdgeProducesNormalizedGradient() {
        val magnitude = ViewfinderCpuEdgeDetector.edgeMagnitude(verticalEdge4x4(), 4, 4)
        assertEquals(16, magnitude.size)
        // 左侧内部（x=0）同色 → 无梯度
        assertEquals(0f, magnitude[0], delta)
        // 边界处（x=2, y=1）梯度达到理论最大值 1020/1020 = 1.0
        assertEquals(1f, magnitude[1 * 4 + 2], delta)
        // 所有值都必须在 0..1
        assertTrue(magnitude.all { it in 0f..1f })
    }

    @Test
    fun stepScaleChangesSamplingDistance() {
        val pixels = verticalEdge4x4()
        val stepOne = ViewfinderCpuEdgeDetector.edgeMagnitude(pixels, 4, 4, stepScale = 1)
        val stepTwo = ViewfinderCpuEdgeDetector.edgeMagnitude(pixels, 4, 4, stepScale = 2)
        assertEquals(16, stepOne.size)
        assertEquals(16, stepTwo.size)
        // stepScale 只改变采样邻居距离，不影响输出尺寸与取值范围
        assertTrue(stepTwo.all { it in 0f..1f })
        // 非法 stepScale（0 / 负数）被抬到 1，不得崩溃
        assertEquals(16, ViewfinderCpuEdgeDetector.edgeMagnitude(pixels, 4, 4, stepScale = 0).size)
        assertEquals(16, ViewfinderCpuEdgeDetector.edgeMagnitude(pixels, 4, 4, stepScale = -7).size)
    }

    // ------------------------------------------------------------ 异常输入

    @Test
    fun invalidDimensionsReturnEmptyArray() {
        assertTrue(ViewfinderCpuEdgeDetector.edgeMagnitude(IntArray(0), 0, 0).isEmpty())
        assertTrue(ViewfinderCpuEdgeDetector.edgeMagnitude(IntArray(9), 2, 3).isEmpty())
        assertTrue(ViewfinderCpuEdgeDetector.edgeMagnitude(IntArray(9), 3, 2).isEmpty())
        assertTrue(ViewfinderCpuEdgeDetector.edgeMagnitude(IntArray(4), -4, 4).isEmpty())
    }

    @Test
    fun pixelsShorterThanDimensionsReturnEmptyInsteadOfZeroFilled() {
        // 与直方图分析器的"尽力而为"不同：边缘检测宁可返回空数组，也不产出半张假结果
        assertTrue(ViewfinderCpuEdgeDetector.edgeMagnitude(IntArray(8), 4, 4).isEmpty())
    }

    // ---------------------------------------------------------------- 掩码

    @Test
    fun peakingMaskThresholdIsInclusive() {
        val mask = ViewfinderCpuEdgeDetector.peakingMask(
            edgeMagnitude = floatArrayOf(0.79f, 0.80f, 0.81f),
            threshold = 0.80f,
        )
        assertEquals(3, mask.size)
        assertFalse(mask[0])
        assertTrue(mask[1])
        assertTrue(mask[2])
    }

    @Test
    fun peakingMaskClampsThresholdIntoLegalRange() {
        // 高于上限 → clamp 到 0.99：满分边缘仍点亮
        val high = ViewfinderCpuEdgeDetector.peakingMask(floatArrayOf(1f, 0.5f), 5f)
        assertTrue(high[0])
        assertFalse(high[1])
        // 低于下限 → clamp 到 0.05：几乎全部点亮
        val low = ViewfinderCpuEdgeDetector.peakingMask(floatArrayOf(0.04f, 0.06f), -3f)
        assertFalse(low[0])
        assertTrue(low[1])
    }

    @Test
    fun peakingMaskEmptyInput() {
        assertTrue(ViewfinderCpuEdgeDetector.peakingMask(FloatArray(0), 0.5f).isEmpty())
    }

    // -------------------------------------------------------------- 覆盖率

    @Test
    fun coverageRatioCountsMask() {
        assertEquals(0.5f, ViewfinderCpuEdgeDetector.coverageRatio(booleanArrayOf(true, false, true, false)), delta)
        assertEquals(1f, ViewfinderCpuEdgeDetector.coverageRatio(booleanArrayOf(true, true)), delta)
        assertEquals(0f, ViewfinderCpuEdgeDetector.coverageRatio(booleanArrayOf(false)), delta)
        assertEquals(0f, ViewfinderCpuEdgeDetector.coverageRatio(BooleanArray(0)), delta)
    }

    @Test
    fun coverageRatioIsSensitivityRelated() {
        // 同一边缘图，阈值越低覆盖率越高（灵敏度越高点亮越多）
        val magnitude = ViewfinderCpuEdgeDetector.edgeMagnitude(verticalEdge4x4(), 4, 4)
        val sensitive = ViewfinderCpuEdgeDetector.coverageRatio(ViewfinderCpuEdgeDetector.peakingMask(magnitude, 0.65f))
        val conservative = ViewfinderCpuEdgeDetector.coverageRatio(ViewfinderCpuEdgeDetector.peakingMask(magnitude, 0.95f))
        assertTrue(sensitive >= conservative)
    }

    @Test
    fun suggestedPixelBudgetIsDocumented() {
        assertEquals(4_000_000, ViewfinderCpuEdgeDetector.MAX_SUGGESTED_PIXELS)
    }

    // -------------------------------------------------------------- 降采样

    @Test
    fun downscaleUsesNearestNeighbourMapping() {
        // 源图 4×4，像素值 = 下标 → 2×2 应取 (0,0)(0,2)(2,0)(2,2) = 0,2,8,10
        val source = IntArray(16) { it }
        val output = ViewfinderBgTasks.downscale(source, 4, 4, 2, 2)
        assertEquals(4, output.size)
        assertEquals(0, output[0])
        assertEquals(2, output[1])
        assertEquals(8, output[2])
        assertEquals(10, output[3])
    }

    @Test
    fun downscaleIdentityAndUpscaleAreStable() {
        val source = IntArray(9) { it + 100 }
        // 同尺寸 → 原样
        assertEquals(source.toList(), ViewfinderBgTasks.downscale(source, 3, 3, 3, 3).toList())
        // 放大（3×3 → 6×6）为最近邻复制，不越界、不插值
        val upscaled = ViewfinderBgTasks.downscale(source, 3, 3, 6, 6)
        assertEquals(36, upscaled.size)
        assertEquals(100, upscaled[0])          // (0,0) → 源(0,0)
        assertEquals(100, upscaled[1])          // (0,1) → 源(0,0)
        assertEquals(101, upscaled[2])          // (0,2) → 源(0,1)
        assertEquals(103, upscaled[2 * 6 + 0])  // (2,0) → 源(1,0)
        assertEquals(108, upscaled[5 * 6 + 5])  // (5,5) → 源(2,2)
        assertTrue(upscaled.all { it in 100..108 })
    }

    @Test
    fun downscaleInvalidInputsReturnEmpty() {
        assertTrue(ViewfinderBgTasks.downscale(IntArray(16), 4, 4, 0, 2).isEmpty())
        assertTrue(ViewfinderBgTasks.downscale(IntArray(16), 4, 4, 2, -1).isEmpty())
        assertTrue(ViewfinderBgTasks.downscale(IntArray(16), 0, 0, 2, 2).isEmpty())
    }

    @Test
    fun downscaleShorterThanDeclaredSizeFillsZero() {
        // 声明 4×4 但只给 4 个像素：越界位置补 0，绝不读越界
        val output = ViewfinderBgTasks.downscale(intArrayOf(1, 2, 3, 4), 4, 4, 4, 4)
        assertEquals(16, output.size)
        assertEquals(1, output[0])
        assertEquals(4, output[3])
        assertEquals(0, output[4])
        assertEquals(0, output[15])
    }
}
