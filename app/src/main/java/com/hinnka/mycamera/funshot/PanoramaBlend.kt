package com.hinnka.mycamera.funshot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 形变后的帧 + 有效像素掩膜（掩膜为 false 表示该像素来自图像外，不参与融合）。 */
data class FunWarpedFrame(val width: Int, val height: Int, val argb: IntArray, val valid: BooleanArray)

/** 画布包围盒（左/上/右/下，右/下为开区间）。 */
data class FunCanvasBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Int get() = max(1, kotlin.math.ceil(right - left).toInt())
    val height: Int get() = max(1, kotlin.math.ceil(bottom - top).toInt())
}

/**
 * 柱面投影（水平大角度拼接更稳）：把平面图像重投到以 [focalPx] 为焦距的柱面上。
 *
 * 参考 OpenCV Stitcher 的 `CylindricalWarper` 思路（opencv/opencv, Apache-2.0），**代码自研**。
 */
object FunCylindricalProjection {

    fun project(frame: FunArgbFrame, focalPx: Float): FunWarpedFrame {
        val w = frame.width
        val h = frame.height
        val f = if (focalPx.isFinite() && focalPx > 1f) focalPx else max(w, h).toFloat()
        val cx = (w - 1) * 0.5
        val cy = (h - 1) * 0.5
        val out = IntArray(w * h)
        val valid = BooleanArray(w * h)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                // 柱面反变换：x_src = f*tan((x-cx)/f)，y_src = dy*sqrt(dx²+f²)/f
                val srcX = cx + f * kotlin.math.tan(dx / f)
                val srcY = cy + dy * sqrt(dx * dx + f * f) / f
                val sx = srcX.toInt()
                val sy = srcY.toInt()
                val i = y * w + x
                if (sx in 0 until w && sy in 0 until h) {
                    out[i] = frame.argb[sy * w + sx]
                    valid[i] = true
                } else {
                    out[i] = 0
                    valid[i] = false
                }
            }
        }
        return FunWarpedFrame(w, h, out, valid)
    }
}

/** 融合用数学助手：距离羽化权重、均值亮度、包围盒。 */
object FunBlendMath {

    /**
     * 距离羽化权重（两遍 chamfer 距离变换）：
     * 权重 = clamp(到无效区域的距离 / [featherRadius], 0, 1)，再取 [gamma] 次幂。
     */
    fun distanceWeights(valid: BooleanArray, width: Int, height: Int, featherRadius: Int, gamma: Float): FloatArray {
        val dist = FloatArray(width * height)
        val big = (width + height).toFloat()
        for (i in dist.indices) dist[i] = if (valid[i]) big else 0f
        // 前向扫描
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var d = dist[i]
                if (d > 0f) {
                    if (x > 0) d = min(d, dist[i - 1] + 1f)
                    if (y > 0) d = min(d, dist[i - width] + 1f)
                    if (x > 0 && y > 0) d = min(d, dist[i - width - 1] + 1.414f)
                    if (x < width - 1 && y > 0) d = min(d, dist[i - width + 1] + 1.414f)
                    dist[i] = d
                }
            }
        }
        // 后向扫描
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val i = y * width + x
                var d = dist[i]
                if (d > 0f) {
                    if (x < width - 1) d = min(d, dist[i + 1] + 1f)
                    if (y < height - 1) d = min(d, dist[i + width] + 1f)
                    if (x < width - 1 && y < height - 1) d = min(d, dist[i + width + 1] + 1.414f)
                    if (x > 0 && y < height - 1) d = min(d, dist[i + width - 1] + 1.414f)
                    dist[i] = d
                }
            }
        }
        val weights = FloatArray(width * height)
        val radius = featherRadius.coerceAtLeast(0).toFloat()
        val g = if (gamma.isFinite() && gamma > 0.05f) gamma else 1f
        for (i in weights.indices) {
            if (!valid[i]) {
                weights[i] = 0f
                continue
            }
            val normalized = if (radius <= 0f) 1f else min(1f, dist[i] / radius)
            weights[i] = if (g == 1f) normalized else Math.pow(normalized.toDouble(), g.toDouble()).toFloat()
        }
        return weights
    }

    /** 有效区均值亮度（Rec.601，0..255）；无有效像素返回 NaN。 */
    fun meanLuma(argb: IntArray, valid: BooleanArray): Double {
        var sum = 0.0
        var count = 0
        for (i in argb.indices) {
            if (!valid[i]) continue
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }

    /** 变换后的四角包围盒（含无效变换时用 [fallback] 顶替；无有效角点返回 null）。 */
    fun canvasBounds(width: Int, height: Int, homographies: List<FunMatrix3?>, fallback: FunMatrix3): FunCanvasBounds? {
        var left = Double.MAX_VALUE
        var top = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var bottom = -Double.MAX_VALUE
        for (index in homographies.indices) {
            val h = homographies[index] ?: fallback
            for (corner in cornersOf(width, height)) {
                val p = h.transform(corner) ?: return null
                left = min(left, p.x)
                top = min(top, p.y)
                right = max(right, p.x)
                bottom = max(bottom, p.y)
            }
        }
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right - left < 1.0 || bottom - top < 1.0) return null
        return FunCanvasBounds(left, top, right, bottom)
    }

    internal fun cornersOf(width: Int, height: Int): List<FunPointF> = listOf(
        FunPointF(0.0, 0.0),
        FunPointF(width - 1.0, 0.0),
        FunPointF(0.0, height - 1.0),
        FunPointF(width - 1.0, height - 1.0),
    )
}

/**
 * 权重累加画布：`sum += pixel * weight`，最后 `sum / weight`。
 *
 * 内存与帧数无关（固定 3 张 float + 1 张 weight，与工作分辨率相关），
 * 大缓冲分配前由调用方过 `guardMemory`（可降工作分辨率）。
 */
class FunArgbCanvas(val width: Int, val height: Int) {
    val sumR = FloatArray(width * height)
    val sumG = FloatArray(width * height)
    val sumB = FloatArray(width * height)
    val weight = FloatArray(width * height)

    /**
     * 把 [warped] 经单应 [h]（warped 坐标 → 画布坐标）累加进画布。
     *
     * 采用**逆映射 + 双线性采样**（逐目标像素），避免正向 splatting 的孔洞；
     * [gain] 为曝光补偿增益（1.0 = 不补偿）。
     */
    fun addWarped(
        warped: FunWarpedFrame,
        h: FunMatrix3,
        gain: Float,
        featherRadius: Int,
        gamma: Float,
    ): Boolean {
        val hInv = h.inverse() ?: return false
        val weights = FunBlendMath.distanceWeights(warped.valid, warped.width, warped.height, featherRadius, gamma)
        val g = if (gain.isFinite() && gain > 0f) gain else 1f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val src = hInv.transform(FunPointF(x + 0.5, y + 0.5)) ?: continue
                val sx = src.x - 0.5
                val sy = src.y - 0.5
                val x0 = kotlin.math.floor(sx).toInt()
                val y0 = kotlin.math.floor(sy).toInt()
                if (x0 < -1 || y0 < -1 || x0 > warped.width - 1 || y0 > warped.height - 1) continue
                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()
                val i00 = indexOf(warped, x0, y0) ?: continue
                val i10 = indexOf(warped, x0 + 1, y0) ?: continue
                val i01 = indexOf(warped, x0, y0 + 1) ?: continue
                val i11 = indexOf(warped, x0 + 1, y0 + 1) ?: continue
                val w00 = (1 - fx) * (1 - fy) * weights[i00]
                val w10 = fx * (1 - fy) * weights[i10]
                val w01 = (1 - fx) * fy * weights[i01]
                val w11 = fx * fy * weights[i11]
                val total = w00 + w10 + w01 + w11
                if (total <= 1e-6f) continue
                val canvasIndex = y * width + x
                sumR[canvasIndex] += g * (
                    w00 * ((warped.argb[i00] shr 16) and 0xFF) +
                        w10 * ((warped.argb[i10] shr 16) and 0xFF) +
                        w01 * ((warped.argb[i01] shr 16) and 0xFF) +
                        w11 * ((warped.argb[i11] shr 16) and 0xFF)
                    )
                sumG[canvasIndex] += g * (
                    w00 * ((warped.argb[i00] shr 8) and 0xFF) +
                        w10 * ((warped.argb[i10] shr 8) and 0xFF) +
                        w01 * ((warped.argb[i01] shr 8) and 0xFF) +
                        w11 * ((warped.argb[i11] shr 8) and 0xFF)
                    )
                sumB[canvasIndex] += g * (
                    w00 * (warped.argb[i00] and 0xFF) +
                        w10 * (warped.argb[i10] and 0xFF) +
                        w01 * (warped.argb[i01] and 0xFF) +
                        w11 * (warped.argb[i11] and 0xFF)
                    )
                weight[canvasIndex] += total
            }
        }
        return true
    }

    private fun indexOf(warped: FunWarpedFrame, x: Int, y: Int): Int? {
        if (x < 0 || y < 0 || x >= warped.width || y >= warped.height) return null
        val i = y * warped.width + x
        return if (warped.valid[i]) i else null
    }

    /** 归一化输出；未被任何帧覆盖的像素用邻域扩散填充，保证无黑边。 */
    fun toArgb(): IntArray {
        val out = IntArray(width * height)
        val filled = weight.copyOf()
        for (i in out.indices) {
            if (filled[i] > 1e-6f) {
                val r = (sumR[i] / filled[i]).toInt().coerceIn(0, 255)
                val g = (sumG[i] / filled[i]).toInt().coerceIn(0, 255)
                val b = (sumB[i] / filled[i]).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                out[i] = 0
            }
        }
        FillHoles.propagate(out, filled, width, height)
        return out
    }

    /** 空洞填充：多轮前/后向传播，把最近的已填充像素值扩散进空像素。 */
    private object FillHoles {
        private const val NO_SOURCE = Int.MIN_VALUE

        fun propagate(out: IntArray, filled: FloatArray, width: Int, height: Int) {
            var empty = 0
            for (i in out.indices) if (filled[i] <= 1e-6f) empty++
            if (empty == 0) return
            val pending = BooleanArray(out.size) { filled[it] <= 1e-6f }
            for (pass in 0 until 64) {
                var changed = false
                // 前向
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = y * width + x
                        if (!pending[i]) continue
                        val source = pick(out, pending, width, height, x, y)
                        if (source != NO_SOURCE) {
                            out[i] = source
                            pending[i] = false
                            changed = true
                        }
                    }
                }
                // 后向
                for (y in height - 1 downTo 0) {
                    for (x in width - 1 downTo 0) {
                        val i = y * width + x
                        if (!pending[i]) continue
                        val source = pick(out, pending, width, height, x, y)
                        if (source != NO_SOURCE) {
                            out[i] = source
                            pending[i] = false
                            changed = true
                        }
                    }
                }
                if (!changed) break
            }
        }

        private fun pick(out: IntArray, pending: BooleanArray, width: Int, height: Int, x: Int, y: Int): Int {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val ni = ny * width + nx
                    if (!pending[ni]) return out[ni]
                }
            }
            return NO_SOURCE
        }
    }
}

/**
 * 逐帧曝光补偿：把各帧均值亮度对齐到参考帧，增益被 [gainLimit] 限制，避免拉爆。
 */
object FunExposureCompensator {

    fun gainFor(
        frame: FunWarpedFrame,
        referenceMeanLuma: Double,
        enabled: Boolean,
        gainLimit: Float,
    ): Float {
        if (!enabled) return 1f
        val mean = FunBlendMath.meanLuma(frame.argb, frame.valid)
        if (!mean.isFinite() || mean <= 1e-3 || !referenceMeanLuma.isFinite()) return 1f
        val raw = referenceMeanLuma / mean
        if (!raw.isFinite()) return 1f
        val limit = if (gainLimit.isFinite()) gainLimit.coerceIn(1f, 4f) else 1.6f
        return raw.coerceIn(1.0 / limit, limit).toFloat()
    }

    /** 相对亮度差是否超限（诊断用）。 */
    fun isOutlier(meanLuma: Double, referenceMeanLuma: Double, tolerance: Double = 0.5): Boolean {
        if (!meanLuma.isFinite() || !referenceMeanLuma.isFinite() || referenceMeanLuma <= 1e-3) return false
        return abs(meanLuma - referenceMeanLuma) / referenceMeanLuma > tolerance
    }
}
