package com.hinnka.mycamera.videomodes

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * §1 高帧率 / 慢动作。
 *
 * 与既有链路的关系（先核对、不重复）：
 * - 既有 `video/VideoTypes.kt` 的 `VideoFpsPreset` 只到 60fps（24/25/30/50/60），
 *   `video/VideoCapabilitiesResolver.resolveAvailableFps()` 只用 AE 帧率区间做过滤；
 * - 既有 `Camera2Controller.applyVideoFpsRange()` 已经会把目标帧率写进
 *   `CONTROL_AE_TARGET_FPS_RANGE`（无精确区间时强锁 `[fps, fps]`）。
 *
 * 本模块**不重写**上面两者，只做「开放档位 + 能力门控 + 隐藏原因」的补足：
 * 档位扩到 24/30/48/60/120/240，逐个按 HAL 上报能力开放，不足的自动隐藏并给出可读原因。
 */

/** 帧率档位阶梯（按能力逐个开/隐藏，不做统一截断）。 */
object VideoFpsLadder {
    /** 开放档位：实时与慢动作共用一条阶梯。 */
    val ALL_TIERS: List<Int> = listOf(24, 30, 48, 60, 120, 240)

    /** 兜底帧率（能力探测完全失败时）。 */
    const val BASE_FPS = 30

    fun label(fps: Int): String = "${fps}fps"
}

/** 单个帧率档位的可用性 + 原因（UI 用它决定显示/置灰/说明文案）。 */
data class FpsTierAvailability(
    val fps: Int,
    val available: Boolean,
    val reason: String,
)

/** 帧率能力快照。 */
data class FpsCapability(
    val tiers: List<FpsTierAvailability>,
    val recommendedFps: Int,
) {
    /** 可用档位（升序）。 */
    val availableFps: List<Int> get() = tiers.filter { it.available }.map { it.fps }

    /** 被隐藏的档位（含原因，UI 可折叠展示「为什么没有 240fps」）。 */
    val hiddenTiers: List<FpsTierAvailability> get() = tiers.filter { !it.available }

    /** 本机可用最高帧率。 */
    val maxAvailableFps: Int
        get() = tiers.filter { it.available }.maxOfOrNull { it.fps } ?: VideoFpsLadder.BASE_FPS

    /** 是否支持高帧率慢动作（>=120fps）。 */
    val supportsHighFrameRate: Boolean get() = maxAvailableFps >= 120
}

/**
 * 帧率能力解析器（纯逻辑：入参为已从 CameraCharacteristics 取出并归一化的数据）。
 *
 * @param advertisedRanges `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 归一化成的区间列表
 *        （闭区间，例如 `[30, 30]`、`[15, 60]`）。
 * @param minFrameDurationNs 当前录制/预览尺寸的最小帧间隔（`SCALER_STREAM_CONFIGURATION_MAP`
 *        的 `getOutputMinFrameDuration`）；<=0 表示未知，跳过时长推导。
 * @param requestedFps 用户当前选择（仅用于给出推荐值，不改变可用集合）。
 */
object FpsCapabilityResolver {

    fun resolve(
        advertisedRanges: List<IntRange>,
        minFrameDurationNs: Long,
        requestedFps: Int,
    ): FpsCapability {
        val maxFpsByDuration = if (minFrameDurationNs > 0L) {
            (1_000_000_000.0 / minFrameDurationNs.toDouble()).toInt()
        } else {
            Int.MAX_VALUE
        }

        val tiers = VideoFpsLadder.ALL_TIERS.map { fps ->
            val advertised = isAdvertised(advertisedRanges, fps)
            val durationOk = fps <= maxFpsByDuration
            when {
                advertised && durationOk -> FpsTierAvailability(
                    fps = fps,
                    available = true,
                    reason = "HAL 已上报该帧率",
                )

                advertised -> FpsTierAvailability(
                    fps = fps,
                    available = true,
                    reason = "HAL 已上报，但当前分辨率最小帧间隔仅支持 ≤$maxFpsByDuration" +
                        "fps，可能由 HAL 内部降分辨率",
                )

                // 与既有 VideoCapabilitiesResolver 的「乐观档位」行为一致：<=60fps 且
                // 分辨率最小帧间隔允许时，仍开放（不强锁区间，交给 applyVideoFpsRange 处理）。
                durationOk && fps <= OPTIMISTIC_MAX_FPS -> FpsTierAvailability(
                    fps = fps,
                    available = true,
                    reason = "由当前分辨率最小帧间隔推导（HAL 未显式上报该档位）",
                )

                !durationOk -> FpsTierAvailability(
                    fps = fps,
                    available = false,
                    reason = "当前分辨率最小帧间隔 ${minFrameDurationNs}ns 仅支持 ≤" +
                        "$maxFpsByDuration fps，请降低分辨率后重试",
                )

                else -> FpsTierAvailability(
                    fps = fps,
                    available = false,
                    reason = "设备未上报该帧率（CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES），" +
                        "或该组合不受 HAL 支持",
                )
            }
        }

        return FpsCapability(
            tiers = tiers,
            recommendedFps = pickRecommended(tiers, requestedFps),
        )
    }

    /** 区间列表是否上报了精确档位（区间含该帧率）。 */
    fun isAdvertised(ranges: List<IntRange>, fps: Int): Boolean =
        ranges.any { it.first <= fps && it.last >= fps }

    internal const val OPTIMISTIC_MAX_FPS = 60

    private fun pickRecommended(tiers: List<FpsTierAvailability>, requestedFps: Int): Int {
        val available = tiers.filter { it.available }.map { it.fps }
        if (available.isEmpty()) return VideoFpsLadder.BASE_FPS
        if (available.contains(requestedFps)) return requestedFps
        // 优先向下取最接近的可用档位，其次取最高可用档位。
        return available.filter { it < requestedFps }.maxOrNull() ?: available.max()
    }
}

/**
 * §1 慢动作回放速度策略（0.25 / 0.5 / 1x 档位可配，开放区间 0.125~1.0x）。
 *
 * 语义：`speed` 是**回放速度**（时间轴相对原始拍摄的时间比例）。
 * 0.5x 表示回放时长 = 拍摄时长的 2 倍；因此若要维持既定回放帧率 `playbackFps`，
 * 拍摄帧率必须 >= `playbackFps / speed`。
 */
object SlowMotionPolicy {

    /** 慢放档位（回放速度）。 */
    val SPEED_LADDER: List<Float> = listOf(0.25f, 0.5f, 1f)

    /** 回放帧率默认值（合成/回放目标）。 */
    const val DEFAULT_PLAYBACK_FPS = 30

    /** 允许的浮点误差（帧率比较用）。 */
    private const val EPS = 0.001f

    /** 越界 / NaN / 无穷 → clamp 到 [0.125, 1.0]，非法值回落到默认（原速）。 */
    fun normalizeSpeed(speed: Float): Float {
        if (!speed.isFinite()) return VideoModesRanges.DEFAULT_SLOW_MOTION_SPEED
        return speed.coerceIn(
            VideoModesRanges.SLOW_MOTION_SPEED_MIN,
            VideoModesRanges.SLOW_MOTION_SPEED_MAX,
        )
    }

    /** 档位步进（forward=true 更快，false 更慢）；当前值不在档位上时先吸附到最近的档位。 */
    fun stepSpeed(current: Float, forward: Boolean): Float {
        val normalized = normalizeSpeed(current)
        val index = SPEED_LADDER.indexOfFirst { abs(it - normalized) < EPS }
        if (index < 0) {
            val nearest = SPEED_LADDER.minByOrNull { abs(it - normalized) }
                ?: VideoModesRanges.DEFAULT_SLOW_MOTION_SPEED
            return nearest
        }
        val next = if (forward) index + 1 else index - 1
        return SPEED_LADDER[next.coerceIn(0, SPEED_LADDER.lastIndex)]
    }

    /** 为维持 [playbackFps] 回放帧率、以 [speed] 回放，所需的最小拍摄帧率。 */
    fun requiredCaptureFps(playbackFps: Int, speed: Float): Int {
        val s = normalizeSpeed(speed)
        val target = playbackFps.coerceAtLeast(1)
        return ceil(target / s.toDouble()).toInt()
    }

    /** 给定拍摄帧率是否够用（放慢 [speed] 倍后仍不低于目标回放帧率）。 */
    fun isSpeedSupported(captureFps: Int, playbackFps: Int, speed: Float): Boolean {
        val s = normalizeSpeed(speed)
        val effectiveFps = captureFps * s
        return effectiveFps + EPS >= playbackFps
    }

    /** 当前拍摄帧率下可用的慢放档位（不足的档位自动隐藏，1x 永远可用）。 */
    fun availableSpeeds(
        captureFps: Int,
        playbackFps: Int = DEFAULT_PLAYBACK_FPS,
    ): List<Float> = SPEED_LADDER.filter { it >= 1f || isSpeedSupported(captureFps, playbackFps, it) }

    /** 慢动作导出/回放时长（毫秒）。 */
    fun playbackDurationMs(captureDurationMs: Long, speed: Float): Long {
        val s = normalizeSpeed(speed)
        if (captureDurationMs <= 0L) return 0L
        return (captureDurationMs / s.toDouble()).roundToInt().toLong()
    }
}

/**
 * §1 帧率与慢动作的开放配置（默认值 = 既有行为：30fps 原速）。
 *
 * @param targetFps 目标帧率，范围 [24, 240]，默认 30；实际开放档位由 [FpsCapability] 决定。
 * @param slowMotionEnabled 是否启用慢动作（仅影响回放/合成，不改采集路径）。
 * @param slowMotionSpeed 回放速度，范围 [0.125, 1.0]，默认 1.0（原速）。UI 档位 0.25/0.5/1x。
 */
data class FpsModeConfig(
    val targetFps: Int = VideoModesRanges.DEFAULT_FPS,
    val slowMotionEnabled: Boolean = false,
    val slowMotionSpeed: Float = VideoModesRanges.DEFAULT_SLOW_MOTION_SPEED,
) {
    /** clamp 并返回被修正项（供日志/UI 提示）。 */
    fun clampReport(): Pair<FpsModeConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val fps = targetFps.coerceIn(VideoModesRanges.FPS_MIN, VideoModesRanges.FPS_MAX)
        if (fps != targetFps) corrections += "targetFps $targetFps → $fps"
        val speed = SlowMotionPolicy.normalizeSpeed(slowMotionSpeed)
        if (abs(speed - slowMotionSpeed) > 0.0001f) {
            corrections += "slowMotionSpeed $slowMotionSpeed → $speed"
        }
        return FpsModeConfig(
            targetFps = fps,
            slowMotionEnabled = slowMotionEnabled,
            slowMotionSpeed = speed,
        ) to corrections
    }

    fun clamp(): FpsModeConfig = clampReport().first

    /** 采集帧率与回放速度组合是否成立（不成立时上层应回退原速或提升帧率）。 */
    fun isPlaybackFeasible(playbackFps: Int = SlowMotionPolicy.DEFAULT_PLAYBACK_FPS): Boolean =
        !slowMotionEnabled || SlowMotionPolicy.isSpeedSupported(targetFps, playbackFps, slowMotionSpeed)
}
