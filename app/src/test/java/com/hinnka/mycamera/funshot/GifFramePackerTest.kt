package com.hinnka.mycamera.funshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** GIF 帧打包 / 调色板量化的边界（任务书要求的「GIF 帧打包边界」覆盖）。 */
class GifFramePackerTest {

    private fun paletteOf(frame: FunArgbFrame, maxColors: Int): IntArray =
        FunGifPalette.medianCut(frame.argb, maxColors)

    @Test
    fun packProducesOneIndexPerPixelWithinPaletteRange() {
        val frame = FunshotTestFixtures.gradientFrame(16, 16)
        val palette = paletteOf(frame, 16)
        assertTrue(palette.isNotEmpty())
        assertTrue(palette.size <= 16)
        val indexer = FunGifPaletteIndex(palette)
        val indices = FunGifFramePacker.pack(frame.argb, frame.width, frame.height, indexer, false, 0f)
        assertEquals(frame.width * frame.height, indices.size)
        for (value in indices) {
            val index = value.toInt()
            assertTrue("index $index out of palette ${palette.size}", index in palette.indices)
        }
    }

    @Test
    fun packWithDitherKeepsSizeAndRange() {
        val frame = FunshotTestFixtures.gradientFrame(12, 9)
        val palette = paletteOf(frame, 4)
        val indexer = FunGifPaletteIndex(palette)
        val dithered = FunGifFramePacker.pack(frame.argb, frame.width, frame.height, indexer, true, 1f)
        assertEquals(12 * 9, dithered.size)
        for (value in dithered) {
            assertTrue(value.toInt() in palette.indices)
        }
    }

    @Test
    fun packRejectsMismatchedBufferSize() {
        val frame = FunshotTestFixtures.gradientFrame(8, 8)
        val indexer = FunGifPaletteIndex(paletteOf(frame, 8))
        assertThrows(IllegalArgumentException::class.java) {
            FunGifFramePacker.pack(IntArray(63), 8, 8, indexer, false, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FunGifFramePacker.pack(frame.argb, 0, 8, indexer, false, 0f)
        }
    }

    @Test
    fun medianCutRespectsMaxColorBoundary() {
        val frame = FunshotTestFixtures.gradientFrame(32, 32)
        // maxColors 下限是 2（GIF 调色板最小 2 项），传入 1 被夹紧到 2。
        assertTrue(FunGifPalette.medianCut(frame.argb, 1).size <= 2)
        assertTrue(FunGifPalette.medianCut(frame.argb, 2).size <= 2)
        assertTrue(FunGifPalette.medianCut(frame.argb, 256).size <= 256)
    }

    @Test
    fun medianCutHandlesEmptyAndSingleColorInput() {
        assertEquals(1, FunGifPalette.medianCut(IntArray(0), 16).size)
        val solid = IntArray(64) { 0xFF123456.toInt() }
        val palette = FunGifPalette.medianCut(solid, 16)
        assertEquals(1, palette.size)
        assertEquals(0, FunGifPalette.nearestIndex(palette, 0xFF123456.toInt()))
        assertEquals(0, FunGifPalette.nearestIndex(IntArray(0), 0xFF000000.toInt()))
    }

    @Test
    fun palettePaddingAndSizeBitsFollowGifRules() {
        assertEquals(2, FunGifPalette.padToPowerOfTwo(IntArray(1)).size)
        assertEquals(4, FunGifPalette.padToPowerOfTwo(IntArray(3)).size)
        assertEquals(256, FunGifPalette.padToPowerOfTwo(IntArray(256)).size)
        assertEquals(1, FunGifPalette.sizeBits(2))
        assertEquals(2, FunGifPalette.sizeBits(4))
        assertEquals(4, FunGifPalette.sizeBits(16))
        assertEquals(8, FunGifPalette.sizeBits(256))
    }

    @Test
    fun paletteIndexCacheIsStableForRepeatedColors() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val indexer = FunGifPaletteIndex(palette)
        val first = indexer.indexOf(0xFFFF0000.toInt())
        val second = indexer.indexOf(0xFFFF0000.toInt())
        assertEquals(first, second)
        assertEquals(2, first)
        assertEquals(palette.size, indexer.paletteSize)
    }
}
