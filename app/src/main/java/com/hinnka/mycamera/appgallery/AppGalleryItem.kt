package com.hinnka.mycamera.appgallery

import java.util.Locale

/** 媒体种类（纯 Kotlin 枚举；不与工程既有 `MediaType` 重名）。 */
enum class AppGalleryMediaKind {
    IMAGE,
    VIDEO,
}

/** 条目来源：应用自身私密目录（DB）还是系统相册（MediaStore）。 */
enum class AppGalleryItemSource {
    /** 来自 `GalleryMediaStore` / `GalleryDatabase`（相机自身产物）。 */
    APP_DATABASE,

    /** 来自系统 `MediaStore`（含相机导出到相册的成片）。 */
    SYSTEM_MEDIA_STORE,
}

/**
 * 相册列表项（纯 Kotlin 数据模型，可 JVM 单测）。
 *
 * 从既有 `MediaData` / `MediaMetadata` 映射而来（映射器在 Android 侧 [AppGalleryItemMapper]）；
 * 本类刻意不引用 `android.net.Uri`，只保存字符串形式的 URI，保证纯逻辑可测。
 */
data class AppGalleryItem(
    val id: String,
    val displayName: String,
    /** 展示用 URI（字符串形式；file:// 或 content://）。 */
    val uri: String,
    /** 缩略图 URI（可能为空 → 列表用占位图）。 */
    val thumbnailUri: String? = null,
    val kind: AppGalleryMediaKind = AppGalleryMediaKind.IMAGE,
    val category: AppGalleryCategory = AppGalleryCategory.NORMAL,
    val dateAdded: Long = 0L,
    val dateTaken: Long? = null,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    /** 视频时长（毫秒）。 */
    val durationMs: Long? = null,
    /** 视频/GIF 帧数（元数据可得时填充；不得为负）。 */
    val frameCount: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    /** 反查得到的城市/地点名（可空）。 */
    val locationName: String? = null,
    val isFavorite: Boolean = false,
    /** 评分（0 = 未评分；上限见 [AppGalleryConfig.maxRating]）。 */
    val rating: Int = 0,
    val tags: List<String> = emptyList(),
    /** 所属相册 id 列表（首项=主相册；收藏夹用 [AppGalleryFolderModel.FAVORITES_ID]）。 */
    val folderIds: List<String> = emptyList(),
    val source: AppGalleryItemSource = AppGalleryItemSource.APP_DATABASE,
    val isBurstPhoto: Boolean = false,
    val isMotionPhoto: Boolean = false,
    /** 是否有可编辑的 RAW 源（UI 徽标用）。 */
    val hasRaw: Boolean = false,
    /**
     * 若本项是系统相册条目且能在应用私有库中找到对应产物，则记录该产物 id
     * （复用工程既有 `MediaData.sourceUri` 关联思路，避免同一张照片展示两次）。
     */
    val linkedAppItemId: String? = null,
    /** 拍摄参数（EXIF 概览，相册项 4）；null = 未映射（纯测试构造时为 null）。 */
    val shotParams: AppGalleryShotParams? = null,
) {
    val isVideo: Boolean get() = kind == AppGalleryMediaKind.VIDEO

    /** 拍摄时间优先，回落到入库时间。 */
    val sortDate: Long get() = dateTaken ?: dateAdded

    /** 人可读体积。 */
    fun formattedSize(): String = formatBytes(sizeBytes)

    /** 分辨率字符串（宽高为 0 时返回 "—"）。 */
    fun formattedResolution(): String =
        if (width > 0 && height > 0) "${width}x${height}" else "—"

    /** 视频时长 / GIF 帧数的展示文本。 */
    fun formattedDuration(): String = formatDuration(durationMs ?: 0L)

    /** 帧数展示文本（无则为 "—"）。 */
    fun formattedFrameCount(): String = frameCount?.takeIf { it > 0 }?.toString() ?: "—"

    companion object {
        /** 字节格式化（B/KB/MB/GB，一位小数；纯函数，方便测试与复用）。 */
        fun formatBytes(bytes: Long): String {
            val safe = if (bytes < 0L) 0L else bytes
            return when {
                safe < 1024L -> "$safe B"
                safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
                safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            }
        }

        /** 时长格式化（mm:ss，超 1 小时为 h:mm:ss；负数按 0 处理）。 */
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (if (durationMs < 0L) 0L else durationMs) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
