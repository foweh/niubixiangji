/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：内嵌 NOAA WMM2025 球谐系数 + 球谐求值的**数值正确性**（54 条金标准向量）
 *       与边界（全球网格有限性、经度回绕、极点、非法输入、年代外推线性、有效期窗口、海拔微扰）。
 *
 * 金标准向量的来源：NOAA/NCEI 发布的 WMM2025 官方球谐系数（美国联邦政府作品，公有领域），
 * 由参考实现 pygeomag(WMM_2025.COF) 在 18 个测点 × 3 个历元（2025.0/2027.5/2030.0）离线生成，保留 6 位小数。
 * 本模块的 Kotlin 移植与 Python 镜像比对时最大偏差为 0.000e+00 度；此处容差 0.02 度用于吸收 Float 往返误差。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Wmm2025ModelTest {

    /** 金标准：纬度(°)、经度(°)、十进制年、磁偏角(°，东正西负)。 */
    private data class WmmGolden(
        val latitudeDeg: Float,
        val longitudeDeg: Float,
        val year: Float,
        val declinationDeg: Float,
    )

    private val toleranceDeg = 0.02f

    // ---------------- 模型完整性 ----------------

    @Test
    fun model_metadata_matches_wmm2025_release() {
        assertEquals(90, Wmm2025Model.coefficientCount)
        assertEquals(2025.0, Wmm2025Model.EPOCH_YEAR, 0.0)
        assertEquals(2025.0, Wmm2025Model.VALID_FROM_YEAR, 0.0)
        assertEquals(2030.0, Wmm2025Model.VALID_TO_YEAR, 0.0)
    }

    @Test
    fun validity_window_is_inclusive_and_rejects_nan() {
        assertTrue(Wmm2025Model.isWithinValidity(2025.0))
        assertTrue(Wmm2025Model.isWithinValidity(2026.3))
        assertTrue(Wmm2025Model.isWithinValidity(2030.0))
        assertFalse(Wmm2025Model.isWithinValidity(2024.9))
        assertFalse(Wmm2025Model.isWithinValidity(2030.1))
        assertFalse(Wmm2025Model.isWithinValidity(Double.NaN))
    }

    // ---------------- 金标准向量 ----------------


    @Test
    fun golden_vectors_match_reference_implementation() {
        assertEquals(54, GOLDEN.size)
        var worst = 0f
        for (v in GOLDEN) {
            val actual = Wmm2025Model
                .declinationDegrees(v.latitudeDeg.toDouble(), v.longitudeDeg.toDouble(), 0.0, v.year.toDouble())
                .toFloat()
            val diff = abs(actual - v.declinationDeg)
            if (diff > worst) worst = diff
            assertEquals(
                "lat=${v.latitudeDeg} lon=${v.longitudeDeg} year=${v.year} " +
                    "expected=${v.declinationDeg} actual=$actual",
                v.declinationDeg,
                actual,
                toleranceDeg,
            )
        }
        assertTrue("最大偏差应远小于容差: $worst", worst < toleranceDeg)
    }

    // ---------------- 数值边界 ----------------

    @Test
    fun declination_is_finite_on_global_grid() {
        // 30° 步长的全球网格：结果必须有限且落在 [-180, 180]
        var lat = -90.0
        while (lat <= 90.0) {
            var lon = -180.0
            while (lon <= 180.0) {
                val d = Wmm2025Model.declinationDegrees(lat, lon, 0.0, 2025.0)
                assertTrue("lat=$lat lon=$lon -> $d", d.isFinite())
                assertTrue("lat=$lat lon=$lon -> $d", d >= -180.0 && d <= 180.0)
                lon += 30.0
            }
            lat += 30.0
        }
    }

    @Test
    fun longitude_wrapping_is_equivalent() {
        val base = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2025.0)
        assertEquals(base, Wmm2025Model.declinationDegrees(41.0, 480.0, 0.0, 2025.0), 1e-3)
        assertEquals(base, Wmm2025Model.declinationDegrees(41.0, -240.0, 0.0, 2025.0), 1e-3)
        assertEquals(base, Wmm2025Model.declinationDegrees(41.0, 120.0 + 360.0 * 5, 0.0, 2025.0), 1e-3)
    }

    @Test
    fun poles_and_out_of_range_arguments_do_not_throw() {
        // 地理极点：cos(90°) 恰好为 0，历史上是不稳定点，这里要求仍返回有限值
        assertTrue(Wmm2025Model.declinationDegrees(90.0, 0.0, 0.0, 2025.0).isFinite())
        assertTrue(Wmm2025Model.declinationDegrees(-90.0, 0.0, 0.0, 2025.0).isFinite())
        assertTrue(Wmm2025Model.declinationDegrees(90.0, 179.9, 0.0, 2025.0).isFinite())
        // 超范围纬度（调用方理应 clamp，这里只要求不崩溃）
        assertTrue(Wmm2025Model.declinationDegrees(95.0, 0.0, 0.0, 2025.0).isFinite())
        // NaN / Inf 输入：不得抛异常
        Wmm2025Model.declinationDegrees(Double.NaN, 0.0, 0.0, 2025.0)
        Wmm2025Model.declinationDegrees(0.0, Double.POSITIVE_INFINITY, 0.0, 2025.0)
        Wmm2025Model.declinationDegrees(0.0, 0.0, Double.NaN, Double.NaN)
    }

    @Test
    fun secular_variation_is_linear_in_decimal_year() {
        // dg/dh 年代变率是线性项：等间隔年份的差值应基本相等
        val y2025 = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2025.0)
        val y2026 = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2026.0)
        val y2027 = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2027.0)
        val y2030 = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2030.0)
        assertEquals(y2026 - y2025, y2027 - y2026, 1e-6)
        assertEquals(5.0 * (y2026 - y2025), y2030 - y2025, 1e-6)
        // 与金标准一致（北京/沈阳附近逐年向西约 -0.039°/年）
        assertTrue(y2030 < y2025)
    }

    @Test
    fun altitude_perturbs_value_only_slightly() {
        val sea = Wmm2025Model.declinationDegrees(41.0, 120.0, 0.0, 2025.0)
        val high = Wmm2025Model.declinationDegrees(41.0, 120.0, 10.0, 2025.0)
        assertTrue(high.isFinite())
        assertTrue("海拔 10km 的磁偏角变化应很小: ${abs(high - sea)}", abs(high - sea) < 1.0)
    }

    @Test
    fun southern_magnetic_pole_region_stays_finite_and_large() {
        // 南磁极附近磁偏角极大（这也是「网格插值」方案被否决的原因），此处只做有限性/量级回归
        val near = Wmm2025Model.declinationDegrees(-64.0, 136.0, 0.0, 2025.0)
        assertTrue(near.isFinite())
        assertTrue("南磁极附近磁偏角应为大角度: $near", abs(near) > 45.0)
    }

    private companion object {
        /** 18 测点 × 3 历元 = 54 条金标准向量（pygeomag × WMM_2025.COF 生成）。 */
        val GOLDEN: List<WmmGolden> = listOf(
        WmmGolden(0.0f, 0.0f, 2025.0f, -4.016244f),
        WmmGolden(0.0f, 0.0f, 2027.5f, -3.707512f),
        WmmGolden(0.0f, 0.0f, 2030.0f, -3.397411f),
        WmmGolden(41.0f, 120.0f, 2025.0f, -8.733556f),
        WmmGolden(41.0f, 120.0f, 2027.5f, -8.831118f),
        WmmGolden(41.0f, 120.0f, 2030.0f, -8.928881f),
        WmmGolden(39.9042f, 116.4074f, 2025.0f, -7.493186f),
        WmmGolden(39.9042f, 116.4074f, 2027.5f, -7.586616f),
        WmmGolden(39.9042f, 116.4074f, 2030.0f, -7.680254f),
        WmmGolden(43.8378f, 126.5495f, 2025.0f, -10.737022f),
        WmmGolden(43.8378f, 126.5495f, 2027.5f, -10.823912f),
        WmmGolden(43.8378f, 126.5495f, 2030.0f, -10.910926f),
        WmmGolden(45.8038f, 126.535f, 2025.0f, -11.311210f),
        WmmGolden(45.8038f, 126.535f, 2027.5f, -11.394233f),
        WmmGolden(45.8038f, 126.535f, 2030.0f, -11.477380f),
        WmmGolden(23.1291f, 113.2644f, 2025.0f, -3.264923f),
        WmmGolden(23.1291f, 113.2644f, 2027.5f, -3.295072f),
        WmmGolden(23.1291f, 113.2644f, 2030.0f, -3.325252f),
        WmmGolden(31.2304f, 121.4737f, 2025.0f, -6.490109f),
        WmmGolden(31.2304f, 121.4737f, 2027.5f, -6.583092f),
        WmmGolden(31.2304f, 121.4737f, 2030.0f, -6.676169f),
        WmmGolden(22.3193f, 114.1694f, 2025.0f, -3.278922f),
        WmmGolden(22.3193f, 114.1694f, 2027.5f, -3.314950f),
        WmmGolden(22.3193f, 114.1694f, 2030.0f, -3.351014f),
        WmmGolden(52.52f, 13.405f, 2025.0f, 4.940656f),
        WmmGolden(52.52f, 13.405f, 2027.5f, 5.279043f),
        WmmGolden(52.52f, 13.405f, 2030.0f, 5.617288f),
        WmmGolden(40.7128f, -74.006f, 2025.0f, -12.534137f),
        WmmGolden(40.7128f, -74.006f, 2027.5f, -12.434742f),
        WmmGolden(40.7128f, -74.006f, 2030.0f, -12.336265f),
        WmmGolden(51.5074f, -0.1278f, 2025.0f, 0.906051f),
        WmmGolden(51.5074f, -0.1278f, 2027.5f, 1.332657f),
        WmmGolden(51.5074f, -0.1278f, 2030.0f, 1.758526f),
        WmmGolden(35.6762f, 139.6503f, 2025.0f, -7.874176f),
        WmmGolden(35.6762f, 139.6503f, 2027.5f, -7.969531f),
        WmmGolden(35.6762f, 139.6503f, 2030.0f, -8.064747f),
        WmmGolden(-33.8688f, 151.2093f, 2025.0f, 12.788533f),
        WmmGolden(-33.8688f, 151.2093f, 2027.5f, 12.842237f),
        WmmGolden(-33.8688f, 151.2093f, 2030.0f, 12.896050f),
        WmmGolden(1.3521f, 103.8198f, 2025.0f, 0.179730f),
        WmmGolden(1.3521f, 103.8198f, 2027.5f, 0.257791f),
        WmmGolden(1.3521f, 103.8198f, 2030.0f, 0.335756f),
        WmmGolden(89.9f, 0.0f, 2025.0f, 13.552840f),
        WmmGolden(89.9f, 0.0f, 2027.5f, 18.644097f),
        WmmGolden(89.9f, 0.0f, 2030.0f, 23.633894f),
        WmmGolden(-89.9f, 0.0f, 2025.0f, -31.408501f),
        WmmGolden(-89.9f, 0.0f, 2027.5f, -31.785877f),
        WmmGolden(-89.9f, 0.0f, 2030.0f, -32.162954f),
        WmmGolden(-64.0f, 136.0f, 2025.0f, 101.097930f),
        WmmGolden(-64.0f, 136.0f, 2027.5f, 105.551842f),
        WmmGolden(-64.0f, 136.0f, 2030.0f, 107.930502f),
        WmmGolden(-77.85f, 166.7f, 2025.0f, 140.426483f),
        WmmGolden(-77.85f, 166.7f, 2027.5f, 139.917724f),
        WmmGolden(-77.85f, 166.7f, 2030.0f, 139.416966f),
        )
    }
}
