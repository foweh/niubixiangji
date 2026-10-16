package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VideoCodec
import kotlin.math.abs

/**
 * §14 编码控制（编码器 / 位深 / 码率 / 音频编码参数，全部开放可调 + clamp）。
 *
 * 核对结论（工程已有，禁止重复）：
 * - `video/VideoRecorder.kt:413` 已把码率 clamp 到 `2_000_000..300_000_000`
 *   （本次用户要求开放区间为 **1~200 Mbps**，故由本策略做上层 clamp，详见补丁 NOTES）；
 * - `video/VideoTypes.kt` 的 `VideoCodec`（H264/H265）与 `VideoBitratePreset` 是既有枚举；
 * - `video/VideoEncoderColorConfig.kt` 的 `resolve10BitProfile()` 已在真实编码器能力上判定
 *   10bit profile，本策略只复用其结论（[VideoEncodeCapability] 由探测层填充）。
 */

/**
 * 编码开放配置。
 *
 * @param codec 编码器，默认 H.264（既有默认）。
 * @param bitrateMbps 视频码率，范围 **[1, 200] Mbps**，默认 60；越界 clamp。
 * @param bitDepth 位深，取值 {8, 10}，默认 8；10bit 需要编码器 profile 支持，否则回退 8bit。
 * @param audioBitrateKbps 音频码率，范围 [64, 512] kbps，默认 256。
 * @param audioSampleRateHz 采样率，取值 {16000, 32000, 44100, 48000, 96000}，默认 48000（就近吸附）。
 * @param audioChannels 声道数，范围 [1, 2]，默认 2。
 */
data class VideoEncodeConfig(
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateMbps: Int = VideoModesRanges.DEFAULT_BITRATE_MBPS,
    val bitDepth: Int = VideoModesRanges.DEFAULT_VIDEO_BIT_DEPTH,
    val audioBitrateKbps: Int = VideoModesRanges.DEFAULT_AUDIO_BITRATE_KBPS,
    val audioSampleRateHz: Int = VideoModesRanges.DEFAULT_AUDIO_SAMPLE_RATE_HZ,
    val audioChannels: Int = VideoModesRanges.DEFAULT_AUDIO_CHANNELS,
) {
    fun clampReport(): Pair<VideoEncodeConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val bitrate = VideoEncodePolicy.clampBitrateMbps(bitrateMbps)
        if (bitrate != bitrateMbps) corrections += "bitrateMbps $bitrateMbps → $bitrate"
        val depth = HdrVideoGate.normalizeBitDepth(bitDepth)
        if (depth != bitDepth) corrections += "bitDepth $bitDepth → $depth"
        val audioKbps = audioBitrateKbps.coerceIn(
            VideoModesRanges.AUDIO_BITRATE_KBPS_MIN,
            VideoModesRanges.AUDIO_BITRATE_KBPS_MAX,
        )
        if (audioKbps != audioBitrateKbps) corrections += "audioBitrateKbps $audioBitrateKbps → $audioKbps"
        val sampleRate = VideoEncodePolicy.snapSampleRate(audioSampleRateHz)
        if (sampleRate != audioSampleRateHz) corrections += "audioSampleRateHz $audioSampleRateHz → $sampleRate"
        val channels = audioChannels.coerceIn(
            VideoModesRanges.AUDIO_CHANNELS_MIN,
            VideoModesRanges.AUDIO_CHANNELS_MAX,
        )
        if (channels != audioChannels) corrections += "audioChannels $audioChannels → $channels"
        return VideoEncodeConfig(
            codec = codec,
            bitrateMbps = bitrate,
            bitDepth = depth,
            audioBitrateKbps = audioKbps,
            audioSampleRateHz = sampleRate,
            audioChannels = channels,
        ) to corrections
    }

    fun clamp(): VideoEncodeConfig = clampReport().first
}

/** 编码器能力（由 [VideoModesCapabilityProbe] 从 MediaCodecList 填充）。 */
data class VideoEncodeCapability(
    val avcSupported: Boolean = true,
    val hevcSupported: Boolean = false,
    val tenBitAvcSupported: Boolean = false,
    val tenBitHevcSupported: Boolean = false,
) {
    fun supportsBitDepth(codec: VideoCodec, bitDepth: Int): Boolean {
        if (bitDepth < 10) return true
        return when (codec) {
            VideoCodec.H264 -> tenBitAvcSupported
            VideoCodec.H265 -> tenBitHevcSupported
        }
    }
}

/** 编码解析结果。 */
data class ResolvedVideoEncode(
    val codec: VideoCodec,
    val bitrateMbps: Int,
    val bitDepth: Int,
    val audioBitrateKbps: Int,
    val audioSampleRateHz: Int,
    val audioChannels: Int,
    val corrections: List<String>,
    val reason: String,
) {
    val tenBit: Boolean get() = bitDepth >= 10
    /** 编码器码率 bps（供 MediaFormat.KEY_BIT_RATE）。 */
    val bitrateBps: Int get() = bitrateMbps * 1_000_000
}

/** 编码策略（纯逻辑）。 */
object VideoEncodePolicy {

    fun clampBitrateMbps(mbps: Int): Int =
        mbps.coerceIn(VideoModesRanges.BITRATE_MBPS_MIN, VideoModesRanges.BITRATE_MBPS_MAX)

    /** 采样率就近吸附到开放集合。 */
    fun snapSampleRate(hz: Int): Int =
        VideoModesRanges.AUDIO_SAMPLE_RATES_HZ.minByOrNull { abs(it - hz) }
            ?: VideoModesRanges.DEFAULT_AUDIO_SAMPLE_RATE_HZ

    /**
     * 解析生效编码参数：编码器与位深按真实能力回退，其余参数 clamp。
     * 任何情况下都返回一个可用组合（不抛异常、不阻断录制）。
     */
    fun resolve(config: VideoEncodeConfig, capability: VideoEncodeCapability): ResolvedVideoEncode {
        val (clamped, clampCorrections) = config.clampReport()
        val corrections = clampCorrections.toMutableList()
        val reasons = mutableListOf<String>()

        val codec = when (clamped.codec) {
            VideoCodec.H265 -> if (capability.hevcSupported) {
                VideoCodec.H265
            } else {
                corrections += "codec H265 → H264（本机不支持 HEVC）"
                VideoCodec.H264
            }

            VideoCodec.H264 -> if (capability.avcSupported) {
                VideoCodec.H264
            } else if (capability.hevcSupported) {
                corrections += "codec H264 → H265（本机不支持 AVC）"
                VideoCodec.H265
            } else {
                // 两个都没上报属异常情况：保留用户选择并提示，交由编码器初始化失败兜底。
                reasons += "本机未上报任何可用视频编码器（异常），已保留用户选择"
                clamped.codec
            }
        }

        val requestedDepth = clamped.bitDepth
        val bitDepth = if (capability.supportsBitDepth(codec, requestedDepth)) {
            requestedDepth
        } else {
            corrections += "bitDepth $requestedDepth → 8（${codec.displayName} 不支持该 profile）"
            8
        }

        reasons += "编码 ${codec.displayName} / ${bitDepth}bit / ${clamped.bitrateMbps}Mbps"

        return ResolvedVideoEncode(
            codec = codec,
            bitrateMbps = clamped.bitrateMbps,
            bitDepth = bitDepth,
            audioBitrateKbps = clamped.audioBitrateKbps,
            audioSampleRateHz = clamped.audioSampleRateHz,
            audioChannels = clamped.audioChannels,
            corrections = corrections,
            reason = reasons.joinToString("；"),
        )
    }
}
