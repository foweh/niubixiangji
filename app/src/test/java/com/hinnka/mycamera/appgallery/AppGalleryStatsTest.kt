package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 拍摄统计与清理计划（相册项 14 / 15）：正常、边界、异常三类用例。
 * 纯 JVM 测试，固定时区保证结果确定。
 */
class AppGalleryStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val config = AppGalleryConfig()

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun item(
        id: String,
        date: Long,
        size: Long = 1_000L,
        category: AppGalleryCategory = AppGalleryCategory.NORMAL,
        favorite: Boolean = false,
        video: Boolean = false,
    ) = AppGalleryItem(
        id = id,
        displayName = "$id.jpg",
        uri = "file:///photos/$id/original.jpg",
        category = category,
        dateAdded = date,
        sizeBytes = size,
        isFavorite = favorite,
        kind = if (video) AppGalleryMediaKind.VIDEO else AppGalleryMediaKind.IMAGE,
    )

    // ── 正常 ────────────────────────────────────────────────────

    @Test
    fun `按天聚合张数与占用`() {
        val items = listOf(
            item("a", millis(2026, 10, 16), size = 100L, category = AppGalleryCategory.RAW_DNG),
            item("b", millis(2026, 10, 16, 20), size = 200L),
            item("c", millis(2026, 10, 15), size = 300L),
        )
        val report = AppGalleryStats.compute(items, TimelineGranularity.DAY, config, zone)
        assertEquals(3, report.totalCount)
        assertEquals(600L, report.totalBytes)
        assertEquals(2, report.buckets.size)
        // 桶按时间倒序
        assertEquals("2026-10-16", report.buckets.first().key)
        assertEquals(2, report.buckets.first().count)
        assertEquals(300L, report.buckets.first().totalBytes)
        assertEquals(1, report.buckets.first().countByCategory[AppGalleryCategory.RAW_DNG] ?: 0)
        assertEquals(AppGalleryCategory.NORMAL, report.countByCategory.maxByOrNull { it.value }?.key)
    }

    @Test
    fun `按月与按年聚类得到不同粒度`() {
        val items = listOf(
            item("a", millis(2026, 10, 16)),
            item("b", millis(2026, 9, 30)),
            item("c", millis(2025, 1, 1)),
        )
        assertEquals(
            listOf("2026-10", "2026-09", "2025-01"),
            AppGalleryStats.compute(items, TimelineGranularity.MONTH, config, zone).buckets.map { it.key },
        )
        assertEquals(
            listOf("2026", "2025"),
            AppGalleryStats.compute(items, TimelineGranularity.YEAR, config, zone).buckets.map { it.key },
        )
    }

    @Test
    fun `占用分布按类型汇总`() {
        val items = listOf(
            item("a", millis(2026, 10, 16), size = 100L, category = AppGalleryCategory.RAW_DNG),
            item("b", millis(2026, 10, 16), size = 250L, category = AppGalleryCategory.RAW_DNG),
            item("c", millis(2026, 10, 16), size = 400L, category = AppGalleryCategory.VIDEO, video = true),
        )
        val report = AppGalleryStats.compute(items, TimelineGranularity.DAY, config, zone)
        val usage = report.usageByCategory()
        // 按占用降序
        assertEquals(AppGalleryCategory.VIDEO, usage.first().category)
        val raw = usage.first { it.category == AppGalleryCategory.RAW_DNG }
        assertEquals(2, raw.count)
        assertEquals(350L, raw.bytes)
        assertEquals(350f * 100f / 750f, raw.percent, 0.01f)
        // 占比之和约等于 100
        assertTrue(usage.sumOf { it.percent.toDouble() } in 99.0..101.0)
        assertFalse(report.isEmpty)
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    fun `空输入返回空统计而不抛异常`() {
        val report = AppGalleryStats.compute(emptyList(), TimelineGranularity.DAY, config, zone)
        assertEquals(0, report.totalCount)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.buckets.isEmpty())
        assertTrue(report.countByCategory.isEmpty())
        assertTrue(report.usageByCategory.isEmpty())
    }

    @Test
    fun `跨日边界按本地时区切分`() {
        // 10-16 23:59 与 10-17 00:01 属于两个不同的「日」桶
        val before = LocalDateTime.of(2026, 10, 16, 23, 59).atZone(zone).toInstant().toEpochMilli()
        val after = LocalDateTime.of(2026, 10, 17, 0, 1).atZone(zone).toInstant().toEpochMilli()
        val report = AppGalleryStats.compute(
            listOf(item("a", before), item("b", after)),
            TimelineGranularity.DAY,
            config,
            zone,
        )
        assertEquals(listOf("2026-10-17", "2026-10-16"), report.buckets.map { it.key })
        assertTrue(report.buckets.first().startMillis > report.buckets.last().startMillis)
    }

    @Test
    fun `时区参数影响聚类结果`() {
        val instant = LocalDateTime.of(2026, 10, 16, 23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val shanghai = AppGalleryStats.compute(listOf(item("a", instant)), TimelineGranularity.DAY, config, zone)
        val utc = AppGalleryStats.compute(
            listOf(item("a", instant)),
            TimelineGranularity.DAY,
            config,
            ZoneId.of("UTC"),
        )
        assertEquals("2026-10-17", shanghai.buckets.first().key)
        assertEquals("2026-10-16", utc.buckets.first().key)
    }

    @Test
    fun `单条统计与其他条数一致`() {
        val report = AppGalleryStats.compute(
            listOf(item("only", millis(2026, 10, 16), size = 42L)),
            TimelineGranularity.YEAR,
            config,
            zone,
        )
        assertEquals(1, report.totalCount)
        assertEquals(1, report.buckets.size)
        assertEquals(42L, report.buckets.first().totalBytes)
    }

    // ── 异常 / 脏数据 ───────────────────────────────────────────

    @Test
    fun `负数体积按 0 计不入负数`() {
        val report = AppGalleryStats.compute(
            listOf(item("a", millis(2026, 10, 16), size = -500L), item("b", millis(2026, 10, 16), size = 100L)),
            TimelineGranularity.DAY,
            config,
            zone,
        )
        assertEquals(100L, report.totalBytes)
        assertEquals(0L, report.buckets.first().bytesByCategory.values.sum())
        assertEquals(100L, report.buckets.first().totalBytes)
    }

    @Test
    fun `时间戳为 0 的脏数据不导致崩溃`() {
        val report = AppGalleryStats.compute(
            listOf(item("a", 0L), item("b", millis(2026, 10, 16))),
            TimelineGranularity.DAY,
            config,
            zone,
        )
        assertEquals(2, report.totalCount)
        assertEquals(2, report.buckets.size)
    }

    @Test
    fun `关闭视频统计后视频不计入`() {
        val items = listOf(
            item("a", millis(2026, 10, 16), size = 100L),
            item("v", millis(2026, 10, 16), size = 9_000L, category = AppGalleryCategory.VIDEO, video = true),
        )
        val withVideos = AppGalleryStats.compute(items, TimelineGranularity.DAY, config, zone)
        val withoutVideos = AppGalleryStats.compute(
            items,
            TimelineGranularity.DAY,
            config.copy(statsIncludeVideos = false),
            zone,
        )
        assertEquals(2, withVideos.totalCount)
        assertEquals(1, withoutVideos.totalCount)
        assertEquals(100L, withoutVideos.totalBytes)
    }

    // ── 清理计划（相册项 15） ───────────────────────────────────

    @Test
    fun `清理计划按体积降序取到预算为止`() {
        val items = listOf(
            item("big", millis(2026, 10, 16), size = 600L),
            item("mid", millis(2026, 10, 15), size = 300L),
            item("small", millis(2026, 10, 14), size = 100L),
        )
        val plan = AppGalleryStats.buildCleanupPlan(items, maxBytes = 900L)
        assertEquals(listOf("big", "mid", "small"), plan.targets.map { it.id })
        assertEquals(1_000L, plan.totalBytes)
        assertEquals(0, plan.skippedCount)

        val tight = AppGalleryStats.buildCleanupPlan(items, maxBytes = 700L)
        assertEquals(listOf("big", "mid"), tight.targets.map { it.id })
        assertEquals(900L, tight.totalBytes)
    }

    @Test
    fun `清理计划默认保护收藏项`() {
        val items = listOf(
            item("fav", millis(2026, 10, 16), size = 800L, favorite = true),
            item("plain", millis(2026, 10, 15), size = 200L),
        )
        val guarded = AppGalleryStats.buildCleanupPlan(items, maxBytes = null)
        assertEquals(listOf("plain"), guarded.targets.map { it.id })
        assertEquals(1, guarded.skippedCount)

        val unguarded = AppGalleryStats.buildCleanupPlan(items, maxBytes = null, protectFavorites = false)
        assertEquals(2, unguarded.targets.size)
        assertEquals(0, unguarded.skippedCount)
    }

    @Test
    fun `清理计划按类型筛选与最旧优先`() {
        val items = listOf(
            item("raw1", millis(2026, 10, 10), size = 100L, category = AppGalleryCategory.RAW_DNG),
            item("raw2", millis(2026, 10, 12), size = 200L, category = AppGalleryCategory.RAW_DNG),
            item("jpg", millis(2026, 10, 11), size = 300L),
        )
        val onlyRaw = AppGalleryStats.buildCleanupPlan(
            items,
            categories = setOf(AppGalleryCategory.RAW_DNG),
            maxBytes = null,
            oldestFirst = true,
        )
        assertEquals(listOf("raw1", "raw2"), onlyRaw.targets.map { it.id })
        assertFalse(onlyRaw.targets.any { it.category != AppGalleryCategory.RAW_DNG })
    }

    @Test
    fun `清理计划预算为 0 或负数视为不限`() {
        val items = listOf(item("a", 1_000L, size = 10L), item("b", 2_000L, size = 20L))
        assertEquals(2, AppGalleryStats.buildCleanupPlan(items, maxBytes = 0L).targets.size)
        assertEquals(2, AppGalleryStats.buildCleanupPlan(items, maxBytes = -100L).targets.size)
    }

    @Test
    fun `可再生成资源估算取八分之一`() {
        val items = listOf(item("a", 1_000L, size = 800L), item("b", 2_000L, size = 1_000L))
        assertEquals(225L, AppGalleryStats.regenerableBytes(items))
        assertEquals(0L, AppGalleryStats.regenerableBytes(emptyList()))
    }
}
