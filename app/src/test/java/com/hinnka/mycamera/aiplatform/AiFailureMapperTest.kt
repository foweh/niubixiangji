package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

/**
 * 失败归因映射（K12 崩溃面覆盖）：HTTP 状态、DNS/连接、超时、TLS、明文被拒、取消、未知。
 * 同时校验用户提示文案契约。
 */
class AiFailureMapperTest {

    @Test
    fun `HTTP 状态码映射并脱敏 detail`() {
        val reason = AiFailureMapper.fromHttpCode(401, "invalid api_key=sk-live-abcdef123456")
        assertTrue(reason is AiFailureReason.Http)
        assertEquals(401, (reason as AiFailureReason.Http).code)
        assertFalse(reason.detail.contains("sk-live-abcdef123456"))
        assertTrue(reason.detail.contains(AiLogRedactor.MASK))

        assertEquals(429, (AiFailureMapper.fromHttpCode(429) as AiFailureReason.Http).code)
        assertEquals(503, (AiFailureMapper.fromHttpCode(503) as AiFailureReason.Http).code)
        assertEquals("", (AiFailureMapper.fromHttpCode(500) as AiFailureReason.Http).detail)
    }

    @Test
    fun `网络类异常归因`() {
        assertTrue(AiFailureMapper.fromThrowable(UnknownHostException("api.example.com")) is AiFailureReason.Offline)
        assertTrue(AiFailureMapper.fromThrowable(SocketException("broken pipe")) is AiFailureReason.Offline)
        assertTrue(AiFailureMapper.fromThrowable(IOException("network is unreachable")) is AiFailureReason.Offline)
        assertTrue(AiFailureMapper.fromThrowable(IOException("failed to connect to /10.0.0.1")) is AiFailureReason.Offline)
        assertTrue(AiFailureMapper.fromThrowable(IOException("unable to resolve host api.example.com")) is AiFailureReason.Offline)
    }

    @Test
    fun `超时类异常带阶段`() {
        val connect = AiFailureMapper.fromThrowable(SocketTimeoutException("connect timed out"), AiFailureMapper.PHASE_CONNECT)
        assertTrue(connect is AiFailureReason.Timeout)
        assertEquals(AiFailureMapper.PHASE_CONNECT, (connect as AiFailureReason.Timeout).phase)

        val read = AiFailureMapper.fromThrowable(InterruptedIOException("timeout"), AiFailureMapper.PHASE_READ)
        assertEquals(AiFailureMapper.PHASE_READ, (read as AiFailureReason.Timeout).phase)

        val messageTimeout = AiFailureMapper.fromThrowable(IOException("Read timed out"), AiFailureMapper.PHASE_READ)
        assertTrue(messageTimeout is AiFailureReason.Timeout)
    }

    @Test
    fun `TLS 与明文被拒归因`() {
        val tls = AiFailureMapper.fromThrowable(SSLHandshakeException("certificate verify failed"))
        assertTrue(tls is AiFailureReason.Tls)

        val cleartext = AiFailureMapper.fromThrowable(
            IOException("Cleartext HTTP traffic to 192.168.1.10 not permitted"),
            AiFailureMapper.PHASE_CALL,
            "http://192.168.1.10:8000",
        )
        assertTrue(cleartext is AiFailureReason.CleartextBlocked)
        assertEquals("http://192.168.1.10:8000", (cleartext as AiFailureReason.CleartextBlocked).baseUrl)
    }

    @Test
    fun `取消与未知异常`() {
        assertTrue(AiFailureMapper.fromThrowable(CancellationException("cancelled")) is AiFailureReason.Cancelled)
        assertTrue(AiFailureMapper.fromThrowable(IllegalStateException("boom")) is AiFailureReason.Unknown)
        assertTrue(AiFailureMapper.fromThrowable(IOException("something odd")) is AiFailureReason.Unknown)
    }

    @Test
    fun `失败原因都有用户可读文案与资源 key`() {
        val reasons = listOf(
            AiFailureReason.Disabled,
            AiFailureReason.MissingCredential,
            AiFailureReason.Offline,
            AiFailureReason.Timeout("read"),
            AiFailureReason.Http(401),
            AiFailureReason.Http(429),
            AiFailureReason.Http(503),
            AiFailureReason.Http(418),
            AiFailureReason.Parse("bad json"),
            AiFailureReason.CleartextBlocked("http://a"),
            AiFailureReason.Tls("handshake"),
            AiFailureReason.Cancelled,
            AiFailureReason.Unknown(IllegalStateException()),
        )
        val keys = reasons.map { AiNotices.stringKey(it) }
        assertEquals(reasons.size, keys.toSet().size)
        keys.forEach { key -> assertTrue("key 必须是字符串资源名：$key", key.startsWith("ai_error_")) }
        reasons.forEach { reason -> assertTrue(AiNotices.describe(reason).isNotBlank()) }
    }

    @Test
    fun `降级提示区分是否用了本地兜底`() {
        val withFallback = AiNotices.fallbackNotice(AiFailureReason.Offline, true)
        val withoutFallback = AiNotices.fallbackNotice(AiFailureReason.Offline, false)
        assertTrue(withFallback.isNotBlank())
        assertTrue(withoutFallback.isNotBlank())
        assertTrue(withFallback != withoutFallback)
    }

    @Test
    fun `结果类型扩展函数`() {
        val success: AiCallResult<Int> = AiCallResult.Success(7)
        val failure: AiCallResult<Int> = AiCallResult.Failure(AiFailureReason.Offline, retried = 2)

        assertTrue(success.isSuccess)
        assertFalse(success.isFailure)
        assertEquals(7, success.valueOrNull() ?: -1)
        assertEquals(0, success.retriedCount)

        assertEquals(2, failure.retriedCount)
        assertEquals(AiFailureReason.Offline, failure.reasonOrNull())
        assertNull(failure.valueOrNull())
        assertTrue(failure.mapResult { it + 1 }.isFailure)
        assertEquals(8, success.mapResult { it + 1 }.valueOrNull() ?: -1)
    }
}
