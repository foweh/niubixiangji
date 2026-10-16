package com.hinnka.mycamera.funshot

import kotlin.math.abs

/** 二维点（双精度，便于单应估计的数值稳定）。 */
data class FunPointF(val x: Double, val y: Double) {
    operator fun plus(other: FunPointF) = FunPointF(x + other.x, y + other.y)
    operator fun minus(other: FunPointF) = FunPointF(x - other.x, y - other.y)
    fun norm(): Double = kotlin.math.sqrt(x * x + y * y)
}

/**
 * 3×3 齐次矩阵（行主序，9 个元素）。
 *
 * 单应矩阵 H 满足 `p_dst ~ H · p_src`（齐次坐标）；[transform] 做透视除法，
 * `|w| < EPS` 时返回 null（点在无穷远，调用方跳过该像素）。
 */
class FunMatrix3(val v: DoubleArray) {

    init {
        require(v.size == 9) { "FunMatrix3 requires 9 elements, got ${v.size}" }
    }

    operator fun times(other: FunMatrix3): FunMatrix3 {
        val r = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += v[row * 3 + k] * other.v[k * 3 + col]
                }
                r[row * 3 + col] = sum
            }
        }
        return FunMatrix3(r)
    }

    /** 透视变换；`|w| < [EPS]` 或结果非有限值返回 null。 */
    fun transform(p: FunPointF): FunPointF? {
        val x = v[0] * p.x + v[1] * p.y + v[2]
        val y = v[3] * p.x + v[4] * p.y + v[5]
        val w = v[6] * p.x + v[7] * p.y + v[8]
        if (!w.isFinite() || abs(w) < EPS) return null
        val ox = x / w
        val oy = y / w
        return if (ox.isFinite() && oy.isFinite()) FunPointF(ox, oy) else null
    }

    fun det(): Double =
        v[0] * (v[4] * v[8] - v[5] * v[7]) -
            v[1] * (v[3] * v[8] - v[5] * v[6]) +
            v[2] * (v[3] * v[7] - v[4] * v[6])

    /** 逆矩阵（伴随矩阵 / 行列式）；奇异或非有限值返回 null。 */
    fun inverse(): FunMatrix3? {
        val d = det()
        if (!d.isFinite() || abs(d) < EPS) return null
        val inv = 1.0 / d
        val r = DoubleArray(9)
        r[0] = (v[4] * v[8] - v[5] * v[7]) * inv
        r[1] = (v[2] * v[7] - v[1] * v[8]) * inv
        r[2] = (v[1] * v[5] - v[2] * v[4]) * inv
        r[3] = (v[5] * v[6] - v[3] * v[8]) * inv
        r[4] = (v[0] * v[8] - v[2] * v[6]) * inv
        r[5] = (v[2] * v[3] - v[0] * v[5]) * inv
        r[6] = (v[3] * v[7] - v[4] * v[6]) * inv
        r[7] = (v[1] * v[6] - v[0] * v[7]) * inv
        r[8] = (v[0] * v[4] - v[1] * v[3]) * inv
        return FunMatrix3(r)
    }

    fun toDoubleArray(): DoubleArray = v.copyOf()

    fun isFinite(): Boolean = v.all { it.isFinite() }

    override fun equals(other: Any?): Boolean =
        other is FunMatrix3 && v.contentEquals(other.v)

    override fun hashCode(): Int = v.contentHashCode()

    override fun toString(): String =
        v.toList().chunked(3).joinToString(" | ") { row -> row.joinToString(", ") }

    companion object {
        const val EPS = 1e-12

        fun identity(): FunMatrix3 = FunMatrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun translation(tx: Double, ty: Double): FunMatrix3 =
            FunMatrix3(doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0))

        fun scale(sx: Double, sy: Double): FunMatrix3 =
            FunMatrix3(doubleArrayOf(sx, 0.0, 0.0, 0.0, sy, 0.0, 0.0, 0.0, 1.0))
    }
}

/** 灰度图（0..255 浮点，含像素中心双线性采样）。 */
class FunGrayImage(val width: Int, val height: Int, val pixels: FloatArray) {

    init {
        require(width > 0 && height > 0) { "gray image must be non-empty" }
        require(pixels.size == width * height) {
            "pixels size ${pixels.size} != $width x $height"
        }
    }

    /** 越界返回 0（用 [inBounds] 判断有效性）。 */
    fun at(x: Int, y: Int): Float =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0f

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** 双线性采样；采样点越界时返回 null（调用方视为无效像素）。 */
    fun bilinear(x: Double, y: Double): Float? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (x < -0.5 || y < -0.5 || x > width - 0.5 || y > height - 0.5) return null
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val p00 = at(x0, y0)
        val p10 = at(x0 + 1, y0)
        val p01 = at(x0, y0 + 1)
        val p11 = at(x0 + 1, y0 + 1)
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return top + (bottom - top) * fy
    }

    /** 取整像素（最近邻，越界 clamp 到边界）。 */
    fun nearestClamped(x: Int, y: Int): Float =
        at(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
}

/** ARGB 图像帧（全景输入 / 输出统一载体）。 */
data class FunArgbFrame(val width: Int, val height: Int, val argb: IntArray) {

    init {
        require(width > 0 && height > 0) { "frame must be non-empty" }
        require(argb.size == width * height) {
            "argb size ${argb.size} != $width x $height"
        }
    }

    /** Rec.601 full-range 亮度，范围 0..255。 */
    fun toGray(): FunGrayImage {
        val g = FloatArray(width * height)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val b = c and 0xFF
            g[i] = 0.299f * r + 0.587f * gg + 0.114f * b
        }
        return FunGrayImage(width, height, g)
    }

    companion object {
        fun fromGray(image: FunGrayImage): FunArgbFrame {
            val out = IntArray(image.width * image.height)
            for (i in out.indices) {
                val v = image.pixels[i].coerceIn(0f, 255f).toInt()
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            return FunArgbFrame(image.width, image.height, out)
        }
    }
}

/** 按比例缩放 ARGB 帧（最近邻；用于工作分辨率降级，保持内容一致）。 */
object FunArgbScaler {

    fun scale(frame: FunArgbFrame, scale: Float): FunArgbFrame {
        if (!scale.isFinite() || scale >= 0.999f) return frame
        val tw = (frame.width * scale).toInt().coerceAtLeast(8)
        val th = (frame.height * scale).toInt().coerceAtLeast(8)
        return resize(frame, tw, th)
    }

    /** 缩放到指定尺寸（最近邻）。尺寸非法时原样返回。 */
    fun resize(frame: FunArgbFrame, targetW: Int, targetH: Int): FunArgbFrame {
        if (targetW <= 0 || targetH <= 0) return frame
        if (targetW == frame.width && targetH == frame.height) return frame
        val out = IntArray(targetW * targetH)
        val sx = frame.width.toDouble() / targetW
        val sy = frame.height.toDouble() / targetH
        var i = 0
        for (y in 0 until targetH) {
            val srcY = (y * sy + sy * 0.5).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until targetW) {
                val srcX = (x * sx + sx * 0.5).toInt().coerceIn(0, frame.width - 1)
                out[i++] = frame.argb[srcY * frame.width + srcX]
            }
        }
        return FunArgbFrame(targetW, targetH, out)
    }

    /** 限制长边不超过 [maxSide]（保持比例；已满足则原样返回）。 */
    fun limitLongSide(frame: FunArgbFrame, maxSide: Int): FunArgbFrame {
        val longSide = maxOf(frame.width, frame.height)
        if (longSide <= maxSide) return frame
        val ratio = maxSide.toFloat() / longSide
        val tw = (frame.width * ratio).toInt().coerceAtLeast(1)
        val th = (frame.height * ratio).toInt().coerceAtLeast(1)
        return resize(frame, tw, th)
    }
}
