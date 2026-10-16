package com.hinnka.mycamera.longexposure

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * 光绘"实时叠加预览"后台队列。
 *
 * 为什么不用 [com.hinnka.mycamera.computational.BgJobRunner]：预览要求**低延迟、不排队**
 * （全局重算队列是单消费者串行、会长时间排队），因此这里用**独立的低优先级守护线程 + 只保留最新帧**的
 * 生产者-消费者模型：
 *
 *  - [offer]：采集线程把工作分辨率帧投递进来（O(1)，不阻塞采集）；队列满时**丢弃旧帧**而不是阻塞；
 *  - 消费线程：按 [LightPaintingConfig.previewIntervalFrames] 抽帧，把帧并入队列自有的增量叠加器，
 *    再按 [LightPaintingConfig.previewThrottleMs] 节流产出预览；
 *  - 因此预览展示的是**持续累加的叠加结果**（与成片语义一致），而不是单帧；
 *  - 预览分辨率上限 [LightPaintingConfig.previewMaxLongEdge]，与成片分辨率解耦。
 *
 * 线程与内存均可控：单线程、单帧驻留 + 一个预览分辨率累加器；[shutdown] 后线程退出。
 */
class LightPaintingPreviewQueue(
    config: LightPaintingConfig = LightPaintingConfig.DEFAULT,
    private val onPreview: (LinearRgbPlane, Int) -> Unit,
) : AutoCloseable {

    private val clamped: LightPaintingConfig = config.clamped()
    private val interval: Int = clamped.previewIntervalFrames
    private val throttleMs: Long = clamped.previewThrottleMs
    private val previewEdge: Int = clamped.previewMaxLongEdge
    private val gains: FloatArray = ColorTemperatureGains.gainsFor(
        clamped.brushColorTemperatureK,
        clamped.brushTint,
    )

    private val lock = ReentrantLock()
    private val wakeup: Condition = lock.newCondition()
    private var pending: LinearRgbPlane? = null
    private var darkFrame: ShortArray? = null
    private var running = true

    private var stacker: LongExposureStacker? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var lastEmitMs = 0L
    private var offeredCount = 0

    /** 自 [reset] 以来的投递计数（用于抽帧判定；[offeredFrames] 是累计统计量，不参与判定）。 */
    private var sampledOffers = 0

    /** 投递的帧数。 */
    @Volatile
    var offeredFrames: Int = 0
        private set

    /** 被丢弃的帧数（未到抽帧点 / 已被更新帧取代 / 队列已关闭）。 */
    @Volatile
    var droppedFrames: Int = 0
        private set

    /** 实际产出的预览张数。 */
    @Volatile
    var previewCount: Int = 0
        private set

    private val worker: Thread = Thread({ loop() }, "light-painting-preview").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    /**
     * 投递一帧（非阻塞、O(1)）。
     *
     * 契约：调用方**交出该平面的所有权**（投递后不得再修改 `samples`）。
     * 抽帧规则：自 [reset] 以来**第 1 帧必定受理**（首帧预览延迟最小），此后每
     * [LightPaintingConfig.previewIntervalFrames] 帧受理一次；未到抽帧点、已有待处理帧
     * 或队列已关闭时直接丢弃（计数 [droppedFrames]）。
     */
    fun offer(frame: LinearRgbPlane) {
        lock.withLock {
            offeredFrames++
            if (!running) {
                droppedFrames++
                return
            }
            sampledOffers++
            if (interval > 1 && sampledOffers != 1 && sampledOffers % interval != 0) {
                droppedFrames++
                return
            }
            if (pending != null) droppedFrames++
            pending = frame
            wakeup.signalAll()
        }
    }

    /** 设置/更新暗帧（预览同样做暗帧扣除；null = 关闭）。 */
    fun updateDarkFrame(dark: ShortArray?) {
        lock.withLock { darkFrame = dark }
    }

    /** 复位：清空待处理帧与叠加器（开始新一轮预览；抽帧计数也一并复位）。 */
    fun reset() {
        lock.withLock {
            pending = null
            stacker = null
            previewWidth = 0
            previewHeight = 0
            lastEmitMs = 0L
            offeredCount = 0
            sampledOffers = 0
        }
    }

    /** 关闭后台线程（幂等；最多等待 500ms）。 */
    fun shutdown() {
        lock.withLock {
            running = false
            pending = null
            wakeup.signalAll()
        }
        runCatching { worker.join(500L) }
    }

    override fun close() = shutdown()

    private fun loop() {
        while (true) {
            val frame: LinearRgbPlane?
            val dark: ShortArray?
            lock.withLock {
                while (running && pending == null) {
                    runCatching { wakeup.await(200L, TimeUnit.MILLISECONDS) }
                }
                if (!running) return
                frame = pending
                pending = null
                dark = darkFrame
            }
            if (frame == null) continue
            runCatching { consume(frame, dark) }
                .onFailure { /* 预览失败不影响拍摄主链路 */ }
        }
    }

    private fun consume(frame: LinearRgbPlane, dark: ShortArray?) {
        val target = stacker ?: createStacker(frame).also { stacker = it }
        val samples = LongExposureResampler.downsample(
            frame.samples,
            frame.width,
            frame.height,
            previewWidth,
            previewHeight,
        )
        target.addFrame(samples, dark, clamped.darkFrame)
        offeredCount++
        val now = System.currentTimeMillis()
        if (now - lastEmitMs < throttleMs) return
        lastEmitMs = now
        previewCount++
        onPreview(target.previewPlane(previewEdge, gains), target.accumulatedFrameCount)
    }

    private fun createStacker(frame: LinearRgbPlane): LongExposureStacker {
        val longest = max(frame.width, frame.height)
        val scale = min(1f, previewEdge.toFloat() / longest.toFloat())
        previewWidth = max(1, (frame.width * scale).toInt())
        previewHeight = max(1, (frame.height * scale).toInt())
        return LongExposureStacker(previewWidth, previewHeight, clamped.stackMode)
    }
}
