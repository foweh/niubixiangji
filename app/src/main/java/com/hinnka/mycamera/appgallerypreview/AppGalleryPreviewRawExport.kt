package com.hinnka.mycamera.appgallerypreview

/** 目标像素尺寸。 */
data class AppGalleryPreviewSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
    val longEdge: Int get() = maxOf(width, height)
}

/** RAW→JPEG 导出请求（第 36 项）。 */
data class AppGalleryPreviewRawExportRequest(
    val mediaUri: String,
    /** 输出文件名（不含目录）；非法字符会被清洗。 */
    val outputFileName: String,
    val maxDimension: Int = 4096,
    val jpegQuality: Int = 95,
    val preserveExif: Boolean = true,
    val useEmbeddedPreview: Boolean = true,
    val chunkRows: Int = 256,
    val workScale: Float = 1f,
) {
    /**
     * clamp 全部数值参数，防误设。
     *
     * 上限与 [AppGalleryPreviewConfig.appGalleryPreviewSafe] 保持一致（512..16384 / 1..100 / 16..4096 / 0.25..1）。
     */
    fun normalized(config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT): AppGalleryPreviewRawExportRequest {
        val safe = config.appGalleryPreviewSafe().config
        return copy(
            maxDimension = maxDimension.coerceIn(512, minOf(16384, safe.rawExportMaxDimension))
                .let { if (it <= 0) safe.rawExportMaxDimension else it },
            jpegQuality = jpegQuality.coerceIn(1, 100),
            chunkRows = chunkRows.coerceIn(16, 4096),
            workScale = workScale.coerceIn(0.25f, 1f),
            outputFileName = AppGalleryPreviewRawExportMath.sanitizeFileName(
                outputFileName,
                fallback = "raw_export_${kotlin.math.abs(outputFileName.hashCode())}.jpg",
            ),
        )
    }
}

/** RAW→JPEG 导出结果。 */
data class AppGalleryPreviewRawExportResult(
    val outputPath: String,
    val size: AppGalleryPreviewSize,
    val byteCount: Long,
    val usedEmbeddedPreview: Boolean,
    val elapsedMs: Long,
)

/** 整数像素尺寸对（轻量结构，避免在纯逻辑层引入 Bitmap）。 */
data class AppGalleryPreviewIntSize(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
}

/**
 * RAW 导出的纯数学部分（可 JVM 单测）：缩放目标、采样率、分片行区间、文件名清洗。
 */
object AppGalleryPreviewRawExportMath {

    /** 每像素字节数（ARGB_8888 为 4）。 */
    const val BYTES_PER_PIXEL = 4

    /**
     * 计算缩放后的目标尺寸：只缩不放（long edge <= maxDimension）。
     *
     * @return 源尺寸非法时返回 (0,0)
     */
    fun targetSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): AppGalleryPreviewIntSize {
        if (srcWidth <= 0 || srcHeight <= 0) return AppGalleryPreviewIntSize(0, 0)
        val limit = maxDimension.coerceAtLeast(1)
        val longEdge = maxOf(srcWidth, srcHeight)
        if (longEdge <= limit) return AppGalleryPreviewIntSize(srcWidth, srcHeight)
        val ratio = limit.toDouble() / longEdge.toDouble()
        val w = kotlin.math.round(srcWidth * ratio).toInt().coerceAtLeast(1)
        val h = kotlin.math.round(srcHeight * ratio).toInt().coerceAtLeast(1)
        return AppGalleryPreviewIntSize(w, h)
    }

    /** 估算 [width]x[height] 的 ARGB_8888 缓冲字节数。 */
    fun estimatedBytes(width: Int, height: Int): Long =
        if (width <= 0 || height <= 0) 0L else width.toLong() * height.toLong() * BYTES_PER_PIXEL

    /**
     * 计算 BitmapFactory 采样率（2 的幂），使解码结果同时满足：
     *  1) 长边 <= [targetLongEdge]（>=1 时不限制）
     *  2) 解码缓冲估算 <= [budgetBytes]（<=0 时不限制）
     *
     * 返回 >=1 的 2 的幂；源尺寸非法返回 1（让解码器自行处理）。
     */
    fun decodeSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetLongEdge: Int,
        budgetBytes: Long = 0L,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var sample = 1
        // 上限保护：避免极端宽高比下循环过久（4096x 已远超手机屏幕需求）。
        val hardCap = 4096
        while (sample < hardCap) {
            val w = srcWidth / (sample * 2)
            val h = srcHeight / (sample * 2)
            if (w <= 0 || h <= 0) break
            val fitsEdge = targetLongEdge <= 0 || maxOf(w, h) <= targetLongEdge
            val fitsBudget = budgetBytes <= 0L || estimatedBytes(w, h) <= budgetBytes
            if (!fitsEdge || !fitsBudget) {
                sample *= 2
            } else {
                break
            }
        }
        // 再检查当前 sample 是否已满足；不满足则至少保证不超预算（继续加倍）。
        while (sample < hardCap) {
            val w = srcWidth / sample
            val h = srcHeight / sample
            if (w <= 0 || h <= 0) return sample
            val fitsBudget = budgetBytes <= 0L || estimatedBytes(w, h) <= budgetBytes
            if (fitsBudget) return sample
            sample *= 2
        }
        return sample
    }

    /**
     * 把总行数切成行区间（分片流式处理用）。
     * 总行数 <=0 返回空列表；[chunkRows] 越界按 1 处理。
     */
    fun chunkRowRanges(totalRows: Int, chunkRows: Int): List<IntRange> {
        if (totalRows <= 0) return emptyList()
        val step = chunkRows.coerceAtLeast(1)
        val out = ArrayList<IntRange>(totalRows / step + 1)
        var start = 0
        while (start < totalRows) {
            val end = minOf(start + step, totalRows) // 左闭右开语义封装为 IntRange 使用
            out += start until end
            start = end
        }
        return out
    }

    /** 清洗文件名：去目录、替换非法字符、限制长度、补 .jpg 后缀。 */
    fun sanitizeFileName(name: String?, fallback: String = "raw_export.jpg"): String {
        val raw = name?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val withoutQuery = base.substringBefore('?').substringBefore('#')
        val cleaned = withoutQuery
            .map { if (it in ILLEGAL_CHARS || it.code < 0x20) '_' else it }
            .joinToString("")
            .trim('.', '_', ' ')
        val safe = cleaned.takeIf { it.isNotEmpty() } ?: fallback.substringBeforeLast('.')
        val limited = if (safe.length > MAX_NAME_LENGTH) safe.take(MAX_NAME_LENGTH) else safe
        val withExtension = if (limited.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) {
            limited
        } else {
            val stem = limited.substringBeforeLast('.', limited)
            "$stem.jpg"
        }
        return withExtension
    }

    /** 由源文件名/URI 生成导出文件名。 */
    fun exportFileNameFor(sourceHint: String?, fallbackStem: String = "raw_export"): String {
        val stem = sourceHint?.let { sanitizeFileName(it, "$fallbackStem.jpg") }
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackStem
        return "${stem}_export.jpg"
    }

    /** 亮度直方图分片预取的行区间（与 [chunkRowRanges] 同源，供 Bitmap#getPixels 使用）。 */
    fun pixelBandRanges(bitmapHeight: Int, chunkRows: Int): List<IntRange> =
        chunkRowRanges(bitmapHeight, chunkRows)

    private val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_NAME_LENGTH = 120
}
