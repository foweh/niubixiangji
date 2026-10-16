package com.hinnka.mycamera.focusstack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 清晰度图计算 —— 焦点堆叠的「谁在这个像素上更清晰」判据。
 *
 * 流程（逐帧）：亮度 → 细节响应（拉普拉斯 / Sobel 梯度）→ 局部统计（方差 / 均值）
 * → 稳健归一化 → 逐帧权重 → 高斯平滑 + 邻域一致性 → 软掩模。
 *
 * 算法思路来源：PetteriAimonen/focus-stack（MIT，Star≈670，C++）。
 * 其核心是「小波/拉普拉斯细节能量 → 逐像素选择或加权」；本工程按其思路实现为
 * **可切换的拉普拉斯方差 / 梯度能量 + 软掩模**，并做分片友好的常驻内存控制（见 changelog 复用标注）。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测；native 加速见 [FocusStackNative]。
 */
object FocusClarityMap {

    /** 权重分母下限：避免全零清晰度导致除零。 */
    const val MASK_EPS = 1e-8f

    /** 一致性滤波阈值：某像素权重超过「四邻域最大值 + 该阈值」时压回邻域（抑制孤立噪点抢焦点）。 */
    const val CONSISTENCY_RATIO_THRESHOLD = 1.5f

    // ------------------------------------------------------------------ 亮度 / 细节响应

    /** ARGB 整型像素 → 0..1 亮度（Rec.709，与相机亮度口径一致）。 */
    fun lumaFromArgb(pixels: IntArray, count: Int = pixels.size): FloatArray {
        val n = count.coerceIn(0, pixels.size)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            out[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        return out
    }

    /**
     * 拉普拉斯响应（4 邻域中心核）：`L = 4c - (l + r + u + d)`，边界用钳位复制。
     */
    fun laplacianResponse(luma: FloatArray, width: Int, height: Int): FloatArray {
        val n = minOf(luma.size, width * height)
        val out = FloatArray(n)
        if (width <= 0 || height <= 0) return out
        for (y in 0 until height) {
            val y0 = max(0, y - 1); val y1 = minOf(height - 1, y + 1)
            for (x in 0 until width) {
                val x0 = max(0, x - 1); val x1 = minOf(width - 1, x + 1)
                val i = y * width + x
                val c = luma[i]
                out[i] = 4f * c - (luma[y * width + x0] + luma[y * width + x1] +
                    luma[y0 * width + x] + luma[y1 * width + x])
            }
        }
        return out
    }

    /** Sobel 梯度幅值 `sqrt(gx² + gy²)`，边界钳位。 */
    fun gradientMagnitude(luma: FloatArray, width: Int, height: Int): FloatArray {
        val n = minOf(luma.size, width * height)
        val out = FloatArray(n)
        if (width <= 0 || height <= 0) return out
        for (y in 0 until height) {
            val ym = max(0, y - 1); val yp = minOf(height - 1, y + 1)
            for (x in 0 until width) {
                val xm = max(0, x - 1); val xp = minOf(width - 1, x + 1)
                val tl = luma[ym * width + xm]; val tc = luma[ym * width + x]; val tr = luma[ym * width + xp]
                val ml = luma[y * width + xm]; val mr = luma[y * width + xp]
                val bl = luma[yp * width + xm]; val bc = luma[yp * width + x]; val br = luma[yp * width + xp]
                val gx = (tr + 2f * mr + br) - (tl + 2f * ml + bl)
                val gy = (bl + 2f * bc + br) - (tl + 2f * tc + tr)
                out[y * width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 局部统计

    /** 可分离盒式模糊（半径 r；滑动窗口 + 边界钳位；O(w·h)）。 */
    fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (width <= 0 || height <= 0) return FloatArray(0)
        val n = minOf(src.size, width * height)
        if (n <= 0) return FloatArray(0)
        val r = radius.coerceAtLeast(0)
        if (r == 0) return src.copyOf(n)
        val window = 2 * r + 1
        val tmp = FloatArray(n)

        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                tmp[row + x] = sum / window
                sum += src[row + (x + r + 1).coerceIn(0, width - 1)] -
                    src[row + (x - r).coerceIn(0, width - 1)]
            }
        }

        val out = FloatArray(n)
        for (x in 0 until width) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                sum += tmp[(y + r + 1).coerceIn(0, height - 1) * width + x] -
                    tmp[(y - r).coerceIn(0, height - 1) * width + x]
            }
        }
        return out
    }

    /**
     * 高斯平滑（3 遍盒式模糊近似；[radius] = 0 时返回拷贝）。
     *
     * 3 遍盒式滤波在视觉上近似高斯，且每遍都是 O(w·h)，比真正的卷积核快一个量级。
     */
    fun gaussianBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        // 3 遍盒式：等效 σ ≈ radius，盒半径 r' ≈ radius/2（下限 1）。
        val r = max(1, radius / 2)
        var acc = boxBlur(src, width, height, r)
        acc = boxBlur(acc, width, height, r)
        return boxBlur(acc, width, height, r)
    }

    /** 局部方差：`Var = E[x²] - E[x]²`（负值截断为 0，防浮点误差）。 */
    fun localVariance(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        var acc = boxBlur(values, width, height, radius)
        val squared = FloatArray(values.size)
        for (i in values.indices) squared[i] = values[i] * values[i]
        val meanSquare = boxBlur(squared, width, height, radius)
        for (i in acc.indices) {
            val v = meanSquare[i] - acc[i] * acc[i]
            acc[i] = if (v.isNaN() || v <= 0f) 0f else v
        }
        return acc
    }

    /**
     * 逐帧清晰度图。
     *
     * @param radius 统计邻域半径（像素），内部 clamp 到 [1, 32]。
     */
    fun computeClarity(
        luma: FloatArray,
        width: Int,
        height: Int,
        metric: ClarityMetric,
        radius: Int,
    ): FloatArray {
        if (width <= 0 || height <= 0 || luma.isEmpty()) return FloatArray(0)
        val r = radius.coerceIn(FocusStackConfig.CLARITY_RADIUS_MIN, FocusStackConfig.CLARITY_RADIUS_MAX)
        return when (metric) {
            ClarityMetric.LAPLACIAN_VARIANCE ->
                localVariance(laplacianResponse(luma, width, height), width, height, r)

            ClarityMetric.GRADIENT_ENERGY ->
                boxBlur(gradientMagnitude(luma, width, height), width, height, r)
        }
    }

    // ------------------------------------------------------------------ 归一化

    /**
     * 稳健归一化：用 [percentile] 分位（默认 99%）作为缩放基准，避免单个过亮像素把整图压暗。
     *
     * 实现用 256 桶直方图（O(n)，无需排序）。全零 / 非法输入返回全零。
     */
    fun normalize(map: FloatArray, percentile: Float = 0.99f): FloatArray {
        if (map.isEmpty()) return FloatArray(0)
        val hist = IntArray(HIST_BINS)
        var maxValue = 0f
        for (v in map) {
            if (v.isNaN() || v <= 0f) continue
            if (v > maxValue) maxValue = v
        }
        if (maxValue <= 0f) return FloatArray(map.size)
        for (v in map) {
            if (v.isNaN() || v <= 0f) continue
            val bin = ((v / maxValue) * (HIST_BINS - 1)).toInt().coerceIn(0, HIST_BINS - 1)
            hist[bin]++
        }
        val target = (map.size * percentile.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)
        var cumulative = 0
        var scale = maxValue
        for (bin in hist.indices) {
            cumulative += hist[bin]
            if (cumulative >= target) {
                scale = maxValue * (bin + 1).toFloat() / HIST_BINS.toFloat()
                break
            }
        }
        if (scale <= 0f || !scale.isFinite()) scale = maxValue
        val out = FloatArray(map.size)
        for (i in map.indices) {
            val v = map[i]
            out[i] = if (v.isNaN() || v <= 0f) 0f else (v / scale).coerceIn(0f, 1f)
        }
        return out
    }

    // ------------------------------------------------------------------ 软掩模

    /**
     * 由逐帧清晰度生成**软掩模**（逐像素加权，Σ mask ≈ 1）。
     *
     * 步骤：
     * 1. 逐帧稳健归一化（避免"整体更亮的纹理"抢占权重）；
     * 2. 权重 `w = c^exponent`（指数越大越接近「赢家通吃」）；
     * 3. 跨帧归一化 `mask_k = w_k / (Σ w + ε)`（Σ 为 0 的像素退化为均匀权重）；
     * 4. 每帧掩模高斯平滑（消除像素级抖动与融合接缝）；
     * 5. 再次跨帧归一化；
     * 6. [consistency] 轮邻域一致性滤波 + 再归一化（抑制孤立噪点抢焦点）。
     *
     * @return 与 [clarities] 等长的掩模列表，每张长度为 width*height。
     */
    fun softMask(
        clarities: List<FloatArray>,
        width: Int,
        height: Int,
        exponent: Float,
        smoothRadius: Int,
        consistency: Int,
    ): List<FloatArray> {
        val n = clarities.size
        if (n == 0) return emptyList()
        val size = max(0, width * height)
        if (size == 0) return List(n) { FloatArray(0) }

        val p = if (exponent.isFinite()) {
            exponent.coerceIn(FocusStackConfig.CLARITY_EXPONENT_MIN, FocusStackConfig.CLARITY_EXPONENT_MAX)
        } else {
            FocusStackConfig.DEFAULT_CLARITY_EXPONENT
        }

        val masks = ArrayList<FloatArray>(n)
        for (k in 0 until n) {
            val normalized = normalize(clarities[k])
            val out = FloatArray(size)
            val limit = minOf(size, normalized.size)
            for (i in 0 until limit) {
                val v = normalized[i]
                out[i] = maskPow(v, p)
            }
            masks += out
        }

        normalizeAcrossFrames(masks, size)

        val smooth = smoothRadius.coerceIn(
            FocusStackConfig.MASK_SMOOTH_RADIUS_MIN,
            FocusStackConfig.MASK_SMOOTH_RADIUS_MAX,
        )
        if (smooth > 0) {
            for (k in 0 until n) {
                masks[k] = gaussianBlur(masks[k], width, height, smooth)
            }
            normalizeAcrossFrames(masks, size)
        }

        val rounds = consistency.coerceIn(
            FocusStackConfig.MASK_CONSISTENCY_MIN,
            FocusStackConfig.MASK_CONSISTENCY_MAX,
        )
        if (rounds > 0) {
            repeat(rounds) {
                applyConsistencyRound(masks, width, height)
                normalizeAcrossFrames(masks, size)
            }
        }
        return masks
    }

    /** 跨帧归一化：逐像素 `mask_k = w_k / Σw`；Σw 为 0 时取均匀权重 1/n。 */
    fun normalizeAcrossFrames(masks: List<FloatArray>, size: Int) {
        val n = masks.size
        if (n == 0 || size <= 0) return
        val uniform = 1f / n.toFloat()
        for (i in 0 until size) {
            var sum = 0f
            for (k in 0 until n) {
                val v = masks[k][i]
                if (!v.isNaN() && v > 0f) sum += v
            }
            if (sum <= MASK_EPS) {
                for (k in 0 until n) masks[k][i] = uniform
            } else {
                for (k in 0 until n) {
                    val v = masks[k][i]
                    masks[k][i] = if (!v.isNaN() && v > 0f) v / sum else 0f
                }
            }
        }
    }

    /**
     * 一致性滤波一轮：某像素权重若显著高于其 4 邻域（> 邻域最大值 × [CONSISTENCY_RATIO_THRESHOLD]），
     * 则压回邻域最大值 —— 孤立单像素「最清晰」通常是噪声而非真实焦点层。
     */
    private fun applyConsistencyRound(masks: List<FloatArray>, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        for (mask in masks) {
            val src = mask.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val centre = src[i]
                    var neighbourMax = 0f
                    if (x > 0) neighbourMax = max(neighbourMax, src[i - 1])
                    if (x < width - 1) neighbourMax = max(neighbourMax, src[i + 1])
                    if (y > 0) neighbourMax = max(neighbourMax, src[i - width])
                    if (y < height - 1) neighbourMax = max(neighbourMax, src[i + width])
                    mask[i] = if (neighbourMax > 0f &&
                        centre > neighbourMax * CONSISTENCY_RATIO_THRESHOLD
                    ) {
                        neighbourMax
                    } else {
                        centre
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 缩放

    /** 盒式降采样 ARGB（用于把全分辨率帧降到工作分辨率算清晰度）。 */
    fun downsampleArgb(src: IntArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): IntArray {
        val dw = dstWidth.coerceAtLeast(1)
        val dh = dstHeight.coerceAtLeast(1)
        val out = IntArray(dw * dh)
        if (width <= 0 || height <= 0 || src.isEmpty()) return out
        for (y in 0 until dh) {
            val sy0 = (y.toLong() * height / dh).toInt().coerceIn(0, height - 1)
            val sy1 = (((y + 1).toLong() * height / dh).toInt() - 1).coerceIn(sy0, height - 1)
            for (x in 0 until dw) {
                val sx0 = (x.toLong() * width / dw).toInt().coerceIn(0, width - 1)
                val sx1 = (((x + 1).toLong() * width / dw).toInt() - 1).coerceIn(sx0, width - 1)
                var r = 0L; var g = 0L; var b = 0L; var a = 0L; var count = 0
                for (sy in sy0..sy1) {
                    val row = sy * width
                    for (sx in sx0..sx1) {
                        val c = src[row + sx]
                        a += (c ushr 24) and 0xFF
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
                val d = count.coerceAtLeast(1)
                out[y * dw + x] = ((a / d).toInt() shl 24) or
                    ((r / d).toInt() shl 16) or ((g / d).toInt() shl 8) or (b / d).toInt()
            }
        }
        return out
    }

    /** 双线性上采样浮点图（把工作分辨率掩模放大到全分辨率；掩模平滑，放大质量足够）。 */
    fun upsampleFloat(src: FloatArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): FloatArray {
        val dw = dstWidth.coerceAtLeast(1)
        val dh = dstHeight.coerceAtLeast(1)
        val out = FloatArray(dw * dh)
        if (width <= 0 || height <= 0 || src.isEmpty()) return out
        if (width == dw && height == dh) {
            src.copyInto(out, 0, 0, minOf(src.size, out.size))
            return out
        }
        val xRatio = if (dw > 1) (width - 1).toFloat() / (dw - 1).toFloat() else 0f
        val yRatio = if (dh > 1) (height - 1).toFloat() / (dh - 1).toFloat() else 0f
        for (y in 0 until dh) {
            val fy = y * yRatio
            val y0 = fy.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = x * xRatio
                val x0 = fx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceIn(0, width - 1)
                val tx = fx - x0
                val v00 = src[y0 * width + x0]; val v01 = src[y0 * width + x1]
                val v10 = src[y1 * width + x0]; val v11 = src[y1 * width + x1]
                val top = v00 + (v01 - v00) * tx
                val bottom = v10 + (v11 - v10) * tx
                out[y * dw + x] = (top + (bottom - top) * ty).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /** 均值（用于选取「基准帧」：清晰度均值最高的帧最可靠）。 */
    fun mean(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        var count = 0
        for (v in values) {
            if (!v.isNaN()) { sum += v; count++ }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /** 索引定位（清晰度均值最大者）；空输入返回 0。 */
    fun argMaxMean(maps: List<FloatArray>): Int {
        var best = 0
        var bestMean = Float.NEGATIVE_INFINITY
        for (i in maps.indices) {
            val m = mean(maps[i])
            if (m > bestMean) { bestMean = m; best = i }
        }
        return best
    }

    private const val HIST_BINS = 256

    /**
     * 幂运算（[exponent] clamp 到 [1,16]）。
     *
     * 用标准 `pow` 而非自研近似：C++ 侧同为 `std::pow`，保证 native 与纯 Kotlin 两条路径
     * 的权重**数值一致**（PATTERN_SPEC §10 要求两条路径行为对齐）。
     */
    private fun maskPow(base: Float, exponent: Float): Float {
        val e = exponent.coerceIn(FocusStackConfig.CLARITY_EXPONENT_MIN, FocusStackConfig.CLARITY_EXPONENT_MAX)
        return if (base <= 0f) 0f else base.pow(e)
    }

    /**
     * 一致性滤波的**单帧原始结果**（不做跨帧归一化）—— 供单测/诊断直接观察抑制效果。
     *
     * 注意：真实流水线里一致性滤波是**跨帧归一化之间**的一步（见 [softMask]）；
     * 单帧独立归一化会退化为「全 1」，因此这里刻意返回未归一化的原始值。
     */
    fun consistencyRefineSingle(mask: FloatArray, width: Int, height: Int, rounds: Int): FloatArray {
        val list = arrayListOf(mask.copyOf())
        val r = rounds.coerceIn(FocusStackConfig.MASK_CONSISTENCY_MIN, FocusStackConfig.MASK_CONSISTENCY_MAX)
        repeat(r) { applyConsistencyRound(list, width, height) }
        return list[0]
    }

    /** 便于单测/诊断：绝对值版拉普拉斯（用于观察响应强度）。 */
    fun laplacianMagnitude(luma: FloatArray, width: Int, height: Int): FloatArray =
        laplacianResponse(luma, width, height).also { arr ->
            for (i in arr.indices) arr[i] = abs(arr[i])
        }
}
