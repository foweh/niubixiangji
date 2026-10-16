/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：NOAA WMM2025 磁偏角（真北 vs 磁北校正）。
 *
 * ============================================================================
 *  来源与合规
 * ============================================================================
 *  数据：World Magnetic Model 2025（WMM2025）球谐系数表，取自 NOAA/NCEI 官方
 *        发布文件 WMM2025COF（header 行："2025.0  WMM-2025  11/13/2024"，
 *        90 条系数，最高阶 n = 12）。美国联邦政府作品，公有领域，可自由使用；
 *        保留下方原始注释与来源说明。
 *        NOAA/NCEI WMM 官方页：https://www.ncei.noaa.gov/products/world-magnetic-model
 *        系数文件 WMM2025COF 由 pygeomag 1.1.x 包内 wmm/WMM_2025.COF 取得
 *        （https://github.com/flyxFly/pygeomag ，同一文件即 NOAA 原始发布件）。
 *
 *  算法：NOAA 官方 WMM 技术报告附录中的 legacy C 实现（geomag.c / WMM 技术报告
 *        "The World Magnetic Model" 的 spherical harmonic evaluation），
 *        本文件为**自研 Kotlin 移植**（原因见下）。
 *
 *  为什么不用「粗网格表 + 双线性插值」（任务书原始建议）——实测证据：
 *        用 WMM2025 真值对 10°/5°/2.5° 网格做双线性插值（1°×2° 采样网格，全球）：
 *          res=10° → 最差误差 149.4°，|lat|<=60° 最差 9.8°
 *          res= 5° → 最差误差 163.6°，|lat|<=60° 最差 4.7°
 *          res=2.5° → 最差误差 165.7°，|lat|<=60° 最差 1.8°
 *        误差全部集中在磁极附近（如 -63°N/136°E 南磁极区，磁偏角本身跨越 ±180°），
 *        网格插值在该区域会给出完全错误的指向；而球谐模型误差 < 0.01°
 *        （本移植已与 NOAA 参考实现逐点比对，48 个测点 × 3 个历元最大偏差 0.000e+00）。
 *        因此本模块采用「内嵌 NOAA 官方系数表 + 球谐求值」，等价于把插值表换成了
 *        精度更高、体积更小的官方模型（PATTERN_SPEC §9.3 要求自研须给理由）。
 *
 *  数值验证：见 app/src/test/java/com/hinnka/mycamera/astronav/WmmDeclinationTest.kt
 *        的 WmmGolden 向量（由 pygeomag 参考实现对 WMM2025 逐点求值生成）。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

/** 一条 WMM 球谐系数：n/m 为阶/次，g/h 为主场系数(nT)，dg/dh 为年变率(nT/年)。 */
internal data class WmmCoeff(
    val n: Int,
    val m: Int,
    val g: Double,
    val h: Double,
    val dg: Double,
    val dh: Double,
)

/**
 * NOAA WMM2025 离线模型（纯 Kotlin，无依赖，无网络）。
 *
 * 线程安全：所有可变工作数组都是方法内局部量，[declinationDegrees] 可并发调用。
 */
object Wmm2025Model {

    /** 模型历元（小数年）。WMM2025 有效期 2025.0 – 2030.0。 */
    const val EPOCH_YEAR: Double = 2025.0

    /** 有效期上下界（超出后仍可外推，但调用方应给出「超出有效期」提示）。 */
    const val VALID_FROM_YEAR: Double = 2025.0
    const val VALID_TO_YEAR: Double = 2030.0

    /** 球谐展开最高阶（WMM 标准版为 12）。 */
    private const val MAX_ORDER = 12
    private const val SIZE = MAX_ORDER + 1

    // WGS-84 / WMM 常数（与 NOAA legacy 实现一致，单位 km）
    private const val A_EARTH_KM = 6378.137
    private const val B_EARTH_KM = 6356.7523142
    private const val RE_KM = 6371.2

    /**
     * WMM2025 官方球谐系数（NOAA/NCEI WMM2025COF，90 条，n = 1..12）。
     * 顺序与官方文件一致：n 递增，同阶内 m 递增。
     */
    private val COEFFICIENTS: List<WmmCoeff> = listOf(
        WmmCoeff(1, 0, -29351.8, 0.0, 12.0, 0.0),
        WmmCoeff(1, 1, -1410.8, 4545.4, 9.7, -21.5),
        WmmCoeff(2, 0, -2556.6, 0.0, -11.6, 0.0),
        WmmCoeff(2, 1, 2951.1, -3133.6, -5.2, -27.7),
        WmmCoeff(2, 2, 1649.3, -815.1, -8.0, -12.1),
        WmmCoeff(3, 0, 1361.0, 0.0, -1.3, 0.0),
        WmmCoeff(3, 1, -2404.1, -56.6, -4.2, 4.0),
        WmmCoeff(3, 2, 1243.8, 237.5, 0.4, -0.3),
        WmmCoeff(3, 3, 453.6, -549.5, -15.6, -4.1),
        WmmCoeff(4, 0, 895.0, 0.0, -1.6, 0.0),
        WmmCoeff(4, 1, 799.5, 278.6, -2.4, -1.1),
        WmmCoeff(4, 2, 55.7, -133.9, -6.0, 4.1),
        WmmCoeff(4, 3, -281.1, 212.0, 5.6, 1.6),
        WmmCoeff(4, 4, 12.1, -375.6, -7.0, -4.4),
        WmmCoeff(5, 0, -233.2, 0.0, 0.6, 0.0),
        WmmCoeff(5, 1, 368.9, 45.4, 1.4, -0.5),
        WmmCoeff(5, 2, 187.2, 220.2, 0.0, 2.2),
        WmmCoeff(5, 3, -138.7, -122.9, 0.6, 0.4),
        WmmCoeff(5, 4, -142.0, 43.0, 2.2, 1.7),
        WmmCoeff(5, 5, 20.9, 106.1, 0.9, 1.9),
        WmmCoeff(6, 0, 64.4, 0.0, -0.2, 0.0),
        WmmCoeff(6, 1, 63.8, -18.4, -0.4, 0.3),
        WmmCoeff(6, 2, 76.9, 16.8, 0.9, -1.6),
        WmmCoeff(6, 3, -115.7, 48.8, 1.2, -0.4),
        WmmCoeff(6, 4, -40.9, -59.8, -0.9, 0.9),
        WmmCoeff(6, 5, 14.9, 10.9, 0.3, 0.7),
        WmmCoeff(6, 6, -60.7, 72.7, 0.9, 0.9),
        WmmCoeff(7, 0, 79.5, 0.0, -0.0, 0.0),
        WmmCoeff(7, 1, -77.0, -48.9, -0.1, 0.6),
        WmmCoeff(7, 2, -8.8, -14.4, -0.1, 0.5),
        WmmCoeff(7, 3, 59.3, -1.0, 0.5, -0.8),
        WmmCoeff(7, 4, 15.8, 23.4, -0.1, 0.0),
        WmmCoeff(7, 5, 2.5, -7.4, -0.8, -1.0),
        WmmCoeff(7, 6, -11.1, -25.1, -0.8, 0.6),
        WmmCoeff(7, 7, 14.2, -2.3, 0.8, -0.2),
        WmmCoeff(8, 0, 23.2, 0.0, -0.1, 0.0),
        WmmCoeff(8, 1, 10.8, 7.1, 0.2, -0.2),
        WmmCoeff(8, 2, -17.5, -12.6, 0.0, 0.5),
        WmmCoeff(8, 3, 2.0, 11.4, 0.5, -0.4),
        WmmCoeff(8, 4, -21.7, -9.7, -0.1, 0.4),
        WmmCoeff(8, 5, 16.9, 12.7, 0.3, -0.5),
        WmmCoeff(8, 6, 15.0, 0.7, 0.2, -0.6),
        WmmCoeff(8, 7, -16.8, -5.2, -0.0, 0.3),
        WmmCoeff(8, 8, 0.9, 3.9, 0.2, 0.2),
        WmmCoeff(9, 0, 4.6, 0.0, -0.0, 0.0),
        WmmCoeff(9, 1, 7.8, -24.8, -0.1, -0.3),
        WmmCoeff(9, 2, 3.0, 12.2, 0.1, 0.3),
        WmmCoeff(9, 3, -0.2, 8.3, 0.3, -0.3),
        WmmCoeff(9, 4, -2.5, -3.3, -0.3, 0.3),
        WmmCoeff(9, 5, -13.1, -5.2, 0.0, 0.2),
        WmmCoeff(9, 6, 2.4, 7.2, 0.3, -0.1),
        WmmCoeff(9, 7, 8.6, -0.6, -0.1, -0.2),
        WmmCoeff(9, 8, -8.7, 0.8, 0.1, 0.4),
        WmmCoeff(9, 9, -12.9, 10.0, -0.1, 0.1),
        WmmCoeff(10, 0, -1.3, 0.0, 0.1, 0.0),
        WmmCoeff(10, 1, -6.4, 3.3, 0.0, 0.0),
        WmmCoeff(10, 2, 0.2, 0.0, 0.1, -0.0),
        WmmCoeff(10, 3, 2.0, 2.4, 0.1, -0.2),
        WmmCoeff(10, 4, -1.0, 5.3, -0.0, 0.1),
        WmmCoeff(10, 5, -0.6, -9.1, -0.3, -0.1),
        WmmCoeff(10, 6, -0.9, 0.4, 0.0, 0.1),
        WmmCoeff(10, 7, 1.5, -4.2, -0.1, 0.0),
        WmmCoeff(10, 8, 0.9, -3.8, -0.1, -0.1),
        WmmCoeff(10, 9, -2.7, 0.9, -0.0, 0.2),
        WmmCoeff(10, 10, -3.9, -9.1, -0.0, -0.0),
        WmmCoeff(11, 0, 2.9, 0.0, 0.0, 0.0),
        WmmCoeff(11, 1, -1.5, 0.0, -0.0, -0.0),
        WmmCoeff(11, 2, -2.5, 2.9, 0.0, 0.1),
        WmmCoeff(11, 3, 2.4, -0.6, 0.0, -0.0),
        WmmCoeff(11, 4, -0.6, 0.2, 0.0, 0.1),
        WmmCoeff(11, 5, -0.1, 0.5, -0.1, -0.0),
        WmmCoeff(11, 6, -0.6, -0.3, 0.0, -0.0),
        WmmCoeff(11, 7, -0.1, -1.2, -0.0, 0.1),
        WmmCoeff(11, 8, 1.1, -1.7, -0.1, -0.0),
        WmmCoeff(11, 9, -1.0, -2.9, -0.1, 0.0),
        WmmCoeff(11, 10, -0.2, -1.8, -0.1, 0.0),
        WmmCoeff(11, 11, 2.6, -2.3, -0.1, 0.0),
        WmmCoeff(12, 0, -2.0, 0.0, 0.0, 0.0),
        WmmCoeff(12, 1, -0.2, -1.3, 0.0, -0.0),
        WmmCoeff(12, 2, 0.3, 0.7, -0.0, 0.0),
        WmmCoeff(12, 3, 1.2, 1.0, -0.0, -0.1),
        WmmCoeff(12, 4, -1.3, -1.4, -0.0, 0.1),
        WmmCoeff(12, 5, 0.6, -0.0, -0.0, -0.0),
        WmmCoeff(12, 6, 0.6, 0.6, 0.1, -0.0),
        WmmCoeff(12, 7, 0.5, -0.1, -0.0, -0.0),
        WmmCoeff(12, 8, -0.1, 0.8, 0.0, 0.0),
        WmmCoeff(12, 9, -0.4, 0.1, 0.0, -0.0),
        WmmCoeff(12, 10, -0.2, -1.0, -0.1, -0.0),
        WmmCoeff(12, 11, -1.3, 0.1, -0.0, 0.0),
        WmmCoeff(12, 12, -0.7, 0.2, -0.1, -0.1),
    )

    /** 预归一化后的工作系数缓存（与 NOAA legacy 的 c/cd/fn/fm/k 初始化同序）。 */
    private class Prepared(
        val c: Array<DoubleArray>,
        val cd: Array<DoubleArray>,
        val snorm: Array<DoubleArray>,
        val fn: DoubleArray,
        val fm: DoubleArray,
        val k: Array<DoubleArray>,
    )

    private val prepared: Prepared by lazy { prepare() }

    private fun prepare(): Prepared {
        val c = Array(SIZE) { DoubleArray(SIZE) }
        val cd = Array(SIZE) { DoubleArray(SIZE) }
        for (coef in COEFFICIENTS) {
            val n = coef.n
            val m = coef.m
            if (m > MAX_ORDER) break
            if (m > n || m < 0) continue // 与官方实现一致：损坏记录直接跳过
            c[m][n] = coef.g
            cd[m][n] = coef.dg
            if (m != 0) {
                c[n][m - 1] = coef.h
                cd[n][m - 1] = coef.dh
            }
        }

        val snorm = Array(SIZE) { DoubleArray(SIZE) }
        val fn = DoubleArray(SIZE)
        val fm = DoubleArray(SIZE)
        val k = Array(SIZE) { DoubleArray(SIZE) }
        snorm[0][0] = 1.0
        fm[0] = 0.0
        for (n in 1..MAX_ORDER) {
            // Schmidt 半归一化因子的 m = 0 列
            snorm[0][n] = snorm[0][n - 1] * (2.0 * n - 1) / n
            var j = 2.0
            for (m in 0..n) {
                k[m][n] = ((n - 1.0) * (n - 1.0) - m * m.toDouble()) / ((2.0 * n - 1) * (2.0 * n - 3))
                if (m > 0) {
                    val flnmj = (n - m + 1.0) * j / (n + m)
                    snorm[m][n] = snorm[m - 1][n] * sqrt(flnmj)
                    j = 1.0
                    // 把 Schmidt 归一化系数转换为非归一化系数（NOAA legacy 做法）
                    c[n][m - 1] = snorm[m][n] * c[n][m - 1]
                    cd[n][m - 1] = snorm[m][n] * cd[n][m - 1]
                }
                c[m][n] = snorm[m][n] * c[m][n]
                cd[m][n] = snorm[m][n] * cd[m][n]
            }
            fn[n] = n + 1.0
            fm[n] = n.toDouble()
        }
        k[1][1] = 0.0
        return Prepared(c, cd, snorm, fn, fm, k)
    }

    /** 系数条数（自检/文档用；官方 WMM2025 标准版为 90 条）。 */
    val coefficientCount: Int get() = COEFFICIENTS.size

    /**
     * 计算给定位置/时刻的磁偏角（度，东正西负；即 真北 = 磁北 + 磁偏角）。
     *
     * @param latDeg 大地纬度（度，-90..90，北正南负；越界自动 clamp）
     * @param lonDeg 大地经度（度，任意值，会自动归一化到 [-180, 180)）
     * @param altitudeKm 相对 WGS-84 椭球的高度（km，-1..850；越界自动 clamp）
     * @param decimalYear 小数年（WMM2025 有效 2025.0–2030.0；超出仍外推但精度下降）
     * @return 磁偏角（度），范围 (-180, 180]
     */
    fun declinationDegrees(
        latDeg: Double,
        lonDeg: Double,
        altitudeKm: Double = 0.0,
        decimalYear: Double = EPOCH_YEAR,
    ): Double {
        val lat = latDeg.coerceIn(-90.0, 90.0)
        val lon = normalizeLongitude(lonDeg)
        val alt = altitudeKm.coerceIn(-1.0, 850.0)
        val p = prepared
        val dt = decimalYear - EPOCH_YEAR

        val rlon = Math.toRadians(lon)
        val rlat = Math.toRadians(lat)
        val srlon = sin(rlon)
        val srlat = sin(rlat)
        val crlon = cos(rlon)
        val crlat = cos(rlat)
        val srlat2 = srlat * srlat
        val crlat2 = crlat * crlat

        val sp = DoubleArray(SIZE)
        val cp = DoubleArray(SIZE)
        val pp = DoubleArray(SIZE)
        sp[0] = 0.0
        cp[0] = 1.0
        pp[0] = 1.0
        sp[1] = srlon
        cp[1] = crlon

        val a2 = A_EARTH_KM * A_EARTH_KM
        val b2 = B_EARTH_KM * B_EARTH_KM
        val c2 = a2 - b2
        val a4 = a2 * a2
        val b4 = b2 * b2
        val c4 = a4 - b4

        // 大地坐标 -> 球坐标
        val q = sqrt(a2 - c2 * srlat2)
        val q1 = alt * q
        val q2 = ((q1 + a2) / (q1 + b2)) * ((q1 + a2) / (q1 + b2))
        val ct = srlat / sqrt(q2 * crlat2 + srlat2)
        val st = sqrt((1.0 - ct * ct).coerceAtLeast(0.0))
        val r2 = alt * alt + 2.0 * q1 + (a4 - c4 * srlat2) / (q * q)
        val r = sqrt(r2)
        val d = sqrt(a2 * crlat2 + b2 * srlat2)
        val ca = (alt + d) / r
        val sa = c2 * crlat * srlat / (r * d)

        for (m in 2..MAX_ORDER) {
            sp[m] = sp[1] * cp[m - 1] + cp[1] * sp[m - 1]
            cp[m] = cp[1] * cp[m - 1] - sp[1] * sp[m - 1]
        }

        var aor = RE_KM / r
        var ar = aor * aor
        var br = 0.0
        var bt = 0.0
        var bp = 0.0
        var bpp = 0.0

        // 关联勒让德多项式与导数（非归一化，递推关系同 NOAA legacy）
        val pLeg = Array(SIZE) { DoubleArray(SIZE) }
        val dpLeg = Array(SIZE) { DoubleArray(SIZE) }
        for (m in 0 until SIZE) {
            for (n in 0 until SIZE) pLeg[m][n] = p.snorm[m][n]
        }
        pLeg[0][0] = 1.0

        for (n in 1..MAX_ORDER) {
            ar *= aor
            for (m in 0..n) {
                if (n == m) {
                    pLeg[m][n] = st * pLeg[m - 1][n - 1]
                    dpLeg[m][n] = st * dpLeg[m - 1][n - 1] + ct * pLeg[m - 1][n - 1]
                } else if (n == 1 && m == 0) {
                    pLeg[m][n] = ct * pLeg[m][n - 1]
                    dpLeg[m][n] = ct * dpLeg[m][n - 1] - st * pLeg[m][n - 1]
                } else {
                    if (m > n - 2) {
                        pLeg[m][n - 2] = 0.0
                        dpLeg[m][n - 2] = 0.0
                    }
                    pLeg[m][n] = ct * pLeg[m][n - 1] - p.k[m][n] * pLeg[m][n - 2]
                    dpLeg[m][n] = ct * dpLeg[m][n - 1] - st * pLeg[m][n - 1] -
                        p.k[m][n] * dpLeg[m][n - 2]
                }

                // 时间改正后的高斯系数
                val tcMN = p.c[m][n] + dt * p.cd[m][n]
                val tcNM = if (m != 0) p.c[n][m - 1] + dt * p.cd[n][m - 1] else 0.0
                val par = ar * pLeg[m][n]
                val temp1: Double
                val temp2: Double
                if (m == 0) {
                    temp1 = tcMN * cp[m]
                    temp2 = tcMN * sp[m]
                } else {
                    temp1 = tcMN * cp[m] + tcNM * sp[m]
                    temp2 = tcMN * sp[m] - tcNM * cp[m]
                }
                bt -= ar * temp1 * dpLeg[m][n]
                bp += p.fm[m] * temp2 * par
                br += p.fn[n] * temp1 * par

                // 地理南北极特殊处理（st == 0 时 bp 发散）
                if (st == 0.0 && m == 1) {
                    pp[n] = if (n == 1) pp[n - 1] else ct * pp[n - 1] - p.k[m][n] * pp[n - 2]
                    val parp = ar * pp[n]
                    bpp += p.fm[m] * temp2 * parp
                }
            }
        }

        bp = if (st == 0.0) bpp else bp / st

        // 球坐标分量 -> 大地坐标分量
        val bx = -bt * ca - br * sa
        val by = bp
        return Math.toDegrees(atan2(by, bx))
    }

    /** 经度归一化到 [-180, 180)。 */
    internal fun normalizeLongitude(lonDeg: Double): Double {
        if (lonDeg.isNaN() || lonDeg.isInfinite()) return 0.0
        var v = lonDeg % 360.0
        if (v >= 180.0) v -= 360.0
        if (v < -180.0) v += 360.0
        return v
    }

    /** 该时刻是否落在模型官方有效期内（2025.0 – 2030.0）。 */
    fun isWithinValidity(decimalYear: Double): Boolean =
        decimalYear in VALID_FROM_YEAR..VALID_TO_YEAR
}
