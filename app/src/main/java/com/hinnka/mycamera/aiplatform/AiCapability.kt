package com.hinnka.mycamera.aiplatform

/**
 * 统一 API 能力域标识（AI 平台层契约符号，名称勿改；对齐审计报告 §3.1）。
 *
 * 新增能力只在此枚举追加，**禁止**在调用点各自硬编码字符串。
 * 各能力的默认端点/路径/模型见 [AiCapabilityDefaults]（= 审计报告 §3.6 默认端点表）。
 *
 * @param defaultPath 相对 baseUrl 的请求路径（必须 "/" 开头）
 * @param defaultModel 默认模型名；空串 = 该能力无模型字段
 * @param builtInBacked 是否属于「内置体验服务」族：用户未填密钥时回落到
 *        BuildConfig 内置端点 + 内置密钥（保持改造前行为）。false 的能力默认关闭（空端点）。
 * @param defaultConnectTimeoutMs / defaultReadTimeoutMs / defaultWriteTimeoutMs
 *        默认超时；三者均可被用户覆盖，clamp 范围 1s–600s
 */
enum class AiCapability(
    val defaultPath: String,
    val defaultModel: String,
    val builtInBacked: Boolean,
    val defaultConnectTimeoutMs: Int,
    val defaultReadTimeoutMs: Int,
    val defaultWriteTimeoutMs: Int,
) {
    /** 服务器超分：POST {baseUrl}/enhance（multipart: model + file）。默认关闭。 */
    SERVER_ENHANCE("/enhance", "basicsr-swinir", false, 15_000, 120_000, 120_000),

    /** AI LUT 配方：POST {baseUrl}/chat/completions。 */
    LUT_RECIPE("/chat/completions", "gemini-3.5-flash", true, 30_000, 300_000, 300_000),

    /** AI 图生图 / 图像还原：POST {baseUrl}/images/edits。 */
    IMAGE_RESTORE("/images/edits", "gemini-3.5-flash", true, 30_000, 300_000, 300_000),

    /** AI 照片质量评审：POST {baseUrl}/chat/completions。 */
    PHOTO_EVALUATION("/chat/completions", "gemini-3.5-flash", true, 30_000, 300_000, 300_000),

    /** 模型列表：GET {baseUrl}/models。 */
    MODEL_LIST("/models", "", true, 30_000, 120_000, 120_000),

    /** AI LUT 推荐（可选远端；端点空则走本地启发式，不解散既有行为）。默认关闭。 */
    LUT_RECOMMEND("/classify", "", false, 15_000, 60_000, 60_000),

    /** AI 白平衡（可选远端；端点空则走端侧灰世界 + 高光估计）。默认关闭。 */
    WHITE_BALANCE("/white-balance", "", false, 15_000, 60_000, 60_000),

    /** AI 场景识别（可选远端；端点空则走端侧轻量规则分类）。默认关闭。 */
    SCENE_RECOGNITION("/classify", "", false, 15_000, 60_000, 60_000),

    /** 地磁偏角（NOAA WMM 在线计算，可选远端；端点空则只用内嵌 WMM2025，完全离线）。默认关闭。 */
    DECLINATION("/calculators/calculateDeclination", "", false, 8_000, 15_000, 60_000),    ;

    /** DataStore 存储前缀（每字段一个 key，见 [AiConfigCodec.storageKey]）。 */
    val storagePrefix: String get() = "cap_" + name.lowercase()

    companion object {
        /** 默认重试次数（0 = 不重试；clamp 上限见 [AiEndpointConfig.RETRY_MAX]）。 */
        const val DEFAULT_MAX_RETRIES = 2

        /** 默认指数退避基数（毫秒）。 */
        const val DEFAULT_RETRY_BASE_DELAY_MS = 500L

        /** 默认是否允许明文 http：false = 强制 https（自部署 http 需用户显式开启 + manifest 放行）。 */
        const val DEFAULT_ALLOW_CLEARTEXT = false
    }
}

/**
 * 各能力默认端点（审计报告 §3.6）。**纯函数**，不读 Context / BuildConfig，
 * 便于 JVM 单测；构建态默认值由调用方（[DataStoreAiConfigStore]）注入。
 */
object AiCapabilityDefaults {

    /**
     * 单能力默认配置。
     *
     * @param builtInBaseUrl BuildConfig.BUILT_IN_API_URL（内置体验服务端点）
     * @param builtInApiKey  BuildConfig.BUILT_IN_API_KEY（构建期注入，源码/仓库无明文）
     * @return 已 clamp 的默认配置；密钥默认不落默认值，避免内置 Key 被带到第三方端点
     *         （内置密钥注入见 [AiConfigCodec.resolveBuiltInCredential]）。
     */
    fun forCapability(
        capability: AiCapability,
        builtInBaseUrl: String = "",
        builtInApiKey: String = "",
    ): AiEndpointConfig = AiConfigCodec.resolveBuiltInCredential(
        config = AiConfigCodec.defaults(capability = capability, builtInBaseUrl = builtInBaseUrl),
        builtInBaseUrl = builtInBaseUrl,
        builtInApiKey = builtInApiKey,
    )

    /** 全部能力的默认配置快照。 */
    fun all(
        builtInBaseUrl: String = "",
        builtInApiKey: String = "",
    ): Map<AiCapability, AiEndpointConfig> = AiCapability.entries.associateWith {
        forCapability(it, builtInBaseUrl, builtInApiKey)
    }
}
