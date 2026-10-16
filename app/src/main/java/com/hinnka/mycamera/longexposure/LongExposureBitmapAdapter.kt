package com.hinnka.mycamera.longexposure

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 线性 RGB 平面 ↔ Android Bitmap 适配（**含 Android 依赖，不参与 JVM 单测**）。
 *
 * 全部算法（叠加、暗帧扣除、色调映射）都在 [LinearRgbPlane] / [LongExposureStacker] 内完成，
 * 本类只负责：
 *  - 线性域 → sRGB 显示域的编码（LUT 加速）；
 *  - Bitmap 尺寸管理（按输出长边上限缩放，避免 64MP 全分辨率 Bitmap 峰值内存）。
 */
object LongExposureBitmapAdapter {

    private const val TAG = "LongExposureBitmap"

    /** 编码查找表（线性 12bit → 8bit 显示值）。 */
    private val encodeLut: IntArray = IntArray(LinearRgbPlane.SAMPLE_MAX + 1) { linear ->
        val normalized = linear.toDouble() / LinearRgbPlane.SAMPLE_MAX
        val encoded = 255.0 * normalized.pow(1.0 / 2.2)
        encoded.roundToInt().coerceIn(0, 255)
    }

    /** 按缩放系数换算工作尺寸（至少 1×1）。 */
    fun scaledDimensions(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        val safeScale = if (scale.isFinite()) scale.coerceIn(0.01f, 4f) else 1f
        return maxOf(1, (width * safeScale).roundToInt()) to maxOf(1, (height * safeScale).roundToInt())
    }

    /** 计算"长边不超过 maxLongEdge"的目标尺寸；maxLongEdge <= 0 表示不缩放。 */
    fun dimensionsForMaxLongEdge(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int> {
        if (maxLongEdge <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxLongEdge) return width to height
        return scaledDimensions(width, height, maxLongEdge.toFloat() / longest.toFloat())
    }

    /**
     * 线性 RGB 平面 → ARGB_8888 Bitmap。
     *
     * @param maxLongEdge 输出长边上限（<=0 = 原尺寸）；超限时先盒平均下采样再编码。
     * @param gamma 编码 gamma（默认 2.2，与 sRGB 显示近似）。
     */
    fun toBitmap(plane: LinearRgbPlane, maxLongEdge: Int = 0, gamma: Float = 2.2f): Bitmap {
        val (width, height) = dimensionsForMaxLongEdge(plane.width, plane.height, maxLongEdge)
        val samples = if (width != plane.width || height != plane.height) {
            LongExposureResampler.downsample(plane.samples, plane.width, plane.height, width, height)
        } else {
            plane.samples
        }
        val pixels = IntArray(width * height)
        val useStandardGamma = !gamma.isFinite() || kotlin.math.abs(gamma - 2.2f) < 1e-3f
        var source = 0
        for (i in pixels.indices) {
            val r = samples[source]
            val g = samples[source + 1]
            val b = samples[source + 2]
            source += 3
            val rr = if (useStandardGamma) encodeLut[r.toInt()] else gammaEncode(r, gamma)
            val gg = if (useStandardGamma) encodeLut[g.toInt()] else gammaEncode(g, gamma)
            val bb = if (useStandardGamma) encodeLut[b.toInt()] else gammaEncode(b, gamma)
            pixels[i] = Color.rgb(rr, gg, bb)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * 从已解码文件读入平面（供"分片落盘"帧源使用）。
     * @param scale 读取时缩放系数（映射到 BitmapFactory inSampleSize 近似值）。
     */
    fun fromFile(path: String, scale: Float = 1f): LinearRgbPlane? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // 直接用采样比做整数降采样，避免先全解码再缩放。
            inSampleSize = sampleSizeFor(scale)
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(path, options) }.getOrNull() ?: return null
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Bitmap（sRGB 显示域）→ 线性 RGB 平面（解码 gamma 2.2）。 */
    fun fromBitmap(bitmap: Bitmap): LinearRgbPlane {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val samples = ShortArray(width * height * 3)
        for (i in pixels.indices) {
            val color = pixels[i]
            val base = i * 3
            samples[base] = decode(Color.red(color))
            samples[base + 1] = decode(Color.green(color))
            samples[base + 2] = decode(Color.blue(color))
        }
        return LinearRgbPlane(width, height, samples)
    }

    /** 8bit 显示值 → 12bit 线性样本（gamma 2.2 反解）。 */
    private fun decode(value: Int): Short {
        val normalized = value.coerceIn(0, 255) / 255.0
        return (normalized.pow(2.2) * LinearRgbPlane.SAMPLE_MAX)
            .roundToInt()
            .coerceIn(0, LinearRgbPlane.SAMPLE_MAX)
            .toShort()
    }

    private fun gammaEncode(linear: Short, gamma: Float): Int {
        val normalized = linear.toDouble() / LinearRgbPlane.SAMPLE_MAX
        return (255.0 * normalized.pow(1.0 / gamma)).roundToInt().coerceIn(0, 255)
    }

    private fun sampleSizeFor(scale: Float): Int {
        val safe = if (scale.isFinite()) scale.coerceIn(0.01f, 1f) else 1f
        if (safe >= 1f) return 1
        var sample = 1
        var current = 1f
        while (current / 2f >= safe && sample < 64) {
            current /= 2f
            sample *= 2
        }
        return sample
    }
}
