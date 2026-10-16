/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：GPS/网络定位（LocationManager）。
 *
 * 职责：
 *  - 星空模式下获取**真实经纬度**（精度优先：GPS > 网络，更新间隔/最小距离可配，见 [AstroNavConfig]）；
 *  - 定位状态经 StateFlow 暴露给 UI（补丁挂点见 delivery/patches/astronav_patch.md）；
 *  - 定位不可用时**不阻塞**：立刻进入可手动兜底的状态（[AstroNavLocation]），UI 引导用户输入。
 *
 * 复用说明（PATTERN_SPEC §9）：直接使用 Android 框架 LocationManager + getCurrentLocation
 * （API 30+ 已经在 minSdk 内），**未引入任何第三方定位库**（Google Play Services / AMap / 高德
 * 都会显著增大体积且 Play Services 在国内机型不可用），属「用平台原生能力」而非自研算法。
 *
 * 权限：需要 ACCESS_FINE_LOCATION（网络定位另需 ACCESS_COARSE_LOCATION）——
 * 本文件按规范**不改 AndroidManifest**，权限声明与运行时申请由交付补丁给出。
 */
package com.hinnka.mycamera.astronav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定位提供者（GPS 优先；可配置更新间隔与最小位移）。
 *
 * 线程：回调在 Looper.getMainLooper() 上；状态经 StateFlow 异步发布（不阻塞 UI）。
 * 生命周期：由调用方（[AstroNavController]）在进入/退出星空模式时调用 [start] / [stop]。
 *
 * @param context 任意 Context（内部只取 applicationContext，不持有 Activity 引用）
 * @param configProvider 取当前生效配置（支持运行期改更新间隔/最小距离）
 */
class AstroNavLocationProvider(
    context: Context,
    private val configProvider: () -> AstroNavConfig = { AstroNavConfig() },
) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(AstroNavLocation.idle())

    /** 当前定位状态（UI 直接 collect）。 */
    val state: StateFlow<AstroNavLocation> = _state.asStateFlow()

    private var listener: LocationListener? = null
    private var started = false

    /** 手动兜底设置的位置（非空时优先于传感器定位，直到 [clearManual]）。 */
    @Volatile
    private var manual: AstroNavLocation? = null

    // 各提供者最新精度（用于精度优先择优）
    private var gpsAccuracy: Float? = null
    private var networkAccuracy: Float? = null
    private var lastKnownAccuracy: Float? = null
    private var currentSource: LocationSource? = null
    private var currentAccuracy: Float = Float.MAX_VALUE

    private val timeoutRunnable = Runnable {
        if (_state.value.status == LocationStatus.SEARCHING) {
            val manager = locationManager()
            val last = runCatching {
                if (hasPermission()) {
                    manager?.getProviders(true)
                        ?.mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
                        ?.maxByOrNull { it.time }
                } else {
                    null
                }
            }.getOrNull()
            if (last != null && last.time > 0L) {
                PLog.i(TAG, "location timeout, using last known fix")
                lastKnownAccuracy = last.accuracy
                applyFix(last, LocationSource.LAST_KNOWN)
            } else if (manual != null) {
                _state.value = manual!!
            } else if (configProvider().clamped().config.allowManualFallback) {
                PLog.i(TAG, "location timeout, awaiting manual fallback")
                _state.value = AstroNavLocation.failed("定位超时（${configProvider().config.locationTimeoutMs / 1000}s），请手动输入位置")
            } else {
                _state.value = AstroNavLocation.failed("定位超时")
            }
        }
    }

    /** 开始定位（重复调用安全）。 */
    fun start() {
        if (started) return
        started = true
        manual?.let {
            _state.value = it
            return
        }
        if (!hasPermission()) {
            _state.value = AstroNavLocation.permissionDenied()
            return
        }
        val manager = locationManager()
        if (manager == null) {
            _state.value = AstroNavLocation.failed("本机没有定位服务")
            return
        }
        if (!manager.isLocationEnabled) {
            _state.value = AstroNavLocation.providerDisabled()
            return
        }

        val cfg = configProvider().clamped().config
        _state.value = AstroNavLocation.searching()
        registerProviders(manager, cfg)
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, cfg.locationTimeoutMs)
    }

    /** 停止定位（退出星空模式时调用，释放监听）。 */
    fun stop() {
        started = false
        mainHandler.removeCallbacks(timeoutRunnable)
        val manager = locationManager()
        val l = listener
        if (manager != null && l != null) {
            runCatching { manager.removeUpdates(l) }
        }
        listener = null
    }

    /** 手动兜底：直接采用手动输入/离线搜索的结果。 */
    fun applyManual(result: ManualLocationResult) {
        val loc = result.location ?: return
        manual = loc
        _state.value = loc
        PLog.i(TAG, "manual location applied: ${loc.source} ${loc.point}")
    }

    /** 清除手动位置，回到传感器定位（若已 start 会重新尝试）。 */
    fun clearManual() {
        manual = null
        if (started) {
            stop()
            start()
        } else {
            _state.value = AstroNavLocation.idle()
        }
    }

    /** 当前是否处于手动位置模式。 */
    fun isManual(): Boolean = manual != null

    /** 手动位置（供测试/自检）。 */
    fun manualLocation(): AstroNavLocation? = manual

    /**
     * 一次性请求当前定位（用于「重新定位」按钮）。
     * 失败通过 [onResult] 回传状态，不抛异常。
     */
    fun requestSingleFix(onResult: (AstroNavLocation) -> Unit = {}) {
        if (!hasPermission()) {
            val s = AstroNavLocation.permissionDenied()
            _state.value = s
            onResult(s)
            return
        }
        val manager = locationManager()
        if (manager == null || !manager.isLocationEnabled) {
            val s = AstroNavLocation.providerDisabled()
            _state.value = s
            onResult(s)
            return
        }
        _state.value = AstroNavLocation.searching()
        val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationManager.GPS_PROVIDER
        } else {
            LocationManager.NETWORK_PROVIDER
        }
        runCatching {
            manager.getCurrentLocation(provider, null, mainHandler::post) { location ->
                if (location != null) {
                    when (provider) {
                        LocationManager.GPS_PROVIDER -> gpsAccuracy = location.accuracy
                        else -> networkAccuracy = location.accuracy
                    }
                    applyFix(location, provider.toSource())
                } else {
                    val s = AstroNavLocation.failed("未能获取位置，请手动输入")
                    _state.value = s
                    onResult(s)
                }
                onResult(_state.value)
            }
        }.onFailure { e ->
            PLog.w(TAG, "getCurrentLocation failed: ${e.message}")
            val s = AstroNavLocation.failed("定位请求失败：${e.message}")
            _state.value = s
            onResult(s)
        }
    }

    /** 当前 Android 权限是否满足（需要 FINE；仅有 COARSE 时也能拿到粗定位）。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否仅拿到粗略定位权限。 */
    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ---- 内部 ----

    private fun registerProviders(manager: LocationManager, cfg: AstroNavConfig) {
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (manual != null) return // 手动位置优先，不被传感器覆盖
                val source = location.provider.toSource()
                when (source) {
                    LocationSource.GPS -> gpsAccuracy = location.accuracy
                    LocationSource.NETWORK -> networkAccuracy = location.accuracy
                    else -> Unit
                }
                applyFix(location, source)
            }

            override fun onProviderEnabled(provider: String) {
                PLog.i(TAG, "provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                PLog.i(TAG, "provider disabled: $provider")
                if (!hasAnyProviderEnabled()) _state.value = AstroNavLocation.providerDisabled()
            }

            @Deprecated("Android 框架已弃用，此处仅保持接口完整")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = l

        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            _state.value = AstroNavLocation.providerDisabled()
            return
        }
        for (p in providers) {
            runCatching {
                manager.requestLocationUpdates(
                    p,
                    cfg.locationUpdateIntervalMs.coerceAtLeast(AstroNavConfig.LOCATION_INTERVAL_MIN),
                    cfg.locationMinDistanceM.coerceAtLeast(0f),
                    l,
                    Looper.getMainLooper(),
                )
            }.onFailure { e -> PLog.w(TAG, "requestLocationUpdates($p) failed: ${e.message}") }
        }
        PLog.i(
            TAG,
            "location started providers=$providers interval=${cfg.locationUpdateIntervalMs}ms " +
                "minDistance=${cfg.locationMinDistanceM}m timeout=${cfg.locationTimeoutMs}ms",
        )
    }

    /** 采用一个定位结果（含精度优先裁决与去抖）。 */
    private fun applyFix(location: Location, source: LocationSource) {
        val cfg = configProvider().clamped().config
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        val lastKnownFlagged = source == LocationSource.LAST_KNOWN
        val effectiveSource = if (lastKnownFlagged) LocationSource.LAST_KNOWN else source

        if (!LocationSourceSelector.shouldUpgrade(
                currentSource = currentSource,
                currentAccuracy = currentAccuracy,
                newSource = effectiveSource,
                newAccuracy = accuracy,
                preferGpsOverNetwork = cfg.preferGpsOverNetwork,
            )
        ) {
            return
        }

        val point = GeoPoint(
            latitudeDegrees = location.latitude,
            longitudeDegrees = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else cfg.altitudeMeters,
            accuracyMeters = accuracy.takeIf { it.isFinite() } ?: 0f,
            label = effectiveSource.displayName,
        ).normalized()

        currentSource = effectiveSource
        currentAccuracy = accuracy
        if (effectiveSource == LocationSource.LAST_KNOWN) lastKnownAccuracy = accuracy

        _state.value = AstroNavLocation.fixed(
            point = point,
            source = effectiveSource,
            accuracyMeters = point.accuracyMeters,
            updatedAtMs = System.currentTimeMillis(),
        )
        mainHandler.removeCallbacks(timeoutRunnable)
        PLog.i(TAG, "fix accepted: $effectiveSource lat=${point.latitudeDegrees} lon=${point.longitudeDegrees} acc=${point.accuracyMeters}")
    }

    private fun hasAnyProviderEnabled(): Boolean {
        val m = locationManager() ?: return false
        return runCatching {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    private fun locationManager(): LocationManager? =
        runCatching { appContext.getSystemService(LocationManager::class.java) }.getOrNull()

    private fun String?.toSource(): LocationSource = when (this) {
        LocationManager.GPS_PROVIDER -> LocationSource.GPS
        LocationManager.NETWORK_PROVIDER -> LocationSource.NETWORK
        else -> LocationSource.FUSED
    }

    private companion object {
        const val TAG = "AstroNavLocation"
    }
}
