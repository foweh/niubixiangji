package com.hinnka.mycamera.logvideo

/**
 * RGB 色域转换矩阵（D65 → D65，无白点适配，Log→Rec709 技术转换用）。
 *
 * 计算方法（与工程 `CameraRawCalibrationMatrix.rgbToXyzFromPrimaries` 同一套标准做法，
 * 因该方法为 private 不可直接调用，故在本模块内独立实现）：
 *  1. 三基色色度 (x,y) → XYZ 列向量（Y 归一化到 1）；
 *  2. 求基色矩阵逆 × D65 白点 → 三通道缩放系数；
 *  3. 组出 M_src→XYZ；再乘 M_XYZ→dst（= M_dst→XYZ 的逆）。
 *
 * 矩阵为**行主序 3×3**（`[m00,m01,m02, m10,...]`），与 GLSL `mat3` 列主序的转换在
 * 上传处完成（`LogVideoBakeJob` 只用于 CPU 烘焙，GPU 侧无需上传）。
 *
 * 纯 Kotlin，可 JVM 单测（identity、白点保持、往返误差）。
 */
object LogGamutMatrix {

    /** 源色域 → 目标色域，行主序 3×3。 */
    fun conversion(from: LogGamut, to: LogGamut): FloatArray {
        if (from == to) return identity()
        val fromToXyz = rgbToXyz(from)
        val toToXyz = rgbToXyz(to)
        val toInverse = invert3x3(toToXyz) ?: return identity()
        return multiply3x3(toInverse, fromToXyz)
    }

    /** 行主序 3×3 × 列向量 RGB。 */
    fun apply(matrix: FloatArray, r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        matrix[0] * r + matrix[1] * g + matrix[2] * b,
        matrix[3] * r + matrix[4] * g + matrix[5] * b,
        matrix[6] * r + matrix[7] * g + matrix[8] * b,
    )

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** 基色色度 → XYZ（Y 归一化为 1）。 */
    fun rgbToXyz(gamut: LogGamut): FloatArray {
        val r = xyToXyz(gamut.xr, gamut.yr)
        val g = xyToXyz(gamut.xg, gamut.yg)
        val b = xyToXyz(gamut.xb, gamut.yb)
        val white = xyToXyz(LogGamut.WHITE_X, LogGamut.WHITE_Y)
        val primaries = floatArrayOf(
            r[0], g[0], b[0],
            r[1], g[1], b[1],
            r[2], g[2], b[2],
        )
        val scales = multiply3x3Vector(invert3x3(primaries) ?: return identity(), white)
        return floatArrayOf(
            r[0] * scales[0], g[0] * scales[1], b[0] * scales[2],
            r[1] * scales[0], g[1] * scales[1], b[1] * scales[2],
            r[2] * scales[0], g[2] * scales[1], b[2] * scales[2],
        )
    }

    private fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = if (kotlin.math.abs(y) < 1e-6f) 1e-6f else y
        return floatArrayOf(x / safeY, 1f, (1f - x - y) / safeY)
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += a[row * 3 + k] * b[k * 3 + col]
                }
                out[row * 3 + col] = sum
            }
        }
        return out
    }

    private fun multiply3x3Vector(a: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
        a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
        a[6] * v[0] + a[7] * v[1] + a[8] * v[2],
    )

    /** 3×3 求逆；奇异（行列式退化）返回 null，由调用方回退 identity（不崩溃）。 */
    fun invert3x3(m: FloatArray): FloatArray? {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        val inv = 1.0 / det

        return floatArrayOf(
            ((e * i - f * h) * inv).toFloat(), ((c * h - b * i) * inv).toFloat(), ((b * f - c * e) * inv).toFloat(),
            ((f * g - d * i) * inv).toFloat(), ((a * i - c * g) * inv).toFloat(), ((c * d - a * f) * inv).toFloat(),
            ((d * h - e * g) * inv).toFloat(), ((b * g - a * h) * inv).toFloat(), ((a * e - b * d) * inv).toFloat(),
        )
    }
}
