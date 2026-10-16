package com.hinnka.mycamera.videomodes

/**
 * §12 边录边拍快照（录制中按间隔抓帧存 JPEG，间隔/质量可配）。
 *
 * 核对结论：工程内没有「录制中抓帧落盘」的入口
 * （`grep -rn "takePicture.*recording\|snapshotDuring"` 无命中；既有
 * `livephoto/` 是照片侧的动态照片、`screencapture/` 是屏幕捕获，均不等价），故本模块为新增。
 *
 * 抓帧数据源二选一（由补丁接线）：
 * - 预览侧：既有 `ui/camera/CameraGLSurfaceView.capturePreviewFrame(maxLongEdge)`（已在用）；
 * - 录制侧：`VideoRecorder` 的编码输入 Surface 旁路 reader（若 HAL 允许第三路流）。
 *
 * 背压策略：落盘排队超过 [SnapshotDuringRecordingConfig.maxPending] 时**丢帧并提示**，
 * 绝不阻塞录制主链路（录制优先）。
 */

/** 快照输出长边范围与默认值（与既有预览抓帧默认一致：1440）。 */
const val SNAPSHOT_LONG_EDGE_MIN = 320
const val SNAPSHOT_LONG_EDGE_MAX = 8192
const val SNAPSHOT_DEFAULT_LONG_EDGE = 1440

/**
 * 边录边拍开放配置。
 *
 * @param enabled 是否启用，默认 false（既有行为 = 不抓帧）。
 * @param intervalMs 抓帧间隔，范围 **[100, 600000] ms**，默认 1000。
 * @param jpegQuality JPEG 质量，范围 [60, 100]，默认 95。
 * @param maxPending 未落盘快照的最大排队数，范围 [1, 64]，默认 3；超出即丢帧。
 * @param maxLongEdge 输出长边，范围 [320, 8192]，默认 1440（与既有预览抓帧默认一致）。
 * @param source 抓帧数据源。
 */
data class SnapshotDuringRecordingConfig(
    val enabled: Boolean = false,
    val intervalMs: Long = VideoModesRanges.DEFAULT_SNAPSHOT_INTERVAL_MS,
    val jpegQuality: Int = VideoModesRanges.DEFAULT_SNAPSHOT_JPEG_QUALITY,
    val maxPending: Int = VideoModesRanges.DEFAULT_SNAPSHOT_MAX_PENDING,
    val maxLongEdge: Int = SNAPSHOT_DEFAULT_LONG_EDGE,
    val source: SnapshotSource = SnapshotSource.PREVIEW,
) {
    fun clampReport(): Pair<SnapshotDuringRecordingConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val interval = SnapshotIntervalPolicy.normalizeIntervalMs(intervalMs)
        if (interval != intervalMs) corrections += "intervalMs $intervalMs → $interval"
        val quality = jpegQuality.coerceIn(
            VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MIN,
            VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MAX,
        )
        if (quality != jpegQuality) corrections += "jpegQuality $jpegQuality → $quality"
        val pending = maxPending.coerceIn(
            VideoModesRanges.SNAPSHOT_MAX_PENDING_MIN,
            VideoModesRanges.SNAPSHOT_MAX_PENDING_MAX,
        )
        if (pending != maxPending) corrections += "maxPending $maxPending → $pending"
        val longEdge = maxLongEdge.coerceIn(SNAPSHOT_LONG_EDGE_MIN, SNAPSHOT_LONG_EDGE_MAX)
        if (longEdge != maxLongEdge) corrections += "maxLongEdge $maxLongEdge → $longEdge"
        return copy(
            intervalMs = interval,
            jpegQuality = quality,
            maxPending = pending,
            maxLongEdge = longEdge,
        ) to corrections
    }

    fun clamp(): SnapshotDuringRecordingConfig = clampReport().first
}

/** 抓帧数据源。 */
enum class SnapshotSource(val label: String) {
    /** 预览/相机 Surface 抓帧（不额外占用 HAL 流）。 */
    PREVIEW("预览抓帧"),

    /** 录制编码输入旁路（画质更贴近成片，需要第三路流）。 */
    RECORDING_SURFACE("录制旁路"),
}

/** 抓帧决策。 */
data class SnapshotDecision(
    val shouldGrab: Boolean,
    /** 因落盘排队满而丢弃（UI 可提示「快照过快，已跳过 n 张」）。 */
    val droppedByBackpressure: Boolean,
    /** 下一次允许抓帧的会话时刻（ms）。 */
    val nextAllowedElapsedMs: Long,
    val reason: String,
)

/** 边录边拍调度策略（纯逻辑）。 */
object SnapshotIntervalPolicy {

    /** 尚未抓过任何一帧的哨兵值。 */
    const val NO_GRAB_YET = -1L

    fun normalizeIntervalMs(intervalMs: Long): Long = intervalMs.coerceIn(
        VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN,
        VideoModesRanges.SNAPSHOT_INTERVAL_MS_MAX,
    )

    /**
     * 判定当前时刻是否应抓帧。
     *
     * @param elapsedMs 录制会话已流逝时间（ms）；<0 视为 0（防时钟异常）。
     * @param lastGrabElapsedMs 上一帧抓取时刻；[NO_GRAB_YET] 表示尚未抓过（立即抓）。
     * @param pendingCount 当前未落盘快照数（落盘背压）。
     */
    fun resolve(
        config: SnapshotDuringRecordingConfig,
        elapsedMs: Long,
        lastGrabElapsedMs: Long,
        pendingCount: Int = 0,
    ): SnapshotDecision {
        val (clamped, _) = config.clampReport()
        val interval = clamped.intervalMs
        val now = elapsedMs.coerceAtLeast(0L)

        if (!clamped.enabled) {
            return SnapshotDecision(
                shouldGrab = false,
                droppedByBackpressure = false,
                nextAllowedElapsedMs = 0L,
                reason = "边录边拍未开启",
            )
        }

        val last = lastGrabElapsedMs
        val isFirstGrab = last < 0L
        val nextAllowed = if (isFirstGrab) 0L else last + interval

        // 背压优先：排队满时丢帧，不阻塞录制。
        if (pendingCount >= clamped.maxPending) {
            return SnapshotDecision(
                shouldGrab = false,
                droppedByBackpressure = true,
                nextAllowedElapsedMs = nextAllowed,
                reason = "快照落盘排队已满（$pendingCount/${clamped.maxPending}），本帧已跳过",
            )
        }

        if (!isFirstGrab && now < last) {
            return SnapshotDecision(
                shouldGrab = false,
                droppedByBackpressure = false,
                nextAllowedElapsedMs = nextAllowed,
                reason = "会话时钟回拨，等待正常时间基准",
            )
        }

        val due = isFirstGrab || now - last >= interval
        return SnapshotDecision(
            shouldGrab = due,
            droppedByBackpressure = false,
            nextAllowedElapsedMs = if (due) now + interval else nextAllowed,
            reason = if (due) {
                "到点抓帧（间隔 ${interval}ms）"
            } else {
                "距下次抓帧还有 ${(nextAllowed - now).coerceAtLeast(0L)}ms"
            },
        )
    }

    /** 便捷判定（只关心布尔）。 */
    fun shouldGrab(
        config: SnapshotDuringRecordingConfig,
        elapsedMs: Long,
        lastGrabElapsedMs: Long,
        pendingCount: Int = 0,
    ): Boolean = resolve(config, elapsedMs, lastGrabElapsedMs, pendingCount).shouldGrab

    /** 一段录制内理论的抓帧张数（仅用于 UI 预估，不做上限）。 */
    fun estimatedSnapshotCount(config: SnapshotDuringRecordingConfig, recordingDurationMs: Long): Int {
        if (!config.enabled || recordingDurationMs <= 0L) return 0
        val interval = normalizeIntervalMs(config.intervalMs)
        return (recordingDurationMs / interval).toInt().coerceAtLeast(1)
    }
}
