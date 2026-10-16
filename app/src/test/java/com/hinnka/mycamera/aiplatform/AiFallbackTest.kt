package com.hinnka.mycamera.aiplatform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 测试替身：只用于驱动 [executeWithFallback]，业务方法一律不应被调用。 */
private class FakeAiHttpClient : AiHttpClient {
    override suspend fun <T> executeJson(capability: AiCapability, request: AiRequest, parse: (String) -> T): AiCallResult<T> =
        throw UnsupportedOperationException("未在本测试范围")

    override suspend fun <T> executeMultipart(
        capability: AiCapability,
        request: AiMultipartRequest,
        parse: (String) -> T,
    ): AiCallResult<T> = throw UnsupportedOperationException("未在本测试范围")

    override suspend fun <T> executeRaw(
        capability: AiCapability,
        request: AiRawRequest,
        parse: (AiRawResponse) -> T,
    ): AiCallResult<T> = throw UnsupportedOperationException("未在本测试范围")

    override suspend fun downloadBinary(
        capability: AiCapability,
        request: AiRawRequest,
        expectedContentTypes: Set<String>,
        minBytes: Long,
        maxBytes: Long,
    ): AiCallResult<ByteArray> = throw UnsupportedOperationException("未在本测试范围")

    override suspend fun downloadToFile(
        capability: AiCapability,
        request: AiRawRequest,
        destFile: File,
        expectedContentTypes: Set<String>,
        minBytes: Long,
        maxBytes: Long,
    ): AiCallResult<File> = throw UnsupportedOperationException("未在本测试范围")
}

/**
 * 回退分支（M8 / K5 第 7 环）：成功直返、失败降级本地、无兜底返回 null、
 * 兜底自身失败不崩、通知回调抛异常不崩、取消必须继续传播。
 */
class AiFallbackTest {

    private val client = FakeAiHttpClient()

    @Test
    fun `远端成功时不触发本地兜底`() = runBlocking {
        var fallbackUsed = false
        var notice: Pair<AiFailureReason, Boolean>? = null

        val value = client.executeWithFallback(
            capability = AiCapability.LUT_RECOMMEND,
            remote = { AiCallResult.Success(listOf("film_portra_400")) },
            localFallback = {
                fallbackUsed = true
                emptyList()
            },
            onNotice = { reason, used -> notice = reason to used },
        )

        assertEquals(listOf("film_portra_400"), value)
        assertFalse(fallbackUsed)
        assertNull(notice)
    }

    @Test
    fun `远端失败降级本地并把失败归因通知 UI`() = runBlocking {
        var notice: Pair<AiFailureReason, Boolean>? = null

        val value = client.executeWithFallback(
            capability = AiCapability.SCENE_RECOGNITION,
            remote = { AiCallResult.Failure(AiFailureReason.Offline) },
            localFallback = { "LANDSCAPE" },
            onNotice = { reason, used -> notice = reason to used },
        )

        assertEquals("LANDSCAPE", value)
        assertEquals(AiFailureReason.Offline, notice?.first)
        assertTrue(notice?.second == true)
    }

    @Test
    fun `无本地兜底时返回 null 并标注未降级`() = runBlocking {
        var notice: Pair<AiFailureReason, Boolean>? = null

        val value = client.executeWithFallback<String>(
            capability = AiCapability.PHOTO_EVALUATION,
            remote = { AiCallResult.Failure(AiFailureReason.Http(503)) },
            localFallback = null,
            onNotice = { reason, used -> notice = reason to used },
        )

        assertNull(value)
        assertEquals(AiFailureReason.Http(503), notice?.first)
        assertTrue(notice?.second == false)
    }

    @Test
    fun `本地兜底自身抛异常也不崩`() = runBlocking {
        var notice: Pair<AiFailureReason, Boolean>? = null

        val value = client.executeWithFallback<String>(
            capability = AiCapability.WHITE_BALANCE,
            remote = { AiCallResult.Failure(AiFailureReason.Timeout("read")) },
            localFallback = { throw IllegalStateException("本地估计失败") },
            onNotice = { reason, used -> notice = reason to used },
        )

        assertNull(value)
        assertEquals(true, notice?.first is AiFailureReason.Timeout)
        assertTrue(notice?.second == false)
    }

    @Test
    fun `远端回调直接抛异常时被归因并降级`() = runBlocking {
        var notice: AiFailureReason? = null

        val value = client.executeWithFallback(
            capability = AiCapability.LUT_RECIPE,
            remote = { throw IllegalStateException("unexpected") },
            localFallback = { "local-recipe" },
            onNotice = { reason, _ -> notice = reason },
        )

        assertEquals("local-recipe", value)
        assertTrue(notice is AiFailureReason.Unknown)
    }

    @Test
    fun `通知回调抛异常不影响返回`() = runBlocking {
        val value = client.executeWithFallback(
            capability = AiCapability.MODEL_LIST,
            remote = { AiCallResult.Failure(AiFailureReason.Disabled) },
            localFallback = { emptyList<String>() },
            onNotice = { _, _ -> throw IllegalStateException("通知失败") },
        )

        assertEquals(emptyList<String>(), value)
    }

    @Test
    fun `取消必须继续向上传播`() = runBlocking {
        try {
            client.executeWithFallback<String>(
                capability = AiCapability.PHOTO_EVALUATION,
                remote = { throw CancellationException("cancelled") },
                localFallback = { "should-not-happen" },
            )
            fail("CancellationException 必须向上抛出")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun `runAiSafely 把异常收敛为归因`() = runBlocking {
        val ok = runAiSafely { 42 }
        assertEquals(42, ok.valueOrNull() ?: -1)

        val failed = runAiSafely<String> { throw IllegalStateException("boom") }
        assertTrue(failed is AiCallResult.Failure)
        assertTrue(failed.reasonOrNull() is AiFailureReason.Unknown)
    }

    @Test
    fun `runAiSafelyBlock 同步版本同样收敛`() {
        assertEquals("v", runAiSafelyBlock { "v" }.valueOrNull())
        assertTrue(runAiSafelyBlock<Int> { throw IllegalStateException("boom") }.isFailure)
    }
}