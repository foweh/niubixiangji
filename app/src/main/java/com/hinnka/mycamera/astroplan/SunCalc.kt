package com.hinnka.mycamera.astroplan

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * ============================================================================
 *  SunCalc — Kotlin 移植（Android/arm64 可用，纯 Kotlin，无第三方依赖）
 * ============================================================================
 *
 * 来源：https://github.com/mourner/suncalc （Vladimir Agafonkin）
 *   - Star：3495（2026-10-16 核实，GitHub API）
 *   - License：BSD-2-Clause（可自由移植/再分发，需保留版权与免责声明，见下方原文）
 *   - 移植基准版本：suncalc@1.9.0（src/suncalc.js，317 行）
 *
 * 改动点（最小改造，公式与常量逐行对齐原实现，保证数值一致）：
 *   1. JS `Date` → epoch 毫秒 `Long`；`Date` 构造 → 返回 `Long?`（不可解时 null）。
 *   2. JS 在极昼/极夜时会返回 `Invalid Date`；本移植返回 `null`（对 UI 更明确）。
 *   3. JS `Math.round` 用 `java.lang.Math.round`（同为 floor(x+0.5)，行为一致）；
 *      `asin/acos` 入参先 clamp 到 [-1,1]，避免浮点边界产生 NaN。
 *   4. `times` 角度表提取为可配置对象（[SunCalc.addTime]），默认表与原实现逐条一致。
 *   5. 月亮升落：原实现依赖进程本地时区取「当天 0 点」；移植改为显式传入「当天起点 epoch 毫秒」
 *      （[getMoonTimes] / [getMoonTimesLocalDay]），避免测试与设备时区耦合。
 *
 * 原有 BSD-2-Clause 版权与免责声明（移植自 suncalc，按许可证要求保留）：
 *
 *   Copyright (c) 2011-2015, Vladimir Agafonkin
 *   All rights reserved.
 *
 *   Redistribution and use in source and binary forms, with or without
 *   modification, are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following disclaimer in the documentation
 *      and/or other materials provided with the distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 *   AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *   IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *   ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 *   LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *   CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *   SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 *   INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 *   CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 *   ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE.
 *
 * 公式出处（原作者注释，一并保留）：
 *   - 太阳位置：http://aa.quae.nl/en/reken/zonpositie.html
 *   - 月亮位置：http://aa.quae.nl/en/reken/hemelpositie.html
 *   - 大气折射 / 视差角 / 月相：Jean Meeus《Astronomical Algorithms》2nd ed.（Willmann-Bell, 1998）
 *   - 月亮升落：http://www.stargazing.net/kepler/moonrise.html
 * ============================================================================
 */

/** 某一天体的地平坐标（弧度；[azimuthDegrees]/[altitudeDegrees] 为便于 UI 使用的角度制）。 */
data class HorizontalPosition(val azimuth: Double, val altitude: Double) {
    val azimuthDegrees: Double get() = azimuth * 180.0 / PI
    val altitudeDegrees: Double get() = altitude * 180.0 / PI
}

/** 月亮位置（弧度 + 距离 km + 视差角）。 */
data class MoonPosition(
    val azimuth: Double,
    val altitude: Double,
    /** 地心距离（km）。 */
    val distance: Double,
    val parallacticAngle: Double,
) {
    val azimuthDegrees: Double get() = azimuth * 180.0 / PI
    val altitudeDegrees: Double get() = altitude * 180.0 / PI
    val parallacticAngleDegrees: Double get() = parallacticAngle * 180.0 / PI
}

/** 月相照明参数。 */
data class MoonIllumination(
    /** 被照亮比例 0..1（0 = 新月，1 = 满月）。 */
    val fraction: Double,
    /** 相位 0..1（0/1 = 新月，0.5 = 满月）。 */
    val phase: Double,
    /** 相位角（弧度，含符号）。 */
    val angle: Double,
)

/** 太阳事件时刻表。极昼/极夜或该角度当日不跨越时对应字段为 null。 */
data class SunTimes(
    val solarNoon: Long?,
    val nadir: Long?,
    val sunrise: Long?,
    val sunset: Long?,
    val sunriseEnd: Long?,
    val sunsetStart: Long?,
    val dawn: Long?,
    val dusk: Long?,
    val nauticalDawn: Long?,
    val nauticalDusk: Long?,
    val nightEnd: Long?,
    val night: Long?,
    val goldenHourEnd: Long?,
    val goldenHour: Long?,
    /** 经 [SunCalc.addTime] 注册的自定义角度事件（key = 上升/下降名）。 */
    val custom: Map<String, Long?> = emptyMap(),
) {
    /** 太阳在地平线以上的时长（毫秒）；任一时刻缺失返回 null。 */
    val daylightDurationMs: Long?
        get() = if (sunrise != null && sunset != null) sunset - sunrise else null
}

/** 月亮升落。 */
data class MoonTimes(
    val rise: Long?,
    val set: Long?,
    val alwaysUp: Boolean = false,
    val alwaysDown: Boolean = false,
)

/** 一个可配置的太阳角度事件（角度制，负值为地平线以下）。 */
data class SunTimeAngle(
    val angleDegrees: Double,
    val riseName: String,
    val setName: String,
)

object SunCalc {

    /** 一天的毫秒数。 */
    const val DAY_MS: Long = 1000L * 60L * 60L * 24L
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val RAD = PI / 180.0

    /** 地球黄赤交角（弧度）。 */
    private val OBLIQUITY = RAD * 23.4397

    /** 儒略日零点的偏移常量（原实现 `J0`）。 */
    private const val J0 = 0.0009

    /** 原实现的默认角度表（角度、上升名、下降名）。 */
    val DEFAULT_TIMES: List<SunTimeAngle> = listOf(
        SunTimeAngle(-0.833, "sunrise", "sunset"),
        SunTimeAngle(-0.3, "sunriseEnd", "sunsetStart"),
        SunTimeAngle(-6.0, "dawn", "dusk"),
        SunTimeAngle(-12.0, "nauticalDawn", "nauticalDusk"),
        SunTimeAngle(-18.0, "nightEnd", "night"),
        SunTimeAngle(6.0, "goldenHourEnd", "goldenHour"),
    )

    /** 运行时角度表；[resetTimes] 可恢复默认。 */
    private val times = ArrayList(DEFAULT_TIMES)

    /** 已注册的自定义事件名（供 UI 展示；不含默认表）。 */
    private val customKeys = ConcurrentHashMap.newKeySet<String>()

    // ---------------------------------------------------------------- 时间换算

    /** epoch 毫秒 → 儒略日（对应 `toJulian`）。 */
    fun toJulian(epochMs: Long): Double = epochMs / DAY_MS.toDouble() - 0.5 + J1970

    /** 儒略日 → epoch 毫秒；非有限值返回 null（对应 `fromJulian`）。 */
    fun fromJulian(julian: Double): Long? {
        if (!julian.isFinite()) return null
        return ((julian + 0.5 - J1970) * DAY_MS).toLong()
    }

    /** epoch 毫秒 → 距 J2000 的天数（对应 `toDays`）。 */
    fun toDays(epochMs: Long): Double = toJulian(epochMs) - J2000

    // ------------------------------------------------------------ 通用天球换算

    private fun safeAsin(x: Double): Double = asin(x.coerceIn(-1.0, 1.0))

    /** 赤经（对应 `rightAscension`）。 */
    internal fun rightAscension(l: Double, b: Double): Double =
        atan2(sin(l) * cos(OBLIQUITY) - tan(b) * sin(OBLIQUITY), cos(l))

    /** 赤纬（对应 `declination`）。 */
    internal fun declination(l: Double, b: Double): Double =
        safeAsin(sin(b) * cos(OBLIQUITY) + cos(b) * sin(OBLIQUITY) * sin(l))

    /** 方位角（对应 `azimuth`）。 */
    private fun azimuth(h: Double, phi: Double, dec: Double): Double =
        atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))

    /** 高度角（对应 `altitude`）。 */
    private fun altitude(h: Double, phi: Double, dec: Double): Double =
        safeAsin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))

    /** 恒星时（对应 `siderealTime`）。 */
    private fun siderealTime(d: Double, lw: Double): Double = RAD * (280.16 + 360.9856235 * d) - lw

    /**
     * 大气折射修正（对应 `astroRefraction`）。
     * 公式 16.4，Meeus《Astronomical Algorithms》2nd ed.；入参/返回均为弧度。
     */
    internal fun astroRefraction(h: Double): Double {
        var height = h
        // 该公式仅对正高度角成立；h 为极小负值时分母会趋近 0。
        if (height < 0) height = 0.0
        return 0.0002967 / tan(height + 0.00312536 / (height + 0.08901179))
    }

    // ---------------------------------------------------------------- 太阳座标

    /** 太阳平近点角（对应 `solarMeanAnomaly`）。 */
    private fun solarMeanAnomaly(d: Double): Double = RAD * (357.5291 + 0.98560028 * d)

    /** 黄经（对应 `eclipticLongitude`，含中心差与近日点）。 */
    private fun eclipticLongitude(m: Double): Double {
        val center = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val perihelion = RAD * 102.9372
        return m + center + perihelion + PI
    }

    private class Equatorial(val ra: Double, val dec: Double)

    private class MoonEquatorial(val ra: Double, val dec: Double, val dist: Double)

    private fun sunCoords(d: Double): Equatorial {
        val m = solarMeanAnomaly(d)
        val l = eclipticLongitude(m)
        return Equatorial(rightAscension(l, 0.0), declination(l, 0.0))
    }

    // ------------------------------------------------------------ 太阳位置/时刻

    /** 太阳地平坐标（对应 `getPosition`）。 */
    fun getPosition(epochMs: Long, latitude: Double, longitude: Double): HorizontalPosition {
        val lw = RAD * -longitude
        val phi = RAD * latitude
        val d = toDays(epochMs)
        val c = sunCoords(d)
        val h = siderealTime(d, lw) - c.ra
        return HorizontalPosition(azimuth(h, phi, c.dec), altitude(h, phi, c.dec))
    }

    /** 增加一个自定义角度事件（对应 `addTime`；名称不可与默认表重复）。 */
    fun addTime(angleDegrees: Double, riseName: String, setName: String) {
        require(riseName.isNotBlank() && setName.isNotBlank()) { "rise/set name must not be blank" }
        require(times.none { it.riseName == riseName || it.setName == setName }) {
            "duplicated sun time name: $riseName/$setName"
        }
        times.add(SunTimeAngle(angleDegrees, riseName, setName))
        customKeys.add(riseName)
        customKeys.add(setName)
    }

    /** 恢复默认角度表（清空自定义项）。 */
    fun resetTimes() {
        times.clear()
        times.addAll(DEFAULT_TIMES)
        customKeys.clear()
    }

    /** 当前生效的角度表快照。 */
    fun timeAngles(): List<SunTimeAngle> = times.toList()

    /** 儒略周期（对应 `julianCycle`；用 Math.round 复现 JS 的半值上取整）。 */
    private fun julianCycle(d: Double, lw: Double): Double =
        Math.round(d - J0 - lw / (2 * PI)).toDouble()

    private fun approxTransit(ht: Double, lw: Double, n: Double): Double = J0 + (ht + lw) / (2 * PI) + n

    private fun solarTransitJ(ds: Double, m: Double, l: Double): Double =
        J2000 + ds + 0.0053 * sin(m) - 0.0069 * sin(2 * l)

    /**
     * 时角（对应 `hourAngle`）。
     *
     * 注意：这里**故意不做 [-1,1] 钳制**。原实现的 `Math.acos` 在 |x| > 1 时返回 NaN，
     * 语义是"该角度当日不跨越地平线"（极昼/极夜）；NaN 经 `getSetJ` → `solarTransitJ`
     * 一路传下去，最终由 [fromJulian] 转成 null。
     * 若在此处 clamp，极昼会得到一个虚假的日出/日落时刻（acos(±1) 的假根），
     * 与 suncalc 语义不一致。
     */
    private fun hourAngle(h: Double, phi: Double, d: Double): Double =
        acos((sin(h) - sin(phi) * sin(d)) / (cos(phi) * cos(d)))

    /** 观测点海拔对应的地平线俯角（对应 `observerAngle`，度）。 */
    internal fun observerAngle(heightMeters: Double): Double = -2.076 * sqrt(heightMeters) / 60.0

    private fun getSetJ(h: Double, lw: Double, phi: Double, dec: Double, n: Double, m: Double, l: Double): Double {
        val w = hourAngle(h, phi, dec)
        val a = approxTransit(w, lw, n)
        return solarTransitJ(a, m, l)
    }

    /**
     * 计算太阳各事件时刻（对应 `getTimes`）。
     *
     * @param epochMs 参考时刻（当日任意时刻即可，算法按儒略周期取整到当天）。
     * @param heightMeters 观测点相对地平线高度（米），默认 0。
     */
    fun getTimes(
        epochMs: Long,
        latitude: Double,
        longitude: Double,
        heightMeters: Double = 0.0,
    ): SunTimes {
        val lw = RAD * -longitude
        val phi = RAD * latitude
        val dh = observerAngle(heightMeters)
        val d = toDays(epochMs)
        val n = julianCycle(d, lw)
        val ds = approxTransit(0.0, lw, n)
        val m = solarMeanAnomaly(ds)
        val l = eclipticLongitude(m)
        val dec = declination(l, 0.0)
        val jNoon = solarTransitJ(ds, m, l)

        val map = HashMap<String, Long?>(times.size * 2)
        for (t in times) {
            val h0 = (t.angleDegrees + dh) * RAD
            val jSet = getSetJ(h0, lw, phi, dec, n, m, l)
            val jRise = jNoon - (jSet - jNoon)
            map[t.riseName] = fromJulian(jRise)
            map[t.setName] = fromJulian(jSet)
        }
        val custom = if (customKeys.isEmpty()) {
            emptyMap()
        } else {
            customKeys.associateWith { map[it] }
        }
        return SunTimes(
            solarNoon = fromJulian(jNoon),
            nadir = fromJulian(jNoon - 0.5),
            sunrise = map["sunrise"],
            sunset = map["sunset"],
            sunriseEnd = map["sunriseEnd"],
            sunsetStart = map["sunsetStart"],
            dawn = map["dawn"],
            dusk = map["dusk"],
            nauticalDawn = map["nauticalDawn"],
            nauticalDusk = map["nauticalDusk"],
            nightEnd = map["nightEnd"],
            night = map["night"],
            goldenHourEnd = map["goldenHourEnd"],
            goldenHour = map["goldenHour"],
            custom = custom,
        )
    }

    // ---------------------------------------------------------------- 月亮座标

    private fun moonCoords(d: Double): MoonEquatorial {
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val dist = 385001.0 - 20905.0 * cos(m)
        return MoonEquatorial(rightAscension(l, b), declination(l, b), dist)
    }

    /** 月亮地平坐标（对应 `getMoonPosition`，含大气折射修正）。 */
    fun getMoonPosition(epochMs: Long, latitude: Double, longitude: Double): MoonPosition {
        val lw = RAD * -longitude
        val phi = RAD * latitude
        val d = toDays(epochMs)
        val c = moonCoords(d)
        val h = siderealTime(d, lw) - c.ra
        var alt = altitude(h, phi, c.dec)
        // 视差角（Meeus 14.1）。
        val pa = atan2(sin(h), tan(phi) * cos(c.dec) - sin(c.dec) * cos(h))
        alt += astroRefraction(alt)
        return MoonPosition(azimuth(h, phi, c.dec), alt, c.dist, pa)
    }

    /**
     * 月相照明（对应 `getMoonIllumination`）。
     * 公式来源：idlastro mphase.pro + Meeus 第 48 章。
     */
    fun getMoonIllumination(epochMs: Long): MoonIllumination {
        val d = toDays(epochMs)
        val s = sunCoords(d)
        val m = moonCoords(d)
        val sdist = 149598000.0
        val phi = acos(
            (sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
                .coerceIn(-1.0, 1.0)
        )
        val inc = atan2(sdist * sin(phi), m.dist - sdist * cos(phi))
        val angle = atan2(
            cos(s.dec) * sin(s.ra - m.ra),
            sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra),
        )
        return MoonIllumination(
            fraction = (1 + cos(inc)) / 2,
            phase = 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI,
            angle = angle,
        )
    }

    // ------------------------------------------------------------ 月亮升落

    /**
     * 月亮升落（对应 `getMoonTimes`）。
     *
     * 原实现把「当天 0 点」交给本地时区；移植版显式接收 [dayStartEpochMs]，
     * 时区无关、可测试。用 [getMoonTimesLocalDay] 可获得「本地时区当天 0 点」语义。
     *
     * 算法：以 2 小时步长采样月亮高度角（扣 0.133° 视半径），对每段做三点二次曲线求根，
     * 判断是否跨越地平线（http://www.stargazing.net/kepler/moonrise.html）。
     */
    fun getMoonTimes(dayStartEpochMs: Long, latitude: Double, longitude: Double): MoonTimes {
        val hc = 0.133 * RAD
        var h0 = getMoonPosition(dayStartEpochMs, latitude, longitude).altitude - hc
        var h1: Double
        var h2: Double
        var rise: Double? = null
        var set: Double? = null
        // 最后一段二次曲线的顶点高度（原实现用它判「整日不落/整日不出」）。
        var lastYe = 0.0

        var i = 1
        while (i <= 24) {
            h1 = getMoonPosition(dayStartEpochMs + i * DAY_MS / 24, latitude, longitude).altitude - hc
            h2 = getMoonPosition(dayStartEpochMs + (i + 1) * DAY_MS / 24, latitude, longitude).altitude - hc

            val a = (h0 + h2) / 2 - h1
            val b = (h2 - h0) / 2
            val xe = -b / (2 * a)
            val ye = (a * xe + b) * xe + h1
            val d = b * b - 4 * a * h1
            var roots = 0
            var x1 = 0.0
            var x2 = 0.0
            if (d >= 0) {
                val dx = sqrt(d) / (abs(a) * 2)
                x1 = xe - dx
                x2 = xe + dx
                if (abs(x1) <= 1.0) roots++
                if (abs(x2) <= 1.0) roots++
                if (x1 < -1.0) x1 = x2
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1 else set = i + x1
            } else if (roots == 2) {
                rise = i + (if (ye < 0) x2 else x1)
                set = i + (if (ye < 0) x1 else x2)
            }
            if (rise != null && set != null) break

            lastYe = ye
            h0 = h2
            i += 2
        }

        return MoonTimes(
            rise = rise?.let { hoursLater(dayStartEpochMs, it) },
            set = set?.let { hoursLater(dayStartEpochMs, it) },
            alwaysUp = rise == null && set == null && lastYe > 0,
            // 与 JS 一致：`ye > 0 ? alwaysUp : alwaysDown`，NaN 归入 alwaysDown。
            alwaysDown = rise == null && set == null && !(lastYe > 0),
        )
    }

    /**
     * 「本地时区当天 0 点」版本（等价原 `getMoonTimes(date, lat, lng, inUTC=false)`）。
     * @param utcOffsetMinutes 相对 UTC 的分钟偏移（例如东八区 = 480）。
     */
    fun getMoonTimesLocalDay(
        epochMs: Long,
        latitude: Double,
        longitude: Double,
        utcOffsetMinutes: Int,
    ): MoonTimes {
        val offsetMs = utcOffsetMinutes.toLong() * 60_000L
        val localMs = epochMs + offsetMs
        val localMidnight = floor(localMs / DAY_MS.toDouble()).toLong() * DAY_MS
        return getMoonTimes(localMidnight - offsetMs, latitude, longitude)
    }

    /** 把「当天 0 点后的第 h 小时」转为 epoch 毫秒（对应 `hoursLater`）。 */
    fun hoursLater(dayStartEpochMs: Long, hours: Double): Long =
        dayStartEpochMs + (hours * DAY_MS / 24.0).roundToInt()

    /** 角度制 → 弧度（工具方法，供 UI 层换算）。 */
    fun toRadians(degrees: Double): Double = degrees * RAD
}
