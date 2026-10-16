package com.hinnka.mycamera.funshot

import kotlin.math.log10
import kotlin.math.sqrt

/** 声控判定结果（声控与运动共用 [TriggerDecision] 语义）。 */
typealias SoundTriggerDecision = TriggerDecision

/** 声控状态快照。 */
typealias SoundTriggerSnapshot = TriggerSnapshot

/** 电平测量（纯 JVM，无 Android 依赖）。 */
object AudioLevelMeter {

    /** 静音下限（dBFS），用于 log10 定义域保护。 */
    const val SILENCE_DBFS = -120.0

    /** 均方根电平（dBFS，全幅 32768 → 0 dBFS）。 */
    fun dbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        if (length <= 0) return SILENCE_DBFS
        var sum = 0.0
        var count = 0
        val end = (offset + length).coerceAtMost(samples.size)
        for (i in offset until end) {
            val v = samples[i].toDouble()
            sum += v * v
            count++
        }
        if (count == 0) return SILENCE_DBFS
        val rms = sqrt(sum / count)
        if (rms <= 1e-6) return SILENCE_DBFS
        val db = 20.0 * log10(rms / 32768.0)
        return if (db.isFinite()) db.coerceAtLeast(SILENCE_DBFS) else SILENCE_DBFS
    }

    /** 峰值电平（dBFS）。 */
    fun peakDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        if (length <= 0) return SILENCE_DBFS
        var peak = 0
        val end = (offset + length).coerceAtMost(samples.size)
        for (i in offset until end) {
            val v = kotlin.math.abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        if (peak <= 0) return SILENCE_DBFS
        return 20.0 * log10(peak / 32768.0)
    }
}

/**
 * 声控触发闸门：把「PCM/电平 → dBFS → 灵敏度增益 → 阈值判定」包在共用状态机
 * [TriggerThresholdGate] 外面（声明式映射，见 [delegate]）。
 */
class SoundTriggerGate(config: SoundTriggerConfig) {

    private val effective: SoundTriggerConfig = config.coerceWithReport().value
    private val delegate: TriggerThresholdGate = buildDelegate(effective)
    private var lastLevelDbfs = AudioLevelMeter.SILENCE_DBFS

    val config: SoundTriggerConfig get() = effective

    val isRecording: Boolean get() = delegate.isRecording

    fun reset() {
        delegate.reset()
        lastLevelDbfs = AudioLevelMeter.SILENCE_DBFS
    }

    /** 用一段 PCM 判定。 */
    fun onSamples(samples: ShortArray, nowMs: Long): SoundTriggerDecision =
        onLevel(AudioLevelMeter.dbfs(samples), nowMs)

    /**
     * 用一个电平值判定。
     * @param levelDbfs 实测电平（dBFS，≤0）
     * @param nowMs 单调时钟毫秒
     */
    fun onLevel(levelDbfs: Double, nowMs: Long): SoundTriggerDecision {
        lastLevelDbfs = levelDbfs
        if (!effective.enabled) {
            return TriggerDecision(false, null, levelDbfs, "disabled")
        }
        // 灵敏度作为电平增益叠加（默认 +6 dB @ sensitivity=0.5）。
        val gained = levelDbfs + effective.sensitivityGainDb()
        return delegate.onLevel(gained, nowMs)
    }

    /** 无新音频时的定时驱动（自动停止录制）。 */
    fun tick(nowMs: Long): SoundTriggerDecision = delegate.tick(nowMs)

    fun snapshot(nowMs: Long): SoundTriggerSnapshot =
        delegate.snapshot(nowMs, lastLevelDbfs + effective.sensitivityGainDb())

    /** 配置 → 共用状态机的声明式映射（声控用绝对迟滞：`threshold - hysteresisDb`）。 */
    private fun buildDelegate(config: SoundTriggerConfig) = TriggerThresholdGate(
        threshold = config.effectiveThresholdDbfs().toDouble(),
        releaseThreshold = (config.effectiveThresholdDbfs() - config.hysteresisDb).toDouble(),
        minDurationMs = config.minDurationMs,
        mode = config.mode,
        cooldownMs = config.cooldownMs,
        minRecordingMs = config.minRecordingMs,
        maxRecordingMs = config.maxRecordingMs,
        autoStopEnabled = config.autoStopEnabled,
    )
}
