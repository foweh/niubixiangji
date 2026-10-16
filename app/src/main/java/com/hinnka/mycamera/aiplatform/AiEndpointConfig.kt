package com.hinnka.mycamera.aiplatform

/**
 * 单能力端点 / 鉴权 / 限额配置（AI 平台层契约符号，对齐审计报告 §3.1）。
 *
 * 取值范围（[clamp] 保障，越界自动修正不崩溃）：
 *  - connectTimeoutMs / readTimeoutMs / writeTimeoutMs ∈ [1_000, 600_000]
 *  - maxRetries ∈ [0, 5]；retryBaseDelayMs ∈ [100, 10_000]
 *  - baseUrl：空串 = 未配置端点；非空必须为 http(s)，且 [allowCleartext] = false 时必须是 https
 *  - path：空串或自动补前导 "/"
 *  - authHeaderName 为空 = 不注入鉴权头（此时 authScheme 一并置空）
 *
 * @param userEnabled 设置页总开关；false 时无论 baseUrl 是否填写都视为未启用。
 * @param apiKey 运行时注入；**源码/仓库禁止出现明文**。空串表示不做鉴权（仅限本机/可信网段）。
 * @param authScheme 鉴权方案前缀（如 "Bearer"）；空串表示只注入裸 Key。
 * @param authHeaderName 鉴权头名（如 "Authorization" / "X-Api-Key"）；空串表示不注入。
 */
data class AiEndpointConfig(
    val capability: AiCapability,
    val userEnabled: Boolean = true,
    val baseUrl: String = "",
    val path: String = capability.defaultPath,
    val apiKey: String = "",
    val authScheme: String = "Bearer",
    val authHeaderName: String = "Authorization",
    val model: String = capability.defaultModel,
    val connectTimeoutMs: Int = capability.defaultConnectTimeoutMs,
    val readTimeoutMs: Int = capability.defaultReadTimeoutMs,
    val writeTimeoutMs: Int = capability.defaultWriteTimeoutMs,
    val maxRetries: Int = AiCapability.DEFAULT_MAX_RETRIES,
    val retryBaseDelayMs: Long = AiCapability.DEFAULT_RETRY_BASE_DELAY_MS,
    val allowCleartext: Boolean = AiCapability.DEFAULT_ALLOW_CLEARTEXT,
) {

    /** 端点是否已填写（非空即视为已配置；真正是否可用还要看 [isEnabled] 与鉴权）。 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** 能力总开关：用户开关 && 端点已配置。 */
    val isEnabled: Boolean get() = userEnabled && isConfigured

    val isHttps: Boolean get() = baseUrl.startsWith(HTTPS_PREFIX, ignoreCase = true)

    val isCleartextHttp: Boolean get() = baseUrl.startsWith(HTTP_PREFIX, ignoreCase = true)

    /** 明文 http 但未显式放行：调用前直接判失败，给出明确归因，避免「静默回退」。 */
    val isCleartextBlocked: Boolean get() = isCleartextHttp && !allowCleartext

    /** 协议是否受支持（http / https）。 */
    val isSchemeSupported: Boolean get() = isHttps || isCleartextHttp

    /** 是否需要鉴权（配置了鉴权头名）。 */
    val requiresAuth: Boolean get() = authHeaderName.isNotBlank()

    /** 是否持有可用凭据。 */
    val hasCredential: Boolean get() = apiKey.isNotBlank()

    val isAuthenticated: Boolean get() = requiresAuth && hasCredential

    /** 拼接后的完整请求地址（pathOverride 为空时用 [path]）。 */
    fun resolvedUrl(pathOverride: String? = null): String {
        val raw = if (pathOverride.isNullOrBlank()) path else pathOverride
        return baseUrl + AiEndpointConfig.normalizePath(raw)
    }

    /** 鉴权头（name to value）。不需要鉴权或无凭据时返回 null。 */
    fun authHeader(): Pair<String, String>? {
        if (!requiresAuth || !hasCredential) return null
        val value = if (authScheme.isBlank()) apiKey else "$authScheme $apiKey"
        return authHeaderName to value
    }

    /** 配置校验：null = 可发起请求；否则给出失败归因。 */
    fun validate(): AiFailureReason? = when {
        !isEnabled -> AiFailureReason.Disabled
        !isSchemeSupported -> AiFailureReason.Parse("baseUrl 必须以 http:// 或 https:// 开头")
        isCleartextBlocked -> AiFailureReason.CleartextBlocked(baseUrl)
        else -> null
    }

    /** 日志 / 设置页用脱敏描述（**永不包含完整 Key**）。 */
    fun describe(): String = buildString {
        append(capability.name)
        append(" enabled=").append(isEnabled)
        append(" url=").append(resolvedUrl())
        append(" model=").append(model.ifBlank { "-" })
        append(" auth=")
        if (requiresAuth) {
            append(authHeaderName).append(':').append(AiLogRedactor.maskSecret(apiKey))
        } else {
            append("none")
        }
        append(" timeout=").append(connectTimeoutMs).append('/').append(readTimeoutMs)
            .append('/').append(writeTimeoutMs).append("ms")
        append(" retries=").append(maxRetries)
        append(" cleartext=").append(allowCleartext)
    }

    companion object {
        const val TIMEOUT_MIN_MS = 1_000
        const val TIMEOUT_MAX_MS = 600_000
        const val RETRY_MIN = 0
        const val RETRY_MAX = 5
        const val RETRY_BASE_MIN_MS = 100L
        const val RETRY_BASE_MAX_MS = 10_000L

        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"

        /** 裁空格、去尾斜杠。 */
        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

        /** 裁空格、补前导 "/"；空串保持空串。 */
        fun normalizePath(path: String): String {
            val trimmed = path.trim()
            return when {
                trimmed.isEmpty() -> ""
                trimmed.startsWith("/") -> trimmed
                else -> "/$trimmed"
            }
        }

        fun clamp(config: AiEndpointConfig): AiEndpointConfig = clampWithReport(config).config

        /** clamp + 返回被修正项的人类可读描述（调用方自行 log，保持本类纯函数可单测）。 */
        fun clampWithReport(config: AiEndpointConfig): AiClampResult {
            val reports = ArrayList<String>(8)
            val baseUrl = normalizeBaseUrl(config.baseUrl)
            if (baseUrl != config.baseUrl) reports += "baseUrl 归一化"
            val path = normalizePath(config.path)
            if (path != config.path) reports += "path 归一化"

            val connect = config.connectTimeoutMs.coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)
            if (connect != config.connectTimeoutMs) reports += "connectTimeoutMs ${config.connectTimeoutMs}->$connect"
            val read = config.readTimeoutMs.coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)
            if (read != config.readTimeoutMs) reports += "readTimeoutMs ${config.readTimeoutMs}->$read"
            val write = config.writeTimeoutMs.coerceIn(TIMEOUT_MIN_MS, TIMEOUT_MAX_MS)
            if (write != config.writeTimeoutMs) reports += "writeTimeoutMs ${config.writeTimeoutMs}->$write"

            val retries = config.maxRetries.coerceIn(RETRY_MIN, RETRY_MAX)
            if (retries != config.maxRetries) reports += "maxRetries ${config.maxRetries}->$retries"
            val retryBase = config.retryBaseDelayMs.coerceIn(RETRY_BASE_MIN_MS, RETRY_BASE_MAX_MS)
            if (retryBase != config.retryBaseDelayMs) reports += "retryBaseDelayMs ${config.retryBaseDelayMs}->$retryBase"

            val headerName = config.authHeaderName.trim()
            if (headerName != config.authHeaderName) reports += "authHeaderName 归一化"
            val scheme = if (headerName.isEmpty()) "" else config.authScheme.trim()

            return AiClampResult(
                config = config.copy(
                    baseUrl = baseUrl,
                    path = path,
                    connectTimeoutMs = connect,
                    readTimeoutMs = read,
                    writeTimeoutMs = write,
                    maxRetries = retries,
                    retryBaseDelayMs = retryBase,
                    authHeaderName = headerName,
                    authScheme = scheme,
                    model = config.model.trim(),
                ),
                reports = reports,
            )
        }
    }
}

/** clamp 结果：被修正的项以人类可读字符串返回。 */
data class AiClampResult(
    val config: AiEndpointConfig,
    val reports: List<String>,
)
