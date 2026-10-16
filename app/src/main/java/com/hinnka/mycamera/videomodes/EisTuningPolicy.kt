package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.DEFAULT_VIDEO_STABILIZATION_STRENGTH
import com.hinnka.mycamera.stabilization.MAX_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.MIN_VIDEO_STABILIZATION_LOOKAHEAD
import com.hinnka.mycamera.stabilization.STABILIZATION_LOOKAHEAD_FRAME_COUNT
import com.hinnka.mycamera.stabilization.normalizeStabilizationLookahead
import com.hinnka.mycamera.stabilization.normalizeStabilizationStrength
import kotlin.math.abs

/**
 * §3 EIS 强化（**开放参数**，不是重写）。
 *
 * 核对结论（工程已有，禁止重复实现）：
 * - `stabilization/RealtimeStabilizationCoordinator.kt`：顶层 `normalizeStabilizationStrength`
 *   （0..1）、`normalizeStabilizationLookahead`（3..10）、`DEFAULT_VIDEO_STABILIZATION_*`、
 *   `STABILIZATION_LOOKAHEAD_FRAME_COUNT`（= native 引擎前瞻深度）；
 * - `stabilization/MgcEisNativeEngine.kt` + `MgcEisNativeBridge`：MGC EIS 的 JNI 实现
 *   （`create(width, height, frontFacing, strength, lookaheadFrames)`）；
 * - `video/VideoTypes.kt` 的 `VideoConfig.enhancedStabilizationStrength / enhancedStabilizationLookahead`
 *   已把强度/前瞻透传给协调器。
 *
 * 因此本模块只补两件既有链路**没有**的事：
 * 1. 把「启用开关 + 强度 + 前瞻」聚合成一个可配置对象并给出 clamp 报告；
 * 2. 新增**额外裁剪系数**（`cropFactor`，1.0 = 既有行为、1.6 = 更稳但视野更窄）——
 *    这是原实现没有暴露的自由度。
 *
 * **零回归保证**：默认值 `enabled=true / strength=1.0 / cropFactor=1.0 / lookahead=7` 经本策略
 * 解析后，引擎侧入参与既有链路完全一致（`normalizeStabilizationStrength(1f)=1f`、
 * `normalizeStabilizationLookahead(7)=7`），不引入任何行为变化。
 */

/**
 * EIS 开放配置。
 *
 * @param enabled 是否启用算法防抖（EIS/EIS+）；默认 true（既有链路在支持的机型上默认走 OIS/EIS+）。
 * @param strength 防抖强度，范围 [0, 2]，默认 1.0。
 *        说明：**配置域**上限 2.0；当前 native 引擎只消费 0..1（`normalizeStabilizationStrength`），
 *        因此 >1 的值会被记录为「已开放但引擎暂不消费」，等 native 缓冲升级后即可生效。
 * @param cropFactor 额外裁剪系数，范围 [1.0, 1.6]，默认 1.0（= 不做额外裁剪 = 既有行为）。
 *        该系数是数字稳定余量的放大器：1.1 表示在引擎固有裁剪之外再放大 10%。
 * @param lookaheadFrames 前瞻帧数，范围 [3, 64]，默认 7。
 *        说明：**配置域**上限 64；native 引擎缓冲深度为 [STABILIZATION_LOOKAHEAD_FRAME_COUNT]（7），
 *        既有 `normalizeStabilizationLookahead` 会把它收敛到 3..10，因此超额部分不会崩、
 *        只会被引擎侧削平（真实放开需 patch 扩 native 缓冲）。
 * @param horizonLockEnabled 地平线锁定（旋转方向的额外约束），默认 false。
 */
data class EisTuningConfig(
    val enabled: Boolean = true,
    val strength: Float = VideoModesRanges.DEFAULT_EIS_STRENGTH,
    val cropFactor: Float = VideoModesRanges.DEFAULT_EIS_CROP_FACTOR,
    val lookaheadFrames: Int = VideoModesRanges.DEFAULT_EIS_LOOKAHEAD,
    val horizonLockEnabled: Boolean = false,
) {
    fun clampReport(): Pair<EisTuningConfig, List<String>> {
        val corrections = mutableListOf<String>()
        val requestedStrength = if (strength.isFinite()) strength else VideoModesRanges.DEFAULT_EIS_STRENGTH
        if (!strength.isFinite()) corrections += "strength $strength → $requestedStrength（非法浮点）"
        val clampedStrength = requestedStrength.coerceIn(
            VideoModesRanges.EIS_STRENGTH_MIN,
            VideoModesRanges.EIS_STRENGTH_MAX,
        )
        if (abs(clampedStrength - requestedStrength) > 0.0001f) {
            corrections += "strength $requestedStrength → $clampedStrength"
        }
        val crop = if (cropFactor.isFinite()) cropFactor else VideoModesRanges.DEFAULT_EIS_CROP_FACTOR
        val clampedCrop = crop.coerceIn(
            VideoModesRanges.EIS_CROP_FACTOR_MIN,
            VideoModesRanges.EIS_CROP_FACTOR_MAX,
        )
        if (abs(clampedCrop - crop) > 0.0001f) corrections += "cropFactor $crop → $clampedCrop"
        val lookahead = lookaheadFrames.coerceIn(
            VideoModesRanges.EIS_LOOKAHEAD_MIN,
            VideoModesRanges.EIS_LOOKAHEAD_ABSOLUTE_MAX,
        )
        if (lookahead != lookaheadFrames) corrections += "lookaheadFrames $lookaheadFrames → $lookahead"
        return EisTuningConfig(
            enabled = enabled,
            strength = clampedStrength,
            cropFactor = clampedCrop,
            lookaheadFrames = lookahead,
            horizonLockEnabled = horizonLockEnabled,
        ) to corrections
    }

    fun clamp(): EisTuningConfig = clampReport().first
}

/** EIS 解析结果（配置域值 + 引擎侧实际生效值）。 */
data class EisTuning(
    val enabled: Boolean,
    /** 配置域请求强度（clamp 到 [0, 2]）。 */
    val requestedStrength: Float,
    /** 引擎侧实际入参（既有 normalizer 收敛到 [0, 1]）。 */
    val engineStrength: Float,
    /** 额外裁剪系数（[1.0, 1.6]）。 */
    val cropFactor: Float,
    /** 配置域前瞻帧数（clamp 到 [3, 64]）。 */
    val requestedLookaheadFrames: Int,
    /** 引擎侧实际前瞻帧数（既有 normalizer 收敛到 [3, 10]）。 */
    val engineLookaheadFrames: Int,
    /** 当前 native 引擎支持的前瞻深度（见 MgcEisNativeEngine.LOOKAHEAD_FRAME_COUNT）。 */
    val nativeLookaheadDepth: Int,
    /** 裁剪系数换算的等效数字变焦比（供 UI 提示视野变化）。 */
    val effectiveZoomRatio: Float,
    val horizonLockEnabled: Boolean,
    /** 打开 EIS 时的内部约定强度（既有默认值，供「恢复默认」用）。 */
    val defaultStrength: Float,
    val defaultLookaheadFrames: Int,
    val corrections: List<String>,
) {
    /** 强度是否超出引擎当前可消费范围（UI 应提示「已开放，待引擎支持」）。 */
    val strengthBeyondEngine: Boolean get() = requestedStrength > engineStrength + 0.0001f

    /** 前瞻是否超出既有 normalizer 上限。 */
    val lookaheadBeyondEngine: Boolean get() = requestedLookaheadFrames > engineLookaheadFrames
}

/** EIS 参数策略（纯逻辑，零回归）。 */
object EisTuningPolicy {

    /** native 引擎缓冲深度（既有常量，仅用于报告，不改动）。 */
    val NATIVE_LOOKAHEAD_DEPTH: Int = STABILIZATION_LOOKAHEAD_FRAME_COUNT

    /** 既有引擎侧的强度范围上界（`normalizeStabilizationStrength` 收敛到 0..1）。 */
    const val ENGINE_STRENGTH_MAX = 1f

    fun resolve(config: EisTuningConfig): EisTuning {
        val (clamped, corrections) = config.clampReport()
        return EisTuning(
            enabled = clamped.enabled,
            requestedStrength = clamped.strength,
            // 复用既有 normalizer：NaN/越界一视同仁，且默认 1.0 通过后仍是 1.0 → 零回归。
            engineStrength = normalizeStabilizationStrength(clamped.strength),
            cropFactor = clamped.cropFactor,
            requestedLookaheadFrames = clamped.lookaheadFrames,
            // 复用既有 normalizer：默认 7 通过后仍是 7 → 零回归。
            engineLookaheadFrames = normalizeStabilizationLookahead(clamped.lookaheadFrames),
            nativeLookaheadDepth = NATIVE_LOOKAHEAD_DEPTH,
            effectiveZoomRatio = clamped.cropFactor,
            horizonLockEnabled = clamped.horizonLockEnabled,
            defaultStrength = DEFAULT_VIDEO_STABILIZATION_STRENGTH,
            defaultLookaheadFrames = DEFAULT_VIDEO_STABILIZATION_LOOKAHEAD,
            corrections = corrections,
        )
    }

    /** 引擎侧可接受的强度上界（供 UI 滑杆标注「引擎当前上限」）。 */
    fun engineStrengthMax(): Float = ENGINE_STRENGTH_MAX

    /** 引擎侧可接受的前瞻上限（既有 normalizer 上限）。 */
    fun engineLookaheadMax(): Int = MAX_VIDEO_STABILIZATION_LOOKAHEAD

    /** 引擎侧可接受的前瞻下限。 */
    fun engineLookaheadMin(): Int = MIN_VIDEO_STABILIZATION_LOOKAHEAD
}
