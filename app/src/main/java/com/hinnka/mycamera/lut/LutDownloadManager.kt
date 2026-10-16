package com.hinnka.mycamera.lut

import android.content.Context
import android.net.Uri
import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.data.CustomImportManager
import com.hinnka.mycamera.raw.ColorSpace
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.aiplatform.AiParamStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「可拆卸 LUT 下载包」管理器（C 模块交付：内置 8 个不动 + 其余按需下载/可删）。
 *
 * 设计：
 *  - 内置精选 LUT 已随 assets/luts/config.json 打包，开箱即用；
 *  - 本管理器只负责**非内置**开源 LUT 包的按需下载，下载后落到应用私有目录，
 *    经 [CustomImportManager.importLut] 转为内部 .plut v4 并注册为 custom_* 条目，
 *    因此自动进入 LutManager / LutDisplayOrdering 的查找路径，B1 的 LUT 面板按注册表
 *    数据驱动即可显示，无需硬编码。
 *  - 下载源目录默认读 assets/luts/downloadable_sources.json；若其中 remoteCatalogUrl
 *    非空，则运行时拉取远端目录覆盖，便于不发版更新下载源（服务器/远程可配置）。
 *  - 轻量：未下载前不占 APK 体积；用户可随时删除已下载 LUT。
 *
 * 注意：本环境无 Android SDK，未编译。所有网络/文件操作在 Dispatchers.IO 执行。
 */
class LutDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "LutDownloadManager"
        private const val CATALOG_ASSET = "luts/downloadable_sources.json"
        private const val DOWNLOAD_SUBDIR = "downloaded_luts"
        private const val CATEGORY_DOWNLOADED = "Downloaded"
        /** 用户可在运行时覆盖的远端目录地址（设置页 / 后续统一设置页写入）。 */
        const val KEY_REMOTE_CATALOG = "lut.remoteCatalogUrl"    }

    private val customImportManager = CustomImportManager(context)

    /** 一个可下载的 LUT 包（来自目录 json）。 */
    data class DownloadablePack(
        val id: String,
        val nameEn: String,
        val nameZh: String,
        val sourceUrl: String,
        val license: String,
        val starNote: String,
        val contents: String,
        /** 实际可下载的 .cube/.zip 直链；为空时仅展示源仓库链接，由用户自行下载后用「导入」。 */
        val fileUrl: String,
        val format: String,
    )

    /** 读取内置下载目录（可选：远端目录覆盖）。 */
    suspend fun loadCatalog(): List<DownloadablePack> = withContext(Dispatchers.IO) {
        val remoteUrl = runCatching { readRemoteCatalogUrl() }.getOrNull().orEmpty()
        val rawJson = if (remoteUrl.isNotBlank()) {
            runCatching { httpGetText(remoteUrl) }.getOrNull()
        } else null
            ?: runCatching {
                context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
            ?: return@withContext emptyList()

        runCatching {
            val obj = JSONObject(rawJson)
            val arr = obj.getJSONArray("packs")
            buildList {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val name = p.optJSONObject("name")
                    add(
                        DownloadablePack(
                            id = p.getString("id"),
                            nameEn = name?.optString("en") ?: p.getString("id"),
                            nameZh = name?.optString("zh").orEmpty(),
                            sourceUrl = p.optString("sourceUrl"),
                            license = p.optString("license"),
                            starNote = p.optString("star"),
                            contents = p.optString("contents"),
                            fileUrl = p.optString("fileUrl"),
                            format = p.optString("format"),
                        )
                    )
                }
            }
        }.onFailure { PLog.w(TAG, "Failed to parse downloadable catalog", it) }.getOrDefault(emptyList())
    }

    /**
     * 下载并安装一个 LUT 包。
     * @return 新注册的 LUT id；失败返回 null。
     */
    suspend fun downloadAndInstall(pack: DownloadablePack): String? = withContext(Dispatchers.IO) {
        if (pack.fileUrl.isBlank()) {
            PLog.w(TAG, "Pack ${pack.id} has no direct fileUrl; user should download from ${pack.sourceUrl} and use Import")
            return@withContext null
        }
        val dir = File(context.filesDir, DOWNLOAD_SUBDIR).apply { mkdirs() }
        val outFile = File(dir, guessFileName(pack))
        val partFile = File(dir, outFile.name + ".part")
        val ok = runCatching { httpDownload(URL(pack.fileUrl), partFile) }.getOrDefault(false)
        if (ok && partFile.length() > 0L) partFile.renameTo(outFile) else partFile.delete()
        if (!ok || outFile.length() <= 0L) {
            outFile.delete()
            PLog.w(TAG, "Download failed for ${pack.id}")
            return@withContext null
        }
        // 复用现有导入管线：转内部 .plut v4 并注册（自动进 LutManager/LutDisplayOrdering）
        runCatching {
            customImportManager.importLut(
                uri = Uri.fromFile(outFile),
                displayName = pack.nameEn,
                category = CATEGORY_DOWNLOADED,
                colorSpace = ColorSpace.SRGB,
                curve = TransferCurve.SRGB,
            )
        }.onFailure { PLog.w(TAG, "Install failed for ${pack.id}", it) }.getOrNull()
    }

    /** 列出已下载（custom_*）的 LUT。 */
    fun listDownloaded(): List<LutInfo> =
        customImportManager.getCustomLuts().filter { it.isBuiltIn.not() }

    /** 删除一个已下载 LUT（用户可删，app 保持轻量）。 */
    suspend fun deleteDownloaded(lutId: String) = withContext(Dispatchers.IO) {
        customImportManager.deleteCustomLut(lutId)
    }

    // ---- 内部工具 ----

    /**
     * 远端目录地址（I14 修复）：优先级 = 用户运行时配置（DataStore）> BuildConfig 注入 > assets 内置。
     * 任何一级为空就往下走，全空则只用内置 assets 目录。
     */
    private suspend fun readRemoteCatalogUrl(): String? {
        val fromUser = runCatching {
            AiParamStore.readString(context, KEY_REMOTE_CATALOG, "")
        }.getOrNull().orEmpty()
        if (fromUser.isNotBlank()) return fromUser
        val fromBuild = runCatching { BuildConfig.LUT_CATALOG_URL }.getOrNull().orEmpty()
        if (fromBuild.isNotBlank()) return fromBuild
        return runCatching {
            val obj = JSONObject(context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() })
            obj.optJSONObject("_source_config")?.optString("remoteCatalogUrl").orEmpty().ifBlank { null }
        }.getOrNull()
    }
    private fun guessFileName(pack: DownloadablePack): String {
        val ext = when (pack.format) {
            "cube_bundle", "haldclut" -> "cube"
            "xmp_bundle" -> "zip"
            else -> "cube"
        }
        return "dl_${pack.id}.${ext}"
    }

    private fun httpDownload(url: URL, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                PLog.w(TAG, "LUT download HTTP ${conn.responseCode} for $url")
                return false
            }
            val contentType = conn.contentType.orEmpty()
            if (contentType.isNotBlank() &&
                !contentType.startsWith("application/") &&
                !contentType.startsWith("text/") &&
                !contentType.startsWith("binary/")
            ) {
                PLog.w(TAG, "Unexpected content-type for LUT: $contentType")
                return false
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            PLog.w(TAG, "httpDownload error", e); false
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpGetText(url: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                PLog.w(TAG, "Catalog fetch HTTP ${conn.responseCode}")
                return ""
            }
            conn.inputStream.bufferedReader().use { it.readText() }        } finally {
            conn?.disconnect()
        }
    }
}
