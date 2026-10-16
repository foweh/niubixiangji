package com.hinnka.mycamera.multiframesr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 块匹配对齐（NCC 粗搜 + 抛物线/梯度法亚像素精化），纯 Kotlin，无 FFT。
 *
 * 采用「粗到细」两段式，兼顾速度与 0.1px 精度：
 *  1. **整像素粗搜**：把两帧降采样后做零均值归一化互相关（NCC）全搜索，得到一个整数平移；
 *     降采样把搜索空间压到几十个候选位置，避免高分辨率全搜索的组合爆炸。
 *  2. **亚像素精化**：在原始（工作分辨率）平面上用梯度法（Lucas-Kanade / Gauss-Newton）
 *     迭代求解平移增量，逐步逼近真实位移。收敛后精度通常 < 0.05px，优于 0.1px 目标。
 *
 * 平移约定与 [MfsrShift] 一致：`tgt(x,y) = ref(x - dx, y - dy)`。
 */
internal object MfsrBlockMatchAligner {

    /** 亚像素精化时每轮允许的最大位移增量（防误设导致的发散）。 */
    private const val MAX_STEP_PER_ITERATION = 4f

    /** 精化采样网格的目标边长（点数），控制单轮成本。 */
    private const val REFINE_GRID = 64

    /** 粗搜降采样后的目标短边像素数。 */
    private const val COARSE_SHORT_EDGE = 96

    /** 粗搜 NCC 的采样点数目标。 */
    private const val COARSE_SAMPLES_PER_AXIS = 24

    /** 粗搜允许的最大搜索半径（降采样网格上的整数像素）。 */
    private const val COARSE_MAX_RADIUS = 64

    /**
     * 完整估计（粗搜 + 精化）。
     *
     * @param refineIterations 精化迭代次数（来自配置，已 clamp）。
     */
    fun estimate(
        ref: MfsrPlane,
        tgt: MfsrPlane,
        maxShiftPx: Int,
        refineIterations: Int,
    ): MfsrShift {
        if (ref.width != tgt.width || ref.height != tgt.height) return MfsrShift.UNDEFINED
        if (MfsrStat.meanAbsDeviation(ref) < 1e-3f) return MfsrShift.UNDEFINED
        val coarse = coarseIntegerShift(ref, tgt, maxShiftPx)
        val refined = refineFrom(ref, tgt, coarse, refineIterations, maxShiftPx.toFloat())
        return refined
    }

    /** 整像素粗搜：返回已换算回原始像素坐标的整数平移。 */
    private fun coarseIntegerShift(ref: MfsrPlane, tgt: MfsrPlane, maxShiftPx: Int): MfsrShift {
        val shortEdge = min(ref.width, ref.height)
        val step = max(1, shortEdge / COARSE_SHORT_EDGE)
        val dw = max(4, ref.width / step)
        val dh = max(4, ref.height / step)
        val a = ref.resampleTo(dw, dh)
        val b = tgt.resampleTo(dw, dh)

        val radiusWanted = min(COARSE_MAX_RADIUS, ceil(maxShiftPx.toFloat() / step).toInt() + 1)
        // 搜索窗必须放得进图内：小图自动收窄半径（亚像素精化仍能把位移补回来）。
        val radius = min(radiusWanted, (min(dw, dh) - 4) / 2)
        if (radius < 1) return MfsrShift.ZERO
        val xs = sampleAxis(radius, dw - radius)
        val ys = sampleAxis(radius, dh - radius)
        if (xs.isEmpty() || ys.isEmpty()) return MfsrShift.ZERO

        var bestDx = 0
        var bestDy = 0
        var bestNcc = -2.0
        for (dyd in -radius..radius) {
            for (dxd in -radius..radius) {
                var sa = 0.0
                var sb = 0.0
                var saa = 0.0
                var sbb = 0.0
                var sab = 0.0
                var n = 0
                for (iy in ys) {
                    for (ix in xs) {
                        val av = a.bilinear((ix - dxd).toFloat(), (iy - dyd).toFloat())
                        if (!av.isFinite()) continue
                        val bv = b.at(ix, iy)
                        sa += av
                        sb += bv
                        saa += av * av
                        sbb += bv * bv
                        sab += av * bv
                        n++
                    }
                }
                if (n < 8) continue
                val fn = n.toDouble()
                val ma = sa / fn
                val mb = sb / fn
                val cov = sab - fn * ma * mb
                val va = saa - fn * ma * ma
                val vb = sbb - fn * mb * mb
                val denom = sqrt(max(0.0, va * vb))
                val ncc = if (denom < 1e-9) 0.0 else cov / denom
                if (ncc > bestNcc) {
                    bestNcc = ncc
                    bestDx = dxd
                    bestDy = dyd
                }
            }
        }
        val conf = bestNcc.coerceIn(0.0, 1.0).toFloat()
        return MfsrShift((bestDx * step).toFloat(), (bestDy * step).toFloat(), conf)
    }

    private fun sampleAxis(from: Int, to: Int): List<Int> {
        if (to <= from) return emptyList()
        val span = to - from + 1
        val stride = max(1, span / COARSE_SAMPLES_PER_AXIS)
        val out = ArrayList<Int>(span / stride + 1)
        var i = from
        while (i <= to) {
            out += i
            i += stride
        }
        return out
    }

    /**
     * 从 [initial] 出发做梯度法（Gauss-Newton）亚像素精化。
     *
     * 目标函数：Σ [ref(x - d) - tgt(x)]²，法方程 A·Δd = b，
     * A = Σ ∇ref(u)∇ref(u)ᵀ，b = Σ ∇ref(u)·r，u = x - d，r = ref(u) - tgt(x)。
     *
     * 返回过程中残差最小的一次估计（防止个别迭代发散把结果带偏）。
     */
    fun refineFrom(
        ref: MfsrPlane,
        tgt: MfsrPlane,
        initial: MfsrShift,
        iterations: Int,
        maxShift: Float,
    ): MfsrShift {
        if (ref.width != tgt.width || ref.height != tgt.height) return initial
        var dx = initial.dx
        var dy = initial.dy
        if (!dx.isFinite() || !dy.isFinite()) return MfsrShift.UNDEFINED

        val w = ref.width
        val h = ref.height
        val step = max(1, min(w, h) / REFINE_GRID)
        val devRef = MfsrStat.meanAbsDeviation(ref).coerceAtLeast(1e-3f)

        var bestDx = dx
        var bestDy = dy
        var bestResidual = Float.MAX_VALUE
        val iters = iterations.coerceIn(1, MfsrConfig.SUBPIXEL_REFINE_MAX)

        for (iter in 0 until iters) {
            var axx = 0.0
            var axy = 0.0
            var ayy = 0.0
            var bx = 0.0
            var by = 0.0
            var resSum = 0.0
            var n = 0
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    val ux = x - dx
                    val uy = y - dy
                    val rv = ref.bilinear(ux, uy)
                    if (rv.isFinite()) {
                        val l = ref.bilinear(ux - 1f, uy)
                        val r = ref.bilinear(ux + 1f, uy)
                        val d = ref.bilinear(ux, uy - 1f)
                        val u = ref.bilinear(ux, uy + 1f)
                        if (l.isFinite() && r.isFinite() && d.isFinite() && u.isFinite()) {
                            val gx = (r - l) * 0.5f
                            val gy = (u - d) * 0.5f
                            val resid = rv - tgt.at(x, y)
                            axx += (gx * gx).toDouble()
                            axy += (gx * gy).toDouble()
                            ayy += (gy * gy).toDouble()
                            bx += (gx * resid).toDouble()
                            by += (gy * resid).toDouble()
                            resSum += abs(resid).toDouble()
                            n++
                        }
                    }
                    x += step
                }
                y += step
            }
            if (n < 8) break

            val det = axx * ayy - axy * axy
            if (!det.isFinite() || abs(det) < 1e-9) break
            var ddx = ((bx * ayy - by * axy) / det).toFloat()
            var ddy = ((axx * by - axy * bx) / det).toFloat()
            if (!ddx.isFinite() || !ddy.isFinite()) break
            ddx = ddx.coerceIn(-MAX_STEP_PER_ITERATION, MAX_STEP_PER_ITERATION)
            ddy = ddy.coerceIn(-MAX_STEP_PER_ITERATION, MAX_STEP_PER_ITERATION)
            dx += ddx
            dy += ddy

            val residual = (resSum / n).toFloat()
            if (residual < bestResidual) {
                bestResidual = residual
                bestDx = dx
                bestDy = dy
            }
            if (abs(ddx) + abs(ddy) < 1e-4f) break
        }

        if (bestResidual == Float.MAX_VALUE) {
            // 一次都没成功累积（纹理过弱）→ 保留初始量测的置信度。
            return initial.clampedTo(maxShift)
        }
        // 残差相对纹理幅度越小 → 越可信。
        val conf = (1f - bestResidual / devRef).coerceIn(0f, 1f) * initial.confidence.coerceIn(0f, 1f)
        return MfsrShift(bestDx, bestDy, conf).clampedTo(maxShift)
    }
}

/**
 * 亚像素对齐门面：按 [MfsrConfig.alignMethod] 选择实现，并统一做退化处理与 clamp。
 *
 * 精度契约：[TARGET_ACCURACY_PX] = 0.1px（纹理充足时 BLOCK_MATCH / PHASE_CORRELATION 都能达到）。
 * 退化（无纹理、尺寸不一致、非有限值）→ 返回 [MfsrShift.UNDEFINED]，由上层按"不采纳对齐"处理，
 * **不会抛异常**。
 */
object MfsrSubpixelAligner {

    /** 目标亚像素精度（像素）。 */
    const val TARGET_ACCURACY_PX = 0.1f

    /** 相位相关内部工作边长（唯一定义处，便于测试与调参）。 */
    const val PHASE_ALIGN_MAX_SIDE = MfsrPhaseCorrelation.DEFAULT_ALIGN_MAX_SIDE

    fun estimate(config: MfsrConfig, ref: MfsrPlane, tgt: MfsrPlane): MfsrShift {
        if (!config.alignmentActive) return MfsrShift.ZERO
        if (ref.width != tgt.width || ref.height != tgt.height) return MfsrShift.UNDEFINED
        val maxShift = config.maxAlignShiftPx.toFloat()

        val raw = when (config.alignMethod) {
            MfsrAlignMethod.OFF -> MfsrShift.ZERO
            MfsrAlignMethod.PHASE_CORRELATION -> {
                val pc = MfsrPhaseCorrelation.estimate(ref, tgt, config.maxAlignShiftPx, PHASE_ALIGN_MAX_SIDE)
                if (!pc.isFiniteShift) {
                    pc
                } else {
                    // FFT 峰值只到 0.5px 量级，再用梯度法精化到 0.1px 以内。
                    MfsrBlockMatchAligner.refineFrom(
                        ref = ref,
                        tgt = tgt,
                        initial = pc,
                        iterations = config.subpixelRefineIterations,
                        maxShift = maxShift,
                    )
                }
            }
            MfsrAlignMethod.BLOCK_MATCH -> MfsrBlockMatchAligner.estimate(
                ref = ref,
                tgt = tgt,
                maxShiftPx = config.maxAlignShiftPx,
                refineIterations = config.subpixelRefineIterations,
            )
        }

        if (!raw.isFiniteShift) return MfsrShift.UNDEFINED
        val clamped = raw.clampedTo(maxShift)
        return if (clamped.confidence.isFinite()) clamped else clamped.copy(confidence = 0f)
    }
}
