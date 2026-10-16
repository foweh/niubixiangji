package com.hinnka.mycamera.videomodes

import android.graphics.Bitmap

/**
 * 视频处理**接线契约**（seam）：本模块与既有超分/降噪模块之间唯一的接口。
 *
 * 为什么要单独抽一层（PATTERN_SPEC §9 复用优先 + §1 只写新文件）：
 * - 超分能力已存在于 `multiframesr/`（`MultiFrameSrProcessor.run(...)`）与 `superres/`
 *   （`SuperResRuntime.submit(...)`），降噪能力散在 `lut/ChromaDenoiseAlgorithm.kt`、
 *   `processor/GlesMgcRawSpatialStacker.kt` 等处 —— 但它们都面向**照片**；
 * - 本模块的视频任务编排只依赖下面这几个**函数式接口**，因此既不需要复制它们的实现，
 *   也不依赖它们的具体符号（它们改签名不会波及本模块）；集成阶段只需在补丁接线处
 *   提供一个实现（见 `delivery/patches/videomodes_patch.md` 的 CODE_HOOKS）。
 *
 * 默认实现策略：**任何一步不可用都必须优雅回退**——返回 null 表示「本帧未处理」，
 * 调用方按原帧直通继续，不抛异常、不中断录制/转换。
 */

/** 单帧超分契约。 */
fun interface VideoFrameUpscaler {
    /**
     * @param frame 源帧（调用方负责在返回后回收）
     * @param scale 目标倍率（已 clamp 过内存降级）
     * @return 超分结果；null = 本帧未处理（直通）
     */
    fun upscale(frame: Bitmap, scale: Float, config: VideoSrConfig): Bitmap?
}

/** 多帧（时域窗口）降噪契约。 */
fun interface VideoFrameDenoiser {
    /**
     * @param window 时域窗口内的帧（含中心帧；顺序为时间顺序）
     * @param config 降噪配置（已 clamp）
     * @return 中心帧的降噪结果；null = 未处理（直通）
     */
    fun denoise(window: List<Bitmap>, config: VideoDenoiseConfig): Bitmap?
}

/** 快照落盘契约（边录边拍）。 */
fun interface VideoFrameSink {
    /**
     * @param frame 待落盘帧
     * @param quality JPEG 质量（已 clamp）
     * @param index 快照序号（从 0 递增，用于命名）
     * @return 是否成功写入
     */
    fun write(frame: Bitmap, quality: Int, index: Int): Boolean
}

/** 重编码契约（media3 Transformer 实现接入点，依赖已在工程内：`libs.media3.transformer`）。 */
fun interface VideoReencoder {
    /**
     * @param inputPath 处理后的帧序列/中间文件路径（实现自定，例如图像序列目录或中间 mp4）
     * @param outputPath 输出视频路径
     * @param config 超分配置（含输出码率/编码器覆盖）
     * @param sourceBitrateMbps 源视频码率（config.outputBitrateMbps=0 时沿用）
     * @return 是否成功
     */
    fun reencode(
        inputPath: String,
        outputPath: String,
        config: VideoSrConfig,
        sourceBitrateMbps: Int,
    ): Boolean
}

/** 可空安全调用：任何接线实现抛异常都不影响主流程。 */
internal inline fun <T> safeProcess(block: () -> T?): T? = runCatching { block() }.getOrNull()
