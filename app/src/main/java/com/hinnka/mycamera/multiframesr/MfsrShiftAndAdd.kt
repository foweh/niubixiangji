package com.hinnka.mycamera.multiframesr

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.max

/**
 * 移位叠加（shift-and-add）投影器：把各帧按亚像素平移投到 HR 网格上做**加权平均**。
 *
 * 内存特性：只维护 `sum` 与 `weight` 两张 HR 缓冲，**与帧数无关**（O(1)），
 * 因此 1000 帧与 8 帧的常驻内存完全相同 —— 这是"帧数完全开放"的基础。
 *
 * 坐标映射（与 [MfsrShift] 约定一致）：
 *  - HR 像素 X 的中心对应参考帧的工作分辨率坐标 `u(X) = (X + 0.5) / scale - 0.5`；
 *  - 第 i 帧满足 `tgt(x) = ref(x - dx_i)`，故取该帧的采样坐标 `x = u + dx_i`。
 */
internal class MfsrHrAccumulator(
    val hrWidth: Int,
    val hrHeight: Int,
    val scale: Float,
) {
    val sum: FloatArray = FloatArray(hrWidth * hrHeight)
    val weight: FloatArray = FloatArray(hrWidth * hrHeight)

    /** 已叠加帧数（无上限）。 */
    var addedFrames: Int = 0
        private set

    /**
     * 叠加一帧。
     * @return 覆盖率 0..1（该帧有效写入 HR 网格的像素比例，越界部分不计入）。
     */
    fun add(lr: MfsrPlane, shift: MfsrShift): Float {
        val sx = if (shift.dx.isFinite()) shift.dx else 0f
        val sy = if (shift.dy.isFinite()) shift.dy else 0f
        // nativecompute: 单帧投影叠加下沉 C++（与下面双层循环逐字等价，含 bilinear 非有限判定）
        val nativeCoverage = NativeComputeBridge.shiftAddAccumulate(
            lr = lr.data,
            sum = sum,
            weight = weight,
            lrWidth = lr.width,
            lrHeight = lr.height,
            hrWidth = hrWidth,
            hrHeight = hrHeight,
            scale = scale,
            dx = sx,
            dy = sy,
        )
        if (nativeCoverage >= 0f) {
            addedFrames++
            return nativeCoverage
        }
        var valid = 0
        var idx = 0
        for (y in 0 until hrHeight) {
            val uy = (y + 0.5f) / scale - 0.5f
            val vy = uy + sy
            for (x in 0 until hrWidth) {
                val ux = (x + 0.5f) / scale - 0.5f
                val v = lr.bilinear(ux + sx, vy)
                if (v.isFinite()) {
                    sum[idx] += v
                    weight[idx] += 1f
                    valid++
                }
                idx++
            }
        }
        addedFrames++
        return if (weight.isEmpty()) 0f else valid.toFloat() / weight.size
    }

    /**
     * 归一化为 HR 平面，并把「所有帧都没覆盖到」的空洞用邻域扩散补上
     * （手持漂移较大时 HR 边缘会出现空洞，不补会出现黑边）。
     */
    fun toPlane(): MfsrPlane {
        val out = FloatArray(weight.size)
        // nativecompute: 归一化 + 4 邻域空洞扩散（含"无覆盖时全 0"）整体下沉 C++
        if (NativeComputeBridge.resolveShiftAdd(
                sum = sum,
                weight = weight,
                out = out,
                hrWidth = hrWidth,
                hrHeight = hrHeight,
                holePasses = 8,
            )
        ) {
            return MfsrPlane(hrWidth, hrHeight, out)
        }
        val known = BooleanArray(weight.size)
        var knownSum = 0.0
        var knownCount = 0
        for (i in out.indices) {
            val w = weight[i]
            if (w > 0f) {
                val v = sum[i] / w
                out[i] = v
                known[i] = true
                knownSum += v
                knownCount++
            }
        }
        if (knownCount == 0) {
            // 极端情况：一帧都没覆盖（不该发生）→ 输出中性灰，绝不抛异常。
            out.fill(0f)
            return MfsrPlane(hrWidth, hrHeight, out)
        }
        fillHoles(out, known, (knownSum / knownCount).toFloat())
        return MfsrPlane(hrWidth, hrHeight, out)
    }

    private fun fillHoles(out: FloatArray, known: BooleanArray, fallback: Float, passes: Int = 8) {
        var holes = known.count { !it }
        if (holes == 0) return
        val w = hrWidth
        val h = hrHeight
        repeat(passes) {
            if (holes == 0) return
            val newly = ArrayList<Int>(holes)
            val values = ArrayList<Float>(holes)
            for (i in out.indices) {
                if (known[i]) continue
                val x = i % w
                val y = i / w
                var s = 0f
                var n = 0
                if (x > 0 && known[i - 1]) { s += out[i - 1]; n++ }
                if (x < w - 1 && known[i + 1]) { s += out[i + 1]; n++ }
                if (y > 0 && known[i - w]) { s += out[i - w]; n++ }
                if (y < h - 1 && known[i + w]) { s += out[i + w]; n++ }
                if (n > 0) {
                    newly += i
                    values += s / n
                }
            }
            for (k in newly.indices) {
                val i = newly[k]
                out[i] = values[k]
                known[i] = true
            }
            holes -= newly.size
        }
        if (holes > 0) {
            for (i in out.indices) if (!known[i]) out[i] = fallback
        }
    }

    companion object {
        /** HR 网格尺寸（由工作尺寸与倍率推出）。 */
        fun hrSizeOf(workSize: Int, scale: Float): Int =
            max(1, Math.round(workSize * scale.coerceIn(MfsrConfig.SCALE_MIN, MfsrConfig.SCALE_MAX)))
    }
}
