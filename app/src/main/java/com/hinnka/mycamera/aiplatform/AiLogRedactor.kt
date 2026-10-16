package com.hinnka.mycamera.aiplatform

/**
 * 日志 / 异常信息脱敏（对齐审计报告 K3、I16）。
 *
 * 规则：
 *  - 永不打印 `Authorization` 头与完整 Key；
 *  - Key 一律走 [maskSecret]（保留前 3 位 + 后 2 位，便于用户辨认是哪一把）；
 *  - 任意文本（异常 message、响应体回显）走 [scrub]，把形如 `sk-…` / `AIza…` /
 *    `Bearer <token>` / `api_key=…` 的片段替换为掩码。
 *
 * 纯 Kotlin（无 Android / 无日志实现），可 JVM 单测。
 */
object AiLogRedactor {

    const val MASK = "****"

    private const val VISIBLE_PREFIX = 3
    private const val VISIBLE_SUFFIX = 2
    private const val SHORT_SECRET_MAX = 8

    private val BEARER_REGEX = Regex("""(?i)\bbearer\s+([A-Za-z0-9\-._~+/=]{8,})""")
    private val SK_REGEX = Regex("""sk-[A-Za-z0-9_\-]{6,}""")
    private val GOOGLE_KEY_REGEX = Regex("""AIza[0-9A-Za-z_\-]{10,}""")
    private val KEY_VALUE_REGEX = Regex(
        """(?i)\b(api[-_]?key|apikey|access[-_]?token|auth[-_]?token|authorization|secret)\b\s*[:=]\s*["']?([^\s,;"'}]{6,})""",
    )

    /** 屏蔽单个密钥。空串返回空串；长度 ≤ 8 全掩码。 */
    fun maskSecret(value: String): String {
        val v = value.trim()
        return when {
            v.isEmpty() -> ""
            v.length <= SHORT_SECRET_MAX -> MASK
            else -> v.take(VISIBLE_PREFIX) + MASK + v.takeLast(VISIBLE_SUFFIX)
        }
    }

    /** 判断是否为敏感头（其值必须掩码）。 */
    fun isSensitiveHeader(name: String): Boolean {
        val n = name.lowercase()
        return n == "authorization" ||
            n.contains("api-key") ||
            n.contains("apikey") ||
            n.contains("token") ||
            n.contains("secret") ||
            n.contains("cookie")
    }

    /** 脱敏单条头："Authorization: ****" 。 */
    fun maskHeader(name: String, value: String): String =
        if (isSensitiveHeader(name)) "$name: $MASK" else "$name: $value"

    /** 清洗任意文本中的密钥 / 鉴权串。null 或空返回空串。 */
    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = text
        out = BEARER_REGEX.replace(out) { m -> "Bearer $MASK" }
        out = SK_REGEX.replace(out) { MASK }
        out = GOOGLE_KEY_REGEX.replace(out) { MASK }
        out = KEY_VALUE_REGEX.replace(out) { m -> "${m.groupValues[1]}=$MASK" }
        return out
    }
}
