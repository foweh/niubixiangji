package com.hinnka.mycamera.appgallery

import android.content.Context
import com.hinnka.mycamera.gallery.GalleryManager
import com.hinnka.mycamera.gallery.MediaData
import com.hinnka.mycamera.gallery.MediaType

/**
 * `MediaData` / `MediaMetadata` → [AppGalleryItem] / [AppGalleryClassifyInput] 映射器（Android 侧）。
 *
 * 纯逻辑部分（分类、排序、过滤、统计）在各自文件里，本文件只做「读工程既有模型 → 纯模型」的适配，
 * 因此不含任何业务判定。
 */
object AppGalleryItemMapper {

    /** 组装分类输入（工程既有字段 → 纯输入）。 */
    fun classifyInput(
        media: MediaData,
        hasDng: Boolean = false,
        dirTag: String? = null,
        persistedProcessingType: String? = null,
    ): AppGalleryClassifyInput {
        val metadata = media.metadata
        return AppGalleryClassifyInput(
            fileName = media.displayName,
            relativeDirTag = dirTag ?: dirTagOf(media),
            isVideo = media.isVideo,
            mimeType = media.mimeType,
            hasDng = hasDng,
            hasBurstFrames = media.isBurstPhoto,
            isMotionPhoto = media.isMotionPhoto,
            lutId = metadata?.lutId,
            filmStockId = metadata?.spectralFilmStock,
            dynamicRangeProfile = metadata?.dynamicRangeProfile,
            hasEmbeddedGainmap = metadata?.hasEmbeddedGainmap ?: false,
            manualHdrEffectEnabled = metadata?.manualHdrEffectEnabled ?: false,
            multipleExposureFrameCount = metadata?.multipleExposureFrameCount,
            hasAiDenoisedBase = metadata?.hasAiDenoisedBase ?: false,
            persistedProcessingType = persistedProcessingType,
            captureMode = metadata?.captureMode,
            customProperties = metadata?.customProperties ?: emptyMap(),
        )
    }

    /** 分类（直接返回类型，便于列表徽标）。 */
    fun categoryOf(
        media: MediaData,
        hasDng: Boolean = false,
        dirTag: String? = null,
        persistedProcessingType: String? = null,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
    ): AppGalleryCategory = AppGalleryClassifier.classify(
        classifyInput(media, hasDng, dirTag, persistedProcessingType),
        rules,
    )

    /** 组装列表项。 */
    fun toItem(
        media: MediaData,
        source: AppGalleryItemSource,
        hasDng: Boolean = false,
        dirTag: String? = null,
        persistedProcessingType: String? = null,
        annotation: AppGalleryAnnotation? = null,
        folderIds: List<String> = emptyList(),
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
        locationName: String? = null,
    ): AppGalleryItem {
        val metadata = media.metadata
        val category = categoryOf(media, hasDng, dirTag, persistedProcessingType, rules)
        return AppGalleryItem(
            id = media.id,
            displayName = media.displayName,
            uri = media.uri.toString(),
            thumbnailUri = media.thumbnailUri.toString().takeIf { it.isNotEmpty() },
            kind = if (media.mediaType == MediaType.VIDEO) {
                AppGalleryMediaKind.VIDEO
            } else {
                AppGalleryMediaKind.IMAGE
            },
            category = category,
            dateAdded = media.dateAdded,
            dateTaken = metadata?.dateTaken?.takeIf { it > 0L },
            sizeBytes = media.size,
            width = media.width.takeIf { it > 0 } ?: (metadata?.width ?: 0),
            height = media.height.takeIf { it > 0 } ?: (metadata?.height ?: 0),
            durationMs = media.durationMs ?: metadata?.durationMs,
            frameCount = null, // 帧数由 AppGalleryBgTasks.enrichVideo() 按需补充（MediaMetadataRetriever）
            latitude = metadata?.latitude,
            longitude = metadata?.longitude,
            altitude = metadata?.altitude,
            locationName = locationName ?: metadata?.location,
            isFavorite = annotation?.isFavorite ?: false,
            rating = annotation?.rating ?: 0,
            tags = annotation?.tags ?: emptyList(),
            folderIds = folderIds,
            source = source,
            isBurstPhoto = media.isBurstPhoto,
            isMotionPhoto = media.isMotionPhoto,
            hasRaw = hasDng,
            shotParams = shotParamsOf(media),
        )
    }

    /**
     * 拍摄参数（相册项 4）：复用工程既有 [MediaMetadata]（其内部已用 `ExifInterface`
     * 解析过 EXIF），加上列表项自带的尺寸/体积，交给纯 Kotlin
     * [AppGalleryShotParams.of] 做净化与展示整形。
     */
    fun shotParamsOf(media: MediaData): AppGalleryShotParams {
        val metadata = media.metadata
        val dimensions = if (media.width > 0 && media.height > 0) {
            "${media.width}x${media.height}"
        } else {
            null
        }
        return AppGalleryShotParams.of(
            iso = metadata?.iso,
            shutterSpeed = metadata?.shutterSpeed,
            aperture = metadata?.aperture,
            focalLength = metadata?.focalLength,
            focalLength35mm = metadata?.focalLength35mm,
            exposureBias = metadata?.exposureBias,
            capturedAt = metadata?.dateTaken ?: media.dateAdded,
            latitude = metadata?.latitude,
            longitude = metadata?.longitude,
            altitude = metadata?.altitude,
            locationName = metadata?.location,
            cameraId = metadata?.cameraId,
            colorSpace = metadata?.colorSpace?.name,
            software = metadata?.software,
            dimensions = dimensions,
            sizeBytes = media.size,
        )
    }

    /**
     * 就地从 DB 查询是否有 DNG（复用 `GalleryManager.getDngFile`，只做一次 `exists()` 判断）。
     * 频繁调用时请放到后台任务里（[AppGalleryBgTasks] 已如此处理）。
     */
    fun hasDng(context: Context, media: MediaData): Boolean =
        runCatching { GalleryManager.getDngFile(context, media.id).exists() }.getOrDefault(false)

    /**
     * 从 URI/路径推断目录规则标记（取文件所在目录名）。
     * 例：`.../superres/img_001.jpg` → `superres`；`.../photos/<uuid>/original.jpg` → `<uuid>`（不会命中规则）。
     */
    fun dirTagOf(media: MediaData): String? {
        val path = media.sourceUri?.path ?: media.uri.path
        return dirTagFromPath(path)
    }

    fun dirTagFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.trimEnd('/')
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        val dirName = parent.substringAfterLast('/')
        if (dirName.isEmpty()) return null
        return dirName
    }

    /** 位置名首选项：元数据里的 location 优先（导入照片已带），否则由地点聚类填充。 */
    fun preferredLocationName(media: MediaData): String? =
        media.metadata?.location?.takeIf { it.isNotBlank() }
}
