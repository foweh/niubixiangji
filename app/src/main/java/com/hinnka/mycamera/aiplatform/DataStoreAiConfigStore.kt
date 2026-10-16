package com.hinnka.mycamera.aiplatform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * AI 平台层专用 DataStore（与既有 `user_preferences` 分离）。
 *
 * 为什么另开一个文件：既有 `Context.dataStore` 是 `UserPreferencesRepository.kt` 的
 * **文件级 private 扩展**，外部包不可见；另开独立 DataStore 既复用 DataStore 能力，
 * 又避免改动既有文件、避免与既有迁移/加密链路耦合（PATTERN_SPEC §1 禁止改既有文件）。
 */
private val Context.aiPlatformDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_platform_preferences",
)

/**
 * DataStore 持久化的 [AiConfigStore] 实现（对齐审计报告 §3.2）。
 *
 * - **复用**：`androidx.datastore:datastore-preferences`（既有依赖）+ 既有
 *   `data/OpenAiApiKeyCipher`（Keystore AES/GCM），新增依赖 0；
 * - **密钥**：一律经 [AiSecretProtector] 加密后落盘；加密不可用则**拒写**（宁可不存也不落明文）；
 * - **不崩**：读取失败回落构建态默认，写入失败仅记录日志；
 * - **默认值**：内置端点取 `BuildConfig.BUILT_IN_API_URL`，内置密钥取
 *   `BuildConfig.BUILT_IN_API_KEY`（构建期由 local.properties 注入，源码无明文）。
 *
 * @param protector 密钥保护器（默认 Keystore 实现）
 * @param builtInBaseUrl / @param builtInApiKey 构建态默认（测试可注入假值）
 */
class DataStoreAiConfigStore(
    context: Context,
    private val protector: AiSecretProtector = KeystoreAiSecretProtector(),
    private val builtInBaseUrl: String = BuildConfig.BUILT_IN_API_URL,
    private val builtInApiKey: String = BuildConfig.BUILT_IN_API_KEY,
) : AiConfigStore {

    private val appContext: Context = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.aiPlatformDataStore

    private fun prefKey(capability: AiCapability, field: String) =
        stringPreferencesKey(AiConfigCodec.storageKey(capability, field))

    private fun openSecret(sealed: String): String? = runCatching { protector.open(sealed) }
        .onFailure { PLog.w(TAG, "AI 密钥解密失败，回落默认值（不落明文）") }
        .getOrNull()

    private fun fallbackConfig(capability: AiCapability): AiEndpointConfig =
        AiConfigCodec.resolveBuiltInCredential(
            config = AiConfigCodec.defaults(capability, builtInBaseUrl),
            builtInBaseUrl = builtInBaseUrl,
            builtInApiKey = builtInApiKey,
        )

    private fun resolve(preferences: Preferences, capability: AiCapability): AiEndpointConfig {
        val base = AiConfigCodec.defaults(capability, builtInBaseUrl)
        val stored = LinkedHashMap<String, String>(AiConfigCodec.fields.size)
        AiConfigCodec.fields.forEach { field ->
            preferences[prefKey(capability, field)]?.let { stored[field] = it }
        }
        val merged = AiConfigCodec.merge(base, stored) { sealed -> openSecret(sealed) }
        return AiConfigCodec.resolveBuiltInCredential(merged, builtInBaseUrl, builtInApiKey)
    }

    override suspend fun config(capability: AiCapability): AiEndpointConfig = withContext(Dispatchers.IO) {
        runCatching { dataStore.data.first() }
            .map { resolve(it, capability) }
            .getOrElse { fallbackConfig(capability) }
    }

    override fun observe(capability: AiCapability): Flow<AiEndpointConfig> = dataStore.data
        .map { resolve(it, capability) }
        .distinctUntilChanged()

    override suspend fun setUserOverride(capability: AiCapability, patch: AiConfigPatch) {
        withContext(Dispatchers.IO) {
            val entries = AiConfigCodec.patchToEntries(capability, patch) { plain ->
                runCatching { protector.seal(plain) }
                    .onFailure { PLog.e(TAG, "AI 密钥加密失败，拒写（禁止明文落盘）", it) }
                    .getOrNull()
            }
            if (AiConfigCodec.rejectedSecret(patch, entries)) {
                PLog.w(TAG, "AI 密钥未能加密，本次不落盘（密钥仅在内存中生效，重启后需重新填写）")
            }
            runCatching {
                dataStore.edit { preferences ->
                    entries.forEach { (field, value) ->
                        val key = prefKey(capability, field)
                        if (value.isEmpty()) preferences.remove(key) else preferences[key] = value
                    }
                }
            }.onFailure { PLog.e(TAG, "写入 AI 配置失败", it) }
        }
    }

    override suspend fun clearUserOverride(capability: AiCapability) {
        withContext(Dispatchers.IO) {
            runCatching {
                dataStore.edit { preferences ->
                    AiConfigCodec.fields.forEach { preferences.remove(prefKey(capability, it)) }
                }
            }.onFailure { PLog.e(TAG, "清除 AI 配置失败", it) }
        }
    }

    override suspend fun isAvailable(capability: AiCapability): Boolean {
        val config = config(capability)
        return config.isEnabled && (!config.requiresAuth || config.hasCredential)
    }

    private companion object {
        const val TAG = "AiConfigStore"
    }
}
