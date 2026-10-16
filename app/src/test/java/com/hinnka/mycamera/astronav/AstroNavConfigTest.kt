/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**开放可调参数的安全网**——默认配置零修正、越界值全量 clamp、
 *       非有限（NaN/Inf）输入回落默认值（不污染解算链路）、屏幕旋转吸附、
 *       模型年换算与有效期、参数范围常量自洽（min <= default <= max）。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroNavConfigTest {

    // ---------------- 默认值自洽 ----------------

    @Test
    fun default_config_is_within_every_documented_range() {
        val report = AstroNavConfig().clamped()
        assertTrue("默认配置不应被修正: ${report.corrected}", report.isClean)
        assertTrue(report.corrected.isEmpty())
        val d = report.config

        // 每一个开放参数都必须落在 [MIN, MAX] 内（min <= default <= max）
        assertTrue(d.locationUpdateIntervalMs in AstroNavConfig.LOCATION_INTERVAL_MIN..AstroNavConfig.LOCATION_INTERVAL_MAX)
        assertTrue(d.locationMinDistanceM in AstroNavConfig.LOCATION_MIN_DISTANCE_MIN..AstroNavConfig.LOCATION_MIN_DISTANCE_MAX)
        assertTrue(d.locationTimeoutMs in AstroNavConfig.LOCATION_TIMEOUT_MIN..AstroNavConfig.LOCATION_TIMEOUT_MAX)
        assertTrue(d.altitudeMeters in AstroNavConfig.ALTITUDE_MIN..AstroNavConfig.ALTITUDE_MAX)
        assertTrue(d.modelDecimalYear in AstroNavConfig.MODEL_YEAR_MIN..AstroNavConfig.MODEL_YEAR_MAX)
        assertTrue(d.sensorSamplingPeriodUs in AstroNavConfig.SAMPLING_PERIOD_MIN..AstroNavConfig.SAMPLING_PERIOD_MAX)
        assertTrue(d.attitudeUpdateIntervalMs in AstroNavConfig.ATTITUDE_INTERVAL_MIN..AstroNavConfig.ATTITUDE_INTERVAL_MAX)
        assertTrue(d.fusionGain in AstroNavConfig.FUSION_GAIN_MIN..AstroNavConfig.FUSION_GAIN_MAX)
        assertTrue(d.startupGain in AstroNavConfig.STARTUP_GAIN_MIN..AstroNavConfig.STARTUP_GAIN_MAX)
        assertTrue(d.startupPeriodS in AstroNavConfig.STARTUP_PERIOD_MIN..AstroNavConfig.STARTUP_PERIOD_MAX)
        assertTrue(d.accelerationRejectionDeg in AstroNavConfig.REJECTION_DEG_MIN..AstroNavConfig.REJECTION_DEG_MAX)
        assertTrue(d.magneticRejectionDeg in AstroNavConfig.REJECTION_DEG_MIN..AstroNavConfig.REJECTION_DEG_MAX)
        assertTrue(d.rejectionTimeoutS in AstroNavConfig.REJECTION_TIMEOUT_MIN..AstroNavConfig.REJECTION_TIMEOUT_MAX)
        assertTrue(d.gyroscopeRangeDps in AstroNavConfig.GYRO_RANGE_MIN..AstroNavConfig.GYRO_RANGE_MAX)
        assertTrue(d.complementaryAlpha in AstroNavConfig.COMPLEMENTARY_ALPHA_MIN..AstroNavConfig.COMPLEMENTARY_ALPHA_MAX)
        assertTrue(d.exponentialAlpha in AstroNavConfig.EXPONENTIAL_ALPHA_MIN..AstroNavConfig.EXPONENTIAL_ALPHA_MAX)
        assertTrue(d.oneEuroMinCutoff in AstroNavConfig.ONE_EURO_MIN_CUTOFF_MIN..AstroNavConfig.ONE_EURO_MIN_CUTOFF_MAX)
        assertTrue(d.oneEuroBeta in AstroNavConfig.ONE_EURO_BETA_MIN..AstroNavConfig.ONE_EURO_BETA_MAX)
        assertTrue(d.kalmanProcessNoise in AstroNavConfig.KALMAN_PROCESS_NOISE_MIN..AstroNavConfig.KALMAN_PROCESS_NOISE_MAX)
        assertTrue(
            d.kalmanMeasurementNoise in
                AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MIN..AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MAX,
        )
        assertTrue(d.smoothingWindowSize in AstroNavConfig.SMOOTHING_WINDOW_MIN..AstroNavConfig.SMOOTHING_WINDOW_MAX)
        assertTrue(d.declinationManualDeg in AstroNavConfig.DECLINATION_MANUAL_MIN..AstroNavConfig.DECLINATION_MANUAL_MAX)
        assertTrue(d.declinationCacheTtlMs in AstroNavConfig.DECLINATION_CACHE_TTL_MIN..AstroNavConfig.DECLINATION_CACHE_TTL_MAX)
        assertTrue(d.manualOffsetDegrees in AstroNavConfig.MANUAL_OFFSET_MIN..AstroNavConfig.MANUAL_OFFSET_MAX)
        assertTrue(d.calibrateSampleCount in AstroNavConfig.CALIBRATE_SAMPLE_MIN..AstroNavConfig.CALIBRATE_SAMPLE_MAX)
        assertTrue(d.heavyPathThrottleMs in AstroNavConfig.HEAVY_THROTTLE_MIN..AstroNavConfig.HEAVY_THROTTLE_MAX)
        assertTrue(d.magCalibrationQualityMin in AstroNavConfig.MAG_QUALITY_MIN..AstroNavConfig.MAG_QUALITY_MAX)
        assertTrue(d.recordMaxSamples in AstroNavConfig.RECORD_SAMPLES_MIN..AstroNavConfig.RECORD_SAMPLES_MAX)
        // 展示旋转只允许 4 档
        assertTrue(d.displayRotationDegrees in AstroNavConfig.DISPLAY_ROTATIONS.toList())

        // 关键默认值（与文档/UI 文案一致）
        assertEquals(1_000L, d.locationUpdateIntervalMs)
        assertEquals(15_000L, d.locationTimeoutMs)
        assertTrue(d.preferGpsOverNetwork)
        assertTrue(d.allowManualFallback)
        assertEquals(FusionAlgorithm.FUSION_AHRS, d.fusionAlgorithm)
        assertEquals(SmoothingMode.ONE_EURO, d.smoothingMode)
        assertEquals(DeclinationSource.EMBEDDED_WMM, d.declinationSource)
        assertEquals(CameraAxis.BACK, d.cameraAxis)
        assertTrue(d.trueNorthCorrection)
        assertTrue(d.requireCalibration)
        assertEquals(300, d.calibrateSampleCount)
        assertEquals(1_800, d.recordMaxSamples)
    }

    // ---------------- 越界值全量 clamp ----------------

    @Test
    fun clamp_corrects_every_out_of_range_parameter() {
        val raw = AstroNavConfig(
            locationUpdateIntervalMs = 1L,
            locationMinDistanceM = -5f,
            locationTimeoutMs = 10L,
            altitudeMeters = 1e9,
            modelDecimalYear = 1900.0,
            sensorSamplingPeriodUs = 1,
            attitudeUpdateIntervalMs = 1L,
            fusionGain = 99f,
            startupGain = 99f,
            startupPeriodS = -1f,
            accelerationRejectionDeg = 200f,
            magneticRejectionDeg = -10f,
            rejectionTimeoutS = 99f,
            gyroscopeRangeDps = 99_999f,
            complementaryAlpha = 2f,
            exponentialAlpha = -1f,
            oneEuroMinCutoff = 0f,
            oneEuroBeta = 99f,
            kalmanProcessNoise = 0f,
            kalmanMeasurementNoise = 99f,
            smoothingWindowSize = 0,
            declinationManualDeg = 999f,
            declinationCacheTtlMs = -1L,
            displayRotationDegrees = 77,
            manualOffsetDegrees = 999f,
            calibrateSampleCount = 0,
            heavyPathThrottleMs = 99_999L,
            magCalibrationQualityMin = 9,
            recordMaxSamples = 1,
        )
        val report = raw.clamped()
        val c = report.config

        assertEquals(AstroNavConfig.LOCATION_INTERVAL_MIN, c.locationUpdateIntervalMs)
        assertEquals(AstroNavConfig.LOCATION_MIN_DISTANCE_MIN, c.locationMinDistanceM, 0f)
        assertEquals(AstroNavConfig.LOCATION_TIMEOUT_MIN, c.locationTimeoutMs)
        assertEquals(AstroNavConfig.ALTITUDE_MAX, c.altitudeMeters, 0.0)
        assertEquals(AstroNavConfig.MODEL_YEAR_MIN, c.modelDecimalYear, 0.0)
        assertEquals(AstroNavConfig.SAMPLING_PERIOD_MIN, c.sensorSamplingPeriodUs)
        assertEquals(AstroNavConfig.ATTITUDE_INTERVAL_MIN, c.attitudeUpdateIntervalMs)
        assertEquals(AstroNavConfig.FUSION_GAIN_MAX, c.fusionGain, 0f)
        assertEquals(AstroNavConfig.STARTUP_GAIN_MAX, c.startupGain, 0f)
        assertEquals(AstroNavConfig.STARTUP_PERIOD_MIN, c.startupPeriodS, 0f)
        assertEquals(AstroNavConfig.REJECTION_DEG_MAX, c.accelerationRejectionDeg, 0f)
        assertEquals(AstroNavConfig.REJECTION_DEG_MIN, c.magneticRejectionDeg, 0f)
        assertEquals(AstroNavConfig.REJECTION_TIMEOUT_MAX, c.rejectionTimeoutS, 0f)
        assertEquals(AstroNavConfig.GYRO_RANGE_MAX, c.gyroscopeRangeDps, 0f)
        assertEquals(AstroNavConfig.COMPLEMENTARY_ALPHA_MAX, c.complementaryAlpha, 0f)
        assertEquals(AstroNavConfig.EXPONENTIAL_ALPHA_MIN, c.exponentialAlpha, 0f)
        assertEquals(AstroNavConfig.ONE_EURO_MIN_CUTOFF_MIN, c.oneEuroMinCutoff, 0f)
        assertEquals(AstroNavConfig.ONE_EURO_BETA_MAX, c.oneEuroBeta, 0f)
        assertEquals(AstroNavConfig.KALMAN_PROCESS_NOISE_MIN, c.kalmanProcessNoise, 0f)
        assertEquals(AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MAX, c.kalmanMeasurementNoise, 0f)
        assertEquals(AstroNavConfig.SMOOTHING_WINDOW_MIN, c.smoothingWindowSize)
        assertEquals(AstroNavConfig.DECLINATION_MANUAL_MAX, c.declinationManualDeg, 0f)
        assertEquals(AstroNavConfig.DECLINATION_CACHE_TTL_MIN, c.declinationCacheTtlMs)
        assertEquals(90, c.displayRotationDegrees) // 77 -> 吸附到最近档位
        assertEquals(AstroNavConfig.MANUAL_OFFSET_MAX, c.manualOffsetDegrees, 0f)
        assertEquals(AstroNavConfig.CALIBRATE_SAMPLE_MIN, c.calibrateSampleCount)
        assertEquals(AstroNavConfig.HEAVY_THROTTLE_MAX, c.heavyPathThrottleMs)
        assertEquals(AstroNavConfig.MAG_QUALITY_MAX, c.magCalibrationQualityMin)
        assertEquals(AstroNavConfig.RECORD_SAMPLES_MIN, c.recordMaxSamples)

        assertFalse(report.isClean)
        // 修正明细应逐项列出（不抛异常，仅报告）
        for (field in listOf(
            "locationUpdateIntervalMs", "locationMinDistanceM", "locationTimeoutMs", "altitudeMeters",
            "modelDecimalYear", "sensorSamplingPeriodUs", "attitudeUpdateIntervalMs", "fusionGain",
            "startupGain", "startupPeriodS", "accelerationRejectionDeg", "magneticRejectionDeg",
            "rejectionTimeoutS", "gyroscopeRangeDps", "complementaryAlpha", "exponentialAlpha",
            "oneEuroMinCutoff", "oneEuroBeta", "kalmanProcessNoise", "kalmanMeasurementNoise",
            "smoothingWindowSize", "declinationManualDeg", "declinationCacheTtlMs", "displayRotationDegrees",
            "manualOffsetDegrees", "calibrateSampleCount", "heavyPathThrottleMs", "magCalibrationQualityMin",
            "recordMaxSamples",
        )) {
            assertTrue("修正明细缺少 $field", report.corrected.contains(field))
        }
    }

    @Test
    fun non_finite_float_parameters_fall_back_to_defaults() {
        val raw = AstroNavConfig(
            locationMinDistanceM = Float.NaN,
            altitudeMeters = Double.NaN,
            modelDecimalYear = Double.POSITIVE_INFINITY,
            fusionGain = Float.NaN,
            startupGain = Float.NEGATIVE_INFINITY,
            startupPeriodS = Float.NaN,
            accelerationRejectionDeg = Float.NaN,
            magneticRejectionDeg = Float.POSITIVE_INFINITY,
            rejectionTimeoutS = Float.NaN,
            gyroscopeRangeDps = Float.NaN,
            complementaryAlpha = Float.NaN,
            exponentialAlpha = Float.NaN,
            oneEuroMinCutoff = Float.NaN,
            oneEuroBeta = Float.NaN,
            kalmanProcessNoise = Float.NaN,
            kalmanMeasurementNoise = Float.NaN,
            declinationManualDeg = Float.NaN,
            manualOffsetDegrees = Float.NaN,
        )
        val c = raw.clamped().config
        val defaults = AstroNavConfig()

        // 非有限输入 -> 回落默认值（coerceIn 对 NaN 无定义，必须显式兜底）
        assertEquals(defaults.locationMinDistanceM, c.locationMinDistanceM, 0f)
        assertEquals(defaults.altitudeMeters, c.altitudeMeters, 0.0)
        assertEquals(defaults.modelDecimalYear, c.modelDecimalYear, 0.0)
        assertEquals(defaults.fusionGain, c.fusionGain, 0f)
        assertEquals(defaults.startupGain, c.startupGain, 0f)
        assertEquals(defaults.startupPeriodS, c.startupPeriodS, 0f)
        assertEquals(defaults.accelerationRejectionDeg, c.accelerationRejectionDeg, 0f)
        assertEquals(defaults.magneticRejectionDeg, c.magneticRejectionDeg, 0f)
        assertEquals(defaults.rejectionTimeoutS, c.rejectionTimeoutS, 0f)
        assertEquals(defaults.gyroscopeRangeDps, c.gyroscopeRangeDps, 0f)
        assertEquals(defaults.complementaryAlpha, c.complementaryAlpha, 0f)
        assertEquals(defaults.exponentialAlpha, c.exponentialAlpha, 0f)
        assertEquals(defaults.oneEuroMinCutoff, c.oneEuroMinCutoff, 0f)
        assertEquals(defaults.oneEuroBeta, c.oneEuroBeta, 0f)
        assertEquals(defaults.kalmanProcessNoise, c.kalmanProcessNoise, 0f)
        assertEquals(defaults.kalmanMeasurementNoise, c.kalmanMeasurementNoise, 0f)
        assertEquals(defaults.declinationManualDeg, c.declinationManualDeg, 0f)
        assertEquals(defaults.manualOffsetDegrees, c.manualOffsetDegrees, 0f)

        // 二次 clamp 幂等：修正后的配置不再被修正
        assertTrue(AstroNavConfig.clamp(c).isClean)
    }

    @Test
    fun clamp_is_idempotent_and_never_throws() {
        val once = AstroNavConfig(fusionGain = -100f, recordMaxSamples = 999_999, displayRotationDegrees = 359).clamped()
        val twice = AstroNavConfig.clamp(once.config)
        assertTrue(twice.isClean)
        assertEquals(once.config, twice.config)
        // 极端 int/long 值不抛异常
        val extreme = AstroNavConfig(
            locationUpdateIntervalMs = Long.MIN_VALUE,
            locationTimeoutMs = Long.MAX_VALUE,
            sensorSamplingPeriodUs = Int.MAX_VALUE,
            smoothingWindowSize = Int.MIN_VALUE,
            calibrateSampleCount = Int.MAX_VALUE,
            recordMaxSamples = Int.MIN_VALUE,
            displayRotationDegrees = Int.MIN_VALUE,
        ).clamped()
        assertTrue(extreme.config.locationUpdateIntervalMs > 0L)
        assertTrue(extreme.config.smoothingWindowSize >= 1)
        assertTrue(extreme.config.displayRotationDegrees in AstroNavConfig.DISPLAY_ROTATIONS.toList())
        assertTrue(extreme.corrected.isNotEmpty())
    }

    // ---------------- 屏幕旋转 / 模型年 ----------------

    @Test
    fun display_rotation_snaps_to_nearest_legal_value() {
        assertEquals(0, AstroNavConfig.snapDisplayRotation(0))
        assertEquals(0, AstroNavConfig.snapDisplayRotation(20))
        assertEquals(90, AstroNavConfig.snapDisplayRotation(77))
        assertEquals(90, AstroNavConfig.snapDisplayRotation(90))
        assertEquals(180, AstroNavConfig.snapDisplayRotation(181))
        assertEquals(270, AstroNavConfig.snapDisplayRotation(269))
        // 远超范围也只会吸附到 4 档之一（1000 -> 270，最近档）
        assertEquals(270, AstroNavConfig.snapDisplayRotation(1000))
        for (raw in intArrayOf(-1000, -1, 0, 44, 45, 135, 226, 315, 316, 4000)) {
            assertTrue(
                "非法吸附: $raw -> ${AstroNavConfig.snapDisplayRotation(raw)}",
                AstroNavConfig.snapDisplayRotation(raw) in AstroNavConfig.DISPLAY_ROTATIONS.toList(),
            )
        }
    }

    @Test
    fun model_year_derivation_is_bounded_by_validity_window() {
        // 2025-01-01T00:00:00Z
        assertEquals(2025.0, AstroNavConfig.modelYearFor(1_735_689_600_000L), 1e-6)
        // 2026-06-01T00:00:00Z ≈ 2026.4127
        assertEquals(2026.4127, AstroNavConfig.modelYearFor(1_780_272_000_000L), 0.02)
        // 早于起点 / 远未来都被 clamp 到有效窗口
        assertEquals(2025.0, AstroNavConfig.modelYearFor(0L), 1e-6)
        assertEquals(AstroNavConfig.MODEL_YEAR_MAX, AstroNavConfig.modelYearFor(3_000_000_000_000L), 1e-6)
        assertTrue(Wmm2025Model.isWithinValidity(AstroNavConfig.modelYearFor(1_780_272_000_000L)))
    }

    @Test
    fun effective_model_year_switches_between_clock_and_config() {
        val auto = AstroNavConfig(autoModelYear = true)
        assertEquals(2026.4127, auto.effectiveModelYear(1_780_272_000_000L), 0.02)

        val manual = AstroNavConfig(autoModelYear = false, modelDecimalYear = 2028.25)
        assertEquals(2028.25, manual.effectiveModelYear(1_780_272_000_000L), 1e-9)

        // 配置值越界会被 clamp（即使 autoModelYear 关闭）
        val outOfRange = AstroNavConfig(autoModelYear = false, modelDecimalYear = 2050.0).clamped().config
        assertEquals(AstroNavConfig.MODEL_YEAR_MAX, outOfRange.effectiveModelYear(0L), 1e-9)
    }

    @Test
    fun enums_expose_all_supported_choices() {
        assertEquals(3, FusionAlgorithm.entries.size)
        assertTrue(FusionAlgorithm.entries.contains(FusionAlgorithm.FUSION_AHRS))
        assertTrue(FusionAlgorithm.entries.contains(FusionAlgorithm.COMPLEMENTARY))
        assertTrue(FusionAlgorithm.entries.contains(FusionAlgorithm.GYRO_ONLY))

        assertEquals(4, SmoothingMode.entries.size)
        assertTrue(SmoothingMode.entries.contains(SmoothingMode.NONE))
        assertTrue(SmoothingMode.entries.contains(SmoothingMode.EXPONENTIAL))
        assertTrue(SmoothingMode.entries.contains(SmoothingMode.ONE_EURO))
        assertTrue(SmoothingMode.entries.contains(SmoothingMode.KALMAN))

        assertEquals(3, DeclinationSource.entries.size)
        assertEquals(2, CameraAxis.entries.size)

        // 所有枚举值都能构成合法配置（clamp 后无修正）
        for (algo in FusionAlgorithm.entries) {
            for (mode in SmoothingMode.entries) {
                val c = AstroNavConfig(fusionAlgorithm = algo, smoothingMode = mode).clamped()
                assertTrue("${algo.name}/${mode.name} 不应触发修正: ${c.corrected}", c.isClean)
            }
        }
    }
}
