package com.hinnka.mycamera.astroplan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * suncalc Kotlin 移植的**已知值比对**单测。
 *
 * 参考值来源：官方 `mourner/suncalc@1.9.0`（BSD-2-Clause，3495★）在 Node 下的真实输出，
 * 由离线 oracle 脚本逐项打印后**逐字抄录**到本文件（生成脚本见
 * `delivery/selfchecks/longexposure.md` 的"suncalc 数值验证"一节）。
 *
 * 容差说明（为什么不是 0）：
 *  - 角度 / 月相：1e-6（度或比例）—— 覆盖 Kotlin 与 JS 浮点求值次序差异；
 *  - 距离：1e-3 km；
 *  - 时刻：5000 ms —— 原实现用 `new Date(ms)` 承载儒略日结果，毫秒以下被截断，
 *    移植版直接返回 epoch 毫秒，两者理论差异 < 1ms，5s 容差足以暴露算法级错误
 *    （例如采样步长、二次插值或时区处理写错都会偏出分钟级）。
 *
 * 纯 JVM：SunCalc 不依赖 Android。
 */
class SunCalcTest {

    private val angleTolerance = 1e-6
    private val timeToleranceMs = 5_000L

    private val beijingLat = 39.9042
    private val beijingLng = 116.4074
    private val changchunLat = 43.8975
    private val changchunLng = 125.3263

    @Before
    fun setUp() {
        SunCalc.resetTimes()
    }

    @After
    fun tearDown() {
        SunCalc.resetTimes()
    }

    // ------------------------------------------------------------ 太阳位置

    @Test
    fun sunPositionMatchesOracleAtBeijingSolstice() {
        val position = SunCalc.getPosition(1_782_014_400_000L, beijingLat, beijingLng)
        assertEquals(-13.013259193537852, position.azimuthDegrees, angleTolerance)
        assertEquals(73.17846186905643, position.altitudeDegrees, angleTolerance)
    }

    @Test
    fun sunPositionMatchesOracleNearHorizonInWinter() {
        val position = SunCalc.getPosition(1_768_465_800_000L, changchunLat, changchunLng)
        assertEquals(61.505729322913695, position.azimuthDegrees, angleTolerance)
        assertEquals(-1.4523907350696763, position.altitudeDegrees, angleTolerance)
    }

    @Test
    fun sunPositionMatchesOracleBelowHorizonAtNight() {
        val position = SunCalc.getPosition(1_792_152_000_000L, changchunLat, changchunLng)
        assertEquals(112.5164442734234, position.azimuthDegrees, angleTolerance)
        assertEquals(-33.56287033271014, position.altitudeDegrees, angleTolerance)
    }

    // ------------------------------------------------------------ 太阳时刻

    @Test
    fun sunTimesMatchOracleAtBeijingSolstice() {
        val times = SunCalc.getTimes(1_782_014_400_000L, beijingLat, beijingLng, 0.0)
        assertTime(1_782_015_438_033L, times.solarNoon, "solarNoon")
        assertTime(1_781_972_238_033L, times.nadir, "nadir")
        assertTime(1_781_988_430_360L, times.sunrise, "sunrise")
        assertTime(1_782_042_445_706L, times.sunset, "sunset")
        assertTime(1_781_988_626_546L, times.sunriseEnd, "sunriseEnd")
        assertTime(1_782_042_249_519L, times.sunsetStart, "sunsetStart")
        assertTime(1_781_986_461_788L, times.dawn, "dawn")
        assertTime(1_782_044_414_277L, times.dusk, "dusk")
        assertTime(1_781_983_963_604L, times.nauticalDawn, "nauticalDawn")
        assertTime(1_782_046_912_462L, times.nauticalDusk, "nauticalDusk")
        assertTime(1_781_981_050_113L, times.nightEnd, "nightEnd")
        assertTime(1_782_049_825_952L, times.night, "night")
        assertTime(1_781_990_871_996L, times.goldenHourEnd, "goldenHourEnd")
        assertTime(1_782_040_004_070L, times.goldenHour, "goldenHour")
    }

    @Test
    fun sunTimesMatchOracleAtChangchunInOctober() {
        val times = SunCalc.getTimes(1_792_152_000_000L, changchunLat, changchunLng, 0.0)
        assertTime(1_792_121_131_229L, times.solarNoon, "solarNoon")
        assertTime(1_792_077_931_229L, times.nadir, "nadir")
        assertTime(1_792_101_278_943L, times.sunrise, "sunrise")
        assertTime(1_792_140_983_516L, times.sunset, "sunset")
        assertTime(1_792_101_460_139L, times.sunriseEnd, "sunriseEnd")
        assertTime(1_792_140_802_319L, times.sunsetStart, "sunsetStart")
        assertTime(1_792_099_536_970L, times.dawn, "dawn")
        assertTime(1_792_142_725_489L, times.dusk, "dusk")
        assertTime(1_792_097_534_135L, times.nauticalDawn, "nauticalDawn")
        assertTime(1_792_144_728_324L, times.nauticalDusk, "nauticalDusk")
        assertTime(1_792_095_533_213L, times.nightEnd, "nightEnd")
        assertTime(1_792_146_729_246L, times.night, "night")
        assertTime(1_792_103_633_406L, times.goldenHourEnd, "goldenHourEnd")
        assertTime(1_792_138_629_053L, times.goldenHour, "goldenHour")
    }

    @Test
    fun sunTimesMatchOracleAtEquinoxOnEquator() {
        val times = SunCalc.getTimes(1_774_008_000_000L, 0.0, 0.0, 0.0)
        assertTime(1_774_008_530_870L, times.solarNoon, "solarNoon")
        assertTime(1_773_965_330_870L, times.nadir, "nadir")
        assertTime(1_773_986_730_948L, times.sunrise, "sunrise")
        assertTime(1_774_030_330_791L, times.sunset, "sunset")
        assertTime(1_773_986_858_869L, times.sunriseEnd, "sunriseEnd")
        assertTime(1_774_030_202_870L, times.sunsetStart, "sunsetStart")
        assertTime(1_773_985_490_859L, times.dawn, "dawn")
        assertTime(1_774_031_570_880L, times.dusk, "dusk")
        assertTime(1_773_984_050_848L, times.nauticalDawn, "nauticalDawn")
        assertTime(1_774_033_010_891L, times.nauticalDusk, "nauticalDusk")
        assertTime(1_773_982_610_837L, times.nightEnd, "nightEnd")
        assertTime(1_774_034_450_903L, times.night, "night")
        assertTime(1_773_988_370_880L, times.goldenHourEnd, "goldenHourEnd")
        assertTime(1_774_028_690_859L, times.goldenHour, "goldenHour")
    }

    @Test
    fun sunTimesAreChronologicallyOrdered() {
        val times = SunCalc.getTimes(1_792_152_000_000L, changchunLat, changchunLng, 0.0)
        val ordered = listOf(
            times.nightEnd,
            times.nauticalDawn,
            times.dawn,
            times.sunrise,
            times.sunriseEnd,
            times.goldenHourEnd,
            times.solarNoon,
            times.goldenHour,
            times.sunsetStart,
            times.sunset,
            times.dusk,
            times.nauticalDusk,
            times.night,
        ).map { requireNotNull(it) }
        for (i in 1 until ordered.size) {
            assertTrue("index $i 未按时间递增: $ordered", ordered[i] > ordered[i - 1])
        }
    }

    // ------------------------------------------------------------ 极昼 / 极夜

    @Test
    fun midnightSunReturnsNullSunriseInsteadOfInvalidDate() {
        // 北纬 89°、2026-06-21：极昼，原实现返回 Invalid Date，移植版必须给出 null。
        val times = SunCalc.getTimes(1_782_014_400_000L, 89.0, 0.0, 0.0)
        assertNull("极昼不应给出日出时刻", times.sunrise)
        assertNull("极昼不应给出日落时刻", times.sunset)
        assertNotNull("太阳正午仍然存在", times.solarNoon)
    }

    @Test
    fun polarNightReturnsNullSunrise() {
        // 北纬 89°、2026-12-21：极夜。
        val times = SunCalc.getTimes(1_798_000_000_000L, 89.0, 0.0, 0.0)
        assertNull("极夜不应给出日出时刻", times.sunrise)
    }

    // ------------------------------------------------------------ 月球位置

    @Test
    fun moonPositionMatchesOracle() {
        val position = SunCalc.getMoonPosition(1_792_152_000_000L, changchunLat, changchunLng)
        assertEquals(50.051306568052205, position.azimuthDegrees, angleTolerance)
        assertEquals(0.22079378948973793, position.altitudeDegrees, angleTolerance)
        assertEquals(405853.00717873045, position.distance, 1e-3)
        assertEquals(38.630561105206844, position.parallacticAngleDegrees, angleTolerance)
    }

    @Test
    fun moonPositionMatchesOracleOnSummerNight() {
        val position = SunCalc.getMoonPosition(1_782_936_000_000L, beijingLat, beijingLng)
        assertEquals(32.76402938562644, position.azimuthDegrees, angleTolerance)
        assertEquals(20.180795788294567, position.altitudeDegrees, angleTolerance)
        assertEquals(400458.014154549, position.distance, 1e-3)
        assertEquals(26.72994882863166, position.parallacticAngleDegrees, angleTolerance)
    }

    // ------------------------------------------------------------ 月相

    @Test
    fun moonIlluminationMatchesOracle() {
        val first = SunCalc.getMoonIllumination(1_792_152_000_000L)
        assertEquals(0.30727903169285786, first.fraction, 1e-9)
        assertEquals(0.18702450287388883, first.phase, 1e-9)
        assertEquals(-88.08548673996232, first.angle * 180.0 / Math.PI, angleTolerance)

        val full = SunCalc.getMoonIllumination(1_767_441_600_000L)
        assertEquals(0.9981025858233941, full.fraction, 1e-9)
        assertEquals(0.5138697499958466, full.phase, 1e-9)

        val new = SunCalc.getMoonIllumination(1_768_737_600_000L)
        assertEquals(0.0025281515199560567, new.fraction, 1e-9)
        assertEquals(0.983988396006642, new.phase, 1e-9)
    }

    // ------------------------------------------------------------ 月出月落

    @Test
    fun moonTimesMatchOracleForChangchun() {
        val times = SunCalc.getMoonTimes(1_792_108_800_000L, changchunLat, changchunLng)
        assertTime(1_792_122_235_139L, times.rise, "moon rise")
        assertTime(1_792_152_039_061L, times.set, "moon set")
        assertTrue("既非终日不落", !times.alwaysUp)
        assertTrue("也非终日不升", !times.alwaysDown)
    }

    @Test
    fun moonTimesMatchOracleForBeijing() {
        val times = SunCalc.getMoonTimes(1_782_864_000_000L, beijingLat, beijingLng)
        assertTime(1_782_910_593_236L, times.rise, "moon rise")
        assertTime(1_782_945_158_794L, times.set, "moon set")
    }

    @Test
    fun moonTimesLocalDayUsesRequestedUtcOffset() {
        val epoch = 1_792_152_000_000L // 2026-10-16T12:00Z
        val utcDayStart = Math.floorDiv(epoch, SunCalc.DAY_MS) * SunCalc.DAY_MS

        // offset = 0 → 与"UTC 当日 0 点起"完全一致。
        val utcZero = SunCalc.getMoonTimesLocalDay(epoch, changchunLat, changchunLng, 0)
        val directUtc = SunCalc.getMoonTimes(utcDayStart, changchunLat, changchunLng)
        assertEquals(directUtc.rise, utcZero.rise)
        assertEquals(directUtc.set, utcZero.set)

        // offset = +480（东八区）→ 本地日 0 点 = UTC 前一日 16:00Z。
        val plusEight = SunCalc.getMoonTimesLocalDay(epoch, changchunLat, changchunLng, 480)
        val directPlusEight = SunCalc.getMoonTimes(utcDayStart - 8L * 3_600_000L, changchunLat, changchunLng)
        assertEquals(directPlusEight.rise, plusEight.rise)
        assertEquals(directPlusEight.set, plusEight.set)
    }

    // ------------------------------------------------------------ 自定义角度表

    @Test
    fun addTimeRegistersCustomEventAndResetRestoresDefaults() {
        val defaults = SunCalc.timeAngles().size
        SunCalc.addTime(-4.0, "civilTestRise", "civilTestSet")
        assertEquals(defaults + 1, SunCalc.timeAngles().size)

        val times = SunCalc.getTimes(1_792_152_000_000L, changchunLat, changchunLng, 0.0)
        assertNotNull("自定义事件应出现在 custom 表", times.custom["civilTestRise"])

        SunCalc.resetTimes()
        assertEquals(defaults, SunCalc.timeAngles().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateTimeNameIsRejected() {
        SunCalc.addTime(-4.0, "sunrise", "sunset")
    }

    // ------------------------------------------------------------ 时间换算

    @Test
    fun julianRoundTripIsStableWithinOneSecond() {
        val epoch = 1_792_152_000_000L
        val julian = SunCalc.toJulian(epoch)
        val back = requireNotNull(SunCalc.fromJulian(julian))
        assertTrue("儒略日往返误差应在 1s 内", Math.abs(back - epoch) <= 1000L)
    }

    @Test
    fun hoursLaterAdvancesWithinOneSecond() {
        val dayStart = 1_792_108_800_000L
        val plusSix = SunCalc.hoursLater(dayStart, 6.0)
        assertTrue(Math.abs(plusSix - (dayStart + 6L * 3_600_000L)) <= 1000L)
    }

    private fun assertTime(expectedMs: Long, actualMs: Long?, label: String) {
        assertNotNull("$label 缺失", actualMs)
        assertEquals("$label 与 oracle 偏差超容差", expectedMs.toDouble(), actualMs!!.toDouble(), timeToleranceMs.toDouble())
    }
}
