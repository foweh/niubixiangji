/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**GPS 解析边界 / 手动地点兜底（正常·边界·异常）** 中的经纬度文本解析部分——
 *       十进制、方位后缀、度分秒（含多 token 写法）、全角逗号、范围裁决与归因、
 *       GeoPoint 归一化与距离近似。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTextParserTest {

    private fun ok(text: String?): GeoPoint {
        val r = GeoTextParser.parsePair(text)
        assertTrue("应解析成功: \"$text\" -> ${r.error}", r.isSuccess)
        assertNotNull(r.point)
        return r.point!!
    }

    private fun err(text: String?): GeoParseError? = GeoTextParser.parsePair(text).error

    private fun assertNear(expected: Double, actual: Double, tol: Double = 1e-4) =
        assertEquals(expected, actual, tol)

    // ---------------- 正常：十进制 ----------------

    @Test
    fun decimal_pairs_parse_in_multiple_separators() {
        assertNear(39.9042, ok("39.9042, 116.4074").latitudeDegrees)
        assertNear(116.4074, ok("39.9042, 116.4074").longitudeDegrees)

        // 空格 / 分号 / 全角逗号 / 多余空白
        assertNear(39.9042, ok("39.9042 116.4074").latitudeDegrees)
        assertNear(39.9042, ok("39.9042;116.4074").latitudeDegrees)
        assertNear(39.9042, ok("39.9042，116.4074").latitudeDegrees)
        assertNear(39.9042, ok("   39.9042 \t 116.4074  ").latitudeDegrees)

        // 负值（南纬 / 西经）
        val sydney = ok("-33.8688, 151.2093")
        assertNear(-33.8688, sydney.latitudeDegrees)
        val nyc = ok("40.7128, -74.0060")
        assertNear(-74.0060, nyc.longitudeDegrees)

        // 赤道/本初子午线
        val origin = ok("0, 0")
        assertNear(0.0, origin.latitudeDegrees)
        assertNear(0.0, origin.longitudeDegrees)

        // 多点输入取前两个（第三个当作海拔忽略，不报错）
        assertNear(39.9042, ok("39.9042, 116.4074, 100").latitudeDegrees)
        // 标签与精度
        assertEquals("手动输入", origin.label)
        assertEquals(0f, origin.accuracyMeters, 0f)
    }

    @Test
    fun decimal_with_hemisphere_suffix_is_normalised() {
        val nw = ok("39.9042N, 116.4074E")
        assertNear(39.9042, nw.latitudeDegrees)
        assertNear(116.4074, nw.longitudeDegrees)

        // 南纬西经：后缀决定符号，前缀符号与之相乘
        val sw = ok("33.8688S, 151.2093W")
        assertNear(-33.8688, sw.latitudeDegrees)
        assertNear(-151.2093, sw.longitudeDegrees)

        val lower = ok("39.9042n, 116.4074e")
        assertNear(39.9042, lower.latitudeDegrees)

        // 带度符号的十进制
        val deg = ok("39.9042°N, 116.4074°E")
        assertNear(39.9042, deg.latitudeDegrees)
    }

    // ---------------- 正常：度分秒 ----------------

    @Test
    fun dms_pairs_parse_for_all_common_notations() {
        // 标准度分秒
        val a = ok("39°54'15\"N 116°24'27\"E")
        assertNear(39.904167, a.latitudeDegrees, 1e-6)
        assertNear(116.407500, a.longitudeDegrees, 1e-6)

        // 度分（小数分）
        val b = ok("39°54.25'N 116°24.45'E")
        assertNear(39.904167, b.latitudeDegrees, 1e-6)
        assertNear(116.407500, b.longitudeDegrees, 1e-6)

        // 空格分隔 + 方位后缀（中文输入法常见）
        val c = ok("39 54.25 N 116 24.45 E")
        assertNear(39.904167, c.latitudeDegrees, 1e-6)
        assertNear(116.407500, c.longitudeDegrees, 1e-6)

        // 南半球（悉尼）
        val sydney = ok("33°51'54\"S 151°12'36\"E")
        assertNear(-33.865000, sydney.latitudeDegrees, 1e-6)
        assertNear(151.210000, sydney.longitudeDegrees, 1e-6)

        // 无方位字母、仅度符号：按「度分秒」分组推断
        val noLetter = ok("39°54'15\" 116°24'27\"")
        assertNear(39.904167, noLetter.latitudeDegrees, 1e-6)
        assertNear(116.407500, noLetter.longitudeDegrees, 1e-6)

        // "度" 关键字与全角空格
        val cn = ok("39度54分15秒N 116度24分27秒E")
        assertNear(39.904167, cn.latitudeDegrees, 1e-3)
    }

    @Test
    fun dms_component_parsing_handles_signs_and_units() {
        assertNear(39.904167, GeoTextParser.parseDmsComponent("39°54'15\"N", true)!!, 1e-6)
        assertNear(-33.865, GeoTextParser.parseDmsComponent("33°51'54\"S", true)!!, 1e-6)
        assertNear(120.4075, GeoTextParser.parseDmsComponent("120°24'27\"E", false)!!, 1e-6)
        assertNear(-45.0, GeoTextParser.parseDmsComponent("45°W", false)!!, 1e-6)
        assertNear(39.904167, GeoTextParser.parseDmsComponent("39 54.25 N", true)!!, 1e-6)
        // 负号前缀
        assertNear(-33.868889, GeoTextParser.parseDmsComponent("-33 52 8", true)!!, 1e-6)
        // 仅度
        assertNear(91.0, GeoTextParser.parseDmsComponent("91°N", true)!!, 1e-6) // 范围裁决交给 combine()

        // 非法：分/秒 >= 60
        assertNull(GeoTextParser.parseDmsComponent("39°75'N", true))
        assertNull(GeoTextParser.parseDmsComponent("39°54'90\"N", true))
        // 非法：无数字 / 空
        assertNull(GeoTextParser.parseDmsComponent("abc", true))
        assertNull(GeoTextParser.parseDmsComponent("", true))
        assertNull(GeoTextParser.parseDmsComponent(null, true))
        assertNull(GeoTextParser.parseDmsComponent("N", true))
    }

    // ---------------- 边界 ----------------

    @Test
    fun boundary_coordinates_are_accepted() {
        val n = ok("90, 0")
        assertNear(90.0, n.latitudeDegrees)
        val s = ok("-90, 180")
        assertNear(-90.0, s.latitudeDegrees)
        assertNear(180.0, s.longitudeDegrees)
        val w = ok("0, -180")
        assertNear(-180.0, w.longitudeDegrees)
        // 与 ±180 等价的 360 会被归一化（合法）
        val wrap = GeoTextParser.parsePair("0, 360")
        assertTrue(wrap.isSuccess)
        assertNear(0.0, wrap.point!!.longitudeDegrees)
        assertTrue("应给出归一化说明", wrap.note.isNotEmpty())
    }

    @Test
    fun latitude_out_of_range_is_rejected_not_clamped() {
        // 不 clamp，避免用户以为已被修正
        assertEquals(GeoParseError.LATITUDE_OUT_OF_RANGE, err("91, 0"))
        assertEquals(GeoParseError.LATITUDE_OUT_OF_RANGE, err("-90.1, 0"))
        assertEquals(GeoParseError.LATITUDE_OUT_OF_RANGE, err("91°30'N 20°E"))
        assertEquals(GeoParseError.LATITUDE_OUT_OF_RANGE, err("91.5, 116"))
    }

    @Test
    fun longitude_wrapping_and_range_decisions() {
        // 361 -> 1（归一化并给出说明）
        val r = GeoTextParser.parsePair("0, 361")
        assertTrue(r.isSuccess)
        assertNear(1.0, r.point!!.longitudeDegrees)
        assertTrue(r.note.isNotEmpty())

        // 359.5 -> -0.5
        assertNear(-0.5, ok("0, 359.5").longitudeDegrees)
        // |lon| > 360 -> 非法
        assertEquals(GeoParseError.LONGITUDE_OUT_OF_RANGE, err("0, 400"))
        assertEquals(GeoParseError.LONGITUDE_OUT_OF_RANGE, err("0, -361"))
        // 无归一化时不应产生说明
        assertEquals("", GeoTextParser.parsePair("39.9042, 116.4074").note)
    }

    // ---------------- 异常 ----------------

    @Test
    fun malformed_input_is_reported_not_thrown() {
        assertEquals(GeoParseError.EMPTY, err(""))
        assertEquals(GeoParseError.EMPTY, err("    "))
        assertEquals(GeoParseError.EMPTY, err(null))

        // 只有一个分量 / 全是文字
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("39.9042"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("39.9042N"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("北京市"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("abc, def"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("39.9042, abc"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err("91°"))
        assertEquals(GeoParseError.UNRECOGNISED_FORMAT, err(",,"))

        // NaN / 溢出（Double 解析器接受 "NaN"/"Infinity"，必须被拦下）
        assertEquals(GeoParseError.NOT_A_NUMBER, err("NaN, 0"))
        assertEquals(GeoParseError.NOT_A_NUMBER, err("0, NaN"))
        assertEquals(GeoParseError.LATITUDE_OUT_OF_RANGE, err("1e999, 0"))

        // 失败结果不带点
        assertNull(GeoTextParser.parsePair("abc, def").point)
        assertFalse(GeoTextParser.parsePair("abc, def").isSuccess)
    }

    @Test
    fun parse_pair_never_throws_for_arbitrary_text() {
        val samples = listOf(
            "  ", "0", "0,", ",0", "--1,2", "+39.9, +116.4", "39.9,,116.4",
            "39°54'15\"X 116°24'27\"E", "N39.9 E116.4", "39.9E, 116.4N",
            "39.9, 116.4, 100, 200", "９９, １００", "12°34'56\"", "\u0000,\u0001",
        )
        for (s in samples) {
            val r = GeoTextParser.parsePair(s) // 只需不抛异常
            assertTrue("结果必须自洽: \"$s\"", r.isSuccess || r.error != null)
        }
    }

    // ---------------- GeoPoint ----------------

    @Test
    fun geo_point_normalisation_clamps_every_field() {
        val p = GeoPoint(95.0, 190.0, Double.NaN, -5f)
        val n = p.normalized()
        assertEquals(90.0, n.latitudeDegrees, 1e-9)
        assertEquals(-170.0, n.longitudeDegrees, 1e-9)
        assertEquals(0.0, n.altitudeMeters, 1e-9)
        assertEquals(0f, n.accuracyMeters, 1e-6f)

        // 非有限的经纬度 -> 0
        val bad = GeoPoint(Double.NaN, Double.POSITIVE_INFINITY).normalized()
        assertEquals(0.0, bad.latitudeDegrees, 1e-9)
        assertEquals(0.0, bad.longitudeDegrees, 1e-9)
        assertTrue(bad.isFinite)

        // 海拔被 clamp 到配置范围
        val alt = GeoPoint(0.0, 0.0, 1e9).normalized()
        assertTrue(alt.altitudeMeters <= AstroNavConfig.ALTITUDE_MAX)

        assertFalse(GeoPoint(Double.NaN, 0.0).isFinite)
        // of() 链式构造即归一化
        assertEquals(-170.0, GeoPoint.of(95.0, 190.0).longitudeDegrees, 1e-9)
    }

    @Test
    fun geo_point_distance_is_reasonable_and_wraps_antimeridian() {
        val origin = GeoPoint.of(0.0, 0.0)
        // 1° 纬度 ≈ 111km
        val oneDegNorth = origin.distanceMetersTo(GeoPoint.of(1.0, 0.0))
        assertTrue("1° 纬度应约 111km: $oneDegNorth", oneDegNorth in 110_000.0..112_000.0)
        // 1° 经度在赤道同样 ≈ 111km，在高纬显著变小
        val oneDegEast = origin.distanceMetersTo(GeoPoint.of(0.0, 1.0))
        assertTrue("赤道 1° 经度应约 111km: $oneDegEast", oneDegEast in 110_000.0..112_000.0)
        val highLat = GeoPoint.of(60.0, 0.0).distanceMetersTo(GeoPoint.of(60.0, 1.0))
        assertTrue("高纬 1° 经度应明显更小: $highLat", highLat < oneDegEast * 0.6)
        // 同点距离 0
        assertEquals(0.0, origin.distanceMetersTo(GeoPoint.of(0.0, 0.0)), 1e-6)
        // 跨 180° 经线取短路径（1° 而不是 359°）
        val across = GeoPoint.of(0.0, 179.5).distanceMetersTo(GeoPoint.of(0.0, -179.5))
        assertTrue("应走短路径: $across", across in 110_000.0..112_000.0)
    }
}
