package com.hinnka.mycamera.ui.viewfinder

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.viewfinder.FaceStickerAnchorMapper
import com.hinnka.mycamera.viewfinder.FaceStickerCatalog
import com.hinnka.mycamera.viewfinder.FaceStickerDetector
import com.hinnka.mycamera.viewfinder.NormalizedCrop
import com.hinnka.mycamera.viewfinder.ResolvedSticker
import com.hinnka.mycamera.viewfinder.SamplingThrottle
import com.hinnka.mycamera.viewfinder.StickerAnchor
import com.hinnka.mycamera.viewfinder.StickerConfig
import com.hinnka.mycamera.viewfinder.StickerPackConfig
import com.hinnka.mycamera.viewfinder.StickerPackResolver
import com.hinnka.mycamera.viewfinder.StickerPlacement
import com.hinnka.mycamera.viewfinder.ViewfinderHistogram
import com.hinnka.mycamera.viewfinder.ViewfinderHistogramAdapter
import com.hinnka.mycamera.viewfinder.ViewfinderSettings
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取景器辅助叠加层的**接线控制器**（给 CameraScreen 用的唯一挂点）。
 *
 * 职责：
 * 1. 消费预览帧（GL 线程回调）→ 节流 → 直方图统计 / 人脸关键点检测；
 * 2. 把结果暴露成 Compose 可观察状态（[histogram] / [placements] / [resolvedSticker]）；
 * 3. 持有 [FaceStickerDetector]，任何异常都降级为「贴纸关闭 + 提示文案」，不崩溃。
 *
 * 性能取舍（用户硬约束「轻任务前台节流直跑、重任务走 BgJobRunner」）：
 * - 直方图统计是**轻量任务**（一次线性扫描 + 桶自增），但不放在 GL 线程跑：
 *   先做一次 Bitmap 浅拷贝（亚毫秒级 memcpy），再把拷贝交给专用 MIN_PRIORITY 单线程；
 * - 人脸关键点是 MediaPipe CPU 推理，**轻量前台任务**，按 33..2000ms 节流直跑，不进 BgJobRunner；
 * - 大图边缘检测属**重计算**，见 `viewfinder/ViewfinderBgTasks`（必须走 BgJobRunner）。
 *
 * 线程安全：Compose 的 snapshot state 允许跨线程写入，因此后台统计完成后可直接赋值。
 */
@Stable
class ViewfinderOverlayController(
    context: Context,
    initialSettings: ViewfinderSettings = ViewfinderSettings(),
) : Closeable {

    companion object {
        private const val TAG = "ViewfinderOverlay"

        /** 直方图统计线程名（低优先级，不抢 GL/UI）。 */
        private const val HISTOGRAM_THREAD_NAME = "ViewfinderHistogram"
    }

    /** 应用上下文（构造期即取 applicationContext，避免持有 Activity）。 */
    private val appContext: Context = context.applicationContext

    private val released = AtomicBoolean(false)

    /** 当前生效设置（写入即 clamp）。 */
    var settings: ViewfinderSettings by mutableStateOf(ViewfinderSettings.clamp(initialSettings).value)
        private set

    /** 最新一帧直方图；null 表示尚未取到帧或已关闭该叠加。 */
    var histogram: ViewfinderHistogram? by mutableStateOf(null)
        private set

    /** 每张脸的最终贴位（最多 [StickerConfig.maxFaces] 个）。 */
    var placements: List<StickerPlacement> by mutableStateOf(emptyList())
        private set

    /** 当前贴纸解析结果（内置矢量 / 外部位图）。 */
    var resolvedSticker: ResolvedSticker by mutableStateOf(
        ResolvedSticker.BuiltIn(FaceStickerCatalog.find(settings.sticker.stickerId)),
    )
        private set

    /** 人脸模型不可用时的提示文案（null = 正常）。 */
    var unavailableReason: String? by mutableStateOf(null)
        private set

    private val histogramThrottle = SamplingThrottle.forHistogram(settings.histogram.sampleIntervalMs)
    private val histogramExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, HISTOGRAM_THREAD_NAME).apply { priority = Thread.MIN_PRIORITY }
    }
    private val detector = FaceStickerDetector(appContext)

    /** 最近一次平滑后的锚点（仅保留首个/最近一张脸）。 */
    private var anchors: List<StickerAnchor> = emptyList()

    init {
        detector.onFacesDetected = { result ->
            if (!released.get()) handleFaceLandmarks(result.faces)
        }
        detector.onError = { error ->
            PLog.w(TAG, "人脸关键点检测不可用，AR 贴纸降级", error)
            unavailableReason = STICKER_UNAVAILABLE_MESSAGE
        }
        applyStickerSettings(settings.sticker)
    }

    /** 更新设置：clamp → 重配节流与检测器（模型缺失时保持关闭而非崩溃）。 */
    fun updateSettings(newSettings: ViewfinderSettings) {
        if (released.get()) return
        val clamped = ViewfinderSettings.clamp(newSettings).value
        val previous = settings
        settings = clamped
        if (previous.histogram.sampleIntervalMs != clamped.histogram.sampleIntervalMs) {
            histogramThrottle.intervalMs = clamped.histogram.sampleIntervalMs
        }
        if (previous.sticker != clamped.sticker) {
            anchors = emptyList()
            applyStickerSettings(clamped.sticker)
        }
        if (!clamped.histogram.enabled) histogram = null
        if (!clamped.sticker.enabled) placements = emptyList()
    }

    /**
     * 预览帧回调（可在任意线程调用；**bitmap 所有权移交本类**，由本类负责回收）。
     *
     * @param bitmap 预览帧
     * @param timestampMs 帧时间戳（毫秒，用于节流判定）
     */
    fun onViewfinderFrame(bitmap: Bitmap, timestampMs: Long) {
        if (released.get()) {
            recycleQuietly(bitmap)
            return
        }
        val snapshot = settings
        try {
            if (snapshot.histogram.enabled && histogramThrottle.tryAcquire(timestampMs)) {
                dispatchHistogram(bitmap, snapshot.histogram.binCount)
            }
        } catch (error: Exception) {
            PLog.w(TAG, "直方图调度失败", error)
        }

        if (snapshot.sticker.enabled && detector.isAvailable) {
            val submitted = try {
                detector.processFrame(bitmap, timestampMs)
            } catch (error: Exception) {
                PLog.w(TAG, "贴纸检测调度失败", error)
                false
            }
            // 检测器内部节流/异常时不会接手 bitmap，这里兜底回收。
            if (!submitted) recycleQuietly(bitmap)
        } else {
            recycleQuietly(bitmap)
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        runCatching { detector.close() }
        histogramExecutor.shutdown()
        placements = emptyList()
        anchors = emptyList()
    }

    private fun handleFaceLandmarks(faces: List<FloatArray>) {
        val snapshot = settings.sticker
        val mapped = faces.map { landmarks ->
            FaceStickerAnchorMapper.mapAnchors(
                landmarks = landmarks,
                mirror = false,
                rotationDegrees = 0,
                crop = NormalizedCrop.FULL,
            )
        }
        val primary = mapped.firstOrNull().orEmpty()
        val anchor = FaceStickerAnchorMapper.anchorFor(snapshot.anchorMode, primary)
        if (!anchor.isValid) {
            placements = emptyList()
            return
        }
        val previous = anchors.firstOrNull()
        val smoothed = if (previous != null) {
            FaceStickerAnchorMapper.smooth(previous, anchor, snapshot.smoothing)
        } else {
            anchor
        }
        anchors = listOf(smoothed)
        updatePlacements(smoothed)
    }

    private fun updatePlacements(anchor: StickerAnchor) {
        val snapshot = settings.sticker
        if (!snapshot.enabled || !anchor.isValid) {
            placements = emptyList()
            return
        }
        val definition = when (resolvedSticker) {
            is ResolvedSticker.BuiltIn -> resolvedSticker.definition
            is ResolvedSticker.External -> resolvedSticker.definition
        }
        placements = listOf(
            FaceStickerAnchorMapper.placementFor(anchor, snapshot, definition),
        )
    }

    private fun dispatchHistogram(bitmap: Bitmap, binCount: Int) {
        // 先浅拷贝，避免与「移交给检测器的那份 bitmap」产生竞态；拷贝成本为一次 memcpy。
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (error: Exception) {
            PLog.w(TAG, "预览帧拷贝失败", error)
            null
        }
        if (copy == null) return
        try {
            histogramExecutor.execute {
                try {
                    val result = ViewfinderHistogramAdapter.fromBitmap(copy, binCount)
                    if (!released.get()) histogram = result
                } catch (error: Exception) {
                    PLog.w(TAG, "直方图统计失败", error)
                } finally {
                    recycleQuietly(copy)
                }
            }
        } catch (_: RejectedExecutionException) {
            recycleQuietly(copy)
        }
    }

    private fun applyStickerSettings(config: StickerConfig) {
        resolvedSticker = try {
            StickerPackResolver.resolve(appContext, config.stickerId, config.toPackConfig())
        } catch (error: Exception) {
            PLog.w(TAG, "贴纸素材解析失败，回落内置贴纸", error)
            ResolvedSticker.BuiltIn(FaceStickerCatalog.find(config.stickerId))
        }
        if (!config.enabled) {
            unavailableReason = null
            placements = emptyList()
            return
        }
        detector.configure(config)
        detector.prewarm()
        if (!detector.isAvailable) {
            unavailableReason = STICKER_UNAVAILABLE_MESSAGE
        }
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        runCatching { bitmap.recycle() }
    }

    /** [StickerConfig] → [StickerPackConfig]：外部素材包地址留空即视为不启用（默认内置矢量贴纸）。 */
    private fun StickerConfig.toPackConfig(): StickerPackConfig = StickerPackConfig(
        enabled = packDownloadUrl.isNotBlank(),
        downloadUrlTemplate = packDownloadUrl,
        directory = packDirectory,
    )
}

private const val STICKER_UNAVAILABLE_MESSAGE = "AR 贴纸暂不可用（人脸模型未就绪）"
