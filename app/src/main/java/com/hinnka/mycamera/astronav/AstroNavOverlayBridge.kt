/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：与既有 AR 星图叠加层 ui/astro/AstroOverlay 的**接线桥**。
 *
 * ============================================================================
 *  零回归原则（硬约束）
 * ============================================================================
 *  既有 AstroOverlay 自己注册 TYPE_ROTATION_VECTOR（SENSOR_DELAY_UI）做低通出矩阵，
 *  本模块**不修改**它的任何渲染行为。桥只做两件事：
 *    1) 把 astronav 的高精度姿态矩阵「喂」给叠加层——**可选注入**：
 *       AstroOverlay 的补丁新增一个默认 null 的可选参数
 *       `matrixOverrideProvider: (() -> FloatArray?)? = null`；
 *       传 null（既有调用点）时逐行行为不变 -> 零回归；
 *       传 [AstroNavOverlayFeed.matrixOrNull] 时用 astronav 融合+真北校正后的矩阵。
 *       补丁详见 delivery/patches/astronav_patch.md 的 CODE_HOOKS 段。
 *    2) 把观测点纬度/屏幕旋转/FOV 等**状态字段**映射进 AstroOverlayState
 *       （[applyTo] 只改这三个字段，其它字段原样保留）。
 *
 *  ⚠ 双重计角陷阱：既有 AstroOverlay 会把 `manualOffsetDegrees` 折算成 lstHours 偏移。
 *  而本模块已经在姿态四元数上叠加了「磁偏角 + 手动偏角」。若两边都加，方位会偏两倍。
 *  因此 [lstOffsetDegreesFor] 默认返回 0（真北校正已在姿态里完成，叠加层无需再加）。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import com.hinnka.mycamera.ui.astro.AstroOverlayState

/**
 * 桥接输入（全部可空：任一为空即「不干预」，保持既有行为）。
 */
data class AstroNavOverlayInputs(
    /** 当前姿态（null = 未就绪）。 */
    val attitude: AstroNavAttitude? = null,
    /** 当前定位（null / 不可用 = 不覆盖既有纬度）。 */
    val location: AstroNavLocation? = null,
    /** 取景参数（null = 不覆盖既有 FOV / 旋转）。 */
    val viewport: AstroNavViewport? = null,
    /** 总开关（false 时桥完全透传，用于排障「回到既有传感器链路」）。 */
    val enabled: Boolean = true,
) {
    /** 是否真的可以干预姿态矩阵。 */
    val hasAttitude: Boolean get() = enabled && attitude != null

    companion object {
        /** 透传（不干预）。 */
        val passthrough = AstroNavOverlayInputs(enabled = false)
    }
}

/**
 * 状态映射（纯函数，可 JVM 单测）。
 */
object AstroNavOverlayBridge {

    /**
     * 把 astronav 的状态映射到既有 [AstroOverlayState]。
     *
     * 只改三个字段（且仅在数据可用时才改）：
     *  - `observerLatitudeDegrees` <- 真实纬度（定位成功时）
     *  - `displayRotationDegrees`  <- 屏幕旋转（viewport 提供时；自动吸附 0/90/180/270）
     *  - `horizontalFovDegrees`    <- 相机水平 FOV（viewport 提供且合法时，clamp 到 20..120）
     * 其余字段（visible / showConstellations / showLabels / manualOffsetDegrees /
     * showHorizon / showAltAzGrid / selectedTarget）**一律原样保留**。
     *
     * @param base 既有状态（UI 传进来的原始值）
     * @param inputs 桥接输入
     * @return 新的状态；[AstroNavOverlayInputs.enabled] = false 时直接返回 base
     */
    fun applyTo(base: AstroOverlayState, inputs: AstroNavOverlayInputs): AstroOverlayState {
        if (!inputs.enabled) return base
        var out = base

        val point = inputs.location?.point
        if (point != null && point.latitudeDegrees.isFinite()) {
            out = out.copy(observerLatitudeDegrees = point.latitudeDegrees.toFloat())
        }

        val vp = inputs.viewport
        if (vp != null) {
            out = out.copy(
                displayRotationDegrees = AstroNavConfig.snapDisplayRotation(vp.displayRotationDegrees),
                horizontalFovDegrees = clampFov(vp.horizontalFovDegrees, base.horizontalFovDegrees),
            )
        }
        return out
    }

    /**
     * 该给叠加层补多少「lstHours 角度偏移」。
     *
     * **默认 0**：磁偏角与手动偏角已经在姿态四元数里做过真北校正
     * （见 [CoordinateAligner.applyTrueNorth]），叠加层再折算一次会双倍偏转。
     * 仅当调用方显式把 [trueNorthAlreadyInAttitude] 传 false（例如只想要纯姿态、
     * 让叠加层自己扛校正）时才返回角度值。
     *
     * @param declinationDeg 磁偏角（度）
     * @param manualYawOffsetDeg 手动偏角（度）
     * @param trueNorthAlreadyInAttitude 真北校正是否已在姿态内完成（默认 true）
     */
    fun lstOffsetDegreesFor(
        declinationDeg: Float,
        manualYawOffsetDeg: Float,
        trueNorthAlreadyInAttitude: Boolean = true,
    ): Float {
        if (trueNorthAlreadyInAttitude) return 0f
        val d = if (declinationDeg.isFinite()) declinationDeg else 0f
        val m = if (manualYawOffsetDeg.isFinite()) manualYawOffsetDeg else 0f
        return (d + m).coerceIn(-360f, 360f)
    }

    /** 观测纬度兜底：定位不可用时保持叠加层既有默认（不写死 0，避免南半球反相）。 */
    fun observerLatitudeFor(
        location: AstroNavLocation?,
        fallback: Float = AstroOverlayState().observerLatitudeDegrees,
    ): Float {
        val lat = location?.point?.latitudeDegrees
        if (lat == null || !lat.isFinite()) return fallback
        return lat.toFloat().coerceIn(-90f, 90f)
    }

    /** FOV 合法性：非法/超界回落到 [fallback]（默认叠加层自己的 60°）。 */
    fun clampFov(candidate: Float, fallback: Float = 60f): Float {
        if (!candidate.isFinite()) return fallback.coerceIn(FOV_MIN, FOV_MAX)
        return candidate.coerceIn(FOV_MIN, FOV_MAX)
    }

    /** 水平 FOV 允许范围（度）：覆盖手机主摄~超广角的合理区间。 */
    const val FOV_MIN = 20f
    const val FOV_MAX = 120f
}

/**
 * 供 Compose 侧消费的运行时数据源（把 astronav 引擎/定位包成叠加层能用的几个取值）。
 *
 * 用法（补丁落点；参数为「值」而非函数，配合 Compose State 读值可自动重组重绘）：
 * ```
 * val controller = remember { AstroNavController(context) }
 * val ui by controller.state.collectAsState()
 * AstroOverlay(
 *     state = AstroNavOverlayBridge.applyTo(
 *         state,
 *         AstroNavOverlayInputs(
 *             attitude = ui.attitude,
 *             location = ui.location,
 *             viewport = viewport,
 *         ),
 *     ),
 *     matrixOverride = ui.attitude?.rotationMatrix,   // 传 null 即回到既有行为（零回归）
 * )
 * ```
 * 非 Compose / 自定义 UI 可直接复用 [AstroNavOverlayFeed]（它把同一份装配逻辑包好）。
 */
class AstroNavOverlayFeed(
    private val engine: AstroNavAttitudeEngine,
    private val locationProvider: AstroNavLocationProvider,
    private val configProvider: () -> AstroNavConfig = { AstroNavConfig() },
    private val viewportProvider: () -> AstroNavViewport? = { null },
) {

    /** 组装当前桥接输入（每帧调用，纯读取，无副作用）。 */
    fun inputs(): AstroNavOverlayInputs {
        val cfg = configProvider().clamped().config
        return AstroNavOverlayInputs(
            attitude = engine.attitude.value,
            location = locationProvider.state.value,
            viewport = viewportProvider() ?: AstroNavViewport(
                displayRotationDegrees = cfg.displayRotationDegrees,
                cameraAxis = cfg.cameraAxis,
                manualYawOffsetDegrees = cfg.manualOffsetDegrees,
            ),
            enabled = true,
        )
    }

    /**
     * 给 AstroOverlay 的矩阵（世界 -> 设备，与 SensorManager 同一公式与语义）。
     * 返回 null 表示「不要覆盖」——叠加层继续用它自己的旋转向量链路（零回归）。
     */
    fun matrixOrNull(): FloatArray? {
        val attitude = engine.attitude.value ?: return null
        if (!attitude.quaternion.isUsable) return null
        return CoordinateAligner.worldToDeviceMatrix(attitude.quaternion)
    }

    /** 供 UI 角标显示的一行精度摘要。 */
    fun caption(): String {
        val a = engine.attitude.value ?: return "姿态未就绪"
        return "精度 ${(a.qualityScore * 100).toInt()}%（${a.qualityText}）· 磁偏角 " +
            "${"%.2f".format(a.declinationDegrees)}° · ${a.pointing.compassName} " +
            "${"%.0f".format(a.pointing.altitudeDegrees)}°"
    }

    /** 是否需要提示用户画 8 字（UI 在角标位置弹提示）。 */
    fun needsCalibration(): Boolean = engine.attitude.value?.needsCalibration ?: false

    /** 校准提示文案（含进度）。 */
    fun calibrationHint(): String =
        engine.attitude.value?.calibrationHint ?: engine.calibrationTracker.hint
}
