package com.hinnka.mycamera.nativecompute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计算层参数层单测（纯 JVM）：覆盖**默认值 / 取值范围 / 越界 clamp** 三类规则。
 *
 * 这些规则是跨 JNI 契约的前半段（后半段在 C++ 侧同值再 clamp 一次），
 * 因此每条断言都对应 `delivery/selfchecks/nativecompute.md` 的「参数 clamp 对照表」一行。
 */
class NativeComputeLimitsTest {

    private val eps = 0f

    // ------------------------------------------------------------ 整数 clamp

    @Test
    fun alignMaxSideClampsIntoDocumentedRange() {
        assertEquals(NativeComputeLimits.ALIGN_MAX_SIDE_MIN, NativeComputeLimits.alignMaxSide(0))
        assertEquals(NativeComputeLimits.ALIGN_MAX_SIDE_MIN, NativeComputeLimits.alignMaxSide(-4096))
        assertEquals(512, NativeComputeLimits.alignMaxSide(512))
        assertEquals(NativeComputeLimits.ALIGN_MAX_SIDE_MAX, NativeComputeLimits.alignMaxSide(99999))
    }

    @Test
    fun maxAlignShiftPxClampsIntoDocumentedRange() {
        assertEquals(1, NativeComputeLimits.maxAlignShiftPx(0))
        assertEquals(1, NativeComputeLimits.maxAlignShiftPx(Int.MIN_VALUE))
        assertEquals(64, NativeComputeLimits.maxAlignShiftPx(64))
        assertEquals(NativeComputeLimits.MAX_ALIGN_SHIFT_MAX, NativeComputeLimits.maxAlignShiftPx(Int.MAX_VALUE))
    }

    @Test
    fun holePassesClampsIntoDocumentedRange() {
        assertEquals(0, NativeComputeLimits.holePasses(-1))
        assertEquals(8, NativeComputeLimits.holePasses(8))
        assertEquals(NativeComputeLimits.HOLE_PASSES_MAX, NativeComputeLimits.holePasses(1000))
    }

    @Test
    fun claheTileCountsClampIntoDocumentedRange() {
        assertEquals(1, NativeComputeLimits.claheTilesX(0))
        assertEquals(NativeComputeLimits.CLAHE_TILES_MAX, NativeComputeLimits.claheTilesX(1024))
        assertEquals(8, NativeComputeLimits.claheTilesX(8))
        assertEquals(1, NativeComputeLimits.claheTilesY(-7))
        assertEquals(NativeComputeLimits.CLAHE_TILES_MAX, NativeComputeLimits.claheTilesY(33))
    }

    @Test
    fun bilateralRadiusClampsIntoDocumentedRange() {
        assertEquals(1, NativeComputeLimits.bilateralRadius(0))
        assertEquals(12, NativeComputeLimits.bilateralRadius(999))
        assertEquals(3, NativeComputeLimits.bilateralRadius(3))
    }

    @Test
    fun mfsrBilateralRadiusClampsIntoDocumentedRange() {
        assertEquals(1, NativeComputeLimits.mfsrBilateralRadius(0))
        assertEquals(4, NativeComputeLimits.mfsrBilateralRadius(9))
        assertEquals(2, NativeComputeLimits.mfsrBilateralRadius(2))
    }

    @Test
    fun unsharpThresholdClampsIntoDocumentedRange() {
        assertEquals(0, NativeComputeLimits.unsharpThreshold(-5))
        assertEquals(64, NativeComputeLimits.unsharpThreshold(65))
        assertEquals(2, NativeComputeLimits.unsharpThreshold(2))
    }

    // -------------------------------------------------------------- 浮点 clamp

    @Test
    fun ibpGainUsesDefaultForNonFiniteAndClampsOtherwise() {
        assertEquals(0.6f, NativeComputeLimits.ibpGain(0.6f), eps)
        assertEquals(NativeComputeLimits.IBP_GAIN_MIN, NativeComputeLimits.ibpGain(-3f), eps)
        assertEquals(NativeComputeLimits.IBP_GAIN_MAX, NativeComputeLimits.ibpGain(9f), eps)
        assertEquals(NativeComputeLimits.IBP_GAIN_DEFAULT, NativeComputeLimits.ibpGain(Float.NaN), eps)
        assertEquals(NativeComputeLimits.IBP_GAIN_DEFAULT, NativeComputeLimits.ibpGain(Float.POSITIVE_INFINITY), eps)
        assertEquals(NativeComputeLimits.IBP_GAIN_DEFAULT, NativeComputeLimits.ibpGain(Float.NEGATIVE_INFINITY), eps)
    }

    @Test
    fun mfsrBilateralSigmaRangeFallsBackForTinyOrNonFinite() {
        assertEquals(10f, NativeComputeLimits.mfsrBilateralSigmaRange(0f), eps)
        assertEquals(10f, NativeComputeLimits.mfsrBilateralSigmaRange(1e-4f), eps)
        assertEquals(10f, NativeComputeLimits.mfsrBilateralSigmaRange(Float.NaN), eps)
        // 与 MfsrRefiner 的判据一致：有限且 > 1e-3 时原样使用，不再 clamp 上限
        assertEquals(64f, NativeComputeLimits.mfsrBilateralSigmaRange(64f), eps)
    }

    @Test
    fun mixFallsBackToZeroAndClampsToUnitRange() {
        assertEquals(0f, NativeComputeLimits.mix(Float.NaN), eps)
        assertEquals(0f, NativeComputeLimits.mix(-1f), eps)
        assertEquals(1f, NativeComputeLimits.mix(2f), eps)
        assertEquals(0.5f, NativeComputeLimits.mix(0.5f), eps)
    }

    @Test
    fun claheClipLimitFallsBackToZeroForNonFinite() {
        assertEquals(0f, NativeComputeLimits.claheClipLimit(Float.NaN), eps)
        assertEquals(NativeComputeLimits.CLAHE_CLIP_LIMIT_MIN, NativeComputeLimits.claheClipLimit(0f), eps)
        assertEquals(NativeComputeLimits.CLAHE_CLIP_LIMIT_MAX, NativeComputeLimits.claheClipLimit(99f), eps)
        assertEquals(2.5f, NativeComputeLimits.claheClipLimit(2.5f), eps)
    }

    @Test
    fun bilateralSigmasUseDefaultsForNonFinite() {
        assertEquals(8f, NativeComputeLimits.bilateralSigmaSpace(Float.NaN), eps)
        assertEquals(8f, NativeComputeLimits.bilateralSigmaSpace(Float.POSITIVE_INFINITY), eps)
        assertEquals(0.12f, NativeComputeLimits.bilateralSigmaColor(Float.NaN), eps)
        assertEquals(1f, NativeComputeLimits.bilateralSigmaColor(5f), eps)
        assertEquals(1e-3f, NativeComputeLimits.bilateralSigmaColor(-1f), eps)
    }

    @Test
    fun unsharpAmountIsZeroForNonFinite() {
        assertEquals(0f, NativeComputeLimits.unsharpAmount(Float.NaN), eps)
        assertEquals(0f, NativeComputeLimits.unsharpAmount(-2f), eps)
        assertEquals(3f, NativeComputeLimits.unsharpAmount(7f), eps)
        assertEquals(0.6f, NativeComputeLimits.unsharpAmount(0.6f), eps)
    }

    @Test
    fun accumulateGainUsesDefaultForNonFinite() {
        assertEquals(1f, NativeComputeLimits.accumulateGain(Float.NaN), eps)
        assertEquals(0f, NativeComputeLimits.accumulateGain(-1f), eps)
        assertEquals(2f, NativeComputeLimits.accumulateGain(4f), eps)
        assertEquals(1.5f, NativeComputeLimits.accumulateGain(1.5f), eps)
    }

    // ------------------------------------------------------------ 缓冲长度校验

    @Test
    fun bufferCoversAcceptsExactAndLarger() {
        assertTrue(NativeComputeLimits.bufferCovers(1024, 32, 32))
        assertTrue(NativeComputeLimits.bufferCovers(2048, 32, 32))
        assertTrue(NativeComputeLimits.bufferCovers(3072, 32, 32, planes = 3))
    }

    @Test
    fun bufferCoversRejectsShortOrInvalid() {
        assertFalse(NativeComputeLimits.bufferCovers(1023, 32, 32))
        assertFalse(NativeComputeLimits.bufferCovers(0, 0, 0))
        assertFalse(NativeComputeLimits.bufferCovers(100, -1, 10))
        assertFalse(NativeComputeLimits.bufferCovers(100, 10, 10, planes = 0))
    }

    @Test
    fun bufferCoversUsesWideArithmeticWithoutOverflow() {
        // 4096×4096 = 16,777,216 远超「两个 Int 相乘后截断」的场景，必须用 Long 计算
        assertTrue(NativeComputeLimits.bufferCovers(16_777_216, 4096, 4096))
        assertFalse(NativeComputeLimits.bufferCovers(16_777_215, 4096, 4096))
    }

    @Test
    fun abiVersionMatchesCppConstant() {
        // 与 app/src/main/cpp/nativecompute/nativecompute_common.h 的 kAbiVersion 必须一致
        assertEquals(1, NativeComputeLimits.ABI_VERSION)
        assertEquals("my-native-lib", NativeComputeLimits.NATIVE_LIB)
    }
}
