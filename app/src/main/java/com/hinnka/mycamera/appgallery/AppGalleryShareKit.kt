package com.hinnka.mycamera.appgallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 导出/分享入口（相册项 9、16、8 的分享部分）。
 *
 * 设计原则：
 * - **优先复用已有的 `content://` 授权**（应用产物的导出 URI、系统相册 URI），此时不需要 FileProvider；
 * - 对 `file://` 路径（回收站内的文件、备份中转目录）走工程既有的 `${applicationId}.fileprovider`
 *   （`androidx.core.content.FileProvider`，已在 Manifest 注册）；
 * - 地图跳转不引入地图 SDK，只用 `geo:` 隐式 Intent（相册项 16 的「能力不足说明」见 changelog）。
 */
object AppGalleryShareKit {

    const val AUTHORITY_SUFFIX = ".fileprovider"

    /** 单条分享，或 0 条时返回 null。 */
    fun buildShareIntent(
        context: Context,
        items: List<AppGalleryItem>,
        subject: String? = null,
    ): Intent? {
        val uris = items.mapNotNull { resolveShareUri(context, it) }
        if (uris.isEmpty()) return null
        val mimeTypes = mimeTypesOf(items)
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeTypes.firstOrNull() ?: MIME_FALLBACK
                putExtra(Intent.EXTRA_STREAM, uris.first())
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "photos", uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (mimeTypes.size == 1) mimeTypes.first() else MIME_FALLBACK
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "photos", uris.first()).apply {
                    uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
                }
            }
        }
    }

    /** 单条查看（系统图库/播放器）。 */
    fun buildViewIntent(context: Context, item: AppGalleryItem): Intent? {
        val uri = resolveShareUri(context, item) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeTypeOrFallback())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 解析可分享 URI：
     * 1. `content://` 直接用（复用应用导出 URI / MediaStore 授权）；
     * 2. `file://` 或裸路径交给 FileProvider（需要 `file_paths.xml` 覆盖该目录，见补丁）；
     * 3. 全部失败返回 null（不抛异常）。
     */
    fun resolveShareUri(context: Context, item: AppGalleryItem): Uri? {
        val parsed = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return null
        if (parsed.scheme.equals("content", true)) return parsed
        val path = parsed.path ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        }.onFailure { PLog.w(TAG, "FileProvider failed for ${item.id}", it) }.getOrNull()
    }

    /** 分享用的 MIME 集合（去重、保持顺序，空则回落 `*/*`）。 */
    fun mimeTypesOf(items: List<AppGalleryItem>): List<String> =
        items.map { it.mimeTypeOrFallback() }.distinct()

    /** 批量分享的默认主题（按首条时间命名，保证可读）。 */
    fun shareSubject(items: List<AppGalleryItem>): String =
        when {
            items.isEmpty() -> "PhotonCamera 照片"
            items.size == 1 -> items.first().displayName
            else -> "PhotonCamera 照片（${items.size} 张）"
        }

    /** 删除/清空类操作的二次确认文案。 */
    fun confirmMessage(action: AppGalleryBatchAction, count: Int, trashDays: Int): String = when (action) {
        AppGalleryBatchAction.DELETE -> "将 $count 项移入回收站，$trashDays 天内可恢复"
        AppGalleryBatchAction.PURGE -> "将彻底删除 $count 项，无法恢复，是否继续？"
        AppGalleryBatchAction.RESTORE -> "将恢复 $count 项到原位置"
        AppGalleryBatchAction.MOVE_TO_FOLDER -> "将 $count 项移动到所选相册"
        AppGalleryBatchAction.REMOVE_FROM_FOLDER -> "将 $count 项移出相册（不删除文件）"
        else -> "将对 $count 项执行「${action.labelZh}」"
    }

    /**
     * 外部地图跳转（相册项 16）：系统会挑选已安装的地图应用；没有任何地图应用时返回 null。
     * 不引入地图 SDK、不申请新权限（仅用既有坐标数据）。
     */
    fun buildGeoIntent(latitude: Double, longitude: Double, label: String? = null): Intent? {
        if (!AppGalleryPlaceClustering.isValidCoordinate(latitude, longitude)) return null
        val uri = Uri.parse(AppGalleryPlaceClustering.externalMapUri(latitude, longitude, label))
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** 可直接放入 `startActivity` 的「查看所在位置」Intent（带兜底 chooser 包装）。 */
    fun buildGeoChooser(
        latitude: Double,
        longitude: Double,
        label: String? = null,
        chooserTitle: String = "选择地图应用",
    ): Intent? = buildGeoIntent(latitude, longitude, label)?.let {
        Intent.createChooser(it, chooserTitle)
    }

    private const val TAG = "AppGalleryShare"
    private const val MIME_FALLBACK = "*/*"

    private fun AppGalleryItem.mimeTypeOrFallback(): String = when {
        isVideo -> "video/*"
        category == AppGalleryCategory.GIF -> "image/gif"
        hasRaw -> "image/*"
        else -> "image/*"
    }
}

/** 兜底：把字符串转成安全的文件扩展名（分享/导出命名共用）。 */
internal fun String.safeExtension(fallback: String = "bin"): String {
    val cleaned = trim().removePrefix(".").lowercase().filter { it.isLetterOrDigit() }
    return cleaned.ifEmpty { fallback }.take(8)
}
