package com.hinnka.mycamera.longexposure

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max
import kotlin.math.min

/**
 * 长曝光重计算的**统一后台入口**（PATTERN_SPEC §1.5 / §4 / §10.6）。
 *
 * 契约：
 *  - 全部重算走 [BgJobRunner] 全局低优先级队列（[BgTaskType.LONG_EXPOSURE]，由补丁注入枚举）；
 *  - 每个分片末尾调用 `job.checkYield()`（响应暂停/取消 + CPU 节流）；
 *  - 分配累加器/工作缓冲前用 `job.guardMemory(bytes)` 守卫；**超预算时降工作分辨率**，
 *    绝不因此限制用户帧数（帧数开放，默认 30，上限见 [LongExposureLimits]）；
 *  - 帧数不做总量上限：循环次数 = 用户配置帧数 ∩ 帧源可提供帧数。
 *
 * 帧源抽象 [FrameSource] 支持两种落地形态：
 *  1. [InMemoryFrameSource]：内存驻留的工作分辨率平面（连拍后立即处理）；
 *  2. [FileFrameSource]：**分片落盘**（每帧写盘、逐帧读回，峰值内存 = 1 帧）。
 * 相机侧只需实现 [FrameSource.readFrame] 即可接入任意采集/落盘策略。
 */
object LongExposureJobRunner {

    private const val TAG = "LongExposureJob"

    /** 任务类型：`BgTaskType.LONG_EXPOSURE` 由补丁在 `BgJobRunner.kt` 枚举中新增。 */
    private val TASK_TYPE = BgTaskType.LONG_EXPOSURE

    /** 每分片帧数（只决定"一次干多少"，不限制总量）。 */
    const val CHUNK_FRAMES = 4

    /** 工作分辨率降级的终点（长边像素）。 */
    const val MIN_WORK_LONG_EDGE = 320

    private const val BYTES_PER_SHORT_SAMPLE = 2L
    private const val BYTES_PER_FLOAT_SAMPLE = 4L
    private const val BYTES_PER_INT_SAMPLE = 4L

    /**
     * 帧源：按索引**逐帧**读取工作分辨率的线性 RGB 平面。
     *
     * 实现方需保证：
     *  - [frameCount] 为本轮实际可用帧数（用户配置帧数 ∩ 采集到的帧数）；
     *  - [readFrame] 允许返回 null（该帧损坏/读取失败）→ 运行器计数跳过，不中断任务。
     */
    interface FrameSource {
        val frameCount: Int
        val width: Int
        val height: Int
        fun readFrame(index: Int): LinearRgbPlane?
    }

    /** 内存帧源（平面已按工作分辨率准备好）。 */
    class InMemoryFrameSource(private val planes: List<LinearRgbPlane>) : FrameSource {
        override val frameCount: Int get() = planes.size
        override val width: Int get() = planes.firstOrNull()?.width ?: 1
        override val height: Int get() = planes.firstOrNull()?.height ?: 1
        override fun readFrame(index: Int): LinearRgbPlane? = planes.getOrNull(index)
    }

    /**
     * 分片落盘帧源：每帧写成一个文件，逐帧解码读回。
     * 峰值内存 = 1 帧（+ 累加器），帧数受磁盘而非内存约束。
     *
     * @param scale 读取缩放（配合 `BitmapFactory.inSampleSize`，进一步降低单帧驻留）。
     */
    class FileFrameSource(
        private val paths: List<String>,
        override val width: Int,
        override val height: Int,
        private val scale: Float = 1f,
    ) : FrameSource {
        override val frameCount: Int get() = paths.size

        override fun readFrame(index: Int): LinearRgbPlane? =
            paths.getOrNull(index)?.let { LongExposureBitmapAdapter.fromFile(it, scale) }
    }

    /** 一次长曝光处理请求。 */
    data class Request(
        val mode: LongExposureMode,
        val source: FrameSource,
        /** 暗帧（工作分辨率平面）；空 = 不做暗帧扣除。 */
        val darkFrames: List<LinearRgbPlane> = emptyList(),
        val lightPainting: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        val fireworks: FireworksConfig = FireworksConfig.DEFAULT,
        val superMoon: SuperMoonConfig = SuperMoonConfig.DEFAULT,
        /** 慢门预设（[LongExposureMode.SLOW_SHUTTER] 时必填）。 */
        val preset: SlowShutterPreset? = null,
    )

    /** 一次任务的真实运行参数（clamp/降级后的结果，供 UI 与自检回读）。 */
    data class RunOutcome(
        val requestedFrames: Int,
        val processedFrames: Int,
        val skippedFrames: Int,
        val workWidth: Int,
        val workHeight: Int,
        val workScale: Float,
        val degradedSteps: Int,
        val darkFrameUsed: Boolean,
        val hotPixelCount: Int,
        val stackMode: LongExposureStackMode,
        val reports: List<String>,
    )

    /** 成片 + 运行参数。 */
    private data class JobResult(val outcome: RunOutcome, val plane: LinearRgbPlane?)

    /**
     * 提交一次长曝光处理。
     *
     * @param previewEnabled 是否在生产任务内回调实时叠加预览（UI 侧也可复用
     *   [LightPaintingPreviewQueue]，二者互不冲突）。
     * @param onPreview 预览回调（**后台线程**）：参数为预览平面与已完成帧数。
     * @param onFinish 结束回调（**后台线程**）：成功给成片平面，取消/失败给 null。
     * @return 已入队的 [BgJob]（可 pause/resume/cancel）。
     */
    fun submit(
        request: Request,
        jobName: String = "长曝光",
        priority: Int = BgJobPriority.LOW,
        previewEnabled: Boolean = false,
        onPreview: ((LinearRgbPlane, Int) -> Unit)? = null,
        onOutcome: ((RunOutcome) -> Unit)? = null,
        onFinish: ((LinearRgbPlane?) -> Unit)? = null,
    ): BgJob {
        // 闭包内回传：block 与 onFinish 在同一后台线程顺序执行（BgJobRunner 契约）。
        var outcome: RunOutcome? = null
        var result: LinearRgbPlane? = null

        return BgJobRunner.submit(
            name = jobName,
            chunkSize = CHUNK_FRAMES,
            type = TASK_TYPE,
            priority = priority,
            onFinish = {
                onOutcome?.invoke(outcome ?: fallbackOutcome(request))
                onFinish?.invoke(result)
            },
        ) {
            val job = this // 接收者即本次 BgJob（submit 的 block: BgJob.() -> Unit）
            val jobResult = runJob(request, previewEnabled, onPreview, job)
            outcome = jobResult.outcome
            result = jobResult.plane
        }
    }

    private fun fallbackOutcome(request: Request): RunOutcome = RunOutcome(
        requestedFrames = targetFrameCount(request),
        processedFrames = 0,
        skippedFrames = 0,
        workWidth = request.source.width,
        workHeight = request.source.height,
        workScale = 1f,
        degradedSteps = 0,
        darkFrameUsed = request.darkFrames.isNotEmpty(),
        hotPixelCount = 0,
        stackMode = stackModeOf(request),
        reports = emptyList(),
    )

    private fun runJob(
        request: Request,
        previewEnabled: Boolean,
        onPreview: ((LinearRgbPlane, Int) -> Unit)?,
        job: BgJob,
    ): JobResult {
        val clamped = LongExposureConfigCodec.clampAll(
            request.lightPainting,
            request.fireworks,
            request.superMoon,
        )
        val reports = ArrayList<String>(clamped.reports)

        val mode = stackModeOf(request)
        val target = targetFrameCount(request).coerceAtMost(request.source.frameCount)
        val darkConfig = darkFrameConfigOf(request)

        var workWidth = request.source.width
        var workHeight = request.source.height
        var workScale = 1f
        var degradedSteps = 0

        // ---- 工作分辨率：先按配置缩放，再用内存守卫逐档降级（降级而非失败、不限帧） ----
        val (initialWidth, initialHeight) = if (request.lightPainting.workLongEdge != 0 ||
            request.fireworks.workLongEdge != 0 || request.superMoon.workLongEdge != 0
        ) {
            LongExposureBitmapAdapter.dimensionsForMaxLongEdge(
                request.source.width,
                request.source.height,
                maxOf(
                    request.lightPainting.workLongEdge,
                    request.fireworks.workLongEdge,
                    request.superMoon.workLongEdge,
                ),
            )
        } else {
            LongExposureBitmapAdapter.scaledDimensions(
                request.source.width,
                request.source.height,
                request.lightPainting.workScale,
            )
        }
        workWidth = initialWidth
        workHeight = initialHeight
        workScale = workWidth.toFloat() / request.source.width
        reports.add("work resolution ${request.source.width}x${request.source.height} -> ${workWidth}x$workHeight")

        val needToneMap = request.mode == LongExposureMode.SUPER_MOON && request.superMoon.toneMap.enabled
        while (true) {
            val estimate = estimateBytes(
                workWidth,
                workHeight,
                mode,
                needToneMap,
                hasDark = request.darkFrames.isNotEmpty(),
            )
            if (job.guardMemory(estimate)) break
            val nextWidth = workWidth / 2
            if (nextWidth < MIN_WORK_LONG_EDGE) {
                PLog.w(TAG, "memory guard still rejecting at min work edge; continue streaming anyway")
                break
            }
            workWidth = nextWidth
            workHeight = max(1, workHeight / 2)
            workScale = workWidth.toFloat() / request.source.width
            degradedSteps++
            PLog.w(
                TAG,
                "guardMemory reject (need=$estimate), degrade work resolution to ${workWidth}x$workHeight",
            )
        }
        if (degradedSteps > 0) {
            reports.add("memory guard degraded work resolution $degradedSteps step(s) -> ${workWidth}x$workHeight")
        }

        PLog.i(
            TAG,
            "start mode=${request.mode} target=$target source=${request.source.frameCount} " +
                "work=${workWidth}x$workHeight scale=$workScale",
        )

        // ---- 暗帧平均（内存不足时降级为"不做暗帧扣除"，而不是失败） ----
        var dark: ShortArray? = null
        if (darkConfig.enabled && request.darkFrames.isNotEmpty()) {
            val darkSize = workWidth * workHeight * 3
            val darkBytes = darkSize.toLong() * BYTES_PER_INT_SAMPLE
            if (!job.guardMemory(darkBytes)) {
                PLog.w(TAG, "dark frame averaging exceeds budget ($darkBytes), skip dark subtraction")
                reports.add("dark frame skipped: memory budget")
            } else {
                val planes = request.darkFrames.mapNotNull { resampleTo(it, workWidth, workHeight) }
                dark = DarkFrameSubtractor.averageDarkFrames(planes, darkSize)
                val rejected = DarkFrameSubtractor.countRejectedDarkFrames(planes, darkSize)
                if (rejected > 0) reports.add("dark frame rejected: $rejected of ${planes.size}")
                val hotPixels = DarkFrameSubtractor.countHotPixels(dark, darkConfig.hotPixelThreshold)
                if (hotPixels > 0) {
                    reports.add("dark hot pixels: $hotPixels (threshold=${darkConfig.hotPixelThreshold})")
                }
            }
        }

        val stacker = LongExposureStacker(workWidth, workHeight, mode)
        val gains = colorGainsOf(request)
        val toneMap = if (needToneMap) request.superMoon.clamped().toneMap else null
        val previewEvery = previewIntervalOf(request)
        val previewEdge = previewEdgeOf(request)
        val total = max(target, 1)

        job.report(0, total)
        job.checkYield()

        var processed = 0
        var skipped = 0
        val batch = job.batchSizeFor(total)
        var index = 0
        while (index < target) {
            val end = min(index + batch, target)
            for (i in index until end) {
                val plane = request.source.readFrame(i)
                val samples = plane?.let { resampleTo(it, workWidth, workHeight) }
                if (samples == null) {
                    skipped++
                    continue
                }
                stacker.addFrame(samples, dark, darkConfig)
                processed++
                if (previewEnabled && onPreview != null && previewEvery > 0 && processed % previewEvery == 0) {
                    onPreview(stacker.previewPlane(previewEdge, gains), processed)
                }
            }
            job.report(end, total)
            job.checkYield()
            index = end
        }

        val plane = stacker.result(gains, toneMap)

        val outcome = RunOutcome(
            requestedFrames = targetFrameCount(request),
            processedFrames = processed,
            skippedFrames = skipped + stacker.rejectedFrameCount,
            workWidth = workWidth,
            workHeight = workHeight,
            workScale = workScale,
            degradedSteps = degradedSteps,
            darkFrameUsed = dark != null,
            hotPixelCount = stacker.hotPixelCount,
            stackMode = mode,
            reports = reports,
        )
        PLog.i(
            TAG,
            "done processed=$processed skipped=${outcome.skippedFrames} " +
                "work=${workWidth}x$workHeight mode=$mode hotPixels=${stacker.hotPixelCount}",
        )
        return JobResult(outcome, plane)
    }

    /** 把任意尺寸的平面重采样到工作尺寸（相同尺寸直接复用样本，不复制）。 */
    private fun resampleTo(plane: LinearRgbPlane, workWidth: Int, workHeight: Int): ShortArray? {
        if (plane.width == workWidth && plane.height == workHeight) return plane.samples
        if (workWidth <= 0 || workHeight <= 0) return null
        return LongExposureResampler.downsample(plane.samples, plane.width, plane.height, workWidth, workHeight)
    }

    /**
     * 峰值内存估算：帧缓冲 + 累加器 + 暗帧 +（可选）色调映射的浮点平面。
     * 用途仅是"是否需要降工作分辨率"，**不是总量上限**。
     */
    private fun estimateBytes(
        width: Int,
        height: Int,
        mode: LongExposureStackMode,
        needToneMap: Boolean,
        hasDark: Boolean,
    ): Long {
        val samples = width.toLong() * height.toLong() * 3L
        val frame = samples * BYTES_PER_SHORT_SAMPLE
        val accumulator = when (mode) {
            LongExposureStackMode.MAX -> samples * BYTES_PER_SHORT_SAMPLE
            LongExposureStackMode.MEAN -> samples * BYTES_PER_INT_SAMPLE + samples * BYTES_PER_SHORT_SAMPLE
        }
        val darkBytes = if (hasDark) samples * BYTES_PER_INT_SAMPLE + frame else 0L
        // 色调映射：luma + 全局曲线 + 盒模糊临时 ≈ 3 × 4B/像素。
        val toneBytes = if (needToneMap) width.toLong() * height.toLong() * 3L * BYTES_PER_FLOAT_SAMPLE else 0L
        return frame * 2 + accumulator + darkBytes + toneBytes
    }

    // ------------------------------------------------------------ 请求解析

    /** 目标帧数（用户配置；**无总量截断**，只受帧源可提供帧数限制）。 */
    fun targetFrameCount(request: Request): Int = when (request.mode) {
        LongExposureMode.LIGHT_PAINTING -> request.lightPainting.clamped().frameCount
        LongExposureMode.SLOW_SHUTTER -> request.preset?.clamped()?.frameCount ?: 20
        LongExposureMode.FIREWORKS -> request.fireworks.clamped().frameCount
        LongExposureMode.SUPER_MOON -> request.superMoon.clamped().frameCount
    }

    fun stackModeOf(request: Request): LongExposureStackMode = when (request.mode) {
        LongExposureMode.LIGHT_PAINTING -> request.lightPainting.clamped().stackMode
        LongExposureMode.SLOW_SHUTTER -> request.preset?.clamped()?.stackMode ?: LongExposureStackMode.MAX
        LongExposureMode.FIREWORKS -> request.fireworks.clamped().stackMode
        LongExposureMode.SUPER_MOON -> LongExposureStackMode.MEAN
    }

    fun darkFrameConfigOf(request: Request): DarkFrameConfig = when (request.mode) {
        LongExposureMode.LIGHT_PAINTING -> request.lightPainting.clamped().darkFrame
        LongExposureMode.SLOW_SHUTTER -> if (request.preset?.darkFrameEnabled == true) {
            request.lightPainting.clamped().darkFrame
        } else {
            DarkFrameConfig.DEFAULT.copy(enabled = false)
        }

        LongExposureMode.FIREWORKS -> request.fireworks.clamped().darkFrame
        LongExposureMode.SUPER_MOON -> DarkFrameConfig.DEFAULT.copy(enabled = false)
    }

    private fun previewIntervalOf(request: Request): Int =
        if (request.mode == LongExposureMode.LIGHT_PAINTING) {
            request.lightPainting.clamped().previewIntervalFrames
        } else {
            1
        }

    private fun previewEdgeOf(request: Request): Int =
        if (request.mode == LongExposureMode.LIGHT_PAINTING) {
            request.lightPainting.clamped().previewMaxLongEdge
        } else {
            480
        }

    /** 光源画笔色偏增益（仅光绘模式）；null = 不做色偏。 */
    private fun colorGainsOf(request: Request): FloatArray? {
        if (request.mode != LongExposureMode.LIGHT_PAINTING) return null
        val config = request.lightPainting.clamped()
        val gains = ColorTemperatureGains.gainsFor(config.brushColorTemperatureK, config.brushTint)
        if (config.brushGain == 1f) return gains
        for (i in gains.indices) gains[i] = (gains[i] * config.brushGain).coerceIn(0f, 8f)
        return gains
    }
}
