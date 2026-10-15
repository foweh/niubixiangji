package com.hinnka.mycamera.computational

import android.os.Process
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 长时后台任务状态。 */
enum class BgJobStatus { RUNNING, PAUSED, DONE, FAILED }

data class BgJobState(
    val status: BgJobStatus = BgJobStatus.RUNNING,
    /** 0f..1f。 */
    val progress: Float = 0f,
    val message: String = "",
)

/**
 * 一个后台慢速任务。block 在后台低优先级线程执行；分片间必须调用 [tick] 或 [checkYield]，
 * 以便响应暂停/恢复与进度上报。
 */
class BgJob internal constructor(
    val displayName: String,
    val chunkSize: Int,
    internal val block: BgJob.() -> Unit,
) {
    private val _state = MutableStateFlow(BgJobState())
    val state: StateFlow<BgJobState> = _state.asStateFlow()

    private val lock = ReentrantLock()
    private val pausedGate = lock.newCondition()
    @Volatile private var paused = false
    @Volatile private var finished = false

    /** 已处理的分片计数 / 总分片数（由 block 通过 [report] 维护）。 */
    private var doneChunks = 0
    private var totalChunks = 1

    /** 暂停执行（分片间隙生效，不会抢占正在算的一个 chunk）。 */
    fun pause() {
        lock.withLock {
            if (finished) return@withLock
            paused = true
            _state.value = _state.value.copy(status = BgJobStatus.PAUSED)
        }
        PLog.i(TAG, "job[$displayName] paused at ${(_state.value.progress * 100).toInt()}%")
    }

    fun resume() {
        lock.withLock {
            if (finished) return@withLock
            paused = false
            pausedGate.signalAll()
            _state.value = _state.value.copy(status = BgJobStatus.RUNNING)
        }
        PLog.i(TAG, "job[$displayName] resumed")
    }

    /**
     * 上报进度（每处理若干帧调用一次）。
     * @param done 已完成单元数；@param total 总单元数。
     */
    fun report(done: Int, total: Int) {
        totalChunks = total.coerceAtLeast(1)
        doneChunks = done.coerceIn(0, totalChunks)
        val p = doneChunks.toFloat() / totalChunks
        _state.value = _state.value.copy(
            status = if (p >= 1f && finished) BgJobStatus.DONE else _state.value.status,
            progress = p,
        )
    }

    /**
     * 分片间隙调用：若处于暂停则阻塞直到 resume；否则短暂 yield 降低瞬时占用。
     * 在 block 的每个 chunk 末尾调用。
     */
    fun checkYield() {
        lock.withLock {
            while (paused && !finished) {
                pausedGate.await()
            }
        }
        // 让出一点时间片，避免长任务抢前台拍照/预览。
        Thread.yield()
    }

    internal fun markDone() {
        finished = true
        lock.withLock { paused = false; pausedGate.signalAll() }
        _state.value = _state.value.copy(status = BgJobStatus.DONE, progress = 1f)
    }

    internal fun markFailed(msg: String) {
        finished = true
        lock.withLock { paused = false; pausedGate.signalAll() }
        _state.value = _state.value.copy(status = BgJobStatus.FAILED, message = msg)
    }

    companion object { private const val TAG = "BgJob" }
}

/**
 * BgJobRunner — 长时任务「后台低优先级慢速完成」调度器（用户 2026-10-15 确认）。
 *
 * - 单后台线程，设置 [Process.THREAD_PRIORITY_BACKGROUND]（最低优先级，不抢前台）。
 * - 分片分时：block 自行按 chunkSize 分批，每批 [BgJob.checkYield] 一次。
 * - 可暂停/恢复；进度 StateFlow 供 UI 进度条；完成/失败自动通知。
 * - 不卡拍照/预览/UI：所有重算在后台线程，前台仅收集结果。
 */
object BgJobRunner {
    private const val TAG = "BgJobRunner"

    private val _activeJobs = MutableStateFlow<List<BgJob>>(emptyList())
    /** 当前/最近任务列表，B1 读它显示进度条与完成通知。 */
    val activeJobs: StateFlow<List<BgJob>> = _activeJobs.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bg-job-runner").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * 提交一个后台任务。
     * @param name 任务名（UI 显示）；@param chunkSize 每批处理单元数（帧等）。
     * @param block 任务体（在后台线程执行，分片间调 [BgJob.checkYield]/[BgJob.report]）。
     */
    fun submit(name: String, chunkSize: Int, block: BgJob.() -> Unit): BgJob {
        val job = BgJob(name, chunkSize.coerceAtLeast(1), block)
        _activeJobs.value = _activeJobs.value + job
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { job.block() }
                .onSuccess { job.markDone() }
                .onFailure {
                    PLog.w(TAG, "job[$name] failed: ${it.message}")
                    job.markFailed(it.message ?: "error")
                }
            // 任务结束后从活动列表移除（保留 1s 让 UI 看到 DONE）。
            Thread.sleep(1000L)
            _activeJobs.value = _activeJobs.value.filterNot { it === job }
        }
        return job
    }

    fun cancelAll() {
        _activeJobs.value.forEach { it.pause() }
    }
}
