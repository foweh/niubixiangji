package com.hinnka.mycamera.ui.appgallery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hinnka.mycamera.appgallery.AppGalleryBatchAction
import com.hinnka.mycamera.appgallery.AppGalleryCategory
import com.hinnka.mycamera.appgallery.AppGalleryDisplay
import com.hinnka.mycamera.appgallery.AppGalleryExportShape
import com.hinnka.mycamera.appgallery.AppGalleryFolderModel
import com.hinnka.mycamera.appgallery.AppGalleryItem
import com.hinnka.mycamera.appgallery.AppGalleryPreviewContract
import com.hinnka.mycamera.appgallery.AppGalleryPreviewLaunch
import com.hinnka.mycamera.appgallery.AppGalleryShareKit
import com.hinnka.mycamera.appgallery.AppGalleryThemeMode
import com.hinnka.mycamera.appgallery.AppGalleryViewMemoryKeeper
import com.hinnka.mycamera.appgallery.AppGalleryViewMode

/** 顶部页签。 */
private enum class AppGalleryTab(val label: String) {
    ALL("照片"),
    FOLDERS("相册"),
    FAVORITES("收藏"),
    TRASH("回收站"),
    STATS("统计"),
    DUPLICATES("重复"),
    PLACES("地点"),
    INFO("信息"),
    SETTINGS("设置"),
}

/**
 * 应用内置相册主界面（相册项 1–21 的浏览与管理入口）。
 *
 * - 多视图：网格 / 列表 / 时间线 / 年历 + 视图记忆（[AppGalleryViewModel.switchScope]）
 * - 筛选：类型筛选条 + 搜索语法（`type:` `tag:` `fav:` `rating:` `since:` `size:`）
 * - 管理：长按多选 → 底部批量条（删除/收藏/评分/标签/移动/导出/分享）
 * - 回收站 / 统计 / 重复 / 地点 / 相册 / 设置 各自独立页签
 *
 * 元数据：列表长按/选中后可切到「信息」页查看 EXIF 拍摄参数（相册项 4）。
 *
 * 预览（缩放/幻灯片/RAW/对比）由预览域实现：本界面只把
 * [AppGalleryPreviewLaunch] 交给 [onOpenPreview]（相册项 3 的跳转契约）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGalleryScreen(
    onBack: () -> Unit,
    onOpenPreview: (AppGalleryPreviewLaunch) -> Unit,
    onOpenMap: (Double, Double, String?) -> Unit,
    viewModel: AppGalleryViewModel = viewModel(),
) {
    val config by viewModel.config.collectAsState()
    val items by viewModel.visibleItems.collectAsState()
    val allItems by viewModel.items.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val granularity by viewModel.granularity.collectAsState()
    val sortSpec by viewModel.sortSpec.collectAsState()
    val rawSearch by viewModel.rawSearchText.collectAsState()
    val selectedCategories by viewModel.selectedCategories.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val trash by viewModel.trash.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val stats by viewModel.statsReport.collectAsState()
    val duplicateGroups by viewModel.duplicateGroups.collectAsState()
    val placeClusters by viewModel.placeClusters.collectAsState()
    val timelineGroups by viewModel.timelineGroups.collectAsState()
    val yearBuckets by viewModel.yearBuckets.collectAsState()
    val message by viewModel.message.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val lastReport by viewModel.lastReport.collectAsState()

    var tab by remember { mutableStateOf(AppGalleryTab.ALL) }
    var searchVisible by remember { mutableStateOf(false) }
    // 拍摄参数面板（相册项 4）当前查看的条目；为空时回落到列表首项
    var infoItemId by remember { mutableStateOf<String?>(null) }
    val infoItem = allItems.firstOrNull { it.id == infoItemId } ?: items.firstOrNull()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val columnCount = AppGalleryDisplay.resolveGridColumnCount(config, screenWidthDp)
    val rowHeight = AppGalleryDisplay.resolveListRowHeightDp(config, screenWidthDp)
    val isSelectionMode = selection.isActive

    // 启动时触发一次回收站到期清理（相册项 7，后台任务自带节流）
    LaunchedEffect(Unit) { viewModel.startTrashPurge() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = !isSelectionMode && tab != AppGalleryTab.ALL) { tab = AppGalleryTab.ALL }
    BackHandler(enabled = !isSelectionMode && tab == AppGalleryTab.ALL) { onBack() }

    val openPreview: (AppGalleryItem) -> Unit = { item ->
        AppGalleryPreviewContract.build(
            items = items,
            anchorId = item.id,
            containerId = viewModel.scopeId.value,
            viewMode = viewMode,
        )?.let(onOpenPreview)
    }

    val startSelection: (AppGalleryItem) -> Unit = { item ->
        viewModel.toggleSelection(item.id)
    }

    AppGalleryContentContainer(themeMode = config.themeMode) {
        Scaffold(
            topBar = {
                if (isSelectionMode) {
                    TopAppBar(
                        title = { Text("已选 ${selection.count} 项", fontSize = 16.sp) },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "退出多选")
                            }
                        },
                        actions = {
                            if (selection.count == 1) {
                                TextButton(
                                    onClick = {
                                        infoItemId = selection.selectedIds.firstOrNull()
                                        viewModel.clearSelection()
                                        tab = AppGalleryTab.INFO
                                    },
                                ) {
                                    Text("信息", fontSize = 13.sp)
                                }
                            }
                            TextButton(onClick = { viewModel.selectAll() }) { Text("全选") }
                            TextButton(onClick = { viewModel.invertSelection() }) { Text("反选") }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = if (loading) "相册（加载中…）" else "相册 · ${allItems.size} 项",
                                fontSize = 16.sp,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            if (tab == AppGalleryTab.ALL || tab == AppGalleryTab.FAVORITES) {
                                IconButton(onClick = { searchVisible = !searchVisible }) {
                                    Icon(Icons.Default.Share, contentDescription = "搜索")
                                }
                            }
                            // 相册项 4：查看当前条目的 EXIF 拍摄参数
                            TextButton(
                                onClick = {
                                    infoItemId = items.firstOrNull()?.id
                                    tab = AppGalleryTab.INFO
                                },
                            ) {
                                Text("信息", fontSize = 13.sp)
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (isSelectionMode) {
                    AppGalleryBatchBar(
                        count = selection.count,
                        folders = folders.map { it.name },
                        onDelete = { viewModel.deleteSelected() },
                        onFavorite = { viewModel.batchFavorite(true) },
                        onUnfavorite = { viewModel.batchFavorite(false) },
                        onRating = { viewModel.batchSetRating(it) },
                        onAddTags = { viewModel.batchAddTags(it) },
                        onExport = { viewModel.batchExport(AppGalleryExportShape.ZIP) },
                        onShare = {
                            val targets = viewModel.selectedItems()
                            AppGalleryShareKit.buildShareIntent(
                                context = context,
                                items = targets,
                                subject = AppGalleryShareKit.shareSubject(targets),
                            )?.let { intent ->
                                context.startActivity(Intent.createChooser(intent, "分享照片"))
                            }
                            viewModel.clearSelection()
                        },
                        onMoveToFolder = { index ->
                            folders.getOrNull(index)?.let { viewModel.batchMoveToFolder(it.id) }
                        },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    AppGalleryTab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = {
                                tab = entry
                                when (entry) {
                                    AppGalleryTab.ALL ->
                                        viewModel.switchScope(AppGalleryViewMemoryKeeper.SCOPE_ALL)
                                    AppGalleryTab.FAVORITES ->
                                        viewModel.switchScope(AppGalleryFolderModel.FAVORITES_ID)
                                    else -> Unit
                                }
                            },
                            text = { Text(entry.label, fontSize = 12.sp) },
                        )
                    }
                }

                when (tab) {
                    AppGalleryTab.ALL, AppGalleryTab.FAVORITES -> {
                        if (searchVisible) {
                            AppGallerySearchBar(
                                text = rawSearch,
                                onTextChange = viewModel::updateSearchText,
                                onClear = viewModel::clearFilters,
                            )
                        }
                        AppGalleryCategoryFilterBar(
                            ordered = AppGalleryCategory.FILTER_ORDER,
                            selected = selectedCategories,
                            onToggle = viewModel::toggleCategoryFilter,
                        )
                        AppGalleryViewSwitcher(
                            viewMode = viewMode,
                            sortSpec = sortSpec,
                            granularity = granularity,
                            onViewMode = viewModel::setViewMode,
                            onSortField = { field -> viewModel.setSortSpec(sortSpec.copy(field = field)) },
                            onToggleSortOrder = viewModel::toggleSortOrder,
                            onGranularity = viewModel::setGranularity,
                        )
                        if (items.isEmpty()) {
                            AppGalleryEmptyState(
                                text = if (allItems.isEmpty()) "还没有照片" else "没有符合条件的照片",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            when (viewMode) {
                                AppGalleryViewMode.GRID -> AppGalleryGridView(
                                    items = items,
                                    columnCount = columnCount,
                                    selectionIds = selection.selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onOpen = openPreview,
                                    onToggleSelection = { viewModel.toggleSelection(it.id) },
                                    onLongPress = startSelection,
                                )

                                AppGalleryViewMode.LIST -> AppGalleryListView(
                                    items = items,
                                    rowHeightDp = rowHeight,
                                    selectionIds = selection.selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onOpen = openPreview,
                                    onToggleSelection = { viewModel.toggleSelection(it.id) },
                                    onLongPress = startSelection,
                                )

                                AppGalleryViewMode.TIMELINE -> AppGalleryTimelineView(
                                    groups = timelineGroups,
                                    granularity = granularity,
                                    columnCount = columnCount,
                                    selectionIds = selection.selectedIds,
                                    isSelectionMode = isSelectionMode,
                                    onOpen = openPreview,
                                    onToggleSelection = { viewModel.toggleSelection(it.id) },
                                    onLongPress = startSelection,
                                )

                                AppGalleryViewMode.YEAR_CALENDAR -> AppGalleryYearCalendarView(
                                    buckets = yearBuckets,
                                    onOpenYear = { /* 年 → 过滤到该年（搜索语法可复用） */ },
                                    onOpenDay = { _, dateKey ->
                                        viewModel.updateSearchText("since:$dateKey until:$dateKey")
                                        viewModel.setViewMode(AppGalleryViewMode.GRID)
                                    },
                                )
                            }
                        }
                    }

                    AppGalleryTab.FOLDERS -> AppGalleryFolderPanel(
                        folders = folders,
                        onCreate = viewModel::createFolder,
                        onRename = viewModel::renameFolder,
                        onDelete = viewModel::deleteFolder,
                        onOpen = { folder ->
                            viewModel.switchScope(folder.id)
                            tab = AppGalleryTab.ALL
                        },
                        onCreateSmartByTags = viewModel::createSmartFoldersForTags,
                    )

                    AppGalleryTab.TRASH -> AppGalleryTrashPanel(
                        entries = trash,
                        summaryText = viewModel.trashSummaryText(),
                        retentionDays = config.trashRetentionDays,
                        onRestore = viewModel::restoreFromTrash,
                        onPurge = viewModel::purgeTrashEntry,
                        onEmpty = viewModel::emptyTrash,
                    )

                    AppGalleryTab.STATS -> AppGalleryStatsPanel(
                        report = stats,
                        granularity = granularity,
                        onGranularity = {
                            viewModel.setGranularity(it)
                            viewModel.computeStats(it)
                        },
                        onCleanup = { categories -> viewModel.startStorageCleanup(categories, null) },
                    )

                    AppGalleryTab.DUPLICATES -> AppGalleryDuplicatePanel(
                        groups = duplicateGroups,
                        threshold = config.duplicateHammingThreshold,
                        onScan = viewModel::startDuplicateScan,
                        onDeleteDuplicates = viewModel::deleteDuplicates,
                    )

                    AppGalleryTab.PLACES -> AppGalleryPlacePanel(
                        clusters = placeClusters,
                        onOpenMap = onOpenMap,
                        onRecompute = viewModel::computePlaceClusters,
                    )

                    AppGalleryTab.INFO -> AppGalleryMetadataPanel(item = infoItem)

                    AppGalleryTab.SETTINGS -> AppGallerySettingsPanel(
                        config = config,
                        onUpdate = viewModel::updateConfig,
                        onBackfillThumbnails = viewModel::startThumbnailBackfill,
                        onBackup = { viewModel.startBackup(AppGalleryExportShape.ZIP) },
                    )
                }

                if (lastReport != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = lastReport?.summaryText().orEmpty(),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (lastReport?.action == AppGalleryBatchAction.DELETE) {
                            TextButton(onClick = { viewModel.undoLastDelete() }) { Text("撤销") }
                        }
                    }
                }
            }
        }
    }
}

/** 深色模式（相册项 19）：仅当用户强制浅/深色时覆盖子树配色，跟随系统时沿用宿主主题。 */
@Composable
private fun AppGalleryContentContainer(
    themeMode: AppGalleryThemeMode,
    content: @Composable () -> Unit,
) {
    when (themeMode) {
        AppGalleryThemeMode.FOLLOW_SYSTEM -> content()
        AppGalleryThemeMode.LIGHT -> MaterialTheme(colorScheme = lightColorScheme()) { content() }
        AppGalleryThemeMode.DARK -> MaterialTheme(colorScheme = darkColorScheme()) { content() }
    }
}

/** 批量操作条（相册项 8）：删除 / 收藏 / 评分 / 标签 / 移动 / 导出 / 分享。 */
@Composable
private fun AppGalleryBatchBar(
    count: Int,
    folders: List<String>,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onUnfavorite: () -> Unit,
    onRating: (Int) -> Unit,
    onAddTags: (List<String>) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onMoveToFolder: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
            IconButton(onClick = onFavorite) {
                Icon(Icons.Default.Share, contentDescription = "收藏")
            }
            OutlinedButton(onClick = { menu = "rating" }) { Text("评分", fontSize = 12.sp) }
            OutlinedButton(onClick = { menu = "tags" }) { Text("标签", fontSize = 12.sp) }
            OutlinedButton(onClick = { menu = "folder" }) { Text("移动", fontSize = 12.sp) }
            OutlinedButton(onClick = onExport) { Text("导出", fontSize = 12.sp) }
            OutlinedButton(onClick = onShare) { Text("分享", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "$count 项", fontSize = 12.sp)
        }
        Box {
            DropdownMenu(expanded = menu == "rating", onDismissRequest = { menu = null }) {
                (1..5).forEach { value ->
                    DropdownMenuItem(
                        text = { Text("★".repeat(value), fontSize = 13.sp) },
                        onClick = {
                            menu = null
                            onRating(value)
                        },
                    )
                }
            }
            DropdownMenu(expanded = menu == "tags", onDismissRequest = { menu = null }) {
                listOf("精选", "待整理", "分享过", "重要").forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag, fontSize = 13.sp) },
                        onClick = {
                            menu = null
                            onAddTags(listOf(tag))
                        },
                    )
                }
            }
            DropdownMenu(expanded = menu == "folder", onDismissRequest = { menu = null }) {
                folders.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name, fontSize = 13.sp) },
                        onClick = {
                            menu = null
                            onMoveToFolder(index)
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onUnfavorite) { Text("取消收藏", fontSize = 12.sp) }
        }
    }
}
