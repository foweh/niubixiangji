package com.hinnka.mycamera.funshot

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/**
 * 把 [BgJob] 的 `checkYield()` / `guardMemory()` 适配成算法层接口（PATTERN_SPEC §4）。
 *
 * - [checkpoint] → `job.checkYield()`：响应暂停/取消 + 节流 + 让出时间片；
 * - [allow] → `job.guardMemory(bytes)`：不通过时算法层**降级**（降工作分辨率/降输出分辨率）。
 */
internal class PanoramaJobBridge(private val job: BgJob) : FunshotCheckpoint, FunshotMemoryGuard {

    override fun checkpoint() {
        job.checkYield()
    }

    override fun allow(requiredBytes: Long): Boolean = job.guardMemory(requiredBytes)
}

/**
 * 全景拼接后台任务入口。
 *
 * 统一走 `BgJobRunner.submit(type = BgTaskType.PANORAMA, priority = LOW)`，串行排队慢慢跑，
 * 不抢前台拍照/预览；分片粒度用 `job.batchSizeFor()`，每个分片末尾 `checkYield()`。
 *
 * > ⚠️ 依赖补丁新增的枚举值 `BgTaskType.PANORAMA`（见 `delivery/patches/funshot_patch.md`）。
 */
object FunshotPanoramaTask {

    private const val TAG = "FunshotPanoramaTask"
    const val JOB_NAME = "全景拼接"
    private const val FRAME_CHUNK = 4

    /**
     * @param frames 输入帧（开放 2 ~ 100，由 [PanoramaConfig.frameCount] clamp；不做硬性拒绝）
     * @param onResult 结果回调（**后台线程**执行；null 表示拼接失败或无结果）
     * @param onFinish 任务结束回调（成功/失败/取消均触发，后台线程）
     */
    fun submit(
        frames: List<Bitmap>,
        config: PanoramaConfig = PanoramaConfig(),
        priority: Int = BgJobPriority.LOW,
        onResult: ((Bitmap?) -> Unit)? = null,
        onFinish: ((BgJob) -> Unit)? = null,
    ): BgJob {
        val effective = config.coerced()
        return BgJobRunner.submit(
            name = JOB_NAME,
            chunkSize = FRAME_CHUNK,
            type = BgTaskType.PANORAMA,
            priority = priority,
            onFinish = onFinish,
        ) {
            val bridge = PanoramaJobBridge(this)
            val session = FunshotPanoramaSession(effective, bridge, bridge)
            val total = frames.size
            val batch = batchSizeFor(total).coerceAtLeast(1)
            var index = 0
            while (index < total) {
                val end = minOf(index + batch, total)
                for (i in index until end) {
                    val bitmap = frames[i]
                    if (bitmap.isRecycled) continue
                    session.ingest(FunshotBitmaps.toArgbFrame(bitmap))
                }
                report(end, total)
                checkYield()
                index = end
            }
            val result = session.finish()
            PLog.i(
                TAG,
                "panorama done: ${result.width}x${result.height} used=${result.usedFrames} " +
                    "dropped=${result.droppedFrames} degraded=${result.degraded} notes=${result.notes}",
            )
            onResult?.invoke(FunshotBitmaps.toBitmap(result))
        }
    }
}

/**
 * 全景功能的接线点（宿主注入帧源与结果落盘，避免本包硬依赖 `viewmodel` / `gallery`）。
 *
 * 与 `multiframesr/MfsrRuntime` 同款模式：未接线时 `isReady == false`，UI 灰化禁用，不崩溃。
 */
object PanoramaRuntime {

    /** 最近一次连拍/多帧的帧源；返回空列表或抛异常都会被当作「无输入」。 */
    @Volatile
    var frameProvider: (() -> List<Bitmap>)? = null

    /** 结果落盘（例如 `GalleryManager.saveBitmapPhoto`）；后台线程回调。 */
    @Volatile
    var resultSink: ((Bitmap) -> Unit)? = null

    @Volatile
    var config: PanoramaConfig = PanoramaConfig()

    val isReady: Boolean get() = frameProvider != null

    /** 便捷入口：取帧 → 提交后台任务 → 结果交给 [resultSink]。 */
    fun startFromProvidedFrames(priority: Int = BgJobPriority.LOW): BgJob? {
        val provider = frameProvider ?: run {
            PLog.w("PanoramaRuntime", "frameProvider not wired, panorama skipped")
            return null
        }
        val frames = runCatching { provider() }.getOrElse { error ->
            PLog.w("PanoramaRuntime", "frameProvider failed: ${error.message}", error)
            emptyList()
        }
        if (frames.isEmpty()) {
            PLog.w("PanoramaRuntime", "no frames available for panorama")
            return null
        }
        return FunshotPanoramaTask.submit(
            frames = frames,
            config = config,
            priority = priority,
            onResult = { bitmap -> bitmap?.let { resultSink?.invoke(it) } },
        )
    }
}

/** 全景配置持久化（`SharedPreferences("funshot_config")` 上的 `funshot_pano_*` key）。 */
object PanoramaPreferences {

    fun load(preferences: SharedPreferences): FunshotClampReport<PanoramaConfig> {
        val report = PanoramaConfigCodec.decode { key -> preferences.getString(key, null) }
        if (report.changed) {
            PLog.i("PanoramaPreferences", "clamped on load: ${report.fixed}")
        }
        return report
    }

    fun save(preferences: SharedPreferences, config: PanoramaConfig) {
        val effective = config.coerced()
        preferences.edit().apply {
            PanoramaConfigCodec.encode(effective).forEach { (key, value) -> putString(key, value) }
        }.apply()
    }
}
