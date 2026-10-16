package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.astronav.AstroNavConfig
import com.hinnka.mycamera.astronav.CameraAxis
import com.hinnka.mycamera.astronav.DeclinationSource
import com.hinnka.mycamera.astronav.FusionAlgorithm
import com.hinnka.mycamera.astronav.SmoothingMode
import com.hinnka.mycamera.funshot.FunshotRanges
import com.hinnka.mycamera.funshot.FunshotTriggerMode
import com.hinnka.mycamera.funshot.FunshotWorkingScale
import com.hinnka.mycamera.funshot.PanoramaQuality
import com.hinnka.mycamera.longexposure.DarkFrameConfig
import com.hinnka.mycamera.longexposure.FireworksConfig
import com.hinnka.mycamera.longexposure.HotPixelMode
import com.hinnka.mycamera.longexposure.LightPaintingConfig
import com.hinnka.mycamera.longexposure.LocalToneMapConfig
import com.hinnka.mycamera.longexposure.LongExposureConfigCodec
import com.hinnka.mycamera.longexposure.LongExposureLimits
import com.hinnka.mycamera.longexposure.LongExposureStackMode
import com.hinnka.mycamera.longexposure.SuperMoonConfig

/**
 * 目录④：趣味拍摄（`funshot`）+ 长曝光（`longexposure`）+ 天文导航（`astronav`）。
 *
 * 键名说明：
 * - funshot：键为模块已定义的 `funshot_*` 字面量（与各 Codec 的 `KEY_*` 常量逐字一致）。
 * - longexposure：优先用 `LongExposureConfigCodec.KEY_*`；少数字段模块未声明常量，
 *   本页按 `long_exposure_<字段>` 约定命名并在说明里标注（接线时由集成层确认）。
 * - astronav：模块只提供 `configProvider` 注入点、未声明键，本页按 `astronav.<字段>` 约定命名。
 */
object ProSettingsCatalogFun {

    /** astronav 的约定键（模块未声明持久化常量）。 */
    private fun ak(field: String): String = "astronav.$field"

    /** longexposure 的约定键（模块未声明对应 KEY_* 常量）。 */
    private fun lk(field: String): String = "long_exposure_$field"

    val entries: List<ProSettingSpec> = buildList {

        // ═════════════════════════════════════════ 全景拼接（funshot_pano_*）

        add(ProSettingBuilder.it("funshot.pano.frameCount", "funshot_pano_frame_count", "拼接帧数",
            "参与拼接的帧数；无算法上限，只做输入卫生 clamp", 6,
            FunshotRanges.PANO_FRAME_MIN, FunshotRanges.PANO_FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.dd("funshot.pano.quality", "funshot_pano_quality", "投影方式",
            "PLANAR = 平面；CYLINDRICAL = 柱面", PanoramaQuality.PLANAR.name,
            PanoramaQuality.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.focalLengthPx", "funshot_pano_focal", "柱面焦距",
            "像素；0 = 自动", 0f, 0f, 8192f, 1, "px", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.autoFocalRatio", "funshot_pano_auto_focal", "自动焦距比例",
            "自动焦距 = 短边 × 该比例", 0.9f, 0.4f, 2f, 2, "", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.maxCorners", "funshot_pano_max_corners", "角点上限",
            "Harris 角点上限", 800, 100, 4000, 20, "", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.cornerQuality", "funshot_pano_corner_quality", "角点质量门限",
            "相对最大响应", 0.01f, 0.0001f, 0.2f, 4, "", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.minCornerDistance", "funshot_pano_min_corner_dist", "角点最小间距",
            "像素", 8, 2, 64, 1, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.descriptorBits", "funshot_pano_desc_bits", "描述子位数",
            "必须是 64 的倍数（不足向下取整）", 256, 64, 512, 64, "bit", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.matchRatio", "funshot_pano_match_ratio", "Lowe ratio 门限",
            "越小越严格", 0.72f, 0.5f, 0.95f, 2, "", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.ransacIterations", "funshot_pano_ransac_iters", "RANSAC 迭代上限",
            "次", 2000, 100, 20000, 100, "次", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.ransacThresholdPx", "funshot_pano_ransac_threshold",
            "内点重投影阈值", "像素", 3.5f, 0.5f, 20f, 2, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.minInliers", "funshot_pano_min_inliers", "最少内点",
            "单应有效所需最少内点", 12, 4, 200, 1, "", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.minInlierRatio", "funshot_pano_min_inlier_ratio", "内点占比门限",
            "0..1", 0.2f, 0.05f, 1f, 2, "", "ProCam"))
        add(ProSettingBuilder.sw("funshot.pano.exposureCompensation", "funshot_pano_exposure_comp",
            "逐帧曝光补偿", "拼接前统一曝光", true, "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.gainLimit", "funshot_pano_gain_limit", "曝光补偿增益上限",
            "1 = 不补偿", 1.6f, 1f, 4f, 2, "×", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.featherRadiusPx", "funshot_pano_feather", "羽化半径",
            "像素", 24, 0, 128, 1, "px", "ProCam"))
        add(ProSettingBuilder.fl("funshot.pano.blendGamma", "funshot_pano_blend_gamma", "融合权重伽马",
            "0.2..3", 1f, 0.2f, 3f, 2, "", "ProCam"))
        add(ProSettingBuilder.dd("funshot.pano.workingScale", "funshot_pano_working_scale", "起始工作分辨率",
            "内存不足自动再降", FunshotWorkingScale.THREE_QUARTER.name,
            FunshotWorkingScale.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.inputMaxSide", "funshot_pano_input_max_side", "输入长边上限",
            "送入帧长边上限", 1600, FunshotRanges.INPUT_MAX_SIDE_MIN,
            FunshotRanges.INPUT_MAX_SIDE_MAX, 64, "px", "ProCam"))
        add(ProSettingBuilder.lt("funshot.pano.maxOutputPixels", "funshot_pano_max_output_pixels",
            "输出像素上限", "超出等比缩小（只降不拒）", 64L * 1024 * 1024,
            4_000_000L, 256_000_000L, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.pano.outputJpegQuality", "funshot_pano_jpeg_quality",
            "输出 JPEG 质量", "60..100", 95, 60, 100, 1, "", "OpenCam"))
        add(ProSettingBuilder.lt("funshot.pano.memoryBudgetBytes", "funshot_pano_memory_budget", "内存预算",
            "中间缓冲预算（字节）", 768L * 1024 * 1024,
            32L * 1024 * 1024, 2L * 1024 * 1024 * 1024, "B", "ProCam"))
        add(ProSettingBuilder.sw("funshot.pano.autoDegrade", "funshot_pano_auto_degrade", "自动降级",
            "内存不足时降工作分辨率", true, "ProCam"))

        // ═════════════════════════════════════════ 声控触发（funshot_sound_*）

        add(ProSettingBuilder.sw("funshot.sound.enabled", "funshot_sound_enabled", "声控触发",
            "拍手 / 喊声触发快门或录制", true, "MotionCam,ProCam"))
        add(ProSettingBuilder.it("funshot.sound.sampleRate", "funshot_sound_sample_rate", "采样率",
            "Hz", 16_000, FunshotRanges.SOUND_SAMPLE_RATE_MIN,
            FunshotRanges.SOUND_SAMPLE_RATE_MAX, 1_000, "Hz", "MotionCam"))
        add(ProSettingBuilder.it("funshot.sound.frameSize", "funshot_sound_frame_size", "音频帧长",
            "样本", 1024, 128, 8192, 128, "", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.sound.thresholdDbfs", "funshot_sound_threshold_dbfs", "触发阈值",
            "dBFS；越大越不敏感", -32f, -80f, 0f, 1, "dBFS", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.sound.sensitivity", "funshot_sound_sensitivity", "灵敏度",
            "0..1（与阈值叠加）", 0.5f, 0f, 1f, 2, "", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.sound.minDurationMs", "funshot_sound_min_duration_ms", "最短持续",
            "毫秒", 150L, 20L, 2_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.sound.cooldownMs", "funshot_sound_cooldown_ms", "冷却时间",
            "毫秒", 1_500L, 200L, 10_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.sound.hysteresisDb", "funshot_sound_hysteresis_db", "迟滞",
            "dB；防抖动重复触发", 6f, 0f, 24f, 1, "dB", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.sound.releaseHoldMs", "funshot_sound_release_hold_ms", "释放保持",
            "毫秒", 120L, 0L, 1_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.dd("funshot.sound.mode", "funshot_sound_mode", "触发动作",
            "PHOTO = 拍照；VIDEO = 录影", FunshotTriggerMode.PHOTO.name,
            FunshotTriggerMode.entries.map { it.name }, "MotionCam"))
        add(ProSettingBuilder.lt("funshot.sound.minRecordingMs", "funshot_sound_min_recording_ms", "最短录制",
            "毫秒（VIDEO 模式）", 2_000L, 500L, 60_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.sound.maxRecordingMs", "funshot_sound_max_recording_ms", "最长录制",
            "毫秒（VIDEO 模式）", 120_000L, 1_000L, 600_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.sw("funshot.sound.autoStopEnabled", "funshot_sound_auto_stop", "自动停止",
            "超过最长录制自动停", true, "MotionCam"))
        add(ProSettingBuilder.sw("funshot.sound.useUnprocessedSource", "funshot_sound_unprocessed_source",
            "使用未处理音源", "绕过系统 AGC / 降噪，阈值更稳", true, "MotionCam"))

        // ═════════════════════════════════════════ 运动触发（funshot_motion_*）

        add(ProSettingBuilder.sw("funshot.motion.enabled", "funshot_motion_enabled", "运动触发",
            "抖动 / 移动触发拍摄（偷拍 / 抓拍）", true, "MotionCam"))
        add(ProSettingBuilder.fl("funshot.motion.threshold", "funshot_motion_threshold", "触发阈值",
            "加速度偏差倍数", 1.3f, 0.05f, 20f, 2, "", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.motion.hysteresisFactor", "funshot_motion_hysteresis_factor",
            "迟滞系数", "停止阈值 = 触发阈值 × 该系数", 0.6f, 0.1f, 0.9f, 2, "", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.motion.windowMs", "funshot_motion_window_ms", "判定窗口",
            "毫秒", 80L, 20L, 2_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.motion.cooldownMs", "funshot_motion_cooldown_ms", "冷却时间",
            "毫秒", 1_200L, 200L, 10_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.motion.sensitivity", "funshot_motion_sensitivity", "灵敏度",
            "0..1；内部增益 = 1 + 灵敏度×3", 0.5f, 0f, 1f, 2, "", "MotionCam"))
        add(ProSettingBuilder.fl("funshot.motion.gravityAlpha", "funshot_motion_gravity_alpha", "重力低通系数",
            "越小越稳、越滞后", 0.08f, 0.02f, 0.5f, 3, "", "MotionCam"))
        add(ProSettingBuilder.it("funshot.motion.sampleDelayUs", "funshot_motion_sample_delay_us", "采样延迟",
            "微秒；0 = 关（关闭延迟采样）", 20_000, 0, 200_000, 1_000, "us", "MotionCam"))
        add(ProSettingBuilder.it("funshot.motion.axisMask", "funshot_motion_axis_mask", "参与轴掩码",
            "位掩码：1=X，2=Y，4=Z（可相加）", 7,
            FunshotRanges.MOTION_AXIS_MASK_MIN, FunshotRanges.MOTION_AXIS_MASK_MAX, 1, "", "MotionCam"))
        add(ProSettingBuilder.dd("funshot.motion.mode", "funshot_motion_mode", "触发动作",
            "PHOTO = 拍照；VIDEO = 录影", FunshotTriggerMode.PHOTO.name,
            FunshotTriggerMode.entries.map { it.name }, "MotionCam"))
        add(ProSettingBuilder.lt("funshot.motion.minRecordingMs", "funshot_motion_min_recording_ms", "最短录制",
            "毫秒（VIDEO 模式）", 2_000L, 500L, 60_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.lt("funshot.motion.maxRecordingMs", "funshot_motion_max_recording_ms", "最长录制",
            "毫秒（VIDEO 模式）", 120_000L, 1_000L, 600_000L, "ms", "MotionCam"))
        add(ProSettingBuilder.sw("funshot.motion.autoStopEnabled", "funshot_motion_auto_stop", "自动停止",
            "超过最长录制自动停", true, "MotionCam"))

        // ═════════════════════════════════════════ GIF / 动图（funshot_gif_*）

        add(ProSettingBuilder.it("funshot.gif.frameIntervalMs", "funshot_gif_frame_interval_ms", "帧间隔",
            "毫秒；GIF 时间单位 = 1/100 秒", 100, 20, 5_000, 10, "ms", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.holdLastFrameMs", "funshot_gif_hold_last_frame_ms", "末帧停留",
            "毫秒", 0, 0, 5_000, 10, "ms", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.loopCount", "funshot_gif_loop_count", "循环次数",
            "0 = 无限循环", 0, 0, 65_535, 1, "次", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.maxWidth", "funshot_gif_max_width", "最大宽度",
            "像素", 480, 32, 1_024, 32, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.maxHeight", "funshot_gif_max_height", "最大高度",
            "像素", 480, 32, 1_024, 32, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.maxColors", "funshot_gif_max_colors", "最大颜色数",
            "2..256", 256, 2, 256, 1, "", "ProCam"))
        add(ProSettingBuilder.sw("funshot.gif.perFramePalette", "funshot_gif_per_frame_palette",
            "逐帧调色板", "每帧独立调色，质量更好但体积更大", false, "ProCam"))
        add(ProSettingBuilder.sw("funshot.gif.dither", "funshot_gif_dither", "抖动",
            "有序抖动，减少色带", false, "ProCam"))
        add(ProSettingBuilder.fl("funshot.gif.ditherStrength", "funshot_gif_dither_strength", "抖动强度",
            "0..1", 0.6f, 0f, 1f, 2, "", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.maxFrames", "funshot_gif_max_frames", "最大帧数",
            "不设算法上限", 300, FunshotRanges.GIF_FRAME_MIN, FunshotRanges.GIF_FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.videoFrameRate", "funshot_gif_video_fps", "视频转 GIF 帧率",
            "fps", 12, 1, 50, 1, "fps", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.videoTargetWidth", "funshot_gif_video_width", "视频转 GIF 宽度",
            "像素", 480, 32, 1_024, 32, "px", "ProCam"))
        add(ProSettingBuilder.it("funshot.gif.videoMaxFrames", "funshot_gif_video_max_frames", "视频转 GIF 帧数",
            "上限 600", 120, 1, 600, 10, "帧", "ProCam"))
        add(ProSettingBuilder.lt("funshot.gif.memoryBudgetBytes", "funshot_gif_memory_budget", "内存预算",
            "字节", 256L * 1024 * 1024,
            8L * 1024 * 1024, 1024L * 1024 * 1024, "B", "ProCam"))

        // ═════════════════════════════════════════ 间隔拍摄（funshot_interval_*）

        add(ProSettingBuilder.sw("funshot.interval.enabled", "funshot_interval_enabled", "间隔拍摄",
            "定时多张拍摄（可与既有倒计时 UI 复用）", false, "ProCam,OpenCam"))
        add(ProSettingBuilder.it("funshot.interval.startDelaySec", "funshot_interval_start_delay_sec",
            "开始延时", "秒；映射到既有定时器档位（0/3/5/10）", 3, 0,
            FunshotRanges.INTERVAL_TIMER_MAX_SEC, 1, "s", "ProCam"))
        add(ProSettingBuilder.it("funshot.interval.shotCount", "funshot_interval_shot_count", "拍摄张数",
            "1..200", 10, 1, FunshotRanges.INTERVAL_SHOT_MAX, 1, "张", "ProCam"))
        add(ProSettingBuilder.lt("funshot.interval.intervalMs", "funshot_interval_ms", "拍摄间隔",
            "毫秒", 1_000L, FunshotRanges.INTERVAL_MIN_MS, FunshotRanges.INTERVAL_MAX_MS, "ms", "ProCam"))
        add(ProSettingBuilder.lt("funshot.interval.intervalJitterMs", "funshot_interval_jitter_ms", "间隔抖动",
            "毫秒；0 = 精确定时", 0L, 0L, FunshotRanges.INTERVAL_JITTER_MAX_MS, "ms", "ProCam"))
        add(ProSettingBuilder.sw("funshot.interval.shutterSound", "funshot_interval_shutter_sound",
            "快门音", "跟随系统快门音设置", true, "OpenCam"))
        add(ProSettingBuilder.sw("funshot.interval.reuseExistingTimer", "funshot_interval_reuse_timer",
            "复用既有定时器", "复用工程既有倒计时实现", true, "OpenCam"))
        add(ProSettingBuilder.sw("funshot.interval.autoStopOnError", "funshot_interval_auto_stop",
            "出错自动停止", "单张失败即停（避免连拍空转）", true, "ProCam"))

        // ═════════════════════════════════════════ 长曝光 · 光绘（long_exposure_light_painting_*）

        add(ProSettingBuilder.it("longexposure.lightPainting.frameCount",
            LongExposureConfigCodec.KEY_LP_FRAMES, "光绘帧数", "不设算法上限；内存不够请降工作分辨率",
            30, LongExposureLimits.FRAME_MIN, LongExposureLimits.ABSOLUTE_FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.dd("longexposure.lightPainting.stackMode",
            LongExposureConfigCodec.KEY_LP_STACK_MODE, "叠加方式",
            "MAX = 亮部只增不减（光轨）；MEAN = 平均（雾化水流）", LongExposureStackMode.MAX.name,
            LongExposureStackMode.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.it("longexposure.lightPainting.brushTemperatureK",
            LongExposureConfigCodec.KEY_LP_BRUSH_TEMPERATURE, "笔刷色温", "开尔文",
            3200, LightPaintingConfig.COLOR_TEMP_MIN, LightPaintingConfig.COLOR_TEMP_MAX, 100, "K", "ProCam,OpenCam"))
        add(ProSettingBuilder.fl("longexposure.lightPainting.brushTint",
            LongExposureConfigCodec.KEY_LP_BRUSH_TINT, "笔刷色调", "绿-品红偏移",
            0f, LightPaintingConfig.TINT_MIN, LightPaintingConfig.TINT_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.lightPainting.brushGain",
            LongExposureConfigCodec.KEY_LP_BRUSH_GAIN, "笔刷增益", "亮部加权",
            1f, LightPaintingConfig.BRUSH_GAIN_MIN, LightPaintingConfig.BRUSH_GAIN_MAX, 2, "×", "ProCam"))
        add(ProSettingBuilder.sw("longexposure.lightPainting.incrementalPreviewEnabled",
            LongExposureConfigCodec.KEY_LP_PREVIEW, "实时叠加预览", "边拍边看合成结果", true, "ProCam"))
        add(ProSettingBuilder.it("longexposure.lightPainting.previewIntervalFrames",
            LongExposureConfigCodec.KEY_LP_PREVIEW_INTERVAL, "预览刷新间隔",
            "每 N 帧刷新一次预览", 4, LightPaintingConfig.PREVIEW_INTERVAL_MIN,
            LightPaintingConfig.PREVIEW_INTERVAL_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.it("longexposure.lightPainting.previewMaxLongEdge", lk("light_painting_preview_edge"),
            "预览长边上限", "像素", 480, LightPaintingConfig.PREVIEW_EDGE_MIN,
            LightPaintingConfig.PREVIEW_EDGE_MAX, 64, "px", "ProCam"))
        add(ProSettingBuilder.it("longexposure.lightPainting.previewThrottleMs",
            lk("light_painting_preview_throttle"), "预览节流",
            "毫秒", 120, LightPaintingConfig.PREVIEW_THROTTLE_MIN.toInt(),
            LightPaintingConfig.PREVIEW_THROTTLE_MAX.toInt(), 8, "ms", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.lightPainting.workScale", lk("light_painting_work_scale"),
            "工作分辨率系数", "内存不足时先降这里（不要降帧数）", 1f,
            LongExposureLimits.MIN_WORK_SCALE, LongExposureLimits.MAX_WORK_SCALE, 3, "", "ProCam"))
        add(ProSettingBuilder.it("longexposure.lightPainting.workLongEdge", lk("light_painting_work_long_edge"),
            "工作长边上限", "像素；0 = 不限", 0, 0, 8192, 64, "px", "ProCam"))
        add(ProSettingBuilder.it("longexposure.frameCountLimit", LongExposureConfigCodec.KEY_FRAME_COUNT_LIMIT,
            "全局帧数安全上限", "所有长曝光模式共用的防误设上限",
            120, LongExposureLimits.FRAME_MIN, LongExposureLimits.ABSOLUTE_FRAME_MAX, 1, "帧", "ProCam"))

        // ═════════════════════════════════════════ 长曝光 · 暗帧（long_exposure_dark_frame_*）

        add(ProSettingBuilder.sw("longexposure.darkFrame.enabled",
            LongExposureConfigCodec.KEY_LP_DARK_ENABLED, "暗帧扣除", "扣除暗电流与热噪点", true, "ProCam"))
        add(ProSettingBuilder.it("longexposure.darkFrame.frameCount",
            LongExposureConfigCodec.KEY_LP_DARK_FRAMES, "暗帧数量", "越多越稳、越慢",
            8, DarkFrameConfig.FRAME_MIN, DarkFrameConfig.FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.darkFrame.subtractGain",
            LongExposureConfigCodec.KEY_LP_DARK_GAIN, "扣除增益", "0 = 不扣，1 = 全额扣",
            1f, DarkFrameConfig.GAIN_MIN, DarkFrameConfig.GAIN_MAX, 2, "×", "ProCam"))
        add(ProSettingBuilder.dd("longexposure.darkFrame.hotPixelMode", lk("dark_frame_hot_pixel_mode"),
            "坏点处理", "OFF = 仅暗帧减法；ZERO = 置零掩膜",
            HotPixelMode.OFF.name, HotPixelMode.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.it("longexposure.darkFrame.hotPixelThreshold", lk("dark_frame_hot_pixel_threshold"),
            "坏点判定阈值", "暗帧样本超过该值判为坏点", 0, 0, 255, 1, "", "ProCam"))
        add(ProSettingBuilder.sw("longexposure.darkFrame.reuseCached", lk("dark_frame_reuse_cached"),
            "复用缓存暗帧", "同参数下复用上次暗帧，省时间", true, "ProCam"))

        // ═════════════════════════════════════════ 长曝光 · 烟火（long_exposure_fireworks_*）

        add(ProSettingBuilder.it("longexposure.fireworks.frameCount",
            LongExposureConfigCodec.KEY_FW_FRAMES, "烟火帧数", "短曝光 burst 叠加",
            20, LongExposureLimits.FRAME_MIN, LongExposureLimits.ABSOLUTE_FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.dd("longexposure.fireworks.stackMode",
            LongExposureConfigCodec.KEY_FW_STACK_MODE, "叠加方式", "烟火建议 MAX",
            LongExposureStackMode.MAX.name, LongExposureStackMode.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.lt("longexposure.fireworks.burstShutterSpeedNs",
            LongExposureConfigCodec.KEY_FW_SHUTTER_NS, "单帧快门", "纳秒",
            33_333_333L, FireworksConfig.SHUTTER_MIN_NS, FireworksConfig.SHUTTER_MAX_NS, "ns", "ProCam"))
        add(ProSettingBuilder.it("longexposure.fireworks.iso", LongExposureConfigCodec.KEY_FW_ISO,
            "ISO", "固定 ISO 以稳定亮度", 400, FireworksConfig.ISO_MIN,
            FireworksConfig.ISO_MAX, 50, "", "ProCam"))
        add(ProSettingBuilder.it("longexposure.fireworks.preBurstFrames", lk("fireworks_pre_burst_frames"),
            "预拍帧数", "按下快门前先缓存 N 帧", 0, 0, FireworksConfig.PRE_BURST_MAX, 1, "帧", "ProCam"))

        // ═════════════════════════════════════════ 长曝光 · 超级月亮（long_exposure_super_moon_*）

        add(ProSettingBuilder.it("longexposure.superMoon.frameCount",
            LongExposureConfigCodec.KEY_SM_FRAMES, "堆栈帧数", "多帧堆栈提升信噪比",
            40, LongExposureLimits.FRAME_MIN, LongExposureLimits.ABSOLUTE_FRAME_MAX, 1, "帧", "ProCam"))
        add(ProSettingBuilder.sw("longexposure.superMoon.align",
            LongExposureConfigCodec.KEY_SM_ALIGN, "帧间对齐", "月亮移动不大时建议开", true, "ProCam"))
        add(ProSettingBuilder.fl("longexposure.superMoon.sigmaClipSigma", lk("super_moon_sigma_clip_sigma"),
            "σ-clip 强度", "剔除异常样本", 2.5f, SuperMoonConfig.SIGMA_MIN,
            SuperMoonConfig.SIGMA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.sw("longexposure.superMoon.toneMap",
            LongExposureConfigCodec.KEY_SM_TONEMAP, "月亮局部色调映射", "保高光细节", true, "ProCam"))
        add(ProSettingBuilder.fl("longexposure.superMoon.highlightThreshold",
            LongExposureConfigCodec.KEY_SM_HIGHLIGHT_THRESHOLD, "高光阈值", "超过即压缩",
            0.80f, LocalToneMapConfig.THRESHOLD_MIN, LocalToneMapConfig.THRESHOLD_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.toneMap.highlightCompression", lk("tone_map_compression"),
            "高光压缩", "0 = 不压缩", 1.2f, LocalToneMapConfig.COMPRESSION_MIN,
            LocalToneMapConfig.COMPRESSION_MAX, 2, "×", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.toneMap.shadowLift", lk("tone_map_shadow_lift"),
            "暗部提亮", "0..1", 0.15f, LocalToneMapConfig.LIFT_MIN,
            LocalToneMapConfig.LIFT_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.toneMap.gamma", lk("tone_map_gamma"),
            "伽马", "0.3..3", 1f, LocalToneMapConfig.GAMMA_MIN, LocalToneMapConfig.GAMMA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.it("longexposure.toneMap.localRadius", lk("tone_map_local_radius"),
            "局部分析半径", "像素", 24, LocalToneMapConfig.RADIUS_MIN,
            LocalToneMapConfig.RADIUS_MAX, 2, "px", "ProCam"))
        add(ProSettingBuilder.fl("longexposure.toneMap.localAmount",
            LongExposureConfigCodec.KEY_SM_LOCAL_AMOUNT, "局部强度", "0..2",
            0.35f, LocalToneMapConfig.AMOUNT_MIN, LocalToneMapConfig.AMOUNT_MAX, 2, "", "ProCam"))

        // ═════════════════════════════════════════ 天文导航（astronav，约定键 astronav.<字段>）

        add(ProSettingBuilder.lt("astronav.locationUpdateIntervalMs", ak("locationUpdateIntervalMs"),
            "定位刷新间隔", "毫秒", 1_000L,
            AstroNavConfig.LOCATION_INTERVAL_MIN, AstroNavConfig.LOCATION_INTERVAL_MAX, "ms", "OpenCam"))
        add(ProSettingBuilder.fl("astronav.locationMinDistanceM", ak("locationMinDistanceM"),
            "定位最小位移", "米；0 = 不设阈值", 0f,
            AstroNavConfig.LOCATION_MIN_DISTANCE_MIN, AstroNavConfig.LOCATION_MIN_DISTANCE_MAX, 1, "m", "OpenCam"))
        add(ProSettingBuilder.sw("astronav.preferGpsOverNetwork", ak("preferGpsOverNetwork"),
            "优先 GPS", "关闭则优先网络定位（更快但更粗）", true, "OpenCam"))
        add(ProSettingBuilder.lt("astronav.locationTimeoutMs", ak("locationTimeoutMs"),
            "定位超时", "毫秒", 15_000L,
            AstroNavConfig.LOCATION_TIMEOUT_MIN, AstroNavConfig.LOCATION_TIMEOUT_MAX, "ms", "OpenCam"))
        add(ProSettingBuilder.sw("astronav.allowManualFallback", ak("allowManualFallback"),
            "允许手动坐标兜底", "定位失败时可手填坐标", true, "OpenCam"))
        add(ProSettingBuilder.num("astronav.altitudeMeters", ak("altitudeMeters"),
            "海拔", "米；影响月亮视位置", 0f,
            AstroNavConfig.ALTITUDE_MIN.toFloat(), AstroNavConfig.ALTITUDE_MAX.toFloat(), 1, "m", "ProCam"))
        add(ProSettingBuilder.num("astronav.modelDecimalYear", ak("modelDecimalYear"),
            "磁模型年份", "小数年（如 2026.75）", 2026.75f,
            AstroNavConfig.MODEL_YEAR_MIN.toFloat(), AstroNavConfig.MODEL_YEAR_MAX.toFloat(), 2, "", "ProCam"))
        add(ProSettingBuilder.sw("astronav.autoModelYear", ak("autoModelYear"),
            "自动取当前年份", "开启则忽略手动年份", true, "ProCam"))
        add(ProSettingBuilder.it("astronav.sensorSamplingPeriodUs", ak("sensorSamplingPeriodUs"),
            "传感器采样周期", "微秒", 10_000,
            AstroNavConfig.SAMPLING_PERIOD_MIN, AstroNavConfig.SAMPLING_PERIOD_MAX, 1_000, "us", "ProCam"))
        add(ProSettingBuilder.lt("astronav.attitudeUpdateIntervalMs", ak("attitudeUpdateIntervalMs"),
            "姿态回调间隔", "毫秒（前台轻量路径）", 20L,
            AstroNavConfig.ATTITUDE_INTERVAL_MIN, AstroNavConfig.ATTITUDE_INTERVAL_MAX, "ms", "ProCam"))
        add(ProSettingBuilder.dd("astronav.fusionAlgorithm", ak("fusionAlgorithm"), "姿态解算算法",
            "FUSION_AHRS 综合最好", FusionAlgorithm.FUSION_AHRS.name,
            FusionAlgorithm.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.fl("astronav.fusionGain", ak("fusionGain"), "反馈增益",
            "0 = 纯陀螺积分", 0.5f,
            AstroNavConfig.FUSION_GAIN_MIN, AstroNavConfig.FUSION_GAIN_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("astronav.startupGain", ak("startupGain"), "启动期增益",
            "快速收敛", 10f,
            AstroNavConfig.STARTUP_GAIN_MIN, AstroNavConfig.STARTUP_GAIN_MAX, 1, "", "ProCam"))
        add(ProSettingBuilder.fl("astronav.startupPeriodS", ak("startupPeriodS"), "启动期时长",
            "秒", 3f, AstroNavConfig.STARTUP_PERIOD_MIN, AstroNavConfig.STARTUP_PERIOD_MAX, 1, "s", "ProCam"))
        add(ProSettingBuilder.fl("astronav.accelerationRejectionDeg", ak("accelerationRejectionDeg"),
            "加速度拒绝阈值", "度；0 = 关闭", 10f,
            AstroNavConfig.REJECTION_DEG_MIN, AstroNavConfig.REJECTION_DEG_MAX, 1, "°", "ProCam"))
        add(ProSettingBuilder.fl("astronav.magneticRejectionDeg", ak("magneticRejectionDeg"),
            "磁力拒绝阈值", "度；0 = 关闭", 10f,
            AstroNavConfig.REJECTION_DEG_MIN, AstroNavConfig.REJECTION_DEG_MAX, 1, "°", "ProCam"))
        add(ProSettingBuilder.fl("astronav.rejectionTimeoutS", ak("rejectionTimeoutS"),
            "拒绝恢复超时", "秒；0 = 关闭", 5f,
            AstroNavConfig.REJECTION_TIMEOUT_MIN, AstroNavConfig.REJECTION_TIMEOUT_MAX, 1, "s", "ProCam"))
        add(ProSettingBuilder.fl("astronav.gyroscopeRangeDps", ak("gyroscopeRangeDps"),
            "陀螺量程", "度/秒；0 = 不做超量程检测", 2_000f,
            AstroNavConfig.GYRO_RANGE_MIN, AstroNavConfig.GYRO_RANGE_MAX, 100, "°/s", "ProCam"))
        add(ProSettingBuilder.fl("astronav.complementaryAlpha", ak("complementaryAlpha"),
            "互补滤波系数", "陀螺权重", 0.98f,
            AstroNavConfig.COMPLEMENTARY_ALPHA_MIN, AstroNavConfig.COMPLEMENTARY_ALPHA_MAX, 3, "", "ProCam"))
        add(ProSettingBuilder.dd("astronav.smoothingMode", ak("smoothingMode"), "抖动抑制策略",
            "ONE_EURO 兼顾平滑与跟随", SmoothingMode.ONE_EURO.name,
            SmoothingMode.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.fl("astronav.exponentialAlpha", ak("exponentialAlpha"),
            "指数平滑系数", "越小越平滑、越滞后", 0.35f,
            AstroNavConfig.EXPONENTIAL_ALPHA_MIN, AstroNavConfig.EXPONENTIAL_ALPHA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("astronav.oneEuroMinCutoff", ak("oneEuroMinCutoff"),
            "1€ 最小截止频率", "Hz", 1.2f,
            AstroNavConfig.ONE_EURO_MIN_CUTOFF_MIN, AstroNavConfig.ONE_EURO_MIN_CUTOFF_MAX, 2, "Hz", "ProCam"))
        add(ProSettingBuilder.fl("astronav.oneEuroBeta", ak("oneEuroBeta"), "1€ 速度系数",
            "越大跟随越快", 0.35f,
            AstroNavConfig.ONE_EURO_BETA_MIN, AstroNavConfig.ONE_EURO_BETA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.num("astronav.kalmanProcessNoise", ak("kalmanProcessNoise"),
            "卡尔曼过程噪声", "越小越平滑", 0.008f,
            AstroNavConfig.KALMAN_PROCESS_NOISE_MIN, AstroNavConfig.KALMAN_PROCESS_NOISE_MAX, 5, "", "ProCam"))
        add(ProSettingBuilder.num("astronav.kalmanMeasurementNoise", ak("kalmanMeasurementNoise"),
            "卡尔曼观测噪声", "越大越不信观测", 0.12f,
            AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MIN,
            AstroNavConfig.KALMAN_MEASUREMENT_NOISE_MAX, 4, "", "ProCam"))
        add(ProSettingBuilder.it("astronav.smoothingWindowSize", ak("smoothingWindowSize"),
            "滑动平均窗口", "样本", 5,
            AstroNavConfig.SMOOTHING_WINDOW_MIN, AstroNavConfig.SMOOTHING_WINDOW_MAX, 1, "", "ProCam"))
        add(ProSettingBuilder.dd("astronav.declinationSource", ak("declinationSource"), "磁偏角来源",
            "EMBEDDED_WMM 离线可用（推荐）", DeclinationSource.EMBEDDED_WMM.name,
            DeclinationSource.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.fl("astronav.declinationManualDeg", ak("declinationManualDeg"),
            "手动磁偏角", "度（MANUAL 来源生效）", 0f,
            AstroNavConfig.DECLINATION_MANUAL_MIN, AstroNavConfig.DECLINATION_MANUAL_MAX, 2, "°", "ProCam"))
        add(ProSettingBuilder.lt("astronav.declinationCacheTtlMs", ak("declinationCacheTtlMs"),
            "在线磁偏角缓存", "毫秒", 86_400_000L,
            AstroNavConfig.DECLINATION_CACHE_TTL_MIN,
            AstroNavConfig.DECLINATION_CACHE_TTL_MAX, "ms", "ProCam"))
        add(ProSettingBuilder.it("astronav.displayRotationDegrees", ak("displayRotationDegrees"),
            "屏幕显示旋转", "度；只允许 0/90/180/270（AstroNavConfig.DISPLAY_ROTATIONS，非 4 倍数就近吸附）", 0,
            0, 270, 90, "°", "ProCam"))
        add(ProSettingBuilder.dd("astronav.cameraAxis", ak("cameraAxis"), "取景相机",
            "BACK / FRONT", CameraAxis.BACK.name, CameraAxis.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.sw("astronav.trueNorthCorrection", ak("trueNorthCorrection"),
            "真北校正", "套用磁偏角得真北", true, "ProCam"))
        add(ProSettingBuilder.fl("astronav.manualOffsetDegrees", ak("manualOffsetDegrees"),
            "方向微调", "度；三脚架对齐补偿", 0f,
            AstroNavConfig.MANUAL_OFFSET_MIN, AstroNavConfig.MANUAL_OFFSET_MAX, 2, "°", "ProCam"))
        add(ProSettingBuilder.sw("astronav.requireCalibration", ak("requireCalibration"),
            "要求磁力校准", "未画 8 字校准则不给高可信姿态", true, "ProCam"))
        add(ProSettingBuilder.it("astronav.calibrateSampleCount", ak("calibrateSampleCount"),
            "校准采样点数", "样本", 300,
            AstroNavConfig.CALIBRATE_SAMPLE_MIN, AstroNavConfig.CALIBRATE_SAMPLE_MAX, 10, "", "ProCam"))
        add(ProSettingBuilder.sw("astronav.figureEightHintEnabled", ak("figureEightHintEnabled"),
            "显示画 8 字提示", "", true, "ProCam"))
        add(ProSettingBuilder.sw("astronav.heavyPathInBackground", ak("heavyPathInBackground"),
            "重路径走后台", "低优先队列，前台不掉帧", true, "ProCam"))
        add(ProSettingBuilder.lt("astronav.heavyPathThrottleMs", ak("heavyPathThrottleMs"),
            "重路径节流", "毫秒", 33L,
            AstroNavConfig.HEAVY_THROTTLE_MIN, AstroNavConfig.HEAVY_THROTTLE_MAX, "ms", "ProCam"))
        add(ProSettingBuilder.it("astronav.magCalibrationQualityMin", ak("magCalibrationQualityMin"),
            "磁力校准等级下限", "0..3（SensorManager 等级）", 2,
            AstroNavConfig.MAG_QUALITY_MIN, AstroNavConfig.MAG_QUALITY_MAX, 1, "", "ProCam"))
        add(ProSettingBuilder.it("astronav.recordMaxSamples", ak("recordMaxSamples"),
            "姿态录制缓存上限", "条；离线精修任务本身不设硬上限", 1800,
            AstroNavConfig.RECORD_SAMPLES_MIN, AstroNavConfig.RECORD_SAMPLES_MAX, 10, "条", "ProCam"))
    }
}
