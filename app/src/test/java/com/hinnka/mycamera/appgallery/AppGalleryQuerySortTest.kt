package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索语法解析、筛选与排序（相册项 6）。
 * 纯 JVM 测试。
 */
class AppGalleryQuerySortTest {

    private val config = AppGalleryConfig()

    private fun item(
        id: String,
        name: String = "IMG_$id.jpg",
        category: AppGalleryCategory = AppGalleryCategory.NORMAL,
        date: Long = 1_700_000_000_000L,
        size: Long = 1_000_000L,
        rating: Int = 0,
        favorite: Boolean = false,
        tags: List<String> = emptyList(),
        folderIds: List<String> = emptyList(),
        video: Boolean = false,
        lat: Double? = null,
        lon: Double? = null,
    ) = AppGalleryItem(
        id = id,
        displayName = name,
        uri = "file:///photos/$id/original.jpg",
        category = category,
        dateAdded = date,
        sizeBytes = size,
        rating = rating,
        isFavorite = favorite,
        tags = tags,
        folderIds = folderIds,
        kind = if (video) AppGalleryMediaKind.VIDEO else AppGalleryMediaKind.IMAGE,
        latitude = lat,
        longitude = lon,
    )

    // ── 搜索语法 ────────────────────────────────────────────────

    @Test
    fun `type 语法支持英文别名与中文标签`() {
        assertTrue(
            AppGallerySearchParser.parse("type:raw", AppGalleryQuery(), config)
                .categories.contains(AppGalleryCategory.RAW_DNG)
        )
        assertTrue(
            AppGallerySearchParser.parse("type:超分", AppGalleryQuery(), config)
                .categories.contains(AppGalleryCategory.SUPER_RES)
        )
        assertTrue(
            AppGallerySearchParser.parse("type:hdr,star", AppGalleryQuery(), config)
                .categories.containsAll(setOf(AppGalleryCategory.HDR, AppGalleryCategory.ASTRO_TRAIL))
        )
    }

    @Test
    fun `fav 语法两种写法都识别`() {
        assertTrue(AppGallerySearchParser.parse("fav:1", AppGalleryQuery(), config).favoritesOnly)
        assertTrue(AppGallerySearchParser.parse("fav", AppGalleryQuery(), config).favoritesOnly)
        assertTrue(AppGallerySearchParser.parse("收藏", AppGalleryQuery(), config).favoritesOnly)
        assertFalse(AppGallerySearchParser.parse("fav:0", AppGalleryQuery(), config).favoritesOnly)
    }

    @Test
    fun `rating 语法被 clamp 到最大评分`() {
        assertEquals(3, AppGallerySearchParser.parse("rating:3", AppGalleryQuery(), config).minRating)
        assertEquals(5, AppGallerySearchParser.parse("rating:99", AppGalleryQuery(), config).minRating)
        assertEquals(0, AppGallerySearchParser.parse("rating:-2", AppGalleryQuery(), config).minRating)
    }

    @Test
    fun `tag 语法支持中英文与多个标签`() {
        val query = AppGallerySearchParser.parse("tag:星空 tag:光绘", AppGalleryQuery(), config)
        assertEquals(setOf("星空", "光绘"), query.tags)
        assertFalse(query.tagMatchAll)
    }

    @Test
    fun `since 与 until 语法解析成时间区间且自动纠正反向区间`() {
        val query = AppGallerySearchParser.parse("since:2026-01-01 until:2026-01-31", AppGalleryQuery(), config)
        assertNotNull(query.dateFrom)
        assertNotNull(query.dateTo)
        assertTrue(query.dateFrom!! < query.dateTo!!)

        val reversed = AppGalleryQuery.clamp(
            AppGalleryQuery(dateFrom = 2_000L, dateTo = 1_000L),
            config,
        )
        assertEquals(1_000L, reversed.dateFrom ?: -1L)
        assertEquals(2_000L, reversed.dateTo ?: -1L)
    }

    @Test
    fun `size 语法支持比较符与单位`() {
        assertEquals(10L * 1024 * 1024 + 1, AppGallerySearchParser.parse("size:>10mb", AppGalleryQuery(), config).minSizeBytes ?: -1L)
        assertEquals(10L * 1024 * 1024, AppGallerySearchParser.parse("size:>=10m", AppGalleryQuery(), config).minSizeBytes ?: -1L)
        assertEquals(1024L - 1, AppGallerySearchParser.parse("size:<1kb", AppGalleryQuery(), config).maxSizeBytes ?: -1L)
    }

    @Test
    fun `非法语法回落为自由文本检索`() {
        val query = AppGallerySearchParser.parse("IMG_0001 since:notadate", AppGalleryQuery(), config)
        assertTrue(query.text.contains("IMG_0001"))
        assertTrue(query.text.contains("since:notadate"))
        assertFalse(query.isActive.not())
    }

    @Test
    fun `is 语法区分图片与视频`() {
        assertTrue(
            AppGallerySearchParser.parse("is:video", AppGalleryQuery(), config)
                .kinds.contains(AppGalleryMediaKind.VIDEO)
        )
        assertTrue(
            AppGallerySearchParser.parse("is:照片", AppGalleryQuery(), config)
                .kinds.contains(AppGalleryMediaKind.IMAGE)
        )
    }

    // ── 筛选 ────────────────────────────────────────────────────

    @Test
    fun `筛选按类型收藏评分标签组合生效`() {
        val items = listOf(
            item("1", category = AppGalleryCategory.RAW_DNG, favorite = true, rating = 5, tags = listOf("星空")),
            item("2", category = AppGalleryCategory.RAW_DNG, favorite = false, rating = 1, tags = listOf("光绘")),
            item("3", category = AppGalleryCategory.HDR, favorite = true, rating = 5, tags = listOf("星空", "光绘")),
        )
        val query = AppGalleryQuery(
            categories = setOf(AppGalleryCategory.RAW_DNG),
            favoritesOnly = true,
            minRating = 3,
            tags = setOf("星空"),
        )
        val result = AppGalleryFilter.apply(items, query, config)
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun `标签 ALL 模式要求全部命中`() {
        val items = listOf(
            item("1", tags = listOf("星空", "光绘")),
            item("2", tags = listOf("星空")),
        )
        val any = AppGalleryFilter.apply(items, AppGalleryQuery(tags = setOf("星空", "光绘")), config)
        val all = AppGalleryFilter.apply(
            items,
            AppGalleryQuery(tags = setOf("星空", "光绘"), tagMatchAll = true),
            config,
        )
        assertEquals(2, any.size)
        assertEquals(listOf("1"), all.map { it.id })
    }

    @Test
    fun `自由文本同时搜索名称类型与标签`() {
        val items = listOf(
            item("1", name = "IMG_0001_superres.jpg", category = AppGalleryCategory.SUPER_RES, tags = listOf("精选")),
            item("2", name = "IMG_0002.jpg"),
        )
        assertEquals(listOf("1"), AppGalleryFilter.apply(items, AppGallerySearchParser.parse("精选", AppGalleryQuery(), config), config).map { it.id })
        assertEquals(listOf("1"), AppGalleryFilter.apply(items, AppGallerySearchParser.parse("超分", AppGalleryQuery(), config), config).map { it.id })
        assertEquals(2, AppGalleryFilter.apply(items, AppGallerySearchParser.parse("img", AppGalleryQuery(), config), config).size)
    }

    @Test
    fun `大小写敏感开关生效`() {
        val items = listOf(item("1", name = "Photo.JPG"))
        val insensitive = AppGalleryFilter.apply(items, AppGalleryQuery(text = "photo.j"), config)
        val sensitive = AppGalleryFilter.apply(
            items,
            AppGalleryQuery(text = "photo.j"),
            config.copy(searchCaseSensitive = true),
        )
        assertEquals(1, insensitive.size)
        assertEquals(0, sensitive.size)
    }

    @Test
    fun `文件夹与体积区间筛选`() {
        val items = listOf(
            item("1", size = 500L, folderIds = listOf("folder_a")),
            item("2", size = 2_000L, folderIds = listOf("folder_b")),
        )
        assertEquals(
            listOf("1"),
            AppGalleryFilter.apply(items, AppGalleryQuery(folderId = "folder_a"), config).map { it.id },
        )
        assertEquals(
            listOf("2"),
            AppGalleryFilter.apply(items, AppGalleryQuery(minSizeBytes = 1_000L), config).map { it.id },
        )
    }

    // ── 排序 ────────────────────────────────────────────────────

    @Test
    fun `按时间与大小排序`() {
        val items = listOf(
            item("a", date = 300L, size = 30L),
            item("b", date = 100L, size = 10L),
            item("c", date = 200L, size = 20L),
        )
        assertEquals(
            listOf("a", "c", "b"),
            AppGallerySorter.sort(items, AppGallerySortSpec(AppGallerySortField.DATE, AppGallerySortOrder.DESCENDING)).map { it.id },
        )
        assertEquals(
            listOf("b", "c", "a"),
            AppGallerySorter.sort(items, AppGallerySortSpec(AppGallerySortField.SIZE, AppGallerySortOrder.ASCENDING)).map { it.id },
        )
    }

    @Test
    fun `名称排序使用自然序而不是字典序`() {
        val items = listOf(
            item("x", name = "IMG_10.jpg"),
            item("y", name = "IMG_2.jpg"),
            item("z", name = "IMG_1.jpg"),
        )
        assertEquals(
            listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_10.jpg"),
            AppGallerySorter.sort(items, AppGallerySortSpec(AppGallerySortField.NAME, AppGallerySortOrder.ASCENDING))
                .map { it.displayName },
        )
        assertTrue(AppGallerySorter.compareNatural("IMG_2", "IMG_10") < 0)
        assertTrue(AppGallerySorter.compareNatural("IMG_10", "IMG_2") > 0)
        assertEquals(0, AppGallerySorter.compareNatural("IMG_2", "img_2"))
    }

    @Test
    fun `排序稳定：同值按 id 兜底`() {
        val items = listOf(
            item("b", date = 100L),
            item("a", date = 100L),
        )
        assertEquals(
            listOf("a", "b"),
            AppGallerySorter.sort(items, AppGallerySortSpec(AppGallerySortField.DATE, AppGallerySortOrder.DESCENDING))
                .map { it.id },
        )
    }

    @Test
    fun `排序可叠加筛选且不修改入参`() {
        val source = listOf(item("a", favorite = true), item("b", favorite = false))
        val filtered = AppGalleryFilter.apply(source, AppGalleryQuery(favoritesOnly = true), config)
        val sorted = AppGallerySorter.sort(filtered, AppGallerySortSpec())
        assertEquals(1, sorted.size)
        assertEquals(2, source.size)
        assertTrue(AppGalleryQuery(favoritesOnly = true).isActive)
        assertFalse(AppGalleryQuery().isActive)
    }
}
