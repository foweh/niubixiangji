package com.hinnka.mycamera.videomodes

/**
 * §11 低光 / 夜景视频（增益提升 + 时域降噪档位，能力门控 + 参数开放）。
 *
 * 核对结论：工程已有夜间能力都在**照片**侧——`camera/ComputationalScene.NIGHT`、
 * `camera/MultiFrameConfig`（夜景多帧）、`hdr/` 与 `processor/GlesMgcRawSpatialStacker`；
 * 视频侧只有 EIS/YUV 通路，没有「夜景视频」档，故本模块补门控 + 参数开放。
 *
 * 复用优先（§9）：**不新写降噪算法**，夜景视频的时域降噪直接复用
 * [VideoDenoisePlan]（`VideoDenoisePlanner.plan(...)` 走 `BgTaskType.VIDEO_DENOISE`）。
 * 本文件只负责「增益提升 + ISO/快门上限 + 是否启用时域降噪」的**决策与 clamp**。
 */

/**
 * 夜景视频开放配置。
 *
 * @param enabled 是否启用夜景视频档，默认 false（既有行为 = 不做任何额外提升）。
 * @param gainBoostEv 增益提升，范围 **[0, 2] EV**，默认 0（= 不额外提亮）。
 * @param temporalStrength 时域降噪强度，范围 [0, 1]，默认 0.5。
 * @param maxIso 手动 ISO 上限，范围 [100, 102400]，默认 6400（**可上调**，越高越亮也越噪）。
 * @param allowLongShutter 是否允许把单帧快门放长到 [maxShutterNs]，默认 true。
 * @param maxShutterNs 单帧快门上限（ns），范围 [1ms, 10s]，默认 1/30s（防运动拖影）。
 * @param denoiseMode 时域降噪模式，默认 [VideoDenoiseMode.TEMPORAL]（夜景优先时域）。
 */
data class NightVideoConfig(
    val enabled: Boolean = false,
    val gainBoostEv: Float = VideoModesRanges.DEFAULT_NIGHT_GAIN_BOOST_EV,
    val temporalStrength: Float = VideoModesRanges.DEFAULT_NIGHT_TEMPORAL_STRENGTH,
    val maxIso: Int = VideoModesRanges.DEFAULT_NIGHT_MAX_ISO,
    val allowLongShutter: Boolean = true,
    val maxShutterNs: Long = VideoModesRanges.DEFAULT_NIGHT_MAX_SHUTTER_NS,
    val denoiseMode: VideoDenoiseMode = VideoDenoiseMode.TEMPORAL,
) {
    fun clampReport(): Pair<NightVideoConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val rawEv = if (gainBoostEv.isFinite()) gainBoostEv else VideoModesRanges.DEFAULT_NIGHT_GAIN_BOOST_EV
        val ev = rawEv.coerceIn(
            VideoModesRanges.NIGHT_GAIN_BOOST_EV_MIN,
            VideoModesRanges.NIGHT_GAIN_BOOST_EV_MAX,
        )
        if (ev != rawEv) corrections += "gainBoostEv $rawEv → $ev"
        val rawTemporal = if (temporalStrength.isFinite()) {
            temporalStrength
        } else {
            VideoModesRanges.DEFAULT_NIGHT_TEMPORAL_STRENGTH
        }
        val temporal = rawTemporal.coerceIn(
            VideoModesRanges.NIGHT_TEMPORAL_STRENGTH_MIN,
            VideoModesRanges.NIGHT_TEMPORAL_STRENGTH_MAX,
        )
        if (temporal != rawTemporal) corrections += "temporalStrength $rawTemporal → $temporal"
        val iso = maxIso.coerceIn(
            VideoModesRanges.NIGHT_MAX_ISO_MIN,
            VideoModesRanges.NIGHT_MAX_ISO_ABSOLUTE_MAX,
        )
        if (iso != maxIso) corrections += "maxIso $maxIso → $iso"
        val shutter = maxShutterNs.coerceIn(
            VideoModesRanges.NIGHT_MAX_SHUTTER_NS_MIN,
            VideoModesRanges.NIGHT_MAX_SHUTTER_NS_ABSOLUTE_MAX,
        )
        if (shutter != maxShutterNs) corrections += "maxShutterNs $maxShutterNs → $shutter"
        return copy(
            gainBoostEv = ev,
            temporalStrength = temporal,
            maxIso = iso,
            maxShutterNs = shutter,
        ) to corrections
    }

    fun clamp(): NightVideoConfig = clampReport().first
}

/** 夜景视频能力（由 [VideoModesCapabilityProbe] 从 CameraCharacteristics 填充）。 */
data class NightVideoCapability(
    val supportsManualIso: Boolean = false,
    val supportsManualShutter: Boolean = false,
    /** 时域降噪不需要 HAL 支持（本模块后台软件实现），此处指「是否允许调度」。 */
    val supportsTemporalDenoise: Boolean = true,
    /** 传感器 ISO 范围；[IntRange.EMPTY] = 未上报。 */
    val isoRange: IntRange = IntRange.EMPTY,
    /** 传感器快门范围（ns）；[LongRange.EMPTY] = 未上报。 */
    val shutterRangeNs: LongRange = LongRange.EMPTY,
)

/** 夜景视频决策。 */
data class NightVideoDecision(
    val active: Boolean,
    val gainBoostEv: Float,
    /** 生效 ISO 上限（0 = 不介入手动 ISO）。 */
    val targetIso: Int,
    /** 生效快门上限（ns，0 = 不介入）。 */
    val maxShutterNs: Long,
    val temporalStrength: Float,
    val denoiseConfig: VideoDenoiseConfig,
    val reason: String,
)

/** 夜景视频策略（纯逻辑）。 */
object NightVideoPolicy {

    private fun IntRange.isValidRange(): Boolean = first <= last

    private fun LongRange.isValidRange(): Boolean = first <= last

    fun resolve(config: NightVideoConfig, capability: NightVideoCapability): NightVideoDecision {
        val (clamped, corrections) = config.clampReport()
        val disabled = VideoDenoiseConfig(enabled = false)

        if (!clamped.enabled) {
            return NightVideoDecision(
                active = false,
                gainBoostEv = 0f,
                targetIso = 0,
                maxShutterNs = 0L,
                temporalStrength = 0f,
                denoiseConfig = disabled,
                reason = "夜景视频未开启",
            )
        }

        val anySupport = capability.supportsManualIso ||
            capability.supportsManualShutter ||
            capability.supportsTemporalDenoise
        if (!anySupport) {
            return NightVideoDecision(
                active = false,
                gainBoostEv = 0f,
                targetIso = 0,
                maxShutterNs = 0L,
                temporalStrength = 0f,
                denoiseConfig = disabled,
                reason = "本机视频档不支持夜景增强（手动 ISO / 手动快门 / 时域降噪均不可用），" +
                    "已自动隐藏该档位",
            )
        }

        // 生效 ISO：仅当 HAL 支持手动 ISO 时介入，并按传感器范围夹取。
        val targetIso = if (capability.supportsManualIso) {
            if (capability.isoRange.isValidRange()) {
                clamped.maxIso.coerceIn(capability.isoRange.first, capability.isoRange.last)
            } else {
                clamped.maxIso
            }
        } else {
            0
        }

        // 生效快门上限：仅当允许长快门且 HAL 支持手动快门时介入。
        val maxShutter = if (clamped.allowLongShutter && capability.supportsManualShutter) {
            if (capability.shutterRangeNs.isValidRange()) {
                clamped.maxShutterNs.coerceIn(
                    capability.shutterRangeNs.first,
                    capability.shutterRangeNs.last,
                )
            } else {
                clamped.maxShutterNs
            }
        } else {
            0L
        }

        val denoise = VideoDenoiseConfig(
            enabled = capability.supportsTemporalDenoise && clamped.temporalStrength > 0.0001f,
            mode = clamped.denoiseMode,
            strength = clamped.temporalStrength,
            preserveDetailOnMotion = true,
        ).clamp()

        val reason = buildString {
            append("夜景视频已启用：+").append(clamped.gainBoostEv).append("EV")
            append("，时域降噪 ").append(clamped.temporalStrength)
            if (targetIso > 0) append("，ISO ≤ ").append(targetIso) else append("，ISO 自动")
            if (maxShutter > 0) append("，快门 ≤ ").append(maxShutter).append("ns")
            if (!capability.supportsManualIso) append("（本机不支持手动 ISO，增益提亮仍生效）")
            if (corrections.isNotEmpty()) append("；参数已修正 ").append(corrections.size).append(" 项")
        }

        return NightVideoDecision(
            active = true,
            gainBoostEv = clamped.gainBoostEv,
            targetIso = targetIso,
            maxShutterNs = maxShutter,
            temporalStrength = clamped.temporalStrength,
            denoiseConfig = denoise,
            reason = reason,
        )
    }
}
