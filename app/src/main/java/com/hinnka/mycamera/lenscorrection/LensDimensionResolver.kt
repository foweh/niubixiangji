package com.hinnka.mycamera.lenscorrection

/**
 * 相机维度解析：由镜头等效焦距判断当前用的是哪颗镜头，从而取对应的畸变预设。
 *
 * 目标机 K50G 的三颗后摄等效焦距大致为：超广角 ≈ 16mm、主摄 ≈ 26mm、微距 ≈ 50mm+。
 * 阈值取在档位之间并留足容差，避免边界抖动导致预设来回切换。
 *
 * 纯逻辑（无 Android 依赖），可直接 JVM 单测。集成时可直接用
 * `Camera2Controller` 已解析出的等效焦距调用 [fromEquivalentFocalLengthMm]；
 * 若拿不到焦距，用 [fromZoomRatio] 兜底（倍率 < 1 一定是超广角）。
 */
object LensDimensionResolver {

    /** 小于该等效焦距视为超广角（16mm 与 26mm 的中点偏主摄侧）。 */
    const val ULTRA_WIDE_MAX_MM = 20f

    /** 小于该等效焦距视为主摄（26mm 与 50mm 的中点）。 */
    const val MAIN_MAX_MM = 38f

    /** 变焦倍率低于该值（即 1.0x 以下）必然是超广角副摄。 */
    const val ULTRA_WIDE_MAX_ZOOM = 1.0f

    /**
     * 由等效焦距（mm）解析相机维度。
     * @param equivalentFocalLengthMm null / 非有限值 / 非正值 → 回退 [LensCameraDimension.MAIN]
     *   （最保守的一档：主摄畸变最小，即使判错也不会把主摄过度校正）。
     */
    fun fromEquivalentFocalLengthMm(equivalentFocalLengthMm: Float?): LensCameraDimension {
        val mm = equivalentFocalLengthMm ?: return LensCameraDimension.MAIN
        if (!mm.isFinite() || mm <= 0f) return LensCameraDimension.MAIN
        return when {
            mm < ULTRA_WIDE_MAX_MM -> LensCameraDimension.ULTRA_WIDE
            mm < MAIN_MAX_MM -> LensCameraDimension.MAIN
            else -> LensCameraDimension.MACRO
        }
    }

    /**
     * 由变焦倍率解析相机维度（拿不到焦距时的兜底）。
     * < 1.0x 视为超广角，1.0x~1.5x 为主摄，> 1.5x 无法区分主摄裁切与微距，回退主摄。
     */
    fun fromZoomRatio(zoomRatio: Float?): LensCameraDimension {
        val zoom = zoomRatio ?: return LensCameraDimension.MAIN
        if (!zoom.isFinite() || zoom <= 0f) return LensCameraDimension.MAIN
        return if (zoom < ULTRA_WIDE_MAX_ZOOM) {
            LensCameraDimension.ULTRA_WIDE
        } else {
            LensCameraDimension.MAIN
        }
    }
}
