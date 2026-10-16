package com.hinnka.mycamera.net

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.random.Random

/**
 * 通用下载规格（aiplatform_patch.md 注入）：给「模型权重 / LUT 包 / 目录」等非 AI 推理类下载
 * 统一连接/读超时、指数退避重试、基于内容长度与已落盘字节的**断点续传**、以及状态码校验。
 * 与 AI 平台层（aiplatform/）互不依赖，纯 Java 网络栈 + 纯 Kotlin，新增依赖 0。
 */
data class HttpDownloadSpec(
    val connectTimeoutMs: Int = 15_000,      // [1000, 60000]
    val readTimeoutMs: Int = 30_000,         // [1000, 600000]
    val maxRetries: Int = 2,                 // [0, 5]
    val retryBaseDelayMs: Long = 500L,       // [100, 10000]
    val jitterRatio: Float = 0.25f,          // [0, 1]
    val resume: Boolean = true,
    val expectedContentTypes: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT = HttpDownloadSpec()

        fun clamp(spec: HttpDownloadSpec) = spec.copy(
            connectTimeoutMs = spec.connectTimeoutMs.coerceIn(1000, 60_000),
            readTimeoutMs = spec.readTimeoutMs.coerceIn(1000, 600_000),
            maxRetries = spec.maxRetries.coerceIn(0, 5),
            retryBaseDelayMs = spec.retryBaseDelayMs.coerceIn(100L, 10_000L),
            jitterRatio = spec.jitterRatio.coerceIn(0f, 1f),
        )
    }

    /** 指数退避 + 抖动（与 AI 平台层 `AiRetryPolicy` 同一算法口径）。 */
    fun backoffDelayMs(attempt: Int, random: Double = Random.nextDouble()): Long {
        val c = clamp(this)
        val base = (c.retryBaseDelayMs * Math.pow(2.0, attempt.coerceAtLeast(0).toDouble())).toLong()
        val jitter = (base * c.jitterRatio * random).toLong()
        return min(base + jitter, 30_000L)
    }

    /** 单次分块下载；`resume` 为真且目标已存在部分内容时带 `Range` 续传。 */
    fun download(url: URL, dest: File): Boolean {
        val c = clamp(this)
        val existing = if (c.resume && dest.exists()) dest.length() else 0L
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = c.connectTimeoutMs
                readTimeout = c.readTimeoutMs
                instanceFollowRedirects = true
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            val partialOk = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (!partialOk && code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code for $url")
            }
            val contentType = conn.contentType.orEmpty()
            if (c.expectedContentTypes.isNotEmpty() && contentType.isNotBlank() &&
                c.expectedContentTypes.none { contentType.startsWith(it) }
            ) {
                throw IOException("unexpected content-type: $contentType")
            }
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    if (!partialOk) output.channel.truncate(0L)
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** 重试包装。 */
    fun downloadWithRetry(url: URL, dest: File): Boolean {
        val c = clamp(this)
        var attempt = 0
        while (true) {
            if (download(url, dest)) return true
            if (attempt >= c.maxRetries) return false
            runCatching { Thread.sleep(c.backoffDelayMs(attempt)) }
            attempt++
        }
    }
}
