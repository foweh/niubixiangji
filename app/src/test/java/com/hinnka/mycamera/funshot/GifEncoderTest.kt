package com.hinnka.mycamera.funshot

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GIF89a 容器组装 + LZW 位流（自研编码器）的往返验证。
 *
 * 测试内自带一个**最小 GIF 解析器 + LZW 解码器**（仅测试用），
 * 因此「编码器写出的字节能否被标准规则解回原始索引」是可验证的，
 * 而不是只断言「不抛异常」。
 */
class GifEncoderTest {

    private val palette = intArrayOf(
        0xFF000000.toInt(),
        0xFFFFFFFF.toInt(),
        0xFFFF0000.toInt(),
        0xFF00FF00.toInt(),
    )

    private fun encodeTwoFrames(loopCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = FunGifEncoder(out, 4, 4, loopCount)
        encoder.writeHeader(palette)
        encoder.addFrame(ByteArray(16) { (it % 4).toByte() }, 10)
        encoder.addFrame(ByteArray(16) { ((it + 1) % 4).toByte() }, 12)
        encoder.finish()
        assertEquals(2, encoder.framesWritten)
        return out.toByteArray()
    }

    @Test
    fun lzwRoundTripRestoresOriginalIndices() {
        val indices = ByteArray(512) { ((it * 7 + it / 3) % 4).toByte() }
        val encoded = FunGifLzw.encode(indices, 2)
        assertTrue(encoded.size > 0)
        assertEquals(0, encoded.last().toInt() and 0xFF) // 子块结束符
        val decoded = lzwDecode(gatherSubBlocks(encoded, 0).first, 2, indices.size)
        assertArrayEquals(indices, decoded)
    }

    @Test
    fun containerHeaderScreenDescriptorAndTrailerAreCorrect() {
        val bytes = encodeTwoFrames(loopCount = 0)
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertEquals(4, bytes[6].toInt() and 0xFF)
        assertEquals(0, bytes[7].toInt() and 0xFF)
        assertEquals(4, bytes[8].toInt() and 0xFF)
        assertEquals(0, bytes[9].toInt() and 0xFF)
        // packed: 全局色表 | 8bit 颜色分辨率 | 色表位宽-1（4 项 → 2^(1+1) = 4）
        val packed = bytes[10].toInt() and 0xFF
        assertTrue(packed and 0x80 != 0)
        assertEquals(1, packed and 0x07)
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
    }

    @Test
    fun loopExtensionIsWrittenUnlessPlayingOnce() {
        val looping = encodeTwoFrames(loopCount = 0)
        assertTrue(String(looping, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))

        val once = encodeTwoFrames(loopCount = 1)
        assertFalse(String(once, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }

    @Test
    fun decoderRecoversBothFramesFromContainer() {
        val bytes = encodeTwoFrames(loopCount = 0)
        val frames = parseFrames(bytes, expectedWidth = 4, expectedHeight = 4)
        assertEquals(2, frames.size)
        assertArrayEquals(ByteArray(16) { (it % 4).toByte() }, frames[0])
        assertArrayEquals(ByteArray(16) { ((it + 1) % 4).toByte() }, frames[1])
    }

    @Test
    fun perFrameLocalPaletteIsAccepted() {
        val out = ByteArrayOutputStream()
        val encoder = FunGifEncoder(out, 2, 2, loopCount = 0)
        encoder.writeHeader(palette)
        encoder.addFrame(ByteArray(4) { it.toByte() }, 10, localPalette = palette)
        encoder.finish()
        val frames = parseFrames(out.toByteArray(), expectedWidth = 2, expectedHeight = 2)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(4) { it.toByte() }, frames[0])
    }

    @Test
    fun invalidFrameSizeIsRejected() {
        val out = ByteArrayOutputStream()
        val encoder = FunGifEncoder(out, 3, 3, loopCount = 0)
        encoder.writeHeader(palette)
        var thrown = false
        try {
            encoder.addFrame(ByteArray(8), 10)
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    // ------------------------------------------------------------------ 管线端到端
    @Test
    fun pipelineEncodesFramesIncludingDegradationPath() {
        val frames = (0 until 3).map { FunshotTestFixtures.gradientFrame(64, 64, seed = it * 5) }
        val result = FunGifPipeline.encode(frames, GifConfig(maxFrames = 300))
        assertFalse(result.isEmpty)
        assertEquals(3, result.frameCount)
        assertEquals(64, result.width)
        assertEquals(64, result.height)
        assertFalse(result.degraded)
        assertEquals("GIF89a", String(result.bytes, 0, 6, Charsets.US_ASCII))
    }

    @Test
    fun pipelineHonoursFrameCapAndMemoryGuard() {
        val frames = (0 until 8).map { FunshotTestFixtures.gradientFrame(64, 64, seed = it) }
        val capped = FunGifPipeline.encode(frames, GifConfig(maxFrames = 2))
        assertEquals(2, capped.frameCount)
        assertTrue(capped.degraded)
        assertTrue(capped.notes.any { it.contains("maxFrames") })

        // 内存守卫始终拒绝：必须降级而不是失败。
        val degraded = FunGifPipeline.encode(
            frames = frames,
            config = GifConfig(),
            guard = FunshotMemoryGuard { false },
        )
        assertFalse(degraded.isEmpty)
        assertTrue(degraded.degraded)
        assertTrue(degraded.width < 64)
        assertTrue(degraded.notes.any { it.contains("memory guard") })
    }

    @Test
    fun pipelineReturnsEmptyForNoFrames() {
        val result = FunGifPipeline.encode(emptyList(), GifConfig())
        assertTrue(result.isEmpty)
        assertEquals(0, result.frameCount)
        assertTrue(result.notes.contains("no frames"))
    }

    // ------------------------------------------------------------------ 测试内最小解码器

    /** 收集从 [start] 开始的一串子块内容，返回 (数据, 结束符之后的下标)。 */
    private fun gatherSubBlocks(bytes: ByteArray, start: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var p = start
        while (true) {
            val length = bytes[p].toInt() and 0xFF
            p++
            if (length == 0) break
            out.write(bytes, p, length)
            p += length
        }
        return out.toByteArray() to p
    }

    private fun lzwDecode(data: ByteArray, minCodeSize: Int, expectedLength: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        var dictionary = ArrayList<IntArray>()
        fun resetDictionary() {
            dictionary = ArrayList()
            for (i in 0 until clear) dictionary.add(intArrayOf(i))
            dictionary.add(IntArray(0))
            dictionary.add(IntArray(0))
        }
        resetDictionary()

        val out = ArrayList<Int>(expectedLength)
        var bitPosition = 0
        var previous: IntArray? = null
        val totalBits = data.size * 8
        while (bitPosition + codeSize <= totalBits) {
            var code = 0
            for (i in 0 until codeSize) {
                val index = bitPosition + i
                val bit = (data[index / 8].toInt() shr (index % 8)) and 1
                code = code or (bit shl i)
            }
            bitPosition += codeSize
            when {
                code == clear -> {
                    resetDictionary()
                    codeSize = minCodeSize + 1
                    previous = null
                }
                code == end -> break
                else -> {
                    val entry: IntArray = when {
                        code < dictionary.size -> dictionary[code]
                        previous != null && code == dictionary.size -> previous + previous[0]
                        else -> error("invalid LZW code $code (dictionary size ${dictionary.size})")
                    }
                    for (value in entry) out.add(value)
                    if (previous != null) {
                        dictionary.add(previous + entry[0])
                        if (dictionary.size == (1 shl codeSize) && codeSize < 12) codeSize++
                    }
                    previous = entry
                }
            }
        }
        assertEquals("decoded pixel count", expectedLength, out.size)
        return ByteArray(out.size) { out[it].toByte() }
    }

    /** 顺序解析 GIF：跳过扩展块，解出每个图像描述符的索引数据。 */
    private fun parseFrames(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int): List<ByteArray> {
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        var p = 6
        fun u8(): Int = bytes[p++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)

        val width = u16()
        val height = u16()
        assertEquals(expectedWidth, width)
        assertEquals(expectedHeight, height)
        val packed = u8()
        u8()
        u8()
        val gctSize = 1 shl ((packed and 0x07) + 1)
        if (packed and 0x80 != 0) p += gctSize * 3

        val frames = ArrayList<ByteArray>()
        while (p < bytes.size) {
            val intro = u8()
            when (intro) {
                0x3B -> break
                0x21 -> {
                    // 扩展块（GCE / 应用扩展）：头部 + 若干子块，统一跳过。
                    u8()
                    val blockSize = u8()
                    p += blockSize
                    val (_, next) = gatherSubBlocks(bytes, p)
                    p = next
                }
                0x2C -> {
                    u16()
                    u16()
                    u16()
                    u16()
                    val imagePacked = u8()
                    if (imagePacked and 0x80 != 0) {
                        p += (1 shl ((imagePacked and 0x07) + 1)) * 3
                    }
                    val minCodeSize = u8()
                    val (data, next) = gatherSubBlocks(bytes, p)
                    p = next
                    frames += lzwDecode(data, minCodeSize, width * height)
                }
                else -> error("unexpected block 0x${intro.toString(16)} at $p")
            }
        }
        return frames
    }
}
