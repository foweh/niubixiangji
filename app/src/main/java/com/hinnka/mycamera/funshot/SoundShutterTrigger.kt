package com.hinnka.mycamera.funshot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import com.hinnka.mycamera.utils.PLog

/**
 * 声控触发（拍照 / 录制）前台轻量驱动。
 *
 * 为什么可以放前台直跑（PATTERN_SPEC §6 + 用户性能硬约束）：
 *  - 每帧只做一次 RMS 能量计算（无 FFT、无重采样），单帧成本微秒级；
 *  - `AudioRecord.read` 本身按块阻塞（默认 1024 样本 ≈ 64ms @16kHz），天然形成节流，
 *    平均 CPU 占用远低于 1%；
 *  - 真正耗资源的动作（拍照、视频录制、编码）都通过 [onAction] 交给宿主，不在本线程执行。
 *
 * 权限：需要 `RECORD_AUDIO`（见补丁 MANIFEST 段）；未授权时 [start] 返回 false 并打日志，不崩溃。
 */
class SoundShutterTrigger(
    private val context: Context,
    private val configProvider: () -> SoundTriggerConfig,
    private val onAction: (FunshotTriggerAction) -> Unit,
) {

    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var activeConfig: SoundTriggerConfig = SoundTriggerConfig()

    @Volatile
    private var gateRef: SoundTriggerGate = SoundTriggerGate(activeConfig)

    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 当前电平/阈值快照（供 UI 电平条）；未运行时也安全。 */
    fun snapshot(): SoundTriggerSnapshot = gateRef.snapshot(SystemClock.elapsedRealtime())

    /**
     * 启动监听。
     * @return true 表示已开始；false 表示未授权 / 未启用 / 音频设备不可用（均不抛异常）。
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (running) return true
            if (!hasPermission()) {
                PLog.w(TAG, "RECORD_AUDIO not granted, sound trigger not started")
                return false
            }
            val requested = configProvider().coerceWithReport().value
            if (!requested.enabled) {
                PLog.i(TAG, "sound trigger disabled by config")
                return false
            }
            activeConfig = requested
            gateRef = SoundTriggerGate(requested)

            val minBufferBytes = AudioRecord.getMinBufferSize(
                requested.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferBytes <= 0) {
                PLog.w(TAG, "invalid AudioRecord buffer size $minBufferBytes")
                return false
            }
            val bufferBytes = maxOf(minBufferBytes, requested.frameSize * 2 * 2)
            val source = resolveAudioSource(requested)
            val record = runCatching {
                AudioRecord(
                    source,
                    requested.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrElse { error ->
                PLog.w(TAG, "AudioRecord create failed: ${error.message}", error)
                return false
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                PLog.w(TAG, "AudioRecord not initialized (source=$source)")
                return false
            }
            recorder = record
            running = true
            worker = Thread({ loop(record) }, "funshot-sound-trigger").apply {
                isDaemon = true
                start()
            }
            PLog.i(TAG, "sound trigger started: rate=${requested.sampleRate} frame=${requested.frameSize}")
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            recorder?.let { record ->
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            recorder = null
            worker = null
            gateRef.reset()
        }
    }

    /** 配置变化时调用：未在录制中则立即换用新阈值/时长。 */
    fun refreshConfig() {
        val requested = configProvider().coerceWithReport().value
        activeConfig = requested
        if (!gateRef.isRecording) {
            gateRef = SoundTriggerGate(requested)
        }
    }

    private fun resolveAudioSource(config: SoundTriggerConfig): Int =
        if (config.useUnprocessedSource) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC

    private fun loop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frame = ShortArray(activeConfig.frameSize.coerceAtLeast(128))
        val started = runCatching { record.startRecording() }
            .onFailure { PLog.w(TAG, "startRecording failed: ${it.message}", it) }
            .isSuccess
        if (!started) {
            running = false
            return
        }
        var errorStreak = 0
        while (running) {
            val read = runCatching { record.read(frame, 0, frame.size) }.getOrDefault(-1)
            if (read <= 0) {
                errorStreak++
                if (errorStreak > MAX_ERROR_STREAK) {
                    PLog.w(TAG, "AudioRecord read failed $errorStreak times, stopping")
                    break
                }
                runCatching { Thread.sleep(ERROR_BACKOFF_MS) }
                continue
            }
            errorStreak = 0
            val now = SystemClock.elapsedRealtime()
            val samples = if (read == frame.size) frame else frame.copyOf(read)
            val decision = runCatching { gateRef.onSamples(samples, now) }.getOrNull()
            dispatch(decision)
            // 无新音频时也要能自动停止录制。
            if (decision?.fired != true) {
                dispatch(runCatching { gateRef.tick(now) }.getOrNull())
            }
        }
        runCatching { record.stop() }
    }

    private fun dispatch(decision: SoundTriggerDecision?) {
        if (decision == null || !decision.fired) return
        val action = decision.action ?: return
        PLog.i(TAG, "sound trigger fired: action=$action level=${decision.level} reason=${decision.reason}")
        runCatching { onAction(action) }
            .onFailure { PLog.w(TAG, "sound trigger action failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "SoundShutterTrigger"
        private const val MAX_ERROR_STREAK = 20
        private const val ERROR_BACKOFF_MS = 20L
    }
}

/**
 * 声控接线点：宿主设置 [config] 与 [onAction]，再 [attach] 后 [start]。
 * 与全景/多帧模块同款「未接线则功能不可用但不崩溃」策略。
 */
object SoundTriggerRuntime {

    @Volatile
    var config: SoundTriggerConfig = SoundTriggerConfig()

    /** 触发动作者（拍照 / 开始录制 / 停止录制）。 */
    @Volatile
    var onAction: ((FunshotTriggerAction) -> Unit)? = null

    @Volatile
    private var trigger: SoundShutterTrigger? = null

    fun attach(context: Context) {
        if (trigger == null) {
            trigger = SoundShutterTrigger(context.applicationContext, { config }) { action ->
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

    fun snapshot(): SoundTriggerSnapshot? = trigger?.snapshot()

    fun refreshConfig() {
        trigger?.refreshConfig()
    }
}
