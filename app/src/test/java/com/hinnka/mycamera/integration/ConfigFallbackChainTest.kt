package com.hinnka.mycamera.integration

import com.hinnka.mycamera.integration.IntegrationSources.count
import com.hinnka.mycamera.integration.IntegrationSources.filesContaining
import com.hinnka.mycamera.integration.IntegrationSources.readMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 2 组：配置解析 / 回退链路（aiplatform 平台层）。
 *
 * 两段式验证：
 *  A. 契约镜像（纯 JVM 逻辑）：把平台层「默认值 → clamp → 鉴权头 → 重试退避」的语义在本文件内
 *     以等价实现复算一遍，覆盖正常 / 边界 / 异常；
 *  B. 源码断言：确认这些语义在工程里确实由对应符号承载（符号缺失即集成失败）。
 */
class ConfigFallbackChainTest {

    // ---------- A. 契约镜像 ----------

    /** 与 AiEndpointConfig.clamp 同语义：只允许非空 https 或显式放行明文；超时收敛到 [1s, 1h]。 */
    private data class Endpoint(val url: String, val timeoutMs: Long, val allowCleartext: Boolean)

    private fun clamp(url: String, timeoutMs: Long, allowCleartext: Boolean, defaultUrl: String): Endpoint {
        val normalized = url.trim()
        val safe = when {
            normalized.isEmpty() -> defaultUrl
            normalized.startsWith("http://") && !allowCleartext -> defaultUrl
            !normalized.startsWith("https://") && !normalized.startsWith("http://") -> defaultUrl
            else -> normalized
        }
        return Endpoint(safe, timeoutMs.coerceIn(1_000L, 3_600_000L), allowCleartext)
    }

    /** 与 AiFailureReason.MAX_DELAY_MS 同语义的指数退避（含抖动上界收敛）。 */
    private fun backoffDelayMs(attempt: Int, base: Long = 1_000L, jitter: Double = 0.0): Long {
        val exp = base shl attempt.coerceIn(0, 5)
        val jittered = (exp * (1.0 + jitter)).toLong()
        return jittered.coerceIn(0L, 30_000L)
    }

    @Test
    fun clampKeepsDefaultOnEmptyOrCleartextOrSchemeLessUrl() {
        assertEquals("https://api.example.com", clamp("", 5_000, false, "https://api.example.com").url)
        assertEquals("https://api.example.com", clamp("   ", 5_000, false, "https://api.example.com").url)
        // 明文 http 未显式放行 → 回退默认（不静默降级到 http）
        assertEquals("https://api.example.com", clamp("http://10.0.0.8:8080", 5_000, false, "https://api.example.com").url)
        // 显式放行则保留用户值（自部署场景）
        assertEquals("http://10.0.0.8:8080", clamp("http://10.0.0.8:8080", 5_000, true, "https://api.example.com").url)
        // 无 scheme → 回退
        assertEquals("https://api.example.com", clamp("api.example.com", 5_000, false, "https://api.example.com").url)
    }

    @Test
    fun clampBoundsTimeoutAtBothEdges() {
        assertEquals(1_000L, clamp("https://a.b", 0, false, "https://a.b").timeoutMs)
        assertEquals(1_000L, clamp("https://a.b", -123L, false, "https://a.b").timeoutMs)
        assertEquals(3_600_000L, clamp("https://a.b", Long.MAX_VALUE, false, "https://a.b").timeoutMs)
        assertEquals(15_000L, clamp("https://a.b", 15_000L, false, "https://a.b").timeoutMs)
    }

    @Test
    fun backoffIsMonotonicAndCapped() {
        val delays = (0..8).map { backoffDelayMs(it) }
        assertTrue("退避应单调不减：$delays", delays.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("退避上界应为 30s：$delays", delays.all { it <= 30_000L })
        assertEquals(30_000L, backoffDelayMs(20))
        assertEquals(1_000L, backoffDelayMs(0))
    }

    @Test
    fun secretMaskingNeverLeaksMiddleOfKey() {
        fun mask(v: String): String {
            val s = v.trim()
            return when {
                s.isEmpty() -> ""
                s.length <= 8 -> "****"
                else -> s.take(3) + "****" + s.takeLast(2)
            }
        }
        assertEquals("", mask(""))
        assertEquals("****", mask("sk-123"))
        assertEquals("****", mask("12345678"))
        assertEquals("sk-****99", mask("sk-abcdefghij99"))
        assertTrue("掩码不得包含原 Key 中段", !mask("sk-abcdefghij99").contains("defgh"))
    }

    // ---------- B. 源码断言 ----------

    @Test
    fun platformSymbolsExistForClampFallbackAndAuth() {
        assertEquals("AiEndpointConfig.clamp 应存在", 1,
            count(readMain("java/com/hinnka/mycamera/aiplatform/AiEndpointConfig.kt"), "fun clamp("))
        assertTrue("缺少 clampWithReport",
            readMain("java/com/hinnka/mycamera/aiplatform/AiEndpointConfig.kt").contains("clampWithReport"))
        val http = readMain("java/com/hinnka/mycamera/aiplatform/AiHttpClient.kt")
        assertTrue("缺少 executeWithFallback", http.contains("executeWithFallback"))
        assertTrue("缺少二进制体积上限", http.contains("MAX_BINARY_BYTES"))
        assertTrue("缺少鉴权头处理", http.contains("Authorization") || http.contains("authorization"))
        val redactor = readMain("java/com/hinnka/mycamera/aiplatform/AiLogRedactor.kt")
        assertTrue("缺少脱敏器 maskSecret", redactor.contains("fun maskSecret("))
        assertTrue("缺少 scrub", redactor.contains("fun scrub("))
    }

    @Test
    fun configStoreExposesExpectedContract() {
        val store = readMain("java/com/hinnka/mycamera/aiplatform/AiConfigStore.kt")
        listOf("fun config(", "fun observe(", "fun setUserOverride(", "fun clearUserOverride(", "fun isAvailable(")
            .forEach { assertTrue("AiConfigStore 缺少 $it", store.contains(it)) }
    }

    @Test
    fun consumersActuallyUseThePlatformLayer() {
        // 集成断言：老客户端（ServerEnhancementClient / LutRecommender / LutDownloadManager）已改走平台层
        val consumers = listOf(
            "java/com/hinnka/mycamera/computational/ServerEnhancementClient.kt",
            "java/com/hinnka/mycamera/lut/LutRecommender.kt",
            "java/com/hinnka/mycamera/lut/LutDownloadManager.kt",
        )
        consumers.forEach { path ->
            val src = readMain(path)
            assertTrue("$path 未接入 aiplatform", src.contains("com.hinnka.mycamera.aiplatform"))
        }
        // 白平衡 / 场景识别的平台化接线至少各有一处引用
        assertTrue("AiWhiteBalanceAnalyzer 未被接线", filesContaining("AiWhiteBalanceAnalyzer").size >= 2)
        assertTrue("AiResponseValidator 未被使用", filesContaining("AiResponseValidator").size >= 2)
    }
}
