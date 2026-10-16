package com.hinnka.mycamera.funshot

import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 一次抽帧请求（时间戳 + 输出尺寸 + 该帧在 GIF 中的延迟）。 */
data class FunVideoFrameRequest(
    val timestampMs: Long,
    val outWidth: Int,
    val outHeight: Int,
    val delayCentis: Int,
)

/** 抽帧计划（[error] 非空表示元数据非法，计划为空）。 */
data class FunVideoSamplePlan(val frames: List<FunVideoFrameRequest>, val error: String? = null) {
    val isEmpty: Boolean get() = frames.isEmpty()
    val size: Int get() = frames.size
}

/** 抽帧器抽象（Android 侧由 `MediaMetadataRetriever` 实现，纯逻辑侧可注入假实现做单测）。 */
fun interface FunVideoFrameGrabber {
    /** @return 行主序 ARGB（长度 = outWidth × outHeight），取不到返回 null */
    fun frameAt(timestampMs: Long, outWidth: Int, outHeight: Int): IntArray?
}

/**
 * 视频抽帧计划器（**纯逻辑**，可单测）。
 *
 * 规则：
 *  - 起止时间 clamp 到 `[0, durationMs]`；[GifConfig.videoEndMs] 为 0 或超长 = 到结尾；
 *  - 按 [GifConfig.videoFrameRate] 等间隔取帧；帧数超 [GifConfig.videoMaxFrames] 时改为
 *    在区间内**等间隔重采样**（首尾都保留），不丢区间信息；
 *  - 输出尺寸按 [GifConfig.videoTargetWidth] 等比缩放（不放大），并受 [GifConfig.maxWidth] /
 *    [GifConfig.maxHeight] 二次约束；
 *  - 延迟 = 采样间隔（GIF 单位 1/100 秒，下限 2）；末帧叠加 [GifConfig.holdLastFrameMs]。
 */
object FunVideoFrameSampler {

    fun plan(videoWidth: Int, videoHeight: Int, durationMs: Long, config: GifConfig): FunVideoSamplePlan {
        val effective = config.coerceWithReport().value
        if (videoWidth <= 0 || videoHeight <= 0) {
            return FunVideoSamplePlan(emptyList(), "invalid video size ${videoWidth}x$videoHeight")
        }
        if (durationMs <= 0L) {
            return FunVideoSamplePlan(emptyList(), "invalid video duration $durationMs ms")
        }

        val startMs = effective.videoStartMs.coerceIn(0L, durationMs)
        val endMs = when {
            effective.videoEndMs <= 0L -> durationMs
            effective.videoEndMs >= durationMs -> durationMs
            else -> effective.videoEndMs.coerceIn(startMs + 1L, durationMs)
        }
        if (endMs <= startMs) {
            return FunVideoSamplePlan(emptyList(), "empty range [$startMs, $endMs] ms")
        }

        val (outWidth, outHeight) = GifOutputSize.fit(
            videoWidth,
            videoHeight,
            minOf(effective.videoTargetWidth, effective.maxWidth),
            effective.maxHeight,
        )

        val span = endMs - startMs
        val nominalCount = (span.toDouble() * effective.videoFrameRate / 1000.0).toInt() + 1
        val count = nominalCount.coerceIn(1, effective.videoMaxFrames)
        val intervalMs = if (count <= 1) span.toDouble() else (span.toDouble() / (count - 1)).round()
        val delayCentis = max(2, (intervalMs / 10.0).roundToInt())

        val frames = ArrayList<FunVideoFrameRequest>(count)
        for (i in 0 until count) {
            val timestamp = if (count <= 1) startMs else (startMs + (intervalMs * i)).roundToLong()
            val clampedTs = timestamp.coerceIn(startMs, endMs)
            val isLast = i == count - 1
            val delay = if (isLast) {
                max(2, ((intervalMs + effective.holdLastFrameMs) / 10.0).roundToInt())
            } else {
                delayCentis
            }
            frames += FunVideoFrameRequest(clampedTs, outWidth, outHeight, delay)
        }
        return FunVideoSamplePlan(frames)
    }
}
