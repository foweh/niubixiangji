package com.hinnka.mycamera.ui.appgallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.appgallery.AppGalleryCategory
import com.hinnka.mycamera.appgallery.AppGalleryConfig
import com.hinnka.mycamera.appgallery.AppGalleryDisplay
import com.hinnka.mycamera.appgallery.AppGalleryDuplicateGroup
import com.hinnka.mycamera.appgallery.AppGalleryFolder
import com.hinnka.mycamera.appgallery.AppGalleryItem
import com.hinnka.mycamera.appgallery.AppGalleryPlaceCluster
import com.hinnka.mycamera.appgallery.AppGalleryShotParams
import com.hinnka.mycamera.appgallery.AppGalleryShotRow
import com.hinnka.mycamera.appgallery.AppGalleryStatsReport
import com.hinnka.mycamera.appgallery.AppGalleryThemeMode
import com.hinnka.mycamera.appgallery.AppGalleryTrashEntry
import com.hinnka.mycamera.appgallery.TimelineGranularity

/**
 * 侧栏/面板（相册项 5/7/10/11/12/14/15/16/18/19）。
 *
 * 每个面板只接收「数据 + 回调」，不直接访问仓库，便于在预览环境单独渲染。
 */

/** 回收站面板（相册项 7）。 */
@Composable
fun AppGalleryTrashPanel(
    entries: List<AppGalleryTrashEntry>,
    summaryText: String,
    retentionDays: Int,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        AppGalleryEmptyState(text = "回收站为空（删除的条目会在这里保留 $retentionDays 天）", modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = summaryText, fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onEmpty) { Text("清空", fontSize = 13.sp) }
        }
        LazyColumn {
            items(entries, key = { it.itemId }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppGalleryThumbnail(
                        item = entry.toPreviewItem(),
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        showBadges = false,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = entry.displayName, maxLines = 1, fontSize = 14.sp)
                        Text(
                            text = "${entry.category.labelZh} · ${AppGalleryItem.formatBytes(entry.sizeBytes)} · " +
                                "剩余 ${appGalleryTrashRemainingDays(entry, System.currentTimeMillis())} 天",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(entry.itemId) }) { Text("恢复", fontSize = 13.sp) }
                    TextButton(onClick = { onPurge(entry.itemId) }) { Text("删除", fontSize = 13.sp) }
                }
                AppGalleryThinDivider()
            }
        }
    }
}

/** 剩余天数（UI 展示用，向下取整到「天」但至少显示 0）。 */
private fun appGalleryTrashRemainingDays(entry: AppGalleryTrashEntry, now: Long): Long =
    ((entry.expiresAt - now).coerceAtLeast(0L)) / 86_400_000L

/** 回收站条目在缩略图里复用 item 结构（只填必要字段）。 */
private fun AppGalleryTrashEntry.toPreviewItem(): AppGalleryItem = AppGalleryItem(
    id = itemId,
    displayName = displayName,
    uri = trashUri,
    category = category,
    sizeBytes = sizeBytes,
    dateAdded = deletedAt,
)

/** 统计面板（相册项 14/15）：按天/月/年看张数、类型分布、占用；并按类型一键清理。 */
@Composable
fun AppGalleryStatsPanel(
    report: AppGalleryStatsReport,
    granularity: TimelineGranularity,
    onGranularity: (TimelineGranularity) -> Unit,
    onCleanup: (Set<AppGalleryCategory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TimelineGranularity.entries.forEach { item ->
                FilterChip(
                    selected = item == granularity,
                    onClick = { onGranularity(item) },
                    label = { Text(item.labelZh, fontSize = 12.sp) },
                )
            }
        }
        Text(
            text = "合计 ${report.totalCount} 项 · ${AppGalleryItem.formatBytes(report.totalBytes)}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val maxBytes = report.buckets.maxOfOrNull { it.totalBytes } ?: 0L
        report.buckets.forEach { bucket ->
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row {
                    Text(
                        text = AppGalleryDisplay.timelineTitle(bucket.key, report.granularity),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${bucket.count} 项 · ${AppGalleryItem.formatBytes(bucket.totalBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(AppGalleryDisplay.normalizedBarRatio(bucket.totalBytes, maxBytes))
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        AppGalleryThinDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "按类型占用",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        report.usageByCategory().forEach { usage ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = usage.category.labelZh, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    text = "${usage.count} 项 · ${AppGalleryItem.formatBytes(usage.bytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onCleanup(setOf(usage.category)) }) {
                    Text("清理", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 重复照片面板（相册项 12）。 */
@Composable
fun AppGalleryDuplicatePanel(
    groups: List<AppGalleryDuplicateGroup>,
    threshold: Int,
    onScan: () -> Unit,
    onDeleteDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (groups.isEmpty()) {
                    "尚未检测（汉明距离阈值 $threshold）"
                } else {
                    "发现 ${groups.size} 组重复 · 可释放 ${
                        AppGalleryItem.formatBytes(groups.sumOf { it.reclaimableBytes })
                    }"
                },
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onScan) { Text("开始检测", fontSize = 13.sp) }
            if (groups.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(onClick = onDeleteDuplicates) { Text("清理重复", fontSize = 13.sp) }
            }
        }
        LazyColumn {
            items(groups, key = { it.anchorId }) { group ->
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "保留 ${group.anchorId.take(8)}…，其余 ${group.duplicateIds.size} 项重复" +
                            "（最大距离 ${group.maxDistance}）",
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "可释放 ${AppGalleryItem.formatBytes(group.reclaimableBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppGalleryThinDivider()
            }
        }
    }
}

/** 拍摄参数面板（相册项 4）：EXIF 概览，纯展示、无回调。 */
@Composable
fun AppGalleryMetadataPanel(
    item: AppGalleryItem?,
    modifier: Modifier = Modifier,
) {
    val params = item?.shotParams
    val rows: List<AppGalleryShotRow> = params?.rows().orEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    item == null -> "未选择照片"
                    params == null || params.isEmpty -> "该条目没有可用的拍摄参数"
                    else -> "${item.displayName} · ${item.category.labelZh}"
                },
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = item?.let { "${it.formattedResolution()} · ${it.formattedSize()}" }.orEmpty(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rows.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.label,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(84.dp),
                        )
                        Text(text = row.value, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                    AppGalleryThinDivider()
                }
            }
        }
        if (params != null && !params.isEmpty) {
            Text(
                text = "参数来自 EXIF 快照；缺失项显示「${AppGalleryShotParams.PLACEHOLDER}」。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 地点面板（相册项 10/16）：按坐标聚类 + 外部地图跳转。 */
@Composable
fun AppGalleryPlacePanel(
    clusters: List<AppGalleryPlaceCluster>,
    onOpenMap: (Double, Double, String?) -> Unit,
    onRecompute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (clusters.isEmpty()) "没有带定位信息的照片" else "${clusters.size} 个地点分组",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRecompute) { Text("重新聚类", fontSize = 13.sp) }
        }
        Text(
            text = "说明：本模块不内置地图渲染，点击「地图」将调用系统已安装的地图应用（geo: 协议）。" +
                "离线城市名来自内置粗粒度矩形表，边界附近可能落到相邻城市。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        LazyColumn {
            items(clusters, key = { it.key }) { cluster ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cluster.cityName ?: cluster.key,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${cluster.count} 项 · ${"%.4f".format(cluster.centerLatitude)}, " +
                                "${"%.4f".format(cluster.centerLongitude)} · 半径 ${cluster.radiusKm}km",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            onOpenMap(
                                cluster.centerLatitude,
                                cluster.centerLongitude,
                                cluster.cityName,
                            )
                        },
                    ) { Text("地图", fontSize = 13.sp) }
                }
                AppGalleryThinDivider()
            }
        }
    }
}

/** 相册管理面板（相册项 11）。 */
@Composable
fun AppGalleryFolderPanel(
    folders: List<AppGalleryFolder>,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (AppGalleryFolder) -> Unit,
    onCreateSmartByTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "${folders.size} 个相册", fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCreateSmartByTags) { Text("按标签建智能相册", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(onClick = { onCreate("新相册") }) { Text("新建", fontSize = 13.sp) }
        }
        LazyColumn {
            items(folders, key = { it.id }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.name + if (folder.isFavoriteFolder) "（收藏夹）" else "",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = buildString {
                                append("${folder.itemIds.size} 项")
                                if (folder.isSmartFolder) append(" · 智能：${folder.smartQuery}")
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpen(folder) }) { Text("打开", fontSize = 13.sp) }
                    TextButton(onClick = { onRename(folder.id, folder.name + "-副本") }) {
                        Text("重命名", fontSize = 13.sp)
                    }
                    if (!folder.isFavoriteFolder) {
                        TextButton(onClick = { onDelete(folder.id) }) { Text("删除", fontSize = 13.sp) }
                    }
                }
                AppGalleryThinDivider()
            }
        }
    }
}

/** 设置面板（相册项 19/7/12/18：主题、回收站、重复阈值、备份开关）。 */
@Composable
fun AppGallerySettingsPanel(
    config: AppGalleryConfig,
    onUpdate: ((AppGalleryConfig) -> AppGalleryConfig) -> Unit,
    onBackfillThumbnails: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("外观（相册项 19）")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppGalleryThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == config.themeMode,
                    onClick = { onUpdate { it.copy(themeMode = mode) } },
                    label = { Text(themeLabel(mode), fontSize = 12.sp) },
                )
            }
        }
        SwitchRow(
            title = "网格列数（竖屏基准）",
            value = config.gridColumnCount.toString(),
            onDecrease = { onUpdate { it.copy(gridColumnCount = it.gridColumnCount - 1) } },
            onIncrease = { onUpdate { it.copy(gridColumnCount = it.gridColumnCount + 1) } },
        )
        SwitchRow(
            title = "列表行高（dp）",
            value = config.listRowHeightDp.toString(),
            onDecrease = { onUpdate { it.copy(listRowHeightDp = it.listRowHeightDp - 8) } },
            onIncrease = { onUpdate { it.copy(listRowHeightDp = it.listRowHeightDp + 8) } },
        )
        ToggleRow(
            title = "按相册分别记忆视图",
            checked = config.rememberViewPerFolder,
            onChange = { value -> onUpdate { it.copy(rememberViewPerFolder = value) } },
        )

        SectionTitle("回收站（相册项 7）")
        ToggleRow(
            title = "启用回收站（删除先入回收站）",
            checked = config.trashEnabled,
            onChange = { value -> onUpdate { it.copy(trashEnabled = value) } },
        )
        SwitchRow(
            title = "保留天数",
            value = config.trashRetentionDays.toString(),
            onDecrease = { onUpdate { it.copy(trashRetentionDays = it.trashRetentionDays - 1) } },
            onIncrease = { onUpdate { it.copy(trashRetentionDays = it.trashRetentionDays + 1) } },
        )
        ToggleRow(
            title = "到期自动清空",
            checked = config.trashAutoPurge,
            onChange = { value -> onUpdate { it.copy(trashAutoPurge = value) } },
        )

        SectionTitle("重复检测（相册项 12）")
        ToggleRow(
            title = "启用重复检测",
            checked = config.duplicateDetectionEnabled,
            onChange = { value -> onUpdate { it.copy(duplicateDetectionEnabled = value) } },
        )
        SwitchRow(
            title = "汉明距离阈值",
            value = config.duplicateHammingThreshold.toString(),
            onDecrease = { onUpdate { it.copy(duplicateHammingThreshold = it.duplicateHammingThreshold - 1) } },
            onIncrease = { onUpdate { it.copy(duplicateHammingThreshold = it.duplicateHammingThreshold + 1) } },
        )
        ToggleRow(
            title = "使用均值哈希（速度优先）",
            checked = config.duplicateUseAverageHash,
            onChange = { value -> onUpdate { it.copy(duplicateUseAverageHash = value) } },
        )

        SectionTitle("缩略图与备份（相册项 1/18）")
        ToggleRow(
            title = "自动补齐缺失缩略图",
            checked = config.autoGenerateMissingThumbnails,
            onChange = { value -> onUpdate { it.copy(autoGenerateMissingThumbnails = value) } },
        )
        SwitchRow(
            title = "缩略图长边（px）",
            value = config.thumbnailMaxEdgePx.toString(),
            onDecrease = { onUpdate { it.copy(thumbnailMaxEdgePx = it.thumbnailMaxEdgePx - 64) } },
            onIncrease = { onUpdate { it.copy(thumbnailMaxEdgePx = it.thumbnailMaxEdgePx + 64) } },
        )
        ToggleRow(
            title = "启用备份/转存",
            checked = config.backupEnabled,
            onChange = { value -> onUpdate { it.copy(backupEnabled = value) } },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBackfillThumbnails) { Text("补齐缩略图", fontSize = 13.sp) }
            OutlinedButton(onClick = onBackup) { Text("立即备份", fontSize = 13.sp) }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDecrease) { Text("−", fontSize = 16.sp) }
        Text(text = value, fontSize = 13.sp)
        TextButton(onClick = onIncrease) { Text("＋", fontSize = 16.sp) }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun themeLabel(mode: AppGalleryThemeMode): String = when (mode) {
    AppGalleryThemeMode.FOLLOW_SYSTEM -> "跟随系统"
    AppGalleryThemeMode.LIGHT -> "浅色"
    AppGalleryThemeMode.DARK -> "深色"
}

/** 卡片式统计摘要（供首页顶部展示，相册项 14）。 */
@Composable
fun AppGallerySummaryCard(report: AppGalleryStatsReport, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${report.totalCount} 项\n${AppGalleryItem.formatBytes(report.totalBytes)}",
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = report.usageByCategory().take(3).joinToString(" · ") {
                    "${it.category.labelZh} ${it.count}"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 细分隔线（避免依赖各 Material3 版本对 `Divider`/`HorizontalDivider` 的差异）。 */
@Composable
private fun AppGalleryThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(androidx.compose.ui.graphics.Color(0x22000000)),
    )
}
