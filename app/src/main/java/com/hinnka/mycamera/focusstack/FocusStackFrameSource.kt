package com.hinnka.mycamera.focusstack

/**
 * 焦点堆叠的帧源抽象。
 *
 * **为什么要这一层**：堆叠的总帧数开放（2~4096），不可能把全部帧同时驻留内存。
 * 因此处理器只通过 [withFrame] **借出一帧**（用完即由实现释放/回收），
 * 融合改为「一帧 + 一个输出累加器」的流式累加 —— 常驻内存与**总帧数无关**
 * （仅掩模按 1 字节/像素/帧 驻留），符合 PATTERN_SPEC §1.5 / §4。
 *
 * 纯 Kotlin 接口（无 Android 依赖）；[MemoryFrameSource] 供单测与已解码场景，
 * `FocusStackBitmapAdapter` / `FocusStackJobRunner` 提供 Bitmap 与磁盘实现。
 */
interface FocusStackFrameSource {

    /** 可用帧数（>=0）。处理器只会取 `min(config.frameCount, frameCount)` 帧。 */
    val frameCount: Int

    val width: Int

    val height: Int

    /**
     * 借出第 [index] 帧的**全分辨率 ARGB 像素**（长度 = width*height）。
     *
     * 实现可在此处解码/缩放；返回后即视为已归还，调用方不得持有该数组
     * （处理器在需要保留时自行 `copyOf()`）。
     *
     * @throws IllegalArgumentException index 越界。
     */
    fun <T> withFrame(index: Int, block: (IntArray) -> T): T
}

/**
 * 内存帧源：帧已在内存中（单测、相册内已解码的位图链路）。
 *
 * 注意：本实现**不复制**像素，调用方需保证在这期间不修改这些数组
 * （处理器内部的融合是只读的）。
 */
class MemoryFrameSource(
    private val frames: List<IntArray>,
    override val width: Int,
    override val height: Int,
) : FocusStackFrameSource {

    constructor(frames: List<IntArray>, edge: Int) : this(frames, edge, edge)

    override val frameCount: Int get() = frames.size

    override fun <T> withFrame(index: Int, block: (IntArray) -> T): T {
        require(index in frames.indices) { "frame index $index out of range [0, ${frames.size})" }
        return block(frames[index])
    }
}
