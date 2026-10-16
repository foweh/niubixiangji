package com.hinnka.mycamera.longexposure

import com.hinnka.mycamera.utils.PLog

/**
 * 长曝光玩法模式。
 */
enum class LongExposureMode {
    /** 光绘：暗帧扣除 + 增量 MAX 叠加 + 实时叠加预览。 */
    LIGHT_PAINTING,

    /** 慢门场景预设（车流光轨/瀑布拉丝/雨丝）。 */
    SLOW_SHUTTER,

    /** 烟火：短曝光 burst MAX 叠加 + 暗帧扣除。 */
    FIREWORKS,

    /** 超级月亮：多帧堆栈 + 月亮局部色调映射（高光保留）。 */
    SUPER_MOON,
}

/**
 * 帧叠加方式。
 * - [MAX] 逐像素取最大值（lighten）：光轨/烟火/星轨，亮部只增不减。
 * - [MEAN] 逐帧平均：雾化水流、降噪，动态范围更平滑。
 * 语义与既有 `AstroStacker.TrailAccumulationMode.LIGHTEN_MAX` / `PsStackType.MAX|MEAN` 一致。
 */
enum class LongExposureStackMode(val displayName: String) {
    MAX("最大值叠加"),
    MEAN("平均值叠加"),
}

/** 暗电流坏点（热噪点）处理方式。 */
enum class HotPixelMode(val displayName: String) {
    /** 关闭：仅做暗帧减法并截断到 0（默认）。 */
    OFF("关闭"),

    /** 置零掩膜：暗帧样本超过阈值的像素判为坏点，输出置 0（交由堆栈剔除）。 */
    ZERO("置零掩膜"),
}

/**
 * 长曝光开放度上限（所有模式共用；帧数**不设算法上限**，仅防误设）。
 *
 * 内存不够时的正确解法是**降低工作分辨率 / 分片 / 流式累加**，不是限制帧数
 * （PATTERN_SPEC §1.5/§4）。本模块的叠加器是 O(1) 增量的：帧数增加只拉长耗时，不放大峰值内存。
 */
object LongExposureLimits {
    /** 帧数下限（少于 2 帧无叠加意义）。 */
    const val FRAME_MIN = 2

    /** 帧数推荐上限（UI 滑杆默认顶值）。 */
    const val RECOMMENDED_FRAME_MAX = 1000

    /** 帧数绝对安全上限（仅防误设/溢出）。 */
    const val ABSOLUTE_FRAME_MAX = 4096

    /** 工作分辨率缩放下限（内存守卫降级的终点）。 */
    const val MIN_WORK_SCALE = 0.125f

    /** 工作分辨率缩放上限。 */
    const val MAX_WORK_SCALE = 1.0f

    /**
     * 运行时帧数上限（用户可配）。默认 [RECOMMENDED_FRAME_MAX]，下调/上调后由 [frameCountLimit] clamp。
     */
    @Volatile
    var userFrameCountLimit: Int = RECOMMENDED_FRAME_MAX

    /** 当前生效的帧数上限。 */
    fun frameCountLimit(): Int =
        userFrameCountLimit.coerceIn(FRAME_MIN, ABSOLUTE_FRAME_MAX)

    /** 统一的工作分辨率 clamp（0 表示"自动"，由内存守卫决定）。 */
    fun normalizeWorkLongEdge(value: Int): Int = when {
        value <= 0 -> 0
        else -> value.coerceIn(320, 8192)
    }

    /** 统一的工作缩放 clamp。 */
    fun normalizeWorkScale(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIN_WORK_SCALE, MAX_WORK_SCALE) else MAX_WORK_SCALE

    internal fun frameClampReport(name: String, raw: Int, clamped: Int, reports: MutableList<String>) {
        if (raw != clamped) reports.add("$name: $raw -> $clamped")
    }
}

/**
 * 暗帧扣除配置。
 *
 * 取值范围（越界 clamp）：
 *  - [frameCount] 1 .. 256（暗帧帧数可配）
 *  - [subtractGain] 0.0 .. 2.0（暗电流扣除增益；>1 用于暗电流随温度/增益放大时）
 *  - [hotPixelThreshold] 0 .. [LinearRgbPlane.SAMPLE_MAX]（0 = 关闭坏点判定）
 */
data class DarkFrameConfig(
    val enabled: Boolean = true,
    val frameCount: Int = 8,
    val subtractGain: Float = 1.0f,
    val hotPixelThreshold: Int = 0,
    val hotPixelMode: HotPixelMode = HotPixelMode.OFF,
    /** 是否在同一拍摄会话内复用已缓存的暗帧（避免重复拍暗帧）。 */
    val reuseCached: Boolean = true,
) {
    fun clampWithReport(): Pair<DarkFrameConfig, List<String>> {
        val reports = ArrayList<String>(3)
        val frames = frameCount.coerceIn(FRAME_MIN, FRAME_MAX)
        val gain = if (subtractGain.isFinite()) subtractGain.coerceIn(GAIN_MIN, GAIN_MAX) else 1f
        val threshold = hotPixelThreshold.coerceIn(0, LinearRgbPlane.SAMPLE_MAX)
        if (frames != frameCount) reports.add("darkFrame.frameCount: $frameCount -> $frames")
        if (gain != subtractGain) reports.add("darkFrame.subtractGain: $subtractGain -> $gain")
        if (threshold != hotPixelThreshold) {
            reports.add("darkFrame.hotPixelThreshold: $hotPixelThreshold -> $threshold")
        }
        return copy(frameCount = frames, subtractGain = gain, hotPixelThreshold = threshold) to reports
    }

    /** 便捷版：只取 clamp 结果，丢弃修正报告（与其它配置类的 API 保持一致）。 */
    fun clamped(): DarkFrameConfig = clampWithReport().first

    companion object {
        const val FRAME_MIN = 1
        const val FRAME_MAX = 256
        const val GAIN_MIN = 0f
        const val GAIN_MAX = 2f

        /** 默认配置（8 帧暗帧、增益 1.0、坏点判定关闭）。 */
        val DEFAULT = DarkFrameConfig()
    }
}

/**
 * 光绘配置。
 *
 * 取值范围（越界 clamp）：
 *  - [frameCount] 2 .. [LongExposureLimits.frameCountLimit]（默认 1000；运行时上限可配）
 *  - [brushColorTemperatureK] 1000 .. 12000（光源画笔色温）
 *  - [brushTint] -1.0 .. 1.0（绿-品红偏移，正=偏品红）
 *  - [brushGain] 0.0 .. 4.0
 *  - [previewIntervalFrames] 1 .. 256（每 N 帧产出一张实时预览）
 *  - [previewMaxLongEdge] 64 .. 2048
 *  - [previewThrottleMs] 16 .. 2000
 *  - [workScale] 0.125 .. 1.0；[workLongEdge] 0（自动）或 320 .. 8192
 */
data class LightPaintingConfig(
    val frameCount: Int = 30,
    val stackMode: LongExposureStackMode = LongExposureStackMode.MAX,
    val darkFrame: DarkFrameConfig = DarkFrameConfig(),
    val brushColorTemperatureK: Int = 3200,
    val brushTint: Float = 0.0f,
    val brushGain: Float = 1.0f,
    val incrementalPreviewEnabled: Boolean = true,
    val previewIntervalFrames: Int = 4,
    val previewMaxLongEdge: Int = 480,
    val previewThrottleMs: Long = 120L,
    val workScale: Float = 1.0f,
    val workLongEdge: Int = 0,
) {
    fun clampWithReport(): Pair<LightPaintingConfig, List<String>> {
        val reports = ArrayList<String>(6)
        val limit = LongExposureLimits.frameCountLimit()
        val frames = frameCount.coerceIn(LongExposureLimits.FRAME_MIN, limit)
        LongExposureLimits.frameClampReport("lightPainting.frameCount", frameCount, frames, reports)
        val temperature = brushColorTemperatureK.coerceIn(COLOR_TEMP_MIN, COLOR_TEMP_MAX)
        if (temperature != brushColorTemperatureK) {
            reports.add("lightPainting.brushColorTemperatureK: $brushColorTemperatureK -> $temperature")
        }
        val tint = finite(brushTint, 0f).coerceIn(TINT_MIN, TINT_MAX)
        if (tint != brushTint) reports.add("lightPainting.brushTint: $brushTint -> $tint")
        val gain = finite(brushGain, 1f).coerceIn(BRUSH_GAIN_MIN, BRUSH_GAIN_MAX)
        if (gain != brushGain) reports.add("lightPainting.brushGain: $brushGain -> $gain")
        val interval = previewIntervalFrames.coerceIn(PREVIEW_INTERVAL_MIN, PREVIEW_INTERVAL_MAX)
        if (interval != previewIntervalFrames) {
            reports.add("lightPainting.previewIntervalFrames: $previewIntervalFrames -> $interval")
        }
        val previewEdge = previewMaxLongEdge.coerceIn(PREVIEW_EDGE_MIN, PREVIEW_EDGE_MAX)
        if (previewEdge != previewMaxLongEdge) {
            reports.add("lightPainting.previewMaxLongEdge: $previewMaxLongEdge -> $previewEdge")
        }
        val throttle = previewThrottleMs.coerceIn(PREVIEW_THROTTLE_MIN, PREVIEW_THROTTLE_MAX)
        if (throttle != previewThrottleMs) {
            reports.add("lightPainting.previewThrottleMs: $previewThrottleMs -> $throttle")
        }
        val (dark, darkReports) = darkFrame.clampWithReport()
        reports.addAll(darkReports)
        return copy(
            frameCount = frames,
            darkFrame = dark,
            brushColorTemperatureK = temperature,
            brushTint = tint,
            brushGain = gain,
            previewIntervalFrames = interval,
            previewMaxLongEdge = previewEdge,
            previewThrottleMs = throttle,
            workScale = LongExposureLimits.normalizeWorkScale(workScale),
            workLongEdge = LongExposureLimits.normalizeWorkLongEdge(workLongEdge),
        ) to reports
    }

    /** clamp 便利版。 */
    fun clamped(): LightPaintingConfig = clampWithReport().first

    companion object {
        const val COLOR_TEMP_MIN = 1000
        const val COLOR_TEMP_MAX = 12000
        const val TINT_MIN = -1f
        const val TINT_MAX = 1f
        const val BRUSH_GAIN_MIN = 0f
        const val BRUSH_GAIN_MAX = 4f
        const val PREVIEW_INTERVAL_MIN = 1
        const val PREVIEW_INTERVAL_MAX = 256
        const val PREVIEW_EDGE_MIN = 64
        const val PREVIEW_EDGE_MAX = 2048
        const val PREVIEW_THROTTLE_MIN = 16L
        const val PREVIEW_THROTTLE_MAX = 2000L

        /** 默认配置（30 帧、MAX 叠加、3200K 画笔、4 帧一预览）。 */
        val DEFAULT = LightPaintingConfig()

        private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
    }
}

/**
 * 烟火模式配置。
 *
 * 取值范围（越界 clamp）：
 *  - [frameCount] 2 .. [LongExposureLimits.frameCountLimit]
 *  - [burstShutterSpeedNs] 1_000_000 .. 500_000_000（1ms .. 0.5s 的短曝光）
 *  - [iso] 50 .. 102400
 *  - [preBurstFrames] 0 .. 64（发射前基线帧数，供 MAX 叠加前建立底色）
 *  - [workScale] / [workLongEdge] 同光绘
 */
data class FireworksConfig(
    val frameCount: Int = 20,
    val stackMode: LongExposureStackMode = LongExposureStackMode.MAX,
    val darkFrame: DarkFrameConfig = DarkFrameConfig(enabled = true, frameCount = 4),
    val burstShutterSpeedNs: Long = 33_333_333L,
    val iso: Int = 400,
    val preBurstFrames: Int = 0,
    val workScale: Float = 1.0f,
    val workLongEdge: Int = 0,
) {
    fun clampWithReport(): Pair<FireworksConfig, List<String>> {
        val reports = ArrayList<String>(4)
        val limit = LongExposureLimits.frameCountLimit()
        val frames = frameCount.coerceIn(LongExposureLimits.FRAME_MIN, limit)
        LongExposureLimits.frameClampReport("fireworks.frameCount", frameCount, frames, reports)
        val shutter = burstShutterSpeedNs.coerceIn(SHUTTER_MIN_NS, SHUTTER_MAX_NS)
        if (shutter != burstShutterSpeedNs) {
            reports.add("fireworks.burstShutterSpeedNs: $burstShutterSpeedNs -> $shutter")
        }
        val sensitivity = iso.coerceIn(ISO_MIN, ISO_MAX)
        if (sensitivity != iso) reports.add("fireworks.iso: $iso -> $sensitivity")
        val pre = preBurstFrames.coerceIn(0, PRE_BURST_MAX)
        if (pre != preBurstFrames) reports.add("fireworks.preBurstFrames: $preBurstFrames -> $pre")
        val (dark, darkReports) = darkFrame.clampWithReport()
        reports.addAll(darkReports)
        return copy(
            frameCount = frames,
            darkFrame = dark,
            burstShutterSpeedNs = shutter,
            iso = sensitivity,
            preBurstFrames = pre,
            workScale = LongExposureLimits.normalizeWorkScale(workScale),
            workLongEdge = LongExposureLimits.normalizeWorkLongEdge(workLongEdge),
        ) to reports
    }

    fun clamped(): FireworksConfig = clampWithReport().first

    companion object {
        const val SHUTTER_MIN_NS = 1_000_000L
        const val SHUTTER_MAX_NS = 500_000_000L
        const val ISO_MIN = 50
        const val ISO_MAX = 102_400
        const val PRE_BURST_MAX = 64

        /** 默认配置（20 帧、1/30s、ISO 400）。 */
        val DEFAULT = FireworksConfig()
    }
}

/**
 * 月亮局部色调映射配置（高光保留）。
 *
 * 取值范围（越界 clamp）：
 *  - [highlightThreshold] 0.05 .. 0.99（高光肩部起点，归一化亮度）
 *  - [highlightCompression] 0.0 .. 4.0（肩部压缩强度，越大高光越柔）
 *  - [shadowLift] 0.0 .. 1.0（暗部提升指数，纯黑保持 0）
 *  - [gamma] 0.3 .. 3.0（最终 gamma）
 *  - [localRadius] 2 .. 256（局部对比盒滤波半径，像素）
 *  - [localAmount] 0.0 .. 2.0（局部对比强度，高光区自动衰减）
 */
data class LocalToneMapConfig(
    val enabled: Boolean = true,
    val highlightThreshold: Float = 0.80f,
    val highlightCompression: Float = 1.20f,
    val shadowLift: Float = 0.15f,
    val gamma: Float = 1.0f,
    val localRadius: Int = 24,
    val localAmount: Float = 0.35f,
) {
    fun clampWithReport(): Pair<LocalToneMapConfig, List<String>> {
        val reports = ArrayList<String>(4)
        val threshold = finite(highlightThreshold, 0.80f).coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        if (threshold != highlightThreshold) {
            reports.add("toneMap.highlightThreshold: $highlightThreshold -> $threshold")
        }
        val compression = finite(highlightCompression, 1.20f).coerceIn(COMPRESSION_MIN, COMPRESSION_MAX)
        if (compression != highlightCompression) {
            reports.add("toneMap.highlightCompression: $highlightCompression -> $compression")
        }
        val lift = finite(shadowLift, 0.15f).coerceIn(LIFT_MIN, LIFT_MAX)
        if (lift != shadowLift) reports.add("toneMap.shadowLift: $shadowLift -> $lift")
        val g = finite(gamma, 1.0f).coerceIn(GAMMA_MIN, GAMMA_MAX)
        if (g != gamma) reports.add("toneMap.gamma: $gamma -> $g")
        val radius = localRadius.coerceIn(RADIUS_MIN, RADIUS_MAX)
        if (radius != localRadius) reports.add("toneMap.localRadius: $localRadius -> $radius")
        val amount = finite(localAmount, 0.35f).coerceIn(AMOUNT_MIN, AMOUNT_MAX)
        if (amount != localAmount) reports.add("toneMap.localAmount: $localAmount -> $amount")
        return copy(
            highlightThreshold = threshold,
            highlightCompression = compression,
            shadowLift = lift,
            gamma = g,
            localRadius = radius,
            localAmount = amount,
        ) to reports
    }

    fun clamped(): LocalToneMapConfig = clampWithReport().first

    companion object {
        const val THRESHOLD_MIN = 0.05f
        const val THRESHOLD_MAX = 0.99f
        const val COMPRESSION_MIN = 0f
        const val COMPRESSION_MAX = 4f
        const val LIFT_MIN = 0f
        const val LIFT_MAX = 1f
        const val GAMMA_MIN = 0.3f
        const val GAMMA_MAX = 3f
        const val RADIUS_MIN = 2
        const val RADIUS_MAX = 256
        const val AMOUNT_MIN = 0f
        const val AMOUNT_MAX = 2f

        /** 默认配置（阈值 0.8、肩部 1.2、暗部 0.15、半径 24、局部 0.35）。 */
        val DEFAULT = LocalToneMapConfig()

        private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
    }
}

/**
 * 超级月亮配置（多帧堆栈 + 局部色调映射）。
 *
 * 取值范围（越界 clamp）：
 *  - [frameCount] 2 .. [LongExposureLimits.frameCountLimit]
 *  - [sigmaClipSigma] 1.0 .. 5.0（sigma-clip 倍数，复用 AstroStacker 默认 2.5）
 *  - [workScale] / [workLongEdge] 同光绘
 */
data class SuperMoonConfig(
    val frameCount: Int = 40,
    val align: Boolean = true,
    val sigmaClipSigma: Float = 2.5f,
    val workScale: Float = 1.0f,
    val workLongEdge: Int = 0,
    val toneMap: LocalToneMapConfig = LocalToneMapConfig(),
) {
    fun clampWithReport(): Pair<SuperMoonConfig, List<String>> {
        val reports = ArrayList<String>(3)
        val limit = LongExposureLimits.frameCountLimit()
        val frames = frameCount.coerceIn(LongExposureLimits.FRAME_MIN, limit)
        LongExposureLimits.frameClampReport("superMoon.frameCount", frameCount, frames, reports)
        val sigma = if (sigmaClipSigma.isFinite()) sigmaClipSigma.coerceIn(SIGMA_MIN, SIGMA_MAX) else 2.5f
        if (sigma != sigmaClipSigma) reports.add("superMoon.sigmaClipSigma: $sigmaClipSigma -> $sigma")
        val (tone, toneReports) = toneMap.clampWithReport()
        reports.addAll(toneReports)
        return copy(
            frameCount = frames,
            sigmaClipSigma = sigma,
            toneMap = tone,
            workScale = LongExposureLimits.normalizeWorkScale(workScale),
            workLongEdge = LongExposureLimits.normalizeWorkLongEdge(workLongEdge),
        ) to reports
    }

    fun clamped(): SuperMoonConfig = clampWithReport().first

    companion object {
        const val SIGMA_MIN = 1f
        const val SIGMA_MAX = 5f

        /** 默认配置（40 帧、对齐、sigma 2.5）。 */
        val DEFAULT = SuperMoonConfig()
    }
}

/**
 * 把全部长曝光配置一次性 clamp（供 UI/后台任务入口统一使用）。
 */
object LongExposureConfigCodec {

    private const val TAG = "LongExposureConfig"

    /** 结果三元组：光绘 / 烟火 / 超级月亮，均已 clamp。 */
    data class Clamped(
        val lightPainting: LightPaintingConfig,
        val fireworks: FireworksConfig,
        val superMoon: SuperMoonConfig,
        val reports: List<String>,
    )

    fun clampAll(
        lightPainting: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        fireworks: FireworksConfig = FireworksConfig.DEFAULT,
        superMoon: SuperMoonConfig = SuperMoonConfig.DEFAULT,
    ): Clamped {
        val (lp, lpReports) = lightPainting.clampWithReport()
        val (fw, fwReports) = fireworks.clampWithReport()
        val (sm, smReports) = superMoon.clampWithReport()
        val reports = lpReports + fwReports + smReports
        if (reports.isNotEmpty()) PLog.w(TAG, "long exposure config clamped: ${reports.joinToString()}")
        return Clamped(lp, fw, sm, reports)
    }

    /**
     * 从偏好读取配置（读取器由集成层注入，避免本模块依赖具体 DataStore 实现）。
     *
     * key 清单见补丁 `delivery/patches/longexposure_patch.md` 的 CONFIG 段；
     * 缺省或类型不符回退默认值，**读取后一律 clamp**。
     */
    fun fromPreferences(read: (String) -> Any?): Clamped {
        fun intOf(key: String, fallback: Int) = (read(key) as? Number)?.toInt() ?: fallback
        fun floatOf(key: String, fallback: Float) = (read(key) as? Number)?.toFloat() ?: fallback
        fun longOf(key: String, fallback: Long) = (read(key) as? Number)?.toLong() ?: fallback
        fun boolOf(key: String, fallback: Boolean) = (read(key) as? Boolean) ?: fallback

        val lightPainting = LightPaintingConfig.DEFAULT.copy(
            frameCount = intOf(KEY_LP_FRAMES, LightPaintingConfig.DEFAULT.frameCount),
            stackMode = enumOf(read(KEY_LP_STACK_MODE), LightPaintingConfig.DEFAULT.stackMode),
            brushColorTemperatureK = intOf(
                KEY_LP_BRUSH_TEMPERATURE,
                LightPaintingConfig.DEFAULT.brushColorTemperatureK,
            ),
            brushTint = floatOf(KEY_LP_BRUSH_TINT, LightPaintingConfig.DEFAULT.brushTint),
            brushGain = floatOf(KEY_LP_BRUSH_GAIN, LightPaintingConfig.DEFAULT.brushGain),
            incrementalPreviewEnabled = boolOf(KEY_LP_PREVIEW, LightPaintingConfig.DEFAULT.incrementalPreviewEnabled),
            previewIntervalFrames = intOf(KEY_LP_PREVIEW_INTERVAL, LightPaintingConfig.DEFAULT.previewIntervalFrames),
            darkFrame = DarkFrameConfig.DEFAULT.copy(
                enabled = boolOf(KEY_LP_DARK_ENABLED, DarkFrameConfig.DEFAULT.enabled),
                frameCount = intOf(KEY_LP_DARK_FRAMES, DarkFrameConfig.DEFAULT.frameCount),
                subtractGain = floatOf(KEY_LP_DARK_GAIN, DarkFrameConfig.DEFAULT.subtractGain),
            ),
        )
        val fireworks = FireworksConfig.DEFAULT.copy(
            frameCount = intOf(KEY_FW_FRAMES, FireworksConfig.DEFAULT.frameCount),
            stackMode = enumOf(read(KEY_FW_STACK_MODE), FireworksConfig.DEFAULT.stackMode),
            burstShutterSpeedNs = longOf(KEY_FW_SHUTTER_NS, FireworksConfig.DEFAULT.burstShutterSpeedNs),
            iso = intOf(KEY_FW_ISO, FireworksConfig.DEFAULT.iso),
        )
        val superMoon = SuperMoonConfig.DEFAULT.copy(
            frameCount = intOf(KEY_SM_FRAMES, SuperMoonConfig.DEFAULT.frameCount),
            align = boolOf(KEY_SM_ALIGN, SuperMoonConfig.DEFAULT.align),
            toneMap = LocalToneMapConfig.DEFAULT.copy(
                enabled = boolOf(KEY_SM_TONEMAP, LocalToneMapConfig.DEFAULT.enabled),
                highlightThreshold = floatOf(
                    KEY_SM_HIGHLIGHT_THRESHOLD,
                    LocalToneMapConfig.DEFAULT.highlightThreshold,
                ),
                localAmount = floatOf(KEY_SM_LOCAL_AMOUNT, LocalToneMapConfig.DEFAULT.localAmount),
            ),
        )
        return clampAll(lightPainting, fireworks, superMoon)
    }

    /** 帧数上限运行时配置入口（设置页调用；clamp 后写回 [LongExposureLimits]）。 */
    fun setFrameCountLimit(value: Int): Int {
        val clamped = value.coerceIn(LongExposureLimits.FRAME_MIN, LongExposureLimits.ABSOLUTE_FRAME_MAX)
        LongExposureLimits.userFrameCountLimit = clamped
        if (clamped != value) {
            PLog.w(TAG, "frame count limit clamped: $value -> $clamped")
        }
        return clamped
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: Any?, fallback: T): T {
        val name = raw as? String ?: return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    const val KEY_LP_FRAMES = "long_exposure_light_painting_frames"
    const val KEY_LP_STACK_MODE = "long_exposure_light_painting_stack_mode"
    const val KEY_LP_BRUSH_TEMPERATURE = "long_exposure_light_painting_brush_temperature_k"
    const val KEY_LP_BRUSH_TINT = "long_exposure_light_painting_brush_tint"
    const val KEY_LP_BRUSH_GAIN = "long_exposure_light_painting_brush_gain"
    const val KEY_LP_PREVIEW = "long_exposure_light_painting_preview"
    const val KEY_LP_PREVIEW_INTERVAL = "long_exposure_light_painting_preview_interval"
    const val KEY_LP_DARK_ENABLED = "long_exposure_dark_frame_enabled"
    const val KEY_LP_DARK_FRAMES = "long_exposure_dark_frame_count"
    const val KEY_LP_DARK_GAIN = "long_exposure_dark_frame_gain"
    const val KEY_FW_FRAMES = "long_exposure_fireworks_frames"
    const val KEY_FW_STACK_MODE = "long_exposure_fireworks_stack_mode"
    const val KEY_FW_SHUTTER_NS = "long_exposure_fireworks_shutter_ns"
    const val KEY_FW_ISO = "long_exposure_fireworks_iso"
    const val KEY_SM_FRAMES = "long_exposure_super_moon_frames"
    const val KEY_SM_ALIGN = "long_exposure_super_moon_align"
    const val KEY_SM_TONEMAP = "long_exposure_super_moon_tonemap"
    const val KEY_SM_HIGHLIGHT_THRESHOLD = "long_exposure_super_moon_highlight_threshold"
    const val KEY_SM_LOCAL_AMOUNT = "long_exposure_super_moon_local_amount"
    const val KEY_FRAME_COUNT_LIMIT = "long_exposure_frame_count_limit"
}
