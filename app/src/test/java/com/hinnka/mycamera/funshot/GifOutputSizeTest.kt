package com.hinnka.mycamera.funshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GIF 输出尺寸与配置 clamp 的边界（任务书要求的「GIF 尺寸边界」覆盖）。 */
class GifOutputSizeTest {

    @Test
    fun fitKeepsAspectRatioAndNeverUpscales() {
        assertEquals(100 to 50, GifOutputSize.fit(100, 50, 480, 480))
        assertEquals(4000 to 3000, GifOutputSize.fit(4000, 3000, 4000, 3000))
    }

    @Test
    fun fitAppliesBothWidthAndHeightBounds() {
        assertEquals(480 to 360, GifOutputSize.fit(4000, 3000, 480, 480))
        assertEquals(480 to 270, GifOutputSize.fit(1920, 1080, 480, 480))
        // 高度先受限：1000×4000 → 上限 480×480 → 120×480。
        assertEquals(120 to 480, GifOutputSize.fit(1000, 4000, 480, 480))
    }

    @Test
    fun fitClampsToMinimumEdgeOf32() {
        val (width, height) = GifOutputSize.fit(10, 10, 480, 480)
        assertEquals(32, width)
        assertEquals(32, height)
    }

    @Test
    fun fitHandlesNonPositiveSource() {
        assertEquals(480 to 480, GifOutputSize.fit(0, 0, 480, 480))
        assertEquals(32 to 32, GifOutputSize.fit(-5, 10, 16, 16))
    }

    @Test
    fun fitWidthNeverUpscalesAndKeepsAspect() {
        assertEquals(480 to 360, GifOutputSize.fitWidth(4000, 3000, 480))
        assertEquals(100 to 200, GifOutputSize.fitWidth(100, 200, 480))
        val (width, height) = GifOutputSize.fitWidth(1000, 1000, 4)
        assertTrue(width >= 32)
        assertTrue(height >= 1)
    }

    @Test
    fun frameDelaysAreExpressedInHundredthsOfASecond() {
        val config = GifConfig()
        assertEquals(10, config.frameDelayCentis())
        // 最小 2 centis（20ms）；间隔上限 5000ms → 500 centis。
        assertEquals(2, config.frameDelayCentis(15))
        assertEquals(500, config.frameDelayCentis(6_000))
        assertEquals(60, GifConfig(holdLastFrameMs = 500).lastFrameDelayCentis())
    }

    @Test
    fun configClampFixesOutOfRangeAndDirtyValues() {
        val report = GifConfig(
            frameIntervalMs = 1,
            holdLastFrameMs = -100,
            loopCount = -5,
            maxWidth = 20,
            maxHeight = 99_999,
            maxColors = 9_999,
            ditherStrength = Float.NaN,
            maxFrames = 0,
            videoFrameRate = 0,
            videoStartMs = -1_000,
            videoEndMs = -1,
            videoTargetWidth = 8,
            videoMaxFrames = 100_000,
            memoryBudgetBytes = -1,
        ).coerceWithReport()

        val config = report.value
        assertEquals(20, config.frameIntervalMs)
        assertEquals(0, config.holdLastFrameMs)
        assertEquals(0, config.loopCount)
        assertEquals(32, config.maxWidth)
        assertEquals(1_024, config.maxHeight)
        assertEquals(256, config.maxColors)
        assertEquals(0.6f, config.ditherStrength, 1e-6f)
        assertEquals(1, config.maxFrames)
        assertEquals(1, config.videoFrameRate)
        assertEquals(0L, config.videoStartMs)
        assertEquals(0L, config.videoEndMs)
        assertEquals(32, config.videoTargetWidth)
        assertEquals(600, config.videoMaxFrames)
        assertEquals(8L * 1024 * 1024, config.memoryBudgetBytes)
        assertTrue(report.changed)
        assertTrue(report.fixed.contains("maxColors"))
        assertTrue(report.fixed.contains("ditherStrength"))
    }

    @Test
    fun configDefaultsAreWithinRange() {
        val report = GifConfig().coerceWithReport()
        assertFalse("defaults must already be valid", report.changed)
        assertTrue(report.fixed.isEmpty())
    }

    @Test
    fun codecRoundTripPreservesValues() {
        val source = GifConfig(frameIntervalMs = 80, loopCount = 3, maxColors = 64, perFramePalette = true)
        val encoded = GifConfigCodec.encode(source)
        val decoded = GifConfigCodec.decode { key -> encoded[key] }.value
        assertEquals(source.frameIntervalMs, decoded.frameIntervalMs)
        assertEquals(source.loopCount, decoded.loopCount)
        assertEquals(source.maxColors, decoded.maxColors)
        assertEquals(source.perFramePalette, decoded.perFramePalette)
    }
}
