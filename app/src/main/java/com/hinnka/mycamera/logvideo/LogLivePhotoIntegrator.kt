package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.VideoLogProfile

/**
 * Log 实况照片输出计划（Log 视频 20 项 · 第 3 项「对接 livephoto/ 包」）。
 *
 * @param videoClipLogEncoded 视频片段（MP4）末尾是否为 Log 编码灰片。
 * @param stillFrameLogEncoded 静止帧（JPEG）末尾是否为 Log 编码灰片。
 * @param stillCurve 静止帧需要 CPU 编码时使用的配方（null = 不处理）。
 * @param videoTrim 视频片段在 GL 侧使用的曲线微调（恒等 = 预设原生）。
 */
data class LogLivePhotoPlan(
    val profileName: String,
    val videoClipLogEncoded: Boolean,
    val stillFrameLogEncoded: Boolean,
    val stillCurve: LogCurveRecipe?,
    val videoTrim: LogCurveTrim,
    val restoreLutId: String,
    /** 实况照片的监看/预览是否套还原 LUT（边拍边看）。 */
    val previewLutApplied: Boolean,
    /** 静止帧是否需要做 CPU Log 编码（据此决定是否起后台任务）。 */
    val needsStillFrameJob: Boolean,
    val notes: List<String>,
) {
    val isLogLivePhoto: Boolean get() = videoClipLogEncoded || stillFrameLogEncoded
}

/**
 * Log 视频 ↔ 实况照片桥（Log 视频 20 项 · 第 3 项）。
 *
 * **已核对的既有链路（复用，不改造）**：
 *  - `livephoto/LivePhotoRecorder.onPreviewFrame(...)` 接收的是 `LutRenderer`
 *    渲染出的 **已调色纹理**；`LutRenderer` 在视频 Log 档位下 `enableVideoLog = true`，
 *    因此「Log 曲线 + 还原 LUT」**已经烘焙在实况照片视频帧里**——第 3 项的视频半边
 *    是**接线即用**（无需新渲染代码），只需把 Log 色彩标签交给编码器；
 *  - `livephoto/HardwareLutVideoRenderer` 现在只做拷贝/裁切（不做色彩变换），
 *    故其 `encoderColorConfig` 参数（默认 `VideoEncoderColorConfig.sdrDisplay()`）
 *    就是视频半边唯一需要改的接线点（patch 的 LIVE_PHOTO 段）。
 *
 * **缺口（需要 patch 接线）**：实况照片的**静止帧**从拍照管线出图，
 * 不经 `LutRenderer` 的 Log 编码 → 需要按 [LogLivePhotoPlan.stillCurve] 走
 * [LogVideoFrameProcessor] 的 CPU 编码（后台任务），否则会出现「静止帧是彩色、片段是灰片」
 * 的封面/首帧不一致问题。
 */
object LogLivePhotoIntegrator {

    fun plan(
        config: LogVideoConfig,
        profile: VideoLogProfile,
        isLivePhotoEnabled: Boolean,
        lutAvailable: Boolean,
        stillFrameSizeBytes: Long = DEFAULT_STILL_FRAME_BYTES,
    ): LogLivePhotoPlan {
        val notes = mutableListOf<String>()

        if (!isLivePhotoEnabled) {
            return LogLivePhotoPlan(
                profileName = profile.name,
                videoClipLogEncoded = false,
                stillFrameLogEncoded = false,
                stillCurve = null,
                videoTrim = LogCurveTrim.IDENTITY,
                restoreLutId = "",
                previewLutApplied = false,
                needsStillFrameJob = false,
                notes = listOf("实况照片未开启，Log 计划为空"),
            )
        }

        if (!config.enabled || !profile.isEnabled) {
            return LogLivePhotoPlan(
                profileName = profile.name,
                videoClipLogEncoded = false,
                stillFrameLogEncoded = false,
                stillCurve = null,
                videoTrim = LogCurveTrim.IDENTITY,
                restoreLutId = "",
                previewLutApplied = false,
                needsStillFrameJob = false,
                notes = listOf("Log 未开启（config.enabled=${config.enabled}, profile=${profile.name}）"),
            )
        }

        if (!config.livePhotoLogEnabled) {
            notes += "实况照片 Log 已在配置中关闭：片段与静止帧都按普通实况照片输出"
            return LogLivePhotoPlan(
                profileName = profile.name,
                videoClipLogEncoded = false,
                stillFrameLogEncoded = false,
                stillCurve = null,
                videoTrim = LogCurveTrim.IDENTITY,
                restoreLutId = config.resolvedRestoreLutId(),
                previewLutApplied = false,
                needsStillFrameJob = false,
                notes = notes,
            )
        }

        // 片段：帧源已是 LutRenderer 的 Log 编码输出 → 接线即 Log 灰片。
        notes += "视频片段由 LutRenderer(enableVideoLog=true) 直接产出 Log 编码帧，接线即用"
        if (config.lutMode == VideoLogLutMode.BAKE_IN) {
            notes += "BAKE_IN：片段像素为「Log + 还原 LUT」烘焙结果，编码器按显示域标记"
        } else {
            notes += "MONITOR_ONLY：片段保留纯 Log 灰片，还原 LUT 仅监看"
        }
        if (!lutAvailable) {
            notes += "配套还原 LUT 不可用：预览无 LUT，片段仍为纯 Log（不降级画质）"
        }

        val trimmed = config.appliesGpuTrim
        val stillEncoded = config.stillFrameLogEnabled
        if (stillEncoded) {
            notes += "静止帧需 CPU Log 编码（拍照管线不经 GL）：配方=${config.curve.preset}，" +
                "预估工作集 ${stillFrameSizeBytes / 1024 / 1024}MB，走 BgJobRunner(AI_LUT, LOW)"
        } else {
            notes += "静止帧按显示域输出（stillFrameLogEnabled=false），与片段不一致属预期"
        }
        if (trimmed) {
            notes += "曲线微调非原生（black=${config.curve.blackLevel}/peak=${config.curve.peak}/" +
                "slope=${config.curve.slope}）：GL 走 trim uniform，CPU 侧由配方自动同构"
        }

        return LogLivePhotoPlan(
            profileName = profile.name,
            videoClipLogEncoded = true,
            stillFrameLogEncoded = stillEncoded,
            stillCurve = config.curve.clamped().takeIf { stillEncoded },
            videoTrim = LogCurveTrim.from(config),
            restoreLutId = config.resolvedRestoreLutId(),
            previewLutApplied = LogVideoLutDecision.shouldPreviewLut(profile, config),
            needsStillFrameJob = stillEncoded,
            notes = notes,
        )
    }

    /**
     * 实况照片静止帧的 CPU 编码是否值得起后台任务（小图直接同步处理更省事、更快）。
     */
    fun shouldUseBackgroundJob(pixelCount: Int): Boolean = pixelCount > INLINE_PIXEL_BUDGET

    /** 4K 静止帧（3840×2160 ARGB）的典型工作集。 */
    const val DEFAULT_STILL_FRAME_BYTES: Long = 3840L * 2160L * 4L

    /** 低于该像素数（约 200 万像素）就地同步编码，避免小任务起线程的调度开销。 */
    const val INLINE_PIXEL_BUDGET = 2_000_000
}
