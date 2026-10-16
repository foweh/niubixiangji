package com.hinnka.mycamera.funshot

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** 单应估计结果（失败时 [homography] 为 null，[message] 说明原因，不抛异常）。 */
data class FunHomographyResult(
    val homography: FunMatrix3?,
    val inliers: IntArray,
    val inlierCount: Int,
    val inlierRatio: Double,
    val success: Boolean,
    val message: String,
) {
    fun inlierMask(total: Int): BooleanArray {
        val mask = BooleanArray(total)
        for (i in inliers) {
            if (i in 0 until total) mask[i] = true
        }
        return mask
    }
}

/**
 * 归一化 DLT + RANSAC 单应估计（纯自研 Kotlin）。
 *
 * 管线参考 OpenCV `Stitcher` / `findHomography` 的成熟做法
 * （opencv/opencv, Apache-2.0, star≈91k）：
 *   1) Hartley 归一化（质心平移 + 均方差缩放）改善条件数；
 *   2) 4 点最小解（把 h33 固定为 1 的 8×8 线性方程）作为 RANSAC 假设；
 *   3) 重投影误差内点统计 + 自适应迭代次数早停；
 *   4) 用全部内点做 9×9 DLT（Jacobi 对称特征分解取最小特征向量）重拟合。
 *
 * 退化保护：点数不足、共线/重合点、奇异线性系统、非有限值、行列式过小
 * 一律判定为失败（返回 [FunHomographyResult.success] = false），**不抛异常**。
 */
object FunRansacHomography {

    private const val MIN_POINTS = 4
    private const val COLLINEAR_AREA_EPS = 1e-2
    private const val MIN_PAIR_DISTANCE = 1e-3
    private const val PIVOT_EPS = 1e-10
    private const val CHECKPOINT_INTERVAL = 128

    fun estimate(
        src: List<FunPointF>,
        dst: List<FunPointF>,
        iterations: Int,
        thresholdPx: Double,
        confidence: Double = 0.995,
        minInliers: Int = 4,
        minInlierRatio: Double = 0.0,
        seed: Long = 20261016L,
        checkpoint: FunshotCheckpoint? = null,
    ): FunHomographyResult {
        val n = minOf(src.size, dst.size)
        if (n < MIN_POINTS) {
            return fail(n, "need >= $MIN_POINTS correspondences, got $n")
        }
        val threshold = if (thresholdPx.isFinite() && thresholdPx > 0) thresholdPx else 3.0
        val maxIter = iterations.coerceIn(1, 100_000)
        val rnd = FunLcg(seed)

        var bestInliers: IntArray = IntArray(0)
        var bestHomography: FunMatrix3? = null
        var iterationsDone = 0
        val required = { ratio: Double ->
            if (ratio <= 0.0) Double.MAX_VALUE
            else {
                val inlierProb = ratio.coerceAtMost(0.999)
                val denom = ln(1.0 - inlierProb * inlierProb * inlierProb * inlierProb)
                if (denom >= -1e-12) Double.MAX_VALUE
                else ln(1.0 - confidence.coerceIn(0.5, 0.9999)) / denom
            }
        }

        for (iter in 0 until maxIter) {
            iterationsDone = iter + 1
            if (iter % CHECKPOINT_INTERVAL == 0) checkpoint?.checkpoint()

            val picked = sampleFour(n, rnd) ?: continue
            val sampleSrc = picked.map { src[it] }
            val sampleDst = picked.map { dst[it] }
            if (isDegenerate(sampleSrc) || isDegenerate(sampleDst)) continue

            val hypothesis = solveFourPoint(sampleSrc, sampleDst) ?: continue
            val inliers = collectInliers(hypothesis, src, dst, n, threshold)
            if (inliers.size > bestInliers.size) {
                bestInliers = inliers
                bestHomography = hypothesis
                val ratio = inliers.size.toDouble() / n
                if (iterationsDone >= required(ratio).toInt()) break
            }
        }

        if (bestHomography == null || bestInliers.size < MIN_POINTS) {
            return fail(iterationsDone, "no valid hypothesis (degenerate or unmatched)")
        }

        val refitSrc = bestInliers.map { src[it] }
        val refitDst = bestInliers.map { dst[it] }
        val refined = if (bestInliers.size >= 5) solveDlt(refitSrc, refitDst) else bestHomography
        val final = if (refined != null && refined.isFinite()) refined else bestHomography

        val finalInliers = collectInliers(final, src, dst, n, threshold)
        val ratio = finalInliers.size.toDouble() / n
        if (finalInliers.size < minInliers || ratio < minInlierRatio) {
            return FunHomographyResult(
                homography = null,
                inliers = finalInliers,
                inlierCount = finalInliers.size,
                inlierRatio = ratio,
                success = false,
                message = "inliers ${finalInliers.size}/$n below requirement " +
                    "(min=$minInliers, ratio=$minInlierRatio)",
            )
        }
        return FunHomographyResult(
            homography = final,
            inliers = finalInliers,
            inlierCount = finalInliers.size,
            inlierRatio = ratio,
            success = true,
            message = "ok: inliers=${finalInliers.size}/$n ratio=${"%.3f".format(ratio)} iters=$iterationsDone",
        )
    }

    private fun fail(attempts: Int, reason: String) = FunHomographyResult(
        homography = null,
        inliers = IntArray(0),
        inlierCount = 0,
        inlierRatio = 0.0,
        success = false,
        message = "failed after $attempts samples: $reason",
    )

    /** 无放回抽样 4 个不同索引。 */
    private fun sampleFour(n: Int, rnd: FunLcg): IntArray? {
        if (n < MIN_POINTS) return null
        val picked = IntArray(MIN_POINTS)
        var count = 0
        var guard = 0
        while (count < MIN_POINTS && guard < 64) {
            val candidate = (rnd.nextPositive() % n).toInt()
            var duplicate = false
            for (i in 0 until count) {
                if (picked[i] == candidate) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) picked[count++] = candidate
            guard++
        }
        return if (count == MIN_POINTS) picked else null
    }

    private fun collectInliers(
        h: FunMatrix3,
        src: List<FunPointF>,
        dst: List<FunPointF>,
        n: Int,
        thresholdPx: Double,
    ): IntArray {
        val list = ArrayList<Int>(n)
        for (i in 0 until n) {
            if (transferError(h, src[i], dst[i]) <= thresholdPx) list += i
        }
        return list.toIntArray()
    }

    /** 重投影误差（src → dst 方向）。 */
    internal fun transferError(h: FunMatrix3, p: FunPointF, q: FunPointF): Double {
        val projected = h.transform(p) ?: return Double.MAX_VALUE
        val dx = projected.x - q.x
        val dy = projected.y - q.y
        val err = sqrt(dx * dx + dy * dy)
        return if (err.isFinite()) err else Double.MAX_VALUE
    }

    /** 退化判定：存在重合点，或所有点近似共线（最大三角形面积过小）。 */
    internal fun isDegenerate(points: List<FunPointF>): Boolean {
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                if ((points[i] - points[j]).norm() < MIN_PAIR_DISTANCE) return true
            }
        }
        var maxArea = 0.0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                for (k in j + 1 until points.size) {
                    val area = abs(
                        (points[j].x - points[i].x) * (points[k].y - points[i].y) -
                            (points[k].x - points[i].x) * (points[j].y - points[i].y),
                    ) * 0.5
                    if (area > maxArea) maxArea = area
                }
            }
        }
        return maxArea < COLLINEAR_AREA_EPS
    }

    /** Hartley 归一化：质心移到原点、平均距离缩放到 √2。 */
    internal fun normalize(points: List<FunPointF>): Pair<List<FunPointF>, FunMatrix3> {
        var cx = 0.0
        var cy = 0.0
        for (p in points) {
            cx += p.x
            cy += p.y
        }
        cx /= points.size
        cy /= points.size
        var meanDist = 0.0
        for (p in points) {
            meanDist += sqrt((p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy))
        }
        meanDist /= points.size
        val scale = if (meanDist > 1e-9) sqrt(2.0) / meanDist else 1.0
        val t = FunMatrix3(
            doubleArrayOf(
                scale, 0.0, -scale * cx,
                0.0, scale, -scale * cy,
                0.0, 0.0, 1.0,
            ),
        )
        val normalized = points.map { FunPointF((it.x - cx) * scale, (it.y - cy) * scale) }
        return normalized to t
    }

    /** 4 点最小解：固定 h33=1，解 8×8 线性系统（归一化后求解再反归一化）。 */
    internal fun solveFourPoint(src: List<FunPointF>, dst: List<FunPointF>): FunMatrix3? {
        if (src.size < MIN_POINTS || dst.size < MIN_POINTS) return null
        val (ns, tSrc) = normalize(src)
        val (nd, tDst) = normalize(dst)
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until MIN_POINTS) {
            val x = ns[i].x
            val y = ns[i].y
            val u = nd[i].x
            val v = nd[i].y
            a[i * 2][0] = x; a[i * 2][1] = y; a[i * 2][2] = 1.0
            a[i * 2][6] = -u * x; a[i * 2][7] = -u * y
            b[i * 2] = u
            a[i * 2 + 1][3] = x; a[i * 2 + 1][4] = y; a[i * 2 + 1][5] = 1.0
            a[i * 2 + 1][6] = -v * x; a[i * 2 + 1][7] = -v * y
            b[i * 2 + 1] = v
        }
        val solved = solveLinear(a, b) ?: return null
        val hn = FunMatrix3(
            doubleArrayOf(
                solved[0], solved[1], solved[2],
                solved[3], solved[4], solved[5],
                solved[6], solved[7], 1.0,
            ),
        )
        val tDstInv = tDst.inverse() ?: return null
        val h = tDstInv * hn * tSrc
        return if (h.isFinite()) h else null
    }

    /** 高斯消元（部分主元），奇异/非有限返回 null。 */
    internal fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val rhs = b.copyOf()
        for (col in 0 until n) {
            var pivotRow = col
            var pivotValue = abs(m[col][col])
            for (row in col + 1 until n) {
                val candidate = abs(m[row][col])
                if (candidate > pivotValue) {
                    pivotValue = candidate
                    pivotRow = row
                }
            }
            if (pivotValue < PIVOT_EPS) return null
            if (pivotRow != col) {
                val tmpRow = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmpRow
                val tmpB = rhs[col]; rhs[col] = rhs[pivotRow]; rhs[pivotRow] = tmpB
            }
            val pivot = m[col][col]
            for (row in col + 1 until n) {
                val factor = m[row][col] / pivot
                if (factor == 0.0) continue
                for (k in col until n) m[row][k] -= factor * m[col][k]
                rhs[row] -= factor * rhs[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = rhs[row]
            for (k in row + 1 until n) sum -= m[row][k] * x[k]
            if (abs(m[row][row]) < PIVOT_EPS) return null
            x[row] = sum / m[row][row]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    /**
     * 多点 DLT：构造 2n×9 的 A，取 AᵀA 最小特征向量作为 h。
     * 用 Jacobi 对称特征分解代替 SVD（无第三方依赖，9×9 规模足够）。
     */
    internal fun solveDlt(src: List<FunPointF>, dst: List<FunPointF>): FunMatrix3? {
        val n = minOf(src.size, dst.size)
        if (n < MIN_POINTS) return null
        val (ns, tSrc) = normalize(src)
        val (nd, tDst) = normalize(dst)

        val ata = Array(9) { DoubleArray(9) }
        for (i in 0 until n) {
            val x = ns[i].x
            val y = ns[i].y
            val u = nd[i].x
            val v = nd[i].y
            val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, -u)
            val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, -v)
            for (row in 0 until 9) {
                for (col in row until 9) {
                    val value = r1[row] * r1[col] + r2[row] * r2[col]
                    ata[row][col] += value
                    if (row != col) ata[col][row] += value
                }
            }
        }
        val smallest = jacobiSmallestEigenvector(ata) ?: return null
        val hn = FunMatrix3(smallest.copyOf())
        val tDstInv = tDst.inverse() ?: return null
        val h = tDstInv * hn * tSrc
        if (!h.isFinite() || abs(h.det()) < 1e-9) return null
        // 归一化到 h33=1（便于数值比较与调试），h33 过小时按范数缩放。
        val w = h.v[8]
        return if (abs(w) > 1e-9) {
            val s = 1.0 / w
            FunMatrix3(DoubleArray(9) { h.v[it] * s })
        } else {
            h
        }
    }

    /**
     * 对称矩阵最小特征值对应的特征向量（循环 Jacobi）。
     * @return 单位化特征向量；输入非对称/含非有限值时返回 null。
     */
    internal fun jacobiSmallestEigenvector(matrix: Array<DoubleArray>): DoubleArray? {
        val n = matrix.size
        if (n == 0 || matrix.any { it.size != n }) return null
        if (matrix.any { row -> row.any { !it.isFinite() } }) return null
        val a = Array(n) { matrix[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        for (sweep in 0 until 64) {
            var offDiagonal = 0.0
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    offDiagonal += a[p][q] * a[p][q]
                }
            }
            if (offDiagonal < 1e-24) break
            for (p in 0 until n - 1) {
                for (q in p + 1 until n) {
                    val apq = a[p][q]
                    if (abs(apq) < 1e-18) continue
                    val theta = (a[q][q] - a[p][p]) / (2.0 * apq)
                    val t = (if (theta >= 0) 1.0 else -1.0) /
                        (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c
                    for (k in 0 until n) {
                        val akp = a[k][p]
                        val akq = a[k][q]
                        a[k][p] = c * akp - s * akq
                        a[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = a[p][k]
                        val aqk = a[q][k]
                        a[p][k] = c * apk - s * aqk
                        a[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }
        var bestIndex = 0
        var bestValue = a[0][0]
        for (i in 1 until n) {
            if (a[i][i] < bestValue) {
                bestValue = a[i][i]
                bestIndex = i
            }
        }
        val eigen = DoubleArray(n) { v[it][bestIndex] }
        var norm = 0.0
        for (value in eigen) norm += value * value
        norm = sqrt(norm)
        if (norm < 1e-18 || !norm.isFinite()) return null
        for (i in eigen.indices) eigen[i] /= norm
        // 固定符号：令最大绝对分量为正，保证同一输入产生同一输出（确定性）。
        var maxAbs = 0.0
        var maxIdx = 0
        for (i in eigen.indices) {
            val absValue = abs(eigen[i])
            if (absValue > maxAbs) {
                maxAbs = absValue
                maxIdx = i
            }
        }
        if (eigen[maxIdx] < 0) {
            for (i in eigen.indices) eigen[i] = -eigen[i]
        }
        return eigen
    }

    /** 交叉验证用的双向误差（仅在测试与诊断中使用）。 */
    internal fun symmetricError(h: FunMatrix3, p: FunPointF, q: FunPointF): Double {
        val forward = transferError(h, p, q)
        val backward = h.inverse()?.let { transferError(it, q, p) } ?: Double.MAX_VALUE
        return max(forward, backward)
    }
}
