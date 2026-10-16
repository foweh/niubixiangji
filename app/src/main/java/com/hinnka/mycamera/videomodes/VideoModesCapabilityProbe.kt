package com.hinnka.mycamera.videomodes

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Range
import android.util.Size
import com.hinnka.mycamera.utils.PLog

/**
 * 视频模式能力探测层（**唯一需要 android 依赖的地方**；纯逻辑不在此处）。
 *
 * 设计原则：
 * - 全部查询都包在 `runCatching` 里，任何 key 在旧版本/异常 HAL 上取不到都退化为「未上报」，
 *   **绝不抛异常**（探测失败 → 对应档位自动隐藏并说明，符合各 policy 的「能力不足自动隐藏」）；
 * - 只读，不发起会话、不申请权限；本机禁编译，故此处不引用任何未在本工程出现过的 API
 *   （全部 key 已在 `android-36/android.jar` 上 `javap` 核实）。
 */
object VideoModesCapabilityProbe {

    private const val TAG = "VideoModesProbe"

    /**
     * §1 帧率能力：AE 帧率区间 + 目标尺寸的最小帧间隔。
     *
     * @param outputSize 目标录制/预览尺寸；null = 不按时长过滤（只用 AE 区间）。
     */
    fun probeFps(
        characteristics: CameraCharacteristics,
        requestedFps: Int,
        outputSize: Size? = null,
    ): FpsCapability {
        val rawRanges = characteristics.safeGet(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
        )
        val ranges: List<IntRange> = rawRanges?.map { it.lower..it.upper } ?: emptyList()
        val durationNs = outputSize?.let { size ->
            runCatching {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
            }.getOrNull()
        } ?: 0L
        return FpsCapabilityResolver.resolve(ranges, durationNs, requestedFps)
    }

    /**
     * §15 防闪烁（anti-banding）能力：`CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`。
     *
     * 低版本/异常 HAL 上取不到 → 返回空集合，[AntiFlickerPolicy] 会走「保持 HAL 默认」分支。
     */
    fun probeAntiFlicker(characteristics: CameraCharacteristics): Set<Int> {
        val raw = runCatching {
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
        }.getOrNull()
        return AntiFlickerPolicy.normalizeAvailableModes(raw)
    }

    /**
     * §4 HDR 视频能力：相机动态范围 profile + 编码器 10bit profile。
     */
    fun probeHdr(characteristics: CameraCharacteristics): HdrVideoCapability {
        val encode = probeEncode()
        val supported: Set<Long> = runCatching {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.toSet()
        }.getOrNull() ?: emptySet()
        val hdrProfile = supported.contains(DynamicRangeProfiles.HDR10) ||
            supported.contains(DynamicRangeProfiles.HLG10)
        return HdrVideoCapability(
            hevcSupported = encode.hevcSupported,
            tenBitHevcSupported = encode.tenBitHevcSupported,
            tenBitAvcSupported = encode.tenBitAvcSupported,
            hdrDynamicRangeSupported = hdrProfile,
            // BT.2020 是容器标注：只要存在 10bit 通路（或相机支持 HDR profile）即可写入。
            bt2020Supported = encode.tenBitHevcSupported || encode.tenBitAvcSupported || hdrProfile,
        )
    }

    /**
     * §14 编码器能力：AVC/HEVC 是否存在、是否支持 10bit profile。
     *
     * 判定方式与既有 `video/VideoEncoderColorConfig.resolve10BitProfile()` 同源
     * （`MediaCodecInfo.getCapabilitiesForType(mime).profileLevels`），此处按「任一编码器支持即可」
     * 汇总成能力对象，供 [VideoEncodePolicy] 做回退决策。
     */
    fun probeEncode(): VideoEncodeCapability {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        }.getOrDefault(emptyList())

        fun anyCodecSupports(mime: String): Boolean = infos.any { info ->
            runCatching { info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .getOrDefault(false)
        }

        fun anyCodecSupportsProfile(mime: String, profile: Int): Boolean = infos.any { info ->
            runCatching {
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@runCatching false
                info.getCapabilitiesForType(mime).profileLevels.any { it.profile == profile }
            }.getOrDefault(false)
        }

        val avc = anyCodecSupports(MediaFormat.MIMETYPE_VIDEO_AVC)
        val hevc = anyCodecSupports(MediaFormat.MIMETYPE_VIDEO_HEVC)
        return VideoEncodeCapability(
            avcSupported = avc,
            hevcSupported = hevc,
            tenBitAvcSupported = anyCodecSupportsProfile(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            ),
            tenBitHevcSupported = anyCodecSupportsProfile(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            ),
        )
    }

    /**
     * §16 双摄并发能力：`CameraManager.getConcurrentCameraIds()`。
     *
     * 目标机 K50G 是否上报未知；为空即能力不足 → [DualCameraPipGate] 自动隐藏画中画档位。
     */
    fun probeConcurrentCameras(cameraManager: CameraManager): ConcurrentCameraCapability {
        val sets: Set<Set<String>> = runCatching {
            cameraManager.concurrentCameraIds.map { it.toSet() }.toSet()
        }.getOrElse { error ->
            PLog.w(TAG, "getConcurrentCameraIds 不可用: ${error.message}")
            emptySet()
        }
        return ConcurrentCameraCapacityMapper.fromCameraIdSets(sets)
    }

    /** §7 手动参数能力：快门/ISO/色温范围 + 最近对焦 + 手动与锁定支持。 */
    fun probeManual(
        characteristics: CameraCharacteristics,
        videoManualSupported: Boolean,
    ): ManualVideoProbe {
        val capabilities = runCatching {
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        }.getOrDefault(intArrayOf())
        val manualSensor = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
        val exposureRange = characteristics.safeGet(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
        )
        val isoRange = characteristics.safeGet(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
        )
        // Android 16（API 36）起才上报色温范围；与 Camera2Controller 的判定保持一致。
        val awbRange = if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                characteristics.safeGet(
                    CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE,
                )
            }.getOrNull()
        } else {
            null
        }
        val minFocus = runCatching {
            characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        }.getOrDefault(0f)

        return ManualVideoProbe(
            shutterRangeNs = exposureRange.toLongRangeOrEmpty(),
            isoRange = isoRange.toIntRangeOrEmpty(),
            awbRangeK = awbRange.toIntRangeOrEmpty(),
            maxFocusDiopters = minFocus,
            manualExposureSupported = manualSensor && exposureRange != null && isoRange != null &&
                videoManualSupported,
            lockSupported = videoManualSupported,
        )
    }

    /** §11 夜景视频能力：复用 [probeManual] 的结论 + 时域降噪可调度性。 */
    fun probeNight(
        characteristics: CameraCharacteristics,
        videoManualSupported: Boolean,
    ): NightVideoCapability {
        val manual = probeManual(characteristics, videoManualSupported)
        return NightVideoCapability(
            supportsManualIso = manual.manualExposureSupported && manual.isoRange.first <= manual.isoRange.last,
            supportsManualShutter = manual.manualExposureSupported &&
                manual.shutterRangeNs.first <= manual.shutterRangeNs.last,
            // 时域降噪是本模块后台软件实现，只要允许调度即可（默认允许）。
            supportsTemporalDenoise = true,
            isoRange = manual.isoRange,
            shutterRangeNs = manual.shutterRangeNs,
        )
    }

    // ---------- 范围转换辅助（null / 非法区间 → EMPTY，避免上层误 clamp 成 0） ----------

    private fun Range<Long>?.toLongRangeOrEmpty(): LongRange =
        this?.let { it.lower..it.upper }?.takeIf { it.first <= it.last } ?: LongRange.EMPTY

    private fun Range<Int>?.toIntRangeOrEmpty(): IntRange =
        this?.let { it.lower..it.upper }?.takeIf { it.first <= it.last } ?: IntRange.EMPTY

    /**
     * 安全读 key：本模块**不使用** `camera/` 包内的 `readMetadataOrNull`（它是 `internal`，
     * 跨包不可见），改用 `get()` + `runCatching`，未上报 / 不支持时返回 null。
     */
    private inline fun <T> CameraCharacteristics.safeGet(key: CameraCharacteristics.Key<T>): T? =
        runCatching { get(key) }.getOrNull()
}

/** 并发相机 id 集合 → 能力对象（抽成独立对象便于纯 JVM 单测，不需要 CameraManager）。 */
object ConcurrentCameraCapacityMapper {

    /** 单相机可同时输出的最大流数默认估计（未知时 0，由会话配置校验兜底）。 */
    const val UNKNOWN_MAX_STREAMS = 0

    fun fromCameraIdSets(sets: Set<Set<String>>): ConcurrentCameraCapability =
        ConcurrentCameraCapability(
            concurrentCameraIdSets = sets.map { it.toSet() },
            maxConcurrentStreamsPerCamera = UNKNOWN_MAX_STREAMS,
        )
}
