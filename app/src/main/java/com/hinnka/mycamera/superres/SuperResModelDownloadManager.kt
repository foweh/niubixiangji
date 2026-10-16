package com.hinnka.mycamera.superres

import android.content.Context
import android.net.Uri
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 「可拆卸下载」模型管理器（仿 `lut/LutDownloadManager.kt` 的模式，独立实现、互不依赖）。
 *
 * 设计（PATTERN_SPEC 第 1.6 / 第 1.7 节）：
 *  - **权重不进 APK**：APK 只带元信息目录 `assets/superres/model_sources.json`，
 *    权重按需下载到应用私有目录 `filesDir/superres_models/`，用户可随时删除；
 *  - **远程目录可配**：目录 json 的 `_source_config.remoteCatalogUrl` 非空时，
 *    运行时拉取远端目录覆盖内置目录，便于不发版增补模型源；
 *  - **下载目录可配**：`_source_config.downloadDir`（默认 `superres_models`）；
 *  - 下载完成做**大小 + 可选 sha256**校验，再原子改名落盘，避免半截文件被当成可用模型；
 *  - 全流程由 [SuperResDownloadStateMachine] 驱动，非法事件被吸收，任何异常都不崩溃；
 *  - 无任何硬编码密钥：直链来自目录 json 或运行时远程目录，纯公开 HTTPS 资源。
 *
 * 模型来源与许可见 [SuperResModelCatalog] 的 KDoc（Real-ESRGAN BSD-3-Clause 37101★ 可直采；
 * fast-neural-style 许可不明确只参考思路；LaMa 代码 Apache-2.0 10315★，权重另计需自备）。
 */
class SuperResModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "SuperResDownload"
        private const val CATALOG_ASSET = "superres/model_sources.json"
        private const val DEFAULT_DOWNLOAD_SUBDIR = "superres_models"
        private const val TEMP_SUFFIX = ".part"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    /** 目录配置（远端可覆盖）。 */
    data class CatalogConfig(
        val remoteCatalogUrl: String = "",
        val downloadDir: String = DEFAULT_DOWNLOAD_SUBDIR,
    )

    /** 运行时覆盖远端目录地址（空 = 用内置目录 json 里的 `_source_config.remoteCatalogUrl`）。 */
    @Volatile
    var remoteCatalogUrlOverride: String = ""

    private var cachedCatalog: List<SuperResModelInfo>? = null

    /** 模型根目录（用户可删除整个目录释放空间）；子目录名取自目录 json 的 downloadDir。 */
    fun modelDir(): File = File(context.filesDir, downloadSubDir)

    private val downloadSubDir: String by lazy {
        readSourceConfig().downloadDir.ifBlank { DEFAULT_DOWNLOAD_SUBDIR }
    }

    // ---- 目录 ----

    /**
     * 读取模型目录：优先远端（若配置了 remoteCatalogUrl），失败回落内置 assets，
     * 再失败回落 [SuperResModelCatalog.models]（编译期常量），**永不返回 null**。
     */
    suspend fun loadCatalog(forceRefresh: Boolean = false): List<SuperResModelInfo> = withContext(Dispatchers.IO) {
        cachedCatalog?.takeIf { !forceRefresh }?.let { return@withContext it }
        val remoteUrl = remoteCatalogUrlOverride.ifBlank { readSourceConfig().remoteCatalogUrl }
        val remoteJson = if (remoteUrl.isNotBlank()) {
            runCatching { httpGetText(remoteUrl) }.getOrNull()
        } else {
            null
        }
        val assetJson = runCatching {
            context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()

        val parsed = parseCatalog(remoteJson) ?: parseCatalog(assetJson) ?: SuperResModelCatalog.models
        cachedCatalog = parsed
        parsed
    }

    /** 纯解析：[json] 非法时返回 null（调用方据此回落下一来源）。 */
    internal fun parseCatalog(json: String?): List<SuperResModelInfo>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val arr = root.optJSONArray("models") ?: return@runCatching null
            val out = ArrayList<SuperResModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val kindName = o.optString("kind")
                val kind = SuperResModelKind.entries.firstOrNull { it.name.equals(kindName, true) }
                    ?: continue
                if (id.isBlank()) continue
                val nameObj = o.optJSONObject("name")
                val base = SuperResModelCatalog.byId(id)
                out.add(
                    SuperResModelInfo(
                        id = id,
                        kind = kind,
                        nameEn = nameObj?.optString("en").orEmpty().ifBlank { base?.nameEn ?: id },
                        nameZh = nameObj?.optString("zh").orEmpty().ifBlank { base?.nameZh.orEmpty() },
                        scale = o.optInt("scale", base?.scale ?: 1),
                        fileName = o.optString("fileName").ifBlank { base?.fileName ?: "$id.onnx" },
                        approxSizeBytes = o.optLong("approxSizeMB", 0L) * 1024L * 1024L,
                        sourceUrl = o.optString("sourceUrl").ifBlank { base?.sourceUrl.orEmpty() },
                        license = o.optString("license").ifBlank { base?.license.orEmpty() },
                        starNote = o.optString("star").ifBlank { base?.starNote.orEmpty() },
                        note = o.optString("note").ifBlank { base?.note.orEmpty() },
                        directFileUrl = o.optString("fileUrl").ifBlank { base?.directFileUrl.orEmpty() },
                        sha256 = o.optString("sha256").orEmpty(),
                    )
                )
            }
            out.takeIf { it.isNotEmpty() }
        }.onFailure { PLog.w(TAG, "catalog parse failed", it) }.getOrNull()
    }

    /** 内置目录里的远端配置（远端目录可配）。 */
    private fun readSourceConfig(): CatalogConfig = runCatching {
        val json = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        val cfg = JSONObject(json).optJSONObject("_source_config")
        CatalogConfig(
            remoteCatalogUrl = cfg?.optString("remoteCatalogUrl").orEmpty(),
            downloadDir = cfg?.optString("downloadDir").orEmpty().ifBlank { DEFAULT_DOWNLOAD_SUBDIR },
        )
    }.getOrDefault(CatalogConfig())

    // ---- 安装 / 删除 ----

    /**
     * 下载并安装一个模型。[onState] 在每个阶段变化时回调（供 UI 渲染进度条）。
     *
     * 流程：RESOLVING → DOWNLOADING(带进度) → VERIFYING → READY；
     * 失败 → FAILED(retryable/不可重试)，取消 → CANCELLED。任何异常都转为 FAILED，不抛出。
     */
    suspend fun downloadAndInstall(
        info: SuperResModelInfo,
        onState: ((SuperResDownloadState) -> Unit)? = null,
    ): SuperResDownloadState = withContext(Dispatchers.IO) {
        var state = SuperResDownloadStateMachine.initial(info.id)
        fun emit(s: SuperResDownloadState): SuperResDownloadState {
            state = s
            runCatching { onState?.invoke(s) }
            return s
        }

        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.ResolveStarted))
        if (info.directFileUrl.isBlank()) {
            // 许可/镜像不明确的模型（如风格迁移、LaMa 权重）没有官方直链：
            // 不给「一键下载」，改为提示用户用「导入模型」放入自备 ONNX。
            emit(
                SuperResDownloadStateMachine.transition(
                    state,
                    SuperResDownloadEvent.Failed(
                        message = "no_direct_url:${info.sourceUrl}",
                        retryable = false,
                    ),
                )
            )
            return@withContext state
        }

        val dir = modelDir().apply { runCatching { mkdirs() } }
        val target = File(dir, info.fileName)
        val temp = File(dir, info.fileName + TEMP_SUFFIX)
        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.DownloadStarted))

        val downloaded = runCatching {
            httpDownload(URL(info.directFileUrl), temp) { done, total ->
                emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.Progress(done, total)))
            }
        }.getOrDefault(false)

        if (!downloaded) {
            runCatching { temp.delete() }
            emit(
                SuperResDownloadStateMachine.transition(
                    state,
                    SuperResDownloadEvent.Failed("download_failed", retryable = true),
                )
            )
            return@withContext state
        }

        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.VerifyStarted))
        val verifyError = verify(temp, info)
        if (verifyError != null) {
            runCatching { temp.delete() }
            emit(
                SuperResDownloadStateMachine.transition(
                    state,
                    SuperResDownloadEvent.Failed(verifyError, retryable = true),
                )
            )
            return@withContext state
        }

        val moved = runCatching {
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.getOrDefault(false)
        if (!moved || !target.isFile) {
            runCatching { temp.delete() }
            emit(
                SuperResDownloadStateMachine.transition(
                    state,
                    SuperResDownloadEvent.Failed("install_rename_failed", retryable = true),
                )
            )
            return@withContext state
        }
        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.Installed))
        PLog.i(TAG, "model installed: ${info.id} (${target.length()} bytes)")
        state
    }

    /** 导入用户自备的 ONNX 模型（许可不明确的档位唯一接入路径；也可离线导入 Real-ESRGAN）。 */
    suspend fun importLocalModel(
        sourceUri: Uri,
        info: SuperResModelInfo,
        onState: ((SuperResDownloadState) -> Unit)? = null,
    ): SuperResDownloadState = withContext(Dispatchers.IO) {
        var state = SuperResDownloadStateMachine.initial(info.id)
        fun emit(s: SuperResDownloadState): SuperResDownloadState {
            state = s
            runCatching { onState?.invoke(s) }
            return s
        }
        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.DownloadStarted))
        val dir = modelDir().apply { runCatching { mkdirs() } }
        val target = File(dir, info.fileName)
        val copied = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied || target.length() <= 0L) {
            runCatching { target.delete() }
            emit(
                SuperResDownloadStateMachine.transition(
                    state,
                    SuperResDownloadEvent.Failed("import_failed", retryable = true),
                )
            )
            return@withContext state
        }
        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.VerifyStarted))
        val err = verify(target, info.copy(sha256 = ""))
        if (err != null) {
            runCatching { target.delete() }
            emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.Failed(err, true)))
            return@withContext state
        }
        emit(SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.Installed))
        state
    }

    /** 已安装模型文件。 */
    fun modelFile(info: SuperResModelInfo): File = File(modelDir(), info.fileName)

    fun isInstalled(id: String): Boolean {
        val info = SuperResModelCatalog.byId(id) ?: return false
        val f = modelFile(info)
        return f.isFile && f.length() > 0L
    }

    /**
     * 已安装模型 id 集合（受 [maxInstalledBytes] 影响不做裁剪，仅返回存在的文件）。
     * 目录内可能还有用户手放的其它 .onnx，按目录元信息匹配文件名。
     */
    fun installedIds(catalog: List<SuperResModelInfo> = SuperResModelCatalog.models): Set<String> {
        val dir = modelDir()
        if (!dir.isDirectory) return emptySet()
        return catalog.filter { info ->
            val f = File(dir, info.fileName)
            f.isFile && f.length() > 0L
        }.map { it.id }.toSet()
    }

    /** 已安装占用（字节），用于设置页展示与清理提示。 */
    fun installedBytes(): Long {
        val dir = modelDir()
        if (!dir.isDirectory) return 0L
        return dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".onnx") }.sumOf { it.length() }
    }

    /** 删除单个已下载模型（用户可随时删，app 保持轻量）。 */
    fun deleteModel(id: String): Boolean {
        val info = SuperResModelCatalog.byId(id) ?: return false
        val f = modelFile(info)
        val ok = !f.exists() || f.delete()
        PLog.i(TAG, "delete model $id -> $ok")
        return ok
    }

    /** 删除全部已下载模型（清缓存）。 */
    fun deleteAll(): Int {
        val dir = modelDir()
        if (!dir.isDirectory) return 0
        var n = 0
        dir.listFiles().orEmpty().forEach { if (it.isFile && it.delete()) n++ }
        return n
    }

    // ---- 内部 ----

    /** 校验：大小（若目录给了近似大小，允许 -20% 容差）与 sha256（若目录给了）。 */
    private fun verify(file: File, info: SuperResModelInfo): String? {
        if (!file.isFile || file.length() <= 0L) return "verify_empty_file"
        val expected = info.approxSizeBytes
        if (expected > 0L) {
            val min = (expected * 8L) / 10L
            if (file.length() < min) return "verify_size_too_small:${file.length()}/$expected"
        }
        if (info.sha256.isNotBlank()) {
            val actual = runCatching { sha256(file) }.getOrNull()
            if (actual == null || !actual.equals(info.sha256, ignoreCase = true)) {
                return "verify_hash_mismatch"
            }
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 带进度回调的下载；返回是否成功。HTTP 非 200 视为失败，不抛异常。 */
    private fun httpDownload(url: URL, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            val total = conn.contentLengthLong.coerceAtLeast(-1L)
            var done = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        done += read
                        onProgress(done, if (total > 0L) total else -1L)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: Exception) {
            PLog.w(TAG, "httpDownload error", e)
            false
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
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }
}
