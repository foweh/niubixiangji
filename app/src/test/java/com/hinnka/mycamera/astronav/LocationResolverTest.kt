/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**手动地点兜底（正常/边界/异常）**——经纬度文本兜底、离线城市表精确/模糊搜索、
 *       自动分流入口、离线表查询与最近城市、定位来源择优（GPS > 网络、精度提升阈值）、
 *       定位模型状态语义。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResolverTest {

    private val resolver = ManualLocationResolver()
    private val catalog = OfflinePlaceCatalog

    // ---------------- 经纬度文本兜底 ----------------

    @Test
    fun coordinate_input_succeeds_and_is_labelled_manual() {
        val r = resolver.resolveCoordinates("39.9042, 116.4074")
        assertTrue(r.isSuccess)
        assertEquals(LocationSource.MANUAL_INPUT, r.location!!.source)
        assertEquals(LocationStatus.MANUAL, r.location!!.status)
        assertEquals(39.9042, r.location!!.point!!.latitudeDegrees, 1e-6)
        assertEquals(116.4074, r.location!!.point!!.longitudeDegrees, 1e-6)
        assertTrue(r.location!!.message.contains("手动坐标"))
        assertTrue(r.location!!.isUsable)
        // 手动输入视为精确（精度 0）
        assertEquals(0f, r.location!!.accuracyMeters, 1e-6f)
        assertNull(r.errorMessage)
    }

    @Test
    fun coordinate_input_accepts_dms_and_carries_altitude() {
        val dms = resolver.resolveCoordinates("39°54'15\"N 116°24'27\"E", altitudeMeters = 1200.0)
        assertTrue(dms.isSuccess)
        assertEquals(39.904167, dms.location!!.point!!.latitudeDegrees, 1e-6)
        assertEquals(1200.0, dms.location!!.point!!.altitudeMeters, 1e-6)

        // 归一化的输入应把说明带进 message
        val wrapped = resolver.resolveCoordinates("0, 361")
        assertTrue(wrapped.isSuccess)
        assertTrue("应包含归一化说明: ${wrapped.location!!.message}", wrapped.location!!.message.contains("归一化"))
    }

    @Test
    fun altitude_is_clamped_or_zeroed() {
        // 越界海拔 -> clamp 到配置范围
        val high = resolver.resolveCoordinates("0, 0", altitudeMeters = 1e9)
        assertEquals(AstroNavConfig.ALTITUDE_MAX, high.location!!.point!!.altitudeMeters, 1e-6)
        val low = resolver.resolveCoordinates("0, 0", altitudeMeters = -1e9)
        assertEquals(AstroNavConfig.ALTITUDE_MIN, low.location!!.point!!.altitudeMeters, 1e-6)
        // 非有限海拔 -> 0
        assertEquals(0.0, resolver.resolveCoordinates("0, 0", Double.NaN).location!!.point!!.altitudeMeters, 1e-9)
        assertEquals(
            0.0,
            resolver.resolveCoordinates("0, 0", Double.POSITIVE_INFINITY).location!!.point!!.altitudeMeters,
            1e-9,
        )
    }

    @Test
    fun coordinate_input_boundaries() {
        assertTrue(resolver.resolveCoordinates("90, 180").isSuccess)
        assertTrue(resolver.resolveCoordinates("-90, -180").isSuccess)
        assertTrue(resolver.resolveCoordinates("0, 0").isSuccess)
        // 越界 -> 中文归因，不抛异常
        assertTrue(resolver.resolveCoordinates("90.0001, 0").errorMessage!!.contains("纬度"))
        assertTrue(resolver.resolveCoordinates("0, 400").errorMessage!!.contains("经度"))
    }

    @Test
    fun coordinate_input_errors_are_chinese_hints() {
        assertEquals("请输入经纬度，如 39.9042, 116.4074", resolver.resolveCoordinates("").errorMessage)
        assertEquals("请输入经纬度，如 39.9042, 116.4074", resolver.resolveCoordinates("   ").errorMessage)
        assertEquals("请输入经纬度，如 39.9042, 116.4074", resolver.resolveCoordinates(null).errorMessage)

        val nan = resolver.resolveCoordinates("NaN, 0")
        assertTrue(nan.errorMessage!!.contains("必须是数字"))

        val bad = resolver.resolveCoordinates("abc, def")
        assertTrue(bad.errorMessage!!.contains("无法识别"))

        val lat = resolver.resolveCoordinates("91, 116")
        assertTrue(lat.errorMessage!!.contains("-90 到 90"))
        // |lon| <= 360 视为可归一化 -> 成功；超过则报经度越界
        val lon = resolver.resolveCoordinates("39, -359.5")
        assertTrue(lon.isSuccess)
        assertEquals(0.5, lon.location!!.point!!.longitudeDegrees, 1e-6)
        assertTrue(resolver.resolveCoordinates("39, 720").errorMessage!!.contains("-180 到 180"))

        // 失败结果不带位置
        assertNull(bad.location)
        assertFalse(bad.isSuccess)
    }

    // ---------------- 离线城市表 ----------------

    @Test
    fun catalog_contains_china_and_jilin_entries() {
        assertTrue("离线表规模: ${catalog.size}", catalog.size >= 80)
        val beijing = catalog.byName("北京")
        assertNotNull(beijing)
        assertEquals(39.9042, beijing!!.latitudeDegrees, 1e-4)
        assertEquals(116.4074, beijing.longitudeDegrees, 1e-4)
        assertEquals("北京市", beijing.region)

        // 吉林省内 7 城（用户所在区域）
        for (name in listOf("吉林", "延吉", "通化", "四平", "松原", "白城", "白山")) {
            assertNotNull("缺少离线地点: $name", catalog.byName(name))
        }
        // 名称含空白时 byName 会 trim，内部空白不匹配
        assertNotNull(catalog.byName("  北京  "))
        assertNull(catalog.byName("北 京"))
    }

    @Test
    fun catalog_lookup_is_case_and_space_insensitive_by_any_name() {
        assertEquals("北京", catalog.byAnyName("北京")!!.nameZh)
        assertEquals("北京", catalog.byAnyName("Beijing")!!.nameZh)
        assertEquals("北京", catalog.byAnyName("beijing")!!.nameZh)
        assertEquals("北京", catalog.byAnyName(" Bei Jing ")!!.nameZh)
        // 拼音与英文名不一致的特殊条目（哈尔滨/西安）
        assertEquals("哈尔滨", catalog.byAnyName("haerbin")!!.nameZh)
        assertEquals("西安", catalog.byAnyName("xian")!!.nameZh)
        assertNull(catalog.byAnyName(""))
        assertNull(catalog.byAnyName("   "))
        assertNull(catalog.byAnyName("不存在的城市"))
    }

    @Test
    fun catalog_search_ranking_and_limits() {
        // 精确/前缀优先
        val hits = catalog.search("bei")
        assertTrue(hits.isNotEmpty())
        assertEquals("北京", hits[0].nameZh)

        // 空查询不返回全表
        assertEquals(0, catalog.search("").size)
        assertEquals(0, catalog.search("   ").size)
        assertEquals(0, catalog.search("zzzzzz").size)

        // limit 越界 clamp
        assertEquals(1, catalog.search("hai", limit = 1).size)
        assertEquals(1, catalog.search("hai", limit = 0).size)
        assertTrue(catalog.search("a", limit = 999).size <= OfflinePlaceCatalog.MAX_RESULTS)

        // 中文包含匹配（省级）
        val jilin = catalog.search("吉")
        assertTrue("应能搜到吉林省内城市: $jilin", jilin.any { it.nameZh == "吉林" })
    }

    @Test
    fun catalog_nearest_picks_the_closest_city() {
        assertEquals("北京", catalog.nearest(39.9042, 116.4074)!!.nameZh)
        assertEquals("吉林", catalog.nearest(43.8378, 126.5495)!!.nameZh)
        assertEquals("延吉", catalog.nearest(42.9084, 129.5096)!!.nameZh)
        // 非有限输入不抛异常
        assertNull(catalog.nearest(Double.NaN, 0.0))
        assertNull(catalog.nearest(0.0, Double.POSITIVE_INFINITY))
    }

    // ---------------- 城市名兜底 ----------------

    @Test
    fun place_name_resolution_success_paths() {
        val bj = resolver.resolvePlaceName("北京")
        assertTrue(bj.isSuccess)
        assertEquals(LocationSource.OFFLINE_SEARCH, bj.location!!.source)
        assertEquals(LocationStatus.MANUAL, bj.location!!.status)
        assertTrue(bj.location!!.message.contains("离线地点：北京"))
        assertTrue(bj.location!!.point!!.latitudeDegrees in 39.0..41.0)

        // 拼音 / 英文名 / 带空格
        assertTrue(resolver.resolvePlaceName("beijing").isSuccess)
        assertTrue(resolver.resolvePlaceName("Bei Jing").isSuccess)
        assertTrue(resolver.resolvePlaceName("  changchun ").isSuccess)
    }

    @Test
    fun place_name_resolution_multi_hit_returns_candidates() {
        // "吉" 命中「吉林」与「延吉」-> 需要用户选择
        val r = resolver.resolvePlaceName("吉")
        if (r.isSuccess) {
            // 若未来表中只剩一条，也应给出候选
            assertTrue(r.candidates.isNotEmpty())
        } else {
            assertTrue("应给出候选: ${r.errorMessage}", r.candidates.size >= 2)
            assertTrue(r.errorMessage!!.contains("匹配到"))
            assertTrue(r.errorMessage!!.contains("吉林"))
        }
    }

    @Test
    fun place_name_resolution_error_paths() {
        assertEquals(
            "请输入城市名（如「北京」或「beijing」）",
            resolver.resolvePlaceName("").errorMessage,
        )
        assertEquals(
            "请输入城市名（如「北京」或「beijing」）",
            resolver.resolvePlaceName("   ").errorMessage,
        )
        assertEquals(
            "请输入城市名（如「北京」或「beijing」）",
            resolver.resolvePlaceName(null).errorMessage,
        )

        val miss = resolver.resolvePlaceName("瓦坎达")
        assertTrue(miss.errorMessage!!.contains("未找到"))
        assertTrue("应提示改用经纬度: ${miss.errorMessage}", miss.errorMessage!!.contains("39.9042"))
        assertFalse(miss.isSuccess)
        assertTrue(miss.candidates.isEmpty())

        // exactOnly 不允许模糊
        val exact = resolver.resolvePlaceName("北", exactOnly = true)
        assertTrue(exact.errorMessage!!.contains("离线地点表中没有"))
        assertNull(exact.location)
    }

    @Test
    fun resolve_place_takes_entry_directly() {
        val entry = catalog.byName("延吉")!!
        val loc = resolver.resolvePlace(entry)
        assertEquals(LocationStatus.MANUAL, loc.status)
        assertEquals(LocationSource.OFFLINE_SEARCH, loc.source)
        assertEquals(entry.latitudeDegrees, loc.point!!.latitudeDegrees, 1e-9)
        assertTrue(loc.message.contains("延吉"))
    }

    // ---------------- 自动分流入口 ----------------

    @Test
    fun resolve_auto_accepts_coordinates_or_city_names() {
        val coord = resolver.resolveAuto("39.9042, 116.4074")
        assertTrue(coord.isSuccess)
        assertEquals(LocationSource.MANUAL_INPUT, coord.location!!.source)

        val city = resolver.resolveAuto("上海")
        assertTrue(city.isSuccess)
        assertEquals(LocationSource.OFFLINE_SEARCH, city.location!!.source)

        val pinyin = resolver.resolveAuto("guangzhou")
        assertTrue(pinyin.isSuccess)
        assertTrue(pinyin.location!!.point!!.longitudeDegrees in 112.0..115.0)
    }

    @Test
    fun resolve_auto_combines_error_hints() {
        val r = resolver.resolveAuto("!!!@@@")
        assertFalse(r.isSuccess)
        assertNotNull(r.errorMessage)
        assertTrue("应同时给出两种输入的提示: ${r.errorMessage}", r.errorMessage!!.contains("；"))
        assertTrue(r.errorMessage!!.contains("无法识别"))
        // 无输入
        val empty = resolver.resolveAuto("")
        assertFalse(empty.isSuccess)
        assertNotNull(empty.errorMessage)
    }

    // ---------------- 来源择优 ----------------

    @Test
    fun source_selection_prefers_accuracy_then_provider_priority() {
        // GPS 优先（默认）
        assertEquals(
            LocationSource.GPS,
            LocationSourceSelector.select(10f, 20f, null),
        )
        // 关闭 GPS 优先后取网络
        assertEquals(
            LocationSource.NETWORK,
            LocationSourceSelector.select(10f, 20f, null, preferGpsOverNetwork = false),
        )
        // 只有网络 / 只有上次已知
        assertEquals(LocationSource.NETWORK, LocationSourceSelector.select(null, 30f, null))
        assertEquals(LocationSource.LAST_KNOWN, LocationSourceSelector.select(null, null, 500f))
        assertEquals(LocationSource.NONE, LocationSourceSelector.select(null, null, null))
        // 非有限精度视为不可用
        assertEquals(LocationSource.NONE, LocationSourceSelector.select(Float.NaN, Float.NEGATIVE_INFINITY, -1f))
    }

    @Test
    fun upgrade_decisions_follow_precision_threshold() {
        // 首次定位总是采用
        assertTrue(LocationSourceSelector.shouldUpgrade(null, 0f, LocationSource.GPS, 100f))

        // 同源：精度提升超过阈值才替换
        assertTrue(LocationSourceSelector.shouldUpgrade(LocationSource.GPS, 20f, LocationSource.GPS, 10f))
        assertFalse(LocationSourceSelector.shouldUpgrade(LocationSource.GPS, 20f, LocationSource.GPS, 18f))
        // 阈值可配
        assertTrue(
            LocationSourceSelector.shouldUpgrade(
                LocationSource.GPS, 20f, LocationSource.GPS, 18f, minImprovementMeters = 1f,
            ),
        )

        // 同等精度不降级（不因网络结果而丢掉更可信的 GPS）
        assertFalse(LocationSourceSelector.shouldUpgrade(LocationSource.GPS, 10f, LocationSource.NETWORK, 10f))

        // 网络结果明显更准（> 阈值）时可替换 GPS
        assertTrue(LocationSourceSelector.shouldUpgrade(LocationSource.GPS, 30f, LocationSource.NETWORK, 5f))

        // 上次已知位置被更准的新定位替代
        assertTrue(LocationSourceSelector.shouldUpgrade(LocationSource.LAST_KNOWN, 1000f, LocationSource.NETWORK, 500f))
        assertFalse(LocationSourceSelector.shouldUpgrade(LocationSource.LAST_KNOWN, 100f, LocationSource.NETWORK, 500f))

        // 非有限精度不接受
        assertFalse(LocationSourceSelector.shouldUpgrade(LocationSource.GPS, 20f, LocationSource.GPS, Float.NaN))
    }

    // ---------------- 定位模型 ----------------

    @Test
    fun location_model_state_semantics() {
        val idle = AstroNavLocation.idle()
        assertFalse(idle.isUsable)
        assertEquals(35f, idle.fallbackLatitude, 1e-6f) // 与既有 AstroOverlayState 默认纬度一致
        assertEquals(LocationStatus.IDLE, idle.status)

        val searching = AstroNavLocation.searching()
        assertFalse(searching.isUsable)
        assertEquals(LocationStatus.SEARCHING, searching.status)

        val denied = AstroNavLocation.permissionDenied()
        assertEquals(LocationStatus.PERMISSION_DENIED, denied.status)
        assertTrue(denied.message.contains("手动输入"))

        val disabled = AstroNavLocation.providerDisabled()
        assertEquals(LocationStatus.PROVIDER_DISABLED, disabled.status)
        assertTrue(disabled.message.contains("手动"))

        val failed = AstroNavLocation.failed("")
        assertEquals(LocationStatus.FAILED, failed.status)
        assertFalse(failed.isUsable)
        assertEquals("定位失败，可手动输入位置", failed.message) // 空消息回落默认文案

        val fixed = AstroNavLocation.fixed(GeoPoint.of(39.9042, 116.4074), LocationSource.GPS, 12.5f, 1_700_000_000_000L)
        assertTrue(fixed.isUsable)
        assertEquals(LocationStatus.FIXED, fixed.status)
        assertTrue(fixed.message.contains("GPS"))
        assertTrue(fixed.message.contains("±12m"))
        assertEquals(39.9042f, fixed.fallbackLatitude, 1e-4f)

        // 无精度信息时不显示 ±
        val coarse = AstroNavLocation.fixed(GeoPoint.of(0.0, 0.0), LocationSource.NETWORK, 0f, 0L)
        assertFalse(coarse.message.contains("±"))
        // 来源语义
        assertTrue(LocationSource.GPS.isPrecise)
        assertTrue(LocationSource.FUSED.isPrecise)
        assertFalse(LocationSource.NETWORK.isPrecise)
        assertFalse(LocationSource.LAST_KNOWN.isPrecise)
        assertTrue(LocationSource.MANUAL_INPUT.displayName.isNotEmpty())
    }
}
