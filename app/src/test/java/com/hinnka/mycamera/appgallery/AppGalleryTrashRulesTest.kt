package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站保留期边界与清空策略（相册项 7）。
 * 纯 JVM 测试。
 */
class AppGalleryTrashRulesTest {

    private val day = AppGalleryTrashRules.DAY_MILLIS
    private val config = AppGalleryConfig()

    private fun entry(
        id: String,
        deletedAt: Long,
        retentionDays: Int = 30,
        size: Long = 1_000L,
        category: AppGalleryCategory = AppGalleryCategory.NORMAL,
    ) = AppGalleryTrashEntry(
        itemId = id,
        displayName = "$id.jpg",
        originalUri = "file:///photos/$id/original.jpg",
        trashUri = "/trash/$id",
        category = category,
        sizeBytes = size,
        deletedAt = deletedAt,
        expiresAt = AppGalleryTrashRules.expiresAt(deletedAt, retentionDays),
    )

    // ── 保留期计算 ──────────────────────────────────────────────

    @Test
    fun `到期时间等于删除时间加保留天数`() {
        assertEquals(1_000L + 30 * day, AppGalleryTrashRules.expiresAt(1_000L, 30))
        assertEquals(1_000L + day, AppGalleryTrashRules.expiresAt(1_000L, 1))
    }

    @Test
    fun `保留天数被 clamp 到合法范围`() {
        assertEquals(1, AppGalleryTrashRules.expiresAt(0L, 0) / day)
        assertEquals(AppGalleryConfig.TRASH_RETENTION_MAX_DAYS.toLong(), AppGalleryTrashRules.expiresAt(0L, 99_999) / day)
    }

    // ── 边界：恰好到期即视为过期 ────────────────────────────────

    @Test
    fun `边界 到期前一毫秒未过期恰到期即过期`() {
        val item = entry("a", deletedAt = 0L, retentionDays = 30)
        val expireAt = item.expiresAt
        assertFalse(AppGalleryTrashRules.isExpired(item, expireAt - 1))
        assertTrue(AppGalleryTrashRules.isExpired(item, expireAt))
        assertTrue(AppGalleryTrashRules.isExpired(item, expireAt + 1))
    }

    @Test
    fun `剩余天数向上取整且过期后为 0`() {
        val item = entry("a", deletedAt = 0L, retentionDays = 10)
        assertEquals(10L, AppGalleryTrashRules.remainingDays(item, now = 0L))
        // 剩 1 毫秒也算 1 天（向上取整）
        assertEquals(1L, AppGalleryTrashRules.remainingDays(item, now = item.expiresAt - 1))
        assertEquals(0L, AppGalleryTrashRules.remainingDays(item, now = item.expiresAt))
        assertEquals(0L, AppGalleryTrashRules.remainingDays(item, now = item.expiresAt + day))
    }

    @Test
    fun `条目状态覆盖活跃 即将到期 已过期`() {
        val item = entry("a", deletedAt = 0L, retentionDays = 10)
        assertEquals(AppGalleryTrashEntryState.EXPIRED, AppGalleryTrashRules.stateOf(item, item.expiresAt))
        assertEquals(
            AppGalleryTrashEntryState.EXPIRING_SOON,
            AppGalleryTrashRules.stateOf(item, item.expiresAt - day),
        )
        assertEquals(
            AppGalleryTrashEntryState.ACTIVE,
            AppGalleryTrashRules.stateOf(item, item.expiresAt - 5 * day),
        )
    }

    @Test
    fun `过期筛选与即将到期筛选互不重叠`() {
        val entries = listOf(
            entry("expired", deletedAt = 0L, retentionDays = 1),
            entry("soon", deletedAt = 0L, retentionDays = 10),
            entry("fresh", deletedAt = 0L, retentionDays = 30),
        )
        val now = 2 * day
        assertEquals(listOf("expired"), AppGalleryTrashRules.expiredEntries(entries, now).map { it.itemId })
        assertEquals(listOf("soon"), AppGalleryTrashRules.expiringSoonEntries(entries, now, 8).map { it.itemId })
    }

    // ── 清理计划与概览 ──────────────────────────────────────────

    @Test
    fun `清理计划只包含过期项并给出批大小`() {
        val entries = listOf(
            entry("expired", 0L, 1, size = 100L),
            entry("fresh", 0L, 30, size = 900L),
        )
        val plan = AppGalleryTrashRules.buildCleanupPlan(entries, now = 2 * day, config = config)
        assertEquals(1, plan.expired.size)
        assertEquals(100L, plan.totalBytes)
        assertEquals(config.batchOperationBatchSize, plan.batchSize)
        assertFalse(plan.isNoop)

        val empty = AppGalleryTrashRules.buildCleanupPlan(entries, now = 0L, config = config)
        assertTrue(empty.isNoop)
        assertEquals(0, empty.expired.size)
    }

    @Test
    fun `概览统计条数与占用`() {
        val entries = listOf(
            entry("a", 0L, 1, size = 100L),
            entry("b", 0L, 10, size = 200L),
        )
        val summary = AppGalleryTrashRules.summary(entries, now = 2 * day, expiringSoonDays = 9)
        assertEquals(2, summary.count)
        assertEquals(300L, summary.totalBytes)
        assertEquals(1, summary.expiredCount)
        assertEquals(100L, summary.expiredBytes)
        assertEquals(1, summary.expiringSoonCount)
        assertEquals(0L, summary.oldestDeletedAt)
    }

    @Test
    fun `空回收站概览安全`() {
        val summary = AppGalleryTrashRules.summary(emptyList(), now = 123L)
        assertEquals(0, summary.count)
        assertEquals(0L, summary.totalBytes)
        assertTrue(summary.oldestDeletedAt == null || summary.oldestDeletedAt == 0L)
    }

    // ── 自动清空节流 ────────────────────────────────────────────

    @Test
    fun `自动清空节流与时钟回拨`() {
        val intervalHours = config.trashPurgeCheckIntervalHours
        // 从未跑过 → 立即跑
        assertTrue(AppGalleryTrashRules.shouldRunPurge(0L, now = 1_000L, intervalHours = intervalHours))
        // 未到间隔 → 不跑
        assertFalse(
            AppGalleryTrashRules.shouldRunPurge(
                lastRunAt = 10_000L,
                now = 10_000L + intervalHours * 3_600_000L / 2,
                intervalHours = intervalHours,
            )
        )
        // 恰好到间隔 → 跑
        assertTrue(
            AppGalleryTrashRules.shouldRunPurge(
                lastRunAt = 10_000L,
                now = 10_000L + intervalHours * 3_600_000L,
                intervalHours = intervalHours,
            )
        )
        // 时钟回拨 → 保守重跑
        assertTrue(AppGalleryTrashRules.shouldRunPurge(lastRunAt = 10_000L, now = 5_000L, intervalHours = intervalHours))
        // now 非法 → 不跑
        assertFalse(AppGalleryTrashRules.shouldRunPurge(lastRunAt = 10_000L, now = 0L, intervalHours = intervalHours))
    }

    // ── 编解码 ──────────────────────────────────────────────────

    @Test
    fun `回收站编解码往返保持字段`() {
        val entries = listOf(
            entry("a", deletedAt = 1_700_000_000_000L, retentionDays = 7, size = 4_096L, category = AppGalleryCategory.RAW_DNG)
                .copy(
                    originalFolderIds = listOf("folder_1", "folder_2"),
                    annotation = AppGalleryAnnotation("a", isFavorite = true, rating = 3, tags = listOf("星空"), updatedAt = 9L),
                    source = AppGalleryItemSource.APP_DATABASE,
                ),
        )
        val decoded = AppGalleryTrashCodec.decode(AppGalleryTrashCodec.encode(entries))
        assertEquals(1, decoded.size)
        val item = decoded.first()
        assertEquals("a", item.itemId)
        assertEquals(AppGalleryCategory.RAW_DNG, item.category)
        assertEquals(4_096L, item.sizeBytes)
        assertEquals(listOf("folder_1", "folder_2"), item.originalFolderIds)
        assertTrue(item.annotation?.isFavorite == true)
        assertEquals(3, item.annotation?.rating)
        assertEquals(listOf("星空"), item.annotation?.tags)
    }

    @Test
    fun `回收站编解码容忍脏数据`() {
        assertEquals(emptyList<AppGalleryTrashEntry>(), AppGalleryTrashCodec.decode(null))
        assertEquals(emptyList<AppGalleryTrashEntry>(), AppGalleryTrashCodec.decode(""))
        // 字段不足的记录（只有 2 个字段）、空 itemId 的记录一律跳过，不崩溃
        val dirty = buildString {
            append("a").append(AppGalleryCodec.FIELD_SEPARATOR).append("name")
            append(AppGalleryCodec.RECORD_SEPARATOR)
            append(AppGalleryCodec.FIELD_SEPARATOR).append("空 id")
            append(AppGalleryCodec.RECORD_SEPARATOR)
            append("截断记录")
        }
        val decoded = AppGalleryTrashCodec.decode(dirty)
        assertNotNull(decoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `回收站条目按分类判断视频类型`() {
        val video = entry("v", 0L, category = AppGalleryCategory.VIDEO)
        assertTrue(video.isVideoCategory)
        assertFalse(entry("p", 0L).isVideoCategory)
    }
}
