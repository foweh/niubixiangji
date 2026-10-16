package com.hinnka.mycamera.multiframesr

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MFSR 分片闸门（纯 JVM）：暂停 / 恢复 / 取消 / 节流的**唯一**语义实现。
 *
 * 为什么单独抽出来：
 *  - 生产路径经 [MfsrBgJobCheckpoint] 转调 `BgJob.checkYield()`，但 BgJob 依赖 Android 类，
 *    无法在纯 JVM 单测里验证"暂停恢复契约"；
 *  - 因此把契约语义收敛到本类，单测直接验证它，生产侧只做一层薄适配。
 *
 * 契约（与 `BgJob.checkYield()` 保持一致）：
 *  1. 每帧/每轮迭代末尾调用 [checkpoint]；已取消 → 抛 [MfsrCancelledException]；
 *  2. 暂停 → 阻塞在 [checkpoint] 内直到 [resume] 或 [cancel]；
 *  3. 节流：相邻两次 [checkpoint] 至少间隔 [throttleIntervalMs]（占空比限频）；
 *  4. [cancel] 在暂停中也必须立刻醒过来并抛异常（不会死等）；
 *  5. [resume] / [pause] / [cancel] 幂等；取消后 [resume] 不再有意义。
 */
class MfsrJobGate(
    private val name: String = "mfsr",
) : MfsrCheckpoint {

    /** 相邻 checkpoint 的最小间隔（毫秒），0 = 不节流。 */
    @Volatile
    var throttleIntervalMs: Long = 0L

    /** 每次 checkpoint 回调（进度上报/诊断），在调用线程执行。 */
    @Volatile
    var onCheckpoint: ((completedUnits: Int, totalUnits: Int) -> Unit)? = null

    private val lock = ReentrantLock()
    private val gate = lock.newCondition()
    private val cancelledFlag = AtomicBoolean(false)

    @Volatile
    private var paused = false

    private var lastTickNs = System.nanoTime()

    val isCancelled: Boolean get() = cancelledFlag.get()
    val isPaused: Boolean get() = paused

    fun pause() {
        lock.withLock {
            if (cancelledFlag.get()) return
            paused = true
        }
    }

    fun resume() {
        lock.withLock {
            if (cancelledFlag.get()) return
            paused = false
            gate.signalAll()
        }
    }

    /** 取消：立即解除暂停并唤醒等待者，下一次 [checkpoint] 抛异常。 */
    fun cancel() {
        cancelledFlag.set(true)
        lock.withLock {
            paused = false
            gate.signalAll()
        }
    }

    override fun checkpoint(completedUnits: Int, totalUnits: Int) {
        if (cancelledFlag.get()) throw MfsrCancelledException("$name cancelled")
        lock.withLock {
            while (paused && !cancelledFlag.get()) {
                gate.await()
            }
        }
        if (cancelledFlag.get()) throw MfsrCancelledException("$name cancelled")

        val interval = throttleIntervalMs
        if (interval > 0L) {
            val intervalNs = interval * 1_000_000L
            val elapsed = System.nanoTime() - lastTickNs
            if (elapsed < intervalNs) {
                val sleepMs = (intervalNs - elapsed) / 1_000_000L
                if (sleepMs > 0L) runCatching { Thread.sleep(sleepMs) }
                if (cancelledFlag.get()) throw MfsrCancelledException("$name cancelled")
            }
        }
        lastTickNs = System.nanoTime()
        onCheckpoint?.invoke(completedUnits, totalUnits)
        Thread.yield()
    }
}
