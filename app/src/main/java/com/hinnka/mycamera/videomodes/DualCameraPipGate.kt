package com.hinnka.mycamera.videomodes

/**
 * §16 画中画（**双摄并发**画中画：主摄 + 第二路相机同时出流，小窗叠加）。
 *
 * 核对结论：工程内**没有**双摄并发能力探测，`screencapture/ScreenCapturePipActivity.kt`
 * 用的是 Android 应用级 PiP（悬浮窗），与「双摄并发」不是同一件事，故本模块为新增。
 *
 * 硬原则（用户明确要求）：**能力不足自动隐藏并说明，绝不强行**。
 * 因此本策略在探测不到并发组合（`CameraManager.getConcurrentCameraIds()` 为空，
 * 目标机 K50G 出厂 ROM 是否上报未知）时，只会返回 `available=false + 原因`，
 * 不会伪造流组合，也不会让上层进入「两个 CameraDevice 抢同一路 ISP」的崩溃路径。
 */

/**
 * 双摄并发画中画开放配置。
 *
 * @param enabled 是否启用画中画录制，默认 false（既有行为 = 单摄）。
 * @param mainLensId 主画面相机 id；null = 自动（取并发组合里第一个可用）。
 * @param secondaryLensId 小窗相机 id；null = 自动。
 * @param secondaryFps 小窗帧率，范围 [24, 240]，默认 30（小窗不需要高帧率，省功耗）。
 * @param requireSeamlessSwitch 是否要求两路可无缝切换（不支持则视为能力不足），默认 false。
 */
data class DualCameraPipConfig(
    val enabled: Boolean = false,
    val mainLensId: String? = null,
    val secondaryLensId: String? = null,
    val secondaryFps: Int = VideoModesRanges.DEFAULT_FPS,
    val requireSeamlessSwitch: Boolean = false,
) {
    fun clampReport(): Pair<DualCameraPipConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val fps = secondaryFps.coerceIn(VideoModesRanges.FPS_MIN, VideoModesRanges.FPS_MAX)
        if (fps != secondaryFps) corrections += "secondaryFps $secondaryFps → $fps"
        return copy(secondaryFps = fps) to corrections
    }

    fun clamp(): DualCameraPipConfig = clampReport().first
}

/** 并发相机能力（由 [VideoModesCapabilityProbe] 从 CameraManager 填充）。 */
data class ConcurrentCameraCapability(
    /** `CameraManager.getConcurrentCameraIds()`：每个元素是一组可同时打开的相机 id。 */
    val concurrentCameraIdSets: List<Set<String>> = emptyList(),
    /**
     * 单相机可同时输出的最大流数（`CameraCharacteristics.get(SENSOR_INFO_... )` 无关，
     * 由 `SCALER_STREAM_CONFIGURATION_MAP` + HAL 上报的并发流能力推导；0 = 未知）。
     */
    val maxConcurrentStreamsPerCamera: Int = 0,
) {
    /** 是否上报了任何并发能力。 */
    val hasConcurrentSupport: Boolean get() = concurrentCameraIdSets.any { it.size >= 2 }
}

/** 画中画决策。 */
data class DualCameraPipDecision(
    /** 本机是否真的能做双摄并发画中画。 */
    val available: Boolean,
    /** 最终是否启用（= 用户开启 且 available）。 */
    val enabled: Boolean,
    val reason: String,
    val mainLensId: String?,
    val secondaryLensId: String?,
    val secondaryFps: Int,
)

/** 双摄并发画中画门控（纯逻辑）。 */
object DualCameraPipGate {

    fun resolve(
        config: DualCameraPipConfig,
        capability: ConcurrentCameraCapability,
        availableLensIds: List<String> = emptyList(),
    ): DualCameraPipDecision {
        val (clamped, _) = config.clampReport()

        if (!capability.hasConcurrentSupport) {
            return DualCameraPipDecision(
                available = false,
                enabled = false,
                reason = "设备未上报可同时打开的相机组合（CameraManager.getConcurrentCameraIds " +
                    "为空或仅单路），双摄并发画中画不可用——已自动隐藏，不会强行开启",
                mainLensId = null,
                secondaryLensId = null,
                secondaryFps = clamped.secondaryFps,
            )
        }

        val pairs = pairsFor(capability, availableLensIds)
        if (pairs.isEmpty()) {
            return DualCameraPipDecision(
                available = false,
                enabled = false,
                reason = "上报的并发组合里没有本工程可用的镜头对（需同时包含主摄与副摄 id）",
                mainLensId = null,
                secondaryLensId = null,
                secondaryFps = clamped.secondaryFps,
            )
        }

        val requested = clamped.mainLensId?.let { main ->
            clamped.secondaryLensId?.let { secondary -> main to secondary }
        }
        val resolvedPair = requested?.takeIf { pairs.contains(it) } ?: pairs.first()
        val usedFallback = requested != null && requested != resolvedPair

        return DualCameraPipDecision(
            available = true,
            enabled = clamped.enabled,
            reason = buildString {
                append("并发组合可用：主 ").append(resolvedPair.first)
                    .append(" + 副 ").append(resolvedPair.second)
                if (usedFallback) append("（用户指定的组合不被支持，已改用可用组合）")
                if (!clamped.enabled) append("；当前未启用")
            },
            mainLensId = resolvedPair.first,
            secondaryLensId = resolvedPair.second,
            secondaryFps = clamped.secondaryFps,
        )
    }

    /** 列出可用的镜头对（主 + 副，顺序稳定）。 */
    fun pairsFor(
        capability: ConcurrentCameraCapability,
        availableLensIds: List<String> = emptyList(),
    ): List<Pair<String, String>> {
        val preferred = availableLensIds.toSet()
        val result = LinkedHashSet<Pair<String, String>>()
        capability.concurrentCameraIdSets.forEach { set ->
            val ids = set.toList().filter { preferred.isEmpty() || preferred.contains(it) }.sorted()
            for (i in ids.indices) {
                for (j in ids.indices) {
                    if (i == j) continue
                    result += ids[i] to ids[j]
                }
            }
        }
        return result.toList()
    }
}
