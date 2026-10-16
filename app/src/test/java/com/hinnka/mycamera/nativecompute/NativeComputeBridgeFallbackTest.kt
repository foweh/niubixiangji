package com.hinnka.mycamera.nativecompute

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 计算层桥的**可用性与回退分支**单测（纯 JVM）。
 *
 * 测试环境没有 `libmy-native-lib.so`（宿主是桌面 JVM，不是 arm64 设备），因此这里天然就是
 * PATTERN_SPEC §10.4 要求的「库加载失败 / 函数缺失」场景：**每一个入口都必须安静地返回失败哨兵，
 * 由调用方回退纯 Kotlin 实现，绝不抛异常、绝不崩溃**。
 *
 * 覆盖：
 *  - 加载状态：`ensureLoaded()` 幂等、`isLibraryLoaded()`、`abiVersion()` 哨兵、`available` 为 false；
 *  - 强制兜底钩子：[NativeComputeBridge.forceKotlinForTest] / [NativeComputeBridge.resetTestHooks]；
 *  - 16 个能力入口在本环境下的返回值形状（null / false / -1）与"参数非法也返回哨兵"的行为；
 *  - 入口对 NaN / 尺寸不符 / 空缓冲的健壮性（不抛异常）。
 */
class NativeComputeBridgeFallbackTest {

    @Before
    fun reset() {
        NativeComputeBridge.resetTestHooks()
    }

    @After
    fun tearDown() {
        NativeComputeBridge.resetTestHooks()
    }

    // ------------------------------------------------------------- 加载状态

    @Test
    fun nativeLibraryIsUnavailableOnHostJvm() {
        // 主机 JVM 没有该 .so：加载必然失败，且不得抛出
        assertFalse(NativeComputeBridge.ensureLoaded())
        assertFalse(NativeComputeBridge.isLibraryLoaded())
        assertFalse(NativeComputeBridge.available)
    }

    @Test
    fun abiProbeReturnsUnavailableSentinelWhenLibraryMissing() {
        assertEquals(NativeComputeBridge.UNAVAILABLE_INT, NativeComputeBridge.abiVersion())
    }

    @Test
    fun ensureLoadedIsIdempotent() {
        val first = NativeComputeBridge.ensureLoaded()
        val second = NativeComputeBridge.ensureLoaded()
        assertEquals(first, second)
        assertFalse(first)
    }

    @Test
    fun forceKotlinForTestDrivesFallbackPathAndCanBeReset() {
        NativeComputeBridge.forceKotlinForTest()
        assertFalse(NativeComputeBridge.ensureLoaded())
        assertFalse(NativeComputeBridge.available)
        NativeComputeBridge.resetTestHooks()
        assertFalse(NativeComputeBridge.ensureLoaded()) // 仍然不可用，但判定原因回到"库缺失"
    }

    // ------------------------------------------------- MFSR 入口回退与健壮性

    @Test
    fun phaseCorrelateFallsBackWithNull() {
        val plane = FloatArray(16 * 16)
        assertNull(NativeComputeBridge.phaseCorrelate(plane, plane, 16, 16, 8, 512))
        // 参数非法（缓冲过短 / 尺寸非正）同样只返回 null
        assertNull(NativeComputeBridge.phaseCorrelate(FloatArray(3), plane, 16, 16, 8, 512))
        assertNull(NativeComputeBridge.phaseCorrelate(plane, plane, 0, 16, 8, 512))
    }

    @Test
    fun shiftAddAccumulateFallsBackWithMinusOneSentinel() {
        val lr = FloatArray(4 * 4)
        val sum = FloatArray(8 * 8)
        val weight = FloatArray(8 * 8)
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_FLOAT,
            NativeComputeBridge.shiftAddAccumulate(lr, sum, weight, 4, 4, 8, 8, 2f, 0.5f, -0.25f),
            EPS,
        )
        // 非法 scale / 非法平移 / 缓冲不足
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_FLOAT,
            NativeComputeBridge.shiftAddAccumulate(lr, sum, weight, 4, 4, 8, 8, 0f, 0.5f, 0f),
            EPS,
        )
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_FLOAT,
            NativeComputeBridge.shiftAddAccumulate(lr, sum, weight, 4, 4, 8, 8, 2f, Float.NaN, 0f),
            EPS,
        )
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_FLOAT,
            NativeComputeBridge.shiftAddAccumulate(FloatArray(1), sum, weight, 4, 4, 8, 8, 2f, 0f, 0f),
            EPS,
        )
    }

    @Test
    fun resolveShiftAddFallsBackWithFalse() {
        val size = 8 * 8
        assertFalse(
            NativeComputeBridge.resolveShiftAdd(
                FloatArray(size), FloatArray(size), FloatArray(size), 8, 8, 8,
            ),
        )
        assertFalse(NativeComputeBridge.resolveShiftAdd(FloatArray(2), FloatArray(2), FloatArray(2), 8, 8, 8))
    }

    @Test
    fun ibpAccumulateFrameFallsBackWithNull() {
        val hr = FloatArray(8 * 8)
        val lr = FloatArray(4 * 4)
        assertNull(
            NativeComputeBridge.ibpAccumulateFrame(
                hr, lr, FloatArray(64), FloatArray(64), 8, 8, 4, 4, 0.25f, -0.5f, 2f,
            ),
        )
        // 非法 scale
        assertNull(
            NativeComputeBridge.ibpAccumulateFrame(
                hr, lr, FloatArray(64), FloatArray(64), 8, 8, 4, 4, 0f, 0f, Float.NaN,
            ),
        )
    }

    @Test
    fun ibpApplyStepFallsBackWithFalse() {
        val size = 8 * 8
        assertFalse(NativeComputeBridge.ibpApplyStep(FloatArray(size), FloatArray(size), FloatArray(size), size, 0.85f))
        assertFalse(NativeComputeBridge.ibpApplyStep(FloatArray(size), FloatArray(size), FloatArray(size), 0, 0.85f))
        assertFalse(NativeComputeBridge.ibpApplyStep(FloatArray(1), FloatArray(1), FloatArray(1), 64, 0.85f))
    }

    @Test
    fun bilateralLumaAndUnsharpLumaFallBackWithFalse() {
        val src = FloatArray(16 * 16)
        val dst = FloatArray(16 * 16)
        assertFalse(NativeComputeBridge.bilateralLuma(src, dst, 16, 16, 2, 18f, 0.85f))
        assertFalse(NativeComputeBridge.unsharpLuma(src, dst, 16, 16, 1f, 1.2f))
        // amount <= 0 直接短路（不做无意义的 native 调用）
        assertFalse(NativeComputeBridge.unsharpLuma(src, dst, 16, 16, 0f, 1.2f))
        assertFalse(NativeComputeBridge.unsharpLuma(src, dst, 16, 16, Float.NaN, 1.2f))
    }

    @Test
    fun clampPlaneFallsBackWithFalse() {
        val data = FloatArray(64) { 300f }
        assertFalse(NativeComputeBridge.clampPlane(data, data.size, 0f, 255f))
        assertFalse(NativeComputeBridge.clampPlane(data, 0, 0f, 255f))
        assertFalse(NativeComputeBridge.clampPlane(data, data.size + 1, 0f, 255f))
        assertFalse(NativeComputeBridge.clampPlane(data, data.size, Float.NaN, 255f))
        // 回退路径下数据必须保持原样（桥不得"半途"改写调用方缓冲）
        assertEquals(300f, data[0], EPS)
    }

    // ------------------------------------------------------- 叠加层入口回退

    @Test
    fun accumulateEntriesFallBackWithSentinels() {
        val frame = ShortArray(64) { 100 }
        val accum = ShortArray(64)
        val sum = IntArray(64)
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.accumulateMax(frame, null, accum, 64, 1f, 0, false, false),
        )
        assertFalse(NativeComputeBridge.accumulateMean(sum, frame, 64))
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.subtractMeanAccumulate(frame, null, sum, 64, 1f, 0, false),
        )
        // 累加器不得被回退路径污染
        assertEquals(0, sum[0])
        assertEquals(0, accum[0].toInt())
    }

    @Test
    fun accumulateEntriesRejectSizeMismatchWithoutThrowing() {
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.accumulateMax(ShortArray(8), null, ShortArray(4), 64, 1f, 0, false, false),
        )
        assertFalse(NativeComputeBridge.accumulateMean(IntArray(4), ShortArray(64), 64))
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.subtractMeanAccumulate(ShortArray(64), null, IntArray(4), 64, 1f, 0, false),
        )
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.accumulateMax(ShortArray(64), null, ShortArray(64), 0, 1f, 0, false, false),
        )
    }

    // ------------------------------------------------------- 后处理入口回退

    @Test
    fun claheEqualizeFallsBackWithNull() {
        val luma = FloatArray(32 * 32) { 0.5f }
        assertNull(NativeComputeBridge.claheEqualize(luma, 32, 32, 8, 8, 2f))
        assertNull(NativeComputeBridge.claheEqualize(FloatArray(4), 32, 32, 8, 8, 2f))
        assertNull(NativeComputeBridge.claheEqualize(luma, 0, 32, 8, 8, 2f))
    }

    @Test
    fun applyLumaGainFallsBackWithFalse() {
        val size = 32 * 32
        assertFalse(
            NativeComputeBridge.applyLumaGain(
                FloatArray(size), FloatArray(size), FloatArray(size), FloatArray(size), FloatArray(size), size, 1f,
            ),
        )
        assertFalse(
            NativeComputeBridge.applyLumaGain(FloatArray(4), FloatArray(4), FloatArray(4), FloatArray(4), FloatArray(4), 8, 1f),
        )
        assertFalse(
            NativeComputeBridge.applyLumaGain(FloatArray(size), FloatArray(size), FloatArray(size), FloatArray(size), FloatArray(size), 0, 1f),
        )
    }

    @Test
    fun bilateralRowsFallsBackWithFalse() {
        val size = 32 * 32
        val plane = FloatArray(size)
        assertFalse(
            NativeComputeBridge.bilateralRows(
                plane, plane, plane, FloatArray(size), FloatArray(size), FloatArray(size),
                32, 32, 0, 32, 3, 8f, 0.12f,
            ),
        )
        // 空行带
        assertFalse(
            NativeComputeBridge.bilateralRows(
                plane, plane, plane, FloatArray(size), FloatArray(size), FloatArray(size),
                32, 32, 16, 16, 3, 8f, 0.12f,
            ),
        )
        // 目标缓冲不足
        assertFalse(
            NativeComputeBridge.bilateralRows(
                plane, plane, plane, FloatArray(4), FloatArray(4), FloatArray(4),
                32, 32, 0, 32, 3, 8f, 0.12f,
            ),
        )
    }

    @Test
    fun unsharpChannelFallsBackWithFalse() {
        val channel = FloatArray(32 * 32) { 0.4f }
        assertFalse(NativeComputeBridge.unsharpChannel(channel, 32, 32, 2, 0.6f, 2))
        // radius <= 0 视为退化，桥直接拒绝（调用方原样返回）
        assertFalse(NativeComputeBridge.unsharpChannel(channel, 32, 32, 0, 0.6f, 2))
        assertFalse(NativeComputeBridge.unsharpChannel(channel, 32, 32, 2, 0f, 2))
        assertFalse(NativeComputeBridge.unsharpChannel(FloatArray(4), 32, 32, 2, 0.6f, 2))
        // 回退路径下缓冲保持原样
        assertEquals(0.4f, channel[0], EPS)
    }

    @Test
    fun everyEntryReturnsSentinelInsteadOfThrowingWhenParametersAreHostile() {
        val empty = FloatArray(0)
        // 这一组调用刻意混入 NaN / 0 尺寸 / 空数组：桥必须全部安静返回，不得抛出任何异常
        assertNull(NativeComputeBridge.phaseCorrelate(empty, empty, -1, -1, Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_FLOAT,
            NativeComputeBridge.shiftAddAccumulate(
                empty, empty, empty, -1, -1, -1, -1, Float.NaN, Float.NaN, Float.NaN,
            ),
            EPS,
        )
        assertFalse(NativeComputeBridge.resolveShiftAdd(empty, empty, empty, -1, -1, Int.MAX_VALUE))
        assertNull(
            NativeComputeBridge.ibpAccumulateFrame(
                empty, empty, empty, empty, -1, -1, -1, -1, Float.NaN, Float.NaN, Float.NaN,
            ),
        )
        assertFalse(NativeComputeBridge.ibpApplyStep(empty, empty, empty, -1, Float.NaN))
        assertFalse(NativeComputeBridge.bilateralLuma(empty, empty, -1, -1, Int.MIN_VALUE, Float.NaN, Float.NaN))
        assertFalse(NativeComputeBridge.unsharpLuma(empty, empty, -1, -1, Float.NaN, Float.NaN))
        assertFalse(NativeComputeBridge.clampPlane(empty, -1, Float.NaN, Float.NaN))
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.accumulateMax(ShortArray(0), ShortArray(0), ShortArray(0), -1, Float.NaN, -1, true, true),
        )
        assertFalse(NativeComputeBridge.accumulateMean(IntArray(0), ShortArray(0), -1))
        assertEquals(
            NativeComputeBridge.UNAVAILABLE_INT,
            NativeComputeBridge.subtractMeanAccumulate(ShortArray(0), ShortArray(0), IntArray(0), -1, Float.NaN, -1, true),
        )
        assertNull(NativeComputeBridge.claheEqualize(empty, -1, -1, Int.MIN_VALUE, Int.MAX_VALUE, Float.NaN))
        assertFalse(
            NativeComputeBridge.applyLumaGain(empty, empty, empty, empty, empty, -1, Float.NaN),
        )
        assertFalse(
            NativeComputeBridge.bilateralRows(
                empty, empty, empty, empty, empty, empty, -1, -1, -1, -1, Int.MIN_VALUE, Float.NaN, Float.NaN,
            ),
        )
        assertFalse(NativeComputeBridge.unsharpChannel(empty, -1, -1, -1, Float.NaN, Int.MIN_VALUE))
        assertTrue(NativeComputeBridge.abiVersion() != NativeComputeLimits.ABI_VERSION)
    }

    private companion object {
        const val EPS = 0f
    }
}
