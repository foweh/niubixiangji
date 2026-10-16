package com.hinnka.mycamera.funshot

/** 触发判定结果（[fired] 为 true 时 [action] 非空）。 */
data class TriggerDecision(
    val fired: Boolean,
    val action: FunshotTriggerAction?,
    val level: Double,
    val reason: String,
)

/** 触发状态快照（电平/阈值/累积时长/冷却剩余）。 */
data class TriggerSnapshot(
    val recording: Boolean,
    val level: Double,
    val threshold: Double,
    val aboveDurationMs: Long,
    val cooldownRemainingMs: Long,
)

/**
 * 触发阈值闸门（声控与运动**共用同一套状态机**，避免两份重复逻辑）。
 *
 * 判定链：`电平 >= threshold` → 持续 [minDurationMs] → 冷却内不重复 → 出动作。
 * 释放条件由 [releaseThreshold] 给出（声控：`threshold - hysteresisDb`；
 * 运动：`threshold × hysteresisFactor`），落在释放线与阈值之间时保持当前累积状态（迟滞带）。
 *
 * 动作语义（[mode]）：
 * - [FunshotTriggerMode.PHOTO]：每次触发 `CAPTURE_PHOTO`，冷却 [cooldownMs]；
 * - [FunshotTriggerMode.VIDEO]：第 1 次 `VIDEO_START`，第 2 次 `VIDEO_STOP`，交替；
 *   开录后 [minRecordingMs] 内不会被下一次触发停掉；[maxRecordingMs] 到点且 [autoStopEnabled] 时自动 `VIDEO_STOP`。
 *
 * 时间由调用方注入（[nowMs] 单调时钟毫秒），因此本类是**纯 JVM 可测**状态机。
 */
class TriggerThresholdGate(
    val threshold: Double,
    val releaseThreshold: Double,
    val minDurationMs: Long,
    val mode: FunshotTriggerMode,
    val cooldownMs: Long,
    val minRecordingMs: Long,
    val maxRecordingMs: Long,
    val autoStopEnabled: Boolean,
) {

    var isRecording: Boolean = false
        private set

    private var aboveSinceMs = Long.MIN_VALUE
    private var nextAllowedMs = Long.MIN_VALUE
    private var recordingDeadlineMs = Long.MIN_VALUE

    fun reset() {
        isRecording = false
        aboveSinceMs = Long.MIN_VALUE
        nextAllowedMs = Long.MIN_VALUE
        recordingDeadlineMs = Long.MIN_VALUE
    }

    /** 用一个电平值判定。 */
    fun onLevel(level: Double, nowMs: Long): TriggerDecision {
        autoStopIfDue(nowMs)?.let { return it }

        if (level >= threshold) {
            if (aboveSinceMs == Long.MIN_VALUE) aboveSinceMs = nowMs
        } else if (level < releaseThreshold) {
            aboveSinceMs = Long.MIN_VALUE
        }

        if (aboveSinceMs == Long.MIN_VALUE) {
            return TriggerDecision(false, null, level, "below threshold")
        }
        val held = nowMs - aboveSinceMs
        if (held < minDurationMs) {
            return TriggerDecision(false, null, level, "accumulating ${held}ms")
        }
        if (nowMs < nextAllowedMs) {
            return TriggerDecision(false, null, level, "cooldown ${nextAllowedMs - nowMs}ms")
        }
        return fire(nowMs, level)
    }

    /** 无新采样时的定时驱动（自动停止录制）。 */
    fun tick(nowMs: Long): TriggerDecision =
        autoStopIfDue(nowMs) ?: TriggerDecision(false, null, Double.NaN, "tick")

    fun snapshot(nowMs: Long, level: Double): TriggerSnapshot = TriggerSnapshot(
        recording = isRecording,
        level = level,
        threshold = threshold,
        aboveDurationMs = if (aboveSinceMs == Long.MIN_VALUE) 0L else (nowMs - aboveSinceMs).coerceAtLeast(0L),
        cooldownRemainingMs = (nextAllowedMs - nowMs).coerceAtLeast(0L),
    )

    private fun fire(nowMs: Long, level: Double): TriggerDecision {
        aboveSinceMs = Long.MIN_VALUE
        return when (mode) {
            FunshotTriggerMode.PHOTO -> {
                nextAllowedMs = nowMs + cooldownMs
                TriggerDecision(true, FunshotTriggerAction.CAPTURE_PHOTO, level, "photo trigger")
            }
            FunshotTriggerMode.VIDEO -> {
                if (!isRecording) {
                    isRecording = true
                    recordingDeadlineMs = if (autoStopEnabled) nowMs + maxRecordingMs else Long.MIN_VALUE
                    nextAllowedMs = nowMs + maxOf(cooldownMs, minRecordingMs)
                    TriggerDecision(true, FunshotTriggerAction.VIDEO_START, level, "record start")
                } else {
                    isRecording = false
                    recordingDeadlineMs = Long.MIN_VALUE
                    nextAllowedMs = nowMs + cooldownMs
                    TriggerDecision(true, FunshotTriggerAction.VIDEO_STOP, level, "record stop")
                }
            }
        }
    }

    private fun autoStopIfDue(nowMs: Long): TriggerDecision? {
        if (!isRecording) return null
        if (recordingDeadlineMs == Long.MIN_VALUE || nowMs < recordingDeadlineMs) return null
        isRecording = false
        recordingDeadlineMs = Long.MIN_VALUE
        nextAllowedMs = nowMs + cooldownMs
        aboveSinceMs = Long.MIN_VALUE
        return TriggerDecision(true, FunshotTriggerAction.VIDEO_STOP, Double.NaN, "record auto stop")
    }
}
