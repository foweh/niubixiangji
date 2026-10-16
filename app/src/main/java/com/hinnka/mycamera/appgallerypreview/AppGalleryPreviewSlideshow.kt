package com.hinnka.mycamera.appgallerypreview

import java.util.Random

/** 幻灯片放映状态。 */
enum class AppGalleryPreviewSlideshowState { IDLE, PLAYING, PAUSED, FINISHED }

/**
 * 幻灯片放映状态机（自研，纯 JVM，可单测）。
 *
 * 自研理由：需求仅为"定时切换 + 循环/随机 + 边界可控"，引入任何第三方库都比本实现（<120 行）
 * 更重；且必须与预览页的缩放/翻页状态机共用同一 clamp 后的配置。
 *
 * 时间由外部注入（[onTick] 的 nowMs），不依赖 Handler/协程，因此可在纯 JVM 下单测间隔边界。
 */
class AppGalleryPreviewSlideshow(
    count: Int,
    startIndex: Int = 0,
    val config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    /** 随机种子来源：配置 [AppGalleryPreviewConfig.slideshowShuffleSeed] 为 0 时用它取真随机。 */
    private val seedProvider: () -> Long = { System.nanoTime() },
) {
    private val safe = config.appGalleryPreviewSafe().config

    val count: Int = count.coerceAtLeast(0)

    /** 实际使用的间隔（毫秒，已 clamp 到 1000..30000）。 */
    val intervalMs: Long = safe.slideshowIntervalMs

    /** 播放顺序：随机开启时为确定性置换，否则为自然序。 */
    val order: List<Int> = buildOrder(this.count, safe, seedProvider)

    private var cursor: Int = 0

    var state: AppGalleryPreviewSlideshowState = AppGalleryPreviewSlideshowState.IDLE
        private set

    /** 上一次推进的时间戳（毫秒）；-1 表示尚未起算。 */
    private var lastAdvanceMs: Long = -1L

    /** 当前序号（0-based，取自 [order]）。 */
    val currentIndex: Int
        get() = order.getOrElse(cursor) { 0 }

    /** 当前播放位置（1-based，用于"3/12"计数显示）。 */
    val position: Int get() = if (count == 0) 0 else cursor + 1

    val isPlaying: Boolean get() = state == AppGalleryPreviewSlideshowState.PLAYING

    init {
        cursor = order.indexOf(startIndex).takeIf { it >= 0 } ?: 0
    }

    /** 开始/继续放映；空列表直接进入 FINISHED。 */
    fun play(nowMs: Long = System.currentTimeMillis()) {
        if (count == 0) {
            state = AppGalleryPreviewSlideshowState.FINISHED
            return
        }
        if (state == AppGalleryPreviewSlideshowState.FINISHED) {
            cursor = 0
        }
        state = AppGalleryPreviewSlideshowState.PLAYING
        lastAdvanceMs = nowMs
    }

    fun pause() {
        if (state == AppGalleryPreviewSlideshowState.PLAYING) {
            state = AppGalleryPreviewSlideshowState.PAUSED
        }
    }

    fun toggle(nowMs: Long = System.currentTimeMillis()) {
        when (state) {
            AppGalleryPreviewSlideshowState.PLAYING -> pause()
            else -> play(nowMs)
        }
    }

    fun stop() {
        state = AppGalleryPreviewSlideshowState.IDLE
        cursor = 0
        lastAdvanceMs = -1L
    }

    /** 距下次推进的剩余毫秒（用于进度指示）；未播放返回 0。 */
    fun remainingMs(nowMs: Long): Long {
        if (state != AppGalleryPreviewSlideshowState.PLAYING) return 0L
        if (lastAdvanceMs < 0L) return intervalMs
        val elapsed = nowMs - lastAdvanceMs
        return (intervalMs - elapsed).coerceIn(0L, intervalMs)
    }

    /** 进度 0..1（用于进度条）。 */
    fun progressFraction(nowMs: Long): Float =
        (1f - remainingMs(nowMs).toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)

    /**
     * 时间驱动：到达间隔则推进一项。
     *
     * 触发条件为 `elapsed >= intervalMs`（等于即触发，边界确定）。
     * @return 推进后的新序号；未推进返回 null
     */
    fun onTick(nowMs: Long): Int? {
        if (state != AppGalleryPreviewSlideshowState.PLAYING || count == 0) return null
        if (lastAdvanceMs < 0L) {
            lastAdvanceMs = nowMs
            return null
        }
        val elapsed = nowMs - lastAdvanceMs
        if (elapsed < 0L) {
            // 时间回拨（系统时间被改）：重新起算，避免疯狂推进。
            lastAdvanceMs = nowMs
            return null
        }
        if (elapsed < intervalMs) return null
        lastAdvanceMs = nowMs
        return advance()
    }

    /**
     * 手动推进一项（上一张/下一张按钮、或无定时器的驱动源）。
     * @return 新序号；已到末尾且不循环时返回 null 并置 FINISHED
     */
    fun advance(): Int? {
        if (count == 0) {
            state = AppGalleryPreviewSlideshowState.FINISHED
            return null
        }
        if (cursor + 1 < order.size) {
            cursor++
            return currentIndex
        }
        return if (safe.slideshowLoop) {
            cursor = 0
            currentIndex
        } else {
            state = AppGalleryPreviewSlideshowState.FINISHED
            null
        }
    }

    /** 回退一项（不改变播放状态）。 */
    fun retreat(): Int? {
        if (count == 0) return null
        cursor = if (cursor - 1 >= 0) cursor - 1 else if (safe.slideshowLoop) order.size - 1 else 0
        return currentIndex
    }

    /** 直接跳到某张（按原图索引），越界 clamp；找不到时保持原位。 */
    fun seekTo(mediaIndex: Int): Int {
        if (count == 0) return 0
        val clamped = mediaIndex.coerceIn(0, count - 1)
        val found = order.indexOf(clamped)
        if (found >= 0) cursor = found
        return currentIndex
    }

    private companion object {
        /**
         * 构建播放顺序。
         *
         * @param seedProvider 种子来源（config.seed>0 时优先使用配置种子）
         */
        fun buildOrder(
            count: Int,
            safe: AppGalleryPreviewConfig,
            seedProvider: () -> Long,
        ): List<Int> {
            val natural = (0 until count.coerceAtLeast(0)).toList()
            if (count <= 1 || !safe.slideshowShuffle) return natural
            val seed = if (safe.slideshowShuffleSeed > 0L) safe.slideshowShuffleSeed else seedProvider()
            return natural.shuffled(Random(seed))
        }
    }
}
