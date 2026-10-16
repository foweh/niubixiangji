/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）单元测试。
 * 覆盖：**坐标系对齐与既有 AR 叠加的零回归接线**——状态映射只改三个字段、
 *       关闭总开关时严格透传（同一实例）、真北校正不双重计角（lst 偏移默认 0）、
 *       观测纬度兜底、FOV/屏幕旋转合法性、姿态可用性判定、
 *       以及「手动/GPS 定位 -> 叠加层纬度」的端到端纯逻辑串联。
 *
 * 说明：被测的 [AstroNavOverlayBridge] 与其依赖的 [AstroOverlayState] 均为纯 Kotlin
 * （后者是 AstroOverlay.kt 中的 data class，字段全为基本类型），可在 JVM 单测中直接实例化；
 * 含 Android 依赖的 AstroNavOverlayFeed 不在本测试覆盖范围内。
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.ui.astro.AstroOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroNavOverlayBridgeTest {

    private fun baseState() = AstroOverlayState(
        visible = true,
        showConstellations = false,
        showLabels = false,
        manualOffsetDegrees = 12.5f,
        observerLatitudeDegrees = 35f,
        horizontalFovDegrees = 60f,
        displayRotationDegrees = 0,
        showHorizon = false,
        showAltAzGrid = true,
        selectedTarget = "猎户座",
    )

    private fun stubAttitude(): AstroNavAttitude {
        val q = AstroNavQuaternion.IDENTITY
        return AstroNavAttitude(
            quaternion = q,
            magneticQuaternion = q,
            pointing = AstroNavPointing(0f, 0f),
            qualityScore = 1f,
            qualityText = "精度优秀",
            calibrationProgress = 1f,
            calibrationHint = "",
            needsCalibration = false,
            declinationDegrees = 0f,
            declinationNote = "",
            declinationSource = DeclinationSource.EMBEDDED_WMM,
            magAccuracy = 3,
            sampleRateHz = 100f,
            fusionGain = 0.5f,
            gyroOverRange = false,
            magneticDisturbed = false,
            usingRotationVectorFallback = false,
            timestampMs = 0L,
        )
    }

    // ---------------- 零回归：透传 ----------------

    @Test
    fun disabled_inputs_return_the_base_instance_untouched() {
        val base = baseState()
        val out = AstroNavOverlayBridge.applyTo(
            base,
            AstroNavOverlayInputs(
                attitude = stubAttitude(),
                location = AstroNavLocation.fixed(GeoPoint.of(43.8378, 126.5495), LocationSource.GPS, 3f, 1L),
                viewport = AstroNavViewport(displayRotationDegrees = 90, horizontalFovDegrees = 100f),
                enabled = false,
            ),
        )
        // 严格透传：同一实例（说明没有 copy，行为逐行不变）
        assertSame(base, out)
        assertEquals(false, AstroNavOverlayInputs.passthrough.enabled)
        assertFalse(AstroNavOverlayInputs.passthrough.hasAttitude)
    }

    @Test
    fun empty_inputs_leave_state_identical() {
        val base = baseState()
        // 全部可空且未提供 -> 不干预
        assertSame(base, AstroNavOverlayBridge.applyTo(base, AstroNavOverlayInputs()))
        // 有位置但无取景参数：只改纬度
        val withLocation = AstroNavOverlayBridge.applyTo(
            base,
            AstroNavOverlayInputs(location = AstroNavLocation.fixed(GeoPoint.of(43.8378, 126.5495), LocationSource.GPS, 3f, 1L)),
        )
        assertEquals(43.8378f, withLocation.observerLatitudeDegrees, 1e-3f)
        assertEquals(base.horizontalFovDegrees, withLocation.horizontalFovDegrees, 0f)
        assertEquals(base.displayRotationDegrees, withLocation.displayRotationDegrees)
    }

    // ---------------- 只改三个字段 ----------------

    @Test
    fun only_latitude_rotation_and_fov_are_overwritten() {
        val base = baseState()
        val out = AstroNavOverlayBridge.applyTo(
            base,
            AstroNavOverlayInputs(
                location = AstroNavLocation.fixed(GeoPoint.of(43.8378, 126.5495), LocationSource.GPS, 3f, 1L),
                viewport = AstroNavViewport(
                    displayRotationDegrees = 90,
                    cameraAxis = CameraAxis.BACK,
                    horizontalFovDegrees = 75f,
                ),
            ),
        )
        // 被改的三个字段
        assertEquals(43.8378f, out.observerLatitudeDegrees, 1e-3f)
        assertEquals(90, out.displayRotationDegrees)
        assertEquals(75f, out.horizontalFovDegrees, 0f)
        // 其余字段一律原样保留（含 manualOffsetDegrees 与 selectedTarget）
        assertEquals(base.visible, out.visible)
        assertEquals(base.showConstellations, out.showConstellations)
        assertEquals(base.showLabels, out.showLabels)
        assertEquals(base.manualOffsetDegrees, out.manualOffsetDegrees, 0f)
        assertEquals(base.showHorizon, out.showHorizon)
        assertEquals(base.showAltAzGrid, out.showAltAzGrid)
        assertEquals(base.selectedTarget, out.selectedTarget)
        assertEquals("猎户座", out.selectedTarget)
    }

    @Test
    fun invalid_location_never_overrides_latitude() {
        val base = baseState()
        // 未定位
        assertSame(
            base,
            AstroNavOverlayBridge.applyTo(base, AstroNavOverlayInputs(location = AstroNavLocation.idle())),
        )
        // 定位失败
        assertSame(
            base,
            AstroNavOverlayBridge.applyTo(base, AstroNavOverlayInputs(location = AstroNavLocation.failed("超时"))),
        )
        // 有 point 但纬度非有限
        val nanLocation = AstroNavLocation(
            status = LocationStatus.FIXED,
            point = GeoPoint(Double.NaN, 116.4074),
            source = LocationSource.GPS,
            message = "坏数据",
        )
        assertSame(
            base,
            AstroNavOverlayBridge.applyTo(base, AstroNavOverlayInputs(location = nanLocation)),
        )
    }

    @Test
    fun rotation_is_snapped_and_fov_is_clamped_into_range() {
        val base = baseState()
        val out = AstroNavOverlayBridge.applyTo(
            base,
            AstroNavOverlayInputs(
                viewport = AstroNavViewport(
                    displayRotationDegrees = 999,
                    horizontalFovDegrees = 500f,
                ),
            ),
        )
        assertTrue(out.displayRotationDegrees in AstroNavConfig.DISPLAY_ROTATIONS.toList())
        assertEquals(270, out.displayRotationDegrees)
        assertEquals(AstroNavOverlayBridge.FOV_MAX, out.horizontalFovDegrees, 0f)

        // 非法 FOV -> 回落叠加层自己的值（不是写死 60）
        val wideBase = baseState().copy(horizontalFovDegrees = 88f)
        val nanFov = AstroNavOverlayBridge.applyTo(
            wideBase,
            AstroNavOverlayInputs(viewport = AstroNavViewport(horizontalFovDegrees = Float.NaN)),
        )
        assertEquals(88f, nanFov.horizontalFovDegrees, 0f)
    }

    @Test
    fun clamp_fov_handles_garbage_and_extremes() {
        assertEquals(60f, AstroNavOverlayBridge.clampFov(60f), 0f)
        assertEquals(AstroNavOverlayBridge.FOV_MAX, AstroNavOverlayBridge.clampFov(150f), 0f)
        assertEquals(AstroNavOverlayBridge.FOV_MIN, AstroNavOverlayBridge.clampFov(5f), 0f)
        // 非有限 -> 用 fallback
        assertEquals(60f, AstroNavOverlayBridge.clampFov(Float.NaN), 0f)
        assertEquals(60f, AstroNavOverlayBridge.clampFov(Float.POSITIVE_INFINITY), 0f)
        assertEquals(45f, AstroNavOverlayBridge.clampFov(Float.NaN, fallback = 45f), 0f)
        // fallback 自身也要 clamp
        assertEquals(AstroNavOverlayBridge.FOV_MAX, AstroNavOverlayBridge.clampFov(Float.NaN, fallback = 999f), 0f)
        assertEquals(AstroNavOverlayBridge.FOV_MIN, AstroNavOverlayBridge.clampFov(0f, fallback = 1f), 0f)
        assertTrue(AstroNavOverlayBridge.FOV_MIN < AstroNavOverlayBridge.FOV_MAX)
    }

    // ---------------- 双重计角保护 ----------------

    @Test
    fun lst_offset_defaults_to_zero_to_avoid_double_true_north_correction() {
        // 真北校正已在姿态四元数内完成 -> 叠加层再折算会双倍偏转
        assertEquals(0f, AstroNavOverlayBridge.lstOffsetDegreesFor(10f, 5f), 0f)
        assertEquals(0f, AstroNavOverlayBridge.lstOffsetDegreesFor(0f, 0f), 0f)
        assertEquals(0f, AstroNavOverlayBridge.lstOffsetDegreesFor(Float.NaN, Float.NaN), 0f)

        // 只有显式声明「姿态内没做校正」时才返回角度
        assertEquals(15f, AstroNavOverlayBridge.lstOffsetDegreesFor(10f, 5f, trueNorthAlreadyInAttitude = false), 0f)
        assertEquals(-7.5f, AstroNavOverlayBridge.lstOffsetDegreesFor(-7.5f, 0f, false), 0f)
        // 非有限角度按 0 处理，且总角受限
        assertEquals(0f, AstroNavOverlayBridge.lstOffsetDegreesFor(Float.NaN, Float.NaN, false), 0f)
        assertEquals(360f, AstroNavOverlayBridge.lstOffsetDegreesFor(1e9f, 1e9f, false), 0f)
        assertEquals(-360f, AstroNavOverlayBridge.lstOffsetDegreesFor(-1e9f, -1e9f, false), 0f)
    }

    // ---------------- 纬度兜底 ----------------

    @Test
    fun observer_latitude_fallback_matches_overlay_default() {
        // 默认兜底 = 既有 AstroOverlayState 的默认纬度 35°（不写死 0）
        assertEquals(35f, AstroNavOverlayBridge.observerLatitudeFor(null), 0f)
        assertEquals(AstroOverlayState().observerLatitudeDegrees, AstroNavOverlayBridge.observerLatitudeFor(null), 0f)
        assertEquals(-12f, AstroNavOverlayBridge.observerLatitudeFor(null, fallback = -12f), 0f)

        val loc = AstroNavLocation.fixed(GeoPoint.of(43.8378, 126.5495), LocationSource.GPS, 5f, 1L)
        assertEquals(43.8378f, AstroNavOverlayBridge.observerLatitudeFor(loc), 1e-3f)
        // 未定位 / 非有限 / 越界 -> 兜底与 clamp
        assertEquals(35f, AstroNavOverlayBridge.observerLatitudeFor(AstroNavLocation.idle()), 0f)
        assertEquals(
            -12f,
            AstroNavOverlayBridge.observerLatitudeFor(
                AstroNavLocation(
                    LocationStatus.FIXED, GeoPoint(Double.NaN, 0.0), LocationSource.GPS, "x",
                ),
                fallback = -12f,
            ),
            0f,
        )
        assertEquals(
            90f,
            AstroNavOverlayBridge.observerLatitudeFor(
                AstroNavLocation(
                    LocationStatus.FIXED, GeoPoint(95.0, 0.0), LocationSource.GPS, "x",
                ),
            ),
            0f,
        )
    }

    // ---------------- 姿态可用性 ----------------

    @Test
    fun has_attitude_requires_both_switch_and_payload() {
        assertFalse(AstroNavOverlayInputs().hasAttitude)
        assertFalse(AstroNavOverlayInputs(attitude = stubAttitude(), enabled = false).hasAttitude)
        assertTrue(AstroNavOverlayInputs(attitude = stubAttitude()).hasAttitude)
        assertTrue(AstroNavOverlayInputs(attitude = stubAttitude(), enabled = true).hasAttitude)
        // 只有位置没有姿态 -> 不能干预矩阵（只能改状态字段）
        assertFalse(
            AstroNavOverlayInputs(location = AstroNavLocation.idle(), viewport = AstroNavViewport()).hasAttitude,
        )
    }

    // ---------------- 端到端纯逻辑串联 ----------------

    @Test
    fun manual_location_flows_into_overlay_latitude() {
        // 手动兜底（离线城市搜索：吉林市）-> 桥 -> 叠加层观测纬度
        val manual = ManualLocationResolver().resolvePlaceName("吉林")
        assertTrue(manual.isSuccess)

        val out = AstroNavOverlayBridge.applyTo(
            baseState(),
            AstroNavOverlayInputs(
                location = manual.location,
                viewport = AstroNavViewport(horizontalFovDegrees = 62f),
            ),
        )
        assertEquals(43.8378f, out.observerLatitudeDegrees, 0.01f)
        assertEquals(62f, out.horizontalFovDegrees, 0f)
        // 桥不改变手动偏角字段（真北校正由姿态负责）
        assertEquals(12.5f, out.manualOffsetDegrees, 0f)

        // 手动经纬度输入同样生效（输入即兜底路径的第三种入口）
        val byCoords = ManualLocationResolver().resolveCoordinates("43.8378, 126.5495")
        val out2 = AstroNavOverlayBridge.applyTo(baseState(), AstroNavOverlayInputs(location = byCoords.location))
        assertEquals(out.observerLatitudeDegrees, out2.observerLatitudeDegrees, 1e-3f)
    }
}
