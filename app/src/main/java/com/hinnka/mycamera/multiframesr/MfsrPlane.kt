package com.hinnka.mycamera.multiframesr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 单通道浮点亮度平面（行主序）。MFSR 全部数值计算都在它上面进行：纯 Kotlin、零 Android 依赖，
 * 因此核心算法可在纯 JVM 单测里直接跑。
 *
 * 约定：采样坐标 [bilinear] 使用**像素中心**坐标系（整数 x 对应第 x 个像素中心）；
 * 越界返回 NaN（而非静默 clamp），让上层能区分「帧外」与「真实 0」，从而正确统计覆盖率。
 */
class MfsrPlane(
    val width: Int,
    val height: Int,
    val data: FloatArray = FloatArray(width * height),
) {
    init {
        require(width > 0 && height > 0) { "plane size must be positive: ${width}x$height" }
        require(data.size == width * height) {
            "data size ${data.size} != ${width}x$height"
        }
    }

    val pixels: Int get() = width * height

    fun at(x: Int, y: Int): Float = data[y * width + x]

    /** 边界 clamp 取整像素（用于卷积等需要固定窗口的场合）。 */
    fun clampi(x: Int, y: Int): Float =
        data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun fill(v: Float) {
        data.fill(v)
    }

    fun copy(): MfsrPlane = MfsrPlane(width, height, data.copyOf())

    /**
     * 双线性采样（像素中心坐标）。越界（含 0.5px 容差）返回 NaN。
     */
    fun bilinear(x: Float, y: Float): Float {
        if (!x.isFinite() || !y.isFinite()) return Float.NaN
        if (x < -0.5f || y < -0.5f || x > width - 0.5f || y > height - 0.5f) return Float.NaN
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val v00 = data[y0 * width + x0]
        val v10 = data[y0 * width + x1]
        val v01 = data[y1 * width + x0]
        val v11 = data[y1 * width + x1]
        val top = v00 + (v10 - v00) * fx
        val bot = v01 + (v11 - v01) * fx
        return top + (bot - top) * fy
    }

    fun mean(): Float {
        var s = 0.0
        for (v in data) s += v
        return (s / pixels).toFloat()
    }

    /** 样本方差（用于判断「无纹理帧」）。 */
    fun variance(): Float {
        val m = mean().toDouble()
        var s = 0.0
        for (v in data) {
            val d = v - m
            s += d * d
        }
        return (s / pixels).toFloat()
    }

    /** 取样式降采样/升采样到目标尺寸：降采样用区域平均（抗混叠），升采样用双线性。 */
    fun resampleTo(targetW: Int, targetH: Int): MfsrPlane {
        val w = targetW.coerceAtLeast(1)
        val h = targetH.coerceAtLeast(1)
        if (w == width && h == height) return copy()
        val out = MfsrPlane(w, h)
        val scX = width.toFloat() / w
        val scY = height.toFloat() / h
        val down = scX > 1.001f || scY > 1.001f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (down) areaSample((x + 0.5f) * scX - 0.5f, (y + 0.5f) * scY - 0.5f, scX, scY)
                else bilinear((x + 0.5f) * scX - 0.5f, (y + 0.5f) * scY - 0.5f)
                out.data[y * w + x] = if (v.isFinite()) v else clampi(x, y)
            }
        }
        return out
    }

    /** 以 (cx,cy) 为中心、按 (spanX,spanY) 窗口做区域平均（边界 clamp）。 */
    private fun areaSample(cx: Float, cy: Float, spanX: Float, spanY: Float): Float {
        val halfX = max(1, (spanX / 2f).roundToInt())
        val halfY = max(1, (spanY / 2f).roundToInt())
        val x0 = cx.toInt() - halfX + 1
        val x1 = cx.toInt() + halfX
        val y0 = cy.toInt() - halfY + 1
        val y1 = cy.toInt() + halfY
        var s = 0.0
        var n = 0
        for (y in y0..y1) {
            val yy = y.coerceIn(0, height - 1)
            for (x in x0..x1) {
                val xx = x.coerceIn(0, width - 1)
                s += data[yy * width + xx]
                n++
            }
        }
        return if (n == 0) data[0] else (s / n).toFloat()
    }

    /** 可分离高斯模糊（卷积核半径 = ceil(3σ)）。 */
    fun gaussianBlur(sigma: Float): MfsrPlane {
        val s = if (sigma.isFinite() && sigma > 0.05f) sigma else 0.05f
        val radius = max(1, ceil(3f * s).toInt())
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val v = exp(-(i * i) / (2.0 * s * s)).toFloat()
            kernel[i + radius] = v
            sum += v
        }
        for (i in kernel.indices) kernel[i] /= sum
        val tmp = FloatArray(pixels)
        val out = FloatArray(pixels)
        // 水平
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    acc += kernel[k + radius] * clampi(x + k, y)
                }
                tmp[row + x] = acc
            }
        }
        // 垂直
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    acc += kernel[k + radius] * tmp[k.coerceIn(0, height - 1).let { yy -> yy * width + x }]
                }
                out[row + x] = acc
            }
        }
        return MfsrPlane(width, height, out)
    }

    /** 中心差分梯度（边界前向/后向）。 */
    fun gradient(x: Int, y: Int): Pair<Float, Float> {
        val xm = x - 1
        val xp = x + 1
        val ym = y - 1
        val yp = y + 1
        val gx = if (xm >= 0 && xp < width) (at(xp, y) - at(xm, y)) * 0.5f
        else if (xp < width) at(xp, y) - at(x, y) else at(x, y) - at(xm, y)
        val gy = if (ym >= 0 && yp < height) (at(x, yp) - at(x, ym)) * 0.5f
        else if (yp < height) at(x, yp) - at(x, y) else at(x, y) - at(x, ym)
        return gx to gy
    }

    /** 逐点截断到 [lo, hi]（非有限值回落 lo）。 */
    fun clampInPlace(lo: Float, hi: Float) {
        val a = min(lo, hi)
        val b = max(lo, hi)
        for (i in data.indices) {
            val v = data[i]
            data[i] = if (v.isFinite()) v.coerceIn(a, b) else a
        }
    }

    fun toClamped(minV: Float, maxV: Float): MfsrPlane {
        val out = MfsrPlane(width, height)
        for (i in data.indices) {
            val v = data[i]
            out.data[i] = if (v.isFinite()) v.coerceIn(minV, maxV) else minV
        }
        return out
    }

    override fun toString(): String = "MfsrPlane(${width}x$height)"

    companion object {
        /** 由 ARGB(0xAARRGGBB) 整型数组构造亮度平面（Rec.601）。纯逻辑，供 Bitmap 桥接与单测共用。 */
        fun lumaFromArgb(argb: IntArray, width: Int, height: Int): MfsrPlane {
            require(argb.size >= width * height) { "argb too small" }
            val out = MfsrPlane(width, height)
            for (i in 0 until width * height) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out.data[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            return out
        }
    }
}

/** 平面统计小工具（对齐置信度 / 残差诊断用）。 */
internal object MfsrStat {
    /** 均值绝对偏差：衡量一幅图"有多少纹理"，用于置信度归一。 */
    fun meanAbsDeviation(plane: MfsrPlane): Float {
        val m = plane.mean()
        var s = 0.0
        for (v in plane.data) s += abs(v - m)
        return (s / plane.pixels).toFloat()
    }

    /** 归一化到 0..1 的 sigmoid，用作"指标 → 置信度"的软映射。 */
    fun softConfidence(metric: Float, halfPoint: Float): Float {
        if (!metric.isFinite()) return 0f
        if (halfPoint <= 0f) return if (metric > 0f) 1f else 0f
        val x = metric / halfPoint
        return (x / (1f + x)).coerceIn(0f, 1f)
    }
}

/** 便于测试/日志的浮点近似比较（0.1px 精度判定阈值集中在此）。 */
internal object MfsrTolerance {
    const val SUBPIXEL_TARGET_PX = 0.1f
    const val LOOSE_EPS = 1e-4f
    fun within(a: Float, b: Float, tol: Float): Boolean = abs(a - b) <= tol
    fun norm2(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
}
