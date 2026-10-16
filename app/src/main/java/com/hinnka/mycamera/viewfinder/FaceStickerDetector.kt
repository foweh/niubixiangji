package com.hinnka.mycamera.viewfinder

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.hinnka.mycamera.utils.PLog
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AR 趣味贴纸的人脸关键点检测器。
 *
 * 复用策略（PATTERN_SPEC §9 ①/②）：直接复用工程**已依赖**的
 * `com.google.mediapipe:tasks-vision:1.0.0`（Apache-2.0）中的 FaceLandmarker（FaceMesh 468 点），
 * 不引入任何新依赖、不重写人脸检测；本类只做「模型装配 + 取帧节流 + 关键点扁平化 + 生命周期兜底」。
 *
 * 调用方式对齐工程既有 `preview/PreviewEyeFocusProcessor`（同一套 BaseOptions / Delegate.CPU /
 * RunningMode.LIVE_STREAM / detectAsync + ResultListener 模式），便于集成阶段复用既有接线经验。
 *
 * 健壮性（硬约束）：模型缺失、assets 读不到、MediaPipe 初始化异常、检测异常——
 * 一律落到 [isAvailable]=false / [onError] 回调并**静默降级**（不贴纸、不崩溃、不闪退）。
 * 网络侧不涉及（模型下载由 [StickerConfig.modelDownloadUrl] 交给外部下载器，本类不发起请求）。
 *
 * 性能取舍：FaceLandmarker 属**轻量前台任务**（CPU 委托、480px 级输入、默认 10fps 节流），
 * 按 [SamplingThrottle] 节流直跑，不进 [com.hinnka.mycamera.computational.BgJobRunner]；
 * 重计算（大图边缘检测）另见 [ViewfinderBgTasks]。
 */
class FaceStickerDetector(context: Context) : Closeable {

    /** 一帧的检测结果：每张脸一个扁平归一化关键点数组 `[x0,y0,x1,y1,...]`。 */
    data class FaceLandmarks(
        val faces: List<FloatArray>,
        val timestampMs: Long,
        val processingDurationNanos: Long,
    )

    companion object {
        private const val TAG = "FaceStickerDetector"

        /** 默认模型：优先读 assets；缺失时可由调用方指向 filesDir 下的已下载模型。 */
        const val DEFAULT_MODEL_ASSET_PATH = "face_landmarker.task"

        /**
         * 默认模型下载地址（MediaPipe 官方托管，Apache-2.0）。
         *
         * 端点**可配置**（[StickerConfig.modelDownloadUrl]），本类不硬编码发起请求；
         * 留空即表示只用内置/已安装模型。
         */
        const val DEFAULT_MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

        private const val MIN_FACE_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_FACE_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f

        /** 降级后的输入长边（模型缺失前不涉及；用于限制 MPImage 构建成本）。 */
        const val MAX_DETECT_LONG_EDGE = 512
    }

    private val appContext = context.applicationContext
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MediaPipeFaceSticker").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val frameInFlight = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    private var faceLandmarker: FaceLandmarker? = null
    private var initializationFailed = false
    private var lastTimestampMs = 0L
    private var pendingImage: MPImage? = null
    private var pendingBitmap: Bitmap? = null
    private var pendingGeneration = 0
    private var maxFaces = ViewfinderParamRanges.STICKER_MAX_FACES_DEFAULT
    private var modelPath = DEFAULT_MODEL_ASSET_PATH
    private var throttle = SamplingThrottle.forFaceSticker()

    /** 关键点结果回调（检测线程）。 */
    var onFacesDetected: ((FaceLandmarks) -> Unit)? = null

    /** 初始化/检测异常回调；调用方据此提示"贴纸不可用"并降级。 */
    var onError: ((Throwable) -> Unit)? = null

    /** 模型是否可用（未初始化时返回"未知"，只有初始化失败才确定不可用）。 */
    val isAvailable: Boolean get() = !initializationFailed && !released.get()

    /** 应用运行时配置（缩放/镜像等由 UI 层处理，这里只关心检测相关参数）。 */
    fun configure(config: StickerConfig) {
        val clamped = StickerConfig.clamp(config).value
        maxFaces = clamped.maxFaces
        modelPath = clamped.modelAssetPath.ifBlank { DEFAULT_MODEL_ASSET_PATH }
        throttle = SamplingThrottle.forFaceSticker(clamped.detectIntervalMs)
        // 模型路径/人脸数变化需要重建推理器。
        synchronized(this) {
            if (faceLandmarker != null) {
                runCatching { faceLandmarker?.close() }
                faceLandmarker = null
            }
            initializationFailed = false
        }
    }

    /** 预热：提前在后台线程建好推理器，避免首帧卡顿。 */
    fun prewarm() {
        if (released.get()) return
        try {
            inferenceExecutor.execute {
                if (!released.get()) getOrCreateLandmarker()
            }
        } catch (_: RejectedExecutionException) {
            // 正在释放，忽略。
        }
    }

    /**
     * 提交一帧（需在任意线程调用；内部按 [StickerConfig.detectIntervalMs] 节流）。
     *
     * **bitmap 所有权交给本类**：检测完成或失败时由本类 recycle，调用方不要再回收。
     *
     * @return true 表示本次真正提交检测；false 表示被节流跳过或已有帧在处理中
     */
    fun processFrame(bitmap: Bitmap, timestampMs: Long): Boolean {
        if (released.get() || initializationFailed) {
            safeRecycle(bitmap)
            return false
        }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            safeRecycle(bitmap)
            return false
        }
        if (!throttle.tryAcquire(SystemClock.elapsedRealtime())) {
            safeRecycle(bitmap)
            return false
        }
        if (!frameInFlight.compareAndSet(false, true)) {
            safeRecycle(bitmap)
            return false
        }
        try {
            inferenceExecutor.execute { runDetection(bitmap, timestampMs) }
        } catch (_: RejectedExecutionException) {
            safeRecycle(bitmap)
            finishFrame()
            return false
        }
        return true
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        generation.incrementAndGet()
        try {
            inferenceExecutor.execute {
                synchronized(this) {
                    cleanupPendingLocked()
                    runCatching { faceLandmarker?.close() }
                    faceLandmarker = null
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(this) {
                cleanupPendingLocked()
                runCatching { faceLandmarker?.close() }
                faceLandmarker = null
            }
        }
        inferenceExecutor.shutdown()
    }

    private fun runDetection(bitmap: Bitmap, timestampMs: Long) {
        val landmarker = getOrCreateLandmarker()
        if (landmarker == null || released.get()) {
            safeRecycle(bitmap)
            finishFrame()
            return
        }
        val submitted: MPImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (error: Exception) {
            PLog.w(TAG, "构建 MPImage 失败", error)
            safeRecycle(bitmap)
            finishFrame()
            return
        }
        val inputTimestamp = timestampMs.coerceAtLeast(lastTimestampMs + 1L)
        synchronized(this) {
            pendingImage = submitted
            pendingBitmap = bitmap
            pendingGeneration = generation.get()
        }
        lastTimestampMs = inputTimestamp
        try {
            landmarker.detectAsync(submitted, inputTimestamp)
        } catch (error: RuntimeException) {
            synchronized(this) { cleanupPendingLocked() }
            onError?.invoke(error)
            finishFrame()
        }
    }

    private fun getOrCreateLandmarker(): FaceLandmarker? {
        faceLandmarker?.let { return it }
        if (initializationFailed || released.get()) return null

        return try {
            val resolved = resolveModelInput()
            if (resolved == null) {
                initializationFailed = true
                PLog.w(TAG, "FaceMesh 模型不可用：$modelPath（assets 与私有目录均未找到）")
                return null
            }
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(resolved)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumFaces(maxFaces)
                .setMinFaceDetectionConfidence(MIN_FACE_DETECTION_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_FACE_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(::handleResult)
                .setErrorListener(::handleError)
                .build()
            FaceLandmarker.createFromOptions(appContext, options).also {
                faceLandmarker = it
                PLog.d(TAG, "MediaPipe FaceLandmarker 初始化完成（maxFaces=$maxFaces）")
            }
        } catch (error: Exception) {
            // MediaPipe 在模型缺失/格式错误时抛 RuntimeException，这里必须兜住。
            initializationFailed = true
            PLog.e(TAG, "FaceLandmarker 初始化失败，AR 贴纸将保持关闭", error)
            onError?.invoke(error)
            null
        }
    }

    /**
     * 解析模型输入：优先 filesDir 下的绝对路径（已下载/导入），其次 assets 相对路径。
     *
     * @return MediaPipe 可接受路径；都不可用时返回 null
     */
    private fun resolveModelInput(): String? {
        val configured = modelPath.trim().ifEmpty { DEFAULT_MODEL_ASSET_PATH }
        val file = File(configured)
        if (file.isAbsolute && file.isFile && file.length() > 0L) return file.absolutePath
        val inFilesDir = File(appContext.filesDir, configured)
        if (inFilesDir.isFile && inFilesDir.length() > 0L) return inFilesDir.absolutePath
        return try {
            appContext.assets.open(configured).use { }
            configured
        } catch (_: Exception) {
            null
        }
    }

    private fun handleResult(result: FaceLandmarkerResult, input: MPImage) {
        val currentGeneration: Int
        synchronized(this) {
            currentGeneration = pendingGeneration
            cleanupPendingLocked()
        }
        if (currentGeneration != generation.get() || released.get()) return

        val faces = try {
            result.faceLandmarks().take(maxFaces).map { landmarks ->
                val flat = FloatArray(landmarks.size * 2)
                landmarks.forEachIndexed { index, landmark ->
                    flat[index * 2] = landmark.x()
                    flat[index * 2 + 1] = landmark.y()
                }
                flat
            }
        } catch (error: Exception) {
            PLog.w(TAG, "解析 FaceMesh 关键点失败", error)
            emptyList()
        }
        onFacesDetected?.invoke(
            FaceLandmarks(
                faces = faces,
                timestampMs = SystemClock.elapsedRealtime(),
                processingDurationNanos = 0L,
            ),
        )
        finishFrame()
    }

    private fun handleError(error: RuntimeException) {
        PLog.w(TAG, "FaceLandmarker 运行期错误", error)
        synchronized(this) { cleanupPendingLocked() }
        onError?.invoke(error)
        finishFrame()
    }

    private fun cleanupPendingLocked() {
        pendingImage = null
        pendingBitmap?.let { safeRecycle(it) }
        pendingBitmap = null
    }

    private fun finishFrame() {
        frameInFlight.set(false)
    }

    private fun safeRecycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            runCatching { bitmap.recycle() }
        }
    }
}
