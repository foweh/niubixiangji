package com.hinnka.mycamera.multiframesr

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 极简 radix-2 复数 FFT（纯 Kotlin）。
 *
 * 只为相位相关（互功率谱求平移）服务：尺寸固定为 2 的幂、原地运算、Float 存储。
 * 不引入任何第三方数学库，保证可在纯 JVM 单测中直接验证。
 */
internal object MfsrFft {

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * 一维原地 FFT（Cooley-Tukey，迭代实现）。
     * @param inverse true = 逆变换（含 1/N 归一）。
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size) { "re/im size mismatch" }
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be power of two: $n" }
        if (n == 1) return

        // 位反转置换
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until half) {
                    val idxA = i + k
                    val idxB = idxA + half
                    val vRe = re[idxB] * curRe - im[idxB] * curIm
                    val vIm = re[idxB] * curIm + im[idxB] * curRe
                    re[idxB] = re[idxA] - vRe
                    im[idxB] = im[idxA] - vIm
                    re[idxA] += vRe
                    im[idxA] += vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val inv = 1f / n
            for (i in 0 until n) {
                re[i] *= inv
                im[i] *= inv
            }
        }
    }

    /** 二维 FFT：先行后列（宽高都必须是 2 的幂）。 */
    fun transform2D(re: FloatArray, im: FloatArray, w: Int, h: Int, inverse: Boolean) {
        val rowRe = FloatArray(w)
        val rowIm = FloatArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                rowRe[x] = re[base + x]
                rowIm[x] = im[base + x]
            }
            transform(rowRe, rowIm, inverse)
            for (x in 0 until w) {
                re[base + x] = rowRe[x]
                im[base + x] = rowIm[x]
            }
        }
        val colRe = FloatArray(h)
        val colIm = FloatArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                colRe[y] = re[y * w + x]
                colIm[y] = im[y * w + x]
            }
            transform(colRe, colIm, inverse)
            for (y in 0 until h) {
                re[y * w + x] = colRe[y]
                im[y * w + x] = colIm[y]
            }
        }
    }
}

/**
 * 相位相关亚像素平移估计（经典 phase correlation）。
 *
 * 流程：Hann 窗 → 零填充到 2 的幂 → FFT → 归一化互功率谱 → 逆 FFT 求相关峰 →
 * 峰值抛物线插值得到亚像素位移 → （由 [MfsrSubpixelAligner] 决定是否再叠加梯度法精化）。
 *
 * 平移约定与 [MfsrShift] 一致：`tgt(x,y) = ref(x - dx, y - dy)`，即返回值就是所需的 (dx,dy)。
 */
internal object MfsrPhaseCorrelation {

    /** 相位相关内部的最大工作边长（控制 FFT 规模，与"帧数上限"无关）。 */
    const val DEFAULT_ALIGN_MAX_SIDE = 512

    /** 置信度软映射的半点（峰噪比）。 */
    private const val CONF_HALF_POINT = 3.5f

    fun estimate(
        ref: MfsrPlane,
        tgt: MfsrPlane,
        maxShiftPx: Int,
        alignMaxSide: Int = DEFAULT_ALIGN_MAX_SIDE,
    ): MfsrShift {
        if (ref.width != tgt.width || ref.height != tgt.height) return MfsrShift.UNDEFINED
        // 无纹理（纯色/全黑）帧相位相关无意义，直接判退化，避免返回乱跳的平移。
        val texture = MfsrStat.meanAbsDeviation(ref)
        val textureT = MfsrStat.meanAbsDeviation(tgt)
        if (texture < 1e-3f || textureT < 1e-3f) return MfsrShift.UNDEFINED

        // nativecompute: Hann 窗 + 2D FFT + 归一化互功率谱 + 抛物线亚像素整体下沉 C++；
        // 返回 null（不可用/退化/尺寸非法）时继续走下面的纯 Kotlin 实现。
        NativeComputeBridge.phaseCorrelate(
            ref = ref.data,
            tgt = tgt.data,
            width = ref.width,
            height = ref.height,
            maxShiftPx = maxShiftPx,
            alignMaxSide = alignMaxSide,
        )?.let { native ->
            if (native.size >= 3) {
                val shift = MfsrShift(native[0], native[1], native[2]).clampedTo(maxShiftPx.toFloat())
                if (shift.isFiniteShift) return shift
            }
        }
        val (a, b) = prepare(ref, tgt, alignMaxSide)
        val w = a.width
        val h = a.height
        val pw = MfsrFft.nextPow2(w)
        val ph = MfsrFft.nextPow2(h)

        val reA = FloatArray(pw * ph)
        val imA = FloatArray(pw * ph)
        val reB = FloatArray(pw * ph)
        val imB = FloatArray(pw * ph)

        val winA = hann(a)
        val winB = hann(b)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val p = y * pw + x
                reA[p] = a.data[idx] * winA[idx]
                reB[p] = b.data[idx] * winB[idx]
            }
        }

        MfsrFft.transform2D(reA, imA, pw, ph, inverse = false)
        MfsrFft.transform2D(reB, imB, pw, ph, inverse = false)

        // 互功率谱 C = F_tgt * conj(F_ref)，归一化后逆变换 → 峰值即平移量。
        val eps = 1e-6f
        for (i in 0 until pw * ph) {
            val ar = reA[i]
            val ai = imA[i]
            val br = reB[i]
            val bi = imB[i]
            val cr = br * ar + bi * ai
            val ci = bi * ar - br * ai
            val mag = sqrt(cr * cr + ci * ci)
            val d = if (mag < eps) eps else mag
            reA[i] = cr / d
            imA[i] = ci / d
        }

        MfsrFft.transform2D(reA, imA, pw, ph, inverse = true)
        val surface = reA

        // 由内部降采样网格换算回原始（工作分辨率）像素：kx/ky 是网格 → 像素的比例。
        val kx = ref.width.toFloat() / w
        val ky = ref.height.toFloat() / h

        // 找峰值：只在与 maxShift 相符的窗口内搜索。
        // （周期纹理会在别处产生同样强的峰，若全图搜索会选到错误的周期性峰。）
        val limitX = maxShiftPx.toFloat() / kx + 2f
        val limitY = maxShiftPx.toFloat() / ky + 2f
        var peakX = 0
        var peakY = 0
        var peakVal = -Float.MAX_VALUE
        for (y in 0 until ph) {
            val wy = wrapIndex(y, ph)
            if (kotlin.math.abs(wy) > limitY) continue
            val base = y * pw
            for (x in 0 until pw) {
                val wx = wrapIndex(x, pw)
                if (kotlin.math.abs(wx) > limitX) continue
                val v = surface[base + x]
                if (v > peakVal) {
                    peakVal = v
                    peakX = x
                    peakY = y
                }
            }
        }
        if (peakVal == -Float.MAX_VALUE) {
            // 窗口内无候选（理论上不会发生，(0,0) 必在窗口内）→ 退化为零平移。
            return MfsrShift.UNDEFINED
        }

        // 峰噪比 → 置信度
        var sum = 0.0
        for (v in surface) sum += v
        val meanSurf = (sum / (pw * ph)).toFloat()
        var varSum = 0.0
        for (v in surface) {
            val d = v - meanSurf
            varSum += d * d
        }
        val stdSurf = sqrt((varSum / (pw * ph)).toFloat()).coerceAtLeast(1e-6f)
        val peakToNoise = (peakVal - meanSurf) / stdSurf
        val confidence = MfsrStat.softConfidence(peakToNoise, CONF_HALF_POINT)

        // 亚像素：对峰值沿 x / y 轴做抛物线拟合（带轴解缠）。
        val subX = parabolic(surface, pw, ph, peakX, peakY, alongX = true)
        val subY = parabolic(surface, pw, ph, peakX, peakY, alongX = false)

        val wrappedX = wrapIndex(peakX, pw) + subX
        val wrappedY = wrapIndex(peakY, ph) + subY

        val shift = MfsrShift(
            dx = wrappedX * kx,
            dy = wrappedY * ky,
            confidence = confidence,
        ).clampedTo(maxShiftPx.toFloat())
        return if (shift.isFiniteShift) shift else MfsrShift.UNDEFINED
    }

    private fun prepare(ref: MfsrPlane, tgt: MfsrPlane, alignMaxSide: Int): Pair<MfsrPlane, MfsrPlane> {
        val maxSide = alignMaxSide.coerceAtLeast(64)
        val longEdge = max(ref.width, ref.height)
        if (longEdge <= maxSide) return ref to tgt
        val k = maxSide.toFloat() / longEdge
        val w = max(8, (ref.width * k).toInt())
        val h = max(8, (ref.height * k).toInt())
        return ref.resampleTo(w, h) to tgt.resampleTo(w, h)
    }

    private fun hann(plane: MfsrPlane): FloatArray {
        val w = plane.width
        val h = plane.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val wy = if (h > 1) 0.5f * (1f - cos(2.0 * PI * y / (h - 1)).toFloat()) else 1f
            for (x in 0 until w) {
                val wx = if (w > 1) 0.5f * (1f - cos(2.0 * PI * x / (w - 1)).toFloat()) else 1f
                out[y * w + x] = wx * wy
            }
        }
        return out
    }

    private fun wrapIndex(idx: Int, n: Int): Float {
        val half = n / 2
        return if (idx > half) (idx - n).toFloat() else idx.toFloat()
    }

    /** 峰值邻域抛物线插值，返回 0.5px 内的亚像素偏移。 */
    private fun parabolic(surface: FloatArray, w: Int, h: Int, px: Int, py: Int, alongX: Boolean): Float {
        val n = if (alongX) w else h
        val step = 1
        fun sample(i: Int): Float {
            val ii = ((i % n) + n) % n
            return if (alongX) surface[py * w + ii] else surface[ii * w + px]
        }
        val cur = if (alongX) px else py
        val prev = sample(cur - step)
        val c = sample(cur)
        val next = sample(cur + step)
        val denom = prev - 2f * c + next
        if (!denom.isFinite() || kotlin.math.abs(denom) < 1e-9f) return 0f
        val delta = 0.5f * (prev - next) / denom
        return if (delta.isFinite()) delta.coerceIn(-0.5f, 0.5f) else 0f
    }
}
