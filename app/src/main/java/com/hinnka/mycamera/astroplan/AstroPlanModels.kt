package com.hinnka.mycamera.astroplan

/**
 * 一段时刻窗口。任一时刻缺失（极昼/极夜/该角度当日不跨越）即为 null。
 */
data class TimeWindow(
    val startMs: Long?,
    val endMs: Long?,
) {
    val durationMs: Long? get() = if (startMs != null && endMs != null) endMs - startMs else null
    val isComplete: Boolean get() = startMs != null && endMs != null

    /** 该时刻是否落在窗口内（窗口不完整时永远 false）。 */
    fun contains(epochMs: Long): Boolean =
        startMs != null && endMs != null && epochMs >= startMs && epochMs < endMs

    /** 距窗口开始的毫秒数；已在窗口内返回 0；窗口缺失返回 null。 */
    fun millisUntilStart(epochMs: Long): Long? =
        startMs?.let { if (epochMs >= it) 0L else it - epochMs }
}

/** 拍摄条件评价等级（供 ASTRO 场景/设置页显示）。 */
enum class AstroSuitability(val displayName: String) {
    /** 非常适合拍星空。 */
    EXCELLENT("非常适合"),

    /** 适合拍星空。 */
    GOOD("适合"),

    /** 一般，可拍但条件不理想。 */
    FAIR("一般"),

    /** 不适合拍星空（天亮或月亮太亮）。 */
    POOR("不适合"),
}

/** 拍摄条件评分结果。 */
data class AstroSuitabilityResult(
    val level: AstroSuitability,
    /** 归一化总分 0..1（越高越适合拍星空）。 */
    val score: Float,
    /** 太阳高度角得分（越低越暗 → 越高分）。 */
    val sunScore: Float,
    /** 月亮照明得分（月面越暗、越在地平线下 → 越高分）。 */
    val moonScore: Float,
    /** 月亮高度角得分（月亮在地平线下 → 满分）。 */
    val moonAltitudeScore: Float,
    /** 月亮"有效亮度" = 照明比例 ×（在地平线上 ? 1 : [AstroPlanConfig] 的折扣）。 */
    val effectiveMoonBrightness: Float,
    /** 评分依据（中文短句，供 UI 直接展示；非空）。 */
    val reasons: List<String>,
    /** 当前是否处于天文暗夜（太阳低于 [AstroPlanConfig.astroDarkSunAltitudeDeg]）。 */
    val isAstroDark: Boolean,
) {
    /** 是否建议拍摄星空（等级 ≥ GOOD 且处于天文暗夜）。 */
    val recommended: Boolean
        get() = isAstroDark && (level == AstroSuitability.EXCELLENT || level == AstroSuitability.GOOD)
}

/**
 * 一次完整的日月位置规划结果。
 *
 * @param computedAtMs 计算时刻。
 * @param sunPosition 当前太阳地平坐标（方位角弧度）。
 * @param moonPosition 当前月亮地平坐标。
 * @param illumination 当前月相照明。
 * @param sunTimes 当天太阳事件时刻表。
 * @param moonTimes 当天（本地 0 点起）月亮升落。
 * @param goldenHourMorning 晨间黄金时刻窗口 = [日出, 太阳升到 +6°]。
 * @param goldenHourEvening 傍晚黄金时刻窗口 = [太阳降到 +6°, 日落]。
 * @param blueHourMorning 晨间蓝调时刻窗口 = [太阳升到 -6°, 日出]。
 * @param blueHourEvening 傍晚蓝调时刻窗口 = [日落, 太阳降到 -6°]。
 * @param astroDarkWindow 与当前时刻相关的那一段天文暗夜（正在其中即为当前段）。
 * @param suitability 拍摄条件评分。
 * @param nextEventName 下一个摄影相关事件名（中文）。
 * @param nextEventAtMs 下一个摄影相关事件时刻；无则为 null。
 * @param secondsUntilNextEvent 距该事件的秒数；无则为 null。
 */
data class AstroPlan(
    val computedAtMs: Long,
    val sunPosition: HorizontalPosition,
    val moonPosition: MoonPosition,
    val illumination: MoonIllumination,
    val sunTimes: SunTimes,
    val moonTimes: MoonTimes,
    val goldenHourMorning: TimeWindow,
    val goldenHourEvening: TimeWindow,
    val blueHourMorning: TimeWindow,
    val blueHourEvening: TimeWindow,
    val astroDarkWindow: TimeWindow,
    val suitability: AstroSuitabilityResult,
    val nextEventName: String?,
    val nextEventAtMs: Long?,
    val secondsUntilNextEvent: Long?,
) {
    /** 太阳是否在地平线以上。 */
    val sunUp: Boolean get() = sunPosition.altitude > 0.0

    /** 月亮是否在地平线以上。 */
    val moonUp: Boolean get() = moonPosition.altitude > 0.0

    /**
     * 黄金时刻倒计时（毫秒）：正在黄金时刻内为 0，否则为距下一段黄金时刻开始的毫秒数；
     * 两段都还没开始且都在未来时取更近的一段，都已结束则为 null。
     *
     * 实现委托给 [AstroPlanner.goldenCountdown]，保证模型与计划器**只有一处判断逻辑**。
     */
    val goldenHourCountdownMs: Long?
        get() = AstroPlanner.goldenCountdown(computedAtMs, goldenHourMorning, goldenHourEvening)
}
