package com.hinnka.mycamera.videomodes

import kotlin.math.abs

/**
 * §9 实时视频滤镜（录制中套用 LUT / 滤镜档位，档位与强度可配）。
 *
 * 核对结论（工程已有，禁止重复实现 GL 管线）：
 * - `lut/RealtimeVideoRenderer.kt`：实时视频渲染器；
 * - `lut/VideoColorEffectLayer.kt`：一层可实时烘焙的 LUT + 色彩配方 + 胶片质感
 *   （字段 `lutConfig` / `recipeParams` / `filmTexture` / `lutIntensity`，其中 `lutIntensity`
 *   文档明确范围 0f..2f，默认 1f）；
 * - `lut/VideoLutEffect.kt`：media3 `GlEffect` 封装；
 * - `ui/camera/VideoLogLutModeSelector.kt`：Log LUT 模式选择器。
 *
 * 本模块**只做档位编排与 clamp**，并把「Log 预览」与「滤镜档位」**并列**成互不覆盖的插槽：
 * - Log 预览由 logvideo 代理负责（本模块**不依赖其任何符号**，只接收一个
 *   `logProfileEnabled: Boolean` 入参）；
 * - 滤镜档位（LUT / 胶片质感）与 Log 预览可同时存在：Log 预览负责监看还原，
 *   滤镜档位叠加在其之上（与既有 `VideoLogLutMode.MONITOR_ONLY / BAKE_IN` 语义一致）。
 */

/** 滤镜插槽种类。 */
enum class VideoFilterSlotKind {
    /** 关闭（无色处理）。 */
    NONE,

    /** LUT 档位（实时 LUT 映射）。 */
    LUT,

    /** 胶片质感层（颗粒/暗角/漏光/柔光/锐化组合）。 */
    FILM_TEXTURE,

    /** Log 监看还原预览（由 logvideo 模块提供，本模块只占位不实现）。 */
    LOG_PREVIEW,
}

/** 单个滤镜插槽。 */
data class VideoFilterSlot(
    val id: String,
    val kind: VideoFilterSlotKind,
    val label: String,
    val enabled: Boolean,
    val intensity: Float,
)

/**
 * 实时滤镜开放配置。
 *
 * @param enabled 是否启用实时滤镜，默认 false（既有行为 = 关闭）。
 * @param lutId 生效 LUT id（null = 不套 LUT）。
 * @param intensity LUT 强度，范围 [0, 2]，默认 1.0（与既有 `VideoColorEffectLayer.lutIntensity` 一致）。
 * @param tierIndex UI 档位索引（0 = 原片，逐档叠加）；仅用于 UI 记忆，不改变语义。
 * @param filmTextureId 胶片质感预设 id（null = 不叠加）。
 * @param logPreviewCoexist 是否允许与 Log 预览并列共存，默认 true。
 */
data class RealtimeFilterConfig(
    val enabled: Boolean = false,
    val lutId: String? = null,
    val intensity: Float = VideoModesRanges.DEFAULT_FILTER_INTENSITY,
    val tierIndex: Int = 0,
    val filmTextureId: String? = null,
    val logPreviewCoexist: Boolean = true,
) {
    fun clampReport(): Pair<RealtimeFilterConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val requested = if (intensity.isFinite()) intensity else VideoModesRanges.DEFAULT_FILTER_INTENSITY
        val clamped = requested.coerceIn(
            VideoModesRanges.FILTER_INTENSITY_MIN,
            VideoModesRanges.FILTER_INTENSITY_MAX,
        )
        if (abs(clamped - requested) > 0.0001f) corrections += "intensity $requested → $clamped"
        val tier = tierIndex.coerceAtLeast(0)
        if (tier != tierIndex) corrections += "tierIndex $tierIndex → $tier"
        return copy(intensity = clamped, tierIndex = tier) to corrections
    }

    fun clamp(): RealtimeFilterConfig = clampReport().first
}

/** 滤镜插槽解析结果。 */
data class ResolvedFilterSlots(
    val slots: List<VideoFilterSlot>,
    /** 真正生效的滤镜插槽（null = 只做 Log 预览或全关）。 */
    val activeFilter: VideoFilterSlot?,
    /** Log 预览插槽是否存在（由 logvideo 模块负责渲染）。 */
    val logPreviewActive: Boolean,
    /** 滤镜是否叠加在 Log 预览之上（两者并列时的真实语义）。 */
    val appliesOnTopOfLog: Boolean,
    val corrections: List<String>,
)

/** 实时滤镜档位编排（纯逻辑）。 */
object RealtimeFilterSlots {

    /** UI 强度档位阶梯（覆盖 0~2 全区间）。 */
    val INTENSITY_LADDER: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** 档位步进：不在阶梯上时先吸附最近档位。 */
    fun stepIntensity(current: Float, forward: Boolean): Float {
        val clamped = current.takeIf { it.isFinite() }?.coerceIn(
            VideoModesRanges.FILTER_INTENSITY_MIN,
            VideoModesRanges.FILTER_INTENSITY_MAX,
        ) ?: VideoModesRanges.DEFAULT_FILTER_INTENSITY
        val index = INTENSITY_LADDER.indexOfFirst { abs(it - clamped) < 0.001f }
        if (index < 0) {
            return INTENSITY_LADDER.minByOrNull { abs(it - clamped) }
                ?: VideoModesRanges.DEFAULT_FILTER_INTENSITY
        }
        val next = if (forward) index + 1 else index - 1
        return INTENSITY_LADDER[next.coerceIn(0, INTENSITY_LADDER.lastIndex)]
    }

    /**
     * 编排插槽。
     *
     * @param logProfileEnabled 是否处于 Log 录制/预览（由 logvideo 模块决定；本模块只读该布尔值）。
     */
    fun resolve(config: RealtimeFilterConfig, logProfileEnabled: Boolean): ResolvedFilterSlots {
        val (clamped, corrections) = config.clampReport()
        val slots = mutableListOf<VideoFilterSlot>()

        if (logProfileEnabled) {
            slots += VideoFilterSlot(
                id = "log-preview",
                kind = VideoFilterSlotKind.LOG_PREVIEW,
                label = "Log 监看预览",
                enabled = true,
                intensity = 1f,
            )
        }

        val lutSlot = clamped.lutId?.takeIf { clamped.enabled && it.isNotBlank() }?.let { lutId ->
            VideoFilterSlot(
                id = lutId,
                kind = VideoFilterSlotKind.LUT,
                label = "LUT",
                enabled = true,
                intensity = clamped.intensity,
            )
        }
        val textureSlot = clamped.filmTextureId?.takeIf { clamped.enabled && it.isNotBlank() }?.let { texId ->
            VideoFilterSlot(
                id = texId,
                kind = VideoFilterSlotKind.FILM_TEXTURE,
                label = "胶片质感",
                enabled = true,
                intensity = clamped.intensity,
            )
        }
        lutSlot?.let { slots += it }
        textureSlot?.let { slots += it }

        // 与 Log 预览冲突消解：logvideo 要求独占时，滤镜插槽让位（保留配置，不丢用户设置）。
        val coexist = clamped.logPreviewCoexist
        val applyOnTop = logProfileEnabled && (lutSlot != null || textureSlot != null) && coexist
        val active = if (logProfileEnabled && !coexist) null else lutSlot ?: textureSlot

        return ResolvedFilterSlots(
            slots = slots,
            activeFilter = active,
            logPreviewActive = logProfileEnabled,
            appliesOnTopOfLog = applyOnTop,
            corrections = corrections,
        )
    }
}
