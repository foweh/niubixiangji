package com.hinnka.mycamera.longexposure

import kotlin.math.roundToInt

/**
 * 暗帧扣除（长曝光/烟火/星空前置步骤）。
 *
 * 语义（不再依赖任何既有同步堆栈入口，可独立流式执行）：
 *  1. 暗帧平均：把 [DarkFrameConfig.frameCount] 张暗帧按样本平均 → 暗电流图案 D；
 *  2. 逐帧扣除：`out = max(0, frame - D * gain)`；
 *  3. 坏点处理：D 超过 [DarkFrameConfig.hotPixelThreshold] 的像素判为热噪点/坏点，
 *     按 [HotPixelMode] 置零（交由堆栈剔除）或仅做减法截断。
 *
 * 纯 Kotlin，无 Android 依赖，可直接 JVM 单测。
 */
object DarkFrameSubtractor {

    private const val TAG = "DarkFrameSubtractor"

    /**
     * 平均多帧暗帧。
     *
     * @param frames 暗帧样本（长度必须一致；不一致的帧被忽略并计数返回）。
     * @param expectedSize 期望样本数；<=0 时取第一帧长度。
     * @return 平均暗帧（长度 = expectedSize）；无有效帧返回 null。
     */
    fun averageDarkFrames(frames: List<ShortArray>, expectedSize: Int = 0): ShortArray? {
        if (frames.isEmpty()) return null
        val size = if (expectedSize > 0) expectedSize else frames.first().size
        if (size <= 0) return null
        val sum = LongArray(size)
        var used = 0
        for (frame in frames) {
            if (frame.size != size) continue
            for (i in 0 until size) sum[i] += frame[i].toLong()
            used++
        }
        if (used == 0) return null
        val out = ShortArray(size)
        for (i in 0 until size) {
            out[i] = (sum[i] / used).coerceIn(0L, LinearRgbPlane.SAMPLE_MAX.toLong()).toShort()
        }
        return out
    }

    /**
     * 数量不一致的暗帧在平均时被忽略的帧数（用于日志/自检）。
     */
    fun countRejectedDarkFrames(frames: List<ShortArray>, expectedSize: Int): Int {
        if (frames.isEmpty() || expectedSize <= 0) return 0
        return frames.count { it.size != expectedSize }
    }

    /** 统计暗帧中超过阈值的坏点数（阈值 <= 0 时返回 0）。 */
    fun countHotPixels(dark: ShortArray?, threshold: Int): Int {
        if (dark == null || threshold <= 0) return 0
        var count = 0
        for (s in dark) if (s >= threshold) count++
        return count
    }

    /**
     * 把一帧扣除暗帧后写入 [out]。
     *
     * @param dark 平均暗帧；null 或长度不匹配时视为"无暗帧"（仅做增益/坏点处理，不抛异常）。
     * @param gain 暗电流扣除增益（内部 clamp 到 0..2）。
     * @param hotPixelThreshold 坏点阈值（<=0 关闭）。
     * @return 本次被判定为坏点并置零的样本数（供日志）。
     */
    fun subtractInto(
        frame: ShortArray,
        dark: ShortArray?,
        out: ShortArray,
        gain: Float = 1f,
        hotPixelThreshold: Int = 0,
        hotPixelMode: HotPixelMode = HotPixelMode.OFF,
    ): Int {
        require(out.size == frame.size) {
            "out size ${out.size} != frame size ${frame.size}"
        }
        val effectiveDark = dark?.takeIf { it.size == frame.size }
        val g = if (gain.isFinite()) gain.coerceIn(DarkFrameConfig.GAIN_MIN, DarkFrameConfig.GAIN_MAX) else 1f
        val maskEnabled = hotPixelMode == HotPixelMode.ZERO && hotPixelThreshold > 0 && effectiveDark != null
        var hotPixels = 0
        for (i in frame.indices) {
            val darkSample = effectiveDark?.get(i)?.toInt() ?: 0
            if (maskEnabled && darkSample >= hotPixelThreshold) {
                out[i] = 0
                hotPixels++
                continue
            }
            val value = frame[i] - (darkSample * g).roundToInt()
            out[i] = value.coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
        }
        return hotPixels
    }

    /** [subtractInto] 的分配版（返回新数组），便于单测与函数式调用。 */
    fun subtract(
        frame: ShortArray,
        dark: ShortArray?,
        gain: Float = 1f,
        hotPixelThreshold: Int = 0,
        hotPixelMode: HotPixelMode = HotPixelMode.OFF,
    ): ShortArray {
        val out = ShortArray(frame.size)
        subtractInto(frame, dark, out, gain, hotPixelThreshold, hotPixelMode)
        return out
    }

    internal fun tag(): String = TAG
}

/**
 * 增量累加器契约：**O(1) 内存**，帧数增加只拉长耗时，不放大峰值内存。
 * （PATTERN_SPEC §4：帧数不做总上限，用流式累加解决资源问题。）
 */
interface IncrementalAccumulator {
    val width: Int
    val height: Int

    /** 已并入的帧数。 */
    val frameCount: Int

    /** 是否已有至少一帧（首帧用于建立基线）。 */
    val isSeeded: Boolean

    /** 期望的单帧样本数。 */
    val sampleSize: Int

    /** 并入一帧（样本数不符抛 [IllegalArgumentException]，由调用方保证对齐）。 */
    fun addFrame(frame: ShortArray)

    /** 导出当前累加结果（新数组）。 */
    fun snapshot(): ShortArray
}

/**
 * 增量 MAX 叠加（lighten）：逐像素取最大值，亮部只增不减。
 * 用于光绘光轨、烟火、星轨。首帧直接建立基线。
 */
class MaxAccumulator(
    override val width: Int,
    override val height: Int,
) : IncrementalAccumulator {

    private val accum = ShortArray(width * height * 3)

    override var frameCount: Int = 0
        private set

    override val isSeeded: Boolean get() = frameCount > 0

    override val sampleSize: Int get() = accum.size

    init {
        require(width > 0 && height > 0) { "invalid accumulator size: ${width}x$height" }
    }

    override fun addFrame(frame: ShortArray) {
        require(frame.size == accum.size) {
            "frame size ${frame.size} != expected ${accum.size}"
        }
        if (frameCount == 0) {
            frame.copyInto(accum)
        } else {
            // 增量 MAX：不新增大缓冲，逐样本比较。
            for (i in accum.indices) {
                val incoming = frame[i]
                if (incoming > accum[i]) accum[i] = incoming
            }
        }
        frameCount++
    }

    override fun snapshot(): ShortArray = accum.copyOf()

    /** 当前累加结果的只读视图（不复制，供高频预览使用，勿修改）。 */
    fun view(): ShortArray = accum

    /**
     * 累加缓冲的**就地写入句柄**：由 [LongExposureStacker] 的 C++ 融合路径
     * （`暗帧减法 + MAX 累加` 单次遍历）直接写入，避免每帧一次整幅拷贝。
     *
     * 调用方契约：写入的必须是已按 MAX 语义合并的样本，写完后调用
     * [commitExternallyWrittenFrame] 完成计数。
     */
    internal fun rawBuffer(): ShortArray = accum

    /** 配合 [rawBuffer]：外部完成一帧就地累加后递增计数。 */
    internal fun commitExternallyWrittenFrame() {
        frameCount++
    }

    fun toPlane(): LinearRgbPlane = LinearRgbPlane(width, height, snapshot())
}

/**
 * 增量平均值叠加：逐帧平均（瀑布拉丝/降噪）。
 *
 * 累加使用 [IntArray]（4 字节/样本）：在 [LongExposureLimits.ABSOLUTE_FRAME_MAX]（4096）
 * 与 12bit 样本上限下总和 ≤ 4095×4096 ≈ 1.68e7，远低于 Int 上限，无溢出风险。
 */
class MeanAccumulator(
    override val width: Int,
    override val height: Int,
) : IncrementalAccumulator {

    private val sum = IntArray(width * height * 3)

    override var frameCount: Int = 0
        private set

    override val isSeeded: Boolean get() = frameCount > 0

    override val sampleSize: Int get() = sum.size

    init {
        require(width > 0 && height > 0) { "invalid accumulator size: ${width}x$height" }
    }

    override fun addFrame(frame: ShortArray) {
        require(frame.size == sum.size) {
            "frame size ${frame.size} != expected ${sum.size}"
        }
        for (i in sum.indices) sum[i] += frame[i].toInt()
        frameCount++
    }

    override fun snapshot(): ShortArray {
        val out = ShortArray(sum.size)
        if (frameCount == 0) return out
        for (i in sum.indices) {
            out[i] = (sum[i] / frameCount).coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
        }
        return out
    }

    /** 就地读取某一通道的平均值（不分配整幅缓冲；无帧时返回 0）。 */
    internal fun sampleAt(index: Int): Int =
        if (frameCount == 0) 0 else (sum[index] / frameCount).coerceIn(0, LinearRgbPlane.SAMPLE_MAX)

    /**
     * 累加缓冲的**就地写入句柄**（[MaxAccumulator.rawBuffer] 的同构版本）：由
     * [LongExposureStacker] 的 nativecompute 融合路径（`暗帧减法 + MEAN 累加` 单次遍历）直接写入
     * `IntArray` 求和缓冲，避免每帧一次整幅 `ShortArray` 中转拷贝。
     *
     * 调用方契约：写入的必须是「已扣除暗帧并 clamp 到 0..[LinearRgbPlane.SAMPLE_MAX] 的样本增量」，
     * 即对本帧完成 `sum[i] += value`；写完后调用 [commitExternallyWrittenFrame] 完成计数。
     */
    internal fun rawSumBuffer(): IntArray = sum

    /** 配合 [rawSumBuffer]：外部完成一帧就地累加后递增计数。 */
    internal fun commitExternallyWrittenFrame() {
        frameCount++
    }

    fun toPlane(): LinearRgbPlane = LinearRgbPlane(width, height, snapshot())
}

/** 按 [LongExposureStackMode] 创建累加器。 */
object IncrementalAccumulators {
    fun create(mode: LongExposureStackMode, width: Int, height: Int): IncrementalAccumulator =
        when (mode) {
            LongExposureStackMode.MAX -> MaxAccumulator(width, height)
            LongExposureStackMode.MEAN -> MeanAccumulator(width, height)
        }
}
