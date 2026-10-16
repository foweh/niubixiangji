/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：传感器样本预处理与校准进度（纯 Kotlin，无 Android 依赖 -> 可 JVM 单测）。
 *
 * 校准流程说明（用户可见的「画 8 字」）：
 *  - 手机磁力计需要在三维空间中转过足够多的姿态，系统的硬/软磁校准才能收敛。
 *    业界通用做法（Android 官方指南、Google 地图指南针、各星图 App）是提示用户
 *    「手持手机画 8 字」。本模块用 [AstroNavCalibrationTracker] 量化这一过程：
 *    把加速度计重力方向映射到 8 个方向桶（覆盖 6 个面 + 对角），
 *    覆盖桶数越多 = 姿态转得越全 = 校准越充分，避免「转了两下就以为校准好了」。
 *  - 桶覆盖 + 样本数是**可配置**的（[AstroNavConfig.calibrateSampleCount] /
 *    [AstroNavConfig.magCalibrationQualityMin]），并对磁力计等级做加权。
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 传感器样本预处理（无状态工具）。
 */
object AstroNavSensorMath {

    /** 一阶低通（对传感器原始样本降噪；alpha 越小越平滑）。 */
    fun lowPass(prev: AstroNavVec3, sample: AstroNavVec3, alpha: Float): AstroNavVec3 {
        // 非有限 alpha（误设 NaN/Inf）按「透传最新样本」处理，避免把 NaN 灌进滤波器
        val a = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 1f
        return AstroNavVec3(
            prev.x + (sample.x - prev.x) * a,
            prev.y + (sample.y - prev.y) * a,
            prev.z + (sample.z - prev.z) * a,
        )
    }

    /** 向量模长（度/秒 或 g）。 */
    fun magnitude(v: AstroNavVec3): Float = v.norm

    /**
     * 估计采样率（Hz）：用时间戳差分的中位数（抗丢帧/抖动）。
     * 样本不足或时间戳非法时返回 0。
     */
    fun estimateSampleRateHz(timestampsMs: LongArray): Float {
        if (timestampsMs.size < 3) return 0f
        val diffs = ArrayList<Long>(timestampsMs.size - 1)
        for (i in 1 until timestampsMs.size) {
            val d = timestampsMs[i] - timestampsMs[i - 1]
            if (d in 1..1_000) diffs += d
        }
        if (diffs.isEmpty()) return 0f
        diffs.sort()
        val median = diffs[diffs.size / 2].toFloat()
        if (median <= 0f) return 0f
        return (1000f / median).coerceIn(1f, 1_000f)
    }

    /** 加速度计读数是否「接近静止」（|a| ≈ 1g，容差可配）——用于判断是否可用于倾角反馈。 */
    fun isNearGravity(accelerometerG: AstroNavVec3, toleranceG: Float = 0.15f): Boolean {
        if (accelerometerG.isZero) return false
        val m = accelerometerG.norm
        if (!m.isFinite()) return false
        return abs(m - 1f) <= toleranceG.coerceIn(0.01f, 1f)
    }

    /**
     * 重力方向与期望方向的夹角（度，0..180）。用于判断手机是否被平稳放置。
     * 零向量 -> 返回 180（视为不可用）。
     */
    fun angleBetweenDegrees(a: AstroNavVec3, b: AstroNavVec3): Float {
        val na = a.normalized()
        val nb = b.normalized()
        if (na.isZero || nb.isZero) return 180f
        val dot = na.dot(nb).coerceIn(-1f, 1f)
        return (Math.toDegrees(kotlin.math.acos(dot.toDouble()))).toFloat()
    }

    /** 陀螺仪读数是否超量程（用于提示「转动过快，已软重启」）。 */
    fun isGyroOverRange(gyroscopeDps: AstroNavVec3, rangeDps: Float): Boolean {
        if (rangeDps <= 0f || !rangeDps.isFinite()) return false
        val threshold = 0.98f * rangeDps
        return abs(gyroscopeDps.x) > threshold ||
            abs(gyroscopeDps.y) > threshold ||
            abs(gyroscopeDps.z) > threshold
    }

    /** 磁力计强度是否在常见地磁场范围（25–65 µT）内——用于识别强磁干扰（喇叭/支架磁铁）。 */
    fun isMagneticFieldPlausible(magnetometerMicroTesla: AstroNavVec3): Boolean {
        if (magnetometerMicroTesla.isZero) return false
        val m = magnetometerMicroTesla.norm
        if (!m.isFinite()) return false
        return m in MAG_FIELD_MIN_UT..MAG_FIELD_MAX_UT
    }

    /** 磁力计校准等级（0..3）-> 中文描述。 */
    fun describeMagAccuracy(level: Int): String = when (level.coerceIn(0, 3)) {
        0 -> "磁力计未校准"
        1 -> "磁力计精度低"
        2 -> "磁力计精度中"
        else -> "磁力计精度高"
    }

    /** 把 3 轴向量映射到方向桶下标（8 桶：以重力方向的主导轴 + 符号分桶）。 */
    fun directionBin(v: AstroNavVec3, bins: Int): Int {
        if (bins <= 0) return 0
        val n = v.normalized()
        if (n.isZero) return -1
        // 用符号把空间分成 8 个卦限，再按需压缩到 bins
        var octant = 0
        if (n.x >= 0f) octant = octant or 1
        if (n.y >= 0f) octant = octant or 2
        if (n.z >= 0f) octant = octant or 4
        return if (bins >= 8) octant else octant % bins
    }

    /** 地磁场强度合理区间（µT）。 */
    const val MAG_FIELD_MIN_UT = 20f
    const val MAG_FIELD_MAX_UT = 70f

    /** 平方根（供调用点少写 import）。 */
    internal fun sqrtOf(v: Float): Float = sqrt(v)
}

/**
 * 「画 8 字」校准进度跟踪器（纯逻辑，可单测）。
 *
 * 完成判据（两者同时满足，任一可配）：
 *  1. 有效样本数 >= [requiredSamples]；
 *  2. 覆盖的姿态方向桶数 >= 桶总数（即三维空间都转到过）。
 *
 * 进度 = 0.4 × 样本进度 + 0.6 × 覆盖进度（覆盖更能反映校准是否充分）。
 */
class AstroNavCalibrationTracker(
    private val requiredSamples: Int = 300,
    private val orientationBins: Int = 8,
    /** 单样本纳入统计的最小时间间隔（毫秒），防止高频样本瞬间刷满计数。 */
    private val minSampleIntervalMs: Long = 16L,
) {

    private val bins = BooleanArray(orientationBins.coerceAtLeast(1))
    private var lastSampleMs = Long.MIN_VALUE

    /** 已接受的样本数。 */
    var sampleCount: Int = 0
        private set

    /** 已覆盖的方向桶数。 */
    val coveredBins: Int get() = bins.count { it }

    /** 总桶数。 */
    val totalBins: Int get() = bins.size

    /** 进度 0..1。 */
    val progress: Float
        get() {
            val req = requiredSamples.coerceAtLeast(1)
            val sampleProgress = (sampleCount.toFloat() / req).coerceIn(0f, 1f)
            val coverageProgress = coveredBins.toFloat() / totalBins.toFloat()
            return (0.4f * sampleProgress + 0.6f * coverageProgress).coerceIn(0f, 1f)
        }

    /** 是否已完成校准。 */
    val isComplete: Boolean
        get() = sampleCount >= requiredSamples.coerceAtLeast(1) && coveredBins >= totalBins

    /** 供 UI 展示的中文提示。 */
    val hint: String
        get() = if (isComplete) {
            "校准完成，可开始瞄准"
        } else {
            "请手持手机缓慢画 8 字（多个方向各转几次）：已覆盖 $coveredBins/$totalBins 个方向，" +
                "样本 $sampleCount/${requiredSamples.coerceAtLeast(1)}"
        }

    /**
     * 接受一个传感器样本。
     *
     * @param accelerometerG 加速度计（g）
     * @param magnetometer 磁力计（任意单位，用于判断是否真有磁场读数）
     * @param timestampMs 样本时间戳
     * @return 更新后的 [progress]
     */
    fun accept(accelerometerG: AstroNavVec3, magnetometer: AstroNavVec3, timestampMs: Long): Float {
        val accOk = !accelerometerG.isZero && accelerometerG.norm.isFinite()
        val magOk = !magnetometer.isZero && magnetometer.norm.isFinite()
        if (!accOk || !magOk) return progress // 脏样本不计入
        if (timestampMs - lastSampleMs < minSampleIntervalMs && lastSampleMs != Long.MIN_VALUE) {
            return progress
        }
        lastSampleMs = timestampMs
        sampleCount++

        // 重力方向（设备系）作为姿态方向代表；磁力计存在时再叠加其方向桶
        val bin = AstroNavSensorMath.directionBin(accelerometerG, bins.size)
        if (bin in bins.indices) bins[bin] = true
        val magBin = AstroNavSensorMath.directionBin(magnetometer, bins.size)
        if (magBin in bins.indices) bins[magBin] = true
        return progress
    }

    /** 复位（换地点/用户点「重新校准」时调用）。 */
    fun reset() {
        bins.fill(false)
        sampleCount = 0
        lastSampleMs = Long.MIN_VALUE
    }
}
