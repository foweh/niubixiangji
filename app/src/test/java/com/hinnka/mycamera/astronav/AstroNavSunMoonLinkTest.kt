/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**与既有 astro/astroplan 功能的联动契约**——恒星时（复用既有 suncalc GMST 公式）、
 *       太阳/月亮位置（复用既有 SunCalc，仅做方位角口径转换）、月相光照比例、
 *       星表取星（StarCatalog.INDEX_BY_EN 是「下标」而非条目，防回归）、
 *       目标 -> 屏幕投影链路、瞄准引导文案。
 * 断言含两组独立金标准（中天时刻的高度角/方位角闭式解），与实现公式互相校验。
 * 纯 JVM，无 Android 依赖（复用既有 ui.astro.AstroProjection 与 astroplan.SunCalc）。
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.astroplan.SunCalc
import com.hinnka.mycamera.ui.astro.StarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroNavSunMoonLinkTest {

    /** 2026-01-01T00:00:00Z */
    private val t2026Start = 1_767_225_600_000L

    /** 2026-06-21T04:14Z ≈ 北京（116.41°E）夏至正午 */
    private val tSummerNoon = 1_782_015_240_000L

    /** 2026-12-21T04:14Z ≈ 北京冬至正午 */
    private val tWinterNoon = 1_797_826_440_000L

    private val beijingLat = 39.9042
    private val beijingLon = 116.4074

    // ---------------- 恒星时 ----------------

    @Test
    fun gmst_matches_suncalc_formula_and_known_value() {
        // 契约：GMST = 280.16 + 360.9856235 × (J2000 起的天数)，取自既有 SunCalc
        val days = SunCalc.toDays(t2026Start)
        val expected = AstroNavAngles.wrap360((280.16 + 360.9856235 * days).toFloat())
        assertEquals(expected, AstroNavSiderealTime.gmstDegrees(t2026Start), 1e-3f)

        // 天文常识：1 月 1 日 00:00 UT 的 GMST ≈ 6h40m（≈ 99.6°）
        val gmst = AstroNavSiderealTime.gmstDegrees(t2026Start)
        assertTrue("GMST=$gmst", gmst in 99f..100.2f)
        // 归一化到 [0, 360)
        assertTrue(gmst >= 0f && gmst < 360f)

        // 一天后 GMST 前进约 360.99°（消去整圈后约 +0.99°）
        val next = AstroNavSiderealTime.gmstDegrees(t2026Start + 86_400_000L)
        val delta = AstroNavAngles.wrap180(next - gmst)
        assertTrue("一天 GMST 增量=$delta", delta in 0.5f..1.5f)
    }

    @Test
    fun lst_combines_gmst_with_east_positive_longitude() {
        val gmst = AstroNavSiderealTime.gmstDegrees(t2026Start)
        val lst = AstroNavSiderealTime.lstDegrees(t2026Start, beijingLon)
        assertEquals(AstroNavAngles.wrap360(gmst + beijingLon.toFloat()), lst, 1e-3f)
        // 北京 116.41°E -> 约 216.0°
        assertTrue("LST=$lst", lst in 215.4f..216.6f)
        // 小时口径 = 度 / 15
        assertEquals(lst / 15f, AstroNavSiderealTime.lstHours(t2026Start, beijingLon), 1e-4f)
        // 西经为负（纽约 -74°）
        val ny = AstroNavSiderealTime.lstDegrees(t2026Start, -74.006)
        assertEquals(AstroNavAngles.wrap360(gmst - 74.006f), ny, 1e-3f)
        // 非法经度按 0 格林尼治处理
        assertEquals(gmst, AstroNavSiderealTime.lstDegrees(t2026Start, Double.NaN), 1e-3f)
    }

    @Test
    fun lst_hours_with_offset_keeps_overlay_semantics() {
        val base = AstroNavSiderealTime.lstHours(t2026Start, beijingLon)
        // 15° = 1h 偏移
        assertEquals(
            (base + 1f + 24f) % 24f,
            AstroNavSiderealTime.lstHoursWithOffset(t2026Start, beijingLon, 15f, 0f),
            1e-4f,
        )
        assertEquals(
            (base + 2f + 24f) % 24f,
            AstroNavSiderealTime.lstHoursWithOffset(t2026Start, beijingLon, 15f, 15f),
            1e-4f,
        )
        assertEquals(
            (base + 23f + 24f) % 24f,
            AstroNavSiderealTime.lstHoursWithOffset(t2026Start, beijingLon, -15f, 0f),
            1e-4f,
        )
        // 非有限偏移按 0 处理，结果始终落在 0..24h
        assertEquals(base, AstroNavSiderealTime.lstHoursWithOffset(t2026Start, beijingLon, Float.NaN, Float.NaN), 1e-4f)
        val wrapped = AstroNavSiderealTime.lstHoursWithOffset(t2026Start, beijingLon, 5_000f, 0f)
        assertTrue("wrapped=$wrapped", wrapped >= 0f && wrapped < 24f)
    }

    // ---------------- 太阳 ----------------

    @Test
    fun sun_target_converts_azimuth_convention() {
        val pos = SunCalc.getPosition(t2026Start, beijingLat, beijingLon)
        val target = AstroNavSunMoonLink.sunTarget(t2026Start, beijingLat, beijingLon)
        assertEquals("sun", target.id)
        assertEquals("太阳", target.nameZh)
        // suncalc「从正南向西为正」-> 本模块「从真北顺时针」= wrap360(az + 180)
        assertEquals(
            AstroNavAngles.wrap360(pos.azimuthDegrees.toFloat() + 180f),
            target.azimuthDegrees,
            1e-3f,
        )
        assertEquals(pos.altitudeDegrees.toFloat(), target.altitudeDegrees, 1e-3f)
        assertEquals(-26.7f, target.magnitude!!, 0f)
        assertTrue(target.azimuthDegrees >= 0f && target.azimuthDegrees < 360f)
        assertTrue(target.altitudeDegrees in -90f..90f)
    }

    @Test
    fun sun_position_matches_solar_noon_reality_in_beijing() {
        // 夏至正午：太阳几乎正南（≈179°），高度 ≈ 73.5°
        val summer = AstroNavSunMoonLink.sunTarget(tSummerNoon, beijingLat, beijingLon)
        assertTrue("夏至高度 = ${summer.altitudeDegrees}", summer.altitudeDegrees in 70f..77f)
        assertTrue("夏至方位 = ${summer.azimuthDegrees}", summer.azimuthDegrees in 170f..190f)
        assertTrue(summer.aboveHorizon)

        // 冬至正午：仍在正南，但高度 ≈ 26.7°
        val winter = AstroNavSunMoonLink.sunTarget(tWinterNoon, beijingLat, beijingLon)
        assertTrue("冬至高度 = ${winter.altitudeDegrees}", winter.altitudeDegrees in 23f..30f)
        assertTrue("冬至方位 = ${winter.azimuthDegrees}", winter.azimuthDegrees in 175f..187f)

        // 正午太阳一定比冬正午高
        assertTrue(summer.altitudeDegrees > winter.altitudeDegrees + 40f)
    }

    // ---------------- 月亮 ----------------

    @Test
    fun moon_target_and_illumination_fraction_are_bounded() {
        val pos = SunCalc.getMoonPosition(t2026Start, beijingLat, beijingLon)
        val moon = AstroNavSunMoonLink.moonTarget(t2026Start, beijingLat, beijingLon)
        assertEquals("moon", moon.id)
        assertEquals("月亮", moon.nameZh)
        assertEquals(
            AstroNavAngles.wrap360(pos.azimuthDegrees.toFloat() + 180f),
            moon.azimuthDegrees,
            1e-3f,
        )
        assertEquals(pos.altitudeDegrees.toFloat(), moon.altitudeDegrees, 1e-3f)

        // 一个朔望月内必然同时出现接近满月与接近新月
        var maxF = 0f
        var minF = 1f
        for (day in 0 until 30) {
            val f = AstroNavSunMoonLink.moonIlluminationFraction(t2026Start + day * 86_400_000L)
            assertTrue("fraction=$f", f.isFinite() && f in 0f..1f)
            if (f > maxF) maxF = f
            if (f < minF) minF = f
        }
        assertTrue("maxFraction=$maxF", maxF > 0.9f)
        assertTrue("minFraction=$minF", minF < 0.1f)
    }

    // ---------------- 星表取星 ----------------

    @Test
    fun star_target_uses_index_map_contract() {
        // 契约：INDEX_BY_EN 的值是 STARS 下标（不是条目本身）
        val idx = StarCatalog.INDEX_BY_EN["Sirius"]
        assertNotNull(idx)
        assertEquals("天狼星", StarCatalog.STARS[idx!!].zhName)
        assertEquals("Sirius", StarCatalog.STARS[StarCatalog.INDEX_BY_EN["Polaris"]!!].enName)

        // 未知名不抛异常
        assertNull(AstroNavSunMoonLink.starTarget("NotAStar", 0f, beijingLat))

        // 取星成功且方位/高度合法
        val sirius = AstroNavSunMoonLink.starTarget("Sirius", 6.75f, beijingLat)
        assertNotNull(sirius)
        assertEquals("Sirius", sirius!!.id)
        assertEquals("天狼星", sirius.nameZh)
        assertEquals(-1.46f, sirius.magnitude!!, 1e-6f)
        assertTrue(sirius.azimuthDegrees >= 0f && sirius.azimuthDegrees < 360f)
        assertTrue(sirius.altitudeDegrees in -90f..90f)
    }

    @Test
    fun star_target_matches_meridian_transit_math() {
        // 中天（LST == 赤经，ha = 0）闭式解：
        //   高度角 = 90 - |纬度 - 赤纬|；赤纬 < 纬度 时方位角 = 180°（正南）
        val sirius = AstroNavSunMoonLink.starTarget("Sirius", 6.75f, 39.9042)!!
        assertEquals(33.3958f, sirius.altitudeDegrees, 1e-2f)
        assertEquals(180f, sirius.azimuthDegrees, 1e-2f)
        assertTrue(sirius.aboveHorizon)

        // 北极星（赤纬 89.26° > 纬度）中天时几乎正北（方位 ≈ 0°/360°），高度 ≈ 40.64°
        val polaris = AstroNavSunMoonLink.starTarget("Polaris", 2.53f, 39.9042)!!
        val azFromNorth = AstroNavAngles.wrap180(polaris.azimuthDegrees)
        assertEquals(0f, azFromNorth, 1e-2f)
        assertEquals(40.6442f, polaris.altitudeDegrees, 1e-2f)

        // 北纬观测：赤纬为负的星（天狼星）地平高度必然低于北极星
        assertTrue(sirius.altitudeDegrees < polaris.altitudeDegrees)
    }

    // ---------------- 投影与引导 ----------------

    @Test
    fun screen_point_centers_target_when_camera_looks_at_it() {
        val cx = 540f
        val cy = 960f
        val focal = 935f
        // 后置相机指向正北地平线
        val lookNorthBack = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), -90f)
        val northHorizon = AstroNavSkyTarget("t", "测试目标", 0f, 0f)

        val center = AstroNavSunMoonLink.screenPointOf(northHorizon, lookNorthBack, CameraAxis.BACK, cx, cy, focal)
        assertNotNull(center)
        assertEquals(cx, center!!.first, 1f)
        assertEquals(cy, center.second, 1f)

        // 屏幕旋转不影响「正中央」的目标
        val rotated = AstroNavSunMoonLink.screenPointOf(
            northHorizon, lookNorthBack, CameraAxis.BACK, cx, cy, focal, displayRotationDegrees = 90,
        )
        assertNotNull(rotated)
        assertEquals(cx, rotated!!.first, 1f)
        assertEquals(cy, rotated.second, 1f)

        // 地平线以下 -> 不投影
        assertNull(
            AstroNavSunMoonLink.screenPointOf(
                AstroNavSkyTarget("t", "地下", 0f, -1f), lookNorthBack, CameraAxis.BACK, cx, cy, focal,
            ),
        )
        // 相机朝下（单位姿态 + 后置）看不到地平线上的目标
        assertNull(
            AstroNavSunMoonLink.screenPointOf(
                northHorizon, AstroNavQuaternion.IDENTITY, CameraAxis.BACK, cx, cy, focal,
            ),
        )
    }

    @Test
    fun guide_text_tells_direction_and_tilt() {
        val target = AstroNavSkyTarget("sun", "太阳", 40f, 30f)
        // 完全对准
        assertEquals("已对准太阳", AstroNavSunMoonLink.guideText(target, AstroNavPointing(40f, 30f)))
        // 向右 + 上仰
        assertEquals("向右转 40°，上仰 10°", AstroNavSunMoonLink.guideText(target, AstroNavPointing(0f, 20f)))
        // 向左 + 下俯（跨 0/360 缝走短路径）
        val west = AstroNavSkyTarget("m", "月亮", 350f, 10f)
        assertEquals("向左转 20°，下俯 10°", AstroNavSunMoonLink.guideText(west, AstroNavPointing(10f, 20f)))
        // 方位已对准（|Δaz| < 3°）
        assertEquals("方位已对准，上仰 20°", AstroNavSunMoonLink.guideText(target, AstroNavPointing(41f, 10f)))
        // 高度已对准（|Δalt| < 3°）时不追加俯仰提示
        assertEquals("向右转 40°", AstroNavSunMoonLink.guideText(target, AstroNavPointing(0f, 30f)))
        // 地平线以下
        val below = AstroNavSkyTarget("m", "月亮", 90f, -12f)
        assertTrue(AstroNavSunMoonLink.guideText(below, AstroNavPointing(90f, 10f)).contains("在地平线以下"))
    }

    @Test
    fun sky_target_helpers_agree_with_overlay_projection() {
        // toEnu 直接复用既有 AstroProjection，保证与叠加层同源
        val target = AstroNavSkyTarget("s", "南方目标", 180f, 0f)
        val enu = target.toEnu()
        assertEquals(0f, enu.x, 1e-4f)
        assertEquals(-1f, enu.y, 1e-4f)
        assertEquals(0f, enu.z, 1e-4f)
        // 天顶
        val zenith = AstroNavSkyTarget("z", "天顶", 0f, 90f).toEnu()
        assertEquals(1f, zenith.z, 1e-3f)
        // 地平线以上判据
        assertTrue(target.aboveHorizon)
        assertTrue(!AstroNavSkyTarget("d", "地下", 0f, 0f).aboveHorizon)
        // toString 给 UI 用
        assertTrue(target.toString().contains("南方目标"))
    }
}
