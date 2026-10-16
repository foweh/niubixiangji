package com.hinnka.mycamera.appgallery

import android.content.Context
import com.hinnka.mycamera.gallery.MediaMetadata
import com.hinnka.mycamera.gallery.db.GalleryMediaStore
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 单条入回收站的结果。 */
data class AppGalleryTrashMoveResult(
    val itemId: String,
    val success: Boolean,
    val entry: AppGalleryTrashEntry? = null,
    /** 失败/跳过原因（中文可直接展示）。 */
    val reason: String? = null,
)

/** 恢复结果。 */
data class AppGalleryTrashRestoreResult(
    val itemId: String,
    val success: Boolean,
    val reason: String? = null,
)

/**
 * 回收站（相册项 7：删除 → 回收站 → 恢复；到期自动清空）。
 *
 * 关键实现（务实且可逆）：
 * - 应用私有产物以 `photos/<photoId>/` **整个目录**为单位，从 `Pictures/photos/` 
 *   **同卷改名**到 `Pictures/appgallery_trash/<photoId>/`（毫秒级，不拷贝大文件）；
 * - 同时用 `GalleryMediaStore.deleteMedia` 摘掉 DB 索引（照片目录内容原样保留在回收站里）；
 * - 恢复时把目录改名回原位，再用目录内的 `metadata.json` 经 `MediaMetadata.fromLegacyJson`
 *   重新写回 DB（缺失时以空白元数据重建索引，保证条目重新可见）；
 * - 到期用 [AppGalleryTrashRules] 判定，物理删除在后台任务（[AppGalleryBgTasks]）里分片执行。
 *
 * **已知边界（诚实说明）**：系统相册（MediaStore）条目**不进回收站**——跨应用移动外部媒体
 * 需要 MediaStore 删除请求（工程既有 `systemDeletePendingIntent` 能力，见补丁 CODE_HOOKS），
 * 因此 [moveToTrash] 对这类条目返回失败并给出原因，不做隐式危险操作。
 */
class AppGalleryTrashStore(
    private val context: Context,
    private val settings: AppGallerySettingsStore = AppGallerySettingsStore.getInstance(context),
) {

    suspend fun entries(): List<AppGalleryTrashEntry> = settings.loadTrash()

    /**
     * 把单条移入回收站。
     */
    suspend fun moveToTrash(
        item: AppGalleryItem,
        config: AppGalleryConfig = settings.loadConfig(),
        now: Long = System.currentTimeMillis(),
    ): AppGalleryTrashMoveResult = withContext(Dispatchers.IO) {
        if (item.source == AppGalleryItemSource.SYSTEM_MEDIA_STORE) {
            return@withContext AppGalleryTrashMoveResult(
                itemId = item.id,
                success = false,
                reason = "系统相册条目不支持回收站，请在系统相册中删除",
            )
        }
        val safeConfig = AppGalleryConfig.clamp(config)
        val photoDir = photoDirFor(item.id)
        if (!photoDir.exists() || !photoDir.isDirectory) {
            return@withContext AppGalleryTrashMoveResult(
                itemId = item.id,
                success = false,
                reason = "找不到原始文件，可能已被删除",
            )
        }
        val trashDir = AppGalleryStorageLayout.trashDir(context)
        val target = File(trashDir, sanitizeId(item.id))
        if (target.exists()) target.deleteRecursively()

        val moved = runCatching { moveDirectory(photoDir, target) }.getOrDefault(false)
        if (!moved) {
            return@withContext AppGalleryTrashMoveResult(
                itemId = item.id,
                success = false,
                reason = "移动文件失败（权限或空间不足）",
            )
        }
        // 摘索引（文件仍在回收站目录里，内容未丢）
        runCatching { GalleryMediaStore.deleteMedia(context, item.id) }
            .onFailure { PLog.w(TAG, "drop index failed: ${item.id}", it) }

        val annotation = AppGalleryAnnotation(
            itemId = item.id,
            isFavorite = item.isFavorite,
            rating = item.rating,
            tags = item.tags,
            updatedAt = now,
        )
        val entry = AppGalleryTrashEntry(
            itemId = item.id,
            displayName = item.displayName,
            originalUri = item.uri,
            trashUri = target.absolutePath,
            category = item.category,
            sizeBytes = directorySize(target),
            deletedAt = now,
            expiresAt = AppGalleryTrashRules.expiresAt(now, safeConfig.trashRetentionDays),
            originalFolderIds = item.folderIds,
            annotation = if (annotation.isEmpty) null else annotation,
            source = item.source,
        )
        val merged = settings.loadTrash().filterNot { it.itemId == entry.itemId } + entry
        settings.saveTrash(merged)
        PLog.i(TAG, "moved to trash: ${item.id} (${entry.sizeBytes} bytes, expires ${entry.expiresAt})")
        AppGalleryTrashMoveResult(itemId = item.id, success = true, entry = entry)
    }

    /**
     * 批量移入回收站（串行；逐条结果用于汇总报告）。
     */
    suspend fun moveToTrash(
        items: List<AppGalleryItem>,
        config: AppGalleryConfig = settings.loadConfig(),
        now: Long = System.currentTimeMillis(),
    ): List<AppGalleryTrashMoveResult> {
        val out = ArrayList<AppGalleryTrashMoveResult>(items.size)
        items.forEach { item -> out.add(moveToTrash(item, config, now)) }
        return out
    }

    /** 恢复单条。 */
    suspend fun restore(itemId: String): AppGalleryTrashRestoreResult = withContext(Dispatchers.IO) {
        val entries = settings.loadTrash()
        val entry = entries.firstOrNull { it.itemId == itemId }
            ?: return@withContext AppGalleryTrashRestoreResult(itemId, false, "回收站中没有该条目")
        val source = File(entry.trashUri)
        if (!source.exists()) {
            settings.saveTrash(entries.filterNot { it.itemId == itemId })
            return@withContext AppGalleryTrashRestoreResult(itemId, false, "回收站文件已丢失，已移除记录")
        }
        val target = photoDirFor(itemId)
        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        val moved = runCatching { moveDirectory(source, target) }.getOrDefault(false)
        if (!moved) {
            return@withContext AppGalleryTrashRestoreResult(itemId, false, "恢复失败（权限或空间不足）")
        }
        // 重建 DB 索引：优先用目录内 metadata.json，缺失时用空白元数据（保证条目重新可见）
        runCatching {
            val metadataFile = File(target, LEGACY_METADATA_FILE)
            val metadata = if (metadataFile.exists()) {
                MediaMetadata.fromLegacyJson(metadataFile.readText())
            } else {
                null
            }
            GalleryMediaStore.saveMetadata(context, itemId, metadata ?: MediaMetadata())
        }.onFailure { PLog.w(TAG, "reindex failed: $itemId", it) }

        // 还原收藏/标签/评分与相册归属
        entry.annotation?.let { annotation ->
            settings.updateAnnotations { current ->
                AppGalleryAnnotationMachine.mergeAnnotations(current, listOf(annotation))
            }
        }
        if (entry.originalFolderIds.isNotEmpty()) {
            val folders = settings.loadFolders()
            var updated = folders
            entry.originalFolderIds.forEach { folderId ->
                updated = AppGalleryFolderModel.addItems(updated, folderId, listOf(itemId)).folders
            }
            settings.saveFolders(updated)
        }
        settings.saveTrash(entries.filterNot { it.itemId == itemId })
        AppGalleryTrashRestoreResult(itemId, true)
    }

    /**
     * 执行到期清理（相册项 7 的自动清空；由后台任务调用，逐条物理删除）。
     *
     * @return 实际删除的条数。
     */
    suspend fun purgeExpired(
        now: Long = System.currentTimeMillis(),
        config: AppGalleryConfig = settings.loadConfig(),
    ): Int = withContext(Dispatchers.IO) {
        val plan = AppGalleryTrashRules.buildCleanupPlan(settings.loadTrash(), now, config)
        if (plan.isNoop) return@withContext 0
        val purgedIds = ArrayList<String>(plan.expired.size)
        plan.expired.forEach { entry ->
            val target = File(entry.trashUri)
            val removed = runCatching { !target.exists() || target.deleteRecursively() }.getOrDefault(false)
            if (removed) purgedIds.add(entry.itemId)
        }
        settings.saveTrash(settings.loadTrash().filterNot { purgedIds.contains(it.itemId) })
        settings.updateAnnotations { AppGalleryAnnotationMachine.drop(it, purgedIds) }
        settings.setLastTrashPurgeAt(now)
        PLog.i(TAG, "trash purge done: ${purgedIds.size}/${plan.expired.size}")
        purgedIds.size
    }

    /** 清空回收站（用户主动）。 */
    suspend fun emptyAll(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val entries = settings.loadTrash()
        var removed = 0
        entries.forEach { entry ->
            val target = File(entry.trashUri)
            if (runCatching { !target.exists() || target.deleteRecursively() }.getOrDefault(false)) {
                removed++
            }
        }
        settings.saveTrash(emptyList())
        settings.updateAnnotations { AppGalleryAnnotationMachine.drop(it, entries.map { e -> e.itemId }) }
        settings.setLastTrashPurgeAt(now)
        removed
    }

    /** 概览（UI 展示剩余条数/可释放空间/即将到期）。 */
    suspend fun summary(now: Long = System.currentTimeMillis()): AppGalleryTrashSummary =
        AppGalleryTrashRules.summary(settings.loadTrash(), now)

    // ── 文件工具 ────────────────────────────────────────────────

    /** 应用私有产物目录（与 `GalleryManager.getPhotoDir` 一致）。 */
    fun photoDirFor(photoId: String): File =
        File(AppGalleryStorageLayout.photoLibraryDir(context), sanitizeId(photoId))

    /** 目录内所有文件大小之和。 */
    fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 目录移动：优先同卷 `renameTo`（毫秒级）；失败退化为递归拷贝 + 删除源目录。
     */
    fun moveDirectory(source: File, target: File): Boolean {
        if (!source.exists()) return false
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return runCatching {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
            true
        }.getOrDefault(false)
    }

    /** 目录名安全化（只保留字母数字与 `_ - .`，防路径穿越）。 */
    fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_.-]"), "_").takeIf { it.isNotEmpty() } ?: "unknown"

    companion object {
        private const val TAG = "AppGalleryTrash"
        private const val LEGACY_METADATA_FILE = "metadata.json"
    }
}
