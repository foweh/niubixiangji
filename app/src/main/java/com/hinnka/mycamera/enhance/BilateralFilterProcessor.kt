package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.exp

/**
 * 快速双边降噪（Bilateral Filter）。
 *
 * 思路移植自 opencv/opencv（Apache-2.0，Star≈91191，已核实）`photo/src/bilateral_filter.dispatch.cpp`：
 * `w(p,q) = exp(-‖p-q‖²/(2σ_s²)) · exp(-(I_p - I_q)²/(2σ_c²))`。
 *
 * 相对原版的「快速」取舍（针对 3.9G 内存目标机）：
 * ① 值域权重只按**亮度差**计算，RGB 三通道共用同一权重（省 2/3 指数运算，视觉差异极小）；
 * ② 空间权重用预计算的一维可分离核（误差远小于 σ_s 本身的不确定性）；
 * ③ 输出按 [BilateralConfig.strength] 与原图混合，滑杆即混合系数。
 *
 * 分片：本类是管线里唯一 O(radius²)/像素的算子，故提供**行带分片**（[filterRows]），
 * 由 [EnhancePipeline] 在每个分片末尾回调 `job.checkYield()`，长时间任务也能暂停/取消。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object BilateralFilterProcessor {

    /**
     * 按配置降噪 ARGB_8888 像素，返回新数组（不改入参）。
     *
     * @param bandHeight 行带高度；<= 0 表示不分片（小图/单测）。
     * @param onChunk 每个行带完成后回调（集成层传 `{ job.checkYield() }`）。
     */
    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: BilateralConfig,
        bandHeight: Int = 0,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = BilateralConfig.clamp(config)
        val out = IntArray(pixels.size)
        if (clamped.strength <= 0f) {
            pixels.copyInto(out)
            onChunk?.invoke()
            return out
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val filtered = PlanarRgb(width, height)
        for (rowStart in 0 until height step bandHeightOf(bandHeight, height)) {
            val rowEnd = minOf(rowStart + bandHeightOf(bandHeight, height), height)
            filterRows(image, clamped, filtered, rowStart, rowEnd)
            onChunk?.invoke()
        }
        val n = minOf(image.count, width * height)
        for (c in 0 until 3) {
            val source = image.channel(c)
            val target = filtered.channel(c)
            for (i in 0 until n) {
                source[i] = PlanarRgb.mix(source[i], target[i], clamped.strength)
            }
        }
        image.toArgb(out, pixels)
        return out
    }

    /** 行带高度：未指定时一次算完。 */
    internal fun bandHeightOf(bandHeight: Int, height: Int): Int =
        if (bandHeight <= 0) height else bandHeight.coerceAtMost(height)

    /** 对平面 RGB 缓冲做快速双边滤波（整幅），返回新缓冲。 */
    internal fun filter(image: PlanarRgb, radius: Int, sigmaSpace: Float, sigmaColor: Float): PlanarRgb {
        val config = BilateralConfig.clamp(
            BilateralConfig(radius = radius, sigmaSpace = sigmaSpace, sigmaColor = sigmaColor)
        )
        val out = PlanarRgb(image.width, image.height)
        filterRows(image, config, out, 0, image.height)
        return out
    }

    /**
     * 逐行带双边滤波，结果写入 [out] 的对应行。
     * 边界（不足 radius）按 clamp 复制边界像素，保证输出与输入同尺寸。
     */
    internal fun filterRows(
        image: PlanarRgb,
        config: BilateralConfig,
        out: PlanarRgb,
        rowStart: Int,
        rowEnd: Int,
    ) {
        val r = config.radius.coerceAtLeast(1)
        val sigmaSpace = config.sigmaSpace.coerceAtLeast(1e-3f)
        val sigmaColor = config.sigmaColor.coerceAtLeast(1e-3f)
        val spatial = FloatArray(2 * r + 1) { k ->
            val d = (k - r).toFloat()
            exp(-(d * d) / (2f * sigmaSpace * sigmaSpace))
        }
        val rangeScale = -1f / (2f * sigmaColor * sigmaColor)
        val width = image.width
        val height = image.height
        val y0 = rowStart.coerceIn(0, height)
        val y1 = rowEnd.coerceIn(y0, height)

        // nativecompute: 本行带双边下沉 C++（同 kernel、同 clamp、同亮度差权重）
        if (NativeComputeBridge.bilateralRows(
                srcR = image.r,
                srcG = image.g,
                srcB = image.b,
                dstR = out.r,
                dstG = out.g,
                dstB = out.b,
                width = width,
                height = height,
                rowStart = rowStart,
                rowEnd = rowEnd,
                radius = config.radius,
                sigmaSpace = config.sigmaSpace,
                sigmaColor = config.sigmaColor,
            )
        ) {
            return
        }
        for (y in y0 until y1) {
            for (x in 0 until width) {
                val center = y * width + x
                val centerLuma = image.luma(center)
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f
                for (dy in -r..r) {
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val wy = spatial[dy + r]
                    val rowBase = sy * width
                    for (dx in -r..r) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val index = rowBase + sx
                        val lumaDiff = image.luma(index) - centerLuma
                        val weight = wy * spatial[dx + r] * exp(lumaDiff * lumaDiff * rangeScale)
                        sumR += image.r[index] * weight
                        sumG += image.g[index] * weight
                        sumB += image.b[index] * weight
                        sumW += weight
                    }
                }
                if (sumW <= 1e-12f) {
                    out.r[center] = image.r[center]
                    out.g[center] = image.g[center]
                    out.b[center] = image.b[center]
                } else {
                    out.r[center] = (sumR / sumW).coerceIn(0f, 1f)
                    out.g[center] = (sumG / sumW).coerceIn(0f, 1f)
                    out.b[center] = (sumB / sumW).coerceIn(0f, 1f)
                }
            }
        }
    }
}
