package com.hinnka.mycamera.multiframesr

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * 最终精修：可选双边降噪 → 反锐化（USM）。
 *
 * 顺序有讲究：先降噪再锐化，避免把噪声当成细节放大。强度受 [MfsrConfig.strength] 缩放，
 * strength=0 时输出与输入一致（便于"只要融合不要精修"的画质对比）。
 */
internal object MfsrRefiner {

    fun apply(plane: MfsrPlane, config: MfsrConfig): MfsrPlane {
        val strength = if (config.strength.isFinite()) {
            config.strength.coerceIn(MfsrConfig.STRENGTH_MIN, MfsrConfig.STRENGTH_MAX)
        } else 1f
        if (strength <= 0f) return plane
        var out = plane
        if (config.denoiseOn) {
            out = bilateral(
                plane = out,
                radius = config.denoiseRadius.coerceIn(MfsrConfig.DENOISE_RADIUS_MIN, MfsrConfig.DENOISE_RADIUS_MAX),
                sigmaRange = 10f + 18f * strength,
                mix = (0.85f * strength).coerceIn(0f, 1f),
            )
        }
        val amount = (config.sharpenAmount.coerceIn(MfsrConfig.SHARPEN_AMOUNT_MIN, MfsrConfig.SHARPEN_AMOUNT_MAX)) *
            strength.coerceAtMost(1.5f)
        if (amount > 0f) {
            out = unsharp(
                plane = out,
                amount = amount,
                sigma = config.sharpenSigma
                    .coerceIn(MfsrConfig.SHARPEN_SIGMA_MIN, MfsrConfig.SHARPEN_SIGMA_MAX),
            )
        }
        return out
    }

    /**
     * 反锐化掩模：`out = v + amount * (v - blur(v))`，结果 clamp 到 [0,255]。
     */
    fun unsharp(plane: MfsrPlane, amount: Float, sigma: Float): MfsrPlane {
        if (amount <= 0f) return plane
        // nativecompute: 可分离高斯模糊 + 反锐化 + clamp 下沉 C++；失败则继续下面的纯 Kotlin
        val nativeOut = MfsrPlane(plane.width, plane.height)
        if (NativeComputeBridge.unsharpLuma(
                src = plane.data,
                dst = nativeOut.data,
                width = plane.width,
                height = plane.height,
                amount = amount,
                sigma = sigma,
            )
        ) {
            return nativeOut
        }
        val blur = plane.gaussianBlur(sigma)
        val out = MfsrPlane(plane.width, plane.height)
        for (i in plane.data.indices) {
            val v = plane.data[i]
            val v2 = v + amount * (v - blur.data[i])
            out.data[i] = if (v2.isFinite()) v2.coerceIn(
                MfsrIterativeBackProjection.CLAMP_LO,
                MfsrIterativeBackProjection.CLAMP_HI,
            ) else v
        }
        return out
    }

    /**
     * 简化双边滤波（十字窗口，空间高斯 × 值域高斯）。
     *
     * @param mix 与原始像素的混合系数（0 = 不降噪）。
     */
    fun bilateral(plane: MfsrPlane, radius: Int, sigmaRange: Float, mix: Float): MfsrPlane {
        if (radius <= 0 || mix <= 0f) return plane
        val r = radius.coerceAtMost(MfsrConfig.DENOISE_RADIUS_MAX)
        val sr = if (sigmaRange.isFinite() && sigmaRange > 1e-3f) sigmaRange else 10f
        // nativecompute: 空间高斯 × 512 档值域 LUT × mix 混合下沉 C++；失败则继续下面的纯 Kotlin
        val nativeOut = MfsrPlane(plane.width, plane.height)
        if (NativeComputeBridge.bilateralLuma(
                src = plane.data,
                dst = nativeOut.data,
                width = plane.width,
                height = plane.height,
                radius = r,
                sigmaRange = sr,
                mix = mix,
            )
        ) {
            return nativeOut
        }
        val spatial = FloatArray(r + 1) { i -> exp(-(i * i) / (2.0 * max(1, r) * max(1, r))).toFloat() }
        val rangeLut = FloatArray(512) { i ->
            val d = (i - 256) / 256f * sr * 2f
            exp(-(d * d) / (2.0 * sr * sr)).toFloat()
        }
        val w = plane.width
        val h = plane.height
        val out = MfsrPlane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = plane.at(x, y)
                var acc = 0f
                var wsum = 0f
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val sy = spatial[abs(dy)]
                    for (dx in -r..r) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val v = plane.at(xx, yy)
                        val diff = v - center
                        val idx = (diff + 256f).toInt().coerceIn(0, 511)
                        val wgt = sy * spatial[abs(dx)] * rangeLut[idx]
                        acc += v * wgt
                        wsum += wgt
                    }
                }
                val filtered = if (wsum > 0f) acc / wsum else center
                out.data[y * w + x] = center + (filtered - center) * mix
            }
        }
        return out
    }
}
