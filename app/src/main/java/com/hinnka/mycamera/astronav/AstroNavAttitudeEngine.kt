/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：传感器绑定 + 姿态解算编排（Android 侧）。
 *
 * ============================================================================
 *  传感器绑定与融合链路
 * ============================================================================
 *  绑定：陀螺仪（TYPE_GYROSCOPE）+ 加速度计（TYPE_ACCELEROMETER）+
 *        磁力计（TYPE_MAGNETIC_FIELD），全部注册在**独立 HandlerThread**
 *        （不占主线程；对应「传感器回调节流在前台轻量直跑」的取舍说明见下）。
 *  可选兜底：设备缺少陀螺仪时，退化使用 TYPE_ROTATION_VECTOR 直接给姿态
 *        （标注 usingRotationVectorFallback=true，精度与抗抖动能力下降）。
 *  融合：默认 xioTechnologies/Fusion 的 AHRS（MIT，见 [FusionAhrs]）；
 *        可切 [FusionAlgorithm.COMPLEMENTARY]（自研互补滤波兜底）/ GYRO_ONLY。
 *  真北：融合输出的是**磁北**对齐姿态；本类再叠加磁偏角（[MagneticDeclinationProvider]，
 *        内嵌 NOAA WMM2025）与用户手动偏角，得到真北对齐姿态（[AstroNavAttitude.quaternion]）。
 *
 *  取舍（用户硬约束「走性能调度」）：
 *   - **前台轻量直跑**：融合解算本身是 O(1) 的几十次浮点运算（无分配、无 IO），
 *     在传感器线程上按采样率直接跑，延迟最低；若把它塞进 BgJobRunner，
 *     会引入队列延迟 + 分片节流，指向会出现肉眼可见的滞后（星图最忌讳）。
 *   - **节流**：状态发布（StateFlow 写入 + 磁偏角查询 + 平滑）按
 *     [AstroNavConfig.attitudeUpdateIntervalMs] 节流，避免 UI 重组过频。
 *   - **重路径**：离线批处理（对一段姿态序列做高精度平滑/质量评估/漂移分析）
 *     走 BgJobRunner 低优先，见 [AstroNavBackgroundSolver]。
 * ============================================================================
 */
package com.hinnka.mycamera.astronav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 传感器可用性（UI 提示用；也用于自检报告）。 */
data class AstroNavSensorAvailability(
    val hasGyroscope: Boolean,
    val hasAccelerometer: Boolean,
    val hasMagnetometer: Boolean,
    val hasRotationVector: Boolean,
) {
    /** 是否有足够传感器做完整融合（陀螺 + 加速度 + 磁力）。 */
    val canFuseFull: Boolean get() = hasGyroscope && hasAccelerometer && hasMagnetometer

    /** 是否至少能给出姿态（完整融合，或退化的旋转向量）。 */
    val canOrient: Boolean get() = canFuseFull || hasRotationVector || hasAccelerometer

    /** 中文说明。 */
    val description: String
        get() = buildString {
            append("陀螺仪=").append(if (hasGyroscope) "有" else "无")
            append("，加速度计=").append(if (hasAccelerometer) "有" else "无")
            append("，磁力计=").append(if (hasMagnetometer) "有" else "无")
            append("，旋转向量=").append(if (hasRotationVector) "有" else "无")
            if (!canFuseFull) append("（精度受限：缺少完整传感器组合）")
        }
}

/** 取景参数（决定「相机指向哪里」，由 UI/Activity 注入；可运行期更新）。 */
data class AstroNavViewport(
    val displayRotationDegrees: Int = 0,
    val cameraAxis: CameraAxis = CameraAxis.BACK,
    val horizontalFovDegrees: Float = 60f,
    /** 手动方向微调（度，绕天顶轴，正 = 向东/顺时针）。 */
    val manualYawOffsetDegrees: Float = 0f,
)

/**
 * 姿态状态快照（UI + AR 叠加层消费）。
 *
 * [rotationMatrix] 为派生属性（不参与 equals），语义与
 * SensorManager.getRotationMatrixFromVector 完全一致，可直接替换既有
 * AstroOverlay 内的 r 矩阵，实现零回归接入。
 */
data class AstroNavAttitude(
    /** 真北对齐的设备姿态（已含磁偏角 + 手动偏角）。 */
    val quaternion: AstroNavQuaternion,
    /** 融合原始输出（磁北对齐，未做真北校正）。 */
    val magneticQuaternion: AstroNavQuaternion,
    /** 相机当前指向的地平坐标。 */
    val pointing: AstroNavPointing,
    /** 精度评分 0..1 与文案。 */
    val qualityScore: Float,
    val qualityText: String,
    /** 校准进度 0..1 与提示文案。 */
    val calibrationProgress: Float,
    val calibrationHint: String,
    /** 是否需要提示用户画 8 字。 */
    val needsCalibration: Boolean,
    /** 采用的磁偏角（度）与说明。 */
    val declinationDegrees: Float,
    val declinationNote: String,
    val declinationSource: DeclinationSource,
    /** 磁力计校准等级 0..3。 */
    val magAccuracy: Int,
    /** 实测采样率（Hz）。 */
    val sampleRateHz: Float,
    /** 当前生效反馈增益。 */
    val fusionGain: Float,
    /** 陀螺是否超量程（提示转动过快）。 */
    val gyroOverRange: Boolean,
    /** 磁力计是否检测到强干扰（磁场强度超出 20–70µT）。 */
    val magneticDisturbed: Boolean,
    /** 是否退化为旋转向量直通（无陀螺仪的兜底路径）。 */
    val usingRotationVectorFallback: Boolean,
    val timestampMs: Long,
) {
    /** 给既有 AstroOverlay 用的世界->设备旋转矩阵（与 SensorManager 公式等价）。 */
    val rotationMatrix: FloatArray get() = CoordinateAligner.worldToDeviceMatrix(quaternion)
}

/**
 * 传感器姿态引擎。
 *
 * 生命周期：进入星空模式 [start]，退出 [stop]（内部注销监听并退出 HandlerThread）。
 * 线程安全：传感器回调在内部 HandlerThread；[attitude] 经 StateFlow 发布，
 * 主线程 collect 即可（不持有 Activity 引用）。
 */
class AstroNavAttitudeEngine(
    context: Context,
    private val configProvider: () -> AstroNavConfig = { AstroNavConfig() },
    private val locationSupplier: () -> AstroNavLocation = { AstroNavLocation.idle() },
    private val declinationProvider: MagneticDeclinationProvider =
        MagneticDeclinationProvider(configProvider),
) {

    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? =
        runCatching { appContext.getSystemService(SensorManager::class.java) }.getOrNull()

    private val _attitude = MutableStateFlow<AstroNavAttitude?>(null)

    /** 当前姿态（未启动/无传感器时为 null）。 */
    val attitude: StateFlow<AstroNavAttitude?> = _attitude.asStateFlow()

    private val _availability = MutableStateFlow(readAvailability())

    /** 传感器可用性。 */
    val availability: StateFlow<AstroNavSensorAvailability> = _availability.asStateFlow()

    /** 取景参数（UI 可写；displayRotation 变化时应调用 [updateViewport]）。 */
    @Volatile
    var viewport: AstroNavViewport = AstroNavViewport()

    /** 校准进度（也包含在 [AstroNavAttitude] 内，这里单独暴露给校准页）。 */
    val calibrationTracker: AstroNavCalibrationTracker
        get() = tracker

    // ---- 内部状态 ----
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var started = false

    @Volatile
    private var cfg: AstroNavConfig = AstroNavConfig()

    private var fusion = FusionAhrs()
    private var smoother = AttitudeSmoother(SmoothingMode.ONE_EURO, 0.35f, 1.2f, 0.35f)
    private var tracker = AstroNavCalibrationTracker()

    private var latestGyro: AstroNavVec3 = AstroNavVec3.ZERO
    private var latestAccel: AstroNavVec3 = AstroNavVec3.ZERO
    private var latestMag: AstroNavVec3 = AstroNavVec3.ZERO
    private var hasGyroSample = false
    private var hasAccelSample = false
    private var hasMagSample = false
    private var hasRotationVectorSample = false

    private var magAccuracy = 0
    private var lastEmitMs = 0L
    private var lastFusionMs = 0L
    private val sampleIntervals = ArrayDeque<Long>()

    private var cachedDeclination: DeclinationResult? = null
    private var cachedDeclinationKey: String = ""

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            runCatching { handleEvent(event) }
                .onFailure { e -> PLog.w(TAG, "sensor event failed: ${e.message}") }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                magAccuracy = accuracy.coerceIn(0, 3)
            }
        }
    }

    /** 开始姿态解算（重复调用安全）。 */
    fun start() {
        if (started) return
        val sm = sensorManager
        if (sm == null) {
            PLog.w(TAG, "no SensorManager, attitude unavailable")
            return
        }
        cfg = configProvider().clamped().config
        if (!cfg.clamped().isClean) {
            PLog.i(TAG, "config clamped: ${cfg.clamped().corrected}")
        }
        fusion = FusionAhrs(
            FusionAhrsSettings(
                sampleRateHz = (1_000_000f / cfg.sensorSamplingPeriodUs.toFloat()).coerceIn(1f, 1_000f),
                convention = FusionConvention.NWU,
                gain = cfg.fusionGain,
                gyroscopeRangeDps = cfg.gyroscopeRangeDps,
                accelerationRejectionDeg = cfg.accelerationRejectionDeg,
                magneticRejectionDeg = cfg.magneticRejectionDeg,
                rejectionTimeoutS = cfg.rejectionTimeoutS,
                initialStartupGain = cfg.startupGain,
                startupPeriodS = cfg.startupPeriodS,
            ),
        )
        smoother = AttitudeSmoother(
            cfg.smoothingMode, cfg.exponentialAlpha, cfg.oneEuroMinCutoff, cfg.oneEuroBeta,
        )
        tracker = AstroNavCalibrationTracker(cfg.calibrateSampleCount)
        viewport = viewport.copy(
            manualYawOffsetDegrees = if (viewport.manualYawOffsetDegrees != 0f) {
                viewport.manualYawOffsetDegrees
            } else {
                cfg.manualOffsetDegrees
            },
        )

        val t = HandlerThread("astronav-sensors").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        started = true

        _availability.value = readAvailability()

        val registered = registerSensors(sm, h, cfg)
        _availability.value = readAvailability()
        PLog.i(TAG, "attitude engine started, registered sensors: $registered, cfg=$cfg")
    }

    /** 停止并释放（务必在退出星空模式时调用）。 */
    fun stop() {
        if (!started) return
        started = false
        val sm = sensorManager
        if (sm != null) runCatching { sm.unregisterListener(listener) }
        thread?.let { t -> runCatching { t.quitSafely() } }
        thread = null
        handler = null
        _attitude.value = null
        PLog.i(TAG, "attitude engine stopped")
    }

    /** 重新校准（清空进度，UI 重新提示画 8 字）。 */
    fun resetCalibration() {
        tracker.reset()
        fusion.restart()
        smoother.reset()
        _attitude.value = null
    }

    /** 更新取景参数（屏幕旋转 / 相机切换 / 手动微调）。 */
    fun updateViewport(newViewport: AstroNavViewport) {
        viewport = newViewport
    }

    /** 写入在线磁偏角（由 [DeclinationOnlineUpdater] 拉取后调用）。 */
    fun setOnlineDeclination(latDegrees: Float, lonDegrees: Float, degrees: Float, nowMs: Long) {
        declinationProvider.setOnlineResult(latDegrees, lonDegrees, degrees, nowMs)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun readAvailability(): AstroNavSensorAvailability {
        val sm = sensorManager
        return AstroNavSensorAvailability(
            hasGyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            hasAccelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            hasMagnetometer = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
            hasRotationVector = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
        )
    }

    private fun registerSensors(sm: SensorManager, h: Handler, cfg: AstroNavConfig): Int {
        var count = 0
        val period = cfg.sensorSamplingPeriodUs.coerceIn(
            AstroNavConfig.SAMPLING_PERIOD_MIN, AstroNavConfig.SAMPLING_PERIOD_MAX,
        )
        fun register(type: Int) {
            val sensor = sm.getDefaultSensor(type) ?: return
            val ok = runCatching { sm.registerListener(listener, sensor, period, h) }.getOrDefault(false)
            if (ok) count++
        }
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        register(Sensor.TYPE_ROTATION_VECTOR) // 无陀螺仪时的兜底
        return count
    }

    private fun handleEvent(event: SensorEvent) {
        val nowMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = AstroNavVec3(event.values[0], event.values[1], event.values[2])
                hasGyroSample = true
                // 陀螺触发融合（最高采样率）
                if (availability.value.hasGyroscope) {
                    stepFusion(nowMs)
                    emitThrottled(nowMs)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = AstroNavVec3(event.values[0], event.values[1], event.values[2])
                hasAccelSample = true
                if (!availability.value.hasGyroscope) {
                    // 无陀螺：用加速度事件驱动（此时姿态质量下降，已在状态中标注）
                    stepFusion(nowMs)
                    emitThrottled(nowMs)
                } else {
                    emitThrottled(nowMs)
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                latestMag = AstroNavVec3(event.values[0], event.values[1], event.values[2])
                hasMagSample = true
                emitThrottled(nowMs)
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!availability.value.hasGyroscope) {
                    latestRotationVector = event.values.copyOf()
                    hasRotationVectorSample = true
                    emitThrottled(nowMs)
                }
            }
        }

        if (isCalibrating()) {
            tracker.accept(latestAccel, latestMag, nowMs)
        }
    }

    private var latestRotationVector: FloatArray? = null

    /** 校准阶段：有加速度与磁力样本即可累积（不要求已握稳）。 */
    private fun isCalibrating(): Boolean = hasAccelSample && hasMagSample

    /** 一次融合步进（轻量：O(1) 浮点运算，无分配、无 IO，在前台传感器线程直跑）。 */
    private fun stepFusion(nowMs: Long) {
        if (lastFusionMs > 0L) {
            val dt = nowMs - lastFusionMs
            if (dt in 1..1_000) {
                sampleIntervals.addLast(dt)
                if (sampleIntervals.size > 32) sampleIntervals.removeFirst()
                // 用实测间隔补偿陀螺样本时钟误差（Fusion 提供的能力）
                fusion.updateSamplePeriod(dt / 1000f)
            }
        }
        lastFusionMs = nowMs

        when (cfg.fusionAlgorithm) {
            FusionAlgorithm.FUSION_AHRS -> {
                if (hasGyroSample && hasAccelSample) {
                    fusion.update(
                        gyroscopeDps = latestGyro,
                        accelerometerG = latestAccel,
                        magnetometer = if (hasMagSample) latestMag else AstroNavVec3.ZERO,
                    )
                }
            }

            FusionAlgorithm.COMPLEMENTARY -> {
                complementaryStep(nowMs)
            }

            FusionAlgorithm.GYRO_ONLY -> {
                if (hasGyroSample) gyroOnlyStep(nowMs)
            }
        }
    }

    // ---- 兜底算法（自研；Fusion 不可用时使用）----

    private var complementaryQ: AstroNavQuaternion = AstroNavQuaternion.IDENTITY
    private var gyroOnlyQ: AstroNavQuaternion = AstroNavQuaternion.IDENTITY

    /** 互补滤波：陀螺积分 +（加速度计重力方向）低频倾斜校正。 */
    private fun complementaryStep(nowMs: Long) {
        val dt = stepSeconds(nowMs)
        val alpha = cfg.complementaryAlpha.coerceIn(0f, 1f)

        // 1) 陀螺积分
        val gyroQ = AstroNavQuaternion.fromAxisAngle(latestGyro, latestGyro.norm * dt)
        var q = (complementaryQ * gyroQ).normalized()

        // 2) 低频校正：把「解算出的重力方向」往「实测重力方向」拉，比例 = (1 - alpha)
        if (hasAccelSample && !latestAccel.isZero) {
            val accRef = latestAccel.normalized()
            // 设备系下的实测重力方向（加速度计静止时指向 +Z 设备轴上方的反方向）
            val measuredDown = (accRef * -1f).normalized()
            val currentDown = q.conjugate().rotate(AstroNavVec3(0f, 0f, -1f))
            val tiltError = currentDown.cross(measuredDown)
            if (!tiltError.isZero) {
                val correction = AstroNavQuaternion.fromAxisAngle(
                    tiltError.normalized(),
                    tiltError.norm * (1f - alpha) * CORRECTION_GAIN_DEG,
                )
                q = (correction * q).normalized()
            }
        }
        complementaryQ = q
    }

    /** 纯陀螺积分（会缓慢漂移；仅在用户显式选择 [FusionAlgorithm.GYRO_ONLY] 时使用）。 */
    private fun gyroOnlyStep(nowMs: Long) {
        val dt = stepSeconds(nowMs)
        val gyroQ = AstroNavQuaternion.fromAxisAngle(latestGyro, latestGyro.norm * dt)
        gyroOnlyQ = (gyroOnlyQ * gyroQ).normalized()
    }

    /** 相邻两次融合步进的时间差（秒），异常值回落 1/100s。 */
    private fun stepSeconds(nowMs: Long): Float {
        val dtMs = if (lastFusionMs > 0L) nowMs - lastFusionMs else 10L
        return (dtMs.coerceIn(1L, 200L) / 1000f)
    }

    /** 当前使用的磁北对齐姿态（按算法分支取值）。 */
    private fun currentMagneticQuaternion(): AstroNavQuaternion = when (cfg.fusionAlgorithm) {
        FusionAlgorithm.FUSION_AHRS -> fusion.quaternion
        FusionAlgorithm.COMPLEMENTARY -> complementaryQ
        FusionAlgorithm.GYRO_ONLY -> gyroOnlyQ
    }

    /** 节流发布状态（磁偏角查询 + 平滑 + StateFlow 写入都在这条路径上）。 */
    private fun emitThrottled(nowMs: Long) {
        if (nowMs - lastEmitMs < cfg.attitudeUpdateIntervalMs) return
        lastEmitMs = nowMs

        val raw = when {
            availability.value.hasGyroscope -> currentMagneticQuaternion()
            hasRotationVectorSample -> latestRotationVector?.let {
                AstroNavQuaternion.fromRotationVectorValues(it)
            } ?: AstroNavQuaternion.IDENTITY
            else -> return
        }
        if (!raw.isUsable) return

        val smoothed = smoother.filter(raw, nowMs)

        // 磁偏角（按位置缓存；位置变化才重算 WMM）
        val loc = locationSupplier()
        val declination = resolveDeclination(loc, nowMs)

        val trueNorth = CoordinateAligner.applyTrueNorth(
            attitude = smoothed,
            declinationDeg = if (cfg.trueNorthCorrection) declination.degrees else 0f,
            manualYawOffsetDeg = viewport.manualYawOffsetDegrees,
        )

        // 诊断信息只在 Fusion AHRS 模式下有效；兜底算法给保守的残差估计
        val residuals = if (cfg.fusionAlgorithm == FusionAlgorithm.FUSION_AHRS) {
            fusion.diagnostics()
        } else {
            null
        }
        val residualDeg = residuals?.let {
            maxOf(it.accelerationErrorDeg, it.magneticErrorDeg)
        } ?: (if (hasAccelSample && AstroNavSensorMath.isNearGravity(latestAccel)) 2f else 8f)
        val quality = AttitudeQuality.score(
            magAccuracy = magAccuracy,
            sampleCount = tracker.sampleCount,
            requiredSamples = cfg.calibrateSampleCount,
            residualDeg = residualDeg,
        )
        val needsCalibration = cfg.requireCalibration &&
            AttitudeQuality.needsFigureEight(
                magAccuracy, tracker.sampleCount, cfg.calibrateSampleCount, cfg.magCalibrationQualityMin,
            )

        val pointing = CoordinateAligner.pointing(trueNorth, viewport.cameraAxis)

        _attitude.value = AstroNavAttitude(
            quaternion = trueNorth,
            magneticQuaternion = smoothed,
            pointing = pointing,
            qualityScore = quality,
            qualityText = AttitudeQuality.describe(quality),
            calibrationProgress = tracker.progress,
            calibrationHint = tracker.hint,
            needsCalibration = needsCalibration,
            declinationDegrees = declination.degrees,
            declinationNote = declination.note,
            declinationSource = declination.source,
            magAccuracy = magAccuracy,
            sampleRateHz = measuredSampleRateHz(),
            fusionGain = residuals?.currentGain ?: cfg.fusionGain,
            gyroOverRange = AstroNavSensorMath.isGyroOverRange(latestGyro, cfg.gyroscopeRangeDps),
            magneticDisturbed = hasMagSample &&
                !AstroNavSensorMath.isMagneticFieldPlausible(latestMag),
            usingRotationVectorFallback = !availability.value.hasGyroscope && hasRotationVectorSample,
            timestampMs = nowMs,
        )
    }

    /** 磁偏角：按「位置 + 日期」缓存，避免每帧跑 WMM 球谐展开。 */
    private fun resolveDeclination(loc: AstroNavLocation, nowMs: Long): DeclinationResult {
        val point = loc.point
        val key = if (point == null) {
            "none"
        } else {
            "${(point.latitudeDegrees * 100).toInt()}_${(point.longitudeDegrees * 100).toInt()}_" +
                "${(point.altitudeMeters / 100).toInt()}"
        }
        val cached = cachedDeclination
        if (cached != null && cachedDeclinationKey == key &&
            nowMs - (lastDeclinationMs) < DECLINATION_RECACHE_MS
        ) {
            return cached
        }
        val result = declinationProvider.declination(
            latDegrees = loc.fallbackLatitude,
            lonDegrees = point?.longitudeDegrees?.toFloat() ?: 0f,
            altitudeMeters = point?.altitudeMeters ?: cfg.altitudeMeters,
            nowMs = nowMs,
        )
        cachedDeclination = result
        cachedDeclinationKey = key
        lastDeclinationMs = nowMs
        return result
    }

    private var lastDeclinationMs = 0L

    private fun measuredSampleRateHz(): Float {
        if (sampleIntervals.isEmpty()) return 0f
        val sorted = sampleIntervals.toList().sorted()
        val median = sorted[sorted.size / 2]
        return if (median > 0L) (1000f / median).coerceIn(1f, 1_000f) else 0f
    }

    private companion object {
        const val TAG = "AstroNavAttitude"

        /** 互补滤波每步最大校正角（度）：限制校正速度，避免加速度干扰被放大。 */
        const val CORRECTION_GAIN_DEG = 2.0f

        /** 磁偏角重算间隔（毫秒）：即使位置不变，也按此间隔刷新（跨天/跨年漂移）。 */
        const val DECLINATION_RECACHE_MS = 600_000L
    }
}
