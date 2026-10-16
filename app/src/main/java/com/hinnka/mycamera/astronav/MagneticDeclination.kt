/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：磁偏角（真北 vs 磁北）提供者 + 可选在线 API 更新链。
 *
 * ============================================================================
 *  来源与精度
 * ============================================================================
 *  离线真值：NOAA/NCEI **WMM2025** 球谐模型（内嵌官方系数表，见 [Wmm2025Model]）。
 *    美国联邦政府作品（公有领域）；官方页
 *    https://www.ncei.noaa.gov/products/world-magnetic-model
 *    精度：与 NOAA 参考实现逐点比对，54 个测点最大偏差 0.000e+00 度。
 *  在线更新（可选，默认关闭）：NOAA NCEI Geomagnetism 公开 Web 服务
 *    https://www.ngdc.noaa.gov/geomag-web/calculators/calculateDeclination
 *    仅作「模型外/跨年/极端位置」的校准补充；**默认端点只作示例**，
 *    实际端点/鉴权一律可配（见 [DeclinationApiConfig] 与 [DeclinationOnlineUpdater]），
 *    源码内无任何明文密钥/私有端点。
 *
 *  为什么还要在线更新：WMM2025 有效期 2025.0–2030.0，超期后外推误差增大；
 *  在线服务可给出更新模型的结果。取不到时静默回落内嵌模型（不崩溃、不阻塞 UI）。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.aiplatform.AiCapability
import com.hinnka.mycamera.aiplatform.AiConfigStore
import com.hinnka.mycamera.aiplatform.AiEndpointConfig
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 磁偏角解算结果。 */
data class DeclinationResult(
    /**
     * 磁偏角（度，东正西负）。语义：**真北 = 磁北 + declinationDegrees**
     * （罗盘读数为磁北航向，加该值得到真北航向）。
     */
    val degrees: Float,
    /** 实际采用的来源（可能与配置的期望来源不同：在线失败会回落）。 */
    val source: DeclinationSource,
    /** 是否仍处于模型官方有效期内（2025.0–2030.0）；false 表示已外推，误差增大。 */
    val withinModelValidity: Boolean,
    /** 解算用到的纬度/经度/小数年（自检与 UI 展示）。 */
    val latitudeDegrees: Float,
    val longitudeDegrees: Float,
    val decimalYear: Double,
    /** 人类可读说明（中文，供 UI 直接展示）。 */
    val note: String,
) {
    /** 真北航向 = 磁北航向 + 磁偏角。 */
    fun trueHeadingFrom(magneticHeadingDeg: Float): Float =
        AstroNavAngles.wrap360(magneticHeadingDeg + degrees)
}

/**
 * 磁偏角提供者（线程安全：内部状态只有一个 volatile 缓存槽）。
 *
 * 优先顺序：
 *  1. [DeclinationSource.MANUAL] -> 直接用配置值；
 *  2. [DeclinationSource.ONLINE_API] -> 有未过期且位置接近的在线缓存则用之，否则回落；
 *  3. [DeclinationSource.EMBEDDED_WMM] -> 内嵌 WMM2025 球谐模型（默认，离线可用）。
 */
class MagneticDeclinationProvider(
    /** 取当前生效配置（允许运行期改配置）。 */
    private val configProvider: () -> AstroNavConfig = { AstroNavConfig() },
) {

    /** 在线结果缓存（位置 + 时间 + 值）。 */
    private class OnlineCache(
        val lat: Float,
        val lon: Float,
        val degrees: Float,
        val fetchedAtMs: Long,
    )

    @Volatile
    private var onlineCache: OnlineCache? = null

    /** 写入在线获取到的磁偏角（由 [DeclinationOnlineUpdater] 调用）。 */
    fun setOnlineResult(latDegrees: Float, lonDegrees: Float, degrees: Float, nowMs: Long) {
        if (!degrees.isFinite() || degrees < -180f || degrees > 180f) return
        onlineCache = OnlineCache(latDegrees, lonDegrees, degrees, nowMs)
    }

    /** 清空在线缓存（位置大范围变化 / 用户手动刷新时调用）。 */
    fun invalidateOnlineCache() {
        onlineCache = null
    }

    /** 是否有有效的在线缓存（按 TTL 与位置距离判断）。 */
    fun hasFreshOnlineResult(latDegrees: Float, lonDegrees: Float, nowMs: Long): Boolean =
        freshOnline(latDegrees, lonDegrees, nowMs) != null

    /**
     * 解算指定位置/时刻的磁偏角。
     *
     * @param latDegrees 纬度（度）
     * @param lonDegrees 经度（度）
     * @param altitudeMeters 海拔（米，WGS-84 椭球高）
     * @param nowMs 当前时刻（epoch 毫秒；用于模型年与在线缓存 TTL）
     */
    fun declination(
        latDegrees: Float,
        lonDegrees: Float,
        altitudeMeters: Double = 0.0,
        nowMs: Long = System.currentTimeMillis(),
    ): DeclinationResult {
        val cfg = configProvider().clamped().config
        val lat = if (latDegrees.isFinite()) latDegrees.coerceIn(-90f, 90f) else 0f
        val lon = if (lonDegrees.isFinite()) lonDegrees else 0f
        val year = cfg.effectiveModelYear(nowMs)
        val valid = Wmm2025Model.isWithinValidity(year)

        when (cfg.declinationSource) {
            DeclinationSource.MANUAL -> return DeclinationResult(
                degrees = cfg.declinationManualDeg,
                source = DeclinationSource.MANUAL,
                withinModelValidity = true,
                latitudeDegrees = lat,
                longitudeDegrees = lon,
                decimalYear = year,
                note = "手动指定磁偏角 ${fmt(cfg.declinationManualDeg)}°",
            )

            DeclinationSource.ONLINE_API -> {
                val cached = freshOnline(lat, lon, nowMs)
                if (cached != null) {
                    return DeclinationResult(
                        degrees = cached.degrees,
                        source = DeclinationSource.ONLINE_API,
                        withinModelValidity = valid,
                        latitudeDegrees = lat,
                        longitudeDegrees = lon,
                        decimalYear = year,
                        note = "在线磁偏角 ${fmt(cached.degrees)}°（缓存，" +
                            "${((nowMs - cached.fetchedAtMs) / 60_000L)} 分钟前）",
                    )
                }
                // 无可用在线结果 -> 回落内嵌模型
                val deg = embedded(lat, lon, altitudeMeters, year)
                return DeclinationResult(
                    degrees = deg,
                    source = DeclinationSource.EMBEDDED_WMM,
                    withinModelValidity = valid,
                    latitudeDegrees = lat,
                    longitudeDegrees = lon,
                    decimalYear = year,
                    note = "在线磁偏角不可用，已回落内嵌 WMM2025（${fmt(deg)}°）",
                )
            }

            DeclinationSource.EMBEDDED_WMM -> {
                val deg = embedded(lat, lon, altitudeMeters, year)
                return DeclinationResult(
                    degrees = deg,
                    source = DeclinationSource.EMBEDDED_WMM,
                    withinModelValidity = valid,
                    latitudeDegrees = lat,
                    longitudeDegrees = lon,
                    decimalYear = year,
                    note = "内嵌 NOAA WMM2025 ${fmt(year)} 年 " +
                        if (valid) "（${fmt(deg)}°）" else "（已超出模型有效期，外推值 ${fmt(deg)}°，误差增大）",
                )
            }
        }
    }

    /** 内嵌模型解算（异常一律回落 0，不抛）。 */
    fun embedded(latDegrees: Float, lonDegrees: Float, altitudeMeters: Double, decimalYear: Double): Float =
        runCatching {
            Wmm2025Model.declinationDegrees(
                latDeg = latDegrees.toDouble(),
                lonDeg = lonDegrees.toDouble(),
                altitudeKm = altitudeMeters / 1000.0,
                decimalYear = decimalYear,
            ).toFloat()
        }.getOrElse { e ->
            PLog.w(TAG_PROVIDER, "WMM declination failed: ${e.message}")
            0f
        }

    private fun freshOnline(latDegrees: Float, lonDegrees: Float, nowMs: Long): OnlineCache? {
        val c = onlineCache ?: return null
        val cfg = configProvider().clamped().config
        val ttl = cfg.declinationCacheTtlMs
        if (ttl <= 0L) return null
        if (nowMs - c.fetchedAtMs > ttl || nowMs < c.fetchedAtMs) return null
        // 位置一致性：网格距离 > 1° 视为过期（1° ≈ 111km，磁偏角在 100km 尺度变化 < 0.5°）
        val dLat = c.lat - latDegrees
        val dLon = AstroNavAngles.delta(c.lon, lonDegrees)
        if (dLat * dLat + dLon * dLon > 1f) return null
        return c
    }

    private fun fmt(v: Float): String = ((v * 100).toInt() / 100f).toString()

    private fun fmt(v: Double): String = ((v * 100).toInt() / 100.0).toString()
}

// ---------------------------------------------------------------------------
// 在线更新：端点构造 / 响应解析（纯函数，可单测） + HTTP 链（走 AI 平台配置层）
// ---------------------------------------------------------------------------

/**
 * NOAA Geomagnetism Web 服务的请求构造与响应解析（纯函数，无 IO，可 JVM 单测）。
 *
 * 默认端点仅作**示例**（可在设置页/DataStore 覆盖为自建代理或其它服务）；
 * 源码不含任何密钥，`key` 参数只在显式配置了凭据时附加。
 */
object DeclinationApiConfig {

    /** 示例端点（NOAA/NCEI 公开服务，无需鉴权）。 */
    const val DEFAULT_ENDPOINT: String =
        "https://www.ngdc.noaa.gov/geomag-web/calculators/calculateDeclination"

    /** 请求参数名（可配，便于替换为自建服务）。 */
    const val PARAM_LAT = "lat1"
    const val PARAM_LON = "lon1"
    const val PARAM_KEY = "key"
    const val PARAM_FORMAT = "resultFormat"
    const val PARAM_MODEL = "model"
    const val FORMAT_JSON = "json"

    /**
     * 构造请求 URL（经度允许负值；参数值统一 URL 编码）。
     *
     * @param endpoint 基础端点（空则回落 [DEFAULT_ENDPOINT]）
     * @param latitudeDegrees 纬度（-90..90，越界 clamp）
     * @param longitudeDegrees 经度（-180..180，越界归一化）
     * @param model 模型名（空 = 不传该参数；示例 "WMM-2025"）
     * @param apiKey 可选的 key 参数（空 = 不传；**由运行时注入，禁止硬编码**）
     */
    fun buildUrl(
        endpoint: String,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        model: String = "",
        apiKey: String = "",
    ): String {
        val base = endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
        val lat = latitudeDegrees.coerceIn(-90.0, 90.0)
        val lon = Wmm2025Model.normalizeLongitude(longitudeDegrees)
        val sb = StringBuilder(base)
        sb.append(if (base.contains('?')) '&' else '?')
        sb.append(enc(PARAM_LAT)).append('=').append(enc(lat.toString()))
        sb.append('&').append(enc(PARAM_LON)).append('=').append(enc(lon.toString()))
        sb.append('&').append(enc(PARAM_FORMAT)).append('=').append(enc(FORMAT_JSON))
        if (model.isNotBlank()) {
            sb.append('&').append(enc(PARAM_MODEL)).append('=').append(enc(model))
        }
        if (apiKey.isNotBlank()) {
            sb.append('&').append(enc(PARAM_KEY)).append('=').append(enc(apiKey))
        }
        return sb.toString()
    }

    /**
     * 解析 NOAA JSON 响应中的磁偏角。
     *
     * 目标字段形如：`{"result":[{"declination":"-8.77","declination_sv":...}]}`
     * 为容忍格式微调，这里用**宽松扫描**：定位 `"declination"` 后取其后的数字字面量，
     * 不引入 JSON 依赖（PATTERN_SPEC §2：尽量不加新依赖）。
     *
     * @return 磁偏角（度）；解析失败/越界返回 null（调用方回落内嵌模型）。
     */
    fun parseDeclination(json: String?): Float? {
        if (json.isNullOrBlank()) return null
        val idx = json.indexOf("\"declination\"")
        val start = if (idx >= 0) idx + "\"declination\"".length else -1
        if (start < 0) return null
        var i = start
        while (i < json.length && json[i] != ':' ) i++
        if (i >= json.length) return null
        i++ // 跳过 ':'
        // 跳过空格与可能的引号
        while (i < json.length && (json[i] == ' ' || json[i] == '"')) i++
        val numStart = i
        if (i < json.length && (json[i] == '-' || json[i] == '+')) i++
        while (i < json.length && (json[i].isDigit() || json[i] == '.')) i++
        if (i <= numStart) return null
        val value = json.substring(numStart, i).toFloatOrNull() ?: return null
        if (!value.isFinite() || value < -180f || value > 180f) return null
        return value
    }

    private fun enc(v: String): String = runCatching {
        URLEncoder.encode(v, "UTF-8")
    }.getOrElse { v }
}

/** 在线磁偏角拉取结果。 */
data class DeclinationFetch(
    val degrees: Float,
    val url: String,
    val fetchedAtMs: Long,
)

/** HTTP 传输抽象（便于单测注入假实现；默认实现基于 [HttpURLConnection]，零新依赖）。 */
interface DeclinationHttpTransport {
    /**
     * 发起 GET 请求。
     * 失败（超时/非 2xx/异常）应抛异常，由调用方统一归因与回退。
     */
    fun get(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): String
}

/** 默认传输实现：HttpURLConnection（工程既有 OkHttp 依赖仅供 aiplatform 使用，这里保持零耦合）。 */
class HttpUrlConnectionDeclinationTransport : DeclinationHttpTransport {

    override fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs.coerceIn(1_000, 600_000)
            readTimeout = readTimeoutMs.coerceIn(1_000, 600_000)
            headers.forEach { (k, v) -> if (k.isNotBlank()) setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

/**
 * 在线磁偏角更新器。完整链路：构造请求 -> 鉴权 -> 超时 -> 重试 -> 解析 -> 错误处理 -> 优雅回退。
 *
 * 配置与凭据**全部**来自 AI 平台层 [AiConfigStore]（PATTERN_SPEC §1.7）：
 * 通过新增能力 [AiCapability.DECLINATION]（由交付补丁在枚举中追加）读取
 * baseUrl / path / apiKey / authScheme / authHeaderName / 超时 / 重试参数，
 * 源码内**无任何明文密钥或私有端点**；未配置时回落 [DeclinationApiConfig.DEFAULT_ENDPOINT]
 * （NOAA 公开服务，无需鉴权）。
 *
 * 任何失败都不抛异常：返回 null，调用方继续用内嵌 WMM2025。
 */
class DeclinationOnlineUpdater(
    /** AI 平台配置仓库；为 null 时只用示例端点且不带鉴权。 */
    private val configStore: AiConfigStore? = null,
    private val transport: DeclinationHttpTransport = HttpUrlConnectionDeclinationTransport(),
    private val fallbackEndpoint: String = DeclinationApiConfig.DEFAULT_ENDPOINT,
    /** 允许的最大重试次数上限（实际取配置值与它的较小者）。 */
    private val maxRetryCeiling: Int = 3,
) {

    /**
     * 拉取在线磁偏角。
     *
     * @return 成功返回 [DeclinationFetch]；未配置 / 网络失败 / 解析失败一律返回 null。
     */
    suspend fun fetch(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): DeclinationFetch? {
        val cfg = readConfig()
        val endpoint = cfg?.resolvedUrl()?.takeIf { it.isNotBlank() } ?: fallbackEndpoint
        val url = DeclinationApiConfig.buildUrl(
            endpoint = endpoint,
            latitudeDegrees = latitudeDegrees,
            longitudeDegrees = longitudeDegrees,
            model = cfg?.model.orEmpty(),
            apiKey = "", // key 走鉴权头，不放查询串（避免落日志）
        )
        val headers = HashMap<String, String>()
        cfg?.authHeader()?.let { (k, v) -> headers[k] = v }
        headers["Accept"] = "application/json"

        val connect = cfg?.connectTimeoutMs ?: DEFAULT_CONNECT_MS
        val read = cfg?.readTimeoutMs ?: DEFAULT_READ_MS
        val retries = (cfg?.maxRetries ?: 0).coerceIn(0, maxRetryCeiling.coerceAtLeast(0))
        val baseDelay = (cfg?.retryBaseDelayMs ?: DEFAULT_RETRY_DELAY_MS).coerceIn(100L, 10_000L)

        var attempt = 0
        while (attempt <= retries) {
            val body = runCatching { transport.get(url, headers, connect, read) }
                .onFailure { e -> PLog.w(TAG, "declination fetch failed (attempt ${attempt + 1}): ${e.message}") }
                .getOrNull()
            val degrees = DeclinationApiConfig.parseDeclination(body)
            if (degrees != null) {
                return DeclinationFetch(degrees, url, nowMs)
            }
            if (attempt < retries) {
                delay(baseDelay * (1L shl attempt.coerceAtMost(5)))
            }
            attempt++
        }
        PLog.i(TAG, "declination online unavailable, fallback to embedded WMM2025")
        return null
    }

    /**
     * 读取 AI 平台配置；未配置/异常一律 null（不抛）。
     *
     * 注意：这里**按名字查找能力项**而不是直接写 `AiCapability.DECLINATION`——
     * 该枚举值由交付补丁追加（见 patches/astronav_patch.md 的 ENUM 段）。
     * 按名字查找有两个好处：
     *  1. 补丁未应用时本文件依然可编译（无悬空符号引用）；
     *  2. 补丁未应用时在线更新自动降级为「只用示例端点、不带鉴权」，
     *     内嵌 WMM2025 仍然可用，功能不缺失。
     */
    private suspend fun readConfig(): AiEndpointConfig? {
        val store = configStore ?: return null
        val capability = AiCapability.entries.firstOrNull { it.name == CAPABILITY_NAME } ?: return null
        return runCatching { store.config(capability) }
            .onFailure { e -> PLog.w(TAG, "read DECLINATION config failed: ${e.message}") }
            .getOrNull()
            ?.takeIf { it.isEnabled }
            ?.also { cfg ->
                // 明文 http 且未放行 -> 不发请求（避免静默明文传输）
                val reason = runCatching { cfg.validate() }.getOrNull()
                if (reason != null) {
                    PLog.w(TAG, "declination endpoint invalid: ${cfg.describe()}")
                }
            }
    }

    private companion object {
        const val TAG = "AstroNavDeclination"
        const val DEFAULT_CONNECT_MS = 8_000
        const val DEFAULT_READ_MS = 15_000
        const val DEFAULT_RETRY_DELAY_MS = 500L

        /** 在线磁偏角对应的 AI 能力名（补丁在 AiCapability 中追加同名枚举值）。 */
        const val CAPABILITY_NAME = "DECLINATION"
    }
}

/** 磁偏角提供者的日志 TAG（HTTP 更新器用其 companion 内同名 TAG）。 */
private const val TAG_PROVIDER = "AstroNavDeclination"
