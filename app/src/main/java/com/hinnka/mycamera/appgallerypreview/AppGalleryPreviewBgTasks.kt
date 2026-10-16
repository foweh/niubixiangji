package com.hinnka.mycamera.appgallerypreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream

/** RAW 预览解码结果：位图 + 曝光元数据 + 是否用到 DNG 内嵌预览。 */
data class AppGalleryPreviewDecodedImage(
    val bitmap: Bitmap,
    val exposure: AppGalleryPreviewRawExposure?,
    val usedEmbeddedPreview: Boolean,
    val sampleSize: Int,
) {
    val size: AppGalleryPreviewSize get() = AppGalleryPreviewSize(bitmap.width, bitmap.height)
}

/**
 * 预览域的重任务后台接入（第 34/36 项）。
 *
 * 全部重计算（大图解码 / RAW 预览渲染 / 直方图 / RAW→JPEG 导出）统一走
 * `computational/BgJobRunner`，遵守性能硬约束：
 *  - 每个分片末尾调用 `job.checkYield()`（响应暂停/取消 + CPU 节流）；
 *  - 大缓冲分配前用 `job.guardMemory(bytes)` 守卫，失败则**降级**（提高采样率 / 降工作分辨率），不失败退出；
 *  - 分批用 `job.batchSizeFor` / 配置的 `bgChunkPixels`；
 *  - **不对总像素数/总帧数设上限**，内存问题用「降采样 + 分片流式累加」解决。
 *
 * 任务类型使用 [BgTaskType.PREVIEW]（由补丁在 BgJobRunner.kt 枚举中新增；见补丁 ENUM 节）。
 *
 * 复用说明：字节读取与 DNG 内嵌缩略图提取复用工程已依赖的
 * `androidx.exifinterface:exifinterface:1.4.2`（Apache-2.0）；解码复用平台
 * `BitmapFactory`。RAW 精细渲染仍由工程既有 `raw/` 包（RawRenderingEngine 等）负责，
 * 本对象只承担"预览级"渲染，避免引入重依赖。
 */
object AppGalleryPreviewBgTasks {

    private const val TAG = "AgpBgTasks"

    /** 需要从源文件复制到导出结果的 EXIF 标签（TIFF/EXIF 标准名，ExifInterface 通用标签名）。 */
    private val EXIF_COPY_TAGS = listOf(
        "Make",
        "Model",
        "DateTime",
        "DateTimeOriginal",
        "ExposureTime",
        "FNumber",
        "FocalLength",
        "ExposureBiasValue",
        "ISOSpeedRatings",
        "Orientation",
    )

    /** 需要读取的元数据标签（与 [AppGalleryPreviewRawMetadataParser] 的常量一致）。 */
    private val METADATA_TAGS = listOf(
        AppGalleryPreviewRawMetadataParser.TAG_EXPOSURE_TIME,
        AppGalleryPreviewRawMetadataParser.TAG_ISO,
        AppGalleryPreviewRawMetadataParser.TAG_PHOTOGRAPHIC_SENSITIVITY,
        AppGalleryPreviewRawMetadataParser.TAG_F_NUMBER,
        AppGalleryPreviewRawMetadataParser.TAG_EXPOSURE_BIAS,
        AppGalleryPreviewRawMetadataParser.TAG_FOCAL_LENGTH,
        AppGalleryPreviewRawMetadataParser.TAG_BASELINE_EXPOSURE,
        AppGalleryPreviewRawMetadataParser.TAG_PIXEL_X_DIMENSION,
        AppGalleryPreviewRawMetadataParser.TAG_PIXEL_Y_DIMENSION,
        AppGalleryPreviewRawMetadataParser.TAG_IMAGE_WIDTH,
        AppGalleryPreviewRawMetadataParser.TAG_IMAGE_LENGTH,
    )

    /**
     * 把本模块的配置下发到 BgJobRunner 的 PREVIEW 类型覆盖（CPU 频控与内存预算可运行时调）。
     * 建议在预览页进入时调用一次。
     */
    fun applyRunnerOverrides(config: AppGalleryPreviewConfig) {
        val safe = config.appGalleryPreviewSafe().config
        BgJobRunner.configure(
            type = BgTaskType.PREVIEW,
            throttleMs = safe.bgThrottleMs.takeIf { it >= 0L },
            maxFrameBatch = safe.bgMaxFrameBatch.takeIf { it > 0 },
            maxHeapBytes = safe.bgMaxHeapMb.takeIf { it > 0 }?.let { it * 1024L * 1024L },
        )
        PLog.i(
            TAG,
            "runner overrides: throttle=${safe.bgThrottleMs} batch=${safe.bgMaxFrameBatch} heapMb=${safe.bgMaxHeapMb}",
        )
    }

    /**
     * 直方图统计（像素数组版）。
     *
     * 分片大小 = [AppGalleryPreviewConfig.bgChunkPixels]；累加器只持有固定大小计数，
     * 因此总像素数不受限制。
     */
    fun submitHistogramFromPixels(
        name: String,
        pixels: IntArray,
        config: AppGalleryPreviewConfig,
        withRgb: Boolean = true,
        priority: Int = BgJobPriority.LOW,
        onResult: (AppGalleryPreviewHistogram) -> Unit = {},
    ): BgJob {
        val safe = config.appGalleryPreviewSafe().config
        return BgJobRunner.submit(
            name = name,
            chunkSize = 1,
            type = BgTaskType.PREVIEW,
            priority = priority,
        ) { job ->
            val accumulator = newAccumulator(safe, withRgb)
            if (pixels.isEmpty()) {
                job.report(1, 1)
                onResult(accumulator.toHistogram())
                return@submit
            }
            val chunk = safe.bgChunkPixels
            var index = 0
            while (index < pixels.size) {
                val end = minOf(index + chunk, pixels.size)
                for (i in index until end) accumulator.add(pixels[i])
                index = end
                job.report(index, pixels.size)
                job.checkYield()
            }
            onResult(accumulator.toHistogram())
        }
    }

    /**
     * 直方图统计（位图版，按行分片取像素）。
     *
     * 每批行数由 [AppGalleryPreviewConfig.bgChunkPixels] / 位图宽度推导；
     * 分配行缓冲前用 `guardMemory` 守卫，失败则把批行数降到 1（降级而非崩溃）。
     */
    fun submitHistogramFromBitmap(
        name: String,
        bitmap: Bitmap,
        config: AppGalleryPreviewConfig,
        withRgb: Boolean = true,
        priority: Int = BgJobPriority.LOW,
        onResult: (AppGalleryPreviewHistogram) -> Unit = {},
    ): BgJob {
        val safe = config.appGalleryPreviewSafe().config
        return BgJobRunner.submit(
            name = name,
            chunkSize = 1,
            type = BgTaskType.PREVIEW,
            priority = priority,
        ) { job ->
            val accumulator = newAccumulator(safe, withRgb)
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) {
                job.report(1, 1)
                onResult(accumulator.toHistogram())
                return@submit
            }
            val desiredRows = (safe.bgChunkPixels / width).coerceAtLeast(1)
            val budgetBytes = width.toLong() * desiredRows.toLong() *
                AppGalleryPreviewRawExportMath.BYTES_PER_PIXEL
            // 内存守卫失败 → 降级到单行批（而不是放弃统计）。
            val bandRows = if (job.guardMemory(budgetBytes)) desiredRows else 1
            val buffer = IntArray(width * bandRows)
            var y = 0
            while (y < height) {
                val rows = minOf(bandRows, height - y)
                bitmap.getPixels(buffer, 0, width, 0, y, width, rows)
                val count = width * rows
                for (i in 0 until count) accumulator.add(buffer[i])
                y += rows
                job.report(y, height)
                job.checkYield()
            }
            onResult(accumulator.toHistogram())
        }
    }

    /** 读取 RAW/DNG 曝光元数据（不解码像素，很轻）。 */
    fun submitRawMetadataRead(
        name: String,
        context: Context,
        uri: Uri,
        priority: Int = BgJobPriority.NORMAL,
        onResult: (AppGalleryPreviewRawExposure?) -> Unit = {},
    ): BgJob {
        val appContext = context.applicationContext
        return BgJobRunner.submit(
            name = name,
            chunkSize = 1,
            type = BgTaskType.PREVIEW,
            priority = priority,
        ) { job ->
            val exposure = readExposureMetadata(appContext, uri)
            job.report(1, 1)
            job.checkYield()
            onResult(exposure)
        }
    }

    /**
     * RAW/DNG 预览渲染：优先取内嵌预览（缩略图），失败回退平台解码。
     *
     * 降级链（任一环节失败都不崩溃）：
     *  1. 内嵌预览位图（DNG 通常内嵌全尺寸 JPEG 预览）→ 必要时按目标长边缩放；
     *  2. `BitmapFactory` 按内存预算选采样率解码；
     *  3. 仍失败 → 回调 null，UI 展示占位与原因。
     *
     * 目标长边 = [AppGalleryPreviewConfig.rawPreviewMaxDimension] × [AppGalleryPreviewConfig.rawRenderWorkingScale]。
     */
    fun submitRawPreviewDecode(
        name: String,
        context: Context,
        uri: Uri,
        config: AppGalleryPreviewConfig,
        priority: Int = BgJobPriority.LOW,
        onResult: (AppGalleryPreviewDecodedImage?) -> Unit = {},
    ): BgJob {
        val safe = config.appGalleryPreviewSafe().config
        val appContext = context.applicationContext
        return BgJobRunner.submit(
            name = name,
            chunkSize = 1,
            type = BgTaskType.PREVIEW,
            priority = priority,
        ) { job ->
            val targetLongEdge = (safe.rawPreviewMaxDimension * safe.rawRenderWorkingScale)
                .toInt()
                .coerceAtLeast(256)

            val exposure = readExposureMetadata(appContext, uri)
            job.report(1, 6)
            job.checkYield()

            var result: AppGalleryPreviewDecodedImage? = null

            // 预览优先取内嵌预览：DNG 内嵌（通常全尺寸）JPEG，解码最快且不触碰原始 CFA 数据。
            val embedded = decodeEmbeddedPreview(appContext, uri, targetLongEdge, job)
            if (embedded != null) {
                result = AppGalleryPreviewDecodedImage(
                    bitmap = embedded,
                    exposure = exposure,
                    usedEmbeddedPreview = true,
                    sampleSize = 1,
                )
            }
            job.report(3, 6)
            job.checkYield()

            if (result == null) {
                val decoded = decodeWithBudget(appContext, uri, targetLongEdge, job)
                if (decoded != null) {
                    result = AppGalleryPreviewDecodedImage(
                        bitmap = decoded.first,
                        exposure = exposure,
                        usedEmbeddedPreview = false,
                        sampleSize = decoded.second,
                    )
                }
            }
            job.report(5, 6)
            job.checkYield()

            if (result == null) {
                PLog.w(TAG, "raw preview decode failed for $uri")
            }
            job.report(6, 6)
            onResult(result)
        }
    }

    /**
     * RAW→JPEG 导出（第 36 项）。
     *
     * 流式策略：解码分级采样（内存守卫）→ 目标尺寸缩放 → JPEG 编码落盘（分片进度上报 +
     * 每片 `checkYield`）→ 按配置复制 EXIF（逐标签，失败忽略）。
     * `Bitmap.compress` 为单次平台调用，无法在编码内部切分；因此"分片"体现在
     * 解码采样分级、元数据逐条复制与阶段性进度/让出，不限制总工作量。
     *
     * @param outputFile 输出目标文件（调用方负责目录是否可写）
     */
    fun submitRawExport(
        name: String,
        context: Context,
        uri: Uri,
        request: AppGalleryPreviewRawExportRequest,
        outputFile: File,
        config: AppGalleryPreviewConfig,
        priority: Int = BgJobPriority.LOW,
        onResult: (Result<AppGalleryPreviewRawExportResult>) -> Unit = {},
    ): BgJob {
        val safe = config.appGalleryPreviewSafe().config
        val appContext = context.applicationContext
        val normalized = request.normalized(safe)
        return BgJobRunner.submit(
            name = name,
            chunkSize = 1,
            type = BgTaskType.PREVIEW,
            priority = priority,
        ) { job ->
            val startedAt = System.currentTimeMillis()
            try {
                val targetLongEdge = (normalized.maxDimension * normalized.workScale).toInt().coerceAtLeast(256)
                job.report(1, 10)
                job.checkYield()

                // 1) 解码：优先内嵌预览（快），否则按内存预算采样解码。
                var usedEmbedded = normalized.useEmbeddedPreview
                var source = if (normalized.useEmbeddedPreview) {
                    decodeEmbeddedPreview(appContext, uri, targetLongEdge, job)
                } else {
                    null
                }
                if (source == null) {
                    source = decodeEmbeddedPreview(appContext, uri, targetLongEdge, job)
                        ?: decodeWithBudget(appContext, uri, targetLongEdge, job)?.first
                        ?: throw IllegalStateException("decode failed: no decodable preview for $uri")
                    usedEmbedded = false
                }
                job.report(4, 10)
                job.checkYield()

                // 2) 缩放到导出目标尺寸（工作分辨率已由 targetLongEdge 约束）。
                val scaled = scaleToTarget(source, targetLongEdge, job)
                job.report(6, 10)
                job.checkYield()

                val target = AppGalleryPreviewRawExportMath.targetSize(
                    scaled.width,
                    scaled.height,
                    normalized.maxDimension,
                )
                val finalBitmap = if (target.isValid && (target.width != scaled.width || target.height != scaled.height)) {
                    Bitmap.createScaledBitmap(scaled, target.width, target.height, true)
                } else {
                    scaled
                }

                // 3) 编码落盘（先取尺寸，再回收，避免读到已回收位图）。
                val outWidth = finalBitmap.width
                val outHeight = finalBitmap.height
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { stream ->
                    val ok = finalBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        normalized.jpegQuality,
                        stream,
                    )
                    stream.flush()
                    if (!ok) throw IllegalStateException("jpeg compress returned false")
                }
                job.report(8, 10)
                job.checkYield()

                // 4) 复制 EXIF（可配；逐标签分片让出）。
                if (normalized.preserveExif) {
                    copyExifTags(appContext, uri, outputFile, normalized.chunkRows, job)
                }
                val byteCount = outputFile.length()
                if (finalBitmap !== scaled) finalBitmap.recycle()
                if (scaled !== source) scaled.recycle()
                source.recycle()

                job.report(10, 10)
                onResult(
                    Result.success(
                        AppGalleryPreviewRawExportResult(
                            outputPath = outputFile.absolutePath,
                            size = AppGalleryPreviewSize(outWidth, outHeight),
                            byteCount = byteCount,
                            usedEmbeddedPreview = usedEmbedded,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        ),
                    ),
                )
            } catch (error: Exception) {
                PLog.w(TAG, "raw export failed for $uri", error)
                onResult(Result.failure(error))
            }
        }
    }

    // ---------------- 内部实现 ----------------

    private fun newAccumulator(
        safe: AppGalleryPreviewConfig,
        withRgb: Boolean,
    ): AppGalleryPreviewHistogramAccumulator = AppGalleryPreviewHistogramAccumulator(
        bins = safe.rawHistogramBins,
        overexposureLevel = (safe.rawOverexposureThreshold * 255f).toInt().coerceIn(0, 255),
        underexposureLevel = (safe.rawUnderexposureThreshold * 255f).toInt().coerceIn(0, 255),
        withRgb = withRgb,
    )

    /** 读取曝光元数据；任何异常都返回 null（UI 显示"元数据不可用"）。 */
    private fun readExposureMetadata(context: Context, uri: Uri): AppGalleryPreviewRawExposure? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                AppGalleryPreviewRawMetadataParser.fromTags(
                    METADATA_TAGS.associateWith { exif.getAttribute(it) },
                )
            }
        }.onFailure { PLog.w(TAG, "read raw metadata failed: $uri", it) }.getOrNull()
    }

    /** 取内嵌预览并（必要时）缩放，同时按 EXIF 方向纠正；失败返回 null。 */
    private fun decodeEmbeddedPreview(
        context: Context,
        uri: Uri,
        targetLongEdge: Int,
        job: BgJob,
    ): Bitmap? {
        val thumb = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).thumbnailBitmap
            }
        }.onFailure { PLog.w(TAG, "embedded preview decode failed: $uri", it) }.getOrNull() ?: return null
        job.checkYield()
        val oriented = applyExifOrientation(context, uri, thumb)
        return scaleToTarget(oriented, targetLongEdge, job)
    }

    /** 按内存预算选采样率解码；返回 (bitmap, sampleSize)，失败返回 null。 */
    private fun decodeWithBudget(
        context: Context,
        uri: Uri,
        targetLongEdge: Int,
        job: BgJob,
    ): Pair<Bitmap, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.onFailure { PLog.w(TAG, "decode bounds failed: $uri", it) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        job.checkYield()

        // 预算：优先用类型堆预算；再与"目标尺寸缓冲"取小，保证不会一次吃掉整块堆。
        val typeBudget = BgTaskType.PREVIEW.defaultMaxHeapBytes
        val targetBudget = AppGalleryPreviewRawExportMath.estimatedBytes(
            minOf(bounds.outWidth, targetLongEdge.coerceAtLeast(1)),
            minOf(bounds.outHeight, targetLongEdge.coerceAtLeast(1)),
        ) * 2L
        val budget = if (targetBudget in 1L until typeBudget) targetBudget else typeBudget
        val sample = AppGalleryPreviewRawExportMath.decodeSampleSize(
            srcWidth = bounds.outWidth,
            srcHeight = bounds.outHeight,
            targetLongEdge = targetLongEdge,
            budgetBytes = budget,
        )
        job.checkYield()

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.onFailure { PLog.w(TAG, "decode failed: $uri", it) }.getOrNull() ?: return null
        job.checkYield()
        return applyExifOrientation(context, uri, decoded) to sample
    }

    /** 按 EXIF Orientation 纠正方向（失败时返回原图）。 */
    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt("Orientation", ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ORIENTATION_NORMAL
        val matrix = when (orientation) {
            ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
            ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
            ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
            ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
            ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
            else -> null
        } ?: return bitmap
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    /** 按长边缩放到目标（不放大）；已满足则原样返回。 */
    private fun scaleToTarget(bitmap: Bitmap, targetLongEdge: Int, job: BgJob): Bitmap {
        if (targetLongEdge <= 0) return bitmap
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        val longEdge = maxOf(srcWidth, srcHeight)
        if (longEdge <= targetLongEdge) return bitmap
        val target = AppGalleryPreviewRawExportMath.targetSize(srcWidth, srcHeight, targetLongEdge)
        if (!target.isValid) return bitmap
        val bytes = AppGalleryPreviewRawExportMath.estimatedBytes(target.width, target.height)
        if (!job.guardMemory(bytes)) {
            // 内存不足：放弃缩放，返回原图（由上层采样率保证不超预算），而不是失败。
            PLog.i(TAG, "skip scale, insufficient memory for ${target.width}x${target.height}")
            return bitmap
        }
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, target.width, target.height, true) }
            .getOrNull() ?: return bitmap
        if (scaled !== bitmap) {
            PLog.i(TAG, "scaled preview ${srcWidth}x$srcHeight -> ${target.width}x${target.height}")
            bitmap.recycle()
        }
        job.checkYield()
        return scaled
    }

    /**
     * 按配置逐条复制 EXIF 标签（分片：每 [chunkRows] 条让出一次）。
     *
     * 目标文件必须是已写好的 JPEG；失败仅记录日志（不阻断导出）。
     */
    private fun copyExifTags(
        context: Context,
        sourceUri: Uri,
        targetFile: File,
        chunkRows: Int,
        job: BgJob,
    ) {
        runCatching {
            val values = ArrayList<Pair<String, String>>(EXIF_COPY_TAGS.size)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val source = ExifInterface(input)
                for (tag in EXIF_COPY_TAGS) {
                    source.getAttribute(tag)?.let { values += tag to it }
                }
            }
            if (values.isEmpty()) return@runCatching
            val target = ExifInterface(targetFile)
            var written = 0
            for ((tag, value) in values) {
                target.setAttribute(tag, value)
                written++
                if (written % chunkRows.coerceAtLeast(1) == 0) job.checkYield()
            }
            target.saveAttributes()
        }.onFailure { PLog.w(TAG, "copy exif failed: ${targetFile.name}", it) }
    }

    private const val ORIENTATION_NORMAL = 1
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_ROTATE_270 = 8
}
