package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve

/**
 * 帧级后台任务的协作接口（纯 Kotlin 抽象）。
 *
 * 存在的意义：让 [LogVideoFrameProcessor] / 曲线查表 **保持纯 Kotlin 可单测**，
 * 而把 Android 侧的 `BgJobRunner`（节流/暂停/取消/内存守卫）适配留到
 * `LogVideoBakeJob`（见该类中的 `BgJobSinkAdapter`）。
 */
interface LogFrameJobSink {
    val isCancelled: Boolean

    /** 上报进度（分片完成时调用）。 */
    fun report(done: Int, total: Int)

    /** 分片间隙调用：响应取消/暂停、施加 CPU 节流并让出时间片。 */
    fun checkYield()
}

/**
 * Log 视频帧处理器（Log 视频 20 项 · 第 1 / 3 项）：**CPU 侧** Log 曲线查表编码。
 *
 * 用途：
 *  1. **Log 实况照片的静止帧**——实况照片的静止帧走拍照管线（不经 GL 的 Log 编码），
 *     需要在此按同一配方做一次 CPU Log 编码，才能保证「静止帧 = 视频帧」同一灰片观感；
 *  2. **离线校验**——用同一配方对测试图逐像素编码，与 GL 输出/单测已知点比对；
 *  3. 无 GL 上下文的降级路径（例如导出/远端处理）。
 *
 * 输入域约定：静止帧像素为 **sRGB 显示编码**（工程显示域），因此先 `SRGB.logToLinear`
 * 转到线性，再查 [LogCurveTable]（与 GL 侧 `srgbToLinear → prepareLogLinearInput → log 编码`
 * 的顺序一致；GL 侧额外的 `exp2(1.24)` 增益属于相机输入适配，静止帧不适用）。
 */
object LogVideoFrameProcessor {

    /** 默认分片高度（行），与 `BgTaskType.AI_LUT.defaultMaxFrameBatch` 同量级。 */
    const val DEFAULT_CHUNK_ROWS = 16

    /**
     * 就地编码 ARGB_8888 像素（[android.graphics.Bitmap.getPixels] 的数组）。
     *
     * @return 同一个数组（就地修改，避免位图级额外内存：4K 帧约 33MB 复制开销）。
     */
    fun encodeArgbInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: LogCurveTable,
        sink: LogFrameJobSink? = null,
        chunkRows: Int = DEFAULT_CHUNK_ROWS,
    ): IntArray {
        require(width > 0 && height > 0) { "invalid frame size ${width}x$height" }
        require(pixels.size >= width * height) { "pixel buffer smaller than ${width}x$height" }
        val rowsPerChunk = chunkRows.coerceAtLeast(1)
        var row = 0
        while (row < height) {
            if (sink?.isCancelled == true) return pixels
            val endRow = minOf(row + rowsPerChunk, height)
            var index = row * width
            val endIndex = endRow * width
            while (index < endIndex) {
                pixels[index] = encodeArgbPixel(pixels[index], table)
                index++
            }
            sink?.report(endRow, height)
            sink?.checkYield()
            row = endRow
        }
        return pixels
    }

    /** 返回新数组（不修改入参），供需要保留原图的路径使用。 */
    fun encodeArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: LogCurveTable,
        sink: LogFrameJobSink? = null,
        chunkRows: Int = DEFAULT_CHUNK_ROWS,
    ): IntArray = encodeArgbInPlace(pixels.copyOf(), width, height, table, sink, chunkRows)

    /** 单像素：ARGB(sRGB) → ARGB(Log 代码)，alpha 保持不变。 */
    fun encodeArgbPixel(argb: Int, table: LogCurveTable): Int {
        val a = (argb ushr 24) and 0xFF
        val r = encodeChannel((argb shr 16) and 0xFF, table)
        val g = encodeChannel((argb shr 8) and 0xFF, table)
        val b = encodeChannel(argb and 0xFF, table)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 单通道：0..255 的 sRGB 代码 → 0..255 的 Log 代码。 */
    fun encodeChannel(srgbCode: Int, table: LogCurveTable): Int {
        val normalized = srgbCode.coerceIn(0, 255) / 255f
        val linear = TransferCurve.SRGB.logToLinear(normalized)
        val logCode = table.sample(linear)
        return (logCode.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * 浮点线性帧（0..1，RGB 顺序）→ Log 代码帧。供测试与无 sRGB 往返的场景使用。
     */
    fun encodeLinearRgb(
        linear: FloatArray,
        table: LogCurveTable,
        sink: LogFrameJobSink? = null,
        channelBatch: Int = DEFAULT_CHUNK_ROWS * 3,
    ): FloatArray {
        val out = FloatArray(linear.size)
        var index = 0
        while (index < linear.size) {
            if (sink?.isCancelled == true) return out
            val end = minOf(index + channelBatch, linear.size)
            while (index < end) {
                out[index] = table.sample(linear[index])
                index++
            }
            sink?.report(index, linear.size)
            sink?.checkYield()
        }
        return out
    }
}
