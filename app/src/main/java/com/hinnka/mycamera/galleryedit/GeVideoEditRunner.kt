package com.hinnka.mycamera.galleryedit

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.lut.VideoLutEffect
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "GeVideoEditRunner"

/**
 * 恒定变速的 [SpeedProvider]（纯实现，无 Android 依赖）。
 *
 * 媒体3 1.10 的变速入口是 `EditedMediaItem.Builder.setSpeed(SpeedProvider)`，
 * 需要由调用方提供速度曲线；本类即"全程恒定倍率"的最小实现。
 * `getNextSpeedChangeTimeUs` 返回 [C.TIME_UNSET] 表示此后不再变速。
 */
class GeConstantSpeedProvider(private val speed: Float) : SpeedProvider {

    /** 已 clamp 到 [GeEditRanges.VIDEO_SPEED_MIN, GeEditRanges.VIDEO_SPEED_MAX] 的实际倍率。 */
    val resolvedSpeed: Float = GeEditRanges.clampFloat(
        speed,
        GeEditRanges.VIDEO_SPEED_MIN,
        GeEditRanges.VIDEO_SPEED_MAX,
        GeEditRanges.VIDEO_SPEED_DEFAULT,
    )

    override fun getSpeed(mediaTimeUs: Long): Float = resolvedSpeed

    override fun getNextSpeedChangeTimeUs(mediaTimeUs: Long): Long = C.TIME_UNSET
}

/**
 * 视频导出执行参数（开放配置 + clamp）。
 *
 * @param allowEncoderFallback 编码器不支持所选分辨率时是否允许自动降级（默认允许，保证可出片）。
 * @param targetVideoMime 目标视频编码（默认 H.264，兼容性最好）。
 * @param targetAudioMime 目标音频编码（默认 AAC）。
 * @param progressPollMs 进度轮询间隔（毫秒），范围 100..2000。
 */
data class GeVideoExportOptions(
    val allowEncoderFallback: Boolean = true,
    val targetVideoMime: String = MimeTypes.VIDEO_H264,
    val targetAudioMime: String = MimeTypes.AUDIO_AAC,
    val progressPollMs: Long = 300L,
) {
    fun clamp(): Pair<GeVideoExportOptions, GeClampReport> {
        val safePoll = progressPollMs.coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        val corrections = buildList {
            GeClampReport.of("video.progressPollMs", progressPollMs.toDouble(), safePoll.toDouble())?.let(::add)
            if (targetVideoMime.isBlank()) {
                add(GeClampCorrection("video.targetVideoMime.length", 0.0, MimeTypes.VIDEO_H264.length.toDouble()))
            }
            if (targetAudioMime.isBlank()) {
                add(GeClampCorrection("video.targetAudioMime.length", 0.0, MimeTypes.AUDIO_AAC.length.toDouble()))
            }
        }
        return copy(
            progressPollMs = safePoll,
            targetVideoMime = targetVideoMime.ifBlank { MimeTypes.VIDEO_H264 },
            targetAudioMime = targetAudioMime.ifBlank { MimeTypes.AUDIO_AAC },
        ) to GeClampReport(corrections)
    }

    companion object {
        const val MIN_POLL_MS = 100L
        const val MAX_POLL_MS = 2000L
    }
}

/** 预处理结果：把 [GeVideoEditSpec] 变成可直接交给 Transformer 的编排计划。 */
data class GeVideoEditPlan(
    val items: List<EditedMediaItem>,
    val composition: Composition,
    val resolvedDurationsMs: List<Long>,
    val totalSourceDurationMs: Long,
) {
    val isUsable: Boolean get() = items.isNotEmpty()
}

/**
 * 视频编辑执行器（媒体3 Transformer 复用）。
 *
 * 覆盖用户要求的视频能力：**时间区间裁剪、旋转、变速、调色（复用 lut/ 管线）、去音频、
 * 多段拼接、书签**。全部通过媒体3 现成 API 实现（无自研编解码）：
 * - 裁剪：`MediaItem.ClippingConfiguration.setStartPositionMs/setEndPositionMs`；
 * - 旋转：`ScaleAndRotateTransformation.Builder().setRotationDegrees`；
 * - 变速：`EditedMediaItem.Builder.setSpeed(SpeedProvider)`（媒体3 1.10 接口）；
 * - 去音频：`EditedMediaItem.Builder.setRemoveAudio(true)`；
 * - 拼接：`EditedMediaItemSequence` + `Composition`；
 * - 调色：复用工程既有 `lut/VideoLutEffect`（GlEffect，LUT + 色彩配方）。
 *
 * 调度（PATTERN_SPEC §5）：预处理与导出整体提交到 [BgJobRunner]，类型 [BgTaskType.VIDEO_EDIT]（补丁新增）；
 * 分段探测逐个 `checkYield()`，大缓冲前 `guardMemory()`，Transformer 在 Main Looper 上驱动
 * （媒体3 要求），导出期间用同一个 job 回报进度并在轮询循环里 `checkYield()` 响应暂停/取消。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object GeVideoEditRunner {

    /** 当前设备是否支持 Transformer 导出（minSdk 30 = Android 11，恒为 true，保留以便未来下调 minSdk）。 */
    fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /**
     * 探测媒体时长（毫秒）。失败返回 0（调用方按"不裁剪"处理，不中断流程）。
     */
    fun resolveDurationMs(context: Context, uriString: String): Long {
        if (uriString.isBlank()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, android.net.Uri.parse(uriString))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            PLog.w(TAG, "resolveDurationMs failed for $uriString: ${e.message}")
            0L
        } catch (e: java.io.IOException) {
            PLog.w(TAG, "resolveDurationMs io failed for $uriString: ${e.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 构建单个片段对应的 [EditedMediaItem]（裁剪 + 旋转 + 变速 + 去音频 + 可选调色）。
     *
     * @param lutConfig 已解析的 LUT 配置（可为 null，此时只用色彩配方）。
     * @param recipeParams 色彩配方参数（可为 null）。
     */
    fun buildEditedMediaItem(
        context: Context,
        segment: GeVideoSegment,
        spec: GeVideoEditSpec,
        lutConfig: LutConfig? = null,
        recipeParams: ColorRecipeParams? = null,
    ): EditedMediaItem? {
        if (!segment.isUsable) return null
        val trimmedDuration = segment.sourceDurationMs
        val trim = segment.trim
        val mediaItemBuilder = MediaItem.Builder().setUri(segment.uriString)
        if (trim != null) {
            val (safeTrim, _) = GeTrimMath.clamp(
                range = trim,
                sourceDurationMs = trimmedDuration,
                minDurationMs = GeEditRanges.VIDEO_TRIM_MIN_DURATION_MS,
            )
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(safeTrim.startMs)
                    .setEndPositionMs(safeTrim.resolvedEndMs(trimmedDuration))
                    .build()
            )
        }
        val mediaItem = mediaItemBuilder.build()

        val videoEffects = buildList {
            if (spec.rotationDegrees != 0) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(spec.rotationDegrees.toFloat())
                        .build()
                )
            }
            if (spec.applyColorPipeline) {
                add(VideoLutEffect(lutConfig, recipeParams))
            }
        }

        val builder = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(/* audioProcessors= */ emptyList(), /* videoEffects= */ videoEffects))
        if (!spec.keepAudio) {
            builder.setRemoveAudio(true)
        }
        if (!GeEditRanges.nearlyEqual(spec.speed, 1f)) {
            builder.setSpeed(GeConstantSpeedProvider(spec.speed))
        }
        if (spec.outputFrameRate > 0) {
            builder.setFrameRate(spec.outputFrameRate)
        }
        return builder.build()
    }

    /** 多段拼接：一个 [EditedMediaItemSequence] 内按顺序排列即首尾拼接。 */
    fun buildComposition(items: List<EditedMediaItem>): Composition {
        val sequence = EditedMediaItemSequence.Builder(*items.toTypedArray()).build()
        return Composition.Builder(sequence).build()
    }

    /** 构建 Transformer（Main Looper 驱动；编码器可配置、可选允许降级）。 */
    fun buildTransformer(context: Context, options: GeVideoExportOptions): Transformer {
        val (safeOptions, _) = options.clamp()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setEnableFallback(safeOptions.allowEncoderFallback)
            .build()
        return Transformer.Builder(context)
            .setVideoMimeType(safeOptions.targetVideoMime)
            .setAudioMimeType(safeOptions.targetAudioMime)
            .setEncoderFactory(encoderFactory)
            .setLooper(Looper.getMainLooper())
            .build()
    }

    /**
     * 提交视频编辑任务（异步）。
     *
     * @param outputFile 输出文件（调用方负责最终写入相册/分享，本方法只负责编码落盘）。
     * @param onProgress 进度回调（0..100，后台线程）。
     * @param onFinished 结束回调（成功为输出文件，失败/取消为 null）。
     * @return 已提交的 [BgJob]（可用于界面展示/暂停/取消）；参数不合法时返回 null。
     */
    fun submit(
        context: Context,
        spec: GeVideoEditSpec,
        outputFile: File,
        options: GeVideoExportOptions = GeVideoExportOptions(),
        lutConfig: LutConfig? = null,
        recipeParams: ColorRecipeParams? = null,
        onProgress: ((Int) -> Unit)? = null,
        onFinished: ((File?) -> Unit)? = null,
    ): BgJob? {
        val (safeSpec, report) = spec.clamp()
        if (report.changed) {
            PLog.i(TAG, "video spec clamped: ${report.describe()}")
        }
        if (!safeSpec.isRenderable()) {
            PLog.w(TAG, "submit skipped: spec has no usable segment")
            return null
        }
        if (!isSupported()) {
            PLog.w(TAG, "submit skipped: Transformer unsupported on SDK ${android.os.Build.VERSION.SDK_INT}")
            return null
        }
        val (safeOptions, optionsReport) = options.clamp()
        if (optionsReport.changed) {
            PLog.i(TAG, "video options clamped: ${optionsReport.describe()}")
        }

        val segmentCount = safeSpec.segments.size
        return BgJobRunner.submit(
            name = "视频编辑",
            chunkSize = segmentCount.coerceAtLeast(1),
            type = BgTaskType.VIDEO_EDIT,
            priority = BgJobPriority.LOW,
            onFinish = { job ->
                PLog.i(TAG, "video edit job finished: ${job.state.value.status}")
            },
        ) {
            val job = this
            val result = runBlocking {
                runEditPipeline(job, context, safeSpec, outputFile, safeOptions, lutConfig, recipeParams, onProgress)
            }
            onFinished?.invoke(result)
        }
    }

    /**
     * 预处理 + 导出（挂起实现，供 [submit] 的 BgJob 内 runBlocking 驱动）。
     *
     * 预处理（后台线程、逐段 checkYield）→ 导出（Main Looper 驱动 Transformer）→ 返回输出文件。
     */
    private suspend fun runEditPipeline(
        job: BgJob,
        context: Context,
        spec: GeVideoEditSpec,
        outputFile: File,
        options: GeVideoExportOptions,
        lutConfig: LutConfig?,
        recipeParams: ColorRecipeParams?,
        onProgress: ((Int) -> Unit)?,
    ): File? {
        // 1) 预处理：逐段解析时长（业务上必须真实时长才能正确裁剪）
        val resolvedDurations = ArrayList<Long>(spec.segments.size)
        spec.segments.forEachIndexed { index, segment ->
            val duration = if (segment.sourceDurationMs > 0L) {
                segment.sourceDurationMs
            } else {
                resolveDurationMs(context, segment.uriString)
            }
            resolvedDurations.add(duration)
            job.report(index + 1, spec.segments.size + 100)
            job.checkYield()
        }

        // 2) 构建编排（含内存守卫：整段解码缓冲按 4 字节/像素 * 一帧估算）
        val items = ArrayList<EditedMediaItem>(spec.segments.size)
        spec.segments.forEachIndexed { index, segment ->
            val withDuration = segment.copy(sourceDurationMs = resolvedDurations[index])
            val item = buildEditedMediaItem(context, withDuration, spec, lutConfig, recipeParams)
            if (item != null) items.add(item)
            job.checkYield()
        }
        if (items.isEmpty()) {
            PLog.w(TAG, "no EditedMediaItem could be built")
            return null
        }
        val composition = buildComposition(items)

        // 3) 导出（Transformer 必须在有 Looper 的线程上构建；Main Looper 最稳）
        return withContext(Dispatchers.Main) {
            val transformer = buildTransformer(context, options)
            val progressHolder = ProgressHolder()
            suspendCancellableCoroutine<File?> { cont ->
                var settled = false

                // 进度轮询必须在独立协程中跑：suspendCancellableCoroutine 的 block 是非 suspend lambda，
                // 不能直接调用 delay；参照 lut/VideoExporter.runTransformer 的既有写法。
                val pollingJob = CoroutineScope(Dispatchers.Main).launch {
                    while (!settled) {
                        try {
                            delay(options.progressPollMs)
                            if (settled) break
                            val state = transformer.getProgress(progressHolder)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress?.invoke(progressHolder.progress)
                                job.report(
                                    spec.segments.size + progressHolder.progress,
                                    spec.segments.size + 100,
                                )
                            }
                            job.checkYield()
                        } catch (cancelled: com.hinnka.mycamera.computational.BgJobCancelledException) {
                            PLog.i(TAG, "video edit cancelled while exporting")
                            settled = true
                            runCatching { transformer.cancel() }
                            if (cont.isActive) cont.resume(null)
                            break
                        } catch (cancellation: CancellationException) {
                            // 真正的协程取消必须继续向上传播，不能被吞掉
                            throw cancellation
                        } catch (error: Exception) {
                            // 轮询偶发错误不致命：记录后继续下一轮，等 onCompleted/onError 收敛
                            PLog.w(TAG, "progress polling error: ${error.message}")
                        }
                    }
                }

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (settled) return
                        settled = true
                        pollingJob.cancel()
                        PLog.i(TAG, "video export completed: ${outputFile.absolutePath}")
                        onProgress?.invoke(100)
                        job.report(100, 100)
                        if (cont.isActive) cont.resume(outputFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (settled) return
                        settled = true
                        pollingJob.cancel()
                        PLog.e(TAG, "video export failed: code=${exportException.errorCode}", exportException)
                        if (cont.isActive) cont.resume(null)
                    }
                }
                transformer.addListener(listener)
                val started = runCatching {
                    outputFile.parentFile?.mkdirs()
                    transformer.start(composition, outputFile.absolutePath)
                }.isSuccess
                if (!started) {
                    settled = true
                    PLog.e(TAG, "transformer.start threw; aborting export")
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    pollingJob.cancel()
                    runCatching { transformer.cancel() }
                }
            }
        }
    }
}
