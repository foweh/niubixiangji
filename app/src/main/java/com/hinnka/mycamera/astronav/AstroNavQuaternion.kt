/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：四元数 / 三维向量基础运算（纯 Kotlin，无 Android 依赖）。
 *
 * 约定（与 xioTechnologies/Fusion 对齐，便于逐行核对移植结果）：
 *  - 四元数分量顺序为 (w, x, y, z)，Hamilton 乘法（Fusion 的 FusionQuaternionProduct）。
 *  - 旋转矩阵为 3x3 行主序 FloatArray(9)，语义与 android.hardware.SensorManager
 *    getRotationMatrixFromVector 一致：把世界坐标系向量变换到设备坐标系。
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 三维向量（可变的浮点数组视图，避免热路径频繁装箱）。 */
data class AstroNavVec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: AstroNavVec3) = AstroNavVec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: AstroNavVec3) = AstroNavVec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = AstroNavVec3(x * s, y * s, z * s)

    fun dot(o: AstroNavVec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: AstroNavVec3) = AstroNavVec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val normSquared: Float get() = x * x + y * y + z * z

    val norm: Float get() = sqrt(normSquared)

    /** 单位化；零向量/非有限输入返回 [ZERO]（调用方据 [isZero] 判空，不抛异常）。 */
    fun normalized(): AstroNavVec3 {
        val n = norm
        if (n.isNaN() || n < 1e-9f) return ZERO
        return AstroNavVec3(x / n, y / n, z / n)
    }

    val isZero: Boolean get() = abs(x) < 1e-9f && abs(y) < 1e-9f && abs(z) < 1e-9f

    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)

    companion object {
        val ZERO = AstroNavVec3(0f, 0f, 0f)

        fun of(a: FloatArray): AstroNavVec3 =
            if (a.size >= 3) AstroNavVec3(a[0], a[1], a[2]) else ZERO
    }
}

/**
 * 单位四元数（w, x, y, z）。
 *
 * 所有运算对非有限输入做了防御：遇到 NaN/Inf 一律返回安全值而不抛异常，
 * 保证传感器脏样本不会把 UI 打崩（与工程的 RotationQuaternion.isUsableQuaternion 同思路）。
 */
data class AstroNavQuaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {

    /** 模长平方（用于可用性校验）。 */
    val normSquared: Float get() = w * w + x * x + y * y + z * z

    val isFinite: Boolean
        get() = !(w.isNaN() || x.isNaN() || y.isNaN() || z.isNaN() ||
            w.isInfinite() || x.isInfinite() || y.isInfinite() || z.isInfinite())

    /**
     * 样本是否可用：有限、模长平方落在 [0.5, 1.5]（移植 stardroid RotationQuaternion
     * 的可用性区间，对应其 issue #1109：未校准的传感器栈会发全零/超大样本，
     * 放行会让星图乱指）。
     */
    val isUsable: Boolean get() = isFinite && normSquared in 0.5f..1.5f

    /** 单位化；不可用时返回 [IDENTITY]。 */
    fun normalized(): AstroNavQuaternion {
        if (!isFinite) return IDENTITY
        val n = sqrt(normSquared)
        if (n < 1e-9f) return IDENTITY
        return AstroNavQuaternion(w / n, x / n, y / n, z / n)
    }

    fun conjugate() = AstroNavQuaternion(w, -x, -y, -z)

    /** Hamilton 积 this ⊗ o（与 Fusion FusionQuaternionProduct 完全一致）。 */
    operator fun times(o: AstroNavQuaternion) = AstroNavQuaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
    )

    operator fun plus(o: AstroNavQuaternion) =
        AstroNavQuaternion(w + o.w, x + o.x, y + o.y, z + o.z)

    operator fun times(s: Float) = AstroNavQuaternion(w * s, x * s, y * s, z * s)

    /**
     * 把三维向量按本四元数旋转（等价 v' = q ⊗ (0,v) ⊗ q*，展开为向量形式省一次四元数乘法）：
     *   v' = v + 2·qw·(qv × v) + 2·(qv × (qv × v))，其中 qv = (x, y, z)。
     */
    fun rotate(v: AstroNavVec3): AstroNavVec3 {
        val q = normalized()
        val qv = AstroNavVec3(q.x, q.y, q.z)
        val t = qv.cross(v)
        return v + t * (2f * q.w) + qv.cross(t) * 2f
    }

    /**
     * 转 3x3 旋转矩阵（行主序），语义同 SensorManager.getRotationMatrixFromVector。
     * 与 Fusion FusionQuaternionToMatrix 一致。
     */
    fun toRotationMatrix(): FloatArray {
        val q = normalized()
        val qw = q.w
        val qx = q.x
        val qy = q.y
        val qz = q.z
        val twoQw = 2f * qw
        val twoQx = 2f * qx
        val twoQy = 2f * qy
        val twoQz = 2f * qz
        return floatArrayOf(
            twoQw * qw - 1f + twoQx * qx, twoQx * qy - twoQw * qz, twoQx * qz + twoQw * qy,
            twoQx * qy + twoQw * qz, twoQw * qw - 1f + twoQy * qy, twoQy * qz - twoQw * qx,
            twoQx * qz - twoQw * qy, twoQy * qz + twoQw * qx, twoQw * qw - 1f + twoQz * qz,
        )
    }

    /** 与另一姿态的夹角（度，0..180），用于校准质量/漂移评估。 */
    fun angleDegreesTo(o: AstroNavQuaternion): Float {
        val a = normalized()
        val b = o.normalized()
        val dot = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(0f, 1f)
        return (2.0 * acos(dot.toDouble()) * 180.0 / Math.PI).toFloat()
    }

    fun toFloatArray() = floatArrayOf(w, x, y, z)

    companion object {
        val IDENTITY = AstroNavQuaternion(1f, 0f, 0f, 0f)

        /** 轴角构造（轴会自动单位化；零轴返回单位四元数）。 */
        fun fromAxisAngle(axis: AstroNavVec3, angleDeg: Float): AstroNavQuaternion {
            val n = axis.normalized()
            if (n.isZero) return IDENTITY
            val half = (angleDeg * 0.5f) * (Math.PI / 180.0).toFloat()
            val s = sin(half)
            return AstroNavQuaternion(cos(half), n.x * s, n.y * s, n.z * s)
        }

        /** 由旋转矩阵（行主序 3x3）反解四元数；非有限输入返回单位四元数。 */
        fun fromRotationMatrix(m: FloatArray): AstroNavQuaternion {
            if (m.size < 9) return IDENTITY
            val m00 = m[0]; val m01 = m[1]; val m02 = m[2]
            val m10 = m[3]; val m11 = m[4]; val m12 = m[5]
            val m20 = m[6]; val m21 = m[7]; val m22 = m[8]
            val trace = m00 + m11 + m22
            val q = if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                AstroNavQuaternion(
                    0.25f * s,
                    (m21 - m12) / s,
                    (m02 - m20) / s,
                    (m10 - m01) / s,
                )
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                AstroNavQuaternion(
                    (m21 - m12) / s,
                    0.25f * s,
                    (m01 + m10) / s,
                    (m02 + m20) / s,
                )
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                AstroNavQuaternion(
                    (m02 - m20) / s,
                    (m01 + m10) / s,
                    0.25f * s,
                    (m12 + m21) / s,
                )
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                AstroNavQuaternion(
                    (m10 - m01) / s,
                    (m02 + m20) / s,
                    (m12 + m21) / s,
                    0.25f * s,
                )
            }
            return q.normalized()
        }

        /**
         * 解析 Android Sensor.TYPE_ROTATION_VECTOR 的样本（分量顺序 x, y, z[, w]）。
         * 缺 w 时按 w = sqrt(max(0, 1 - x²-y²-z²)) 补全（与工程
         * AstroProjection.isUsableQuaternion 的处理一致，但这里同时完成顺序转换）。
         * 非有限/退化样本返回 [IDENTITY]。
         */
        fun fromRotationVectorValues(values: FloatArray, offset: Int = 0): AstroNavQuaternion {
            if (values.size < offset + 3) return IDENTITY
            val vx = values[offset]
            val vy = values[offset + 1]
            val vz = values[offset + 2]
            val vw = if (values.size >= offset + 4) values[offset + 3]
            else sqrt(max(0f, 1f - (vx * vx + vy * vy + vz * vz)))
            val q = AstroNavQuaternion(vw, vx, vy, vz)
            return if (q.isUsable) q.normalized() else IDENTITY
        }

        /**
         * 线性插值（nlerp，归一化线性插值）：t ∈ [0,1] 越界会被 clamp。
         * 用于低通平滑（比 slerp 便宜，夹角 < 90° 时误差可忽略）。
         */
        fun nlerp(a: AstroNavQuaternion, b: AstroNavQuaternion, t: Float): AstroNavQuaternion {
            val tt = t.coerceIn(0f, 1f)
            // 取最短路径：点积为负时翻转 b
            val dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            val target = if (dot < 0f) b * -1f else b
            return (a * (1f - tt) + target * tt).normalized()
        }
    }
}
