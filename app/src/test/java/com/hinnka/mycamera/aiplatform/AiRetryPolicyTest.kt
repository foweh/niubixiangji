package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重试策略：可重试归因、次数边界、指数退避 + 抖动（K8 / M2 / M6）。
 * 纯 JVM 测试（random 注入以便确定化）。
 */
class AiRetryPolicyTest {

    // ---------------------------------------------------------------- 可重试状态码

    @Test
    fun `仅 408 429 与 5xx 可重试`() {
        assertTrue(AiRetryPolicy.isRetryableHttpCode(408))
        assertTrue(AiRetryPolicy.isRetryableHttpCode(429))
        assertTrue(AiRetryPolicy.isRetryableHttpCode(500))
        assertTrue(AiRetryPolicy.isRetryableHttpCode(502))
        assertTrue(AiRetryPolicy.isRetryableHttpCode(599))
        // 4xx 业务错误重试无意义（401 缺凭据、404 端点错、422 参数错）
        assertFalse(AiRetryPolicy.isRetryableHttpCode(400))
        assertFalse(AiRetryPolicy.isRetryableHttpCode(401))
        assertFalse(AiRetryPolicy.isRetryableHttpCode(403))
        assertFalse(AiRetryPolicy.isRetryableHttpCode(404))
        assertFalse(AiRetryPolicy.isRetryableHttpCode(422))
    }

    // ---------------------------------------------------------------- 可重试归因

    @Test
    fun `网络类失败可重试其余不可`() {
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 0, 2))
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Timeout("read"), 0, 2))
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Http(503), 0, 2))
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Http(429), 0, 2))

        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Http(404), 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Parse("bad json"), 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Disabled, 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.MissingCredential, 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.CleartextBlocked("http://a"), 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Cancelled, 0, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Unknown(IllegalStateException()), 0, 2))
    }

    // ---------------------------------------------------------------- 次数边界

    @Test
    fun `重试次数边界`() {
        // 0 次重试：首次失败即终止
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 0, 0))
        // 2 次重试：attempt = 0, 1 允许；attempt = 2 已达上限
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 0, 2))
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 1, 2))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 2, 2))
        // 负数 attempt 不重试
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, -1, 2))
    }

    @Test
    fun `重试次数超过上限被 clamp 到 5`() {
        assertTrue(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 4, 99))
        assertFalse(AiRetryPolicy.shouldRetry(AiFailureReason.Offline, 5, 99))
    }

    // ---------------------------------------------------------------- 退避

    @Test
    fun `退避按指数增长且无抖动时可确定化`() {
        assertEquals(500L, AiRetryPolicy.backoffDelayMs(500L, 0, jitterRatio = 0.0, random = { 0.5 }))
        assertEquals(1_000L, AiRetryPolicy.backoffDelayMs(500L, 1, jitterRatio = 0.0, random = { 0.5 }))
        assertEquals(2_000L, AiRetryPolicy.backoffDelayMs(500L, 2, jitterRatio = 0.0, random = { 0.5 }))
        assertEquals(4_000L, AiRetryPolicy.backoffDelayMs(500L, 3, jitterRatio = 0.0, random = { 0.5 }))
    }

    @Test
    fun `退避基数被 clamp 到合法区间`() {
        assertEquals(100L, AiRetryPolicy.backoffDelayMs(1L, 0, jitterRatio = 0.0, random = { 0.5 }))
        assertEquals(10_000L, AiRetryPolicy.backoffDelayMs(999_999L, 0, jitterRatio = 0.0, random = { 0.5 }))
    }

    @Test
    fun `退避有 30 秒上限`() {
        // 10000 * 2^5 = 320000 → 封顶 30000
        assertEquals(AiRetryPolicy.MAX_DELAY_MS.toLong(), AiRetryPolicy.backoffDelayMs(10_000L, 5, 0.0, { 0.5 }))
        // attempt 极大也不溢出
        assertEquals(AiRetryPolicy.MAX_DELAY_MS.toLong(), AiRetryPolicy.backoffDelayMs(10_000L, 999, 0.0, { 0.5 }))
    }

    @Test
    fun `抖动落在正负比例区间内`() {
        val base = 1_000L
        val low = AiRetryPolicy.backoffDelayMs(base, 0, jitterRatio = 0.25, random = { 0.0 })
        val high = AiRetryPolicy.backoffDelayMs(base, 0, jitterRatio = 0.25, random = { 1.0 })
        assertEquals(750L, low)
        assertEquals(1_250L, high)
        // 抖动比例越界被 clamp 到 1.0：1000 + 1000 = 2000
        assertEquals(
            2_000L,
            AiRetryPolicy.backoffDelayMs(base, 0, jitterRatio = 9.0, random = { 1.0 }),
        )
    }

    @Test
    fun `延迟永不为负`() {
        assertTrue(AiRetryPolicy.backoffDelayMs(100L, 0, jitterRatio = 1.0, random = { 0.0 }) >= 0L)
    }
}
