package com.hinnka.mycamera.funshot

/**
 * 运动触发配置（阈值 / 窗口 / 灵敏度 / 轴掩码等全部开放，默认值 + 范围 + clamp）。
 *
 * 字段范围：
 * - [threshold] 0.05 ~ 20（m/s²，去重力后的线性加速度模长阈值）
 * - [hysteresisFactor] 0.1 ~ 0.9（释放线 = `threshold × factor`）
 * - [windowMs] 20 ~ 2000；[cooldownMs] 200 ~ 10000
 * - [sensitivity] 0 ~ 1（映射为 ×1 ~ ×4 幅度增益）
 * - [gravityAlpha] 0.02 ~ 0.5（重力低通系数，越小越稳、越慢）
 * - [sampleDelayUs] 0（最快）或 1000 ~ 200000；[axisMask] 1 ~ 7（bit0=X, bit1=Y, bit2=Z）
 * - [minRecordingMs] 500 ~ 60000；[maxRecordingMs] 1000 ~ 600000（仅 [FunshotTriggerMode.VIDEO] 生效）
 */
data class MotionTriggerConfig(
    val enabled: Boolean = true,
    val threshold: Float = 1.3f,
    val hysteresisFactor: Float = 0.6f,
    val windowMs: Long = 80,
    val cooldownMs: Long = 1_200,
    val sensitivity: Float = 0.5f,
    val gravityAlpha: Float = 0.08f,
    /** 传感器采样周期（微秒）；0 = 最快（SENSOR_DELAY_FASTEST 等价）。 */
    val sampleDelayUs: Int = 20_000,
    /** 参与判定的轴位掩码：bit0=X, bit1=Y, bit2=Z。 */
    val axisMask: Int = 7,
    val mode: FunshotTriggerMode = FunshotTriggerMode.PHOTO,
    val minRecordingMs: Long = 2_000,
    val maxRecordingMs: Long = 120_000,
    val autoStopEnabled: Boolean = true,
) {

    fun sensitivityGain(): Float = 1f + sensitivity.coerceIn(0f, 1f) * 3f

    fun coerceWithReport(): FunshotClampReport<MotionTriggerConfig> {
        val fixed = ArrayList<String>()
        val c = copy(
            threshold = FunshotClamp.float(threshold, 0.05f, 20f, 1.3f, "threshold", fixed),
            hysteresisFactor = FunshotClamp.float(hysteresisFactor, 0.1f, 0.9f, 0.6f, "hysteresisFactor", fixed),
            windowMs = FunshotClamp.long(windowMs, 20L, 2_000L, "windowMs", fixed),
            cooldownMs = FunshotClamp.long(cooldownMs, 200L, 10_000L, "cooldownMs", fixed),
            sensitivity = FunshotClamp.float(sensitivity, 0f, 1f, 0.5f, "sensitivity", fixed),
            gravityAlpha = FunshotClamp.float(gravityAlpha, 0.02f, 0.5f, 0.08f, "gravityAlpha", fixed),
            sampleDelayUs = normalizeSampleDelay(sampleDelayUs, fixed),
            axisMask = FunshotClamp.int(
                axisMask, FunshotRanges.MOTION_AXIS_MASK_MIN, FunshotRanges.MOTION_AXIS_MASK_MAX,
                "axisMask", fixed,
            ),
            minRecordingMs = FunshotClamp.long(minRecordingMs, 500L, 60_000L, "minRecordingMs", fixed),
            maxRecordingMs = FunshotClamp.long(maxRecordingMs, 1_000L, 600_000L, "maxRecordingMs", fixed),
        )
        return FunshotClampReport(c, fixed)
    }

    private fun normalizeSampleDelay(raw: Int, fixed: MutableList<String>): Int {
        if (raw == 0) return 0
        val clamped = raw.coerceIn(1_000, 200_000)
        if (clamped != raw) fixed += "sampleDelayUs"
        return clamped
    }
}

/** 运动触发配置编解码（`funshot_motion_*` key）。 */
object MotionTriggerConfigCodec {

    fun encode(config: MotionTriggerConfig): Map<String, String> = mapOf(
        KEY_ENABLED to config.enabled.toString(),
        KEY_THRESHOLD to config.threshold.toString(),
        KEY_HYSTERESIS to config.hysteresisFactor.toString(),
        KEY_WINDOW to config.windowMs.toString(),
        KEY_COOLDOWN to config.cooldownMs.toString(),
        KEY_SENSITIVITY to config.sensitivity.toString(),
        KEY_GRAVITY_ALPHA to config.gravityAlpha.toString(),
        KEY_SAMPLE_DELAY to config.sampleDelayUs.toString(),
        KEY_AXIS_MASK to config.axisMask.toString(),
        KEY_MODE to config.mode.name,
        KEY_MIN_RECORDING to config.minRecordingMs.toString(),
        KEY_MAX_RECORDING to config.maxRecordingMs.toString(),
        KEY_AUTO_STOP to config.autoStopEnabled.toString(),
    )

    fun decode(read: (String) -> String?): FunshotClampReport<MotionTriggerConfig> {
        val d = MotionTriggerConfig()
        val raw = MotionTriggerConfig(
            enabled = read(KEY_ENABLED)?.toBooleanStrictOrNull() ?: d.enabled,
            threshold = read(KEY_THRESHOLD)?.toFloatOrNull() ?: d.threshold,
            hysteresisFactor = read(KEY_HYSTERESIS)?.toFloatOrNull() ?: d.hysteresisFactor,
            windowMs = read(KEY_WINDOW)?.toLongOrNull() ?: d.windowMs,
            cooldownMs = read(KEY_COOLDOWN)?.toLongOrNull() ?: d.cooldownMs,
            sensitivity = read(KEY_SENSITIVITY)?.toFloatOrNull() ?: d.sensitivity,
            gravityAlpha = read(KEY_GRAVITY_ALPHA)?.toFloatOrNull() ?: d.gravityAlpha,
            sampleDelayUs = read(KEY_SAMPLE_DELAY)?.toIntOrNull() ?: d.sampleDelayUs,
            axisMask = read(KEY_AXIS_MASK)?.toIntOrNull() ?: d.axisMask,
            mode = FunshotTriggerMode.entries.firstOrNull { it.name == read(KEY_MODE) } ?: d.mode,
            minRecordingMs = read(KEY_MIN_RECORDING)?.toLongOrNull() ?: d.minRecordingMs,
            maxRecordingMs = read(KEY_MAX_RECORDING)?.toLongOrNull() ?: d.maxRecordingMs,
            autoStopEnabled = read(KEY_AUTO_STOP)?.toBooleanStrictOrNull() ?: d.autoStopEnabled,
        )
        return raw.coerceWithReport()
    }

    const val KEY_ENABLED = "funshot_motion_enabled"
    const val KEY_THRESHOLD = "funshot_motion_threshold"
    const val KEY_HYSTERESIS = "funshot_motion_hysteresis_factor"
    const val KEY_WINDOW = "funshot_motion_window_ms"
    const val KEY_COOLDOWN = "funshot_motion_cooldown_ms"
    const val KEY_SENSITIVITY = "funshot_motion_sensitivity"
    const val KEY_GRAVITY_ALPHA = "funshot_motion_gravity_alpha"
    const val KEY_SAMPLE_DELAY = "funshot_motion_sample_delay_us"
    const val KEY_AXIS_MASK = "funshot_motion_axis_mask"
    const val KEY_MODE = "funshot_motion_mode"
    const val KEY_MIN_RECORDING = "funshot_motion_min_recording_ms"
    const val KEY_MAX_RECORDING = "funshot_motion_max_recording_ms"
    const val KEY_AUTO_STOP = "funshot_motion_auto_stop"
}
