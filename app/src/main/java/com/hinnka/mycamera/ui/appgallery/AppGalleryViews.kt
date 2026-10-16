package com.hinnka.mycamera.ui.appgallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.appgallery.AppGalleryCategory
import com.hinnka.mycamera.appgallery.AppGalleryDisplay
import com.hinnka.mycamera.appgallery.AppGalleryItem
import com.hinnka.mycamera.appgallery.AppGallerySortField
import com.hinnka.mycamera.appgallery.AppGallerySortOrder
import com.hinnka.mycamera.appgallery.AppGallerySortSpec
import com.hinnka.mycamera.appgallery.AppGalleryTimeGroup
import com.hinnka.mycamera.appgallery.AppGalleryViewMode
import com.hinnka.mycamera.appgallery.AppGalleryYearBucket
import com.hinnka.mycamera.appgallery.TimelineGranularity

/**
 * 多视图渲染（相册项 1）：网格 / 列表 / 时间线 / 年历。
 *
 * 四种视图共用同一份「已过滤 + 已排序」列表与同一套点击回调，因此切换视图不会丢选中态；
 * 长按 = 进入多选，单击行为由 [AppGalleryDisplay.tapBehavior] 决定（相册项 20）。
 */
@Composable
fun AppGalleryGridView(
    items: List<AppGalleryItem>,
    columnCount: Int,
    selectionIds: Set<String>,
    isSelectionMode: Boolean,
    onOpen: (AppGalleryItem) -> Unit,
    onToggleSelection: (AppGalleryItem) -> Unit,
    onLongPress: (AppGalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount.coerceAtLeast(1)),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.id }) { item ->
            AppGalleryCell(
                item = item,
                selected = selectionIds.contains(item.id),
                isSelectionMode = isSelectionMode,
                onOpen = onOpen,
                onToggleSelection = onToggleSelection,
                onLongPress = onLongPress,
                modifier = Modifier.aspectRatio(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGalleryCell(
    item: AppGalleryItem,
    selected: Boolean,
    isSelectionMode: Boolean,
    onOpen: (AppGalleryItem) -> Unit,
    onToggleSelection: (AppGalleryItem) -> Unit,
    onLongPress: (AppGalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AppGalleryThumbnail(
            item = item,
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {
                        if (AppGalleryDisplay.tapBehavior(isSelectionMode) ==
                            com.hinnka.mycamera.appgallery.AppGalleryTapBehavior.TOGGLE_SELECTION
                        ) {
                            onToggleSelection(item)
                        } else {
                            onOpen(item)
                        }
                    },
                    onLongClick = { onLongPress(item) },
                ),
        )
        if (isSelectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection(item) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(32.dp),
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x332196F3)),
            )
        }
    }
}

/** 列表视图（相册项 1）：一行一条，含名称/时间/类型/大小。 */
@Composable
fun AppGalleryListView(
    items: List<AppGalleryItem>,
    rowHeightDp: Int,
    selectionIds: Set<String>,
    isSelectionMode: Boolean,
    onOpen: (AppGalleryItem) -> Unit,
    onToggleSelection: (AppGalleryItem) -> Unit,
    onLongPress: (AppGalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeightDp.dp)
                    .combinedClickable2(
                        onClick = {
                            if (AppGalleryDisplay.tapBehavior(isSelectionMode) ==
                                com.hinnka.mycamera.appgallery.AppGalleryTapBehavior.TOGGLE_SELECTION
                            ) {
                                onToggleSelection(item)
                            } else {
                                onOpen(item)
                            }
                        },
                        onLongClick = { onLongPress(item) },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = selectionIds.contains(item.id),
                        onCheckedChange = { onToggleSelection(item) },
                    )
                }
                AppGalleryThumbnail(
                    item = item,
                    modifier = Modifier
                        .size((rowHeightDp - 12).dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        maxLines = 1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${item.category.labelZh} · ${item.formattedSize()} · " +
                            item.formattedResolution(),
                        maxLines = 1,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (AppGalleryDisplay.badgesOf(item).isNotEmpty()) {
                    Text(
                        text = AppGalleryDisplay.badgesOf(item).first(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 时间线（相册项 1）：按日/月/年聚类，组头显示数量与占用。 */
@Composable
fun AppGalleryTimelineView(
    groups: List<AppGalleryTimeGroup>,
    granularity: TimelineGranularity,
    columnCount: Int,
    selectionIds: Set<String>,
    isSelectionMode: Boolean,
    onOpen: (AppGalleryItem) -> Unit,
    onToggleSelection: (AppGalleryItem) -> Unit,
    onLongPress: (AppGalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "header_${group.key}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = AppGalleryDisplay.timelineTitle(group.key, granularity),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${group.count} 项 · ${AppGalleryItem.formatBytes(group.totalBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(group.items, key = { it.id }) { item ->
                AppGalleryListRow(
                    item = item,
                    selected = selectionIds.contains(item.id),
                    isSelectionMode = isSelectionMode,
                    onOpen = onOpen,
                    onToggleSelection = onToggleSelection,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGalleryListRow(
    item: AppGalleryItem,
    selected: Boolean,
    isSelectionMode: Boolean,
    onOpen: (AppGalleryItem) -> Unit,
    onToggleSelection: (AppGalleryItem) -> Unit,
    onLongPress: (AppGalleryItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (AppGalleryDisplay.tapBehavior(isSelectionMode) ==
                        com.hinnka.mycamera.appgallery.AppGalleryTapBehavior.TOGGLE_SELECTION
                    ) {
                        onToggleSelection(item)
                    } else {
                        onOpen(item)
                    }
                },
                onLongClick = { onLongPress(item) },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection(item) },
            )
        }
        AppGalleryThumbnail(item = item, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.displayName, maxLines = 1, fontSize = 14.sp)
            Text(
                text = "${item.category.labelZh} · ${item.formattedSize()}",
                maxLines = 1,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 年历视图（相册项 1）：年 → 稀疏日格子。 */
@Composable
fun AppGalleryYearCalendarView(
    buckets: List<AppGalleryYearBucket>,
    onOpenYear: (Int) -> Unit,
    onOpenDay: (AppGalleryYearBucket, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        buckets.forEach { bucket ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${bucket.year} 年",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${bucket.totalCount} 项 · ${AppGalleryItem.formatBytes(bucket.totalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpenYear(bucket.year) }) { Text("查看") }
            }
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(bucket.cells, key = { it.dateKey }) { cell ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(6.dp)
                            .combinedClickable2(
                                onClick = { onOpenDay(bucket, cell.dateKey) },
                                onLongClick = {},
                            ),
                    ) {
                        Text(text = cell.dateKey.takeLast(5), fontSize = 12.sp)
                        Text(text = "${cell.count}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = AppGalleryItem.formatBytes(cell.totalBytes),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 变体包装：非多选场景（年历）不需要长按，仍复用 `combinedClickable`。
 * 单独抽出是为了避免在多个 composable 上重复书写 `@OptIn`。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickable2(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** 视图切换条 + 排序（相册项 1/6）。 */
@Composable
fun AppGalleryViewSwitcher(
    viewMode: AppGalleryViewMode,
    sortSpec: AppGallerySortSpec,
    granularity: TimelineGranularity,
    onViewMode: (AppGalleryViewMode) -> Unit,
    onSortField: (AppGallerySortField) -> Unit,
    onToggleSortOrder: () -> Unit,
    onGranularity: (TimelineGranularity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(AppGalleryViewMode.entries.toList(), key = { it.name }) { mode ->
            FilterChip(
                selected = mode == viewMode,
                onClick = { onViewMode(mode) },
                label = { Text(mode.labelZh, fontSize = 12.sp) },
            )
        }
        item { Spacer(modifier = Modifier.width(8.dp)) }
        items(AppGallerySortField.entries.toList(), key = { "sort_${it.name}" }) { field ->
            FilterChip(
                selected = field == sortSpec.field,
                onClick = { onSortField(field) },
                label = { Text(sortLabel(field), fontSize = 12.sp) },
            )
        }
        item {
            FilterChip(
                selected = sortSpec.order == AppGallerySortOrder.ASCENDING,
                onClick = { onToggleSortOrder() },
                label = {
                    Text(
                        if (sortSpec.order == AppGallerySortOrder.ASCENDING) "升序" else "降序",
                        fontSize = 12.sp,
                    )
                },
            )
        }
        if (viewMode == AppGalleryViewMode.TIMELINE) {
            items(TimelineGranularity.entries.toList(), key = { "gran_${it.name}" }) { granularityItem ->
                FilterChip(
                    selected = granularityItem == granularity,
                    onClick = { onGranularity(granularityItem) },
                    label = { Text(granularityItem.labelZh, fontSize = 12.sp) },
                )
            }
        }
    }
}

private fun sortLabel(field: AppGallerySortField): String = when (field) {
    AppGallerySortField.DATE -> "时间"
    AppGallerySortField.NAME -> "名称"
    AppGallerySortField.SIZE -> "大小"
    AppGallerySortField.CATEGORY -> "类型"
    AppGallerySortField.RATING -> "评分"
}

/** 搜索栏（相册项 6）：支持 `type:` `tag:` `fav:` `rating:` `since:` `size:` 语法。 */
@Composable
fun AppGallerySearchBar(
    text: String,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text("搜索：名称 / type:raw / fav:1 / tag:星空 / since:2026-01", fontSize = 13.sp) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("清除", fontSize = 12.sp) }
            }
        },
    )
}

/** 类型筛选条（相册项 2）。 */
@Composable
fun AppGalleryCategoryFilterBar(
    ordered: List<AppGalleryCategory>,
    selected: Set<AppGalleryCategory>,
    onToggle: (AppGalleryCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ordered, key = { it.name }) { category ->
            FilterChip(
                selected = selected.contains(category),
                onClick = { onToggle(category) },
                label = { Text(category.labelZh, fontSize = 12.sp) },
            )
        }
    }
}
