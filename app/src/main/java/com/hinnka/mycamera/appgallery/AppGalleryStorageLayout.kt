package com.hinnka.mycamera.appgallery

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 模块存储布局（全部位于 App 私有目录，无需新权限）。
 *
 * | 目录 | 路径 | 用途 |
 * |---|---|---|
 * | 回收站 | `getExternalFilesDir(Pictures)/appgallery_trash/` | 删除时把整个 `photos/<photoId>/` 目录**同卷改名**移入（毫秒级、可完全恢复） |
 * | 备份中转 | `getExternalFilesDir(Pictures)/appgallery_backup/` | 导出 zip / 复制到用户目录前的暂存与落盘 |
 * | 缩略图补充缓存 | `getExternalFilesDir(Pictures)/appgallery_thumbs/` | 既有无缩略图条目的补齐缓存（可整目录删除重建） |
 * | 自定义城市表 | `filesDir/appgallery/city_table.txt` | 离线反查表（`名称,minLat,maxLat,minLon,maxLon` 每行一条，`#` 注释） |
 *
 * 回收站与照片目录同卷（都在 `getExternalFilesDir(Pictures)/` 下），因此移除/恢复都是
 * `File.renameTo`，不产生整目录拷贝开销。
 *
 * 分享所需的 FileProvider 路径需在 `res/xml/file_paths.xml` 补三条（见补丁）。
 */
object AppGalleryStorageLayout {

    const val TRASH_DIR_NAME = "appgallery_trash"
    const val BACKUP_DIR_NAME = "appgallery_backup"
    const val THUMB_DIR_NAME = "appgallery_thumbs"
    const val CITY_TABLE_DIR_NAME = "appgallery"
    const val CITY_TABLE_FILE_NAME = "city_table.txt"

    private fun picturesRoot(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)


    fun trashDir(context: Context, create: Boolean = true): File =
        resolve(picturesRoot(context), context.filesDir, TRASH_DIR_NAME, create)

    fun backupDir(context: Context, create: Boolean = true): File =
        resolve(picturesRoot(context), context.filesDir, BACKUP_DIR_NAME, create)

    fun thumbnailCacheDir(context: Context, create: Boolean = true): File =
        resolve(picturesRoot(context), context.filesDir, THUMB_DIR_NAME, create)

    fun cityTableFile(context: Context, createParent: Boolean = true): File {
        val dir = File(context.filesDir, CITY_TABLE_DIR_NAME)
        if (createParent && !dir.exists()) dir.mkdirs()
        return File(dir, CITY_TABLE_FILE_NAME)
    }

    /** FileProvider 授权用的路径名（补丁在 file_paths.xml 中添加同名字段）。 */
    const val FILE_PROVIDER_PATH_TRASH = "appgallery_trash"
    const val FILE_PROVIDER_PATH_BACKUP = "appgallery_backup"
    const val FILE_PROVIDER_PATH_THUMBS = "appgallery_thumbs"

    /** 相册产物目录（与 `GalleryManager` 的 `getPhotosBaseDir` 保持一致）。 */
    fun photoLibraryDir(context: Context): File =
        File(picturesRoot(context) ?: context.filesDir, "photos")

    /**
     * 解析（并在需要时创建）子目录。
     *
     * 兜底：`getExternalFilesDir` 在极端情况下返回 null（外部存储不可用），此时回落到
     * **内部私有目录** `filesDir/<name>`，保证「可写、可恢复」；该路径不在 `file_paths.xml`
     * 覆盖范围内，因此分享会安全失败（[AppGalleryShareKit.resolveShareUri] 返回 null）而非崩溃。
     */
    private fun resolve(root: File?, fallbackRoot: File, name: String, create: Boolean): File {
        val base = root ?: fallbackRoot
        val dir = File(base, name)
        if (create && !dir.exists()) dir.mkdirs()
        return dir
    }
}
