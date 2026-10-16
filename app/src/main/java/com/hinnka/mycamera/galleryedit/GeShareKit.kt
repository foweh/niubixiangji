package com.hinnka.mycamera.galleryedit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream

private const val TAG = "GeShareKit"

/**
 * 一键分享（系统分享 intent）。
 *
 * 支持三类分享：文本 / 图片 / 视频；可勾选"套用当前编辑"（[GeShareRequest.applyEdits]），
 * 此时由调用方先用 [GeBitmapEditPipeline] 生成编辑结果再交给本类。
 *
 * 复用关系：直接使用系统 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` + 工程既有
 * `androidx.core.content.FileProvider`（authorities = `${applicationId}.fileprovider`，
 * 缓存在 `cacheDir/shared/`，与既有 `res/xml/file_paths.xml` 的 `shared_images` 路径一致），
 * 不新增依赖。
 */
object GeShareKit {

    /** 分享临时文件目录（必须在 file_paths.xml 的 shared 路径下）。 */
    fun shareDirectory(context: Context): File =
        File(context.cacheDir, SHARE_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 构造分享 intent（不含 chooser）。
     *
     * @return 可直接 `startActivity` 的 intent；参数不合法时返回 null（不抛异常）。
     */
    fun buildIntent(context: Context, request: GeShareRequest): Intent? {
        val (safe, report) = request.clamp()
        if (report.changed) {
            PLog.i(TAG, "share request clamped: ${report.describe()}")
        }
        if (!safe.isShareable()) {
            PLog.w(TAG, "share skipped: nothing to share (target=${safe.target})")
            return null
        }
        return try {
            when (safe.target) {
                GeShareTarget.TEXT -> Intent(Intent.ACTION_SEND).apply {
                    type = GeShareRequest.mimeFor(GeShareTarget.TEXT)
                    safe.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    if (safe.mediaUris.isNotEmpty()) {
                        putExtra(Intent.EXTRA_STREAM, safe.mediaUris.first().toUri())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }

                GeShareTarget.IMAGE, GeShareTarget.VIDEO -> {
                    val uris = safe.mediaUris.map { it.toUri() }
                    if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = GeShareRequest.mimeFor(safe.target)
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                            safe.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = GeShareRequest.mimeFor(safe.target)
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            safe.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            PLog.e(TAG, "buildIntent failed", e)
            null
        }
    }

    /**
     * 构造 chooser（推荐入口）。
     *
     * @param chooserTitle 选择器标题（由 UI 传入 stringResource，禁止硬编码文案）。
     */
    fun buildChooser(context: Context, request: GeShareRequest, chooserTitle: String?): Intent? {
        val intent = buildIntent(context, request) ?: return null
        return try {
            if (chooserTitle.isNullOrBlank()) {
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: RuntimeException) {
            PLog.e(TAG, "buildChooser failed", e)
            null
        }
    }

    /** 直接调起分享；返回是否成功唤起（未安装任何可分享应用时返回 false）。 */
    fun share(context: Context, request: GeShareRequest, chooserTitle: String? = null): Boolean {
        val chooser = buildChooser(context, request, chooserTitle) ?: return false
        // 非 Activity context 必须带 NEW_TASK，否则抛 AndroidRuntimeException
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            PLog.w(TAG, "no activity to handle share: ${e.message}")
            false
        } catch (e: RuntimeException) {
            PLog.e(TAG, "share failed", e)
            false
        }
    }

    /**
     * 把位图写成可分享的 content uri（写入 `cacheDir/shared/`）。
     *
     * @param fileName 文件名（不含目录）；建议带时间戳避免重名。
     * @param format 压缩格式（默认 PNG，无损；JPEG 会丢 alpha）。
     */
    fun prepareImageUri(
        context: Context,
        bitmap: Bitmap,
        fileName: String = defaultImageName(),
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = DEFAULT_QUALITY,
    ): Uri? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            PLog.w(TAG, "prepareImageUri skipped: invalid bitmap")
            return null
        }
        return try {
            val target = File(shareDirectory(context), sanitizeFileName(fileName))
            FileOutputStream(target).use { stream ->
                bitmap.compress(format, quality.coerceIn(1, 100), stream)
            }
            fileUri(context, target)
        } catch (e: RuntimeException) {
            PLog.e(TAG, "prepareImageUri failed", e)
            null
        } catch (e: java.io.IOException) {
            PLog.e(TAG, "prepareImageUri io failed", e)
            null
        }
    }

    /** 把已有文件（如导出的视频/编辑后图片）登记为可分享 uri。 */
    fun prepareFileUri(context: Context, sourceFile: File, fileName: String? = null): Uri? {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            PLog.w(TAG, "prepareFileUri skipped: file missing ${sourceFile.absolutePath}")
            return null
        }
        return try {
            val target = File(shareDirectory(context), sanitizeFileName(fileName ?: sourceFile.name))
            if (target.absolutePath != sourceFile.absolutePath) {
                sourceFile.copyTo(target, overwrite = true)
            }
            fileUri(context, target)
        } catch (e: RuntimeException) {
            PLog.e(TAG, "prepareFileUri failed", e)
            null
        } catch (e: java.io.IOException) {
            PLog.e(TAG, "prepareFileUri io failed", e)
            null
        }
    }

    /** FileProvider uri（authorities 与既有 manifest 一致）。 */
    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 生成默认图片名（带时间戳，避免覆盖）。 */
    fun defaultImageName(prefix: String = "photon_share"): String =
        "${prefix}_${System.currentTimeMillis()}.png"

    /** 生成默认视频名。 */
    fun defaultVideoName(prefix: String = "photon_share"): String =
        "${prefix}_${System.currentTimeMillis()}.mp4"

    /** 文件名净化：去掉路径分隔符与非法字符。 */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        return if (cleaned.isEmpty()) "photon_share_${System.currentTimeMillis()}" else cleaned
    }

    private fun String.toUri(): Uri = if (startsWith("content://") || startsWith("file://")) {
        Uri.parse(this)
    } else {
        Uri.fromFile(File(this))
    }

    /** 与 file_paths.xml 中 `<cache-path name="shared_images" path="shared/"/>` 对应。 */
    const val SHARE_DIR_NAME = "shared"

    /** 默认压缩质量（仅 JPEG/WEBP 生效）。 */
    const val DEFAULT_QUALITY = 95
}
