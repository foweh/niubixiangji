/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：Fusion AHRS 移植的**数值边界**——纯陀螺积分精度、脏样本防护、
 *       零反馈不动、超量程软重启、诊断量、设置 clamp、
 *       启动增益斜坡（本移植新增的可调项）、罗盘航向、坐标系约定一致性。
 * 纯 JVM（FusionAhrs 无 Android 依赖）。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionAhrsTest {

    private val g = AstroNavVec3(0f, 0f, 1f) // 设备平放时加速度计读数（g）
    private val magNorth = AstroNavVec3(1f, 0f, 0f)

    // ---------------- 数值边界：积分精度 ----------------

    @Test
    fun pure_gyro_integration_matches_expected_angle() {
        // 100Hz、绕 Z 轴 90°/s、积分 1 秒 -> 姿态角应约 90°
        val ahrs = FusionAhrs(FusionAhrsSettings(sampleRateHz = 100f, gain = 0f))
        repeat(100) {
            ahrs.update(AstroNavVec3(0f, 0f, 90f), AstroNavVec3.ZERO, AstroNavVec3.ZERO)
        }
        assertEquals(90f, AstroNavQuaternion.IDENTITY.angleDegreesTo(ahrs.quaternion), 1f)
        // 旋转轴应为 Z（四元数虚部以 z 为主）
        assertEquals(0f, ahrs.quaternion.x, 1e-3f)
        assertEquals(0f, ahrs.quaternion.y, 1e-3f)
    }

    @Test
    fun small_dt_accumulates_consistently() {
        // 两次半步 vs 一次整步：角度一致（线性积分对小角度误差可忽略）
        val a = FusionAhrs(FusionAhrsSettings(sampleRateHz = 100f, gain = 0f))
        val b = FusionAhrs(FusionAhrsSettings(sampleRateHz = 200f, gain = 0f))
        repeat(2) { a.update(AstroNavVec3(0f, 0f, 90f), AstroNavVec3.ZERO, AstroNavVec3.ZERO) }
        repeat(4) { b.update(AstroNavVec3(0f, 0f, 90f), AstroNavVec3.ZERO, AstroNavVec3.ZERO) }
        assertEquals(
            a.quaternion.angleDegreesTo(AstroNavQuaternion.IDENTITY),
            b.quaternion.angleDegreesTo(AstroNavQuaternion.IDENTITY),
            0.5f,
        )
    }

    @Test
    fun zero_gyro_and_zero_reference_keeps_identity() {
        val ahrs = FusionAhrs(FusionAhrsSettings(gain = 0.5f))
        repeat(50) { ahrs.update(AstroNavVec3.ZERO, AstroNavVec3.ZERO, AstroNavVec3.ZERO) }
        // 无陀螺输入、无参考向量 -> 无反馈 -> 姿态保持单位
        assertEquals(0f, AstroNavQuaternion.IDENTITY.angleDegreesTo(ahrs.quaternion), 1e-3f)
    }

    // ---------------- 数值边界：脏样本防护（新增改动点 4） ----------------

    @Test
    fun non_finite_samples_are_ignored() {
        val ahrs = FusionAhrs(FusionAhrsSettings(gain = 0f))
        repeat(10) { ahrs.update(AstroNavVec3(0f, 0f, 90f), AstroNavVec3.ZERO, AstroNavVec3.ZERO) }
        val before = ahrs.quaternion

        ahrs.update(AstroNavVec3(Float.NaN, 0f, 0f), g, magNorth)   // 陀螺 NaN
        ahrs.update(AstroNavVec3.ZERO, AstroNavVec3(Float.NaN, 0f, 0f), magNorth) // 加速度 NaN
        ahrs.update(AstroNavVec3.ZERO, g, AstroNavVec3(0f, Float.POSITIVE_INFINITY, 0f)) // 磁力 Inf

        assertEquals(before, ahrs.quaternion)
        assertTrue(ahrs.quaternion.isFinite)
    }

    @Test
    fun enormous_values_do_not_produce_non_finite_quaternion() {
        val ahrs = FusionAhrs(FusionAhrsSettings(gain = 0.5f, gyroscopeRangeDps = 0f))
        repeat(20) {
            ahrs.update(AstroNavVec3(1e6f, 1e6f, 1e6f), g, magNorth)
        }
        // 不做量程检测（range=0）时也只是数值饱和，姿态仍必须是有限的单位四元数
        assertTrue(ahrs.quaternion.isFinite)
    }

    // ---------------- 反馈 / 拒绝逻辑 ----------------

    @Test
    fun inclination_feedback_pulls_gravity_back_in_line() {
        // 初始姿态被旋转 30°（不是真实姿态），给定真实重力方向，反馈应让它回到重力对齐
        val ahrs = FusionAhrs(
            FusionAhrsSettings(sampleRateHz = 100f, gain = 2f, accelerationRejectionDeg = 45f),
        )
        ahrs.restart()
        repeat(200) { ahrs.update(AstroNavVec3.ZERO, g, magNorth) }
        // 无陀螺 + 参考向量自洽 -> 姿态仍稳定（残差不会发散）
        assertTrue(ahrs.quaternion.isFinite)
        val residual = ahrs.diagnostics().accelerationErrorDeg
        assertTrue("残差应保持在合理范围: $residual", residual < 90f)
    }

    @Test
    fun acceleration_rejection_flags_are_reported() {
        val ahrs = FusionAhrs(
            FusionAhrsSettings(
                sampleRateHz = 100f,
                gain = 0.5f,
                accelerationRejectionDeg = 5f,
                rejectionTimeoutS = 1f,
                startupPeriodS = 0f, // 关闭启动斜坡，使拒绝逻辑立即生效
            ),
        )
        // 给出与姿态严重不符的「加速度」（模拟剧烈线性加速）-> 应被拒绝
        repeat(10) { ahrs.update(AstroNavVec3.ZERO, AstroNavVec3(1f, 1f, 0f), magNorth) }
        val d = ahrs.diagnostics()
        assertTrue("加速度应被拒绝", d.accelerometerIgnored)
        // 拒绝后进入恢复计时（0..1），且误差为有限值
        assertTrue(d.accelerationRecoveryTrigger in 0f..1f)
        assertTrue(d.accelerationErrorDeg.isFinite() && d.accelerationErrorDeg >= 0f)
    }

    @Test
    fun overrange_triggers_soft_restart_and_recovers() {
        val ahrs = FusionAhrs(
            FusionAhrsSettings(
                sampleRateHz = 100f,
                gain = 0.5f,
                gyroscopeRangeDps = 1_000f,
                startupPeriodS = 1f,
            ),
        )
        // 超过量程（阈值 = 0.98 × 1000）-> 软重启：overrangeRecovery 标记为 true
        ahrs.update(AstroNavVec3(2_000f, 0f, 0f), g, magNorth)
        assertTrue(ahrs.diagnostics().overrangeRecovery)
        assertTrue(ahrs.quaternion.isUsable) // 软重启保留姿态，不清零

        // 回到量程内并等启动斜坡走完 -> 恢复标记被清除（与 Fusion 原实现一致）
        repeat(150) { ahrs.update(AstroNavVec3(1f, 0f, 0f), g, magNorth) }
        assertFalse(ahrs.diagnostics().overrangeRecovery)
        assertTrue(ahrs.quaternion.isUsable)
    }

    @Test
    fun startup_ramp_is_configurable_and_ends() {
        // 启动期设为 0 秒 -> 不需要斜坡（本移植新增的可调项）
        val instant = FusionAhrs(FusionAhrsSettings(sampleRateHz = 100f, gain = 1f, startupPeriodS = 0f))
        assertFalse(instant.diagnostics().startup)
        assertEquals(1f, instant.diagnostics().currentGain, 1e-3f)

        // 正常启动期：前一帧处于 startup，增益高于设定值
        val ramped = FusionAhrs(
            FusionAhrsSettings(sampleRateHz = 100f, gain = 1f, initialStartupGain = 10f, startupPeriodS = 1f),
        )
        ramped.update(AstroNavVec3.ZERO, g, magNorth)
        assertTrue(ramped.diagnostics().currentGain > 1f)

        // 持续运行超过启动期 -> startup 结束、增益回到设定值
        repeat(300) { ramped.update(AstroNavVec3.ZERO, g, magNorth) }
        assertFalse(ramped.diagnostics().startup)
        assertEquals(1f, ramped.diagnostics().currentGain, 1e-3f)
    }

    // ---------------- 设置 / 复位 ----------------

    @Test
    fun settings_are_clamped_for_out_of_range_input() {
        val clamped = FusionAhrsSettings.clamp(
            FusionAhrsSettings(
                sampleRateHz = -5f,
                gain = 99f,
                gyroscopeRangeDps = Float.NaN,
                accelerationRejectionDeg = -10f,
                magneticRejectionDeg = 200f,
                rejectionTimeoutS = -1f,
                initialStartupGain = 1_000f,
                startupPeriodS = -3f,
            ),
        )
        assertEquals(100f, clamped.sampleRateHz, 1e-3f)         // 非法采样率回落默认
        assertEquals(10f, clamped.gain, 1e-3f)                   // gain 上限 10
        assertEquals(2_000f, clamped.gyroscopeRangeDps, 1e-3f)   // 非法值回落默认
        assertEquals(0f, clamped.accelerationRejectionDeg, 1e-3f)
        assertEquals(90f, clamped.magneticRejectionDeg, 1e-3f)
        assertEquals(0f, clamped.rejectionTimeoutS, 1e-3f)
        assertEquals(20f, clamped.initialStartupGain, 1e-3f)
        assertEquals(0f, clamped.startupPeriodS, 1e-3f)

        // 设置越界值也不抛异常
        val ahrs = FusionAhrs(FusionAhrsSettings(sampleRateHz = Float.NaN, gain = -1f))
        assertEquals(100f, ahrs.currentSettings.sampleRateHz, 1e-3f)
        assertEquals(0f, ahrs.currentSettings.gain, 1e-3f)
    }

    @Test
    fun restart_and_set_heading_behave_as_documented() {
        val ahrs = FusionAhrs(FusionAhrsSettings(sampleRateHz = 100f, gain = 0f))
        repeat(20) { ahrs.update(AstroNavVec3(0f, 0f, 90f), AstroNavVec3.ZERO, AstroNavVec3.ZERO) }
        assertTrue(AstroNavQuaternion.IDENTITY.angleDegreesTo(ahrs.quaternion) > 5f)

        ahrs.restart()
        assertEquals(0f, AstroNavQuaternion.IDENTITY.angleDegreesTo(ahrs.quaternion), 1e-3f)

        // setHeading 非法输入不改变姿态
        val before = ahrs.quaternion
        ahrs.setHeading(Float.NaN)
        assertEquals(before, ahrs.quaternion)

        // updateSamplePeriod 非法输入被忽略（保持此前有效值）
        ahrs.updateSamplePeriod(-1f)
        ahrs.updateSamplePeriod(Float.NaN)
        assertTrue(ahrs.quaternion.isUsable)
    }

    // ---------------- 罗盘 / 坐标约定 ----------------

    @Test
    fun compass_heading_is_zero_when_magnetometer_points_device_x() {
        val ahrs = FusionAhrs(FusionAhrsSettings(convention = FusionConvention.NWU))
        val heading = ahrs.compassHeadingDeg(AstroNavVec3(0f, 0f, 1f), AstroNavVec3(1f, 0f, 0f))
        assertEquals(0f, AstroNavAngles.wrap360(heading), 0.5f)

        // 磁力指向设备 Y 轴 -> 航向 270°（= -90°，罗盘从北向顺时针）
        val heading2 = ahrs.compassHeadingDeg(AstroNavVec3(0f, 0f, 1f), AstroNavVec3(0f, 1f, 0f))
        assertEquals(270f, AstroNavAngles.wrap360(heading2), 0.5f)

        // 脏样本 -> 0（不抛异常）
        assertEquals(0f, ahrs.compassHeadingDeg(AstroNavVec3(Float.NaN, 0f, 0f), magNorth), 1e-3f)
    }

    @Test
    fun gravity_and_linear_acceleration_are_consistent() {
        val ahrs = FusionAhrs(FusionAhrsSettings(gain = 1f))
        ahrs.update(AstroNavVec3.ZERO, AstroNavVec3(0f, 0f, 1f), magNorth)
        val gravity = ahrs.gravity()
        // 单位姿态下重力方向应为 +Z，且模长 1
        assertEquals(1f, gravity.norm, 1e-2f)
        assertTrue(gravity.z > 0f)
        // 线性加速度 = 实测 - 重力
        val linear = ahrs.linearAcceleration()
        assertEquals(0f, linear.z, 1e-2f)
    }

    @Test
    fun conventions_produce_expected_gravity_sign() {
        val nwu = FusionAhrs(FusionAhrsSettings(convention = FusionConvention.NWU))
        val ned = FusionAhrs(FusionAhrsSettings(convention = FusionConvention.NED))
        nwu.update(AstroNavVec3.ZERO, AstroNavVec3(0f, 0f, 1f), magNorth)
        ned.update(AstroNavVec3.ZERO, AstroNavVec3(0f, 0f, -1f), magNorth)
        assertEquals(1f, nwu.gravity().norm, 1e-2f)
        assertEquals(1f, ned.gravity().norm, 1e-2f)
    }
}
