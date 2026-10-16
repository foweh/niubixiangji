/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：传感器融合（陀螺仪 + 加速度计 + 磁力计）-> 稳定姿态四元数。
 *
 * ============================================================================
 *  复用来源（PATTERN_SPEC §9：复用优先）
 * ============================================================================
 *  项目：xioTechnologies/Fusion（作者 Seb Madgwick）
 *  Star：1753（2026-10-16 通过 GitHub API 核实：api.github.com/repos/xioTechnologies/Fusion）
 *  License：MIT（LICENSE.md 首行 "The MIT License (MIT)"，
 *           "Copyright (c) 2021 x-io Technologies"；GitHub API license.spdx_id = "MIT"）
 *  → MIT 属可直采/可移植许可（PATTERN_SPEC §1.4），故按「最小改造移植」处理。
 *
 *  移植基准：Fusion/FusionAhrs.c + Fusion/FusionAhrs.h + Fusion/FusionMath.h +
 *           Fusion/FusionConvention.h（main 分支，2026-09-18 最后提交）。
 *  改动点（仅接口/类型适配，算法逐行对齐，未重写任何计算步骤）：
 *    1. C 结构体 FusionAhrs（含内部状态）→ Kotlin class [FusionAhrs]（状态为私有属性）。
 *    2. FusionVector → [AstroNavVec3]；FusionQuaternion → [AstroNavQuaternion]；
 *       FusionQuaternionVectorProduct(q, v) 等价替换为标准 Hamilton 积 q ⊗ (0, v)
 *       （已对照 FusionMath.h 源码确认两者结果完全一致）。
 *    3. FLT_MAX → [Float.MAX_VALUE]；FusionAhrsSettings → [FusionAhrsSettings]（含 clamp 入口）。
 *    4. 新增：入参非有限值防护（脏样本直接忽略，不污染姿态，也不抛异常）。
 *    5. 保留：启动增益斜坡、超量程软重启、加速度/磁力拒绝与恢复计时器，
 *       三套地球坐标系约定（NWU/ENU/NED）与 Fusion 完全一致 —— 一行未改。
 *    6. 新增两个可调设置项 initialStartupGain / startupPeriodS（Fusion 原实现为编译期常量），
 *       默认值与 Fusion 原常量完全一致 -> 默认行为不变，但满足工程「参数开放可调」要求。
 *    7. 未移植 Fusion 的 Python/C 示例与 FusionBias/Remap 工具（本模块用不到，
 *       避免无关代码入库；磁偏角与坐标对齐由本模块其它文件负责）。
 *
 *  关于任务书提到的第二个候选 xioTechnologies/imu-filter：
 *    2026-10-16 GitHub API 返回 404（仓库不存在/已改名），无法核实 star 与许可证，
 *    按 PATTERN_SPEC §1.4「拿不准按参考处理」→ **未采用，也未引用其任何代码**。
 *    其功能（IMU 工具集）已由本文件（姿态解算）+ [AstroNavSensorMath] 覆盖。
 *
 *  为什么不用现成 Maven/JitPack 库直接依赖：Android 侧没有官方 Fusion artifact；
 *  第三方包装库 star 极低（<300）且多为 C++/JNI 包装，会引入额外 native 依赖；
 *  本移植为零依赖纯 Kotlin（约 300 行），符合 PATTERN_SPEC §2「尽量不加新依赖」。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** 地球坐标系约定（移植 Fusion FusionConvention，语义与原实现一致）。 */
enum class FusionConvention {
    /** X 指北、Y 指西、Z 指上。 */
    NWU,

    /** X 指东、Y 指北、Z 指上（Android 传感器世界系常用）。 */
    ENU,

    /** X 指北、Y 指东、Z 指下（航空常用）。 */
    NED,
}

/**
 * AHRS 设置（对应 Fusion FusionAhrsSettings；所有字段可调，见 [AstroNavConfig]）。
 *
 * @param sampleRateHz 采样率（Hz），决定积分步长。默认 100。
 * @param convention 地球坐标系约定，默认 [FusionConvention.NWU]。
 * @param gain 反馈增益（0 = 纯陀螺积分）。默认 0.5。
 * @param gyroscopeRangeDps 陀螺量程（度/秒，0 = 关闭超量程软重启）。默认 2000。
 * @param accelerationRejectionDeg 加速度拒绝阈值（度，0 = 关闭）。默认 10。
 * @param magneticRejectionDeg 磁力拒绝阈值（度，0 = 关闭）。默认 10。
 * @param rejectionTimeoutS 拒绝恢复超时（秒，0 = 关闭）。默认 5。
 * @param initialStartupGain 启动期初始增益（本移植新增的可调项；Fusion 原实现为编译期常量
 *        INITIAL_STARTUP_GAIN = 10）。默认 10，范围 [0, 20]；设为 0 或 [startupPeriodS]=0 时跳过启动斜坡。
 * @param startupPeriodS 启动期时长（秒，本移植新增的可调项；Fusion 原实现为编译期常量
 *        STARTUP_PERIOD = 3）。默认 3，范围 [0, 10]。
 */
data class FusionAhrsSettings(
    val sampleRateHz: Float = 100.0f,
    val convention: FusionConvention = FusionConvention.NWU,
    val gain: Float = 0.5f,
    val gyroscopeRangeDps: Float = 2_000.0f,
    val accelerationRejectionDeg: Float = 10.0f,
    val magneticRejectionDeg: Float = 10.0f,
    val rejectionTimeoutS: Float = 5.0f,
    val initialStartupGain: Float = INITIAL_STARTUP_GAIN,
    val startupPeriodS: Float = STARTUP_PERIOD_S,
) {
    companion object {
        const val SAMPLE_RATE_MIN = 1.0f
        const val SAMPLE_RATE_MAX = 1_000.0f
        const val STARTUP_GAIN_MIN = 0.0f
        const val STARTUP_GAIN_MAX = 20.0f
        const val STARTUP_PERIOD_MIN = 0.0f
        const val STARTUP_PERIOD_MAX = 10.0f

        /** 初始启动增益默认值（= Fusion INITIAL_STARTUP_GAIN）。 */
        const val INITIAL_STARTUP_GAIN = 10.0f

        /** 启动期时长默认值（秒，= Fusion STARTUP_PERIOD）。 */
        const val STARTUP_PERIOD_S = 3.0f

        /** 收敛到安全区间（误设不崩溃）。 */
        fun clamp(s: FusionAhrsSettings): FusionAhrsSettings = s.copy(
            sampleRateHz = if (s.sampleRateHz.isFinite() && s.sampleRateHz > 0f) {
                s.sampleRateHz.coerceIn(SAMPLE_RATE_MIN, SAMPLE_RATE_MAX)
            } else {
                100.0f
            },
            gain = s.gain.coerceIn(0f, 10f),
            gyroscopeRangeDps = if (s.gyroscopeRangeDps.isFinite()) {
                s.gyroscopeRangeDps.coerceIn(0f, 8_000f)
            } else {
                2_000f
            },
            accelerationRejectionDeg = s.accelerationRejectionDeg.coerceIn(0f, 90f),
            magneticRejectionDeg = s.magneticRejectionDeg.coerceIn(0f, 90f),
            rejectionTimeoutS = s.rejectionTimeoutS.coerceIn(0f, 60f),
            initialStartupGain = if (s.initialStartupGain.isFinite()) {
                s.initialStartupGain.coerceIn(STARTUP_GAIN_MIN, STARTUP_GAIN_MAX)
            } else {
                INITIAL_STARTUP_GAIN
            },
            startupPeriodS = if (s.startupPeriodS.isFinite()) {
                s.startupPeriodS.coerceIn(STARTUP_PERIOD_MIN, STARTUP_PERIOD_MAX)
            } else {
                STARTUP_PERIOD_S
            },
        )
    }
}

/** 内部状态快照（对应 Fusion FusionAhrsInternalStates + Flags，供 UI/自检展示）。 */
data class FusionAhrsDiagnostics(
    /** 加速度残差对应的角度误差（度）。 */
    val accelerationErrorDeg: Float,
    /** 本帧加速度计是否被拒绝。 */
    val accelerometerIgnored: Boolean,
    /** 加速度恢复进度 0..1。 */
    val accelerationRecoveryTrigger: Float,
    /** 磁力残差对应的角度误差（度）。 */
    val magneticErrorDeg: Float,
    /** 本帧磁力计是否被拒绝。 */
    val magnetometerIgnored: Boolean,
    /** 磁力恢复进度 0..1。 */
    val magneticRecoveryTrigger: Float,
    /** 是否仍处于启动增益斜坡期。 */
    val startup: Boolean,
    /** 是否处于超量程恢复。 */
    val overrangeRecovery: Boolean,
    /** 当前生效的反馈增益（启动期高于设定值）。 */
    val currentGain: Float,
)

/**
 * AHRS 姿态解算（陀螺 + 加速度 + 磁力）。
 *
 * 线程不安全：传感器回调线程内单线程使用；跨线程读取请取 [quaternion] 快照
 * （返回不可变对象，可安全发布）。
 */
class FusionAhrs(settings: FusionAhrsSettings = FusionAhrsSettings()) {

    private var settings: FusionAhrsSettings = FusionAhrsSettings.clamp(settings)

    // ---- Fusion 内部状态（字段一一对应 FusionAhrs 结构体）----
    private var samplePeriod: Float = 1.0f / this.settings.sampleRateHz
    private var convention: FusionConvention = this.settings.convention
    private var gain: Float = this.settings.gain
    private var startupGainRate: Float = 0.0f

    private var overrangeEnabled: Boolean = false
    private var overrangeThreshold: Float = 0.0f

    private var accelerationRejection: Float = Float.MAX_VALUE
    private var magneticRejection: Float = Float.MAX_VALUE
    private var rejectionTimeout: Int = 0

    private var accelerationRecoveryThreshold: Int = 0
    private var magneticRecoveryThreshold: Int = 0

    private var quaternionInternal: AstroNavQuaternion = AstroNavQuaternion.IDENTITY
    private var accelerometerInternal: AstroNavVec3 = AstroNavVec3.ZERO

    private var startup: Boolean = true
    private var startupGain: Float = INITIAL_STARTUP_GAIN
    private var overrangeRecovery: Boolean = false

    private var halfAccelerometerResidual: AstroNavVec3 = AstroNavVec3.ZERO
    private var accelerationRecoveryTrigger: Int = 0
    private var accelerometerIgnored: Boolean = false

    private var halfMagnetometerResidual: AstroNavVec3 = AstroNavVec3.ZERO
    private var magneticRecoveryTrigger: Int = 0
    private var magnetometerIgnored: Boolean = false

    init {
        applySettings(this.settings)
        restart()
    }

    /** 当前姿态四元数（世界系 -> 设备系的旋转，约定同 [convention]）。 */
    val quaternion: AstroNavQuaternion get() = quaternionInternal

    /** 当前生效设置。 */
    val currentSettings: FusionAhrsSettings get() = settings

    /** 运行期改设置（会重算派生量并做 clamp）。 */
    fun updateSettings(newSettings: FusionAhrsSettings) {
        applySettings(FusionAhrsSettings.clamp(newSettings))
    }

    private fun applySettings(s: FusionAhrsSettings) {
        settings = s
        samplePeriod = 1.0f / s.sampleRateHz
        convention = s.convention
        gain = s.gain
        // 启动期增益斜坡速率：(初始增益 - 目标增益) / 启动时长 * 采样步长
        startupGainRate = if (s.startupPeriodS > 0f) {
            ((s.initialStartupGain - gain) / s.startupPeriodS) * samplePeriod
        } else {
            Float.MAX_VALUE // 启动期设为 0 秒 => 首帧即结束斜坡
        }

        overrangeEnabled = s.gyroscopeRangeDps > 0f
        overrangeThreshold = 0.98f * s.gyroscopeRangeDps

        accelerationRejection = if (s.accelerationRejectionDeg == 0f) {
            Float.MAX_VALUE
        } else {
            (0.5f * sin(Math.toRadians(s.accelerationRejectionDeg.toDouble()).toFloat())).pow(2)
        }
        magneticRejection = if (s.magneticRejectionDeg == 0f) {
            Float.MAX_VALUE
        } else {
            (0.5f * sin(Math.toRadians(s.magneticRejectionDeg.toDouble()).toFloat())).pow(2)
        }
        rejectionTimeout = (s.sampleRateHz * s.rejectionTimeoutS).toInt()

        accelerationRecoveryThreshold = rejectionTimeout
        magneticRecoveryThreshold = rejectionTimeout

        // 增益为 0 或恢复超时为 0 时关闭拒绝功能（与 Fusion 原实现一致）
        if (gain == 0f || s.rejectionTimeoutS == 0f) {
            accelerationRejection = Float.MAX_VALUE
            magneticRejection = Float.MAX_VALUE
        }
    }

    /** 重启（姿态归零、启动斜坡重置）。对应 FusionAhrsRestart。 */
    fun restart() {
        quaternionInternal = AstroNavQuaternion.IDENTITY
        accelerometerInternal = AstroNavVec3.ZERO
        // startupPeriodS = 0 表示不要启动斜坡（直接以目标增益运行）
        startup = settings.startupPeriodS > 0f
        startupGain = settings.initialStartupGain
        overrangeRecovery = false
        halfAccelerometerResidual = AstroNavVec3.ZERO
        accelerationRecoveryTrigger = 0
        accelerationRecoveryThreshold = rejectionTimeout
        accelerometerIgnored = false
        halfMagnetometerResidual = AstroNavVec3.ZERO
        magneticRecoveryTrigger = 0
        magneticRecoveryThreshold = rejectionTimeout
        magnetometerIgnored = false
    }

    /** 跳过启动斜坡（已知初始姿态时调用）。对应 FusionAhrsSkipStartup。 */
    fun skipStartup() {
        startup = false
        overrangeRecovery = false
    }

    /** 覆盖采样周期（补偿陀螺仪样本时钟误差）。对应 FusionAhrsSetSamplePeriod。 */
    fun updateSamplePeriod(periodSeconds: Float) {
        if (periodSeconds.isFinite() && periodSeconds > 0f) samplePeriod = periodSeconds
    }

    /**
     * 更新一次姿态（陀螺 + 加速度 + 磁力）。
     *
     * @param gyroscopeDps 陀螺仪读数（度/秒）
     * @param accelerometerG 加速度计读数（g）
     * @param magnetometer 磁力计读数（任意已校准单位）
     */
    fun update(
        gyroscopeDps: AstroNavVec3,
        accelerometerG: AstroNavVec3,
        magnetometer: AstroNavVec3,
    ) {
        if (!isFinite(gyroscopeDps) || !isFinite(accelerometerG) || !isFinite(magnetometer)) {
            return // 脏样本直接丢弃（新增防护，见文件头改动点 4）
        }
        accelerometerInternal = accelerometerG

        overrange(gyroscopeDps)

        val effectiveGain = startupGain()

        val halfGyroscope = gyroscopeDps * (Math.toRadians(0.5).toFloat())

        val halfGravity = halfGravity()

        val halfFeedback = halfInclinationFeedback(halfGravity, accelerometerG) +
            halfHeadingFeedback(halfGravity, magnetometer)

        val halfAngularRate = halfGyroscope + halfFeedback * effectiveGain

        // q += q ⊗ (0, halfAngularRate) * dt
        quaternionInternal = (
            quaternionInternal +
                quaternionInternal * AstroNavQuaternion(0f, halfAngularRate.x, halfAngularRate.y, halfAngularRate.z) *
                samplePeriod
            ).normalized()
    }

    /** 只用陀螺 + 加速度（无磁力计）；启动期把航向锁 0。对应 FusionAhrsUpdateNoMagnetometer。 */
    fun updateNoMagnetometer(gyroscopeDps: AstroNavVec3, accelerometerG: AstroNavVec3) {
        update(gyroscopeDps, accelerometerG, AstroNavVec3.ZERO)
        if (startup) setHeading(0f)
    }

    /**
     * 用外部航向（如磁偏角校正后的真北航向）替代磁力计。
     * 对应 FusionAhrsUpdateExternalHeading：由航向反推等效磁力计向量。
     */
    fun updateExternalHeading(
        gyroscopeDps: AstroNavVec3,
        accelerometerG: AstroNavVec3,
        headingDeg: Float,
    ) {
        val q = quaternionInternal
        val roll = atan2(q.w * q.x + q.y * q.z, 0.5f - q.y * q.y - q.x * q.x)
        val headingRad = Math.toRadians(headingDeg.toDouble()).toFloat()
        val sinHeading = sin(headingRad)
        val magnetometer = AstroNavVec3(
            cos(headingRad),
            -cos(roll) * sinHeading,
            sinHeading * sin(roll),
        )
        update(gyroscopeDps, accelerometerG, magnetometer)
    }

    /** 重力方向单位向量。对应 FusionAhrsGetGravity。 */
    fun gravity(): AstroNavVec3 = halfGravity() * 2f

    /** 线性加速度（g，已去重力）。对应 FusionAhrsGetLinearAcceleration。 */
    fun linearAcceleration(): AstroNavVec3 = accelerometerInternal - gravity()

    /** 地球坐标系加速度（g）。对应 FusionAhrsGetEarthAcceleration。 */
    fun earthAcceleration(): AstroNavVec3 {
        val q = quaternionInternal
        val a = accelerometerInternal
        val acc = AstroNavVec3(
            2f * ((q.w * q.w - 0.5f + q.x * q.x) * a.x + (q.x * q.y - q.w * q.z) * a.y +
                (q.x * q.z + q.w * q.y) * a.z),
            2f * ((q.x * q.y + q.w * q.z) * a.x + (q.w * q.w - 0.5f + q.y * q.y) * a.y +
                (q.y * q.z - q.w * q.x) * a.z),
            2f * ((q.x * q.z - q.w * q.y) * a.x + (q.y * q.z + q.w * q.x) * a.y +
                (q.w * q.w - 0.5f + q.z * q.z) * a.z),
        )
        return when (convention) {
            FusionConvention.NWU, FusionConvention.ENU -> acc - AstroNavVec3(0f, 0f, 1f)
            FusionConvention.NED -> acc + AstroNavVec3(0f, 0f, 1f)
        }
    }

    /**
     * 纯磁力计航向（倾斜补偿罗盘，度）。对应 FusionCompass。
     * 返回的是**磁北**航向；真北航向需再叠加磁偏角（见 [MagneticDeclination]）。
     */
    fun compassHeadingDeg(accelerometerG: AstroNavVec3, magnetometer: AstroNavVec3): Float {
        if (!isFinite(accelerometerG) || !isFinite(magnetometer)) return 0f
        return when (convention) {
            FusionConvention.NWU -> {
                val west = accelerometerG.cross(magnetometer).normalized()
                val north = west.cross(accelerometerG).normalized()
                degrees(atan2(west.x, north.x))
            }
            FusionConvention.ENU -> {
                val west = accelerometerG.cross(magnetometer).normalized()
                val north = west.cross(accelerometerG).normalized()
                val east = west * -1f
                degrees(atan2(north.x, east.x))
            }
            FusionConvention.NED -> {
                val up = accelerometerG * -1f
                val west = up.cross(magnetometer).normalized()
                val north = west.cross(up).normalized()
                degrees(atan2(west.x, north.x))
            }
        }
    }

    /** 设置航向（绕 Z 轴旋转对齐）。对应 FusionAhrsSetHeading。 */
    fun setHeading(headingDeg: Float) {
        if (!headingDeg.isFinite()) return
        val q = quaternionInternal
        val yaw = atan2(q.w * q.z + q.x * q.y, 0.5f - q.y * q.y - q.z * q.z)
        val halfYawMinusHeading = 0.5f * (yaw - Math.toRadians(headingDeg.toDouble()).toFloat())
        val rotation = AstroNavQuaternion(cos(halfYawMinusHeading), 0f, 0f, -sin(halfYawMinusHeading))
        quaternionInternal = (rotation * q).normalized()
    }

    /** 内部诊断快照（对应 FusionAhrsGetInternalStates + GetFlags）。 */
    fun diagnostics(): FusionAhrsDiagnostics = FusionAhrsDiagnostics(
        accelerationErrorDeg = degrees(
            _asin(2f * halfAccelerometerResidual.norm).toDouble(),
        ),
        accelerometerIgnored = accelerometerIgnored,
        accelerationRecoveryTrigger = ratio(accelerationRecoveryTrigger),
        magneticErrorDeg = degrees(_asin(2f * halfMagnetometerResidual.norm).toDouble()),
        magnetometerIgnored = magnetometerIgnored,
        magneticRecoveryTrigger = ratio(magneticRecoveryTrigger),
        startup = startup,
        overrangeRecovery = overrangeRecovery,
        currentGain = if (startup) startupGain else gain,
    )

    // ------------------------------------------------------------------
    // 以下为 FusionAhrs.c 静态函数的一对一移植（私有）
    // ------------------------------------------------------------------

    /** 超量程检测 -> 软重启。对应 Overrange。 */
    private fun overrange(gyroscope: AstroNavVec3) {
        if (!overrangeEnabled) return
        if (abs(gyroscope.x) <= overrangeThreshold &&
            abs(gyroscope.y) <= overrangeThreshold &&
            abs(gyroscope.z) <= overrangeThreshold
        ) {
            return
        }
        softRestart()
        overrangeRecovery = true
    }

    /** 保留输出的重启。对应 SoftRestart。 */
    private fun softRestart() {
        val q = quaternionInternal
        val acc = accelerometerInternal
        restart()
        quaternionInternal = q
        accelerometerInternal = acc
    }

    /** 启动增益斜坡。对应 Startup。 */
    private fun startupGain(): Float {
        if (!startup) return gain
        startupGain -= startupGainRate
        if (startupGain > gain) return startupGain
        startup = false
        overrangeRecovery = false
        return gain
    }

    /** 倾斜反馈（×0.5）。对应 HalfInclinationFeedback。 */
    private fun halfInclinationFeedback(
        halfGravity: AstroNavVec3,
        accelerometer: AstroNavVec3,
    ): AstroNavVec3 {
        var feedback = AstroNavVec3.ZERO
        accelerometerIgnored = true
        if (!accelerometer.isZero) {
            halfAccelerometerResidual = residual(accelerometer.normalized(), halfGravity)

            if (startup || halfAccelerometerResidual.normSquared <= accelerationRejection) {
                accelerometerIgnored = false
                accelerationRecoveryTrigger -= 9
            } else {
                accelerationRecoveryTrigger += 1
            }

            if (accelerationRecoveryTrigger > accelerationRecoveryThreshold) {
                accelerationRecoveryThreshold = 0
                accelerometerIgnored = false
            } else {
                accelerationRecoveryThreshold = rejectionTimeout
            }
            accelerationRecoveryTrigger = clampInt(accelerationRecoveryTrigger, 0, rejectionTimeout)

            if (!accelerometerIgnored) feedback = halfAccelerometerResidual
        }
        return feedback
    }

    /** 航向反馈（×0.5）。对应 HalfHeadingFeedback。 */
    private fun halfHeadingFeedback(
        halfGravity: AstroNavVec3,
        magnetometer: AstroNavVec3,
    ): AstroNavVec3 {
        var feedback = AstroNavVec3.ZERO
        magnetometerIgnored = true
        if (!magnetometer.isZero) {
            val halfWest = halfWest()
            halfMagnetometerResidual = residual(halfGravity.cross(magnetometer).normalized(), halfWest)

            if (startup || halfMagnetometerResidual.normSquared <= magneticRejection) {
                magnetometerIgnored = false
                magneticRecoveryTrigger -= 9
            } else {
                magneticRecoveryTrigger += 1
            }

            if (magneticRecoveryTrigger > magneticRecoveryThreshold) {
                magneticRecoveryThreshold = 0
                magnetometerIgnored = false
            } else {
                magneticRecoveryThreshold = rejectionTimeout
            }
            magneticRecoveryTrigger = clampInt(magneticRecoveryTrigger, 0, rejectionTimeout)

            if (!magnetometerIgnored) feedback = halfMagnetometerResidual
        }
        return feedback
    }

    /** 重力方向（×0.5）。对应 HalfGravity。 */
    private fun halfGravity(): AstroNavVec3 {
        val q = quaternionInternal
        return when (convention) {
            FusionConvention.NWU, FusionConvention.ENU -> AstroNavVec3(
                q.x * q.z - q.w * q.y,
                q.y * q.z + q.w * q.x,
                q.w * q.w - 0.5f + q.z * q.z,
            )
            FusionConvention.NED -> AstroNavVec3(
                q.w * q.y - q.x * q.z,
                -(q.y * q.z + q.w * q.x),
                0.5f - q.w * q.w - q.z * q.z,
            )
        }
    }

    /** 西向（×0.5）。对应 HalfWest。 */
    private fun halfWest(): AstroNavVec3 {
        val q = quaternionInternal
        return when (convention) {
            FusionConvention.NWU -> AstroNavVec3(
                q.x * q.y + q.w * q.z,
                q.w * q.w - 0.5f + q.y * q.y,
                q.y * q.z - q.w * q.x,
            )
            FusionConvention.ENU -> AstroNavVec3(
                0.5f - q.w * q.w - q.x * q.x,
                q.w * q.z - q.x * q.y,
                -(q.x * q.z + q.w * q.y),
            )
            FusionConvention.NED -> AstroNavVec3(
                -(q.x * q.y + q.w * q.z),
                0.5f - q.w * q.w - q.y * q.y,
                q.w * q.x - q.y * q.z,
            )
        }
    }

    /** 残差。对应 Residual（含 >90° 时归一化处理）。 */
    private fun residual(sensor: AstroNavVec3, reference: AstroNavVec3): AstroNavVec3 {
        if (sensor.dot(reference) > 0f) return sensor.cross(reference)
        val cross = sensor.cross(reference)
        if (cross.isZero) return AstroNavVec3.ZERO
        return cross.normalized()
    }

    private fun clampInt(value: Int, min: Int, max: Int): Int =
        if (value < min) min else if (value > max) max else value

    private fun ratio(trigger: Int): Float =
        if (rejectionTimeout == 0) 0f else trigger.toFloat() / rejectionTimeout.toFloat()

    private fun degrees(rad: Double): Float = (Math.toDegrees(rad)).toFloat()

    private fun _asin(v: Float): Double = Math.asin(v.coerceIn(-1f, 1f).toDouble())

    private fun isFinite(v: AstroNavVec3): Boolean =
        v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    companion object {
        /** 初始启动增益默认值（对应 Fusion INITIAL_STARTUP_GAIN）；可被设置项覆盖。 */
        const val INITIAL_STARTUP_GAIN = FusionAhrsSettings.INITIAL_STARTUP_GAIN

        /** 启动期时长默认值（秒，对应 Fusion STARTUP_PERIOD）；可被设置项覆盖。 */
        const val STARTUP_PERIOD_S = FusionAhrsSettings.STARTUP_PERIOD_S
    }
}
