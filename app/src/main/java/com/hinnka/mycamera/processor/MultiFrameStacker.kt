package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.MultiFrameConfig
import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.raw.DngProfileGainTableMap
import com.hinnka.mycamera.raw.MgcSpatialStrengthMap
import com.hinnka.mycamera.raw.RawProfileToneMapMode
import com.hinnka.mycamera.raw.RawSceneAERawStats
import com.hinnka.mycamera.utils.BitmapUtils
import com.hinnka.mycamera.computational.AstroStacker
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.camera.PsStackType

enum class RawStackBufferLayout {
    CFA,
    LINEAR_RGB,
}

enum class MgcSpatialOutputMode {
    BAYER,
    RGB,
}

/** Merge implementations exposed by MGC's ShotParams::merge_method_override. */
enum class MgcMergeMethod(val mgcValue: Int) {
    WIENER(0),
    SABRE(1),
    SPATIAL_BAYER(2),
    SPATIAL_RGB(3),
}

/** HDR+ fusion modes exposed in professional-mode settings. */
enum class MgcRawMaxMode {
    SABRE,
    SPATIAL;

    companion object {
        val DEFAULT: MgcRawMaxMode = SABRE
    }

    val supportsBracketExposure: Boolean
        get() = this == SPATIAL

    val outputMode: MgcSpatialOutputMode
        get() = MgcSpatialOutputMode.RGB

    val mergeMethod: MgcMergeMethod
        get() = when (this) {
            SABRE -> MgcMergeMethod.SABRE
            SPATIAL -> MgcMergeMethod.SPATIAL_RGB
        }
}

/**
 * Physical storage of an opaque LinearRaw texture. RGBA16F is used only for the direct Spatial
 * default-denoise handoff; persistent render/DNG sources use RGBA16UI.
 */
enum class GpuLinearRgbStorage {
    RGBA16UI,
    RGBA16F,
}

/** Opaque LinearRaw texture owned by the persistent RAW renderer context. */
data class GpuLinearRgbSource(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val samplesPerPixel: Int = 4,
    val stackCompletionTimeline: GpuStackCompletionTimeline? = null,
    val storage: GpuLinearRgbStorage = GpuLinearRgbStorage.RGBA16UI,
)

/**
 * Opaque normalized Bayer texture exported by a stacker into the persistent RAW renderer context.
 * Storage is full-resolution R16UI CFA. It may only be consumed or released on that context's GL
 * dispatcher.
 */
data class GpuBayerSource(
    val textureId: Int,
    val width: Int,
    val height: Int,
    val stackCompletionTimeline: GpuStackCompletionTimeline? = null,
)

data class RawStackResult(
    /** Null when a GPU source is exported and CPU/DNG materialization has been deferred. */
    var fusedBayerBuffer: ByteBuffer?,
    val width: Int,
    val height: Int,
    val isNormalizedSensorData: Boolean,
    val blackLevel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    val fusedBayerUsesNativeAllocator: Boolean = false,
    val profileGainTableMap: DngProfileGainTableMap? = null,
    val profileToneMapMode: RawProfileToneMapMode = RawProfileToneMapMode.Default,
    val diagnostics: RawStackDiagnostics? = null,
    val bufferLayout: RawStackBufferLayout = RawStackBufferLayout.CFA,
    val inputRowStepSamples: Int? = null,
    val inputColStepSamples: Int? = null,
    val baselineExposureEv: Float? = null,
    val gpuLinearRgbSource: GpuLinearRgbSource? = null,
    val gpuBayerSource: GpuBayerSource? = null,
    /** Reference-frame sensor maxima used by MGC Fast Moments after a LinearRaw merge. */
    val fastMomentsRawStats: RawSceneAERawStats? = null,
    /** True only when lens-shading gain has already been multiplied into the fused pixels. */
    val lensShadingCorrectionApplied: Boolean = false,
    val mergedFrameCount: Int = 1,
    /**
     * MGC's normalized 128-bin merged NoiseModel correlation spectrum.
     *
     * Null means the merged model is unavailable; a requested default merge-denoise pass must fail.
     */
    val mgcDenoiseCorrelation: FloatArray? = null,
    /**
     * Normalized camera-RGB read variance emitted by the selected MGC merge branch.
     */
    val mgcDenoiseReadNoise: FloatArray? = null,
    /**
     * Normalized camera-RGB shot coefficient emitted by the selected MGC merge branch.
     */
    val mgcDenoiseShotNoise: FloatArray? = null,
    /**
     * Exact process-local Q8 variance multiplier emitted by MGC Spatial and consumed before
     * DNG write.
     */
    val mgcSpatialStrengthMap: MgcSpatialStrengthMap? = null,
    /**
     * Merged output-frame SNR used by MGC FinishRaw to select luma/chroma tuning.
     * This is the linear signal-domain SNR, not ISO or sensor gain.
     */
    val mgcDenoiseTuningSnr: Float? = null,
    /** Reference-frame SNR for original MGC sharpen curve selection; not the merged SNR. */
    val mgcSharpenTuningSnr: Float? = null,
    /** MGC-derived attenuation applied to the final sharpen kernel. */
    val mgcSharpenAttenuationScale: Float? = null,
    /**
     * True only for the debug reference-only isolation path. This state is process-local and is
     * never persisted into RAW/DNG metadata.
     */
    val mgcSpatialReferenceOnlyDiagnostic: Boolean = false,
)

enum class YuvHdrStackFrameRole {
    ZERO_EV,
    HIGH_EV,
    LOW_EV,
}

data class YuvHdrStackFrame(
    val image: SafeImage,
    val exposureProduct: Float,
    val role: YuvHdrStackFrameRole,
)

/**
 * Multi-Frame Stacker
 * 
 * Manages the native stacking process for burst captures.
 * Aligns and merges multiple frames to reduce noise and improve quality.
 */
object MultiFrameStacker {
    private const val TAG = "MultiFrameStacker"

    /**
     * Process a burst of images and return a stacked Bitmap.
     * 
     * @param images List of captured Images (YUV_420_888).
     * @return Stacked Bitmap (ARGB_8888), or null if failed.
     */
    @Synchronized
    fun processBurst(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        outputScale: Float = MultiFrameConfig.DEFAULT_SUPER_RESOLUTION_SCALE,
        colorSpace: ColorSpace,
    ): Bitmap? {
        if (images.isEmpty()) return null

        val width = images[0].width
        val height = images[0].height

        val scale = MultiFrameConfig.normalizeOutputScale(outputScale)
        val dimensions = BitmapUtils.calculateProcessedRect(width, height, aspectRatio, null, rotation)
        // Match preparePhoto: scale the input bounds, then apply the aspect crop and rotation.
        // Keep the native crop separately so rounding never changes the sampled field of view.
        val outputDimensions = BitmapUtils.calculateProcessedRect(
            MultiFrameConfig.scaledRawOutputDimension(width, scale),
            MultiFrameConfig.scaledRawOutputDimension(height, scale),
            aspectRatio,
            null,
            rotation,
        )

        val inputFormat = images[0].format
        if (!GlesYuvStacker.supportsImageFormat(inputFormat)) {
            PLog.w(TAG, "GLES streaming stacker does not support image format=$inputFormat")
            images.forEach { it.close() }
            return null
        }
        RawStackRuntimeDebug.i(TAG) {
            "Starting GLES streaming stacking process for ${images.size} frames ($width x $height). outputScale=$scale"
        }
        return try {
            GlesYuvStacker(
                width = width,
                height = height,
                outputWidth = outputDimensions.width(),
                outputHeight = outputDimensions.height(),
                rotation = rotation,
                colorSpace = colorSpace,
                inputFormat = inputFormat,
                outputScale = scale,
                referenceOutputWidth = dimensions.width(),
                referenceOutputHeight = dimensions.height(),
            ).process(images).also { result ->
                if (result == null) {
                    PLog.w(TAG, "GLES streaming stacker failed")
                }
            }
        } finally {
            images.forEach { it.close() }
        }
    }

    @Synchronized
    fun processHdrBurstYuv(
        frames: List<YuvHdrStackFrame>,
        fusionExposureProducts: FloatArray?,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
    ): Bitmap? {
        if (frames.size < 3) return null
        val images = frames.map { it.image }
        val width = images[0].width
        val height = images[0].height
        val dimensions = BitmapUtils.calculateProcessedRect(width, height, aspectRatio, null, rotation)
        val inputFormat = images[0].format

        if (!GlesYuvStacker.supportsImageFormat(inputFormat)) {
            PLog.w(TAG, "GLES HDR YUV stacker does not support image format=$inputFormat")
            images.forEach { it.close() }
            return null
        }

        val result = try {
            GlesYuvStacker(
                width = width,
                height = height,
                outputWidth = dimensions.width(),
                outputHeight = dimensions.height(),
                rotation = rotation,
                colorSpace = colorSpace,
                inputFormat = inputFormat,
            ).processHdr(
                frames = frames.map {
                    GlesYuvStacker.HdrInputFrame(
                        image = it.image,
                        exposureProduct = it.exposureProduct,
                        role = when (it.role) {
                            YuvHdrStackFrameRole.ZERO_EV -> GlesYuvStacker.HdrFrameRole.ZERO_EV
                            YuvHdrStackFrameRole.HIGH_EV -> GlesYuvStacker.HdrFrameRole.HIGH_EV
                            YuvHdrStackFrameRole.LOW_EV -> GlesYuvStacker.HdrFrameRole.LOW_EV
                        },
                    )
                },
                exposureProducts = fusionExposureProducts,
            )
        } finally {
            images.forEach { it.close() }
        }
        return result
    }

    @Synchronized
    fun processBurstRaw(
        frames: List<RawStackFrame>,
        cfaPattern: Int,
        outputMode: MgcSpatialOutputMode = MgcSpatialOutputMode.BAYER,
        mergeMethod: MgcMergeMethod = when (outputMode) {
            MgcSpatialOutputMode.BAYER -> MgcMergeMethod.SPATIAL_BAYER
            MgcSpatialOutputMode.RGB -> MgcMergeMethod.SPATIAL_RGB
        },
        masterBlackLevel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        whiteLevel: Int = 1023,
        whiteBalanceGains: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        cameraWhite: FloatArray,
        noiseProfileSelection: RawNoiseProfileSelection,
        lensShading: FloatArray? = null,
        lensShadingWidth: Int = 0,
        lensShadingHeight: Int = 0,
        applyLensShadingCorrection: Boolean = true,
        sourceBounds: Rect? = null,
        useCurrentGlContext: Boolean = false,
        exportGpuLinearRgbSource: Boolean = false,
        gpuLinearRgbStorage: GpuLinearRgbStorage = GpuLinearRgbStorage.RGBA16UI,
        enableHdrFusion: Boolean = true,
    ): RawStackResult? {
        if (frames.isEmpty()) return null
        val images = frames.map { it.image }
        val sourceWidth = images[0].width
        val sourceHeight = images[0].height
        val physicalSourceBounds = sourceBounds ?: Rect(0, 0, sourceWidth, sourceHeight)
        require(
            physicalSourceBounds.left >= 0 && physicalSourceBounds.top >= 0 &&
                physicalSourceBounds.right <= sourceWidth &&
                physicalSourceBounds.bottom <= sourceHeight &&
                !physicalSourceBounds.isEmpty &&
                (physicalSourceBounds.width() and 1) == 0 &&
                (physicalSourceBounds.height() and 1) == 0
        ) { "RAW physical crop must contain complete Bayer cells: $physicalSourceBounds" }
        // Fusion and FinishRaw consume the native sensor grid. Display scaling follows sharpening.
        RawStackRuntimeDebug.d(TAG) {
            "Starting MGC ${if (mergeMethod == MgcMergeMethod.SABRE) "Sabre" else "Spatial ${outputMode.name}"} " +
                "fusion for ${images.size} frames source=${sourceWidth}x$sourceHeight " +
                "physicalCrop=$physicalSourceBounds " +
                "Pattern=$cfaPattern " +
                "BL=${masterBlackLevel.joinToString()} WL=$whiteLevel " +
                "noiseProfile=${noiseProfileSelection.id} " +
                "legacyHdrFlag=$enableHdrFusion"
        }
        val stackLensShading = validLensShadingOrNull(
            lensShading = lensShading,
            width = lensShadingWidth,
            height = lensShadingHeight,
            enabled = applyLensShadingCorrection && outputMode == MgcSpatialOutputMode.RGB,
        )
        return GlesMgcRawFusion(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceBounds = physicalSourceBounds,
            cfaPattern = cfaPattern,
            blackLevel = masterBlackLevel,
            whiteLevel = whiteLevel,
            whiteBalanceGains = whiteBalanceGains,
            cameraWhite = cameraWhite,
            noiseProfileSelection = noiseProfileSelection,
            lensShading = stackLensShading,
            lensShadingWidth = if (stackLensShading != null) lensShadingWidth else 0,
            lensShadingHeight = if (stackLensShading != null) lensShadingHeight else 0,
            outputMode = outputMode,
            mergeMethod = mergeMethod,
            useCurrentGlContext = useCurrentGlContext,
            exportGpuLinearRgbSource = exportGpuLinearRgbSource,
            gpuLinearRgbStorage = gpuLinearRgbStorage,
        ).processFrames(frames)
    }

    private fun validLensShadingOrNull(
        lensShading: FloatArray?,
        width: Int,
        height: Int,
        enabled: Boolean,
    ): FloatArray? {
        if (!enabled || lensShading == null || width <= 0 || height <= 0) return null
        return lensShading.takeIf { it.size >= width * height * 4 }
    }

    // ------------------------------------------------------------------
    // 天文 / PS 式堆栈（A2 计算摄影模块，原则 B：轻量 Kotlin 移植）
    // ------------------------------------------------------------------

    /**
     * 天文堆栈（银河/深空）：默认免对齐（三脚架固定拍摄，星点位移保留）+ sigma-clip 合并。
     *
     * 输入输出约定与 [processBurst] 一致：YUV_420_888 帧列表（函数负责 close），
     * 返回 ARGB_8888 Bitmap；在后台线程调用。失败返回 null。
     *
     * 场景（用户澄清）：主流程=架三脚架固定拍摄，默认不对齐；[align] 仅作手持/多帧间
     * 位移补偿的可选增强。sigma-clip 裁剪合并自动剔除卫星/飞机等离群轨迹。
     */
    @Synchronized
    fun processAstroBurst(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        referenceFrameIndex: Int = images.size / 2,
        sigmaClipSigma: Float = AstroStacker.DEFAULT_SIGMA_CLIP_SIGMA,
        maxAlignShiftPx: Int = AstroStacker.DEFAULT_MAX_ALIGN_SHIFT_PX,
        enableRotationCompensation: Boolean = false,
        align: Boolean = false,
    ): Bitmap? {
        if (images.isEmpty()) return null
        return try {
            AstroStacker.stackAstro(
                images = images,
                rotation = rotation,
                aspectRatio = aspectRatio,
                referenceFrameIndex = referenceFrameIndex,
                sigmaClipSigma = sigmaClipSigma,
                maxAlignShiftPx = maxAlignShiftPx,
                enableRotationCompensation = enableRotationCompensation,
                align = align,
            )
        } finally {
            images.forEach { it.close() }
        }
    }

    /**
     * 星轨堆栈：不对齐，逐帧亮化（lighten=max）叠加，用于星轨/长曝光光绘。
     * 约定同 [processAstroBurst]。
     */
    @Synchronized
    fun processStarTrailsBurst(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        accumulationMode: AstroStacker.TrailAccumulationMode =
            AstroStacker.TrailAccumulationMode.LIGHTEN_MAX,
    ): Bitmap? {
        if (images.isEmpty()) return null
        return try {
            AstroStacker.stackStarTrails(
                images = images,
                rotation = rotation,
                aspectRatio = aspectRatio,
                mode = accumulationMode,
            )
        } finally {
            images.forEach { it.close() }
        }
    }

    /**
     * PS 式堆栈（Photopea/Photoshop 堆栈模式）：MEAN/MEDIAN/MAX/MIN。
     *  - MEAN：多帧降噪；
     *  - MEDIAN：去除移动物体（行人/车）；
     *  - MAX：星轨/亮化叠加；
     *  - MIN：暗场/热噪点去除。
     * align=true 时做全局平移对齐；false 时直接叠加。约定同 [processBurst]。
     */
    @Synchronized
    fun processPsStack(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        type: PsStackType,
        frameCount: Int,
        align: Boolean,
    ): Bitmap? {
        if (images.isEmpty()) return null
        return try {
            AstroStacker.stackPs(
                images = images,
                rotation = rotation,
                aspectRatio = aspectRatio,
                type = type,
                frameCount = frameCount,
                align = align,
            )
        } finally {
            images.forEach { it.close() }
        }
    }

    // ------------------------------------------------------------------
    // 长时任务后台化（用户 2026-10-15 性能策略：低优先级慢速完成，可暂停/恢复/进度）
    // 这些是 job 化入口，不改变上面同步方法的行为；onResult 在后台线程回调。
    // ------------------------------------------------------------------

    /** 星轨（200+ 帧）后台慢速堆栈。返回 [BgJob]（UI 读 state 显示进度条）。 */
    fun processStarTrailsAsync(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        displayName: String = "Star Trails",
        accumulationMode: AstroStacker.TrailAccumulationMode = AstroStacker.TrailAccumulationMode.LIGHTEN_MAX,
        onResult: (Bitmap?) -> Unit,
    ): BgJob = BgJobRunner.submit(displayName, chunkSize = 8) {
        val result = AstroStacker.stackStarTrails(
            images = images, rotation = rotation, aspectRatio = aspectRatio,
            mode = accumulationMode, job = this,
        )
        images.forEach { it.close() }
        onResult(result)
    }

    /** PS 堆栈后台化（MEDIAN 去移动物体/大量帧时用）。 */
    fun processPsStackAsync(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        type: PsStackType,
        frameCount: Int,
        align: Boolean,
        displayName: String = "Stack",
        onResult: (Bitmap?) -> Unit,
    ): BgJob = BgJobRunner.submit(displayName, chunkSize = 8) {
        val result = AstroStacker.stackPs(
            images = images, rotation = rotation, aspectRatio = aspectRatio,
            type = type, frameCount = frameCount, align = align, job = this,
        )
        images.forEach { it.close() }
        onResult(result)
    }

}
