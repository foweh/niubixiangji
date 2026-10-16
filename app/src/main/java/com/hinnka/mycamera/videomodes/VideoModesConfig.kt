package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VideoCodec

/**
 * 视频模式扩展模块的**统一配置聚合**（PATTERN_SPEC §5 开放度配置对象模板）。
 *
 * 用途：
 * - 一处拿到本模块 16 个功能的全部开放参数，默认值 = 既有行为（零回归）；
 * - [clampReport] 一次性 clamp 全部参数并汇报被修正项（供日志/设置页提示）；
 * - [toMap] / [fromMap] 提供稳定的持久化 key（供 DataStore / 配置文件读取路径，
 *   见 `delivery/patches/videomodes_patch.md` 的 CONFIG 段）。
 *
 * 读取路径约定：`video_modes.*`（key 清单见各字段上方注释与 [toMap] 实现）。
 */
data class VideoModesConfig(
    /** §1 高帧率 / 慢动作。 */
    val fps: FpsModeConfig = FpsModeConfig(),
    /** §2 延时摄影。 */
    val timelapse: TimelapseConfig = TimelapseConfig(),
    /** §3 EIS 参数强化（开放裁剪系数等）。 */
    val eis: EisTuningConfig = EisTuningConfig(),
    /** §4 HDR 视频（10bit HEVC / BT.2020 门控）。 */
    val hdr: HdrVideoConfig = HdrVideoConfig(),
    /** §14 编码控制（编码器 / 位深 / 码率 / 音频编码）。 */
    val encode: VideoEncodeConfig = VideoEncodeConfig(),
    /** §15 防闪烁。 */
    val antiFlicker: AntiFlickerConfig = AntiFlickerConfig(),
    /** §8 音频控制（来源 / 增益 / 静音）。 */
    val audio: AudioControlConfig = AudioControlConfig(),
    /** §9 实时视频滤镜（档位 / 强度）。 */
    val filter: RealtimeFilterConfig = RealtimeFilterConfig(),
    /** §7 手动参数视频档（快门 / ISO / 色温 / 对焦 / 锁定）。 */
    val manual: ManualVideoParamsConfig = ManualVideoParamsConfig(),
    /** §6 多镜头切换（白名单 / 录制中切换 / 稳定时长）。 */
    val lens: MultiLensSwitchConfig = MultiLensSwitchConfig(),
    /** §5 视频超分后台任务。 */
    val sr: VideoSrConfig = VideoSrConfig(),
    /** §10 视频降噪后台任务。 */
    val denoise: VideoDenoiseConfig = VideoDenoiseConfig(),
    /** §11 低光 / 夜景视频。 */
    val night: NightVideoConfig = NightVideoConfig(),
    /** §12 边录边拍快照。 */
    val snapshot: SnapshotDuringRecordingConfig = SnapshotDuringRecordingConfig(),
    /** §13 循环录制。 */
    val loop: LoopRecordingConfig = LoopRecordingConfig(),
    /** §16 双摄并发画中画。 */
    val pip: DualCameraPipConfig = DualCameraPipConfig(),
) {

    /**
     * 一次性 clamp 全部子配置。
     *
     * @return pair.first = 修正后的配置；pair.second = 被修正项（形如 `"fps.targetFps 1000 → 240"`）。
     */
    fun clampReport(): Pair<VideoModesConfig, List<String>> {
        val all = mutableListOf<String>()
        val (fpsC, c1) = fps.clampReport()
        val (timelapseC, c2) = timelapse.clampReport()
        val (eisC, c3) = eis.clampReport()
        val (encodeC, c4) = encode.clampReport()
        val (audioC, c5) = audio.clampReport()
        val (filterC, c6) = filter.clampReport()
        val (lensC, c7) = lens.clampReport()
        val (srC, c8) = sr.clampReport()
        val (denoiseC, c9) = denoise.clampReport()
        val (nightC, c10) = night.clampReport()
        val (snapshotC, c11) = snapshot.clampReport()
        val (loopC, c12) = loop.clampReport()
        val (pipC, c13) = pip.clampReport()
        all += c1.map { "fps.$it" }
        all += c2.map { "timelapse.$it" }
        all += c3.map { "eis.$it" }
        all += c4.map { "encode.$it" }
        all += c5.map { "audio.$it" }
        all += c6.map { "filter.$it" }
        all += c7.map { "lens.$it" }
        all += c8.map { "sr.$it" }
        all += c9.map { "denoise.$it" }
        all += c10.map { "night.$it" }
        all += c11.map { "snapshot.$it" }
        all += c12.map { "loop.$it" }
        all += c13.map { "pip.$it" }
        return copy(
            fps = fpsC,
            timelapse = timelapseC,
            eis = eisC,
            encode = encodeC,
            audio = audioC,
            filter = filterC,
            lens = lensC,
            sr = srC,
            denoise = denoiseC,
            night = nightC,
            snapshot = snapshotC,
            loop = loopC,
            pip = pipC,
        ) to all
    }

    fun clamp(): VideoModesConfig = clampReport().first

    /** 序列化为持久化 map（全部 key 见 [KEY_PREFIX]）。 */
    fun toMap(): Map<String, String> = buildMap {
        put("${KEY_PREFIX}fps.target_fps", fps.targetFps.toString())
        put("${KEY_PREFIX}fps.slow_motion_enabled", fps.slowMotionEnabled.toString())
        put("${KEY_PREFIX}fps.slow_motion_speed", fps.slowMotionSpeed.toString())

        put("${KEY_PREFIX}timelapse.interval_ms", timelapse.intervalMs.toString())
        put("${KEY_PREFIX}timelapse.total_frames", timelapse.totalFrames.toString())
        put("${KEY_PREFIX}timelapse.output_fps", timelapse.outputFps.toString())
        put("${KEY_PREFIX}timelapse.background_compose", timelapse.backgroundCompose.toString())
        put("${KEY_PREFIX}timelapse.stop_after_duration_ms", timelapse.stopAfterDurationMs.toString())

        put("${KEY_PREFIX}eis.enabled", eis.enabled.toString())
        put("${KEY_PREFIX}eis.strength", eis.strength.toString())
        put("${KEY_PREFIX}eis.crop_factor", eis.cropFactor.toString())
        put("${KEY_PREFIX}eis.lookahead_frames", eis.lookaheadFrames.toString())
        put("${KEY_PREFIX}eis.horizon_lock", eis.horizonLockEnabled.toString())

        put("${KEY_PREFIX}hdr.enabled", hdr.enabled.toString())
        put("${KEY_PREFIX}hdr.prefer_10bit", hdr.prefer10Bit.toString())
        put("${KEY_PREFIX}hdr.prefer_bt2020", hdr.preferBt2020.toString())
        put("${KEY_PREFIX}hdr.fallback_to_sdr", hdr.fallbackToSdr.toString())
        put("${KEY_PREFIX}hdr.transfer", hdr.preferredTransfer.name)

        put("${KEY_PREFIX}encode.codec", encode.codec.name)
        put("${KEY_PREFIX}encode.bitrate_mbps", encode.bitrateMbps.toString())
        put("${KEY_PREFIX}encode.bit_depth", encode.bitDepth.toString())
        put("${KEY_PREFIX}encode.audio_bitrate_kbps", encode.audioBitrateKbps.toString())
        put("${KEY_PREFIX}encode.audio_sample_rate_hz", encode.audioSampleRateHz.toString())
        put("${KEY_PREFIX}encode.audio_channels", encode.audioChannels.toString())

        put("${KEY_PREFIX}anti_flicker.mode", antiFlicker.mode.name)
        put("${KEY_PREFIX}anti_flicker.auto_fallback", antiFlicker.resolutionAutoFallback.toString())
        put("${KEY_PREFIX}anti_flicker.use_50hz_grid", antiFlicker.use50HzGrid.toString())

        put("${KEY_PREFIX}audio.input_id", audio.inputId)
        put("${KEY_PREFIX}audio.gain_percent", audio.gainPercent.toString())
        put("${KEY_PREFIX}audio.muted", audio.muted.toString())
        put("${KEY_PREFIX}audio.noise_suppression", audio.noiseSuppression.toString())
        put("${KEY_PREFIX}audio.monitor", audio.monitorEnabled.toString())

        put("${KEY_PREFIX}filter.enabled", filter.enabled.toString())
        filter.lutId?.let { put("${KEY_PREFIX}filter.lut_id", it) }
        put("${KEY_PREFIX}filter.intensity", filter.intensity.toString())
        put("${KEY_PREFIX}filter.tier_index", filter.tierIndex.toString())
        filter.filmTextureId?.let { put("${KEY_PREFIX}filter.film_texture_id", it) }
        put("${KEY_PREFIX}filter.log_preview_coexist", filter.logPreviewCoexist.toString())

        put("${KEY_PREFIX}manual.shutter_ns", manual.shutterSpeedNs.toString())
        put("${KEY_PREFIX}manual.iso", manual.iso.toString())
        put("${KEY_PREFIX}manual.awb_k", manual.awbTemperatureK.toString())
        put("${KEY_PREFIX}manual.focus_diopters", manual.focusDistanceDiopters.toString())
        put("${KEY_PREFIX}manual.lock_focus", manual.lockFocus.toString())
        put("${KEY_PREFIX}manual.lock_exposure", manual.lockExposure.toString())
        put("${KEY_PREFIX}manual.lock_white_balance", manual.lockWhiteBalance.toString())

        put("${KEY_PREFIX}lens.enabled", lens.enabled.toString())
        put("${KEY_PREFIX}lens.allowed_ids", lens.allowedLensIds.joinToString(","))
        put("${KEY_PREFIX}lens.switch_during_recording", lens.switchDuringRecording.toString())
        put("${KEY_PREFIX}lens.seamless", lens.seamlessSwitch.toString())
        put("${KEY_PREFIX}lens.settle_ms", lens.switchSettleMs.toString())

        put("${KEY_PREFIX}sr.enabled", sr.enabled.toString())
        put("${KEY_PREFIX}sr.scale", sr.scale.toString())
        put("${KEY_PREFIX}sr.frame_stride", sr.frameStride.toString())
        put("${KEY_PREFIX}sr.chunk_frames", sr.chunkFrames.toString())
        put("${KEY_PREFIX}sr.jpeg_quality", sr.jpegQuality.toString())
        put("${KEY_PREFIX}sr.denoise_first", sr.denoiseFirst.toString())
        put("${KEY_PREFIX}sr.output_bitrate_mbps", sr.outputBitrateMbps.toString())
        sr.outputCodecName?.let { put("${KEY_PREFIX}sr.output_codec", it) }

        put("${KEY_PREFIX}denoise.enabled", denoise.enabled.toString())
        put("${KEY_PREFIX}denoise.mode", denoise.mode.name)
        put("${KEY_PREFIX}denoise.strength", denoise.strength.toString())
        put("${KEY_PREFIX}denoise.radius", denoise.radius.toString())
        put("${KEY_PREFIX}denoise.temporal_frames", denoise.temporalFrames.toString())
        put("${KEY_PREFIX}denoise.chunk_frames", denoise.chunkFrames.toString())
        put("${KEY_PREFIX}denoise.preserve_motion", denoise.preserveDetailOnMotion.toString())

        put("${KEY_PREFIX}night.enabled", night.enabled.toString())
        put("${KEY_PREFIX}night.gain_boost_ev", night.gainBoostEv.toString())
        put("${KEY_PREFIX}night.temporal_strength", night.temporalStrength.toString())
        put("${KEY_PREFIX}night.max_iso", night.maxIso.toString())
        put("${KEY_PREFIX}night.allow_long_shutter", night.allowLongShutter.toString())
        put("${KEY_PREFIX}night.max_shutter_ns", night.maxShutterNs.toString())
        put("${KEY_PREFIX}night.denoise_mode", night.denoiseMode.name)

        put("${KEY_PREFIX}snapshot.enabled", snapshot.enabled.toString())
        put("${KEY_PREFIX}snapshot.interval_ms", snapshot.intervalMs.toString())
        put("${KEY_PREFIX}snapshot.jpeg_quality", snapshot.jpegQuality.toString())
        put("${KEY_PREFIX}snapshot.max_pending", snapshot.maxPending.toString())
        put("${KEY_PREFIX}snapshot.max_long_edge", snapshot.maxLongEdge.toString())
        put("${KEY_PREFIX}snapshot.source", snapshot.source.name)

        put("${KEY_PREFIX}loop.enabled", loop.enabled.toString())
        put("${KEY_PREFIX}loop.buffer_seconds", loop.bufferSeconds.toString())
        put("${KEY_PREFIX}loop.overwrite_oldest", loop.overwriteOldest.toString())
        put("${KEY_PREFIX}loop.segment_seconds", loop.segmentSeconds.toString())

        put("${KEY_PREFIX}pip.enabled", pip.enabled.toString())
        pip.mainLensId?.let { put("${KEY_PREFIX}pip.main_lens_id", it) }
        pip.secondaryLensId?.let { put("${KEY_PREFIX}pip.secondary_lens_id", it) }
        put("${KEY_PREFIX}pip.secondary_fps", pip.secondaryFps.toString())
        put("${KEY_PREFIX}pip.require_seamless", pip.requireSeamlessSwitch.toString())
    }

    companion object {
        /** 持久化 key 前缀（DataStore / 配置文件读取路径）。 */
        const val KEY_PREFIX = "video_modes."

        /** 从持久化 map 构造（缺项取默认值，脏值经 clamp 修正，**不抛异常**）。 */
        fun fromMap(values: Map<String, String?>): VideoModesConfig {
            val base = VideoModesConfig()
            return VideoModesConfig(
                fps = FpsModeConfig(
                    targetFps = values.readInt("${KEY_PREFIX}fps.target_fps", base.fps.targetFps),
                    slowMotionEnabled = values.readBool(
                        "${KEY_PREFIX}fps.slow_motion_enabled",
                        base.fps.slowMotionEnabled,
                    ),
                    slowMotionSpeed = values.readFloat(
                        "${KEY_PREFIX}fps.slow_motion_speed",
                        base.fps.slowMotionSpeed,
                    ),
                ),
                timelapse = TimelapseConfig(
                    intervalMs = values.readLong(
                        "${KEY_PREFIX}timelapse.interval_ms",
                        base.timelapse.intervalMs,
                    ),
                    totalFrames = values.readInt(
                        "${KEY_PREFIX}timelapse.total_frames",
                        base.timelapse.totalFrames,
                    ),
                    outputFps = values.readInt(
                        "${KEY_PREFIX}timelapse.output_fps",
                        base.timelapse.outputFps,
                    ),
                    backgroundCompose = values.readBool(
                        "${KEY_PREFIX}timelapse.background_compose",
                        base.timelapse.backgroundCompose,
                    ),
                    stopAfterDurationMs = values.readLong(
                        "${KEY_PREFIX}timelapse.stop_after_duration_ms",
                        base.timelapse.stopAfterDurationMs,
                    ),
                ),
                eis = EisTuningConfig(
                    enabled = values.readBool("${KEY_PREFIX}eis.enabled", base.eis.enabled),
                    strength = values.readFloat("${KEY_PREFIX}eis.strength", base.eis.strength),
                    cropFactor = values.readFloat("${KEY_PREFIX}eis.crop_factor", base.eis.cropFactor),
                    lookaheadFrames = values.readInt(
                        "${KEY_PREFIX}eis.lookahead_frames",
                        base.eis.lookaheadFrames,
                    ),
                    horizonLockEnabled = values.readBool(
                        "${KEY_PREFIX}eis.horizon_lock",
                        base.eis.horizonLockEnabled,
                    ),
                ),
                hdr = HdrVideoConfig(
                    enabled = values.readBool("${KEY_PREFIX}hdr.enabled", base.hdr.enabled),
                    prefer10Bit = values.readBool("${KEY_PREFIX}hdr.prefer_10bit", base.hdr.prefer10Bit),
                    preferBt2020 = values.readBool("${KEY_PREFIX}hdr.prefer_bt2020", base.hdr.preferBt2020),
                    fallbackToSdr = values.readBool(
                        "${KEY_PREFIX}hdr.fallback_to_sdr",
                        base.hdr.fallbackToSdr,
                    ),
                    preferredTransfer = HdrTransfer.entries.firstOrNull {
                        it.name.equals(
                            values.readString("${KEY_PREFIX}hdr.transfer", base.hdr.preferredTransfer.name),
                            ignoreCase = true,
                        )
                    } ?: base.hdr.preferredTransfer,
                ),
                encode = VideoEncodeConfig(
                    codec = VideoCodec.entries.firstOrNull {
                        it.name.equals(
                            values.readString("${KEY_PREFIX}encode.codec", base.encode.codec.name),
                            ignoreCase = true,
                        )
                    } ?: base.encode.codec,
                    bitrateMbps = values.readInt(
                        "${KEY_PREFIX}encode.bitrate_mbps",
                        base.encode.bitrateMbps,
                    ),
                    bitDepth = values.readInt("${KEY_PREFIX}encode.bit_depth", base.encode.bitDepth),
                    audioBitrateKbps = values.readInt(
                        "${KEY_PREFIX}encode.audio_bitrate_kbps",
                        base.encode.audioBitrateKbps,
                    ),
                    audioSampleRateHz = values.readInt(
                        "${KEY_PREFIX}encode.audio_sample_rate_hz",
                        base.encode.audioSampleRateHz,
                    ),
                    audioChannels = values.readInt(
                        "${KEY_PREFIX}encode.audio_channels",
                        base.encode.audioChannels,
                    ),
                ),
                antiFlicker = AntiFlickerConfig(
                    mode = AntiFlickerMode.fromName(
                        values.readString("${KEY_PREFIX}anti_flicker.mode", base.antiFlicker.mode.name),
                    ),
                    resolutionAutoFallback = values.readBool(
                        "${KEY_PREFIX}anti_flicker.auto_fallback",
                        base.antiFlicker.resolutionAutoFallback,
                    ),
                    use50HzGrid = values.readBool(
                        "${KEY_PREFIX}anti_flicker.use_50hz_grid",
                        base.antiFlicker.use50HzGrid,
                    ),
                ),
                audio = AudioControlConfig(
                    inputId = values.readString("${KEY_PREFIX}audio.input_id", base.audio.inputId),
                    gainPercent = values.readInt("${KEY_PREFIX}audio.gain_percent", base.audio.gainPercent),
                    muted = values.readBool("${KEY_PREFIX}audio.muted", base.audio.muted),
                    noiseSuppression = values.readBool(
                        "${KEY_PREFIX}audio.noise_suppression",
                        base.audio.noiseSuppression,
                    ),
                    monitorEnabled = values.readBool("${KEY_PREFIX}audio.monitor", base.audio.monitorEnabled),
                ),
                filter = RealtimeFilterConfig(
                    enabled = values.readBool("${KEY_PREFIX}filter.enabled", base.filter.enabled),
                    lutId = values.readStringOrNull("${KEY_PREFIX}filter.lut_id"),
                    intensity = values.readFloat("${KEY_PREFIX}filter.intensity", base.filter.intensity),
                    tierIndex = values.readInt("${KEY_PREFIX}filter.tier_index", base.filter.tierIndex),
                    filmTextureId = values.readStringOrNull("${KEY_PREFIX}filter.film_texture_id"),
                    logPreviewCoexist = values.readBool(
                        "${KEY_PREFIX}filter.log_preview_coexist",
                        base.filter.logPreviewCoexist,
                    ),
                ),
                manual = ManualVideoParamsConfig(
                    shutterSpeedNs = values.readLong(
                        "${KEY_PREFIX}manual.shutter_ns",
                        base.manual.shutterSpeedNs,
                    ),
                    iso = values.readInt("${KEY_PREFIX}manual.iso", base.manual.iso),
                    awbTemperatureK = values.readInt("${KEY_PREFIX}manual.awb_k", base.manual.awbTemperatureK),
                    focusDistanceDiopters = values.readFloat(
                        "${KEY_PREFIX}manual.focus_diopters",
                        base.manual.focusDistanceDiopters,
                    ),
                    lockFocus = values.readBool("${KEY_PREFIX}manual.lock_focus", base.manual.lockFocus),
                    lockExposure = values.readBool(
                        "${KEY_PREFIX}manual.lock_exposure",
                        base.manual.lockExposure,
                    ),
                    lockWhiteBalance = values.readBool(
                        "${KEY_PREFIX}manual.lock_white_balance",
                        base.manual.lockWhiteBalance,
                    ),
                ),
                lens = MultiLensSwitchConfig(
                    enabled = values.readBool("${KEY_PREFIX}lens.enabled", base.lens.enabled),
                    allowedLensIds = values.readStringList("${KEY_PREFIX}lens.allowed_ids"),
                    switchDuringRecording = values.readBool(
                        "${KEY_PREFIX}lens.switch_during_recording",
                        base.lens.switchDuringRecording,
                    ),
                    seamlessSwitch = values.readBool("${KEY_PREFIX}lens.seamless", base.lens.seamlessSwitch),
                    switchSettleMs = values.readLong("${KEY_PREFIX}lens.settle_ms", base.lens.switchSettleMs),
                ),
                sr = VideoSrConfig(
                    enabled = values.readBool("${KEY_PREFIX}sr.enabled", base.sr.enabled),
                    scale = values.readFloat("${KEY_PREFIX}sr.scale", base.sr.scale),
                    frameStride = values.readInt("${KEY_PREFIX}sr.frame_stride", base.sr.frameStride),
                    chunkFrames = values.readInt("${KEY_PREFIX}sr.chunk_frames", base.sr.chunkFrames),
                    jpegQuality = values.readInt("${KEY_PREFIX}sr.jpeg_quality", base.sr.jpegQuality),
                    denoiseFirst = values.readBool("${KEY_PREFIX}sr.denoise_first", base.sr.denoiseFirst),
                    outputBitrateMbps = values.readInt(
                        "${KEY_PREFIX}sr.output_bitrate_mbps",
                        base.sr.outputBitrateMbps,
                    ),
                    outputCodecName = values.readStringOrNull("${KEY_PREFIX}sr.output_codec"),
                ),
                denoise = VideoDenoiseConfig(
                    enabled = values.readBool("${KEY_PREFIX}denoise.enabled", base.denoise.enabled),
                    mode = VideoDenoiseMode.fromName(
                        values.readString("${KEY_PREFIX}denoise.mode", base.denoise.mode.name),
                    ),
                    strength = values.readFloat("${KEY_PREFIX}denoise.strength", base.denoise.strength),
                    radius = values.readInt("${KEY_PREFIX}denoise.radius", base.denoise.radius),
                    temporalFrames = values.readInt(
                        "${KEY_PREFIX}denoise.temporal_frames",
                        base.denoise.temporalFrames,
                    ),
                    chunkFrames = values.readInt(
                        "${KEY_PREFIX}denoise.chunk_frames",
                        base.denoise.chunkFrames,
                    ),
                    preserveDetailOnMotion = values.readBool(
                        "${KEY_PREFIX}denoise.preserve_motion",
                        base.denoise.preserveDetailOnMotion,
                    ),
                ),
                night = NightVideoConfig(
                    enabled = values.readBool("${KEY_PREFIX}night.enabled", base.night.enabled),
                    gainBoostEv = values.readFloat(
                        "${KEY_PREFIX}night.gain_boost_ev",
                        base.night.gainBoostEv,
                    ),
                    temporalStrength = values.readFloat(
                        "${KEY_PREFIX}night.temporal_strength",
                        base.night.temporalStrength,
                    ),
                    maxIso = values.readInt("${KEY_PREFIX}night.max_iso", base.night.maxIso),
                    allowLongShutter = values.readBool(
                        "${KEY_PREFIX}night.allow_long_shutter",
                        base.night.allowLongShutter,
                    ),
                    maxShutterNs = values.readLong(
                        "${KEY_PREFIX}night.max_shutter_ns",
                        base.night.maxShutterNs,
                    ),
                    denoiseMode = VideoDenoiseMode.fromName(
                        values.readString("${KEY_PREFIX}night.denoise_mode", base.night.denoiseMode.name),
                    ),
                ),
                snapshot = SnapshotDuringRecordingConfig(
                    enabled = values.readBool("${KEY_PREFIX}snapshot.enabled", base.snapshot.enabled),
                    intervalMs = values.readLong(
                        "${KEY_PREFIX}snapshot.interval_ms",
                        base.snapshot.intervalMs,
                    ),
                    jpegQuality = values.readInt(
                        "${KEY_PREFIX}snapshot.jpeg_quality",
                        base.snapshot.jpegQuality,
                    ),
                    maxPending = values.readInt(
                        "${KEY_PREFIX}snapshot.max_pending",
                        base.snapshot.maxPending,
                    ),
                    maxLongEdge = values.readInt(
                        "${KEY_PREFIX}snapshot.max_long_edge",
                        base.snapshot.maxLongEdge,
                    ),
                    source = SnapshotSource.entries.firstOrNull {
                        it.name.equals(
                            values.readString("${KEY_PREFIX}snapshot.source", base.snapshot.source.name),
                            ignoreCase = true,
                        )
                    } ?: base.snapshot.source,
                ),
                loop = LoopRecordingConfig(
                    enabled = values.readBool("${KEY_PREFIX}loop.enabled", base.loop.enabled),
                    bufferSeconds = values.readInt(
                        "${KEY_PREFIX}loop.buffer_seconds",
                        base.loop.bufferSeconds,
                    ),
                    overwriteOldest = values.readBool(
                        "${KEY_PREFIX}loop.overwrite_oldest",
                        base.loop.overwriteOldest,
                    ),
                    segmentSeconds = values.readInt(
                        "${KEY_PREFIX}loop.segment_seconds",
                        base.loop.segmentSeconds,
                    ),
                ),
                pip = DualCameraPipConfig(
                    enabled = values.readBool("${KEY_PREFIX}pip.enabled", base.pip.enabled),
                    mainLensId = values.readStringOrNull("${KEY_PREFIX}pip.main_lens_id"),
                    secondaryLensId = values.readStringOrNull("${KEY_PREFIX}pip.secondary_lens_id"),
                    secondaryFps = values.readInt("${KEY_PREFIX}pip.secondary_fps", base.pip.secondaryFps),
                    requireSeamlessSwitch = values.readBool(
                        "${KEY_PREFIX}pip.require_seamless",
                        base.pip.requireSeamlessSwitch,
                    ),
                ),
            )
        }
    }
}

// ---------- 持久化读取辅助（脏值一律回退默认，不抛异常） ----------

private fun Map<String, String?>.readInt(key: String, fallback: Int): Int =
    this[key]?.trim()?.toIntOrNull() ?: fallback

private fun Map<String, String?>.readLong(key: String, fallback: Long): Long =
    this[key]?.trim()?.toLongOrNull() ?: fallback

private fun Map<String, String?>.readFloat(key: String, fallback: Float): Float =
    this[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback

private fun Map<String, String?>.readBool(key: String, fallback: Boolean): Boolean {
    val raw = this[key]?.trim()?.lowercase() ?: return fallback
    return when (raw) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> fallback
    }
}

private fun Map<String, String?>.readString(key: String, fallback: String): String =
    this[key]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

private fun Map<String, String?>.readStringOrNull(key: String): String? =
    this[key]?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, String?>.readStringList(key: String): List<String> =
    this[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
