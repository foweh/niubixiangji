package com.hinnka.mycamera.aiplatform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应校验（M3 / K12）：Content-Type 白名单、文本类拦截、魔数、体积双阈值。
 * 纯 JVM 测试。
 */
class AiResponseValidatorTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
    private val tiffLittle = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00)
    private val tiffBig = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x08)
    private val webp = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
    )
    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
    private val gzip = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00)

    @Test
    fun `mime 解析去掉参数并小写`() {
        assertEquals("application/json", AiResponseValidator.mimeOf("application/json; charset=utf-8"))
        assertEquals("image/png", AiResponseValidator.mimeOf("IMAGE/PNG"))
        assertNull(AiResponseValidator.mimeOf(""))
        assertNull(AiResponseValidator.mimeOf(null))
    }

    @Test
    fun `文本类 content type 判定`() {
        assertTrue(AiResponseValidator.isTextual("application/json"))
        assertTrue(AiResponseValidator.isTextual("text/html; charset=utf-8"))
        assertTrue(AiResponseValidator.isTextual("text/plain"))
        assertFalse(AiResponseValidator.isTextual("image/png"))
        assertFalse(AiResponseValidator.isTextual(null))
    }

    @Test
    fun `魔数识别`() {
        assertEquals(AiBinaryFormat.PNG, AiResponseValidator.detectFormat(png))
        assertEquals(AiBinaryFormat.JPEG, AiResponseValidator.detectFormat(jpeg))
        assertEquals(AiBinaryFormat.TIFF_OR_DNG, AiResponseValidator.detectFormat(tiffLittle))
        assertEquals(AiBinaryFormat.TIFF_OR_DNG, AiResponseValidator.detectFormat(tiffBig))
        assertEquals(AiBinaryFormat.WEBP, AiResponseValidator.detectFormat(webp))
        assertEquals(AiBinaryFormat.ZIP, AiResponseValidator.detectFormat(zip))
        assertEquals(AiBinaryFormat.GZIP, AiResponseValidator.detectFormat(gzip))
        assertEquals(AiBinaryFormat.UNKNOWN, AiResponseValidator.detectFormat(byteArrayOf(0x01, 0x02)))
        assertEquals(AiBinaryFormat.UNKNOWN, AiResponseValidator.detectFormat(ByteArray(0)))
    }

    @Test
    fun `合法图片响应通过`() {
        val result = AiResponseValidator.validate(
            head = png,
            actualBytes = 1_024,
            contentType = "image/png",
            expectedContentTypes = AiResponseValidator.IMAGE_CONTENT_TYPES,
            minBytes = 1,
            maxBytes = 1_000_000,
        )
        assertEquals(AiResponseValidation.Ok, result)
    }

    @Test
    fun `200 加 JSON 错误体被拦截（审计报告 M3 核心场景）`() {
        val result = AiResponseValidator.validate(
            head = """{"error":"model not found"}""".toByteArray(),
            actualBytes = 64,
            contentType = "application/json",
            expectedContentTypes = AiResponseValidator.IMAGE_CONTENT_TYPES,
        )
        assertTrue(result is AiResponseValidation.ContentTypeRejected)
    }

    @Test
    fun `content type 不在白名单被拒`() {
        val result = AiResponseValidator.validate(
            head = png,
            actualBytes = 1_024,
            contentType = "application/pdf",
            expectedContentTypes = setOf("image/png", "image/jpeg"),
        )
        assertTrue(result is AiResponseValidation.ContentTypeRejected)
    }

    @Test
    fun `无 content type 时用魔数兜底`() {
        assertEquals(
            AiResponseValidation.Ok,
            AiResponseValidator.validate(png, 512, null, AiResponseValidator.IMAGE_CONTENT_TYPES),
        )
        val unknown = AiResponseValidator.validate(byteArrayOf(0x01, 0x02, 0x03, 0x04), 512, null)
        assertTrue(unknown is AiResponseValidation.FormatRejected)
    }

    @Test
    fun `0 字节与超上限被拒`() {
        val tooSmall = AiResponseValidator.validate(png, 0, "image/png", minBytes = 1)
        assertTrue(tooSmall is AiResponseValidation.TooSmall)

        val tooLarge = AiResponseValidator.validate(png, 4_096, "image/png", maxBytes = 1_024)
        assertTrue(tooLarge is AiResponseValidation.TooLarge)
    }

    @Test
    fun `边界值恰等于阈值时通过`() {
        assertEquals(
            AiResponseValidation.Ok,
            AiResponseValidator.validate(png, actualBytes = 1, contentType = "image/png", minBytes = 1, maxBytes = 1),
        )
    }
}
