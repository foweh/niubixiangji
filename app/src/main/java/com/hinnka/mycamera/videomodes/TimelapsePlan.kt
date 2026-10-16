package com.hinnka.mycamera.videomodes

/**
 * §2 延时摄影（核对结论：工程内**无**延时模块，`grep -i timelapse|lapse|interval` 只命中
 * Camera2Controller 的录像计时字段与「判读间隔」无关代码，故本模块为新增实现）。
 *
 * 职责划分：
 * - 采集：使用既有 `Camera2Controller` 的静止捕获路径（本模块只提供间隔调度与帧计数）；
 * - 合成：**后台**走 `BgJobRunner`（见 [VideoModesBackgroundRunner.submitTimelapseCompose]），
 *   分片 + `guardMemory`，帧数不设总量上限。
 */

/**
 * 延时摄影运行时上限（**溢出保护 + 可上调**，不是功能策略上限）。
 *
 * 默认 = [VideoModesRanges.TIMELAPSE_FRAMES_ABSOLUTE_MAX]（100000）；
 * 用户可在设置页继续上调（clamp 到同一绝对上限防溢出），资源问题交给后台分片与内存守卫。
 */
object TimelapseLimits {
    const val ABSOLUTE_MAX_FRAMES = VideoModesRanges.TIMELAPSE_FRAMES_ABSOLUTE_MAX

    /** 用户可配的总帧数上限（默认 = 绝对上限，即「不额外限制」）。 */
    @Volatile
    var userMaxFrames: Int = ABSOLUTE_MAX_FRAMES

    /** 当前生效的总帧数上限（clamp 后）。 */
    fun maxFrames(): Int = userMaxFrames.coerceIn(
        VideoModesRanges.TIMELAPSE_FRAMES_MIN,
        ABSOLUTE_MAX_FRAMES,
    )
}

/**
 * 延时摄影开放配置。
 *
 * @param intervalMs 拍摄间隔，范围 [500, 60000] ms，默认 1000。
 * @param totalFrames 总帧数，范围 [2, TimelapseLimits.maxFrames()]，默认 300（**不设总量硬上限**）。
 * @param outputFps 合成成片的帧率，范围 [1, 120]，默认 30。
 * @param backgroundCompose 是否后台合成（默认 true：走 BgJobRunner，不阻塞拍摄）。
 * @param stopAfterDurationMs 会话时长提示上限（0 = 不限制，仅用于 UI 预估提示，**不截断帧数**）。
 */
data class TimelapseConfig(
    val intervalMs: Long = VideoModesRanges.DEFAULT_TIMELAPSE_INTERVAL_MS,
    val totalFrames: Int = VideoModesRanges.DEFAULT_TIMELAPSE_FRAMES,
    val outputFps: Int = VideoModesRanges.DEFAULT_TIMELAPSE_OUTPUT_FPS,
    val backgroundCompose: Boolean = true,
    val stopAfterDurationMs: Long = 0L,
) {
    fun clampReport(): Pair<TimelapseConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val interval = intervalMs.coerceIn(
            VideoModesRanges.TIMELAPSE_INTERVAL_MS_MIN,
            VideoModesRanges.TIMELAPSE_INTERVAL_MS_MAX,
        )
        if (interval != intervalMs) corrections += "intervalMs $intervalMs → $interval"
        val frameLimit = TimelapseLimits.maxFrames()
        val frames = totalFrames.coerceIn(VideoModesRanges.TIMELAPSE_FRAMES_MIN, frameLimit)
        if (frames != totalFrames) corrections += "totalFrames $totalFrames → $frames"
        val fps = outputFps.coerceIn(
            VideoModesRanges.TIMELAPSE_OUTPUT_FPS_MIN,
            VideoModesRanges.TIMELAPSE_OUTPUT_FPS_MAX,
        )
        if (fps != outputFps) corrections += "outputFps $outputFps → $fps"
        val durationHint = stopAfterDurationMs.coerceAtLeast(0L)
        if (durationHint != stopAfterDurationMs) corrections += "stopAfterDurationMs → $durationHint"
        return TimelapseConfig(
            intervalMs = interval,
            totalFrames = frames,
            outputFps = fps,
            backgroundCompose = backgroundCompose,
            stopAfterDurationMs = durationHint,
        ) to corrections
    }

    fun clamp(): TimelapseConfig = clampReport().first
}

/** 延时计划（UI 预估 + 后台任务参数）。 */
data class TimelapsePlan(
    val intervalMs: Long,
    val totalFrames: Int,
    val outputFps: Int,
    /** 拍摄阶段总耗时（ms）：`intervalMs × (totalFrames - 1)`。 */
    val shootingDurationMs: Long,
    /** 成片时长（ms）：`totalFrames / outputFps × 1000`。 */
    val playbackDurationMs: Long,
    val backgroundCompose: Boolean,
    val corrections: List<String>,
)

/** 延时摄影调度器（纯逻辑，可单测）。 */
object TimelapsePlanner {

    fun plan(config: TimelapseConfig): TimelapsePlan {
        val (clamped, corrections) = config.clampReport()
        val shooting = shootingDurationMs(clamped)
        val playback = playbackDurationMs(clamped)
        return TimelapsePlan(
            intervalMs = clamped.intervalMs,
            totalFrames = clamped.totalFrames,
            outputFps = clamped.outputFps,
            shootingDurationMs = shooting,
            playbackDurationMs = playback,
            backgroundCompose = clamped.backgroundCompose,
            corrections = corrections,
        )
    }

    /** 拍摄阶段总耗时（ms）；帧数 <=1 时为 0。 */
    fun shootingDurationMs(config: TimelapseConfig): Long {
        val frames = config.totalFrames.coerceAtLeast(0)
        if (frames <= 1) return 0L
        return config.intervalMs.coerceAtLeast(0L) * (frames - 1).toLong()
    }

    /** 成片时长（ms）。 */
    fun playbackDurationMs(config: TimelapseConfig): Long {
        val fps = config.outputFps.coerceAtLeast(1)
        return config.totalFrames.coerceAtLeast(0).toLong() * 1000L / fps
    }

    /** 第 [index] 帧的触发时间（相对会话开始的 ms）；索引越界自动夹取到有效范围。 */
    fun frameTimestampMs(config: TimelapseConfig, index: Int): Long {
        val safeIndex = index.coerceIn(0, (config.totalFrames - 1).coerceAtLeast(0))
        return config.intervalMs.coerceAtLeast(0L) * safeIndex.toLong()
    }

    /**
     * 是否应在 [elapsedMs] 触发下一帧。
     *
     * @param lastCaptureElapsedMs 上一帧触发时间；传 [NO_CAPTURE_YET] 表示尚未拍过（立即触发）。
     * @param elapsedMs 会话已流逝时间（<0 视为 0，防时钟回拨导致立刻连拍）。
     */
    fun shouldCaptureAt(
        config: TimelapseConfig,
        elapsedMs: Long,
        lastCaptureElapsedMs: Long,
    ): Boolean {
        val now = elapsedMs.coerceAtLeast(0L)
        if (lastCaptureElapsedMs == NO_CAPTURE_YET) return true
        val last = lastCaptureElapsedMs.coerceAtLeast(0L)
        if (now < last) return false // 时钟回拨：不触发，避免重复连拍
        return now - last >= config.intervalMs.coerceAtLeast(1L)
    }

    /**
     * 采集分片计划：把 [totalFrames] 个帧索引切成若干批（**分片大小，不是总量上限**）。
     * 每批完成后由调用方 `job.checkYield()`，实现「可长时间跑但峰值受限」。
     */
    fun captureBatches(totalFrames: Int, batchSize: Int): List<IntRange> {
        val total = totalFrames.coerceAtLeast(0)
        if (total == 0) return emptyList()
        val size = batchSize.coerceAtLeast(1)
        val result = ArrayList<IntRange>((total + size - 1) / size)
        var start = 0
        while (start < total) {
            val end = (start + size - 1).coerceAtMost(total - 1)
            result += start..end
            start = end + 1
        }
        return result
    }

    /** 尚未拍过任何一帧的哨兵值。 */
    const val NO_CAPTURE_YET = -1L
}
