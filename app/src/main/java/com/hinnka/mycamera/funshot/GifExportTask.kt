package com.hinnka.mycamera.funshot

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/** 把 [BgJob] 的分片让出 / 内存守卫适配成 funshot 的回调（与全景任务同款）。 */
internal class GifJobBridge(private val job: BgJob) : FunshotCheckpoint, FunshotMemoryGuard {

    override fun checkpoint() {
        job.checkYield()
    }

    override fun allow(requiredBytes: Long): Boolean = job.guardMemory(requiredBytes)
}

/** 视频源（由宿主提供 Uri 与元数据；不在此包内做媒体选择）。 */
data class FunVideoSource(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

/** `MediaMetadataRetriever` 抽帧器（Android 侧实现；取不到帧返回 null 由上层优雅跳过）。 */
class RetrieverVideoFrameGrabber(private val retriever: MediaMetadataRetriever) : FunVideoFrameGrabber {

    override fun frameAt(timestampMs: Long, outWidth: Int, outHeight: Int): IntArray? {
        if (outWidth <= 0 || outHeight <= 0) return null
        val bitmap = runCatching {
            retriever.getFrameAtTime(timestampMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null
        var scaled: Bitmap? = null
        return try {
            val source = if (bitmap.width != outWidth || bitmap.height != outHeight) {
                Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true).also { scaled = it }
            } else {
                bitmap
            }
            val pixels = IntArray(outWidth * outHeight)
            source.getPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
            pixels
        } catch (error: Throwable) {
            PLog.w(TAG, "frame grab failed at ${timestampMs}ms: ${error.message}")
            null
        } finally {
            runCatching { scaled?.recycle() }
            runCatching { bitmap.recycle() }
        }
    }

    companion object {
        private const val TAG = "RetrieverVideoFrameGrabber"
    }
}

/**
 * GIF / 视频转 GIF 的后台任务。
 *
 * 走 [BgTaskType.GIF_EXPORT] 队列：分片 + [BgJob.checkYield] + [BgJob.guardMemory] + [BgJob.batchSizeFor]；
 * 帧数超出配置上限时**均匀抽样**而不是报错（不硬性写死帧数上限，见 PATTERN_SPEC §1）。
 */
object FunshotGifTask {

    const val JOB_NAME_FRAMES = "GIF 导出"
    const val JOB_NAME_VIDEO = "视频转 GIF"
    const val FRAME_CHUNK = 8

    /** burst / LivePhoto 帧序列 → GIF。 */
    fun submitFrames(
        frames: List<Bitmap>,
        config: GifConfig,
        priority: Int = BgJobPriority.NORMAL,
        onResult: ((FunGifEncodeResult?) -> Unit)? = null,
        onFinish: (() -> Unit)? = null,
    ): BgJob {
        val snapshot = frames.toList()
        val effective = config.coerceWithReport().value
        return BgJobRunner.submit(
            name = JOB_NAME_FRAMES,
            chunkSize = FRAME_CHUNK,
            type = BgTaskType.GIF_EXPORT,
            priority = priority,
            onFinish = { onFinish?.invoke() },
        ) { job ->
            val bridge = GifJobBridge(job)
            job.report(0f, "准备帧序列")
            val argbFrames = ArrayList<FunArgbFrame>(snapshot.size)
            var converted = 0
            for (bitmap in snapshot) {
                if (bitmap.isRecycled) continue
                argbFrames += FunshotBitmaps.toArgbFrame(bitmap)
                converted++
                if (converted % job.batchSizeFor(snapshot.size.coerceAtLeast(1)) == 0) {
                    job.report(converted.toFloat() / snapshot.size, "转换 $converted/${snapshot.size}")
                    job.checkYield()
                    if (!job.guardMemory(FunGifPipeline.estimateBytes(bitmap.width, bitmap.height, argbFrames.size))) {
                        PLog.w(TAG, "memory guard rejects full frame list, pipeline will degrade")
                    }
                }
            }
            job.report(0.5f, "编码 GIF")
            val result = FunGifPipeline.encode(argbFrames, effective, bridge, bridge)
            job.report(1f, "完成 ${result.width}x${result.height} / ${result.frameCount} 帧")
            if (result.isEmpty) {
                PLog.w(TAG, "gif encode produced empty result: ${result.notes}")
                onResult?.invoke(null)
            } else {
                onResult?.invoke(result)
            }
        }
    }

    /** 视频文件 → 抽帧 → GIF。 */
    fun submitVideo(
        context: Context,
        source: FunVideoSource,
        config: GifConfig,
        priority: Int = BgJobPriority.NORMAL,
        onResult: ((FunGifEncodeResult?) -> Unit)? = null,
        onFinish: (() -> Unit)? = null,
    ): BgJob {
        val effective = config.coerceWithReport().value
        return BgJobRunner.submit(
            name = JOB_NAME_VIDEO,
            chunkSize = FRAME_CHUNK,
            type = BgTaskType.GIF_EXPORT,
            priority = priority,
            onFinish = { onFinish?.invoke() },
        ) { job ->
            val bridge = GifJobBridge(job)
            val retriever = MediaMetadataRetriever()
            try {
                job.report(0f, "读取视频")
                retriever.setDataSource(context, source.uri)
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    ?: source.width
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    ?: source.height
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: source.durationMs
                // 旋转 90/270 时宽高互换，避免生成被拉伸的 GIF。
                val layoutWidth = if (rotation % 180 == 90) height else width
                val layoutHeight = if (rotation % 180 == 90) width else height

                val plan = FunVideoFrameSampler.plan(layoutWidth, layoutHeight, duration, effective)
                if (plan.isEmpty) {
                    PLog.w(TAG, "video frame plan empty: ${plan.error}")
                    onResult?.invoke(null)
                    return@submit
                }

                val grabber = RetrieverVideoFrameGrabber(retriever)
                val argbFrames = ArrayList<FunArgbFrame>(plan.size)
                val delays = ArrayList<Int>(plan.size)
                var grabbed = 0
                for (request in plan.frames) {
                    val pixels = grabber.frameAt(request.timestampMs, request.outWidth, request.outHeight)
                    if (pixels == null) {
                        PLog.w(TAG, "frame at ${request.timestampMs}ms unavailable, skipped")
                    } else {
                        argbFrames += FunArgbFrame(request.outWidth, request.outHeight, pixels)
                        delays += request.delayCentis
                    }
                    grabbed++
                    if (!job.guardMemory(
                            FunGifPipeline.estimateBytes(request.outWidth, request.outHeight, argbFrames.size),
                        ) &&
                        argbFrames.isNotEmpty()
                    ) {
                        PLog.w(TAG, "memory guard stops sampling at ${argbFrames.size} frames")
                        break
                    }
                    job.report(grabbed.toFloat() / plan.size * 0.5f, "抽帧 $grabbed/${plan.size}")
                    job.checkYield()
                }
                if (argbFrames.isEmpty()) {
                    PLog.w(TAG, "no frames grabbed from video")
                    onResult?.invoke(null)
                    return@submit
                }
                job.report(0.5f, "编码 GIF")
                val result = FunGifPipeline.encode(argbFrames, effective, bridge, bridge, delays)
                job.report(1f, "完成 ${result.width}x${result.height} / ${result.frameCount} 帧")
                onResult?.invoke(if (result.isEmpty) null else result)
            } catch (error: Throwable) {
                PLog.w(TAG, "video to gif failed: ${error.message}", error)
                onResult?.invoke(null)
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private const val TAG = "FunshotGifTask"
}

/** GIF 接线点（宿主提供帧来源 / 视频来源 / 结果落盘回调）。 */
object GifRuntime {

    @Volatile
    var config: GifConfig = GifConfig()

    /** burst / LivePhoto 帧提供者（返回帧序列；无则返回空表）。 */
    @Volatile
    var burstFrameProvider: (() -> List<Bitmap>)? = null

    /** 当前待转换视频（通常来自相册选择或刚录制的视频）。 */
    @Volatile
    var videoSource: FunVideoSource? = null

    /** 编码结果落盘（写文件 / 分享 / 贴纸库）；未接线时结果仅在回调返回。 */
    @Volatile
    var resultSink: ((FunGifEncodeResult, String) -> Unit)? = null

    val isBurstReady: Boolean get() = burstFrameProvider != null
    val isVideoReady: Boolean get() = videoSource != null

    fun exportFromBurst(onFinish: (() -> Unit)? = null): BgJob? {
        val provider = burstFrameProvider ?: run {
            PLog.w("GifRuntime", "burst frame provider not attached")
            return null
        }
        val frames = runCatching { provider() }.getOrDefault(emptyList())
        if (frames.isEmpty()) {
            PLog.w("GifRuntime", "burst frame provider returned no frames")
            return null
        }
        return FunshotGifTask.submitFrames(frames, config, BgJobPriority.NORMAL, { result ->
            deliver(result, FunshotGifTask.JOB_NAME_FRAMES)
        }, onFinish)
    }

    fun exportFromVideo(context: Context, onFinish: (() -> Unit)? = null): BgJob? {
        val source = videoSource ?: run {
            PLog.w("GifRuntime", "video source not set")
            return null
        }
        return FunshotGifTask.submitVideo(context.applicationContext, source, config, BgJobPriority.NORMAL, { result ->
            deliver(result, FunshotGifTask.JOB_NAME_VIDEO)
        }, onFinish)
    }

    private fun deliver(result: FunGifEncodeResult?, name: String) {
        if (result == null) {
            PLog.w("GifRuntime", "gif export produced no result")
            return
        }
        val sink = resultSink
        if (sink == null) {
            PLog.w("GifRuntime", "result sink not attached, dropping ${result.bytes.size} bytes")
            return
        }
        runCatching { sink(result, name) }
            .onFailure { PLog.w("GifRuntime", "result sink failed: ${it.message}", it) }
    }
}

/** GIF 配置持久化（`funshot_config`）。 */
object GifPreferences {

    private const val PREFS_NAME = "funshot_config"

    fun load(context: Context): GifConfig {
        val prefs = prefs(context)
        return GifConfigCodec.decode { key -> prefs.getString(key, null) }.value
    }

    fun save(context: Context, config: GifConfig) {
        val effective = config.coerceWithReport().value
        prefs(context).edit().apply {
            GifConfigCodec.encode(effective).forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
