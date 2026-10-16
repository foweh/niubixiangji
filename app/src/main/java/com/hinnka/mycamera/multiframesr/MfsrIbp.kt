package com.hinnka.mycamera.multiframesr

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.max
import kotlin.math.min

/** IBP 结果。 */
internal class MfsrIbpOutcome(
    val plane: MfsrPlane,
    /** 每轮迭代后的平均绝对残差（长度 = 实际执行轮数）。 */
    val residuals: List<Float>,
    /** 因内存守卫不足而跳过 IBP（降级，不是失败）。 */
    val skippedForMemory: Boolean,
    val iterationsRun: Int,
)

/**
 * 迭代反投影（Iterative Back-Projection, Irani & Peleg 经典多帧超分）。
 *
 * 对每一轮迭代：
 *  1. **正向仿真**：把当前 HR 估计按各帧平移投影回 LR 网格，得到"该帧看上去应该是什么样"；
 *  2. **反投影**：用 (观测 − 仿真) 的残差，按双线性分摊权重（正向采样的伴随算子）投回 HR；
 *  3. 归一化反投影量后按步长 λ 更新 HR，并做 boundedness 约束（截断到有效亮度区间）。
 *
 * 关键设计：
 *  - 帧数据从 [MfsrFrameStore] 逐帧读取，**内存与帧数无关**（允许上千帧落盘后多轮复用）；
 *  - 每处理一帧调用一次 [MfsrCheckpoint]（响应暂停/取消/节流）；
 *  - 全程记录残差，保留残差最小的那一轮结果（防止个别轮次振荡带偏输出）；
 *  - 误差缓冲分配前先过内存守卫，不通过就**跳过 IBP**（返回移位叠加结果），绝不失败。
 */
internal object MfsrIterativeBackProjection {

    /** 有效亮度区间（8bit luma 域）。 */
    const val CLAMP_LO = 0f
    const val CLAMP_HI = 255f

    fun refine(
        initial: MfsrPlane,
        store: MfsrFrameStore,
        shifts: List<MfsrShift>,
        scale: Float,
        iterations: Int,
        lambda: Float,
        stride: Int,
        guard: (Long) -> Boolean,
        checkpoint: MfsrCheckpoint?,
    ): MfsrIbpOutcome {
        val iters = iterations.coerceIn(MfsrConfig.IBP_MIN, MfsrConfig.IBP_MAX)
        val frameCount = store.count
        if (frameCount <= 0) return MfsrIbpOutcome(initial, emptyList(), false, 0)

        val hrPixels = initial.pixels
        val bufferBytes = 2L * hrPixels.toLong() * 4L
        if (!guard(bufferBytes)) {
            // 内存不足 → 降级为"只用移位叠加"，IBP 跳过（不抛异常、不失败）。
            return MfsrIbpOutcome(initial, emptyList(), true, 0)
        }

        val hr = initial.data.copyOf()
        val err = FloatArray(hrPixels)
        val wsum = FloatArray(hrPixels)
        val w = initial.width
        val h = initial.height
        val step = stride.coerceIn(1, MfsrConfig.IBP_STRIDE_MAX)
        val gain = lambda.coerceIn(0.05f, 1.2f)

        var bestHr = hr.copyOf()
        var bestResidual = Float.MAX_VALUE
        val residuals = ArrayList<Float>(iters)

        for (iter in 1..iters) {
            err.fill(0f)
            wsum.fill(0f)
            var absSum = 0.0
            var sampleCount = 0

            var i = 0
            while (i < frameCount) {
                val lr = store.load(i)
                val sh = shifts.getOrNull(i) ?: MfsrShift.ZERO
                if (lr != null && sh.isFiniteShift) {
                    val dx = sh.dx
                    val dy = sh.dy
                    // nativecompute: HR→LR 正向仿真 + 残差反投影下沉 C++（与下面双层循环逐字等价）
                    val nativeStats = NativeComputeBridge.ibpAccumulateFrame(
                        hr = hr,
                        lr = lr.data,
                        err = err,
                        wsum = wsum,
                        hrWidth = w,
                        hrHeight = h,
                        lrWidth = lr.width,
                        lrHeight = lr.height,
                        dx = dx,
                        dy = dy,
                        scale = scale,
                    )
                    if (nativeStats != null) {
                        absSum += nativeStats[0]
                        sampleCount += nativeStats[1].toInt()
                        checkpoint?.checkpoint(iter, iters)
                        i += step
                        continue
                    }
                    for (y in 0 until lr.height) {
                        // LR 像素中心 → HR 连续坐标
                        val hy = (y - dy + 0.5f) * scale - 0.5f
                        if (hy < 0f || hy > h - 1f) continue
                        val y0 = hy.toInt().coerceIn(0, h - 1)
                        val y1 = min(y0 + 1, h - 1)
                        val fy = hy - y0
                        for (x in 0 until lr.width) {
                            val hx = (x - dx + 0.5f) * scale - 0.5f
                            if (hx < 0f || hx > w - 1f) continue
                            val x0 = hx.toInt().coerceIn(0, w - 1)
                            val x1 = min(x0 + 1, w - 1)
                            val fx = hx - x0

                            // 正向：HR → LR 仿真（双线性采样）
                            val v00 = hr[y0 * w + x0]
                            val v10 = hr[y0 * w + x1]
                            val v01 = hr[y1 * w + x0]
                            val v11 = hr[y1 * w + x1]
                            val top = v00 + (v10 - v00) * fx
                            val bot = v01 + (v11 - v01) * fx
                            val sim = top + (bot - top) * fy

                            val e = lr.data[y * lr.width + x] - sim
                            if (!e.isFinite()) continue
                            absSum += kotlin.math.abs(e.toDouble())
                            sampleCount++

                            // 反投影：双线性权重分摊（正向采样的伴随）
                            val w00 = (1f - fx) * (1f - fy)
                            val w10 = fx * (1f - fy)
                            val w01 = (1f - fx) * fy
                            val w11 = fx * fy
                            err[y0 * w + x0] += e * w00
                            wsum[y0 * w + x0] += w00
                            err[y0 * w + x1] += e * w10
                            wsum[y0 * w + x1] += w10
                            err[y1 * w + x0] += e * w01
                            wsum[y1 * w + x0] += w01
                            err[y1 * w + x1] += e * w11
                            wsum[y1 * w + x1] += w11
                        }
                    }
                }
                checkpoint?.checkpoint(iter, iters)
                i += step
            }

            if (sampleCount == 0) break

            // nativecompute: 更新步（wsum>0 才更新、非有限值保留原值、clamp 到 0..255）下沉 C++
            if (!NativeComputeBridge.ibpApplyStep(hr, err, wsum, hrPixels, gain)) {
                for (k in 0 until hrPixels) {
                    val ws = wsum[k]
                    if (ws > 0f) {
                        val v = hr[k] + gain * (err[k] / ws)
                        hr[k] = if (v.isFinite()) v.coerceIn(CLAMP_LO, CLAMP_HI) else hr[k]
                    }
                }
            }
            val residual = (absSum / sampleCount).toFloat()
            residuals += residual
            if (residual < bestResidual) {
                bestResidual = residual
                System.arraycopy(hr, 0, bestHr, 0, hrPixels)
            }
        }

        return MfsrIbpOutcome(MfsrPlane(w, h, bestHr), residuals, false, residuals.size)
    }
}
