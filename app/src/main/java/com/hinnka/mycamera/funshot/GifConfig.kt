package com.hinnka.mycamera.funshot

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GIF / 动态贴纸 导出配置（帧间隔/循环/尺寸/配色全部开放，默认值 + 范围 + clamp）。
 *
 * 覆盖两条来源：
 *  1. **帧序列**（连拍 burst / LivePhoto 帧）→ GIF；
 *  2. **视频文件** → 抽帧 → GIF（视频能力项）。
 *
 * 字段范围：
 * - [frameIntervalMs] 20 ~ 5000；[holdLastFrameMs] 0 ~ 5000；[loopCount] 0 ~ 65535（0 = 无限循环）
 * - [maxWidth] / [maxHeight] 32 ~ 1024；[maxColors] 2 ~ 256
 * - [ditherStrength] 0 ~ 1；[maxFrames] 1 ~ 1000
 * - [videoFrameRate] 1 ~ 50；[videoStartMs] / [videoEndMs] >= 0（0 的 videoEndMs = 到视频结尾）
 * - [videoTargetWidth] 32 ~ 1024；[videoMaxFrames] 1 ~ 600
 * - [memoryBudgetBytes] 8M ~ 1G
 */
data class GifConfig(
    val frameIntervalMs: Int = 100,
    /** 末帧额外停留（0 = 不加）。 */
    val holdLastFrameMs: Int = 0,
    /** 循环次数；0 = 无限循环（GIF 的 NETSCAPE 约定），1 = 只播一次。 */
    val loopCount: Int = 0,
    val maxWidth: Int = 480,
    val maxHeight: Int = 480,
    val maxColors: Int = 256,
    /** 逐帧独立调色板（体积更大但色彩更准）；false = 全帧共享全局调色板。 */
    val perFramePalette: Boolean = false,
    val dither: Boolean = false,
    val ditherStrength: Float = 0.6f,
    val maxFrames: Int = 300,
    // ---- 视频 → GIF ----
    val videoFrameRate: Int = 12,
    val videoStartMs: Long = 0,
    /** 0 或超过时长 = 到视频结尾。 */
    val videoEndMs: Long = 0,
    val videoTargetWidth: Int = 480,
    val videoMaxFrames: Int = 120,
    // ---- 通用 ----
    val memoryBudgetBytes: Long = 256L * 1024 * 1024,
) {

    fun coerceWithReport(): FunshotClampReport<GifConfig> {
        val fixed = ArrayList<String>()
        val c = copy(
            frameIntervalMs = FunshotClamp.int(frameIntervalMs, 20, 5_000, "frameIntervalMs", fixed),
            holdLastFrameMs = FunshotClamp.int(holdLastFrameMs, 0, 5_000, "holdLastFrameMs", fixed),
            loopCount = FunshotClamp.int(loopCount, 0, 65_535, "loopCount", fixed),
            maxWidth = FunshotClamp.int(maxWidth, 32, 1_024, "maxWidth", fixed),
            maxHeight = FunshotClamp.int(maxHeight, 32, 1_024, "maxHeight", fixed),
            maxColors = FunshotClamp.int(maxColors, 2, 256, "maxColors", fixed),
            ditherStrength = FunshotClamp.float(ditherStrength, 0f, 1f, 0.6f, "ditherStrength", fixed),
            maxFrames = FunshotClamp.int(
                maxFrames, FunshotRanges.GIF_FRAME_MIN, FunshotRanges.GIF_FRAME_MAX, "maxFrames", fixed,
            ),
            videoFrameRate = FunshotClamp.int(videoFrameRate, 1, 50, "videoFrameRate", fixed),
            videoStartMs = FunshotClamp.long(videoStartMs, 0L, 86_400_000L, "videoStartMs", fixed),
            videoEndMs = FunshotClamp.long(videoEndMs, 0L, 86_400_000L, "videoEndMs", fixed),
            videoTargetWidth = FunshotClamp.int(videoTargetWidth, 32, 1_024, "videoTargetWidth", fixed),
            videoMaxFrames = FunshotClamp.int(videoMaxFrames, 1, 600, "videoMaxFrames", fixed),
            memoryBudgetBytes = FunshotClamp.long(
                memoryBudgetBytes, 8L * 1024 * 1024, 1024L * 1024 * 1024, "memoryBudgetBytes", fixed,
            ),
        )
        return FunshotClampReport(c, fixed)
    }

    /** 帧间隔（GIF 时间单位 = 1/100 秒，最小 2 = 20ms，最大 65535）。 */
    fun frameDelayCentis(intervalMs: Int = frameIntervalMs): Int =
        (intervalMs.coerceIn(20, 5_000) / 10).coerceIn(2, 65_535)

    /** 末帧延迟（含 [holdLastFrameMs]）。 */
    fun lastFrameDelayCentis(): Int = frameDelayCentis(frameIntervalMs + holdLastFrameMs)
}

/** 目标尺寸计算（等比、不放大、下限 32px）。 */
object GifOutputSize {

    fun fit(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return maxWidth.coerceAtLeast(32) to maxHeight.coerceAtLeast(32)
        val scale = min(1.0, min(maxWidth.toDouble() / sourceWidth, maxHeight.toDouble() / sourceHeight))
        val w = (sourceWidth * scale).roundToInt().coerceIn(32, maxWidth.coerceAtLeast(32))
        val h = (sourceHeight * scale).roundToInt().coerceIn(32, maxHeight.coerceAtLeast(32))
        return w to h
    }

    /** 仅有宽度约束（视频转 GIF 用）：等比缩放、不放大。 */
    fun fitWidth(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return targetWidth.coerceAtLeast(32) to targetWidth.coerceAtLeast(32)
        val width = min(targetWidth, sourceWidth).coerceAtLeast(32)
        val height = (sourceHeight.toDouble() * width / sourceWidth).roundToInt().coerceAtLeast(1)
        return width to height
    }
}

/** GIF 配置编解码（`funshot_gif_*` key）。 */
object GifConfigCodec {

    fun encode(config: GifConfig): Map<String, String> = mapOf(
        KEY_FRAME_INTERVAL to config.frameIntervalMs.toString(),
        KEY_HOLD_LAST to config.holdLastFrameMs.toString(),
        KEY_LOOP to config.loopCount.toString(),
        KEY_MAX_WIDTH to config.maxWidth.toString(),
        KEY_MAX_HEIGHT to config.maxHeight.toString(),
        KEY_MAX_COLORS to config.maxColors.toString(),
        KEY_PER_FRAME_PALETTE to config.perFramePalette.toString(),
        KEY_DITHER to config.dither.toString(),
        KEY_DITHER_STRENGTH to config.ditherStrength.toString(),
        KEY_MAX_FRAMES to config.maxFrames.toString(),
        KEY_VIDEO_FPS to config.videoFrameRate.toString(),
        KEY_VIDEO_START to config.videoStartMs.toString(),
        KEY_VIDEO_END to config.videoEndMs.toString(),
        KEY_VIDEO_WIDTH to config.videoTargetWidth.toString(),
        KEY_VIDEO_MAX_FRAMES to config.videoMaxFrames.toString(),
        KEY_MEMORY_BUDGET to config.memoryBudgetBytes.toString(),
    )

    fun decode(read: (String) -> String?): FunshotClampReport<GifConfig> {
        val d = GifConfig()
        val raw = GifConfig(
            frameIntervalMs = read(KEY_FRAME_INTERVAL)?.toIntOrNull() ?: d.frameIntervalMs,
            holdLastFrameMs = read(KEY_HOLD_LAST)?.toIntOrNull() ?: d.holdLastFrameMs,
            loopCount = read(KEY_LOOP)?.toIntOrNull() ?: d.loopCount,
            maxWidth = read(KEY_MAX_WIDTH)?.toIntOrNull() ?: d.maxWidth,
            maxHeight = read(KEY_MAX_HEIGHT)?.toIntOrNull() ?: d.maxHeight,
            maxColors = read(KEY_MAX_COLORS)?.toIntOrNull() ?: d.maxColors,
            perFramePalette = read(KEY_PER_FRAME_PALETTE)?.toBooleanStrictOrNull() ?: d.perFramePalette,
            dither = read(KEY_DITHER)?.toBooleanStrictOrNull() ?: d.dither,
            ditherStrength = read(KEY_DITHER_STRENGTH)?.toFloatOrNull() ?: d.ditherStrength,
            maxFrames = read(KEY_MAX_FRAMES)?.toIntOrNull() ?: d.maxFrames,
            videoFrameRate = read(KEY_VIDEO_FPS)?.toIntOrNull() ?: d.videoFrameRate,
            videoStartMs = read(KEY_VIDEO_START)?.toLongOrNull() ?: d.videoStartMs,
            videoEndMs = read(KEY_VIDEO_END)?.toLongOrNull() ?: d.videoEndMs,
            videoTargetWidth = read(KEY_VIDEO_WIDTH)?.toIntOrNull() ?: d.videoTargetWidth,
            videoMaxFrames = read(KEY_VIDEO_MAX_FRAMES)?.toIntOrNull() ?: d.videoMaxFrames,
            memoryBudgetBytes = read(KEY_MEMORY_BUDGET)?.toLongOrNull() ?: d.memoryBudgetBytes,
        )
        return raw.coerceWithReport()
    }

    const val KEY_FRAME_INTERVAL = "funshot_gif_frame_interval_ms"
    const val KEY_HOLD_LAST = "funshot_gif_hold_last_frame_ms"
    const val KEY_LOOP = "funshot_gif_loop_count"
    const val KEY_MAX_WIDTH = "funshot_gif_max_width"
    const val KEY_MAX_HEIGHT = "funshot_gif_max_height"
    const val KEY_MAX_COLORS = "funshot_gif_max_colors"
    const val KEY_PER_FRAME_PALETTE = "funshot_gif_per_frame_palette"
    const val KEY_DITHER = "funshot_gif_dither"
    const val KEY_DITHER_STRENGTH = "funshot_gif_dither_strength"
    const val KEY_MAX_FRAMES = "funshot_gif_max_frames"
    const val KEY_VIDEO_FPS = "funshot_gif_video_fps"
    const val KEY_VIDEO_START = "funshot_gif_video_start_ms"
    const val KEY_VIDEO_END = "funshot_gif_video_end_ms"
    const val KEY_VIDEO_WIDTH = "funshot_gif_video_width"
    const val KEY_VIDEO_MAX_FRAMES = "funshot_gif_video_max_frames"
    const val KEY_MEMORY_BUDGET = "funshot_gif_memory_budget"
}
