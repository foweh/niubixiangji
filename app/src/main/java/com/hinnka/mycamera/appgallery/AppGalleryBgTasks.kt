package com.hinnka.mycamera.appgallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 后台任务结果包装（避免回调里再查一次状态）。 */
data class AppGalleryThumbnailResult(
    val generated: Int,
    val failed: Int,
    /** 生成过程中因内存不足降级的次数。 */
    val degraded: Int,
)

data class AppGalleryDuplicateScanResult(
    val groups: List<AppGalleryDuplicateGroup>,
    val hashedCount: Int,
    val skippedCount: Int,
) {
    val reclaimableBytes: Long get() = AppGalleryDuplicateDetector.reclaimableBytes(groups)
}

data class AppGalleryBackupResult(
    val outputPath: String?,
    val fileCount: Int,
    val totalBytes: Long,
    val failedCount: Int,
)

/**
 * 应用内置相册的后台重任务（相册项 12/14/15/18/7）。
 *
 * 全部通过 `computational/BgJobRunner.submit(...)` 入全局队列（单消费者 + CPU 节流 + 优先级），
 * **每个分片末尾调用 `job.checkYield()`**，大缓冲分配前 `job.guardMemory(bytes)`，
 * 批大小用 `job.batchSizeFor(n)`；内存不足时**降工作分辨率**而不是失败。
 *
 * 任务类型默认 `BgTaskType.GENERIC`；补丁里给出新增 `BgTaskType.GALLERY` 的完整条目，
 * 应用补丁后可把 [TASK_TYPE] 换成独立类型（不影响本文件其余逻辑）。
 */
object AppGalleryBgTasks {

    /** 全模块统一使用的任务类型（补丁应用后可改为 BgTaskType.GALLERY）。 */
    private var taskType: BgTaskType = BgTaskType.GENERIC

    /** 由集成阶段/设置页调用，切到独立频控档位。 */
    fun useTaskType(type: BgTaskType) {
        taskType = type
    }

    /** 缩略图补齐的最小工作边长（再降就无意义）。 */
    private const val MIN_THUMBNAIL_EDGE = 96

    /** 复制/打包用的流式缓冲（64KB，远低于任何内存预算）。 */
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    // ── 缩略图补齐（相册项 1） ──────────────────────────────────

    /**
     * 为缺失缩略图的条目生成补充缓存（写入 `Pictures/appgallery_thumbs/<id>.jpg`）。
     * 已有缩略图或解码失败的条目自动跳过，不阻断整体流程。
     */
    fun submitThumbnailBackfill(
        context: Context,
        items: List<AppGalleryItem>,
        config: AppGalleryConfig = AppGalleryConfig(),
        priority: Int = BgJobPriority.LOW,
        onFinish: ((AppGalleryThumbnailResult) -> Unit)? = null,
    ): BgJob {
        val appContext = context.applicationContext
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        val targets = items.filter { it.thumbnailUri.isNullOrBlank() || !thumbnailExists(appContext, it) }
        return BgJobRunner.submit(
            name = "相册缩略图补齐",
            chunkSize = safeConfig.thumbnailGenerationBatch,
            type = taskType,
            priority = priority,
            onFinish = null,
        ) { job ->
            var generated = 0
            var failed = 0
            var degraded = 0
            val thumbDir = AppGalleryStorageLayout.thumbnailCacheDir(appContext)
            var edge = safeConfig.thumbnailMaxEdgePx
            for (index in targets.indices step job.batchSizeFor(targets.size)) {
                val end = minOf(index + job.batchSizeFor(targets.size), targets.size)
                for (i in index until end) {
                    val item = targets[i]
                    // 每次解码工作缓冲 = edge²×4 字节；超预算则逐级降边长（不失败）
                    while (edge > MIN_THUMBNAIL_EDGE && !job.guardMemory(edge.toLong() * edge * 4L)) {
                        edge /= 2
                        degraded++
                    }
                    val ok = runCatching {
                        generateThumbnail(appContext, item, thumbDir, edge, safeConfig.thumbnailQuality)
                    }.onFailure { PLog.w(TAG, "thumbnail failed: ${item.id}", it) }.getOrDefault(false)
                    if (ok) generated++ else failed++
                }
                job.checkYield()
                job.report(end, targets.size)
            }
            onFinish?.invoke(AppGalleryThumbnailResult(generated, failed, degraded))
        }
    }

    private fun thumbnailExists(context: Context, item: AppGalleryItem): Boolean {
        val cached = File(AppGalleryStorageLayout.thumbnailCacheDir(context, create = false), thumbName(item.id))
        if (cached.exists()) return true
        val uriPath = runCatching { Uri.parse(item.thumbnailUri ?: "") }.getOrNull()?.path
        return uriPath != null && File(uriPath).exists()
    }

    private fun thumbName(itemId: String): String =
        itemId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jpg"

    private fun generateThumbnail(
        context: Context,
        item: AppGalleryItem,
        thumbDir: File,
        edge: Int,
        quality: Int,
    ): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeToOptions(context, item.uri, bounds, null)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (longest > 0) {
            while (longest / (sample * 2) >= edge) sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = decodeToOptions(context, item.uri, options, null) ?: return false
        val scaled = runCatching {
            val scale = edge.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            if (scale >= 1f) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }.getOrElse { bitmap }
        if (!thumbDir.exists()) thumbDir.mkdirs()
        val out = File(thumbDir, thumbName(item.id))
        val saved = runCatching {
            FileOutputStream(out).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 100), stream)
            }
        }.getOrDefault(false)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return saved
    }

    /** file:// / content:// 统一解码（失败返回 null，不抛）。 */
    fun decodeToOptions(
        context: Context,
        uri: String,
        options: BitmapFactory.Options,
    ): Bitmap? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase()) {
            "file" -> options.let { BitmapFactory.decodeFile(parsed.path, it) }
            "content", "android.resource" -> runCatching {
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }.getOrNull()
            else -> runCatching {
                val path = parsed.path
                if (path != null && File(path).exists()) {
                    BitmapFactory.decodeFile(path, options)
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    /** 解码为灰度（[insetEdge] 目标边长，用于 pHash）。 */
    private fun decodeGray(context: Context, uri: String, targetEdge: Int): AppGalleryGrayImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeToOptions(context, uri, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (longest > 0) {
            while (longest / (sample * 2) >= targetEdge) sample *= 2
        }
        val bitmap = decodeToOptions(
            context,
            uri,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = AppGalleryPHash.grayFromArgb(pixels)
        bitmap.recycle()
        return AppGalleryGrayImage(gray, width, height)
    }

    // ── 重复检测（相册项 12） ───────────────────────────────────

    /**
     * 感知哈希重复检测：分片解码 → 算哈希 → 并查集分组。
     * 解码前用 `guardMemory` 估算（工作图边长²×4），不足则降采样边长。
     */
    fun submitDuplicateScan(
        context: Context,
        items: List<AppGalleryItem>,
        config: AppGalleryConfig = AppGalleryConfig(),
        priority: Int = BgJobPriority.LOW,
        onFinish: ((AppGalleryDuplicateScanResult) -> Unit)? = null,
    ): BgJob {
        val appContext = context.applicationContext
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        val candidates = items.filter { it.kind == AppGalleryMediaKind.IMAGE }
        return BgJobRunner.submit(
            name = "重复照片检测",
            chunkSize = safeConfig.duplicateScanBatch,
            type = taskType,
            priority = priority,
        ) { job ->
            val entries = ArrayList<AppGalleryHashEntry>(candidates.size)
            var skipped = 0
            var edge = safeConfig.duplicateResizeEdge
            val images = ArrayList<AppGalleryGrayImage>(4)
            for (index in candidates.indices step job.batchSizeFor(candidates.size)) {
                val end = minOf(index + job.batchSizeFor(candidates.size), candidates.size)
                images.clear()
                for (i in index until end) {
                    val item = candidates[i]
                    while (edge > 16 && !job.guardMemory(edge.toLong() * edge * 4L)) edge /= 2
                    val gray = runCatching { decodeGray(appContext, item.uri, edge) }.getOrNull()
                    if (gray == null) {
                        skipped++
                        continue
                    }
                    images.add(gray)
                }
                images.forEachIndexed { offset, gray ->
                    val item = candidates[index + offset]
                    val hash = if (safeConfig.duplicateUseAverageHash) {
                        AppGalleryPHash.averageHash(gray.pixels, gray.width, gray.height, safeConfig.duplicateHashSize, edge)
                    } else {
                        AppGalleryPHash.dctHash(gray.pixels, gray.width, gray.height, safeConfig.duplicateHashSize, edge)
                    }
                    if (hash.isNotEmpty()) {
                        entries.add(
                            AppGalleryHashEntry(
                                itemId = item.id,
                                hash = hash,
                                sizeBytes = item.sizeBytes,
                                sortDate = item.sortDate,
                                keepPriority = keepPriorityOf(item),
                            )
                        )
                    } else {
                        skipped++
                    }
                }
                job.checkYield()
                job.report(end, candidates.size)
            }
            val groups = AppGalleryDuplicateDetector.group(
                entries = entries,
                threshold = safeConfig.duplicateHammingThreshold,
                hashSize = safeConfig.duplicateHashSize,
            )
            onFinish?.invoke(
                AppGalleryDuplicateScanResult(
                    groups = groups,
                    hashedCount = entries.size,
                    skippedCount = skipped,
                )
            )
        }
    }

    /** 保留优先级：收藏 > 已评分 > 已打标签 > 普通。 */
    private fun keepPriorityOf(item: AppGalleryItem): Int {
        var priority = 0
        if (item.isFavorite) priority += 4
        if (item.rating > 0) priority += 2
        if (item.tags.isNotEmpty()) priority += 1
        return priority
    }

    // ── 回收站自动清空（相册项 7） ───────────────────────────────

    /**
     * 到期自动清空（带节流：间隔见 [AppGalleryConfig.trashPurgeCheckIntervalHours]；
     * 不满足间隔则直接结束且不清任何东西）。
     */
    fun submitTrashPurge(
        context: Context,
        now: Long = System.currentTimeMillis(),
        config: AppGalleryConfig = AppGalleryConfig(),
        onFinish: ((Int) -> Unit)? = null,
    ): BgJob {
        val appContext = context.applicationContext
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        val settings = AppGallerySettingsStore.getInstance(appContext)
        val trashStore = AppGalleryTrashStore(appContext, settings)
        return BgJobRunner.submit(
            name = "回收站到期清理",
            chunkSize = safeConfig.batchOperationBatchSize,
            type = taskType,
            priority = BgJobPriority.LOW,
        ) { job ->
            if (!safeConfig.trashAutoPurge) {
                job.checkYield()
                onFinish?.invoke(0)
                return@submit
            }
            val lastRun = settings.lastTrashPurgeAt()
            if (!AppGalleryTrashRules.shouldRunPurge(lastRun, now, safeConfig.trashPurgeCheckIntervalHours)) {
                job.checkYield()
                onFinish?.invoke(0)
                return@submit
            }
            val entries = settings.loadTrash()
            val plan = AppGalleryTrashRules.buildCleanupPlan(entries, now, safeConfig)
            if (plan.isNoop) {
                settings.setLastTrashPurgeAt(now)
                job.checkYield()
                onFinish?.invoke(0)
                return@submit
            }
            val done = ArrayList<String>(plan.expired.size)
            for (index in plan.expired.indices step job.batchSizeFor(plan.expired.size)) {
                val end = minOf(index + job.batchSizeFor(plan.expired.size), plan.expired.size)
                for (i in index until end) {
                    val entry = plan.expired[i]
                    val target = File(entry.trashUri)
                    if (runCatching { !target.exists() || target.deleteRecursively() }.getOrDefault(false)) {
                        done.add(entry.itemId)
                    }
                }
                job.checkYield()
                job.report(end, plan.expired.size)
            }
            settings.saveTrash(settings.loadTrash().filterNot { done.contains(it.itemId) })
            settings.updateAnnotations { AppGalleryAnnotationMachine.drop(it, done) }
            settings.setLastTrashPurgeAt(now)
            onFinish?.invoke(done.size)
        }
    }

    // ── 统计（相册项 14） ───────────────────────────────────────

    /** 统计计算（轻量，仍走队列以免大库阻塞前台）。 */
    fun submitStats(
        items: List<AppGalleryItem>,
        granularity: TimelineGranularity = TimelineGranularity.MONTH,
        config: AppGalleryConfig = AppGalleryConfig(),
        onFinish: ((AppGalleryStatsReport) -> Unit)? = null,
    ): BgJob {
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        return BgJobRunner.submit(
            name = "拍摄统计",
            chunkSize = safeConfig.batchOperationBatchSize,
            type = taskType,
            priority = BgJobPriority.LOW,
        ) { job ->
            // 分片累加（不整表驻留中间结果）：按桶逐步合并
            val chunkSize = job.batchSizeFor(items.size)
            var report = AppGalleryStats.compute(emptyList(), granularity, safeConfig)
            for (index in items.indices step chunkSize) {
                val end = minOf(index + chunkSize, items.size)
                val slice = items.subList(index, end)
                val sliceReport = AppGalleryStats.compute(slice, granularity, safeConfig)
                report = mergeReports(report, sliceReport, granularity)
                job.checkYield()
                job.report(end, items.size)
            }
            onFinish?.invoke(report)
        }
    }

    private fun mergeReports(
        a: AppGalleryStatsReport,
        b: AppGalleryStatsReport,
        granularity: TimelineGranularity,
    ): AppGalleryStatsReport {
        val bucketMap = LinkedHashMap<String, AppGalleryStatsBucket>()
        (a.buckets + b.buckets).forEach { bucket ->
            val existing = bucketMap[bucket.key]
            bucketMap[bucket.key] = if (existing == null) {
                bucket
            } else {
                existing.copy(
                    count = existing.count + bucket.count,
                    totalBytes = existing.totalBytes + bucket.totalBytes,
                    countByCategory = mergeIntMaps(existing.countByCategory, bucket.countByCategory),
                    bytesByCategory = mergeLongMaps(existing.bytesByCategory, bucket.bytesByCategory),
                )
            }
        }
        return AppGalleryStatsReport(
            granularity = granularity,
            buckets = bucketMap.values.sortedByDescending { it.startMillis },
            totalCount = a.totalCount + b.totalCount,
            totalBytes = a.totalBytes + b.totalBytes,
            countByCategory = mergeIntMaps(a.countByCategory, b.countByCategory),
            bytesByCategory = mergeLongMaps(a.bytesByCategory, b.bytesByCategory),
        )
    }

    private fun mergeLongMaps(
        a: Map<AppGalleryCategory, Long>,
        b: Map<AppGalleryCategory, Long>,
    ): Map<AppGalleryCategory, Long> {
        val out = LinkedHashMap<AppGalleryCategory, Long>(a)
        b.forEach { (key, value) -> out[key] = (out[key] ?: 0L) + value }
        return out
    }

    private fun mergeIntMaps(
        a: Map<AppGalleryCategory, Int>,
        b: Map<AppGalleryCategory, Int>,
    ): Map<AppGalleryCategory, Int> {
        val out = LinkedHashMap<AppGalleryCategory, Int>(a)
        b.forEach { (key, value) -> out[key] = (out[key] ?: 0) + value }
        return out
    }

    // ── 备份 / 转存（相册项 18） ────────────────────────────────

    /**
     * 导出 zip 或复制到中转目录（用户目录由集成阶段的 SAF 授权流程提供，见补丁）。
     * 流式写入，缓冲固定 64KB，天然满足内存约束。
     */
    fun submitBackup(
        context: Context,
        items: List<AppGalleryItem>,
        shape: AppGalleryExportShape = AppGalleryExportShape.ZIP,
        config: AppGalleryConfig = AppGalleryConfig(),
        onFinish: ((AppGalleryBackupResult) -> Unit)? = null,
    ): BgJob {
        val appContext = context.applicationContext
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        val plan = AppGalleryExportPlanner.build(items, shape, safeConfig)
        return BgJobRunner.submit(
            name = if (shape == AppGalleryExportShape.ZIP) "相册备份打包" else "相册转存",
            chunkSize = safeConfig.batchOperationBatchSize,
            type = taskType,
            priority = BgJobPriority.LOW,
        ) { job ->
            val dir = AppGalleryStorageLayout.backupDir(appContext)
            if (plan.isEmpty) {
                job.checkYield()
                onFinish?.invoke(AppGalleryBackupResult(null, 0, 0L, 0))
                return@submit
            }
            var failed = 0
            var written = 0
            var bytes = 0L
            val outputPath: String?
            if (shape == AppGalleryExportShape.ZIP) {
                val zipFile = File(dir, plan.targetName)
                zipFile.delete()
                ZipOutputStream(FileOutputStream(zipFile).buffered(STREAM_BUFFER_BYTES)).use { zip ->
                    zip.setLevel(safeConfig.exportZipCompressionLevel)
                    var pending = 0
                    plan.entries.forEach { entry ->
                        val copied = copyInto(zip, appContext, entry)
                        if (copied >= 0) {
                            written++
                            bytes += copied
                        } else {
                            failed++
                        }
                        pending++
                        if (pending >= job.batchSizeFor(plan.entries.size)) {
                            job.checkYield()
                            job.report(written + failed, plan.entries.size)
                            pending = 0
                        }
                    }
                }
                outputPath = zipFile.absolutePath
            } else {
                val targetDir = File(dir, plan.targetName)
                if (!targetDir.exists()) targetDir.mkdirs()
                var pending = 0
                plan.entries.forEach { entry ->
                    val out = File(targetDir, entry.entryName)
                    val copied = copyToFile(appContext, entry, out)
                    if (copied >= 0) {
                        written++
                        bytes += copied
                    } else {
                        failed++
                    }
                    pending++
                    if (pending >= job.batchSizeFor(plan.entries.size)) {
                        job.checkYield()
                        job.report(written + failed, plan.entries.size)
                        pending = 0
                    }
                }
                outputPath = targetDir.absolutePath
            }
            job.checkYield()
            onFinish?.invoke(AppGalleryBackupResult(outputPath, written, bytes, failed))
        }
    }

    /** 写一个 zip 条目；返回写入字节数，失败返回 -1。 */
    private fun copyInto(zip: ZipOutputStream, context: Context, entry: AppGalleryExportEntry): Long {
        return runCatching {
            val input = openInput(context, entry.sourceUri) ?: return -1L
            input.use { source ->
                zip.putNextEntry(ZipEntry(entry.entryName))
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    zip.write(buffer, 0, read)
                    total += read
                }
                zip.closeEntry()
                total
            }
        }.getOrDefault(-1L)
    }

    private fun copyToFile(context: Context, entry: AppGalleryExportEntry, out: File): Long {
        return runCatching {
            val input = openInput(context, entry.sourceUri) ?: return -1L
            input.use { source ->
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { target ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        target.write(buffer, 0, read)
                        total += read
                    }
                    total
                }
            }
        }.getOrDefault(-1L)
    }

    private fun openInput(context: Context, uri: String): java.io.InputStream? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase()) {
            "content", "android.resource" ->
                runCatching { context.contentResolver.openInputStream(parsed) }.getOrNull()
            else -> parsed.path?.let { path ->
                val file = File(path)
                if (file.exists()) file.inputStream() else null
            }
        }
    }

    // ── 存储清理（相册项 15） ───────────────────────────────────

    /**
     * 一键清理：把计划内的条目逐条移入回收站（不物理删除，符合「先入回收站」要求）。
     */
    fun submitStorageCleanup(
        context: Context,
        items: List<AppGalleryItem>,
        categories: Set<AppGalleryCategory> = emptySet(),
        maxBytes: Long? = null,
        protectFavorites: Boolean = true,
        config: AppGalleryConfig = AppGalleryConfig(),
        onFinish: ((Int) -> Unit)? = null,
    ): BgJob {
        val appContext = context.applicationContext
        val safeConfig = AppGalleryConfig.clampAndLog(config, TAG)
        val settings = AppGallerySettingsStore.getInstance(appContext)
        val trashStore = AppGalleryTrashStore(appContext, settings)
        val plan = AppGalleryStats.buildCleanupPlan(
            items = items,
            categories = categories,
            maxBytes = maxBytes,
            protectFavorites = protectFavorites,
        )
        return BgJobRunner.submit(
            name = "存储占用清理",
            chunkSize = safeConfig.batchOperationBatchSize,
            type = taskType,
            priority = BgJobPriority.NORMAL,
        ) { job ->
            var moved = 0
            val targets = plan.targets
            for (index in targets.indices step job.batchSizeFor(targets.size)) {
                val end = minOf(index + job.batchSizeFor(targets.size), targets.size)
                for (i in index until end) {
                    val result = trashStore.moveToTrash(targets[i], safeConfig)
                    if (result.success) moved++
                }
                job.checkYield()
                job.report(end, targets.size)
            }
            onFinish?.invoke(moved)
        }
    }

    private const val TAG = "AppGalleryBgTasks"
}
