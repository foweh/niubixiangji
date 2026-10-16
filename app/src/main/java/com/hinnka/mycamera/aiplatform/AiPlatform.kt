package com.hinnka.mycamera.aiplatform

import android.content.Context

/**
 * 平台层聚合入口：一次拿到「配置仓库 + 统一 HTTP 客户端」。
 * 所有 AI/API 调用点统一从这里取依赖，避免各自 new OkHttpClient（消除重复实现）。
 */
class AiPlatformClient(
    val configStore: AiConfigStore,
    val httpClient: AiHttpClient,
)

/**
 * 进程级单例（懒加载）。
 *
 * 用法（补丁接线处）：
 * ```
 * val platform = AiPlatform.get(context)
 * platform.httpClient.executeJson(AiCapability.MODEL_LIST, AiRequest(method = "GET")) { json -> ... }
 * ```
 *
 * - 未调用 [get] 之前 [peek] 返回 null（[AiFallback] 依赖此语义在不崩的前提下退化到本地）；
 * - [install] 供测试 / 集成阶段注入替身实现。
 */
object AiPlatform {

    @Volatile
    private var client: AiPlatformClient? = null

    /** 取（必要时创建）进程单例。 */
    fun get(context: Context): AiPlatformClient {
        val existing = client
        if (existing != null) return existing
        return synchronized(this) {
            val current = client
            if (current != null) {
                current
            } else {
                val store = DataStoreAiConfigStore(context.applicationContext)
                AiPlatformClient(configStore = store, httpClient = OkHttpAiHttpClient(store)).also {
                    client = it
                }
            }
        }
    }

    /** 只读已创建实例；未初始化返回 null（不触发创建，避免在无 Context 场景崩溃）。 */
    fun peek(): AiPlatformClient? = client

    /** 注入替身（测试 / 集成阶段）；传 null 复位。 */
    fun install(value: AiPlatformClient?) {
        synchronized(this) { client = value }
    }

    /** 便捷读取：配置仓库（未初始化时返回 null）。 */
    fun configStoreOrNull(): AiConfigStore? = client?.configStore

    /** 便捷读取：HTTP 客户端（未初始化时返回 null）。 */
    fun httpClientOrNull(): AiHttpClient? = client?.httpClient
}
