package com.hinnka.mycamera.longexposure

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.max
import kotlin.math.min

/**
 * 长曝光流式叠加器：**暗帧扣除 → 增量叠加（MAX/MEAN）→ 可选光源画笔色偏**。
 *
 * 设计要点（PATTERN_SPEC §1.5 / §4 / §10）：
 *  1. **O(1) 增量**：只驻留 1 帧工作缓冲 + 1 个累加器，帧数增加不放大峰值内存；
 *  2. MAX 模式走 [LongExposureNative] 的 `暗帧减法 + MAX 累加` 融合路径（C++），
 *     native 不可用时同语义回退纯 Kotlin（由 [LongExposureNative] 内部保证）；
 *  3. 帧尺寸不符时**计数拒绝而不是崩溃**（分片落盘/读取异常时链路不中断）。
 *
 * 不依赖 android.graphics：成图编码由 [LongExposureBitmapAdapter] 负责，本类可纯 JVM 单测。
 */
class LongExposureStacker(
    val width: Int,
    val height: Int,
    val mode: LongExposureStackMode,
    /** 是否允许走 C++ 加速（false 时强制纯 Kotlin，便于确定性测试/调试）。 */
    private val useNativeAcceleration: Boolean = true,
) {
    private val sampleSize: Int = width * height * 3

    private val maxAccumulator: MaxAccumulator? =
        if (mode == LongExposureStackMode.MAX) MaxAccumulator(width, height) else null
    private val meanAccumulator: MeanAccumulator? =
        if (mode == LongExposureStackMode.MEAN) MeanAccumulator(width, height) else null

    /** MEAN 模式的暗帧减法中转缓冲（复用，避免每帧分配）。 */
    private val subtractScratch: ShortArray? =
        if (mode == LongExposureStackMode.MEAN) ShortArray(sampleSize) else null

    /** 累计坏点样本数（暗帧掩膜命中）。 */
    var hotPixelCount: Int = 0
        private set

    /** 因尺寸不符被拒绝的帧数。 */
    var rejectedFrameCount: Int = 0
        private set

    /** 已并入的帧数。 */
    var accumulatedFrameCount: Int = 0
        private set

    val isSeeded: Boolean get() = accumulatedFrameCount > 0

    val expectedSampleSize: Int get() = sampleSize

    init {
        require(width > 0 && height > 0) { "invalid stacker size: ${width}x$height" }
    }

    /**
     * 并入一帧（内部完成暗帧扣除与叠加）。
     *
     * @param frame 交错 RGB 样本，长度应等于 [expectedSampleSize]。
     * @param dark 平均暗帧（可为 null = 无暗帧；尺寸不符时按无暗帧处理）。
     */
    fun addFrame(frame: ShortArray, dark: ShortArray? = null, darkConfig: DarkFrameConfig = DarkFrameConfig.DEFAULT) {
        if (frame.size != sampleSize) {
            rejectedFrameCount++
            return
        }
        val effectiveDark = if (darkConfig.enabled) dark?.takeIf { it.size == sampleSize } else null
        val max0 = maxAccumulator
        if (max0 != null) {
            val maskHot = darkConfig.hotPixelMode == HotPixelMode.ZERO
            val gain = if (darkConfig.enabled) darkConfig.subtractGain else 0f
            val hot = if (useNativeAcceleration) {
                LongExposureNative.subtractMaxAccumulate(
                    frame = frame,
                    dark = effectiveDark,
                    accum = max0.rawBuffer(),
                    gain = gain,
                    hotThreshold = darkConfig.hotPixelThreshold,
                    maskHotPixels = maskHot,
                    seeded = max0.frameCount > 0,
                )
            } else {
                LongExposureNative.kotlinSubtractMax(
                    frame = frame,
                    dark = effectiveDark,
                    accum = max0.rawBuffer(),
                    gain = gain,
                    hotThreshold = darkConfig.hotPixelThreshold,
                    maskHotPixels = maskHot,
                    seeded = max0.frameCount > 0,
                )
            }
            max0.commitExternallyWrittenFrame()
            hotPixelCount += hot
        } else {
            val mean = meanAccumulator!!
            if (darkConfig.enabled) {
                val scratch = subtractScratch!!
                // nativecompute: 「暗帧扣除 + MEAN 累加」融合为单次遍历（与下面两段逐值等价，
                // 省掉一次整幅 ShortArray 中转拷贝）；返回 -1 表示不可用/参数非法 → 走原两段式。
                val nativeHot = if (useNativeAcceleration) {
                    NativeComputeBridge.subtractMeanAccumulate(
                        frame = frame,
                        dark = effectiveDark,
                        sum = mean.rawSumBuffer(),
                        sampleCount = sampleSize,
                        gain = darkConfig.subtractGain,
                        hotThreshold = darkConfig.hotPixelThreshold,
                        maskHotPixels = darkConfig.hotPixelMode == HotPixelMode.ZERO,
                    )
                } else {
                    NativeComputeBridge.UNAVAILABLE_INT
                }
                if (nativeHot >= 0) {
                    mean.commitExternallyWrittenFrame()
                    hotPixelCount += nativeHot
                } else {
                    hotPixelCount += DarkFrameSubtractor.subtractInto(
                        frame = frame,
                        dark = effectiveDark,
                        out = scratch,
                        gain = darkConfig.subtractGain,
                        hotPixelThreshold = darkConfig.hotPixelThreshold,
                        hotPixelMode = darkConfig.hotPixelMode,
                    )
                    mean.addFrame(scratch)
                }
            } else {
                // nativecompute: 无暗帧时 MEAN 累加（纯求和，无 clamp，与 addFrame 逐值等价）
                val nativeOk = useNativeAcceleration &&
                    NativeComputeBridge.accumulateMean(mean.rawSumBuffer(), frame, sampleSize)
                if (nativeOk) {
                    mean.commitExternallyWrittenFrame()
                } else {
                    mean.addFrame(frame)
                }
            }        }
        accumulatedFrameCount++
    }

    /** 导出当前叠加结果（新数组，未应用色偏）。 */
    fun snapshot(): ShortArray =
        maxAccumulator?.snapshot() ?: meanAccumulator?.snapshot() ?: ShortArray(sampleSize)

    /**
     * 导出成片平面：叠加结果 + 可选光源画笔色偏增益。
     * @param colorGains 长度 3 的通道增益（[ColorTemperatureGains.gainsFor]）；null = 不做色偏。
     */
    fun result(colorGains: FloatArray? = null, toneMap: LocalToneMapConfig? = null): LinearRgbPlane {
        var samples = snapshot()
        if (colorGains != null) samples = ColorTemperatureGains.applyGains(samples, colorGains)
        if (toneMap != null && toneMap.enabled) {
            samples = LongExposureNative.toneMap(samples, width, height, toneMap)
                ?: SuperMoonToneMapper.toneMap(samples, width, height, toneMap)
        }
        return LinearRgbPlane(width, height, samples)
    }

    /**
     * 实时预览平面（低分辨率、点采样、直接读累加缓冲）。
     *
     * 不复制整幅缓冲、不做盒滤波 → 单次开销 O(预览像素数)，适合"每 N 帧一预览"的实时叠加预览。
     * 预览仅用于观察叠加进程，成片仍走 [result]（全分辨率、正确重采样）。
     */
    fun previewPlane(maxLongEdge: Int, colorGains: FloatArray? = null): LinearRgbPlane {
        val longest = max(width, height)
        val scale = min(1f, maxLongEdge.toFloat() / longest.toFloat())
        val pw = max(1, (width * scale).toInt())
        val ph = max(1, (height * scale).toInt())
        // 直接读累加缓冲/平均值，不整幅复制（预览只为观察叠加进程）。
        val maxSamples = maxAccumulator?.view()
        val meanAcc = meanAccumulator
        val out = ShortArray(pw * ph * 3)
        for (y in 0 until ph) {
            val sy = (y.toLong() * height / ph).toInt().coerceIn(0, height - 1)
            for (x in 0 until pw) {
                val sx = (x.toLong() * width / pw).toInt().coerceIn(0, width - 1)
                val s = (sy * width + sx) * 3
                val o = (y * pw + x) * 3
                for (c in 0 until 3) {
                    val value = maxSamples?.get(s + c)?.toInt() ?: meanAcc?.sampleAt(s + c) ?: 0
                    val gain = if (colorGains != null && colorGains[c].isFinite()) colorGains[c] else 1f
                    out[o + c] = (value * gain).toInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()
                }
            }
        }
        return LinearRgbPlane(pw, ph, out)
    }
}
