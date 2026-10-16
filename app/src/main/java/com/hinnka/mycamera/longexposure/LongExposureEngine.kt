package com.hinnka.mycamera.longexposure

import com.hinnka.mycamera.computational.BgJob

/**
 * 长曝光玩法统一门面（UI/ViewModel 唯一入口）。
 *
 * 覆盖四种玩法的**采集参数**与**后台处理**：
 *  | 玩法 | 采集侧 | 处理侧 |
 *  |---|---|---|
 *  | [LongExposureMode.LIGHT_PAINTING] | 开放帧数 + 暗帧 | [LightPaintingSession]（实时增量叠加 + 预览队列）或 [submitLightPainting]（拍后批处理） |
 *  | [LongExposureMode.SLOW_SHUTTER] | 预设曝光/ISO/帧数 | [submitSlowShutter]（MAX 光轨 / MEAN 拉丝） |
 *  | [LongExposureMode.FIREWORKS] | 短曝光 burst + 暗帧 | [submitFireworks] |
 *  | [LongExposureMode.SUPER_MOON] | 多帧 | [SuperMoonProcessor]（复用 MultiFrameStacker / LinearDngMerger 异步入口 + 色调映射） |
 *
 * 帧数策略（PATTERN_SPEC §1.5/§4）：**不设总量硬上限**，内存不足由
 * [LongExposureJobRunner] 降工作分辨率解决。
 */
object LongExposureEngine {

    // ------------------------------------------------------------- 采集参数

    /** 该模式需要拍摄的暗帧数量（0 = 不需要暗帧；采集侧据此决定是否先拍暗帧）。 */
    fun darkFrameCountFor(mode: LongExposureMode, config: LongExposureConfigSet = LongExposureConfigSet.DEFAULT): Int {
        val dark = when (mode) {
            LongExposureMode.LIGHT_PAINTING -> config.lightPainting.clamped().darkFrame
            LongExposureMode.FIREWORKS -> config.fireworks.clamped().darkFrame
            LongExposureMode.SLOW_SHUTTER -> if (config.slowShutterPreset?.darkFrameEnabled == true) {
                config.lightPainting.clamped().darkFrame
            } else {
                DarkFrameConfig.DEFAULT.copy(enabled = false)
            }

            LongExposureMode.SUPER_MOON -> DarkFrameConfig.DEFAULT.copy(enabled = false)
        }
        return if (dark.enabled) dark.frameCount else 0
    }

    /** 光绘会话规格。 */
    fun lightPaintingSpec(config: LightPaintingConfig): LongExposureSessionSpec =
        LongExposureSessionSpec.fromLightPainting(config)

    /** 慢门预设会话规格。 */
    fun slowShutterSpec(
        preset: SlowShutterPreset,
        previewConfig: LightPaintingConfig = LightPaintingConfig.DEFAULT,
    ): LongExposureSessionSpec = LongExposureSessionSpec.fromPreset(preset, previewConfig)

    /** 烟火会话规格。 */
    fun fireworksSpec(config: FireworksConfig): LongExposureSessionSpec =
        LongExposureSessionSpec.fromFireworks(config)

    /**
     * 创建实时会话（光绘 / 烟火 / 慢门的"边拍边叠"路径）。
     *
     * @param onPreview 实时叠加预览回调（后台线程，低分辨率）；为 null 时不启用预览线程。
     */
    fun createSession(
        spec: LongExposureSessionSpec,
        onPreview: ((LinearRgbPlane, Int) -> Unit)? = null,
    ): LightPaintingSession = LightPaintingSession(spec, onPreview)

    /** 创建独立的实时叠加预览队列（已有会话时可另行挂载；不传 onPreview 则无需创建）。 */
    fun createPreviewQueue(
        config: LightPaintingConfig,
        onPreview: (LinearRgbPlane, Int) -> Unit,
    ): LightPaintingPreviewQueue = LightPaintingPreviewQueue(config, onPreview)

    // ------------------------------------------------------------- 拍后批处理

    /** 光绘：暗帧扣除 + 增量 MAX 叠加（拍后批处理路径，分片落盘/内存帧源均可）。 */
    fun submitLightPainting(
        source: LongExposureJobRunner.FrameSource,
        darkFrames: List<LinearRgbPlane> = emptyList(),
        config: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        jobName: String = "光绘",
        previewEnabled: Boolean = false,
        onPreview: ((LinearRgbPlane, Int) -> Unit)? = null,
        onOutcome: ((LongExposureJobRunner.RunOutcome) -> Unit)? = null,
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob = LongExposureJobRunner.submit(
        request = LongExposureJobRunner.Request(
            mode = LongExposureMode.LIGHT_PAINTING,
            source = source,
            darkFrames = darkFrames,
            lightPainting = config,
        ),
        jobName = jobName,
        previewEnabled = previewEnabled && config.clamped().incrementalPreviewEnabled,
        onPreview = onPreview,
        onOutcome = onOutcome,
        onFinish = onFinish,
    )

    /** 慢门：按预设的叠加类型与帧数处理（曝光/ISO 属采集侧，不影响叠加）。 */
    fun submitSlowShutter(
        preset: SlowShutterPreset,
        source: LongExposureJobRunner.FrameSource,
        darkFrames: List<LinearRgbPlane> = emptyList(),
        previewConfig: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        jobName: String = "慢门",
        onOutcome: ((LongExposureJobRunner.RunOutcome) -> Unit)? = null,
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob = LongExposureJobRunner.submit(
        request = LongExposureJobRunner.Request(
            mode = LongExposureMode.SLOW_SHUTTER,
            source = source,
            darkFrames = darkFrames,
            lightPainting = previewConfig,
            preset = preset,
        ),
        jobName = "$jobName·${preset.clamped().displayName}",
        onOutcome = onOutcome,
        onFinish = onFinish,
    )

    /** 烟火：短曝光 burst MAX 叠加 + 暗帧扣除。 */
    fun submitFireworks(
        source: LongExposureJobRunner.FrameSource,
        darkFrames: List<LinearRgbPlane> = emptyList(),
        config: FireworksConfig = FireworksConfig.DEFAULT,
        jobName: String = "烟火",
        onOutcome: ((LongExposureJobRunner.RunOutcome) -> Unit)? = null,
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob = LongExposureJobRunner.submit(
        request = LongExposureJobRunner.Request(
            mode = LongExposureMode.FIREWORKS,
            source = source,
            darkFrames = darkFrames,
            fireworks = config,
        ),
        jobName = jobName,
        onOutcome = onOutcome,
        onFinish = onFinish,
    )

    /** 超级月亮（平面路径）：MEAN 堆栈 + 月亮局部色调映射。 */
    fun submitSuperMoon(
        source: LongExposureJobRunner.FrameSource,
        config: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        jobName: String = "超级月亮",
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob = SuperMoonProcessor.stackPlanesAsync(source, config, jobName, onFinish)
}

/**
 * 长曝光配置集合：一次拍摄会话可能需要同时持有四套配置。
 */
data class LongExposureConfigSet(
    val lightPainting: LightPaintingConfig = LightPaintingConfig.DEFAULT,
    val fireworks: FireworksConfig = FireworksConfig.DEFAULT,
    val superMoon: SuperMoonConfig = SuperMoonConfig.DEFAULT,
    /** 慢门当前选中的预设（可空）。 */
    val slowShutterPreset: SlowShutterPreset? = null,
    val presetRegistry: SlowShutterPresetRegistry = SlowShutterPresetRegistry(),
) {
    fun clamped(): LongExposureConfigSet {
        val all = LongExposureConfigCodec.clampAll(lightPainting, fireworks, superMoon)
        return copy(lightPainting = all.lightPainting, fireworks = all.fireworks, superMoon = all.superMoon)
    }

    companion object {
        val DEFAULT = LongExposureConfigSet()

        /** 从偏好读取（读取器由集成层注入）。 */
        fun fromPreferences(read: (String) -> Any?): LongExposureConfigSet {
            val all = LongExposureConfigCodec.fromPreferences(read)
            return LongExposureConfigSet(all.lightPainting, all.fireworks, all.superMoon)
        }
    }
}
