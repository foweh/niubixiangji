package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏（K3 / I16）：密钥掩码、敏感头判定、异常文本清洗。
 * 纯 JVM 测试。
 */
class AiLogRedactorTest {

    @Test
    fun `长度超过 8 的密钥保留前后少量字符`() {
        val masked = AiLogRedactor.maskSecret("sk-1234567890")
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.endsWith("90"))
        assertTrue(masked.contains(AiLogRedactor.MASK))
        assertFalse(masked.contains("1234567890"))
    }

    @Test
    fun `短密钥与空值`() {
        assertEquals(AiLogRedactor.MASK, AiLogRedactor.maskSecret("abc123"))
        assertEquals(AiLogRedactor.MASK, AiLogRedactor.maskSecret("12345678"))
        assertEquals("", AiLogRedactor.maskSecret(""))
        assertEquals("", AiLogRedactor.maskSecret("   "))
        // 带空格的真密钥先 trim 再判定
        assertEquals(AiLogRedactor.MASK, AiLogRedactor.maskSecret("  short  "))
    }

    @Test
    fun `敏感头判定覆盖常见写法`() {
        assertTrue(AiLogRedactor.isSensitiveHeader("Authorization"))
        assertTrue(AiLogRedactor.isSensitiveHeader("authorization"))
        assertTrue(AiLogRedactor.isSensitiveHeader("X-Api-Key"))
        assertTrue(AiLogRedactor.isSensitiveHeader("x-apikey"))
        assertTrue(AiLogRedactor.isSensitiveHeader("X-Auth-Token"))
        assertTrue(AiLogRedactor.isSensitiveHeader("Cookie"))
        assertFalse(AiLogRedactor.isSensitiveHeader("Content-Type"))
        assertFalse(AiLogRedactor.isSensitiveHeader("Accept"))
    }

    @Test
    fun `敏感头的值一律掩码`() {
        assertEquals("Authorization: ****", AiLogRedactor.maskHeader("Authorization", "Bearer sk-abcdef1234"))
        assertEquals("Content-Type: application/json", AiLogRedactor.maskHeader("Content-Type", "application/json"))
    }

    @Test
    fun `清洗异常文本中的 Bearer 与 sk 密钥`() {
        val scrubbed = AiLogRedactor.scrub("failed auth: Bearer sk-abcdef123456 for user")
        assertFalse(scrubbed.contains("sk-abcdef123456"))
        assertTrue(scrubbed.contains(AiLogRedactor.MASK))
    }

    @Test
    fun `清洗 key value 形式的密钥`() {
        val scrubbed = AiLogRedactor.scrub("""{"api_key":"sk-live-abcdef123456"}""")
        assertFalse(scrubbed.contains("sk-live-abcdef123456"))
        assertTrue(scrubbed.contains(AiLogRedactor.MASK))
    }

    @Test
    fun `清洗 Google API Key 形式`() {
        val scrubbed = AiLogRedactor.scrub("AIzaSyA1234567890abcdef")
        assertEquals(AiLogRedactor.MASK, scrubbed)
    }

    @Test
    fun `空文本与无密钥文本`() {
        assertEquals("", AiLogRedactor.scrub(null))
        assertEquals("", AiLogRedactor.scrub(""))
        assertEquals("connection reset", AiLogRedactor.scrub("connection reset"))
    }
}
