package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.lut.FilmTextureParams
import com.hinnka.mycamera.lut.LutConfig
import com.hinnka.mycamera.lut.VideoColorEffectLayer
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.video.VideoLogProfile

/**
 * Log 视频链路图层工厂（Log 视频 20 项 · 第 1/2 项的最小接线点）。
 *
 * **复用优先（PATTERN_SPEC §9）= 本文件是「胶水」而非「渲染器」**：
 * 录制路径的 Log 曲线烘焙、LUT 烘焙、胶片质感层全部由工程既有
 * `RealtimeVideoRenderer` + `VideoColorEffectLayer` + `RealtimeVideoRenderer.drawLayer`
 * （`enableVideoLog = videoLogProfile.isEnabled`）完成；预览路径由 `LutRenderer`
 * （`enableVideoLog = true`）完成；本文件只把「配置 → 既有图层列表」翻译一次。
 *
 * 用法（patch 的 `Camera2Controller` 接线点）：
 * ```
 * videoEffectLayers = LogVideoLayerFactory.recordingLayers(
 *     logProfile = videoConfig.logProfile,
 *     config = LogVideoConfigStore.current,
 *     monitorLut = currentLutConfig,
 *     monitorRecipe = currentRecipeParams,
 * )
 * ```
 */
object LogVideoLayerFactory {

    /**
     * 生成**录制**路径的图层列表（0 或 1 层；多图层叠加由 `RealtimeVideoRenderer` 既有能力承载）。
     *
     * 语义与工程现状严格一致（见 [LogVideoLutDecision]）：
     *  - Log OFF：LUT + 配方都进片（普通视频，原行为）；
     *  - Log ON + MONITOR_ONLY：都不进片，录制只留纯 Log（预览仍由 `LutRenderer` 套 LUT）；
     *  - Log ON + BAKE_IN：LUT（强度可调）+ 配方进片，LUT 不匹配/不可用时自动回退并保持 Log 纯净。
     */
    fun recordingLayers(
        logProfile: VideoLogProfile,
        config: LogVideoConfig,
        monitorLut: LutConfig?,
        monitorRecipe: ColorRecipeParams?,
        filmTexture: FilmTextureParams? = null,
    ): List<VideoColorEffectLayer> {
        val decision = LogVideoLutDecision.decide(
            profile = logProfile,
            config = config,
            lutCurve = monitorLut?.curve,
            lutColorSpace = monitorLut?.colorSpace,
            lutAvailable = monitorLut != null,
        )
        if (!decision.bakeLutIntoRecording && !decision.bakeRecipeIntoRecording) {
            // MONITOR_ONLY：录制帧保持纯 Log（不叠加任何色彩层），这是招牌功能的语义核心。
            return emptyList()
        }
        return listOf(
            VideoColorEffectLayer(
                lutConfig = monitorLut.takeIf { decision.bakeLutIntoRecording },
                recipeParams = monitorRecipe.takeIf { decision.bakeRecipeIntoRecording },
                filmTexture = filmTexture,
                lutIntensity = decision.effectiveIntensity,
            )
        )
    }

    /**
     * 生成**预览**路径的图层列表：Log 视频一律把还原 LUT 套到预览上（边拍边看电影感画面）。
     *
     * 预览的 LUT 不写进录制帧（录制帧由 [recordingLayers] 决定），因此预览与录制可以
     * 语义不同——这正是 MONITOR_ONLY / BAKE_IN 的区别所在。
     */
    fun previewLayers(
        logProfile: VideoLogProfile,
        config: LogVideoConfig,
        restoreLut: LutConfig?,
        previewRecipe: ColorRecipeParams?,
        filmTexture: FilmTextureParams? = null,
    ): List<VideoColorEffectLayer> {
        if (!LogVideoLutDecision.shouldPreviewLut(logProfile, config)) return emptyList()
        if (restoreLut == null) return emptyList()
        val intensity = config.lutIntensity
            .coerceIn(LogVideoConfig.LUT_INTENSITY_MIN, LogVideoConfig.LUT_INTENSITY_MAX)
        return listOf(
            VideoColorEffectLayer(
                lutConfig = restoreLut,
                recipeParams = previewRecipe,
                filmTexture = filmTexture,
                lutIntensity = intensity,
            )
        )
    }

    /**
     * 录制 Log 曲线微调（GPU uniform）取值：未开启 / 原生档位 → 恒等（零回归）。
     */
    fun trim(config: LogVideoConfig): LogCurveTrim = LogCurveTrim.from(config)

    /** 供日志/自检的一句话结论（录制烘焙接线结论）。 */
    fun describe(
        logProfile: VideoLogProfile,
        config: LogVideoConfig,
        lutAvailable: Boolean,
    ): String {
        val decision = LogVideoLutDecision.decide(
            profile = logProfile,
            config = config,
            lutCurve = null,
            lutColorSpace = null,
            lutAvailable = lutAvailable,
        )
        return "profile=${logProfile.name}(${logProfile.logCurve.name}/${logProfile.colorSpace.name}) " +
            "bakeLut=${decision.bakeLutIntoRecording} bakeRecipe=${decision.bakeRecipeIntoRecording} " +
            "intensity=${decision.effectiveIntensity} degraded=${decision.degraded} :: ${decision.reason}"
    }
}
