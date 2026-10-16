package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZebraRules] 纯逻辑单测：阈值换算、边界判定、判定基准、条纹相位、覆盖率、文案。
 */
class ZebraRulesTest {

    private val delta = 1e-4f

    // -------------------------------------------------------- 阈值百分比换算

    @Test
    fun lumaThresholdOf_mapsPercentToEightBitLuma() {
        assertEquals(128, ZebraRules.lumaThresholdOf(50f))   // 127.5 → 128
        assertEquals(153, ZebraRules.lumaThresholdOf(60f))
        assertEquals(242, ZebraRules.lumaThresholdOf(95f))   // 242.25 → 242
        assertEquals(255, ZebraRules.lumaThresholdOf(100f))
        // 极端负值同样被 clamp 到下限 50%
        assertEquals(128, ZebraRules.lumaThresholdOf(-999f))
        assertEquals(128, ZebraRules.lumaThresholdOf(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun lumaThresholdOf_clampsBelowMinAndAbove100Percent() {
        // 低于 50% 被 clamp 到 50%
        assertEquals(128, ZebraRules.lumaThresholdOf(10f))
        assertEquals(128, ZebraRules.lumaThresholdOf(50f))
        // 100% = 满量程；>100%（超白）在 8bit 源上等价于 100%
        assertEquals(255, ZebraRules.lumaThresholdOf(100f))
        assertEquals(255, ZebraRules.lumaThresholdOf(110f))
        assertEquals(255, ZebraRules.lumaThresholdOf(120f))
        assertEquals(255, ZebraRules.lumaThresholdOf(500f))
    }

    @Test
    fun isOverexposedLuma_boundaryIsInclusive() {
        // 阈值 95% → 242
        assertFalse(ZebraRules.isOverexposedLuma(241, 95f))
        assertTrue(ZebraRules.isOverexposedLuma(242, 95f))
        assertTrue(ZebraRules.isOverexposedLuma(255, 95f))
        // 阈值 100% → 只有纯白触发
        assertFalse(ZebraRules.isOverexposedLuma(254, 100f))
        assertTrue(ZebraRules.isOverexposedLuma(255, 100f))
        // 越界亮度输入被 clamp，不崩溃
        assertTrue(ZebraRules.isOverexposedLuma(9999, 95f))
        assertFalse(ZebraRules.isOverexposedLuma(-9999, 95f))
    }

    // ------------------------------------------------------------ 亮度计算

    @Test
    fun lumaOf_matchesRec709() {
        assertEquals(0, ZebraRules.lumaOf(0, 0, 0))
        assertEquals(255, ZebraRules.lumaOf(255, 255, 255))
        assertEquals(54, ZebraRules.lumaOf(255, 0, 0))
        assertEquals(182, ZebraRules.lumaOf(0, 255, 0))
        assertEquals(18, ZebraRules.lumaOf(0, 0, 255))
    }

    @Test
    fun lumaOf_clampsChannelValues() {
        assertEquals(54, ZebraRules.lumaOf(300, -20, 0))
        assertEquals(255, ZebraRules.lumaOf(999, 999, 999))
    }

    // ------------------------------------------------------------ 判定基准

    @Test
    fun metricSelection_changesTrigger() {
        // 纯红：亮度只有 54（远低于 242），但 RGB 最大值已达 255
        val lumaConfig = ZebraConfig(thresholdPercent = 95f, metric = ZebraMetric.LUMA)
        val maxRgbConfig = ZebraConfig(thresholdPercent = 95f, metric = ZebraMetric.MAX_RGB)

        assertFalse(ZebraRules.isOverexposed(255, 10, 10, lumaConfig))
        assertTrue(ZebraRules.isOverexposed(255, 10, 10, maxRgbConfig))
        // 两个基准都不触发的情况
        assertFalse(ZebraRules.isOverexposed(120, 120, 120, maxRgbConfig))
        assertFalse(ZebraRules.isOverexposed(120, 120, 120, lumaConfig))
    }

    // -------------------------------------------------------------- 条纹相位

    @Test
    fun stripeShowsAt_zeroAngle_alternatesByPixelX() {
        val config = ZebraConfig(stripeWidthPx = 10f, stripeDuty = 0.5f, stripeAngleDeg = 0f)
        assertTrue(ZebraRules.stripeShowsAt(0f, 0f, config))
        assertTrue(ZebraRules.stripeShowsAt(9f, 0f, config))
        assertFalse(ZebraRules.stripeShowsAt(10f, 0f, config))
        assertFalse(ZebraRules.stripeShowsAt(19f, 0f, config))
        assertTrue(ZebraRules.stripeShowsAt(20f, 0f, config))
    }

    @Test
    fun stripeShowsAt_ninetyDegrees_alternatesByPixelY() {
        val config = ZebraConfig(stripeWidthPx = 10f, stripeDuty = 0.5f, stripeAngleDeg = 90f)
        assertTrue(ZebraRules.stripeShowsAt(999f, 0f, config))
        assertFalse(ZebraRules.stripeShowsAt(999f, 11f, config))
        assertTrue(ZebraRules.stripeShowsAt(999f, 21f, config))
    }

    @Test
    fun stripeShowsAt_dutyClampedToLegalRange() {
        // duty 越界被 clamp 到 0.9：第一个 90% 的相位都是实心
        val config = ZebraConfig(stripeWidthPx = 10f, stripeDuty = 5f, stripeAngleDeg = 0f)
        assertTrue(ZebraRules.stripeShowsAt(17f, 0f, config))
        assertFalse(ZebraRules.stripeShowsAt(18f, 0f, config))

        // stripeWidth 越界被 clamp 到下限 2 → 周期 4
        val thin = ZebraConfig(stripeWidthPx = 0f, stripeDuty = 0.5f, stripeAngleDeg = 0f)
        assertTrue(ZebraRules.stripeShowsAt(1.9f, 0f, thin))
        assertFalse(ZebraRules.stripeShowsAt(2.0f, 0f, thin))
    }

    // -------------------------------------------------------------- 覆盖率

    @Test
    fun coverageRatio_countsOverexposedPixels() {
        val white = 0xFFFFFFFF.toInt()
        val nearlyWhite = 0xFFFEFEFE.toInt() // 亮度 254
        val black = 0xFF000000.toInt()

        // 95%（242）：纯白与近白都算过曝 → 2/4
        assertEquals(
            0.5f,
            ZebraRules.coverageRatio(intArrayOf(white, nearlyWhite, black, black), 95f),
            delta,
        )
        // 100%（255）：只有纯白触发，近白不算 → 1/4
        assertEquals(
            0.25f,
            ZebraRules.coverageRatio(intArrayOf(white, nearlyWhite, black, black), 100f),
            delta,
        )
        // 黑色永远不触发
        assertEquals(0f, ZebraRules.coverageRatio(intArrayOf(black, black), 50f), delta)
        // 中灰 128 在 50% 阈值（=128）下正好命中边界
        assertEquals(1f, ZebraRules.coverageRatio(intArrayOf(0xFF808080.toInt()), 50f), delta)
        // 120% 等价 100%（8bit 源）→ 纯白仍触发
        assertEquals(0.5f, ZebraRules.coverageRatio(intArrayOf(white, white), 120f), delta)
    }

    @Test
    fun coverageRatio_emptyInput_isZero() {
        assertEquals(0f, ZebraRules.coverageRatio(IntArray(0), 95f), delta)
    }

    @Test
    fun coverageRatio_maxRgbMetric_catchesSaturatedColor() {
        // 品红 R=0,G=255,B=255：Rec.709 亮度约 201（低于 242），但 RGB 最大值 255
        val pixels = intArrayOf(0xFF00FFFF.toInt())
        assertEquals(0f, ZebraRules.coverageRatio(pixels, 95f, ZebraMetric.LUMA), delta)
        assertEquals(1f, ZebraRules.coverageRatio(pixels, 95f, ZebraMetric.MAX_RGB), delta)
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun describeThreshold_formatsPercentAndLuma() {
        assertEquals("95%（≈242/255）", ZebraRules.describeThreshold(95f))
        assertEquals("100%（≈255/255）", ZebraRules.describeThreshold(100f))
        assertEquals("95.5%（≈244/255）", ZebraRules.describeThreshold(95.5f))
        // 越界钳制后再描述
        assertEquals("120%（≈255/255）", ZebraRules.describeThreshold(300f))
    }
}
