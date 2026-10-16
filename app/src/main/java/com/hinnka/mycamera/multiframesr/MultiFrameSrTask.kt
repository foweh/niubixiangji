package com.hinnka.mycamera.multiframesr

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobCancelledException
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgJobStatus
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * [MfsrCheckpoint] → `BgJob` 的适配层。
 *
 * 把 MFSR 的分片语义完整映射到调度器的性能约束上：
 *  - [BgJob.checkYield]：响应取消（抛 [BgJobCancelledException]）、暂停阻塞、按 [BgTaskType.MFSR]
 *    的节流间隔做占空比限频、让出时间片；
 *  - [BgJob.report]：把已完成帧数/迭代轮数推给 UI 进度条。
 *
 * 取消异常被**翻译**成 [MfsrCancelledException]，这样算法层（processor）只需识别一种取消类型，
 * 不会把它当成普通异常吞掉。
 */
class MfsrBgJobCheckpoint(private val job: BgJob) : MfsrCheckpoint {
    override fun checkpoint(completedUnits: Int, totalUnits: Int) {
        job.report(completedUnits, totalUnits)
        try {
            job.checkYield()
        } catch (e: BgJobCancelledException) {
            throw MfsrCancelledException("${job.displayName} cancelled")
        }
    }
}

/**
 * 多帧超分的后台任务入口（全局性能调度硬约束：一切重算走 BgJobRunner）。
 *
 * 任务类型为 [BgTaskType.MFSR]（由补丁写入 `BgJobRunner.kt` 枚举）：默认节流 20ms、单批 4 帧、
 * 内存预算 640MB —— 「可以慢，但必须受限」。帧数**不做上限**：1000 帧也只是排队慢慢跑。
 */
object MultiFrameSrTask {

    private const val TAG = "MfsrTask"

    /** 默认任务名（UI 进度 chip 显示）。 */
    const val TASK_NAME = "多帧超分"

    /** 每分片处理的帧数（与 [BgTaskType.MFSR] 的 defaultMaxFrameBatch 一致）。 */
    const val FRAME_CHUNK = 4

    /** 帧缓存落盘目录名（位于调用方传入的缓存根目录下）。 */
    const val SPILL_DIR_NAME = "mfsr_spill"

    /**
     * 提交一次多帧超分任务。
     *
     * 帧数据**逐帧流式**处理：不会把全部帧的位图/平面同时驻留（超预算的帧写入 [spillDir]）。
     *
     * @param frames 连拍帧（第 0 帧为参考帧）。调用方保留所有权，本任务不 recycle 输入。
     * @param config 全部开放参数（内部 clamp）
     * @param spillDir 帧缓存落盘目录；null = 不落盘（全驻内存，仍不拒绝任务）
     * @param onResult 结果回调，在后台线程执行；取消/失败时回调 null
     */
    fun submit(
        frames: List<Bitmap>,
        config: MfsrConfig,
        displayName: String = TASK_NAME,
        priority: Int = BgJobPriority.LOW,
        spillDir: File? = null,
        cacheRoot: File? = null,
        onResult: (Bitmap?) -> Unit,
    ): BgJob {
        val cfg = config.coerced()
        val dir = spillDir ?: cacheRoot?.let { File(it, SPILL_DIR_NAME) }
        var produced: Bitmap? = null

        return BgJobRunner.submit(
            name = displayName,
            chunkSize = FRAME_CHUNK,
            type = BgTaskType.MFSR,
            priority = priority,
            onFinish = { job ->
                val done = job.state.value.status == BgJobStatus.DONE
                val out = if (done) produced else null
                if (out == null) runCatching { produced?.recycle() }
                produced = null
                onResult(out)
            },
        ) {
            val checkpoint = MfsrBgJobCheckpoint(this)
            val batch = batchSizeFor(frames.size)
            try {
                produced = MultiFrameSrProcessor.run(
                    frames = frames,
                    config = cfg,
                    guard = { bytes -> guardMemory(bytes) },
                    checkpoint = checkpoint,
                    spillDir = dir,
                    onProgress = { done, total -> report(done, total) },
                    batchSizeHint = batch,
                )
            } catch (e: MfsrCancelledException) {
                // 翻译回调度器的取消信号：worker 会把状态标记为 FAILED("cancelled")。
                PLog.i(TAG, "mfsr job cancelled: ${jobName(frames.size)}")
                produced = null
                throw BgJobCancelledException(displayName)
            }
        }
    }

    private fun jobName(frameCount: Int): String = "frames=$frameCount"
}

/**
 * 场景入口的运行时装配点。
 *
 * 补丁只负责在场景条里挂上 [MfsrSceneChipHost]；帧来源与结果去处由集成阶段在这里接线：
 * ```
 * MfsrRuntime.frameProvider = { burstFrames.map { it.bitmap } }   // 例：最近一次连拍的位图
 * MfsrRuntime.resultSink = { bmp -> galleryManager.save(bmp) }    // 例：写入相册
 * ```
 * 未接线时 [isReady] 为 false，UI 会提示"未接入帧源"并禁用执行按钮 —— 绝不崩溃。
 */
object MfsrRuntime {

    private const val TAG = "MfsrRuntime"

    /** 帧来源（顺序即采集顺序，第 0 帧为参考帧）。 */
    @Volatile
    var frameProvider: (() -> List<Bitmap>)? = null

    /** 结果去处（高分辨率 Bitmap）。 */
    @Volatile
    var resultSink: ((Bitmap?) -> Unit)? = null

    /** 落盘根目录（一般传 app 外部缓存目录；null = 不落盘）。 */
    @Volatile
    var cacheRoot: File? = null

    /** 最近一次任务（UI 可读 state 显示进度）。 */
    @Volatile
    var lastJob: BgJob? = null
        private set

    /** 默认配置（可由设置页/持久化覆盖）。 */
    @Volatile
    var defaultConfig: MfsrConfig = MfsrConfig()

    fun isReady(): Boolean = frameProvider != null

    /**
     * 用当前配置发起一次超分。返回 false 表示条件不足（未接帧源 / 无帧），并已写日志。
     */
    fun submit(config: MfsrConfig = defaultConfig): Boolean {
        val provider = frameProvider ?: run {
            PLog.w(TAG, "mfsr skipped: frameProvider not wired")
            return false
        }
        val frames = runCatching { provider() }.getOrElse { e ->
            PLog.w(TAG, "mfsr frameProvider failed: ${e.message}", e)
            return false
        }
        if (frames.isEmpty()) {
            PLog.w(TAG, "mfsr skipped: no frames")
            return false
        }
        val job = MultiFrameSrTask.submit(
            frames = frames,
            config = config,
            cacheRoot = cacheRoot,
            onResult = { bmp -> resultSink?.invoke(bmp) },
        )
        lastJob = job
        return true
    }
}

/**
 * MFSR 配置持久化（SharedPreferences）。
 *
 * key 由 [MfsrConfigCodec] 统一定义；读取时缺失/损坏值一律回落默认并 clamp，
 * 因此外部写入脏数据不会导致崩溃。
 */
object MfsrPreferences {

    const val PREFS_NAME = "mfsr_config"

    fun load(prefs: SharedPreferences): MfsrConfig {
        val all = HashMap<String, String?>()
        for ((k, v) in prefs.all) all[k] = v?.toString()
        return MfsrConfigCodec.decode(all)
    }

    fun save(prefs: SharedPreferences, config: MfsrConfig) {
        val editor = prefs.edit()
        for ((k, v) in MfsrConfigCodec.encode(config)) editor.putString(k, v)
        editor.apply()
    }

    /** 用配置里的输入上限生成一次任务可用的落盘目录（可能为 null = 不落盘）。 */
    fun spillDirFor(cacheRoot: File?): File? =
        cacheRoot?.let { File(it, MultiFrameSrTask.SPILL_DIR_NAME) }
}
