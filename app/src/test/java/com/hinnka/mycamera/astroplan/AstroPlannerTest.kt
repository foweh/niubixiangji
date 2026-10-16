package com.hinnka.mycamera.astroplan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日月位置计划器单测（纯 JVM）。
 *
 * 覆盖：
 *  - [AstroPlanner.formatCountdown] 各档位边界；
 *  - [AstroPlanner.evaluate] 评分单调性、等级单调性、good > fair 保证、等权兜底；
 *  - [AstroPlanner.goldenCountdown] 窗口内 / 窗口之间 / 全部结束 / 窗口缺失；
 *  - [AstroPlanConfig] 每个开放参数的 clamp 与 DataStore key 清单；
 *  - 已知时刻下的整链路 [AstroPlanner.plan] 与 suncalc oracle 值比对（长春 2026-10-16）。
 *
 * 说明：本类只使用**默认/合法**配置调用 `plan`，因此不会触发 `PLog`（纯 JVM 单测无
 * `android.util.Log`）；`plan` 中用到的 `SunCalc` 角度表在 [resetSunTimes] 里复位，
 * 避免用例间相互污染。
 */
class AstroPlannerTest {

    @After
    fun resetSunTimes() {
        SunCalc.resetTimes()
    }

    // ------------------------------------------------------------ 倒计时格式化

    @Test
    fun formatCountdownCoversEveryBucket() {
        assertEquals("0秒", AstroPlanner.formatCountdown(0L))
        assertEquals("0秒", AstroPlanner.formatCountdown(-5_000L))
        assertEquals("5秒", AstroPlanner.formatCountdown(5_000L))
        assertEquals("59秒", AstroPlanner.formatCountdown(59_999L))
        assertEquals("1分", AstroPlanner.formatCountdown(60_000L))
        assertEquals("59分", AstroPlanner.formatCountdown(59 * 60_000L + 59_000L))
        assertEquals("1小时", AstroPlanner.formatCountdown(3_600_000L))
        assertEquals("1小时2分", AstroPlanner.formatCountdown(3_600_000L + 120_000L))
        assertEquals("23小时59分", AstroPlanner.formatCountdown(23 * 3_600_000L + 59 * 60_000L))
        assertEquals("1天0小时", AstroPlanner.formatCountdown(86_400_000L))
        assertEquals("1天1小时", AstroPlanner.formatCountdown(90_000_000L))
        assertEquals("2天3小时", AstroPlanner.formatCountdown(2 * 86_400_000L + 3 * 3_600_000L))
    }

    // ------------------------------------------------------------ 时刻窗口

    @Test
    fun timeWindowHelperSemanticsAreExplicit() {
        val window = TimeWindow(1_000L, 2_000L)
        assertTrue(window.isComplete)
        assertEquals(1_000L, window.durationMs)
        assertTrue(window.contains(1_000L))
        assertFalse("右开区间", window.contains(2_000L))
        assertFalse(window.contains(999L))
        assertEquals(0L, window.millisUntilStart(1_500L))
        assertEquals(500L, window.millisUntilStart(500L))

        val partial = TimeWindow(null, 5_000L)
        assertFalse(partial.isComplete)
        assertFalse(partial.contains(2_000L))
        assertNull(partial.durationMs)
        assertNull(partial.millisUntilStart(2_000L))
    }

    @Test
    fun goldenCountdownIsZeroInsideWindow() {
        val morning = TimeWindow(1_000L, 2_000L)
        assertEquals(0L, AstroPlanner.goldenCountdown(1_500L, morning, TimeWindow(9_000L, 10_000L)))
    }

    @Test
    fun goldenCountdownPointsToNextWindowOnlyWhenItIsStillAhead() {
        val morning = TimeWindow(1_000L, 2_000L)
        val evening = TimeWindow(9_000L, 10_000L)
        // 两段之间的"白天"：必须指向尚未开始的傍晚段，而不是被已结束的早晨段骗成 0。
        assertEquals(6_000L, AstroPlanner.goldenCountdown(3_000L, morning, evening))
        // 两段之前：取更近的一段（早晨段）。
        assertEquals(500L, AstroPlanner.goldenCountdown(500L, morning, evening))
    }

    @Test
    fun goldenCountdownIsNullAfterBothWindowsHaveEnded() {
        assertNull(
            AstroPlanner.goldenCountdown(
                20_000L,
                TimeWindow(1_000L, 2_000L),
                TimeWindow(9_000L, 10_000L),
            ),
        )
    }

    @Test
    fun goldenCountdownIsNullWhenWindowsAreMissing() {
        assertNull(AstroPlanner.goldenCountdown(20_000L, TimeWindow(null, null), TimeWindow(null, null)))
        // 极昼/极夜：只有一半窗口时按"开始是否在未来"判定。
        assertEquals(1_000L, AstroPlanner.goldenCountdown(1_000L, TimeWindow(2_000L, null), TimeWindow(null, null)))
    }

    // ------------------------------------------------------------ 评分

    @Test
    fun deepNightWithNewMoonScoresExcellentAndIsRecommended() {
        val result = AstroPlanner.evaluate(
            sunAltitudeDegrees = -25.0,
            moonAltitudeDegrees = -40.0,
            moonFraction = 0.0,
        )
        assertEquals(AstroSuitability.EXCELLENT, result.level)
        assertTrue(result.isAstroDark)
        assertTrue(result.recommended)
        assertEquals(1f, result.score, 1e-6f)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun daylightIsAlwaysPoor() {
        val result = AstroPlanner.evaluate(30.0, 60.0, 0.0)
        assertEquals(AstroSuitability.POOR, result.level)
        assertFalse(result.isAstroDark)
        assertFalse(result.recommended)
        assertEquals(0f, result.sunScore, 1e-6f)
    }

    @Test
    fun twilightIsCappedAtFairEvenWithDarkMoon() {
        val result = AstroPlanner.evaluate(-10.0, -40.0, 0.0)
        assertEquals(AstroSuitability.FAIR, result.level)
        assertFalse(result.isAstroDark)
        assertFalse(result.recommended)
        assertTrue(result.sunScore in 0.5f..0.6f) // 10/18 ≈ 0.556
    }

    @Test
    fun fullMoonAboveHorizonDegradesScoreAndDropsRecommendation() {
        val result = AstroPlanner.evaluate(-25.0, 60.0, 1.0)
        assertEquals(AstroSuitability.POOR, result.level)
        assertFalse("满月在空 → 不建议拍星空", result.recommended)
        assertEquals(0f, result.moonScore, 1e-6f)
        assertEquals(1f, result.effectiveMoonBrightness, 1e-6f)
    }

    @Test
    fun crescentMoonAboveHorizonStillExcellent() {
        val result = AstroPlanner.evaluate(-25.0, 30.0, 0.1)
        assertEquals(AstroSuitability.EXCELLENT, result.level)
        assertTrue(result.recommended)
    }

    @Test
    fun sunScoreAndTotalScoreAreMonotonicInDarkness() {
        val altitudes = listOf(-4.0, -8.0, -12.0, -18.0, -25.0)
        var lastScore = -1f
        var lastLevel = AstroSuitability.POOR
        for (alt in altitudes) {
            val result = AstroPlanner.evaluate(alt, -30.0, 0.2)
            assertTrue("太阳越低分不得越低（$alt）", result.score >= lastScore - 1e-6f)
            assertTrue("等级不得变差（$alt）", result.level.ordinal <= lastLevel.ordinal)
            lastScore = result.score
            lastLevel = result.level
            if (alt > -18.0) {
                assertFalse("未达天文暗夜不得判为可拍", result.isAstroDark)
            } else {
                assertTrue(result.isAstroDark)
            }
        }
    }

    @Test
    fun moonScoreDecreasesAsIlluminationGrows() {
        val new = AstroPlanner.evaluate(-25.0, 20.0, 0.0)
        val half = AstroPlanner.evaluate(-25.0, 20.0, 0.5)
        val full = AstroPlanner.evaluate(-25.0, 20.0, 1.0)
        assertTrue(new.moonScore > half.moonScore)
        assertTrue(half.moonScore > full.moonScore)
        assertTrue(new.score > half.score)
        assertTrue(half.score > full.score)
    }

    @Test
    fun moonBelowHorizonIsDiscountedNotIgnored() {
        val belowHorizon = AstroPlanner.evaluate(-25.0, -30.0, 1.0)
        val aboveHorizon = AstroPlanner.evaluate(-25.0, 30.0, 1.0)
        assertEquals(
            AstroPlanner.MOON_BELOW_HORIZON_DISCOUNT,
            belowHorizon.effectiveMoonBrightness,
            1e-6f,
        )
        assertTrue(
            "地平线下的满月应比地平线上的满月更干净（月光被大气消光折扣）",
            belowHorizon.moonScore > aboveHorizon.moonScore,
        )
    }

    @Test
    fun zeroWeightsFallBackToEqualAverage() {
        val config = AstroPlanConfig(sunWeight = 0f, moonWeight = 0f, moonAltitudeWeight = 0f)
        val result = AstroPlanner.evaluate(-25.0, -40.0, 0.0, config)
        assertEquals(1f, result.score, 1e-6f) // (1 + 1 + 1) / 3
    }

    @Test
    fun outOfRangeAnglesAreClampedInsteadOfBreakingScore() {
        val result = AstroPlanner.evaluate(Double.NaN, Double.POSITIVE_INFINITY, Double.NaN)
        assertTrue(result.score in 0f..1f)
        assertNotNull(result.level)
        assertTrue(result.reasons.isNotEmpty())
    }

    // ------------------------------------------------------------ 配置 clamp

    @Test
    fun configClampsEveryOpenParameter() {
        val (config, reports) = AstroPlanConfig(
            latitudeDegrees = 200.0,
            longitudeDegrees = -999.0,
            elevationMeters = 99_999.0,
            utcOffsetMinutes = 5_000,
            sunWeight = 9f,
            moonWeight = -3f,
            moonAltitudeWeight = Float.NaN,
            astroDarkSunAltitudeDeg = -100.0,
            goldenHourUpperAngleDeg = 50.0,
            blueHourLowerAngleDeg = 5.0,
            goodScoreThreshold = 9f,
            fairScoreThreshold = -9f,
            maxMoonFractionForAstro = 9f,
        ).clampWithReport()

        assertEquals(90.0, config.latitudeDegrees, 1e-9)
        assertEquals(-180.0, config.longitudeDegrees, 1e-9)
        assertEquals(9_000.0, config.elevationMeters, 1e-9)
        assertEquals(840, config.utcOffsetMinutes)
        assertEquals(1f, config.sunWeight, 1e-6f)
        assertEquals(0f, config.moonWeight, 1e-6f)
        assertEquals(0.15f, config.moonAltitudeWeight, 1e-6f) // NaN → 默认值
        assertEquals(-30.0, config.astroDarkSunAltitudeDeg, 1e-9)
        assertEquals(12.0, config.goldenHourUpperAngleDeg, 1e-9)
        assertEquals(-3.0, config.blueHourLowerAngleDeg, 1e-9)
        assertEquals(1f, config.goodScoreThreshold, 1e-6f)
        assertEquals(0f, config.fairScoreThreshold, 1e-6f)
        assertEquals(1f, config.maxMoonFractionForAstro, 1e-6f)
        assertTrue("每项越界都应记录修正说明", reports.size >= 10)
    }

    @Test
    fun nonFiniteCoordinatesFallBackToDefaultsInsteadOfCrashing() {
        val config = AstroPlanConfig(
            latitudeDegrees = Double.NaN,
            longitudeDegrees = Double.NEGATIVE_INFINITY,
            elevationMeters = Double.NaN,
        ).clamped()
        assertEquals(0.0, config.latitudeDegrees, 1e-9)
        assertEquals(0.0, config.longitudeDegrees, 1e-9)
        assertEquals(0.0, config.elevationMeters, 1e-9)
    }

    @Test
    fun goodThresholdIsKeptStrictlyAboveFairThreshold() {
        val (config, reports) = AstroPlanConfig(
            goodScoreThreshold = 0.30f,
            fairScoreThreshold = 0.90f,
        ).clampWithReport()
        assertTrue("good 必须严格大于 fair", config.goodScoreThreshold > config.fairScoreThreshold)
        assertEquals(0.95f, config.goodScoreThreshold, 1e-6f)
        assertTrue(reports.any { it.contains("goodScoreThreshold") })
    }

    @Test
    fun defaultConfigNeedsNoCorrection() {
        val (config, reports) = AstroPlanConfig.DEFAULT.clampWithReport()
        assertTrue("默认配置不得产生修正说明：$reports", reports.isEmpty())
        assertEquals(AstroPlanConfig.DEFAULT, config)
    }

    @Test
    fun fromPreferencesUsesProvidedValuesAndStaysSilentWhenInRange() {
        val values = mapOf(
            AstroPlanConfig.KEY_LATITUDE to 31.23,
            AstroPlanConfig.KEY_LONGITUDE to 121.47,
            AstroPlanConfig.KEY_ELEVATION to 4.0,
            AstroPlanConfig.KEY_UTC_OFFSET to 480,
            AstroPlanConfig.KEY_MOON_WEIGHT to 0.5,
            AstroPlanConfig.KEY_SUN_WEIGHT to 0.3,
            AstroPlanConfig.KEY_GOOD_THRESHOLD to 0.6,
        )
        val config = AstroPlanConfig.DEFAULT.fromPreferences { values[it] }
        assertEquals(31.23, config.latitudeDegrees, 1e-9)
        assertEquals(121.47, config.longitudeDegrees, 1e-9)
        assertEquals(4.0, config.elevationMeters, 1e-9)
        assertEquals(480, config.utcOffsetMinutes)
        assertEquals(0.5f, config.moonWeight, 1e-6f)
        assertEquals(0.3f, config.sunWeight, 1e-6f)
        assertEquals(0.6f, config.goodScoreThreshold, 1e-6f)
        assertTrue(config.goodScoreThreshold > config.fairScoreThreshold)
    }

    @Test
    fun fromPreferencesFallsBackToDefaultsForMalformedValues() {
        val config = AstroPlanConfig.DEFAULT.fromPreferences { key ->
            when (key) {
                AstroPlanConfig.KEY_LATITUDE -> "not-a-number"
                AstroPlanConfig.KEY_ELEVATION -> Double.NaN
                else -> null
            }
        }
        assertEquals(AstroPlanConfig.DEFAULT.latitudeDegrees, config.latitudeDegrees, 1e-9)
        assertEquals(AstroPlanConfig.DEFAULT.elevationMeters, config.elevationMeters, 1e-9)
    }

    @Test
    fun dataStoreKeysAreDistinctAndNamespaced() {
        val keys = listOf(
            AstroPlanConfig.KEY_LATITUDE,
            AstroPlanConfig.KEY_LONGITUDE,
            AstroPlanConfig.KEY_ELEVATION,
            AstroPlanConfig.KEY_UTC_OFFSET,
            AstroPlanConfig.KEY_MOON_WEIGHT,
            AstroPlanConfig.KEY_SUN_WEIGHT,
            AstroPlanConfig.KEY_GOOD_THRESHOLD,
        )
        assertEquals(7, keys.size)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { it.startsWith("astro_plan_") })
    }

    // ------------------------------------------------------------ 整链路 plan

    /** 长春 2026-10-16T12:00Z（本地 20:00）——与 suncalc 官方实现的 oracle 值一致。 */
    private val knownInstant = 1_792_152_000_000L

    @Test
    fun planOnKnownInstantMatchesSuncalcOracle() {
        val plan = AstroPlanner.plan(knownInstant, AstroPlanConfig.DEFAULT)

        assertEquals(knownInstant, plan.computedAtMs)
        assertEquals(-33.56287033271014, plan.sunPosition.altitudeDegrees, 1e-9)
        assertEquals(0.22079378948973793, plan.moonPosition.altitudeDegrees, 1e-9)
        assertEquals(0.30727903169285786, plan.illumination.fraction, 1e-12)
        assertFalse(plan.sunUp)
        assertTrue(plan.moonUp)

        assertEquals(31, AstroPlanner.moonIlluminationPercent(plan.illumination))
        assertTrue(plan.suitability.isAstroDark)
        assertEquals(AstroSuitability.GOOD, plan.suitability.level)
        assertTrue(plan.suitability.recommended)
        assertNotNull(plan.suitability.reasons.firstOrNull())
    }

    @Test
    fun planAstroDarkWindowAgreesWithSuitabilityFlag() {
        val plan = AstroPlanner.plan(knownInstant, AstroPlanConfig.DEFAULT)
        assertTrue(plan.suitability.isAstroDark)
        assertTrue("评分说在天文暗夜，窗口就必须包含当前时刻", plan.astroDarkWindow.contains(knownInstant))
    }

    @Test
    fun planGoldenHourCountdownIsNullAfterBothWindowsEnd() {
        val plan = AstroPlanner.plan(knownInstant, AstroPlanConfig.DEFAULT)
        // 本地 20:00：当天两段黄金时刻都已结束 → 不应再显示"正在黄金时刻"。
        assertNull(plan.goldenHourCountdownMs)
        assertTrue(plan.sunTimes.goldenHour!! < knownInstant)
    }

    @Test
    fun planGoldenHourCountdownCountsDownBeforeSunriseWindow() {
        // 长春 2026-10-16 本地 03:00（= 2026-10-15T19:00Z）→ 距当天早晨黄金时刻（始于日出）还有约 2.9 小时。
        // oracle：日出 = 1792101278943（refgen2.js，suncalc@1.9.0）。
        val beforeDawn = 1_792_090_800_000L
        val plan = AstroPlanner.plan(beforeDawn, AstroPlanConfig.DEFAULT)
        val countdown = plan.goldenHourCountdownMs
        assertNotNull(countdown)
        assertTrue(countdown!! > 0L)
        assertEquals(
            plan.sunTimes.sunrise!! - beforeDawn,
            countdown,
        )
    }

    @Test
    fun planNextEventIsTheNearestFutureEvent() {
        val plan = AstroPlanner.plan(knownInstant, AstroPlanConfig.DEFAULT)
        assertNotNull(plan.nextEventName)
        val at = plan.nextEventAtMs
        assertNotNull(at)
        assertTrue("下一个事件必须在当前时刻之后", at!! > knownInstant)
        assertEquals((at - knownInstant) / 1000L, plan.secondsUntilNextEvent)
    }

    @Test
    fun planExposesCustomSunTimeAngles() {
        val config = AstroPlanConfig.DEFAULT.copy(
            customTimeAngles = listOf(SunTimeAngle(-4.0, "cityBlueStart", "cityBlueEnd")),
        )
        val plan = AstroPlanner.plan(knownInstant, config)
        assertTrue(plan.sunTimes.custom.containsKey("cityBlueStart"))
        assertTrue(plan.sunTimes.custom.containsKey("cityBlueEnd"))
        assertTrue(SunCalc.timeAngles().any { it.riseName == "cityBlueStart" })
    }

    @Test
    fun planDoesNotRegisterTheSameCustomAngleTwice() {
        val config = AstroPlanConfig.DEFAULT.copy(
            customTimeAngles = listOf(SunTimeAngle(-4.5, "dupStart", "dupEnd")),
        )
        AstroPlanner.plan(knownInstant, config)
        val countAfterFirst = SunCalc.timeAngles().size
        AstroPlanner.plan(knownInstant, config)
        assertEquals("重复调用不得重复注册", countAfterFirst, SunCalc.timeAngles().size)
    }
}
