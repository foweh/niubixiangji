package com.hinnka.mycamera.astroplan

import com.hinnka.mycamera.utils.PLog
import kotlin.math.roundToInt

/**
 * 日月位置计划器：把 [SunCalc] 的原始天文量整理成"摄影可用结论"。
 *
 * 供 ASTRO 场景与设置页显示：
 *  - 现在适不适合拍星空（[AstroSuitability] + 理由）；
 *  - 黄金时刻 / 蓝调时刻倒计时（[AstroPlan.goldenHourCountdownMs]）；
 *  - 天文暗夜窗口、月出月落、月相照明。
 *
 * 纯 Kotlin（只依赖 [SunCalc] 与 [AstroPlanConfig]），可在 JVM 单测直接跑。
 */
object AstroPlanner {

    private const val TAG = "AstroPlanner"

    /** 月亮在地平线以下时，其照明对拍摄的等效影响折扣（低月亮被大气消光、遮挡）。 */
    const val MOON_BELOW_HORIZON_DISCOUNT = 0.35f

    /**
     * 计算一次完整规划。
     *
     * @param nowMs 计算时刻（epoch 毫秒）。
     * @param config 配置（内部会 clamp；越界项写日志）。
     */
    fun plan(nowMs: Long, config: AstroPlanConfig = AstroPlanConfig.DEFAULT): AstroPlan {
        val (cfg, reports) = config.clampWithReport()
        if (reports.isNotEmpty()) PLog.i(TAG, "config clamped: ${reports.joinToString()}")
        syncCustomTimes(cfg)

        val lat = cfg.latitudeDegrees
        val lng = cfg.longitudeDegrees
        val sunPosition = SunCalc.getPosition(nowMs, lat, lng)
        val moonPosition = SunCalc.getMoonPosition(nowMs, lat, lng)
        val illumination = SunCalc.getMoonIllumination(nowMs)
        val sunTimes = SunCalc.getTimes(nowMs, lat, lng, cfg.elevationMeters)
        val moonTimes = SunCalc.getMoonTimesLocalDay(nowMs, lat, lng, cfg.utcOffsetMinutes)

        val goldenMorning = TimeWindow(sunTimes.sunrise, sunTimes.goldenHourEnd)
        val goldenEvening = TimeWindow(sunTimes.goldenHour, sunTimes.sunset)
        val blueMorning = TimeWindow(sunTimes.dawn, sunTimes.sunrise)
        val blueEvening = TimeWindow(sunTimes.sunset, sunTimes.dusk)

        val astroDark = resolveAstroDarkWindow(nowMs, cfg, sunTimes)

        val suitability = evaluate(
            sunAltitudeDegrees = sunPosition.altitudeDegrees,
            moonAltitudeDegrees = moonPosition.altitudeDegrees,
            moonFraction = illumination.fraction,
            config = cfg,
            goldenHourMorning = goldenMorning,
            goldenHourEvening = goldenEvening,
            nowMs = nowMs,
        )

        val event = nextEvent(nowMs, sunTimes, moonTimes)

        return AstroPlan(
            computedAtMs = nowMs,
            sunPosition = sunPosition,
            moonPosition = moonPosition,
            illumination = illumination,
            sunTimes = sunTimes,
            moonTimes = moonTimes,
            goldenHourMorning = goldenMorning,
            goldenHourEvening = goldenEvening,
            blueHourMorning = blueMorning,
            blueHourEvening = blueEvening,
            astroDarkWindow = astroDark,
            suitability = suitability,
            nextEventName = event?.first,
            nextEventAtMs = event?.second,
            secondsUntilNextEvent = event?.second?.let { (it - nowMs) / 1000L },
        )
    }

    /** 只要评分（UI 高频刷新用，避免每次都算整套窗口）。 */
    fun evaluate(
        sunAltitudeDegrees: Double,
        moonAltitudeDegrees: Double,
        moonFraction: Double,
        config: AstroPlanConfig = AstroPlanConfig.DEFAULT,
        goldenHourMorning: TimeWindow = TimeWindow(null, null),
        goldenHourEvening: TimeWindow = TimeWindow(null, null),
        nowMs: Long = 0L,
    ): AstroSuitabilityResult {
        val darkThreshold = config.astroDarkSunAltitudeDeg
        // 非有限输入（定位异常/未初始化状态）统一回退为 0，避免 NaN 顺着评分传到 UI。
        val sunAlt = finite(sunAltitudeDegrees).coerceIn(-90.0, 90.0)
        val moonAlt = finite(moonAltitudeDegrees).coerceIn(-90.0, 90.0)
        val fraction = finite(moonFraction).coerceIn(0.0, 1.0)

        // 太阳越深越暗 → 分越高；刚好在地平线（0°）时为 0 分，达到暗夜阈值时为满分。
        // 注意 darkThreshold 为负，两者相除得 (0,1] 的正值。
        val sunScore = if (sunAlt <= darkThreshold) {
            1f
        } else if (sunAlt >= 0.0) {
            0f
        } else {
            (sunAlt / darkThreshold).coerceIn(0.0, 1.0).toFloat()
        }

        val moonUp = moonAlt > 0.0
        val effectiveBrightness =
            (fraction * if (moonUp) 1f else MOON_BELOW_HORIZON_DISCOUNT).toFloat().coerceIn(0f, 1f)
        val moonScore = (1f - effectiveBrightness).coerceIn(0f, 1f)
        val moonAltitudeScore = if (!moonUp) 1f else (1f - (moonAlt / 90.0).coerceIn(0.0, 1.0)).toFloat()

        val sunW = config.sunWeight
        val moonW = config.moonWeight
        val moonAltW = config.moonAltitudeWeight
        val sum = sunW + moonW + moonAltW
        val score = if (sum <= 0f) {
            (sunScore + moonScore + moonAltitudeScore) / 3f
        } else {
            (sunW * sunScore + moonW * moonScore + moonAltW * moonAltitudeScore) / sum
        }

        val isAstroDark = sunAlt <= darkThreshold
        val level = when {
            // 白天（太阳在地平线上）不可能拍星空，直接最差。
            sunAlt > 0.0 -> AstroSuitability.POOR
            // 未达天文暗夜时最高只能到"一般"（还有暮光/城市天光）。
            !isAstroDark -> AstroSuitability.FAIR
            score >= (config.goodScoreThreshold + 1f) / 2f -> AstroSuitability.EXCELLENT
            score >= config.goodScoreThreshold -> AstroSuitability.GOOD
            score >= config.fairScoreThreshold -> AstroSuitability.FAIR
            else -> AstroSuitability.POOR
        }

        val reasons = ArrayList<String>(4)
        reasons.add(
            if (isAstroDark) {
                "太阳高度 ${fmt(sunAlt, 1)}°，已进入天文暗夜"
            } else {
                "太阳高度 ${fmt(sunAlt, 1)}°，未达天文暗夜（阈值 ${fmt(darkThreshold, 0)}°）"
            }
        )
        reasons.add(
            "月面照明 ${fmt(fraction * 100, 0)}%，月亮在地平线" + if (moonUp) "以上" else "以下"
        )
        if (fraction <= config.maxMoonFractionForAstro) {
            reasons.add("月面照明低于 ${fmt(config.maxMoonFractionForAstro.toDouble() * 100.0, 0)}%，月光干扰小")
        } else {
            reasons.add("月面照明高于 ${fmt(config.maxMoonFractionForAstro.toDouble() * 100.0, 0)}%，月光会抬高背景亮度")
        }
        val countdown = goldenCountdown(nowMs, goldenHourMorning, goldenHourEvening)
        if (countdown == 0L) {
            reasons.add("当前正处于黄金时刻")
        } else if (countdown != null) {
            reasons.add("距下一段黄金时刻 ${formatCountdown(countdown)}")
        }

        return AstroSuitabilityResult(
            level = level,
            score = score.coerceIn(0f, 1f),
            sunScore = sunScore,
            moonScore = moonScore,
            moonAltitudeScore = moonAltitudeScore,
            effectiveMoonBrightness = effectiveBrightness,
            reasons = reasons,
            isAstroDark = isAstroDark,
        )
    }

    /**
     * 黄金时刻倒计时（毫秒）：正在窗口内为 0，否则距**下一个尚未开始**的窗口开始；
     * 两段都已结束（或窗口缺失）时返回 null。
     *
     * 注意：不能直接用 [TimeWindow.millisUntilStart]，因为它对"已经过去的窗口"也返回 0，
     * 会让中午的界面误报"当前正处于黄金时刻"。这里只接受 `startMs > nowMs` 的窗口。
     */
    fun goldenCountdown(nowMs: Long, morning: TimeWindow, evening: TimeWindow): Long? {
        if (morning.contains(nowMs) || evening.contains(nowMs)) return 0L
        return listOf(morning, evening)
            .mapNotNull { window -> window.startMs?.takeIf { it > nowMs }?.let { it - nowMs } }
            .minOrNull()
    }

    /**
     * 与当前时刻相关的那一段天文暗夜。
     *
     * 候选（同一本地日内）：
     *  1) 今日凌晨段 = [今日 nightEnd, 今日 dawn]（0 点前已入夜，太阳在 -18° 以下直到 nightEnd）；
     *  2) 今晚起段   = [今日 night, 次日 nightEnd]（当晚入夜到次日天文黎明）。
     * 选取"包含 now 的那段"，否则选"起点在 now 之后且最近的那段"。
     */
    private fun resolveAstroDarkWindow(
        nowMs: Long,
        config: AstroPlanConfig,
        todayTimes: SunTimes,
    ): TimeWindow {
        val morrowMs = nowMs + SunCalc.DAY_MS
        val morrowTimes = SunCalc.getTimes(morrowMs, config.latitudeDegrees, config.longitudeDegrees, config.elevationMeters)
        val candidates = listOf(
            TimeWindow(todayTimes.nightEnd, todayTimes.dawn),
            TimeWindow(todayTimes.night, morrowTimes.nightEnd),
        )
        candidates.firstOrNull { it.contains(nowMs) }?.let { return it }
        return candidates
            .filter { it.startMs != null && it.startMs > nowMs }
            .minByOrNull { it.startMs!! }
            ?: candidates.last()
    }

    /** 下一个摄影相关事件（日出/日落/黄金时刻/蓝调/天文暗夜/月出月落）。 */
    private fun nextEvent(
        nowMs: Long,
        sunTimes: SunTimes,
        moonTimes: MoonTimes,
    ): Pair<String, Long>? {
        val events = listOfNotNull(
            sunTimes.sunrise?.let { "日出" to it },
            sunTimes.goldenHourEnd?.let { "黄金时刻结束" to it },
            sunTimes.sunset?.let { "日落" to it },
            sunTimes.goldenHour?.let { "黄金时刻开始" to it },
            sunTimes.dawn?.let { "晨间蓝调开始" to it },
            sunTimes.dusk?.let { "傍晚蓝调结束" to it },
            sunTimes.nightEnd?.let { "天文暗夜结束" to it },
            sunTimes.night?.let { "天文暗夜开始" to it },
            moonTimes.rise?.let { "月出" to it },
            moonTimes.set?.let { "月落" to it },
        )
        return events.filter { it.second > nowMs }.minByOrNull { it.second }
    }

    /** 把配置里的自定义角度同步进 [SunCalc]（已存在同名则跳过，避免重复注册抛错）。 */
    private fun syncCustomTimes(config: AstroPlanConfig) {
        if (config.customTimeAngles.isEmpty()) return
        val existing = SunCalc.timeAngles().flatMap { listOf(it.riseName, it.setName) }.toSet()
        for (angle in config.customTimeAngles) {
            if (angle.riseName in existing || angle.setName in existing) continue
            runCatching { SunCalc.addTime(angle.angleDegrees, angle.riseName, angle.setName) }
                .onFailure { PLog.w(TAG, "skip custom sun time ${angle.riseName}: ${it.message}") }
        }
    }

    /**
     * 人类可读倒计时（纯函数，可单测）：
     *  - < 0 或 0        → "0秒"（已到）
     *  - < 60s           → "N秒"
     *  - < 60min         → "N分"
     *  - < 24h           → "H小时M分"（M 为 0 时省略）
     *  - >= 24h          → "D天H小时"
     */
    fun formatCountdown(millis: Long): String {
        if (millis <= 0L) return "0秒"
        val totalSeconds = millis / 1000L
        if (totalSeconds < 60L) return "${totalSeconds}秒"
        val totalMinutes = totalSeconds / 60L
        if (totalMinutes < 60L) return "${totalMinutes}分"
        val totalHours = totalMinutes / 60L
        if (totalHours < 24L) {
            val minutes = totalMinutes % 60L
            return if (minutes == 0L) "${totalHours}小时" else "${totalHours}小时${minutes}分"
        }
        val days = totalHours / 24L
        val hours = totalHours % 24L
        return "${days}天${hours}小时"
    }

    private fun finite(value: Double, fallback: Double = 0.0): Double =
        if (value.isFinite()) value else fallback

    private fun fmt(value: Double, digits: Int): String {
        val rounded = when (digits) {
            0 -> Math.round(value).toDouble()
            1 -> Math.round(value * 10.0) / 10.0
            else -> Math.round(value * 100.0) / 100.0
        }
        return if (digits == 0) rounded.toLong().toString() else rounded.toString()
    }

    /** 月亮照明百分比（0..100），供 UI 显示。 */
    fun moonIlluminationPercent(illumination: MoonIllumination): Int =
        (illumination.fraction * 100).roundToInt()
}
