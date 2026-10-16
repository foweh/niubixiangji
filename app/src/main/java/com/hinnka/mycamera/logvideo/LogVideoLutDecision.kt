package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.raw.ColorSpace
import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.VideoLogProfile

/**
 * 「LUT 是否进片」判定（Log 视频 20 项 · 第 2 项：实时 LUT 预览的录制侧语义）。
 *
 * 复用工程既有语义（`Camera2Controller` 的 `monitorOnly` / `profile.matchesLut` / `takeUnless` 分支），
 * 但抽成**纯函数**以便对正常/边界/异常三类输入做单测：
 *  - 正常：BAKE_IN + LUT 与档位匹配 + LUT 就绪 → LUT 与配方一起烘焙进片；
 *  - 边界：强度 0（LUT 不参与但不算降级）、强度 >2（clamp 到 2）；
 *  - 异常：MONITOR_ONLY（录制只留 Log）、LUT 档位不匹配（回退，标记 degraded）、LUT 不可用。
 */
object LogVideoLutDecision {

    /**
     * @param bakeLutIntoRecording 录制路径是否把还原 LUT 烘焙进视频（第 2 项核心结论）。
     * @param bakeRecipeIntoRecording 录制路径是否把色彩配方（look）烘焙进视频。
     * @param effectiveIntensity LUT 全映射强度（已 clamp）。
     * @param degraded 是否发生降级回退（供 UI 提示「配套 LUT 未生效」）。
     */
    data class Decision(
        val bakeLutIntoRecording: Boolean,
        val bakeRecipeIntoRecording: Boolean,
        val effectiveIntensity: Float,
        val degraded: Boolean,
        val reason: String,
    )

    fun decide(
        profile: VideoLogProfile,
        config: LogVideoConfig,
        lutCurve: TransferCurve?,
        lutColorSpace: ColorSpace?,
        lutAvailable: Boolean,
        requestedIntensity: Float = config.lutIntensity,
    ): Decision {
        val intensity = if (requestedIntensity.isFinite()) {
            requestedIntensity.coerceIn(LogVideoConfig.LUT_INTENSITY_MIN, LogVideoConfig.LUT_INTENSITY_MAX)
        } else {
            LogVideoConfig.DEFAULT_LUT_INTENSITY
        }

        // 异常组合：配置开启但相机未切到 Log 档位 → 完全按普通视频处理。
        if (!profile.isEnabled) {
            return Decision(
                bakeLutIntoRecording = false,
                bakeRecipeIntoRecording = true,
                effectiveIntensity = 0f,
                degraded = false,
                reason = "Log 档位未启用，按普通视频处理",
            )
        }

        // MONITOR_ONLY：录制只留 Log（纯净 Log 输出），LUT/配方仅用于监看。
        if (config.lutMode == VideoLogLutMode.MONITOR_ONLY) {
            return Decision(
                bakeLutIntoRecording = false,
                bakeRecipeIntoRecording = false,
                effectiveIntensity = 0f,
                degraded = false,
                reason = "MONITOR_ONLY：录制输出纯 Log，LUT 仅监看",
            )
        }

        // BAKE_IN 且强度为 0 → LUT 恒等，不进片（配方仍进）。
        if (intensity == 0f) {
            return Decision(
                bakeLutIntoRecording = false,
                bakeRecipeIntoRecording = true,
                effectiveIntensity = 0f,
                degraded = false,
                reason = "LUT 强度为 0，LUT 恒等不进片",
            )
        }

        // BAKE_IN 但 LUT 与当前档位不匹配（异常：选了别的 Log 组的还原 LUT）。
        if (!profile.matchesLut(lutCurve, lutColorSpace)) {
            return Decision(
                bakeLutIntoRecording = false,
                bakeRecipeIntoRecording = true,
                effectiveIntensity = 0f,
                degraded = true,
                reason = "LUT(${lutCurve?.name}/${lutColorSpace?.name}) 与档位(${profile.logCurve.name}/${profile.colorSpace.name}) 不匹配，已回退",
            )
        }

        // BAKE_IN 但 LUT 尚未就绪（未下载 / 烘焙中 / 已删除）。
        if (!lutAvailable) {
            return Decision(
                bakeLutIntoRecording = false,
                bakeRecipeIntoRecording = true,
                effectiveIntensity = 0f,
                degraded = true,
                reason = "配套 Log 还原 LUT 不可用（未下载或烘焙中）",
            )
        }

        return Decision(
            bakeLutIntoRecording = true,
            bakeRecipeIntoRecording = true,
            effectiveIntensity = intensity,
            degraded = false,
            reason = "BAKE_IN：LUT 与配方烘焙进片",
        )
    }

    /**
     * 预览侧：**始终**套用还原 LUT（边拍边看），与 `LutRenderer.enableVideoLog = true` 一致；
     * Log 未启用时退化为普通预览（无 LUT）。
     */
    fun shouldPreviewLut(profile: VideoLogProfile, config: LogVideoConfig): Boolean =
        profile.isEnabled && config.enabled &&
            config.lutIntensity.coerceIn(
                LogVideoConfig.LUT_INTENSITY_MIN,
                LogVideoConfig.LUT_INTENSITY_MAX,
            ) > 0f

    /** 档位 → 推荐曲线档位（UI 默认选中项）。 */
    fun recommendedPreset(profile: VideoLogProfile): LogCurvePreset? = when (profile) {
        VideoLogProfile.OFF -> null
        VideoLogProfile.APPLE_LOG2 -> LogCurvePreset.APPLE_LOG2
        VideoLogProfile.FLOG2_BT2020 -> LogCurvePreset.FLOG2
        VideoLogProfile.V_LOG -> LogCurvePreset.V_LOG
        VideoLogProfile.LOGC4_ARRI4 -> LogCurvePreset.LOGC4
        VideoLogProfile.LOG3G10_RED -> LogCurvePreset.LOG3G10
        VideoLogProfile.LLOG_BT2020 -> LogCurvePreset.LLOG
        // 补丁新增项（本模块不引悬空符号：按名字解析，未打补丁时自然落 else）
        else -> LogCurvePreset.entries.firstOrNull { it.videoLogProfileName == profile.name }
    }

    /**
     * 由配置的曲线档位解析出相机实际应使用的 Log 档位：
     * 优先精确匹配档位，其次同名，再从可用档位里取第一个 Log 档位，全不可用则 OFF。
     */
    fun resolveProfile(
        config: LogVideoConfig,
        availableProfiles: List<VideoLogProfile>,
    ): VideoLogProfile {
        if (!config.enabled) return VideoLogProfile.OFF
        val wanted = config.curve.preset.videoLogProfile
        if (wanted != null && availableProfiles.contains(wanted)) return wanted
        return availableProfiles.firstOrNull { it.isEnabled } ?: VideoLogProfile.OFF
    }
}
