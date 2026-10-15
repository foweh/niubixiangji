/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发：Star Walk 2 风格星空叠加组件（ui/astro）。
 * 本文件为新增文件：赤道坐标 -> ENU 世界向量 -> 设备姿态 -> 屏幕像素 的投影链路。
 * 纯 Kotlin 数学，无第三方依赖。
 */
package com.hinnka.mycamera.ui.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 星空投影算法工具（无状态 object）。
 *
 * 坐标变换链路：
 * 1. 赤道坐标 (RA, Dec) -> 地平坐标 (高度 alt, 方位角 az) -> ENU 世界单位向量；
 *    公式为标准球面天文学公式：
 *      sin(alt) = sin(lat)*sin(dec) + cos(lat)*cos(dec)*cos(HA)
 *      HA = LST - RA（时角）
 *      sin(az)  = -cos(dec)*sin(HA) / cos(alt)
 *      cos(az)  = (sin(dec) - sin(lat)*sin(alt)) / (cos(lat)*cos(alt))
 *    ENU 世界向量：e = cos(alt)*sin(az), n = cos(alt)*cos(az), u = sin(alt)。
 * 2. ENU 世界向量 --(SensorManager 旋转矩阵 R)--> 设备坐标系向量：dev = R * world。
 *    Android 约定 R 把世界坐标系向量变换到设备坐标系（x 向右、y 向上、z 朝屏幕外）。
 * 3. 后置摄像头沿设备 -z 方向取景，故视深 depth = -dev.z；透视投影：
 *      screenX = centerX + (dev.x / depth) * focalPx
 *      screenY = centerY + (dev.y / depth) * focalPx
 *    focalPx = (screenWidth/2) / tan(hFov/2)，hFov 由镜头焦距/zoomRatio 换算。
 * 4. 最后按屏幕显示旋转角度（displayRotation）把 2D 点绕画布中心旋转，
 *    适配手机横握/竖握。
 */
object AstroProjection {

    private const val DEG2RAD = (PI / 180.0).toFloat()
    private const val HOUR2RAD = (PI / 12.0).toFloat() // 24h = 2π

    /** 构造 ENU 世界单位向量的公开小工具（避免各调用点重复写 floatArrayOf）。 */
    fun floatOf(e: Float, n: Float, u: Float): FloatArray = floatArrayOf(e, n, u)

    /** 赤道坐标 -> ENU 世界单位向量（长度约为 1）。 */
    fun equatorialToEnu(
        raHours: Float,
        decDeg: Float,
        observerLatDeg: Float,
        lstHours: Float,
    ): FloatArray {
        val ha = (lstHours - raHours) * HOUR2RAD
        val dec = decDeg * DEG2RAD
        val lat = observerLatDeg * DEG2RAD

        val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1f, 1f))
        val cosAlt = cos(alt).coerceAtLeast(1e-4f) // 避免天顶附近除零

        // 方位角：从正北顺时针
        val sinAz = (-cos(dec) * sin(ha)) / cosAlt
        val cosAz = (sin(dec) - sin(lat) * sinAlt) / (cos(lat) * cosAlt)

        val e = cosAlt * sinAz
        val n = cosAlt * cosAz
        val u = sinAlt
        val norm = sqrt(e * e + n * n + u * u).coerceAtLeast(1e-6f)
        return floatOf(e / norm, n / norm, u / norm)
    }

    /** 世界向量左乘旋转矩阵（R 为 9 元素行-major 3x3，来自 SensorManager）。 */
    fun rotateWorldToDevice(r: FloatArray, world: FloatArray): FloatArray {
        return floatOf(
            r[0] * world[0] + r[1] * world[1] + r[2] * world[2],
            r[3] * world[0] + r[4] * world[1] + r[5] * world[2],
            r[6] * world[0] + r[7] * world[1] + r[8] * world[2],
        )
    }

    /**
     * 设备向量 -> 画布像素坐标。
     * @param dev 设备坐标系向量
     * @param cx 画布中心 x
     * @param cy 画布中心 y
     * @param focalPx 透视焦距（像素）
     * @return null 表示不在后置摄像头视野内（在背后或过近）。
     */
    fun deviceToScreen(dev: FloatArray, cx: Float, cy: Float, focalPx: Float): Pair<Float, Float>? {
        val depth = -dev[2] // 后置相机沿 -z 看出去
        if (depth < 0.05f) return null
        val x = cx + (dev[0] / depth) * focalPx
        val y = cy + (dev[1] / depth) * focalPx
        return x to y
    }

    /** 把画布上的点绕 (cx, cy) 旋转 angleDeg（显示旋转适配）。 */
    fun rotateAroundCenter(
        x: Float, y: Float, cx: Float, cy: Float, angleDeg: Float,
    ): Pair<Float, Float> {
        if (angleDeg == 0f) return x to y
        val rad = angleDeg * DEG2RAD
        val dx = x - cx
        val dy = y - cy
        val cosA = cos(rad)
        val sinA = sin(rad)
        return (cx + dx * cosA - dy * sinA) to (cy + dx * sinA + dy * cosA)
    }

    /** 由水平视场角（度）与画布宽度（像素）换算焦距（像素）。 */
    fun focalPxForHfov(canvasWidthPx: Float, horizontalFovDeg: Float): Float {
        val half = (horizontalFovDeg * 0.5f) * DEG2RAD
        // focal = (w/2) / tan(hFov/2)
        val tanHalf = tan(half)
        return (canvasWidthPx * 0.5f) / tanHalf
    }

    // ------------------------------------------------------------------
    // 以下三项参考 Google 开源星图 Sky Map（stardroid-v2）：
    //  - sensors/RotationQuaternion.kt （姿态样本可用性校验）
    //  - camera/CameraFov.kt           （预览短边 FOV 公式）
    //  - layers/HorizonLayer / AltAzGridLayer（地平/网格的 ENU 采样思路）
    // 仅移植纯数学思路，未引入其渲染/依赖。
    // ------------------------------------------------------------------

    /**
     * 地平坐标 -> ENU 世界向量（stardroid HorizonLayer/AltAzGridLayer 思路）。
     * 地平线、Alt/Az 网格都在本地 ENU 系里采样，与恒星的赤道坐标无关。
     *
     * @param altDeg 高度角（度，0=地平线，90=天顶）
     * @param azDeg 方位角（度，从正北顺时针：0=北 90=东 180=南 270=西）
     */
    fun altAzToEnu(altDeg: Float, azDeg: Float): FloatArray {
        val alt = altDeg * DEG2RAD
        val az = azDeg * DEG2RAD
        val e = cos(alt) * sin(az)
        val n = cos(alt) * cos(az)
        val u = sin(alt)
        val norm = sqrt(e * e + n * n + u * u).coerceAtLeast(1e-6f)
        return floatOf(e / norm, n / norm, u / norm)
    }

    /**
     * 预览短边视场角（度）。移植自 stardroid CameraFov.cameraShortSideFovDeg：
     *   fov = 2 · atan( visibleShortSideMm / (2 · focalLengthMm) )
     * 其中 visibleShortSideMm 按预览宽高比对传感器短边裁剪后得到。
     *
     * 调用方（B1）可从 CameraState 的传感器物理尺寸与镜头焦距换算后传入；
     * 拿不到则返回 null，由调用方回退到默认约 60° 对角 FOV。
     *
     * @param sensorLongSideMm 传感器长边物理尺寸（mm，手机传感器横向）
     * @param sensorShortSideMm 传感器短边物理尺寸（mm）
     * @param focalLengthMm 镜头焦距（mm）
     * @param viewAspectLongOverShort 预览视图的 长/宽 比（>=1）
     */
    fun shortSideFovDeg(
        sensorLongSideMm: Float,
        sensorShortSideMm: Float,
        focalLengthMm: Float,
        viewAspectLongOverShort: Float,
    ): Float? {
        if (sensorLongSideMm <= 0f || sensorShortSideMm <= 0f) return null
        if (focalLengthMm <= 0f || viewAspectLongOverShort < 1f) return null
        val sensorAspect = sensorLongSideMm / sensorShortSideMm
        val visibleShortSideMm = if (viewAspectLongOverShort >= sensorAspect) {
            sensorLongSideMm / viewAspectLongOverShort
        } else {
            sensorShortSideMm
        }
        return (2f * atan(visibleShortSideMm / (2f * focalLengthMm))) / DEG2RAD
    }

    /**
     * 姿态样本可用性校验（移植 stardroid RotationQuaternion.isUsableQuaternion，
     * 对应其 issue #1109：某些传感器栈在未校准好时会发全零/NaN 样本，
     * 放行会让星图乱指）。返回 true 表示 (x,y,z,w) 近似单位四元数。
     * 若事件只给了 3 分量（缺 w），按 w = sqrt(1 - x²-y²-z²) 补全。
     */
    fun isUsableQuaternion(values: FloatArray): Boolean {
        if (values.size < 3) return false
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = if (values.size >= 4) values[3]
        else sqrt(max(0f, 1f - (x * x + y * y + z * z)))
        val norm2 = x * x + y * y + z * z + w * w
        // NaN 会同时让两个比较都失败，所以这一个区间判断同时覆盖了非有限值
        return norm2 in 0.5f..1.5f
    }
}
