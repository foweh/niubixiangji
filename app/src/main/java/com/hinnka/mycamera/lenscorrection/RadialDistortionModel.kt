package com.hinnka.mycamera.lenscorrection

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 径向畸变模型（k1/k2/k3 多项式，Brown-Conrady 的纯径向部分）。
 *
 * 归一化约定（关键，务必与 [DistortionRemapGrid] 一致）：
 * - 坐标以**半对角线**归一化：`normHalf = 0.5·hypot(width, height)`；
 *   于是画面四角 `r² ≈ 1`，用户看到的 k1/k2 数值量级直观（±0.5 以内即可覆盖手机镜头）。
 * - 校正（undistort）方向：给定**校正后**的归一化坐标 `(u, v)`，源（畸变）坐标为
 *   `(u·s, v·s)`，其中 `s = 1 + k1·r² + k2·r⁴ + k3·r⁶`，`r² = u² + v²`。
 *   `k1 > 0` 会把采样点向外推 → 拉伸桶形畸变压扁的边缘 → **校正桶形畸变**；
 *   `k1 < 0` 则校正枕形畸变。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
data class RadialDistortionModel(
    val k1: Float = 0f,
    val k2: Float = 0f,
    /** 第三径向系数；默认 0。Camera2 `LENS_DISTORTION` 的第 3 个系数可从这里传入。 */
    val k3: Float = 0f,
) {
    init {
        require(k1.isFinite() && k2.isFinite() && k3.isFinite()) {
            "radial coefficients must be finite"
        }
    }

    val isIdentity: Boolean get() = k1 == 0f && k2 == 0f && k3 == 0f

    /** 径向缩放因子 `s(r²) = 1 + k1·r² + k2·r⁴ + k3·r⁶`；r²=0 时恒为 1。 */
    fun distortionFactor(rSquared: Float): Float {
        val r2 = if (rSquared.isFinite()) rSquared.coerceAtLeast(0f) else 0f
        val r4 = r2 * r2
        val r6 = r4 * r2
        return 1f + k1 * r2 + k2 * r4 + k3 * r6
    }

    /** `s` 对 r 的导数除以 2r（用于牛顿迭代）。 */
    fun distortionFactorDerivative(rSquared: Float): Float {
        val r2 = if (rSquared.isFinite()) rSquared.coerceAtLeast(0f) else 0f
        val r4 = r2 * r2
        // d/dr [r·s(r²)] = 1 + 3k1 r² + 5k2 r⁴ + 7k3 r⁶
        return 1f + 3f * k1 * r2 + 5f * k2 * r4 + 7f * k3 * r4 * r2
    }

    /**
     * 校正方向映射：校正后归一化坐标 `(u, v)` → 畸变源归一化坐标。
     * @return 长度 2 的数组 `[u', v']`。
     */
    fun mapToSource(u: Float, v: Float): FloatArray {
        val uu = if (u.isFinite()) u else 0f
        val vv = if (v.isFinite()) v else 0f
        val r2 = uu * uu + vv * vv
        val s = distortionFactor(r2)
        return floatArrayOf(uu * s, vv * s)
    }

    /**
     * 逆向映射：畸变坐标 → 理想（无畸变）坐标。
     *
     * 由于映射是纯径向的，先在半径上做一维牛顿迭代 `r·s(r²) = r_d`，再按比例还原方向，
     * 比直接二维牛顿稳健得多。含发散保护（导数过小/超出半径上限即停）。
     *
     * @param iterations 牛顿迭代次数（1..64）。迭代越多次越精确，典型 8 次已足够。
     * @return 长度 2 的数组 `[u, v]`（理想坐标）。
     */
    fun mapToIdeal(ud: Float, vd: Float, iterations: Int = DEFAULT_ITERATIONS): FloatArray {
        val u = if (ud.isFinite()) ud else 0f
        val v = if (vd.isFinite()) vd else 0f
        val radiusDistorted = sqrt(u * u + v * v)
        if (radiusDistorted < 1e-8f) return floatArrayOf(0f, 0f)
        val count = iterations.coerceIn(1, MAX_ITERATIONS)
        var r = radiusDistorted
        for (i in 0 until count) {
            val value = r * distortionFactor(r * r) - radiusDistorted
            val derivative = distortionFactorDerivative(r * r)
            if (!derivative.isFinite() || abs(derivative) < 1e-6f) break
            val step = value / derivative
            val next = (r - step).coerceIn(0f, MAX_RADIUS)
            if (abs(next - r) < 1e-7f) {
                r = next
                break
            }
            r = next
        }
        val scale = r / radiusDistorted
        return floatArrayOf(u * scale, v * scale)
    }

    companion object {
        const val DEFAULT_ITERATIONS = 8
        const val MAX_ITERATIONS = 64

        /** 牛顿迭代的半径上限（归一化单位；正常镜头 r ≤ 1.5）。 */
        const val MAX_RADIUS = 8f

        /** 恒等模型（不校正）。 */
        val IDENTITY = RadialDistortionModel(0f, 0f, 0f)

        /** 由 Camera2 `LENS_DISTORTION` 的 `[k1, k2, k3]` 构造（仅取径向部分）。 */
        fun fromCamera2Radial(k1: Float, k2: Float, k3: Float = 0f): RadialDistortionModel {
            val safe = listOf(k1, k2, k3).map { if (it.isFinite()) it else 0f }
            return RadialDistortionModel(safe[0], safe[1], safe[2])
        }
    }
}
