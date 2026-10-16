package com.hinnka.mycamera.galleryedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.gallery.PostEditGeometry
import com.hinnka.mycamera.utils.BitmapUtils
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "GeBitmapEditPipeline"

/** 管线内的处理步骤数（旋转 / 裁剪 / 水印压平 = 3 个固定步，像素分片另计）。 */
private const val FIXED_STEPS = 3

/**
 * 图片编辑管线的开放配置（默认值 + 范围 + clamp，PATTERN_SPEC §5）。
 *
 * @param workingScale 工作分辨率缩放（相对原图），范围 [GeEditRanges.WORK_SCALE_MIN, GeEditRanges.WORK_SCALE_MAX]。
 *        内存吃紧时由管线自动降级而不是失败。
 * @param stripHeight 逐像素分片的行高（像素），范围 32..4096；越小越省内存、分片越多。
 * @param singlePassPixelLimit 单次 Canvas 直通的像素数上限；超过则走分片逐像素路径。
 * @param flattenOnExport 导出前是否压平 alpha（保存 JPEG 需要）。
 */
data class GeImagePipelineConfig(
    val workingScale: Float = GeEditRanges.WORK_SCALE_DEFAULT,
    val stripHeight: Int = 256,
    val singlePassPixelLimit: Long = 12_000_000L,
    val flattenOnExport: Boolean = true,
) {
    fun clamp(): Pair<GeImagePipelineConfig, GeClampReport> {
        val safeScale = GeEditRanges.clampFloat(
            workingScale,
            GeEditRanges.WORK_SCALE_MIN,
            GeEditRanges.WORK_SCALE_MAX,
            GeEditRanges.WORK_SCALE_DEFAULT,
        )
        val safeStrip = GeEditRanges.clampInt(stripHeight, MIN_STRIP_HEIGHT, MAX_STRIP_HEIGHT)
        val safeLimit = singlePassPixelLimit.coerceIn(MIN_SINGLE_PASS_PIXELS, MAX_SINGLE_PASS_PIXELS)
        val corrections = buildList {
            GeClampReport.of("pipeline.workingScale", workingScale.toDouble(), safeScale.toDouble())?.let(::add)
            GeClampReport.of("pipeline.stripHeight", stripHeight.toDouble(), safeStrip.toDouble())?.let(::add)
            GeClampReport.of("pipeline.singlePassPixelLimit", singlePassPixelLimit.toDouble(), safeLimit.toDouble())
                ?.let(::add)
        }
        return copy(workingScale = safeScale, stripHeight = safeStrip, singlePassPixelLimit = safeLimit) to
            GeClampReport(corrections)
    }

    companion object {
        const val MIN_STRIP_HEIGHT = 32
        const val MAX_STRIP_HEIGHT = 4096
        const val MIN_SINGLE_PASS_PIXELS = 250_000L
        const val MAX_SINGLE_PASS_PIXELS = 200_000_000L
    }
}

/**
 * 一次图片编辑的完整状态。
 *
 * 处理顺序固定：**旋转 → 裁剪 → 亮度/对比度/饱和度/色温 → 日期戳 → 水印**。
 * 旋转复用既有 [PostEditGeometry.normalizeRotation]（不重复实现旋转/拉直）。
 */
data class GeImageEditState(
    val crop: GeCropRect = GeCropRect.FULL,
    val rotationDegrees: Int = 0,
    val pipelineConfig: GeImagePipelineConfig = GeImagePipelineConfig(),
    val pixel: GePixelAdjustParams = GePixelAdjustParams(),
    val watermark: GeWatermarkConfig = GeWatermarkConfig(),
    val dateStamp: GeDateStampConfig = GeDateStampConfig(),
    /** 日期戳时间源（EXIF 拍摄时间）；null 时按 [GeDateStampConfig.useExifTakenTime] 回落当前时间。 */
    val dateStampMillis: Long? = null,
) {
    /** 旋转档位归一化（0/90/180/270）。 */
    val normalizedRotation: Int get() = PostEditGeometry.normalizeRotation(rotationDegrees)

    /** 全部编辑均为默认值时可跳过整条管线。 */
    val isIdentity: Boolean
        get() = crop == GeCropRect.FULL &&
            normalizedRotation == 0 &&
            pixel.isIdentity &&
            !watermark.isActive &&
            !dateStamp.isActive

    /** 越界收敛（UI 输入来源任意，必须安全）。 */
    fun clamp(): Pair<GeImageEditState, GeClampReport> {
        val corrections = mutableListOf<GeClampCorrection>()
        val rotation = normalizedRotation
        if (rotation != rotationDegrees) {
            corrections.add(
                GeClampCorrection("image.rotationDegrees", rotationDegrees.toDouble(), rotation.toDouble())
            )
        }
        val (safePixel, pixelReport) = pixel.clamp()
        corrections += pixelReport.corrections
        val (safeWatermark, watermarkReport) = watermark.clamp()
        corrections += watermarkReport.corrections
        val (safeDateStamp, dateReport) = dateStamp.clamp()
        corrections += dateReport.corrections
        val (safePipeline, pipelineReport) = pipelineConfig.clamp()
        corrections += pipelineReport.corrections
        return copy(
            crop = GeCropMath.sanitize(crop),
            rotationDegrees = rotation,
            pixel = safePixel,
            watermark = safeWatermark,
            dateStamp = safeDateStamp,
            pipelineConfig = safePipeline,
        ) to GeClampReport(corrections)
    }
}

/**
 * 图片快速编辑管线：把 [GeImageEditState] 烘焙成一张新位图。
 *
 * 复用关系（PATTERN_SPEC §9）：
 * - 旋转：复用 `gallery/PostEditGeometry.normalizeRotation` + `utils/BitmapUtils.rotate`（不重写）；
 * - 裁剪：本模块 [GeCropMath]（自研，uCrop 是 View/Activity 形态，见 patch 说明的取舍）；
 * - 亮度/对比度/饱和度/色温：本模块纯数学核心 [GePixelAdjustMath]（自研）；
 * - 水印/日期戳：本模块自研绘制（理由见 [GeWatermarkRenderer]）。
 *
 * 内存策略（PATTERN_SPEC §5/§6）：大图不整批驻留浮点缓冲——像素调节走**按行分片**的 IntArray，
 * 每片调 `job.checkYield()`；分配前用 `job.guardMemory()`；不足时降级为分片而不是失败。
 */
object GeBitmapEditPipeline {

    /** 同步渲染（调用方自行保证不在主线程做重活）。 */
    fun render(
        source: Bitmap,
        state: GeImageEditState,
        density: Float = 1f,
        config: GeImagePipelineConfig = state.pipelineConfig,
    ): Bitmap? = renderInternal(null, source, state, density, config)

    /** 后台任务渲染：分片 `checkYield()` + `guardMemory()` + `batchSizeFor()`。 */
    fun renderChunked(
        job: BgJob,
        source: Bitmap,
        state: GeImageEditState,
        density: Float = 1f,
        config: GeImagePipelineConfig = state.pipelineConfig,
    ): Bitmap? = renderInternal(job, source, state, density, config)

    private fun renderInternal(
        job: BgJob?,
        source: Bitmap,
        state: GeImageEditState,
        density: Float,
        config: GeImagePipelineConfig,
    ): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            PLog.w(TAG, "render skipped: invalid source bitmap")
            return null
        }
        val (safeState, report) = state.clamp()
        val (safeConfig, configReport) = config.clamp()
        if (report.changed || configReport.changed) {
            PLog.i(TAG, "clamped: ${report.describe()} ${configReport.describe()}")
        }
        if (safeState.isIdentity) return source

        return try {
            // 1) 旋转（复用既有实现）
            val rotated = rotate(source, safeState.normalizedRotation)
            job?.checkYield()

            // 2) 裁剪
            val cropped = crop(rotated, safeState.crop)
            job?.checkYield()

            val rowsPerStrip = job?.batchSizeFor(safeConfig.stripHeight)
                ?: safeConfig.stripHeight
            val stripTotal = stripCount(cropped.height, rowsPerStrip)
            val total = FIXED_STEPS + stripTotal
            job?.report(2, total)

            // 3) 亮度/对比度/饱和度/色温
            val adjusted = adjustPixels(job, cropped, safeState.pixel, safeConfig, rowsPerStrip) { processedRows ->
                job?.report(2 + (processedRows + rowsPerStrip - 1) / rowsPerStrip, total)
            }
            job?.checkYield()

            // 4) 日期戳 + 水印（保存时永久写入像素）
            val stamped = drawStamps(adjusted, safeState, density)

            // 5) 导出压平
            val flattened = if (safeConfig.flattenOnExport) flatten(stamped) else stamped
            job?.report(total, total)
            flattened
        } catch (e: RuntimeException) {
            PLog.e(TAG, "image edit pipeline failed", e)
            null
        }
    }

    private fun stripCount(height: Int, rowsPerStrip: Int): Int =
        max(1, (height + rowsPerStrip.coerceAtLeast(1) - 1) / rowsPerStrip.coerceAtLeast(1))

    /** 档位归一化后的旋转；0° 直接返回原图（避免无谓拷贝）。 */
    private fun rotate(source: Bitmap, normalizedDegrees: Int): Bitmap {
        if (normalizedDegrees == 0) return source
        return BitmapUtils.rotate(source, normalizedDegrees.toFloat())
    }

    private fun crop(source: Bitmap, cropRect: GeCropRect): Bitmap {
        val safe = GeCropMath.sanitize(cropRect)
        if (safe == GeCropRect.FULL) return source
        val pixels = GeCropMath.toPixels(safe, GeSize(source.width, source.height))
        if (pixels.isEmpty || (pixels.width == source.width && pixels.height == source.height)) return source
        return try {
            Bitmap.createBitmap(source, pixels.left, pixels.top, pixels.width, pixels.height)
        } catch (e: IllegalArgumentException) {
            PLog.w(TAG, "crop failed, keeping full frame: ${e.message}")
            source
        }
    }

    /**
     * 像素调节。
     *
     * - 小图且内存守卫通过：一次 Canvas + `ColorMatrixColorFilter`（直通，省内存）；
     * - 大图 / 内存不足：按行分片的纯 Kotlin 逐像素路径，每片 `checkYield()`。
     */
    private fun adjustPixels(
        job: BgJob?,
        source: Bitmap,
        params: GePixelAdjustParams,
        config: GeImagePipelineConfig,
        rowsPerStrip: Int,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        if (params.isIdentity) return source
        val matrix = GePixelAdjustMath.buildMatrix(params)
        if (matrix.isIdentity()) return source

        val pixels = source.width.toLong() * source.height.toLong()
        val bytesForCopy = pixels * 4L
        val singlePassPreferred = pixels <= config.singlePassPixelLimit
        val memoryOk = job?.guardMemory(bytesForCopy * 2L) ?: true

        return if (singlePassPreferred && memoryOk) {
            colorMatrixPass(source, matrix)
        } else {
            PLog.i(TAG, "pixel adjust degraded to strip pass (pixels=$pixels, guardMemory=$memoryOk)")
            stripPass(job, source, matrix, rowsPerStrip, onProgress)
        }
    }

    /** 单次 Canvas 直通（矩阵色彩变换）。 */
    private fun colorMatrixPass(source: Bitmap, matrix: GeColorMatrix): Bitmap {
        // 强制 ARGB_8888：HARDWARE 位图无法作为 Canvas 目标
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix.toAndroidColorMatrix())
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /** 分片逐像素路径：内存只驻留"一行片"的 IntArray。 */
    private fun stripPass(
        job: BgJob?,
        source: Bitmap,
        matrix: GeColorMatrix,
        rowsPerStrip: Int,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rows = rowsPerStrip.coerceIn(1, height)
        val buffer = IntArray(width * rows)
        val rgb = FloatArray(3)
        val out = FloatArray(3)
        var y = 0
        while (y < height) {
            val currentRows = minOf(rows, height - y)
            if (currentRows != rows) {
                // 尾片较小：仅读取/写回有效行，避免越界
                val tail = IntArray(width * currentRows)
                source.getPixels(tail, 0, width, 0, y, width, currentRows)
                transformBuffer(tail, matrix, rgb, out)
                output.setPixels(tail, 0, width, 0, y, width, currentRows)
            } else {
                source.getPixels(buffer, 0, width, 0, y, width, rows)
                transformBuffer(buffer, matrix, rgb, out)
                output.setPixels(buffer, 0, width, 0, y, width, rows)
            }
            y += currentRows
            onProgress(y)
            job?.checkYield()
        }
        return output
    }

    private fun transformBuffer(buffer: IntArray, matrix: GeColorMatrix, rgb: FloatArray, out: FloatArray) {
        for (index in buffer.indices) {
            val color = buffer[index]
            val alpha = (color ushr 24) and 0xFF
            rgb[0] = ((color shr 16) and 0xFF).toFloat()
            rgb[1] = ((color shr 8) and 0xFF).toFloat()
            rgb[2] = (color and 0xFF).toFloat()
            matrix.applyToChannel(rgb, out)
            buffer[index] = (alpha shl 24) or
                (out[0].roundToInt().coerceIn(0, 255) shl 16) or
                (out[1].roundToInt().coerceIn(0, 255) shl 8) or
                out[2].roundToInt().coerceIn(0, 255)
        }
    }

    /** 日期戳 + 水印（两者都永久写入像素）。任一绘制失败不影响已完成的像素调节。 */
    private fun drawStamps(source: Bitmap, state: GeImageEditState, density: Float): Bitmap {
        val needsWatermark = state.watermark.isActive
        val needsDateStamp = state.dateStamp.isActive
        if (!needsWatermark && !needsDateStamp) return source

        val mutable = if (source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true) ?: source
        }
        val canvas = Canvas(mutable)
        val container = GeSize(mutable.width, mutable.height)
        if (needsDateStamp) {
            try {
                val timestamp = GeDateStampFormat.resolveTimestamp(
                    exifTakenMillis = state.dateStampMillis,
                    useExifTakenTime = state.dateStamp.useExifTakenTime,
                )
                val text = GeDateStampFormat.format(timestamp, state.dateStamp.pattern)
                GeWatermarkRenderer.drawDateStamp(canvas, container, state.dateStamp, text, density)
            } catch (e: RuntimeException) {
                PLog.w(TAG, "date stamp failed: ${e.message}")
            }
        }
        if (needsWatermark) {
            try {
                GeWatermarkRenderer.drawWatermark(canvas, container, state.watermark, density)
            } catch (e: RuntimeException) {
                PLog.w(TAG, "watermark failed: ${e.message}")
            }
        }
        return mutable
    }

    /** 压平：在有 alpha 的图上先铺黑底再叠原图，保证 JPEG 导出无杂色。 */
    private fun flatten(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.ARGB_8888 && !source.hasAlpha()) return source
        return try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, 0f, 0f, null)
            output
        } catch (e: RuntimeException) {
            PLog.w(TAG, "flatten failed: ${e.message}")
            source
        }
    }
}

/** [GeColorMatrix] → Android `ColorMatrix`（同为 4x5 / 0..255 定义域，直接拷贝）。 */
fun GeColorMatrix.toAndroidColorMatrix(): ColorMatrix = ColorMatrix(values.copyOf())
