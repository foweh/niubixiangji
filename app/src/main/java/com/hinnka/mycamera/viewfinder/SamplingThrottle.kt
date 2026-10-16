package com.hinnka.mycamera.viewfinder

/**
 * 通用采样节流器：把「每帧都想采样」压成「至少间隔 [intervalMs] 才采一次」。
 *
 * 纯 JVM 实现（时间由调用方传入，便于单测控制），可在 GL 线程/检测线程直接使用。
 * 直方图与贴纸检测共用本类，仅取值范围不同。
 */
class SamplingThrottle(
    intervalMs: Long,
    private val minIntervalMs: Long = 1L,
    private val maxIntervalMs: Long = Long.MAX_VALUE,
) {
    private val lock = Any()
    private var interval = normalize(intervalMs)
    private var lastSampleAtMs = Long.MIN_VALUE

    var intervalMs: Long
        get() = synchronized(lock) { interval }
        set(value) {
            synchronized(lock) { interval = normalize(value) }
        }

    /** 当前生效的取值域（自检用）。 */
    val bounds: LongRange get() = minIntervalMs..maxIntervalMs

    val lastSampleTimestampMs: Long
        get() = synchronized(lock) { lastSampleAtMs }

    /** 距离下一次可采样还需等待的毫秒数（已可采样返回 0）。 */
    fun remainingMs(nowMs: Long): Long = synchronized(lock) {
        if (lastSampleAtMs == Long.MIN_VALUE) return 0L
        val elapsed = nowMs - lastSampleAtMs
        (interval - elapsed).coerceAtLeast(0L)
    }

    /**
     * 尝试获取一次采样许可。**首次调用立即通过**（避免开图空白等待一个完整间隔）。
     *
     * 时间回拨（系统改时、竞态）按「可采样」处理并重置基准，避免永久锁死。
     *
     * @return true 表示本次可以采样，并已记录时间戳
     */
    fun tryAcquire(nowMs: Long): Boolean = synchronized(lock) {
        if (lastSampleAtMs == Long.MIN_VALUE) {
            lastSampleAtMs = nowMs
            return true
        }
        val elapsed = nowMs - lastSampleAtMs
        if (elapsed < 0L || elapsed >= interval) {
            lastSampleAtMs = nowMs
            return true
        }
        false
    }

    fun reset() {
        synchronized(lock) { lastSampleAtMs = Long.MIN_VALUE }
    }

    private fun normalize(value: Long): Long {
        val lower = minIntervalMs.coerceAtLeast(1L)
        val upper = maxIntervalMs.coerceAtLeast(lower)
        return if (value < lower) lower else if (value > upper) upper else value
    }

    companion object {
        /** 直方图采样节流（间隔 50..5000ms）。 */
        fun forHistogram(intervalMs: Long = ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_DEFAULT_MS) =
            SamplingThrottle(
                intervalMs = intervalMs,
                minIntervalMs = ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS,
                maxIntervalMs = ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS,
            )

        /** 贴纸人脸检测节流（间隔 33..2000ms）。 */
        fun forFaceSticker(intervalMs: Long = ViewfinderParamRanges.STICKER_INTERVAL_DEFAULT_MS) =
            SamplingThrottle(
                intervalMs = intervalMs,
                minIntervalMs = ViewfinderParamRanges.STICKER_INTERVAL_MIN_MS,
                maxIntervalMs = ViewfinderParamRanges.STICKER_INTERVAL_MAX_MS,
            )
    }
}
