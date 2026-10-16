/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：模块的全部可调参数（默认值 + 取值范围 + clamp）。
 * 纯 Kotlin，无 Android 依赖 -> 可直接 JVM 单测（PATTERN_SPEC §7.4）。
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs

/** 姿态解算算法选择（可调；默认复用 xioTechnologies/Fusion 的 AHRS 实现）。 */
enum class FusionAlgorithm {
    /** Fusion AHRS（MIT，xioTechnologies/Fusion，1753★）：陀螺+加速度+磁力，含拒绝/恢复逻辑。 */
    FUSION_AHRS,

    /** 经典互补滤波（自研，Fusion 不可用/极低功耗档的兜底）。 */
    COMPLEMENTARY,

    /** 仅陀螺积分（无磁力计/无加速度计时的降级，会缓慢漂移；默认关闭）。 */
    GYRO_ONLY,
}

/** 指向抖动抑制策略（可调）。 */
enum class SmoothingMode {
    /** 不平滑（直通，用于对照/调试）。 */
    NONE,

    /** 指数平滑（固定系数，最省 CPU）。 */
    EXPONENTIAL,

    /** 1€ 滤波（Casiez 等 2012，自适应截止频率：静止时强平滑、转动时低延迟）。 */
    ONE_EURO,

    /** 一维卡尔曼（匀速模型，标量版；参数可调）。 */
    KALMAN,
}

/** 磁偏角来源（真北 vs 磁北校正）。 */
enum class DeclinationSource {
    /** 内嵌 NOAA WMM2025 球谐模型（离线，默认；无需网络）。 */
    EMBEDDED_WMM,

    /** 在线 API 更新（端点/密钥可配；失败自动回落 [EMBEDDED_WMM]）。 */
    ONLINE_API,

    /** 用户手动指定磁偏角（度，东正西负）。 */
    MANUAL,
}

/** 相机朝向（决定取景轴映射到设备坐标系的哪个方向）。 */
enum class CameraAxis {
    /** 后置主摄：取景方向 = 设备坐标系 -Z（Android 约定）。 */
    BACK,

    /** 前置摄像头：取景方向 = 设备坐标系 +Z。 */
    FRONT,
}

/**
 * 星空定位/融合模块的总配置。
 *
 * 每个字段都给出：默认值 + 取值范围（KDoc）+ clamp（[AstroNavConfig.clamp]）。
 * 误设不会崩溃：越界一律收敛到最近边界，并通过 [ClampReport.corrected] 记录被修正的字段名。
 *
 * @param locationUpdateIntervalMs 定位更新间隔（毫秒）。范围 [100, 60_000]，默认 1000。
 * @param locationMinDistanceM 定位最小位移触发（米）。范围 [0, 10_000]，默认 0（不限制）。
 * @param preferGpsOverNetwork 精度优先：GPS 提供者优先于网络提供者，默认 true。
 * @param locationTimeoutMs 首次定位等待超时（毫秒），超时后允许手动兜底。范围 [2_000, 120_000]，默认 15_000。
 * @param allowManualFallback 定位失败时允许「手动输入经纬度 / 搜索城市」兜底，默认 true。
 * @param altitudeMeters 观测点海拔（米，WGS-84 椭球高），用于 WMM 解算。范围 [-400, 9_000]，默认 0。
 * @param modelDecimalYear 磁模型小数年（WMM2025 有效期 2025.0–2030.0）。
 *        范围 [2025.0, 2030.0]，默认 2026.75（运行时可由 [AstroNavConfig.autoModelYear] 覆盖）。
 * @param autoModelYear true = 用当前系统时间推算小数年（clamp 到模型有效期），默认 true。
 * @param sensorSamplingPeriodUs 传感器注册周期（微秒，透传 SensorManager）。范围 [2_000, 200_000]，默认 10_000。
 * @param attitudeUpdateIntervalMs 姿态回调节流间隔（毫秒，前台轻量路径）。范围 [5, 200]，默认 20。
 * @param fusionAlgorithm 姿态解算算法，默认 [FusionAlgorithm.FUSION_AHRS]。
 * @param fusionGain AHRS 反馈增益。范围 [0.0, 5.0]，默认 0.5（0 = 纯陀螺积分）。
 * @param startupGain 启动期增益（快速收敛）。范围 [0.0, 20.0]，默认 10.0。
 * @param startupPeriodS 启动期时长（秒）。范围 [0.0, 10.0]，默认 3.0。
 * @param accelerationRejectionDeg 加速度拒绝阈值（度，0 = 关闭）。范围 [0.0, 90.0]，默认 10.0。
 * @param magneticRejectionDeg 磁力拒绝阈值（度，0 = 关闭）。范围 [0.0, 90.0]，默认 10.0。
 * @param rejectionTimeoutS 拒绝恢复超时（秒，0 = 关闭）。范围 [0.0, 30.0]，默认 5.0。
 * @param gyroscopeRangeDps 陀螺量程（度/秒，0 = 不做超量程检测）。范围 [0.0, 4_000.0]，默认 2_000.0。
 * @param complementaryAlpha 互补滤波系数（陀螺权重）。范围 [0.0, 1.0]，默认 0.98。
 * @param smoothingMode 抖动抑制策略，默认 [SmoothingMode.ONE_EURO]。
 * @param exponentialAlpha 指数平滑系数。范围 [0.01, 1.0]，默认 0.35（越小越平滑、越滞后）。
 * @param oneEuroMinCutoff 1€ 最小截止频率（Hz）。范围 [0.01, 10.0]，默认 1.2。
 * @param oneEuroBeta 1€ 速度系数。范围 [0.0, 5.0]，默认 0.35。
 * @param kalmanProcessNoise 卡尔曼过程噪声。范围 [1e-5, 1.0]，默认 0.008。
 * @param kalmanMeasurementNoise 卡尔曼观测噪声。范围 [1e-4, 10.0]，默认 0.12。
 * @param smoothingWindowSize 滑动平均窗口（用于 QUALITY 评估与可选平均）。范围 [1, 64]，默认 5。
 * @param declinationSource 磁偏角来源，默认 [DeclinationSource.EMBEDDED_WMM]。
 * @param declinationManualDeg 手动磁偏角（度）。范围 [-180, 180]，默认 0。
 * @param declinationCacheTtlMs 在线磁偏角缓存有效期（毫秒）。范围 [0, 604_800_000]，默认 86_400_000（24h）。
 * @param displayRotationDegrees 屏幕显示旋转（0/90/180/270，非 4 的倍数会被取模；clamp 到最近档）。默认 0。
 * @param cameraAxis 取景相机，默认 [CameraAxis.BACK]。
 * @param trueNorthCorrection 是否套用磁偏角做真北校正，默认 true。
 * @param manualOffsetDegrees 手动方向微调（度），供三脚架对齐补偿。范围 [-180, 180]，默认 0。
 * @param requireCalibration 是否要求「画 8 字」校准后才给高可信姿态，默认 true。
 * @param calibrateSampleCount 校准所需采样点数量。范围 [20, 2_000]，默认 300。
 * @param figureEightHintEnabled 是否提示画 8 字，默认 true。
 * @param heavyPathInBackground 姿态重路径是否走 BgJobRunner 低优先，默认 true。
 * @param heavyPathThrottleMs 重路径节流间隔（毫秒）。范围 [0, 1_000]，默认 33。
 * @param magCalibrationQualityMin 磁力计校准等级下限（SensorManager 的 0..3 映射）。范围 [0, 3]，默认 2。
 * @param recordMaxSamples 姿态录制缓存上限（条）。范围 [2, 60_000]，默认 1800（约 30s@60Hz/60s@30Hz）。
 *        这是「录制缓存」上限，离线精修任务本身不设样本数硬上限。
 */
data class AstroNavConfig(
    val locationUpdateIntervalMs: Long = 1_000L,
    val locationMinDistanceM: Float = 0f,
    val preferGpsOverNetwork: Boolean = true,
    val locationTimeoutMs: Long = 15_000L,
    val allowManualFallback: Boolean = true,
    val altitudeMeters: Double = 0.0,
    val modelDecimalYear: Double = 2026.75,
    val autoModelYear: Boolean = true,
    val sensorSamplingPeriodUs: Int = 10_000,
    val attitudeUpdateIntervalMs: Long = 20L,
    val fusionAlgorithm: FusionAlgorithm = FusionAlgorithm.FUSION_AHRS,
    val fusionGain: Float = 0.5f,
    val startupGain: Float = 10.0f,
    val startupPeriodS: Float = 3.0f,
    val accelerationRejectionDeg: Float = 10.0f,
    val magneticRejectionDeg: Float = 10.0f,
    val rejectionTimeoutS: Float = 5.0f,
    val gyroscopeRangeDps: Float = 2_000.0f,
    val complementaryAlpha: Float = 0.98f,
    val smoothingMode: SmoothingMode = SmoothingMode.ONE_EURO,
    val exponentialAlpha: Float = 0.35f,
    val oneEuroMinCutoff: Float = 1.2f,
    val oneEuroBeta: Float = 0.35f,
    val kalmanProcessNoise: Float = 0.008f,
    val kalmanMeasurementNoise: Float = 0.12f,
    val smoothingWindowSize: Int = 5,
    val declinationSource: DeclinationSource = DeclinationSource.EMBEDDED_WMM,
    val declinationManualDeg: Float = 0f,
    val declinationCacheTtlMs: Long = 86_400_000L,
    val displayRotationDegrees: Int = 0,
    val cameraAxis: CameraAxis = CameraAxis.BACK,
    val trueNorthCorrection: Boolean = true,
    val manualOffsetDegrees: Float = 0f,
    val requireCalibration: Boolean = true,
    val calibrateSampleCount: Int = 300,
    val figureEightHintEnabled: Boolean = true,
    val heavyPathInBackground: Boolean = true,
    val heavyPathThrottleMs: Long = 33L,
    val magCalibrationQualityMin: Int = 2,
    val recordMaxSamples: Int = 1800,
) {

    companion object {
        // ---- 取值范围（开放但安全；clamp 防误设崩溃）----
        const val LOCATION_INTERVAL_MIN = 100L
        const val LOCATION_INTERVAL_MAX = 60_000L
        const val LOCATION_MIN_DISTANCE_MIN = 0f
        const val LOCATION_MIN_DISTANCE_MAX = 10_000f
        const val LOCATION_TIMEOUT_MIN = 2_000L
        const val LOCATION_TIMEOUT_MAX = 120_000L
        const val ALTITUDE_MIN = -400.0
        const val ALTITUDE_MAX = 9_000.0
        const val MODEL_YEAR_MIN = 2025.0
        const val MODEL_YEAR_MAX = 2030.0
        const val SAMPLING_PERIOD_MIN = 2_000
        const val SAMPLING_PERIOD_MAX = 200_000
        const val ATTITUDE_INTERVAL_MIN = 5L
        const val ATTITUDE_INTERVAL_MAX = 200L
        const val FUSION_GAIN_MIN = 0.0f
        const val FUSION_GAIN_MAX = 5.0f
        const val STARTUP_GAIN_MIN = 0.0f
        const val STARTUP_GAIN_MAX = 20.0f
        const val STARTUP_PERIOD_MIN = 0.0f
        const val STARTUP_PERIOD_MAX = 10.0f
        const val REJECTION_DEG_MIN = 0.0f
        const val REJECTION_DEG_MAX = 90.0f
        const val REJECTION_TIMEOUT_MIN = 0.0f
        const val REJECTION_TIMEOUT_MAX = 30.0f
        const val GYRO_RANGE_MIN = 0.0f
        const val GYRO_RANGE_MAX = 4_000.0f
        const val COMPLEMENTARY_ALPHA_MIN = 0.0f
        const val COMPLEMENTARY_ALPHA_MAX = 1.0f
        const val EXPONENTIAL_ALPHA_MIN = 0.01f
        const val EXPONENTIAL_ALPHA_MAX = 1.0f
        const val ONE_EURO_MIN_CUTOFF_MIN = 0.01f
        const val ONE_EURO_MIN_CUTOFF_MAX = 10.0f
        const val ONE_EURO_BETA_MIN = 0.0f
        const val ONE_EURO_BETA_MAX = 5.0f
        const val KALMAN_PROCESS_NOISE_MIN = 1e-5f
        const val KALMAN_PROCESS_NOISE_MAX = 1.0f
        const val KALMAN_MEASUREMENT_NOISE_MIN = 1e-4f
        const val KALMAN_MEASUREMENT_NOISE_MAX = 10.0f
        const val SMOOTHING_WINDOW_MIN = 1
        const val SMOOTHING_WINDOW_MAX = 64
        const val DECLINATION_MANUAL_MIN = -180f
        const val DECLINATION_MANUAL_MAX = 180f
        const val DECLINATION_CACHE_TTL_MIN = 0L
        const val DECLINATION_CACHE_TTL_MAX = 604_800_000L
        const val MANUAL_OFFSET_MIN = -180f
        const val MANUAL_OFFSET_MAX = 180f
        const val CALIBRATE_SAMPLE_MIN = 20
        const val CALIBRATE_SAMPLE_MAX = 2_000
        const val HEAVY_THROTTLE_MIN = 0L
        const val HEAVY_THROTTLE_MAX = 1_000L
        const val MAG_QUALITY_MIN = 0
        const val MAG_QUALITY_MAX = 3
        const val RECORD_SAMPLES_MIN = 2
        const val RECORD_SAMPLES_MAX = 60_000

        /** 默认配置实例（clamp 时用于取非有限输入的回落值）。 */
        private val DEFAULTS = AstroNavConfig()

        /** 屏幕旋转仅允许 4 档，任何输入都会被吸附到最近的合法档位。 */
        val DISPLAY_ROTATIONS = intArrayOf(0, 90, 180, 270)

        /**
         * clamp 结果：修正后的配置 + 被修正的字段名列表（供日志/自检，不抛异常）。
         */
        data class ClampReport(
            val config: AstroNavConfig,
            val corrected: List<String>,
        ) {
            val isClean: Boolean get() = corrected.isEmpty()
        }

        /** 把任意（可能越界的）配置收敛到安全区间；返回修正明细。 */
        fun clamp(input: AstroNavConfig): ClampReport {
            val fixed = ArrayList<String>(8)
            fun <T> fix(field: String, raw: T, safe: T): T {
                if (raw != safe) fixed += field
                return safe
            }

            // 非有限（NaN/Inf）浮点输入一律回落「默认值」：coerceIn 对 NaN 无定义，
            // 直接放行会让 NaN 污染姿态解算与滤波链路（要求：误设不崩溃）。
            fun fin(v: Float, min: Float, max: Float, fallback: Float): Float =
                if (v.isFinite()) v.coerceIn(min, max) else fallback

            fun fin(v: Double, min: Double, max: Double, fallback: Double): Double =
                if (v.isFinite()) v.coerceIn(min, max) else fallback

            val out = input.copy(
                locationUpdateIntervalMs = fix(
                    "locationUpdateIntervalMs", input.locationUpdateIntervalMs,
                    input.locationUpdateIntervalMs.coerceIn(LOCATION_INTERVAL_MIN, LOCATION_INTERVAL_MAX),
                ),
                locationMinDistanceM = fix(
                    "locationMinDistanceM", input.locationMinDistanceM,
                    fin(
                        input.locationMinDistanceM,
                        LOCATION_MIN_DISTANCE_MIN, LOCATION_MIN_DISTANCE_MAX,
                        DEFAULTS.locationMinDistanceM,
                    ),
                ),
                locationTimeoutMs = fix(
                    "locationTimeoutMs", input.locationTimeoutMs,
                    input.locationTimeoutMs.coerceIn(LOCATION_TIMEOUT_MIN, LOCATION_TIMEOUT_MAX),
                ),
                altitudeMeters = fix(
                    "altitudeMeters", input.altitudeMeters,
                    fin(input.altitudeMeters, ALTITUDE_MIN, ALTITUDE_MAX, DEFAULTS.altitudeMeters),
                ),
                modelDecimalYear = fix(
                    "modelDecimalYear", input.modelDecimalYear,
                    fin(input.modelDecimalYear, MODEL_YEAR_MIN, MODEL_YEAR_MAX, DEFAULTS.modelDecimalYear),
                ),
                sensorSamplingPeriodUs = fix(
                    "sensorSamplingPeriodUs", input.sensorSamplingPeriodUs,
                    input.sensorSamplingPeriodUs.coerceIn(SAMPLING_PERIOD_MIN, SAMPLING_PERIOD_MAX),
                ),
                attitudeUpdateIntervalMs = fix(
                    "attitudeUpdateIntervalMs", input.attitudeUpdateIntervalMs,
                    input.attitudeUpdateIntervalMs.coerceIn(ATTITUDE_INTERVAL_MIN, ATTITUDE_INTERVAL_MAX),
                ),
                fusionGain = fix(
                    "fusionGain", input.fusionGain,
                    fin(input.fusionGain, FUSION_GAIN_MIN, FUSION_GAIN_MAX, DEFAULTS.fusionGain),
                ),
                startupGain = fix(
                    "startupGain", input.startupGain,
                    fin(input.startupGain, STARTUP_GAIN_MIN, STARTUP_GAIN_MAX, DEFAULTS.startupGain),
                ),
                startupPeriodS = fix(
                    "startupPeriodS", input.startupPeriodS,
                    fin(input.startupPeriodS, STARTUP_PERIOD_MIN, STARTUP_PERIOD_MAX, DEFAULTS.startupPeriodS),
                ),
                accelerationRejectionDeg = fix(
                    "accelerationRejectionDeg", input.accelerationRejectionDeg,
                    fin(
                        input.accelerationRejectionDeg,
                        REJECTION_DEG_MIN, REJECTION_DEG_MAX,
                        DEFAULTS.accelerationRejectionDeg,
                    ),
                ),
                magneticRejectionDeg = fix(
                    "magneticRejectionDeg", input.magneticRejectionDeg,
                    fin(
                        input.magneticRejectionDeg,
                        REJECTION_DEG_MIN, REJECTION_DEG_MAX,
                        DEFAULTS.magneticRejectionDeg,
                    ),
                ),
                rejectionTimeoutS = fix(
                    "rejectionTimeoutS", input.rejectionTimeoutS,
                    fin(
                        input.rejectionTimeoutS,
                        REJECTION_TIMEOUT_MIN, REJECTION_TIMEOUT_MAX,
                        DEFAULTS.rejectionTimeoutS,
                    ),
                ),
                gyroscopeRangeDps = fix(
                    "gyroscopeRangeDps", input.gyroscopeRangeDps,
                    fin(input.gyroscopeRangeDps, GYRO_RANGE_MIN, GYRO_RANGE_MAX, DEFAULTS.gyroscopeRangeDps),
                ),
                complementaryAlpha = fix(
                    "complementaryAlpha", input.complementaryAlpha,
                    fin(
                        input.complementaryAlpha,
                        COMPLEMENTARY_ALPHA_MIN, COMPLEMENTARY_ALPHA_MAX,
                        DEFAULTS.complementaryAlpha,
                    ),
                ),
                exponentialAlpha = fix(
                    "exponentialAlpha", input.exponentialAlpha,
                    fin(
                        input.exponentialAlpha,
                        EXPONENTIAL_ALPHA_MIN, EXPONENTIAL_ALPHA_MAX,
                        DEFAULTS.exponentialAlpha,
                    ),
                ),
                oneEuroMinCutoff = fix(
                    "oneEuroMinCutoff", input.oneEuroMinCutoff,
                    fin(
                        input.oneEuroMinCutoff,
                        ONE_EURO_MIN_CUTOFF_MIN, ONE_EURO_MIN_CUTOFF_MAX,
                        DEFAULTS.oneEuroMinCutoff,
                    ),
                ),
                oneEuroBeta = fix(
                    "oneEuroBeta", input.oneEuroBeta,
                    fin(input.oneEuroBeta, ONE_EURO_BETA_MIN, ONE_EURO_BETA_MAX, DEFAULTS.oneEuroBeta),
                ),
                kalmanProcessNoise = fix(
                    "kalmanProcessNoise", input.kalmanProcessNoise,
                    fin(
                        input.kalmanProcessNoise,
                        KALMAN_PROCESS_NOISE_MIN, KALMAN_PROCESS_NOISE_MAX,
                        DEFAULTS.kalmanProcessNoise,
                    ),
                ),
                kalmanMeasurementNoise = fix(
                    "kalmanMeasurementNoise", input.kalmanMeasurementNoise,
                    fin(
                        input.kalmanMeasurementNoise,
                        KALMAN_MEASUREMENT_NOISE_MIN, KALMAN_MEASUREMENT_NOISE_MAX,
                        DEFAULTS.kalmanMeasurementNoise,
                    ),
                ),
                smoothingWindowSize = fix(
                    "smoothingWindowSize", input.smoothingWindowSize,
                    input.smoothingWindowSize.coerceIn(SMOOTHING_WINDOW_MIN, SMOOTHING_WINDOW_MAX),
                ),
                declinationManualDeg = fix(
                    "declinationManualDeg", input.declinationManualDeg,
                    fin(
                        input.declinationManualDeg,
                        DECLINATION_MANUAL_MIN, DECLINATION_MANUAL_MAX,
                        DEFAULTS.declinationManualDeg,
                    ),
                ),
                declinationCacheTtlMs = fix(
                    "declinationCacheTtlMs", input.declinationCacheTtlMs,
                    input.declinationCacheTtlMs.coerceIn(DECLINATION_CACHE_TTL_MIN, DECLINATION_CACHE_TTL_MAX),
                ),
                displayRotationDegrees = fix(
                    "displayRotationDegrees", input.displayRotationDegrees,
                    snapDisplayRotation(input.displayRotationDegrees),
                ),
                manualOffsetDegrees = fix(
                    "manualOffsetDegrees", input.manualOffsetDegrees,
                    fin(
                        input.manualOffsetDegrees,
                        MANUAL_OFFSET_MIN, MANUAL_OFFSET_MAX,
                        DEFAULTS.manualOffsetDegrees,
                    ),
                ),
                calibrateSampleCount = fix(
                    "calibrateSampleCount", input.calibrateSampleCount,
                    input.calibrateSampleCount.coerceIn(CALIBRATE_SAMPLE_MIN, CALIBRATE_SAMPLE_MAX),
                ),
                heavyPathThrottleMs = fix(
                    "heavyPathThrottleMs", input.heavyPathThrottleMs,
                    input.heavyPathThrottleMs.coerceIn(HEAVY_THROTTLE_MIN, HEAVY_THROTTLE_MAX),
                ),
                magCalibrationQualityMin = fix(
                    "magCalibrationQualityMin", input.magCalibrationQualityMin,
                    input.magCalibrationQualityMin.coerceIn(MAG_QUALITY_MIN, MAG_QUALITY_MAX),
                ),
                recordMaxSamples = fix(
                    "recordMaxSamples", input.recordMaxSamples,
                    input.recordMaxSamples.coerceIn(RECORD_SAMPLES_MIN, RECORD_SAMPLES_MAX),
                ),
            )
            return ClampReport(out, fixed)
        }

        /** 屏幕旋转吸附到 4 档中最近的一个；非有限输入回落 0。 */
        fun snapDisplayRotation(raw: Int): Int {
            var best = 0
            var bestDiff = Int.MAX_VALUE
            for (cand in DISPLAY_ROTATIONS) {
                val d = abs(raw - cand)
                if (d < bestDiff) {
                    bestDiff = d
                    best = cand
                }
            }
            return best
        }

        /** 折算磁模型小数年：把 epoch 毫秒换算为小数年后 clamp 到 WMM2025 有效期。 */
        fun modelYearFor(epochMs: Long): Double {
            // 2025-01-01T00:00:00Z = 1_735_689_600_000 ms；一年按 365.25 天近似（WMM 只需 ~0.01 年精度）。
            val yearMs = 365.25 * 24.0 * 3600.0 * 1000.0
            val years = 2025.0 + (epochMs - 1_735_689_600_000L) / yearMs
            return years.coerceIn(MODEL_YEAR_MIN, MODEL_YEAR_MAX)
        }
    }

    /** 应用 clamp，得到安全配置（同时可读 [ClampReport.corrected] 记录被修正项）。 */
    fun clamped(): ClampReport = clamp(this)

    /** 生效的磁模型小数年：autoModelYear 时用系统时间推算，否则用配置值（均已 clamp）。 */
    fun effectiveModelYear(nowEpochMs: Long = System.currentTimeMillis()): Double =
        if (autoModelYear) modelYearFor(nowEpochMs) else modelDecimalYear.coerceIn(MODEL_YEAR_MIN, MODEL_YEAR_MAX)
}
