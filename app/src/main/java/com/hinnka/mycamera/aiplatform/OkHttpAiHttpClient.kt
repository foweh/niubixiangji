package com.hinnka.mycamera.aiplatform

import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Okio
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [AiHttpClient] 的 OkHttp 实现（**复用工程既有 `libs.okhttp`，新增依赖 0**）。
 *
 * 关键实现点：
 *  - **连接池 / 调度器复用**：`baseClient.newBuilder()` 派生逐能力客户端，
 *    各客户端共享同一 [ConnectionPool] 与 [Dispatcher]，改超时不丢连接复用；
 *  - **超时**：按 [AiEndpointConfig] 逐调用设置 connect / read / write；
 *  - **鉴权**：按配置注入鉴权头；日志一律经 [AiLogRedactor] 脱敏；
 *  - **重试**：`retryOnConnectionFailure(false)`，重试完全由 [AiRetryPolicy] 控制
 *    （仅 Offline/Timeout/408/429/5xx；指数退避 + 抖动；次数可配 ≤5）；
 *  - **取消**：`suspendCancellableCoroutine` + `invokeOnCancellation { call.cancel() }`，
 *    协程取消立即中断 socket，无不可中断阻塞读（替代原 `ServerEnhancementClient` 的 120s 悬挂）；
 *  - **不崩**：任何异常收敛为 [AiCallResult.Failure]。
 *
 * @param configStore 配置来源（读端点/密钥/超时/重试）
 */
class OkHttpAiHttpClient(
    private val configStore: AiConfigStore,
) : AiHttpClient {

    private val connectionPool = ConnectionPool(CONNECTION_POOL_MAX_IDLE, CONNECTION_POOL_KEEP_ALIVE_MIN, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher()

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        // 重试由本类统一控制（指数退避 + 抖动 + 可配次数），关闭 OkHttp 隐式重试避免双重重试放大请求量。
        .retryOnConnectionFailure(false)
        .build()

    private fun clientFor(config: AiEndpointConfig): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(config.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(config.writeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .build()

    // ------------------------------------------------------------------ 公开入口

    override suspend fun <T> executeJson(
        capability: AiCapability,
        request: AiRequest,
        parse: (String) -> T,
    ): AiCallResult<T> = parseText(
        capability = capability,
        request = AiRawRequest(
            method = request.method,
            query = request.query,
            headers = request.headers,
            jsonBody = request.jsonBody,
        ),
        parse = parse,
    )

    override suspend fun <T> executeMultipart(
        capability: AiCapability,
        request: AiMultipartRequest,
        parse: (String) -> T,
    ): AiCallResult<T> = parseText(
        capability = capability,
        request = AiRawRequest(method = "POST", multipart = request),
        parse = parse,
    )

    override suspend fun <T> executeRaw(
        capability: AiCapability,
        request: AiRawRequest,
        parse: (AiRawResponse) -> T,
    ): AiCallResult<T> = executeWithResponse(capability, request) { response ->
        wrapParse { parse(response) }
    }

    override suspend fun downloadBinary(
        capability: AiCapability,
        request: AiRawRequest,
        expectedContentTypes: Set<String>,
        minBytes: Long,
        maxBytes: Long,
    ): AiCallResult<ByteArray> = executeWithResponse(capability, request) { response ->
        val bytes = response.bytes(maxBytes)
            ?: return@executeWithResponse AiCallResult.Failure(
                AiFailureReason.Parse("响应体为空或超过上限 ${maxBytes}B（防超大响应 OOM）"),
            )
        val head = if (bytes.size > HEAD_BYTES) bytes.copyOf(HEAD_BYTES) else bytes
        when (
            val validation = AiResponseValidator.validate(
                head = head,
                actualBytes = bytes.size.toLong(),
                contentType = response.contentType,
                expectedContentTypes = expectedContentTypes,
                minBytes = minBytes,
                maxBytes = maxBytes,
            )
        ) {
            AiResponseValidation.Ok -> AiCallResult.Success(bytes)
            else -> AiCallResult.Failure(AiFailureReason.Parse(validation.toString()))
        }
    }

    override suspend fun downloadToFile(
        capability: AiCapability,
        request: AiRawRequest,
        destFile: File,
        expectedContentTypes: Set<String>,
        minBytes: Long,
        maxBytes: Long,
    ): AiCallResult<File> = executeWithResponse(capability, request) { response ->
        val directory = destFile.parentFile
        if (directory != null && !directory.exists()) directory.mkdirs()
        val partFile = File(destFile.absolutePath + PART_SUFFIX)
        runCatching { partFile.delete() }

        val written = response.streamTo(partFile, maxBytes)
        if (written < minBytes || written > maxBytes) {
            runCatching { partFile.delete() }
            return@executeWithResponse AiCallResult.Failure(
                AiFailureReason.Parse("下载体积异常（${written}B，期望 ${minBytes}..${maxBytes}B）"),
            )
        }
        val head = readHead(partFile, HEAD_BYTES)
        when (
            val validation = AiResponseValidator.validate(
                head = head,
                actualBytes = written,
                contentType = response.contentType,
                expectedContentTypes = expectedContentTypes,
                minBytes = minBytes,
                maxBytes = maxBytes,
            )
        ) {
            AiResponseValidation.Ok -> {
                // 校验通过才落正式文件名：校验失败不留半包，也不污染目标文件。
                runCatching { destFile.delete() }
                if (partFile.renameTo(destFile)) {
                    AiCallResult.Success(destFile)
                } else {
                    runCatching { partFile.delete() }
                    AiCallResult.Failure(AiFailureReason.Parse("结果文件重命名失败"))
                }
            }
            else -> {
                runCatching { partFile.delete() }
                AiCallResult.Failure(AiFailureReason.Parse(validation.toString()))
            }
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private suspend fun <T> parseText(
        capability: AiCapability,
        request: AiRawRequest,
        parse: (String) -> T,
    ): AiCallResult<T> = executeWithResponse(capability, request) { response ->
        wrapParse { parse(response.text().orEmpty()) }
    }

    /** 把解析回调的异常收敛为 Parse 失败（绝不向上抛；取消除外）。 */
    private inline fun <T> wrapParse(block: () -> T): AiCallResult<T> = try {
        AiCallResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AiCallResult.Failure(AiFailureReason.Parse(AiLogRedactor.scrub(t.message)))
    }

    /**
     * 单次调用骨架：读配置 → 校验 → 构造请求 → 执行 → 失败按策略退避重试。
     * 仅在 2xx 时把响应交给 [consume]；非 2xx 统一映射为 `Http(code)`（404/401 不重试）。
     */
    private suspend fun <T> executeWithResponse(
        capability: AiCapability,
        request: AiRawRequest,
        consume: (AiRawResponse) -> AiCallResult<T>,
    ): AiCallResult<T> {
        val config = try {
            configStore.config(capability)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return AiCallResult.Failure(AiFailureReason.Unknown(t))
        }

        config.validate()?.let { return AiCallResult.Failure(it) }
        if (config.requiresAuth && !config.hasCredential) {
            return AiCallResult.Failure(AiFailureReason.MissingCredential)
        }

        val client = try {
            clientFor(config)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return AiCallResult.Failure(AiFailureReason.Unknown(t))
        }

        val okRequest = try {
            buildRequest(config, request)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return AiCallResult.Failure(AiFailureReason.Parse("请求构造失败"))
        }

        var retried = 0
        while (true) {
            val failure = try {
                when (val outcome = executeOnce(client, okRequest, consume)) {
                    is AiCallResult.Success -> return outcome
                    is AiCallResult.Failure -> outcome.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AiFailureMapper.fromThrowable(t, AiFailureMapper.PHASE_CALL, config.baseUrl)
            }

            currentCoroutineContext().ensureActive()
            if (!AiRetryPolicy.shouldRetry(failure, retried, config.maxRetries)) {
                return AiCallResult.Failure(failure, retried)
            }
            retried++
            PLog.w(TAG, "AI 调用 ${capability.name} 第 $retried 次重试，原因：${AiNotices.describe(failure)}")
            delay(AiRetryPolicy.backoffDelayMs(config.retryBaseDelayMs, retried - 1))
        }
    }

    private suspend fun <T> executeOnce(
        client: OkHttpClient,
        request: Request,
        consume: (AiRawResponse) -> AiCallResult<T>,
    ): AiCallResult<T> {
        val response = awaitCall(client, request)
        response.use { actual ->
            val body = actual.body
            val wrapped = AiRawResponse(
                code = actual.code,
                contentType = actual.header(HEADER_CONTENT_TYPE) ?: body?.contentType()?.toString(),
                declaredBytes = body?.contentLength() ?: -1L,
                body = body,
            )
            if (!actual.isSuccessful) {
                // 只读一小段错误体用于归因，且必须脱敏后再入库/日志。
                val snippet = runCatching {
                    actual.peekBody(ERROR_BODY_PEEK_BYTES).string()
                }.getOrNull()
                return AiCallResult.Failure(
                    AiFailureMapper.fromHttpCode(
                        actual.code,
                        snippet?.take(ERROR_BODY_MAX_CHARS).orEmpty(),
                    ),
                )
            }
            return consume(wrapped)
        }
    }

    /** 发起调用并等待；协程取消时立刻 `call.cancel()`（对齐 K7）。 */
    private suspend fun awaitCall(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        runCatching { response.close() }
                    }
                }
            })
        }

    private fun buildRequest(config: AiEndpointConfig, raw: AiRawRequest): Request {
        val url = config.resolvedUrl(raw.pathOverride).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("非法端点地址")
        val urlBuilder = url.newBuilder()
        raw.query.forEach { (name, value) -> if (name.isNotBlank()) urlBuilder.addQueryParameter(name, value) }

        val builder = Request.Builder().url(urlBuilder.build())
        config.authHeader()?.let { (name, value) -> builder.header(name, value) }
        raw.headers.forEach { (name, value) -> if (name.isNotBlank()) builder.header(name, value) }

        val body: RequestBody? = when {
            raw.multipart != null -> buildMultipartBody(raw.multipart)
            raw.jsonBody != null -> raw.jsonBody!!.toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }
        builder.method(raw.method.uppercase(), body)
        return builder.build()
    }

    /** 流式 multipart：文件经 [AiMultipartRequest.fileProvider] 逐块写入，不整块驻留内存。 */
    private fun buildMultipartBody(request: AiMultipartRequest): RequestBody {
        val boundary = "----PhotonAiBoundary${System.nanoTime()}"
        return object : RequestBody() {
            override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()

            override fun contentLength(): Long = -1L // 未知长度 → chunked，避免把整文件读进内存

            override fun writeTo(sink: BufferedSink) {
                request.textFields.forEach { (name, value) ->
                    sink.writeUtf8("--$boundary\r\n")
                    sink.writeUtf8("Content-Disposition: form-data; name=\"${escape(name)}\"\r\n\r\n")
                    sink.writeUtf8(value)
                    sink.writeUtf8("\r\n")
                }
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8(
                    "Content-Disposition: form-data; name=\"${escape(request.fileFieldName)}\"; " +
                        "filename=\"${escape(request.fileName)}\"\r\n",
                )
                sink.writeUtf8("Content-Type: ${request.fileContentType}\r\n\r\n")
                request.fileProvider().use { input ->
                    Okio.source(input).use { source -> sink.writeAll(source) }
                }
                sink.writeUtf8("\r\n--$boundary--\r\n")
                sink.flush()
            }
        }
    }

    private fun escape(value: String): String = value.replace('"', '_').replace('\r', '_').replace('\n', '_')

    private fun readHead(file: File, count: Int): ByteArray = runCatching {
        val head = ByteArray(count)
        var offset = 0
        file.inputStream().use { input ->
            while (offset < head.size) {
                val read = input.read(head, offset, head.size - offset)
                if (read <= 0) break
                offset += read
            }
        }
        head.copyOf(offset)
    }.getOrDefault(ByteArray(0))

    private companion object {
        const val TAG = "AiHttpClient"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEAD_BYTES = 16
        const val ERROR_BODY_PEEK_BYTES = 8L * 1024
        const val ERROR_BODY_MAX_CHARS = 300
        const val PART_SUFFIX = ".part"
        const val CONNECTION_POOL_MAX_IDLE = 4
        const val CONNECTION_POOL_KEEP_ALIVE_MIN = 5L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
