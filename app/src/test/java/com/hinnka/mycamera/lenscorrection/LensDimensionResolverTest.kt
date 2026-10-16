package com.hinnka.mycamera.lenscorrection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 相机维度解析单测：等效焦距分档、异常输入回退、变焦倍率兜底、三档预设齐备。
 */
class LensDimensionResolverTest {

    @Test
    fun mapsTypicalFocalLengthsToDimensions() {
        // K50G：超广角 ≈16mm、主摄 ≈26mm、微距 ≈50mm
        assertEquals(LensCameraDimension.ULTRA_WIDE, LensDimensionResolver.fromEquivalentFocalLengthMm(16f))
        assertEquals(LensCameraDimension.ULTRA_WIDE, LensDimensionResolver.fromEquivalentFocalLengthMm(13f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(26f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(35f))
        assertEquals(LensCameraDimension.MACRO, LensDimensionResolver.fromEquivalentFocalLengthMm(50f))
        assertEquals(LensCameraDimension.MACRO, LensDimensionResolver.fromEquivalentFocalLengthMm(120f))
    }

    @Test
    fun focalLengthBoundariesAreStable() {
        // 阈值本身归入「更大焦距」的一侧：20mm → MAIN，38mm → MACRO
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(20f))
        assertEquals(LensCameraDimension.MACRO, LensDimensionResolver.fromEquivalentFocalLengthMm(38f))
        assertEquals(
            LensCameraDimension.ULTRA_WIDE,
            LensDimensionResolver.fromEquivalentFocalLengthMm(19.999f),
        )
        assertEquals(
            LensCameraDimension.MAIN,
            LensDimensionResolver.fromEquivalentFocalLengthMm(37.999f),
        )
    }

    @Test
    fun invalidFocalLengthFallsBackToMain() {
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(null))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(Float.NaN))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(0f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromEquivalentFocalLengthMm(-26f))
        assertEquals(
            LensCameraDimension.MAIN,
            LensDimensionResolver.fromEquivalentFocalLengthMm(Float.POSITIVE_INFINITY),
        )
    }

    @Test
    fun zoomRatioBelowOneIsUltraWide() {
        assertEquals(LensCameraDimension.ULTRA_WIDE, LensDimensionResolver.fromZoomRatio(0.6f))
        assertEquals(LensCameraDimension.ULTRA_WIDE, LensDimensionResolver.fromZoomRatio(0.999f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(1f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(2f))
    }

    @Test
    fun invalidZoomRatioFallsBackToMain() {
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(null))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(Float.NaN))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(-1f))
        assertEquals(LensCameraDimension.MAIN, LensDimensionResolver.fromZoomRatio(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun everyDimensionHasPresetAndSaneCoefficients() {
        for (profile in LensProfileDefaults.ALL) {
            assertTrue("label 不能为空", profile.label.isNotEmpty())
            assertTrue("k1 必须为正值以校正桶形畸变: ${profile.k1}", profile.k1 > 0f)
            assertTrue("系数需落在可调范围内", profile.k1 <= LensDistortionConfig.COEFFICIENT_MAX)
            assertTrue(profile.suggestedCropScale in LensDistortionConfig.CROP_SCALE_MIN..LensDistortionConfig.CROP_SCALE_MAX)
            assertEquals(profile, LensProfileDefaults.presetFor(profile.dimension))
        }
        // 三档的畸变强度应单调：主摄 < 微距 < 超广角
        val main = LensProfileDefaults.presetFor(LensCameraDimension.MAIN)
        val macro = LensProfileDefaults.presetFor(LensCameraDimension.MACRO)
        val ultraWide = LensProfileDefaults.presetFor(LensCameraDimension.ULTRA_WIDE)
        assertTrue(main.k1 < macro.k1)
        assertTrue(macro.k1 < ultraWide.k1)
    }
}
