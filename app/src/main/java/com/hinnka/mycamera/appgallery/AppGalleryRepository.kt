package com.hinnka.mycamera.appgallery

import android.content.Context
import android.net.Uri
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.gallery.GalleryRepository
import com.hinnka.mycamera.gallery.MediaData
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次库加载的快照。 */
data class AppGallerySnapshot(
    /** 合并后的条目（应用私有库在前，系统相册在后）。 */
    val items: List<AppGalleryItem>,
    val appItemCount: Int,
    val systemItemCount: Int,
    /** 系统相册中能关联到应用库的条数（去重收益）。 */
    val linkedCount: Int,
    val loadedAt: Long,
) {
    val totalCount: Int get() = items.size
}

/**
 * 应用内置相册数据仓库（相册项 1/2/21 的数据来源）。
 *
 * **完全复用工程既有存取层**，本类不新建数据库、不重复解析元数据：
 * - 应用私有产物：`GalleryRepository.getPhotosSync()`（内部走 `GalleryMediaStore` + Room DB），
 *   并用 `GalleryManager.getDngFile` 判断是否有 RAW 源；
 * - 系统相册：`GalleryRepository.getAllSystemPhotos()`（MediaStore 查询）；
 * - 去重关联：沿用 `MediaData.sourceUri` 关联思路（`GalleryViewModel.currentPhotos` 同款键集合）。
 *
 * 分类追加字段（`processingType`）走 DB 补丁；未应用补丁时该参数为 null，
 * 分类退化为「文件名/目录规则 + 既有元数据字段」，功能不受影响。
 */
class AppGalleryRepository(private val context: Context) {

    private val galleryRepository = GalleryRepository(context)

    /**
     * 加载应用私有库条目。
     *
     * @param persistedProcessingType 提供 photoId → 分类名 的映射（来自 DB 补丁列 `processingType`）。
     *   未应用补丁时传 null 即可。
     */
    suspend fun loadAppItems(
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        annotations: Map<String, AppGalleryAnnotation> = emptyMap(),
        folderAssignments: Map<String, List<String>> = emptyMap(),
        persistedProcessingType: (String) -> String? = { null },
        checkGpsForPlaceNames: Boolean = true,
    ): List<AppGalleryItem> = withContext(Dispatchers.IO) {
        val media = runCatching { galleryRepository.getPhotosSync() }
            .onFailure { PLog.w(TAG, "load app items failed", it) }
            .getOrDefault(emptyList())
        mapAppItems(media, rules, annotations, folderAssignments, persistedProcessingType, checkGpsForPlaceNames)
    }

    /** 纯映射（供 [loadSnapshot] 复用同一份列表，避免重复查询 DB）。 */
    private fun mapAppItems(
        media: List<MediaData>,
        rules: AppGalleryClassifierRules,
        annotations: Map<String, AppGalleryAnnotation>,
        folderAssignments: Map<String, List<String>>,
        persistedProcessingType: (String) -> String?,
        checkGpsForPlaceNames: Boolean,
    ): List<AppGalleryItem> = media.mapNotNull { item ->
        runCatching {
            AppGalleryItemMapper.toItem(
                media = item,
                source = AppGalleryItemSource.APP_DATABASE,
                hasDng = AppGalleryItemMapper.hasDng(context, item),
                persistedProcessingType = persistedProcessingType(item.id),
                annotation = annotations[item.id],
                folderIds = folderAssignments[item.id] ?: emptyList(),
                rules = rules,
                locationName = if (checkGpsForPlaceNames) {
                    AppGalleryItemMapper.preferredLocationName(item)
                } else {
                    null
                },
            )
        }.onFailure { PLog.w(TAG, "map app item failed: ${item.id}", it) }.getOrNull()
    }

    /** 加载系统相册条目。 */
    suspend fun loadSystemItems(
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        annotations: Map<String, AppGalleryAnnotation> = emptyMap(),
        folderAssignments: Map<String, List<String>> = emptyMap(),
    ): List<AppGalleryItem> = withContext(Dispatchers.IO) {
        val media = runCatching { galleryRepository.getAllSystemPhotos() }
            .onFailure { PLog.w(TAG, "load system items failed", it) }
            .getOrDefault(emptyList())
        media.mapNotNull { item ->
            runCatching {
                AppGalleryItemMapper.toItem(
                    media = item,
                    source = AppGalleryItemSource.SYSTEM_MEDIA_STORE,
                    hasDng = false,
                    annotation = annotations[item.id],
                    folderIds = folderAssignments[item.id] ?: emptyList(),
                    rules = rules,
                )
            }.onFailure { PLog.w(TAG, "map system item failed: ${item.id}", it) }.getOrNull()
        }
    }

    /**
     * 加载全部（应用库 + 系统相册），并做关联去重标记。
     *
     * @param includeSystemAlbum false = 只看应用自身产物（默认 true，与既有相册一致地同时展示两源）。
     */
    suspend fun loadSnapshot(
        includeSystemAlbum: Boolean = true,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        annotations: Map<String, AppGalleryAnnotation> = emptyMap(),
        folderAssignments: Map<String, List<String>> = emptyMap(),
        persistedProcessingType: (String) -> String? = { null },
    ): AppGallerySnapshot = withContext(Dispatchers.IO) {
        // 只查一次应用库（映射与去重索引共用同一份列表）
        val appMedia = runCatching { galleryRepository.getPhotosSync() }
            .onFailure { PLog.w(TAG, "load app media failed", it) }
            .getOrDefault(emptyList())
        val appItems = mapAppItems(
            media = appMedia,
            rules = rules,
            annotations = annotations,
            folderAssignments = folderAssignments,
            persistedProcessingType = persistedProcessingType,
            checkGpsForPlaceNames = true,
        )
        if (!includeSystemAlbum) {
            return@withContext AppGallerySnapshot(
                items = appItems,
                appItemCount = appItems.size,
                systemItemCount = 0,
                linkedCount = 0,
                loadedAt = System.currentTimeMillis(),
            )
        }
        val systemItems = loadSystemItems(rules, annotations, folderAssignments)
        // 复用既有 sourceUri 关联思路：用多种键（完整 URI / lastPathSegment）建立索引
        val appIndex = HashMap<String, String>()
        appMedia.forEach { media ->
            buildLinkKeys(media).forEach { key -> appIndex.putIfAbsent(key, media.id) }
        }
        var linked = 0
        val merged = systemItems.map { item ->
            val key = item.uri.substringAfterLast('/')
            val linkedId = appIndex[item.uri] ?: appIndex[key]
            if (linkedId != null) {
                linked++
                item.copy(linkedAppItemId = linkedId)
            } else {
                item
            }
        }
        AppGallerySnapshot(
            items = appItems + merged,
            appItemCount = appItems.size,
            systemItemCount = merged.size,
            linkedCount = linked,
            loadedAt = System.currentTimeMillis(),
        )
    }

    /** 与既有 `GalleryViewModel.currentPhotos` 同款的关联键集合。 */
    private fun buildLinkKeys(media: MediaData): List<String> = buildList {
        media.sourceUri?.toString()?.let { add(it) }
        media.metadata?.sourceUri?.let { add(it) }
        media.sourceUri?.lastPathSegment?.let { add(it) }
        media.metadata?.sourceUri?.let { raw ->
            runCatching { Uri.parse(raw).lastPathSegment }.getOrNull()?.let { add(it) }
        }
        media.uri.lastPathSegment?.let { add(it) }
    }

    /**
     * 单个条目（用于预览页取元数据、编辑后刷新）。
     */
    suspend fun findById(itemId: String): AppGalleryItem? = withContext(Dispatchers.IO) {
        val media = runCatching { galleryRepository.getPhotosSync() }
            .getOrDefault(emptyList())
            .firstOrNull { it.id == itemId }
            ?: return@withContext null
        runCatching {
            AppGalleryItemMapper.toItem(
                media = media,
                source = AppGalleryItemSource.APP_DATABASE,
                hasDng = AppGalleryItemMapper.hasDng(context, media),
            )
        }.getOrNull()
    }

    /**
     * 复用既有删除（物理删除应用私有产物，连同 `photos/<photoId>/` 目录与 DB 索引）。
     * 回收站流程请用 [AppGalleryTrashStore.moveToTrash]（本方法只作为「彻底删除」实现）。
     */
    suspend fun purgeItem(itemId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { GalleryManager.deletePhoto(context, itemId) }
            .onFailure { PLog.w(TAG, "purge item failed: $itemId", it) }
            .getOrDefault(false)
    }

    /** 批量彻底删除（串行，避免同时占用大量 IO）。 */
    suspend fun purgeItems(itemIds: Collection<String>): AppGalleryBatchReport {
        val results = itemIds.map { id ->
            AppGalleryBatchItemResult(
                itemId = id,
                outcome = if (purgeItem(id)) {
                    AppGalleryBatchOutcome.SUCCESS
                } else {
                    AppGalleryBatchOutcome.FAILED
                },
            )
        }
        return AppGalleryBatchPlanner.buildReport(AppGalleryBatchAction.PURGE, results)
    }

    companion object {
        private const val TAG = "AppGalleryRepo"
    }
}
