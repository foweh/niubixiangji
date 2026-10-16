package com.hinnka.mycamera.aiplatform

import okhttp3.ResponseBody
import java.io.File
import java.io.InputStream

/** 统一 JSON / 文本请求（对齐审计报告 §3.4）。 */
data class AiRequest(
    val method: String = "POST",
    val jsonBody: String? = null,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

/** 统一 multipart 请求；文件以 [fileProvider] 流式写入，大文件不整块驻留内存。 */
data class AiMultipartRequest(
    val fileFieldName: String = "file",
    val fileName: String,
    val fileContentType: String = "application/octet-stream",
    val fileProvider: () -> InputStream,
    val textFields: Map<String, String> = emptyMap(),
)

/** 原始请求（JSON / multipart / 查询参数 / 额外头）；供二进制下载能力复用。 */
data class AiRawRequest(
    val method: String = "POST",
    val pathOverride: String? = null,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val multipart: AiMultipartRequest? = null,
)

/**
 * 原始响应包装（仅 2xx 会交给解析回调）。
 * 注意：响应体只能消费一次。
 */
class AiRawResponse internal constructor(
    val code: Int,
    val contentType: String?,
    /** 声明长度（-1 = 未知，chunked）。 */
    val declaredBytes: Long,
    private val body: ResponseBody?,
) {
    val isSuccessful: Boolean get() = code in 200..299

    /** 读全文（小响应）。 */
    fun text(): String? = runCatching { body?.string() }.getOrNull()

    /** 读全量字节；超过 [maxBytes] 返回 null（防超大响应用尽内存）。 */
    fun bytes(maxBytes: Long): ByteArray? {
        val responseBody = body ?: return null
        val declared = responseBody.contentLength()
        if (declared > maxBytes) return null
        return runCatching {
            responseBody.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(if (declared in 1..(1L shl 20)) declared.toInt() else 64 * 1024)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** 流式写入文件；返回写入字节数，失败或超限返回 -1。 */
    fun streamTo(dest: File, maxBytes: Long): Long {
        val responseBody = body ?: return -1L
        if (responseBody.contentLength() > maxBytes) return -1L
        return runCatching {
            var total = 0L
            responseBody.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) return -1L
                        output.write(buffer, 0, read)
                    }
                }
            }
            total
        }.getOrDefault(-1L)
    }

    /**
     * 读取前 [count] 字节会消费响应体，故**仅供一次性使用**：
     * 与 [text] / [bytes] / [streamTo] 互斥，调用方二选一（本工程内部不使用，留给扩展）。
     */
    fun peekHead(count: Int): ByteArray {
        val responseBody = body ?: return ByteArray(0)
        return runCatching {
            val head = ByteArray(count.coerceAtLeast(1))
            var offset = 0
            responseBody.byteStream().use { input ->
                while (offset < head.size) {
                    val read = input.read(head, offset, head.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
            head.copyOf(offset)
        }.getOrDefault(ByteArray(0))
    }

    internal fun closeQuietly() {
        runCatching { body?.close() }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * 统一 HTTP 入口（AI 平台层契约符号，对齐审计报告 §3.4）。
 *
 * 契约（实现方 [OkHttpAiHttpClient] 必须满足）：
 *  - **鉴权**：按 `AiEndpointConfig.authScheme / authHeaderName` 注入，日志脱敏（永不打印 Key）；
 *  - **超时**：按配置逐调用设置 connect / read / write；
 *  - **重试**：仅 Offline / Timeout / HTTP 408 / 429 / 5xx，指数退避 + 抖动，次数可配；
 *  - **取消**：协程取消即 `call.cancel()`，不存在不可中断的阻塞读；
 *  - **解析**：parse 抛出的异常统一收敛为 `Failure(Parse)`，不向上抛；
 *  - **崩溃**：实现内部全量 try/catch，任何情况返回 [AiCallResult]，绝不向调用方抛异常。
 */
interface AiHttpClient {

    /** JSON / 文本请求；[parse] 在 2xx 时对响应文本求值。 */
    suspend fun <T> executeJson(
        capability: AiCapability,
        request: AiRequest,
        parse: (String) -> T,
    ): AiCallResult<T>

    /** multipart 请求；[parse] 在 2xx 时对响应文本求值。 */
    suspend fun <T> executeMultipart(
        capability: AiCapability,
        request: AiMultipartRequest,
        parse: (String) -> T,
    ): AiCallResult<T>

    /** 原始请求 + 自定义消费（读取策略由调用方决定）。 */
    suspend fun <T> executeRaw(
        capability: AiCapability,
        request: AiRawRequest,
        parse: (AiRawResponse) -> T,
    ): AiCallResult<T>

    /** 二进制下载（服务器超分结果 / 模型文件），带 Content-Type 白名单 + 魔数 + 体积校验。 */
    suspend fun downloadBinary(
        capability: AiCapability,
        request: AiRawRequest,
        expectedContentTypes: Set<String> = emptySet(),
        minBytes: Long = 1L,
        maxBytes: Long = AiHttpClient.MAX_BINARY_BYTES,
    ): AiCallResult<ByteArray>

    /** 二进制下载并落盘（`.part` 临时文件 + 校验通过后原子改名；校验失败不留半包）。 */
    suspend fun downloadToFile(
        capability: AiCapability,
        request: AiRawRequest,
        destFile: File,
        expectedContentTypes: Set<String> = emptySet(),
        minBytes: Long = 1L,
        maxBytes: Long = AiHttpClient.MAX_BINARY_BYTES,
    ): AiCallResult<File>

    companion object {
        /** 单次二进制响应硬上限（256MB）；超限判 Parse 失败，避免 3.9G 内存设备 OOM。 */
        const val MAX_BINARY_BYTES = 256L * 1024 * 1024
    }
}

/** 可识别的二进制格式（魔数判定）。 */
enum class AiBinaryFormat {
    PNG,
    JPEG,
    TIFF_OR_DNG,
    WEBP,
    ZIP,
    GZIP,
    UNKNOWN,
}

/** 响应校验结果。 */
sealed interface AiResponseValidation {
    data object Ok : AiResponseValidation
    data class TooSmall(val bytes: Long, val minBytes: Long) : AiResponseValidation
    data class TooLarge(val bytes: Long, val maxBytes: Long) : AiResponseValidation
    data class ContentTypeRejected(val contentType: String?, val expected: Set<String>) : AiResponseValidation
    data class FormatRejected(val detected: AiBinaryFormat, val expected: Set<String>) : AiResponseValidation
}

/**
 * 响应校验（对齐审计报告 M3 / K12）：Content-Type 白名单 + 魔数 + 最小/最大体积。
 * 纯函数，可 JVM 单测。**解决「200 + JSON 错误体被当图片落盘」**。
 */
object AiResponseValidator {

    /** 常见远端超分/增强服务可接受的 Content-Type 白名单。 */
    val IMAGE_CONTENT_TYPES: Set<String> = setOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/tiff",
        "image/x-tiff",
        "image/webp",
        "application/dng",
        "application/octet-stream",
        "application/zip",
        "application/x-zip-compressed",
    )

    private val TEXTUAL_CONTENT_TYPES = setOf(
        "application/json",
        "text/json",
        "text/plain",
        "text/html",
        "application/xml",
        "text/xml",
    )

    /** 仅取 `;` 前的 mime，转小写。 */
    fun mimeOf(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

    fun isTextual(contentType: String?): Boolean = mimeOf(contentType) in TEXTUAL_CONTENT_TYPES

    /** 魔数识别。 */
    fun detectFormat(head: ByteArray): AiBinaryFormat = when {
        head.size >= 4 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> AiBinaryFormat.PNG

        head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte() -> AiBinaryFormat.JPEG

        head.size >= 4 && (
            (head[0] == 0x49.toByte() && head[1] == 0x49.toByte() &&
                head[2] == 0x2A.toByte() && head[3] == 0x00.toByte()) ||
                (head[0] == 0x4D.toByte() && head[1] == 0x4D.toByte() &&
                    head[2] == 0x00.toByte() && head[3] == 0x2A.toByte())
            ) -> AiBinaryFormat.TIFF_OR_DNG

        head.size >= 12 &&
            head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
            head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
            head[10] == 0x42.toByte() && head[11] == 0x50.toByte() -> AiBinaryFormat.WEBP

        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() &&
            (head[3] == 0x04.toByte() || head[3] == 0x06.toByte() || head[3] == 0x08.toByte()) ->
            AiBinaryFormat.ZIP

        head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> AiBinaryFormat.GZIP

        else -> AiBinaryFormat.UNKNOWN
    }

    /**
     * 校验规则（顺序即优先级）：
     *  1. 实际字节数 < [minBytes] → TooSmall
     *  2. 实际字节数 > [maxBytes] → TooLarge
     *  3. 期望白名单非空 且 拿到非空 Content-Type 且不在白名单 → ContentTypeRejected
     *  4. 文本类 Content-Type（json/html/xml/plain）→ ContentTypeRejected（200 错误体拦截）
     *  5. 魔数不可识别 → FormatRejected
     */
    fun validate(
        head: ByteArray,
        actualBytes: Long,
        contentType: String?,
        expectedContentTypes: Set<String> = emptySet(),
        minBytes: Long = 1L,
        maxBytes: Long = AiHttpClient.MAX_BINARY_BYTES,
    ): AiResponseValidation {
        if (actualBytes < minBytes) return AiResponseValidation.TooSmall(actualBytes, minBytes)
        if (actualBytes > maxBytes) return AiResponseValidation.TooLarge(actualBytes, maxBytes)

        val mime = mimeOf(contentType)
        if (isTextual(mime)) {
            return AiResponseValidation.ContentTypeRejected(contentType, expectedContentTypes)
        }
        if (expectedContentTypes.isNotEmpty() && mime != null && mime !in expectedContentTypes) {
            return AiResponseValidation.ContentTypeRejected(contentType, expectedContentTypes)
        }
        val format = detectFormat(head)
        if (format == AiBinaryFormat.UNKNOWN) {
            return AiResponseValidation.FormatRejected(format, expectedContentTypes)
        }
        return AiResponseValidation.Ok
    }
}
