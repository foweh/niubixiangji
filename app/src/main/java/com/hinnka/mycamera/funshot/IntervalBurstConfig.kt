package com.hinnka.mycamera.funshot

/**
 * 定时连拍（「定时—间隔连拍」变体）配置：开始延时 / 张数 / 间隔 全部开放，默认值 + 范围 + clamp。
 *
 * 与工程既有定时器（`CameraState.timerSeconds` 的 0/3/5/10 循环）的关系：
 * 本变体复用既有定时通道的语义（[reuseExistingTimer] / [toLegacyTimerSeconds]），
 * 但把「延时」与「间隔连拍」拆成两个独立参数，并新增张数、间隔抖动、快门声开关。
 * 既有代码零改动：执行器 [IntervalBurstRunner] 通过宿主回调调用既有的连拍入口。
 *
 * 字段范围：
 * - [startDelaySec] 0 ~ 60（0 = 立即开始）
 * - [shotCount] 1 ~ 200；[intervalMs] 100 ~ 60000；[intervalJitterMs] 0 ~ 1000
 * - [randomSeed] 任意（决定间隔抖动序列，保证可复现）
 */
data class IntervalBurstConfig(
    val enabled: Boolean = false,
    val startDelaySec: Int = 3,
    val shotCount: Int = 10,
    val intervalMs: Long = 1_000,
    /** 间隔抖动上限（毫秒），0 = 严格等间隔；用于避免与机械/防抖周期共振。 */
    val intervalJitterMs: Long = 0,
    val shutterSound: Boolean = true,
    /** true = 延时值尽量映射到既有 0/3/5/10 秒定时按钮语义。 */
    val reuseExistingTimer: Boolean = true,
    /** 连拍过程中出错是否自动停止（避免后台无限重试）。 */
    val autoStopOnError: Boolean = true,
    val randomSeed: Long = 20261016L,
) {

    fun coerceWithReport(): FunshotClampReport<IntervalBurstConfig> {
        val fixed = ArrayList<String>()
        val c = copy(
            startDelaySec = FunshotClamp.int(
                startDelaySec, 0, FunshotRanges.INTERVAL_TIMER_MAX_SEC, "startDelaySec", fixed,
            ),
            shotCount = FunshotClamp.int(
                shotCount, 1, FunshotRanges.INTERVAL_SHOT_MAX, "shotCount", fixed,
            ),
            intervalMs = FunshotClamp.long(
                intervalMs, FunshotRanges.INTERVAL_MIN_MS, FunshotRanges.INTERVAL_MAX_MS, "intervalMs", fixed,
            ),
            intervalJitterMs = FunshotClamp.long(
                intervalJitterMs, 0L, FunshotRanges.INTERVAL_JITTER_MAX_MS, "intervalJitterMs", fixed,
            ),
        )
        return FunshotClampReport(c, fixed)
    }

    /** 开始延时（毫秒）。 */
    fun startDelayMs(): Long = startDelaySec.toLong() * 1_000L

    /**
     * 映射到既有定时器档位（0/3/5/10 秒）：取不超过当前延时值的最大档位。
     * 用于复用既有倒计时 UI（`CountdownOverlay`）而不改写它。
     */
    fun toLegacyTimerSeconds(): Int {
        val candidates = intArrayOf(0, 3, 5, 10)
        var chosen = 0
        for (candidate in candidates) {
            if (candidate <= startDelaySec) chosen = candidate
        }
        return chosen
    }
}

/** 定时连拍配置编解码（`funshot_interval_*` key）。 */
object IntervalBurstConfigCodec {

    fun encode(config: IntervalBurstConfig): Map<String, String> = mapOf(
        KEY_ENABLED to config.enabled.toString(),
        KEY_DELAY_SEC to config.startDelaySec.toString(),
        KEY_SHOT_COUNT to config.shotCount.toString(),
        KEY_INTERVAL to config.intervalMs.toString(),
        KEY_JITTER to config.intervalJitterMs.toString(),
        KEY_SHUTTER_SOUND to config.shutterSound.toString(),
        KEY_REUSE_TIMER to config.reuseExistingTimer.toString(),
        KEY_AUTO_STOP to config.autoStopOnError.toString(),
        KEY_SEED to config.randomSeed.toString(),
    )

    fun decode(read: (String) -> String?): FunshotClampReport<IntervalBurstConfig> {
        val d = IntervalBurstConfig()
        val raw = IntervalBurstConfig(
            enabled = read(KEY_ENABLED)?.toBooleanStrictOrNull() ?: d.enabled,
            startDelaySec = read(KEY_DELAY_SEC)?.toIntOrNull() ?: d.startDelaySec,
            shotCount = read(KEY_SHOT_COUNT)?.toIntOrNull() ?: d.shotCount,
            intervalMs = read(KEY_INTERVAL)?.toLongOrNull() ?: d.intervalMs,
            intervalJitterMs = read(KEY_JITTER)?.toLongOrNull() ?: d.intervalJitterMs,
            shutterSound = read(KEY_SHUTTER_SOUND)?.toBooleanStrictOrNull() ?: d.shutterSound,
            reuseExistingTimer = read(KEY_REUSE_TIMER)?.toBooleanStrictOrNull() ?: d.reuseExistingTimer,
            autoStopOnError = read(KEY_AUTO_STOP)?.toBooleanStrictOrNull() ?: d.autoStopOnError,
            randomSeed = read(KEY_SEED)?.toLongOrNull() ?: d.randomSeed,
        )
        return raw.coerceWithReport()
    }

    const val KEY_ENABLED = "funshot_interval_enabled"
    const val KEY_DELAY_SEC = "funshot_interval_start_delay_sec"
    const val KEY_SHOT_COUNT = "funshot_interval_shot_count"
    const val KEY_INTERVAL = "funshot_interval_ms"
    const val KEY_JITTER = "funshot_interval_jitter_ms"
    const val KEY_SHUTTER_SOUND = "funshot_interval_shutter_sound"
    const val KEY_REUSE_TIMER = "funshot_interval_reuse_timer"
    const val KEY_AUTO_STOP = "funshot_interval_auto_stop"
    const val KEY_SEED = "funshot_interval_seed"
}
