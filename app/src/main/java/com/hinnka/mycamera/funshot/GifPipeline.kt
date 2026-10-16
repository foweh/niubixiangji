package com.hinnka.mycamera.funshot

import java.io.ByteArrayOutputStream

/** GIF 编码结果。 */
data class FunGifEncodeResult(
    val bytes: ByteArray,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val paletteSize: Int,
    val degraded: Boolean,
    val notes: List<String>,
) {
    val isEmpty: Boolean get() = bytes.isEmpty() || frameCount == 0

    override fun equals(other: Any?): Boolean =
        other is FunGifEncodeResult && bytes.contentEquals(other.bytes) &&
            frameCount == other.frameCount && width == other.width && height == other.height

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + frameCount * 31 + width * 31 + height
}

/**
 * 帧序列 → GIF 的**纯逻辑管线**（无 Android 依赖，可纯 JVM 单测）。
 *
 * 处理顺序：帧数治理 → 尺寸治理 → 内存守卫（降分辨率/降帧数，不拒绝）→ 调色板量化 → LZW 打包。
 * 重活由调用方放进 `BgJobRunner`（[FunshotGifTask]），本类只做算法。
 */
object FunGifPipeline {

    private const val PALETTE_SAMPLE_PIXELS = 65_536

    fun encode(
        frames: List<FunArgbFrame>,
        config: GifConfig,
        checkpoint: FunshotCheckpoint = FunshotCheckpoint { },
        guard: FunshotMemoryGuard = FunshotMemoryGuard { true },
        /** 逐帧延迟覆盖（1/100 秒；视频转 GIF 时来自抽帧计划）。长度不足按顺序取，缺失回落配置值。 */
        frameDelaysCentis: List<Int>? = null,
    ): FunGifEncodeResult {
        val effective = config.coerceWithReport().value
        val notes = ArrayList<String>()
        var degraded = false
        if (frames.isEmpty()) {
            return FunGifEncodeResult(ByteArray(0), 0, 0, 0, 0, false, listOf("no frames"))
        }

        // 1) 帧数治理（用户配置的上限，超限时等间隔抽样并说明）。
        var selected = frames
        if (frames.size > effective.maxFrames) {
            val step = frames.size.toDouble() / effective.maxFrames
            selected = List(effective.maxFrames) { index -> frames[(index * step).toInt().coerceIn(0, frames.size - 1)] }
            notes += "frames ${frames.size} > maxFrames ${effective.maxFrames}, sampled evenly"
            degraded = true
        }

        // 2) 尺寸治理（等比、不放大）。
        var targetWidth = selected[0].width
        var targetHeight = selected[0].height
        val (fittedWidth, fittedHeight) = GifOutputSize.fit(
            targetWidth, targetHeight, effective.maxWidth, effective.maxHeight,
        )
        targetWidth = fittedWidth
        targetHeight = fittedHeight

        var resized = selected.map { frame ->
            if (frame.width == targetWidth && frame.height == targetHeight) frame
            else FunArgbScaler.resize(frame, targetWidth, targetHeight)
        }

        // 3) 内存守卫：先降尺寸，再降帧数（只降不拒）。尺寸始终基于基准值重算，避免叠加缩小。
        val baseWidth = targetWidth
        val baseHeight = targetHeight
        var scale = 1.0
        while (!guard.allow(estimateBytes(targetWidth, targetHeight, resized.size)) && scale > 0.42) {
            scale -= 0.2
            targetWidth = (baseWidth * scale).toInt().coerceAtLeast(32)
            targetHeight = (baseHeight * scale).toInt().coerceAtLeast(32)
            notes += "size degraded to ${targetWidth}x$targetHeight (memory guard)"
            degraded = true
        }
        if (resized.any { it.width != targetWidth || it.height != targetHeight }) {
            resized = resized.map { FunArgbScaler.resize(it, targetWidth, targetHeight) }
        }
        if (!guard.allow(estimateBytes(targetWidth, targetHeight, resized.size)) && resized.size > 1) {
            val kept = (resized.size / 2).coerceAtLeast(1)
            val step = resized.size.toDouble() / kept
            resized = List(kept) { index -> resized[(index * step).toInt().coerceIn(0, resized.size - 1)] }
            notes += "frame count degraded to $kept (memory guard)"
            degraded = true
        }

        // 4) 调色板量化。
        val globalPalette = if (effective.perFramePalette) {
            null
        } else {
            FunGifPalette.medianCut(samplePixels(resized), effective.maxColors)
        }

        // 5) 编码。
        val buffer = ByteArrayOutputStream(64 * 1024)
        val encoder = FunGifEncoder(buffer, targetWidth, targetHeight, effective.loopCount)
        val globalIndex = globalPalette?.let { FunGifPaletteIndex(it) }
        encoder.writeHeader(globalPalette ?: emptyIntArray())
        var paletteSize = globalPalette?.size ?: 0

        for (i in resized.indices) {
            val frame = resized[i]
            val isLast = i == resized.lastIndex
            val override = frameDelaysCentis?.getOrNull(i)
            val delay = when {
                override != null -> override.coerceIn(2, 65_535)
                isLast -> effective.lastFrameDelayCentis()
                else -> effective.frameDelayCentis()
            }
            val indexer: FunGifPaletteIndex
            var localPalette: IntArray? = null
            if (effective.perFramePalette) {
                val palette = FunGifPalette.medianCut(frame.argb, effective.maxColors)
                localPalette = palette
                indexer = FunGifPaletteIndex(palette)
                paletteSize = maxOf(paletteSize, palette.size)
            } else {
                indexer = globalIndex ?: FunGifPaletteIndex(emptyIntArray())
            }
            val indices = FunGifFramePacker.pack(
                argb = frame.argb,
                width = targetWidth,
                height = targetHeight,
                index = indexer,
                dither = effective.dither,
                strength = effective.ditherStrength,
            )
            encoder.addFrame(indices, delay, localPalette)
            checkpoint.checkpoint()
        }
        encoder.finish()
        return FunGifEncodeResult(
            bytes = buffer.toByteArray(),
            frameCount = resized.size,
            width = targetWidth,
            height = targetHeight,
            paletteSize = paletteSize,
            degraded = degraded,
            notes = notes,
        )
    }

    /** 跨帧抽样像素（用于全局调色板，代价与帧数无关）。 */
    internal fun samplePixels(frames: List<FunArgbFrame>): IntArray {
        val total = frames.sumOf { it.argb.size }
        if (total == 0) return IntArray(0)
        val step = (total / PALETTE_SAMPLE_PIXELS).coerceAtLeast(1)
        val out = ArrayList<Int>(minOf(total, PALETTE_SAMPLE_PIXELS) + 1)
        for (frame in frames) {
            var i = 0
            while (i < frame.argb.size) {
                out += frame.argb[i]
                i += step
            }
        }
        return out.toIntArray()
    }

    /** 编码期内存峰值估算（帧缓冲 + 索引缓冲 + 调色板）。 */
    internal fun estimateBytes(width: Int, height: Int, frameCount: Int): Long {
        val perFrame = width.toLong() * height.toLong() * 5L // ARGB(4) + 索引(1)
        return perFrame * frameCount.coerceAtLeast(1) + 1L * 1024 * 1024
    }

    private fun emptyIntArray(): IntArray = IntArray(0)
}
