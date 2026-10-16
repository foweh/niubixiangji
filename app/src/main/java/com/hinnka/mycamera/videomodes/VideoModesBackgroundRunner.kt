package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/**
 * 视频模式重任务的后台执行层（PATTERN_SPEC §1.5 性能调度硬约束）。
 *
 * 契约（与 `computational/BgJobRunner.kt` 一致）：
 * - 单消费者串行队列、后台低优先级线程、按 [BgTaskType] 做分片节流；
 * - **每个分片末尾必调 `job.checkYield()`**（响应暂停/取消 + 节流 + 让出时间片）；
 * - 分配前用 `job.guardMemory(bytes)` 守卫，**不足时缩小分片而不是失败**；
 * - **总帧数不设上限**：分片数量随用户配置增长，资源由守卫与降级消化。
 *
 * 新增的 [BgTaskType] 枚举条目（`VIDEO_TIMELAPSE` / `VIDEO_SR` / `VIDEO_DENOISE` / `VIDEO_SNAPSHOT`）
 * 由 `delivery/patches/videomodes_patch.md` 的 ENUM 段给出完整条目，集成阶段添加。
 */
object VideoModesBackgroundRunner {

    private const val TAG = "VideoModesRunner"

    /** 默认分片单元数（与 BgTaskType 的批上限无关，仅决定进队粒度）。 */
    const val DEFAULT_CHUNK_FRAMES = 8

    /**
     * 通用分片执行器：把「按帧序列处理」的重任务跑成 BgJob。
     *
     * @param name 任务显示名（BgJobChip 进度条文案）。
     * @param type 任务类型（决定节流与内存预算）。
     * @param totalFrames 总帧数（**不设上限**，可为 0 → 立即完成）。
     * @param chunkFrames 期望分片帧数（内存不足时按 1/2 逐级缩小）。
     * @param perFrameBytes 单帧工作缓冲估算（<=0 表示跳过内存守卫）。
     * @param onChunk 分片工作体：`(job, 帧索引区间)`；实现内部应处理该批并自行落盘/累加。
     * @return 已提交的 [BgJob]（可 pause/resume/cancel）。
     */
    fun runChunked(
        name: String,
        type: BgTaskType,
        totalFrames: Int,
        chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
        priority: Int = BgJobPriority.LOW,
        perFrameBytes: Long = 0L,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, frames: IntRange) -> Unit,
    ): BgJob {
        val total = totalFrames.coerceAtLeast(0)
        val chunk = chunkFrames.coerceAtLeast(1)
        return BgJobRunner.submit(
            name = name,
            chunkSize = chunk,
            type = type,
            priority = priority,
            onFinish = onFinish,
        ) {
            if (total == 0) return@submit
            val batches = TimelapsePlanner.captureBatches(total, chunk)
            var done = 0
            for (batch in batches) {
                var start = batch.first
                while (start <= batch.last) {
                    var size = batch.last - start + 1
                    if (perFrameBytes > 0L) {
                        // 内存守卫：超预算就把本片减半（**降级而不是失败**，PATTERN_SPEC §4）。
                        var guardCount = 0
                        while (size > 1 && !guardMemory(perFrameBytes * size)) {
                            size /= 2
                            guardCount++
                        }
                        if (guardCount > 0) {
                            PLog.w(
                                TAG,
                                "job[$name] 内存受限：分片缩至 $size 帧（降级 $guardCount 次，任务继续）",
                            )
                        }
                    }
                    val end = (start + size - 1).coerceAtMost(batch.last)
                    onChunk(this, start..end)
                    done += end - start + 1
                    report(done, total)
                    // 分片末尾必调：暂停/取消响应 + CPU 节流 + 让出时间片。
                    checkYield()
                    start = end + 1
                }
            }
        }
    }

    /** §2 延时摄影合成（后台，分片；帧数开放）。 */
    fun submitTimelapseCompose(
        config: TimelapseConfig,
        totalFrames: Int = config.totalFrames,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, frames: IntRange) -> Unit,
    ): BgJob = runChunked(
        name = "延时合成 $totalFrames 帧",
        type = BgTaskType.VIDEO_TIMELAPSE,
        totalFrames = totalFrames,
        chunkFrames = DEFAULT_CHUNK_FRAMES,
        onFinish = onFinish,
        onChunk = onChunk,
    )

    /** §5 视频超分（后台：抽帧 → 超分 → 重编码；分片 + 内存守卫降级）。 */
    fun submitVideoSr(
        config: VideoSrConfig,
        totalFrames: Int,
        perFrameBytes: Long = 0L,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, frames: IntRange) -> Unit,
    ): BgJob {
        val plan = VideoSuperResolutionPlanner.plan(
            config = config,
            totalFrames = totalFrames,
            guardMemory = { true },
            perFrameBytes = 0L,
        )
        return runChunked(
            name = "视频超分 ${plan.scale}x（${plan.processedFrames} 帧）",
            type = BgTaskType.VIDEO_SR,
            totalFrames = plan.processedFrames,
            chunkFrames = plan.chunkFrames,
            perFrameBytes = perFrameBytes,
            onFinish = onFinish,
            onChunk = onChunk,
        )
    }

    /** §10 视频降噪（后台；时域窗口分片）。 */
    fun submitVideoDenoise(
        config: VideoDenoiseConfig,
        totalFrames: Int,
        perFrameBytes: Long = 0L,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, frames: IntRange) -> Unit,
    ): BgJob {
        val plan = VideoDenoisePlanner.plan(config = config, totalFrames = totalFrames)
        return runChunked(
            name = "视频降噪 ${plan.mode.label}",
            type = BgTaskType.VIDEO_DENOISE,
            totalFrames = plan.totalFrames,
            chunkFrames = plan.chunkFrames,
            perFrameBytes = perFrameBytes,
            onFinish = onFinish,
            onChunk = onChunk,
        )
    }

    /**
     * §11 夜景视频（复用降噪任务类型：夜景 = 增益提升 + 时域降噪）。
     * 增益提升在采集侧即时生效（非后台任务）；此处只调度时域降噪部分。
     */
    fun submitNightVideo(
        decision: NightVideoDecision,
        totalFrames: Int,
        perFrameBytes: Long = 0L,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, frames: IntRange) -> Unit,
    ): BgJob? {
        if (!decision.active || !decision.denoiseConfig.enabled) return null
        return submitVideoDenoise(
            config = decision.denoiseConfig,
            totalFrames = totalFrames,
            perFrameBytes = perFrameBytes,
            onFinish = onFinish,
            onChunk = onChunk,
        )
    }

    /** §12 边录边拍快照落盘（后台批量；避免在录制线程做 JPEG 编码）。 */
    fun submitSnapshotBatch(
        config: SnapshotDuringRecordingConfig,
        totalSnapshots: Int,
        perFrameBytes: Long = 0L,
        onFinish: ((BgJob) -> Unit)? = null,
        onChunk: (job: BgJob, indexes: IntRange) -> Unit,
    ): BgJob = runChunked(
        name = "快照落盘 $totalSnapshots 张",
        type = BgTaskType.VIDEO_SNAPSHOT,
        totalFrames = totalSnapshots,
        chunkFrames = config.maxPending.coerceAtLeast(1),
        onFinish = onFinish,
        onChunk = onChunk,
    )

    /** 后台任务内存预算估算：按分辨率与每像素字节数给单帧估算值。 */
    fun estimatePerFrameBytes(width: Int, height: Int, bytesPerPixel: Int = 4): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong() * bytesPerPixel.coerceAtLeast(1)
}
