package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.argb
import com.hinnka.mycamera.enhance.EnhanceTestSupport.channel
import com.hinnka.mycamera.enhance.EnhanceTestSupport.fill
import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.noisy
import com.hinnka.mycamera.enhance.EnhanceTestSupport.ramp
import com.hinnka.mycamera.enhance.EnhanceTestSupport.step
import com.hinnka.mycamera.enhance.EnhanceTestSupport.variance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趣味滤镜行为单测：移轴（带位置/模糊/饱和）、素描（边缘+阈值）、油画（双边+量化+边缘）。
 */
class FunFiltersTest {

    // ---------------------------------------------------------------- 移轴

    @Test
    fun tiltShiftFactorsAreZeroInBandAndOneOutside() {
        val width = 10
        val height = 10
        val config = TiltShiftConfig(
            centerY = 0.5f, bandWidth = 0.2f, transition = 0.1f, angleDegrees = 0f,
        )
        val factors = TiltShiftFilter.blurFactors(width, height, config)
        // 带内（行 4、5）完全不模糊
        assertEquals(0f, factors[4 * width + 5], 1e-6f)
        assertEquals(0f, factors[5 * width + 5], 1e-6f)
        // 带外（首行、末行）完全模糊
        assertEquals(1f, factors[0 * width + 5], 1e-6f)
        assertEquals(1f, factors[9 * width + 5], 1e-6f)
        // 过渡区（行 3）介于两者之间
        val transition = factors[3 * width + 5]
        assertTrue("transition factor=$transition", transition > 0f && transition < 1f)
    }

    @Test
    fun tiltShiftRotatedBandChangesFactorField() {
        val factors = TiltShiftFilter.blurFactors(
            16, 16, TiltShiftConfig(centerY = 0.5f, bandWidth = 0.2f, transition = 0.1f, angleDegrees = 90f),
        )
        // 旋转 90° 后清晰带变成竖直的：中心列清晰，左右列模糊
        assertEquals(0f, factors[8 * 16 + 8], 1e-6f)
        assertEquals(1f, factors[8 * 16 + 0], 1e-6f)
    }

    @Test
    fun tiltShiftBlursOutsideBandAndKeepsBandSharp() {
        val width = 32
        val height = 32
        val pixels = noisy(width, height, base = 128, amplitude = 40, seed = 21L)
        val out = TiltShiftFilter.process(
            pixels, width, height,
            TiltShiftConfig(
                centerY = 0.5f, bandWidth = 0.2f, transition = 0.1f,
                blurRadius = 4, blurStrength = 1f, saturationBoost = 0f,
            ),
        )
        val topRowBefore = pixels.copyOfRange(0, width)
        val topRowAfter = out.copyOfRange(0, width)
        assertTrue(
            "top row must be blurred: ${variance(topRowBefore)} -> ${variance(topRowAfter)}",
            variance(topRowAfter) < variance(topRowBefore) * 0.5,
        )
        val centerRow = 16
        val centerBefore = pixels.copyOfRange(centerRow * width, (centerRow + 1) * width)
        val centerAfter = out.copyOfRange(centerRow * width, (centerRow + 1) * width)
        assertTrue(
            "band center must stay sharp: maxDiff=${EnhanceTestSupport.maxChannelDiff(centerBefore, centerAfter)}",
            EnhanceTestSupport.maxChannelDiff(centerBefore, centerAfter) <= 1,
        )
    }

    @Test
    fun tiltShiftSaturationBoostRaisesChromaInsideBand() {
        val width = 16
        val height = 16
        val pixels = fill(width, height, 150, 100, 100)
        val out = TiltShiftFilter.process(
            pixels, width, height,
            TiltShiftConfig(
                centerY = 0.5f, bandWidth = 0.3f, transition = 0.1f,
                blurRadius = 8, blurStrength = 1f, saturationBoost = 0.5f,
            ),
        )
        val centerPixel = out[8 * width + 8]
        val redBefore = channel(pixels[8 * width + 8], 16)
        val blueBefore = channel(pixels[8 * width + 8], 0)
        assertTrue("red must rise: $redBefore -> ${channel(centerPixel, 16)}", channel(centerPixel, 16) > redBefore)
        assertTrue("blue must drop: $blueBefore -> ${channel(centerPixel, 0)}", channel(centerPixel, 0) < blueBefore)
    }

    // ---------------------------------------------------------------- 素描

    @Test
    fun sketchFlatImageBecomesWhitePaper() {
        val out = SketchFilter.process(fill(16, 16, 130, 130, 130), 16, 16, SketchConfig())
        for (p in out) {
            assertEquals(255, channel(p, 16))
            assertEquals(255, channel(p, 8))
            assertEquals(255, channel(p, 0))
        }
    }

    @Test
    fun sketchInvertTurnsFlatImageBlack() {
        val out = SketchFilter.process(
            fill(16, 16, 130, 130, 130), 16, 16, SketchConfig(invert = true),
        )
        for (p in out) assertEquals(0, channel(p, 16))
    }

    @Test
    fun sketchEdgeProducesDarkLine() {
        val width = 16
        val height = 16
        val out = SketchFilter.process(step(width, height, 30, 230), width, height, SketchConfig())
        var darkest = 255
        for (p in out) darkest = minOf(darkest, channel(p, 16))
        assertTrue("edge must produce dark pixels: darkest=$darkest", darkest < 128)
    }

    @Test
    fun sketchThresholdGateSuppressesLowContrastEdges() {
        val width = 16
        val height = 16
        // 极低对比阶跃：Sobel 梯度 ≈ 4×(3/255) ≈ 0.047 < 默认阈值 0.06 → 全白纸
        val lowContrast = step(width, height, 128, 131)
        val gated = SketchFilter.process(lowContrast, width, height, SketchConfig())
        for (p in gated) assertEquals("low-contrast edge must be gated out", 255, channel(p, 16))

        // 把阈值降到 0 以下（clamp 到 0）后，同一张图必须出现线条 → 证明「是阈值门控」而非别的原因
        val ungated = SketchFilter.process(lowContrast, width, height, SketchConfig(threshold = 0f))
        var darkest = 255
        for (p in ungated) darkest = minOf(darkest, channel(p, 16))
        assertTrue("without threshold the same edge must draw lines", darkest < 255)
    }

    @Test
    fun sketchColorMixOneIsIdentityOnFlatImage() {
        val pixels = fill(12, 12, 120, 90, 60)
        val out = SketchFilter.process(pixels, 12, 12, SketchConfig(colorMix = 1f))
        assertTrue(identical(pixels, out))
    }

    // ---------------------------------------------------------------- 油画

    @Test
    fun oilPaintKeepsFlatImageFlat() {
        val out = OilPaintFilter.process(
            fill(16, 16, 128, 128, 128), 16, 16, OilPaintConfig(levels = 4),
        )
        val first = out[0]
        for (p in out) assertEquals("flat input must stay flat", first, p)
    }

    @Test
    fun oilPaintQuantizesToLattice() {
        val width = 32
        val height = 8
        val levels = 4
        val pixels = ramp(width, height, 10, 250)
        val out = OilPaintFilter.process(
            pixels, width, height,
            OilPaintConfig(
                levels = levels,
                bilateralRadius = 2,
                sigmaColor = 0.3f,
                edgeStrength = 0f,
                edgeThreshold = 0f,
                paintMix = 1f,
            ),
        )
        val allowed = (0 until levels).map { (it * 255) / (levels - 1) }
        for (p in out) {
            for (shift in intArrayOf(16, 8, 0)) {
                val v = channel(p, shift)
                assertTrue(
                    "channel $v must sit on the quantization lattice $allowed",
                    allowed.any { kotlin.math.abs(it - v) <= 1 },
                )
            }
        }
    }

    @Test
    fun oilPaintMixZeroIsIdentity() {
        val pixels = ramp(16, 16, 20, 240)
        val out = OilPaintFilter.process(pixels, 16, 16, OilPaintConfig(paintMix = 0f))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun oilPaintBandBandingMatchesWholeImage() {
        val width = 12
        val height = 12
        val pixels = noisy(width, height, 128, 40, seed = 4L)
        val config = OilPaintConfig(levels = 6, bilateralRadius = 2, sigmaColor = 0.2f, paintMix = 1f)
        val whole = OilPaintFilter.process(pixels, width, height, config, bandHeight = 0)
        val banded = OilPaintFilter.process(pixels, width, height, config, bandHeight = 3)
        assertTrue(identical(whole, banded))
    }

    // ---------------------------------------------------------------- 分发器

    @Test
    fun dispatcherSkipsWhenKindIsNone() {
        val pixels = ramp(8, 8, 10, 240)
        val out = FunFilterProcessor.process(pixels, 8, 8, FunFilterConfig(kind = FunFilterKind.NONE))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun dispatcherRoutesToSelectedFilter() {
        val pixels = fill(8, 8, 130, 130, 130)
        val sketch = FunFilterProcessor.process(
            pixels, 8, 8, FunFilterConfig(kind = FunFilterKind.SKETCH),
        )
        assertEquals(255, channel(sketch[0], 16)) // 素描：平坦图 → 白纸
        val oil = FunFilterProcessor.process(
            pixels, 8, 8, FunFilterConfig(kind = FunFilterKind.OIL_PAINT, oilPaint = OilPaintConfig(levels = 4)),
        )
        assertTrue(channel(oil[0], 16) != 130) // 油画：量化后落在格点上
    }

    @Test
    fun dispatcherClampsConfigBeforeDispatch() {
        val pixels = ramp(16, 16, 10, 240)
        val out = FunFilterProcessor.process(
            pixels, 16, 16,
            FunFilterConfig(
                kind = FunFilterKind.TILT_SHIFT,
                tiltShift = TiltShiftConfig(
                    centerY = 50f, bandWidth = -3f, blurRadius = 9999, saturationBoost = Float.NaN,
                ),
            ),
        )
        assertEquals(pixels.size, out.size)
    }
}
