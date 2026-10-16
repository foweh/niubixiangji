package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点配置：默认值 / 校验 / 鉴权头 / clamp 边界 / 脱敏描述。
 * 纯 JVM 测试（不依赖 Android 框架）。
 */
class AiEndpointConfigTest {

    private val builtInBase = "https://camera-api.hinnka.me/v1"

    // ---------------------------------------------------------------- 默认值

    @Test
    fun `非内置能力默认关闭且空端点`() {
        val config = AiConfigCodec.defaults(AiCapability.SERVER_ENHANCE, builtInBaseUrl = builtInBase)

        assertEquals("", config.baseUrl)
        assertEquals("/enhance", config.path)
        assertEquals("basicsr-swinir", config.model)
        assertFalse(config.isConfigured)
        assertFalse(config.isEnabled)
        // 默认关闭 → 归因必须是 Disabled（而不是 Unknown），便于 UI 给出正确提示
        assertEquals(AiFailureReason.Disabled, config.validate())
    }

    @Test
    fun `内置能力默认指向内置端点但不带密钥`() {
        val config = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = "$builtInBase/")

        assertEquals(builtInBase, config.baseUrl) // 归一化去掉尾斜杠
        assertEquals("/chat/completions", config.path)
        assertEquals("gemini-3.5-flash", config.model)
        assertTrue(config.isEnabled)
        assertTrue(config.isHttps)
        assertNull(config.validate())
        // K1：默认配置不含任何明文密钥
        assertEquals("", config.apiKey)
        assertNull(config.authHeader())
    }

    @Test
    fun `模型列表能力无默认模型名`() {
        val config = AiConfigCodec.defaults(AiCapability.MODEL_LIST, builtInBaseUrl = builtInBase)
        assertEquals("", config.model)
        assertEquals("/models", config.path)
    }

    // ---------------------------------------------------------------- 鉴权头

    @Test
    fun `鉴权头按 scheme 与自定义头名构造`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val withKey = base.copy(apiKey = "sk-test-key")

        assertEquals("Authorization" to "Bearer sk-test-key", withKey.authHeader())
        assertEquals(
            "x-api-key" to "sk-test-key",
            withKey.copy(authScheme = "", authHeaderName = "x-api-key").authHeader(),
        )
        assertEquals(
            "X-Goog-Api-Key" to "ApiKey sk-test-key",
            withKey.copy(authScheme = "ApiKey", authHeaderName = "X-Goog-Api-Key").authHeader(),
        )
        // 头名清空 = 显式关闭鉴权
        assertNull(withKey.copy(authHeaderName = "").authHeader())
    }

    @Test
    fun `无密钥时不构造鉴权头`() {
        val config = AiConfigCodec.defaults(AiCapability.PHOTO_EVALUATION, builtInBaseUrl = builtInBase)
        assertTrue(config.requiresAuth)
        assertFalse(config.hasCredential)
        assertFalse(config.isAuthenticated)
        assertNull(config.authHeader())
    }

    // ---------------------------------------------------------------- 校验（K4 / M4）

    @Test
    fun `明文 http 默认被拒并给出可操作归因`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val cleartext = base.copy(baseUrl = "http://192.168.1.10:8000")

        assertTrue(cleartext.isCleartextHttp)
        assertTrue(cleartext.isCleartextBlocked)
        assertTrue(cleartext.validate() is AiFailureReason.CleartextBlocked)
        // 用户显式放行后（配合 networkSecurityConfig）才允许
        assertNull(cleartext.copy(allowCleartext = true).validate())
    }

    @Test
    fun `非法 scheme 归因 Parse`() {
        val base = AiConfigCodec.defaults(AiCapability.SERVER_ENHANCE)
        val broken = base.copy(baseUrl = "192.168.1.10:8000")
        assertFalse(broken.isSchemeSupported)
        assertTrue(broken.validate() is AiFailureReason.Parse)
    }

    @Test
    fun `地址与路径拼接`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        assertEquals("$builtInBase/chat/completions", base.resolvedUrl())
        assertEquals("$builtInBase/custom/path", base.resolvedUrl("custom/path"))
        assertEquals("$builtInBase/chat/completions", base.resolvedUrl(""))
    }

    // ---------------------------------------------------------------- clamp 边界

    @Test
    fun `clamp 修正越界超时与重试次数`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val clamped = AiEndpointConfig.clamp(
            base.copy(
                baseUrl = " $builtInBase/ ",
                path = "chat/completions",
                connectTimeoutMs = 10,
                readTimeoutMs = 999_999,
                maxRetries = 99,
                retryBaseDelayMs = 1L,
            ),
        )

        assertEquals(builtInBase, clamped.baseUrl)
        assertEquals("/chat/completions", clamped.path)
        assertEquals(AiEndpointConfig.TIMEOUT_MIN_MS, clamped.connectTimeoutMs)
        assertEquals(AiEndpointConfig.TIMEOUT_MAX_MS, clamped.readTimeoutMs)
        assertEquals(AiEndpointConfig.RETRY_MAX, clamped.maxRetries)
        assertEquals(AiEndpointConfig.RETRY_BASE_MIN_MS, clamped.retryBaseDelayMs)
        // 未越界字段保持不变
        assertEquals(base.writeTimeoutMs, clamped.writeTimeoutMs)
    }

    @Test
    fun `clamp 归零重试与负超时`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val clamped = AiEndpointConfig.clamp(
            base.copy(connectTimeoutMs = -5, maxRetries = -3, retryBaseDelayMs = 0L),
        )
        assertEquals(AiEndpointConfig.TIMEOUT_MIN_MS, clamped.connectTimeoutMs)
        assertEquals(AiEndpointConfig.RETRY_MIN, clamped.maxRetries)
        assertEquals(AiEndpointConfig.RETRY_BASE_MIN_MS, clamped.retryBaseDelayMs)
    }

    @Test
    fun `clampWithReport 汇报被修正项`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val result = AiEndpointConfig.clampWithReport(base.copy(maxRetries = 42))
        assertEquals(AiEndpointConfig.RETRY_MAX, result.config.maxRetries)
        assertTrue(result.reports.isNotEmpty())
    }

    // ---------------------------------------------------------------- 脱敏

    @Test
    fun `describe 永不输出完整密钥`() {
        val base = AiConfigCodec.defaults(AiCapability.LUT_RECIPE, builtInBaseUrl = builtInBase)
        val described = base.copy(apiKey = "sk-1234567890abcdef").describe()

        assertFalse(described.contains("1234567890abcdef"))
        assertTrue(described.contains(AiLogRedactor.MASK))
        assertTrue(described.contains("chat/completions"))
    }

    @Test
    fun `关闭鉴权时 describe 标注 none`() {
        val base = AiConfigCodec.defaults(AiCapability.MODEL_LIST, builtInBaseUrl = builtInBase)
        assertNotNull(base.describe())
        assertTrue(base.copy(authHeaderName = "").describe().contains("auth=none"))
    }
}
