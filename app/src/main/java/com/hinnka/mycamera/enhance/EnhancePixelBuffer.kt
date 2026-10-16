package com.hinnka.mycamera.enhance

import kotlin.math.max
import kotlin.math.min

/**
 * 平面 RGB 浮点缓冲（0..1），三个通道各自连续存储。
 *
 * 之所以用平面结构而非交错 ARGB：本模块的滤波（盒式模糊、双边、导向滤波、Sobel）
 * 都是逐通道可分离的，平面结构能顺序访存、缓存友好，且便于分片处理。
 *
 * 纯 Kotlin 实现（无 Android 依赖），可直接 JVM 单测。
 */
internal class PlanarRgb(val width: Int, val height: Int) {
    val count: Int = width * height
    val r: FloatArray = FloatArray(count)
    val g: FloatArray = FloatArray(count)
    val b: FloatArray = FloatArray(count)

    fun channel(index: Int): FloatArray = when (index) {
        0 -> r
        1 -> g
        2 -> b
        else -> error("invalid channel $index")
    }

    /** Rec.709 亮度，与相机/显示器亮度口径一致。 */
    fun luma(i: Int): Float = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]

    fun copyOf(): PlanarRgb {
        val out = PlanarRgb(width, height)
        r.copyInto(out.r)
        g.copyInto(out.g)
        b.copyInto(out.b)
        return out
    }

    /**
     * 写回 ARGB_8888 整型像素。
     * @param alphaSource 非空时逐像素沿用其 alpha（保护带透明通道的输入），否则 alpha = 255。
     */
    fun toArgb(dst: IntArray, alphaSource: IntArray? = null) {
        val n = min(count, dst.size)
        for (i in 0 until n) {
            val a = alphaSource?.let { (it[i] ushr 24) and 0xFF } ?: 255
            dst[i] = (a shl 24) or
                (toByteChannel(r[i]) shl 16) or
                (toByteChannel(g[i]) shl 8) or
                toByteChannel(b[i])
        }
    }

    companion object {
        fun fromArgb(pixels: IntArray, width: Int, height: Int): PlanarRgb {
            val out = PlanarRgb(width, height)
            val n = min(out.count, pixels.size)
            for (i in 0 until n) {
                val c = pixels[i]
                out.r[i] = ((c ushr 16) and 0xFF) / 255f
                out.g[i] = ((c ushr 8) and 0xFF) / 255f
                out.b[i] = (c and 0xFF) / 255f
            }
            return out
        }

        /** 0..1 浮点 → 0..255 字节通道（含四舍五入与饱和）。 */
        fun toByteChannel(v: Float): Int = when {
            v.isNaN() -> 0
            v <= 0f -> 0
            v >= 1f -> 255
            else -> (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        /** 逐元素线性混合：a·(1 - t) + b·t，t 先 clamp 到 [0,1]。 */
        fun mix(a: Float, b: Float, t: Float): Float {
            val k = if (t.isNaN()) 0f else max(0f, min(1f, t))
            return a * (1f - k) + b * k
        }
    }
}
