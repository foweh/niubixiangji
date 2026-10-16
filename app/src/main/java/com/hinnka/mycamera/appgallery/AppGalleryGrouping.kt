package com.hinnka.mycamera.appgallery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 浏览视图模式（相册项 1：网格 / 列表 / 时间线 / 年历）。 */
enum class AppGalleryViewMode(
    val labelZh: String,
) {
    /** 网格（列数可配）。 */
    GRID("网格"),

    /** 列表（行高可配，显示文件名/时间/体积/分类徽标）。 */
    LIST("列表"),

    /** 时间线（按日/月聚类，带吸顶分组头）。 */
    TIMELINE("时间线"),

    /** 年历（按年 → 月 → 日格子，格内显示张数）。 */
    YEAR_CALENDAR("年历");

    companion object {
        fun fromPersistedName(name: String?): AppGalleryViewMode =
            entries.firstOrNull { it.name == name } ?: GRID
    }
}

/** 时间聚类粒度（时间线/统计共用）。 */
enum class TimelineGranularity(
    val labelZh: String,
) {
    DAY("日"),
    MONTH("月"),
    YEAR("年");

    companion object {
        fun fromPersistedName(name: String?): TimelineGranularity =
            entries.firstOrNull { it.name == name } ?: DAY
    }
}

/** 视图记忆条目（相册项 1：视图切换记忆）。 */
data class AppGalleryViewMemory(
    /** 作用域：全库用 [AppGalleryViewMemoryKeeper.SCOPE_ALL]，其余为相册/文件夹 id。 */
    val scopeId: String,
    val viewMode: AppGalleryViewMode = AppGalleryViewMode.GRID,
    val sortField: AppGallerySortField = AppGallerySortField.DATE,
    val sortOrder: AppGallerySortOrder = AppGallerySortOrder.DESCENDING,
    val timelineGranularity: TimelineGranularity = TimelineGranularity.DAY,
    /** 滚动锚点条目 id（切回时定位）。 */
    val scrollAnchorId: String? = null,
    /** 最后使用时间（用于 LRU 淘汰）。 */
    val updatedAt: Long = 0L,
)

/**
 * 视图记忆管理（纯逻辑：LRU 上限、按作用域查找、排序状态同步）。
 */
object AppGalleryViewMemoryKeeper {
    const val SCOPE_ALL = "all"

    /**
     * 写入/更新一条记忆，并按 [slots] 做 LRU 淘汰。
     * 刚写入的那条**永不**被淘汰（即使 slots=1）。
     */
    fun upsert(
        memories: List<AppGalleryViewMemory>,
        memory: AppGalleryViewMemory,
        slots: Int = AppGalleryConfig().viewMemorySlots,
    ): List<AppGalleryViewMemory> {
        val limit = slots.coerceIn(
            AppGalleryConfig.VIEW_MEMORY_SLOTS_MIN,
            AppGalleryConfig.VIEW_MEMORY_SLOTS_MAX,
        )
        val merged = memories.filterNot { it.scopeId == memory.scopeId } + memory
        if (merged.size <= limit) return merged
        val keep = merged.sortedByDescending { it.updatedAt }.take(limit).toMutableList()
        if (keep.none { it.scopeId == memory.scopeId }) {
            // slots=1 之类极端场景：保证新记忆落盘
            keep.removeAt(keep.lastIndex)
            keep.add(memory)
        }
        return keep
    }

    fun find(memories: List<AppGalleryViewMemory>, scopeId: String): AppGalleryViewMemory? =
        memories.firstOrNull { it.scopeId == scopeId }

    /** 取生效记忆：命中返回，未命中返回按配置构造的默认记忆。 */
    fun resolve(
        memories: List<AppGalleryViewMemory>,
        scopeId: String,
        config: AppGalleryConfig,
    ): AppGalleryViewMemory {
        find(memories, scopeId)?.let { return it }
        return AppGalleryViewMemory(
            scopeId = scopeId,
            viewMode = config.defaultViewMode,
            sortField = config.defaultSortField,
            sortOrder = if (config.defaultSortAscending) {
                AppGallerySortOrder.ASCENDING
            } else {
                AppGallerySortOrder.DESCENDING
            },
            timelineGranularity = config.timelineGranularity,
        )
    }
}

/** 时间线分组。 */
data class AppGalleryTimeGroup(
    /** 可排序的 key，如 `2026` / `2026-10` / `2026-10-16`。 */
    val key: String,
    /** 该组起始时刻（本地时区）。 */
    val startMillis: Long,
    /** 组内条目（沿用输入顺序）。 */
    val items: List<AppGalleryItem>,
) {
    val count: Int get() = items.size

    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
}

/**
 * 时间聚类（按日/月/年）：输出按时间倒序的分组，空输入返回空列表。
 */
object AppGalleryTimeline {

    fun keyFor(millis: Long, granularity: TimelineGranularity, zoneId: ZoneId): String {
        val date = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return when (granularity) {
            TimelineGranularity.DAY -> "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
            TimelineGranularity.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
            TimelineGranularity.YEAR -> "%04d".format(date.year)
        }
    }

    fun startMillisFor(millis: Long, granularity: TimelineGranularity, zoneId: ZoneId): Long {
        val date = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        val start = when (granularity) {
            TimelineGranularity.DAY -> date
            TimelineGranularity.MONTH -> date.withDayOfMonth(1)
            TimelineGranularity.YEAR -> date.withDayOfYear(1)
        }
        return start.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    /**
     * 聚类并倒序输出。分组依据为 [AppGalleryItem.sortDate]。
     */
    fun group(
        items: List<AppGalleryItem>,
        granularity: TimelineGranularity = TimelineGranularity.DAY,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<AppGalleryTimeGroup> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<AppGalleryItem>>()
        items.forEach { item ->
            val key = keyFor(item.sortDate, granularity, zoneId)
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }
        return grouped.entries
            .map { (key, groupItems) ->
                AppGalleryTimeGroup(
                    key = key,
                    startMillis = startMillisFor(groupItems.first().sortDate, granularity, zoneId),
                    items = groupItems,
                )
            }
            .sortedByDescending { it.startMillis }
    }
}

/** 年历中「有内容的某一天」。 */
data class AppGalleryYearCell(
    /** `2026-10-16`。 */
    val dateKey: String,
    /** 该天 00:00 的毫秒。 */
    val startMillis: Long,
    val count: Int,
    val totalBytes: Long,
    /** 代表图（该天最新一条）。 */
    val coverItemId: String?,
)

/** 年历中的一个年份桶。 */
data class AppGalleryYearBucket(
    val year: Int,
    val totalCount: Int,
    val totalBytes: Long,
    /** 稀疏格子（仅含有点击内容的日期），UI 按 dateKey 放入 12×31 网格。 */
    val cells: List<AppGalleryYearCell>,
)

/**
 * 年历视图聚合（纯逻辑）：年 → 稀疏日格子。
 */
object AppGalleryYearCalendar {

    fun build(
        items: List<AppGalleryItem>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<AppGalleryYearBucket> {
        if (items.isEmpty()) return emptyList()
        // 先按天聚合
        val dayBuckets = LinkedHashMap<String, MutableList<AppGalleryItem>>()
        items.forEach { item ->
            val key = AppGalleryTimeline.keyFor(item.sortDate, TimelineGranularity.DAY, zoneId)
            dayBuckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        val cells = dayBuckets.map { (dateKey, dayItems) ->
            val latest = dayItems.maxByOrNull { it.sortDate }
            AppGalleryYearCell(
                dateKey = dateKey,
                startMillis = AppGalleryTimeline.startMillisFor(
                    dayItems.first().sortDate,
                    TimelineGranularity.DAY,
                    zoneId,
                ),
                count = dayItems.size,
                totalBytes = dayItems.sumOf { it.sizeBytes },
                coverItemId = latest?.id,
            )
        }
        return cells
            .groupBy { it.dateKey.substringBefore('-').toIntOrNull() ?: 0 }
            .map { (year, yearCells) ->
                AppGalleryYearBucket(
                    year = year,
                    totalCount = yearCells.sumOf { it.count },
                    totalBytes = yearCells.sumOf { it.totalBytes },
                    cells = yearCells.sortedByDescending { it.startMillis },
                )
            }
            .sortedByDescending { it.year }
    }

    /** 解析 `2026-10-16` → [LocalDate]（非法返回 null，UI 布局时跳过）。 */
    fun parseDateKey(dateKey: String, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? =
        runCatching { LocalDate.parse(dateKey) }.getOrNull()
}
