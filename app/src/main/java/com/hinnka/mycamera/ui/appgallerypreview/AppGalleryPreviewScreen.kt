package com.hinnka.mycamera.ui.appgallerypreview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewActionContextFactory
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewActionHost
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewActionId
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewActionPolicy
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBgTasks
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBookmark
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBookmarkSource
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewChromePlacement
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewCompareMapping
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewCompareVariants
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewEditContract
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewHistogram
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewLayoutPolicy
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaKind
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaKindResolver
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaSource
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPagerMath
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPagerState
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewPlaybackGestureMath
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewQuickEditPreset
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawExposure
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawExportMath
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawExportRequest
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRequest
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSlideshow
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSlideshowState
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSwipeOutcome
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobStatus
import kotlinx.coroutines.delay
import java.io.File

/**
 * 应用内置独立相册 · 预览体验域主界面（相册第 34-42 项）。
 *
 * 覆盖：大图预览缩放与翻页(34)、幻灯片放映(35)、RAW 查看与处理(36)、原图对比(37)、
 * GIF/视频预览播放(38)、二次编辑入口(39)、快捷编辑入口(40)、播放手势(41)、快捷操作(42)，
 * 并适配横竖屏与深色模式。
 *
 * 跨代理契约：
 *  - 进入参数见 [AppGalleryPreviewRequest] / `AppGalleryPreviewEntryContract`（管理域构造）；
 *  - 相邻项 URI 由 [AppGalleryPreviewMediaSource] 提供（一次性列表或按需解析）；
 *  - 快捷操作回调走 [AppGalleryPreviewActionHost]（管理域实现）；
 *  - 编辑入口只发 intent（见 [AppGalleryPreviewEditContract]），不依赖编辑模块符号。
 *
 * 重任务（RAW 解码/直方图/RAW 导出）统一走 [AppGalleryPreviewBgTasks] → `BgJobRunner` 的
 * `BgTaskType.PREVIEW` 队列（分片 + `guardMemory` + `checkYield`）。
 */
@Composable
fun AppGalleryPreviewScreen(
    request: AppGalleryPreviewRequest,
    modifier: Modifier = Modifier,
    config: AppGalleryPreviewConfig = AppGalleryPreviewConfig.DEFAULT,
    /** 相邻项数据源；为 null 时由 [request] 的 sourceUris 构造。 */
    mediaSource: AppGalleryPreviewMediaSource? = null,
    /** 快捷操作回调实现（管理核心域注入）；为 null 时相关操作禁用。 */
    actionHost: AppGalleryPreviewActionHost? = null,
    /** 视频书签数据源（画廊编辑模块注入）。 */
    bookmarkSource: AppGalleryPreviewBookmarkSource = AppGalleryPreviewBookmarkSource.NONE,
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
    val safeConfig = remember(config) { config.appGalleryPreviewSafe().config }
    val source = remember(request, mediaSource) { mediaSource ?: AppGalleryPreviewMediaSource.from(request) }
    val configuration = LocalConfiguration.current
    val localDensity = LocalDensity.current
    // 翻页手势的距离阈值以视口宽度为基准；这里用窗口宽度近似（宽屏侧栏时略偏保守，不影响判定正确性）。
    val viewportWidthPx = remember(configuration.screenWidthDp, localDensity) {
        with(localDensity) { configuration.screenWidthDp.dp.toPx() }
    }
    val layout = AppGalleryPreviewLayoutPolicy.resolve(
        widthDp = configuration.screenWidthDp.toFloat(),
        heightDp = configuration.screenHeightDp.toFloat(),
        systemDark = isSystemInDarkTheme(),
        config = safeConfig,
    )

    // ---------------- 逻辑状态（纯 Kotlin 状态机，见 appgallerypreview 包与对应单测） ----------------
    val pager = remember(request, safeConfig, source) {
        AppGalleryPreviewPagerState(source.count, request.index, safeConfig)
    }
    var index by remember(request) { mutableIntStateOf(pager.currentIndex) }
    val slideshow = remember(request, safeConfig, source) {
        AppGalleryPreviewSlideshow(source.count, request.index, safeConfig)
    }
    var slideshowState by remember(request) { mutableStateOf(slideshow.state) }
    var zoomedIn by remember(request) { mutableStateOf(false) }
    var compareOn by remember(request) { mutableStateOf(false) }
    var showBookmarks by remember(request) { mutableStateOf(true) }
    var playToggleTicks by remember(request) { mutableIntStateOf(0) }
    var rawBitmap by remember(request) { mutableStateOf<Bitmap?>(null) }
    var exposure by remember(request) { mutableStateOf<AppGalleryPreviewRawExposure?>(null) }
    var histogram by remember(request) { mutableStateOf<AppGalleryPreviewHistogram?>(null) }
    var exportState by remember(request) { mutableStateOf(AppGalleryPreviewRawExportUiState()) }
    var exportJob by remember(request) { mutableStateOf<BgJob?>(null) }
    var bookmarks by remember(request) { mutableStateOf<List<AppGalleryPreviewBookmark>>(emptyList()) }
    var favorite by remember(request) { mutableStateOf<Boolean?>(null) }
    var showInfo by remember(request) { mutableStateOf(false) }
    var showDeleteConfirm by remember(request) { mutableStateOf(false) }
    var message by remember(request) { mutableStateOf<String?>(null) }

    val mediaUri = source.uriAt(index)
    val mediaKind = source.kindAt(index)
    val mediaId = source.mediaIdAt(index)

    // ---------------- 系统栏 / 方向 / 亮度（横竖屏与沉浸式适配） ----------------
    val activity = remember(context) { context.findPreviewActivity() }
    DisposableEffect(layout.orientationLock) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = when (layout.orientationLock) {
            com.hinnka.mycamera.appgallerypreview.PreviewOrientationLock.AUTO ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            com.hinnka.mycamera.appgallerypreview.PreviewOrientationLock.PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            com.hinnka.mycamera.appgallerypreview.PreviewOrientationLock.LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            if (previous != null) activity?.requestedOrientation = previous
        }
    }

    // ---------------- 后台任务初始化（CPU 频控/内存预算下发） ----------------
    LaunchedEffect(safeConfig) {
        AppGalleryPreviewBgTasks.applyRunnerOverrides(safeConfig)
    }

    // ---------------- 切换条目：清理该条的临时状态 ----------------
    LaunchedEffect(index) {
        zoomedIn = false
        rawBitmap = null
        exposure = null
        histogram = null
        exportState = AppGalleryPreviewRawExportUiState()
        exportJob = null
        compareOn = false
    }

    // ---------------- RAW 管线：读出曝光 + 解码内嵌预览 + 直方图（全部后台） ----------------
    LaunchedEffect(index, mediaUri, mediaKind) {
        val uri = mediaUri ?: return@LaunchedEffect
        if (!AppGalleryPreviewMediaKindResolver.isRaw(mediaKind)) return@LaunchedEffect
        AppGalleryPreviewBgTasks.submitRawMetadataRead(
            name = "agp_meta_$index",
            context = context,
            uri = Uri.parse(uri),
        ) { result -> exposure = result }
        AppGalleryPreviewBgTasks.submitRawPreviewDecode(
            name = "agp_raw_preview_$index",
            context = context,
            uri = Uri.parse(uri),
            config = safeConfig,
        ) { decoded ->
            rawBitmap = decoded?.bitmap
            exposure = decoded?.exposure ?: exposure
            decoded?.bitmap?.let { bitmap ->
                AppGalleryPreviewBgTasks.submitHistogramFromBitmap(
                    name = "agp_hist_$index",
                    bitmap = bitmap,
                    config = safeConfig,
                ) { result -> histogram = result }
            }
        }
    }

    // ---------------- 导出任务进度订阅 ----------------
    LaunchedEffect(exportJob) {
        val job = exportJob ?: return@LaunchedEffect
        job.state.collect { state ->
            exportState = exportState.copy(
                running = state.status == BgJobStatus.RUNNING || state.status == BgJobStatus.PAUSED,
                progress = state.progress,
            )
        }
    }

    // ---------------- 视频书签（编辑模块提供数据，本模块只做播放器层跳转） ----------------
    LaunchedEffect(index, mediaUri, mediaKind) {
        bookmarks = if (mediaKind == AppGalleryPreviewMediaKind.VIDEO && mediaUri != null) {
            runCatching { bookmarkSource.bookmarksFor(mediaUri) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    // ---------------- 收藏状态（管理域读取） ----------------
    LaunchedEffect(index, mediaId, actionHost) {
        favorite = mediaId?.let { id -> runCatching { actionHost?.favoriteState(id) }.getOrNull() }
    }

    // ---------------- 幻灯片驱动（100ms 粒度检查，间隔与循环/随机由纯状态机决定） ----------------
    LaunchedEffect(request, safeConfig) {
        if (request.startSlideshow || safeConfig.slideshowAutoStart) {
            slideshow.play()
            slideshowState = slideshow.state
        }
    }
    LaunchedEffect(slideshow, slideshowState) {
        while (slideshowState == AppGalleryPreviewSlideshowState.PLAYING) {
            delay(SLIDESHOW_TICK_MS)
            val next = slideshow.onTick(System.currentTimeMillis())
            if (next != null) {
                index = pager.goTo(next)
            }
            slideshowState = slideshow.state
        }
    }

    // ---------------- 提示消息 ----------------
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        message = null
    }

    // ---------------- 对比可用性（需两种版本都在；对应"处理前保留原图"约定） ----------------
    val compareVariants = AppGalleryPreviewCompareVariants(
        originalUri = source.compareOriginalUriAt(index),
        processedUri = mediaUri,
    )
    val compareAvailability = remember(compareVariants, safeConfig) {
        AppGalleryPreviewCompareMapping.availability(compareVariants, safeConfig)
    }

    // ---------------- 快捷操作（第 42 项；策略为纯函数） ----------------
    val actionContext = AppGalleryPreviewActionContextFactory.create(
        source = source,
        index = index,
        rawExportEnabled = request.rawExportEnabled,
        slideshowEnabled = request.slideshowEnabled,
        isFavorite = favorite,
        supportsFavorite = actionHost != null,
        supportsDelete = actionHost != null,
        supportsShare = actionHost != null,
        supportsEdit = request.allowEdit,
        compareAvailable = compareAvailability.available,
    )
    val actions = remember(actionContext) { AppGalleryPreviewActionPolicy.actions(actionContext) }

    /** 发编辑 intent（预览域只发 intent，不依赖编辑模块符号）。 */
    fun launchEdit(preset: AppGalleryPreviewQuickEditPreset?, forceQuick: Boolean) {
        val uri = mediaUri ?: return
        val effectivePreset = preset ?: AppGalleryPreviewQuickEditPreset.NONE
        val extras = AppGalleryPreviewEditContract.editExtras(
            mediaUri = uri,
            mediaType = source.mimeAt(index),
            mediaId = mediaId,
            originalUri = source.compareOriginalUriAt(index),
            preset = effectivePreset,
        ) ?: return
        val action = if (forceQuick || effectivePreset != AppGalleryPreviewQuickEditPreset.NONE) {
            AppGalleryPreviewEditContract.ACTION_QUICK_EDIT_MEDIA
        } else {
            AppGalleryPreviewEditContract.ACTION_EDIT_MEDIA
        }
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
        val target = runCatching { intent.resolveActivity(context.packageManager) }.getOrNull()
        if (target == null) {
            message = context.getString(R.string.agp_edit_unavailable)
        } else {
            runCatching { context.startActivity(intent) }
                .onFailure { message = context.getString(R.string.agp_edit_unavailable) }
        }
    }

    /** 启动 RAW→JPEG 导出（后台分片，走 BgJobRunner）。 */
    fun startRawExport() {
        val uri = mediaUri ?: return
        val fileName = AppGalleryPreviewRawExportMath.exportFileNameFor(mediaId ?: uri)
        val outputFile = File(context.cacheDir, "agp_raw_export/$fileName")
        val exportRequest = AppGalleryPreviewRawExportRequest(
            mediaUri = uri,
            outputFileName = fileName,
            maxDimension = safeConfig.rawExportMaxDimension,
            jpegQuality = safeConfig.rawExportJpegQuality,
            preserveExif = safeConfig.rawExportPreserveExif,
            useEmbeddedPreview = safeConfig.rawExportUseEmbeddedPreview,
            chunkRows = safeConfig.rawExportChunkRows,
            workScale = safeConfig.rawRenderWorkingScale,
        )
        exportState = AppGalleryPreviewRawExportUiState(running = true, progress = 0f)
        exportJob = AppGalleryPreviewBgTasks.submitRawExport(
            name = "agp_raw_export_$index",
            context = context,
            uri = Uri.parse(uri),
            request = exportRequest,
            outputFile = outputFile,
            config = safeConfig,
        ) { result ->
            exportState = result.fold(
                onSuccess = { done ->
                    AppGalleryPreviewRawExportUiState(
                        running = false,
                        progress = 1f,
                        outputPath = done.outputPath,
                    )
                },
                onFailure = { AppGalleryPreviewRawExportUiState(running = false, failed = true) },
            )
        }
    }

    fun onAction(id: AppGalleryPreviewActionId) {
        when (id) {
            AppGalleryPreviewActionId.FAVORITE -> {
                val target = mediaId ?: return
                val next = runCatching { actionHost?.onToggleFavorite(target) }.getOrNull()
                if (next != null) favorite = next
            }

            AppGalleryPreviewActionId.DELETE -> showDeleteConfirm = true
            AppGalleryPreviewActionId.SHARE -> mediaId?.let { runCatching { actionHost?.onRequestShare(listOf(it)) } }
            AppGalleryPreviewActionId.INFO -> showInfo = true
            AppGalleryPreviewActionId.EDIT -> launchEdit(null, forceQuick = false)
            AppGalleryPreviewActionId.QUICK_EDIT -> launchEdit(null, forceQuick = true)
            AppGalleryPreviewActionId.SLIDESHOW -> {
                slideshow.toggle()
                slideshowState = slideshow.state
            }

            AppGalleryPreviewActionId.COMPARE -> {
                if (compareAvailability.available) {
                    compareOn = !compareOn
                } else {
                    message = context.getString(R.string.agp_compare_unavailable_generic)
                }
            }

            AppGalleryPreviewActionId.RAW_EXPORT -> startRawExport()
            AppGalleryPreviewActionId.SAVE_AS -> startRawExport()
            AppGalleryPreviewActionId.PLAY_PAUSE -> playToggleTicks++
            AppGalleryPreviewActionId.BOOKMARK -> {
                showBookmarks = !showBookmarks
                if (bookmarks.isEmpty()) message = context.getString(R.string.agp_bookmark_empty)
            }
        }
    }

    // ---------------- 界面组装 ----------------
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(layout.canvasColorArgb)),
    ) {
        AppGalleryPreviewPagerHost(
            index = index,
            count = source.count,
            isZoomedIn = zoomedIn,
            config = safeConfig,
            onResolve = { dragPx, velocityDpPerSec ->
                AppGalleryPreviewPagerMath.resolveSwipe(
                    dxPx = dragPx,
                    velocityDpPerSec = velocityDpPerSec,
                    viewportWidthPx = viewportWidthPx,
                    isZoomedIn = zoomedIn,
                    hasPrevious = pager.hasPrevious,
                    hasNext = pager.hasNext,
                    config = safeConfig,
                )
            },
            onCommit = { outcome: AppGalleryPreviewSwipeOutcome ->
                pager.applyOutcome(outcome)
                index = pager.currentIndex
            },
        ) { itemIndex ->
            val itemUri = source.uriAt(itemIndex)
            val itemKind = source.kindAt(itemIndex)
            val isCurrent = itemIndex == index

            when {
                itemUri == null -> AppGalleryPreviewPlaceholder(
                    text = stringResource(R.string.agp_item_not_available),
                )

                isCurrent && compareOn && compareAvailability.available ->
                    AppGalleryPreviewCompareSlider(
                        originalModel = compareVariants.originalUri,
                        processedModel = compareVariants.processedUri ?: itemUri,
                        config = safeConfig,
                    )

                isCurrent && itemKind == AppGalleryPreviewMediaKind.RAW && rawBitmap == null ->
                    AppGalleryPreviewPlaceholder(text = stringResource(R.string.agp_raw_decoding))

                itemKind == AppGalleryPreviewMediaKind.VIDEO ->
                    AppGalleryPreviewVideoPane(
                        mediaUri = itemUri,
                        config = safeConfig,
                        bookmarks = bookmarks,
                        isActive = isCurrent,
                        showBookmarks = showBookmarks,
                        playToggleTicks = playToggleTicks,
                        onPlayingChanged = {},
                        onBrightnessDelta = { delta -> applyBrightnessDelta(activity, delta) },
                    )

                else -> AppGalleryPreviewZoomablePane(
                    mediaUri = itemUri,
                    mediaKind = itemKind,
                    config = safeConfig,
                    isActive = isCurrent,
                    bitmapModel = if (isCurrent) rawBitmap else null,
                    onZoomChange = { zoomed -> if (isCurrent) zoomedIn = zoomed },
                    contentDescription = source.mediaIdAt(itemIndex),
                )
            }
        }

        AppGalleryPreviewTopBar(
            onBack = onClose,
            counter = if (layout.showMediaCounter) {
                stringResource(R.string.agp_counter_format, index + 1, source.count)
            } else {
                null
            },
            slideshowState = slideshowState,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // RAW 面板（仅 RAW 类型）
        if (AppGalleryPreviewMediaKindResolver.isRaw(mediaKind) &&
            (safeConfig.rawShowHistogram || safeConfig.rawShowOverexposureHint)
        ) {
            AppGalleryPreviewRawPanel(
                exposure = exposure,
                histogram = histogram,
                exportState = exportState,
                onExportClick = { startRawExport() },
                config = safeConfig,
                enabled = request.rawExportEnabled,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp)
                    .width(240.dp),
            )
        }

        if (slideshowState != AppGalleryPreviewSlideshowState.IDLE) {
            AppGalleryPreviewSlideshowIndicator(
                intervalMs = safeConfig.slideshowIntervalMs,
                state = slideshowState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 12.dp,
                        bottom = if (layout.chromePlacement == AppGalleryPreviewChromePlacement.BOTTOM) 76.dp else 24.dp,
                    ),
            )
        }

        val barModifier = if (layout.chromePlacement == AppGalleryPreviewChromePlacement.SIDE) {
            Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        } else {
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        }
        AppGalleryPreviewActionBar(
            actions = actions,
            onAction = { id -> onAction(id) },
            favorite = favorite,
            vertical = layout.chromePlacement == AppGalleryPreviewChromePlacement.SIDE,
            modifier = barModifier,
        )

        if (showDeleteConfirm) {
            AppGalleryPreviewDeleteConfirmDialog(
                onConfirm = {
                    showDeleteConfirm = false
                    val target = mediaId
                    if (target == null) {
                        message = context.getString(R.string.agp_delete_unavailable)
                    } else {
                        runCatching { actionHost?.onRequestDelete(listOf(target)) }
                    }
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }

        if (showInfo) {
            AppGalleryPreviewInfoDialog(
                mediaUri = mediaUri ?: "",
                mediaKind = mediaKind,
                exposure = exposure,
                index = index,
                total = source.count,
                onDismiss = { showInfo = false },
            )
        }
    }

}

/** 幻灯片定时检查粒度（毫秒）。更细的粒度让多张幻灯片的相位更准。 */
private const val SLIDESHOW_TICK_MS = 100L

/** 占位层（无可用数据/解码中）。 */
@Composable
private fun AppGalleryPreviewPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White.copy(alpha = 0.7f))
    }
}

/** 从 Context 链上找到 Activity（用于方向锁定、亮度、系统栏控制）。 */
private fun Context.findPreviewActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 把亮度增量应用到宿主 Window（预览域不持有 Window，由这里从 Activity 取）。 */
private fun applyBrightnessDelta(activity: Activity?, delta: Float) {
    if (activity == null || !delta.isFinite() || delta == 0f) return
    runCatching {
        val window = activity.window
        val params = window.attributes
        val current = params.screenBrightness.takeIf { it in 0f..1f } ?: DEFAULT_BRIGHTNESS
        params.screenBrightness = AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(current, delta)
        window.attributes = params
    }
}

/** 系统亮度未知时的中性起点。 */
private const val DEFAULT_BRIGHTNESS = 0.5f
