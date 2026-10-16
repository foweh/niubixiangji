/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：磁偏角提供者的三条来源分支（手动 / 在线缓存 / 内嵌 WMM2025）、
 *       在线缓存的 TTL 与位置一致性、非法在线值拒收、模型有效期标记、
 *       真北航向换算、请求 URL 构造与响应解析（含异常输入）。
 * 纯 JVM（提供者只依赖 PLog 与内嵌模型，不依赖 Android 框架）。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagneticDeclinationTest {

    private val t0 = 1_780_272_000_000L // 2026-06-01T00:00:00Z

    private fun provider(cfg: AstroNavConfig) = MagneticDeclinationProvider { cfg }

    /** 内嵌模型 + 固定模型年（便于与金标准逐点比对）。 */
    private fun embeddedModel(year: Double = 2025.0): AstroNavConfig = AstroNavConfig(
        declinationSource = DeclinationSource.EMBEDDED_WMM,
        autoModelYear = false,
        modelDecimalYear = year,
    )

    // ---------------- 分支 1：手动 ----------------

    @Test
    fun manual_source_returns_configured_value() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.MANUAL,
            declinationManualDeg = 12.5f,
            autoModelYear = false,
            modelDecimalYear = 2025.0,
        )
        val r = provider(cfg).declination(41f, 120f, 0.0, t0)
        assertEquals(DeclinationSource.MANUAL, r.source)
        assertEquals(12.5f, r.degrees, 1e-4f)
        assertTrue(r.withinModelValidity)
        // 真北航向 = 磁北航向 + 磁偏角（350 + 12.5 -> 2.5）
        assertEquals(2.5f, r.trueHeadingFrom(350f), 1e-3f)
        assertEquals(0f, r.trueHeadingFrom(347.5f), 1e-3f)
    }

    @Test
    fun manual_declination_is_clamped() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.MANUAL,
            declinationManualDeg = 1_000f, // 越界 -> clamp 到 180
        )
        val r = provider(cfg).declination(0f, 0f, 0.0, t0)
        assertEquals(180f, r.degrees, 1e-3f)
    }

    // ---------------- 分支 2：内嵌 WMM2025 ----------------

    @Test
    fun embedded_source_matches_wmm_reference_value() {
        val r = provider(embeddedModel()).declination(41f, 120f, 0.0, t0)
        assertEquals(DeclinationSource.EMBEDDED_WMM, r.source)
        // 金标准：pygeomag × NOAA WMM_2025.COF 为 -8.733556°
        assertEquals(-8.7336f, r.degrees, 0.05f)
        assertTrue(r.withinModelValidity)
        assertEquals(2025.0, r.decimalYear, 1e-6)
        assertEquals(41f, r.latitudeDegrees, 1e-4f)
        assertEquals(120f, r.longitudeDegrees, 1e-4f)
    }

    @Test
    fun embedded_note_mentions_model_and_year() {
        val r = provider(embeddedModel()).declination(39.9042f, 116.4074f, 0.0, t0)
        assertTrue("说明应含模型名: ${r.note}", r.note.contains("WMM2025"))
        // 北京金标准 -7.493186°
        assertEquals(-7.4932f, r.degrees, 0.05f)
    }

    @Test
    fun configured_model_year_is_clamped_into_validity_window() {
        // 2025.0–2030.0 之外的配置会被 clamp，因此提供者给出的年份始终在模型有效期内
        val latest = provider(embeddedModel(year = 2_100.0)).declination(41f, 120f, 0.0, t0)
        assertEquals(2030.0, latest.decimalYear, 1e-6)
        assertTrue(latest.withinModelValidity)

        val earliest = provider(embeddedModel(year = 1_900.0)).declination(41f, 120f, 0.0, t0)
        assertEquals(2025.0, earliest.decimalYear, 1e-6)
        assertTrue(earliest.withinModelValidity)

        // 上界含 2030.0（与 WMM 有效期一致）；年代越晚该点磁偏角越偏西（金标准 -8.7336 -> -8.9289）
        val upper = provider(embeddedModel(year = 2030.0)).declination(41f, 120f, 0.0, t0)
        assertTrue(upper.withinModelValidity)
        assertEquals(-8.9289f, upper.degrees, 0.05f)
    }

    @Test
    fun auto_model_year_follows_clock_and_clamps_to_window() {
        val cfg = AstroNavConfig(autoModelYear = true)
        val now = provider(cfg).declination(41f, 120f, 0.0, t0)
        assertEquals(2026.4127, now.decimalYear, 0.02)
        assertTrue(now.withinModelValidity)

        // 很远的未来 -> clamp 到 2030.0（不会产生 2035 这种越界年）
        val far = provider(cfg).declination(41f, 120f, 0.0, 2_500_000_000_000L)
        assertEquals(2030.0, far.decimalYear, 1e-6)

        // 过去时间 -> clamp 到 2025.0
        val past = provider(cfg).declination(41f, 120f, 0.0, 1_000_000_000_000L)
        assertEquals(2025.0, past.decimalYear, 1e-6)
    }

    @Test
    fun extreme_arguments_are_clamped_and_do_not_throw() {
        val p = provider(embeddedModel())
        val r = p.declination(91f, 999f, 0.0, t0)
        assertEquals(90f, r.latitudeDegrees, 1e-3f) // 纬度 clamp 到 ±90
        assertTrue(r.degrees.isFinite())
        // NaN 输入不抛异常
        val nan = p.declination(Float.NaN, Float.NaN, Double.NaN, t0)
        assertTrue(nan.latitudeDegrees.isFinite())
    }

    // ---------------- 分支 3：在线（缓存 + 回落） ----------------

    @Test
    fun online_source_falls_back_to_embedded_when_no_cache() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.ONLINE_API,
            autoModelYear = false,
            modelDecimalYear = 2025.0,
        )
        val r = provider(cfg).declination(41f, 120f, 0.0, t0)
        assertEquals(DeclinationSource.EMBEDDED_WMM, r.source)
        assertTrue(r.note.contains("回落"))
        assertEquals(-8.7336f, r.degrees, 0.05f)
    }

    @Test
    fun fresh_online_cache_is_used_within_ttl_and_nearby_position() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.ONLINE_API,
            autoModelYear = false,
            modelDecimalYear = 2025.0,
            declinationCacheTtlMs = 3_600_000L,
        )
        val p = provider(cfg)
        p.setOnlineResult(41f, 120f, -8.70f, t0)
        assertTrue(p.hasFreshOnlineResult(41.1f, 120.2f, t0 + 1_000L))

        val r = p.declination(41.1f, 120.2f, 0.0, t0 + 1_000L)
        assertEquals(DeclinationSource.ONLINE_API, r.source)
        assertEquals(-8.70f, r.degrees, 1e-4f)
        assertTrue(r.note.contains("在线"))
    }

    @Test
    fun online_cache_expires_by_ttl_position_and_clock_skew() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.ONLINE_API,
            autoModelYear = false,
            modelDecimalYear = 2025.0,
            declinationCacheTtlMs = 60_000L,
        )
        val p = provider(cfg)
        p.setOnlineResult(41f, 120f, -8.70f, t0)

        // TTL 过期
        assertEquals(DeclinationSource.EMBEDDED_WMM, p.declination(41f, 120f, 0.0, t0 + 60_001L).source)
        // 位置相距 > 1°（约 111km）
        assertEquals(DeclinationSource.EMBEDDED_WMM, p.declination(45f, 125f, 0.0, t0).source)
        // 时钟回拨
        assertEquals(DeclinationSource.EMBEDDED_WMM, p.declination(41f, 120f, 0.0, t0 - 5_000L).source)
        // 经度回绕仍视为同一位置（119.5 与 -240.5 等价）
        p.setOnlineResult(41f, 119.5f, -8.6f, t0)
        assertEquals(DeclinationSource.ONLINE_API, p.declination(41f, -240.5f, 0.0, t0).source)
    }

    @Test
    fun invalid_or_expired_online_values_are_rejected() {
        val cfg = AstroNavConfig(
            declinationSource = DeclinationSource.ONLINE_API,
            autoModelYear = false,
            modelDecimalYear = 2025.0,
        )
        val p = provider(cfg)
        p.setOnlineResult(41f, 120f, 500f, t0)             // 越界
        p.setOnlineResult(41f, 120f, Float.NaN, t0)          // 非数
        assertEquals(DeclinationSource.EMBEDDED_WMM, p.declination(41f, 120f, 0.0, t0).source)

        // TTL 设为 0 -> 视为禁用在线缓存
        val disabled = provider(cfg.copy(declinationCacheTtlMs = 0L))
        disabled.setOnlineResult(41f, 120f, -8.7f, t0)
        assertFalse(disabled.hasFreshOnlineResult(41f, 120f, t0))
        assertEquals(DeclinationSource.EMBEDDED_WMM, disabled.declination(41f, 120f, 0.0, t0).source)

        // 手动失效
        p.setOnlineResult(41f, 120f, -8.7f, t0)
        p.invalidateOnlineCache()
        assertFalse(p.hasFreshOnlineResult(41f, 120f, t0))
    }

    // ---------------- 请求构造 / 响应解析（纯函数） ----------------

    @Test
    fun build_url_contains_required_parameters() {
        val url = DeclinationApiConfig.buildUrl(
            endpoint = DeclinationApiConfig.DEFAULT_ENDPOINT,
            latitudeDegrees = 41.0,
            longitudeDegrees = 120.0,
        )
        assertTrue(url.startsWith(DeclinationApiConfig.DEFAULT_ENDPOINT))
        assertTrue(url.contains("lat1=41.0"))
        assertTrue(url.contains("lon1=120.0"))
        assertTrue(url.contains("resultFormat=json"))
        assertFalse("未配置密钥时不得出现 key 参数", url.contains("key="))

        val withKey = DeclinationApiConfig.buildUrl(
            endpoint = DeclinationApiConfig.DEFAULT_ENDPOINT,
            latitudeDegrees = 41.0,
            longitudeDegrees = 120.0,
            model = "WMM-2025",
            apiKey = "DEMO",
        )
        assertTrue(withKey.contains("key=DEMO"))
        assertTrue(withKey.contains("model=WMM-2025"))
    }

    @Test
    fun build_url_clamps_and_normalizes_coordinates() {
        val url = DeclinationApiConfig.buildUrl(
            endpoint = "",
            latitudeDegrees = 400.0,
            longitudeDegrees = 200.0,
        )
        assertTrue(url.startsWith(DeclinationApiConfig.DEFAULT_ENDPOINT))
        assertTrue("纬度应 clamp 到 90: $url", url.contains("lat1=90.0"))
        assertTrue("经度应回绕到 -160: $url", url.contains("lon1=-160.0"))

        // 端点自带查询串时使用 '&' 续接
        val withQuery = DeclinationApiConfig.buildUrl(
            endpoint = "https://example.invalid/decl?foo=1",
            latitudeDegrees = 0.0,
            longitudeDegrees = 0.0,
        )
        assertTrue(withQuery.contains("?foo=1&lat1=0.0"))
    }

    @Test
    fun parse_declination_handles_quoted_bare_and_bad_payloads() {
        assertEquals(
            -8.77f,
            DeclinationApiConfig.parseDeclination("""{"result":[{"declination":"-8.77","declination_sv":"0.1"}]}"""),
            1e-4f,
        )
        assertEquals(
            7.5f,
            DeclinationApiConfig.parseDeclination("""{"declination": 7.5}"""),
            1e-4f,
        )
        // 异常/缺失
        assertNull(DeclinationApiConfig.parseDeclination(null))
        assertNull(DeclinationApiConfig.parseDeclination(""))
        assertNull(DeclinationApiConfig.parseDeclination("   "))
        assertNull(DeclinationApiConfig.parseDeclination("{}"))
        assertNull(DeclinationApiConfig.parseDeclination("""{"declination":"abc"}"""))
        assertNull(DeclinationApiConfig.parseDeclination("""{"declination":"500"}"""))
        assertNull(DeclinationApiConfig.parseDeclination("not json at all"))
        assertNull(DeclinationApiConfig.parseDeclination("""{"declination":""}"""))
    }
}
