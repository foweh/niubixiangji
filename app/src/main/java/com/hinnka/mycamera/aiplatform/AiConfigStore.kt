package com.hinnka.mycamera.aiplatform

import kotlinx.coroutines.flow.Flow

/**
 * 配置补丁：null 字段 = 不改动该字段；空串 = 清空该项（回落默认）。
 * 对齐审计报告 §3.2。
 */
data class AiConfigPatch(
    val enabled: Boolean? = null,
    val baseUrl: String? = null,
    val path: String? = null,
    val apiKey: String? = null,
    val authScheme: String? = null,
    val authHeaderName: String? = null,
    val model: String? = null,
    val connectTimeoutMs: Int? = null,
    val readTimeoutMs: Int? = null,
    val writeTimeoutMs: Int? = null,
    val maxRetries: Int? = null,
    val retryBaseDelayMs: Long? = null,
    val allowCleartext: Boolean? = null,
)

/**
 * 配置仓库（AI 平台层契约符号，对齐审计报告 §3.2）。
 *
 * 实现见 [DataStoreAiConfigStore]（DataStore 持久化 + AndroidKeystore 加密密钥）。
 * 契约：**任何异常不得向上抛**；读取失败回落构建态默认。
 */
interface AiConfigStore {

    /** 读取最终生效配置（构建态默认 ⊕ 用户覆盖 ⊕ 内置凭据策略），已 clamp。 */
    suspend fun config(capability: AiCapability): AiEndpointConfig

    /** 观察配置变化（UI 门控 / 开关随动）。 */
    fun observe(capability: AiCapability): Flow<AiEndpointConfig>

    /** 写入用户覆盖（写前 clamp；密钥走加密，加密不可用则拒写）。 */
    suspend fun setUserOverride(capability: AiCapability, patch: AiConfigPatch)

    /** 清除用户覆盖，回落构建态默认。 */
    suspend fun clearUserOverride(capability: AiCapability)

    /** 能力是否可用（已启用 && 鉴权按需满足）。 */
    suspend fun isAvailable(capability: AiCapability): Boolean

    /** 全部能力生效配置快照（设置页 / 自检用）。 */
    suspend fun snapshot(): Map<AiCapability, AiEndpointConfig> =
        AiCapability.entries.associateWith { config(it) }
}
