package com.hinnka.mycamera.videomodes

/**
 * §10 视频降噪（后台任务：经典时域/空域降噪，轻量档位可配）。
 *
 * 核对结论：工程已有的降噪能力都面向**照片**——
 * `lenscorrection/`（镜头/去噪 profile）、`lut/ChromaDenoiseAlgorithm.kt`（色度降噪）、
 * `processor/GlesMgcRawSpatialStacker`（RAW 空间降噪）、`mgc_denoise`（NDK）；视频侧没有入口，
 * 故本模块补「视频降噪任务编排 + 档位配置」。
 *
 * 参考：opencv/opencv（91191★，Apache-2.0）——仅参考其**经典算法思路**
 * （时域均值/中值、双边/非局部均值、`fastNlMeansDenoisingColored` 的强度语义），
 * **不采其代码**（OpenCV C++ 会显著增加包体；此处按 §9 复用优先原则，走
 * 「已有 GL/CPU 管线 + 分片后台调度」实现，见 changelog 来源标注）。
 *
 * 遵守 §1.5：时域窗口帧数是**开放配置**（`temporalFrames`），不做总量硬上限。
 */

/** 视频降噪运行时上限（可上调；默认即为「不额外限制」）。 */
object VideoDenoiseLimits {
    const val ABSOLUTE_MAX_TEMPORAL_FRAMES = VideoModesRanges.DENOISE_TEMPORAL_FRAMES_ABSOLUTE_MAX

    /** 用户可配的时域窗口帧数上限（默认 = 绝对上限）。 */
    @Volatile
    var userMaxTemporalFrames: Int = ABSOLUTE_MAX_TEMPORAL_FRAMES

    fun maxTemporalFrames(): Int = userMaxTemporalFrames.coerceIn(
        VideoModesRanges.DENOISE_TEMPORAL_FRAMES_MIN,
        ABSOLUTE_MAX_TEMPORAL_FRAMES,
    )

    /** 用户可配的单分片帧数上限（默认 64）。 */
    @Volatile
    var userMaxChunkFrames: Int = VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX

    fun maxChunkFrames(): Int = userMaxChunkFrames.coerceIn(
        VideoModesRanges.SR_CHUNK_FRAMES_MIN,
        VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX,
    )
}

/** 降噪模式。 */
enum class VideoDenoiseMode(val label: String) {
    /** 纯时域（跨帧对齐累加，最适合静态/低速运镜，最省算力）。 */
    TEMPORAL("时域"),

    /** 纯空域（单帧双边/均值，适合高速运动，可能有拖影/细节损失）。 */
    SPATIAL("空域"),

    /** 时域 + 空域级联（质量最好，最慢）。 */
    TEMPORAL_SPATIAL("时域+空域");

    companion object {
        fun fromName(name: String?): VideoDenoiseMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TEMPORAL
    }
}

/**
 * 视频降噪开放配置。
 *
 * @param enabled 后台任务开关，默认 false（既有行为 = 不处理）。
 * @param mode 降噪模式，默认 [VideoDenoiseMode.TEMPORAL]。
 * @param strength 降噪强度，范围 [0, 1]，默认 0.5；0 = 等效关闭。
 * @param radius 空域滤波半径，范围 [1, 4]，默认 2。
 * @param temporalFrames 时域窗口帧数（含当前帧），范围 [2, VideoDenoiseLimits.maxTemporalFrames()]，默认 3。
 * @param chunkFrames 单分片帧数（**分片大小，不是总帧数上限**），默认 8。
 * @param preserveDetailOnMotion 运动场景自动减弱强度（防拖影），默认 true。
 */
data class VideoDenoiseConfig(
    val enabled: Boolean = false,
    val mode: VideoDenoiseMode = VideoDenoiseMode.TEMPORAL,
    val strength: Float = VideoModesRanges.DEFAULT_DENOISE_STRENGTH,
    val radius: Int = VideoModesRanges.DEFAULT_DENOISE_RADIUS,
    val temporalFrames: Int = VideoModesRanges.DEFAULT_DENOISE_TEMPORAL_FRAMES,
    val chunkFrames: Int = VideoModesRanges.DEFAULT_SR_CHUNK_FRAMES,
    val preserveDetailOnMotion: Boolean = true,
) {
    fun clampReport(): Pair<VideoDenoiseConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val rawStrength = if (strength.isFinite()) strength else VideoModesRanges.DEFAULT_DENOISE_STRENGTH
        val clampedStrength = rawStrength.coerceIn(
            VideoModesRanges.DENOISE_STRENGTH_MIN,
            VideoModesRanges.DENOISE_STRENGTH_MAX,
        )
        if (clampedStrength != rawStrength) corrections += "strength $rawStrength → $clampedStrength"
        val r = radius.coerceIn(VideoModesRanges.DENOISE_RADIUS_MIN, VideoModesRanges.DENOISE_RADIUS_MAX)
        if (r != radius) corrections += "radius $radius → $r"
        val frames = temporalFrames.coerceIn(
            VideoModesRanges.DENOISE_TEMPORAL_FRAMES_MIN,
            VideoDenoiseLimits.maxTemporalFrames(),
        )
        if (frames != temporalFrames) corrections += "temporalFrames $temporalFrames → $frames"
        val chunk = chunkFrames.coerceIn(
            VideoModesRanges.SR_CHUNK_FRAMES_MIN,
            VideoDenoiseLimits.maxChunkFrames(),
        )
        if (chunk != chunkFrames) corrections += "chunkFrames $chunkFrames → $chunk"
        return copy(
            strength = clampedStrength,
            radius = r,
            temporalFrames = frames,
            chunkFrames = chunk,
        ) to corrections
    }

    fun clamp(): VideoDenoiseConfig = clampReport().first

    /** 是否实际生效（开关打开且强度 > 0）。 */
    val effective: Boolean get() = enabled && strength > 0.0001f
}

/** 降噪任务计划。 */
data class VideoDenoisePlan(
    val chunks: List<IntRange>,
    val totalFrames: Int,
    /** 时域窗口数量 = 总帧数（每个帧作为窗口中心滑过一遍）。 */
    val windowCount: Int,
    val mode: VideoDenoiseMode,
    val strength: Float,
    val radius: Int,
    val temporalFrames: Int,
    val chunkFrames: Int,
    val corrections: List<String>,
)

/** 视频降噪编排器（纯逻辑，可单测）。 */
object VideoDenoisePlanner {

    /**
     * 生成计划。**帧数不设上限**：分片 + 分片末尾 `checkYield()` 解决长视频资源问题。
     */
    fun plan(
        config: VideoDenoiseConfig,
        totalFrames: Int,
        guardMemory: (Long) -> Boolean = { true },
        perFrameBytes: Long = 0L,
    ): VideoDenoisePlan {
        val (clamped, corrections) = config.clampReport()
        val total = totalFrames.coerceAtLeast(0)
        var chunkFrames = clamped.chunkFrames
        val degradations = mutableListOf<String>()
        if (perFrameBytes > 0L) {
            while (!guardMemory(perFrameBytes * clamped.temporalFrames) && chunkFrames > 1) {
                chunkFrames = (chunkFrames / 2).coerceAtLeast(1)
                degradations += "内存不足：分片降至 ${chunkFrames} 帧"
            }
        }
        return VideoDenoisePlan(
            chunks = TimelapsePlanner.captureBatches(total, chunkFrames),
            totalFrames = total,
            windowCount = windowCount(total, clamped.temporalFrames),
            mode = clamped.mode,
            strength = clamped.strength,
            radius = clamped.radius,
            temporalFrames = clamped.temporalFrames,
            chunkFrames = chunkFrames,
            corrections = corrections + degradations,
        )
    }

    /** 时域窗口数：每个帧都会作为窗口中心滑过一遍，故等于总帧数。 */
    fun windowCount(totalFrames: Int, temporalFrames: Int): Int {
        val total = totalFrames.coerceAtLeast(0)
        if (total == 0) return 0
        return total
    }

    /** 某个窗口实际可用的帧索引区间（边界处自动收窄，不越界）。 */
    fun windowRange(
        centerIndex: Int,
        temporalFrames: Int,
        totalFrames: Int,
    ): IntRange {
        if (totalFrames <= 0) return IntRange.EMPTY
        val window = temporalFrames.coerceAtLeast(
            VideoModesRanges.DENOISE_TEMPORAL_FRAMES_MIN,
        )
        val half = window / 2
        val start = (centerIndex - half).coerceIn(0, totalFrames - 1)
        val end = (centerIndex + half).coerceIn(0, totalFrames - 1)
        return if (start <= end) start..end else end..start
    }
}
