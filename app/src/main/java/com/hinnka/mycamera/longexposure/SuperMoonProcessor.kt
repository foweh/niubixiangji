package com.hinnka.mycamera.longexposure

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.computational.LinearDngMerger
import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.processor.MultiFrameStacker
import com.hinnka.mycamera.processor.RawStackFrame
import com.hinnka.mycamera.utils.PLog

/**
 * 超级月亮处理（**复用优先**：多帧堆栈走既有异步入口，本类只补"月亮局部色调映射"）。
 *
 * 复用点（PATTERN_SPEC §9）：
 *  1. YUV 多帧对齐/合并：直接调 `MultiFrameStacker.processAstroBurstAsync(...)`
 *     —— 这是**异步入口**（内部 `BgJobRunner` + `BgTaskType.ASTRO` + `guardMemory` + `checkYield`），
 *     不是交叉审查报告里指出的"死代码同步入口"（`processAstroBurst`）；
 *  2. RAW/DNG 多帧线性合并：直接调 `LinearDngMerger.mergeAsync(...)`
 *     —— RAW 路径自带高光恢复 hook（长曝帧 clipped 时改用短曝帧贡献），因此 **RAW 输出不再叠加
 *     本模块的 RGB 色调映射**（Bayer 域做 RGB 色调映射无意义，且会破坏线性 DNG 语义）；
 *  3. 只有"月亮局部色调映射（高光保留）"是本模块新增（见 [SuperMoonToneMapper]），
 *     它作用在合并后的 RGB 平面/Bitmap 上，作为 `BgTaskType.LONG_EXPOSURE` 任务提交。
 *
 * 帧数开放：`config.frameCount` 只用于约束采集侧目标帧数；堆栈循环本身由既有异步入口按
 * 分片 + 内存守卫处理（不设总量上限）。
 */
object SuperMoonProcessor {

    private const val TAG = "SuperMoonProcessor"

    /** 至少 2 帧才构成"堆栈"；不足时交回上层走单帧。 */
    const val MIN_STACK_FRAMES = 2

    /**
     * YUV 多帧堆栈 → 月亮局部色调映射。
     *
     * @param colorSpace 堆栈输出色彩空间（与既有 `MultiFrameStacker.processAstroBurstAsync`
     *   的必填参数一致，直接透传；相机侧一般传 `Camera2Controller` 当前 capture 的
     *   `ImageReader`/`Image.getColorSpace()` 或 `ColorSpace.get(ColorSpace.Named.SRGB)`）。
     * @param images 对齐/免对齐由 [SuperMoonConfig.align] 决定；本方法**接管其所有权**
     *   （既有异步入口会在结束时关闭所有 frame）。
     * @return 堆栈任务 [BgJob]（可取消；色调映射任务在其完成回调内另行提交）。
     */
    fun stackAsync(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        config: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        displayName: String = "超级月亮",
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob? {
        val clamped = config.clamped()
        if (images.size < MIN_STACK_FRAMES) {
            PLog.w(TAG, "super moon needs >= $MIN_STACK_FRAMES frames, got ${images.size}; fallback single frame")
            images.forEach { runCatching { it.close() } }
            onFinish?.invoke(null)
            return null
        }
        return MultiFrameStacker.processAstroBurstAsync(
            images = images,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
            displayName = "$displayName 堆栈(${images.size}帧)",
            align = clamped.align,
            sigmaClipSigma = clamped.sigmaClipSigma,
            onResult = { stacked ->
                if (stacked == null) {
                    onFinish?.invoke(null)
                } else {
                    toneMapAsync(stacked, clamped, "$displayName 色调映射", onFinish)
                }
            },
        )
    }

    /**
     * 月亮局部色调映射（高光保留）后台任务。
     *
     * @param source 堆栈结果位图（本方法不回收它，由调用方决定；内部只读取像素）。
     */
    fun toneMapAsync(
        source: Bitmap,
        config: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        displayName: String = "月亮色调映射",
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob {
        val tone = config.clamped().toneMap
        var output: Bitmap? = null
        return BgJobRunner.submit(
            name = displayName,
            chunkSize = 1,
            type = BgTaskType.LONG_EXPOSURE,
            priority = BgJobPriority.LOW,
            onFinish = { onFinish?.invoke(output) },
        ) {
            val job = this // 接收者即本次 BgJob（submit 的 block: BgJob.() -> Unit）
            job.report(0, 3)
            val plane = LongExposureBitmapAdapter.fromBitmap(source)
            job.report(1, 3)
            job.checkYield()

            // 内存守卫：色调映射需要 2 个 FloatArray（亮度 + 模糊临时）；不足则降工作分辨率。
            var width = plane.width
            var height = plane.height
            var samples = plane.samples
            while (!job.guardMemory(width.toLong() * height.toLong() * 8L) && width > 64) {
                val nextWidth = width / 2
                val nextHeight = maxOf(1, height / 2)
                PLog.w(TAG, "tone map over budget, degrade ${width}x$height -> ${nextWidth}x$nextHeight")
                samples = LongExposureResampler.downsample(samples, width, height, nextWidth, nextHeight)
                width = nextWidth
                height = nextHeight
            }
            job.report(2, 3)
            job.checkYield()

            val mapped = LongExposureNative.toneMap(samples.copyOf(), width, height, tone)
                ?: SuperMoonToneMapper.toneMap(samples, width, height, tone)
            output = LongExposureBitmapAdapter.toBitmap(LinearRgbPlane(width, height, mapped))
            job.report(3, 3)
            job.checkYield()
        }
    }

    /**
     * RAW/DNG 线性多帧合并（复用 [LinearDngMerger.mergeAsync]）。
     *
     * 高光保留由该链路自身的 highlight-recovery hook 承担（长曝帧 clipped 时取短曝帧贡献），
     * 因此这里**不再**叠加 RGB 色调映射。
     */
    fun mergeRawAsync(
        frames: List<RawStackFrame>,
        whiteLevel: Int,
        config: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        displayName: String = "超级月亮 RAW 合并",
        onFinish: ((LinearDngMerger.LinearDngResult?) -> Unit)? = null,
    ): BgJob = LinearDngMerger.mergeAsync(
        frames = frames,
        whiteLevel = whiteLevel,
        sigmaClipSigma = config.clamped().sigmaClipSigma,
        displayName = displayName,
        onResult = { onFinish?.invoke(it) },
    )

    /**
     * 平面级堆栈 + 色调映射（供 RAW 转平面/离线批处理路径使用）。
     *
     * 走本模块统一后台入口 [LongExposureJobRunner]（`BgTaskType.LONG_EXPOSURE`），
     * 语义为 MEAN 叠加 + 月亮局部色调映射。
     */
    fun stackPlanesAsync(
        source: LongExposureJobRunner.FrameSource,
        config: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        displayName: String = "超级月亮",
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob = LongExposureJobRunner.submit(
        request = LongExposureJobRunner.Request(
            mode = LongExposureMode.SUPER_MOON,
            source = source,
            superMoon = config,
        ),
        jobName = displayName,
        onFinish = onFinish,
    )
}
