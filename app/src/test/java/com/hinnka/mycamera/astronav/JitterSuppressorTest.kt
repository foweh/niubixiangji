/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**指向抖动抑制**——角度归一化与最短路径插值、指数平滑（含系数 clamp）、
 *       1€ 滤波（自适应：转得快则更跟手）、卡尔曼（匀速模型）、姿态 nlerp 平滑、
 *       窗口统计与抖动指标、质量评分/文案/8 字校准判据、
 *       数值边界（NaN / Inf / 时间戳回退 / 窗口为 0）。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterSuppressorTest {

    private fun exponential(alpha: Float, windowSize: Int = 5) = ScalarJitterFilter(
        mode = SmoothingMode.EXPONENTIAL,
        exponentialAlpha = alpha,
        oneEuroMinCutoff = 1f,
        oneEuroBeta = 0f,
        kalmanProcessNoise = 0.01f,
        kalmanMeasurementNoise = 1f,
        windowSize = windowSize,
    )

    private fun kalman() = ScalarJitterFilter(
        mode = SmoothingMode.KALMAN,
        exponentialAlpha = 0.5f,
        oneEuroMinCutoff = 1f,
        oneEuroBeta = 0f,
        kalmanProcessNoise = 0.01f,
        kalmanMeasurementNoise = 1f,
        windowSize = 5,
    )

    private fun oneEuro(cutoff: Float, beta: Float) = ScalarJitterFilter(
        mode = SmoothingMode.ONE_EURO,
        exponentialAlpha = 0.5f,
        oneEuroMinCutoff = cutoff,
        oneEuroBeta = beta,
        kalmanProcessNoise = 0.01f,
        kalmanMeasurementNoise = 1f,
        windowSize = 5,
    )

    // ---------------- 角度工具 ----------------

    @Test
    fun angle_wrapping_covers_boundaries() {
        assertEquals(10f, AstroNavAngles.wrap360(370f), 1e-4f)
        assertEquals(350f, AstroNavAngles.wrap360(-10f), 1e-4f)
        assertEquals(0f, AstroNavAngles.wrap360(360f), 1e-4f)
        assertEquals(0f, AstroNavAngles.wrap360(720f), 1e-4f)

        assertEquals(0f, AstroNavAngles.wrap180(0f), 1e-4f)
        assertEquals(-170f, AstroNavAngles.wrap180(190f), 1e-4f)
        assertEquals(170f, AstroNavAngles.wrap180(-190f), 1e-4f)
        assertEquals(-180f, AstroNavAngles.wrap180(180f), 1e-4f)
        assertEquals(-180f, AstroNavAngles.wrap180(-180f), 1e-4f)

        // 非有限输入回落 0（不抛异常）
        assertEquals(0f, AstroNavAngles.wrap360(Float.NaN), 1e-6f)
        assertEquals(0f, AstroNavAngles.wrap180(Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun delta_and_shortest_lerp_cross_the_0_360_seam() {
        assertEquals(20f, AstroNavAngles.delta(350f, 10f), 1e-4f)
        assertEquals(-20f, AstroNavAngles.delta(10f, 350f), 1e-4f)
        assertEquals(-180f, AstroNavAngles.delta(0f, 180f), 1e-4f)

        assertEquals(0f, AstroNavAngles.lerpShortest(350f, 10f, 0.5f), 1e-3f)
        assertEquals(45f, AstroNavAngles.lerpShortest(0f, 90f, 0.5f), 1e-3f)
        // 350 -> 10 走最短路径（+20），不会绕 340°
        assertEquals(355f, AstroNavAngles.lerpShortest(350f, 10f, 0.25f), 1e-3f)
        // t 越界 clamp
        assertEquals(350f, AstroNavAngles.lerpShortest(350f, 10f, -1f), 1e-3f)
        assertEquals(10f, AstroNavAngles.lerpShortest(350f, 10f, 2f), 1e-3f)
    }

    // ---------------- 标量平滑 ----------------

    @Test
    fun exponential_smoothing_matches_closed_form() {
        val f = exponential(0.5f)
        assertEquals(10f, f.filter(10f, 0L), 1e-4f)      // 首帧透传
        assertEquals(15f, f.filter(20f, 33L), 1e-4f)     // 10 + (20-10)*0.5
        assertEquals(17.5f, f.filter(20f, 66L), 1e-4f)
        assertEquals(18.75f, f.filter(20f, 99L), 1e-4f)
        assertTrue(f.isInitialised)
    }

    @Test
    fun smoothing_alpha_is_clamped_into_range() {
        // alpha 过小 -> clamp 到 0.01（仍会缓慢跟随，不会卡死）
        val tiny = exponential(0f)
        tiny.filter(0f, 0L)
        val afterTiny = tiny.filter(100f, 33L)
        assertTrue(afterTiny > 0f && afterTiny < 100f)

        // alpha 过大 -> clamp 到 1（等于透传）
        val full = exponential(9f)
        full.filter(0f, 0L)
        assertEquals(100f, full.filter(100f, 33L), 1e-4f)
    }

    @Test
    fun none_mode_passes_through() {
        val f = ScalarJitterFilter(
            mode = SmoothingMode.NONE,
            exponentialAlpha = 0.5f,
            oneEuroMinCutoff = 1f,
            oneEuroBeta = 0f,
            kalmanProcessNoise = 0.01f,
            kalmanMeasurementNoise = 1f,
        )
        assertEquals(5f, f.filter(5f, 0L), 1e-4f)
        assertEquals(-7f, f.filter(-7f, 33L), 1e-4f)
    }

    @Test
    fun kalman_converges_to_constant_signal() {
        val f = kalman()
        assertEquals(10f, f.filter(10f, 0L), 1e-4f)
        repeat(50) { f.filter(10f, 33L) }
        assertEquals(10f, f.value, 1e-3f) // 常值输入 -> 零新息 -> 稳定不漂移
    }

    @Test
    fun kalman_moves_monotonically_towards_step_with_bounded_lag() {
        val f = kalman()
        f.filter(0f, 0L)
        var previous = 0f
        var monotonicallyGrowing = true
        repeat(8) {
            val v = f.filter(10f, 33L)
            if (v < previous - 1e-4f) monotonicallyGrowing = false
            previous = v
        }
        assertTrue("卡尔曼输出应单调上升: $previous", monotonicallyGrowing)
        assertTrue("应仍有滞后（未立刻到 10）: $previous", previous > 5f && previous < 10f)

        // 长时间收敛：允许有限过冲（速度项），但必须稳定在测量值附近
        var maxValue = 0f
        repeat(300) {
            val v = f.filter(10f, 33L)
            if (v > maxValue) maxValue = v
        }
        assertEquals(10f, f.value, 0.1f)
        assertTrue("过冲应有界: $maxValue", maxValue < 11f)
    }

    @Test
    fun one_euro_converges_when_still() {
        val f = oneEuro(cutoff = 1f, beta = 0f)
        f.filter(0f, 0L)
        var t = 0L
        repeat(200) {
            t += 33L
            f.filter(20f, t)
        }
        assertEquals(20f, f.value, 0.1f)
    }

    @Test
    fun one_euro_is_more_responsive_for_fast_motion() {
        // 同样是 60 步匀加速跟随，快速转动下的「滞后/步长」应显著更小（自适应抬升截止频率）
        fun lagInSteps(deltaPerStep: Float): Float {
            val f = oneEuro(cutoff = 0.5f, beta = 5f)
            var target = 0f
            var t = 0L
            f.filter(target, t)
            repeat(60) {
                target += deltaPerStep
                t += 16L
                f.filter(target, t)
            }
            return (target - f.value) / deltaPerStep
        }

        val slow = lagInSteps(0.2f)
        val fast = lagInSteps(10f)
        assertTrue("快速转动滞后步数应更小: slow=$slow fast=$fast", fast < slow)
        assertTrue("快速转动应基本跟手（<2 步）: $fast", fast < 2f)
    }

    @Test
    fun non_finite_samples_are_ignored() {
        val f = exponential(0.5f)
        f.filter(5f, 0L)
        val before = f.value
        assertEquals(before, f.filter(Float.NaN, 33L), 1e-6f)
        assertEquals(before, f.filter(Float.POSITIVE_INFINITY, 66L), 1e-6f)
        assertEquals(before, f.filter(Float.NEGATIVE_INFINITY, 99L), 1e-6f)
        // 未初始化时喂 NaN -> 返回 0，且不进入已初始化状态
        val fresh = exponential(0.5f)
        assertEquals(0f, fresh.filter(Float.NaN, 0L), 1e-6f)
        assertFalse(fresh.isInitialised)
    }

    @Test
    fun invalid_timestamps_fall_back_to_fixed_step() {
        // 时间戳回退 / 超大间隔：走 1/30s 兜底，不抛异常、不产生非有限值
        val f = oneEuro(cutoff = 1f, beta = 1f)
        f.filter(0f, 1_000L)
        assertTrue(f.filter(10f, 900L).isFinite())      // 时间回退
        assertTrue(f.filter(20f, 900L).isFinite())      // 同时间戳（dt=0）
        assertTrue(f.filter(30f, 900_000L).isFinite())  // 超大间隔
        assertTrue(f.value.isFinite())

        val k = kalman()
        k.filter(0f, 10L)
        assertTrue(k.filter(10f, 5L).isFinite())
        assertTrue(k.filter(10f, 10_000_000L).isFinite())
    }

    // ---------------- 窗口统计 / 批量平滑 / 复位 ----------------

    @Test
    fun window_statistics_track_mean_and_jitter() {
        val f = exponential(0.5f, windowSize = 4)
        f.filter(0f, 0L)
        f.filter(1f, 33L)
        f.filter(2f, 66L)
        f.filter(3f, 99L)
        assertEquals(1.5f, f.windowedMean(), 1e-4f)
        // 平均绝对偏差：(1.5+0.5+0.5+1.5)/4 = 1.0
        assertEquals(1.0f, f.jitterEstimate(), 1e-4f)

        // 窗口满后按环形覆盖
        f.filter(10f, 132L)
        assertEquals((1f + 2f + 3f + 10f) / 4f, f.windowedMean(), 1e-4f)
    }

    @Test
    fun window_size_zero_is_clamped() {
        val f = exponential(0.5f, windowSize = 0)
        f.filter(7f, 0L)
        assertEquals(7f, f.windowedMean(), 1e-4f) // 退化为单样本窗口
        assertEquals(0f, f.jitterEstimate(), 1e-4f) // 样本不足 -> 0
    }

    @Test
    fun reset_clears_all_state() {
        val f = exponential(0.5f)
        f.filter(3f, 0L)
        f.filter(9f, 33L)
        f.reset()
        assertFalse(f.isInitialised)
        assertEquals(0f, f.value, 1e-6f)
        assertEquals(0f, f.windowedMean(), 1e-6f)
        // 复位后首帧再次透传
        assertEquals(42f, f.filter(42f, 0L), 1e-4f)
    }

    @Test
    fun smooth_series_is_deterministic_and_handles_edges() {
        val exp = exponential(0.5f)
        val out = exp.smoothSeries(floatArrayOf(0f, 10f, 10f, 10f))
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(5f, out[1], 1e-4f)
        assertEquals(7.5f, out[2], 1e-4f)
        assertEquals(8.75f, out[3], 1e-4f)
        // 批量平滑不改变内部状态
        assertFalse(exp.isInitialised)
        assertEquals(0, exp.smoothSeries(FloatArray(0)).size)

        // NONE 模式原样拷贝
        val none = ScalarJitterFilter(
            mode = SmoothingMode.NONE,
            exponentialAlpha = 1f, oneEuroMinCutoff = 1f, oneEuroBeta = 0f,
            kalmanProcessNoise = 0.01f, kalmanMeasurementNoise = 1f,
        )
        val copy = none.smoothSeries(floatArrayOf(1f, 9f, -3f))
        assertEquals(-3f, copy[2], 1e-6f)

        // 卡尔曼常值序列保持不变
        val k = kalman().smoothSeries(floatArrayOf(5f, 5f, 5f, 5f))
        for (v in k) assertEquals(5f, v, 1e-3f)
    }

    // ---------------- 姿态平滑 ----------------

    private fun attitudeSmoother(mode: SmoothingMode, alpha: Float = 0.5f, beta: Float = 0f) =
        AttitudeSmoother(mode = mode, exponentialAlpha = alpha, oneEuroMinCutoff = 1f, oneEuroBeta = beta)

    private fun rotationOf(deg: Float) =
        AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), deg)

    @Test
    fun attitude_smoother_passthrough_and_nlerp_path() {
        val none = attitudeSmoother(SmoothingMode.NONE)
        assertFalse(none.isInitialised)
        assertNull(none.value)
        val q = rotationOf(60f)
        assertEquals(0f, q.angleDegreesTo(none.filter(q, 0L)), 1e-3f)
        assertNotNull(none.value)

        val exp = attitudeSmoother(SmoothingMode.EXPONENTIAL, alpha = 0.5f)
        exp.filter(AstroNavQuaternion.IDENTITY, 0L)
        val mid = exp.filter(rotationOf(60f), 33L)
        // 从单位姿态出发、t=0.5 -> 约 30°
        assertEquals(30f, AstroNavQuaternion.IDENTITY.angleDegreesTo(mid), 1f)
    }

    @Test
    fun attitude_smoother_rejects_unusable_samples() {
        val exp = attitudeSmoother(SmoothingMode.EXPONENTIAL)
        // 未初始化 + 脏样本 -> 回落 IDENTITY（不抛异常）
        assertEquals(AstroNavQuaternion.IDENTITY, exp.filter(AstroNavQuaternion(0f, 0f, 0f, 0f), 0L))
        assertEquals(AstroNavQuaternion.IDENTITY, exp.filter(AstroNavQuaternion(Float.NaN, 0f, 0f, 1f), 33L))
        // 已初始化 + 脏样本 -> 保持上一次输出
        exp.reset()
        val good = exp.filter(rotationOf(45f), 0L)
        val kept = exp.filter(AstroNavQuaternion(0f, 0f, 0f, 0f), 33L)
        assertEquals(0f, good.angleDegreesTo(kept), 1e-3f)
        assertTrue(kept.isUsable)
    }

    @Test
    fun attitude_smoother_one_euro_keeps_fast_motion_responsive() {
        // 同一个 90° 跳变：beta 越大 -> 速度项抬升截止频率 -> 输出更接近真实姿态
        val slow = attitudeSmoother(SmoothingMode.ONE_EURO, beta = 0f)
        val fast = attitudeSmoother(SmoothingMode.ONE_EURO, beta = 5f)
        slow.filter(AstroNavQuaternion.IDENTITY, 0L)
        fast.filter(AstroNavQuaternion.IDENTITY, 0L)
        val target = rotationOf(90f)
        val slowOut = slow.filter(target, 16L)
        val fastOut = fast.filter(target, 16L)
        val slowAngle = AstroNavQuaternion.IDENTITY.angleDegreesTo(slowOut)
        val fastAngle = AstroNavQuaternion.IDENTITY.angleDegreesTo(fastOut)
        assertTrue("beta 大应更跟手: slow=$slowAngle fast=$fastAngle", fastAngle > slowAngle)
        assertTrue("快速转动应基本跟上: $fastAngle", fastAngle > 80f)
        // 输出始终是单位可用四元数
        assertTrue(fastOut.isUsable)
        assertEquals(1f, fastOut.normSquared, 1e-3f)
    }

    @Test
    fun attitude_smoother_handles_timestamp_anomalies_and_reset() {
        val s = attitudeSmoother(SmoothingMode.KALMAN)
        assertTrue(s.filter(rotationOf(30f), 1_000L).isUsable)
        assertTrue(s.filter(rotationOf(60f), 900L).isUsable)          // 时间回退
        assertTrue(s.filter(rotationOf(90f), 900_000L).isUsable)      // 超大间隔
        s.reset()
        assertNull(s.value)
        assertFalse(s.isInitialised)
    }

    // ---------------- 质量评估 ----------------

    @Test
    fun quality_score_composition_and_clamping() {
        assertEquals(1f, AttitudeQuality.score(3, 300, 300, 0f), 1e-4f)
        assertEquals(0f, AttitudeQuality.score(0, 0, 300, 20f), 1e-4f)
        // 0.45*(1/3) + 0.35*0.5 + 0.20*0.5 = 0.425
        assertEquals(0.425f, AttitudeQuality.score(1, 150, 300, 10f), 1e-3f)
        // 越界输入被 clamp（magAccuracy 7 -> 3；requiredSamples 0 -> 1）
        assertEquals(1f, AttitudeQuality.score(7, 5, 0, -5f), 1e-4f)
        // 非有限残差不产生 NaN 分值
        val nanScore = AttitudeQuality.score(3, 300, 300, Float.NaN)
        assertTrue("分值必须有限: $nanScore", nanScore.isFinite())
        assertEquals(0.8f, nanScore, 1e-4f) // 只有磁力计与样本两项得分
    }

    @Test
    fun quality_describe_boundaries() {
        assertEquals("精度优秀", AttitudeQuality.describe(1f))
        assertEquals("精度优秀", AttitudeQuality.describe(0.8f))
        assertEquals("精度良好", AttitudeQuality.describe(0.799f))
        assertEquals("精度良好", AttitudeQuality.describe(0.6f))
        assertEquals("精度一般", AttitudeQuality.describe(0.599f))
        assertEquals("精度一般", AttitudeQuality.describe(0.4f))
        assertEquals("精度较差", AttitudeQuality.describe(0.399f))
        assertEquals("精度较差", AttitudeQuality.describe(0.2f))
        assertEquals("精度不可用", AttitudeQuality.describe(0.199f))
        assertEquals("精度不可用", AttitudeQuality.describe(Float.NaN))
    }

    @Test
    fun figure_eight_hint_trigger_conditions() {
        assertTrue(AttitudeQuality.needsFigureEight(2, 500, 300, qualityMin = 3))
        assertTrue(AttitudeQuality.needsFigureEight(3, 299, 300, qualityMin = 3))
        assertFalse(AttitudeQuality.needsFigureEight(3, 300, 300, qualityMin = 3))
        // qualityMin 越界 clamp 到 0..3
        assertFalse(AttitudeQuality.needsFigureEight(0, 300, 300, qualityMin = 0))
        // requiredSamples 0 -> 视为 1
        assertFalse(AttitudeQuality.needsFigureEight(3, 1, 0, qualityMin = 3))
    }

    @Test
    fun std_dev_and_jitter_reduction_ratio() {
        assertEquals(0f, AttitudeQuality.stdDev(FloatArray(0)), 1e-6f)
        assertEquals(0f, AttitudeQuality.stdDev(floatArrayOf(2f, 2f, 2f)), 1e-6f)
        assertEquals(1f, AttitudeQuality.stdDev(floatArrayOf(1f, 3f)), 1e-4f)
        assertEquals(0f, AttitudeQuality.stdDev(floatArrayOf(5f)), 1e-6f)

        assertEquals(0.75f, AttitudeQuality.jitterReductionRatio(1f, 0.25f), 1e-4f)
        assertEquals(0f, AttitudeQuality.jitterReductionRatio(1f, 1f), 1e-4f)
        assertEquals(0f, AttitudeQuality.jitterReductionRatio(0f, 0.5f), 1e-4f)          // 原始无抖动
        assertEquals(0f, AttitudeQuality.jitterReductionRatio(1f, 2f), 1e-4f)            // 变差 -> clamp 0
        assertEquals(0f, AttitudeQuality.jitterReductionRatio(1f, Float.NaN), 1e-4f)     // 非有限 -> 0
    }
}
