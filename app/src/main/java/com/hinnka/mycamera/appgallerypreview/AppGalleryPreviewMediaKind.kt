package com.hinnka.mycamera.appgallerypreview

/**
 * 预览项媒体类型（决定用哪条预览管线）。
 *
 * - [IMAGE]：静图，走大图缩放
 * - [GIF]：动图，走平台 AnimatedImageDrawable 播放
 * - [RAW]：DNG 等 RAW 容器，走 RAW 预览渲染 + 直方图 + 导出
 * - [VIDEO]：视频，走 media3 ExoPlayer
 * - [UNSUPPORTED]：无法预览（例如损坏文件）
 */
enum class AppGalleryPreviewMediaKind { IMAGE, GIF, RAW, VIDEO, UNSUPPORTED }

/**
 * 媒体类型判定（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * 判定优先级：显式 MIME > 扩展名 > 显式 KIND 提示 > [AppGalleryPreviewMediaKind.UNSUPPORTED]。
 */
object AppGalleryPreviewMediaKindResolver {

    /** 需要走后台解码（大图/RAW）的类型。 */
    fun requiresBackgroundDecode(kind: AppGalleryPreviewMediaKind): Boolean =
        kind == AppGalleryPreviewMediaKind.RAW || kind == AppGalleryPreviewMediaKind.IMAGE

    /** 是否支持参与幻灯片放映（动图与视频不参与，避免放映节奏失控）。 */
    fun supportsSlideshow(kind: AppGalleryPreviewMediaKind): Boolean =
        kind == AppGalleryPreviewMediaKind.IMAGE || kind == AppGalleryPreviewMediaKind.RAW

    /** 是否为动效内容（GIF/视频）。 */
    fun isAnimated(kind: AppGalleryPreviewMediaKind): Boolean =
        kind == AppGalleryPreviewMediaKind.GIF || kind == AppGalleryPreviewMediaKind.VIDEO

    /** 是否为 RAW 容器。 */
    fun isRaw(kind: AppGalleryPreviewMediaKind): Boolean = kind == AppGalleryPreviewMediaKind.RAW

    /**
     * 判定类型。
     *
     * @param mimeType 显式 MIME（可为 null/空）
     * @param uri 资源地址（用于取扩展名）
     */
    fun resolve(mimeType: String?, uri: String?): AppGalleryPreviewMediaKind {
        val mime = mimeType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        when (mime) {
            "image/gif" -> return AppGalleryPreviewMediaKind.GIF
            "image/x-adobe-dng", "image/dng", "image/x-dcraw", "image/x-raw" ->
                return AppGalleryPreviewMediaKind.RAW
        }
        if (mime != null && mime.startsWith("video/")) return AppGalleryPreviewMediaKind.VIDEO
        if (mime != null && mime.startsWith("image/")) {
            // 具体子类型未知的 image/* 交给扩展名再判一次，避免把 DNG 当静图。
            val byExtension = resolveByExtension(uri)
            return if (byExtension == AppGalleryPreviewMediaKind.RAW ||
                byExtension == AppGalleryPreviewMediaKind.GIF
            ) {
                byExtension
            } else {
                AppGalleryPreviewMediaKind.IMAGE
            }
        }
        val byExtension = resolveByExtension(uri)
        if (byExtension != AppGalleryPreviewMediaKind.UNSUPPORTED) return byExtension
        if (mime == null && uri.isNullOrBlank()) return AppGalleryPreviewMediaKind.UNSUPPORTED
        return AppGalleryPreviewMediaKind.UNSUPPORTED
    }

    /** 仅按扩展名判定；无法判定返回 [AppGalleryPreviewMediaKind.UNSUPPORTED]。 */
    fun resolveByExtension(uri: String?): AppGalleryPreviewMediaKind {
        val path = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return AppGalleryPreviewMediaKind.UNSUPPORTED
        // 去掉 query/fragment 再取扩展名。
        val cleaned = path.substringBefore('?').substringBefore('#')
        val extension = cleaned.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "dng", "cr2", "cr3", "nef", "arw", "raf", "rw2", "orf", "pef", "srw", "x3f", "raw" ->
                AppGalleryPreviewMediaKind.RAW
            "gif" -> AppGalleryPreviewMediaKind.GIF
            "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "avif", "jxl" ->
                AppGalleryPreviewMediaKind.IMAGE
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "avi", "ts" ->
                AppGalleryPreviewMediaKind.VIDEO
            else -> AppGalleryPreviewMediaKind.UNSUPPORTED
        }
    }

    /** 是否可用系统/Coil 直接解码（无需 RAW 管线）。 */
    fun decodableByPlatform(kind: AppGalleryPreviewMediaKind): Boolean =
        kind == AppGalleryPreviewMediaKind.IMAGE ||
            kind == AppGalleryPreviewMediaKind.GIF ||
            kind == AppGalleryPreviewMediaKind.VIDEO
}
