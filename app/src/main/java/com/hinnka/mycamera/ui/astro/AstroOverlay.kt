/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发：Star Walk 2 风格星空叠加组件（ui/astro）。
 * 本文件为新增文件，对外契约（勿改名）：
 *   @Composable fun AstroOverlay(modifier, state, onVisibilityToggle)
 *   data class AstroOverlayState(visible, showConstellations, showLabels, manualOffsetDegrees)
 *
 * 场景定位（用户 2026-10-15 澄清）：
 *   天文拍摄主流程 = 三脚架固定长曝/堆栈。本 AR 叠加只做「瞄准辅助 / 找目标方位」：
 *   选一个主要目标（猎户座/北斗等），给出方位文案与转向箭头，帮用户把三脚架
 *   大致转到目标方向；对准架稳后即固定拍摄，不依赖手持姿态。
 *
 * 设计约束：
 *  - 纯 Kotlin + Jetpack Compose + SensorManager，零第三方库；
 *  - 只是预览层上的 Canvas 叠加，绝不参与拍摄管线、不写回像素、不降低画面清晰度；
 *  - 夜间模式配色：暗红/低亮度，保护暗适应；
 *  - 自包含：不引用 ui/camera 布局、不引用 CameraState（FOV 由 state 传入）。
 *  - 姿态/FOV/网格思路参考 Google 开源星图 Sky Map（stardroid-v2，Apache/GPL 参考），
 *    仅移植纯数学思路（RotationQuaternion 校验、CameraFov 短边公式、
 *    HorizonLayer / AltAzGridLayer 采样），未引入其代码与依赖。
 */
package com.hinnka.mycamera.ui.astro

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 星空叠加层状态。前四个字段为跨代理契约字段（名称/默认值勿改），
 * 其余为本组件自增字段。
 *
 * @property visible 叠加层是否可见（ASTRO 场景总开关）
 * @property showConstellations 是否绘制星座连线
 * @property showLabels 是否绘制亮星中文名标签
 * @property manualOffsetDegrees 手动方向微调（度）：由界面层或拖拽写入，
 *           用于补偿传感器误差/地点对齐（正方向 = 顺时针转动手机）
 * @property observerLatitudeDegrees 观测点纬度（度，北正南负），默认北半球中纬度 35°
 * @property horizontalFovDegrees 预览水平视场角（度）。界面层可按
 *           camera/CameraState.kt 的 zoomRatio 与镜头焦距换算后传入
 *           （例如主摄 1x 约 60°，2x 约 32°）；读不到时用默认 60°
 * @property displayRotationDegrees 屏幕显示旋转（0/90/180/270），默认 0
 * @property showHorizon 是否绘制地平线与四方位（瞄准辅助，默认开）
 * @property showAltAzGrid 是否绘制 Alt/Az 地平网格（瞄准辅助，默认关）
 * @property selectedTarget 目标中文名（星座名如「猎户座」「北斗七星」或亮星名），
 *           非空时在屏幕上给出方位文案 + 转向箭头，帮三脚架找目标方位
 */
data class AstroOverlayState(
    val visible: Boolean = true,
    val showConstellations: Boolean = true,
    val showLabels: Boolean = true,
    val manualOffsetDegrees: Float = 0f,
    val observerLatitudeDegrees: Float = 35f,
    val horizontalFovDegrees: Float = 60f,
    val displayRotationDegrees: Int = 0,
    val showHorizon: Boolean = true,
    val showAltAzGrid: Boolean = false,
    val selectedTarget: String? = null,
)

/** 夜间模式（暗适应保护）配色：全部为低亮度暖红/暗红，避免冷白高光。 */
private object AstroNightPalette {
    val star: Color = Color(0xFFE09A8C)          // 星点：暗暖红
    val line: Color = Color(0xFF8A3A3A)           // 星座连线：暗红
    val label: Color = Color(0xFFC98A77)          // 星名标签：灰红
    val horizon: Color = Color(0xFF7A4040)        // 地平线
    val grid: Color = Color(0xFF552929)           // Alt/Az 网格
    val target: Color = Color(0xFFFFC9A8)          // 目标瞄准箭头：亮暖红
    val chipBg: Color = Color(0xB31A0D0D)          // 角标/提示底：近黑半透明
    val chipText: Color = Color(0xFFD9A08C)       // 角标字：暖红
}

/** 16 方位罗盘中文名（0=北，顺时针每 22.5° 一档）。 */
private val COMPASS_16 = listOf(
    "北", "东北偏北", "东北", "东北偏东",
    "东", "东南偏东", "东南", "东南偏南",
    "南", "西南偏南", "西南", "西南偏西",
    "西", "西北偏西", "西北", "西北偏北",
)

private fun compassName(azDeg: Float): String {
    val idx = (((azDeg + 11.25f) / 22.5f).roundToInt()) % 16
    return COMPASS_16[(idx + 16) % 16]
}

/** 低通滤波：stardroid 用 OneEuroQuaternionSmoother 平滑姿态，这里用轻量矩阵 lerp 近似。 */
private fun lowPassMatrix(prev: FloatArray, new: FloatArray, alpha: Float): FloatArray =
    FloatArray(9) { i -> prev[i] * (1f - alpha) + new[i] * alpha }

/**
 * Star Walk 2 / Sky Map 风格星空叠加层（瞄准辅助版）。
 *
 * 渲染链路：SensorManager 旋转向量（四元数校验 + 低通平滑）-> 设备坐标系 ->
 * 把星表每颗星 / 地平线 / Alt-Az 网格 / 目标方位箭头经 [AstroProjection] 投影到画布。
 *
 * @param state 叠加层状态
 * @param onVisibilityToggle 可见性变化回调（点击角标时触发），可空
 */
@Composable
fun AstroOverlay(
    modifier: Modifier = Modifier,
    state: AstroOverlayState,
    onVisibilityToggle: ((Boolean) -> Unit)? = null,
    /**
     * astronav 注入的「世界 -> 设备」旋转矩阵（行主序 9 元素，含真北校正）。
     * null（既有调用点的默认值）= 继续用本文件内置的 TYPE_ROTATION_VECTOR 链路，
     * 逐行行为不变（零回归）。
     */
    matrixOverride: FloatArray? = null,
) {    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

    // 旋转矩阵（设备坐标系）；传感器未就绪前为 null，回退到单位矩阵。
    var rotationMatrix by remember { mutableStateOf<FloatArray?>(null) }

    DisposableEffect(context) {
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                // stardroid RotationQuaternion.isUsableQuaternion：
                // 拒绝全零/NaN/非单位四元数样本（其 issue #1109）
                if (!AstroProjection.isUsableQuaternion(event.values)) return
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val prev = rotationMatrix
                // 低通平滑，抑制姿态抖动（stardroid OneEuro 的轻量替代）
                rotationMatrix = if (prev == null) r else lowPassMatrix(prev, r, 0.6f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) {
            sm.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_UI,
            )
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    // 拖拽平移星空（传感器有误差，允许用户手动把目标对准真实方向）。
    var dragOffsetDeg by remember { mutableStateOf(0f) }
    val screenWidthPx = with(density) {
        view.context.resources.displayMetrics.widthPixels.toFloat()
    }
    val degPerPx = state.horizontalFovDegrees / screenWidthPx.coerceAtLeast(1f)

    val measurer = rememberTextMeasurer()

    Box(modifier = modifier.safeDrawingPadding()) {
        if (state.visible) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(state.horizontalFovDegrees) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // 向右拖：画面中的星空向左移 -> 等效于 LST 减小
                            dragOffsetDeg -= dragAmount.x * degPerPx
                        }
                    },
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val focalPx = AstroProjection.focalPxForHfov(
                    size.width, state.horizontalFovDegrees,
                )
                // astronav 注入优先；未注入（null）时与既有行为逐行等价。
                val r = matrixOverride?.takeIf { it.size == 9 }
                    ?: rotationMatrix
                    ?: FloatArray(9) { i ->
                        if (i % 4 == 0) 1f else 0f // 单位矩阵回退
                    }                val lstHours = (state.manualOffsetDegrees + dragOffsetDeg) / 15f
                val lat = state.observerLatitudeDegrees
                val rotDeg = -state.displayRotationDegrees.toFloat()

                // 通用：世界 ENU 向量 -> 画布像素（含显示旋转），不在视野内返回 null
                fun projectWorld(world: FloatArray): Offset? {
                    val dev = AstroProjection.rotateWorldToDevice(r, world)
                    val raw = AstroProjection.deviceToScreen(dev, cx, cy, focalPx)
                        ?: return null
                    val (x, y) = AstroProjection.rotateAroundCenter(
                        raw.first, raw.second, cx, cy, rotDeg,
                    )
                    if (x < -60f || x > size.width + 60f || y < -60f || y > size.height + 60f) {
                        return null
                    }
                    return Offset(x, y)
                }

                // ---- 1) 地平线 + 四方位（stardroid HorizonLayer 思路）----
                if (state.showHorizon) {
                    val horizonPts = (0..360 step 5).map { az ->
                        projectWorld(AstroProjection.altAzToEnu(0f, az.toFloat()))
                    }
                    for (i in 0 until horizonPts.size - 1) {
                        val a = horizonPts[i]
                        val b = horizonPts[i + 1]
                        if (a != null && b != null) {
                            drawLine(AstroNightPalette.horizon, a, b, 1.5f)
                        }
                    }
                    // 四方位小标签
                    listOf("北" to 0f, "东" to 90f, "南" to 180f, "西" to 270f).forEach { (name, az) ->
                        val p = projectWorld(AstroProjection.altAzToEnu(4f, az)) ?: return@forEach
                        val layout = measurer.measure(
                            name,
                            style = TextStyle(color = AstroNightPalette.label, fontSize = 9.sp),
                        )
                        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y))
                    }
                }

                // ---- 2) Alt/Az 地平网格（stardroid AltAzGridLayer 思路，默认关）----
                if (state.showAltAzGrid) {
                    listOf(30f, 60f).forEach { alt ->
                        val pts = (0..360 step 10).map { az ->
                            projectWorld(AstroProjection.altAzToEnu(alt, az.toFloat()))
                        }
                        for (i in 0 until pts.size - 1) {
                            val a = pts[i]; val b = pts[i + 1]
                            if (a != null && b != null) {
                                drawLine(AstroNightPalette.grid, a, b, 0.8f)
                            }
                        }
                    }
                    (0..360 step 30).forEach { az ->
                        val pts = (0..90 step 10).map { alt ->
                            projectWorld(AstroProjection.altAzToEnu(alt.toFloat(), az.toFloat()))
                        }
                        for (i in 0 until pts.size - 1) {
                            val a = pts[i]; val b = pts[i + 1]
                            if (a != null && b != null) {
                                drawLine(AstroNightPalette.grid, a, b, 0.6f)
                            }
                        }
                    }
                }

                // ---- 3) 星座连线（CatalogLayer 思路）----
                // 先投影所有星点
                val positions = arrayOfNulls<Offset>(StarCatalog.STARS.size)
                StarCatalog.STARS.forEachIndexed { i, star ->
                    val world = AstroProjection.equatorialToEnu(
                        star.raHours, star.decDeg, lat, lstHours,
                    )
                    positions[i] = projectWorld(world)
                }
                if (state.showConstellations) {
                    StarCatalog.CONSTELLATIONS.forEach { con ->
                        con.lines.forEach { (a, b) ->
                            val pa = StarCatalog.INDEX_BY_EN[a]?.let { positions[it] }
                            val pb = StarCatalog.INDEX_BY_EN[b]?.let { positions[it] }
                            if (pa != null && pb != null) {
                                drawLine(AstroNightPalette.line, pa, pb, 1.2f)
                            }
                        }
                    }
                }

                // ---- 4) 星点 ----
                StarCatalog.STARS.forEachIndexed { i, star ->
                    val p = positions[i] ?: return@forEachIndexed
                    val radius = (5.2f - star.magnitude).coerceIn(1.0f, 5.0f)
                    drawCircle(
                        color = AstroNightPalette.star.copy(
                            alpha = if (star.magnitude < 2f) 0.95f else 0.6f,
                        ),
                        radius = radius,
                        center = p,
                    )
                }

                // ---- 5) 亮星中文名标签 ----
                if (state.showLabels) {
                    StarCatalog.STARS.forEachIndexed { i, star ->
                        val p = positions[i] ?: return@forEachIndexed
                        if (star.magnitude > 2.2f) return@forEachIndexed
                        val layout = measurer.measure(
                            text = star.zhName,
                            style = TextStyle(
                                color = AstroNightPalette.label,
                                fontSize = 10.sp,
                            ),
                        )
                        drawText(
                            layout,
                            topLeft = Offset(p.x + 8f, p.y - layout.size.height / 2f),
                        )
                    }
                }

                // ---- 6) 目标方位引导（三脚架「找目标」辅助）----
                state.selectedTarget?.let { targetName ->
                    // 目标质心 ENU：优先按星座名找成员星，再按亮星名
                    val memberStars = StarCatalog.CONSTELLATIONS.find { it.zhName == targetName }
                        ?.let { con ->
                            con.lines.flatMap { listOf(it.first, it.second) }
                                .distinct()
                                .mapNotNull { StarCatalog.INDEX_BY_EN[it] }
                        }
                        ?: StarCatalog.STARS.indexOfFirst { it.zhName == targetName }
                            .takeIf { it >= 0 }?.let { listOf(it) }

                    if (memberStars != null && memberStars.isNotEmpty()) {
                        // 平均质心 ENU 向量
                        val centroid = floatArrayOf(0f, 0f, 0f)
                        memberStars.forEach { idx ->
                            val s = StarCatalog.STARS[idx]
                            val w = AstroProjection.equatorialToEnu(s.raHours, s.decDeg, lat, lstHours)
                            centroid[0] += w[0]; centroid[1] += w[1]; centroid[2] += w[2]
                        }
                        val n = memberStars.size.toFloat()
                        centroid[0] /= n; centroid[1] /= n; centroid[2] /= n
                        val norm = kotlin.math.sqrt(
                            centroid[0] * centroid[0] + centroid[1] * centroid[1] +
                                centroid[2] * centroid[2],
                        ).coerceAtLeast(1e-6f)
                        centroid[0] /= norm; centroid[1] /= norm; centroid[2] /= norm

                        // 目标地平坐标（alt/az）用于文案
                        val altDeg = Math.toDegrees(kotlin.math.asin(centroid[2].toDouble())).toFloat()
                        var azDeg = Math.toDegrees(
                            atan2(centroid[0], centroid[1]).toDouble(),
                        ).toFloat()
                        if (azDeg < 0f) azDeg += 360f
                        val altWord = when {
                            altDeg < 15f -> "低空"
                            altDeg < 45f -> "中低空"
                            altDeg < 70f -> "中高空"
                            else -> "近天顶"
                        }

                        // 相对相机的水平/垂直偏角（度）
                        val dev = AstroProjection.rotateWorldToDevice(r, centroid)
                        val horizErrDeg = Math.toDegrees(
                            atan2(dev[0].toDouble(), -dev[2].toDouble()),
                        ).toFloat()
                        val vertErrDeg = Math.toDegrees(
                            atan2(dev[1].toDouble(), -dev[2].toDouble()),
                        ).toFloat()
                        val onScreen = dev[2] < -0.05f &&
                            abs(horizErrDeg) <= state.horizontalFovDegrees / 2f &&
                            abs(vertErrDeg) <= state.horizontalFovDegrees / 2f * (size.height / size.width)

                        val hint = when {
                            dev[2] >= -0.05f -> "$targetName 在身后：转 180°"
                            onScreen -> "$targetName：就在画面中，架稳三脚架"
                            horizErrDeg > 12f -> "向右转 ${{ horizErrDeg.toInt() }}°"
                            horizErrDeg < -12f -> "向左转 ${(-horizErrDeg).toInt()}°"
                            vertErrDeg > 10f -> "抬头 ${vertErrDeg.toInt()}°"
                            vertErrDeg < -10f -> "低头 ${(-vertErrDeg).toInt()}°"
                            else -> "$targetName：微调对准"
                        }
                        val fullHint = "$targetName · ${compassName(azDeg)} · $altWord ｜ $hint"

                        // 屏幕中若目标在视野内：画一个瞄准圈
                        if (onScreen) {
                            val p = projectWorld(centroid)
                            if (p != null) {
                                drawCircle(
                                    color = AstroNightPalette.target,
                                    radius = 18f,
                                    center = p,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
                                )
                                drawCircle(
                                    color = AstroNightPalette.target,
                                    radius = 2.5f,
                                    center = p,
                                )
                            }
                        }

                        // 底部方位提示条
                        val layout = measurer.measure(
                            fullHint,
                            style = TextStyle(
                                color = AstroNightPalette.chipText,
                                fontSize = 11.sp,
                            ),
                        )
                        drawText(
                            layout,
                            topLeft = Offset(
                                (size.width - layout.size.width) / 2f,
                                size.height - 90f,
                            ),
                        )
                    }
                }
            }

            // 角标：ASTRO 星空瞄准辅助 + 点击触发可见性切换
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(AstroNightPalette.chipBg)
                    .clickable(enabled = onVisibilityToggle != null) {
                        onVisibilityToggle?.invoke(!state.visible)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "ASTRO · 星空瞄准",
                    color = AstroNightPalette.chipText,
                    fontSize = 10.sp,
                )
            }
        } else {
            // 不可见时保留一个小角标，方便用户重新打开
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(AstroNightPalette.chipBg)
                    .clickable(enabled = onVisibilityToggle != null) {
                        onVisibilityToggle?.invoke(!state.visible)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "ASTRO · 星空已隐藏",
                    color = AstroNightPalette.chipText,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
