package com.hinnka.mycamera.videomodes

import kotlin.math.ceil

/**
 * §13 循环录制（环形缓冲区，缓冲时长 1~60s 可配 + 循环覆盖开关）。
 *
 * 核对结论：工程内**没有**循环录制（`grep -rn "loopRecording|LoopRecording|RingBuffer"` 无命中，
 * `processor/GlesRawAEStats` 命中的是 GPU 计量缓冲，与录像无关），故本模块为新增。
 *
 * 实现形态（由补丁接线）：分段落盘（每 [LoopRecordingConfig.segmentSeconds] 秒一个分段文件）
 * + 环形索引；缓冲预算按「时长 × 码率」推算，超出即按 [LoopRecordingConfig.overwriteOldest]
 * 决定「覆盖最旧分段」还是「停止录制并提示」。
 *
 * 本文件只做**预算/边界/覆盖策略**（纯逻辑），不碰 MediaRecorder/MediaMuxer 的调用。
 */

/**
 * 循环录制开放配置。
 *
 * @param enabled 是否启用循环录制，默认 false（既有行为 = 普通录制，不覆盖）。
 * @param bufferSeconds 保留时长，范围 **[1, 60] s**，默认 10；越界 clamp。
 * @param overwriteOldest 缓冲满时是否覆盖最旧分段（false = 停止录制并提示），默认 true。
 * @param segmentSeconds 分段时长，范围 [1, 10] s，默认 2（影响覆盖粒度与丢帧窗口）。
 */
data class LoopRecordingConfig(
    val enabled: Boolean = false,
    val bufferSeconds: Int = VideoModesRanges.DEFAULT_LOOP_BUFFER_SECONDS,
    val overwriteOldest: Boolean = true,
    val segmentSeconds: Int = VideoModesRanges.DEFAULT_LOOP_SEGMENT_SECONDS,
) {
    fun clampReport(): Pair<LoopRecordingConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val buffer = LoopRecordingBufferPolicy.normalizeBufferSeconds(bufferSeconds)
        if (buffer != bufferSeconds) corrections += "bufferSeconds $bufferSeconds → $buffer"
        val segment = LoopRecordingBufferPolicy.normalizeSegmentSeconds(segmentSeconds)
        if (segment != segmentSeconds) corrections += "segmentSeconds $segmentSeconds → $segment"
        // 分段时长不得超过总缓冲时长（否则一个分段就撑满环形窗口，失去覆盖意义）。
        val finalSegment = segment.coerceAtMost(buffer.coerceAtLeast(1))
        if (finalSegment != segment) {
            corrections += "segmentSeconds $segment → $finalSegment（不超过缓冲时长）"
        }
        return copy(bufferSeconds = buffer, segmentSeconds = finalSegment) to corrections
    }

    fun clamp(): LoopRecordingConfig = clampReport().first
}

/** 环形缓冲预算计划。 */
data class LoopBufferPlan(
    val bufferSeconds: Int,
    val segmentSeconds: Int,
    /** 需要保留的分段数 = ceil(bufferSeconds / segmentSeconds)。 */
    val segmentCount: Int,
    /** 需为环形缓冲预留的近似磁盘预算（字节）。 */
    val maxBytes: Long,
    val overwriteOldest: Boolean,
    val corrections: List<String>,
    val reason: String,
)

/** 缓冲满时的动作。 */
enum class LoopBufferAction {
    /** 覆盖最旧分段（继续录）。 */
    OVERWRITE_OLDEST,

    /** 停止录制（不覆盖）。 */
    STOP_RECORDING,

    /** 循环录制未启用。 */
    DISABLED,
}

/** 循环录制策略（纯逻辑）。 */
object LoopRecordingBufferPolicy {

    fun normalizeBufferSeconds(seconds: Int): Int = seconds.coerceIn(
        VideoModesRanges.LOOP_BUFFER_SECONDS_MIN,
        VideoModesRanges.LOOP_BUFFER_SECONDS_MAX,
    )

    fun normalizeSegmentSeconds(seconds: Int): Int = seconds.coerceIn(
        VideoModesRanges.LOOP_SEGMENT_SECONDS_MIN,
        VideoModesRanges.LOOP_SEGMENT_SECONDS_MAX,
    )

    /** 分段数 = ceil(缓冲时长 / 分段时长)，保证总覆盖时长 >= 缓冲时长。 */
    fun segmentCount(bufferSeconds: Int, segmentSeconds: Int): Int {
        val buffer = normalizeBufferSeconds(bufferSeconds)
        val segment = normalizeSegmentSeconds(segmentSeconds).coerceAtMost(buffer)
        return ceil(buffer.toDouble() / segment.toDouble()).toInt()
    }

    /**
     * 生成预算计划。
     *
     * @param bitrateMbps 视频码率（Mbps）；用于推算磁盘预算（音频/容器开销忽略，留 10% 余量）。
     */
    fun plan(config: LoopRecordingConfig, bitrateMbps: Int): LoopBufferPlan {
        val (clamped, corrections) = config.clampReport()
        val segments = segmentCount(clamped.bufferSeconds, clamped.segmentSeconds)
        val bitrateBps = VideoEncodePolicy.clampBitrateMbps(bitrateMbps).toLong() * 1_000_000L
        val rawBytes = clamped.bufferSeconds.toLong() * bitrateBps / 8L
        val maxBytes = (rawBytes * 11L / 10L).coerceAtLeast(1L)
        return LoopBufferPlan(
            bufferSeconds = clamped.bufferSeconds,
            segmentSeconds = clamped.segmentSeconds,
            segmentCount = segments,
            maxBytes = maxBytes,
            overwriteOldest = clamped.overwriteOldest,
            corrections = corrections,
            reason = "保留最近 ${clamped.bufferSeconds}s（${clamped.segmentSeconds}s × $segments 段，" +
                "约 ${maxBytes / (1024 * 1024)}MB），" +
                if (clamped.overwriteOldest) "满后覆盖最旧分段" else "满后停止录制",
        )
    }

    /** 缓冲满时的动作。 */
    fun onBufferFull(config: LoopRecordingConfig): LoopBufferAction {
        val (clamped, _) = config.clampReport()
        if (!clamped.enabled) return LoopBufferAction.DISABLED
        return if (clamped.overwriteOldest) {
            LoopBufferAction.OVERWRITE_OLDEST
        } else {
            LoopBufferAction.STOP_RECORDING
        }
    }

    /** 由计划直接取动作（避免上层重复 clamp）。 */
    fun onBufferFull(plan: LoopBufferPlan): LoopBufferAction =
        if (plan.overwriteOldest) LoopBufferAction.OVERWRITE_OLDEST else LoopBufferAction.STOP_RECORDING

    /**
     * 当前已落盘分段列表是否需要淘汰（返回应删除的分段数）。
     *
     * @param storedSegmentCount 已保留分段数。
     * @param plan 预算计划。
     */
    fun segmentsToEvict(storedSegmentCount: Int, plan: LoopBufferPlan): Int =
        (storedSegmentCount - plan.segmentCount).coerceAtLeast(0)
}
