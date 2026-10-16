package com.hinnka.mycamera.computational

import android.os.Process
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 后台任务状态。
 *
 * 注意：取消（[BgJob.cancel]）刻意复用 [FAILED] + message="cancelled"，不新增枚举常量，
 * 以免破坏界面侧（BgJobChip）对 status 的穷尽 when 分支。
 */
enum class BgJobStatus { RUNNING, PAUSED, DONE, FAILED }

data class BgJobState(
    val status: BgJobStatus = BgJobStatus.RUNNING,
    /** 0f..1f。 */
    val progress: Float = 0f,
    val message: String = "",
)

/**
 * 任务类型：决定默认的 CPU 频控（分片节流间隔）与内存预算。
 * 全部参数可经 [BgJobRunner.configure] 运行时覆盖。
 */
enum class BgTaskType(
    /** 每分片之间的最小时间间隔（毫秒）。>0 表示按占空比节流，控单位时间 CPU 峰值。 */
    val defaultThrottleIntervalMs: Long,
    /** 单批最大帧数（限制瞬时内存/峰值）。 */
    val defaultMaxFrameBatch: Int,
    /** 该类任务的内存预算上限（字节）。超限则分片降级或拒绝，防 OOM。 */
    val defaultMaxHeapBytes: Long,
) {
    /** 本地多帧/PS 堆栈（CPU 重）。 */
    STACK(12L, 8, 512L * 1024 * 1024),
    // P0-10：内存预算与设备解耦（见 submit 处 effectiveMaxHeapBytes 应用）。


    /** 天文/星轨堆栈（200+ 帧，CPU 最重）。 */
    ASTRO(16L, 8, 768L * 1024 * 1024),

    /** RAW/DNG 多帧合成（内存最重）。 */
    RAW_MERGE(12L, 6, 768L * 1024 * 1024),

    /** 多帧 HDR 合成。 */
    HDR(8L, 12, 512L * 1024 * 1024),

    /** 服务器超分（IO 密集，不节流）。 */
    SERVER_ENHANCE(0L, 1, 256L * 1024 * 1024),
    /** 端侧 AI 超分/风格迁移/图像修复（ONNX 推理，CPU 密集 + 内存重，按 tile 分片）。 */
    SUPER_RES(10L, 4, 256L * 1024 * 1024),

    /** AI/LUT 分析（轻量 CPU）。 */
    AI_LUT(6L, 16, 256L * 1024 * 1024),

    /** 多帧超分（帧数完全开放 2~1000，CPU 重且需多轮复用帧缓存）。 */
    MFSR(20L, 4, 640L * 1024 * 1024),
    /** 经典后处理 + 镜头畸变校正 + 趣味滤镜（CPU 中等，分片让出、可降工作分辨率）。 */
    ENHANCE(10L, 8, 512L * 1024 * 1024),
    /** 应用内置相册（缩略图补齐 / 重复检测 / 回收站清理 / 拍摄统计 / 导出备份）。 */
    GALLERY(8L, 32, 256L * 1024 * 1024),
    /** 全景拼接（帧数开放 2~100，需多次全帧重采样与融合，内存峰值最高）。 */
    PANORAMA(18L, 4, 768L * 1024 * 1024),
    /** GIF / 动态贴纸导出 + 视频转 GIF（逐帧量化 + 抖动，帧数可到 1000）。 */
    GIF_EXPORT(12L, 8, 512L * 1024 * 1024),
    /** 未分类。 */
    /** 取景器辅助（大图边缘检测等偶发重计算，频率低、可让步）。 */
    VIEWFINDER(20L, 16, 256L * 1024 * 1024),
    /** 延时摄影后台合成（IO + 编码，分片写盘，帧数开放）。 */
    VIDEO_TIMELAPSE(8L, 16, 384L * 1024 * 1024),

    /** 视频超分（抽帧 → 超分 → 重编码；内存最重，故预算较高）。 */
    VIDEO_SR(10L, 4, 768L * 1024 * 1024),

    /** 视频降噪（时域窗口跨帧，需同时驻留窗口帧）。 */
    VIDEO_DENOISE(12L, 6, 512L * 1024 * 1024),

    /** 边录边拍快照批量落盘（JPEG 编码，轻量、可高频）。 */
    VIDEO_SNAPSHOT(4L, 8, 256L * 1024 * 1024),
    /** 预览域：大图/RAW 解码、直方图、RAW→JPEG 导出（IO+CPU 混合，节流偏保守）。 */
    PREVIEW(10L, 8, 384L * 1024 * 1024),
    /** 星空指向离线精修：一段姿态缓冲的低优先批处理，可暂停/取消。 */
    ASTRONAV(16L, 8, 768L * 1024 * 1024),
    GENERIC(10L, 16, 384L * 1024 * 1024),
    LONG_EXPOSURE(16L, 6, 512L * 1024 * 1024),
    /**
     * 视频编辑导出（media3 Transformer 转码：区间裁剪 / 旋转 / 变速 / 去音 / 多段拼接 / LUT 调色）。
     *
     * 取值理由：编码与合流是「长时 IO + 硬编」混合负载，单批只跑 1 段（批量无收益，且 Transformer
     * 实例不应并行）；沿用 10ms 节流与前台任务同级但优先级更低（BgJobPriority.LOW，见调用点）；
     * 768MB 预算对应「编码缓冲 + 音频混流 + 单帧纹理」峰值，与 RAW_MERGE/ASTRO 同量级。
     */
    VIDEO_EDIT(10L, 1, 768L * 1024 * 1024),
    /**
     * 微距焦点堆叠（对焦步进扫描 → 逐帧清晰度图 → 软掩模加权融合）。
     *
     * 取值理由：与 STACK 同为「多帧 + CPU 重」的堆叠类负载，节流 12ms 与 STACK 对齐；
     * 帧数完全开放（2~4096），单批 6 帧与 STACK 的 8 帧同量级（清晰度图逐帧独立，批大小只影响
     * 节流口径）；512MB 预算对应「工作分辨率清晰度/掩模缓存（1B/像素/帧）+ 单个输出累加器
     * （12B/像素）」的峰值，与 STACK 一致；超出预算由 FocusStackProcessor **逐级降低工作分辨率**
     * 而不是失败。
     */
    FOCUS_STACK(12L, 6, 512L * 1024 * 1024),
}

/** 任务优先级（数值越大越先执行；同优先级 FIFO）。 */
object BgJobPriority {
    const val LOW = 0
    const val NORMAL = 5
    const val HIGH = 10
}

/** 任务被取消时在分片间隙抛出；调用方应清理中间态。 */
class BgJobCancelledException(val jobName: String) : RuntimeException("job cancelled: $jobName")

/** 任务内存需求超出预算且无法降级时抛出。 */
class BgJobMemoryLimitException(
    val jobName: String,
    val requiredBytes: Long,
    val budgetBytes: Long,
) : RuntimeException("job[$jobName] needs $requiredBytes bytes > budget $budgetBytes")

/** 任务生命周期事件（供界面完成通知）。 */
data class BgJobEvent(val job: BgJob, val status: BgJobStatus, val message: String = "")

/**
 * 一个后台慢速任务。
 *
 * 契约（与界面 BgJobChip 对齐，勿改）：[displayName] / [pause] / [resume] / [state]。
 * 新增：[cancel] / [isCancelled] / [type] / [batchSizeFor] / [guardMemory]。
 *
 * block 在后台低优先级线程执行；**每个分片末尾必须调用 [checkYield]**，
 * 以响应暂停/取消、施加 CPU 节流并让出时间片。
 */
class BgJob internal constructor(
    val displayName: String,
    val chunkSize: Int,
    val type: BgTaskType,
    val priority: Int,
    internal val block: suspend BgJob.() -> Unit,
) {
    private val _state = MutableStateFlow(BgJobState())
    val state: StateFlow<BgJobState> = _state.asStateFlow()

    private val lock = ReentrantLock()
    private val pausedGate = lock.newCondition()
    @Volatile private var paused = false
    @Volatile private var finished = false
    private val cancelledFlag = AtomicBoolean(false)

    /** 有效 CPU 节流间隔（毫秒），可被 [BgJobRunner.configure] 覆盖。 */
    @Volatile internal var throttleIntervalMs: Long = type.defaultThrottleIntervalMs

    /** 有效内存预算（字节），可被 [BgJobRunner.configure] 覆盖。 */
    @Volatile internal var maxHeapBytes: Long = type.defaultMaxHeapBytes

    private var lastTickNs = System.nanoTime()

    val isCancelled: Boolean get() = cancelledFlag.get()
    val isFinished: Boolean get() = finished

    /** 暂停（分片间隙生效，不抢占正在算的一个 chunk）。 */
    fun pause() {
        lock.withLock {
            if (finished || cancelledFlag.get()) return@withLock
            paused = true
            _state.value = _state.value.copy(status = BgJobStatus.PAUSED)
        }
        PLog.i(TAG, "job[$displayName] paused at ${(_state.value.progress * 100).toInt()}%")
    }

    fun resume() {
        lock.withLock {
            if (finished || cancelledFlag.get()) return@withLock
            paused = false
            pausedGate.signalAll()
            _state.value = _state.value.copy(status = BgJobStatus.RUNNING)
        }
        PLog.i(TAG, "job[$displayName] resumed")
    }

    /** 请求取消：分片间隙抛 [BgJobCancelledException]，调用方负责清理中间态。 */
    fun cancel() {
        cancelledFlag.set(true)
        lock.withLock { paused = false; pausedGate.signalAll() }
        PLog.i(TAG, "job[$displayName] cancel requested")
    }

    /** 上报进度（每个分片调用一次）。 */
    fun report(done: Int, total: Int) {
        val t = total.coerceAtLeast(1)
        val p = done.coerceIn(0, t).toFloat() / t
        _state.value = _state.value.copy(progress = p)
    }

    /**
     * 上报分阶段进度与文案（由 GIF/视频导出等任务调用：progress 为 0f..1f 的比例，
     * message 为给界面展示的阶段说明）。与 [report] 互补，互不影响。
     */
    fun report(progress: Float, message: String) {
        _state.value = _state.value.copy(
            progress = progress.coerceIn(0f, 1f),
            message = message,
        )
    }

    /**
     * 分片间隙调用（每个 chunk 末尾必须调）：
     * 1) 已取消 → 抛 [BgJobCancelledException]；
     * 2) 暂停 → 阻塞至 resume；
     * 3) CPU 节流：保证分片之间至少间隔 [throttleIntervalMs]（占空比限制，压 CPU 峰值）；
     * 4) `Thread.yield()` 再让出一次时间片，避免抢前台拍照/预览/UI。
     */
    fun checkYield() {
        if (cancelledFlag.get()) throw BgJobCancelledException(displayName)
        lock.withLock {
            while (paused && !finished && !cancelledFlag.get()) {
                pausedGate.await()
            }
        }
        if (cancelledFlag.get()) throw BgJobCancelledException(displayName)
        val intervalNs = throttleIntervalMs * 1_000_000L
        if (intervalNs > 0L) {
            val elapsedNs = System.nanoTime() - lastTickNs
            if (elapsedNs < intervalNs) {
                val sleepMs = (intervalNs - elapsedNs) / 1_000_000L
                if (sleepMs > 0L) runCatching { Thread.sleep(sleepMs) }
                if (cancelledFlag.get()) throw BgJobCancelledException(displayName)
            }
        }
        lastTickNs = System.nanoTime()
        Thread.yield()
    }

    /**
     * 按类型内存上限裁剪帧批大小（防 OOM；星轨 200+ 帧、RAW 合成等大任务用）。
     * @return 实际可一次处理的帧数（>=1）。
     */
    fun batchSizeFor(frameCount: Int): Int {
        val byChunk = chunkSize.coerceAtLeast(1)
        val byType = BgJobRunner.effectiveMaxFrameBatch(type)
        return frameCount.coerceAtLeast(1).coerceAtMost(byChunk).coerceAtMost(byType)
    }

    /**
     * 内存守卫：判断一次性分配 [requiredBytes] 是否安全。
     * 超预算返回 false（调用方应分片 / 降分辨率 / 落盘），由调用方决定是否抛异常。
     */
    fun guardMemory(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        if (requiredBytes > maxHeapBytes) return false
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        // 保留 40% 余量给前台（相机预览/拍照），避免极端内存压力下被 LMK 杀。
        return used + requiredBytes < (max * 0.6).toLong()
    }

    internal fun markDone() {
        finished = true
        lock.withLock { paused = false; pausedGate.signalAll() }
        _state.value = _state.value.copy(status = BgJobStatus.DONE, progress = 1f)
    }

    /** 失败/取消统一落到 FAILED（取消时 message="cancelled"，保住界面穷尽 when 分支）。 */
    internal fun markFailed(msg: String) {
        finished = true
        lock.withLock { paused = false; pausedGate.signalAll() }
        _state.value = _state.value.copy(status = BgJobStatus.FAILED, message = msg)
    }

    companion object {
        private const val TAG = "BgJob"
    }
}

/**
 * BgJobRunner — 全局性能调度器（用户 2026-10-16 定稿的性能硬约束）。
 *
 * 设计目标：所有功能都用上顶尖算法，但**允许拖长渲染时间、严格限制单位时间资源占用**
 * （CPU/内存/功耗峰值受限）。为此：
 *
 *  1. **全局任务队列**：所有重任务统一进队，单消费者串行（并发=1），可长时间排队慢慢跑；
 *     同优先级 FIFO，高优先级（如用户刚拍完的成片）可插队。
 *  2. **CPU 频控**：后台线程 [Process.THREAD_PRIORITY_BACKGROUND] + [Thread.MIN_PRIORITY]；
 *     分片之间按 [BgTaskType] 的节流间隔做占空比限制（token-bucket 式「干一会、歇一会」），
 *     把 CPU 峰值摊平，不抢前台拍照/预览/UI。
 *  3. **内存上限**：按 task type 设帧批上限与堆预算；[BgJob.guardMemory] 在分配前判断，
 *     超限则分片 / 降分辨率 / 落盘或拒绝（防 OOM，尤其星轨 200+ 帧与 RAW/DNG 合成）。
 *  4. **可暂停 / 恢复 / 取消**：pause/resume 在分片间隙生效；cancel 抛 [BgJobCancelledException]
 *     让调用方清理中间态。
 *  5. **不阻塞 UI / ANR 防护**：一切重算在后台线程，主线程只提交与消费 StateFlow；
 *     队列不持有 Activity/Context 引用；任务体不 sleep 主线程；进度经 StateFlow 异步推送。
 *     **OOM 防护**：分配前查 [BgJob.guardMemory]；图像缓冲用分片流式累加，不整批驻留；
 *     MEDIAN 类需缓存全部样本的任务在工作分辨率上降采样并受堆预算约束。
 *
 * 调用方（含 LUT 模块的 AI 分析）可直接 `BgJobRunner.submit(...)`，本类不限调用方。
 */
object BgJobRunner {
    private const val TAG = "BgJobRunner"

    private val _activeJobs = MutableStateFlow<List<BgJob>>(emptyList())
    /** 队列中 + 正在执行的任务列表，界面读它显示进度条。 */
    val activeJobs: StateFlow<List<BgJob>> = _activeJobs.asStateFlow()

    private val _events = MutableSharedFlow<BgJobEvent>(extraBufferCapacity = 16)
    /** 任务完成/失败/取消事件，供界面做完成通知。 */
    val jobEvents: SharedFlow<BgJobEvent> = _events.asSharedFlow()

    private val seq = AtomicLong(0L)

    private class Queued(val bgJob: BgJob, private val seqNo: Long) : Comparable<Queued> {
        override fun compareTo(other: Queued): Int {
            val byPriority = other.bgJob.priority.compareTo(bgJob.priority) // 高优先级在前
            return if (byPriority != 0) byPriority else seqNo.compareTo(other.seqNo) // 同优先级 FIFO
        }
    }

    private val queue = PriorityBlockingQueue<Queued>(16)
    @Volatile private var shutdown = false

    // 运行时覆盖的频控 / 内存参数（按类型）。
    private val throttleOverrides = HashMap<BgTaskType, Long>()
    private val batchOverrides = HashMap<BgTaskType, Int>()
    private val heapOverrides = HashMap<BgTaskType, Long>()
    /** 内存预算占可用堆比例上限（P0-10：低内存机不再给出无法达成的绝对预算）。 */
    private const val HEAP_BUDGET_FRACTION = 0.45

    private fun deviceHeapBudgetBytes(): Long =
        (Runtime.getRuntime().maxMemory() * HEAP_BUDGET_FRACTION).toLong()

    private val pendingFinish = HashMap<BgJob, ((BgJob) -> Unit)?>()

    @Suppress("unused")
    private val worker: Thread = Thread({ workerLoop() }, "bg-job-runner").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    /**
     * 提交任务（契约签名，参数向后兼容；既有 `submit(name, chunkSize) { }` 调用不受影响）。
     *
     * @param name 任务名（界面显示，即 BgJob.displayName）。
     * @param chunkSize 每批处理单元数（帧等）。
     * @param type 任务类型 → 默认频控与内存预算。
     * @param priority 见 [BgJobPriority]（数值越大越先执行）。
     * @param onFinish 任务结束（成功/失败/取消）回调，在后台线程执行。
     */
    fun submit(
        name: String,
        chunkSize: Int = 32,
        type: BgTaskType = BgTaskType.GENERIC,
        priority: Int = BgJobPriority.NORMAL,
        onFinish: ((BgJob) -> Unit)? = null,
        block: suspend BgJob.() -> Unit,
    ): BgJob {
        // P2-9：chunkSize<=0（未显式传入）时按任务类型取默认批大小。
        val effectiveChunk = if (chunkSize <= 0) type.defaultMaxFrameBatch else chunkSize
        val job = BgJob(name, effectiveChunk.coerceAtLeast(1), type, priority, block)
        throttleOverrides[type]?.let { job.throttleIntervalMs = it }
        // P0-10：设备感知预算（绝对值 / 覆写值 / 可用堆×比例 三者取小）。
        job.maxHeapBytes = effectiveMaxHeapBytes(type)
        synchronized(_activeJobs) {
            _activeJobs.value = _activeJobs.value + job
        }
        synchronized(pendingFinish) { pendingFinish[job] = onFinish }
        queue.offer(Queued(job, seq.incrementAndGet()))
        PLog.i(TAG, "queued job[$name] type=$type priority=$priority queued=${queue.size}")
        return job
    }

    private fun workerLoop() {
        while (!shutdown) {
            val item = runCatching { queue.take() }.getOrNull() ?: continue
            val job = item.bgJob
            if (job.isCancelled) {
                job.markFailed("cancelled")
                finishBookkeeping(job)
                continue
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // block 为 suspend（任务体内部会调用 DataStore 等挂起函数），
            // 在本后台线程上用 runBlocking 驱动；非 suspend 的既有调用点照常合法。
            runCatching { runBlocking { job.block.invoke(job) } }
                .onSuccess { job.markDone() }
                .onFailure { e ->
                    if (e is BgJobCancelledException) {
                        PLog.i(TAG, "job[${job.displayName}] cancelled")
                        job.markFailed("cancelled")
                    } else {
                        PLog.w(TAG, "job[${job.displayName}] failed: ${e.message}", e)
                        job.markFailed(e.message ?: "error")
                    }
                }
            finishBookkeeping(job)
            // 任务之间也留一点间隔，避免连续重任务把 CPU 拉满（功耗峰值限制）。
            runCatching { Thread.sleep(200L) }
        }
    }

    private fun finishBookkeeping(job: BgJob) {
        val cb = synchronized(pendingFinish) { pendingFinish.remove(job) }
        runCatching { cb?.invoke(job) }
        val st = job.state.value
        _events.tryEmit(BgJobEvent(job, st.status, st.message))
        // 保留 1s 让界面看到 DONE/FAILED，再从活动列表移除。
        runCatching { Thread.sleep(1000L) }
        synchronized(_activeJobs) {
            _activeJobs.value = _activeJobs.value.filterNot { it === job }
        }
    }

    /**
     * 运行时配置某类任务的频控与内存参数（CPU 频控参数可配）。
     *
     * @param throttleMs 分片间节流间隔（毫秒），0 = 不节流。
     * @param maxFrameBatch 单批最大帧数。
     * @param maxHeapBytes 内存预算上限；null / <=0 表示不改。
     */
    fun configure(
        type: BgTaskType,
        throttleMs: Long? = null,
        maxFrameBatch: Int? = null,
        maxHeapBytes: Long? = null,
    ) {
        throttleMs?.let { throttleOverrides[type] = it.coerceAtLeast(0L) }
        if (maxFrameBatch != null) {
            synchronized(batchOverrides) { batchOverrides[type] = maxFrameBatch.coerceAtLeast(1) }
        }
        if (maxHeapBytes != null && maxHeapBytes > 0) {
            synchronized(heapOverrides) { heapOverrides[type] = maxHeapBytes }
        }
        PLog.i(
            TAG,
            "configured $type throttle=${throttleOverrides[type]} " +
                "batch=${batchOverrides[type]} heap=${heapOverrides[type]}"
        )
    }

    internal fun effectiveMaxFrameBatch(type: BgTaskType): Int =
        synchronized(batchOverrides) { batchOverrides[type] } ?: type.defaultMaxFrameBatch

    internal fun effectiveMaxHeapBytes(type: BgTaskType): Long = minOf(
        type.defaultMaxHeapBytes,
        synchronized(heapOverrides) { heapOverrides[type] } ?: Long.MAX_VALUE,
        deviceHeapBudgetBytes(),
    )

    /** 暂停队列中所有任务（含正在跑的）。 */
    fun pauseAll() {
        _activeJobs.value.forEach { it.pause() }
    }

    fun resumeAll() {
        _activeJobs.value.forEach { it.resume() }
    }

    /** 取消所有排队/运行中的任务。 */
    fun cancelAll() {
        _activeJobs.value.forEach { it.cancel() }
    }

    /** 当前队列长度（排队中，不含正在执行）。 */
    val queuedCount: Int get() = queue.size
}
