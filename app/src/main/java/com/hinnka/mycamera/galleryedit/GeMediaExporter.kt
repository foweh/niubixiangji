package com.hinnka.mycamera.galleryedit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "GeMediaExporter"

/**
 * 编辑结果落盘（写入系统相册）。
 *
 * 复用工程既有约定：`MediaStore` + `RELATIVE_PATH`（图片进 `Pictures/PhotonCamera`，
 * 视频进 `Movies/PhotonCamera`，与 `lut/VideoExporter.kt` 的 `saveVideoToMediaStore` 一致），
 * 因此不引入新依赖、也不改动既有文件。
 *
 * 参数开放：目录名与文件名前缀均可配置（默认值见 [DEFAULT_IMAGE_DIR_NAME] 等）。
 */
object GeMediaExporter {

    /** 相对路径目录名（Pictures / Movies 下的子目录）。 */
    const val DEFAULT_IMAGE_DIR_NAME = "PhotonCamera"
    const val DEFAULT_VIDEO_DIR_NAME = "PhotonCamera"

    /** 文件名前缀。 */
    const val DEFAULT_IMAGE_PREFIX = "PhotonCamera"
    const val DEFAULT_VIDEO_PREFIX = "PhotonCamera"

    /** JPEG 压缩质量范围（越界 clamp）。 */
    const val MIN_QUALITY = 1
    const val MAX_QUALITY = 100
    const val DEFAULT_QUALITY = 95

    /**
     * 保存位图到相册。
     *
     * @param format 压缩格式（默认 JPEG；PNG 会保留 alpha 但体积更大）。
     * @param quality 压缩质量，clamp 到 [MIN_QUALITY, MAX_QUALITY]。
     * @return 写入成功的 content uri；失败返回 null（不抛异常）。
     */
    suspend fun saveImageToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        dirName: String = DEFAULT_IMAGE_DIR_NAME,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = DEFAULT_QUALITY,
    ): Uri? = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            PLog.w(TAG, "saveImageToGallery skipped: invalid bitmap")
            return@withContext null
        }
        val safeQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        val mime = when (format) {
            Bitmap.CompressFormat.PNG -> "image/png"
            Bitmap.CompressFormat.WEBP_LOSSLESS, Bitmap.CompressFormat.WEBP_LOSSY -> "image/webp"
            else -> "image/jpeg"
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + dirName.ifBlank { DEFAULT_IMAGE_DIR_NAME },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                PLog.e(TAG, "MediaStore insert returned null for $displayName")
                return@withContext null
            }
            val written = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(format, safeQuality, output)
                } ?: false
            }.getOrElse { error ->
                PLog.e(TAG, "compress failed: ${error.message}", error)
                false
            }
            if (!written) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            runCatching { context.contentResolver.update(uri, values, null, null) }
            uri
        } catch (e: RuntimeException) {
            PLog.e(TAG, "saveImageToGallery failed", e)
            null
        } catch (e: java.io.IOException) {
            PLog.e(TAG, "saveImageToGallery io failed", e)
            null
        }
    }

    /**
     * 保存已有视频文件到相册（编辑导出结果）。
     *
     * @return content uri；失败返回 null。
     */
    suspend fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        displayName: String,
        dirName: String = DEFAULT_VIDEO_DIR_NAME,
        mimeType: String = "video/mp4",
    ): Uri? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            PLog.w(TAG, "saveVideoToGallery skipped: missing file ${sourceFile.absolutePath}")
            return@withContext null
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + dirName.ifBlank { DEFAULT_VIDEO_DIR_NAME },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                PLog.e(TAG, "MediaStore video insert returned null for $displayName")
                return@withContext null
            }
            val copied = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrElse { error ->
                PLog.e(TAG, "copy video failed: ${error.message}", error)
                false
            }
            if (!copied) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            runCatching { context.contentResolver.update(uri, values, null, null) }
            uri
        } catch (e: RuntimeException) {
            PLog.e(TAG, "saveVideoToGallery failed", e)
            null
        } catch (e: java.io.IOException) {
            PLog.e(TAG, "saveVideoToGallery io failed", e)
            null
        }
    }
}
