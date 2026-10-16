/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：指向抖动抑制（标量滤波）+ 姿态平滑。
 *
 * ============================================================================
 *  来源说明（PATTERN_SPEC §9）
 * ============================================================================
 *  1€ 滤波（One Euro Filter）：
 *    论文 / 参考实现：Casiez, Roussel, Vogel. "1€ Filter: A Simple Speed-based
 *    Low-pass Filter for Noisy Input in Interactive Systems", CHI 2012。
 *    参考代码 https://gery.casiez.net/1euro/（作者主页提供的参考实现，
 *    亦见 https://github.com/casiez/OneEuroFilter ，**NOASSERTION/未标注许可证**）。
 *    → 许可证不明，按 PATTERN_SPEC §1.4「拿不准按参考处理」，**仅参考其公式思路、
 *      未抄录其任何代码行**；本文件为按论文公式的自研 Kotlin 实现（约 60 行）。
 *  卡尔曼滤波：标量匀速模型（1 维状态 [角度, 角速度]），经典教科书形式，自研。
 *  指数平滑 / nlerp 姿态平滑：通用做法，自研。
 *
 *  PDAF/OIS 无关：本文件只处理姿态数值滤波，不触碰相机管线。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 角度工具（纯函数，全局唯一的弧度/角度换算与夹角归一化）。 */
object AstroNavAngles {

    /** 把角度归一化到 [-180, 180)。 */
    fun wrap180(deg: Float): Float {
        if (deg.isNaN() || deg.isInfinite()) return 0f
        var v = deg % 360f
        if (v >= 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    /** 把角度归一化到 [0, 360)。 */
    fun wrap360(deg: Float): Float {
        if (deg.isNaN() || deg.isInfinite()) return 0f
        var v = deg % 360f
        if (v < 0f) v += 360f
        return v
    }

    /** 两角之差，结果落在 [-180, 180)。 */
    fun delta(fromDeg: Float, toDeg: Float): Float = wrap180(toDeg - fromDeg)

    /** 沿最短路径插值（t 越界会 clamp）。 */
    fun lerpShortest(fromDeg: Float, toDeg: Float, t: Float): Float =
        wrap360(fromDeg + delta(fromDeg, toDeg) * t.coerceIn(0f, 1f))
}

/**
 * 单个标量通道的抖动抑制器（角度通道 / 姿态分值通道通用）。
 *
 * 模式见 [SmoothingMode]；参数全部来自 [AstroNavConfig]，已 clamp。
 * 用法：每帧 `filter(value, dtSeconds)`；时间戳非法（<=0）时退回固定步长 1/30s。
 */
class ScalarJitterFilter(
    private val mode: SmoothingMode,
    private val exponentialAlpha: Float,
    private val oneEuroMinCutoff: Float,
    private val oneEuroBeta: Float,
    private val kalmanProcessNoise: Float,
    private val kalmanMeasurementNoise: Float,
    /** 窗口大小（仅 [SmoothingMode.NONE] 之外用于自适应判据；见 [windowedMean]）。 */
    private val windowSize: Int = 5,
) {

    private var initialised = false
    private var lastValue = 0f
    private var lastTimeMs = 0L

    // 指数平滑 / 1€ 共用的低通状态
    private var filtered = 0f
    private var lastRaw = 0f

    // 卡尔曼状态
    private var kfX = 0f
    private var kfV = 0f
    private var kfP00 = 1f
    private var kfP01 = 0f
    private var kfP10 = 0f
    private var kfP11 = 1f

    // 滑动窗口（用于 QUALITY 评估与平均输出）
    private val window = FloatArray(windowSize.coerceAtLeast(1))
    private var windowCount = 0
    private var windowHead = 0

    /** 是否已收到有效样本（未初始化时第一帧直接透传，避免从 0 冲上来）。 */
    val isInitialised: Boolean get() = initialised

    /** 当前滤波输出。 */
    val value: Float get() = lastValue

    /**
     * 输入一个新样本。
     *
     * @param raw 原始测量值（角度类通道请传已归一化的角度）
     * @param timestampMs 采样时间戳（毫秒；非法值会退回等距假设 33ms）
     * @return 滤波后的值
     */
    fun filter(raw: Float, timestampMs: Long): Float {
        if (!raw.isFinite()) return if (initialised) lastValue else 0f

        if (!initialised) {
            initialised = true
            lastValue = raw
            lastRaw = raw
            filtered = raw
            kfX = raw
            kfV = 0f
            lastTimeMs = timestampMs
            pushWindow(raw)
            return raw
        }

        val dtSec = computeDt(timestampMs)

        val out = when (mode) {
            SmoothingMode.NONE -> raw
            SmoothingMode.EXPONENTIAL -> {
                filtered = filtered + (raw - filtered) * exponentialAlpha.coerceIn(0.01f, 1f)
                filtered
            }
            SmoothingMode.ONE_EURO -> {
                val alpha = oneEuroAlpha(dtSec)
                filtered = filtered + (raw - filtered) * alpha
                filtered
            }
            SmoothingMode.KALMAN -> kalmanStep(raw, dtSec)
        }

        lastValue = out
        lastRaw = raw
        lastTimeMs = timestampMs
        pushWindow(raw)
        return out
    }

    /**
     * 通过 BgJobRunner 或后台线程批量平滑（不改变滤波器内部状态）。
     * 用于「录一段时间后离线精修指向」的低优先重路径。
     */
    fun smoothSeries(values: FloatArray): FloatArray {
        if (values.isEmpty()) return FloatArray(0)
        val out = FloatArray(values.size)
        when (mode) {
            SmoothingMode.NONE -> values.copyInto(out)
            SmoothingMode.EXPONENTIAL, SmoothingMode.ONE_EURO -> {
                var acc = values[0]
                out[0] = acc
                val a = if (mode == SmoothingMode.EXPONENTIAL) {
                    exponentialAlpha.coerceIn(0.01f, 1f)
                } else {
                    // 批量模式无时间戳，用固定 30Hz 步长估算自适应系数
                    oneEuroAlpha(1f / 30f)
                }
                for (i in 1 until values.size) {
                    acc += (values[i] - acc) * a
                    out[i] = acc
                }
            }
            SmoothingMode.KALMAN -> {
                var x = values[0]
                var p = 1f
                out[0] = x
                val dt = 1f / 30f
                val q = kalmanProcessNoise.coerceAtLeast(SMOOTHING_EPS)
                val r = kalmanMeasurementNoise.coerceAtLeast(SMOOTHING_EPS)
                for (i in 1 until values.size) {
                    p += q * dt
                    val k = p / (p + r)
                    x += k * (values[i] - x)
                    p *= (1f - k)
                    out[i] = x
                }
            }
        }
        return out
    }

    /** 窗口内均值的绝对偏差（抖动强度指标，度）。 */
    fun jitterEstimate(): Float {
        if (windowCount < 2) return 0f
        val mean = windowedMean()
        var acc = 0f
        for (i in 0 until windowCount) acc += abs(window[i] - mean)
        return acc / windowCount
    }

    /** 当前窗口均值。 */
    fun windowedMean(): Float {
        if (windowCount == 0) return 0f
        var acc = 0f
        for (i in 0 until windowCount) acc += window[i]
        return acc / windowCount
    }

    /** 复位（校准/切换位置后调用）。 */
    fun reset() {
        initialised = false
        lastValue = 0f
        lastRaw = 0f
        filtered = 0f
        kfX = 0f
        kfV = 0f
        kfP00 = 1f
        kfP01 = 0f
        kfP10 = 0f
        kfP11 = 1f
        windowCount = 0
        windowHead = 0
        lastTimeMs = 0L
    }

    // ---- 内部实现 ----

    private fun computeDt(timestampMs: Long): Float {
        val dtMs = timestampMs - lastTimeMs
        if (dtMs <= 0L || dtMs > MAX_DT_MS) return FALLBACK_DT_SEC
        return (dtMs / 1000.0).toFloat()
    }

    /** 1€ 滤波的自适应系数：截止频率随速度线性提升。 */
    private fun oneEuroAlpha(dtSec: Float): Float {
        val dt = dtSec.coerceIn(1f / 1000f, 1f)
        val cutoff = oneEuroMinCutoff.coerceIn(
            AstroNavConfig.ONE_EURO_MIN_CUTOFF_MIN,
            AstroNavConfig.ONE_EURO_MIN_CUTOFF_MAX,
        )
        val beta = oneEuroBeta.coerceIn(AstroNavConfig.ONE_EURO_BETA_MIN, AstroNavConfig.ONE_EURO_BETA_MAX)
        // 速度估计（差分），用于抬升截止频率，保证快速转动时低延迟
        val speed = abs(lastRaw - lastValue) / dt
        val fc = cutoff + beta * speed
        val te = 1f / (2f * Math.PI.toFloat() * fc.coerceAtLeast(1e-4f))
        return (dt / (dt + te)).coerceIn(0f, 1f)
    }

    /** 标量匀速卡尔曼一步。 */
    private fun kalmanStep(measurement: Float, dtSec: Float): Float {
        val dt = dtSec.coerceIn(1f / 1000f, 1f)
        val q = kalmanProcessNoise.coerceIn(
            AstroNavConfig.KALMAN_PROCESS_NOISE_MIN,
            AstroNavConfig.KALMAN_PROCESS_NOISE_MAX,
        )
        val r = kalmanMeasurementNoise.coerceIn(
            AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MIN,
            AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MAX,
        )

        // 预测：x = F x, P = F P Fᵗ + Q（Q 按 dt 缩放）
        val predX = kfX + kfV * dt
        val qPos = q * dt * dt * dt / 3f
        val qVel = q * dt
        val qCross = q * dt * dt / 2f
        val p00 = kfP00 + dt * (kfP10 + kfP01) + dt * dt * kfP11 + qPos
        val p01 = kfP01 + dt * kfP11 + qCross
        val p10 = kfP10 + dt * kfP11 + qCross
        val p11 = kfP11 + qVel

        // 更新
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p10 / s
        val innovation = measurement - predX
        kfX = predX + k0 * innovation
        kfV += k1 * innovation
        kfP00 = (1f - k0) * p00
        kfP01 = (1f - k0) * p01
        kfP10 = p10 - k1 * p00
        kfP11 = p11 - k1 * p01

        if (!kfX.isFinite() || !kfV.isFinite()) {
            reset()
            kfX = measurement
        }
        return kfX
    }

    private fun pushWindow(v: Float) {
        window[windowHead] = v
        windowHead = (windowHead + 1) % window.size
        if (windowCount < window.size) windowCount++
    }

    private companion object {
        const val FALLBACK_DT_SEC = 1f / 30f
        const val MAX_DT_MS = 5_000L
        const val SMOOTHING_EPS = 1e-6f
    }
}

/**
 * 姿态（四元数）平滑器：抑制指向抖动，同时对快速转动保持低延迟。
 *
 * 实现：对四元数做 nlerp（[AstroNavQuaternion.nlerp]），平滑系数由
 * [SmoothingMode] 与角速度共同决定（1€ 思路：转得快 -> 少平滑）。
 */
class AttitudeSmoother(
    private val mode: SmoothingMode,
    private val exponentialAlpha: Float,
    private val oneEuroMinCutoff: Float,
    private val oneEuroBeta: Float,
) {

    private var current: AstroNavQuaternion? = null
    private var lastTimeMs = 0L

    val isInitialised: Boolean get() = current != null

    /** 当前平滑后的姿态（未初始化返回 null）。 */
    val value: AstroNavQuaternion? get() = current

    /** 输入新姿态，返回平滑后的姿态。 */
    fun filter(raw: AstroNavQuaternion, timestampMs: Long): AstroNavQuaternion {
        if (!raw.isUsable) return current ?: AstroNavQuaternion.IDENTITY
        val prev = current
        if (prev == null) {
            current = raw.normalized()
            lastTimeMs = timestampMs
            return current!!
        }

        val n = raw.normalized()
        val dt = dtSeconds(timestampMs)
        val alpha = when (mode) {
            SmoothingMode.NONE -> 1f
            SmoothingMode.EXPONENTIAL -> exponentialAlpha.coerceIn(0.01f, 1f)
            SmoothingMode.ONE_EURO, SmoothingMode.KALMAN -> {
                // 姿态侧用角度变化率驱动：转得越快，alpha 越大（越跟手）
                val speedDegPerSec = prev.angleDegreesTo(n) / dt
                val cutoff = oneEuroMinCutoff.coerceAtLeast(0.01f) + oneEuroBeta * speedDegPerSec
                val te = 1f / (2f * Math.PI.toFloat() * cutoff)
                (dt / (dt + te)).coerceIn(0f, 1f)
            }
        }
        val smoothed = AstroNavQuaternion.nlerp(prev, n, alpha)
        current = smoothed
        lastTimeMs = timestampMs
        return smoothed
    }

    fun reset() {
        current = null
        lastTimeMs = 0L
    }

    private fun dtSeconds(timestampMs: Long): Float {
        val dtMs = timestampMs - lastTimeMs
        if (dtMs <= 0L || dtMs > 5_000L) return 1f / 30f
        return (dtMs / 1000.0).toFloat()
    }
}

/**
 * 姿态质量评估（0..1，1 = 最好）：结合磁力计校准等级、采样点数与残差。
 * 纯函数式，便于单测。
 */
object AttitudeQuality {

    /**
     * @param magAccuracy SensorManager 的磁力计校准等级（0=不可信 .. 3=高）
     * @param sampleCount 已累积的有效样本数
     * @param requiredSamples 校准所需样本数（[AstroNavConfig.calibrateSampleCount]）
     * @param residualDeg 解算残差（度，[FusionAhrsDiagnostics] 的 max(加速度误差, 磁力误差)）
     */
    fun score(magAccuracy: Int, sampleCount: Int, requiredSamples: Int, residualDeg: Float): Float {
        val acc = (magAccuracy.coerceIn(0, 3) / 3f)
        val req = requiredSamples.coerceAtLeast(1)
        val samples = (sampleCount.toFloat() / req).coerceIn(0f, 1f)
        // 残差 0° -> 1；>= 20° -> 0；非有限残差（异常样本）按最差处理，避免污染出 NaN 分值
        val residualInput = if (residualDeg.isFinite()) residualDeg else 20f
        val residual = (1f - (residualInput.coerceIn(0f, 20f) / 20f))
        val score = 0.45f * acc + 0.35f * samples + 0.20f * residual
        return if (score.isFinite()) score.coerceIn(0f, 1f) else 0f
    }

    /** 由质量分给出等级文案（UI 用）。 */
    fun describe(score: Float): String = when {
        score >= 0.8f -> "精度优秀"
        score >= 0.6f -> "精度良好"
        score >= 0.4f -> "精度一般"
        score >= 0.2f -> "精度较差"
        else -> "精度不可用"
    }

    /** 是否需要提示「画 8 字」校准（磁力计等级不足或样本不够）。 */
    fun needsFigureEight(magAccuracy: Int, sampleCount: Int, requiredSamples: Int, qualityMin: Int): Boolean =
        magAccuracy < qualityMin.coerceIn(0, 3) || sampleCount < requiredSamples.coerceAtLeast(1)

    /** 标准差（供自检/质量报告使用）。 */
    fun stdDev(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var mean = 0.0
        for (v in values) mean += v
        mean /= values.size
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / values.size).toFloat()
    }

    /** 供 UI 展示的「抖动抑制比」：原始抖动 / 平滑后抖动（<=0 表示不可用）。 */
    fun jitterReductionRatio(rawStdDev: Float, filteredStdDev: Float): Float {
        if (rawStdDev <= 1e-6f) return 0f
        val r = 1f - filteredStdDev / rawStdDev
        return if (r.isFinite()) r.coerceIn(0f, 1f) else 0f
    }

    /** 幂函数（避免调用点各自 import kotlin.math.pow）。 */
    internal fun square(v: Float): Float = v.pow(2)
}
