package com.hinnka.mycamera.funshot

/**
 * 声控触发配置（阈值/时长/灵敏度等全部开放，默认值 + 范围 + clamp）。
 *
 * 目标模式复用 [FunshotTriggerMode]（拍照 / 录制开关），与运动触发共用同一枚举与状态机。
 *
 * 字段范围：
 * - [sampleRate] 8000 ~ 48000；[frameSize] 128 ~ 8192
 * - [thresholdDbfs] -80 ~ 0；[hysteresisDb] 0 ~ 24
 * - [sensitivity] 0 ~ 1（映射为 +0 ~ +12 dB 电平增益）
 * - [minDurationMs] 20 ~ 2000；[cooldownMs] 200 ~ 10000；[releaseHoldMs] 0 ~ 1000
 * - [minRecordingMs] 500 ~ 60000；[maxRecordingMs] 1000 ~ 600000（仅 [FunshotTriggerMode.VIDEO] 生效）
 */
data class SoundTriggerConfig(
    val enabled: Boolean = true,
    val sampleRate: Int = 16_000,
    val frameSize: Int = 1024,
    /** 触发阈值（dBFS，负值；越接近 0 越不敏感）。 */
    val thresholdDbfs: Float = -32f,
    /** 灵敏度 0 ~ 1：额外电平增益 0 ~ +12 dB（与 [thresholdDbfs] 叠加，互不覆盖）。 */
    val sensitivity: Float = 0.5f,
    val minDurationMs: Long = 150,
    val cooldownMs: Long = 1_500,
    /** 迟滞：低于 `threshold - hysteresis` 才算「安静」，避免阈值附近抖动连触。 */
    val hysteresisDb: Float = 6f,
    /** 从「超过迟滞下限」到重新可累积所需的最短时间。 */
    val releaseHoldMs: Long = 120,
    val mode: FunshotTriggerMode = FunshotTriggerMode.PHOTO,
    val minRecordingMs: Long = 2_000,
    val maxRecordingMs: Long = 120_000,
    /** 录制模式自动停止（达到 [maxRecordingMs] 后自动触发 VIDEO_STOP）。 */
    val autoStopEnabled: Boolean = true,
    /** 优先使用 `UNPROCESSED` 音源（不受 AGC/降噪影响，阈值判定更准）；不支持时回落麦克风。 */
    val useUnprocessedSource: Boolean = true,
) {

    /** 灵敏度折算的电平增益（dB）。 */
    fun sensitivityGainDb(): Float = sensitivity.coerceIn(0f, 1f) * 12f

    /** 实际生效阈值（用户设定值，不做二次漂移）。 */
    fun effectiveThresholdDbfs(): Float = thresholdDbfs

    fun coerceWithReport(): FunshotClampReport<SoundTriggerConfig> {
        val fixed = ArrayList<String>()
        val c = copy(
            sampleRate = FunshotClamp.int(
                sampleRate, FunshotRanges.SOUND_SAMPLE_RATE_MIN, FunshotRanges.SOUND_SAMPLE_RATE_MAX,
                "sampleRate", fixed,
            ),
            frameSize = FunshotClamp.int(frameSize, 128, 8192, "frameSize", fixed),
            thresholdDbfs = FunshotClamp.float(thresholdDbfs, -80f, 0f, -32f, "thresholdDbfs", fixed),
            sensitivity = FunshotClamp.float(sensitivity, 0f, 1f, 0.5f, "sensitivity", fixed),
            minDurationMs = FunshotClamp.long(minDurationMs, 20L, 2_000L, "minDurationMs", fixed),
            cooldownMs = FunshotClamp.long(cooldownMs, 200L, 10_000L, "cooldownMs", fixed),
            hysteresisDb = FunshotClamp.float(hysteresisDb, 0f, 24f, 6f, "hysteresisDb", fixed),
            releaseHoldMs = FunshotClamp.long(releaseHoldMs, 0L, 1_000L, "releaseHoldMs", fixed),
            minRecordingMs = FunshotClamp.long(minRecordingMs, 500L, 60_000L, "minRecordingMs", fixed),
            maxRecordingMs = FunshotClamp.long(maxRecordingMs, 1_000L, 600_000L, "maxRecordingMs", fixed),
        )
        return FunshotClampReport(c, fixed)
    }
}

/** 声控配置编解码（`funshot_sound_*` key，缺失/损坏回落默认再 clamp）。 */
object SoundTriggerConfigCodec {

    fun encode(config: SoundTriggerConfig): Map<String, String> = mapOf(
        KEY_ENABLED to config.enabled.toString(),
        KEY_SAMPLE_RATE to config.sampleRate.toString(),
        KEY_FRAME_SIZE to config.frameSize.toString(),
        KEY_THRESHOLD to config.thresholdDbfs.toString(),
        KEY_SENSITIVITY to config.sensitivity.toString(),
        KEY_MIN_DURATION to config.minDurationMs.toString(),
        KEY_COOLDOWN to config.cooldownMs.toString(),
        KEY_HYSTERESIS to config.hysteresisDb.toString(),
        KEY_RELEASE_HOLD to config.releaseHoldMs.toString(),
        KEY_MODE to config.mode.name,
        KEY_MIN_RECORDING to config.minRecordingMs.toString(),
        KEY_MAX_RECORDING to config.maxRecordingMs.toString(),
        KEY_AUTO_STOP to config.autoStopEnabled.toString(),
        KEY_UNPROCESSED to config.useUnprocessedSource.toString(),
    )

    fun decode(read: (String) -> String?): FunshotClampReport<SoundTriggerConfig> {
        val d = SoundTriggerConfig()
        val raw = SoundTriggerConfig(
            enabled = read(KEY_ENABLED)?.toBooleanStrictOrNull() ?: d.enabled,
            sampleRate = read(KEY_SAMPLE_RATE)?.toIntOrNull() ?: d.sampleRate,
            frameSize = read(KEY_FRAME_SIZE)?.toIntOrNull() ?: d.frameSize,
            thresholdDbfs = read(KEY_THRESHOLD)?.toFloatOrNull() ?: d.thresholdDbfs,
            sensitivity = read(KEY_SENSITIVITY)?.toFloatOrNull() ?: d.sensitivity,
            minDurationMs = read(KEY_MIN_DURATION)?.toLongOrNull() ?: d.minDurationMs,
            cooldownMs = read(KEY_COOLDOWN)?.toLongOrNull() ?: d.cooldownMs,
            hysteresisDb = read(KEY_HYSTERESIS)?.toFloatOrNull() ?: d.hysteresisDb,
            releaseHoldMs = read(KEY_RELEASE_HOLD)?.toLongOrNull() ?: d.releaseHoldMs,
            mode = FunshotTriggerMode.entries.firstOrNull { it.name == read(KEY_MODE) } ?: d.mode,
            minRecordingMs = read(KEY_MIN_RECORDING)?.toLongOrNull() ?: d.minRecordingMs,
            maxRecordingMs = read(KEY_MAX_RECORDING)?.toLongOrNull() ?: d.maxRecordingMs,
            autoStopEnabled = read(KEY_AUTO_STOP)?.toBooleanStrictOrNull() ?: d.autoStopEnabled,
            useUnprocessedSource = read(KEY_UNPROCESSED)?.toBooleanStrictOrNull() ?: d.useUnprocessedSource,
        )
        return raw.coerceWithReport()
    }

    const val KEY_ENABLED = "funshot_sound_enabled"
    const val KEY_SAMPLE_RATE = "funshot_sound_sample_rate"
    const val KEY_FRAME_SIZE = "funshot_sound_frame_size"
    const val KEY_THRESHOLD = "funshot_sound_threshold_dbfs"
    const val KEY_SENSITIVITY = "funshot_sound_sensitivity"
    const val KEY_MIN_DURATION = "funshot_sound_min_duration_ms"
    const val KEY_COOLDOWN = "funshot_sound_cooldown_ms"
    const val KEY_HYSTERESIS = "funshot_sound_hysteresis_db"
    const val KEY_RELEASE_HOLD = "funshot_sound_release_hold_ms"
    const val KEY_MODE = "funshot_sound_mode"
    const val KEY_MIN_RECORDING = "funshot_sound_min_recording_ms"
    const val KEY_MAX_RECORDING = "funshot_sound_max_recording_ms"
    const val KEY_AUTO_STOP = "funshot_sound_auto_stop"
    const val KEY_UNPROCESSED = "funshot_sound_unprocessed_source"
}
