package com.hinnka.mycamera.videomodes

import kotlin.math.abs

/**
 * §7 手动对焦 / 曝光 / 白平衡——**视频档**接线与开放参数。
 *
 * 核对结论（工程已有，禁止重复）：
 * - `camera/CameraState.kt` 已有 `getManualShutterSpeedRange()`、`MAX_VIDEO_MANUAL_SHUTTER_SPEED_NS`
 *   （1/6s 上限）、`setShutterSpeed/setIso/setAwbTemperature` 等手动参数入口，
 *   `captureMode == CaptureMode.VIDEO` 时也会应用；
 * - 既有 `VideoConfig.lensLockEnabled / whiteBalanceLockEnabled` 已提供「录制中锁定」开关。
 *
 * 本模块**不做**新的 Camera2 写入实现，只补两件缺口：
 * 1. 视频档手动参数的**可用性判定**（HAL 是否支持视频档手动/锁定）；
 * 2. 把 4 个手动参数（快门 / ISO / 色温 / 对焦距离）的**取值范围与 clamp** 提取成配置对象，
 *    并给出「0/负值 = 自动」的统一哨兵语义，避免上层再各自写魔法数。
 */

/**
 * 现有手动参数能力探测结果（由 [VideoModesCapabilityProbe] 从 CameraCharacteristics 填充）。
 *
 * 范围字段用**空区间**（`first > last`，即 `LongRange.EMPTY` / `IntRange.EMPTY`）表示「设备未上报」，
 * 此时不做范围夹取，避免把用户值错夹成 0。
 */
data class ManualVideoProbe(
    /** 传感器上报的快门范围（ns）；[LongRange.EMPTY] = 未上报。 */
    val shutterRangeNs: LongRange = LongRange.EMPTY,
    /** 传感器上报的 ISO（感光度）范围；[IntRange.EMPTY] = 未上报。 */
    val isoRange: IntRange = IntRange.EMPTY,
    /** 传感器上报的 AWB 色温范围（K）；[IntRange.EMPTY] = 未上报。 */
    val awbRangeK: IntRange = IntRange.EMPTY,
    /** `LENS_INFO_MINIMUM_FOCUS_DISTANCE`：最大屈光度（越大越近）；<=0 表示定焦不可调。 */
    val maxFocusDiopters: Float = 0f,
    /** 视频档是否允许手动曝光（HAL 上报 FULL/LEVEL_3 或视频模式支持手动）。 */
    val manualExposureSupported: Boolean = false,
    /** 视频档是否允许锁定 AE/AWB/AF。 */
    val lockSupported: Boolean = false,
)

/**
 * 手动参数视频档开放配置（**哨兵语义**统一在此定义）。
 *
 * @param shutterSpeedNs 手动快门（ns）；[VideoModesRanges.MANUAL_SHUTTER_NS_AUTO]（0）= 自动。
 * @param iso 手动 ISO；[VideoModesRanges.MANUAL_ISO_AUTO]（0）= 自动。
 * @param awbTemperatureK 手动色温（K）；[VideoModesRanges.MANUAL_AWB_K_AUTO]（0）= 自动。
 * @param focusDistanceDiopters 手动对焦距离（屈光度）；<0 = 自动（连续 AF）。
 * @param lockFocus 录制中锁定对焦（复用既有 `VideoConfig.lensLockEnabled` 语义）。
 * @param lockExposure 录制中锁定曝光（AE 锁定）。
 * @param lockWhiteBalance 录制中锁定白平衡（复用既有 `whiteBalanceLockEnabled` 语义）。
 */
data class ManualVideoParamsConfig(
    val shutterSpeedNs: Long = VideoModesRanges.MANUAL_SHUTTER_NS_AUTO,
    val iso: Int = VideoModesRanges.MANUAL_ISO_AUTO,
    val awbTemperatureK: Int = VideoModesRanges.MANUAL_AWB_K_AUTO,
    val focusDistanceDiopters: Float = VideoModesRanges.MANUAL_FOCUS_DIOPTER_AUTO,
    val lockFocus: Boolean = false,
    val lockExposure: Boolean = false,
    val lockWhiteBalance: Boolean = false,
)

/** 手动参数解析结果（clamp 后的生效值 + 自动标记）。 */
data class ResolvedManualVideoParams(
    val shutterSpeedNs: Long,
    val iso: Int,
    val awbTemperatureK: Int,
    val focusDistanceDiopters: Float,
    val lockFocus: Boolean,
    val lockExposure: Boolean,
    val lockWhiteBalance: Boolean,
    val isShutterAuto: Boolean,
    val isIsoAuto: Boolean,
    val isAwbAuto: Boolean,
    val isFocusAuto: Boolean,
    /** 视频档是否可用手动参数（false 时上层应回退全自动并提示）。 */
    val manualEnabled: Boolean,
    /** 锁定是否生效（能力不足时强制 false）。 */
    val lockEffective: Boolean,
    val corrections: List<String>,
    val reason: String,
)

/** 手动参数策略（纯逻辑）。 */
object ManualVideoParamPolicy {

    /** 区间是否有效（未被 clamp 成空区间）。 */
    private fun LongRange.isValidRange(): Boolean = first <= last

    private fun IntRange.isValidRange(): Boolean = first <= last

    fun resolve(config: ManualVideoParamsConfig, probe: ManualVideoProbe): ResolvedManualVideoParams {
        val corrections = mutableListOf<String>()
        val manualUsable = probe.manualExposureSupported

        // ---- 快门：0 = 自动 ----
        val shutterAuto = config.shutterSpeedNs <= VideoModesRanges.MANUAL_SHUTTER_NS_AUTO
        val shutter = when {
            shutterAuto || !manualUsable -> VideoModesRanges.MANUAL_SHUTTER_NS_AUTO
            else -> {
                val safe = config.shutterSpeedNs.coerceIn(1L, VideoModesRanges.MANUAL_SHUTTER_NS_ABSOLUTE_MAX)
                if (probe.shutterRangeNs.isValidRange()) {
                    val clamped = safe.coerceIn(probe.shutterRangeNs.first, probe.shutterRangeNs.last)
                    if (clamped != safe) corrections += "shutterSpeedNs $safe → $clamped（传感器范围）"
                    clamped
                } else {
                    safe
                }
            }
        }

        // ---- ISO：0 = 自动 ----
        val isoAuto = config.iso <= VideoModesRanges.MANUAL_ISO_AUTO
        val iso = when {
            isoAuto || !manualUsable -> VideoModesRanges.MANUAL_ISO_AUTO
            else -> {
                val safe = config.iso.coerceIn(1, VideoModesRanges.MANUAL_ISO_ABSOLUTE_MAX)
                if (probe.isoRange.isValidRange()) {
                    val clamped = safe.coerceIn(probe.isoRange.first, probe.isoRange.last)
                    if (clamped != safe) corrections += "iso $safe → $clamped（传感器范围）"
                    clamped
                } else {
                    safe
                }
            }
        }

        // ---- 色温：0 = 自动 ----
        val awbAuto = config.awbTemperatureK <= VideoModesRanges.MANUAL_AWB_K_AUTO
        val awb = when {
            awbAuto || !manualUsable -> VideoModesRanges.MANUAL_AWB_K_AUTO
            else -> {
                val safe = config.awbTemperatureK.coerceIn(
                    VideoModesRanges.MANUAL_AWB_K_MIN,
                    VideoModesRanges.MANUAL_AWB_K_ABSOLUTE_MAX,
                )
                if (probe.awbRangeK.isValidRange()) {
                    val clamped = safe.coerceIn(probe.awbRangeK.first, probe.awbRangeK.last)
                    if (clamped != safe) corrections += "awbTemperatureK $safe → $clamped（传感器范围）"
                    clamped
                } else {
                    safe
                }
            }
        }

        // ---- 对焦：负值 / 定焦 → 自动 ----
        val focusAutoRequested = config.focusDistanceDiopters < 0f || !config.focusDistanceDiopters.isFinite()
        val focusAuto = focusAutoRequested || probe.maxFocusDiopters <= 0f
        val focus = if (focusAuto) {
            VideoModesRanges.MANUAL_FOCUS_DIOPTER_AUTO
        } else {
            val safe = config.focusDistanceDiopters.coerceIn(
                VideoModesRanges.MANUAL_FOCUS_DIOPTER_MIN,
                probe.maxFocusDiopters,
            )
            if (abs(safe - config.focusDistanceDiopters) > 0.001f) {
                corrections += "focusDistanceDiopters ${config.focusDistanceDiopters} → $safe" +
                    "（传感器最近对焦 ${probe.maxFocusDiopters}）"
            }
            safe
        }

        val lockWanted = config.lockFocus || config.lockExposure || config.lockWhiteBalance
        val lockEffective = lockWanted && probe.lockSupported
        if (lockWanted && !lockEffective) {
            corrections += "录制锁定不可用（HAL 未支持），已忽略"
        }

        val reason = buildString {
            append(if (manualUsable) "视频档手动参数可用" else "视频档手动参数不可用（回退全自动）")
            append("；")
            append(
                listOf(
                    if (shutterAuto || !manualUsable) "快门自动" else "快门 ${shutter}ns",
                    if (isoAuto || !manualUsable) "ISO 自动" else "ISO $iso",
                    if (awbAuto || !manualUsable) "色温自动" else "色温 ${awb}K",
                    if (focusAuto) "对焦自动" else "对焦 ${focus}D",
                ).joinToString("/"),
            )
            if (lockEffective) append("；录制锁定已启用")
        }

        return ResolvedManualVideoParams(
            shutterSpeedNs = shutter,
            iso = iso,
            awbTemperatureK = awb,
            focusDistanceDiopters = focus,
            lockFocus = config.lockFocus && lockEffective,
            lockExposure = config.lockExposure && lockEffective,
            lockWhiteBalance = config.lockWhiteBalance && lockEffective,
            isShutterAuto = shutterAuto || !manualUsable,
            isIsoAuto = isoAuto || !manualUsable,
            isAwbAuto = awbAuto || !manualUsable,
            isFocusAuto = focusAuto,
            manualEnabled = manualUsable,
            lockEffective = lockEffective,
            corrections = corrections,
            reason = reason,
        )
    }
}
