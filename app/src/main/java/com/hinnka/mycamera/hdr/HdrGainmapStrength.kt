package com.hinnka.mycamera.hdr

object HdrGainmapStrength {
    const val MIN = 0.25f
    const val DEFAULT = 1.0f
    const val MAX = 2.0f
    /** 运行时上限（默认 2.0f 与原 MAX 一致，零回归）。 */
    @Volatile
    var userMax: Float = MAX

    fun maxStrength(): Float = userMax.coerceIn(MIN, 4f)

    fun coerce(value: Float?): Float {
        return (value ?: DEFAULT).coerceIn(MIN, maxStrength())
    }

    fun applyToRatio(ratio: Float, minGainRatio: Float, maxGainRatio: Float, strength: Float): Float {
        val normalizedStrength = coerce(strength)
        return (1f + (ratio - 1f) * normalizedStrength).coerceIn(minGainRatio, maxGainRatio)
    }
}
