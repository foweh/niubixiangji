package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.argb
import com.hinnka.mycamera.enhance.EnhanceTestSupport.channel
import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.ramp
import com.hinnka.mycamera.enhance.EnhanceTestSupport.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAHE 单测：重点覆盖**直方图边界**（裁剪守恒、容量不足、常量 tile、空 tile、极端 tile 网格）
 * 与映射表的单调性不变式。
 */
class ClaheProcessorTest {

    // ------------------------------------------------------- 直方图裁剪边界

    @Test
    fun clipHistogramPreservesTotalCountWhenCapacityIsEnough() {
        // 全部像素挤在 1 个 bin（最坏情况），cap×bins = 8×256 = 2048 ≥ 1024 → 总数严格守恒
        val hist = IntArray(ClaheProcessor.LUMA_BINS).also { it[128] = 1024 }
        val clipped = ClaheProcessor.clipHistogram(hist, 8)
        assertEquals(1024, clipped.sum())
        assertTrue(clipped.max() <= 8)
    }

    @Test
    fun clipHistogramNeverExceedsLimitWhenCapacityIsInsufficient() {
        // cap×bins = 20×256 = 5120 < 总数 25600 → 许可丢弃余量，但绝不能有 bin 越界
        val hist = IntArray(ClaheProcessor.LUMA_BINS) { 100 }
        val clipped = ClaheProcessor.clipHistogram(hist, 20)
        assertTrue("every bin must respect the limit", clipped.all { it <= 20 })
        assertTrue("clipping must not create pixels", clipped.sum() <= hist.sum())
    }

    @Test
    fun clipHistogramWithNonPositiveLimitIsIdentityCopy() {
        val hist = IntArray(ClaheProcessor.LUMA_BINS).also { it[10] = 7; it[200] = 3 }
        val clipped = ClaheProcessor.clipHistogram(hist, 0)
        assertEquals(hist.toList(), clipped.toList())
        // 返回副本，不得与入参共享：改结果不影响原数组
        clipped[10] = 999
        assertEquals(7, hist[10])
    }

    @Test
    fun histogramCountsEverySample() {
        val luma = floatArrayOf(0f, 0.5f, 1f, 0.5f)
        val hist = ClaheProcessor.histogram(luma, 4, 1)
        assertEquals(4, hist.sum())
        assertEquals(2, hist[128])
        assertEquals(1, hist[0])
        assertEquals(1, hist[255])
    }

    // ------------------------------------------------------- tile LUT 边界

    @Test
    fun buildTileLutIsIdentityForConstantTile() {
        // 常量 tile：必须退化为恒等映射（不被裁剪-重分配伪影推白/压黑）
        val width = 4
        val luma = FloatArray(width * 4) { 0.5f }
        val lut = ClaheProcessor.buildTileLut(luma, width, 0, 0, width, 4, clipLimit = 2f)
        for (i in lut.indices) assertEquals("bin $i", i, lut[i])
    }

    @Test
    fun buildTileLutIsIdentityForEmptyTile() {
        val luma = FloatArray(16) { 0.3f }
        val lut = ClaheProcessor.buildTileLut(luma, 4, 2, 2, 2, 2, clipLimit = 2f)
        for (i in lut.indices) assertEquals(i, lut[i])
    }

    @Test
    fun buildTileLutIsMonotonicAndNotIdentityForSkewedTile() {
        val width = 16
        val luma = FloatArray(width * 16)
        for (i in luma.indices) {
            // 偏斜分布：大部分样本集中在 0.2~0.25
            luma[i] = if (i % 7 == 0) 0.8f else 0.2f + 0.05f * (i % 3)
        }
        val lut = ClaheProcessor.buildTileLut(luma, width, 0, 0, width, 16, clipLimit = 3f)
        // 不变式 ①：CDF 映射必然单调不减
        for (i in 1 until lut.size) assertTrue("lut must be non-decreasing at $i", lut[i] >= lut[i - 1])
        // 不变式 ②：值域合法
        assertTrue(lut.all { it in 0 until ClaheProcessor.LUMA_BINS })
        // 不变式 ③：偏斜分布下映射必须偏离恒等（否则等于没做均衡）
        assertTrue(lut.toList() != (0 until ClaheProcessor.LUMA_BINS).toList())
    }

    // ------------------------------------------------------- equalize 边界

    @Test
    fun equalizeKeepsConstantImageUnchanged() {
        val width = 16
        val height = 16
        val luma = FloatArray(width * height) { 0.5f }
        val out = ClaheProcessor.equalize(luma, width, height, ClaheConfig())
        for (v in out) assertEquals(128f / 255f, v, 0.004f)
    }

    @Test
    fun equalizeHandlesTileGridLargerThanImage() {
        val width = 3
        val height = 2
        val luma = floatArrayOf(0.1f, 0.4f, 0.9f, 0.2f, 0.5f, 0.8f)
        val out = ClaheProcessor.equalize(luma, width, height, ClaheConfig(tilesX = 32, tilesY = 32))
        assertEquals(width * height, out.size)
        for (v in out) assertTrue("finite in [0,1]: $v", v.isFinite() && v in 0f..1f)
    }

    @Test
    fun equalizeDoesNotCollapseGlobalRange() {
        val width = 64
        val height = 64
        val luma = FloatArray(width * height) {
            val x = it % width
            0.2f + 0.3f * x / (width - 1)
        }
        val before = luma.max() - luma.min()
        val out = ClaheProcessor.equalize(luma, width, height, ClaheConfig())
        val after = out.max() - out.min()
        assertTrue("range must not collapse: $before -> $after", after >= before * 0.5f)
        assertTrue(out.all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun equalizeRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaheProcessor.equalize(FloatArray(4), 0, 2, ClaheConfig())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClaheProcessor.equalize(FloatArray(1), 2, 2, ClaheConfig())
        }
    }

    // ------------------------------------------------------- process 边界

    @Test
    fun processWithLumaOnlyKeepsGrayNeutral() {
        val width = 32
        val height = 32
        val gray = ramp(width, height, 60, 200)
        val out = ClaheProcessor.process(gray, width, height, ClaheConfig(lumaOnly = true))
        for (p in out) {
            assertTrue(
                "gray must stay neutral",
                channel(p, 16) == channel(p, 8) && channel(p, 8) == channel(p, 0),
            )
        }
    }

    @Test
    fun processWithMixZeroIsIdentity() {
        val width = 16
        val height = 16
        val pixels = ramp(width, height, 30, 220)
        val out = ClaheProcessor.process(pixels, width, height, ClaheConfig(mix = 0f))
        assertTrue(identical(pixels, out))
    }

    @Test
    fun processPreservesAlpha() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(120, 140, 160, a = 77) }
        val out = ClaheProcessor.process(pixels, width, height, ClaheConfig())
        for (p in out) assertEquals(77, channel(p, 24))
    }

    @Test
    fun processRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaheProcessor.process(IntArray(4), 2, 0, ClaheConfig())
        }
    }

    @Test
    fun processClampsOutOfRangeConfigInsteadOfCrashing() {
        val width = 24
        val height = 24
        val pixels = ramp(width, height, 10, 240)
        val out = ClaheProcessor.process(
            pixels, width, height,
            ClaheConfig(tilesX = 9999, tilesY = -5, clipLimit = Float.NaN, mix = 5f),
        )
        assertEquals(width * height, out.size)
        assertTrue(range(out) >= 0)
    }
}
