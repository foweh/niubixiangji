package com.hinnka.mycamera.appgallery

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导出形态（相册项 9 / 18）。 */
enum class AppGalleryExportShape {
    /** 打包成 zip 落到用户目录（备份/转存）。 */
    ZIP,

    /** 逐文件复制到用户目录。 */
    COPY_TO_DIRECTORY,
}

/** 单条导出项。 */
data class AppGalleryExportEntry(
    val itemId: String,
    /** 源文件 URI。 */
    val sourceUri: String,
    /** 输出文件名（已清洗 + 去重，含扩展名）。 */
    val entryName: String,
    val bytes: Long,
    val category: AppGalleryCategory,
)

/** 导出/分享计划。 */
data class AppGalleryExportPlan(
    val shape: AppGalleryExportShape,
    /** 输出目标名（zip 名或目录内前缀）。 */
    val targetName: String,
    val entries: List<AppGalleryExportEntry>,
    val totalBytes: Long,
    /** 因 URI 非法/文件缺失被跳过的条目 id。 */
    val skippedIds: List<String>,
    /** 失败原因（阻塞时非空）。 */
    val blockedReason: String? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val count: Int get() = entries.size
    val isBlocked: Boolean get() = blockedReason != null

    /** 可展示的摘要。 */
    fun summaryText(): String = when {
        isBlocked -> blockedReason.orEmpty()
        isEmpty -> "没有可导出的条目"
        else -> "共 $count 项 · ${AppGalleryItem.formatBytes(totalBytes)}"
    }
}

/**
 * 导出 / 分享计划器（纯函数，可 JVM 单测）。
 *
 * 命名规则（全部可配）：
 * - 模式 [AppGalleryConfig.exportFileNamePattern] 支持 `{date}` `{name}` `{index}` `{category}` 占位符；
 * - 扩展名从源文件名继承（缺失时按分类回退：视频 .mp4、RAW .dng、GIF .gif、其余 .jpg）；
 * - 重名自动追加 `_1`、`_2`…；
 * - 非法字符（`\\ / : * ? " < > |` 与控制符）替换为 `_`，长度截断到 [AppGalleryConfig.exportMaxNameLength]。
 */
object AppGalleryExportPlanner {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** 冲突后缀预留长度：`_` + 最多 4 位序号。 */
    private const val CONFLICT_SUFFIX_LENGTH = 5

    /** 文件名主干的最小长度（避免预算被扩展名吃光）。 */
    private const val MIN_STEM_LENGTH = 8

    fun build(
        items: List<AppGalleryItem>,
        shape: AppGalleryExportShape = AppGalleryExportShape.ZIP,
        config: AppGalleryConfig = AppGalleryConfig(),
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        targetName: String? = null,
    ): AppGalleryExportPlan {
        val safeConfig = AppGalleryConfig.clamp(config)
        val resolvedTarget = sanitizeFileName(
            targetName ?: defaultTargetName(shape, now, zoneId),
            safeConfig.exportMaxNameLength,
        )
        val used = LinkedHashSet<String>()
        val entries = ArrayList<AppGalleryExportEntry>()
        val skipped = ArrayList<String>()

        items.forEachIndexed { index, item ->
            if (item.uri.isBlank()) {
                skipped.add(item.id)
                return@forEachIndexed
            }
            val name = entryNameFor(item, safeConfig, index, used, zoneId)
            entries.add(
                AppGalleryExportEntry(
                    itemId = item.id,
                    sourceUri = item.uri,
                    entryName = name,
                    bytes = item.sizeBytes.coerceAtLeast(0L),
                    category = item.category,
                )
            )
        }

        val blocked = null
        return AppGalleryExportPlan(
            shape = shape,
            targetName = resolvedTarget,
            entries = entries,
            totalBytes = entries.sumOf { it.bytes },
            skippedIds = skipped,
            blockedReason = blocked,
        )
    }

    /** 生成一个唯一输出名（并登记到 [used]；重名追加 `_1`、`_2`…）。 */
    fun entryNameFor(
        item: AppGalleryItem,
        config: AppGalleryConfig,
        index: Int,
        used: MutableSet<String>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val safeConfig = AppGalleryConfig.clamp(config)
        val extension = extensionFor(item)
        val stem = buildStem(item, safeConfig, index, zoneId, extension)
        var candidate = "$stem.$extension"
        var counter = 1
        while (!used.add(candidate.lowercase())) {
            candidate = "$stem" + "_" + counter + "." + extension
            counter++
            if (counter > 9999) break
        }
        return candidate
    }

    /** 组装不含扩展名与冲突后缀的文件名主干。 */
    private fun buildStem(
        item: AppGalleryItem,
        config: AppGalleryConfig,
        index: Int,
        zoneId: ZoneId,
        extension: String,
    ): String {
        val rawName = item.displayName.substringBeforeLast('.', item.displayName)
        val date = Instant.ofEpochMilli(item.sortDate).atZone(zoneId).toLocalDateTime()
        // 预算：总上限 − 扩展名 − 冲突后缀（"_" + 4 位数字）
        val maxStem = (config.exportMaxNameLength - extension.length - CONFLICT_SUFFIX_LENGTH)
            .coerceAtLeast(MIN_STEM_LENGTH)
        val rendered = config.exportFileNamePattern
            .replace("{date}", DATE_FORMAT.format(date))
            .replace("{name}", rawName)
            .replace("{index}", index.toString())
            .replace("{category}", item.category.labelZh)
        val sanitized = sanitizeFileName(rendered, maxStem)
        return sanitized.ifBlank { "photon_" + date.toLocalDate().toString() }
    }

    /** 扩展名：优先继承源文件名，其次按分类回退。 */
    fun extensionFor(item: AppGalleryItem): String {
        val fromName = item.displayName.substringAfterLast('.', "").lowercase()
        if (fromName.isNotEmpty() && fromName.length <= 5 && fromName.all { it.isLetterOrDigit() }) {
            return fromName
        }
        return when (item.category) {
            AppGalleryCategory.VIDEO, AppGalleryCategory.LOG_VIDEO -> "mp4"
            AppGalleryCategory.RAW_DNG -> "dng"
            AppGalleryCategory.GIF -> "gif"
            AppGalleryCategory.PANORAMA -> "jpg"
            else -> "jpg"
        }
    }

    /** 去掉非法字符、控制符与路径分隔符；截断到 [maxLength]。 */
    fun sanitizeFileName(
        raw: String,
        maxLength: Int = AppGalleryConfig().exportMaxNameLength,
    ): String {
        val limit = maxLength.coerceAtLeast(1)
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
        return cleaned.take(limit).trim().let { if (it.isEmpty()) "" else it }
    }

    private fun defaultTargetName(shape: AppGalleryExportShape, now: Long, zoneId: ZoneId): String {
        val stamp = DATE_FORMAT.format(Instant.ofEpochMilli(now).atZone(zoneId).toLocalDateTime())
        return when (shape) {
            AppGalleryExportShape.ZIP -> "PhotonCamera_$stamp.zip"
            AppGalleryExportShape.COPY_TO_DIRECTORY -> "PhotonCamera_$stamp"
        }
    }

    /**
     * 分享 MIME 列表（相册项 9：分享 intent 入口）。
     * 混合内容返回通配型（`*` / `*`），单一类型返回具体 MIME。
     */
    fun shareMimeTypes(items: List<AppGalleryItem>): List<String> {
        if (items.isEmpty()) return listOf("*/*")
        val types = items.map { mimeFor(it) }.distinct()
        return if (types.size == 1) types else listOf("*/*")
    }

    fun mimeFor(item: AppGalleryItem): String = when (item.category) {
        AppGalleryCategory.VIDEO, AppGalleryCategory.LOG_VIDEO -> "video/mp4"
        AppGalleryCategory.GIF -> "image/gif"
        AppGalleryCategory.RAW_DNG -> "image/x-adobe-dng"
        else -> "image/jpeg"
    }

    /** 分享主题（多种子通道用）。 */
    fun shareSubject(items: List<AppGalleryItem>): String = when {
        items.isEmpty() -> "PhotonCamera"
        items.size == 1 -> items.first().displayName
        else -> "PhotonCamera · ${items.size} 项"
    }

    /** 单条分享/导出是否需要走「先导出再分享」的重编码路径（RAW/DNG 与未落盘项）。 */
    fun requiresMaterialization(item: AppGalleryItem): Boolean =
        item.category == AppGalleryCategory.RAW_DNG || item.uri.startsWith("content://")
}
