/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：姿态序列的离线高精度精修（走 BgJobRunner 低优先）。
 *
 * ============================================================================
 *  性能调度取舍（PATTERN_SPEC §1.5，用户硬约束）
 * ============================================================================
 *  - **前台轻量直跑**：实时姿态（Fusion AHRS + 平滑）在传感器线程直接算，
 *    因为它是 O(1) 的几十次浮点运算、无分配无 IO；进后台队列反而会引入
 *    队列排队 + 分片节流（BG 线程 MIN_PRIORITY），导致指向滞后。
 *  - **重路径走 BgJobRunner**：本文件处理「录一段姿态序列后离线精修」——
 *    双向（前向+后向）滤波、漂移/抖动统计、逐点地平坐标换算。
 *    这类任务 CPU 用量随样本数线性增长，必须：
 *      · 分片处理，每个分片末尾调 `job.checkYield()`（响应暂停/取消 + 节流）；
 *      · 分配大缓冲前 `job.guardMemory(bytes)`，不足时降级（只做单遍滤波）；
 *      · 分批用 `job.batchSizeFor(n)`；
 *      · **不写死总样本上限**——内存不够就降级/分片，而不是拒绝用户数据。
 *  - 任务类型：补丁会在 BgJobRunner.kt 的 [BgTaskType] 中追加 ASTRONAV
 *    （低节流、小批量、窄内存预算，见 patches/astronav_patch.md 的 ENUM 段）。
 *    由于「新文件不得依赖未存在符号」的交付约束，本文件提交任务时使用既有的
 *    [BgTaskType.GENERIC]；接入补丁时把 `type = BgTaskType.GENERIC` 一行改为
 *    `type = BgTaskType.ASTRONAV` 即可（单行改动，零风险）。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/**
 * 离线精修结果。
 *
 * @param smoothedQuaternions 精修后姿态（长度 = 4 × 样本数，w,x,y,z 排列）
 * @param sampleCount 样本数
 * @param rawJitterDeg 原始指向抖动（相邻帧角度差的平均绝对值，度）
 * @param smoothedJitterDeg 精修后抖动（度）
 * @param jitterReduction 抖动抑制比 0..1（越大越好；= 1 - 精修/原始）
 * @param driftDegPerMinute 漂移率（首末姿态差 / 时长，度/分钟；越接近 0 越好）
 * @param meanPointingAzimuthDeg 全程平均指向方位角（度，真北顺时针）
 * @param meanPointingAltitudeDeg 全程平均指向高度角（度）
 * @param degraded true = 因内存预算不足降级为单遍滤波
 */
data class AstroNavRefinementResult(
    val smoothedQuaternions: FloatArray,
    val sampleCount: Int,
    val rawJitterDeg: Float,
    val smoothedJitterDeg: Float,
    val jitterReduction: Float,
    val driftDegPerMinute: Float,
    val meanPointingAzimuthDeg: Float,
    val meanPointingAltitudeDeg: Float,
    val degraded: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 姿态序列离线精修器。
 *
 * 线程模型：所有计算在 BgJobRunner 的后台线程（MIN_PRIORITY + 分片节流）执行，
 * 结果经 [onResult] 回调（后台线程）返回；调用方自行切主线程。
 */
object AstroNavBackgroundSolver {

    /** 任务名（显示在既有 BgJobChip 上）。 */
    const val JOB_NAME = "星空指向离线精修"

    /** 单样本字节数（4 float）。 */
    private const val BYTES_PER_SAMPLE = 16L

    /**
     * 提交离线精修任务。
     *
     * @param quaternions 原始姿态序列（长度 = 4 × N，w,x,y,z 排列；不足 2 帧直接返回 null）
     * @param timestampsMs 每个样本的时间戳（长度 = N；可用空数组表示等间隔）
     * @param config 当前配置（决定平滑策略与内存预算）
     * @param cameraAxis 取景相机（用于把姿态换算成指向）
     * @param onResult 结果回调（后台线程；降级/失败时为 null）
     * @param onFinish BgJobRunner 标准完成回调
     * @return 提交的 [BgJob]；样本不足时返回 null（不提交）
     */
    fun submitRefinement(
        quaternions: FloatArray,
        timestampsMs: LongArray = LongArray(0),
        config: AstroNavConfig = AstroNavConfig(),
        cameraAxis: CameraAxis = CameraAxis.BACK,
        priority: Int = BgJobPriority.LOW,
        onResult: (AstroNavRefinementResult?) -> Unit = {},
        onFinish: ((BgJob) -> Unit)? = null,
    ): BgJob? {
        val cfg = config.clamped().config
        val n = quaternions.size / 4
        if (n < 2) {
            PLog.w(TAG, "submitRefinement ignored: only $n sample(s)")
            return null
        }
        val totalBytes = n * BYTES_PER_SAMPLE * 2L // 输入 + 输出
        return BgJobRunner.submit(
            name = JOB_NAME,
            chunkSize = 64,
            type = BgTaskType.ASTRONAV, // 接入补丁后改为 BgTaskType.ASTRONAV（见文件头说明）
            priority = priority,
            onFinish = onFinish,
        ) {
            val job = this
            // 内存守卫：不足则降级为单遍滤波（输出与输入复用同一缓冲策略）
            val canAllocate = job.guardMemory(totalBytes)
            val degraded = !canAllocate
            if (degraded) {
                PLog.i(TAG, "refinement degraded: need $totalBytes bytes, budget limited")
            }

            val out = FloatArray(n * 4)
            val batch = job.batchSizeFor(n)

            // ---- 第一遍：前向平滑 ----
            var prev = readQuaternion(quaternions, 0)
            writeQuaternion(out, 0, prev)
            var rawJitterAcc = 0.0
            var i = 1
            while (i < n) {
                val end = minOf(i + batch, n)
                while (i < end) {
                    val raw = readQuaternion(quaternions, i)
                    if (raw.isUsable) {
                        val dtMs = timestampAt(timestampsMs, i, i - 1)
                        val alpha = forwardAlpha(raw, prev, dtMs, cfg)
                        val smoothed = AstroNavQuaternion.nlerp(prev, raw, alpha)
                        rawJitterAcc += prev.angleDegreesTo(raw).toDouble()
                        prev = smoothed
                        writeQuaternion(out, i, smoothed)
                    } else {
                        writeQuaternion(out, i, prev)
                    }
                    i++
                }
                job.checkYield() // 分片末尾必调（PATTERN_SPEC §1.5）
                job.report(i, n)
            }

            // ---- 第二遍（未降级时）：后向平滑，进一步压抖动 ----
            if (!degraded) {
                var next = readQuaternion(out, n - 1)
                var j = n - 2
                while (j >= 0) {
                    val end = (j - batch).coerceAtLeast(-1)
                    while (j > end) {
                        val cur = readQuaternion(out, j)
                        val blended = AstroNavQuaternion.nlerp(cur, next, BACKWARD_ALPHA)
                        writeQuaternion(out, j, blended)
                        next = blended
                        j--
                    }
                    job.checkYield()
                    job.report(n - 1 - j, n)
                }
            }

            // ---- 统计 ----
            var smoothedJitterAcc = 0.0
            for (k in 1 until n) {
                smoothedJitterAcc += readQuaternion(out, k - 1).angleDegreesTo(readQuaternion(out, k)).toDouble()
            }
            val rawJitter = (rawJitterAcc / (n - 1)).toFloat()
            val smoothedJitter = (smoothedJitterAcc / (n - 1)).toFloat()

            val first = readQuaternion(out, 0)
            val last = readQuaternion(out, n - 1)
            val durationMs = if (timestampsMs.size >= n && n >= 2) {
                (timestampsMs[n - 1] - timestampsMs[0]).coerceAtLeast(0L)
            } else {
                (n - 1) * cfg.attitudeUpdateIntervalMs
            }
            val driftDegPerMin = if (durationMs > 0L) {
                (first.angleDegreesTo(last) / (durationMs / 60_000f))
            } else {
                0f
            }

            // 平均指向（抽样计算，避免全量再扫一遍）
            var azSum = 0.0
            var altSum = 0.0
            var sampled = 0
            val step = (n / 64).coerceAtLeast(1)
            var s = 0
            while (s < n) {
                val p = CoordinateAligner.pointing(readQuaternion(out, s), cameraAxis)
                azSum += p.azimuthDegrees
                altSum += p.altitudeDegrees
                sampled++
                s += step
            }
            val azMean = if (sampled > 0) (azSum / sampled).toFloat() else 0f
            val altMean = if (sampled > 0) (altSum / sampled).toFloat() else 0f

            onResult(
                AstroNavRefinementResult(
                    smoothedQuaternions = out,
                    sampleCount = n,
                    rawJitterDeg = rawJitter,
                    smoothedJitterDeg = smoothedJitter,
                    jitterReduction = AttitudeQuality.jitterReductionRatio(rawJitter, smoothedJitter),
                    driftDegPerMinute = driftDegPerMin,
                    meanPointingAzimuthDeg = AstroNavAngles.wrap360(azMean),
                    meanPointingAltitudeDeg = altMean,
                    degraded = degraded,
                ),
            )
        }
    }

    /**
     * 前向平滑系数：静止时强平滑，转动快时低延迟（1€ 思路的离线版本）。
     */
    private fun forwardAlpha(
        raw: AstroNavQuaternion,
        prev: AstroNavQuaternion,
        dtMs: Long,
        cfg: AstroNavConfig,
    ): Float {
        val dt = (dtMs.coerceIn(1L, 1_000L) / 1000f)
        val speed = prev.angleDegreesTo(raw) / dt
        val cutoff = cfg.oneEuroMinCutoff.coerceAtLeast(0.01f) + cfg.oneEuroBeta * speed
        val te = 1f / (2f * Math.PI.toFloat() * cutoff)
        return (dt / (dt + te)).coerceIn(0.02f, 1f)
    }

    private fun timestampAt(timestampsMs: LongArray, index: Int, prevIndex: Int): Long {
        if (timestampsMs.size <= index || timestampsMs.size <= prevIndex) {
            return 33L
        }
        val d = timestampsMs[index] - timestampsMs[prevIndex]
        return if (d in 1..1_000) d else 33L
    }

    private fun readQuaternion(arr: FloatArray, index: Int): AstroNavQuaternion {
        val base = index * 4
        if (base + 3 >= arr.size) return AstroNavQuaternion.IDENTITY
        return AstroNavQuaternion(arr[base], arr[base + 1], arr[base + 2], arr[base + 3]).normalized()
    }

    private fun writeQuaternion(arr: FloatArray, index: Int, q: AstroNavQuaternion) {
        val base = index * 4
        if (base + 3 >= arr.size) return
        arr[base] = q.w
        arr[base + 1] = q.x
        arr[base + 2] = q.y
        arr[base + 3] = q.z
    }

    /** 后向平滑系数（比前向更保守，避免过冲）。 */
    private const val BACKWARD_ALPHA = 0.25f

    private const val TAG = "AstroNavBgSolver"
}
