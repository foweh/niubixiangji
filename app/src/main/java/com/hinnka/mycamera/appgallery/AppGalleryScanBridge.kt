package com.hinnka.mycamera.appgallery

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 与相机输出联动（相册项 21）+ 视频/GIF 列表元数据补充（相册项 13）。
 *
 * 复用点：
 * - 监听 `GalleryManager.photoLibraryChangedEvents`（工程既有 `SharedFlow<Unit>`）→ 自动重扫；
 * - 目录扫描沿用 `GalleryManager` 的保存路径：`getExternalFilesDir(Pictures)/photos/<photoId>/`，
 *   每个 `photoId` 目录下固定文件名（`original.jpg` / `original.heic` / `video.mp4` / `original.dng`
 *   / `thumbnail.jpg` / `burst/`）；
 * - 新输出的归类优先用 DB 补丁列 `processingType`，未应用补丁时走「目录名 + 文件名」规则
 *   （[AppGalleryClassifier]，与 `superres` / `multiframesr` 等既有输出目录天然匹配）。
 *
 * 说明：本模块**只读扫描**，不写相机保存链路；写入侧 hook 见补丁 CODE_HOOKS（保存时补 `processingType`）。
 */
object AppGalleryScanBridge {

    private const val TAG = "AppGalleryScan"

    /** 相机产物目录下的固定文件名（与 `GalleryMediaStore` 常量一致，避免魔法字符串）。 */
    private val KNOWN_OUTPUT_FILES = listOf(
        "original.jpg", "original.heic", "original.dng", "video.mp4", "thumbnail.jpg",
    )

    /**
     * 新输出变化流：每次相册库通知后重扫一次（并发合并，避免连续拍照时抖动）。
     *
     * @param includeSystemAlbum 是否同时扫系统相册
     */
    fun observeAppGallery(
        context: Context,
        repository: AppGalleryRepository,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        annotationsProvider: suspend () -> Map<String, AppGalleryAnnotation> = { emptyMap() },
        includeSystemAlbum: Boolean = true,
    ): Flow<AppGallerySnapshot> =
        GalleryManager.photoLibraryChangedEvents
            .mapLatest {
                runCatching {
                    repository.loadSnapshot(
                        includeSystemAlbum = includeSystemAlbum,
                        rules = rules,
                        annotations = annotationsProvider(),
                    )
                }.onFailure { error -> PLog.w(TAG, "rescan failed", error) }
                    .getOrNull()
            }
            .conflate()

    /**
     * 一次性扫描「新输出」：返回 [sinceMillis] 之后新增/修改过的条目，
     * 用于「刚拍完/刚处理完」时的局部刷新（避免整库重扫）。
     */
    suspend fun collectNewOutputs(
        context: Context,
        sinceMillis: Long,
        includeSystemAlbum: Boolean = false,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        annotations: Map<String, AppGalleryAnnotation> = emptyMap(),
    ): List<AppGalleryItem> = withContext(Dispatchers.IO) {
        val repository = AppGalleryRepository(context)
        val snapshot = repository.loadSnapshot(
            includeSystemAlbum = includeSystemAlbum,
            rules = rules,
            annotations = annotations,
        )
        snapshot.items.filter { it.dateAdded > sinceMillis || it.sortDate > sinceMillis }
    }

    /**
     * 扫描目录中的「未入库产物」：直接列出 `photos/` 下每个 `photoId` 目录的候选文件，
     * 与已入库 id 集合比对后返回新增目录（供补丁里的「补扫入库」hook 使用）。
     */
    fun scanOrphanOutputDirectories(
        context: Context,
        indexedIds: Set<String>,
        modifiedAfter: Long = 0L,
    ): List<File> {
        val root = AppGalleryStorageLayout.photoLibraryDir(context)
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.filter { it.name !in indexedIds }
            ?.filter { dir -> modifiedAfter <= 0L || dir.lastModified() > modifiedAfter }
            ?.filter { dir -> dir.listFiles()?.any { it.name in KNOWN_OUTPUT_FILES } == true }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /**
     * 判定某个输出目录的产物类型（相册项 2/21）：直接复用分类器的目录规则，
     * 例如 `superres/` → 超分、`multiframesr/` → 多帧超分、`stack/` → 堆栈。
     */
    fun classifyOutputDirectory(
        directory: File,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
    ): AppGalleryCategory =
        AppGalleryDirectoryRules.categoryForDir(directory.name, rules.directoryRules)
            ?: AppGalleryCategory.NORMAL

    /**
     * 视频/GIF 列表元数据补充（相册项 13）：用 `MediaMetadataRetriever` 取时长/帧数/旋转角。
     * 仅在需要展示时按需调用（列表默认用已有 `durationMs`，避免逐条解析拖慢滚动）。
     */
    suspend fun enrichVideoMetadata(
        context: Context,
        item: AppGalleryItem,
    ): AppGalleryItem = withContext(Dispatchers.IO) {
        if (!item.isVideo && item.category != AppGalleryCategory.GIF) return@withContext item
        val retriever = MediaMetadataRetriever()
        try {
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return@withContext item
            if (uri.scheme.equals("content", true)) {
                retriever.setDataSource(context, uri)
            } else {
                val path = uri.path ?: return@withContext item
                if (!File(path).exists()) return@withContext item
                retriever.setDataSource(path)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: item.durationMs
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: item.width
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: item.height
            item.copy(
                durationMs = duration,
                frameCount = frames,
                width = width,
                height = height,
            )
        } catch (error: Throwable) {
            PLog.w(TAG, "enrich video failed: ${item.id}", error)
            item
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 视频角标文案（列表右下角）：`时长 · 帧数`。 */
    fun videoBadge(item: AppGalleryItem, includeFrames: Boolean = true): String {
        val duration = AppGalleryItem.formatDuration(item.durationMs ?: 0L)
        val frames = item.frameCount?.takeIf { it > 0 }
        return if (includeFrames && frames != null) "$duration · ${frames}帧" else duration
    }
}
