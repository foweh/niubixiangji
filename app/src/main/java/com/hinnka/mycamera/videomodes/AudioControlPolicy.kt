package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VIDEO_AUDIO_INPUT_AUTO

/**
 * §8 音频控制（麦克风来源 / 增益 / 静音）。
 *
 * 核对结论（工程已有，禁止重复）：
 * - `video/VideoAudioInput.kt` 已提供 `VideoAudioInputManager`（枚举可选输入设备、
 *   `availableInputs: StateFlow<List<VideoAudioInputOption>>`）与 id 生成规则
 *   `toVideoAudioInputId()`（`type:address:name`）；
 * - `video/VideoConfig.audioInputId` 已能承载所选输入 id。
 *
 * 本模块**只补**既有链路没有的开放项：**增益（0~300%）与静音开关**，以及
 * 「所选设备已拔出时优雅降级到 AUTO」的校验。
 * 增益落地由补丁接线（`MediaRecorder.setAudioSource` 后对采集缓冲做软件增益，
 * 或经 `MediaCodec` 音频通路前处理；具体见补丁 CODE_HOOKS）。
 */

/**
 * 音频开放配置。
 *
 * @param inputId 麦克风来源 id；[VIDEO_AUDIO_INPUT_AUTO]（"auto"）表示系统自动选。
 * @param gainPercent 采集增益，范围 **[0, 300]**（%），默认 100；0 = 静音等效。
 * @param muted 静音开关（优先于增益：开启后有效增益恒为 0），默认 false。
 * @param noiseSuppression 是否启用音频降噪（若 SDK/机型支持），默认 false。
 * @param monitorEnabled 是否开启录音监听（耳机回放监听），默认 false。
 */
data class AudioControlConfig(
    val inputId: String = VIDEO_AUDIO_INPUT_AUTO,
    val gainPercent: Int = VideoModesRanges.DEFAULT_AUDIO_GAIN_PERCENT,
    val muted: Boolean = false,
    val noiseSuppression: Boolean = false,
    val monitorEnabled: Boolean = false,
) {
    fun clampReport(): Pair<AudioControlConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val gain = AudioControlPolicy.clampGainPercent(gainPercent)
        if (gain != gainPercent) corrections += "gainPercent $gainPercent → $gain"
        val id = inputId.trim().ifEmpty { VIDEO_AUDIO_INPUT_AUTO }
        if (id != inputId) corrections += "inputId 空值 → $id"
        return copy(inputId = id, gainPercent = gain) to corrections
    }

    fun clamp(): AudioControlConfig = clampReport().first
}

/** 音频解析结果。 */
data class ResolvedAudioControl(
    val inputId: String,
    val gainPercent: Int,
    /** 增益线性系数 = gainPercent / 100（0.0 ~ 3.0）。 */
    val gainLinear: Float,
    /** 静音后的实际生效增益（静音时恒为 0）。 */
    val effectiveGainLinear: Float,
    val muted: Boolean,
    val noiseSuppression: Boolean,
    val monitorEnabled: Boolean,
    /** 所选输入不可用、已降级到 AUTO。 */
    val degradedToAuto: Boolean,
    val corrections: List<String>,
    val reason: String,
)

/** 音频策略（纯逻辑）。 */
object AudioControlPolicy {

    /** 自动选择输入（与既有 video 包常量同值，直接复用，不另立一套）。 */
    const val AUTO_INPUT_ID: String = VIDEO_AUDIO_INPUT_AUTO

    fun clampGainPercent(percent: Int): Int = percent.coerceIn(
        VideoModesRanges.AUDIO_GAIN_PERCENT_MIN,
        VideoModesRanges.AUDIO_GAIN_PERCENT_MAX,
    )

    /** 百分比 → 线性增益（0.0 ~ 3.0）。 */
    fun gainLinear(percent: Int): Float = clampGainPercent(percent) / 100f

    /**
     * 解析生效音频参数。
     *
     * @param availableInputIds 当前可用输入 id 集合（来自 `VideoAudioInputManager.availableInputs`）；
     *        传空集合表示「未探测」（此时不做降级判断，保留用户选择）。
     */
    fun resolve(config: AudioControlConfig, availableInputIds: Set<String>): ResolvedAudioControl {
        val (clamped, corrections) = config.clampReport()
        val needFallback = availableInputIds.isNotEmpty() &&
            clamped.inputId != AUTO_INPUT_ID &&
            !availableInputIds.contains(clamped.inputId)
        val resolvedId = if (needFallback) AUTO_INPUT_ID else clamped.inputId
        val linear = gainLinear(clamped.gainPercent)
        val effective = if (clamped.muted) 0f else linear

        val reason = buildString {
            append("输入=").append(if (resolvedId == AUTO_INPUT_ID) "自动" else resolvedId)
            append("，增益=").append(clamped.gainPercent).append("%")
            if (clamped.muted) append("（已静音）")
            if (needFallback) append("；所选麦克风已不可用，已回退自动选择")
        }

        return ResolvedAudioControl(
            inputId = resolvedId,
            gainPercent = clamped.gainPercent,
            gainLinear = linear,
            effectiveGainLinear = effective,
            muted = clamped.muted,
            noiseSuppression = clamped.noiseSuppression,
            monitorEnabled = clamped.monitorEnabled,
            degradedToAuto = needFallback,
            corrections = corrections,
            reason = reason,
        )
    }
}
