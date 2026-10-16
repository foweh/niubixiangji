package com.hinnka.mycamera.aiplatform

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AI 功能参数字典（**开放度**：新功能（AI 白平衡 / AI 场景识别等）的开关与阈值
 * 全部通过本对象持久化，设置页可改，越界值由各模块自己的 `clamp` 兜底）。
 *
 * 与 [AiConfigStore]（端点/密钥/超时）分开：本对象只存**业务参数**，不含任何凭据，
 * 因此读写无需加解密，也不存在泄漏面。
 *
 * 独立 DataStore（`ai_feature_preferences`）：既有 `UserPreferencesRepository` 的
 * `Context.dataStore` 是文件级 private 扩展，外部包不可见，故本模块另开一个。
 */
private val Context.aiFeatureDataStore by preferencesDataStore(name = "ai_feature_preferences")

object AiParamStore {

    const val DATASTORE_NAME = "ai_feature_preferences"

    suspend fun readBoolean(context: Context, key: String, default: Boolean): Boolean =
        readOrNull(context, key) { it.toBooleanStrictOrNull() } ?: default

    suspend fun readInt(context: Context, key: String, default: Int): Int =
        readOrNull(context, key) { it.toIntOrNull() } ?: default

    suspend fun readFloat(context: Context, key: String, default: Float): Float =
        readOrNull(context, key) { it.toFloatOrNull() } ?: default

    suspend fun readString(context: Context, key: String, default: String): String =
        readOrNull(context, key) { it } ?: default

    suspend fun writeBoolean(context: Context, key: String, value: Boolean) =
        write(context, key, value.toString())

    suspend fun writeInt(context: Context, key: String, value: Int) =
        write(context, key, value.toString())

    suspend fun writeFloat(context: Context, key: String, value: Float) =
        write(context, key, value.toString())

    suspend fun writeString(context: Context, key: String, value: String) =
        write(context, key, value)

    fun observeBoolean(context: Context, key: String, default: Boolean): Flow<Boolean> =
        context.aiFeatureDataStore.data.map { prefs ->
            prefs[booleanPreferencesKey(key)] ?: prefs[stringPreferencesKey(key)]?.toBooleanStrictOrNull() ?: default
        }

    suspend fun contains(context: Context, key: String): Boolean =
        runCatching { context.aiFeatureDataStore.data.first().asMap().containsKey(stringPreferencesKey(key)) }
            .getOrDefault(false)

    /** 一次性清空本模块的业务参数（「恢复默认」用）。 */
    suspend fun clear(context: Context) {
        runCatching { context.aiFeatureDataStore.edit { it.clear() } }
    }

    private suspend fun <T> readOrNull(context: Context, key: String, parse: (String) -> T?): T? = runCatching {
        val prefs = context.aiFeatureDataStore.data.first()
        val raw = prefs[stringPreferencesKey(key)]
            ?: prefs[intPreferencesKey(key)]?.toString()
            ?: prefs[floatPreferencesKey(key)]?.toString()
            ?: prefs[booleanPreferencesKey(key)]?.toString()
        raw?.let(parse)
    }.getOrNull()

    private suspend fun write(context: Context, key: String, value: String) {
        // 统一以 String 存储：便于「未设置」与「设成默认值」区分，也免去类型迁移。
        runCatching { context.aiFeatureDataStore.edit { it[stringPreferencesKey(key)] = value } }
    }
}
