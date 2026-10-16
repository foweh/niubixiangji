package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VideoCodec

/**
 * §4 HDR 视频（10-bit HEVC + BT.2020 能力门控）。
 *
 * 核对结论（工程已有，禁止重复）：
 * - `video/VideoEncoderColorConfig.kt` 已能按编码器能力选 10bit profile
 *   （`HEVCProfileMain10` / `AVCProfileHigh10`）并写容器色彩元数据；
 * - NDK 资产 `libultrahdr` 已随工程存在（照片侧 HDR 增益图用）。
 *
 * 本模块**只做门控与回退决策**（纯逻辑）：HAL/编码器不支持 10-bit HEVC 或 BT.2020 时，
 * 自动回退 SDR 8-bit 并给出可读原因，绝不把「不支持」硬套成录制失败。
 *
 * 接线点（已在 `android-36/android.jar` 上核实）：
 * - 相机侧 HDR 能力查询：`CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`
 *   → `DynamicRangeProfiles.getSupportedProfiles()`，含 `HDR10` / `HLG10` 即可用；
 * - 相机侧 HDR 生效：对录制输出表面设 `OutputConfiguration.setDynamicRangeProfile(long)`
 *   （`DynamicRangeProfiles.HDR10` / `HLG10`）——属 `Camera2Controller` 既有会话配置的补丁项，
 *   本模块只给决策；
 * - 编码侧 10bit：复用既有 `video/VideoEncoderColorConfig.resolveVideoEncoderColorConfig()`
 *   写 profile 与容器色彩元数据（本模块只给位深/传递函数结论）。
 *
 * 参考：google/libultrahdr（348★，Apache-2.0）——仅参考其「HDR 能力探测 / SDR 回退」的思路，
 * **不采其代码**（其为 C++ 照片 HDR 库，与本处视频编码器门控职责不同）。
 */

/** HDR 传递函数（写入容器时由编码侧映射到 MediaFormat 枚举）。 */
enum class HdrTransfer(val metadataName: String) {
    /** Perceptual Quantizer（HDR10）。 */
    PQ("ST2084"),
    /** Hybrid Log-Gamma（HLG）。 */
    HLG("HLG"),
}

/**
 * HDR 视频开放配置。
 *
 * @param enabled 是否请求 HDR 录制，默认 false（既有行为 = SDR）。
 * @param prefer10Bit 优先 10bit（默认 true）；能力不足时按 [fallbackToSdr] 决定回退。
 * @param preferBt2020 优先生 BT.2020 容器色彩（默认 true）。
 * @param fallbackToSdr 能力不足时是否自动回退 SDR 8-bit（默认 true，保证「永远录得成」）。
 * @param preferredTransfer 首选传递函数，默认 HLG（移动端兼容性更好）。
 */
data class HdrVideoConfig(
    val enabled: Boolean = false,
    val prefer10Bit: Boolean = true,
    val preferBt2020: Boolean = true,
    val fallbackToSdr: Boolean = true,
    val preferredTransfer: HdrTransfer = HdrTransfer.HLG,
)

/** HDR 能力探测结果（由 [VideoModesCapabilityProbe] 从 Camera2 + MediaCodec 填充）。 */
data class HdrVideoCapability(
    /** 是否支持 HEVC 编码。 */
    val hevcSupported: Boolean = false,
    /** 是否支持 HEVC Main10（10bit）。 */
    val tenBitHevcSupported: Boolean = false,
    /** 是否支持 AVC High10（10bit）。 */
    val tenBitAvcSupported: Boolean = false,
    /** 传感器/流是否上报 HDR 相关的动态范围能力（`REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES`）。 */
    val hdrDynamicRangeSupported: Boolean = false,
    /** 显示/应用侧是否支持 BT.2020 容器标注。 */
    val bt2020Supported: Boolean = false,
) {
    /** 是否存在任一可用的 10bit 通路。 */
    val anyTenBit: Boolean get() = tenBitHevcSupported || tenBitAvcSupported
}

/** HDR 决策结果。 */
data class HdrVideoDecision(
    /** 最终是否以 HDR 录制。 */
    val hdrEnabled: Boolean,
    /** 生效位深（8 或 10）。 */
    val effectiveBitDepth: Int,
    /** 生效编码器（HDR 优先后为 HEVC）。 */
    val effectiveCodec: VideoCodec,
    /** 是否写 BT.2020 容器色彩。 */
    val bt2020: Boolean,
    /** 生效传递函数（非 HDR 时为 null）。 */
    val transfer: HdrTransfer?,
    /** 是否发生了自动回退（UI 据此弹提示）。 */
    val fallbackApplied: Boolean,
    /** 可读原因（中文，直接可用于提示）。 */
    val reason: String,
)

/** HDR 门控（纯逻辑）。 */
object HdrVideoGate {

    /**
     * 解析 HDR 录制决策。
     *
     * 规则（自上而下）：
     * 1. 未请求 HDR → SDR 8-bit（沿用 [requestedCodec]），`hdrEnabled=false`；
     * 2. 请求 HDR 且 `10bit + BT.2020 + HDR 动态范围`齐备 → HDR 10-bit HEVC BT.2020；
     * 3. 请求 HDR 但能力不足：
     *    - `fallbackToSdr=true` → 回退 SDR 8-bit，`fallbackApplied=true` + 原因；
     *    - `fallbackToSdr=false` → 仍返回 SDR 决策但明确提示「未回退，需手动改 SDR」。
     *
     * @param requestedCodec 用户在编码设置里选的编码器（HDR 关闭/回退时原样沿用）。
     */
    fun resolve(
        config: HdrVideoConfig,
        capability: HdrVideoCapability,
        requestedCodec: VideoCodec = VideoCodec.H264,
    ): HdrVideoDecision {
        if (!config.enabled) {
            return HdrVideoDecision(
                hdrEnabled = false,
                effectiveBitDepth = 8,
                effectiveCodec = requestedCodec,
                bt2020 = false,
                transfer = null,
                fallbackApplied = false,
                reason = "HDR 未开启（SDR 8-bit 基线）",
            )
        }

        val missing = mutableListOf<String>()
        if (!capability.hevcSupported) missing += "HEVC 编码"
        if (config.prefer10Bit && !capability.tenBitHevcSupported) missing += "HEVC Main10（10bit）"
        if (config.preferBt2020 && !capability.bt2020Supported) missing += "BT.2020 色彩"
        if (!capability.hdrDynamicRangeSupported) missing += "HDR 动态范围 profile"

        if (missing.isEmpty()) {
            return HdrVideoDecision(
                hdrEnabled = true,
                effectiveBitDepth = 10,
                effectiveCodec = VideoCodec.H265,
                bt2020 = true,
                transfer = config.preferredTransfer,
                fallbackApplied = false,
                reason = "HDR 10-bit HEVC / BT.2020 / ${config.preferredTransfer.metadataName} 已启用",
            )
        }

        val reason = if (config.fallbackToSdr) {
            "本机缺少：${missing.joinToString("、")}；已自动回退 SDR 8-bit H.264（画面仍可正常录制，" +
                "无 HDR 广色域/高动态范围）"
        } else {
            "本机缺少：${missing.joinToString("、")}；未开启自动回退，请手动切换 SDR 或关闭 HDR"
        }
        return HdrVideoDecision(
            hdrEnabled = false,
            effectiveBitDepth = 8,
            effectiveCodec = requestedCodec,
            bt2020 = false,
            transfer = null,
            fallbackApplied = true,
            reason = reason,
        )
    }

    /** 位深是否被开放集合接受（防误设，如 12）。 */
    fun normalizeBitDepth(bitDepth: Int): Int =
        if (VideoModesRanges.VIDEO_BIT_DEPTHS.contains(bitDepth)) {
            bitDepth
        } else {
            VideoModesRanges.DEFAULT_VIDEO_BIT_DEPTH
        }
}
