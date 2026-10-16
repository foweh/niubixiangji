package com.hinnka.mycamera.ui.galleryedit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.gallery.calculateBitmapDecodeTarget
import com.hinnka.mycamera.galleryedit.GeBitmapEditPipeline
import com.hinnka.mycamera.galleryedit.GeCompareConfig
import com.hinnka.mycamera.galleryedit.GeCompareExporter
import com.hinnka.mycamera.galleryedit.GeCropMath
import com.hinnka.mycamera.galleryedit.GeCropRatioSpec
import com.hinnka.mycamera.galleryedit.GeCropRect
import com.hinnka.mycamera.galleryedit.GeDateStampConfig
import com.hinnka.mycamera.galleryedit.GeEditRanges
import com.hinnka.mycamera.galleryedit.GeImageEditState
import com.hinnka.mycamera.galleryedit.GeMediaExporter
import com.hinnka.mycamera.galleryedit.GeMediaType
import com.hinnka.mycamera.galleryedit.GePixelAdjustParams
import com.hinnka.mycamera.galleryedit.GeShareRequest
import com.hinnka.mycamera.galleryedit.GeShareTarget
import com.hinnka.mycamera.galleryedit.GeSize
import com.hinnka.mycamera.galleryedit.GeVideoBookmark
import com.hinnka.mycamera.galleryedit.GeVideoEditRunner
import com.hinnka.mycamera.galleryedit.GeVideoEditSpec
import com.hinnka.mycamera.galleryedit.GeVideoSegment
import com.hinnka.mycamera.galleryedit.GeVideoTrimRange
import com.hinnka.mycamera.galleryedit.GeWatermarkConfig
import com.hinnka.mycamera.galleryedit.GeWatermarkPlacement
import com.hinnka.mycamera.galleryedit.GeShareKit
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "GeGalleryEditVM"

/**
 * 面板提示代码（**不放文案**：文案由 UI 层 `stringResource` 提供，保证可多语言化）。
 */
enum class GeEditMessage {
    NONE,
    NO_SOURCE_IMAGE,
    NOTHING_TO_COMPARE,
    EXPORT_DONE,
    EXPORT_FAILED,
    SHARE_UNAVAILABLE,
    SEGMENT_LIMIT_REACHED,
    VIDEO_SHARE_NEEDS_EXPORT,
}

/**
 * 编辑面板的完整 UI 状态。
 *
 * 所有可调项都来自 [com.hinnka.mycamera.galleryedit] 的开放配置对象（默认值 + 范围 + clamp），
 * UI 只做"改数字"，越界收敛由纯逻辑层完成。
 */
data class GeGalleryEditUiState(
    val mediaType: GeMediaType = GeMediaType.IMAGE,
    val mediaUri: String = "",
    val loading: Boolean = true,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val sourceDurationMs: Long = 0L,
    val exifTakenMillis: Long? = null,
    val image: GeImageEditState = GeImageEditState(),
    val video: GeVideoEditSpec = GeVideoEditSpec(),
    val compare: GeCompareConfig = GeCompareConfig(),
    val showCompare: Boolean = false,
    val shareApplyEdits: Boolean = true,
    val exporting: Boolean = false,
    val exportProgress: Int = 0,
    val previewBefore: Bitmap? = null,
    val previewAfter: Bitmap? = null,
    val message: GeEditMessage = GeEditMessage.NONE,
) {
    val isVideo: Boolean get() = mediaType.isVideo

    /** 源图尺寸（实际解码尺寸）。 */
    val sourceSize: GeSize get() = GeSize(sourceWidth, sourceHeight)
}

/**
 * 画廊快速编辑的界面状态与动作（Compose 面板的唯一数据源）。
 *
 * 重活全部走 [BgJobRunner]（图片走 `GENERIC`，视频走补丁新增的 `VIDEO_EDIT`），
 * 预览走降采样位图（长边 [PREVIEW_MAX_EDGE]），保证 64MP 原图也不 OOM。
 */
class GeGalleryEditViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(GeGalleryEditUiState())
    val state: StateFlow<GeGalleryEditUiState> = _state.asStateFlow()

    private var previewRevision = 0L

    /** 打开编辑面板：加载预览图与媒体元数据。 */
    fun open(mediaUri: String, mediaType: GeMediaType) {
        _state.value = _state.value.copy(
            mediaUri = mediaUri,
            mediaType = mediaType,
            loading = true,
            video = GeVideoEditSpec(
                segments = listOf(GeVideoSegment(uriString = mediaUri, trim = GeVideoTrimRange.FULL)),
                keepAudio = true,
            ),
        )
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (mediaType.isVideo) {
                val duration = withContext(Dispatchers.IO) {
                    GeVideoEditRunner.resolveDurationMs(context, mediaUri)
                }
                _state.value = _state.value.copy(
                    loading = false,
                    sourceDurationMs = duration,
                    video = _state.value.video.clamp().first.let { spec ->
                        spec.copy(
                            segments = spec.segments.map { it.copy(sourceDurationMs = duration) },
                        )
                    },
                )
            } else {
                val decoded = withContext(Dispatchers.IO) { decodePreview(context, mediaUri) }
                val exifMillis = withContext(Dispatchers.IO) { readExifTakenMillis(context, mediaUri) }
                _state.value = _state.value.copy(
                    loading = false,
                    sourceWidth = decoded?.width ?: 0,
                    sourceHeight = decoded?.height ?: 0,
                    exifTakenMillis = exifMillis,
                    previewBefore = decoded,
                    previewAfter = decoded,
                    image = _state.value.image.copy(dateStampMillis = exifMillis),
                )
                // 初始裁剪框按当前比例居中
                resetCropToRatio(_state.value.image)
            }
        }
    }

    // ---------------- 像素调节 ----------------

    fun setBrightness(value: Int) = updatePixel { it.copy(brightness = value) }
    fun setContrast(value: Int) = updatePixel { it.copy(contrast = value) }
    fun setSaturation(value: Int) = updatePixel { it.copy(saturation = value) }
    fun setTemperature(value: Int) = updatePixel { it.copy(temperature = value) }

    private fun updatePixel(transform: (GePixelAdjustParams) -> GePixelAdjustParams) {
        updateImage { state ->
            state.copy(pixel = transform(state.pixel).clamp().first)
        }
    }

    // ---------------- 裁剪 / 旋转 ----------------

    fun setCropRatio(spec: GeCropRatioSpec) {
        currentCropRatio = spec.clamp().first
        resetCropToRatio(_state.value.image)
    }

    /** 当前裁剪比例规格（默认自由）。 */
    var currentCropRatio: GeCropRatioSpec = GeCropRatioSpec.FREE
        private set

    fun setCropRect(rect: GeCropRect) {
        val sourceSize = _state.value.sourceSize
        _state.value = _state.value.copy(
            image = _state.value.image.copy(
                crop = GeCropMath.settle(rect, currentCropRatio, sourceSize),
            ),
        )
        refreshPreview()
    }

    /** 旋转 90° 步进（档位归一化复用既有 PostEditGeometry）。 */
    fun rotateBy(deltaDegrees: Int) {
        updateImage { state -> state.copy(rotationDegrees = state.rotationDegrees + deltaDegrees) }
    }

    /** 按当前比例在源图内居中生成最大裁剪框。 */
    private fun resetCropToRatio(state: GeImageEditState) {
        val size = _state.value.sourceSize
        if (!size.isValid) return
        val rect = GeCropMath.fitRatioCentered(size, currentCropRatio)
        _state.value = _state.value.copy(image = state.copy(crop = rect))
        refreshPreview()
    }

    // ---------------- 水印 / 日期戳 ----------------

    fun setWatermarkEnabled(enabled: Boolean) = updateWatermark { it.copy(enabled = enabled) }
    fun setWatermarkText(text: String) = updateWatermark { it.copy(text = text) }
    fun setWatermarkFontSize(sizeSp: Float) = updateWatermark { it.copy(fontSizeSp = sizeSp) }
    fun setWatermarkPlacement(placement: GeWatermarkPlacement) = updateWatermark { it.copy(placement = placement) }
    fun setWatermarkOpacity(opacity: Float) = updateWatermark { it.copy(opacity = opacity) }
    fun setWatermarkRotation(degrees: Float) = updateWatermark { it.copy(rotationDegrees = degrees) }
    fun setWatermarkTileSpacing(factor: Float) = updateWatermark { it.copy(tileSpacingFactor = factor) }

    private fun updateWatermark(transform: (GeWatermarkConfig) -> GeWatermarkConfig) {
        updateImage { state -> state.copy(watermark = transform(state.watermark).clamp().first) }
    }

    fun setDateStampEnabled(enabled: Boolean) = updateDateStamp { it.copy(enabled = enabled) }
    fun setDateStampPattern(pattern: String) = updateDateStamp { it.copy(pattern = pattern) }
    fun setDateStampPlacement(placement: GeWatermarkPlacement) = updateDateStamp { it.copy(placement = placement) }
    fun setDateStampFontSize(sizeSp: Float) = updateDateStamp { it.copy(fontSizeSp = sizeSp) }

    private fun updateDateStamp(transform: (GeDateStampConfig) -> GeDateStampConfig) {
        updateImage { state -> state.copy(dateStamp = transform(state.dateStamp).clamp().first) }
    }

    // ---------------- 视频 ----------------

    fun setVideoTrim(startMs: Long, endMs: Long?) {
        _state.value = _state.value.copy(
            video = _state.value.video.copy(
                segments = _state.value.video.segments.map { segment ->
                    segment.copy(
                        trim = GeVideoTrimRange(startMs, endMs).let { range ->
                            com.hinnka.mycamera.galleryedit.GeTrimMath
                                .clamp(range, segment.sourceDurationMs, GeEditRanges.VIDEO_TRIM_MIN_DURATION_MS)
                                .first
                        },
                    )
                },
            ).clamp().first,
        )
    }

    fun setVideoSpeed(speed: Float) {
        _state.value = _state.value.copy(video = _state.value.video.copy(speed = speed).clamp().first)
    }

    fun setVideoRotation(degrees: Int) {
        _state.value = _state.value.copy(
            video = _state.value.video.copy(rotationDegrees = degrees).clamp().first,
        )
    }

    fun setVideoKeepAudio(keep: Boolean) {
        _state.value = _state.value.copy(video = _state.value.video.copy(keepAudio = keep))
    }

    fun setVideoApplyColor(apply: Boolean) {
        _state.value = _state.value.copy(video = _state.value.video.copy(applyColorPipeline = apply))
    }

    fun setVideoBookmarks(bookmarks: List<GeVideoBookmark>) {
        _state.value = _state.value.copy(
            video = _state.value.video.copy(bookmarks = bookmarks).clamp().first,
        )
    }

    /** 在当前时间点加书签（去重/排序/上限由纯逻辑层保证）。 */
    fun addVideoBookmark(timeMs: Long) {
        val video = _state.value.video
        val normalized = com.hinnka.mycamera.galleryedit.GeBookmarkMath.add(
            bookmarks = video.bookmarks,
            bookmark = GeVideoBookmark(timeMs = timeMs),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = _state.value.sourceDurationMs,
        ).first
        _state.value = _state.value.copy(video = video.copy(bookmarks = normalized))
    }

    fun addVideoSegment(uriString: String) {
        val video = _state.value.video
        if (video.segments.size >= GeEditRanges.VIDEO_SEGMENT_MAX) {
            setMessage(GeEditMessage.SEGMENT_LIMIT_REACHED)
            return
        }
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) {
                GeVideoEditRunner.resolveDurationMs(getApplication(), uriString)
            }
            _state.value = _state.value.copy(
                video = _state.value.video.copy(
                    segments = video.segments + GeVideoSegment(uriString, GeVideoTrimRange.FULL, duration),
                ),
            )
        }
    }

    fun removeVideoSegment(index: Int) {
        val video = _state.value.video
        if (video.segments.size <= 1) return
        _state.value = _state.value.copy(
            video = video.copy(segments = video.segments.filterIndexed { i, _ -> i != index }),
        )
    }

    // ---------------- 前后对比 ----------------

    fun setShowCompare(show: Boolean) {
        _state.value = _state.value.copy(showCompare = show)
    }

    fun setCompareSplit(split: Float) {
        _state.value = _state.value.copy(compare = _state.value.compare.copy(split = split).clamp().first)
    }

    /** 整份对比配置（分割位置 / 排列方向 / 输出模式 / 标签 / 间距）。 */
    fun setCompareConfig(config: GeCompareConfig) {
        _state.value = _state.value.copy(compare = config.clamp().first)
    }

    // ---------------- 分享 ----------------

    fun setShareApplyEdits(apply: Boolean) {
        _state.value = _state.value.copy(shareApplyEdits = apply)
    }

    /** 一键分享（文本 / 图片 / 视频），可选择是否套用当前编辑。 */
    fun share(chooserTitle: String?) {
        val current = _state.value
        val context = getApplication<Application>()
        viewModelScope.launch {
            val uris = when {
                current.isVideo -> withContext(Dispatchers.IO) { exportVideoToShare(context) }
                current.shareApplyEdits -> withContext(Dispatchers.IO) { exportImageToShare(context) }
                else -> listOf(current.mediaUri)
            }.filterNotNull()
            val request = GeShareRequest(
                target = if (current.isVideo) GeShareTarget.VIDEO else GeShareTarget.IMAGE,
                mediaUris = uris,
                applyEdits = current.shareApplyEdits,
            )
            if (!GeShareKit.share(context, request, chooserTitle)) {
                setMessage(GeEditMessage.SHARE_UNAVAILABLE)
            }
        }
    }

    // ---------------- 导出 ----------------

    /** 导出编辑结果到相册（水印/日期戳永久写入像素）。 */
    fun exportToGallery() {
        val current = _state.value
        if (current.exporting) return
        if (current.isVideo) {
            exportVideoToGallery()
        } else {
            exportImageToGallery()
        }
    }

    private fun exportImageToGallery() {
        val current = _state.value
        val source = current.previewBefore
        if (source == null) {
            setMessage(GeEditMessage.NO_SOURCE_IMAGE)
            return
        }
        _state.value = current.copy(exporting = true, exportProgress = 0)
        BgJobRunner.submit(
            name = "画廊编辑导出",
            chunkSize = current.image.pipelineConfig.stripHeight,
            type = BgTaskType.GENERIC,
        ) { job ->
            val full = decodeFullResolution(getApplication(), current.mediaUri)
            if (full == null) {
                postExportResult(null)
                return@submit
            }
            val rendered = GeBitmapEditPipeline.renderChunked(
                job = job,
                source = full,
                state = current.image,
                density = density(),
            )
            if (rendered == null) {
                postExportResult(null)
                return@submit
            }
            val uri = kotlinx.coroutines.runBlocking {
                GeMediaExporter.saveImageToGallery(
                    context = getApplication(),
                    bitmap = rendered,
                    displayName = buildImageFileName(),
                )
            }
            postExportResult(uri)
        }
    }

    private fun exportVideoToGallery() {
        val current = _state.value
        val outputFile = File(getApplication<Application>().cacheDir, "video_edit/ge_${System.nanoTime()}.mp4")
        _state.value = current.copy(exporting = true, exportProgress = 0)
        val job = GeVideoEditRunner.submit(
            context = getApplication(),
            spec = current.video,
            outputFile = outputFile,
            onProgress = { progress ->
                _state.value = _state.value.copy(exporting = true, exportProgress = progress)
            },
            onFinished = { file ->
                val uri = if (file == null) {
                    null
                } else {
                    kotlinx.coroutines.runBlocking {
                        GeMediaExporter.saveVideoToGallery(
                            context = getApplication(),
                            sourceFile = file,
                            displayName = buildVideoFileName(),
                        )
                    }
                }
                postExportResult(uri)
            },
        )
        if (job == null) {
            // 片段不可用或 SDK 不支持时 submit 直接返回 null，避免 UI 卡在"导出中"
            postExportResult(null)
        }
    }

    /** 导出前后对比图（编辑面板的"导出对比图"按钮）。 */
    fun exportCompareImage(beforeLabel: String?, afterLabel: String?) {
        val current = _state.value
        val before = current.previewBefore
        val after = current.previewAfter
        if (before == null || after == null) {
            setMessage(GeEditMessage.NOTHING_TO_COMPARE)
            return
        }
        _state.value = current.copy(exporting = true, exportProgress = 0)
        viewModelScope.launch {
            val composed = withContext(Dispatchers.Default) {
                val scale = GeCompareExporter.fitOutputScale(before, after)
                GeCompareExporter.build(
                    before = GeCompareExporter.scaleForOutput(before, scale),
                    after = GeCompareExporter.scaleForOutput(after, scale),
                    config = current.compare,
                    beforeLabel = beforeLabel,
                    afterLabel = afterLabel,
                    density = density(),
                )
            }
            if (composed == null) {
                postExportResult(null)
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                GeMediaExporter.saveImageToGallery(
                    context = getApplication(),
                    bitmap = composed,
                    displayName = buildCompareFileName(),
                )
            }
            postExportResult(uri)
        }
    }

    private fun postExportResult(uri: Uri?) {
        _state.value = _state.value.copy(
            exporting = false,
            exportProgress = if (uri != null) 100 else 0,
            message = if (uri == null) GeEditMessage.EXPORT_FAILED else GeEditMessage.EXPORT_DONE,
        )
        PLog.i(TAG, "export result: $uri")
    }

    private suspend fun exportImageToShare(context: Application): List<String?> {
        val current = _state.value
        val full = decodeFullResolution(context, current.mediaUri) ?: current.previewBefore
        val rendered = if (current.shareApplyEdits && full != null) {
            GeBitmapEditPipeline.render(full, current.image, density())
        } else {
            full
        }
        val uri = rendered?.let { GeShareKit.prepareImageUri(context, it, GeShareKit.defaultImageName()) }
        return listOf(uri?.toString())
    }

    private suspend fun exportVideoToShare(context: Application): List<String?> {
        val current = _state.value
        if (current.video.requiresTranscode) {
            setMessage(GeEditMessage.VIDEO_SHARE_NEEDS_EXPORT)
        }
        return listOf(current.mediaUri)
    }

    // ---------------- 预览 ----------------

    private fun updateImage(transform: (GeImageEditState) -> GeImageEditState) {
        val next = transform(_state.value.image)
        _state.value = _state.value.copy(image = next)
        refreshPreview()
    }

    /** 重新渲染预览（降采样，防 OOM）。 */
    private fun refreshPreview() {
        val current = _state.value
        if (current.isVideo) return
        val source = current.previewBefore ?: return
        val revision = ++previewRevision
        viewModelScope.launch {
            val rendered = withContext(Dispatchers.Default) {
                GeBitmapEditPipeline.render(source, current.image, density())
            }
            // 丢弃过期结果，避免快速拖动滑杆时的闪回
            if (revision == previewRevision) {
                _state.value = _state.value.copy(previewAfter = rendered ?: source)
            }
        }
    }

    private fun decodePreview(application: Application, uriString: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            application.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val target = calculateBitmapDecodeTarget(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                maxEdge = PREVIEW_MAX_EDGE,
                maxByteCount = PREVIEW_MAX_BYTES,
                assumedBytesPerPixel = BYTES_PER_PIXEL,
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, target.width, target.height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            application.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: RuntimeException) {
            PLog.w(TAG, "decodePreview failed: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            PLog.w(TAG, "decodePreview io failed: ${e.message}")
            null
        }
    }

    private fun decodeFullResolution(application: Application, uriString: String): Bitmap? {
        return try {
            application.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: RuntimeException) {
            PLog.w(TAG, "decodeFullResolution failed: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            PLog.w(TAG, "decodeFullResolution io failed: ${e.message}")
            null
        }
    }

    private fun readExifTakenMillis(application: Application, uriString: String): Long? {
        return try {
            application.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val exif = ExifInterface(input)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                parseExifDate(raw)
            }
        } catch (e: RuntimeException) {
            PLog.w(TAG, "readExifTakenMillis failed: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            PLog.w(TAG, "readExifTakenMillis io failed: ${e.message}")
            null
        }
    }

    private fun density(): Float =
        getApplication<Application>().resources.displayMetrics.density

    private fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun buildImageFileName(): String =
        "${GeMediaExporter.DEFAULT_IMAGE_PREFIX}_${timestamp()}_edit.jpg"

    private fun buildVideoFileName(): String =
        "${GeMediaExporter.DEFAULT_VIDEO_PREFIX}_${timestamp()}_edit.mp4"

    private fun buildCompareFileName(): String =
        "${GeMediaExporter.DEFAULT_IMAGE_PREFIX}_${timestamp()}_compare.jpg"

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun setMessage(message: GeEditMessage) {
        _state.value = _state.value.copy(message = message)
        PLog.w(TAG, "edit panel message: $message")
    }

    /** 提示消费（UI 展示后调用，避免旋转/重组时重复弹出）。 */
    fun consumeMessage() {
        _state.value = _state.value.copy(message = GeEditMessage.NONE)
    }

    override fun onCleared() {
        super.onCleared()
        // 不主动 recycle：Compose 可能仍在绘制最后一帧，回收会触发 "recycled bitmap" 崩溃。
        // 置空引用交给 GC 即可。
        _state.value = _state.value.copy(previewBefore = null, previewAfter = null)
    }

    companion object {
        /** 预览解码长边上限。 */
        const val PREVIEW_MAX_EDGE = 1600

        /** 预览解码字节预算（约 12MB）。 */
        const val PREVIEW_MAX_BYTES = 12L * 1024 * 1024

        const val BYTES_PER_PIXEL = 4

        private fun parseExifDate(raw: String?): Long? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val patterns = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy:MM:dd")
            for (pattern in patterns) {
                val parsed = runCatching {
                    java.text.SimpleDateFormat(pattern, Locale.US).parse(value)?.time
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return null
        }
    }
}
