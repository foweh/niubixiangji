package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * 支撑层回归：配置 clamp / 配置编解码 / 视图记忆 / 显示策略 / 导出计划 /
 * 相册模型 / 地点聚类 / 预览跳转契约 / 输出目录归类。
 * 纯 JVM 测试（不触碰 Android API）。
 */
class AppGallerySupportTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun item(
        id: String,
        name: String = "$id.jpg",
        category: AppGalleryCategory = AppGalleryCategory.NORMAL,
        size: Long = 1_000L,
        date: Long = 1_700_000_000_000L,
        uri: String = "file:///photos/$id/$name",
    ) = AppGalleryItem(
        id = id,
        displayName = name,
        uri = uri,
        category = category,
        dateAdded = date,
        sizeBytes = size,
    )

    // ── 配置 clamp ──────────────────────────────────────────────

    @Test
    fun `配置越界值被 clamp 到合法区间`() {
        val wild = AppGalleryConfig(
            gridColumnCount = 999,
            listRowHeightDp = 1,
            trashRetentionDays = 0,
            trashPurgeCheckIntervalHours = 9_999,
            thumbnailMaxEdgePx = 10,
            thumbnailQuality = 1,
            maxRating = 99,
            maxTagCount = 0,
            maxTagLength = 0,
            duplicateHammingThreshold = -3,
            viewMemorySlots = 0,
            batchOperationBatchSize = 10_000,
        )
        val safe = AppGalleryConfig.clamp(wild)
        assertEquals(AppGalleryConfig.GRID_COLUMN_MAX, safe.gridColumnCount)
        assertEquals(AppGalleryConfig.LIST_ROW_HEIGHT_MIN_DP, safe.listRowHeightDp)
        assertEquals(AppGalleryConfig.TRASH_RETENTION_MIN_DAYS, safe.trashRetentionDays)
        assertEquals(AppGalleryConfig.TRASH_PURGE_INTERVAL_MAX_HOURS, safe.trashPurgeCheckIntervalHours)
        assertEquals(AppGalleryConfig.THUMBNAIL_EDGE_MIN, safe.thumbnailMaxEdgePx)
        assertEquals(AppGalleryConfig.THUMBNAIL_QUALITY_MIN, safe.thumbnailQuality)
        assertEquals(AppGalleryConfig.MAX_RATING_MAX, safe.maxRating)
        assertEquals(AppGalleryConfig.MAX_TAG_COUNT_MIN, safe.maxTagCount)
        assertEquals(AppGalleryConfig.MAX_TAG_LENGTH_MIN, safe.maxTagLength)
        assertEquals(0, safe.duplicateHammingThreshold)
        assertEquals(AppGalleryConfig.VIEW_MEMORY_SLOTS_MIN, safe.viewMemorySlots)
        assertEquals(AppGalleryConfig.BATCH_SIZE_MAX, safe.batchOperationBatchSize)
    }

    @Test
    fun `配置 clamp 对合法值保持不变且可重复调用`() {
        val config = AppGalleryConfig(gridColumnCount = 4, trashRetentionDays = 45)
        assertEquals(config, AppGalleryConfig.clamp(config))
        assertEquals(AppGalleryConfig.clamp(config), AppGalleryConfig.clamp(AppGalleryConfig.clamp(config)))
    }

    @Test
    fun `异常值被替换为安全默认`() {
        val safe = AppGalleryConfig.clamp(AppGalleryConfig(backupTargetFolderName = "!!!"))
        assertTrue(safe.backupTargetFolderName.isNotEmpty())
        assertTrue(safe.backupTargetFolderName.none { it in "\\/:*?\"<>|" })
    }

    // ── 配置编解码（外部文件覆盖 / DataStore 载荷） ────────────

    @Test
    fun `配置编解码往返一致`() {
        val config = AppGalleryConfig(
            themeMode = AppGalleryThemeMode.DARK,
            defaultViewMode = AppGalleryViewMode.TIMELINE,
            gridColumnCount = 3,
            trashRetentionDays = 60,
            maxRating = 4,
        )
        val payload = AppGalleryConfigCodec.encode(config)
        assertTrue(payload.isNotEmpty())
        assertEquals(config, AppGalleryConfigCodec.decode(payload, AppGalleryConfig()))
    }

    @Test
    fun `配置解码忽略未知键与注释并 clamp 越界值`() {
        val payload = buildString {
            appendLine("# 注释行")
            appendLine("unknown_key=whatever")
            appendLine("gridColumnCount=999")
            appendLine("trashRetentionDays=abc")
            appendLine("")
        }
        val decoded = AppGalleryConfigCodec.decode(payload, AppGalleryConfig())
        assertEquals(AppGalleryConfig.GRID_COLUMN_MAX, decoded.gridColumnCount)
        // 非法数字回落为基线值
        assertEquals(AppGalleryConfig().trashRetentionDays, decoded.trashRetentionDays)
    }

    @Test
    fun `配置解码容忍空载荷`() {
        assertEquals(AppGalleryConfig(), AppGalleryConfigCodec.decode(null, AppGalleryConfig()))
        assertEquals(AppGalleryConfig(), AppGalleryConfigCodec.decode("", AppGalleryConfig()))
    }

    // ── 视图记忆（相册项 1：视图切换记忆） ─────────────────────

    @Test
    fun `视图记忆按作用域覆盖写入`() {
        val memory = AppGalleryViewMemory(
            scopeId = AppGalleryViewMemoryKeeper.SCOPE_ALL,
            viewMode = AppGalleryViewMode.YEAR_CALENDAR,
            timelineGranularity = TimelineGranularity.MONTH,
            scrollAnchorId = "id7",
            updatedAt = 10L,
        )
        var memories = AppGalleryViewMemoryKeeper.upsert(emptyList(), memory)
        assertEquals(1, memories.size)
        assertEquals(AppGalleryViewMode.YEAR_CALENDAR, memories.first().viewMode)

        val updated = memory.copy(viewMode = AppGalleryViewMode.LIST, updatedAt = 20L)
        memories = AppGalleryViewMemoryKeeper.upsert(memories, updated)
        assertEquals(1, memories.size)
        assertEquals(AppGalleryViewMode.LIST, AppGalleryViewMemoryKeeper.find(memories, memory.scopeId)?.viewMode)
        assertNull(AppGalleryViewMemoryKeeper.find(memories, "folder_x"))
    }

    @Test
    fun `视图记忆按槽位淘汰最旧但保留新写入`() {
        var memories = emptyList<AppGalleryViewMemory>()
        repeat(6) { index ->
            memories = AppGalleryViewMemoryKeeper.upsert(
                memories,
                AppGalleryViewMemory(scopeId = "scope$index", updatedAt = index.toLong()),
                slots = 3,
            )
        }
        assertEquals(3, memories.size)
        assertEquals(setOf("scope3", "scope4", "scope5"), memories.map { it.scopeId }.toSet())
        // 槽位为 0 时被 clamp 到 1，且新记忆不会被淘汰
        val single = AppGalleryViewMemoryKeeper.upsert(memories, AppGalleryViewMemory(scopeId = "new", updatedAt = 999L), slots = 0)
        assertEquals(1, single.size)
        assertEquals("new", single.first().scopeId)
    }

    @Test
    fun `视图记忆缺省时返回配置默认值`() {
        val config = AppGalleryConfig(
            defaultViewMode = AppGalleryViewMode.LIST,
            defaultSortAscending = true,
            timelineGranularity = TimelineGranularity.YEAR,
        )
        val resolved = AppGalleryViewMemoryKeeper.resolve(emptyList(), "folder_a", config)
        assertEquals("folder_a", resolved.scopeId)
        assertEquals(AppGalleryViewMode.LIST, resolved.viewMode)
        assertEquals(AppGallerySortOrder.ASCENDING, resolved.sortOrder)
        assertEquals(TimelineGranularity.YEAR, resolved.timelineGranularity)
    }

    @Test
    fun `视图记忆编解码往返一致`() {
        val memories = listOf(
            AppGalleryViewMemory(scopeId = "all", viewMode = AppGalleryViewMode.GRID, updatedAt = 5L),
            AppGalleryViewMemory(scopeId = "folder_1", viewMode = AppGalleryViewMode.TIMELINE, scrollAnchorId = "x1", updatedAt = 6L),
        )
        val decoded = AppGalleryViewMemoryCodec.decode(AppGalleryViewMemoryCodec.encode(memories))
        assertEquals(memories, decoded)
        assertTrue(AppGalleryViewMemoryCodec.decode(null).isEmpty())
    }

    // ── 显示策略（相册项 19：横竖屏 / 深色） ───────────────────

    @Test
    fun `深色模式三种取值`() {
        assertTrue(AppGalleryDisplay.isDark(AppGalleryThemeMode.DARK, systemDark = false))
        assertFalse(AppGalleryDisplay.isDark(AppGalleryThemeMode.LIGHT, systemDark = true))
        assertTrue(AppGalleryDisplay.isDark(AppGalleryThemeMode.FOLLOW_SYSTEM, systemDark = true))
        assertFalse(AppGalleryDisplay.isDark(AppGalleryThemeMode.FOLLOW_SYSTEM, systemDark = false))
        assertEquals(AppGalleryThemeMode.DARK, AppGalleryThemeMode.fromPersistedName("dark"))
        assertEquals(AppGalleryThemeMode.FOLLOW_SYSTEM, AppGalleryThemeMode.fromPersistedName(null))
    }

    @Test
    fun `宽屏派生更多列数但不超过上限`() {
        val config = AppGalleryConfig(gridColumnCount = 4)
        assertEquals(4, AppGalleryDisplay.resolveGridColumnCount(config, 360))
        assertEquals(8, AppGalleryDisplay.resolveGridColumnCount(config, 800))
        // 8 列 × 3 倍 = 24 → 被 MAX_DERIVED_COLUMNS 截断
        assertEquals(
            AppGalleryDisplay.MAX_DERIVED_COLUMNS,
            AppGalleryDisplay.resolveGridColumnCount(AppGalleryConfig(gridColumnCount = 8), 1440),
        )
        assertTrue(AppGalleryDisplay.resolveListRowHeightDp(config, 360) <= AppGalleryDisplay.resolveListRowHeightDp(config, 900))
        assertTrue(AppGalleryDisplay.resolveListRowHeightDp(config, 900) <= AppGalleryConfig.LIST_ROW_HEIGHT_MAX_DP)
        assertTrue(AppGalleryDisplay.shouldUseTwoPane(AppGalleryDisplay.TWO_PANE_MIN_WIDTH_DP))
        assertFalse(AppGalleryDisplay.shouldUseTwoPane(AppGalleryDisplay.TWO_PANE_MIN_WIDTH_DP - 1))
    }

    @Test
    fun `手势行为随多选态切换`() {
        assertEquals(AppGalleryTapBehavior.OPEN_PREVIEW, AppGalleryDisplay.tapBehavior(false))
        assertEquals(AppGalleryTapBehavior.TOGGLE_SELECTION, AppGalleryDisplay.tapBehavior(true))
    }

    @Test
    fun `统计条比例归一化并 clamp`() {
        assertEquals(0.5f, AppGalleryDisplay.normalizedBarRatio(5L, 10L), 0.001f)
        assertEquals(0f, AppGalleryDisplay.normalizedBarRatio(5L, 0L), 0.001f)
        assertEquals(1f, AppGalleryDisplay.normalizedBarRatio(50L, 10L), 0.001f)
        assertEquals(0f, AppGalleryDisplay.normalizedBarRatio(-5L, 10L), 0.001f)
        assertTrue(AppGalleryDisplay.timelineTitle("2026-10-16", TimelineGranularity.DAY).isNotEmpty())
        assertTrue(AppGalleryDisplay.timelineTitle("2026-10", TimelineGranularity.MONTH).isNotEmpty())
    }

    // ── 格式化（列表/视频元数据展示） ──────────────────────────

    @Test
    fun `体积与时长格式化`() {
        assertEquals("0 B", AppGalleryItem.formatBytes(-1L))
        assertEquals("512 B", AppGalleryItem.formatBytes(512L))
        assertEquals("1.0 KB", AppGalleryItem.formatBytes(1024L))
        assertEquals("1.0 MB", AppGalleryItem.formatBytes(1024L * 1024L))
        assertEquals("01:05", AppGalleryItem.formatDuration(65_000L))
        assertEquals("1:00:01", AppGalleryItem.formatDuration(3_601_000L))
        assertEquals("00:00", AppGalleryItem.formatDuration(-5L))
    }

    @Test
    fun `视频列表徽标含时长与帧数`() {
        val video = item("v", name = "clip.mp4", category = AppGalleryCategory.VIDEO).copy(
            kind = AppGalleryMediaKind.VIDEO,
            durationMs = 95_000L,
            frameCount = 240,
        )
        assertEquals("01:35 · 240帧", AppGalleryScanBridge.videoBadge(video))
        assertEquals("01:35", AppGalleryScanBridge.videoBadge(video, includeFrames = false))
        assertTrue(AppGalleryScanBridge.videoBadge(video).isNotEmpty())
        assertTrue(AppGalleryDisplay.badgesOf(video).isNotEmpty())
    }

    // ── 导出计划（相册项 18：备份/转存） ───────────────────────

    @Test
    fun `导出计划生成唯一文件名与体积合计`() {
        val items = listOf(
            item("a", name = "IMG_1.jpg", size = 100L),
            item("b", name = "IMG_1.jpg", size = 200L),
        )
        val plan = AppGalleryExportPlanner.build(
            items,
            AppGalleryExportShape.ZIP,
            AppGalleryConfig(),
            now = 1_700_000_000_000L,
            zoneId = zone,
        )
        assertEquals(2, plan.entries.size)
        assertEquals(plan.entries.map { it.entryName }.toSet().size, plan.entries.size)
        assertEquals(300L, plan.totalBytes)
        assertTrue(plan.targetName.endsWith(".zip"))
        assertTrue(plan.entries.all { it.entryName.endsWith(".jpg") })
        assertTrue(plan.skippedIds.isEmpty())
    }

    @Test
    fun `导出计划跳过无 URI 条目并支持自定义目标名`() {
        val items = listOf(item("a", uri = ""), item("b"))
        val plan = AppGalleryExportPlanner.build(
            items,
            AppGalleryExportShape.COPY_TO_DIRECTORY,
            AppGalleryConfig(),
            now = 1L,
            zoneId = zone,
            targetName = "我的备份",
        )
        assertEquals(listOf("a"), plan.skippedIds)
        assertEquals(1, plan.entries.size)
        assertEquals("我的备份", plan.targetName)
    }

    @Test
    fun `文件名清洗去掉非法字符并控长`() {
        assertEquals("a_b", AppGalleryExportPlanner.sanitizeFileName("a/b"))
        assertEquals("", AppGalleryExportPlanner.sanitizeFileName("   "))
        assertTrue(AppGalleryExportPlanner.sanitizeFileName("x".repeat(500)).length <= AppGalleryConfig().exportMaxNameLength.coerceAtLeast(1))
        assertEquals("mp4", AppGalleryExportPlanner.extensionFor(item("v", name = "clip", category = AppGalleryCategory.VIDEO)))
        assertEquals("dng", AppGalleryExportPlanner.extensionFor(item("r", name = "raw", category = AppGalleryCategory.RAW_DNG)))
    }

    // ── 相册模型（相册项 11） ──────────────────────────────────

    @Test
    fun `相册新建重命名移动与删除`() {
        var folders = AppGalleryFolderModel.defaultFolders(now = 1L)
        assertEquals(AppGalleryFolderModel.FAVORITES_ID, folders.first().id)

        val created = AppGalleryFolderModel.create(folders, "  旅行  ", now = 100L)
        assertTrue(created.changed)
        folders = created.folders
        assertEquals(2, folders.size)
        val travelId = created.target!!.id
        assertEquals("旅行", created.target.name)

        // 重名（忽略大小写）被拒
        assertFalse(AppGalleryFolderModel.create(folders, "旅行", now = 200L).changed)
        // 收藏夹不可重命名
        assertFalse(AppGalleryFolderModel.rename(folders, AppGalleryFolderModel.FAVORITES_ID, "新名字").changed)
        // 非法名被清洗，清洗后为空则拒绝
        assertFalse(AppGalleryFolderModel.create(folders, "///", now = 300L).changed)

        folders = AppGalleryFolderModel.rename(folders, travelId, "西北旅行", now = 400L).folders
        assertEquals("西北旅行", folders.first { it.id == travelId }.name)

        folders = AppGalleryFolderModel.addItems(folders, travelId, listOf("a", "b")).folders
        assertEquals(2, folders.first { it.id == travelId }.itemIds.size)
        folders = AppGalleryFolderModel.removeItems(folders, travelId, listOf("a")).folders
        assertEquals(listOf("b"), folders.first { it.id == travelId }.itemIds)

        val deleted = AppGalleryFolderModel.delete(folders, travelId)
        assertTrue(deleted.changed)
        assertEquals(1, deleted.folders.size)
        assertFalse(AppGalleryFolderModel.delete(deleted.folders, travelId).changed)
    }

    @Test
    fun `相册名清洗与校验规则`() {
        assertEquals("a b", AppGalleryFolderModel.sanitizeName("a \\/:*?\"<>| b"))
        assertTrue(AppGalleryFolderModel.validateName("valid", emptyList()).valid)
        assertFalse(AppGalleryFolderModel.validateName("", emptyList()).valid)
        assertFalse(AppGalleryFolderModel.validateName("x".repeat(AppGalleryFolderModel.MAX_NAME_LENGTH + 1), emptyList()).valid)
        assertFalse(AppGalleryFolderModel.validateName("全部", emptyList()).valid)
        assertFalse(
            AppGalleryFolderModel.validateName("dup", listOf(AppGalleryFolder("f1", "DUP"))).valid,
        )
        assertTrue(
            AppGalleryFolderModel.validateName("dup", listOf(AppGalleryFolder("f1", "dup")), excludeId = "f1").valid,
        )
    }

    @Test
    fun `智能相册与收藏夹成员由条件决定`() {
        var folders = AppGalleryFolderModel.defaultFolders(now = 1L)
        val smart = AppGalleryFolderModel.create(folders, "星空精选", now = 2L).folders.let { AppGalleryFolderModel.setSmartQuery(it, it.last().id, "tag:星空") }
        folders = smart.folders
        assertTrue(folders.last().isSmartFolder)
        assertEquals("tag:星空", folders.last().smartQuery)
        // 智能相册不接受手动成员
        assertFalse(AppGalleryFolderModel.addItems(folders, folders.last().id, listOf("a")).changed)

        val annotations = mapOf(
            "a" to AppGalleryAnnotation("a", isFavorite = true),
            "b" to AppGalleryAnnotation("b"),
        )
        assertEquals(listOf("a"), AppGalleryFolderModel.favoriteMembers(annotations))
        assertTrue(AppGalleryFolderModel.search(folders, "星空").isNotEmpty())
        assertTrue(AppGalleryFolderModel.search(folders, "不存在的名字").isEmpty())
        assertTrue(AppGalleryFolderModel.effectiveItems(folders.last(), folders).isEmpty())
    }

    @Test
    fun `相册编解码往返一致`() {
        val folders = AppGalleryFolderModel.defaultFolders(now = 1L) +
            AppGalleryFolder(id = "f1", name = "旅行", itemIds = listOf("a", "b"), createdAt = 5L)
        val decoded = AppGalleryFolderCodec.decode(AppGalleryFolderCodec.encode(folders))
        assertEquals(folders.size, decoded.size)
        assertEquals(listOf("a", "b"), decoded.first { it.id == "f1" }.itemIds)
        assertTrue(AppGalleryFolderCodec.decode(null).isEmpty())
    }

    // ── 地点聚类（相册项 10 / 16） ─────────────────────────────

    @Test
    fun `坐标合法性判定`() {
        assertTrue(AppGalleryPlaceClustering.isValidCoordinate(43.8, 125.3))
        assertFalse(AppGalleryPlaceClustering.isValidCoordinate(91.0, 0.0))
        assertFalse(AppGalleryPlaceClustering.isValidCoordinate(0.0, 181.0))
        assertFalse(AppGalleryPlaceClustering.isValidCoordinate(Double.NaN, 0.0))
    }

    @Test
    fun `离线城市表按外接矩形反查`() {
        val table = listOf(
            AppGalleryOfflineCityIndex.CityBoundary("测试市", 10.0, 20.0, 100.0, 120.0),
            AppGalleryOfflineCityIndex.CityBoundary("测试区", 15.0, 16.0, 110.0, 111.0),
        )
        // 重叠区域取面积更小者
        assertEquals("测试区", AppGalleryOfflineCityIndex.lookup(15.5, 110.5, table))
        assertEquals("测试市", AppGalleryOfflineCityIndex.lookup(11.0, 101.0, table))
        assertNull(AppGalleryOfflineCityIndex.lookup(0.0, 0.0, table))
        assertNull(AppGalleryOfflineCityIndex.lookup(91.0, 0.0, table))

        // 内置表覆盖中国大陆主要城市（粗粒度，仅断言可查到名称）
        assertNotNull(AppGalleryOfflineCityIndex.lookup(43.75, 125.0))
        assertNotNull(AppGalleryOfflineCityIndex.lookup(39.9042, 116.4074))
        assertNull(AppGalleryOfflineCityIndex.lookup(0.0, -160.0))
    }

    @Test
    fun `自备城市表解析并优先小面积`() {
        val table = AppGalleryOfflineCityIndex.parseCustomTable(
            """
            # 自备表：名称,minLat,maxLat,minLon,maxLon
            测试市,10,20,100,120
            测试区,15,16,110,111
            非法行
            坏行,x,y,z,w
            """.trimIndent(),
        )
        assertEquals(2, table.size)
        assertEquals("测试区", AppGalleryOfflineCityIndex.lookup(15.5, 110.5, table))
        assertEquals("测试市", AppGalleryOfflineCityIndex.lookup(11.0, 101.0, table))
        assertNull(AppGalleryOfflineCityIndex.lookup(0.0, 0.0, table))
        assertTrue(AppGalleryOfflineCityIndex.parseCustomTable(null).isEmpty())
    }

    @Test
    fun `聚类按网格分组并给出中心与城市名`() {
        val points = listOf(
            AppGalleryGeoPoint("a", 43.80, 125.30),
            AppGalleryGeoPoint("b", 43.81, 125.31),
            AppGalleryGeoPoint("c", 39.90, 116.40),
            AppGalleryGeoPoint("bad", 200.0, 200.0),
        )
        val clusters = AppGalleryPlaceClustering.cluster(points, AppGalleryConfig(), null)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.count >= 1 })
        assertEquals(3, clusters.sumOf { it.count })
        // 同一网格的两点落在同一簇
        val paired = clusters.first { it.itemIds.contains("a") }
        assertTrue(paired.itemIds.contains("b"))
        assertTrue(clusters.sumOf { it.radiusKm.toDouble() } > 0.0)
    }

    @Test
    fun `联网反查优先且离线表可关闭`() {
        val online = AppGalleryPlaceClustering.resolvePlaceName(
            43.8,
            125.3,
            AppGalleryConfig(placeClusterOnlineLookupEnabled = true),
            reverseGeocoder = { _, _ -> "网络市" },
        )
        assertEquals("网络市", online)

        val offlineDisabled = AppGalleryPlaceClustering.resolvePlaceName(
            43.8,
            125.3,
            AppGalleryConfig(placeClusterUseOfflineCityIndex = false),
            reverseGeocoder = null,
        )
        assertNull(offlineDisabled)

        // 反查抛异常时回退离线表，不崩溃
        val fallback = AppGalleryPlaceClustering.resolvePlaceName(
            15.5,
            110.5,
            AppGalleryConfig(placeClusterOnlineLookupEnabled = true),
            reverseGeocoder = { _, _ -> throw IllegalStateException("geocoder failed") },
            cityIndex = listOf(AppGalleryOfflineCityIndex.CityBoundary("测试区", 15.0, 16.0, 110.0, 111.0)),
        )
        assertEquals("测试区", fallback)
    }

    @Test
    fun `外部地图跳转 URI 形态稳定`() {
        assertEquals("geo:43.8,125.3?z=14", AppGalleryPlaceClustering.externalMapUri(43.8, 125.3))
        assertEquals("geo:43.8,125.3(长春)?z=14", AppGalleryPlaceClustering.externalMapUri(43.8, 125.3, "长春"))
        assertTrue(AppGalleryPlaceClustering.externalMapUri(43.8, 125.3, "a(b)").contains("a_b_"))
    }

    @Test
    fun `球面距离与网格键`() {
        // 长春 → 北京 约 900 公里
        val distance = AppGalleryPlaceClustering.haversineKm(43.88, 125.32, 39.90, 116.40)
        assertTrue(distance in 850.0..1_000.0)
        assertEquals(0.0, AppGalleryPlaceClustering.haversineKm(43.0, 125.0, 43.0, 125.0), 0.0001)
        assertEquals(AppGalleryPlaceClustering.gridKey(43.8, 125.3, 2), AppGalleryPlaceClustering.gridKey(43.81, 125.31, 2))
        assertFalse(AppGalleryPlaceClustering.gridKey(43.8, 125.3, 2) == AppGalleryPlaceClustering.gridKey(39.9, 116.4, 2))
    }

    // ── 预览跳转契约（相册项 3：列表 → 预览） ──────────────────

    @Test
    fun `预览请求按可见顺序构造并定位下标`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val request = AppGalleryPreviewContract.build(items, "b", containerId = "folder_1", viewMode = AppGalleryViewMode.GRID)
        assertNotNull(request)
        assertEquals("b", request!!.itemId)
        assertEquals(listOf("a", "b", "c"), request.orderedIds)
        assertEquals(1, request.index)
        assertEquals("folder_1", request.containerId)
        assertEquals("a", AppGalleryPreviewContract.neighborId(request, -1))
        assertEquals("c", AppGalleryPreviewContract.neighborId(request, 1))
        assertNull(AppGalleryPreviewContract.neighborId(request, -5))
    }

    @Test
    fun `锚点缺失返回 null 且顺序受上限截断`() {
        assertNull(AppGalleryPreviewContract.build(emptyList(), "a"))
        assertNull(AppGalleryPreviewContract.build(listOf(item("a")), "missing"))

        val many = (1..AppGalleryPreviewContract.ORDER_LIMIT + 50).map { item("id$it") }
        val request = AppGalleryPreviewContract.build(many, "id5")
        assertEquals(AppGalleryPreviewContract.ORDER_LIMIT, request!!.orderedIds.size)

        // 过滤后的可见顺序以传入的 visibleIds 为准
        val filtered = AppGalleryPreviewContract.build(
            listOf(item("a"), item("b"), item("c")),
            "c",
            visibleIds = listOf("b", "c"),
        )
        assertEquals(listOf("b", "c"), filtered!!.orderedIds)
        assertEquals(1, filtered.index)
        // containerId 透传给预览域，用于「同容器内左右滑动」
        assertEquals("all", AppGalleryPreviewContract.CONTAINER_ALL)
    }

    @Test
    fun `路由模板与 id 编码可逆`() {
        assertEquals(
            "app_gallery_preview/IMG_0001.jpg",
            AppGalleryPreviewContract.routeFor("IMG_0001.jpg"),
        )
        assertEquals("a%2Fb", AppGalleryPreviewContract.encodeId("a/b"))
        assertEquals("a%20b", AppGalleryPreviewContract.encodeId("a b"))
        assertTrue(AppGalleryPreviewContract.routeFor("a/b").startsWith("app_gallery_preview/"))
    }

    @Test
    fun `能力声明与分类一致`() {
        assertTrue(AppGalleryPreviewContract.supportsRawViewing(AppGalleryCategory.RAW_DNG))
        assertFalse(AppGalleryPreviewContract.supportsRawViewing(AppGalleryCategory.NORMAL))
        assertTrue(AppGalleryPreviewContract.suggestsComparison(AppGalleryCategory.MULTI_FRAME))
        assertTrue(AppGalleryPreviewContract.needsPlayer(item("v", name = "clip.mp4", category = AppGalleryCategory.VIDEO)))
        assertFalse(AppGalleryPreviewContract.needsPlayer(item("p")))
    }

    @Test
    fun `预览 extras 键名与预览域约定逐字一致`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val request = AppGalleryPreviewContract.build(items, "b")!!
        val extras = AppGalleryPreviewContract.toPreviewEntryExtras(request, items)

        assertEquals("media_uri", AppGalleryPreviewContract.PREVIEW_KEY_MEDIA_URI)
        assertEquals("source_ids", AppGalleryPreviewContract.PREVIEW_KEY_SOURCE_IDS)
        assertEquals("appgallery_preview", AppGalleryPreviewContract.PREVIEW_ROUTE)
        assertEquals("appgallery", AppGalleryPreviewContract.PREVIEW_SOURCE_TAG)
        assertEquals("\u001F", AppGalleryPreviewContract.PREVIEW_LIST_SEPARATOR)
        assertEquals(items[1].uri, extras["media_uri"])
        assertEquals("b", extras["media_id"])
        assertEquals("1", extras["index"])
        assertEquals("3", extras["total"])
        assertEquals("a\u001Fb\u001Fc", extras["source_ids"])
        assertEquals("appgallery", extras["source"])
        assertEquals("false", extras["favorite"])
        assertEquals("true", extras["allow_edit"])
        // 静态图片不显式给 MIME（交由预览域按扩展名推断）
        assertFalse(extras.containsKey("media_type"))
    }

    @Test
    fun `预览 extras 仅写有真实信息的键`() {
        val gif = item("g", name = "anim.gif", category = AppGalleryCategory.GIF)
        val video = item("v", name = "clip.mp4", category = AppGalleryCategory.VIDEO)
        val extras = AppGalleryPreviewContract.toPreviewEntryExtras(
            AppGalleryPreviewContract.build(listOf(gif, video), "g")!!,
            listOf(gif, video),
        )
        assertEquals("image/gif", extras["media_type"])
        assertEquals("image/gif\u001Fvideo/*", extras["source_types"])

        // 视频不允许进编辑
        val videoExtras = AppGalleryPreviewContract.toPreviewEntryExtras(
            AppGalleryPreviewContract.build(listOf(gif, video), "v")!!,
            listOf(gif, video),
        )
        assertEquals("false", videoExtras["allow_edit"])
        assertEquals("video/*", videoExtras["media_type"])
    }

    @Test
    fun `锚点缺失或 uri 为空时返回空 extras`() {
        val items = listOf(item("a"), item("b"))
        assertTrue(
            AppGalleryPreviewContract.toPreviewEntryExtras(
                AppGalleryPreviewContract.build(items, "a")!!,
                emptyList(),
            ).isEmpty(),
        )
        val blank = listOf(item("a", uri = ""), item("b"))
        assertTrue(
            AppGalleryPreviewContract.toPreviewEntryExtras(
                AppGalleryPreviewContract.build(blank, "a")!!,
                blank,
            ).isEmpty(),
        )
    }

    @Test
    fun `字段不完整时不下发 uri 序列`() {
        val items = listOf(item("a"), item("b", uri = ""), item("c"))
        val extras = AppGalleryPreviewContract.toPreviewEntryExtras(
            AppGalleryPreviewContract.build(items, "c")!!,
            items,
        )
        assertEquals("a\u001Fb\u001Fc", extras["source_ids"])
        assertFalse(extras.containsKey("source_uris"))
        assertFalse(extras.containsKey("source_types"))
    }

    @Test
    fun `超大序列按锚点窗口化且下标自洽`() {
        // 2000 条 = ORDER_LIMIT（不触发条目截断），但长 id 使字符预算溢出 → 走窗口化
        val ids = (1..AppGalleryPreviewContract.ORDER_LIMIT).map {
            "id_%06d".format(it) + "x".repeat(84)
        }
        val items = ids.map { item(it) }
        val anchor = ids[1000]
        val request = AppGalleryPreviewContract.build(items, anchor)!!
        val extras = AppGalleryPreviewContract.toPreviewEntryExtras(request, items)
        val windowed = extras["source_ids"]!!.split(AppGalleryPreviewContract.PREVIEW_LIST_SEPARATOR)

        assertTrue(windowed.size < ids.size)
        assertEquals(windowed.size.toString(), extras["total"])
        assertEquals(anchor, windowed[extras["index"]!!.toInt()])
        assertTrue(anchor in windowed)
        // 窗口内相对顺序不变（去掉窗口外的项后与全集一致）
        val inside = windowed.toSet()
        assertEquals(windowed, ids.filter { it in inside })
        // 窗口化后仍在字符预算内
        assertTrue(windowed.sumOf { it.length + 1 } <= AppGalleryPreviewContract.PREVIEW_EXTRA_CHAR_BUDGET)
    }

    @Test
    fun `窗口化在预算内且锚点始终保留`() {
        val ids = List(2000) { "media_%04d".format(it) }
        val (all, indexAll) = AppGalleryPreviewContract.fitWindow(ids, 1000)
        assertEquals(ids, all)
        assertEquals(1000, indexAll)
        // 单条超长 id 时退化为只保留锚点
        val huge = listOf("x".repeat(200_000), "y".repeat(10))
        val (one, indexOne) = AppGalleryPreviewContract.fitWindow(huge, 0)
        assertEquals(listOf(huge[0]), one)
        assertEquals(0, indexOne)
        val (tail, indexTail) = AppGalleryPreviewContract.fitWindow(huge, 1)
        assertEquals(listOf(huge[1]), tail)
        assertTrue(indexTail >= 0)
        assertEquals(emptyList<String>(), AppGalleryPreviewContract.fitWindow(emptyList(), 3).first)
    }

    // ── 相机输出联动（相册项 21：目录扫描归类） ────────────────

    @Test
    fun `输出目录名归类`() {
        assertEquals(
            AppGalleryCategory.SUPER_RES,
            AppGalleryScanBridge.classifyOutputDirectory(File("/photos/multiframesr")),
        )
        assertEquals(
            AppGalleryCategory.ASTRO_TRAIL,
            AppGalleryScanBridge.classifyOutputDirectory(File("/photos/astro")),
        )
        assertEquals(
            AppGalleryCategory.NORMAL,
            AppGalleryScanBridge.classifyOutputDirectory(File("/photos")),
        )
    }

    // ── 编解码健壮性（支撑层） ─────────────────────────────────

    @Test
    fun `通用编解码对分隔符与空值安全`() {
        val rows = listOf(
            listOf("a\u001Fb", "c,d", ""),
            listOf("x", "y", "z"),
        )
        val payload = AppGalleryCodec.encodeRecords(rows)
        val decoded = AppGalleryCodec.decodeRecords(payload)
        assertEquals(rows, decoded)
        assertTrue(AppGalleryCodec.decodeRecords(null).isEmpty())
        assertNull(AppGalleryCodec.encodeOrNull(emptyList()))
        // 空值字段（含首尾）原样保留
        assertEquals(listOf(listOf("", "x", "")), AppGalleryCodec.decodeRecords(AppGalleryCodec.encodeRecords(listOf(listOf("", "x", "")))))
    }
}
