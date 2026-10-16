package com.hinnka.mycamera.camera

import kotlin.math.roundToInt
import kotlin.math.ln

object MultiFrameConfig {
    /**
     * Controls the post-Spatial MGC luma/chroma pass as one closed stage.
     * Spatial fusion and Bayer/RGB materialization remain active when disabled.
     */
    const val ENABLE_MGC_SPATIAL_DEFAULT_DENOISE = true

    const val MIN_DENOISE_FRAME_COUNT = 3
    const val DEFAULT_DENOISE_FRAME_COUNT = 5
    const val MIN_HDR_PLUS_FRAME_COUNT = 1
    const val MIN_HDR_PLUS_BRACKET_FRAME_COUNT = 2
    const val DEFAULT_HDR_PLUS_FRAME_COUNT = 3
    const val MAX_FRAME_COUNT = 20
    /** 帧数推荐档位上限（UI 滑杆默认刻度顶值）。默认 20 = 原 MAX_FRAME_COUNT，行为不变。 */
    const val RECOMMENDED_FRAME_COUNT_MAX = 20

    /** 帧数绝对安全上限：仅防误设/溢出，**不做总量截断**（资源问题交给分片 + 内存守卫）。 */
    const val ABSOLUTE_FRAME_COUNT_MAX = 4096

    /**
     * 运行时帧数上限（用户可配）。默认 = MAX_FRAME_COUNT（20），零回归；
     * 由设置页/DataStore 上调后才放大，clamp 到 [MIN_DENOISE_FRAME_COUNT, ABSOLUTE_FRAME_COUNT_MAX]。
     */
    @Volatile
    var userFrameCountLimit: Int = MAX_FRAME_COUNT

    /** 当前生效的帧数上限（clamp 后；调用方勿再各自 coerceIn 魔法数）。 */
    fun frameCountLimit(): Int =
        userFrameCountLimit.coerceIn(MIN_DENOISE_FRAME_COUNT, ABSOLUTE_FRAME_COUNT_MAX)
    const val DEFAULT_HDR_PLUS_BRACKET_EXPOSURE = false
    /**
     * PS/天文堆栈默认是否做全局平移对齐。
     * 默认 false：主流程为三脚架固定拍摄（星轨/星点的位移本身就是要保留的结果）；
     * 手持多帧降噪想要对齐时，由 UI 开关或本常量统一上调（三处调用点共用此真值源）。
     */
    const val DEFAULT_STACK_ALIGN = false
    const val MIN_OUTPUT_SCALE = 1f
    const val MAX_OUTPUT_SCALE = 2f
    /** 输出倍率运行时上限（默认 2f 保持既有行为；上调后由内存守卫/分片保障）。 */
    @Volatile
    var userMaxOutputScale: Float = MAX_OUTPUT_SCALE
    fun maxOutputScale(): Float = userMaxOutputScale.coerceIn(MIN_OUTPUT_SCALE, 4f)
    const val DEFAULT_SUPER_RESOLUTION_SCALE = 1f
    const val SHORT_FRAME_COUNT = 1
    /** 短帧数量（默认 1；开放后由总帧数比例约束，clamp 到总数-2 以内）。 */
    @Volatile
    var userShortFrameCount: Int = SHORT_FRAME_COUNT
    fun shortFrameCount(total: Int): Int =
        userShortFrameCount.coerceIn(0, (normalizeHdrPlusFrameCount(total) - 2).coerceAtLeast(0))
    const val SHORT_FRAME_EXPOSURE_DIVISOR = 3.0
    val DEFAULT_SHORT_FRAME_EXPOSURE_EV = (-ln(SHORT_FRAME_EXPOSURE_DIVISOR) / ln(2.0)).toFloat()
    const val MIN_SHORT_FRAME_EXPOSURE_EV = -4f
    const val MAX_SHORT_FRAME_EXPOSURE_EV = 0f
    const val LONG_FRAME_COUNT_DIVISOR = 4
    @Volatile
    var userLongFrameCountDivisor: Int = LONG_FRAME_COUNT_DIVISOR
    fun longFrameCountDivisor(): Int = userLongFrameCountDivisor.coerceIn(1, 16)
    const val MIN_LONG_FRAME_COUNT = 1
    const val LONG_FRAME_EXPOSURE_EV = 2.5
    const val MIN_LONG_FRAME_EXPOSURE_EV = 0f
    const val MAX_LONG_FRAME_EXPOSURE_EV = 4f
    const val LONG_FRAME_MAX_EXPOSURE_TIME_NS = 10_000_000L
    /** 长帧快门名义上限（默认 10ms 保持既有行为；可上调到 sensor 上限，由 clamp 保障）。 */
    @Volatile
    var userLongFrameMaxExposureTimeNs: Long = LONG_FRAME_MAX_EXPOSURE_TIME_NS
    fun longFrameMaxExposureTimeNs(): Long =
        userLongFrameMaxExposureTimeNs.coerceIn(1_000_000L, 10_000_000_000L)
    const val LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY = 800
    @Volatile
    var userLongFrameFallbackMaxIso: Int = LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY
    fun longFrameFallbackMaxIso(): Int = userLongFrameFallbackMaxIso.coerceIn(100, 102_400)

    fun normalizeShortFrameExposureEv(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_SHORT_FRAME_EXPOSURE_EV, MAX_SHORT_FRAME_EXPOSURE_EV)
        else DEFAULT_SHORT_FRAME_EXPOSURE_EV

    fun normalizeLongFrameExposureEv(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_LONG_FRAME_EXPOSURE_EV, MAX_LONG_FRAME_EXPOSURE_EV)
        else LONG_FRAME_EXPOSURE_EV.toFloat()

    fun normalizeDenoiseFrameCount(
        frameCount: Int,
        limit: Int = frameCountLimit(),
    ): Int {
        return frameCount.coerceIn(MIN_DENOISE_FRAME_COUNT, limit)
    }

    fun normalizeHdrPlusFrameCount(
        frameCount: Int,
        bracketExposureEnabled: Boolean = false,
        limit: Int = frameCountLimit(),
    ): Int {
        val minimumFrameCount = if (bracketExposureEnabled) {
            MIN_HDR_PLUS_BRACKET_FRAME_COUNT
        } else {
            MIN_HDR_PLUS_FRAME_COUNT
        }
        return frameCount.coerceIn(minimumFrameCount, limit)
    }

    fun normalizeOutputScale(
        outputScale: Float,
        fallback: Float = MIN_OUTPUT_SCALE,
    ): Float {
        val normalizedFallback = if (fallback.isFinite()) {
            fallback.coerceIn(MIN_OUTPUT_SCALE, maxOutputScale())
        } else {
            MIN_OUTPUT_SCALE
        }
        return if (outputScale.isFinite()) {
            outputScale.coerceIn(MIN_OUTPUT_SCALE, maxOutputScale())
        } else {
            normalizedFallback
        }
    }

    fun scaledRawOutputDimension(size: Int, outputScale: Float): Int {
        val normalizedScale = normalizeOutputScale(outputScale)
        val scaled = (size.coerceAtLeast(1).toFloat() * normalizedScale)
            .roundToInt()
            .coerceAtLeast(1)
        return if (normalizedScale > MIN_OUTPUT_SCALE && scaled > 1 && scaled % 2 != 0) {
            scaled - 1
        } else {
            scaled
        }
    }

    fun hdrPlusNormalFrameCount(totalFrameCount: Int): Int {
        val normalizedFrameCount = normalizeHdrPlusFrameCount(totalFrameCount)
        return when (normalizedFrameCount) {
            1, 2 -> 1
            else -> normalizedFrameCount -
                hdrPlusShortFrameCount(normalizedFrameCount) -
                hdrPlusLongFrameCount(normalizedFrameCount)
        }
    }

    fun hdrPlusShortFrameCount(totalFrameCount: Int): Int {
        return if (normalizeHdrPlusFrameCount(totalFrameCount) >= 3) SHORT_FRAME_COUNT else 0
    }

    fun hdrPlusLongFrameCount(totalFrameCount: Int): Int {
        return when (val normalizedFrameCount = normalizeHdrPlusFrameCount(totalFrameCount)) {
            1 -> 0
            2 -> 1
            else -> (normalizedFrameCount / LONG_FRAME_COUNT_DIVISOR)
                .coerceAtLeast(MIN_LONG_FRAME_COUNT)
        }
    }

    fun hdrPlusCaptureFrameCount(totalFrameCount: Int): Int {
        val normalizedFrameCount = normalizeHdrPlusFrameCount(totalFrameCount)
        return hdrPlusNormalFrameCount(normalizedFrameCount) +
            hdrPlusShortFrameCount(normalizedFrameCount) +
            hdrPlusLongFrameCount(normalizedFrameCount)
    }
}
