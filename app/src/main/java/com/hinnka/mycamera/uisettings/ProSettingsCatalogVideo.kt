package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.logvideo.LogCurvePreset
import com.hinnka.mycamera.logvideo.LogCurveRecipe
import com.hinnka.mycamera.logvideo.LogVideoConfig
import com.hinnka.mycamera.video.VideoCodec
import com.hinnka.mycamera.videomodes.AntiFlickerMode
import com.hinnka.mycamera.videomodes.HdrTransfer
import com.hinnka.mycamera.videomodes.SnapshotSource
import com.hinnka.mycamera.videomodes.VideoDenoiseMode
import com.hinnka.mycamera.videomodes.VideoModesRanges

/**
 * 目录③：视频模式扩展（`videomodes`）+ Log 视频与 LUT（`logvideo`）。
 *
 * 两组键都落既有 `user_preferences`：
 * - videomodes 用**单键 JSON** `video_modes_config`，其内部键前缀 `video_modes.`；
 *   内部键来自 `VideoModesConfig.toMap()`，与既有 `video_fps` / `video_codec` 等键是**叠加关系**而非冲突。
 * - logvideo 用平铺键 `log_video_*` / `log_curve_*`（键名与 `LogVideoConfig.fromPreferences` 一致）。
 *
 * 取值范围全部来自 `VideoModesRanges` / `LogCurveRecipe.Companion` / `LogVideoConfig.Companion`。
 */
object ProSettingsCatalogVideo {

    /** videomodes 的单键 JSON 容器键（见 videomodes 补丁 CONFIG 节）。 */
    private const val VIDEO_MODES_CONTAINER_KEY = "video_modes_config"

    /** 容器键 → 内部键的书写形式：`video_modes_config` 单键内的 `video_modes.<字段>`。 */
    private fun vk(field: String): String = "$VIDEO_MODES_CONTAINER_KEY :: video_modes.$field"

    val entries: List<ProSettingSpec> = buildList {

        // ───────────────────────────────────────── 帧率 / 慢动作

        add(ProSettingBuilder.it("videomodes.fps.targetFps", vk("fps.target_fps"), "目标帧率",
            "与既有 video_fps 叠加：本项细化目标值", VideoModesRanges.DEFAULT_FPS,
            VideoModesRanges.FPS_MIN, VideoModesRanges.FPS_MAX, 1, "fps", "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.fps.slowMotionEnabled", vk("fps.slow_motion_enabled"), "慢动作",
            "高帧率拍摄 + 低速回放", false, "BMD,ProCam"))
        add(ProSettingBuilder.fl("videomodes.fps.slowMotionSpeed", vk("fps.slow_motion_speed"), "慢放倍率",
            "只影响回放 / 合成，不改采集帧率", VideoModesRanges.DEFAULT_SLOW_MOTION_SPEED,
            VideoModesRanges.SLOW_MOTION_SPEED_MIN, VideoModesRanges.SLOW_MOTION_SPEED_MAX, 3, "×", "BMD"))

        // ───────────────────────────────────────── 延时

        add(ProSettingBuilder.lt("videomodes.timelapse.intervalMs", vk("timelapse.interval_ms"), "延时拍摄间隔",
            "毫秒", VideoModesRanges.DEFAULT_TIMELAPSE_INTERVAL_MS,
            VideoModesRanges.TIMELAPSE_INTERVAL_MS_MIN, VideoModesRanges.TIMELAPSE_INTERVAL_MS_MAX,
            "ms", "ProCam,OpenCam"))
        add(ProSettingBuilder.it("videomodes.timelapse.totalFrames", vk("timelapse.total_frames"), "延时总帧数",
            "不设算法上限", VideoModesRanges.DEFAULT_TIMELAPSE_FRAMES,
            VideoModesRanges.TIMELAPSE_FRAMES_MIN, VideoModesRanges.TIMELAPSE_FRAMES_ABSOLUTE_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.it("videomodes.timelapse.outputFps", vk("timelapse.output_fps"), "延时输出帧率",
            "合成视频帧率", VideoModesRanges.DEFAULT_TIMELAPSE_OUTPUT_FPS,
            VideoModesRanges.TIMELAPSE_OUTPUT_FPS_MIN, VideoModesRanges.TIMELAPSE_OUTPUT_FPS_MAX, 1, "fps", "ProCam"))
        add(ProSettingBuilder.sw("videomodes.timelapse.backgroundCompose", vk("timelapse.background_compose"),
            "后台合成", "拍摄结束后在后台合成，不阻塞拍摄", true, "ProCam"))
        add(ProSettingBuilder.lt("videomodes.timelapse.stopAfterDurationMs", vk("timelapse.stop_after_duration_ms"),
            "自动停止时长", "0 = 不限", 0L, 0L, 86_400_000L, "ms", "ProCam"))

        // ───────────────────────────────────────── 防抖 / HDR

        add(ProSettingBuilder.sw("videomodes.eis.enabled", vk("eis.enabled"), "电子防抖",
            "与既有 video_stabilization_mode 叠加：本项只做细化", true, "BMD,OpenCam"))
        add(ProSettingBuilder.fl("videomodes.eis.strength", vk("eis.strength"), "防抖强度",
            "0..2", VideoModesRanges.DEFAULT_EIS_STRENGTH,
            VideoModesRanges.EIS_STRENGTH_MIN, VideoModesRanges.EIS_STRENGTH_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.fl("videomodes.eis.cropFactor", vk("eis.crop_factor"), "防抖裁切系数",
            "1 = 不额外裁切", VideoModesRanges.DEFAULT_EIS_CROP_FACTOR,
            VideoModesRanges.EIS_CROP_FACTOR_MIN, VideoModesRanges.EIS_CROP_FACTOR_MAX, 3, "×", "BMD"))
        add(ProSettingBuilder.it("videomodes.eis.lookaheadFrames", vk("eis.lookahead_frames"), "防抖前瞻帧数",
            "越大越稳但越滞后", VideoModesRanges.DEFAULT_EIS_LOOKAHEAD,
            VideoModesRanges.EIS_LOOKAHEAD_MIN, VideoModesRanges.EIS_LOOKAHEAD_ABSOLUTE_MAX, 1, "帧", "BMD"))
        add(ProSettingBuilder.sw("videomodes.hdr.enabled", vk("hdr.enabled"), "HDR 视频",
            "默认关（既有行为 = SDR）", false, "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.hdr.prefer10Bit", vk("hdr.prefer_10bit"), "优先 10bit",
            "能力不足时按回退开关决定", true, "BMD"))
        add(ProSettingBuilder.sw("videomodes.hdr.preferBt2020", vk("hdr.prefer_bt2020"), "优先 BT.2020",
            "容器色彩", true, "BMD"))
        add(ProSettingBuilder.sw("videomodes.hdr.fallbackToSdr", vk("hdr.fallback_to_sdr"), "自动回退 SDR",
            "保证「永远录得成」", true, "BMD"))
        add(ProSettingBuilder.dd("videomodes.hdr.transfer", vk("hdr.transfer"), "首选传递函数",
            "默认 HLG（移动端兼容性更好）", HdrTransfer.HLG.name,
            HdrTransfer.entries.map { it.name }, "BMD"))

        // ───────────────────────────────────────── 编码 / 音频

        add(ProSettingBuilder.dd("videomodes.encode.codec", vk("encode.codec"), "视频编码",
            "H.264 兼容最好；H.265 同码率更清晰", VideoCodec.H264.name,
            VideoCodec.entries.map { it.name }, "BMD,OpenCam"))
        add(ProSettingBuilder.it("videomodes.encode.bitrateMbps", vk("encode.bitrate_mbps"), "视频码率",
            "Mbps", VideoModesRanges.DEFAULT_BITRATE_MBPS,
            VideoModesRanges.BITRATE_MBPS_MIN, VideoModesRanges.BITRATE_MBPS_MAX, 1, "Mbps", "BMD,OpenCam"))
        add(ProSettingBuilder.it("videomodes.encode.bitDepth", vk("encode.bit_depth"), "位深",
            "8 或 10（10 需编码器支持）", VideoModesRanges.DEFAULT_VIDEO_BIT_DEPTH, 8, 10, 2, "bit", "BMD"))
        add(ProSettingBuilder.it("videomodes.encode.audioBitrateKbps", vk("encode.audio_bitrate_kbps"), "音频码率",
            "kbps", VideoModesRanges.DEFAULT_AUDIO_BITRATE_KBPS,
            VideoModesRanges.AUDIO_BITRATE_KBPS_MIN, VideoModesRanges.AUDIO_BITRATE_KBPS_MAX, 64, "kbps", "OpenCam"))
        add(ProSettingBuilder.it("videomodes.encode.audioSampleRateHz", vk("encode.audio_sample_rate_hz"), "音频采样率",
            "Hz", VideoModesRanges.DEFAULT_AUDIO_SAMPLE_RATE_HZ, 8_000, 192_000, 8_000, "Hz", "OpenCam"))
        add(ProSettingBuilder.it("videomodes.encode.audioChannels", vk("encode.audio_channels"), "声道数",
            "1 = 单声道，2 = 立体声", VideoModesRanges.DEFAULT_AUDIO_CHANNELS,
            VideoModesRanges.AUDIO_CHANNELS_MIN, VideoModesRanges.AUDIO_CHANNELS_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.dd("videomodes.antiFlicker.mode", vk("anti_flicker.mode"), "防闪烁",
            "AUTO = 按地区兜底（中国大陆 50Hz）", AntiFlickerMode.AUTO.name,
            AntiFlickerMode.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.sw("videomodes.antiFlicker.autoFallback", vk("anti_flicker.auto_fallback"),
            "防闪烁自动降级", "不支持时自动降级而非拒绝录制", true, "BMD"))
        add(ProSettingBuilder.sw("videomodes.antiFlicker.use50hzGrid", vk("anti_flicker.use_50hz_grid"),
            "按 50Hz 电网", "AUTO 模式下的地区兜底依据", true, "BMD"))
        add(ProSettingBuilder.tx("videomodes.audio.inputId", vk("audio.input_id"), "音频输入设备",
            "留空 = 系统默认（VIDEO_AUDIO_INPUT_AUTO）", "", 64, "OpenCam"))
        add(ProSettingBuilder.it("videomodes.audio.gainPercent", vk("audio.gain_percent"), "录音增益",
            "百分比；0 = 静音，100 = 原值", VideoModesRanges.DEFAULT_AUDIO_GAIN_PERCENT,
            VideoModesRanges.AUDIO_GAIN_PERCENT_MIN, VideoModesRanges.AUDIO_GAIN_PERCENT_MAX, 5, "%", "BMD"))
        add(ProSettingBuilder.sw("videomodes.audio.muted", vk("audio.muted"), "录制静音",
            "只影响录制音轨", false, "BMD"))
        add(ProSettingBuilder.sw("videomodes.audio.noiseSuppression", vk("audio.noise_suppression"), "录音降噪",
            "需硬件支持", false, "BMD"))
        add(ProSettingBuilder.sw("videomodes.audio.monitor", vk("audio.monitor"), "耳机监听",
            "录制中监听音轨", false, "BMD"))

        // ───────────────────────────────────────── 实时滤镜 / 手动参数 / 镜头

        add(ProSettingBuilder.sw("videomodes.filter.enabled", vk("filter.enabled"), "实时滤镜",
            "录制 / 预览套色", false, "BMD"))
        add(ProSettingBuilder.tx("videomodes.filter.lutId", vk("filter.lut_id"), "滤镜 LUT",
            "留空 = 不套", "", 128, "BMD"))
        add(ProSettingBuilder.fl("videomodes.filter.intensity", vk("filter.intensity"), "滤镜强度",
            "0..2", VideoModesRanges.DEFAULT_FILTER_INTENSITY,
            VideoModesRanges.FILTER_INTENSITY_MIN, VideoModesRanges.FILTER_INTENSITY_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.filter.tierIndex", vk("filter.tier_index"), "滤镜质量档",
            "0 = 最快", 0, 0, 3, 1, "", "BMD"))
        add(ProSettingBuilder.tx("videomodes.filter.filmTextureId", vk("filter.film_texture_id"), "胶片颗粒素材",
            "留空 = 不加颗粒", "", 128, "BMD"))
        add(ProSettingBuilder.sw("videomodes.filter.logPreviewCoexist", vk("filter.log_preview_coexist"),
            "与 Log 监看共存", "Log 监看 + 滤镜预览同时开启的语义开关", true, "BMD"))
        add(ProSettingBuilder.lt("videomodes.manual.shutterNs", vk("manual.shutter_ns"), "手动快门",
            "纳秒；0 = 自动", VideoModesRanges.MANUAL_SHUTTER_NS_AUTO, 0L,
            VideoModesRanges.MANUAL_SHUTTER_NS_ABSOLUTE_MAX, "ns", "BMD,MotionCam"))
        add(ProSettingBuilder.it("videomodes.manual.iso", vk("manual.iso"), "手动 ISO",
            "0 = 自动", VideoModesRanges.MANUAL_ISO_AUTO, 0,
            VideoModesRanges.MANUAL_ISO_ABSOLUTE_MAX, 50, "", "BMD,MotionCam"))
        add(ProSettingBuilder.it("videomodes.manual.awbK", vk("manual.awb_k"), "手动白平衡",
            "开尔文；0 = 自动，或 2000..12000", VideoModesRanges.MANUAL_AWB_K_AUTO,
            VideoModesRanges.MANUAL_AWB_K_MIN, VideoModesRanges.MANUAL_AWB_K_ABSOLUTE_MAX, 50, "K", "BMD,MotionCam"))
        add(ProSettingBuilder.num("videomodes.manual.focusDiopters", vk("manual.focus_diopters"),
            "手动焦距（屈光度）", "-1 = 自动；0 = 无穷远", VideoModesRanges.MANUAL_FOCUS_DIOPTER_AUTO,
            VideoModesRanges.MANUAL_FOCUS_DIOPTER_MIN, VideoModesRanges.MANUAL_FOCUS_DIOPTER_ABSOLUTE_MAX,
            2, "dpt", "BMD"))
        add(ProSettingBuilder.sw("videomodes.manual.lockFocus", vk("manual.lock_focus"), "锁定对焦",
            "录制中锁 AF", false, "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.manual.lockExposure", vk("manual.lock_exposure"), "锁定曝光",
            "录制中锁 AE", false, "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.manual.lockWhiteBalance", vk("manual.lock_white_balance"),
            "锁定白平衡", "对标 Blackmagic「录制期间锁定 WB」", false, "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.lens.enabled", vk("lens.enabled"), "多摄切换",
            "录制中在允许的镜头间切换", true, "BMD"))
        add(ProSettingBuilder.tx("videomodes.lens.allowedIds", vk("lens.allowed_ids"), "允许的镜头 id",
            "逗号分隔；留空 = 全部允许", "", 256, "BMD"))
        add(ProSettingBuilder.sw("videomodes.lens.switchDuringRecording", vk("lens.switch_during_recording"),
            "录制中允许切换", "关闭则只在空闲时切换", true, "BMD"))
        add(ProSettingBuilder.sw("videomodes.lens.seamless", vk("lens.seamless"), "无缝切换",
            "依赖设备并发流能力", true, "BMD"))
        add(ProSettingBuilder.lt("videomodes.lens.settleMs", vk("lens.settle_ms"), "切换稳定等待",
            "切换后等待对焦 / 曝光的毫秒数", VideoModesRanges.DEFAULT_LENS_SWITCH_SETTLE_MS,
            VideoModesRanges.LENS_SWITCH_SETTLE_MS_MIN, VideoModesRanges.LENS_SWITCH_SETTLE_MS_MAX, "ms", "BMD"))

        // ───────────────────────────────────────── 视频超分 / 降噪 / 夜景 / 抓拍 / 循环 / 画中画

        add(ProSettingBuilder.sw("videomodes.sr.enabled", vk("sr.enabled"), "视频超分",
            "录制后处理超分", false, "BMD"))
        add(ProSettingBuilder.fl("videomodes.sr.scale", vk("sr.scale"), "视频超分倍率",
            "1.5..4", VideoModesRanges.DEFAULT_SR_SCALE, VideoModesRanges.SR_SCALE_MIN,
            VideoModesRanges.SR_SCALE_ABSOLUTE_MAX, 2, "×", "BMD"))
        add(ProSettingBuilder.it("videomodes.sr.frameStride", vk("sr.frame_stride"), "超分抽帧步长",
            "1 = 每帧都做（最慢）", VideoModesRanges.DEFAULT_SR_FRAME_STRIDE,
            VideoModesRanges.SR_FRAME_STRIDE_MIN, VideoModesRanges.SR_FRAME_STRIDE_ABSOLUTE_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.sr.chunkFrames", vk("sr.chunk_frames"), "超分分片帧数",
            "每次后台分片处理的帧数", VideoModesRanges.DEFAULT_SR_CHUNK_FRAMES,
            VideoModesRanges.SR_CHUNK_FRAMES_MIN, VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.sr.jpegQuality", vk("sr.jpeg_quality"), "超分输出质量",
            "60..100", VideoModesRanges.DEFAULT_SR_JPEG_QUALITY,
            VideoModesRanges.SR_JPEG_QUALITY_MIN, VideoModesRanges.SR_JPEG_QUALITY_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.sw("videomodes.sr.denoiseFirst", vk("sr.denoise_first"), "先降噪再超分",
            "低照素材建议开", false, "BMD"))
        add(ProSettingBuilder.it("videomodes.sr.outputBitrateMbps", vk("sr.output_bitrate_mbps"), "超分输出码率",
            "0 = 跟随主码率", 0, 0, VideoModesRanges.BITRATE_MBPS_MAX, 1, "Mbps", "BMD"))
        add(ProSettingBuilder.tx("videomodes.sr.outputCodec", vk("sr.output_codec"), "超分输出编码",
            "留空 = 跟随 encode.codec", "", 16, "BMD"))
        add(ProSettingBuilder.sw("videomodes.denoise.enabled", vk("denoise.enabled"), "视频降噪",
            "录制 / 后处理降噪", false, "BMD"))
        add(ProSettingBuilder.dd("videomodes.denoise.mode", vk("denoise.mode"), "降噪模式",
            "时域最省；时域 + 空域质量最好", VideoDenoiseMode.TEMPORAL.name,
            VideoDenoiseMode.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.fl("videomodes.denoise.strength", vk("denoise.strength"), "降噪强度",
            "0..1", VideoModesRanges.DEFAULT_DENOISE_STRENGTH, VideoModesRanges.DENOISE_STRENGTH_MIN,
            VideoModesRanges.DENOISE_STRENGTH_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.denoise.radius", vk("denoise.radius"), "降噪半径",
            "空域核半径", VideoModesRanges.DEFAULT_DENOISE_RADIUS, VideoModesRanges.DENOISE_RADIUS_MIN,
            VideoModesRanges.DENOISE_RADIUS_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.denoise.temporalFrames", vk("denoise.temporal_frames"),
            "时域累加帧数", "不设算法上限", VideoModesRanges.DEFAULT_DENOISE_TEMPORAL_FRAMES,
            VideoModesRanges.DENOISE_TEMPORAL_FRAMES_MIN,
            VideoModesRanges.DENOISE_TEMPORAL_FRAMES_ABSOLUTE_MAX, 1, "帧", "BMD"))
        add(ProSettingBuilder.it("videomodes.denoise.chunkFrames", vk("denoise.chunk_frames"), "降噪分片帧数",
            "后台分片粒度", VideoModesRanges.DEFAULT_SR_CHUNK_FRAMES,
            VideoModesRanges.SR_CHUNK_FRAMES_MIN, VideoModesRanges.SR_CHUNK_FRAMES_ABSOLUTE_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.sw("videomodes.denoise.preserveMotion", vk("denoise.preserve_motion"),
            "运动保细节", "高速运动时降低时域权重", true, "BMD"))
        add(ProSettingBuilder.sw("videomodes.night.enabled", vk("night.enabled"), "夜景视频",
            "低照增强（增益 / 时域）", false, "BMD,MotionCam"))
        add(ProSettingBuilder.fl("videomodes.night.gainBoostEv", vk("night.gain_boost_ev"), "增益提升",
            "EV", VideoModesRanges.DEFAULT_NIGHT_GAIN_BOOST_EV,
            VideoModesRanges.NIGHT_GAIN_BOOST_EV_MIN, VideoModesRanges.NIGHT_GAIN_BOOST_EV_MAX, 2, "EV", "BMD"))
        add(ProSettingBuilder.fl("videomodes.night.temporalStrength", vk("night.temporal_strength"),
            "夜景时域强度", "0..1", VideoModesRanges.DEFAULT_NIGHT_TEMPORAL_STRENGTH,
            VideoModesRanges.NIGHT_TEMPORAL_STRENGTH_MIN,
            VideoModesRanges.NIGHT_TEMPORAL_STRENGTH_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.night.maxIso", vk("night.max_iso"), "ISO 上限",
            "低照放开的上限（需硬件支持）", VideoModesRanges.DEFAULT_NIGHT_MAX_ISO,
            VideoModesRanges.NIGHT_MAX_ISO_MIN, VideoModesRanges.NIGHT_MAX_ISO_ABSOLUTE_MAX, 50, "", "BMD,MotionCam"))
        add(ProSettingBuilder.sw("videomodes.night.allowLongShutter", vk("night.allow_long_shutter"),
            "允许长快门", "低照自动放慢快门", true, "BMD,MotionCam"))
        add(ProSettingBuilder.lt("videomodes.night.maxShutterNs", vk("night.max_shutter_ns"), "快门上限",
            "纳秒", VideoModesRanges.DEFAULT_NIGHT_MAX_SHUTTER_NS,
            VideoModesRanges.NIGHT_MAX_SHUTTER_NS_MIN,
            VideoModesRanges.NIGHT_MAX_SHUTTER_NS_ABSOLUTE_MAX, "ns", "BMD"))
        add(ProSettingBuilder.dd("videomodes.night.denoiseMode", vk("night.denoise_mode"), "夜景降噪模式",
            "夜景默认时域", VideoDenoiseMode.TEMPORAL.name, VideoDenoiseMode.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.sw("videomodes.snapshot.enabled", vk("snapshot.enabled"), "录制中抓拍",
            "边录边拍静态照片", false, "BMD"))
        add(ProSettingBuilder.lt("videomodes.snapshot.intervalMs", vk("snapshot.interval_ms"), "抓拍间隔",
            "毫秒", VideoModesRanges.DEFAULT_SNAPSHOT_INTERVAL_MS,
            VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN, VideoModesRanges.SNAPSHOT_INTERVAL_MS_MAX, "ms", "BMD"))
        add(ProSettingBuilder.it("videomodes.snapshot.jpegQuality", vk("snapshot.jpeg_quality"), "抓拍质量",
            "60..100", VideoModesRanges.DEFAULT_SNAPSHOT_JPEG_QUALITY,
            VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MIN, VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.it("videomodes.snapshot.maxPending", vk("snapshot.max_pending"), "抓拍排队上限",
            "超过则丢弃最旧", VideoModesRanges.DEFAULT_SNAPSHOT_MAX_PENDING,
            VideoModesRanges.SNAPSHOT_MAX_PENDING_MIN, VideoModesRanges.SNAPSHOT_MAX_PENDING_MAX, 1, "张", "BMD"))
        add(ProSettingBuilder.it("videomodes.snapshot.maxLongEdge", vk("snapshot.max_long_edge"), "抓拍长边上限",
            "像素；0 = 不限", 0, 0, 8192, 64, "px", "BMD"))
        add(ProSettingBuilder.dd("videomodes.snapshot.source", vk("snapshot.source"), "抓拍来源",
            "预览抓帧不额外占流；录制旁路画质更贴近成片", SnapshotSource.PREVIEW.name,
            SnapshotSource.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.sw("videomodes.loop.enabled", vk("loop.enabled"), "循环录制",
            "只保留最近 N 秒", false, "OpenCam"))
        add(ProSettingBuilder.it("videomodes.loop.bufferSeconds", vk("loop.buffer_seconds"), "缓冲时长",
            "秒", VideoModesRanges.DEFAULT_LOOP_BUFFER_SECONDS, VideoModesRanges.LOOP_BUFFER_SECONDS_MIN,
            VideoModesRanges.LOOP_BUFFER_SECONDS_MAX, 1, "s", "OpenCam"))
        add(ProSettingBuilder.sw("videomodes.loop.overwriteOldest", vk("loop.overwrite_oldest"), "覆盖最旧片段",
            "关 = 满了就停", true, "OpenCam"))
        add(ProSettingBuilder.it("videomodes.loop.segmentSeconds", vk("loop.segment_seconds"), "分段时间",
            "秒", VideoModesRanges.DEFAULT_LOOP_SEGMENT_SECONDS, VideoModesRanges.LOOP_SEGMENT_SECONDS_MIN,
            VideoModesRanges.LOOP_SEGMENT_SECONDS_MAX, 1, "s", "OpenCam"))
        add(ProSettingBuilder.sw("videomodes.pip.enabled", vk("pip.enabled"), "双摄画中画",
            "依赖硬件并发流能力", false, "BMD"))
        add(ProSettingBuilder.tx("videomodes.pip.mainLensId", vk("pip.main_lens_id"), "主画面镜头 id",
            "留空 = 默认主摄", "", 64, "BMD"))
        add(ProSettingBuilder.tx("videomodes.pip.secondaryLensId", vk("pip.secondary_lens_id"), "副画面镜头 id",
            "留空 = 默认副摄", "", 64, "BMD"))
        add(ProSettingBuilder.it("videomodes.pip.secondaryFps", vk("pip.secondary_fps"), "副画面帧率",
            "通常低于主画面以省带宽", VideoModesRanges.DEFAULT_FPS,
            VideoModesRanges.FPS_MIN, VideoModesRanges.FPS_MAX, 1, "fps", "BMD"))
        add(ProSettingBuilder.sw("videomodes.pip.requireSeamless", vk("pip.require_seamless"), "要求无缝并发",
            "不满足则拒绝开启（而非降级黑屏）", true, "BMD"))

        // ───────────────────────────────────────── Log 视频与 LUT（logvideo）

        add(ProSettingBuilder.sw("logvideo.enabled", "log_video_enabled", "Log 视频",
            "录制纯 Log 灰片（本工程招牌功能）", LogVideoConfig.DEFAULT_ENABLED, "BMD,OpenCam"))
        add(ProSettingBuilder.dd("logvideo.curvePreset", "log_curve_preset", "Log 曲线档",
            "非法名回落 LOG_C3", LogCurvePreset.LOG_C3.name,
            LogCurvePreset.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.fl("logvideo.curveBlackLevel", "log_curve_black_level", "曲线黑电平",
            "黑电平偏移", LogCurveRecipe.DEFAULT_BLACK_LEVEL,
            LogCurveRecipe.BLACK_LEVEL_MIN, LogCurveRecipe.BLACK_LEVEL_MAX, 3, "", "OpenCam"))
        add(ProSettingBuilder.fl("logvideo.curvePeak", "log_curve_peak", "曲线峰值",
            "高光余量", LogCurveRecipe.DEFAULT_PEAK,
            LogCurveRecipe.PEAK_MIN, LogCurveRecipe.PEAK_MAX, 2, "", "OpenCam"))
        add(ProSettingBuilder.fl("logvideo.curveSlope", "log_curve_slope", "曲线斜率",
            "曲线斜率", LogCurveRecipe.DEFAULT_SLOPE,
            LogCurveRecipe.SLOPE_MIN, LogCurveRecipe.SLOPE_MAX, 2, "", "OpenCam"))
        add(ProSettingBuilder.it("logvideo.curveTableSize", "log_curve_table_size", "曲线查表分辨率",
            "越大越精确、构建越慢", LogCurveRecipe.DEFAULT_TABLE_SIZE,
            LogCurveRecipe.TABLE_SIZE_MIN, LogCurveRecipe.TABLE_SIZE_MAX, 64, "", "OpenCam"))
        add(ProSettingBuilder.dd("logvideo.lutMode", "log_video_lut_mode", "LUT 语义",
            "MONITOR_ONLY = 只监看不进片（推荐）；BAKE_IN = 烘焙进片", "MONITOR_ONLY",
            listOf("MONITOR_ONLY", "BAKE_IN"), "BMD"))
        add(ProSettingBuilder.tx("logvideo.restoreLutId", "log_video_restore_lut_id", "还原 LUT",
            "留空 = 按档位一键套用默认还原 LUT", "", 128, "BMD"))
        add(ProSettingBuilder.fl("logvideo.lutIntensity", "log_video_lut_intensity", "LUT 强度",
            "0 = 恒等，1 = 完整，2 = 过冲", LogVideoConfig.DEFAULT_LUT_INTENSITY,
            LogVideoConfig.LUT_INTENSITY_MIN, LogVideoConfig.LUT_INTENSITY_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.sw("logvideo.bakeOnDevice", "log_video_bake_on_device", "机内烘焙还原 LUT",
            "导出时套还原 LUT", LogVideoConfig.DEFAULT_BAKE_ON_DEVICE, "BMD"))
        add(ProSettingBuilder.it("logvideo.bakedLutSize", "log_video_baked_lut_size", "烘焙尺寸",
            "不足时自动降级 33→17→9", 33, 9, 65, 1, "", "BMD"))
        add(ProSettingBuilder.sw("logvideo.autoApplyBaked", "log_video_auto_apply_baked", "烘焙后自动套用",
            "烘焙完成即套用还原 LUT", true, "BMD"))
        add(ProSettingBuilder.sw("logvideo.tenBit", "log_video_ten_bit", "优先 10bit 输入",
            "纯 Log 输出建议开", true, "BMD"))
        add(ProSettingBuilder.sw("logvideo.gpuCurveTrim", "log_video_gpu_trim", "GPU 曲线微调",
            "复用既有 GL 层做曲线 trim", true, "BMD"))
        add(ProSettingBuilder.sw("logvideo.livePhotoLog", "log_video_live_photo", "动态照片走 Log",
            "Live Photo 片段同样走 Log 管线", true, "BMD"))
        add(ProSettingBuilder.sw("logvideo.stillFrameLog", "log_video_still_log", "视频抓帧走 Log",
            "从 Log 视频抓帧时保持 Log 语义", true, "BMD"))
    }
}
