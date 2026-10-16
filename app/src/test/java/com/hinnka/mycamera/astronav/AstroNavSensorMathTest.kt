/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：传感器原始量处理与校准流程（纯逻辑部分）——一阶低通（含非有限 alpha 兜底）、
 *       模长/夹角、采样率中位数估计（抗丢帧）、静止判据、陀螺量程判据、磁场合理区间、
 *       磁力计校准等级文案、方向分桶，以及「画 8 字」校准进度跟踪器
 *       （样本节流 + 8 卦限覆盖 + 完成判据 + 复位）。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroNavSensorMathTest {

    private fun v(x: Float, y: Float, z: Float) = AstroNavVec3(x, y, z)

    // ---------------- 低通 ----------------

    @Test
    fun low_pass_interpolates_and_clamps_alpha() {
        val prev = v(0f, 0f, 0f)
        val sample = v(10f, 20f, 30f)
        val half = AstroNavSensorMath.lowPass(prev, sample, 0.5f)
        assertEquals(5f, half.x, 1e-4f)
        assertEquals(10f, half.y, 1e-4f)
        assertEquals(15f, half.z, 1e-4f)

        // alpha 越界 -> clamp
        assertEquals(10f, AstroNavSensorMath.lowPass(prev, sample, 5f).x, 1e-4f)
        assertEquals(0f, AstroNavSensorMath.lowPass(prev, sample, -3f).x, 1e-4f)
        // 非有限 alpha -> 透传最新样本（绝不把 NaN 灌进滤波器）
        val nanAlpha = AstroNavSensorMath.lowPass(prev, sample, Float.NaN)
        assertEquals(10f, nanAlpha.x, 1e-4f)
        assertEquals(30f, nanAlpha.z, 1e-4f)
        assertEquals(10f, AstroNavSensorMath.lowPass(prev, sample, Float.POSITIVE_INFINITY).x, 1e-4f)
        // 迭代收敛
        var cur = prev
        repeat(20) { cur = AstroNavSensorMath.lowPass(cur, sample, 0.5f) }
        assertEquals(10f, cur.x, 1e-3f)
    }

    @Test
    fun magnitude_and_angle_helpers() {
        assertEquals(5f, AstroNavSensorMath.magnitude(v(3f, 4f, 0f)), 1e-4f)
        assertEquals(1f, AstroNavSensorMath.magnitude(v(0f, 0f, 1f)), 1e-4f)

        assertEquals(90f, AstroNavSensorMath.angleBetweenDegrees(v(1f, 0f, 0f), v(0f, 1f, 0f)), 1e-3f)
        assertEquals(0f, AstroNavSensorMath.angleBetweenDegrees(v(1f, 2f, 3f), v(2f, 4f, 6f)), 1e-3f)
        assertEquals(180f, AstroNavSensorMath.angleBetweenDegrees(v(0f, 0f, 1f), v(0f, 0f, -1f)), 1e-3f)
        // 零向量视为不可用
        assertEquals(180f, AstroNavSensorMath.angleBetweenDegrees(v(0f, 0f, 0f), v(0f, 0f, 1f)), 1e-3f)
        assertEquals(180f, AstroNavSensorMath.angleBetweenDegrees(v(0f, 0f, 0f), v(0f, 0f, 0f)), 1e-3f)
        // 45° 斜置
        assertEquals(45f, AstroNavSensorMath.angleBetweenDegrees(v(1f, 1f, 0f), v(1f, 0f, 0f)), 1e-3f)
    }

    // ---------------- 采样率估计 ----------------

    @Test
    fun sample_rate_estimation_uses_median_and_rejects_garbage() {
        // 100Hz 稳定序列
        assertEquals(
            100f,
            AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 10, 20, 30, 40, 50)),
            0.01f,
        )
        // 含一次丢帧（980ms）但中位数不受影响
        assertEquals(
            100f,
            AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 10, 20, 1000, 1010, 1020)),
            0.01f,
        )
        // 200Hz
        assertEquals(200f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 5, 10)), 0.01f)
        // 1Hz（间隔 1000ms 仍在接受范围内）
        assertEquals(1f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 1000, 2000)), 0.01f)
        // 上限 1000Hz
        assertEquals(1_000f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 1, 2, 3)), 0.01f)

        // 样本不足
        assertEquals(0f, AstroNavSensorMath.estimateSampleRateHz(LongArray(0)), 0f)
        assertEquals(0f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 10)), 0f)
        // 全部为无效间隔（0 或 > 1000ms）
        assertEquals(0f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 0, 0)), 0f)
        assertEquals(0f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(0, 5000, 10_000)), 0f)
        // 时间戳回退（负间隔被丢弃）
        assertEquals(0f, AstroNavSensorMath.estimateSampleRateHz(longArrayOf(100, 90, 80)), 0f)
    }

    // ---------------- 静止 / 量程 / 磁场 ----------------

    @Test
    fun gravity_and_gyro_range_checks() {
        assertTrue(AstroNavSensorMath.isNearGravity(v(0f, 0f, 1f)))
        assertTrue(AstroNavSensorMath.isNearGravity(v(0f, 0f, 1.1f)))
        assertFalse(AstroNavSensorMath.isNearGravity(v(0f, 0f, 1.3f)))
        // 容差可配
        assertTrue(AstroNavSensorMath.isNearGravity(v(0f, 0f, 1.3f), toleranceG = 0.35f))
        // 容差自身 clamp 到 [0.01, 1]
        assertTrue(AstroNavSensorMath.isNearGravity(v(0f, 0f, 1.3f), toleranceG = 1e9f))
        // 零向量 / 非有限 -> 不可用
        assertFalse(AstroNavSensorMath.isNearGravity(v(0f, 0f, 0f)))
        assertFalse(AstroNavSensorMath.isNearGravity(v(Float.NaN, 0f, 1f)))

        // 陀螺量程：阈值 = 0.98 × 量程
        assertTrue(AstroNavSensorMath.isGyroOverRange(v(1961f, 0f, 0f), 2000f))
        assertFalse(AstroNavSensorMath.isGyroOverRange(v(1960f, 0f, 0f), 2000f))
        assertTrue(AstroNavSensorMath.isGyroOverRange(v(0f, 0f, -2000f), 2000f))
        // 非法量程 -> 不判定（避免误报）
        assertFalse(AstroNavSensorMath.isGyroOverRange(v(10_000f, 0f, 0f), 0f))
        assertFalse(AstroNavSensorMath.isGyroOverRange(v(10_000f, 0f, 0f), -100f))
        assertFalse(AstroNavSensorMath.isGyroOverRange(v(10_000f, 0f, 0f), Float.NaN))
    }

    @Test
    fun magnetic_field_plausibility_window() {
        assertTrue(AstroNavSensorMath.isMagneticFieldPlausible(v(45f, 0f, 0f)))
        assertTrue(AstroNavSensorMath.isMagneticFieldPlausible(v(AstroNavSensorMath.MAG_FIELD_MIN_UT, 0f, 0f)))
        assertTrue(AstroNavSensorMath.isMagneticFieldPlausible(v(0f, 0f, AstroNavSensorMath.MAG_FIELD_MAX_UT)))
        // 边界外（强磁干扰 / 传感器故障）
        assertFalse(AstroNavSensorMath.isMagneticFieldPlausible(v(19.9f, 0f, 0f)))
        assertFalse(AstroNavSensorMath.isMagneticFieldPlausible(v(70.1f, 0f, 0f)))
        assertFalse(AstroNavSensorMath.isMagneticFieldPlausible(v(0f, 0f, 0f)))
        assertFalse(AstroNavSensorMath.isMagneticFieldPlausible(v(Float.NaN, Float.NaN, Float.NaN)))
        assertTrue(AstroNavSensorMath.MAG_FIELD_MIN_UT < AstroNavSensorMath.MAG_FIELD_MAX_UT)
    }

    @Test
    fun mag_accuracy_descriptions_are_all_defined() {
        assertEquals("磁力计未校准", AstroNavSensorMath.describeMagAccuracy(0))
        assertEquals("磁力计精度低", AstroNavSensorMath.describeMagAccuracy(1))
        assertEquals("磁力计精度中", AstroNavSensorMath.describeMagAccuracy(2))
        assertEquals("磁力计精度高", AstroNavSensorMath.describeMagAccuracy(3))
        // 越界 clamp
        assertEquals("磁力计未校准", AstroNavSensorMath.describeMagAccuracy(-5))
        assertEquals("磁力计精度高", AstroNavSensorMath.describeMagAccuracy(99))
        for (level in -3..6) {
            assertTrue(AstroNavSensorMath.describeMagAccuracy(level).startsWith("磁力计"))
        }
    }

    @Test
    fun direction_bins_cover_all_octants() {
        assertEquals(1, AstroNavSensorMath.directionBin(v(1f, 0f, 0f), 8))
        assertEquals(2, AstroNavSensorMath.directionBin(v(0f, 1f, 0f), 8))
        assertEquals(4, AstroNavSensorMath.directionBin(v(0f, 0f, 1f), 8))
        assertEquals(7, AstroNavSensorMath.directionBin(v(1f, 1f, 1f), 8))
        assertEquals(0, AstroNavSensorMath.directionBin(v(-1f, -1f, -1f), 8))
        // 压缩桶
        assertEquals(7 % 4, AstroNavSensorMath.directionBin(v(1f, 1f, 1f), 4))
        // 零向量 / 非法桶数
        assertEquals(-1, AstroNavSensorMath.directionBin(v(0f, 0f, 0f), 8))
        assertEquals(0, AstroNavSensorMath.directionBin(v(1f, 0f, 0f), 0))
        assertEquals(0, AstroNavSensorMath.directionBin(v(1f, 0f, 0f), -3))
    }

    // ---------------- 画 8 字校准 ----------------

    @Test
    fun calibration_tracker_rejects_dirty_samples_and_throttles() {
        val t = AstroNavCalibrationTracker(requiredSamples = 4, orientationBins = 8, minSampleIntervalMs = 16L)
        assertEquals(0, t.sampleCount)
        assertEquals(0, t.coveredBins)
        assertEquals(8, t.totalBins)
        assertEquals(0f, t.progress, 1e-6f)
        assertFalse(t.isComplete)

        // 脏样本：零加速度 / 零磁场 / 非有限 -> 不计入
        assertEquals(0f, t.accept(v(0f, 0f, 0f), v(0f, 0f, 1f), 0L), 1e-6f)
        assertEquals(0f, t.accept(v(0f, 0f, 1f), v(0f, 0f, 0f), 0L), 1e-6f)
        assertEquals(0f, t.accept(v(Float.NaN, 0f, 1f), v(1f, 0f, 0f), 0L), 1e-6f)
        assertEquals(0, t.sampleCount)

        // 有效样本：首帧总是计入
        t.accept(v(0f, 0f, 1f), v(1f, 0f, 0f), 1_000L)
        assertEquals(1, t.sampleCount)
        // 节流：距上一样本不足 16ms -> 跳过
        t.accept(v(0f, 0f, 1f), v(1f, 0f, 0f), 1_005L)
        assertEquals(1, t.sampleCount)
        // 时间戳回退同样跳过
        t.accept(v(0f, 0f, 1f), v(1f, 0f, 0f), 900L)
        assertEquals(1, t.sampleCount)
        // 满 16ms -> 计入
        t.accept(v(0f, 0f, 1f), v(1f, 0f, 0f), 1_016L)
        assertEquals(2, t.sampleCount)
        assertTrue(t.hint.contains("画 8 字"))
        assertTrue(t.hint.contains("已覆盖"))
    }

    @Test
    fun calibration_completes_after_covering_all_bins() {
        val t = AstroNavCalibrationTracker(requiredSamples = 8, orientationBins = 8, minSampleIntervalMs = 0L)
        // 8 个卦限各转一次（±1 组合覆盖 8 桶）
        val samples = listOf(
            v(1f, 1f, 1f), v(1f, 1f, -1f), v(1f, -1f, 1f), v(1f, -1f, -1f),
            v(-1f, 1f, 1f), v(-1f, 1f, -1f), v(-1f, -1f, 1f), v(-1f, -1f, -1f),
        )
        samples.forEachIndexed { i, acc ->
            t.accept(acc, v(0f, 0f, 1f), i * 20L)
        }
        assertEquals(8, t.sampleCount)
        assertEquals(8, t.coveredBins)
        assertTrue(t.isComplete)
        assertEquals(1f, t.progress, 1e-6f)
        assertEquals("校准完成，可开始瞄准", t.hint)

        // 复位后重新计数
        t.reset()
        assertEquals(0, t.sampleCount)
        assertEquals(0, t.coveredBins)
        assertEquals(0f, t.progress, 1e-6f)
        assertFalse(t.isComplete)
    }

    @Test
    fun calibration_progress_weights_samples_and_coverage() {
        val t = AstroNavCalibrationTracker(requiredSamples = 100, orientationBins = 8, minSampleIntervalMs = 0L)
        // 覆盖 1 个桶、1 个样本：0.4×(1/100) + 0.6×(1/8)
        t.accept(v(0f, 0f, 1f), v(0f, 0f, 1f), 0L)
        assertEquals(1, t.coveredBins)
        assertEquals(0.4f * 0.01f + 0.6f * 0.125f, t.progress, 1e-6f)
        // 样本数不影响覆盖，进度随覆盖单调上升
        val before = t.progress
        t.accept(v(1f, 0f, 0f), v(1f, 0f, 0f), 100L)
        assertTrue(t.progress > before)
        assertFalse(t.isComplete)
        // 即使样本达标，覆盖不足也不算完成
        repeat(200) { t.accept(v(0f, 0f, 1f), v(0f, 0f, 1f), 1_000L + it * 20L) }
        assertTrue(t.sampleCount >= 100)
        assertEquals(2, t.coveredBins)
        assertFalse(t.isComplete)
    }
}
