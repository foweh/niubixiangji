package com.hinnka.mycamera.ui.appgallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hinnka.mycamera.appgallery.AppGalleryAnnotation
import com.hinnka.mycamera.appgallery.AppGalleryAnnotationMachine
import com.hinnka.mycamera.appgallery.AppGalleryBatchAction
import com.hinnka.mycamera.appgallery.AppGalleryBatchItemResult
import com.hinnka.mycamera.appgallery.AppGalleryBatchOutcome
import com.hinnka.mycamera.appgallery.AppGalleryBatchPlanner
import com.hinnka.mycamera.appgallery.AppGalleryBatchRequest
import com.hinnka.mycamera.appgallery.AppGalleryBatchReport
import com.hinnka.mycamera.appgallery.AppGalleryBgTasks
import com.hinnka.mycamera.appgallery.AppGalleryCategory
import com.hinnka.mycamera.appgallery.AppGalleryConfig
import com.hinnka.mycamera.appgallery.AppGalleryDuplicateGroup
import com.hinnka.mycamera.appgallery.AppGalleryExportShape
import com.hinnka.mycamera.appgallery.AppGalleryFilter
import com.hinnka.mycamera.appgallery.AppGalleryFolder
import com.hinnka.mycamera.appgallery.AppGalleryFolderStore
import com.hinnka.mycamera.appgallery.AppGalleryGeoPoint
import com.hinnka.mycamera.appgallery.AppGalleryItem
import com.hinnka.mycamera.appgallery.AppGalleryPlaceCluster
import com.hinnka.mycamera.appgallery.AppGalleryQuery
import com.hinnka.mycamera.appgallery.AppGalleryRepository
import com.hinnka.mycamera.appgallery.AppGalleryScanBridge
import com.hinnka.mycamera.appgallery.AppGallerySearchParser
import com.hinnka.mycamera.appgallery.AppGallerySelection
import com.hinnka.mycamera.appgallery.AppGallerySelectionMachine
import com.hinnka.mycamera.appgallery.AppGallerySettingsStore
import com.hinnka.mycamera.appgallery.AppGallerySortSpec
import com.hinnka.mycamera.appgallery.AppGallerySorter
import com.hinnka.mycamera.appgallery.AppGalleryStats
import com.hinnka.mycamera.appgallery.AppGalleryStatsReport
import com.hinnka.mycamera.appgallery.AppGalleryTimeGroup
import com.hinnka.mycamera.appgallery.AppGalleryTimeline
import com.hinnka.mycamera.appgallery.AppGalleryTrashEntry
import com.hinnka.mycamera.appgallery.AppGalleryTrashRules
import com.hinnka.mycamera.appgallery.AppGalleryTrashStore
import com.hinnka.mycamera.appgallery.AppGalleryViewMemory
import com.hinnka.mycamera.appgallery.AppGalleryViewMemoryKeeper
import com.hinnka.mycamera.appgallery.AppGalleryViewMode
import com.hinnka.mycamera.appgallery.AppGalleryYearBucket
import com.hinnka.mycamera.appgallery.AppGalleryYearCalendar
import com.hinnka.mycamera.appgallery.TimelineGranularity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 应用内置相册的界面状态（相册项 1–21 的 UI 侧）。
 *
 * 所有业务规则都在 `appgallery` 纯逻辑包里，本类只做「状态持有 + 调用 + 派发后台任务」，
 * 便于单测覆盖纯逻辑、UI 保持薄。
 */
class AppGalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppGallerySettingsStore.getInstance(application)
    private val repository = AppGalleryRepository(application)
    private val trashStore = AppGalleryTrashStore(application, settings)
    private val folderStore = AppGalleryFolderStore(application, settings)

    private val zoneId: ZoneId = ZoneId.systemDefault()

    // ── 基础状态 ────────────────────────────────────────────────

    private val _config = MutableStateFlow(AppGalleryConfig())
    val config: StateFlow<AppGalleryConfig> = _config

    private val _items = MutableStateFlow<List<AppGalleryItem>>(emptyList())
    val items: StateFlow<List<AppGalleryItem>> = _items

    private val _folders = MutableStateFlow<List<AppGalleryFolder>>(emptyList())
    val folders: StateFlow<List<AppGalleryFolder>> = _folders

    private val _trash = MutableStateFlow<List<AppGalleryTrashEntry>>(emptyList())
    val trash: StateFlow<List<AppGalleryTrashEntry>> = _trash

    private val _viewMode = MutableStateFlow(AppGalleryViewMode.GRID)
    val viewMode: StateFlow<AppGalleryViewMode> = _viewMode

    private val _granularity = MutableStateFlow(TimelineGranularity.DAY)
    val granularity: StateFlow<TimelineGranularity> = _granularity

    private val _sortSpec = MutableStateFlow(AppGallerySortSpec())
    val sortSpec: StateFlow<AppGallerySortSpec> = _sortSpec

    private val _query = MutableStateFlow(AppGalleryQuery())
    val query: StateFlow<AppGalleryQuery> = _query

    private val _rawSearchText = MutableStateFlow("")
    val rawSearchText: StateFlow<String> = _rawSearchText

    private val _selectedCategories = MutableStateFlow<Set<AppGalleryCategory>>(emptySet())
    val selectedCategories: StateFlow<Set<AppGalleryCategory>> = _selectedCategories

    private val _selection = MutableStateFlow(AppGallerySelection())
    val selection: StateFlow<AppGallerySelection> = _selection

    private val _scopeId = MutableStateFlow(AppGalleryViewMemoryKeeper.SCOPE_ALL)
    val scopeId: StateFlow<String> = _scopeId

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _statsReport = MutableStateFlow(
        AppGalleryStats.compute(emptyList(), TimelineGranularity.MONTH)
    )
    val statsReport: StateFlow<AppGalleryStatsReport> = _statsReport

    private val _duplicateGroups = MutableStateFlow<List<AppGalleryDuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<AppGalleryDuplicateGroup>> = _duplicateGroups

    private val _placeClusters = MutableStateFlow<List<AppGalleryPlaceCluster>>(emptyList())
    val placeClusters: StateFlow<List<AppGalleryPlaceCluster>> = _placeClusters

    private val _lastReport = MutableStateFlow<AppGalleryBatchReport?>(null)
    val lastReport: StateFlow<AppGalleryBatchReport?> = _lastReport

    /** 最近一次「移入回收站」的条目，用于撤销。 */
    private val _lastTrashBatch = MutableStateFlow<List<String>>(emptyList())

    // ── 派生状态 ────────────────────────────────────────────────

    /** 过滤 + 排序后的可见列表（列表/网格/时间线/年历共用）。 */
    val visibleItems: StateFlow<List<AppGalleryItem>> =
        combine(_items, _query, _sortSpec, _config) { items, query, spec, config ->
            val filtered = AppGalleryFilter.apply(items, query, config)
            AppGallerySorter.sort(filtered, spec)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 时间线分组（按日/月/年）。 */
    val timelineGroups: StateFlow<List<AppGalleryTimeGroup>> =
        combine(visibleItems, _granularity, _config) { items, granularity, _ ->
            AppGalleryTimeline.group(items, granularity, zoneId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 年历视图（按年 → 稀疏日格子）。 */
    val yearBuckets: StateFlow<List<AppGalleryYearBucket>> =
        combine(visibleItems, _config) { items, _ ->
            AppGalleryYearCalendar.build(items, zoneId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 收藏数（顶栏徽标）。 */
    val favoriteCount: StateFlow<Int> =
        combine(_items, _config) { items, config ->
            AppGalleryFilter.apply(items, AppGalleryQuery(favoritesOnly = true), config).size
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            val config = settings.loadConfig()
            _config.value = config
            _viewMode.value = config.defaultViewMode
            _granularity.value = config.timelineGranularity
            _sortSpec.value = AppGallerySortSpec.from(config)
            _folders.value = folderStore.folders()
            _trash.value = trashStore.entries()
            refresh()
        }
    }

    // ── 加载 ────────────────────────────────────────────────────

    /** 重新加载条目（应用库 + 系统相册），并应用当前筛选上下文。 */
    fun refresh(includeSystemAlbum: Boolean = true) {
        viewModelScope.launch {
            _loading.value = true
            val config = _config.value
            val annotations = settings.loadAnnotations()
            val folderMap = folderStore.assignments()
            val snapshot = repository.loadSnapshot(
                includeSystemAlbum = includeSystemAlbum,
                annotations = annotations,
                folderAssignments = folderMap,
            )
            _items.value = snapshot.items
            _folders.value = folderStore.folders()
            _loading.value = false
        }
    }

    /** 局部刷新（刚拍完/刚处理完，只取新输出）。 */
    fun refreshRecent(sinceMillis: Long) {
        viewModelScope.launch {
            val config = _config.value
            val newItems = AppGalleryScanBridge.collectNewOutputs(
                context = getApplication(),
                sinceMillis = sinceMillis,
                annotations = settings.loadAnnotations(),
            )
            if (newItems.isEmpty()) return@launch
            val known = _items.value.map { it.id }.toSet()
            val merged = (newItems.filterNot { known.contains(it.id) } + _items.value)
            _items.value = merged
            _message.value = "新增 ${newItems.size} 项"
        }
    }

    /** 更新配置（写盘 + 影响后续渲染）。 */
    fun updateConfig(transform: (AppGalleryConfig) -> AppGalleryConfig) {
        viewModelScope.launch {
            val updated = transform(_config.value)
            settings.saveConfig(updated)
            _config.value = AppGalleryConfig.clamp(updated)
        }
    }

    fun setThemeMode(mode: com.hinnka.mycamera.appgallery.AppGalleryThemeMode) {
        updateConfig { it.copy(themeMode = mode) }
    }

    // ── 视图 / 排序 / 搜索 ──────────────────────────────────────

    fun setViewMode(mode: AppGalleryViewMode) {
        _viewMode.value = mode
        rememberView()
    }

    fun setGranularity(granularity: TimelineGranularity) {
        _granularity.value = granularity
        updateConfig { it.copy(timelineGranularity = granularity) }
    }

    fun setSortSpec(spec: AppGallerySortSpec) {
        _sortSpec.value = spec
        rememberView()
    }

    fun toggleSortOrder() {
        setSortSpec(_sortSpec.value.copy(order = _sortSpec.value.order.flipped()))
    }

    /** 搜索文本变化 → 解析语法（`type:` `tag:` `fav:` `rating:` `since:` `size:`）。 */
    fun updateSearchText(text: String) {
        _rawSearchText.value = text
        _query.value = AppGallerySearchParser.parse(text, AppGalleryQuery(), _config.value)
    }

    fun applyQuery(query: AppGalleryQuery) {
        _query.value = AppGalleryQuery.clamp(query, _config.value)
    }

    fun toggleCategoryFilter(category: AppGalleryCategory) {
        val current = _selectedCategories.value
        _selectedCategories.value =
            if (current.contains(category)) current - category else current + category
        applyQuery(_query.value.copy(categories = _selectedCategories.value))
    }

    fun clearFilters() {
        _selectedCategories.value = emptySet()
        _rawSearchText.value = ""
        _query.value = AppGalleryQuery()
    }

    /**
     * 切换浏览范围（全部 / 某个相册 / 收藏夹）。
     * 作用：过滤列表 + 记忆视图 + 相册 id 作为筛选条件。
     */
    fun switchScope(scopeId: String) {
        _scopeId.value = scopeId
        viewModelScope.launch {
            val config = _config.value
            if (scopeId == AppGalleryViewMemoryKeeper.SCOPE_ALL) {
                applyQuery(_query.value.copy(folderId = null, favoritesOnly = false))
            } else {
                val folder = _folders.value.firstOrNull { it.id == scopeId }
                if (folder?.isFavoriteFolder == true) {
                    applyQuery(_query.value.copy(folderId = null, favoritesOnly = true))
                } else {
                    applyQuery(_query.value.copy(folderId = scopeId, favoritesOnly = false))
                }
            }
            if (config.rememberViewPerFolder) {
                val memory = settings.resolveViewMemory(scopeId)
                _viewMode.value = memory.viewMode
                _sortSpec.value = AppGallerySortSpec(memory.sortField, memory.sortOrder)
                _granularity.value = memory.timelineGranularity
            }
        }
    }

    /** 记忆当前视图（滚动锚点可选）。 */
    fun rememberView(anchorId: String? = null) {
        viewModelScope.launch {
            if (!_config.value.rememberViewPerFolder) return@launch
            settings.recordViewMemory(
                scopeId = _scopeId.value,
                viewMode = _viewMode.value,
                sortSpec = _sortSpec.value,
                granularity = _granularity.value,
                scrollAnchorId = anchorId,
            )
        }
    }

    // ── 收藏 / 标签 / 评分 ──────────────────────────────────────

    fun toggleFavorite(itemId: String) {
        viewModelScope.launch {
            settings.toggleFavorite(itemId)
            applyAnnotationToItems(itemId, settings.loadAnnotations()[itemId])
        }
    }

    fun cycleRating(itemId: String) {
        viewModelScope.launch {
            settings.cycleRating(itemId)
            applyAnnotationToItems(itemId, settings.loadAnnotations()[itemId])
        }
    }

    fun addTag(itemId: String, tag: String) {
        viewModelScope.launch {
            settings.addTag(itemId, tag)
            applyAnnotationToItems(itemId, settings.loadAnnotations()[itemId])
        }
    }

    private fun applyAnnotationToItems(itemId: String, annotation: AppGalleryAnnotation?) {
        _items.value = _items.value.map { item ->
            if (item.id != itemId) {
                item
            } else {
                item.copy(
                    isFavorite = annotation?.isFavorite ?: false,
                    rating = annotation?.rating ?: 0,
                    tags = annotation?.tags ?: emptyList(),
                )
            }
        }
    }

    // ── 多选 ────────────────────────────────────────────────────

    fun toggleSelection(itemId: String) {
        _selection.value = AppGallerySelectionMachine.toggle(_selection.value, itemId)
    }

    fun selectRange(itemId: String, orderedIds: List<String>) {
        _selection.value = AppGallerySelectionMachine.selectRange(_selection.value, orderedIds, itemId)
    }

    fun selectAll() {
        _selection.value = AppGallerySelectionMachine.selectAll(
            _selection.value,
            visibleItems.value.map { it.id },
        )
    }

    fun clearSelection() {
        _selection.value = AppGallerySelectionMachine.clear(_selection.value)
    }

    fun invertSelection() {
        _selection.value = AppGallerySelectionMachine.invert(
            _selection.value,
            visibleItems.value.map { it.id },
        )
    }

    fun selectedItems(): List<AppGalleryItem> {
        val ids = _selection.value.selectedIds
        return visibleItems.value.filter { ids.contains(it.id) }
    }

    // ── 删除 / 回收站 ───────────────────────────────────────────

    /** 批量删除（先入回收站；回收站关闭时走彻底删除）。 */
    fun deleteSelected() {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            val config = _config.value
            if (!config.trashEnabled) {
                val report = repository.purgeItems(targets.map { it.id })
                folderStore.purgeItems(targets.map { it.id })
                _lastReport.value = report
                _message.value = "已彻底删除 ${report.succeeded} 项"
            } else {
                val results = trashStore.moveToTrash(targets, config)
                val okIds = results.filter { it.success }.map { it.itemId }
                val batchResults = results.map { result ->
                    AppGalleryBatchItemResult(
                        itemId = result.itemId,
                        outcome = if (result.success) {
                            AppGalleryBatchOutcome.SUCCESS
                        } else {
                            AppGalleryBatchOutcome.SKIPPED
                        },
                        message = result.reason,
                    )
                }
                _lastReport.value = AppGalleryBatchPlanner.buildReport(AppGalleryBatchAction.DELETE, batchResults)
                _lastTrashBatch.value = okIds
                if (okIds.isNotEmpty()) {
                    folderStore.purgeItems(okIds)
                    _items.value = _items.value.filterNot { okIds.contains(it.id) }
                }
                _trash.value = trashStore.entries()
                _message.value = "已移入回收站 ${okIds.size} 项"
            }
            clearSelection()
            _loading.value = false
        }
    }

    /** 撤销最近一次删除（从回收站恢复）。 */
    fun undoLastDelete() {
        val ids = _lastTrashBatch.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { trashStore.restore(it) }
            _lastTrashBatch.value = emptyList()
            _trash.value = trashStore.entries()
            _message.value = "已恢复 ${ids.size} 项"
            refresh()
        }
    }

    fun restoreFromTrash(itemId: String) {
        viewModelScope.launch {
            val result = trashStore.restore(itemId)
            _message.value = if (result.success) "已恢复" else result.reason
            _trash.value = trashStore.entries()
            refresh()
        }
    }

    fun purgeTrashEntry(itemId: String) {
        viewModelScope.launch {
            val before = _trash.value.size
            // 直接清空单条：复用「整表过滤 + 物理删除」逻辑
            val entry = _trash.value.firstOrNull { it.itemId == itemId } ?: return@launch
            java.io.File(entry.trashUri).let { file ->
                if (file.exists()) file.deleteRecursively()
            }
            settings.saveTrash(_trash.value.filterNot { it.itemId == itemId })
            settings.updateAnnotations { AppGalleryAnnotationMachine.drop(it, listOf(itemId)) }
            _trash.value = trashStore.entries()
            _message.value = if (_trash.value.size < before) "已彻底删除" else "删除失败"
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val removed = trashStore.emptyAll()
            _trash.value = trashStore.entries()
            _message.value = "已清空回收站（$removed 项）"
        }
    }

    fun refreshTrash() {
        viewModelScope.launch { _trash.value = trashStore.entries() }
    }

    // ── 批量操作 ────────────────────────────────────────────────

    fun batchFavorite(favorite: Boolean) {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            settings.applyBatch(
                AppGalleryBatchRequest(
                    action = if (favorite) AppGalleryBatchAction.FAVORITE_ON else AppGalleryBatchAction.FAVORITE_OFF,
                    itemIds = ids,
                )
            )
            val annotations = settings.loadAnnotations()
            _items.value = _items.value.map { item ->
                val annotation = annotations[item.id]
                item.copy(isFavorite = annotation?.isFavorite ?: item.isFavorite)
            }
            _message.value = if (favorite) "已收藏 ${ids.size} 项" else "已取消收藏 ${ids.size} 项"
            clearSelection()
        }
    }

    fun batchSetRating(rating: Int) {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            settings.applyBatch(
                AppGalleryBatchRequest(
                    action = AppGalleryBatchAction.SET_RATING,
                    itemIds = ids,
                    rating = rating,
                )
            )
            refresh()
            _message.value = "已设置评分（${ids.size} 项）"
            clearSelection()
        }
    }

    fun batchAddTags(tags: List<String>) {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty() || tags.isEmpty()) return
        viewModelScope.launch {
            settings.applyBatch(
                AppGalleryBatchRequest(
                    action = AppGalleryBatchAction.ADD_TAGS,
                    itemIds = ids,
                    tags = tags,
                )
            )
            refresh()
            _message.value = "已添加标签（${ids.size} 项）"
            clearSelection()
        }
    }

    fun batchMoveToFolder(folderId: String) {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val source = _folders.value.firstOrNull { it.itemIds.any { id -> ids.contains(id) } }?.id
            val mutation = folderStore.moveItems(ids, source, folderId)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已移动 ${ids.size} 项" else mutation.reason
            clearSelection()
            refresh()
        }
    }

    fun batchExport(shape: AppGalleryExportShape = AppGalleryExportShape.ZIP) {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            AppGalleryBgTasks.submitBackup(
                context = getApplication(),
                items = targets,
                shape = shape,
                config = _config.value,
                onFinish = { result ->
                    _message.value = "导出完成：${result.fileCount} 个文件 → ${result.outputPath ?: "-"}"
                },
            )
            _message.value = "已开始导出 ${targets.size} 项（后台）"
            clearSelection()
        }
    }

    // ── 相册管理 ────────────────────────────────────────────────

    fun refreshFolders() {
        viewModelScope.launch { _folders.value = folderStore.folders() }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val mutation = folderStore.create(name)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已创建相册" else mutation.reason
        }
    }

    fun renameFolder(folderId: String, name: String) {
        viewModelScope.launch {
            val mutation = folderStore.rename(folderId, name)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已重命名" else mutation.reason
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val mutation = folderStore.delete(folderId)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已删除相册" else mutation.reason
        }
    }

    fun setFolderAsSmart(folderId: String, query: String?) {
        viewModelScope.launch {
            val mutation = folderStore.setSmartQuery(folderId, query)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已更新智能条件" else mutation.reason
        }
    }

    fun createSmartFoldersForTags() {
        viewModelScope.launch {
            val vocabulary = AppGalleryAnnotationMachine.tagVocabulary(settings.loadAnnotations())
            val created = folderStore.createSmartFoldersForTags(vocabulary.map { it.first }.take(10))
            _folders.value = folderStore.folders()
            _message.value = if (created > 0) "已按标签创建 $created 个智能相册" else "没有可用的新标签"
        }
    }

    fun addSelectionToFolder(folderId: String) {
        val ids = _selection.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val mutation = folderStore.addItems(folderId, ids)
            _folders.value = mutation.folders
            _message.value = if (mutation.changed) "已加入相册" else mutation.reason
            clearSelection()
            refresh()
        }
    }

    // ── 统计 / 重复 / 地点 ──────────────────────────────────────

    fun computeStats(granularity: TimelineGranularity = _granularity.value) {
        viewModelScope.launch {
            val config = _config.value
            _statsReport.value = AppGalleryStats.compute(_items.value, granularity, config)
        }
    }

    fun startDuplicateScan() {
        viewModelScope.launch {
            AppGalleryBgTasks.submitDuplicateScan(
                context = getApplication(),
                items = _items.value,
                config = _config.value,
                onFinish = { result ->
                    _duplicateGroups.value = result.groups
                    _message.value = "发现 ${result.groups.size} 组重复照片"
                },
            )
            _message.value = "重复检测已在后台开始"
        }
    }

    /** 保留每组保留优先级最高的条目，其余移入回收站。 */
    fun deleteDuplicates() {
        val groups = _duplicateGroups.value
        if (groups.isEmpty()) return
        viewModelScope.launch {
            val ids = groups.flatMap { it.duplicateIds }.distinct()
            val targets = _items.value.filter { ids.contains(it.id) }
            val results = trashStore.moveToTrash(targets, _config.value)
            val ok = results.filter { it.success }.map { it.itemId }
            _items.value = _items.value.filterNot { ok.contains(it.id) }
            _duplicateGroups.value = emptyList()
            _trash.value = trashStore.entries()
            _message.value = "已移入回收站 ${ok.size} 项"
        }
    }

    fun startStorageCleanup(categories: Set<AppGalleryCategory>, maxBytes: Long?) {
        viewModelScope.launch {
            AppGalleryBgTasks.submitStorageCleanup(
                context = getApplication(),
                items = _items.value,
                categories = categories,
                maxBytes = maxBytes,
                protectFavorites = true,
                config = _config.value,
                onFinish = { moved ->
                    _message.value = "已清理 $moved 项（可在回收站恢复）"
                },
            )
            _message.value = "清理任务已提交（后台执行）"
        }
    }

    fun computePlaceClusters() {
        viewModelScope.launch {
            val config = _config.value
            val points = _items.value.mapNotNull { item ->
                val lat = item.latitude
                val lon = item.longitude
                if (lat == null || lon == null) {
                    null
                } else {
                    com.hinnka.mycamera.appgallery.AppGalleryGeoPoint(item.id, lat, lon)
                }
            }
            val clusters = AppGalleryPlaceClustering.cluster(
                points = points,
                config = config,
                cityIndex = settings.loadCityTable(),
            )
            _placeClusters.value = clusters
            if (clusters.isEmpty()) _message.value = "没有带定位信息的照片"
        }
    }

    fun startThumbnailBackfill() {
        viewModelScope.launch {
            AppGalleryBgTasks.submitThumbnailBackfill(
                context = getApplication(),
                items = _items.value,
                config = _config.value,
                onFinish = { result ->
                    _message.value = "缩略图补齐：成功 ${result.generated}，失败 ${result.failed}"
                },
            )
            _message.value = "缩略图补齐已提交（后台执行）"
        }
    }

    fun startTrashPurge() {
        viewModelScope.launch {
            AppGalleryBgTasks.submitTrashPurge(
                context = getApplication(),
                config = _config.value,
                onFinish = { purged ->
                    if (purged > 0) _message.value = "已自动清空 $purged 项过期内容"
                    refreshTrash()
                },
            )
        }
    }

    fun startBackup(shape: AppGalleryExportShape = AppGalleryExportShape.ZIP) {
        viewModelScope.launch {
            AppGalleryBgTasks.submitBackup(
                context = getApplication(),
                items = visibleItems.value,
                shape = shape,
                config = _config.value,
                onFinish = { result ->
                    _message.value = "备份完成：${result.fileCount} 个文件"
                },
            )
            _message.value = "备份已提交（后台执行）"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** 回收站概览（面板展示）。 */
    fun trashSummaryText(now: Long = System.currentTimeMillis()): String {
        val summary = AppGalleryTrashRules.summary(_trash.value, now)
        return "共 ${summary.count} 项 · ${AppGalleryItem.formatBytes(summary.totalBytes)}" +
            (if (summary.expiringSoonCount > 0) " · ${summary.expiringSoonCount} 项即将到期" else "")
    }

    companion object {
        /** 视图记忆键：全部 / 单相册（供外部导航复用）。 */
        fun scopeForFolder(folderId: String?): String =
            folderId ?: AppGalleryViewMemoryKeeper.SCOPE_ALL
    }
}
