/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：四元数/向量基础运算、轴角与矩阵互转、旋转向量解析、nlerp 最短路径、
 *       姿态夹角、可用性判据（数值边界：NaN/Inf/全零/模长越界）。
 * 纯 JVM，无 Android 依赖。
 */
package com.hinnka.mycamera.astronav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroNavQuaternionTest {

    private val eps = 1e-4f

    private fun assertVecEquals(expected: AstroNavVec3, actual: AstroNavVec3, tol: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
        assertEquals("z", expected.z, actual.z, tol)
    }

    // ---------------- 向量基础 ----------------

    @Test
    fun vector_operations_are_correct() {
        val a = AstroNavVec3(1f, 2f, 3f)
        val b = AstroNavVec3(4f, 5f, 6f)
        assertEquals(32f, a.dot(b), eps)
        assertVecEquals(AstroNavVec3(-3f, 6f, -3f), a.cross(b))
        assertVecEquals(AstroNavVec3(5f, 7f, 9f), a + b)
        assertVecEquals(AstroNavVec3(-3f, -3f, -3f), a - b)
        assertVecEquals(AstroNavVec3(2f, 4f, 6f), a * 2f)
        assertEquals(14f, a.normSquared, eps)
    }

    @Test
    fun vector_normalization_handles_degenerate_and_non_finite() {
        assertEquals(1f, AstroNavVec3(0f, 3f, 4f).normalized().norm, eps)
        // 零向量 -> ZERO（不抛异常）
        assertEquals(AstroNavVec3.ZERO, AstroNavVec3(0f, 0f, 0f).normalized())
        // NaN / Inf -> ZERO（脏样本防护）
        assertEquals(AstroNavVec3.ZERO, AstroNavVec3(Float.NaN, 1f, 0f).normalized())
        assertEquals(AstroNavVec3.ZERO, AstroNavVec3(Float.POSITIVE_INFINITY, 1f, 0f).normalized())
        assertTrue(AstroNavVec3(0f, 0f, 0f).isZero)
        assertFalse(AstroNavVec3(1e-6f, 0f, 0f).isZero)
    }

    // ---------------- 四元数基础 ----------------

    @Test
    fun identity_rotation_keeps_vector_unchanged() {
        val v = AstroNavVec3(1f, -2f, 0.5f)
        assertVecEquals(v, AstroNavQuaternion.IDENTITY.rotate(v))
        assertEquals(0f, AstroNavQuaternion.IDENTITY.angleDegreesTo(AstroNavQuaternion.IDENTITY), eps)
    }

    @Test
    fun rotation_about_axes_matches_matrix_convention() {
        // 绕设备 X 轴 -90°：(0,1,0) -> (0,0,-1)（与 toRotationMatrix 的行主序遍历一致）
        val qx = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), -90f)
        assertVecEquals(AstroNavVec3(0f, 0f, -1f), qx.rotate(AstroNavVec3(0f, 1f, 0f)))

        // 绕设备 Y 轴 +90°：(1,0,0) -> (0,0,-1)
        val qy = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 1f, 0f), 90f)
        assertVecEquals(AstroNavVec3(0f, 0f, -1f), qy.rotate(AstroNavVec3(1f, 0f, 0f)))

        // rotate(v) 必须等于 toRotationMatrix()·v（两条路径一致性）
        val m = qx.toRotationMatrix()
        val v = AstroNavVec3(0.3f, -0.7f, 0.2f)
        val byMatrix = AstroNavVec3(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z,
        )
        assertVecEquals(byMatrix, qx.rotate(v))
    }

    @Test
    fun rotation_matrix_matches_sensor_manager_formula() {
        // 与 android.hardware.SensorManager.getRotationMatrixFromVector 同公式：
        // 单位四元数 -> 单位矩阵
        val id = AstroNavQuaternion.IDENTITY.toRotationMatrix()
        assertEquals(1f, id[0], eps); assertEquals(0f, id[1], eps); assertEquals(0f, id[2], eps)
        assertEquals(1f, id[4], eps); assertEquals(1f, id[8], eps)

        // 90° 绕 Z：正交且行列式为 +1（旋转矩阵；允许浮点误差）
        val m = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), 90f).toRotationMatrix()
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        assertEquals(1f, det, 1e-3f)
        // 列向量的模长为 1
        assertEquals(1f, AstroNavVec3(m[0], m[3], m[6]).norm, 1e-3f)

        // 矩阵 -> 四元数往返
        val q = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0.2f, -0.5f, 0.84f), 37f)
        val back = AstroNavQuaternion.fromRotationMatrix(q.toRotationMatrix())
        assertEquals(0f, q.angleDegreesTo(back), 1e-2f)
    }

    @Test
    fun hamilton_product_composition_matches_sequential_rotation() {
        val q1 = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), 30f)
        val q2 = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), 40f)
        val v = AstroNavVec3(0.5f, 0.2f, -0.3f)
        val sequential = q2.rotate(q1.rotate(v))
        val combined = (q2 * q1).normalized().rotate(v)
        assertVecEquals(sequential, combined, 1e-3f)
    }

    @Test
    fun from_axis_angle_handles_zero_axis_and_zero_angle() {
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion.fromAxisAngle(AstroNavVec3.ZERO, 45f))
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), 0f))
        // 非单位轴会被单位化
        val q = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 5f, 0f), 180f)
        assertEquals(1f, q.normSquared, eps)
    }

    // ---------------- 数值边界：可用性判据 ----------------

    @Test
    fun is_usable_covers_numeric_boundaries() {
        assertTrue(AstroNavQuaternion.IDENTITY.isUsable)
        assertTrue(AstroNavQuaternion(0.7f, 0.7f, 0f, 0f).isUsable) // 模长平方 = 0.98
        // 全零样本（未校准的传感器栈会发这种）
        assertFalse(AstroNavQuaternion(0f, 0f, 0f, 0f).isUsable)
        // 模长平方越界（> 1.5）
        assertFalse(AstroNavQuaternion(2f, 0f, 0f, 0f).isUsable)
        // NaN / Inf
        assertFalse(AstroNavQuaternion(Float.NaN, 0f, 0f, 0f).isUsable)
        assertFalse(AstroNavQuaternion(Float.POSITIVE_INFINITY, 0f, 0f, 0f).isUsable)
        assertFalse(AstroNavQuaternion(Float.NaN, 0f, 0f, 0f).isFinite)
        // 不可用时 normalized() 回落 IDENTITY，不抛异常
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion(Float.NaN, 0f, 0f, 0f).normalized())
    }

    @Test
    fun from_rotation_vector_values_handles_short_and_bad_input() {
        // 标准 4 分量（Android 顺序 x,y,z,w）-> (w,x,y,z)
        val q = AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(1f, q.w, eps)
        assertEquals(0f, q.x, eps)

        // 缺 w：按 w = sqrt(max(0, 1 - x²-y²-z²)) 补全（与既有 AstroProjection 口径一致）
        val three = AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(0f, 0f, 0.5f))
        assertEquals(0.5f, three.z, eps)
        assertEquals(0.8660254f, three.w, 1e-4f)

        // 分量不足 / 全零 / NaN -> IDENTITY
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion.fromRotationVectorValues(floatArrayOf()))
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(0f, 0f, 0f)))
        assertEquals(AstroNavQuaternion.IDENTITY, AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(0f, 0f, 0f, 0f)))
        assertEquals(
            AstroNavQuaternion.IDENTITY,
            AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(Float.NaN, 0f, 0f, 1f)),
        )
        // offset 支持
        val offset = AstroNavQuaternion.fromRotationVectorValues(floatArrayOf(9f, 9f, 0f, 0f, 0f, 1f), offset = 2)
        assertEquals(1f, offset.w, eps)
    }

    // ---------------- nlerp / 夹角 ----------------

    @Test
    fun nlerp_follows_shortest_path_and_clamps() {
        val a = AstroNavQuaternion.IDENTITY
        val b = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), 60f)
        // t=0.5 应产生约 30°
        assertEquals(30f, a.angleDegreesTo(AstroNavQuaternion.nlerp(a, b, 0.5f)), 0.5f)
        // t 越界 clamp
        assertEquals(0f, a.angleDegreesTo(AstroNavQuaternion.nlerp(a, b, -1f)), eps)
        assertEquals(60f, a.angleDegreesTo(AstroNavQuaternion.nlerp(a, b, 2f)), 0.5f)
        // 取最短路径：b 取反（同一姿态）时结果仍在 a 附近，而不是绕远路
        val negated = b * -1f
        assertEquals(30f, a.angleDegreesTo(AstroNavQuaternion.nlerp(a, negated, 0.5f)), 0.5f)
    }

    @Test
    fun angle_between_uses_absolute_dot() {
        val q = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 1f, 0f), 90f)
        assertEquals(90f, AstroNavQuaternion.IDENTITY.angleDegreesTo(q), 1e-2f)
        // q 与其反号表示同一姿态 -> 夹角 0
        assertEquals(0f, q.angleDegreesTo(q * -1f), 1e-2f)
        // 180° 旋转 -> 夹角 180（|dot| 后 acos(-1)=180）
        val half = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), 180f)
        assertEquals(180f, AstroNavQuaternion.IDENTITY.angleDegreesTo(half), 1e-1f)
    }

    @Test
    fun conjugate_inverts_rotation() {
        val q = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0.3f, 0.4f, 0.86f), 123f)
        val v = AstroNavVec3(1f, 0f, 0f)
        assertVecEquals(v, q.conjugate().rotate(q.rotate(v)), 1e-3f)
    }

    @Test
    fun to_float_array_uses_wxyz_order() {
        val q = AstroNavQuaternion(1f, 2f, 3f, 4f)
        val a = q.toFloatArray()
        assertEquals(1f, a[0], eps)
        assertEquals(2f, a[1], eps)
        assertEquals(3f, a[2], eps)
        assertEquals(4f, a[3], eps)
    }
}
