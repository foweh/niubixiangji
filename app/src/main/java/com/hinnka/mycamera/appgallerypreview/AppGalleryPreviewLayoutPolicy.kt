package com.hinnka.mycamera.appgallerypreview

/** 顶/底 chrome 的落位方式。 */
enum class AppGalleryPreviewChromePlacement {
    /** 竖屏/窄屏：工具栏固定底部。 */
    BOTTOM,

    /** 横屏/宽屏：工具栏移到右侧竖向排列，把画面留给内容。 */
    SIDE,
}

/**
 * 一次布局解析结果（纯数据）。
 *
 * @param darkCanvas 是否使用深色画布
 * @param canvasColorArgb 画布底色（ARGB）
 * @param contentPaddingDp 内容四周留白（宽屏侧栏时右侧加大，避免被工具栏遮挡）
 */
data class AppGalleryPreviewLayoutPlan(
    val chromePlacement: AppGalleryPreviewChromePlacement = AppGalleryPreviewChromePlacement.BOTTOM,
    val immersive: Boolean = true,
    val darkCanvas: Boolean = false,
    val isWide: Boolean = false,
    val isLandscape: Boolean = false,
    val canvasColorArgb: Long = 0xFF0E0E10L,
    val contentPaddingDp: Float = 0f,
    val showMediaCounter: Boolean = true,
    val chromeAutoHideMs: Long = 4000L,
    val orientationLock: PreviewOrientationLock = PreviewOrientationLock.AUTO,
)

/**
 * 横竖屏 + 深色模式适配策略（纯函数，可 JVM 单测，第 42 项配套）。
 *
 * 自研理由：策略要把本模块的配置（是否沉浸、是否跟随系统深色、宽屏是否侧栏）与
 * 运行时窗口尺寸结合，属于模块内决策；Compose 的 WindowSizeClass 依赖额外 artifact，
 * 这里用纯数值阈值判断即可满足需求，且避免新增依赖。
 */
object AppGalleryPreviewLayoutPolicy {

    /** 宽屏判定阈值（dp，短边）。超过即认为可用侧栏（横屏手机 / 平板）。 */
    const val WIDE_SHORT_EDGE_DP = 600f

    /** 横屏判定阈值（dp）：宽 > 高即横屏。 */
    fun isLandscape(widthDp: Float, heightDp: Float): Boolean =
        widthDp.isFinite() && heightDp.isFinite() && widthDp > heightDp

    /** 宽屏判定：横屏 或 短边 >= [WIDE_SHORT_EDGE_DP]。 */
    fun isWide(widthDp: Float, heightDp: Float): Boolean {
        if (!widthDp.isFinite() || !heightDp.isFinite()) return false
        val shortEdge = minOf(widthDp, heightDp)
        return isLandscape(widthDp, heightDp) || shortEdge >= WIDE_SHORT_EDGE_DP
    }

    /**
     * 方向锁定是否允许当前方向；[PreviewOrientationLock.AUTO] 永远允许。
     */
    fun orientationAllows(lock: PreviewOrientationLock, isPortraitNow: Boolean): Boolean = when (lock) {
        PreviewOrientationLock.AUTO -> true
        PreviewOrientationLock.PORTRAIT -> isPortraitNow
        PreviewOrientationLock.LANDSCAPE -> !isPortraitNow
    }

    /**
     * 解析布局。
     *
     * @param widthDp / @param heightDp 窗口尺寸（dp）
     * @param systemDark 系统是否深色
     */
    fun resolve(
        widthDp: Float,
        heightDp: Float,
        systemDark: Boolean,
        config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    ): AppGalleryPreviewLayoutPlan {
        val safe = config.appGalleryPreviewSafe().config
        val landscape = isLandscape(widthDp, heightDp)
        val wide = isWide(widthDp, heightDp)
        val dark = safe.followSystemDarkTheme && systemDark
        val placement = if (wide && safe.wideLayoutToolbarOnSide) {
            AppGalleryPreviewChromePlacement.SIDE
        } else {
            AppGalleryPreviewChromePlacement.BOTTOM
        }
        // 侧栏比底栏需要更多横向留白（工具栏宽约 64dp + 间距 12dp）。
        val padding = when {
            placement == AppGalleryPreviewChromePlacement.SIDE -> 76f
            else -> 0f
        }
        return AppGalleryPreviewLayoutPlan(
            chromePlacement = placement,
            immersive = safe.immersiveChrome,
            darkCanvas = dark,
            isWide = wide,
            isLandscape = landscape,
            canvasColorArgb = if (dark) safe.darkCanvasColorArgb else LIGHT_CANVAS_ARGB,
            contentPaddingDp = padding,
            showMediaCounter = safe.showMediaCounter,
            chromeAutoHideMs = safe.chromeAutoHideMs,
            orientationLock = safe.orientationLock,
        )
    }

    /** 浅色画布底色（纯黑以外的中性色，避免与照片内容产生偏色观感）。 */
    const val LIGHT_CANVAS_ARGB = 0xFF1A1A1CL
}
