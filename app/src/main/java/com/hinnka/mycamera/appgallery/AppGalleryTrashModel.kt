package com.hinnka.mycamera.appgallery

import java.util.concurrent.TimeUnit

/** 回收站条目状态。 */
enum class AppGalleryTrashEntryState {
    /** 还在保留期内且距到期 > 阈值。 */
    ACTIVE,

    /** 即将到期（剩余天数 ≤ 预警阈值）。 */
    EXPIRING_SOON,

    /** 已到期（等待清理）。 */
    EXPIRED,
}

/**
 * 回收站条目（相册项 7：删除 → 回收站 → 恢复；到期自动清空）。
 *
 * 说明：工程内既有 `GalleryManager.deletePhoto` 是**物理删除**（连同 photoId 目录一起删），
 * 因此本模块的删除流程为：先把产物文件移动到回收站目录（记录 [trashUri]），再从 DB 摘除索引；
 * 恢复时把文件移回原路径并重新写回 DB 元数据（补丁说明里给出挂点）。
 */
data class AppGalleryTrashEntry(
    val itemId: String,
    val displayName: String,
    /** 原始 URI（恢复时的目标）。 */
    val originalUri: String,
    /** 回收站内的文件路径。 */
    val trashUri: String,
    val category: AppGalleryCategory = AppGalleryCategory.NORMAL,
    val sizeBytes: Long = 0L,
    /** 删除时间（毫秒）。 */
    val deletedAt: Long,
    /** 到期时间（毫秒，入库时按 [AppGalleryConfig.trashRetentionDays] 算出）。 */
    val expiresAt: Long,
    /** 原属相册（恢复时还原）。 */
    val originalFolderIds: List<String> = emptyList(),
    /** 原收藏/标签/评分（恢复时还原）。 */
    val annotation: AppGalleryAnnotation? = null,
    /** 归属来源（APP_DATABASE 走 DB 摘除+回写；SYSTEM_MEDIA_STORE 只搬文件）。 */
    val source: AppGalleryItemSource = AppGalleryItemSource.APP_DATABASE,
) {
    val isVideoCategory: Boolean get() = category.isVideoCategory
}

/** 回收站概览。 */
data class AppGalleryTrashSummary(
    val count: Int,
    val totalBytes: Long,
    val expiredCount: Int,
    val expiredBytes: Long,
    val expiringSoonCount: Int,
    /** 最早删除时间（空站为 null）。 */
    val oldestDeletedAt: Long?,
)

/** 到期清理计划。 */
data class AppGalleryTrashCleanupPlan(
    val expired: List<AppGalleryTrashEntry>,
    val totalBytes: Long,
    /** 分片处理用的批大小（来自配置）。 */
    val batchSize: Int,
) {
    val isNoop: Boolean get() = expired.isEmpty()
}

/**
 * 回收站规则（纯函数，可 JVM 单测）。
 *
 * 保留期边界定义：`now >= expiresAt` 即视为「已到期」，即到期当天那一刻起可被清空；
 * 剩余天数按向上取整显示（还剩 0.1 天 → 显示 1 天）。
 */
object AppGalleryTrashRules {

    const val DAY_MILLIS = 86_400_000L

    /** 即将到期的预警阈值（天，可经配置覆盖）。 */
    const val DEFAULT_EXPIRING_SOON_DAYS = 3

    /** 计算到期时间（保留期越界自动 clamp）。 */
    fun expiresAt(
        deletedAt: Long,
        retentionDays: Int = AppGalleryConfig().trashRetentionDays,
    ): Long {
        val days = retentionDays.coerceIn(
            AppGalleryConfig.TRASH_RETENTION_MIN_DAYS,
            AppGalleryConfig.TRASH_RETENTION_MAX_DAYS,
        )
        return deletedAt + days * DAY_MILLIS
    }

    /** 是否已到期（边界：`now == expiresAt` 视为已到期）。 */
    fun isExpired(entry: AppGalleryTrashEntry, now: Long): Boolean = now >= entry.expiresAt

    /** 剩余天数（向上取整；已到期返回 0）。 */
    fun remainingDays(entry: AppGalleryTrashEntry, now: Long): Long {
        val remainingMillis = entry.expiresAt - now
        if (remainingMillis <= 0L) return 0L
        return (remainingMillis + DAY_MILLIS - 1L) / DAY_MILLIS
    }

    /** 状态判定。 */
    fun stateOf(
        entry: AppGalleryTrashEntry,
        now: Long,
        expiringSoonDays: Int = DEFAULT_EXPIRING_SOON_DAYS,
    ): AppGalleryTrashEntryState {
        if (isExpired(entry, now)) return AppGalleryTrashEntryState.EXPIRED
        val threshold = expiringSoonDays.coerceAtLeast(0).toLong()
        return if (remainingDays(entry, now) <= threshold) {
            AppGalleryTrashEntryState.EXPIRING_SOON
        } else {
            AppGalleryTrashEntryState.ACTIVE
        }
    }

    /** 全部到期条目（按删除时间升序，先删先清）。 */
    fun expiredEntries(entries: List<AppGalleryTrashEntry>, now: Long): List<AppGalleryTrashEntry> =
        entries.filter { isExpired(it, now) }.sortedBy { it.deletedAt }

    /** 即将到期条目。 */
    fun expiringSoonEntries(
        entries: List<AppGalleryTrashEntry>,
        now: Long,
        expiringSoonDays: Int = DEFAULT_EXPIRING_SOON_DAYS,
    ): List<AppGalleryTrashEntry> = entries
        .filter { stateOf(it, now, expiringSoonDays) == AppGalleryTrashEntryState.EXPIRING_SOON }
        .sortedBy { it.expiresAt }

    /** 到期清理计划（相册项 7 的自动清空 / 后台任务入参）。 */
    fun buildCleanupPlan(
        entries: List<AppGalleryTrashEntry>,
        now: Long,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): AppGalleryTrashCleanupPlan {
        val safeConfig = AppGalleryConfig.clamp(config)
        val expired = expiredEntries(entries, now)
        return AppGalleryTrashCleanupPlan(
            expired = expired,
            totalBytes = expired.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            batchSize = safeConfig.batchOperationBatchSize,
        )
    }

    /** 概览统计。 */
    fun summary(
        entries: List<AppGalleryTrashEntry>,
        now: Long,
        expiringSoonDays: Int = DEFAULT_EXPIRING_SOON_DAYS,
    ): AppGalleryTrashSummary {
        val expired = expiredEntries(entries, now)
        val soon = expiringSoonEntries(entries, now, expiringSoonDays)
        return AppGalleryTrashSummary(
            count = entries.size,
            totalBytes = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            expiredCount = expired.size,
            expiredBytes = expired.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            expiringSoonCount = soon.size,
            oldestDeletedAt = entries.minOfOrNull { it.deletedAt },
        )
    }

    /**
     * 是否该跑一次自动清空（相册项 7 的后台任务节流）。
     * 从未跑过（lastRunAt <= 0）→ true；否则距离上次超过间隔 → true。
     */
    fun shouldRunPurge(
        lastRunAt: Long,
        now: Long,
        intervalHours: Int = AppGalleryConfig().trashPurgeCheckIntervalHours,
    ): Boolean {
        if (!(now > 0L)) return false
        if (lastRunAt <= 0L) return true
        if (now < lastRunAt) return true // 时钟回拨：保守重跑
        val interval = intervalHours.coerceIn(
            AppGalleryConfig.TRASH_PURGE_INTERVAL_MIN_HOURS,
            AppGalleryConfig.TRASH_PURGE_INTERVAL_MAX_HOURS,
        ).toLong() * TimeUnit.HOURS.toMillis(1)
        return now - lastRunAt >= interval
    }
}
