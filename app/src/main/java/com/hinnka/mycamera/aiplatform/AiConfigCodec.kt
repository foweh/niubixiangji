package com.hinnka.mycamera.aiplatform

/**
 * 配置编解码（**纯函数**，无 Context / 无 Android，可 JVM 单测）。
 *
 * 三层合成顺序（顺序很重要，决定密钥安全）：
 *  1. [defaults]：构建态默认（内置端点可作默认 baseUrl，但**默认不带任何密钥**）；
 *  2. [merge]：叠加用户覆盖（密钥以密文入库，读取时由调用方传入 `open` 解密）；
 *  3. [resolveBuiltInCredential]：仅当「用户未提供密钥」且「端点仍是内置端点」时，
 *     才下发 BuildConfig 内置密钥 —— 避免内置密钥被带到第三方端点（防泄漏）。
 *
 * DataStore 存储约定：每个字段一个 key，key = `[storageKey]`；值均为字符串；
 * **值为空串表示删除该覆盖项**（回落默认）。密钥字段的值必须是密文。
 */
object AiConfigCodec {

    const val KEY_ENABLED = "enabled"
    const val KEY_BASE_URL = "baseUrl"
    const val KEY_PATH = "path"
    const val KEY_API_KEY = "apiKey"
    const val KEY_AUTH_SCHEME = "authScheme"
    const val KEY_AUTH_HEADER_NAME = "authHeaderName"
    const val KEY_MODEL = "model"
    const val KEY_CONNECT_TIMEOUT = "connectTimeoutMs"
    const val KEY_READ_TIMEOUT = "readTimeoutMs"
    const val KEY_WRITE_TIMEOUT = "writeTimeoutMs"
    const val KEY_MAX_RETRIES = "maxRetries"
    const val KEY_RETRY_BASE_DELAY = "retryBaseDelayMs"
    const val KEY_ALLOW_CLEARTEXT = "allowCleartext"

    /** 全部可覆盖字段（顺序稳定，供设置页 / 自检 / 清理遍历）。 */
    val fields: List<String> = listOf(
        KEY_ENABLED,
        KEY_BASE_URL,
        KEY_PATH,
        KEY_API_KEY,
        KEY_AUTH_SCHEME,
        KEY_AUTH_HEADER_NAME,
        KEY_MODEL,
        KEY_CONNECT_TIMEOUT,
        KEY_READ_TIMEOUT,
        KEY_WRITE_TIMEOUT,
        KEY_MAX_RETRIES,
        KEY_RETRY_BASE_DELAY,
        KEY_ALLOW_CLEARTEXT,
    )

    /** DataStore 存储 key。 */
    fun storageKey(capability: AiCapability, field: String): String =
        capability.storagePrefix + "." + field

    /**
     * 构建态默认配置（= 审计报告 §3.6 默认端点表）。
     * 内置能力族默认指向 [builtInBaseUrl]，但**不带密钥**（密钥由 [resolveBuiltInCredential] 决定）。
     */
    fun defaults(capability: AiCapability, builtInBaseUrl: String = ""): AiEndpointConfig =
        AiEndpointConfig.clamp(
            AiEndpointConfig(
                capability = capability,
                userEnabled = true,
                baseUrl = if (capability.builtInBacked) {
                    AiEndpointConfig.normalizeBaseUrl(builtInBaseUrl)
                } else {
                    ""
                },
                path = capability.defaultPath,
                apiKey = "",
                authScheme = "Bearer",
                authHeaderName = "Authorization",
                model = capability.defaultModel,
                connectTimeoutMs = capability.defaultConnectTimeoutMs,
                readTimeoutMs = capability.defaultReadTimeoutMs,
                writeTimeoutMs = capability.defaultWriteTimeoutMs,
                maxRetries = AiCapability.DEFAULT_MAX_RETRIES,
                retryBaseDelayMs = AiCapability.DEFAULT_RETRY_BASE_DELAY_MS,
                allowCleartext = AiCapability.DEFAULT_ALLOW_CLEARTEXT,
            ),
        )

    /**
     * 叠加用户覆盖。
     * @param stored 以**字段名**为 key 的原始字符串（见 [fields]）
     * @param open   解密回调（密钥字段）；解密失败返回 null 时回落 base 值，绝不落明文
     */
    fun merge(
        base: AiEndpointConfig,
        stored: Map<String, String>,
        open: (String) -> String? = { it },
    ): AiEndpointConfig {
        if (stored.isEmpty()) return base

        fun str(field: String): String? = stored[field]
        fun bool(field: String): Boolean? = stored[field]?.toBooleanStrictOrNull()
        fun int(field: String): Int? = stored[field]?.toIntOrNull()
        fun long(field: String): Long? = stored[field]?.toLongOrNull()

        val storedKey = str(KEY_API_KEY)
        val apiKey = when {
            storedKey == null -> base.apiKey
            storedKey.isBlank() -> ""
            else -> open(storedKey) ?: base.apiKey
        }

        return AiEndpointConfig.clamp(
            base.copy(
                userEnabled = bool(KEY_ENABLED) ?: base.userEnabled,
                baseUrl = str(KEY_BASE_URL) ?: base.baseUrl,
                path = str(KEY_PATH) ?: base.path,
                apiKey = apiKey,
                authScheme = str(KEY_AUTH_SCHEME) ?: base.authScheme,
                authHeaderName = str(KEY_AUTH_HEADER_NAME) ?: base.authHeaderName,
                model = str(KEY_MODEL) ?: base.model,
                connectTimeoutMs = int(KEY_CONNECT_TIMEOUT) ?: base.connectTimeoutMs,
                readTimeoutMs = int(KEY_READ_TIMEOUT) ?: base.readTimeoutMs,
                writeTimeoutMs = int(KEY_WRITE_TIMEOUT) ?: base.writeTimeoutMs,
                maxRetries = int(KEY_MAX_RETRIES) ?: base.maxRetries,
                retryBaseDelayMs = long(KEY_RETRY_BASE_DELAY) ?: base.retryBaseDelayMs,
                allowCleartext = bool(KEY_ALLOW_CLEARTEXT) ?: base.allowCleartext,
            ),
        )
    }

    /**
     * 内置凭据下发（防泄漏关键步骤）：
     * 仅当能力属于内置族、用户未提供密钥、且当前端点仍等于内置端点时，才注入内置密钥。
     * 用户把端点改成任意第三方地址 → 内置密钥**不下发**（宁可判 MissingCredential 也不外泄）。
     */
    fun resolveBuiltInCredential(
        config: AiEndpointConfig,
        builtInBaseUrl: String,
        builtInApiKey: String,
    ): AiEndpointConfig {
        if (!config.capability.builtInBacked) return config
        if (config.apiKey.isNotBlank()) return config
        if (builtInApiKey.isBlank()) return config
        val builtIn = AiEndpointConfig.normalizeBaseUrl(builtInBaseUrl)
        if (builtIn.isEmpty()) return config
        if (!config.baseUrl.equals(builtIn, ignoreCase = true)) return config
        return config.copy(apiKey = builtInApiKey)
    }

    /** 应用补丁（null = 不改动）并 clamp。 */
    fun applyPatch(base: AiEndpointConfig, patch: AiConfigPatch): AiEndpointConfig =
        AiEndpointConfig.clamp(
            base.copy(
                userEnabled = patch.enabled ?: base.userEnabled,
                baseUrl = patch.baseUrl ?: base.baseUrl,
                path = patch.path ?: base.path,
                apiKey = patch.apiKey ?: base.apiKey,
                authScheme = patch.authScheme ?: base.authScheme,
                authHeaderName = patch.authHeaderName ?: base.authHeaderName,
                model = patch.model ?: base.model,
                connectTimeoutMs = patch.connectTimeoutMs ?: base.connectTimeoutMs,
                readTimeoutMs = patch.readTimeoutMs ?: base.readTimeoutMs,
                writeTimeoutMs = patch.writeTimeoutMs ?: base.writeTimeoutMs,
                maxRetries = patch.maxRetries ?: base.maxRetries,
                retryBaseDelayMs = patch.retryBaseDelayMs ?: base.retryBaseDelayMs,
                allowCleartext = patch.allowCleartext ?: base.allowCleartext,
            ),
        )

    /**
     * 补丁 → 待写入条目（字段名 → 值；空串 = 删除该覆盖项）。
     *
     * @param seal 密钥加密回调；返回 null 表示加密不可用 → **该密钥不落盘**（禁止明文入库）。
     *             调用方可通过「apiKey 非空但结果中没有 [KEY_API_KEY]」判定发生了拒写。
     */
    fun patchToEntries(
        capability: AiCapability,
        patch: AiConfigPatch,
        seal: (String) -> String? = { null },
    ): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        patch.enabled?.let { entries[KEY_ENABLED] = it.toString() }
        patch.baseUrl?.let { entries[KEY_BASE_URL] = AiEndpointConfig.normalizeBaseUrl(it) }
        patch.path?.let { entries[KEY_PATH] = AiEndpointConfig.normalizePath(it) }
        patch.apiKey?.let { plain ->
            if (plain.isBlank()) {
                entries[KEY_API_KEY] = ""
            } else {
                seal(plain)?.let { entries[KEY_API_KEY] = it }
            }
        }
        patch.authScheme?.let { entries[KEY_AUTH_SCHEME] = it.trim() }
        patch.authHeaderName?.let { entries[KEY_AUTH_HEADER_NAME] = it.trim() }
        patch.model?.let { entries[KEY_MODEL] = it.trim() }
        patch.connectTimeoutMs?.let { entries[KEY_CONNECT_TIMEOUT] = it.toString() }
        patch.readTimeoutMs?.let { entries[KEY_READ_TIMEOUT] = it.toString() }
        patch.writeTimeoutMs?.let { entries[KEY_WRITE_TIMEOUT] = it.toString() }
        patch.maxRetries?.let { entries[KEY_MAX_RETRIES] = it.toString() }
        patch.retryBaseDelayMs?.let { entries[KEY_RETRY_BASE_DELAY] = it.toString() }
        patch.allowCleartext?.let { entries[KEY_ALLOW_CLEARTEXT] = it.toString() }
        return entries
    }

    /** 从存储条目反推「被拒写」的密钥（apiKey 非空但未写入）。 */
    fun rejectedSecret(patch: AiConfigPatch, entries: Map<String, String>): Boolean =
        !patch.apiKey.isNullOrBlank() && entries[KEY_API_KEY].isNullOrBlank()
}
