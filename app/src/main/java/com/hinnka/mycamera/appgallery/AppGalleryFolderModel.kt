package com.hinnka.mycamera.appgallery

import java.util.LinkedHashSet

/**
 * 相册（相册项 11：新建 / 重命名 / 移动 / 删除相册、收藏夹）。
 *
 * 三类相册：
 * - **普通相册**：显式持有 [itemIds]；
 * - **收藏夹**（[isFavoriteFolder]，固定 id [AppGalleryFolderModel.FAVORITES_ID]）：成员由收藏状态推导；
 * - **智能相册**（[isSmartFolder]）：成员由 [smartQuery] 描述（复用 [AppGallerySearchParser] 语法），
 *   例如 `rating:>=4 tag:风光`。
 */
data class AppGalleryFolder(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val isFavoriteFolder: Boolean = false,
    val isSmartFolder: Boolean = false,
    /** 智能相册的查询原文（仅 [isSmartFolder] 时有意义）。 */
    val smartQuery: String? = null,
    val coverItemId: String? = null,
    val itemIds: List<String> = emptyList(),
    /** 父相册（支持一层嵌套；null = 顶层）。 */
    val parentId: String? = null,
) {
    val count: Int get() = itemIds.size
}

/** 相册名校验结果。 */
data class AppGalleryFolderNameCheck(
    val valid: Boolean,
    /** 失败原因（中文可直接展示；合法时为 null）。 */
    val reason: String? = null,
    /** 清洗后的名字（合法时非空）。 */
    val sanitizedName: String = "",
)

/** 相册增改结果（纯数据，调用方据此刷新）。 */
data class AppGalleryFolderMutation(
    val folders: List<AppGalleryFolder>,
    /** 变更是否生效。 */
    val changed: Boolean,
    val reason: String? = null,
    /** 新建/重命名后的相册（其余操作为 null）。 */
    val target: AppGalleryFolder? = null,
)

/**
 * 相册/分组模型与操作（纯函数，可 JVM 单测）。
 */
object AppGalleryFolderModel {

    /** 收藏夹固定 id（不可删除、不可重命名）。 */
    const val FAVORITES_ID = "folder_favorites"

    /** 名称长度上限（字符）。 */
    const val MAX_NAME_LENGTH = 40

    /** 相册数量上限。 */
    const val MAX_FOLDERS = 500

    /** 单个相册成员上限（防单表无限膨胀；超出由智能相册承接）。 */
    const val MAX_ITEMS_PER_FOLDER = 20_000

    private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    /** 保留名称（避免与内置分组混淆）。 */
    private val RESERVED_NAMES = setOf("all", "全部", "最近", "recent")

    /** 内置收藏夹（首次使用时落盘）。 */
    fun defaultFolders(now: Long = 0L): List<AppGalleryFolder> = listOf(
        AppGalleryFolder(
            id = FAVORITES_ID,
            name = "收藏夹",
            createdAt = now,
            isFavoriteFolder = true,
        )
    )

    /** 清洗名称：去非法字符、压缩空白、截断。 */
    fun sanitizeName(raw: String?): String {
        if (raw == null) return ""
        return raw
            .replace(ILLEGAL_NAME_CHARS, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_NAME_LENGTH)
            .trim()
    }

    /**
     * 校验名称：空 / 超长 / 非法字符 / 保留名 / 重名（忽略大小写）都会被拒。
     * 注意：非法字符会被**清洗**而非拒绝——只有清洗后为空或重名才算失败。
     */
    fun validateName(
        raw: String?,
        existing: List<AppGalleryFolder>,
        excludeId: String? = null,
    ): AppGalleryFolderNameCheck {
        val trimmedRaw = raw?.trim().orEmpty()
        if (trimmedRaw.isEmpty()) {
            return AppGalleryFolderNameCheck(false, "相册名不能为空")
        }
        if (trimmedRaw.length > MAX_NAME_LENGTH) {
            return AppGalleryFolderNameCheck(false, "相册名不能超过 $MAX_NAME_LENGTH 个字")
        }
        val sanitized = sanitizeName(trimmedRaw)
        if (sanitized.isEmpty()) {
            return AppGalleryFolderNameCheck(false, "相册名包含非法字符")
        }
        if (sanitized.lowercase() in RESERVED_NAMES) {
            return AppGalleryFolderNameCheck(false, "「$sanitized」是保留名称，请换一个")
        }
        val duplicated = existing.any {
            it.id != excludeId && it.name.equals(sanitized, ignoreCase = true)
        }
        if (duplicated) {
            return AppGalleryFolderNameCheck(false, "已存在同名相册")
        }
        return AppGalleryFolderNameCheck(true, null, sanitized)
    }

    /** 新建相册。 */
    fun create(
        folders: List<AppGalleryFolder>,
        name: String?,
        now: Long = 0L,
        parentId: String? = null,
    ): AppGalleryFolderMutation {
        if (folders.size >= MAX_FOLDERS) {
            return AppGalleryFolderMutation(folders, false, "相册数量已达上限（$MAX_FOLDERS）")
        }
        val check = validateName(name, folders)
        if (!check.valid) return AppGalleryFolderMutation(folders, false, check.reason)
        val folder = AppGalleryFolder(
            id = "folder_" + now.toString(36) + "_" + folders.size.toString(36),
            name = check.sanitizedName,
            createdAt = now,
            parentId = parentId,
        )
        return AppGalleryFolderMutation(folders + folder, true, null, folder)
    }

    /** 重命名（收藏夹不可重命名；智能相册可重命名）。 */
    fun rename(
        folders: List<AppGalleryFolder>,
        folderId: String,
        name: String?,
        now: Long = 0L,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == folderId }
            ?: return AppGalleryFolderMutation(folders, false, "相册不存在")
        if (target.isFavoriteFolder) {
            return AppGalleryFolderMutation(folders, false, "收藏夹不可重命名")
        }
        val check = validateName(name, folders, excludeId = folderId)
        if (!check.valid) return AppGalleryFolderMutation(folders, false, check.reason)
        val renamed = target.copy(name = check.sanitizedName)
        return AppGalleryFolderMutation(
            folders = folders.map { if (it.id == folderId) renamed else it },
            changed = true,
            target = renamed,
        )
    }

    /** 删除相册（收藏夹不可删除；子相册提升为顶层）。 */
    fun delete(
        folders: List<AppGalleryFolder>,
        folderId: String,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == folderId }
            ?: return AppGalleryFolderMutation(folders, false, "相册不存在")
        if (target.isFavoriteFolder) {
            return AppGalleryFolderMutation(folders, false, "收藏夹不可删除")
        }
        val remaining = folders
            .filterNot { it.id == folderId }
            .map { if (it.parentId == folderId) it.copy(parentId = null) else it }
        return AppGalleryFolderMutation(remaining, true)
    }

    /** 更新智能相册条件。 */
    fun setSmartQuery(
        folders: List<AppGalleryFolder>,
        folderId: String,
        query: String?,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == folderId }
            ?: return AppGalleryFolderMutation(folders, false, "相册不存在")
        val normalized = query?.trim()?.takeIf { it.isNotEmpty() }
        val updated = target.copy(isSmartFolder = normalized != null, smartQuery = normalized)
        return AppGalleryFolderMutation(
            folders = folders.map { if (it.id == folderId) updated else it },
            changed = true,
            target = updated,
        )
    }

    /** 加入相册（去重、遵守容量上限；收藏夹不接受手动加入）。 */
    fun addItems(
        folders: List<AppGalleryFolder>,
        folderId: String,
        itemIds: Collection<String>,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == folderId }
            ?: return AppGalleryFolderMutation(folders, false, "相册不存在")
        if (target.isFavoriteFolder) {
            return AppGalleryFolderMutation(folders, false, "收藏夹由收藏状态自动维护")
        }
        if (target.isSmartFolder) {
            return AppGalleryFolderMutation(folders, false, "智能相册由条件自动维护")
        }
        if (itemIds.isEmpty()) return AppGalleryFolderMutation(folders, false, "没有可加入的条目")
        val merged = LinkedHashSet(target.itemIds)
        itemIds.forEach { if (merged.size < MAX_ITEMS_PER_FOLDER) merged.add(it) }
        val updated = target.copy(itemIds = merged.toList())
        return AppGalleryFolderMutation(
            folders = folders.map { if (it.id == folderId) updated else it },
            changed = true,
            target = updated,
        )
    }

    /** 移出相册。 */
    fun removeItems(
        folders: List<AppGalleryFolder>,
        folderId: String,
        itemIds: Collection<String>,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == folderId }
            ?: return AppGalleryFolderMutation(folders, false, "相册不存在")
        if (target.isFavoriteFolder || target.isSmartFolder) {
            return AppGalleryFolderMutation(folders, false, "该分组不支持手动移出")
        }
        val removeSet = itemIds.toSet()
        val remaining = target.itemIds.filterNot { removeSet.contains(it) }
        if (remaining.size == target.itemIds.size) {
            return AppGalleryFolderMutation(folders, false, "所选条目不在该相册中")
        }
        val updated = target.copy(itemIds = remaining)
        return AppGalleryFolderMutation(
            folders = folders.map { if (it.id == folderId) updated else it },
            changed = true,
            target = updated,
        )
    }

    /** 移动到另一个相册（先加入目标、再从源移出；收藏夹/智能相册不允许作为源或目标）。 */
    fun moveItems(
        folders: List<AppGalleryFolder>,
        itemIds: Collection<String>,
        fromFolderId: String?,
        toFolderId: String,
    ): AppGalleryFolderMutation {
        val target = folders.firstOrNull { it.id == toFolderId }
            ?: return AppGalleryFolderMutation(folders, false, "目标相册不存在")
        if (target.isFavoriteFolder || target.isSmartFolder) {
            return AppGalleryFolderMutation(folders, false, "目标分组不支持手动加入")
        }
        if (fromFolderId != null) {
            val source = folders.firstOrNull { it.id == fromFolderId }
                ?: return AppGalleryFolderMutation(folders, false, "源相册不存在")
            if (source.isFavoriteFolder || source.isSmartFolder) {
                return AppGalleryFolderMutation(folders, false, "源分组不支持手动移出")
            }
        }
        var current = addItems(folders, toFolderId, itemIds).folders
        if (fromFolderId != null) {
            current = removeItems(current, fromFolderId, itemIds).folders
        }
        return AppGalleryFolderMutation(current, true)
    }

    /** 删除条目后清理所有相册中的引用（含封面）。 */
    fun purgeItems(
        folders: List<AppGalleryFolder>,
        removedItemIds: Collection<String>,
    ): List<AppGalleryFolder> {
        if (removedItemIds.isEmpty()) return folders
        val removed = removedItemIds.toSet()
        return folders.map { folder ->
            val remaining = folder.itemIds.filterNot { removed.contains(it) }
            if (remaining.size == folder.itemIds.size && !removed.contains(folder.coverItemId)) {
                folder
            } else {
                folder.copy(
                    itemIds = remaining,
                    coverItemId = folder.coverItemId?.takeIf { !removed.contains(it) },
                )
            }
        }
    }

    /** 收藏夹成员视图（由收藏状态推导，不落盘）。 */
    fun favoriteMembers(annotations: Map<String, AppGalleryAnnotation>): List<String> =
        annotations.values.filter { it.isFavorite }.map { it.itemId }

    /** 按名称搜索相册。 */
    fun search(folders: List<AppGalleryFolder>, keyword: String?): List<AppGalleryFolder> {
        val key = keyword?.trim()?.lowercase()
        if (key.isNullOrEmpty()) return folders
        return folders.filter { it.name.lowercase().contains(key) }
    }

    /** 合集大小（含嵌套子相册）。 */
    fun effectiveItems(folder: AppGalleryFolder, folders: List<AppGalleryFolder>): List<String> {
        val children = folders.filter { it.parentId == folder.id }
        return (folder.itemIds + children.flatMap { it.itemIds }).distinct()
    }
}
