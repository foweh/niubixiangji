package com.hinnka.mycamera.videomodes

import kotlin.math.ceil

/**
 * §5 视频超分（后台批量任务：抽帧 → 超分 → media3 Transformer 重编码）。
 *
 * 核对结论与接线方式（**只写调用契约，不依赖对方符号**）：
 * - 工程已有 `multiframesr/`（`MultiFrameSrProcessor.run(frames, config, guard, checkpoint, spillDir, ...)`）
 *   与 `superres/`（`SuperResRuntime`：ONNX 单图超分、模型下载与门控）两个模块，
 *   但**都只面向照片**，没有视频入口 —— 本模块补「抽帧 → 逐帧超分 → 重编码」的视频编排；
 * - 真正做超分的那一步由补丁接线到上述模块（`VideoFrameUpscaler` 函数式接口，见
 *   `VideoProcessorContracts.kt`），本文件**不 import 它们的任何类型**，因此两个模块
 *   后续改签名也不会破坏本模块的可读性与可测性；
 * - 重编码用**工程已有**的 media3（`libs.media3.transformer` / `media3.effect` 已在
 *   `app/build.gradle.kts` 依赖里，无需新增依赖）。
 *
 * 参考（**思路参考，不采代码**）：
 * - XPixelGroup/BasicSR（8396★，Apache-2.0）：超分/退化建模范式；
 * - OpenImagingLab/FlashVSR（1919★，Apache-2.0）：视频流式超分的分块调度思路。
 *
 * 遵守 §1.5：**总帧数不设硬上限**，用分片（chunk）+ `BgJobRunner.guardMemory` 解决资源；
 * 内存不足时**降倍率/降分片**而不是失败。
 */

/** 视频超分运行时上限（可上调；默认即为「不额外限制」）。 */
object VideoSrLimits {
    const val ABSOLUTE_MAX_SCALE = VideoModesRanges.SR_SCALE_ABSOLUTE_MAX

    /** 用户可配的倍率上限（默认 = 绝对上限）。 */
    @Volatile
    var userMaxScale: Float = ABSOLUTE_MAX_SCALE

    fun maxScale(): Float = userMaxScale.coerceIn(VideoModesRanges.SR_SCALE_MIN, ABSOLUTE_MAX_SCALE)

    /** 用户可配的单分片帧数上限（默认 = 绝对上限 64）。 */
    @Volatile
    var userMaxChunkFrames: Int = VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX

    fun maxChunkFrames(): Int = userMaxChunkFrames.coerceIn(
        VideoModesRanges.SR_CHUNK_FRAMES_MIN,
        VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX,
    )
}

/**
 * 视频超分开放配置。
 *
 * @param enabled 后台任务开关，默认 false（既有行为 = 不处理）。
 * @param scale 超分倍率，范围 [1.5, VideoSrLimits.maxScale()]，默认 2.0；内存不足时自动降档。
 * @param frameStride 抽帧步长，范围 [1, 240]，默认 1（逐帧）；>1 表示每 N 帧取 1 帧参与超分，
 *        其余帧走原分辨率直通（省算力、避免时域跳变）。
 * @param chunkFrames 单分片帧数，范围 [1, VideoSrLimits.maxChunkFrames()]，默认 8
 *        （**分片大小，不是总帧数上限**）。
 * @param jpegQuality 中间帧/结果 JPEG 质量，范围 [60, 100]，默认 96（落盘中转用）。
 * @param denoiseFirst 超分前是否先做轻量降噪（提升 SR 稳定性），默认 false。
 * @param outputBitrateMbps 输出码率，范围 [1, 200] Mbps；0 = 沿用源视频码率，默认 0。
 * @param outputCodecName 输出编码器（"H264"/"H265"）；null = 沿用源。
 */
data class VideoSrConfig(
    val enabled: Boolean = false,
    val scale: Float = VideoModesRanges.DEFAULT_SR_SCALE,
    val frameStride: Int = VideoModesRanges.DEFAULT_SR_FRAME_STRIDE,
    val chunkFrames: Int = VideoModesRanges.DEFAULT_SR_CHUNK_FRAMES,
    val jpegQuality: Int = VideoModesRanges.DEFAULT_SR_JPEG_QUALITY,
    val denoiseFirst: Boolean = false,
    val outputBitrateMbps: Int = 0,
    val outputCodecName: String? = null,
) {
    fun clampReport(): Pair<VideoSrConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val rawScale = if (scale.isFinite()) scale else VideoModesRanges.DEFAULT_SR_SCALE
        val clampedScale = rawScale.coerceIn(VideoModesRanges.SR_SCALE_MIN, VideoSrLimits.maxScale())
        if (clampedScale != rawScale) corrections += "scale $rawScale → $clampedScale"
        val stride = frameStride.coerceIn(
            VideoModesRanges.SR_FRAME_STRIDE_MIN,
            VideoModesRanges.SR_FRAME_STRIDE_ABSOLUTE_MAX,
        )
        if (stride != frameStride) corrections += "frameStride $frameStride → $stride"
        val chunk = chunkFrames.coerceIn(
            VideoModesRanges.SR_CHUNK_FRAMES_MIN,
            VideoSrLimits.maxChunkFrames(),
        )
        if (chunk != chunkFrames) corrections += "chunkFrames $chunkFrames → $chunk"
        val quality = jpegQuality.coerceIn(
            VideoModesRanges.SR_JPEG_QUALITY_MIN,
            VideoModesRanges.SR_JPEG_QUALITY_MAX,
        )
        if (quality != jpegQuality) corrections += "jpegQuality $jpegQuality → $quality"
        val bitrate = if (outputBitrateMbps == 0) {
            0
        } else {
            VideoEncodePolicy.clampBitrateMbps(outputBitrateMbps)
        }
        if (bitrate != outputBitrateMbps) corrections += "outputBitrateMbps $outputBitrateMbps → $bitrate"
        return copy(
            scale = clampedScale,
            frameStride = stride,
            chunkFrames = chunk,
            jpegQuality = quality,
            outputBitrateMbps = bitrate,
        ) to corrections
    }

    fun clamp(): VideoSrConfig = clampReport().first
}

/** 超分任务计划（后台任务的执行单元）。 */
data class VideoSrPlan(
    /** 参与超分的帧索引分片（每个 IntRange 是一个分片，分片末尾调 `checkYield()`）。 */
    val chunks: List<IntRange>,
    /** 源视频总帧数（**不设上限**）。 */
    val totalFrames: Int,
    /** 实际参与超分的帧数 = ceil(totalFrames / frameStride)。 */
    val processedFrames: Int,
    /** 生效倍率（可能因内存守卫降档）。 */
    val scale: Float,
    /** 生效抽帧步长。 */
    val frameStride: Int,
    /** 生效单分片帧数。 */
    val chunkFrames: Int,
    /** 因内存不足发生的降档（倍率/分片）说明。 */
    val degradations: List<String>,
    /** clamp 修正项。 */
    val corrections: List<String>,
) {
    /** 降档后的输出分辨率（相对源边长的倍数）。 */
    fun outputLongEdge(sourceLongEdge: Int): Int =
        (sourceLongEdge * scale).toInt().coerceAtLeast(sourceLongEdge)
}

/** 视频超分编排器（纯逻辑，可单测）。 */
object VideoSuperResolutionPlanner {

    /** 倍率降级阶梯（从高到低；内存不足时逐级下调，仍不够就降到下限而不是失败）。 */
    val SCALE_LADDER: List<Float> = listOf(4f, 3f, 2f, 1.5f)

    /** 分片大小降级阶梯。 */
    val CHUNK_LADDER: List<Int> = listOf(8, 4, 2, 1)

    /**
     * 生成计划。
     *
     * @param totalFrames 源视频总帧数（任意大小，不做总量限制）。
     * @param guardMemory 内存守卫（生产环境传 `{ job.guardMemory(it) }`；返回 false 触发降档）。
     * @param perFrameBytes 单帧工作缓冲估算字节数（由调用方按分辨率给出）；<=0 时跳过内存降档。
     */
    fun plan(
        config: VideoSrConfig,
        totalFrames: Int,
        guardMemory: (Long) -> Boolean = { true },
        perFrameBytes: Long = 0L,
    ): VideoSrPlan {
        val (clamped, corrections) = config.clampReport()
        val total = totalFrames.coerceAtLeast(0)
        val degradations = mutableListOf<String>()

        var scale = clamped.scale
        var chunkFrames = clamped.chunkFrames
        if (perFrameBytes > 0L) {
            while (!guardMemory(perFrameBytes * chunkFrames.coerceAtLeast(1) * 2) && scale > SCALE_LADDER.last()) {
                val lower = SCALE_LADDER.filter { it < scale }.maxOrNull() ?: SCALE_LADDER.last()
                scale = lower
                degradations += "内存不足：倍率降至 ${scale}x"
            }
            while (!guardMemory(perFrameBytes * chunkFrames.coerceAtLeast(1) * 2) && chunkFrames > 1) {
                chunkFrames = CHUNK_LADDER.filter { it < chunkFrames }.maxOrNull() ?: 1
                degradations += "内存不足：分片降至 ${chunkFrames} 帧"
            }
        }

        val processedFrames = processedFrameCount(total, clamped.frameStride)
        val stride = clamped.frameStride.coerceAtLeast(1)
        // 分片按「参与超分的帧序」切分：第 k 个参与帧对应源索引 k*stride。
        val chunks = TimelapsePlanner.captureBatches(processedFrames, chunkFrames)

        return VideoSrPlan(
            chunks = chunks,
            totalFrames = total,
            processedFrames = processedFrames,
            scale = scale,
            frameStride = stride,
            chunkFrames = chunkFrames,
            degradations = degradations,
            corrections = corrections + degradations,
        )
    }

    /** 参与超分的帧数 = ceil(totalFrames / stride)，步长 1 时等于总帧数。 */
    fun processedFrameCount(totalFrames: Int, frameStride: Int): Int {
        val total = totalFrames.coerceAtLeast(0)
        if (total == 0) return 0
        val stride = frameStride.coerceAtLeast(1)
        return ceil(total.toDouble() / stride.toDouble()).toInt()
    }

    /** 第 [processedIndex] 个参与帧对应的源帧索引。 */
    fun sourceFrameIndex(processedIndex: Int, frameStride: Int): Int =
        processedIndex.coerceAtLeast(0) * frameStride.coerceAtLeast(1)
}
