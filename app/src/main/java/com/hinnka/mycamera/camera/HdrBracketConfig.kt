package com.hinnka.mycamera.camera

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object HdrBracketConfig {
    const val YUV_LONG_EV = 2.2f
    /** HDR 包围 EV（默认＝原常量，零回归）。 */
    @Volatile
    var userYuvLongEv: Float = YUV_LONG_EV
    @Volatile
    var userYuvShortEv: Float = YUV_SHORT_EV
    @Volatile
    var userYuvLongMaxShutterNs: Long = YUV_LONG_MAX_SHUTTER_NS
    fun yuvShortEv(): Float = userYuvShortEv.coerceIn(-4f, -0.5f)
    fun yuvLongMaxShutterNs(): Long = userYuvLongMaxShutterNs.coerceIn(1_000_000L, 10_000_000_000L)
    /** 长帧 EV（提升后由 yuvLongMaxShutterNs() 兜底快门上限，避免拉爆长曝时间）。 */
    fun yuvLongEv(): Float = userYuvLongEv.coerceIn(0.5f, 4f)
    const val YUV_SHORT_EV = -1.5f
    const val YUV_LONG_MAX_SHUTTER_NS = 10_000_000L

    fun planManualExposure(
        baseIso: Int,
        baseShutterNs: Long,
        evOffset: Float,
        isoLower: Int,
        isoUpper: Int,
        shutterLowerNs: Long,
        shutterUpperNs: Long,
    ): Pair<Int, Long> {
        val multiplier = 2.0.pow(evOffset.toDouble())
        val targetProduct = baseIso.toDouble() * baseShutterNs.toDouble() * multiplier
        val effectiveShutterUpperNs = if (evOffset > 0f) {
            minOf(shutterUpperNs, yuvLongMaxShutterNs()).coerceAtLeast(shutterLowerNs)
        } else {
            shutterUpperNs
        }
        val shutterFirst = (baseShutterNs.toDouble() * multiplier)
            .roundToLong()
            .coerceIn(shutterLowerNs, effectiveShutterUpperNs)
        val isoForShutter = (targetProduct / shutterFirst.toDouble())
            .roundToInt()
            .coerceIn(isoLower, isoUpper)
        val shutterForIso = (targetProduct / isoForShutter.toDouble())
            .roundToLong()
            .coerceIn(shutterLowerNs, effectiveShutterUpperNs)

        return Pair(isoForShutter, shutterForIso)
    }
}
