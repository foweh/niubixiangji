package com.hinnka.mycamera.appgallery

import java.time.ZoneId

/** 某个类型/时间桶的占用统计。 */
data class AppGalleryUsageEntry(
    val category: AppGalleryCategory,
    val count: Int,
    val bytes: Long,
    /** 占总量百分比（0..100；总量为 0 时为 0）。 */
    val percent: Float,
)

/** 单个时间桶的统计。 */
data class AppGalleryStatsBucket(
    /** `2026` / `2026-10` / `2026-10-16`。 */
    val key: String,
    val startMillis: Long,
    val count: Int,
    val totalBytes: Long,
    /** 各类型张数（仅含 >0 的项）。 */
    val countByCategory: Map<AppGalleryCategory, Int>,
    /** 各类型占用（仅含 >0 的项）。 */
    val bytesByCategory: Map<AppGalleryCategory, Long>,
)

/** 拍摄统计报告（相册项 14/15）。 */
data class AppGalleryStatsReport(
    val granularity: TimelineGranularity,
    /** 时间桶（倒序）。 */
    val buckets: List<AppGalleryStatsBucket>,
    val totalCount: Int,
    val totalBytes: Long,
    val countByCategory: Map<AppGalleryCategory, Int>,
    val bytesByCategory: Map<AppGalleryCategory, Long>,
) {
    /** 按占用降序的类型明细（相册项 15：按类型统计 + 一键清理）。 */
    fun usageByCategory(descending: Boolean = true): List<AppGalleryUsageEntry> {
        val entries = countByCategory.keys
            .union(bytesByCategory.keys)
            .map { category ->
                val count = countByCategory[category] ?: 0
                val bytes = bytesByCategory[category] ?: 0L
                AppGalleryUsageEntry(
                    category = category,
                    count = count,
                    bytes = bytes,
                    percent = if (totalBytes <= 0L) 0f else (bytes * 100f / totalBytes),
                )
            }
        return if (descending) entries.sortedByDescending { it.bytes } else entries
    }

    val isEmpty: Boolean get() = totalCount == 0
}

/** 清理计划（先入回收站，由调用方执行）。 */
data class AppGalleryCleanupPlan(
    /** 待清理条目（体积降序）。 */
    val targets: List<AppGalleryItem>,
    val totalBytes: Long,
    /** 因受保护（收藏/正在编辑/文件缺失）被跳过的条数。 */
    val skippedCount: Int,
) {
    val isNoop: Boolean get() = targets.isEmpty()
}

/**
 * 拍摄统计与存储统计（纯函数，可 JVM 单测）。
 *
 * 规则：
 * - 体积为负的条目按 0 计（不污染总占用）。
 * - 时间桶用条目 [AppGalleryItem.sortDate] 归类，键格式与 [AppGalleryTimeline] 一致。
 * - 视频是否入统计由 [AppGalleryConfig.statsIncludeVideos] 决定。
 */
object AppGalleryStats {

    fun compute(
        items: List<AppGalleryItem>,
        granularity: TimelineGranularity = TimelineGranularity.MONTH,
        config: AppGalleryConfig = AppGalleryConfig(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): AppGalleryStatsReport {
        val safeConfig = AppGalleryConfig.clamp(config)
        val scoped = items.filter { safeConfig.statsIncludeVideos || !it.isVideo }

        val countByCategory = LinkedHashMap<AppGalleryCategory, Int>()
        val bytesByCategory = LinkedHashMap<AppGalleryCategory, Long>()
        var totalBytes = 0L

        val bucketCount = LinkedHashMap<String, Int>()
        val bucketBytes = LinkedHashMap<String, Long>()
        val bucketCategoryCount = LinkedHashMap<String, MutableMap<AppGalleryCategory, Int>>()
        val bucketCategoryBytes = LinkedHashMap<String, MutableMap<AppGalleryCategory, Long>>()
        val bucketStart = LinkedHashMap<String, Long>()

        scoped.forEach { item ->
            val bytes = item.sizeBytes.coerceAtLeast(0L)
            totalBytes += bytes
            countByCategory[item.category] = (countByCategory[item.category] ?: 0) + 1
            bytesByCategory[item.category] = (bytesByCategory[item.category] ?: 0L) + bytes

            val key = AppGalleryTimeline.keyFor(item.sortDate, granularity, zoneId)
            bucketCount[key] = (bucketCount[key] ?: 0) + 1
            bucketBytes[key] = (bucketBytes[key] ?: 0L) + bytes
            bucketStart.getOrPut(key) {
                AppGalleryTimeline.startMillisFor(item.sortDate, granularity, zoneId)
            }
            val catCount = bucketCategoryCount.getOrPut(key) { LinkedHashMap() }
            catCount[item.category] = (catCount[item.category] ?: 0) + 1
            val catBytes = bucketCategoryBytes.getOrPut(key) { LinkedHashMap() }
            catBytes[item.category] = (catBytes[item.category] ?: 0L) + bytes
        }

        val buckets = bucketStart.entries
            .map { (key, start) ->
                AppGalleryStatsBucket(
                    key = key,
                    startMillis = start,
                    count = bucketCount[key] ?: 0,
                    totalBytes = bucketBytes[key] ?: 0L,
                    countByCategory = bucketCategoryCount[key]?.toMap() ?: emptyMap(),
                    bytesByCategory = bucketCategoryBytes[key]?.toMap() ?: emptyMap(),
                )
            }
            .sortedByDescending { it.startMillis }

        return AppGalleryStatsReport(
            granularity = granularity,
            buckets = buckets,
            totalCount = scoped.size,
            totalBytes = totalBytes,
            countByCategory = countByCategory,
            bytesByCategory = bytesByCategory,
        )
    }

    /**
     * 生成清理计划（相册项 15：按类型统计 + 一键清理选中项，先入回收站）。
     *
     * @param categories 选中的类型（空集 = 不限类型）。
     * @param maxBytes 清理目标上限（<=0 或 null = 不限，全部命中）。
     * @param protectFavorites 是否保护收藏项（默认 true）。
     * @param oldestFirst true=先清理最旧（默认，逐条删除时视觉稳定）；false=先清理最大。
     */
    fun buildCleanupPlan(
        items: List<AppGalleryItem>,
        categories: Set<AppGalleryCategory> = emptySet(),
        maxBytes: Long? = null,
        protectFavorites: Boolean = true,
        oldestFirst: Boolean = false,
    ): AppGalleryCleanupPlan {
        val candidates = items.asSequence()
            .filter { categories.isEmpty() || it.category in categories }
            .filter { !protectFavorites || !it.isFavorite }
            .sortedWith(
                if (oldestFirst) compareBy { it.sortDate }
                else compareByDescending { it.sizeBytes }
            )
            .toList()

        val protectedCount = items.count { item ->
            (categories.isEmpty() || item.category in categories) && protectFavorites && item.isFavorite
        }

        val budget = maxBytes?.takeIf { it > 0L }
        val targets = mutableListOf<AppGalleryItem>()
        var accumulated = 0L
        if (budget == null) {
            targets.addAll(candidates)
            accumulated = candidates.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        } else {
            for (candidate in candidates) {
                if (accumulated >= budget) break
                targets.add(candidate)
                accumulated += candidate.sizeBytes.coerceAtLeast(0L)
            }
        }
        return AppGalleryCleanupPlan(
            targets = targets,
            totalBytes = accumulated,
            skippedCount = protectedCount,
        )
    }

    /** 缓存/缩略图等可再生成资源的占用估算（用于清理页展示「可立即释放」）。 */
    fun regenerableBytes(items: List<AppGalleryItem>): Long =
        items.sumOf { it.sizeBytes.coerceAtLeast(0L) / 8L }
}
