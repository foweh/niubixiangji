package com.hinnka.mycamera.funshot

import kotlin.math.max
import kotlin.math.sqrt

/** 全景拼接结果（[argb] 为画布像素；内存不足时通过降分辨率而非拒绝来保证仍有产物）。 */
data class FunshotPanoramaResult(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    val usedFrames: Int,
    val droppedFrames: Int,
    val degraded: Boolean,
    val notes: List<String>,
) {
    val pixelCount: Int get() = width * height
}

/**
 * 全景拼接会话（流式，逐帧 ingest）。
 *
 * 管线（参考 OpenCV `Stitcher` 的成熟顺序，**实现自研**）：
 * ```
 * 帧 → 工作分辨率降级 → 柱面/平面 → Harris 角点 → BRIEF 描述子
 *    → 与前一帧暴力匹配 → RANSAC 单应 (H_pre,cur)
 *    → 以中间帧为参考串链 H_ref,i
 *    → 包围盒 + 输出分辨率治理 → 逐帧曝光补偿 + 距离羽化融合 → 空洞填充 → ARGB
 * ```
 *
 * 内存策略：入会前按「帧数 × 单帧字节」估算，[guard] 不通过就**先降工作分辨率**
 * （所有帧统一，保证尺度一致）；画布分配前再守一次，可继续降输出分辨率。
 * 无论如何都产出可用结果，不因内存拒绝任务。
 */
class FunshotPanoramaSession(
    private val config: PanoramaConfig,
    private val checkpoint: FunshotCheckpoint = FunshotCheckpoint { },
    private val guard: FunshotMemoryGuard = FunshotMemoryGuard { true },
) {

    private class PreparedFrame(
        val warped: FunWarpedFrame,
        val gray: FunGrayImage,
        val keyPoints: List<FunKeyPoint>,
        val descriptors: Array<LongArray>,
    )

    private val effectiveConfig = config.coerced()
    private val prepared = ArrayList<PreparedFrame>()
    /** pairwise[i] = H(frames[i-1] ← frames[i])，仅在 i>=1 且估计成功时有值。 */
    private val pairwise = ArrayList<FunMatrix3?>()
    private val notes = ArrayList<String>()
    private var dropped = 0
    private var degraded = false
    private var workingScale = effectiveConfig.workingScale

    val frameCount: Int get() = prepared.size

    /** 预先按工作分辨率预估内存；不通过则统一降级（不拒绝）。 */
    fun prepare(referenceWidth: Int, referenceHeight: Int) {
        var scale = workingScale
        while (true) {
            val estimate = estimateFrameStoreBytes(referenceWidth, referenceHeight, scale)
            if (guard.allow(estimate) || scale == FunshotWorkingScale.HALF) break
            scale = when (scale) {
                FunshotWorkingScale.FULL -> FunshotWorkingScale.THREE_QUARTER
                FunshotWorkingScale.THREE_QUARTER -> FunshotWorkingScale.HALF
                FunshotWorkingScale.HALF -> FunshotWorkingScale.HALF
            }
        }
        if (scale != workingScale) {
            workingScale = scale
            degraded = true
            notes += "workingScale degraded to ${scale.name} (memory guard)"
        }
    }

    private fun estimateFrameStoreBytes(width: Int, height: Int, scale: FunshotWorkingScale): Long {
        val w = (width * scale.factor).toLong().coerceAtLeast(1)
        val h = (height * scale.factor).toLong().coerceAtLeast(1)
        // ARGB(4) + valid(1) + gray(4) + 余量 ≈ 10 字节/像素，再乘帧数。
        return w * h * 10L * effectiveConfig.frameCount.coerceAtLeast(1)
    }

    /**
     * 送入一帧。超过 [PanoramaConfig.frameCount] 的帧被忽略（计入 dropped）；
     * 与前一帧匹配/估计失败时该帧被丢弃（断链不入画布），**不抛异常**。
     */
    fun ingest(frame: FunArgbFrame) {
        if (prepared.isNotEmpty() && prepared.size >= effectiveConfig.frameCount) {
            dropped++
            return
        }
        if (prepared.isEmpty()) prepare(frame.width, frame.height)

        val scaled = FunArgbScaler.scale(
            FunArgbScaler.limitLongSide(frame, effectiveConfig.inputMaxSide),
            workingScale.factor,
        )
        val warped = when (effectiveConfig.quality) {
            PanoramaQuality.CYLINDRICAL ->
                FunCylindricalProjection.project(scaled, effectiveConfig.effectiveFocalPx(scaled.width, scaled.height))
            PanoramaQuality.PLANAR ->
                FunWarpedFrame(scaled.width, scaled.height, scaled.argb, BooleanArray(scaled.width * scaled.height) { true })
        }

        val gray = FunGrayImage(
            warped.width,
            warped.height,
            FloatArray(warped.width * warped.height) { i ->
                val c = warped.argb[i]
                if (!warped.valid[i]) 0f
                else {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    0.299f * r + 0.587f * g + 0.114f * b
                }
            },
        )

        val corners = FunPanoramaBackend.detectCorners(
            gray,
            effectiveConfig.maxCorners,
            effectiveConfig.cornerQuality,
            effectiveConfig.minCornerDistance,
        ).filter { kp ->
            val x = kp.x.toInt()
            val y = kp.y.toInt()
            x in 0 until warped.width && y in 0 until warped.height && warped.valid[y * warped.width + x]
        }
        val descriptors = FunPanoramaBackend.describe(gray, corners, effectiveConfig.descriptorBits, effectiveConfig.randomSeed)
        val framePrepared = PreparedFrame(warped, gray, corners, descriptors)

        if (prepared.isEmpty()) {
            prepared += framePrepared
            pairwise += null
            return
        }

        val previous = prepared.last()
        val matches = FunPanoramaBackend.match(
            descA = previous.descriptors,
            descB = framePrepared.descriptors,
            ratio = effectiveConfig.matchRatio,
            maxMatches = minOf(previous.keyPoints.size, framePrepared.keyPoints.size, effectiveConfig.maxCorners),
        )
        if (matches.size < 4) {
            dropped++
            notes += "frame ${prepared.size} dropped: only ${matches.size} matches"
            return
        }
        val srcPoints = matches.map { FunPointF(framePrepared.keyPoints[it.indexB].x.toDouble(), framePrepared.keyPoints[it.indexB].y.toDouble()) }
        val dstPoints = matches.map { FunPointF(previous.keyPoints[it.indexA].x.toDouble(), previous.keyPoints[it.indexA].y.toDouble()) }
        val result = FunPanoramaBackend.estimateHomography(
            src = srcPoints,
            dst = dstPoints,
            iterations = effectiveConfig.ransacIterations,
            thresholdPx = effectiveConfig.ransacThresholdPx.toDouble(),
            minInliers = effectiveConfig.minInliers,
            minInlierRatio = effectiveConfig.minInlierRatio.toDouble(),
            seed = effectiveConfig.randomSeed + prepared.size,
            checkpoint = checkpoint,
        )
        val homography = result.homography
        if (!result.success || homography == null) {
            dropped++
            notes += "frame ${prepared.size} dropped: ${result.message}"
            return
        }
        prepared += framePrepared
        pairwise += homography
        checkpoint.checkpoint()
    }

    /** 完成拼接并输出画布；不足 2 帧时直接返回该帧（附说明）。 */
    fun finish(): FunshotPanoramaResult {
        if (prepared.isEmpty()) {
            return FunshotPanoramaResult(IntArray(0), 0, 0, 0, dropped, degraded, notes + "no input frames")
        }
        if (prepared.size == 1) {
            val only = prepared[0].warped
            return FunshotPanoramaResult(
                argb = only.argb.copyOf(),
                width = only.width,
                height = only.height,
                usedFrames = 1,
                droppedFrames = dropped,
                degraded = degraded,
                notes = notes + "single frame: stitching needs >= 2 frames",
            )
        }

        val reference = prepared.size / 2
        val chain = chainToReference(reference) ?: run {
            val only = prepared[reference].warped
            return FunshotPanoramaResult(
                only.argb.copyOf(), only.width, only.height, 1, dropped, true,
                notes + "homography chain failed; returned reference frame",
            )
        }

        val frameWidth = prepared[0].warped.width
        val frameHeight = prepared[0].warped.height
        val bounds = FunBlendMath.canvasBounds(frameWidth, frameHeight, chain, FunMatrix3.identity())
        if (bounds == null) {
            val only = prepared[reference].warped
            return FunshotPanoramaResult(
                only.argb.copyOf(), only.width, only.height, 1, dropped, true,
                notes + "invalid canvas bounds; returned reference frame",
            )
        }

        val outputScale = chooseOutputScale(bounds)
        if (outputScale < 0.999) degraded = true

        val canvasWidth = max(1, (bounds.width * outputScale).toInt())
        val canvasHeight = max(1, (bounds.height * outputScale).toInt())
        val canvasBytes = canvasWidth.toLong() * canvasHeight.toLong() * 16L
        if (!guard.allow(canvasBytes)) {
            notes += "canvas ${canvasWidth}x$canvasHeight exceeds memory guard; result may be partial"
        }

        val project = FunMatrix3.translation(-bounds.left * outputScale, -bounds.top * outputScale) *
            FunMatrix3.scale(outputScale.toDouble(), outputScale.toDouble())

        val referenceLuma = FunBlendMath.meanLuma(prepared[reference].warped.argb, prepared[reference].warped.valid)
        val canvas = FunArgbCanvas(canvasWidth, canvasHeight)
        var used = 0
        for (i in prepared.indices) {
            val h = chain[i] ?: continue
            val warped = prepared[i].warped
            val gain = FunExposureCompensator.gainFor(
                warped,
                referenceLuma,
                effectiveConfig.exposureCompensation,
                effectiveConfig.gainLimit,
            )
            if (canvas.addWarped(warped, project * h, gain, effectiveConfig.featherRadiusPx, effectiveConfig.blendGamma)) {
                used++
            }
            checkpoint.checkpoint()
        }
        val argb = canvas.toArgb()
        if (used < prepared.size) notes += "merged $used/${prepared.size} frames"
        return FunshotPanoramaResult(
            argb = argb,
            width = canvasWidth,
            height = canvasHeight,
            usedFrames = used,
            droppedFrames = dropped,
            degraded = degraded,
            notes = notes,
        )
    }

    /** 输出分辨率治理：受 [PanoramaConfig.maxOutputPixels] 与内存守卫双重约束（只降不拒）。 */
    private fun chooseOutputScale(bounds: FunCanvasBounds): Double {
        var scale = 1.0
        val maxPixels = effectiveConfig.maxOutputPixels.coerceAtLeast(1L).toDouble()
        val pixels = bounds.width.toDouble() * bounds.height.toDouble()
        if (pixels > maxPixels) {
            scale = sqrt(maxPixels / pixels)
            notes += "output scale limited to ${"%.2f".format(scale)} by maxOutputPixels"
        }
        val steps = doubleArrayOf(0.75, 0.6, 0.5, 0.35, 0.25)
        for (step in steps) {
            if (step >= scale) continue
            val bytes = (bounds.width * step).toLong() * (bounds.height * step).toLong() * 16L
            if (guard.allow(bytes)) return step
        }
        // 最低 0.25 倍仍返回可用结果，不做拒绝。
        return minOf(scale, 0.25)
    }

    /**
     * 以 [reference] 为基准串链：`H(ref ← i)`。
     * 约定 `p_a = H(a ← b) · p_b`；`pairwise[k] = H(k-1 ← k)`。
     */
    private fun chainToReference(reference: Int): List<FunMatrix3?>? {
        val n = prepared.size
        val out = arrayOfNulls<FunMatrix3>(n)
        out[reference] = FunMatrix3.identity()
        // 向右（i > reference）：H(ref ← i) = H(ref ← ref+1) · … · H(i-1 ← i)
        var acc: FunMatrix3? = FunMatrix3.identity()
        for (i in reference + 1 until n) {
            val step = pairwise[i] ?: return null
            acc = acc?.times(step)
            if (acc == null) return null
            out[i] = acc
        }
        // 向左（i < reference）：先算 H(i ← ref) 再取逆。
        // H(i ← ref) = H(i ← i+1) · … · H(ref-1 ← ref)，左侧为更小的 k，故逐步**左乘**。
        var accLeft: FunMatrix3? = FunMatrix3.identity()
        for (i in reference - 1 downTo 0) {
            val step = pairwise[i + 1] ?: return null
            accLeft = accLeft?.let { step.times(it) }
            if (accLeft == null) return null
            out[i] = accLeft?.inverse()
            if (out[i] == null) return null
        }
        return out.toList()
    }

    companion object {
        /** 一次性拼接（测试与后台任务共用；等价于 `prepare → ingest* → finish`）。 */
        fun stitch(
            frames: List<FunArgbFrame>,
            config: PanoramaConfig,
            checkpoint: FunshotCheckpoint = FunshotCheckpoint { },
            guard: FunshotMemoryGuard = FunshotMemoryGuard { true },
        ): FunshotPanoramaResult {
            val session = FunshotPanoramaSession(config, checkpoint, guard)
            frames.forEach { session.ingest(it) }
            return session.finish()
        }
    }
}
