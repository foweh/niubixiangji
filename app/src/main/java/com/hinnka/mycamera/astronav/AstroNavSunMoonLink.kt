/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：与既有 astroplan / ui.astro 的联动契约（太阳、月亮、恒星指向）。
 *
 * ============================================================================
 *  复用与来源（PATTERN_SPEC §9）
 * ============================================================================
 *  1. 太阳/月亮位置：**直接复用工程既有 astroplan/SunCalc.kt**
 *     （来源 github.com/mourner/suncalc，3495★，BSD-2-Clause，
 *      既有代码已按许可证保留版权声明）。本文件不重复实现天文算法，
 *      只做「地平坐标 -> astronav 指向/屏幕」的接线。
 *  2. 恒星：复用既有 ui/astro/StarCatalog.kt（59 颗亮星 + 11 个星座）与
 *     ui/astro/AstroProjection.kt 的 equatorialToEnu 投影。
 *  3. 恒星时：复用 SunCalc 已经内含的 GMST 公式（280.16 + 360.9856235·d，度/日），
 *     见 [AstroNavSiderealTime]。既有 AstroOverlay 用
 *     `lstHours = (manualOffsetDegrees + dragOffsetDeg)/15f` 作为「相对基准」，
 *     那是**没有真实恒星时的简化**；本文件提供真实 LST，供接线后启用。
 * ============================================================================
 *  ⚠ 方位角口径（容易踩坑，已在 [sunTarget] 内做转换并注明）：
 *     suncalc 的方位角从**正南**起算、向**西**为正（其文档明示）；
 *     本模块与 Astronav / AstroProjection 统一为**从真北顺时针**（0=北 90=东）。
 *     转换：az_north = wrap360(az_suncalc + 180)。
 *     自检例：北纬 45° 春分正午，suncalc 给 az=0°（正南）-> 本模块 180°（正南）✓；
 *             同地上午（时角 -30°），suncalc 给 ≈ -39.2°（南偏东）-> 140.8°（东南）✓。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.astroplan.SunCalc
import com.hinnka.mycamera.ui.astro.AstroProjection
import com.hinnka.mycamera.ui.astro.StarCatalog

/**
 * 一个可瞄准的天空目标（太阳/月亮/恒星统一表示）。
 *
 * @param id 稳定标识（"sun" / "moon" / 星表英文名）
 * @param nameZh 中文显示名
 * @param azimuthDegrees 方位角（度，**从真北顺时针**）
 * @param altitudeDegrees 高度角（度，负=地平线下）
 * @param magnitude 视星等（太阳/月亮可为负；未知传 null）
 */
data class AstroNavSkyTarget(
    val id: String,
    val nameZh: String,
    val azimuthDegrees: Float,
    val altitudeDegrees: Float,
    val magnitude: Float? = null,
) {
    /** 是否在地平线以上。 */
    val aboveHorizon: Boolean get() = altitudeDegrees > 0f

    /** 转世界 ENU 向量（复用既有 AstroProjection.altAzToEnu，保证与叠加层同源）。 */
    fun toEnu(): AstroNavVec3 =
        AstroNavVec3.of(AstroProjection.altAzToEnu(altitudeDegrees, azimuthDegrees))

    override fun toString(): String =
        "$nameZh(${"%.1f".format(azimuthDegrees)}°/${"%.1f".format(altitudeDegrees)}°)"
}

/**
 * 恒星时（地球自转相位）。
 *
 * 复用既有 [SunCalc] 内的 GMST 公式（suncalc `siderealTime` 去掉经度项）：
 *   GMST(度) = 280.16 + 360.9856235 × (自 J2000 起的天数)
 * 精度约 0.1°（未含章动/岁差，suncalc 原实现亦然），对星图瞄准足够。
 */
object AstroNavSiderealTime {

    /** 格林尼治平恒星时（度，0..360）。 */
    fun gmstDegrees(epochMs: Long): Float {
        val days = SunCalc.toDays(epochMs)
        return AstroNavAngles.wrap360((280.16 + 360.9856235 * days).toFloat())
    }

    /** 本地平恒星时（度，0..360）：GMST + 东经为正的经度。 */
    fun lstDegrees(epochMs: Long, longitudeDegrees: Double): Float {
        val lon = if (longitudeDegrees.isFinite()) longitudeDegrees.toFloat() else 0f
        return AstroNavAngles.wrap360(gmstDegrees(epochMs) + lon)
    }

    /** 本地平恒星时（小时，0..24），可直接喂给 AstroProjection.equatorialToEnu。 */
    fun lstHours(epochMs: Long, longitudeDegrees: Double): Float = lstDegrees(epochMs, longitudeDegrees) / 15f

    /**
     * 既有 AstroOverlay 的「相对基准 + 手动偏移」口径 -> 真实 LST。
     *
     * 既有实现：lstHours = (manualOffsetDegrees + dragOffsetDeg) / 15f（基准恒为 0）。
     * 接线后：lstHours = 真实 LST + (manualOffsetDegrees + dragOffsetDeg) / 15f，
     * 即把「相对基准」升级为「真实基准」，其余交互语义（拖拽微调）保持不变。
     */
    fun lstHoursWithOffset(
        epochMs: Long,
        longitudeDegrees: Double,
        manualOffsetDegrees: Float,
        dragOffsetDegrees: Float,
    ): Float {
        val base = lstHours(epochMs, longitudeDegrees)
        val offset = ((safe(manualOffsetDegrees) + safe(dragOffsetDegrees)) / 15f)
        return (base + offset + 24f) % 24f
    }

    private fun safe(v: Float): Float = if (v.isFinite()) v else 0f
}

/**
 * 太阳 / 月亮 / 恒星 -> 可瞄准目标（与既有 astroplan 联动）。
 */
object AstroNavSunMoonLink {

    /**
     * 太阳目标（复用既有 SunCalc.getPosition，仅做方位角口径转换）。
     *
     * @param epochMs 时刻（epoch 毫秒）
     * @param latitudeDegrees 观测纬度
     * @param longitudeDegrees 观测经度（东正西负）
     */
    fun sunTarget(epochMs: Long, latitudeDegrees: Double, longitudeDegrees: Double): AstroNavSkyTarget {
        val pos = SunCalc.getPosition(epochMs, latitudeDegrees, longitudeDegrees)
        return AstroNavSkyTarget(
            id = "sun",
            nameZh = "太阳",
            azimuthDegrees = fromSunCalcAzimuth(pos.azimuthDegrees),
            altitudeDegrees = pos.altitudeDegrees.toFloat(),
            magnitude = -26.7f,
        )
    }

    /** 月亮目标（复用既有 SunCalc.getMoonPosition）。 */
    fun moonTarget(epochMs: Long, latitudeDegrees: Double, longitudeDegrees: Double): AstroNavSkyTarget {
        val pos = SunCalc.getMoonPosition(epochMs, latitudeDegrees, longitudeDegrees)
        return AstroNavSkyTarget(
            id = "moon",
            nameZh = "月亮",
            azimuthDegrees = fromSunCalcAzimuth(pos.azimuthDegrees),
            altitudeDegrees = pos.altitudeDegrees.toFloat(),
            magnitude = -12.7f,
        )
    }

    /** 月相光照比例（0..1，复用既有 SunCalc.getMoonIllumination），失败返回 0。 */
    fun moonIlluminationFraction(epochMs: Long): Float =
        runCatching { SunCalc.getMoonIllumination(epochMs).fraction.toFloat() }
            .getOrDefault(0f)
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }

    /**
     * 星表中的一颗星 -> 可瞄准目标（需要真实 LST；用 [AstroNavSiderealTime] 取）。
     *
     * @param starEnName 星表英文名（[StarCatalog.INDEX_BY_EN] 的键）
     * @param lstHours 本地平恒星时（小时）
     * @param latitudeDegrees 观测纬度
     * @return null = 星表里没有这个名字（不抛异常）
     */
    fun starTarget(
        starEnName: String,
        lstHours: Float,
        latitudeDegrees: Double,
    ): AstroNavSkyTarget? {
        // 既有 StarCatalog 的索引是 英文名 -> STARS 下标（Map<String, Int>）
        val index = StarCatalog.INDEX_BY_EN[starEnName] ?: return null
        val star = StarCatalog.STARS.getOrNull(index) ?: return null
        val enu = AstroProjection.equatorialToEnu(
            raHours = star.raHours,
            decDeg = star.decDeg,
            observerLatDeg = latitudeDegrees.toFloat(),
            lstHours = lstHours,
        )
        val pointing = CoordinateAligner.enuToPointing(AstroNavVec3.of(enu))
        return AstroNavSkyTarget(
            id = star.enName,
            nameZh = star.zhName,
            azimuthDegrees = pointing.azimuthDegrees,
            altitudeDegrees = pointing.altitudeDegrees,
            magnitude = star.magnitude,
        )
    }

    /**
     * 目标 -> 屏幕像素（与既有叠加层同一条投影链路，零额外约定）。
     *
     * @param attitudeTrueNorth 真北校正后的姿态
     * @param cameraAxis 取景相机
     * @param centerX / centerY / focalPx 画布参数
     * @param displayRotationDegrees 屏幕旋转
     * @return null = 目标在视野外/地平线下
     */
    fun screenPointOf(
        target: AstroNavSkyTarget,
        attitudeTrueNorth: AstroNavQuaternion,
        cameraAxis: CameraAxis,
        centerX: Float,
        centerY: Float,
        focalPx: Float,
        displayRotationDegrees: Int = 0,
    ): Pair<Float, Float>? {
        if (!target.aboveHorizon) return null
        val matrix = CoordinateAligner.worldToDeviceMatrix(attitudeTrueNorth)
        return CoordinateAligner.worldToScreen(
            rotationMatrix = matrix,
            worldEnu = target.toEnu().toFloatArray(),
            centerX = centerX,
            centerY = centerY,
            focalPx = focalPx,
            displayRotationDegrees = displayRotationDegrees,
        )
    }

    /**
     * 瞄准引导文案（三脚架场景：告诉用户「往哪边转、转多少」）。
     *
     * @param target 期望目标
     * @param pointing 当前指向
     * @return 中文短句，如「向西左转 42°，下俯 12°」
     */
    fun guideText(target: AstroNavSkyTarget, pointing: AstroNavPointing): String {
        if (!target.aboveHorizon) return "${target.nameZh}在地平线以下（${"%.0f".format(target.altitudeDegrees)}°）"
        val dAz = AstroNavAngles.delta(pointing.azimuthDegrees, target.azimuthDegrees)
        val dAlt = target.altitudeDegrees - pointing.altitudeDegrees
        if (Math.abs(dAz) < 3f && Math.abs(dAlt) < 3f) return "已对准${target.nameZh}"
        return buildString {
            if (Math.abs(dAz) >= 3f) {
                append(if (dAz > 0f) "向右" else "向左")
                append("转 ${"%.0f".format(Math.abs(dAz))}°")
            } else {
                append("方位已对准")
            }
            if (Math.abs(dAlt) >= 3f) {
                append("，")
                append(if (dAlt > 0f) "上仰" else "下俯")
                append(" ${"%.0f".format(Math.abs(dAlt))}°")
            }
        }
    }

    /** suncalc 方位角（从正南向西为正）-> 从真北顺时针。 */
    private fun fromSunCalcAzimuth(sunCalcAzimuthDegrees: Double): Float {
        val az = if (sunCalcAzimuthDegrees.isFinite()) sunCalcAzimuthDegrees.toFloat() else 0f
        return AstroNavAngles.wrap360(az + 180f)
    }
}
