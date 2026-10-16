package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置编解码：默认值解析 / 覆盖合并 / 密钥密文（K2）/ 内置凭据防泄漏 / 拒写不落明文。
 * 纯 JVM 测试。
 */
class AiConfigCodecTest {

    private val builtInBase = "https://camera-api.hinnka.me/v1"
    private val builtInKey = "builtin-secret-key-1234"

    // ---------------------------------------------------------------- 存储 key

    @Test
    fun `存储 key 按能力前缀区分且覆盖全部字段`() {
        assertEquals("cap_server_enhance.baseUrl", AiConfigCodec.storageKey(AiCapability.SERVER_ENHANCE, AiConfigCodec.KEY_BASE_URL))
        assertEquals("cap_white_balance.apiKey", AiConfigCodec.storageKey(AiCapability.WHITE_BALANCE, AiConfigCodec.KEY_API_KEY))
        // 13 个可覆盖字段全部有 key，且互不重复
        val keys = AiConfigCodec.fields.map { AiConfigCodec.storageKey(AiCapability.SCENE_RECOGNITION, it) }
        assertEquals(AiConfigCodec.fields.size, keys.toSet().size)
    }

    @Test
    fun `能力前缀唯一`() {
        val prefixes = AiCapability.entries.map { it.storagePrefix }
        assertEquals(prefixes.size, prefixes.toSet().size)
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun `空覆盖返回默认值`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        assertEquals(base, AiConfigCodec.merge(base, emptyMap()))
    }

    @Test
    fun `覆盖项逐个生效且越界值被 clamp`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val merged = AiConfigCodec.merge(
            base = base,
            stored = mapOf(
                AiConfigCodec.KEY_ENABLED to "false",
                AiConfigCodec.KEY_BASE_URL to "https://self-hosted.example.com/v1/",
                AiConfigCodec.KEY_PATH to "v1/enhance",
                AiConfigCodec.KEY_MODEL to "my-model",
                AiConfigCodec.KEY_AUTH_SCHEME to "ApiKey",
                AiConfigCodec.KEY_AUTH_HEADER_NAME to "x-api-key",
                AiConfigCodec.KEY_CONNECT_TIMEOUT to "50",
                AiConfigCodec.KEY_READ_TIMEOUT to "99999999",
                AiConfigCodec.KEY_MAX_RETRIES to "9",
                AiConfigCodec.KEY_RETRY_BASE_DELAY to "77",
                AiConfigCodec.KEY_ALLOW_CLEARTEXT to "true",
            ),
        )

        assertFalse(merged.userEnabled)
        assertEquals("https://self-hosted.example.com/v1", merged.baseUrl)
        assertEquals("/v1/enhance", merged.path)
        assertEquals("my-model", merged.model)
        assertEquals("ApiKey", merged.authScheme)
        assertEquals("x-api-key", merged.authHeaderName)
        assertEquals(AiEndpointConfig.TIMEOUT_MIN_MS, merged.connectTimeoutMs)
        assertEquals(AiEndpointConfig.TIMEOUT_MAX_MS, merged.readTimeoutMs)
        assertEquals(AiEndpointConfig.RETRY_MAX, merged.maxRetries)
        assertEquals(AiEndpointConfig.RETRY_BASE_MIN_MS, merged.retryBaseDelayMs)
        assertTrue(merged.allowCleartext)
    }

    @Test
    fun `非法数值覆盖回落默认值而不是崩溃`() {
        val base = AiConfigCodec.defaults(AiCapability.PHOTO_EVALUATION, builtInBaseUrl = builtInBase)
        val merged = AiConfigCodec.merge(
            base = base,
            stored = mapOf(
                AiConfigCodec.KEY_CONNECT_TIMEOUT to "abc",
                AiConfigCodec.KEY_MAX_RETRIES to "",
                AiConfigCodec.KEY_ENABLED to "yes-please",
            ),
        )
        assertEquals(base.connectTimeoutMs, merged.connectTimeoutMs)
        assertEquals(base.maxRetries, merged.maxRetries)
        assertEquals(base.userEnabled, merged.userEnabled)
    }

    @Test
    fun `密钥以密文存储且解密后可用`() {
        val base = AiConfigCodec.defaults(AiCapability.PHOTO_EVALUATION, builtInBaseUrl = builtInBase)
        val merged = AiConfigCodec.merge(
            base = base,
            stored = mapOf(AiConfigCodec.KEY_API_KEY to "keystore:v1:ENCRYPTED_BLOB"),
            open = { sealed -> if (sealed.startsWith("keystore:v1:")) "sk-plain-key" else null },
        )
        assertEquals("sk-plain-key", merged.apiKey)
        assertEquals("Bearer sk-plain-key", merged.authHeader()?.second)
    }

    @Test
    fun `解密失败回落默认值且不落明文`() {
        val base = AiConfigCodec.defaults(AiCapability.PHOTO_EVALUATION, builtInBaseUrl = builtInBase)
        val merged = AiConfigCodec.merge(
            base = base.copy(apiKey = "kept"),
            stored = mapOf(AiConfigCodec.KEY_API_KEY to "corrupted"),
            open = { null },
        )
        assertEquals("kept", merged.apiKey)
    }

    @Test
    fun `空串密钥覆盖表示清除`() {
        val base = AiConfigCodec.defaults(AiCapability.PHOTO_EVALUATION, builtInBaseUrl = builtInBase)
        val merged = AiConfigCodec.merge(
            base = base.copy(apiKey = "old-key"),
            stored = mapOf(AiConfigCodec.KEY_API_KEY to ""),
        )
        assertEquals("", merged.apiKey)
        assertNull(merged.authHeader())
    }

    // ---------------------------------------------------------------- 内置凭据（K1）

    @Test
    fun `内置端点下未提供密钥时下发内置密钥`() {
        val config = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val resolved = AiConfigCodec.resolveBuiltInCredential(config, builtInBase, builtInKey)
        assertEquals(builtInKey, resolved.apiKey)
        assertEquals("Bearer $builtInKey", resolved.authHeader()?.second)
    }

    @Test
    fun `用户改成第三方端点后不下发内置密钥`() {
        val config = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
            .copy(baseUrl = "https://evil.example.com/v1")
        val resolved = AiConfigCodec.resolveBuiltInCredential(config, builtInBase, builtInKey)
        assertEquals("", resolved.apiKey)
        assertNull(resolved.authHeader())
    }

    @Test
    fun `用户自带密钥优先于内置密钥`() {
        val config = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
            .copy(apiKey = "user-key")
        val resolved = AiConfigCodec.resolveBuiltInCredential(config, builtInBase, builtInKey)
        assertEquals("user-key", resolved.apiKey)
    }

    @Test
    fun `非内置族能力永不注入内置密钥`() {
        val config = AiConfigCodec.defaults(AiCapability.SERVER_ENHANCE, builtInBaseUrl = builtInBase)
            .copy(baseUrl = builtInBase)
        val resolved = AiConfigCodec.resolveBuiltInCredential(config, builtInBase, builtInKey)
        assertEquals("", resolved.apiKey)
    }

    @Test
    fun `构建期未注入内置密钥时不产生凭据`() {
        val config = AiConfigCodec.defaults(AiCapability.MODEL_LIST, builtInBaseUrl = builtInBase)
        val resolved = AiConfigCodec.resolveBuiltInCredential(config, builtInBase, "")
        assertEquals("", resolved.apiKey)
    }

    // ---------------------------------------------------------------- applyPatch

    @Test
    fun `applyPatch 只改非空字段并 clamp`() {
        val base = AiConfigCodec.defaults(AiCapability.SERVER_ENHANCE, builtInBaseUrl = builtInBase)
        val patched = AiConfigCodec.applyPatch(
            base,
            AiConfigPatch(baseUrl = "https://api.example.com/", maxRetries = 100, apiKey = "k"),
        )
        assertEquals("https://api.example.com", patched.baseUrl)
        assertEquals(AiEndpointConfig.RETRY_MAX, patched.maxRetries)
        assertEquals("k", patched.apiKey)
        assertEquals(base.path, patched.path)
        assertEquals(base.model, patched.model)
    }

    // ---------------------------------------------------------------- patchToEntries（K2）

    @Test
    fun `密钥经 seal 加密后才写入条目`() {
        val entries = AiConfigCodec.patchToEntries(
            capability = AiCapability.PHOTO_EVALUATION,
            patch = AiConfigPatch(apiKey = "sk-plain", baseUrl = "https://a.com/"),
            seal = { plain -> "keystore:v1:$plain" },
        )
        assertEquals("keystore:v1:sk-plain", entries[AiConfigCodec.KEY_API_KEY])
        assertEquals("https://a.com", entries[AiConfigCodec.KEY_BASE_URL])
        assertFalse(entries.values.any { it == "sk-plain" })
        assertFalse(AiConfigCodec.rejectedSecret(AiConfigPatch(apiKey = "sk-plain"), entries))
    }

    @Test
    fun `seal 不可用时密钥不落盘并标记拒写`() {
        val patch = AiConfigPatch(apiKey = "sk-plain", model = "m")
        val entries = AiConfigCodec.patchToEntries(AiCapability.MODEL_LIST, patch, seal = { null })

        assertNull(entries[AiConfigCodec.KEY_API_KEY])
        assertFalse(entries.values.any { it == "sk-plain" })
        assertEquals("m", entries[AiConfigCodec.KEY_MODEL])
        assertTrue(AiConfigCodec.rejectedSecret(patch, entries))
    }

    @Test
    fun `空白密钥写入空串表示清除覆盖`() {
        val entries = AiConfigCodec.patchToEntries(
            capability = AiCapability.MODEL_LIST,
            patch = AiConfigPatch(apiKey = "  "),
            seal = { "should-not-be-used" },
        )
        assertEquals("", entries[AiConfigCodec.KEY_API_KEY])
        assertFalse(AiConfigCodec.rejectedSecret(AiConfigPatch(apiKey = "  "), entries))
    }

    @Test
    fun `补丁字段与存储字段一一对应`() {
        val entries = AiConfigCodec.patchToEntries(
            capability = AiCapability.SERVER_ENHANCE,
            patch = AiConfigPatch(
                enabled = true,
                baseUrl = "https://a.com",
                path = "/p",
                authScheme = "Bearer",
                authHeaderName = "Authorization",
                model = "m",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000,
                writeTimeoutMs = 3_000,
                maxRetries = 1,
                retryBaseDelayMs = 200L,
                allowCleartext = false,
            ),
            seal = { null },
        )
        // 12 个非密钥字段 + 无密钥 → 共 12 项
        assertEquals(12, entries.size)
        AiConfigCodec.fields.forEach { field ->
            if (field != AiConfigCodec.KEY_API_KEY) {
                assertTrue("缺少字段 $field", entries.containsKey(field))
            }
        }
    }
}
