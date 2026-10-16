package com.hinnka.mycamera.funshot

import kotlin.math.sqrt

/** 运动判定结果。 */
typealias MotionTriggerDecision = TriggerDecision

/** 运动状态快照。 */
typealias MotionTriggerSnapshot = TriggerSnapshot

/** 运动数学（纯 JVM，可单测）：重力低通估计 + 去重力线性加速度 + 轴掩码模长。 */
object FunshotMotionMath {

    /**
     * 重力低通估计（指数平滑）。
     * @param alpha 0.02 ~ 0.5（越小越稳、越慢跟随）
     */
    fun lowPassGravity(previous: FloatArray, x: Float, y: Float, z: Float, alpha: Float): FloatArray {
        val a = alpha.coerceIn(0.02f, 0.5f)
        val gx = previous[0] + a * (x - previous[0])
        val gy = previous[1] + a * (y - previous[1])
        val gz = previous[2] + a * (z - previous[2])
        return floatArrayOf(gx, gy, gz)
    }

    /**
     * 去重力后的线性加速度模长，按 [axisMask] 选轴。
     * @param axisMask bit0=X, bit1=Y, bit2=Z；掩码为 0 时退化为全轴（防呆）
     */
    fun linearMagnitude(
        x: Float,
        y: Float,
        z: Float,
        gravity: FloatArray,
        axisMask: Int,
    ): Float {
        if (gravity.size < 3) return 0f
        val mask = if (axisMask and 0b111 == 0) 0b111 else axisMask and 0b111
        var sum = 0f
        if (mask and 0b001 != 0) sum += (x - gravity[0]) * (x - gravity[0])
        if (mask and 0b010 != 0) sum += (y - gravity[1]) * (y - gravity[1])
        if (mask and 0b100 != 0) sum += (z - gravity[2]) * (z - gravity[2])
        val magnitude = sqrt(sum)
        return if (magnitude.isFinite()) magnitude else 0f
    }
}

/**
 * 运动触发闸门：加速度计样本 → 去重力幅度 → 灵敏度增益 → 共用阈值状态机
 * [TriggerThresholdGate]（与声控同一套「持续时间 + 冷却 + 录制开关」语义）。
 */
class MotionTriggerGate(config: MotionTriggerConfig) {

    private val effective: MotionTriggerConfig = config.coerceWithReport().value
    private val delegate: TriggerThresholdGate = buildDelegate(effective)

    private var gravity = floatArrayOf(0f, 0f, 0f)
    private var gravityInitialized = false
    private var lastMagnitude = 0f

    val config: MotionTriggerConfig get() = effective

    val isRecording: Boolean get() = delegate.isRecording

    fun reset() {
        delegate.reset()
        gravity = floatArrayOf(0f, 0f, 0f)
        gravityInitialized = false
        lastMagnitude = 0f
    }

    /** 送入一帧加速度计样本（m/s²）。 */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long): MotionTriggerDecision {
        val magnitude = magnitudeOf(x, y, z)
        return onMagnitude(magnitude, nowMs)
    }

    /** 直接送入已算好的线性幅度（便于单测与复用）。 */
    fun onMagnitude(linearMagnitude: Float, nowMs: Long): MotionTriggerDecision {
        lastMagnitude = linearMagnitude
        if (!effective.enabled) {
            return TriggerDecision(false, null, linearMagnitude.toDouble(), "disabled")
        }
        val gained = (linearMagnitude * effective.sensitivityGain()).toDouble()
        return delegate.onLevel(gained, nowMs)
    }

    /** 计算当前线性幅度（含重力更新与轴掩码），不触发判定。 */
    fun magnitudeOf(x: Float, y: Float, z: Float): Float {
        if (!gravityInitialized) {
            // 首帧直接作为重力初值，避免开机瞬间误触发。
            gravity = floatArrayOf(x, y, z)
            gravityInitialized = true
            return 0f
        }
        val magnitude = FunshotMotionMath.linearMagnitude(x, y, z, gravity, effective.axisMask)
        gravity = FunshotMotionMath.lowPassGravity(gravity, x, y, z, effective.gravityAlpha)
        return if (magnitude.isFinite()) magnitude else 0f
    }

    /** 无新样本时的定时驱动（自动停止录制）。 */
    fun tick(nowMs: Long): MotionTriggerDecision = delegate.tick(nowMs)

    fun snapshot(nowMs: Long): MotionTriggerSnapshot =
        delegate.snapshot(nowMs, (lastMagnitude * effective.sensitivityGain()).toDouble())

    /** 配置 → 共用状态机的声明式映射（运动用比例迟滞：`threshold × hysteresisFactor`）。 */
    private fun buildDelegate(config: MotionTriggerConfig) = TriggerThresholdGate(
        threshold = config.threshold.toDouble(),
        releaseThreshold = (config.threshold * config.hysteresisFactor).toDouble(),
        minDurationMs = config.windowMs,
        mode = config.mode,
        cooldownMs = config.cooldownMs,
        minRecordingMs = config.minRecordingMs,
        maxRecordingMs = config.maxRecordingMs,
        autoStopEnabled = config.autoStopEnabled,
    )
}
