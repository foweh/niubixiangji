package com.hinnka.mycamera.aiplatform

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 失败归因（AI 平台层契约符号，对齐审计报告 §3.3 + K12）。
 *
 * UI 据此给出**可操作文案**；禁止把原始异常直接暴露给用户（原始异常只进
 * [Unknown.cause]，且其 message 必须经 [AiLogRedactor.scrub] 后再展示）。
 *
 * 覆盖度（K12）：未配置 [Disabled]、缺凭据 [MissingCredential]、无网 [Offline]、
 * 各阶段超时 [Timeout]、HTTP 状态 [Http]、响应不可解析/魔数不符/0 字节/超限 [Parse]、
 * 明文被拒 [CleartextBlocked]、TLS 失败 [Tls]、协程取消 [Cancelled]。
 */
sealed interface AiFailureReason {

    /** 未配置端点（或用户关闭了该能力开关）。 */
    data object Disabled : AiFailureReason

    /** 该端点需要鉴权但未提供 Key。 */
    data object MissingCredential : AiFailureReason

    /** 无网络 / DNS 失败 / 连接被拒 / 路由不可达。 */
    data object Offline : AiFailureReason

    /** 超时；phase ∈ {connect, read, write, call}。 */
    data class Timeout(val phase: String) : AiFailureReason

    /** 非 2xx；detail 为脱敏后的响应体片段（可为空）。 */
    data class Http(val code: Int, val detail: String = "") : AiFailureReason

    /** 响应不可解析（JSON 结构不符 / 魔数不符 / 空体 / 超限 / 非预期 Content-Type）。 */
    data class Parse(val detail: String) : AiFailureReason

    /** 明文 http 被系统或配置策略拒绝。 */
    data class CleartextBlocked(val baseUrl: String) : AiFailureReason

    /** TLS 握手 / 证书失败。 */
    data class Tls(val detail: String) : AiFailureReason

    /** 协程取消导致的中断（非错误，UI 不应报错）。 */
    data object Cancelled : AiFailureReason

    /** 兜底（无法归类的异常）；cause 仅用于日志，不得直接展示给用户。 */
    data class Unknown(val cause: Throwable) : AiFailureReason
}

/** 统一调用结果（AI 平台层契约符号）。任何网络 / IO / 解析异常都收敛到此类型，不向上抛。 */
sealed interface AiCallResult<out T> {
    data class Success<T>(val value: T) : AiCallResult<T>
    data class Failure(val reason: AiFailureReason, val retried: Int = 0) : AiCallResult<Nothing>
}

val AiCallResult<*>.isSuccess: Boolean get() = this is AiCallResult.Success

val AiCallResult<*>.isFailure: Boolean get() = this is AiCallResult.Failure

fun <T> AiCallResult<T>.valueOrNull(): T? = (this as? AiCallResult.Success)?.value

fun AiCallResult<*>.reasonOrNull(): AiFailureReason? = (this as? AiCallResult.Failure)?.reason

/** 失败重试次数（成功时为 0）。 */
val AiCallResult<*>.retriedCount: Int get() = (this as? AiCallResult.Failure)?.retried ?: 0

inline fun <T, R> AiCallResult<T>.mapResult(transform: (T) -> R): AiCallResult<R> = when (this) {
    is AiCallResult.Success -> AiCallResult.Success(transform(value))
    is AiCallResult.Failure -> this
}

/**
 * 重试策略（对齐审计报告 K8）。
 *
 * 只重试：无网 / 超时 / HTTP 408 / 429 / 5xx。
 * 不重试：未配置、缺凭据、明文被拒、TLS、解析失败、取消、其余 4xx（如 401/403/400）。
 * 退避：`base * 2^attempt`（上限 30s）+ ±jitterRatio 抖动。
 */
object AiRetryPolicy {

    const val HTTP_REQUEST_TIMEOUT = 408
    const val HTTP_TOO_MANY_REQUESTS = 429
    const val MAX_DELAY_MS = 30_000.0
    const val DEFAULT_JITTER_RATIO = 0.25

    /** 该 HTTP 状态码是否可重试。 */
    fun isRetryableHttpCode(code: Int): Boolean =
        code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code in 500..599

    /**
     * 是否重试。
     * @param attempt 已重试次数（首次失败时为 0）
     * @param maxRetries 配置的最大重试次数（会被 clamp 到 [AiEndpointConfig.RETRY_MIN, RETRY_MAX]）
     */
    fun shouldRetry(reason: AiFailureReason, attempt: Int, maxRetries: Int): Boolean {
        val limit = maxRetries.coerceIn(AiEndpointConfig.RETRY_MIN, AiEndpointConfig.RETRY_MAX)
        if (attempt < 0 || attempt >= limit) return false
        return when (reason) {
            AiFailureReason.Offline -> true
            is AiFailureReason.Timeout -> true
            is AiFailureReason.Http -> isRetryableHttpCode(reason.code)
            else -> false
        }
    }

    /**
     * 指数退避 + 抖动（毫秒）。
     * @param attempt 已重试次数（0 = 第一次退避）
     * @param jitterRatio 抖动比例 ∈ [0,1]，0 = 无抖动
     * @param random 0..1 随机源（注入以便单测确定化）
     */
    fun backoffDelayMs(
        baseMs: Long,
        attempt: Int,
        jitterRatio: Double = DEFAULT_JITTER_RATIO,
        random: () -> Double = { Math.random() },
    ): Long {
        val base = baseMs.coerceIn(AiEndpointConfig.RETRY_BASE_MIN_MS, AiEndpointConfig.RETRY_BASE_MAX_MS)
        val exp = attempt.coerceIn(0, 16)
        val raw = base.toDouble() * Math.pow(2.0, exp.toDouble())
        val capped = if (raw > MAX_DELAY_MS) MAX_DELAY_MS else raw
        val ratio = jitterRatio.coerceIn(0.0, 1.0)
        val jitter = capped * ratio * (random().coerceIn(0.0, 1.0) * 2.0 - 1.0)
        return (capped + jitter).toLong().coerceIn(0L, MAX_DELAY_MS.toLong())
    }
}

/** 异常 → 失败归因映射（纯函数，可 JVM 单测）。 */
object AiFailureMapper {

    const val PHASE_CONNECT = "connect"
    const val PHASE_READ = "read"
    const val PHASE_WRITE = "write"
    const val PHASE_CALL = "call"

    /** HTTP 状态码 → 归因。 */
    fun fromHttpCode(code: Int, detail: String = ""): AiFailureReason =
        AiFailureReason.Http(code, AiLogRedactor.scrub(detail))

    /**
     * 异常 → 归因。
     * @param phase 出异常阶段（connect/read/write/call）
     * @param baseUrl 用于明文被拒时给出可操作提示
     */
    fun fromThrowable(t: Throwable, phase: String = PHASE_CALL, baseUrl: String = ""): AiFailureReason = when (t) {
        is CancellationException -> AiFailureReason.Cancelled
        is SocketTimeoutException -> AiFailureReason.Timeout(phase)
        is InterruptedIOException -> AiFailureReason.Timeout(phase)
        is SSLException -> AiFailureReason.Tls(AiLogRedactor.scrub(t.message))
        is UnknownHostException -> AiFailureReason.Offline
        is SocketException -> AiFailureReason.Offline
        is IOException -> {
            val message = t.message.orEmpty()
            if (message.contains("Cleartext", ignoreCase = true) ||
                message.contains("CLEARTEXT", ignoreCase = true)
            ) {
                AiFailureReason.CleartextBlocked(baseUrl)
            } else if (message.contains("timeout", ignoreCase = true)) {
                AiFailureReason.Timeout(phase)
            } else if (message.contains("unable to resolve host", ignoreCase = true) ||
                message.contains("failed to connect", ignoreCase = true) ||
                message.contains("network is unreachable", ignoreCase = true)
            ) {
                AiFailureReason.Offline
            } else {
                AiFailureReason.Unknown(t)
            }
        }
        else -> AiFailureReason.Unknown(t)
    }
}

/**
 * 面向用户的提示文案契约。
 *
 * UI 侧优先用 [stringKey] 对应的 `res/values/strings.xml` 资源（多语言）；
 * [describe] 为无资源上下文的兜底文案（日志 / 后台任务 message / Toast 兜底）。
 */
object AiNotices {

    const val KEY_DISABLED = "ai_error_disabled"
    const val KEY_MISSING_CREDENTIAL = "ai_error_missing_credential"
    const val KEY_OFFLINE = "ai_error_offline"
    const val KEY_TIMEOUT = "ai_error_timeout"
    const val KEY_UNAUTHORIZED = "ai_error_unauthorized"
    const val KEY_RATE_LIMITED = "ai_error_rate_limited"
    const val KEY_SERVER = "ai_error_server"
    const val KEY_HTTP = "ai_error_http"
    const val KEY_PARSE = "ai_error_parse"
    const val KEY_CLEARTEXT = "ai_error_cleartext"
    const val KEY_TLS = "ai_error_tls"
    const val KEY_CANCELLED = "ai_error_cancelled"
    const val KEY_UNKNOWN = "ai_error_unknown"
    const val KEY_FALLBACK_USED = "ai_fallback_used"

    /** 稳定的 strings.xml key（补丁同步增补同名资源）。 */
    fun stringKey(reason: AiFailureReason): String = when (reason) {
        AiFailureReason.Disabled -> KEY_DISABLED
        AiFailureReason.MissingCredential -> KEY_MISSING_CREDENTIAL
        AiFailureReason.Offline -> KEY_OFFLINE
        is AiFailureReason.Timeout -> KEY_TIMEOUT
        is AiFailureReason.Http -> when (reason.code) {
            401, 403 -> KEY_UNAUTHORIZED
            429 -> KEY_RATE_LIMITED
            in 500..599 -> KEY_SERVER
            else -> KEY_HTTP
        }
        is AiFailureReason.Parse -> KEY_PARSE
        is AiFailureReason.CleartextBlocked -> KEY_CLEARTEXT
        is AiFailureReason.Tls -> KEY_TLS
        AiFailureReason.Cancelled -> KEY_CANCELLED
        is AiFailureReason.Unknown -> KEY_UNKNOWN
    }

    /** 兜底中文文案（无资源上下文时使用；不含任何密钥 / URL 明文）。 */
    fun describe(reason: AiFailureReason): String = when (reason) {
        AiFailureReason.Disabled -> "该 AI 能力未配置端点，已跳过"
        AiFailureReason.MissingCredential -> "该 AI 端点需要密钥，请先在设置中填写"
        AiFailureReason.Offline -> "网络不可用，请检查连接后重试"
        is AiFailureReason.Timeout -> "请求超时（${reason.phase}），请稍后重试"
        is AiFailureReason.Http -> when (reason.code) {
            401, 403 -> "鉴权失败（HTTP ${reason.code}），请检查密钥或权限"
            429 -> "请求过于频繁（HTTP 429），请稍后重试"
            in 500..599 -> "服务端异常（HTTP ${reason.code}），请稍后重试"
            else -> "请求失败（HTTP ${reason.code}）"
        }
        is AiFailureReason.Parse -> "响应格式不受支持，已放弃使用该结果"
        is AiFailureReason.CleartextBlocked -> "明文 HTTP 已被系统拦截，请改用 HTTPS 或放行该域名"
        is AiFailureReason.Tls -> "HTTPS 证书校验失败"
        AiFailureReason.Cancelled -> "请求已取消"
        is AiFailureReason.Unknown -> "调用失败（未知原因）"
    }

    /** 降级提示：远端失败 + 是否已用本地结果兜底。 */
    fun fallbackNotice(reason: AiFailureReason, usedLocalFallback: Boolean): String =
        if (usedLocalFallback) "${describe(reason)}；已自动改用本地处理结果" else describe(reason)
}
