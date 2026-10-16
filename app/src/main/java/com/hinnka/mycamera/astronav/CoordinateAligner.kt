/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：坐标系对齐（世界 ENU <-> 设备 <-> 屏幕）与真北校正。
 *
 * ============================================================================
 *  复用与来源（PATTERN_SPEC §9：复用优先）
 * ============================================================================
 *  1. 屏幕投影数学直接复用工程既有 ui/astro/AstroProjection.kt
 *     （其本身来源：Google Sky Map / sky-map-team/stardroid 的纯数学思路，
 *      该仓库 1883★，许可证 GitHub 标为 NOASSERTION（Apache-2.0 与 GPL 混装），
 *      按 PATTERN_SPEC §1.4 只参考思路、未采其代码；投影公式为标准球面天文学）。
 *     → 本文件不重复实现投影，只做「姿态四元数 <-> 旋转矩阵 <-> 屏幕」的接线，
 *       保证与既有 AR 叠加层 100% 同源、零回归。
 *  2. 四元数 -> 3x3 旋转矩阵：与 android.hardware.SensorManager
 *     getRotationMatrixFromVector 的公式完全一致（也等于 Fusion FusionQuaternionToMatrix），
 *     因此本模块给出的矩阵可直接替换既有 AstroOverlay 里由旋转向量算出的矩阵。
 *     Android 该矩阵的语义：R · (世界向量) = 设备向量（世界系 = 本地 ENU）。
 *  3. 磁偏角（真北 vs 磁北）校正：绕世界系天顶轴（+Z）旋转，见 [applyTrueNorth]。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.ui.astro.AstroProjection
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** 相机指向的地平坐标（度）。 */
data class AstroNavPointing(
    /** 方位角（度，从真北顺时针：0=北 90=东 180=南 270=西）。 */
    val azimuthDegrees: Float,
    /** 高度角（度，0=地平线 90=天顶，负=地平线下）。 */
    val altitudeDegrees: Float,
) {
    /** 是否指向地平线以上。 */
    val aboveHorizon: Boolean get() = altitudeDegrees > 0f

    /** 16 方位中文名（与既有 AstroOverlay 的罗盘命名口径一致）。 */
    val compassName: String get() = CoordinateAligner.compassName(azimuthDegrees)
}

/**
 * 坐标系对齐工具（无状态 object，纯 Kotlin + 复用 AstroProjection，可 JVM 单测）。
 *
 * 链路：世界 ENU（真北为 X 轴方位 0）-> [applyTrueNorth] 校正到磁北对齐世界 ->
 * 设备姿态四元数 -> 设备向量 ->（[AstroProjection]）屏幕像素。
 */
object CoordinateAligner {

    /** 世界系天顶轴（ENU 的 +Z）。真北/手动偏角都绕它旋转。 */
    private val UP_AXIS = AstroNavVec3(0f, 0f, 1f)

    private val COMPASS_16 = listOf(
        "北", "东北偏北", "东北", "东北偏东",
        "东", "东南偏东", "东南", "东南偏南",
        "南", "西南偏南", "西南", "西南偏西",
        "西", "西北偏西", "西北", "西北偏北",
    )

    /** 方位角 -> 16 方位中文名。 */
    fun compassName(azDeg: Float): String {
        val az = AstroNavAngles.wrap360(azDeg)
        val idx = (((az + 11.25f) / 22.5f).toInt()) % 16
        return COMPASS_16[(idx + 16) % 16]
    }

    /**
     * 姿态四元数 -> 9 元素行主序旋转矩阵（世界 -> 设备）。
     *
     * 与 SensorManager.getRotationMatrixFromVector 数值等价（同一公式），
     * 可直接喂给既有 AstroProjection.rotateWorldToDevice / AstroOverlay。
     */
    fun worldToDeviceMatrix(attitude: AstroNavQuaternion): FloatArray =
        attitude.normalized().toRotationMatrix()

    /**
     * 真北校正 + 手动偏角：把「磁北对齐世界系」的姿态，转成「真北对齐世界系」的姿态。
     *
     * 推导：设磁偏角 D（东正西负，即 真北 = 磁北 + D）。传感器融合出的世界系 N 轴指向磁北，
     * 而星表投影（AstroProjection.equatorialToEnu / altAzToEnu）的方位角以**真北**为 0。
     * 需要把真北世界向量先绕 +Z 旋转 +D 度（右乘世界系旋转）变成磁北世界向量，再乘姿态矩阵：
     *   dev = R · (Rz(D) · world_true)  =>  等效姿态 q' = q ⊗ q_z(D)
     * 手动偏角 Δ 同轴叠加，故总旋转角 = D + Δ。
     *
     * @param attitude 融合后的设备姿态（磁北对齐世界系）
     * @param declinationDeg 磁偏角（度，东正西负）
     * @param manualYawOffsetDeg 手动方向微调（度，正 = 顺时针/向东；已 clamp 到 [-180,180]）
     * @return 真北对齐世界系的设备姿态
     */
    fun applyTrueNorth(
        attitude: AstroNavQuaternion,
        declinationDeg: Float,
        manualYawOffsetDeg: Float = 0f,
    ): AstroNavQuaternion {
        val total = (safe(declinationDeg) + safe(manualYawOffsetDeg)).coerceIn(-360f, 360f)
        if (total == 0f) return attitude.normalized()
        val correction = AstroNavQuaternion.fromAxisAngle(UP_AXIS, total)
        return (attitude.normalized() * correction).normalized()
    }

    /** 由相机轴约定给出设备坐标系下的取景前向向量。 */
    fun cameraForwardDevice(cameraAxis: CameraAxis): AstroNavVec3 = when (cameraAxis) {
        CameraAxis.BACK -> AstroNavVec3(0f, 0f, -1f) // Android 约定：后置沿 -Z 看出去
        CameraAxis.FRONT -> AstroNavVec3(0f, 0f, 1f)
    }

    /**
     * 相机当前指向的地平坐标（真北对齐世界系）。
     *
     * @param attitudeTrueNorth 已做真北校正的姿态（[applyTrueNorth] 的输出）
     * @param cameraAxis 取景相机
     */
    fun pointing(attitudeTrueNorth: AstroNavQuaternion, cameraAxis: CameraAxis): AstroNavPointing {
        val forwardDev = cameraForwardDevice(cameraAxis)
        // 姿态是 世界->设备；取逆（共轭）得到 设备->世界，把取景前向变回世界 ENU
        val forwardEnu = attitudeTrueNorth.normalized().conjugate().rotate(forwardDev)
        return enuToPointing(forwardEnu)
    }

    /** ENU 世界向量 -> 地平坐标（度）。 */
    fun enuToPointing(enu: AstroNavVec3): AstroNavPointing {
        val n = enu.normalized()
        val alt = Math.toDegrees(asin(n.z.coerceIn(-1f, 1f).toDouble())).toFloat()
        // 方位角：从正北（+Y=北）顺时针到 +X（东）
        val az = AstroNavAngles.wrap360(Math.toDegrees(atan2(n.x.toDouble(), n.y.toDouble())).toFloat())
        return AstroNavPointing(az, alt)
    }

    /**
     * 世界 ENU 向量 -> 屏幕像素（复用 AstroProjection，与既有 AstroOverlay 同源）。
     *
     * @param rotationMatrix 世界->设备旋转矩阵（[worldToDeviceMatrix]）
     * @param worldEnu 世界 ENU 单位向量（可由 AstroProjection.equatorialToEnu / altAzToEnu 得到）
     * @param centerX / centerY 画布中心
     * @param focalPx 透视焦距（AstroProjection.focalPxForHfov）
     * @param displayRotationDegrees 屏幕显示旋转（0/90/180/270 或吸附后的值）
     * @return null = 不在取景视野内（在背后或过近）
     */
    fun worldToScreen(
        rotationMatrix: FloatArray,
        worldEnu: FloatArray,
        centerX: Float,
        centerY: Float,
        focalPx: Float,
        displayRotationDegrees: Int = 0,
    ): Pair<Float, Float>? {
        val dev = AstroProjection.rotateWorldToDevice(rotationMatrix, worldEnu)
        val raw = AstroProjection.deviceToScreen(dev, centerX, centerY, focalPx) ?: return null
        return AstroProjection.rotateAroundCenter(
            raw.first, raw.second, centerX, centerY, -displayRotationDegrees.toFloat(),
        )
    }

    /**
     * 屏幕点 -> 设备坐标系视线向量（未归一化）。用于后续「点击天空看目标」类交互；
     * 与 [worldToScreen] 严格互逆（在 [displayRotationDegrees] 一致时）。
     */
    fun screenToDevice(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        focalPx: Float,
        displayRotationDegrees: Int = 0,
    ): AstroNavVec3 {
        val unrotated = AstroProjection.rotateAroundCenter(
            x, y, centerX, centerY, displayRotationDegrees.toFloat(),
        )
        val dx = unrotated.first - centerX
        val dy = unrotated.second - centerY
        val focal = if (focalPx.isFinite() && focalPx > 1e-3f) focalPx else 1f
        return AstroNavVec3(dx, dy, -focal).normalized()
    }

    /** 屏幕点 -> 世界 ENU（设备->世界），供「点击处指向哪颗星」使用。 */
    fun screenToWorldEnu(
        attitudeTrueNorth: AstroNavQuaternion,
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        focalPx: Float,
        displayRotationDegrees: Int = 0,
    ): AstroNavVec3 {
        val dev = screenToDevice(x, y, centerX, centerY, focalPx, displayRotationDegrees)
        return attitudeTrueNorth.normalized().conjugate().rotate(dev).normalized()
    }

    /**
     * 屏幕像素坐标下的角像素比（用于把「手动微调 / 抖动」换算成度数）。
     * @return 每像素对应的角度（度/像素）；画布宽度非法时返回 0。
     */
    fun degreesPerPixel(canvasWidthPx: Float, horizontalFovDegrees: Float): Float {
        if (canvasWidthPx <= 0f || !horizontalFovDegrees.isFinite()) return 0f
        val w = canvasWidthPx.coerceAtLeast(1f)
        return horizontalFovDegrees.coerceIn(1f, 179f) / w
    }

    /**
     * 屏幕旋转（横竖屏）对设备坐标系的影响：把「自然方向」的姿态矩阵旋转到当前显示方向。
     *
     * 与既有 AstroOverlay 的 2D 后处理（AstroProjection.rotateAroundCenter）等价，
     * 这里额外提供设备系版本，便于在不解算屏幕点的情况下（如测姿态抖动）直接使用。
     *
     * @param displayRotationDegrees 0/90/180/270（其它值会按 [AstroNavConfig.snapDisplayRotation] 吸附）
     */
    fun screenAlignedAttitude(attitude: AstroNavQuaternion, displayRotationDegrees: Int): AstroNavQuaternion {
        val snapped = AstroNavConfig.snapDisplayRotation(displayRotationDegrees)
        if (snapped == 0) return attitude.normalized()
        // 屏幕旋转等价于绕设备坐标系 Z 轴（屏幕法线）反向旋转
        val deviceForwardAxis = AstroNavVec3(0f, 0f, 1f)
        val rot = AstroNavQuaternion.fromAxisAngle(deviceForwardAxis, -snapped.toFloat())
        return (rot * attitude.normalized()).normalized()
    }

    /** 相机水平半视场角（度）-> 焦距（像素），转发 AstroProjection（保证同源）。 */
    fun focalPxForHfov(canvasWidthPx: Float, horizontalFovDegrees: Float): Float =
        AstroProjection.focalPxForHfov(canvasWidthPx, horizontalFovDegrees)

    /** 世界向量模长（自检用；异常输入返回 0）。 */
    internal fun norm(v: AstroNavVec3): Float = sqrt(v.normSquared).takeIf { it.isFinite() } ?: 0f

    private fun safe(v: Float): Float = if (v.isFinite()) v else 0f
}
