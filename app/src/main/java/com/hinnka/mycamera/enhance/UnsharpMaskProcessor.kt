package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.abs

/**
 * 反锐化掩模（Unsharp Mask）锐化。
 *
 * 算法思路移植自 opencv/opencv（Apache-2.0，Star≈91191，已核实）`imgproc` 的
 * `GaussianBlur` + `addWeighted` 组合：`out = I + amount · (I - blur(I))`。
 * 本实现用三次分离式盒式滤波近似高斯（[BoxBlur.gaussianApprox]），无额外依赖；
 * 并加入阈值门控（|I - blur| 小于阈值不锐化），避免把噪声放大成颗粒。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object UnsharpMaskProcessor {

    /** 按配置锐化 ARGB_8888 像素，返回新数组（不改入参）。 */
    fun process(pixels: IntArray, width: Int, height: Int, config: UnsharpMaskConfig): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = UnsharpMaskConfig.clamp(config)
        val out = IntArray(pixels.size)
        val radius = BoxBlur.radiusFromFloat(clamped.radius)
        if (clamped.amount <= 0f || radius <= 0) {
            pixels.copyInto(out)
            return out
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        // nativecompute: 三次盒式模糊近似高斯 + 阈值门控反锐化下沉 C++（逐通道）；
        // 逐通道记录是否已由 native 处理，未处理的通道继续走下面的纯 Kotlin 实现。
        val nativeHandled = BooleanArray(3)
        if (NativeComputeBridge.available) {
            for (c in 0 until 3) {
                nativeHandled[c] = NativeComputeBridge.unsharpChannel(
                    image.channel(c),
                    width,
                    height,
                    radius,
                    clamped.amount,
                    clamped.threshold,
                )
            }
        }
        for (c in 0 until 3) {
            if (nativeHandled[c]) continue
            val channel = image.channel(c)
            val blurred = BoxBlur.gaussianApprox(channel, width, height, radius)
            sharpenChannelInPlace(channel, blurred, clamped.amount, clamped.threshold)
        }
        image.toArgb(out, pixels)
        return out
    }

    /**
     * 就地把通道替换为锐化结果。
     * @param threshold 以 0..255 为单位；差值小于该值直接丢弃（弱边抑制）。
     */
    internal fun sharpenChannelInPlace(
        channel: FloatArray,
        blurred: FloatArray,
        amount: Float,
        threshold: Int,
    ) {
        val thresholdNorm = if (threshold <= 0) 0f else threshold / 255f
        val n = minOf(channel.size, blurred.size)
        for (i in 0 until n) {
            val diff = channel[i] - blurred[i]
            if (thresholdNorm > 0f && abs(diff) < thresholdNorm) continue
            channel[i] = (channel[i] + amount * diff).coerceIn(0f, 1f)
        }
    }
}
