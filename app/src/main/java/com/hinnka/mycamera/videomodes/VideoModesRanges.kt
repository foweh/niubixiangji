package com.hinnka.mycamera.videomodes

/**
 * 视频模式扩展模块的**唯一取值范围真值源**（PATTERN_SPEC §1.6 开放度硬约束）。
 *
 * 约定：
 * - 每个开放参数都有 `默认值 / 最小值 / 最大值`，越界一律 clamp，禁止裸写魔法数散落在各处；
 * - 常量名带 `ABSOLUTE_` 的是**防溢出/防误设**的安全边界，不是功能策略上限；
 * - **帧数/时长类参数不设总量硬上限**：常量只是溢出保护，运行时上限由各 `*Limits` 对象
 *   的 `userXxx`（默认 = ABSOLUTE 上限）决定，用户可继续上调，资源问题交给
 *   `BgJobRunner` 的分片 + `guardMemory` 解决（PATTERN_SPEC §1.5 / §4）。
 */
object VideoModesRanges {
    // ---------- §1 高帧率 / 慢动作 ----------
    /** 帧率档位最小值（1x 实时录制的可用下限）。 */
    const val FPS_MIN = 24
    /** 帧率档位最大值（慢动作高帧率上限，能力不足时按档位隐藏）。 */
    const val FPS_MAX = 240
    const val DEFAULT_FPS = 30
    /** 慢放回放速度下限（0.125x = 1/8 速，最慢档）。 */
    const val SLOW_MOTION_SPEED_MIN = 0.125f
    /** 慢放回放速度上限（1.0x = 原速，即未启用慢放）。 */
    const val SLOW_MOTION_SPEED_MAX = 1f
    const val DEFAULT_SLOW_MOTION_SPEED = 1f

    // ---------- §2 延时摄影 ----------
    /** 拍摄间隔下限（毫秒），0.5s = 500ms。 */
    const val TIMELAPSE_INTERVAL_MS_MIN = 500L
    /** 拍摄间隔上限（毫秒），60s。 */
    const val TIMELAPSE_INTERVAL_MS_MAX = 60_000L
    const val DEFAULT_TIMELAPSE_INTERVAL_MS = 1_000L
    /** 总帧数下限（2 帧才构成一段）。 */
    const val TIMELAPSE_FRAMES_MIN = 2
    /** 总帧数**溢出保护**上限（非策略上限；运行时上限见 TimelapseLimits）。 */
    const val TIMELAPSE_FRAMES_ABSOLUTE_MAX = 100_000
    const val DEFAULT_TIMELAPSE_FRAMES = 300
    /** 合成输出帧率范围（1~120fps）。 */
    const val TIMELAPSE_OUTPUT_FPS_MIN = 1
    const val TIMELAPSE_OUTPUT_FPS_MAX = 120
    const val DEFAULT_TIMELAPSE_OUTPUT_FPS = 30

    // ---------- §3 EIS 强化 ----------
    const val EIS_STRENGTH_MIN = 0f
    /** 强度**配置域**上限（2.0x）；引擎侧当前只消费 0..1，见 EisTuningPolicy。 */
    const val EIS_STRENGTH_MAX = 2f
    const val DEFAULT_EIS_STRENGTH = 1f
    /** 额外裁剪系数下限（1.0 = 不做额外裁剪 = 既有行为）。 */
    const val EIS_CROP_FACTOR_MIN = 1f
    /** 额外裁剪系数上限（1.6x，稳定但视野变窄）。 */
    const val EIS_CROP_FACTOR_MAX = 1.6f
    const val DEFAULT_EIS_CROP_FACTOR = 1f
    /** 前瞻帧下限（既有 MIN_VIDEO_STABILIZATION_LOOKAHEAD = 3）。 */
    const val EIS_LOOKAHEAD_MIN = 3
    /** 前瞻帧**配置域**上限（64）；native 引擎缓冲深度当前为 7，见 EisTuningPolicy。 */
    const val EIS_LOOKAHEAD_ABSOLUTE_MAX = 64
    const val DEFAULT_EIS_LOOKAHEAD = 7

    // ---------- §14 编码控制 ----------
    /** 视频码率下限（1 Mbps）。 */
    const val BITRATE_MBPS_MIN = 1
    /** 视频码率上限（200 Mbps，clamp 保护）。 */
    const val BITRATE_MBPS_MAX = 200
    const val DEFAULT_BITRATE_MBPS = 60
    const val AUDIO_BITRATE_KBPS_MIN = 64
    const val AUDIO_BITRATE_KBPS_MAX = 512
    const val DEFAULT_AUDIO_BITRATE_KBPS = 256
    val AUDIO_SAMPLE_RATES_HZ = intArrayOf(16_000, 32_000, 44_100, 48_000, 96_000)
    const val DEFAULT_AUDIO_SAMPLE_RATE_HZ = 48_000
    const val AUDIO_CHANNELS_MIN = 1
    const val AUDIO_CHANNELS_MAX = 2
    const val DEFAULT_AUDIO_CHANNELS = 2
    /** 10bit 编码位深可选值。 */
    val VIDEO_BIT_DEPTHS = intArrayOf(8, 10)
    const val DEFAULT_VIDEO_BIT_DEPTH = 8

    // ---------- §8 音频控制 ----------
    /** 麦克风增益下限（0% = 静音）。 */
    const val AUDIO_GAIN_PERCENT_MIN = 0
    /** 麦克风增益上限（300%）。 */
    const val AUDIO_GAIN_PERCENT_MAX = 300
    const val DEFAULT_AUDIO_GAIN_PERCENT = 100

    // ---------- §13 循环录制 ----------
    /** 环形缓冲时长下限（1s）。 */
    const val LOOP_BUFFER_SECONDS_MIN = 1
    /** 环形缓冲时长上限（60s）。 */
    const val LOOP_BUFFER_SECONDS_MAX = 60
    const val DEFAULT_LOOP_BUFFER_SECONDS = 10
    const val LOOP_SEGMENT_SECONDS_MIN = 1
    const val LOOP_SEGMENT_SECONDS_MAX = 10
    const val DEFAULT_LOOP_SEGMENT_SECONDS = 2

    // ---------- §12 边录边拍快照 ----------
    /** 快照间隔下限（100ms，防误设成 0 导致每帧都抓）。 */
    const val SNAPSHOT_INTERVAL_MS_MIN = 100L
    /** 快照间隔上限（600s）。 */
    const val SNAPSHOT_INTERVAL_MS_MAX = 600_000L
    const val DEFAULT_SNAPSHOT_INTERVAL_MS = 1_000L
    const val SNAPSHOT_JPEG_QUALITY_MIN = 60
    const val SNAPSHOT_JPEG_QUALITY_MAX = 100
    const val DEFAULT_SNAPSHOT_JPEG_QUALITY = 95
    /** 未落盘快照的最大排队数（超过则丢帧并提示，不阻塞录制）。 */
    const val SNAPSHOT_MAX_PENDING_MIN = 1
    const val SNAPSHOT_MAX_PENDING_MAX = 64
    const val DEFAULT_SNAPSHOT_MAX_PENDING = 3

    // ---------- §5 视频超分 ----------
    /** 超分倍率下限（1.5x 起才有意义）。 */
    const val SR_SCALE_MIN = 1.5f
    /** 超分倍率**配置域**上限（4x；内存不足时由降级逻辑下调而不是失败）。 */
    const val SR_SCALE_ABSOLUTE_MAX = 4f
    const val DEFAULT_SR_SCALE = 2f
    /** 抽帧步长（1 = 逐帧；>1 = 每 N 帧取一帧参与超分）。 */
    const val SR_FRAME_STRIDE_MIN = 1
    const val SR_FRAME_STRIDE_ABSOLUTE_MAX = 240
    const val DEFAULT_SR_FRAME_STRIDE = 1
    /** 单分片帧数（**分片大小，不是总帧数上限**）。 */
    const val SR_CHUNK_FRAMES_MIN = 1
    const val SR_CHUNK_FRAMES_ABSOLUTE_MAX = 64
    const val DEFAULT_SR_CHUNK_FRAMES = 8
    const val SR_JPEG_QUALITY_MIN = 60
    const val SR_JPEG_QUALITY_MAX = 100
    const val DEFAULT_SR_JPEG_QUALITY = 96

    // ---------- §10 视频降噪 ----------
    const val DENOISE_STRENGTH_MIN = 0f
    const val DENOISE_STRENGTH_MAX = 1f
    const val DEFAULT_DENOISE_STRENGTH = 0.5f
    const val DENOISE_RADIUS_MIN = 1
    const val DENOISE_RADIUS_MAX = 4
    const val DEFAULT_DENOISE_RADIUS = 2
    /** 时域窗口帧数下限（含当前帧）。 */
    const val DENOISE_TEMPORAL_FRAMES_MIN = 2
    /** 时域窗口帧数**溢出保护**上限（非策略上限，见 VideoDenoiseLimits）。 */
    const val DENOISE_TEMPORAL_FRAMES_ABSOLUTE_MAX = 100_000
    const val DEFAULT_DENOISE_TEMPORAL_FRAMES = 3

    // ---------- §11 低光 / 夜景视频 ----------
    /** 增益提升（EV）下限：0 = 不额外提亮（既有行为）。 */
    const val NIGHT_GAIN_BOOST_EV_MIN = 0f
    /** 增益提升上限（+2 EV）。 */
    const val NIGHT_GAIN_BOOST_EV_MAX = 2f
    const val DEFAULT_NIGHT_GAIN_BOOST_EV = 0f
    const val NIGHT_TEMPORAL_STRENGTH_MIN = 0f
    const val NIGHT_TEMPORAL_STRENGTH_MAX = 1f
    const val DEFAULT_NIGHT_TEMPORAL_STRENGTH = 0.5f
    const val NIGHT_MAX_ISO_MIN = 100
    const val NIGHT_MAX_ISO_ABSOLUTE_MAX = 102_400
    const val DEFAULT_NIGHT_MAX_ISO = 6_400
    /** 夜景视频最长单帧快门（1/24s 起，避免运动拖影；可配到 sensor 上限）。 */
    const val NIGHT_MAX_SHUTTER_NS_MIN = 1_000_000L
    const val NIGHT_MAX_SHUTTER_NS_ABSOLUTE_MAX = 10_000_000_000L
    const val DEFAULT_NIGHT_MAX_SHUTTER_NS = 1_000_000_000L / 30

    // ---------- §9 实时视频滤镜 ----------
    const val FILTER_INTENSITY_MIN = 0f
    /** 滤镜/LUT 强度上限（与既有 VideoColorEffectLayer.lutIntensity 文档一致：最高 2.0）。 */
    const val FILTER_INTENSITY_MAX = 2f
    const val DEFAULT_FILTER_INTENSITY = 1f

    // ---------- §7 手动参数视频档 ----------
    /** 0 表示自动（由 AE 决定）。 */
    const val MANUAL_SHUTTER_NS_AUTO = 0L
    const val MANUAL_SHUTTER_NS_ABSOLUTE_MAX = 10_000_000_000L
    /** 0 表示自动 ISO。 */
    const val MANUAL_ISO_AUTO = 0
    const val MANUAL_ISO_ABSOLUTE_MAX = 102_400
    /** 0 表示自动白平衡。 */
    const val MANUAL_AWB_K_AUTO = 0
    const val MANUAL_AWB_K_MIN = 2_000
    const val MANUAL_AWB_K_ABSOLUTE_MAX = 12_000
    /** 负值表示自动对焦。 */
    const val MANUAL_FOCUS_DIOPTER_AUTO = -1f
    const val MANUAL_FOCUS_DIOPTER_MIN = 0f
    const val MANUAL_FOCUS_DIOPTER_ABSOLUTE_MAX = 20f
    const val DEFAULT_LENS_SWITCH_SETTLE_MS = 300L
    const val LENS_SWITCH_SETTLE_MS_MIN = 0L
    const val LENS_SWITCH_SETTLE_MS_MAX = 5_000L
}
