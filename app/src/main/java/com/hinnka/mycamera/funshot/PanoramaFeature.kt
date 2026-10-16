package com.hinnka.mycamera.funshot

import kotlin.math.max

/** 特征点（位置 + 响应值）。 */
data class FunKeyPoint(val x: Float, val y: Float, val score: Float)

/** 一次匹配对（两侧索引 + 汉明距离）。 */
data class FunMatch(val indexA: Int, val indexB: Int, val distance: Int)

/**
 * Harris 角点检测（积分图加速的窗口求和 + 3×3 局部极大 + 最小间距贪心抑制）。
 *
 * 参考 OpenCV `goodFeaturesToTrack` 的 Harris 分支思路（opencv/opencv, Apache-2.0），
 * **代码自研**，仅沿用「结构张量 → 角点响应 → 局部极大 + 间距抑制」的成熟管线。
 */
object FunHarrisCorner {

    private const val HARRIS_K = 0.04f
    private const val LOCAL_RADIUS = 1
    private const val WINDOW_RADIUS = 2

    /**
     * @param maxCorners 最多返回的角点数（>=1）
     * @param quality 相对阈值的比例（0..1 有效；越大越少）
     * @param minDistance 角点最小间距（像素）
     */
    fun detect(
        image: FunGrayImage,
        maxCorners: Int,
        quality: Float,
        minDistance: Int,
    ): List<FunKeyPoint> {
        val w = image.width
        val h = image.height
        if (w < 5 || h < 5 || maxCorners <= 0) return emptyList()

        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                gx[i] = (image.pixels[i + 1] - image.pixels[i - 1]) * 0.5f
                gy[i] = (image.pixels[i + w] - image.pixels[i - w]) * 0.5f
            }
        }

        val ixx = FloatArray(w * h)
        val iyy = FloatArray(w * h)
        val ixy = FloatArray(w * h)
        for (i in 0 until w * h) {
            ixx[i] = gx[i] * gx[i]
            iyy[i] = gy[i] * gy[i]
            ixy[i] = gx[i] * gy[i]
        }

        val sxx = boxSum(ixx, w, h, WINDOW_RADIUS)
        val syy = boxSum(iyy, w, h, WINDOW_RADIUS)
        val sxy = boxSum(ixy, w, h, WINDOW_RADIUS)

        val response = FloatArray(w * h)
        var maxResponse = 0f
        for (i in response.indices) {
            val det = sxx[i] * syy[i] - sxy[i] * sxy[i]
            val trace = sxx[i] + syy[i]
            val r = det - HARRIS_K * trace * trace
            response[i] = r
            if (r > maxResponse) maxResponse = r
        }
        if (maxResponse <= 1e-6f) return emptyList()

        val threshold = quality.coerceIn(1e-6f, 1f) * maxResponse
        val candidates = ArrayList<FunKeyPoint>()
        for (y in LOCAL_RADIUS until h - LOCAL_RADIUS) {
            for (x in LOCAL_RADIUS until w - LOCAL_RADIUS) {
                val i = y * w + x
                val r = response[i]
                if (r < threshold) continue
                if (!isLocalMax(response, w, x, y, r)) continue
                candidates += FunKeyPoint(x.toFloat(), y.toFloat(), r)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        candidates.sortByDescending { it.score }
        val minDistSq = (minDistance.coerceAtLeast(1)).toFloat().let { it * it }
        val accepted = ArrayList<FunKeyPoint>(minOf(maxCorners, candidates.size))
        for (candidate in candidates) {
            var ok = true
            for (kept in accepted) {
                val dx = kept.x - candidate.x
                val dy = kept.y - candidate.y
                if (dx * dx + dy * dy < minDistSq) {
                    ok = false
                    break
                }
            }
            if (ok) {
                accepted += candidate
                if (accepted.size >= maxCorners) break
            }
        }
        return accepted
    }

    private fun isLocalMax(response: FloatArray, w: Int, x: Int, y: Int, value: Float): Boolean {
        for (dy in -LOCAL_RADIUS..LOCAL_RADIUS) {
            for (dx in -LOCAL_RADIUS..LOCAL_RADIUS) {
                if (dx == 0 && dy == 0) continue
                val v = response[(y + dy) * w + (x + dx)]
                if (v > value || (v == value && (dy < 0 || (dy == 0 && dx < 0)))) return false
            }
        }
        return true
    }

    /** 半径 [radius] 的方形窗口求和（积分图实现，O(1)/像素）。 */
    private fun boxSum(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                ii[(y + 1) * (w + 1) + (x + 1)] = ii[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = minOf(h - 1, y + radius)
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = minOf(w - 1, x + radius)
                val sum = ii[(y1 + 1) * (w + 1) + (x1 + 1)] -
                    ii[y0 * (w + 1) + (x1 + 1)] -
                    ii[(y1 + 1) * (w + 1) + x0] +
                    ii[y0 * (w + 1) + x0]
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                out[y * w + x] = (sum / area).toFloat()
            }
        }
        return out
    }
}

/**
 * BRIEF 二值描述子（确定性格点对 + 3×3 均值预平滑）。
 *
 * 参考 OpenCV BRIEF/ORB 的思路（opencv/opencv, Apache-2.0），**代码自研**：
 * 与非旋转 BRIEF 一致，本工程只在水平拼接场景使用，不做角度补偿（已在文档说明）。
 */
object FunBriefDescriptor {

    private const val PATTERN_RADIUS = 13
    private const val PATCH_SMOOTH_RADIUS = 1

    /**
     * @param bits 描述子位数（64 的倍数，调用方已 clamp）
     * @return 每个角点一个 `LongArray(bits/64)`
     */
    fun describe(
        image: FunGrayImage,
        keyPoints: List<FunKeyPoint>,
        bits: Int,
        seed: Long,
    ): Array<LongArray> {
        val words = ((bits.coerceIn(64, 512)) / 64)
        val totalBits = words * 64
        val smoothed = boxSmooth(image, PATCH_SMOOTH_RADIUS)
        val pattern = patternOffsets(totalBits, seed)
        return Array(keyPoints.size) { index ->
            val kp = keyPoints[index]
            val descriptor = LongArray(words)
            for (b in 0 until totalBits) {
                val ax = kp.x + pattern[b * 4]
                val ay = kp.y + pattern[b * 4 + 1]
                val bx = kp.x + pattern[b * 4 + 2]
                val by = kp.y + pattern[b * 4 + 3]
                val va = smoothed.bilinear(ax.toDouble(), ay.toDouble()) ?: continue
                val vb = smoothed.bilinear(bx.toDouble(), by.toDouble()) ?: continue
                if (va < vb) descriptor[b / 64] = descriptor[b / 64] or (1L shl (b % 64))
            }
            descriptor
        }
    }

    /** 固定种子的确定性格点对（同一 [seed] + [bits] 永远产生同一 pattern）。 */
    internal fun patternOffsets(bits: Int, seed: Long): FloatArray {
        val rnd = FunLcg(seed)
        val out = FloatArray(bits * 4)
        for (b in 0 until bits) {
            out[b * 4] = rnd.nextRange(-PATTERN_RADIUS, PATTERN_RADIUS)
            out[b * 4 + 1] = rnd.nextRange(-PATTERN_RADIUS, PATTERN_RADIUS)
            out[b * 4 + 2] = rnd.nextRange(-PATTERN_RADIUS, PATTERN_RADIUS)
            out[b * 4 + 3] = rnd.nextRange(-PATTERN_RADIUS, PATTERN_RADIUS)
        }
        return out
    }

    private fun boxSmooth(image: FunGrayImage, radius: Int): FunGrayImage {
        val w = image.width
        val h = image.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (image.inBounds(x + dx, y + dy)) {
                            sum += image.pixels[(y + dy) * w + (x + dx)]
                            count++
                        }
                    }
                }
                out[y * w + x] = if (count > 0) sum / count else 0f
            }
        }
        return FunGrayImage(w, h, out)
    }
}

/** 线性同余随机数（跨平台确定性；不用 java.util.Random 以保证与 C++ 侧 pattern 可对齐复现）。 */
internal class FunLcg(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    private fun next(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        return state
    }

    /** [min, max] 闭区间浮点。 */
    fun nextRange(min: Int, max: Int): Float {
        val span = (max - min + 1)
        val r = ((next() ushr 33) % span).toInt()
        return (min + r).toFloat()
    }

    /** 非负随机数（用于索引抽样，避免负数取模）。 */
    fun nextPositive(): Long = next() ushr 1
}

/** 暴力匹配 + Lowe ratio + 距离上限（特征少时比 KD 树更快且实现简单）。 */
object FunFeatureMatcher {

    fun match(
        descA: Array<LongArray>,
        descB: Array<LongArray>,
        ratio: Float,
        maxMatches: Int,
        maxDistanceFraction: Float = 0.35f,
    ): List<FunMatch> {
        if (descA.isEmpty() || descB.isEmpty()) return emptyList()
        val words = minOf(descA[0].size, descB[0].size)
        val bits = words * 64
        val distanceCap = (bits * maxDistanceFraction.coerceIn(0.01f, 1f)).toInt()
        val matches = ArrayList<FunMatch>()
        for (i in descA.indices) {
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestIndex = -1
            for (j in descB.indices) {
                val d = hamming(descA[i], descB[j], words)
                if (d < best) {
                    second = best
                    best = d
                    bestIndex = j
                } else if (d < second) {
                    second = d
                }
            }
            if (bestIndex < 0) continue
            if (best > distanceCap) continue
            // ratio 检验：次优若明显更差，则最优更可信。
            if (second != Int.MAX_VALUE && best > ratio.coerceIn(0.1f, 1f) * second) continue
            matches += FunMatch(i, bestIndex, best)
            if (matches.size >= maxMatches.coerceAtLeast(1)) break
        }
        return matches
    }

    internal fun hamming(a: LongArray, b: LongArray, words: Int): Int {
        var count = 0
        for (i in 0 until words) {
            count += java.lang.Long.bitCount(a[i] xor b[i])
        }
        return count
    }
}
