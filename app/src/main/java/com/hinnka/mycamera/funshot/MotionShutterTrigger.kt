package com.hinnka.mycamera.funshot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hinnka.mycamera.utils.PLog

/**
 * 运动触发前台轻量驱动（加速度计）。
 *
 * 与声控同款理由：单次回调只做「低通 + 开方」（O(1) 常数级），无分配、无 IO，
 * 采样率由 [MotionTriggerConfig.sampleDelayUs] 控制（默认 20ms ≈ 50Hz，可配到 1ms 或最快），
 * 因此可放一个独立的低优先级 HandlerThread 直跑，不占主线程、不占前台算力。
 *
 * 传感器回调线程：独立 [HandlerThread]（`funshot-motion-sensor`），避免主线程抖动。
 */
class MotionShutterTrigger(
    context: Context,
    private val configProvider: () -> MotionTriggerConfig,
    private val onAction: (FunshotTriggerAction) -> Unit,
) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    private var running = false

    @Volatile
    private var effectiveConfig: MotionTriggerConfig = MotionTriggerConfig()

    @Volatile
    private var gateRef: MotionTriggerGate = MotionTriggerGate(effectiveConfig)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    val isAvailable: Boolean get() = accelerometer != null

    val isRunning: Boolean get() = running

    fun snapshot(): MotionTriggerSnapshot = gateRef.snapshot(SystemClock.elapsedRealtime())

    /**
     * 开始监听。
     * @return false 表示无加速度计 / 未启用 / 注册失败（均不抛异常、不崩溃）
     */
    fun start(): Boolean {
        if (running) return true
        val sensor = accelerometer ?: run {
            PLog.w(TAG, "no accelerometer on this device, motion trigger unavailable")
            return false
        }
        val requested = configProvider().coerced()
        if (!requested.enabled) {
            PLog.i(TAG, "motion trigger disabled by config")
            return false
        }
        effectiveConfig = requested
        gateRef = MotionTriggerGate(requested)
        val manager = sensorManager ?: return false
        val thread = handlerThread ?: HandlerThread("funshot-motion-sensor").apply { start() }.also {
            handlerThread = it
        }
        val target = Handler(thread.looper)
        handler = target
        val samplingUs = if (requested.sampleDelayUs <= 0) 0 else requested.sampleDelayUs
        val registered = runCatching {
            manager.registerListener(this, sensor, samplingUs, target)
        }.getOrElse { error ->
            PLog.w(TAG, "registerListener failed: ${error.message}", error)
            false
        }
        if (!registered) {
            stop()
            return false
        }
        running = true
        PLog.i(TAG, "motion trigger started: threshold=${requested.threshold} window=${requested.windowMs}ms")
        return true
    }

    fun stop() {
        if (running) {
            runCatching { sensorManager?.unregisterListener(this) }
        }
        running = false
        gateRef.reset()
        handlerThread?.let { thread ->
            runCatching { thread.quitSafely() }
        }
        handlerThread = null
        handler = null
    }

    /** 配置变化时调用：未在录制中则换用新阈值/窗口。 */
    fun refreshConfig() {
        val requested = configProvider().coerced()
        effectiveConfig = requested
        if (!gateRef.isRecording) {
            gateRef = MotionTriggerGate(requested)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (!running || e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (e.values.size < 3) return
        val now = SystemClock.elapsedRealtime()
        val decision = runCatching {
            gateRef.onSample(e.values[0], e.values[1], e.values[2], now)
        }.getOrNull()
        dispatch(decision)
        if (decision?.fired != true) {
            dispatch(runCatching { gateRef.tick(now) }.getOrNull())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化不影响阈值判定
    }

    private fun dispatch(decision: MotionTriggerDecision?) {
        if (decision == null || !decision.fired) return
        val action = decision.action ?: return
        PLog.i(TAG, "motion trigger fired: action=$action magnitude=${decision.level} reason=${decision.reason}")
        runCatching { onAction(action) }
            .onFailure { PLog.w(TAG, "motion trigger action failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "MotionShutterTrigger"
    }
}

/** 运动触发接线点（与声控/全景同款「未接线则不可用但不崩溃」策略）。 */
object MotionTriggerRuntime {

    @Volatile
    var config: MotionTriggerConfig = MotionTriggerConfig()

    @Volatile
    var onAction: ((FunshotTriggerAction) -> Unit)? = null

    @Volatile
    private var trigger: MotionShutterTrigger? = null

    fun attach(context: Context) {
        if (trigger == null) {
            trigger = MotionShutterTrigger(context.applicationContext, { config }) { action ->
                onAction?.invoke(action)
            }
        }
    }

    fun start(): Boolean = trigger?.start() ?: false

    fun stop() {
        trigger?.stop()
    }

    val isRunning: Boolean get() = trigger?.isRunning == true

    val isReady: Boolean get() = trigger != null && onAction != null

    fun snapshot(): MotionTriggerSnapshot? = trigger?.snapshot()

    fun refreshConfig() {
        trigger?.refreshConfig()
    }
}
