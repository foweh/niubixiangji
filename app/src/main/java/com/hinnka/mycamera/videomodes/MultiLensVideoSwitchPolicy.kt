package com.hinnka.mycamera.videomodes

/**
 * §6 多镜头切换强化（主摄 / 超广角 / 微距，含**录制中**切换的开放与门控）。
 *
 * 核对结论（工程已有，禁止重复）：
 * - 既有 `video/VideoCapabilitiesResolver` 与 `Camera2Controller` 已实现镜头切换与
 *   连续变焦（`ui/camera/ZoomControlBar.kt`、`ContinuousZoomStopSettlement.kt`、
 *   `LensSwitchTransition.kt` 等 UI 亦已存在）；
 * - 本模块**不改既有切换实现**，只补：
 *   1. 「录制中允许切换哪几颗镜头」的白名单与门控（默认全放开 = 零回归）；
 *   2. 切换后的**稳定等待时间**（避免切镜头瞬间出黑帧被编码进视频）；
 *   3. 无缝切换（HAL 支持时）的可用性判定与回退说明。
 *
 * 目标机三摄：主摄 IMX686 / 超广角 OV8856（定焦）/ 微距 GC02M1。
 */

/**
 * 多镜头切换开放配置。
 *
 * @param enabled 是否允许录制中切换镜头，默认 true（既有行为 = 可切）。
 * @param allowedLensIds 允许的镜头 id 白名单；空 = 不限制（全部可用镜头）。
 * @param switchDuringRecording 录制中是否允许切换，默认 true。
 * @param seamlessSwitch 是否要求/尝试无缝切换（HAL 支持时），默认 false。
 * @param switchSettleMs 切换后稳定等待，范围 [0, 5000] ms，默认 300（避免黑帧入片）。
 */
data class MultiLensSwitchConfig(
    val enabled: Boolean = true,
    val allowedLensIds: List<String> = emptyList(),
    val switchDuringRecording: Boolean = true,
    val seamlessSwitch: Boolean = false,
    val switchSettleMs: Long = VideoModesRanges.DEFAULT_LENS_SWITCH_SETTLE_MS,
) {
    fun clampReport(): Pair<MultiLensSwitchConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val settle = switchSettleMs.coerceIn(
            VideoModesRanges.LENS_SWITCH_SETTLE_MS_MIN,
            VideoModesRanges.LENS_SWITCH_SETTLE_MS_MAX,
        )
        if (settle != switchSettleMs) corrections += "switchSettleMs $switchSettleMs → $settle"
        val cleanIds = allowedLensIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.size != allowedLensIds.size) corrections += "allowedLensIds 已去空/去重"
        return copy(switchSettleMs = settle, allowedLensIds = cleanIds) to corrections
    }

    fun clamp(): MultiLensSwitchConfig = clampReport().first
}

/** 镜头切换决策。 */
data class LensSwitchDecision(
    val allowed: Boolean,
    val seamless: Boolean,
    val targetLensId: String?,
    val settleMs: Long,
    val reason: String,
)

/** 多镜头切换策略（纯逻辑）。 */
object MultiLensVideoSwitchPolicy {

    /**
     * 判定一次切换是否被允许。
     *
     * @param targetLensId 目标镜头 id；null 表示无可用目标。
     * @param isRecording 当前是否正在录制。
     * @param availableLensIds 设备当前可用镜头 id 列表。
     * @param seamlessSupported HAL 是否上报无缝切换（如 logical multi-camera 的
     *        `LOGICAL_MULTI_CAMERA` 能力 + 无重启切换）。
     */
    fun resolve(
        config: MultiLensSwitchConfig,
        targetLensId: String?,
        isRecording: Boolean,
        availableLensIds: List<String>,
        seamlessSupported: Boolean = false,
    ): LensSwitchDecision {
        val (clamped, _) = config.clampReport()

        if (!clamped.enabled) {
            return LensSwitchDecision(
                allowed = false,
                seamless = false,
                targetLensId = null,
                settleMs = clamped.switchSettleMs,
                reason = "多镜头切换已在设置中关闭",
            )
        }
        if (targetLensId.isNullOrBlank()) {
            return LensSwitchDecision(
                allowed = false,
                seamless = false,
                targetLensId = null,
                settleMs = clamped.switchSettleMs,
                reason = "无可用目标镜头",
            )
        }
        if (!availableLensIds.contains(targetLensId)) {
            return LensSwitchDecision(
                allowed = false,
                seamless = false,
                targetLensId = null,
                settleMs = clamped.switchSettleMs,
                reason = "目标镜头 $targetLensId 当前不可用",
            )
        }
        if (clamped.allowedLensIds.isNotEmpty() && !clamped.allowedLensIds.contains(targetLensId)) {
            return LensSwitchDecision(
                allowed = false,
                seamless = false,
                targetLensId = null,
                settleMs = clamped.switchSettleMs,
                reason = "目标镜头 $targetLensId 不在白名单内",
            )
        }
        if (isRecording && !clamped.switchDuringRecording) {
            return LensSwitchDecision(
                allowed = false,
                seamless = false,
                targetLensId = null,
                settleMs = clamped.switchSettleMs,
                reason = "录制中禁止切换镜头（可在设置中放开）",
            )
        }

        val seamless = clamped.seamlessSwitch && seamlessSupported
        return LensSwitchDecision(
            allowed = true,
            seamless = seamless,
            targetLensId = targetLensId,
            settleMs = if (seamless) 0L else clamped.switchSettleMs,
            reason = if (seamless) {
                "允许切换（无缝）到 $targetLensId"
            } else if (clamped.seamlessSwitch) {
                "允许切换到 $targetLensId（HAL 不支持无缝切换，录制中会有约 " +
                    "${clamped.switchSettleMs}ms 的稳定期）"
            } else {
                "允许切换到 $targetLensId"
            },
        )
    }

    /**
     * 循环取下一颗镜头（用于「切换」按钮的轮换逻辑）。
     * @return 下一颗可用镜头 id；无可用时返回 null。
     */
    fun nextLens(
        currentLensId: String?,
        availableLensIds: List<String>,
        allowedLensIds: List<String> = emptyList(),
    ): String? {
        val candidates = availableLensIds
            .filter { allowedLensIds.isEmpty() || allowedLensIds.contains(it) }
            .distinct()
        if (candidates.isEmpty()) return null
        val index = candidates.indexOf(currentLensId)
        if (index < 0) return candidates.first()
        return candidates[(index + 1) % candidates.size]
    }
}
