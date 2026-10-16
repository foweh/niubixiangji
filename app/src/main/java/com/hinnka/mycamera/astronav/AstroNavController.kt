/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：模块总控（把定位、姿态融合、磁偏角、手动兜底、离线精修串起来）。
 *
 * 生命周期（补丁在星空模式入口调用）：
 *   controller.enable()   // 进入星空模式：开始定位 + 开始姿态解算
 *   ...
 *   controller.disable()  // 退出星空模式：停止定位 + 注销传感器（务必调用，避免耗电）
 *
 * 线程/性能（PATTERN_SPEC §1.5）：
 *  - 传感器前台回调节流直跑（见 [AstroNavAttitudeEngine] 文件头取舍说明）；
 *  - 定位回调在主 Looper（系统行为），只做 O(1) 赋值与状态发布；
 *  - 磁偏角在线更新走 IO 调度器（网络）；
 *  - 姿态序列离线精修走 [BgJobRunner] 低优先（[AstroNavBackgroundSolver]）。
 */
package com.hinnka.mycamera.astronav

import android.content.Context
import com.hinnka.mycamera.aiplatform.AiConfigStore
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块对 UI 暴露的完整状态（补丁把它喂给状态提示条 / 手动定位面板 / 校准提示）。
 *
 * @param enabled 模块是否开启
 * @param location 定位状态（含手动兜底）
 * @param attitude 姿态快照（未就绪为 null）
 * @param sensors 传感器可用性
 * @param clampCorrected 启动时被 clamp 修正的配置字段名（空 = 配置干净）
 * @param statusText 一行中文状态摘要
 * @param needsCalibration 是否需要提示画 8 字
 * @param calibrationHint 校准提示文案
 * @param refinementInFlight 是否有离线精修任务在跑
 */
data class AstroNavUiState(
    val enabled: Boolean = false,
    val location: AstroNavLocation = AstroNavLocation.idle(),
    val attitude: AstroNavAttitude? = null,
    val sensors: AstroNavSensorAvailability = AstroNavSensorAvailability(false, false, false, false),
    val clampCorrected: List<String> = emptyList(),
    val statusText: String = "星空定位未开启",
    val needsCalibration: Boolean = false,
    val calibrationHint: String = "",
    val refinementInFlight: Boolean = false,
)

/**
 * 星空定位与传感器融合总控。
 *
 * @param context 任意 Context（内部只取 applicationContext）
 * @param configProvider 取当前配置（UI/DataStore 注入；每次 enable 读取并 clamp）
 * @param configStore AI 平台配置仓库（可为 null：在线磁偏角退化为只用示例端点）
 * @param scope 协程作用域（默认自建，[release] 时取消）
 */
class AstroNavController(
    context: Context,
    private val configProvider: () -> AstroNavConfig = { AstroNavConfig() },
    private val configStore: AiConfigStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val appContext = context.applicationContext

    /** 定位（GPS > 网络，超时回落上次已知位置，可手动兜底）。 */
    val locationProvider: AstroNavLocationProvider = AstroNavLocationProvider(appContext, configProvider)

    /** 手动兜底解析器（经纬度文本 / 城市名）。 */
    val manualResolver: ManualLocationResolver = ManualLocationResolver()

    /** 磁偏角（内嵌 WMM2025 + 可在线更新 + 可手动）。 */
    val declinationProvider: MagneticDeclinationProvider = MagneticDeclinationProvider(configProvider)

    /** 姿态引擎（陀螺 + 加速度 + 磁力 -> 真北对齐四元数）。 */
    val attitudeEngine: AstroNavAttitudeEngine = AstroNavAttitudeEngine(
        context = appContext,
        configProvider = configProvider,
        locationSupplier = { locationProvider.state.value },
        declinationProvider = declinationProvider,
    )

    /** 在线磁偏角更新器（端点/密钥走 AiConfigStore，不硬编码）。 */
    private val declinationUpdater = DeclinationOnlineUpdater(configStore)

    private val _state = MutableStateFlow(AstroNavUiState())
    val state: StateFlow<AstroNavUiState> = _state.asStateFlow()

    private var collector: Job? = null
    private var enabled = false

    /** 记录的姿态样本（用于离线精修；容量由配置决定，不做硬上限丢弃）。 */
    private val recordedQuaternions = ArrayList<Float>(256 * 4)
    private val recordedTimestamps = ArrayList<Long>(256)

    /** 进入星空模式。 */
    fun enable() {
        if (enabled) return
        enabled = true
        val report = configProvider().clamped()
        if (!report.isClean) {
            PLog.i(TAG, "astro nav config clamped: ${report.corrected}")
        }
        locationProvider.start()
        attitudeEngine.start()
        startCollecting(report.corrected)
        _state.value = _state.value.copy(enabled = true, clampCorrected = report.corrected)
    }

    /** 退出星空模式（释放传感器与定位）。 */
    fun disable() {
        if (!enabled) return
        enabled = false
        collector?.cancel()
        collector = null
        attitudeEngine.stop()
        locationProvider.stop()
        _state.value = _state.value.copy(enabled = false, statusText = "星空定位未开启")
    }

    /** 彻底释放（页面销毁时调用）。 */
    fun release() {
        disable()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 手动兜底（定位失败/精度不足的第二路径）
    // ------------------------------------------------------------------

    /** 手动输入经纬度（如 "39.9042, 116.4074" 或 "39°54'15\"N 116°24'27\"E"）。 */
    fun applyManualCoordinates(text: String?, altitudeMeters: Double = Double.NaN): ManualLocationResult {
        val alt = if (altitudeMeters.isFinite()) altitudeMeters else configProvider().clamped().config.altitudeMeters
        val result = manualResolver.resolveCoordinates(text, alt)
        if (result.isSuccess) locationProvider.applyManual(result)
        return result
    }

    /** 搜索离线城市表（返回候选列表，UI 可做下拉）。 */
    fun searchPlaces(query: String): List<PlaceEntry> = OfflinePlaceCatalog.search(query)

    /** 采用某个离线地点。 */
    fun usePlace(entry: PlaceEntry): ManualLocationResult {
        val location = manualResolver.resolvePlace(entry)
        val result = ManualLocationResult.ok(location, listOf(entry))
        locationProvider.applyManual(result)
        return result
    }

    /** 输入城市名/拼音兜底（自动判坐标或地名）。 */
    fun applyManualAuto(text: String?): ManualLocationResult {
        val result = manualResolver.resolveAuto(text)
        if (result.isSuccess) locationProvider.applyManual(result)
        return result
    }

    /** 清除手动位置，回到传感器定位。 */
    fun clearManual() {
        locationProvider.clearManual()
    }

    // ------------------------------------------------------------------
    // 姿态 / 校准 / 取景
    // ------------------------------------------------------------------

    /** 重新校准（清空画 8 字进度）。 */
    fun recalibrate() {
        attitudeEngine.resetCalibration()
        PLog.i(TAG, "calibration reset, ask user to draw figure-eight")
    }

    /** 更新取景参数（屏幕旋转变化 / 切换相机 / 手动微调时调用）。 */
    fun updateViewport(displayRotationDegrees: Int, cameraAxis: CameraAxis, horizontalFovDegrees: Float) {
        val cfg = configProvider().clamped().config
        attitudeEngine.updateViewport(
            AstroNavViewport(
                displayRotationDegrees = AstroNavConfig.snapDisplayRotation(displayRotationDegrees),
                cameraAxis = cameraAxis,
                horizontalFovDegrees = AstroNavOverlayBridge.clampFov(horizontalFovDegrees),
                manualYawOffsetDegrees = cfg.manualOffsetDegrees,
            ),
        )
    }

    /**
     * 记录一个姿态样本（用于离线精修；仅在引导用户「录一段」时调用）。
     *
     * 容量由 [AstroNavConfig.recordMaxSamples] 控制（可调）；超出后丢**最旧**一条
     * （环形缓冲）。注意这是「录制缓存」的上限，不是处理能力上限——
     * [AstroNavBackgroundSolver] 对样本数没有硬上限，内存不足时会自动降级为单遍滤波。
     */
    fun recordAttitudeSample() {
        val q = attitudeEngine.attitude.value?.quaternion ?: return
        val cap = configProvider().clamped().config.recordMaxSamples.coerceAtLeast(2)
        if (recordedTimestamps.size >= cap) {
            recordedTimestamps.removeAt(0)
            repeat(4) { recordedQuaternions.removeAt(0) }
        }
        recordedQuaternions.add(q.w)
        recordedQuaternions.add(q.x)
        recordedQuaternions.add(q.y)
        recordedQuaternions.add(q.z)
        recordedTimestamps.add(System.currentTimeMillis())
    }

    /** 已记录样本数。 */
    fun recordedSampleCount(): Int = recordedTimestamps.size

    /** 清空已记录样本。 */
    fun clearRecordedSamples() {
        recordedQuaternions.clear()
        recordedTimestamps.clear()
    }

    /**
     * 提交离线精修（走 BgJobRunner 低优先；[AstroNavConfig.heavyPathInBackground] = false 时跳过）。
     *
     * @return 任务句柄；无样本或已关闭时返回 null
     */
    fun startOfflineRefinement(
        onResult: (AstroNavRefinementResult?) -> Unit = {},
    ): BgJob? {
        val cfg = configProvider().clamped().config
        if (!cfg.heavyPathInBackground) {
            PLog.i(TAG, "offline refinement skipped (heavyPathInBackground=false)")
            return null
        }
        if (recordedTimestamps.size < 2) return null
        val job = AstroNavBackgroundSolver.submitRefinement(
            quaternions = recordedQuaternions.toFloatArray(),
            timestampsMs = recordedTimestamps.toLongArray(),
            config = cfg,
            cameraAxis = cfg.cameraAxis,
            onResult = { result ->
                _state.value = _state.value.copy(refinementInFlight = false)
                onResult(result)
            },
        )
        if (job != null) _state.value = _state.value.copy(refinementInFlight = true)
        return job
    }

    // ------------------------------------------------------------------
    // 磁偏角：在线更新（可选；失败静默回落内嵌 WMM）
    // ------------------------------------------------------------------

    /**
     * 拉取在线磁偏角并写入缓存；失败返回 null（内嵌 WMM2025 兜底，功能不缺失）。
     * 需要网络时调用（例如用户点「更新磁偏角」或进入星空模式且配置为 ONLINE_API）。
     */
    suspend fun updateOnlineDeclination(): DeclinationFetch? {
        val cfg = configProvider().clamped().config
        if (cfg.declinationSource != DeclinationSource.ONLINE_API) return null
        val loc = locationProvider.state.value
        val lat = loc.point?.latitudeDegrees ?: loc.fallbackLatitude.toDouble()
        val lon = loc.point?.longitudeDegrees ?: 0.0
        val fetch = withContext(Dispatchers.IO) {
            runCatching { declinationUpdater.fetch(lat, lon) }
                .onFailure { e -> PLog.w(TAG, "online declination failed: ${e.message}") }
                .getOrNull()
        }
        if (fetch != null) {
            declinationProvider.setOnlineResult(lat.toFloat(), lon.toFloat(), fetch.degrees, fetch.fetchedAtMs)
            attitudeEngine.setOnlineDeclination(lat.toFloat(), lon.toFloat(), fetch.degrees, fetch.fetchedAtMs)
            PLog.i(TAG, "online declination=${fetch.degrees} for ($lat, $lon)")
        }
        return fetch
    }

    // ------------------------------------------------------------------
    // 日月联动（复用既有 astroplan/SunCalc，纯计算、微秒级，无需后台线程）
    // ------------------------------------------------------------------

    /**
     * 当前太阳/月亮目标（地平坐标，方位角从真北顺时针）。
     * 定位不可用时用 [AstroNavLocation.fallbackLatitude]（结果标注为近似）。
     */
    fun sunMoonTargets(epochMs: Long = System.currentTimeMillis()): List<AstroNavSkyTarget> {
        val loc = locationProvider.state.value
        val lat = loc.point?.latitudeDegrees ?: loc.fallbackLatitude.toDouble()
        val lon = loc.point?.longitudeDegrees ?: 0.0
        return runCatching {
            listOf(
                AstroNavSunMoonLink.sunTarget(epochMs, lat, lon),
                AstroNavSunMoonLink.moonTarget(epochMs, lat, lon),
            )
        }.getOrElse { e ->
            PLog.w(TAG, "sun/moon target failed: ${e.message}")
            emptyList()
        }
    }

    /** 本地平恒星时（小时），供既有叠加层把「相对基准」升级为真实基准。 */
    fun lstHours(epochMs: Long = System.currentTimeMillis()): Float {
        val lon = locationProvider.state.value.point?.longitudeDegrees ?: 0.0
        return AstroNavSiderealTime.lstHours(epochMs, lon)
    }

    // ------------------------------------------------------------------
    // 内部：把三条 StateFlow 合成 UI 状态
    // ------------------------------------------------------------------

    private fun startCollecting(clampCorrected: List<String>) {
        collector = scope.launch {
            combine(
                locationProvider.state,
                attitudeEngine.attitude,
                attitudeEngine.availability,
            ) { location, attitude, sensors -> Triple(location, attitude, sensors) }
                .collect { (location, attitude, sensors) ->
                    _state.value = AstroNavUiState(
                        enabled = true,
                        location = location,
                        attitude = attitude,
                        sensors = sensors,
                        clampCorrected = clampCorrected,
                        statusText = buildStatusText(location, attitude, sensors),
                        needsCalibration = attitude?.needsCalibration ?: false,
                        calibrationHint = attitude?.calibrationHint.orEmpty(),
                        refinementInFlight = _state.value.refinementInFlight,
                    )
                }
        }
    }

    private fun buildStatusText(
        location: AstroNavLocation,
        attitude: AstroNavAttitude?,
        sensors: AstroNavSensorAvailability,
    ): String = buildString {
        append(location.message.ifBlank { location.status.displayName })
        if (attitude != null) {
            append(" · 指向 ").append(attitude.pointing.compassName)
            append(" ").append("%.0f".format(attitude.pointing.altitudeDegrees)).append("°")
            append(" · 精度 ").append((attitude.qualityScore * 100).toInt()).append("%")
        }
        if (!sensors.canFuseFull) append(" · ").append(sensors.description)
        if (attitude?.needsCalibration == true) append(" · 需要画 8 字校准")
    }

    private companion object {
        const val TAG = "AstroNavController"
    }
}
