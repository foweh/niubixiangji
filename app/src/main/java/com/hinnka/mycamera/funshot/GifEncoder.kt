package com.hinnka.mycamera.funshot

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * GIF89a 编码器（**自研**：文件结构 + LZW 均按 GIF89a 规范实现，见 `changelog_drafts/funshot.md` 的来源说明）。
 *
 * 支持：全局/局部调色板、帧延迟（1/100 秒）、无限或指定次数循环、可选透明色索引。
 * 所有尺寸/延迟在入口 clamp，非法输入抛 [IllegalArgumentException]（由任务层兜底为失败提示，不崩溃 App）。
 *
 * 说明：LZW 为 GIF89a 规范规定的压缩算法（公开标准），实现使用标准字典编码，
 * 不使用任何第三方源码（Unisys LZW 专利已于 2004 年全球到期）。
 */
class FunGifEncoder(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    val loopCount: Int = 0,
) {

    init {
        require(width in 1..65_535) { "gif width out of range: $width" }
        require(height in 1..65_535) { "gif height out of range: $height" }
    }

    private var headerWritten = false
    private var frameCount = 0
    private var globalPaletteBits = 8

    val framesWritten: Int get() = frameCount

    /** 写文件头 + 逻辑屏幕描述符 + 全局调色板（+ 循环扩展）。重复调用无效。 */
    fun writeHeader(globalPalette: IntArray) {
        if (headerWritten) return
        val padded = FunGifPalette.padToPowerOfTwo(
            if (globalPalette.isEmpty()) intArrayOf(0xFF000000.toInt()) else globalPalette
        )
        val bits = FunGifPalette.sizeBits(padded.size)
        writeAscii(HEADER)
        writeShort(width)
        writeShort(height)
        // packed: 全局色表标志 | 颜色分辨率(8bit-1) | 未排序 | 色表位宽-1
        out.write(0x80 or ((8 - 1) shl 4) or (bits - 1))
        out.write(0) // 背景色索引
        out.write(0) // 像素宽高比
        writePalette(padded, 1 shl bits)
        if (loopCount != 1) writeNetscapeLoop(loopCount)
        globalPaletteBits = bits
        headerWritten = true
    }

    /**
     * 追加一帧。
     * @param indices 行主序调色板索引，长度必须等于 `width × height`
     * @param delayCentis 帧延迟（1/100 秒），clamp 2 ~ 65535
     * @param localPalette 非空则写局部色表（逐帧调色板模式）
     * @param transparentIndex 0 ~ 255 时开启透明；-1 表示不透明
     */
    fun addFrame(
        indices: ByteArray,
        delayCentis: Int,
        localPalette: IntArray? = null,
        transparentIndex: Int = -1,
    ) {
        require(headerWritten) { "writeHeader must be called before addFrame" }
        require(indices.size == width * height) {
            "frame indices ${indices.size} != $width x $height"
        }
        val delay = delayCentis.coerceIn(2, 65_535)
        val transparent = transparentIndex in 0..255

        // Graphics Control Extension
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write((1 shl 2) or (if (transparent) 1 else 0)) // 处置方式=1（保留），无用户输入
        writeShort(delay)
        out.write(if (transparent) transparentIndex else 0)
        out.write(0x00)

        val activePalette: IntArray
        val useLocal = localPalette != null
        if (useLocal) {
            activePalette = FunGifPalette.padToPowerOfTwo(
                if (localPalette!!.isEmpty()) intArrayOf(0xFF000000.toInt()) else localPalette
            )
        } else {
            activePalette = IntArray(0)
        }

        // Image Descriptor
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        if (useLocal) {
            val localBits = FunGifPalette.sizeBits(activePalette.size)
            out.write(0x80 or (localBits - 1))
        } else {
            out.write(0x00)
        }
        if (useLocal) writePalette(activePalette, 1 shl FunGifPalette.sizeBits(activePalette.size))

        val colorBits = if (useLocal) FunGifPalette.sizeBits(activePalette.size) else globalPaletteBits
        val minCodeSize = colorBits.coerceIn(2, 8)
        out.write(minCodeSize)
        out.write(FunGifLzw.encode(indices, minCodeSize))
        frameCount++
    }

    /** 写文件结束符（0x3B）。 */
    fun finish() {
        if (!headerWritten) writeHeader(intArrayOf(0xFF000000.toInt()))
        out.write(0x3B)
        out.flush()
    }

    private fun writeNetscapeLoop(loop: Int) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        writeAscii("NETSCAPE2.0")
        out.write(0x03)
        out.write(0x01)
        writeShort(loop.coerceIn(0, 65_535))
        out.write(0x00)
    }

    private fun writePalette(palette: IntArray, entries: Int) {
        for (i in 0 until entries) {
            val color = if (i < palette.size) palette[i] else palette.lastOrNull() ?: 0
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun writeAscii(text: String) {
        for (ch in text) out.write(ch.code and 0xFF)
    }

    companion object {
        private const val HEADER = "GIF89a"
    }
}

/**
 * GIF LZW 压缩（GIF89a 规范实现，自研）。
 *
 * 输出为「子块」格式：每块 ≤255 字节并带长度前缀，最后以 0x00 结束——
 * 可直接接在图像描述符的 min-code-size 字节之后。
 */
internal object FunGifLzw {

    private const val MAX_CODE = 0x1000

    fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
        val size = minCodeSize.coerceIn(2, 8)
        val clearCode = 1 shl size
        val endCode = clearCode + 1
        val writer = BitWriter()
        var codeSize = size + 1
        var next = endCode + 1
        val dict = HashMap<Int, Int>(1 shl 14)

        writer.write(clearCode, codeSize)
        if (indices.isEmpty()) {
            writer.write(endCode, codeSize)
            return writer.toSubBlocks()
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val nextByte = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or nextByte
            val existing = dict[key]
            if (existing != null) {
                prefix = existing
                continue
            }
            writer.write(prefix, codeSize)
            if (next < MAX_CODE) {
                dict[key] = next
                next++
                // 码长提升时机：**下一个待分配码**超过当前码长可表示的最大值（`1 shl codeSize`）时提升。
                // 注意这里是 `>`：解码端建表比编码端晚一步（读到下一个码才补上当前条目），
                // 因此提升必须比"刚插入的条目"再晚一个码——与 giflib `EGifCompressOutput` 一致。
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                writer.write(clearCode, codeSize)
                dict.clear()
                next = endCode + 1
                codeSize = size + 1
            }
            prefix = nextByte
        }
        writer.write(prefix, codeSize)
        writer.write(endCode, codeSize)
        return writer.toSubBlocks()
    }

    /** 低位优先的位写入器 + GIF 子块打包。 */
    private class BitWriter {
        private val bytes = ByteArrayOutputStream(4096)
        private var accumulator = 0
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            accumulator = accumulator or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                bytes.write(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
        }

        fun toSubBlocks(): ByteArray {
            while (bitCount > 0) {
                bytes.write(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
            val raw = bytes.toByteArray()
            val out = ByteArrayOutputStream(raw.size + raw.size / 255 + 2)
            var offset = 0
            while (offset < raw.size) {
                val length = minOf(255, raw.size - offset)
                out.write(length)
                out.write(raw, offset, length)
                offset += length
            }
            out.write(0x00)
            return out.toByteArray()
        }
    }
}
