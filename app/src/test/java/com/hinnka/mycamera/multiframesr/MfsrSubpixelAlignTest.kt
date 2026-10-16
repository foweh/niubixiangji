package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 亚像素对齐精度边界：任务契约要求 0.1px。
 *
 * 同时覆盖退化输入（纯色、尺寸不一致、含 NaN）——任何情况都不能抛异常，
 * 只能返回 [MfsrShift.UNDEFINED] 或有限值。
 */
class MfsrSubpixelAlignTest {

    private val w = 160
    private val h = 120

    /** 五种典型位移：零、亚像素、混合符号、大位移（接近上限）、极限亚像素。 */
    private val cases = listOf(
        0f to 0f,
        0.3f to 0.4f,
        1.7f to -2.3f,
        -3.4f to 2.8f,
        0.12f to -0.08f,
    )

    @Test
    fun accuracyContractIsOneTenthPixel() {
        assertEquals(0.1f, MfsrSubpixelAligner.TARGET_ACCURACY_PX, 0f)
    }

    @Test
    fun blockMatchMeetsSubPixelPrecisionOnAllCases() {
        val ref = MfsrTestFixtures.pattern(w, h)
        // cap 取 8 已大于全部测试位移（最大模长 4.4），同时把粗搜候选数控制在千级以内
        val config = MfsrConfig(alignMethod = MfsrAlignMethod.BLOCK_MATCH, maxAlignShiftPx = 8)
        for ((dx, dy) in cases) {
            val tgt = MfsrTestFixtures.shift(ref, dx, dy)
            val est = MfsrSubpixelAligner.estimate(config, ref, tgt)
            val err = hypot(est.dx - dx, est.dy - dy)
            assertTrue(
                "BLOCK_MATCH ($dx,$dy) 误差=$err 超过 0.1px → est=(${est.dx},${est.dy})",
                err <= MfsrSubpixelAligner.TARGET_ACCURACY_PX,
            )
            assertTrue("置信度应为有限值", est.confidence.isFinite())
        }
    }

    @Test
    fun phaseCorrelationMeetsSubPixelPrecisionOnAllCases() {
        val ref = MfsrTestFixtures.pattern(w, h)
        val config = MfsrConfig(alignMethod = MfsrAlignMethod.PHASE_CORRELATION, maxAlignShiftPx = 8)
        for ((dx, dy) in cases) {
            val tgt = MfsrTestFixtures.shift(ref, dx, dy)
            val est = MfsrSubpixelAligner.estimate(config, ref, tgt)
            val err = hypot(est.dx - dx, est.dy - dy)
            assertTrue(
                "PHASE_CORRELATION ($dx,$dy) 误差=$err 超过 0.1px → est=(${est.dx},${est.dy})",
                err <= MfsrSubpixelAligner.TARGET_ACCURACY_PX,
            )
        }
    }

    @Test
    fun largeShiftIsClampedToConfiguredMaximumInsteadOfExploding() {
        val ref = MfsrTestFixtures.pattern(w, h)
        val maxShift = 4
        for (method in listOf(MfsrAlignMethod.BLOCK_MATCH, MfsrAlignMethod.PHASE_CORRELATION)) {
            val config = MfsrConfig(alignMethod = method, maxAlignShiftPx = maxShift)
            val tgt = MfsrTestFixtures.shift(ref, 12f, -9f)
            val est = MfsrSubpixelAligner.estimate(config, ref, tgt)
            assertTrue("结果必须是有限值", est.isFiniteShift)
            assertTrue(
                "平移必须被限制在 ±$maxShift，实际=${est.magnitude}",
                est.magnitude <= maxShift + 0.51f,
            )
        }
    }

    @Test
    fun largeSubpixelShiftJustBelowTheLimitIsStillExact() {
        // 位移模长 5.94 < maxShift 6 —— 贴近上限但不触发限量，精度必须仍然守住 0.1px
        val ref = MfsrTestFixtures.pattern(w, h)
        val config = MfsrConfig(alignMethod = MfsrAlignMethod.BLOCK_MATCH, maxAlignShiftPx = 6)
        val tgt = MfsrTestFixtures.shift(ref, 4.2f, -4.2f)
        val est = MfsrSubpixelAligner.estimate(config, ref, tgt)
        assertEquals(4.2f, est.dx, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
        assertEquals(-4.2f, est.dy, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
        assertTrue(est.magnitude <= 6f)
    }

    @Test
    fun shiftBeyondTheLimitIsScaledDownToTheCapNotSilentlyKept() {
        // 模长 8.49 > maxShift 6 → 按向量长度等比缩到 6（方向不变，不越界）
        val ref = MfsrTestFixtures.pattern(w, h)
        val config = MfsrConfig(alignMethod = MfsrAlignMethod.BLOCK_MATCH, maxAlignShiftPx = 6)
        val tgt = MfsrTestFixtures.shift(ref, 6f, -6f)
        val est = MfsrSubpixelAligner.estimate(config, ref, tgt)
        assertTrue(est.isFiniteShift)
        assertEquals(6f, est.magnitude, 0.05f)
        assertTrue("方向必须保持（dx>0, dy<0）", est.dx > 0f && est.dy < 0f)
        assertEquals(4.2426f, est.dx, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
        assertEquals(-4.2426f, est.dy, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
    }

    @Test
    fun identicalPlanesYieldZeroShift() {
        val ref = MfsrTestFixtures.pattern(w, h)
        for (method in MfsrAlignMethod.entries) {
            val config = MfsrConfig(alignMethod = method)
            val est = MfsrSubpixelAligner.estimate(config, ref, ref.copy())
            assertEquals("$method", 0f, est.dx, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
            assertEquals("$method", 0f, est.dy, MfsrSubpixelAligner.TARGET_ACCURACY_PX)
        }
    }

    @Test
    fun offMethodAndDisabledAlignmentReturnZeroShiftWithoutWork() {
        val ref = MfsrTestFixtures.pattern(w, h)
        val moved = MfsrTestFixtures.shift(ref, 3f, 2f)
        val off = MfsrSubpixelAligner.estimate(MfsrConfig(alignMethod = MfsrAlignMethod.OFF), ref, moved)
        assertEquals(MfsrShift.ZERO, off)
        val disabled = MfsrSubpixelAligner.estimate(MfsrConfig(alignmentEnabled = false), ref, moved)
        assertEquals(MfsrShift.ZERO, disabled)
    }

    @Test
    fun untexturedPlaneIsDegenerateNotCrash() {
        val flat = MfsrPlane(w, h).also { it.fill(100f) }
        for (method in listOf(MfsrAlignMethod.BLOCK_MATCH, MfsrAlignMethod.PHASE_CORRELATION)) {
            val est = MfsrSubpixelAligner.estimate(MfsrConfig(alignMethod = method), flat, flat.copy())
            assertEquals(0f, est.dx, 0f)
            assertEquals(0f, est.dy, 0f)
            assertEquals("无纹理帧置信度必须为 0（上层据此不采纳对齐）", 0f, est.confidence, 0f)
        }
    }

    @Test
    fun mismatchedSizesAreReportedAsUndefined() {
        val ref = MfsrTestFixtures.pattern(w, h)
        val smaller = MfsrTestFixtures.pattern(64, 48)
        val est = MfsrSubpixelAligner.estimate(MfsrConfig(), ref, smaller)
        assertEquals(MfsrShift.UNDEFINED, est)
    }

    @Test
    fun nonFiniteSamplesDoNotProduceNonFiniteShift() {
        val ref = MfsrTestFixtures.pattern(w, h)
        val tgt = MfsrTestFixtures.shift(ref, 1.5f, -0.75f)
        // 注入 NaN / Infinity 脏数据（模拟解码异常帧）
        for (i in 0 until tgt.pixels step 37) tgt.data[i] = Float.NaN
        tgt.data[10] = Float.POSITIVE_INFINITY
        val est = MfsrSubpixelAligner.estimate(MfsrConfig(maxAlignShiftPx = 32), ref, tgt)
        assertTrue("返回值必须可安全使用", est.isFiniteShift)
        assertTrue("置信度必须有限", est.confidence.isFinite())
        assertTrue(abs(est.dx) <= 32.51f && abs(est.dy) <= 32.51f)
    }

    @Test
    fun degenerateShiftZeroAndClampingHelpers() {
        assertEquals(0f, MfsrShift.ZERO.dx, 0f)
        assertEquals(1f, MfsrShift.ZERO.confidence, 0f)
        // 退化值本身按"零平移"安全处理，只有非有限分量才判定不可用
        assertTrue(MfsrShift.UNDEFINED.isFiniteShift)
        assertEquals(0f, MfsrShift.UNDEFINED.confidence, 0f)
        assertFalse(MfsrShift(Float.NaN, 0f, 1f).isFiniteShift)
        val clamped = MfsrShift(30f, 40f, 1f).clampedTo(10f)
        assertEquals(10f, clamped.magnitude, 1e-3f)
        assertEquals(10f, MfsrShift(Float.NaN, 0f, 1f).clampedTo(10f).magnitude, 0f)
    }
}
