package com.hinnka.mycamera.superres

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 本模块**专有**的 DataStore（独立文件 `superres_preferences`）。
 *
 * 为什么独立：PATTERN_SPEC 第 1.1 节禁止修改既有文件（含 `data/UserPreferencesRepository.kt`），
 * 因此本模块自建 DataStore 承载全部开放参数，读取路径明确：
 *  `filesDir/datastore/superres_preferences.preferences_pb`，键名见 [SuperResConfigCodec]。
 *
 * 集成阶段若希望并入既有设置体系，只需在设置页读写同一个 [SuperResConfig]，
 * 或把 codec 产出的 `Map<String,String>` 落到既有 DataStore（补丁里已给出挂点）。
 *
 * 持久化形态：单键 `config`，值为 codec 产出的 `k=v\n` 文本，便于人工查看与运维比对。
 */
private val Context.superResPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "superres_preferences",
)

class SuperResConfigStore(private val context: Context) {

    private val configKey = stringPreferencesKey("config")

    /** 配置流（始终已 clamp）。 */
    val configFlow: Flow<SuperResConfig> = context.superResPreferences.data.map { prefs ->
        decode(prefs[configKey]).config
    }

    /** 读取一次（挂起）。 */
    suspend fun current(): SuperResConfig = configFlow.first()

    /** 覆盖写入（写入前 clamp）。 */
    suspend fun set(config: SuperResConfig) {
        val clamped = SuperResConfig.clamp(config)
        context.superResPreferences.edit { prefs ->
            prefs[configKey] = encodeToString(clamped)
        }
    }

    /** 读改写（挂起）。 */
    suspend fun update(transform: (SuperResConfig) -> SuperResConfig) {
        set(transform(current()))
    }

    /** 删除持久化配置，回到编译期默认值。 */
    suspend fun reset() {
        context.superResPreferences.edit { prefs -> prefs.remove(configKey) }
    }

    /** 解码并记录被忽略/被修正的项（误改配置文件不崩溃，只在日志里留痕）。 */
    private fun decode(raw: String?): SuperResConfigDecodeResult {
        val map = decodeToMap(raw)
        val result = SuperResConfigCodec.decode(map)
        if (result.ignoredKeys.isNotEmpty()) {
            PLog.w(TAG, "superres config ignored keys: ${result.ignoredKeys}")
        }
        if (result.correctedFields.isNotEmpty()) {
            PLog.i(TAG, "superres config clamped fields: ${result.correctedFields}")
        }
        return result
    }

    companion object {
        private const val TAG = "SuperResConfigStore"

        /** `k=v` 行格式（值里不该出现换行；编解码两侧同一实现，保证往返一致）。 */
        fun encodeToString(config: SuperResConfig): String =
            SuperResConfigCodec.encode(config).entries.joinToString("\n") { "${it.key}=${it.value}" }

        /** 文本 → 映射；畸形行被跳过（不抛异常）。 */
        fun decodeToMap(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val out = HashMap<String, String>(32)
            raw.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                if (key.isEmpty()) return@forEach
                out[key] = line.substring(idx + 1).trim()
            }
            return out
        }
    }
}
