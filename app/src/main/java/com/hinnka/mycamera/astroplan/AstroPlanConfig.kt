package com.hinnka.mycamera.astroplan

import com.hinnka.mycamera.utils.PLog

/**
 * 日月位置计划器配置（全部开放可调：默认值 + 取值范围 + 越界 clamp）。
 *
 * 取值范围（越界一律 clamp，不崩溃）：
 *  - [latitudeDegrees]   -90.0 .. 90.0（度）
 *  - [longitudeDegrees] -180.0 .. 180.0（度，东经为正）
 *  - [elevationMeters]     0.0 .. 9000.0（米，影响日出日落的地平线俯角）
 *  - [utcOffsetMinutes] -720 .. 840（本地时区偏移，用于"当天"划分与倒计时展示）
 *  - 三个评分权重        0.0 .. 1.0（会按总和归一化；总和为 0 时回退等权）
 *  - [astroDarkSunAltitudeDeg]  -30.0 .. -6.0（天文暗夜阈值，默认 -18）
 *  - [goldenHourUpperAngleDeg]    0.0 .. 12.0（黄金时刻上限，默认 6）
 *  - [blueHourLowerAngleDeg]     -12.0 .. -3.0（蓝调时刻下限，默认 -6）
 *  - [goodScoreThreshold]/[fairScoreThreshold] 0.0 .. 1.0，且 good > fair（自动纠正）
 *  - [maxMoonFractionForAstro]    0.0 .. 1.0（可拍星空的最大月面照明比例，默认 0.35）
 *
 * @param customTimeAngles 额外注册的太阳角度事件（可为空）；由 [AstroPlanner] 在计算前
 *   同步到 [SunCalc]，用于 UI 自定义"摄影时刻"（如 -4° 的"城市蓝调"）。
 */
data class AstroPlanConfig(
    val latitudeDegrees: Double = 43.8975,
    val longitudeDegrees: Double = 125.3263,
    val elevationMeters: Double = 0.0,
    val utcOffsetMinutes: Int = 480,
    val sunWeight: Float = 0.30f,
    val moonWeight: Float = 0.55f,
    val moonAltitudeWeight: Float = 0.15f,
    val astroDarkSunAltitudeDeg: Double = -18.0,
    val goldenHourUpperAngleDeg: Double = 6.0,
    val blueHourLowerAngleDeg: Double = -6.0,
    val goodScoreThreshold: Float = 0.70f,
    val fairScoreThreshold: Float = 0.45f,
    val maxMoonFractionForAstro: Float = 0.35f,
    val customTimeAngles: List<SunTimeAngle> = emptyList(),
) {

    /**
     * 越界修正 + 修正项说明（PATTERN_SPEC §5：clamp 后记录被修正的项）。
     * @return 修正后的配置与人类可读的修正说明列表。
     */
    fun clampWithReport(): Pair<AstroPlanConfig, List<String>> {
        val reports = ArrayList<String>(4)
        fun <T> fix(name: String, raw: T, fixed: T): T {
            if (raw != fixed) reports.add("$name: $raw -> $fixed")
            return fixed
        }

        val lat = fix("latitude", latitudeDegrees, finite(latitudeDegrees, 0.0).coerceIn(-90.0, 90.0))
        val lng = fix("longitude", longitudeDegrees, finite(longitudeDegrees, 0.0).coerceIn(-180.0, 180.0))
        val elev = fix("elevation", elevationMeters, finite(elevationMeters, 0.0).coerceIn(0.0, 9000.0))
        val offset = fix("utcOffsetMinutes", utcOffsetMinutes, utcOffsetMinutes.coerceIn(-720, 840))
        val sunW = fix("sunWeight", sunWeight, weight(sunWeight, 0.30f))
        val moonW = fix("moonWeight", moonWeight, weight(moonWeight, 0.55f))
        val moonAltW = fix("moonAltitudeWeight", moonAltitudeWeight, weight(moonAltitudeWeight, 0.15f))
        val dark = fix(
            "astroDarkSunAltitude",
            astroDarkSunAltitudeDeg,
            finite(astroDarkSunAltitudeDeg, -18.0).coerceIn(-30.0, -6.0),
        )
        val golden = fix(
            "goldenHourUpperAngle",
            goldenHourUpperAngleDeg,
            finite(goldenHourUpperAngleDeg, 6.0).coerceIn(0.0, 12.0),
        )
        val blue = fix(
            "blueHourLowerAngle",
            blueHourLowerAngleDeg,
            finite(blueHourLowerAngleDeg, -6.0).coerceIn(-12.0, -3.0),
        )
        val good = fix("goodScoreThreshold", goodScoreThreshold, weight(goodScoreThreshold, 0.70f))
        val fair = fix("fairScoreThreshold", fairScoreThreshold, weight(fairScoreThreshold, 0.45f))
        // good 必须严格大于 fair，否则两级重叠、标签无意义。
        val orderedGood = if (good <= fair) {
            reports.add("goodScoreThreshold/fairScoreThreshold: $good/$fair -> ${(fair + 0.05f).coerceAtMost(1f)}/$fair")
            (fair + 0.05f).coerceAtMost(1f)
        } else {
            good
        }
        val maxMoon = fix(
            "maxMoonFractionForAstro",
            maxMoonFractionForAstro,
            weight(maxMoonFractionForAstro, 0.35f),
        )

        return AstroPlanConfig(
            latitudeDegrees = lat,
            longitudeDegrees = lng,
            elevationMeters = elev,
            utcOffsetMinutes = offset,
            sunWeight = sunW,
            moonWeight = moonW,
            moonAltitudeWeight = moonAltW,
            astroDarkSunAltitudeDeg = dark,
            goldenHourUpperAngleDeg = golden,
            blueHourLowerAngleDeg = blue,
            goodScoreThreshold = orderedGood,
            fairScoreThreshold = fair,
            maxMoonFractionForAstro = maxMoon,
            customTimeAngles = customTimeAngles,
        ) to reports
    }

    /** clamp 便利版（不需要修正说明时用）。 */
    fun clamped(): AstroPlanConfig = clampWithReport().first

    /**
     * 从偏好读取配置（读取器由集成层注入，避免本模块依赖具体 DataStore 实现）。
     *
     * 读取路径（补丁 `delivery/patches/longexposure_patch.md` 的 CONFIG 段给出 DataStore key）：
     * `astro_plan_latitude` / `astro_plan_longitude` / `astro_plan_elevation` / `astro_plan_utc_offset` /
     * `astro_plan_moon_weight` / `astro_plan_sun_weight` / `astro_plan_good_threshold`。
     * 缺省或类型不符时回退默认值，**读取后一律 clamp**。
     */
    fun fromPreferences(read: (String) -> Any?): AstroPlanConfig {
        fun num(key: String, fallback: Double): Double =
            (read(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fallback
        val base = copy(
            latitudeDegrees = num(KEY_LATITUDE, latitudeDegrees),
            longitudeDegrees = num(KEY_LONGITUDE, longitudeDegrees),
            elevationMeters = num(KEY_ELEVATION, elevationMeters),
            utcOffsetMinutes = (read(KEY_UTC_OFFSET) as? Number)?.toInt() ?: utcOffsetMinutes,
            moonWeight = ((read(KEY_MOON_WEIGHT) as? Number)?.toFloat()) ?: moonWeight,
            sunWeight = ((read(KEY_SUN_WEIGHT) as? Number)?.toFloat()) ?: sunWeight,
            goodScoreThreshold = ((read(KEY_GOOD_THRESHOLD) as? Number)?.toFloat()) ?: goodScoreThreshold,
        )
        val (clamped, reports) = base.clampWithReport()
        if (reports.isNotEmpty()) PLog.w(TAG, "astro plan config clamped: ${reports.joinToString()}")
        return clamped
    }

    companion object {
        private const val TAG = "AstroPlanConfig"

        const val KEY_LATITUDE = "astro_plan_latitude"
        const val KEY_LONGITUDE = "astro_plan_longitude"
        const val KEY_ELEVATION = "astro_plan_elevation"
        const val KEY_UTC_OFFSET = "astro_plan_utc_offset"
        const val KEY_MOON_WEIGHT = "astro_plan_moon_weight"
        const val KEY_SUN_WEIGHT = "astro_plan_sun_weight"
        const val KEY_GOOD_THRESHOLD = "astro_plan_good_threshold"

        /** 默认配置（长春坐标；UI 可用定位覆盖）。 */
        val DEFAULT = AstroPlanConfig()

        private fun finite(value: Double, fallback: Double): Double =
            if (value.isFinite()) value else fallback

        private fun weight(value: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    }
}
