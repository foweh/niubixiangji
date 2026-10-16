package com.hinnka.mycamera.appgallery

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * DataStore 扩展属性：**独立文件** `app_gallery_preferences`（不与既有 `user_preferences` 混用，
 * 避免集成阶段互相覆盖；本地名 `appGalleryPreferences` 与工程既有 5 个 DataStore 也不重名）。
 */
private val Context.appGalleryPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "app_gallery_preferences"
)

/**
 * 应用内置相册持久化（相册项 1/5/11/19）。
 *
 * 落盘内容（全部为字符串载荷，编解码见 [AppGalleryCodec] / [AppGalleryConfigCodec]）：
 * | key | 内容 | 编解码 |
 * |---|---|---|
 * | `config_payload` | 全部可调参数 | [AppGalleryConfigCodec] |
 * | `annotations_payload` | 收藏/标签/评分 | [AppGalleryAnnotationCodec] |
 * | `folders_payload` | 相册列表 | [AppGalleryFolderCodec] |
 * | `trash_payload` | 回收站索引 | [AppGalleryTrashCodec] |
 * | `view_memory_payload` | 视图记忆 | [AppGalleryViewMemoryCodec] |
 * | `last_trash_purge_at` | 上次自动清空时间 | Long |
 *
 * **外部配置文件覆盖**：若 `filesDir/appgallery/config.txt` 存在，其内容会覆盖 DataStore 中的
 * 同名字段（便于不改代码调参）；再次保存配置时会被写入 DataStore 并保留该文件。
 */
class AppGallerySettingsStore private constructor(private val context: Context) {

    private val store: DataStore<Preferences> get() = context.appGalleryPreferences

    // ── 配置 ────────────────────────────────────────────────────

    val configFlow: Flow<AppGalleryConfig> = store.data.map { prefs ->
        decodeConfig(prefs[KEY_CONFIG])
    }

    suspend fun loadConfig(): AppGalleryConfig =
        runCatching { decodeConfig(store.data.first()[KEY_CONFIG]) }
            .onFailure { PLog.w(TAG, "loadConfig failed", it) }
            .getOrDefault(AppGalleryConfig.clamp(AppGalleryConfig()))

    suspend fun saveConfig(config: AppGalleryConfig) {
        val safe = AppGalleryConfig.clampAndLog(config, TAG)
        write(KEY_CONFIG, AppGalleryConfigCodec.encode(safe))
    }

    suspend fun updateConfig(transform: (AppGalleryConfig) -> AppGalleryConfig) {
        saveConfig(transform(loadConfig()))
    }

    /** DataStore 值 + 外部配置文件（后者优先）合并解码。 */
    private fun decodeConfig(payload: String?): AppGalleryConfig {
        val base = AppGalleryConfigCodec.decode(payload)
        val overrideText = readExternalConfigText() ?: return base
        return AppGalleryConfig.clamp(AppGalleryConfigCodec.decode(overrideText, base))
    }

    private fun readExternalConfigText(): String? = runCatching {
        val dir = AppGalleryStorageLayout.cityTableFile(context, createParent = false).parentFile
        val file = dir?.let { java.io.File(it, EXTERNAL_CONFIG_FILE_NAME) }
        if (file != null && file.exists()) {
            file.readText().take(EXTERNAL_CONFIG_MAX_CHARS)
        } else {
            null
        }
    }.getOrNull()

    /** 写一份默认配置文件模板（供高级用户编辑）。 */
    suspend fun exportDefaultConfigTemplate(): Boolean = runCatching {
        val file = java.io.File(
            AppGalleryStorageLayout.cityTableFile(context).parentFile,
            EXTERNAL_CONFIG_FILE_NAME,
        )
        if (!file.exists()) {
            file.writeText(AppGalleryConfigCodec.encode(loadConfig()))
        }
        true
    }.onFailure { PLog.w(TAG, "export config template failed", it) }.getOrDefault(false)

    // ── 收藏 / 标签 / 评分 ──────────────────────────────────────

    val annotationsFlow: Flow<Map<String, AppGalleryAnnotation>> = store.data.map { prefs ->
        AppGalleryAnnotationCodec.decode(prefs[KEY_ANNOTATIONS])
    }

    suspend fun loadAnnotations(): Map<String, AppGalleryAnnotation> =
        runCatching { AppGalleryAnnotationCodec.decode(store.data.first()[KEY_ANNOTATIONS]) }
            .getOrDefault(emptyMap())

    suspend fun saveAnnotations(annotations: Map<String, AppGalleryAnnotation>) {
        write(KEY_ANNOTATIONS, AppGalleryAnnotationCodec.encode(annotations))
    }

    suspend fun updateAnnotations(
        transform: (Map<String, AppGalleryAnnotation>) -> Map<String, AppGalleryAnnotation>,
    ): Map<String, AppGalleryAnnotation> {
        val updated = transform(loadAnnotations())
        saveAnnotations(updated)
        return updated
    }

    suspend fun toggleFavorite(itemId: String, now: Long = System.currentTimeMillis()) {
        updateAnnotations { AppGalleryAnnotationMachine.toggleFavorite(it, itemId, now, loadConfig()) }
    }

    suspend fun setFavorite(itemId: String, favorite: Boolean, now: Long = System.currentTimeMillis()) {
        updateAnnotations { AppGalleryAnnotationMachine.setFavorite(it, itemId, favorite, now, loadConfig()) }
    }

    suspend fun cycleRating(itemId: String, now: Long = System.currentTimeMillis()) {
        updateAnnotations { AppGalleryAnnotationMachine.cycleRating(it, itemId, now, loadConfig()) }
    }

    suspend fun addTag(itemId: String, tag: String, now: Long = System.currentTimeMillis()) {
        updateAnnotations { AppGalleryAnnotationMachine.addTag(it, itemId, tag, now, loadConfig()) }
    }

    suspend fun applyBatch(request: AppGalleryBatchRequest): Map<String, AppGalleryAnnotation> {
        val config = loadConfig()
        val now = System.currentTimeMillis()
        return when (request.action) {
            AppGalleryBatchAction.FAVORITE_ON ->
                updateAnnotations { AppGalleryAnnotationMachine.batchSetFavorite(it, request.itemIds, true, now, config) }
            AppGalleryBatchAction.FAVORITE_OFF ->
                updateAnnotations { AppGalleryAnnotationMachine.batchSetFavorite(it, request.itemIds, false, now, config) }
            AppGalleryBatchAction.ADD_TAGS ->
                updateAnnotations { AppGalleryAnnotationMachine.batchAddTags(it, request.itemIds, request.tags, now, config) }
            AppGalleryBatchAction.REMOVE_TAGS ->
                updateAnnotations { AppGalleryAnnotationMachine.batchRemoveTags(it, request.itemIds, request.tags, now, config) }
            AppGalleryBatchAction.SET_RATING ->
                updateAnnotations { AppGalleryAnnotationMachine.batchSetRating(it, request.itemIds, request.rating, now, config) }
            else -> loadAnnotations()
        }
    }

    // ── 相册 ────────────────────────────────────────────────────

    suspend fun loadFolders(): List<AppGalleryFolder> {
        val decoded = runCatching { AppGalleryFolderCodec.decode(store.data.first()[KEY_FOLDERS]) }
            .getOrDefault(emptyList())
        if (decoded.isNotEmpty()) return decoded
        // 首次使用：落盘内置收藏夹
        val defaults = AppGalleryFolderModel.defaultFolders(System.currentTimeMillis())
        saveFolders(defaults)
        return defaults
    }

    suspend fun saveFolders(folders: List<AppGalleryFolder>) {
        write(KEY_FOLDERS, AppGalleryFolderCodec.encode(folders))
    }

    // ── 回收站 ──────────────────────────────────────────────────

    suspend fun loadTrash(): List<AppGalleryTrashEntry> =
        runCatching { AppGalleryTrashCodec.decode(store.data.first()[KEY_TRASH]) }
            .onFailure { PLog.w(TAG, "loadTrash failed", it) }
            .getOrDefault(emptyList())

    suspend fun saveTrash(entries: List<AppGalleryTrashEntry>) {
        write(KEY_TRASH, AppGalleryTrashCodec.encode(entries))
    }

    suspend fun lastTrashPurgeAt(): Long =
        runCatching { store.data.first()[KEY_LAST_PURGE] ?: 0L }.getOrDefault(0L)

    suspend fun setLastTrashPurgeAt(value: Long) {
        runCatching { store.edit { it[KEY_LAST_PURGE] = value } }
            .onFailure { PLog.w(TAG, "setLastTrashPurgeAt failed", it) }
    }

    // ── 视图记忆 ────────────────────────────────────────────────

    suspend fun loadViewMemories(): List<AppGalleryViewMemory> =
        runCatching { AppGalleryViewMemoryCodec.decode(store.data.first()[KEY_VIEW_MEMORY]) }
            .getOrDefault(emptyList())

    suspend fun recordViewMemory(
        scopeId: String,
        viewMode: AppGalleryViewMode,
        sortSpec: AppGallerySortSpec,
        granularity: TimelineGranularity,
        scrollAnchorId: String? = null,
    ): List<AppGalleryViewMemory> {
        val limit = loadConfig().viewMemorySlots
        val memory = AppGalleryViewMemory(
            scopeId = scopeId,
            viewMode = viewMode,
            sortField = sortSpec.field,
            sortOrder = sortSpec.order,
            timelineGranularity = granularity,
            scrollAnchorId = scrollAnchorId,
            updatedAt = System.currentTimeMillis(),
        )
        val updated = AppGalleryViewMemoryKeeper.upsert(loadViewMemories(), memory, limit)
        write(KEY_VIEW_MEMORY, AppGalleryViewMemoryCodec.encode(updated))
        return updated
    }

    suspend fun resolveViewMemory(scopeId: String): AppGalleryViewMemory =
        AppGalleryViewMemoryKeeper.resolve(loadViewMemories(), scopeId, loadConfig())

    // ── 自定义城市表 ────────────────────────────────────────────

    /**
     * 读取自定义离线城市表（`filesDir/appgallery/city_table.txt`）。
     * 不存在/解析为空时返回内置表（[AppGalleryOfflineCityIndex.BUILT_IN]）。
     */
    fun loadCityTable(): List<AppGalleryOfflineCityIndex.CityBoundary> {
        val parsed = runCatching {
            val file = AppGalleryStorageLayout.cityTableFile(context, createParent = false)
            if (file.exists()) {
                AppGalleryOfflineCityIndex.parseCustomTable(file.readText())
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { AppGalleryOfflineCityIndex.BUILT_IN }
    }

    // ── 内部 ────────────────────────────────────────────────────

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        runCatching {
            store.edit { prefs ->
                if (value.isNullOrEmpty()) prefs.remove(key) else prefs[key] = value
            }
        }.onFailure { error ->
            if (error is IOException) {
                PLog.w(TAG, "DataStore write failed (IO): $key", error)
            } else {
                PLog.w(TAG, "DataStore write failed: $key", error)
            }
        }
    }

    companion object {
        private const val TAG = "AppGallerySettings"

        /** 外部配置文件名（位于 `filesDir/appgallery/`）。 */
        const val EXTERNAL_CONFIG_FILE_NAME = "config.txt"

        /** 外部配置最大读取长度（字符）。 */
        private const val EXTERNAL_CONFIG_MAX_CHARS = 64 * 1024

        private val KEY_CONFIG = stringPreferencesKey("config_payload")
        private val KEY_ANNOTATIONS = stringPreferencesKey("annotations_payload")
        private val KEY_FOLDERS = stringPreferencesKey("folders_payload")
        private val KEY_TRASH = stringPreferencesKey("trash_payload")
        private val KEY_VIEW_MEMORY = stringPreferencesKey("view_memory_payload")
        private val KEY_LAST_PURGE = longPreferencesKey("last_trash_purge_at")

        @Volatile
        private var instance: AppGallerySettingsStore? = null

        /** 单例（DataStore 同名 delegate 只能有一个实例）。 */
        fun getInstance(context: Context): AppGallerySettingsStore =
            instance ?: synchronized(this) {
                instance ?: AppGallerySettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
