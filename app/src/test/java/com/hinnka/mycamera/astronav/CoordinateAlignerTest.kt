/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**坐标系对齐**——ENU/地平坐标互算、真北（磁偏角）校正、取景前向向量约定、
 *       世界->屏幕与屏幕->世界互逆、显示旋转（0/90/180/270）、焦距换算、方位命名、
 *       数值边界（零向量 / NaN / 地平线以下 / 视野外）。
 * 依赖：复用工程既有 ui.astro.AstroProjection（与 AstroOverlay 同源），纯 JVM 可跑。
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.ui.astro.AstroProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateAlignerTest {

    private val cx = 540f
    private val cy = 960f

    /** 后置相机朝向「磁北地平线」的姿态（设备 -Z 对齐世界 +Y）。 */
    private val lookNorthBack = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), -90f)

    private fun azOf(p: AstroNavPointing) = AstroNavAngles.wrap360(p.azimuthDegrees)

    // ---------------- 地平坐标 <-> ENU ----------------

    @Test
    fun enu_to_pointing_covers_cardinal_directions() {
        val north = CoordinateAligner.enuToPointing(AstroNavVec3(0f, 1f, 0f))
        assertEquals(0f, azOf(north), 1e-3f)
        assertEquals(0f, north.altitudeDegrees, 1e-3f)
        assertFalse(north.aboveHorizon) // 恰好地平线，不算「地平线以上」

        assertEquals(90f, azOf(CoordinateAligner.enuToPointing(AstroNavVec3(1f, 0f, 0f))), 1e-3f)
        assertEquals(180f, azOf(CoordinateAligner.enuToPointing(AstroNavVec3(0f, -1f, 0f))), 1e-3f)
        assertEquals(270f, azOf(CoordinateAligner.enuToPointing(AstroNavVec3(-1f, 0f, 0f))), 1e-3f)

        val zenith = CoordinateAligner.enuToPointing(AstroNavVec3(0f, 0f, 1f))
        assertEquals(90f, zenith.altitudeDegrees, 1e-3f)
        assertTrue(zenith.aboveHorizon)

        val nadir = CoordinateAligner.enuToPointing(AstroNavVec3(0f, 0f, -1f))
        assertEquals(-90f, nadir.altitudeDegrees, 1e-3f)
        assertFalse(nadir.aboveHorizon)

        // 45° 仰角朝东南：e=n=cos45，u=sin45
        val upEast = CoordinateAligner.enuToPointing(AstroNavVec3(1f, 1f, 1f))
        assertEquals(45f, azOf(upEast), 1e-2f)
        assertEquals(35.264f, upEast.altitudeDegrees, 1e-2f)
    }

    @Test
    fun enu_to_pointing_handles_degenerate_input() {
        // 零向量 / NaN：不抛异常，返回 0/0（由调用方按 needsCalibration 处理）
        val zero = CoordinateAligner.enuToPointing(AstroNavVec3.ZERO)
        assertTrue(zero.azimuthDegrees.isFinite())
        assertTrue(zero.altitudeDegrees.isFinite())
        val nan = CoordinateAligner.enuToPointing(AstroNavVec3(Float.NaN, 0f, 0f))
        assertTrue(nan.azimuthDegrees.isFinite())
    }

    // ---------------- 取景前向 + 姿态 -> 指向 ----------------

    @Test
    fun camera_forward_follows_android_axis_convention() {
        assertEquals(AstroNavVec3(0f, 0f, -1f), CoordinateAligner.cameraForwardDevice(CameraAxis.BACK))
        assertEquals(AstroNavVec3(0f, 0f, 1f), CoordinateAligner.cameraForwardDevice(CameraAxis.FRONT))
    }

    @Test
    fun pointing_matches_known_attitudes() {
        // 单位姿态：设备轴 = 世界 ENU 轴 -> 后置相机看向「下」（-Z = 下）
        val down = CoordinateAligner.pointing(AstroNavQuaternion.IDENTITY, CameraAxis.BACK)
        assertEquals(-90f, down.altitudeDegrees, 1e-2f)

        // 前置相机在单位姿态下看向天顶
        val up = CoordinateAligner.pointing(AstroNavQuaternion.IDENTITY, CameraAxis.FRONT)
        assertEquals(90f, up.altitudeDegrees, 1e-2f)

        // 后置相机朝北地平线
        val north = CoordinateAligner.pointing(lookNorthBack, CameraAxis.BACK)
        assertEquals(0f, azOf(north), 1e-2f)
        assertEquals(0f, north.altitudeDegrees, 1e-2f)

        // 后置相机朝东地平线（绕 Y 轴 +90°）
        val east = CoordinateAligner.pointing(
            AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 1f, 0f), 90f),
            CameraAxis.BACK,
        )
        assertEquals(90f, azOf(east), 1e-2f)
        assertEquals(0f, east.altitudeDegrees, 1e-2f)

        // 后置相机朝天顶（绕 X 轴 180°）
        val zenith = CoordinateAligner.pointing(
            AstroNavQuaternion.fromAxisAngle(AstroNavVec3(1f, 0f, 0f), 180f),
            CameraAxis.BACK,
        )
        assertEquals(90f, zenith.altitudeDegrees, 1e-2f)
        assertTrue(zenith.aboveHorizon)
    }

    @Test
    fun pointing_accepts_non_normalized_and_garbage_quaternions() {
        // 未归一化输入（传感器原始样本）不应影响指向
        val raw = AstroNavQuaternion(2f, 0f, 0f, 0f)
        val p = CoordinateAligner.pointing(raw, CameraAxis.BACK)
        assertTrue(p.azimuthDegrees.isFinite())

        // 全零四元数（未校准）-> 单位化回落 IDENTITY，不抛异常
        val zero = CoordinateAligner.pointing(AstroNavQuaternion(0f, 0f, 0f, 0f), CameraAxis.BACK)
        assertTrue(zero.azimuthDegrees.isFinite())

        // NaN
        val nan = CoordinateAligner.pointing(AstroNavQuaternion(Float.NaN, 0f, 0f, 0f), CameraAxis.BACK)
        assertTrue(nan.altitudeDegrees.isFinite())
    }

    // ---------------- 真北校正（磁偏角 / 手动偏角） ----------------

    @Test
    fun true_north_correction_adds_declination_to_azimuth() {
        val magnetic = lookNorthBack // 磁北世界系下，指向磁北（方位 0）
        assertEquals(0f, azOf(CoordinateAligner.pointing(magnetic, CameraAxis.BACK)), 1e-2f)

        // 磁偏角 +10°（东偏）-> 真北方位应为 10°
        val east = CoordinateAligner.applyTrueNorth(magnetic, 10f)
        assertEquals(10f, azOf(CoordinateAligner.pointing(east, CameraAxis.BACK)), 1e-2f)

        // 磁偏角 -10°（西偏）-> 真北方位 350°
        val west = CoordinateAligner.applyTrueNorth(magnetic, -10f)
        assertEquals(350f, azOf(CoordinateAligner.pointing(west, CameraAxis.BACK)), 1e-2f)

        // 手动偏角与磁偏角同轴叠加
        val both = CoordinateAligner.applyTrueNorth(magnetic, 10f, manualYawOffsetDeg = 5f)
        assertEquals(15f, azOf(CoordinateAligner.pointing(both, CameraAxis.BACK)), 1e-2f)
    }

    @Test
    fun true_north_correction_is_noop_for_zero_or_garbage_input() {
        val q = lookNorthBack.normalized()
        // 0 偏角：姿态不变（夹角 0）
        assertEquals(0f, q.angleDegreesTo(CoordinateAligner.applyTrueNorth(q, 0f)), 1e-3f)
        assertEquals(0f, q.angleDegreesTo(CoordinateAligner.applyTrueNorth(q, 0f, 0f)), 1e-3f)
        // NaN / Inf 视为 0（safe()），不抛异常
        assertEquals(0f, q.angleDegreesTo(CoordinateAligner.applyTrueNorth(q, Float.NaN)), 1e-3f)
        assertEquals(0f, q.angleDegreesTo(CoordinateAligner.applyTrueNorth(q, Float.POSITIVE_INFINITY)), 1e-3f)
        // 极大偏角被 clamp 到 ±360（不产生非有限四元数）
        val huge = CoordinateAligner.applyTrueNorth(q, 100_000f)
        assertTrue(huge.isUsable)
    }

    @Test
    fun true_north_correction_result_is_always_unit_and_usable() {
        val q = AstroNavQuaternion(0.5f, 0.5f, 0.5f, 0.5f)
        for (d in listOf(-180f, -30f, 0f, 45f, 90f, 179.9f, 360f)) {
            val out = CoordinateAligner.applyTrueNorth(q, d, 30f)
            assertTrue("d=$d", out.isUsable)
            assertEquals("d=$d", 1f, out.normSquared, 1e-3f)
        }
    }

    // ---------------- 世界 -> 屏幕 / 屏幕 -> 世界 ----------------

    @Test
    fun world_to_screen_center_and_behind_camera() {
        val matrix = CoordinateAligner.worldToDeviceMatrix(AstroNavQuaternion.IDENTITY)
        val focal = CoordinateAligner.focalPxForHfov(1080f, 60f)

        // 正前方（设备 -Z）-> 画面中心
        val center = CoordinateAligner.worldToScreen(matrix, floatArrayOf(0f, 0f, -1f), cx, cy, focal)
        assertNotNull(center)
        assertEquals(cx, center!!.first, 1e-2f)
        assertEquals(cy, center.second, 1e-2f)

        // 背后（设备 +Z）-> 视野外（null）
        assertNull(CoordinateAligner.worldToScreen(matrix, floatArrayOf(0f, 0f, 1f), cx, cy, focal))
        // 侧向 90°（depth = 0）-> 视野外（null）
        assertNull(CoordinateAligner.worldToScreen(matrix, floatArrayOf(0f, 1f, 0f), cx, cy, focal))
        // 零向量不抛异常
        assertNull(CoordinateAligner.worldToScreen(matrix, floatArrayOf(0f, 0f, 0f), cx, cy, focal))
    }

    @Test
    fun world_to_screen_applies_display_rotation() {
        val matrix = CoordinateAligner.worldToDeviceMatrix(AstroNavQuaternion.IDENTITY)
        val focal = CoordinateAligner.focalPxForHfov(1080f, 60f)
        // 略微偏东的世界向量（屏幕上偏右）
        val world = floatArrayOf(0.1f, 0f, -1f)
        val normal = CoordinateAligner.worldToScreen(matrix, world, cx, cy, focal, 0)!!
        assertTrue("应偏右: ${normal.first}", normal.first > cx + 1f)

        // 显示旋转 90°：绕中心旋转 -90°（dx -> -dy 方向），此处 dx>0 变为 y 减小（上移）
        val rotated = CoordinateAligner.worldToScreen(matrix, world, cx, cy, focal, 90)!!
        assertEquals(cx, rotated.first, 1e-2f)
        assertTrue("应上移: ${rotated.second}", rotated.second < cy - 1f)
        assertEquals(normal.first - cx, cy - rotated.second, 1e-2f)
    }

    @Test
    fun screen_to_world_is_inverse_of_projection() {
        val attitude = lookNorthBack
        val focal = CoordinateAligner.focalPxForHfov(1080f, 60f)
        val matrix = CoordinateAligner.worldToDeviceMatrix(attitude)

        // 屏幕中心 -> 世界北（与相机指向一致）
        val worldCenter = CoordinateAligner.screenToWorldEnu(attitude, cx, cy, cx, cy, focal)
        val pointing = CoordinateAligner.enuToPointing(worldCenter)
        assertEquals(0f, azOf(pointing), 1e-2f)
        assertEquals(0f, pointing.altitudeDegrees, 1e-2f)

        // 任意屏幕点 -> 世界 -> 回到屏幕点（往返一致）
        val px = cx + 120f
        val py = cy - 80f
        val world = CoordinateAligner.screenToWorldEnu(attitude, px, py, cx, cy, focal)
        val back = CoordinateAligner.worldToScreen(matrix, world.toFloatArray(), cx, cy, focal)
        assertNotNull(back)
        assertEquals(px, back!!.first, 0.5f)
        assertEquals(py, back.second, 0.5f)

        // 屏幕旋转 90° 时同样互逆
        val worldR = CoordinateAligner.screenToWorldEnu(attitude, px, py, cx, cy, focal, displayRotationDegrees = 90)
        val backR = CoordinateAligner.worldToScreen(matrix, worldR.toFloatArray(), cx, cy, focal, 90)
        assertEquals(px, backR!!.first, 0.5f)
        assertEquals(py, backR.second, 0.5f)
    }

    @Test
    fun screen_to_device_handles_invalid_focal_length() {
        // 非法焦距（0 / NaN）回落到 1，仍是可用的单位向量
        val a = CoordinateAligner.screenToDevice(cx + 10f, cy, cx, cy, 0f)
        assertEquals(1f, a.norm, 1e-3f)
        val b = CoordinateAligner.screenToDevice(cx + 10f, cy, cx, cy, Float.NaN)
        assertEquals(1f, b.norm, 1e-3f)
        // 中心点 -> 正前方 -Z
        val center = CoordinateAligner.screenToDevice(cx, cy, cx, cy, 900f)
        assertEquals(-1f, center.z, 1e-3f)
    }

    // ---------------- 显示旋转 / 焦距 / 命名 ----------------

    @Test
    fun screen_aligned_attitude_snaps_display_rotation() {
        val q = AstroNavQuaternion.IDENTITY
        // 0° 无操作
        assertEquals(0f, q.angleDegreesTo(CoordinateAligner.screenAlignedAttitude(q, 0)), 1e-3f)
        // 90° 等价于绕设备 Z 反向旋转 90°
        val expected = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), -90f)
        assertEquals(0f, expected.angleDegreesTo(CoordinateAligner.screenAlignedAttitude(q, 90)), 1e-2f)
        // 非法值按最近值吸附
        assertEquals(0f, expected.angleDegreesTo(CoordinateAligner.screenAlignedAttitude(q, 77)), 1e-2f)
        val expected180 = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0f, 0f, 1f), -180f)
        assertEquals(0f, expected180.angleDegreesTo(CoordinateAligner.screenAlignedAttitude(q, 200)), 1e-2f)
        // 旋转后仍可用
        assertTrue(CoordinateAligner.screenAlignedAttitude(q, 270).isUsable)
    }

    @Test
    fun focal_length_conversion_matches_pinhole_model() {
        // 60° 水平视场、1080px 宽 -> f = 540 / tan(30°) ≈ 935.3
        val focal = CoordinateAligner.focalPxForHfov(1080f, 60f)
        assertEquals(935.3f, focal, 0.5f)
        // 与既有 AstroProjection 同源（转发）
        assertEquals(focal, AstroProjection.focalPxForHfov(1080f, 60f), 1e-3f)
        // 视场越大焦距越小
        assertTrue(CoordinateAligner.focalPxForHfov(1080f, 90f) < focal)
        // 角像素比
        assertEquals(60f / 1080f, CoordinateAligner.degreesPerPixel(1080f, 60f), 1e-6f)
        assertEquals(0f, CoordinateAligner.degreesPerPixel(0f, 60f), 1e-6f)
        assertEquals(0f, CoordinateAligner.degreesPerPixel(1080f, Float.NaN), 1e-6f)
    }

    @Test
    fun compass_name_covers_16_sectors_and_boundaries() {
        assertEquals("北", CoordinateAligner.compassName(0f))
        assertEquals("北", CoordinateAligner.compassName(350f))
        assertEquals("北", CoordinateAligner.compassName(-10f))
        assertEquals("北", CoordinateAligner.compassName(11.2f))
        assertEquals("东北偏北", CoordinateAligner.compassName(11.3f))
        assertEquals("东北", CoordinateAligner.compassName(45f))
        assertEquals("东", CoordinateAligner.compassName(90f))
        assertEquals("东南", CoordinateAligner.compassName(135f))
        assertEquals("南", CoordinateAligner.compassName(180f))
        assertEquals("西南", CoordinateAligner.compassName(225f))
        assertEquals("西", CoordinateAligner.compassName(270f))
        assertEquals("西北", CoordinateAligner.compassName(315f))
        assertEquals("西北偏北", CoordinateAligner.compassName(337.5f))
        // 非有限输入不抛异常
        assertTrue(CoordinateAligner.compassName(Float.NaN).isNotEmpty())
        // Pointing 自带命名
        assertEquals("东", AstroNavPointing(90f, 5f).compassName)
    }

    @Test
    fun world_to_device_matrix_matches_quaternion_rotation() {
        val q = AstroNavQuaternion.fromAxisAngle(AstroNavVec3(0.2f, -0.5f, 0.84f), 37f)
        val m = CoordinateAligner.worldToDeviceMatrix(q)
        assertEquals(9, m.size)
        val world = AstroNavVec3(0.3f, 0.6f, 0.74f)
        val byMatrix = AstroNavVec3(
            m[0] * world.x + m[1] * world.y + m[2] * world.z,
            m[3] * world.x + m[4] * world.y + m[5] * world.z,
            m[6] * world.x + m[7] * world.y + m[8] * world.z,
        )
        val byQuat = q.normalized().rotate(world)
        assertEquals(byQuat.x, byMatrix.x, 1e-3f)
        assertEquals(byQuat.y, byMatrix.y, 1e-3f)
        assertEquals(byQuat.z, byMatrix.z, 1e-3f)
        // 与 AstroProjection.rotateWorldToDevice 同一输入输出口径
        val viaProjection = AstroProjection.rotateWorldToDevice(m, floatArrayOf(world.x, world.y, world.z))
        assertEquals(byMatrix.x, viaProjection[0], 1e-3f)
        assertEquals(byMatrix.y, viaProjection[1], 1e-3f)
        assertEquals(byMatrix.z, viaProjection[2], 1e-3f)
    }
}
