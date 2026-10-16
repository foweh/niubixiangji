package com.hinnka.mycamera.appgallery

import android.content.Context

/**
 * 相册/分组管理（相册项 11：新建 / 重命名 / 移动 / 删除相册、收藏夹）。
 *
 * 持久化复用 [AppGallerySettingsStore] 的 DataStore 载荷（[AppGalleryFolderCodec]），
 * 本类只负责「业务动作 + 结果回写 + 反向索引」，不重复实现存储。
 */
class AppGalleryFolderStore(
    private val context: Context,
    private val settings: AppGallerySettingsStore = AppGallerySettingsStore.getInstance(context),
) {

    suspend fun folders(): List<AppGalleryFolder> = settings.loadFolders()

    /** itemId → 所属相册 id 列表（供列表筛选与徽标使用）。 */
    suspend fun assignments(): Map<String, List<String>> = invert(folders())

    fun invert(folders: List<AppGalleryFolder>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        folders.forEach { folder ->
            folder.itemIds.forEach { itemId ->
                out.getOrPut(itemId) { mutableListOf() }.add(folder.id)
            }
        }
        return out.mapValues { it.value.distinct() }
    }

    suspend fun create(name: String?, parentId: String? = null): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.create(folders(), name, System.currentTimeMillis(), parentId))

    suspend fun rename(folderId: String, name: String?): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.rename(folders(), folderId, name, System.currentTimeMillis()))

    suspend fun delete(folderId: String): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.delete(folders(), folderId))

    /** 设为智能相册（传 null 则退回普通相册）。 */
    suspend fun setSmartQuery(folderId: String, query: String?): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.setSmartQuery(folders(), folderId, query))

    suspend fun addItems(folderId: String, itemIds: Collection<String>): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.addItems(folders(), folderId, itemIds))

    suspend fun removeItems(folderId: String, itemIds: Collection<String>): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.removeItems(folders(), folderId, itemIds))

    suspend fun moveItems(
        itemIds: Collection<String>,
        fromFolderId: String?,
        toFolderId: String,
    ): AppGalleryFolderMutation =
        persist(AppGalleryFolderModel.moveItems(folders(), itemIds, fromFolderId, toFolderId))

    /** 条目被彻底删除后清理引用。 */
    suspend fun purgeItems(itemIds: Collection<String>): List<AppGalleryFolder> {
        val updated = AppGalleryFolderModel.purgeItems(folders(), itemIds)
        settings.saveFolders(updated)
        return updated
    }

    /** 设置相册封面。 */
    suspend fun setCover(folderId: String, itemId: String?): AppGalleryFolderMutation {
        val current = folders()
        if (current.none { it.id == folderId }) {
            return AppGalleryFolderMutation(current, false, "相册不存在")
        }
        val updated = current.map { if (it.id == folderId) it.copy(coverItemId = itemId) else it }
        settings.saveFolders(updated)
        return AppGalleryFolderMutation(updated, true)
    }

    /**
     * 便捷：按标签批量创建智能相册（相册项 5 + 11 的组合动作）。
     * 已存在同名智能相册时跳过；返回实际新建的数量。
     */
    suspend fun createSmartFoldersForTags(tags: Collection<String>): Int {
        var current = folders()
        var created = 0
        tags.forEach { tag ->
            val name = "#$tag"
            if (current.any { it.name.equals(name, ignoreCase = true) }) return@forEach
            val mutation = AppGalleryFolderModel.create(current, name, System.currentTimeMillis())
            mutation.target?.let { folder ->
                val smart = AppGalleryFolderModel.setSmartQuery(
                    mutation.folders,
                    folder.id,
                    "tag:$tag",
                )
                current = smart.folders
                created++
            }
        }
        if (created > 0) settings.saveFolders(current)
        return created
    }

    /**
     * 智能相册成员求值：把 [AppGalleryFolder.smartQuery] 交给搜索语法解析后过滤。
     * 收藏夹成员由收藏状态给出（见 [AppGalleryFolderModel.favoriteMembers]）。
     */
    fun resolveSmartMembers(
        folder: AppGalleryFolder,
        items: List<AppGalleryItem>,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): List<AppGalleryItem> {
        if (folder.isFavoriteFolder) return items.filter { it.isFavorite }
        val query = folder.smartQuery ?: return items.filter { folder.itemIds.contains(it.id) }
        val parsed = AppGallerySearchParser.parse(query, AppGalleryQuery(), config)
        return AppGalleryFilter.apply(items, parsed, config)
    }

    private suspend fun persist(mutation: AppGalleryFolderMutation): AppGalleryFolderMutation {
        if (mutation.changed) settings.saveFolders(mutation.folders)
        return mutation
    }
}
