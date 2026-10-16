package com.hinnka.mycamera.appgallerypreview

/**
 * 预览渲染器实现选择。
 *
 * - [TELEPHOTO]：复用工程既有依赖 `me.saket.telephoto:zoomable-image-coil:0.18.0`
 *   （Apache-2.0，saket/telephoto，1.6k★）承载手势与渲染（默认值，零新增依赖）。
 * - [COMPOSE_GESTURE]：改用本模块自研的 Compose 手势 + [AppGalleryPreviewZoomState]，
 *   供 Telephoto 不可用时降级，或需要完全自定义手势仲裁时使用。
 */
enum class PreviewScaleRenderer { TELEPHOTO, COMPOSE_GESTURE }

/** 预览页方向策略；[AUTO] 跟随系统/传感器，其余两个锁定。 */
enum class PreviewOrientationLock { AUTO, PORTRAIT, LANDSCAPE }

/** clamp 结果：修正后的配置 + 被修正字段名清单（供日志与自检使用）。 */
data class AppGalleryPreviewClampResult(
    val config: AppGalleryPreviewConfig,
    val correctedFields: List<String>,
) {
    val hasCorrection: Boolean get() = correctedFields.isNotEmpty()
}

/**
 * 应用内置独立相册 · 预览体验域全局配置（开放度硬约束）。
 *
 * 覆盖相册第 34-42 项：大图预览缩放、幻灯片放映、RAW 查看与处理、前后/原图对比、
 * GIF/视频预览播放、二次编辑入口、快捷编辑入口、播放手势、快捷操作。
 *
 * 所有可调参数均给出默认值 / 取值范围 / 越界 clamp，误设不会崩溃。
 * 范围说明见各字段 KDoc；clamp 见 [appGalleryPreviewSafe]。
 */
data class AppGalleryPreviewConfig(
    // ---------------- 34. 大图预览与手势缩放 ----------------
    /** 最小缩放倍率（适配后基准）。范围 [ZOOM_MIN_FLOOR, 4]。 */
    val minZoomFactor: Float = 1f,
    /** 最大缩放倍率。范围 [2, ZOOM_MAX_CEIL]。 */
    val maxZoomFactor: Float = 8f,
    /** 双击档位一：在 min 与 max 之间循环缩放的目标倍率。范围 [1, maxZoomFactor]。 */
    val doubleTapZoomFactor: Float = 2.5f,
    /** 双击档位二：再次双击进入的更大倍率。范围 [doubleTapZoomFactor, maxZoomFactor]。 */
    val doubleTapZoomFactorSecondary: Float = 4f,
    /** 边界外平移的橡皮筋比例。范围 [0, 1]，0=不允许越界。 */
    val panOverscrollFactor: Float = 0.35f,
    /** 翻页后是否复位缩放与平移。 */
    val zoomResetOnPageChange: Boolean = true,
    /** 是否允许左右滑动切换上一张/下一张。 */
    val swipeSwitchEnabled: Boolean = true,
    /** 滑动切页阈值：拖动位移占视口宽度比例。范围 [0.05, 0.6]。 */
    val swipeSwitchThresholdFraction: Float = 0.22f,
    /** 滑动切页的快速抛掷速度阈值（dp/s）。范围 [80, 4000]。 */
    val swipeVelocityThresholdDpPerSec: Float = 320f,
    /** 拖动过程中的跟手位移衰减（松手前）。范围 [0.1, 1]。 */
    val swipeFollowFactor: Float = 0.85f,
    /** 大图解码目标长边像素（内存守卫失败时按此降级）。范围 [512, 16384]。 */
    val decodeTargetMaxPx: Int = 2048,
    /** 缩放渲染器实现。 */
    val previewScaleRenderer: PreviewScaleRenderer = PreviewScaleRenderer.TELEPHOTO,

    // ---------------- 35. 幻灯片放映 ----------------
    /** 自动播放间隔（毫秒）。范围 [1000, 30000]。 */
    val slideshowIntervalMs: Long = 3000L,
    /** 播到末尾是否循环。 */
    val slideshowLoop: Boolean = true,
    /** 是否随机顺序播放。 */
    val slideshowShuffle: Boolean = false,
    /** 随机种子；0 表示每次进入随机播放时取系统时间（非确定性）。 */
    val slideshowShuffleSeed: Long = 0L,
    /** 进入预览页是否自动开始放映。 */
    val slideshowAutoStart: Boolean = false,
    /** 放映期间是否保持屏幕常亮。 */
    val slideshowKeepScreenOn: Boolean = true,
    /** 放映时是否显示进度指示。 */
    val slideshowShowProgress: Boolean = true,

    // ---------------- 36. RAW/DNG 查看与处理 ----------------
    /** DNG 预览解码目标长边像素。范围 [256, 8192]。 */
    val rawPreviewMaxDimension: Int = 2048,
    /** RAW 渲染工作分辨率倍率（内存不足时降级）。范围 [0.25, 1]。 */
    val rawRenderWorkingScale: Float = 1f,
    /** 直方图分箱数。范围 [32, 1024]。 */
    val rawHistogramBins: Int = 256,
    /** 高光过曝判定阈值（归一化亮度）。范围 [0.5, 1]。 */
    val rawOverexposureThreshold: Float = 0.98f,
    /** 暗部欠曝判定阈值。范围 [0, 0.5]。 */
    val rawUnderexposureThreshold: Float = 0.02f,
    /** 过曝像素占比超过该值时给出提示。范围 [0, 1]。 */
    val rawOverexposureWarnRatio: Float = 0.01f,
    /** 是否显示 RAW 直方图面板。 */
    val rawShowHistogram: Boolean = true,
    /** 是否显示过曝/欠曝提示条。 */
    val rawShowOverexposureHint: Boolean = true,
    /** RAW→JPEG 导出目标长边像素。范围 [512, 16384]。 */
    val rawExportMaxDimension: Int = 4096,
    /** RAW→JPEG 导出质量。范围 [1, 100]。 */
    val rawExportJpegQuality: Int = 95,
    /** 导出是否保留 EXIF。 */
    val rawExportPreserveExif: Boolean = true,
    /** 导出优先复用 DNG 内嵌预览图（更快）。 */
    val rawExportUseEmbeddedPreview: Boolean = true,
    /** 导出分片行数（流式写盘）。范围 [16, 4096]。 */
    val rawExportChunkRows: Int = 256,

    // ---------------- 37. 前后/原图对比 ----------------
    /** 对比滑杆初始位置比例。范围 [0, 1]。 */
    val compareSliderDefaultRatio: Float = 0.5f,
    /** 滑杆手柄触摸热区半径（dp）。范围 [8, 96]。 */
    val compareHandleTouchSlopDp: Float = 24f,
    /** 是否要求两种版本都存在才开放对比。 */
    val compareRequireBothVariants: Boolean = true,
    /** 是否显示"原图"/"处理后"标签。 */
    val compareShowLabels: Boolean = true,

    // ---------------- 38. GIF/视频预览播放 ----------------
    /** GIF 是否自动播放。 */
    val gifAutoPlay: Boolean = true,
    /** GIF 是否循环。 */
    val gifLoop: Boolean = true,
    /** GIF 解码目标长边像素。范围 [128, 4096]。 */
    val gifMaxDecodeDimensionPx: Int = 1080,
    /** 视频进入页面是否自动播放。 */
    val videoAutoPlay: Boolean = false,
    /** 视频是否循环播放。 */
    val videoLoop: Boolean = false,
    /** 视频是否显示系统控制条（关闭则仅手势控制）。 */
    val videoShowControls: Boolean = true,
    /** 书签跳转容差（毫秒），用于吸附到最近书签。范围 [0, 5000]。 */
    val videoBookmarkSeekToleranceMs: Long = 300L,
    /** 视频播放到末尾是否回到首个书签。 */
    val videoBookmarkLoopOnEnd: Boolean = false,

    // ---------------- 41. 播放手势 ----------------
    /** 是否启用播放手势层。 */
    val playbackGestureEnabled: Boolean = true,
    /** 双击快退/快进的步长（毫秒）。范围 [1000, 60000]。 */
    val doubleTapSeekMs: Long = 10000L,
    /** 横向拖动 seek 灵敏度（毫秒/每 dp）。范围 [10, 2000]。 */
    val dragSeekSensitivityMsPerDp: Float = 120f,
    /** 竖向拖动调节音量灵敏度（每 1 单位视口高度的音量增量）。范围 [0.05, 5]。 */
    val verticalDragVolumeSensitivity: Float = 0.6f,
    /** 竖向拖动调节亮度灵敏度。范围 [0.05, 5]。 */
    val verticalDragBrightnessSensitivity: Float = 0.6f,
    /** 单击是否切换播放/暂停。 */
    val singleTapTogglesPlayback: Boolean = true,
    /** 手势指示浮层持续时间（毫秒）。范围 [200, 3000]。 */
    val gestureIndicatorTimeoutMs: Long = 700L,

    // ---------------- 后台调度（BgJobRunner / BgTaskType.PREVIEW） ----------------
    /** 预览类任务分片节流间隔覆盖（毫秒）；-1 表示沿用 BgTaskType.PREVIEW 默认。范围 [-1, 60000]。 */
    val bgThrottleMs: Long = -1L,
    /** 预览类任务单批像素上限（分片单位）。范围 [4096, 4194304]。 */
    val bgChunkPixels: Int = 262144,
    /** 预览类任务内存预算覆盖（MB）；-1 表示沿用 BgTaskType.PREVIEW 默认。范围 [-1, 4096]。 */
    val bgMaxHeapMb: Int = -1,
    /** 单批最大帧/图数覆盖；-1 表示沿用默认。范围 [-1, 64]。 */
    val bgMaxFrameBatch: Int = -1,

    // ---------------- 42 / 横竖屏 / 深色模式 ----------------
    /** 方向策略。 */
    val orientationLock: PreviewOrientationLock = PreviewOrientationLock.AUTO,
    /** 是否跟随系统深色模式（关闭则强制浅色画布）。 */
    val followSystemDarkTheme: Boolean = true,
    /** 深色画布底色（ARGB 32 位无符号）。范围 [0, 0xFFFFFFFF]。 */
    val darkCanvasColorArgb: Long = 0xFF0E0E10L,
    /** 是否使用沉浸式（隐藏系统栏）chrome。 */
    val immersiveChrome: Boolean = true,
    /** chrome 自动隐藏延时（毫秒）；0 表示不自动隐藏。范围 [0, 30000]。 */
    val chromeAutoHideMs: Long = 4000L,
    /** 宽屏（横屏/平板）时工具栏是否放到右侧。 */
    val wideLayoutToolbarOnSide: Boolean = true,
    /** 是否显示顶部媒体计数（当前/总数）。 */
    val showMediaCounter: Boolean = true,
) {

    /** clamp 并返回修正报告（误设不崩溃）。 */
    fun appGalleryPreviewSafe(): AppGalleryPreviewClampResult {
        val corrected = ArrayList<String>(4)
        fun <T> fix(name: String, before: T, after: T): T {
            if (before != after) corrected += name
            return after
        }

        val minZoom = fix("minZoomFactor", minZoomFactor, minZoomFactor.coerceIn(ZOOM_MIN_FLOOR, 4f))
        val maxZoom = fix("maxZoomFactor", maxZoomFactor, maxZoomFactor.coerceIn(2f, ZOOM_MAX_CEIL))
        // 保证 min <= max，否则以 max 为基准回退 min。
        val minZoom2 = fix("minZoomFactor>maxZoomFactor", minZoom, minZoom.coerceAtMost(maxZoom))
        val tap1 = fix(
            "doubleTapZoomFactor",
            doubleTapZoomFactor,
            doubleTapZoomFactor.coerceIn(minZoom2, maxZoom),
        )
        val tap2 = fix(
            "doubleTapZoomFactorSecondary",
            doubleTapZoomFactorSecondary,
            doubleTapZoomFactorSecondary.coerceIn(tap1, maxZoom),
        )

        val safe = copy(
            minZoomFactor = minZoom2,
            maxZoomFactor = maxZoom,
            doubleTapZoomFactor = tap1,
            doubleTapZoomFactorSecondary = tap2,
            panOverscrollFactor = fix(
                "panOverscrollFactor", panOverscrollFactor, panOverscrollFactor.coerceIn(0f, 1f)
            ),
            swipeSwitchThresholdFraction = fix(
                "swipeSwitchThresholdFraction",
                swipeSwitchThresholdFraction,
                swipeSwitchThresholdFraction.coerceIn(0.05f, 0.6f),
            ),
            swipeVelocityThresholdDpPerSec = fix(
                "swipeVelocityThresholdDpPerSec",
                swipeVelocityThresholdDpPerSec,
                swipeVelocityThresholdDpPerSec.coerceIn(80f, 4000f),
            ),
            swipeFollowFactor = fix(
                "swipeFollowFactor", swipeFollowFactor, swipeFollowFactor.coerceIn(0.1f, 1f)
            ),
            decodeTargetMaxPx = fix(
                "decodeTargetMaxPx", decodeTargetMaxPx, decodeTargetMaxPx.coerceIn(512, 16384)
            ),

            slideshowIntervalMs = fix(
                "slideshowIntervalMs", slideshowIntervalMs, slideshowIntervalMs.coerceIn(1000L, 30000L)
            ),
            slideshowShuffleSeed = fix("slideshowShuffleSeed", slideshowShuffleSeed, slideshowShuffleSeed.coerceAtLeast(0L)),

            rawPreviewMaxDimension = fix(
                "rawPreviewMaxDimension", rawPreviewMaxDimension, rawPreviewMaxDimension.coerceIn(256, 8192)
            ),
            rawRenderWorkingScale = fix(
                "rawRenderWorkingScale", rawRenderWorkingScale, rawRenderWorkingScale.coerceIn(0.25f, 1f)
            ),
            rawHistogramBins = fix("rawHistogramBins", rawHistogramBins, rawHistogramBins.coerceIn(32, 1024)),
            rawOverexposureThreshold = fix(
                "rawOverexposureThreshold",
                rawOverexposureThreshold,
                rawOverexposureThreshold.coerceIn(0.5f, 1f),
            ),
            rawUnderexposureThreshold = fix(
                "rawUnderexposureThreshold",
                rawUnderexposureThreshold,
                rawUnderexposureThreshold.coerceIn(0f, 0.5f),
            ),
            rawOverexposureWarnRatio = fix(
                "rawOverexposureWarnRatio",
                rawOverexposureWarnRatio,
                rawOverexposureWarnRatio.coerceIn(0f, 1f),
            ),
            rawExportMaxDimension = fix(
                "rawExportMaxDimension", rawExportMaxDimension, rawExportMaxDimension.coerceIn(512, 16384)
            ),
            rawExportJpegQuality = fix(
                "rawExportJpegQuality", rawExportJpegQuality, rawExportJpegQuality.coerceIn(1, 100)
            ),
            rawExportChunkRows = fix(
                "rawExportChunkRows", rawExportChunkRows, rawExportChunkRows.coerceIn(16, 4096)
            ),

            compareSliderDefaultRatio = fix(
                "compareSliderDefaultRatio",
                compareSliderDefaultRatio,
                compareSliderDefaultRatio.coerceIn(0f, 1f),
            ),
            compareHandleTouchSlopDp = fix(
                "compareHandleTouchSlopDp", compareHandleTouchSlopDp, compareHandleTouchSlopDp.coerceIn(8f, 96f)
            ),

            gifMaxDecodeDimensionPx = fix(
                "gifMaxDecodeDimensionPx", gifMaxDecodeDimensionPx, gifMaxDecodeDimensionPx.coerceIn(128, 4096)
            ),
            videoBookmarkSeekToleranceMs = fix(
                "videoBookmarkSeekToleranceMs",
                videoBookmarkSeekToleranceMs,
                videoBookmarkSeekToleranceMs.coerceIn(0L, 5000L),
            ),

            doubleTapSeekMs = fix("doubleTapSeekMs", doubleTapSeekMs, doubleTapSeekMs.coerceIn(1000L, 60000L)),
            dragSeekSensitivityMsPerDp = fix(
                "dragSeekSensitivityMsPerDp",
                dragSeekSensitivityMsPerDp,
                dragSeekSensitivityMsPerDp.coerceIn(10f, 2000f),
            ),
            verticalDragVolumeSensitivity = fix(
                "verticalDragVolumeSensitivity",
                verticalDragVolumeSensitivity,
                verticalDragVolumeSensitivity.coerceIn(0.05f, 5f),
            ),
            verticalDragBrightnessSensitivity = fix(
                "verticalDragBrightnessSensitivity",
                verticalDragBrightnessSensitivity,
                verticalDragBrightnessSensitivity.coerceIn(0.05f, 5f),
            ),
            gestureIndicatorTimeoutMs = fix(
                "gestureIndicatorTimeoutMs",
                gestureIndicatorTimeoutMs,
                gestureIndicatorTimeoutMs.coerceIn(200L, 3000L),
            ),

            bgThrottleMs = fix("bgThrottleMs", bgThrottleMs, bgThrottleMs.coerceIn(-1L, 60000L)),
            bgChunkPixels = fix("bgChunkPixels", bgChunkPixels, bgChunkPixels.coerceIn(4096, 4_194_304)),
            bgMaxHeapMb = fix("bgMaxHeapMb", bgMaxHeapMb, bgMaxHeapMb.coerceIn(-1, 4096)),
            bgMaxFrameBatch = fix("bgMaxFrameBatch", bgMaxFrameBatch, bgMaxFrameBatch.coerceIn(-1, 64)),

            darkCanvasColorArgb = fix(
                "darkCanvasColorArgb",
                darkCanvasColorArgb,
                darkCanvasColorArgb.coerceIn(0L, 0xFFFFFFFFL),
            ),
            chromeAutoHideMs = fix(
                "chromeAutoHideMs", chromeAutoHideMs, chromeAutoHideMs.coerceIn(0L, 30000L)
            ),
        )
        return AppGalleryPreviewClampResult(safe, corrected.toList())
    }

    /**
     * 有效缩放上限：双击档位不得越界。
     * 供 UI/状态机统一取值，避免各处重复 clamp。
     */
    val safeZoomRange: ClosedFloatingPointRange<Float>
        get() = minZoomFactor.coerceAtMost(maxZoomFactor)..maxZoomFactor.coerceAtLeast(minZoomFactor)

    companion object {
        /** 最小缩放倍率的下限（0.25x 允许用户缩小以适配超长图）。 */
        const val ZOOM_MIN_FLOOR = 0.25f
        /** 最大缩放倍率上限。 */
        const val ZOOM_MAX_CEIL = 32f

        /** 默认配置（已 clamp）。 */
        val DEFAULT: AppGalleryPreviewConfig = AppGalleryPreviewConfig().appGalleryPreviewSafe().config
    }
}
