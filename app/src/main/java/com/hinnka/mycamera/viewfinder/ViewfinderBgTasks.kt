package com.hinnka.mycamera.viewfinder

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/**
 * 大图边缘检测的后台任务封装（PATTERN_SPEC §4 / §10）。
 *
 * 取舍说明（用户硬约束「轻任务前台直跑、重任务走 BgJobRunner」）：
 * - **轻量**：直方图统计（一次线性扫描 + 桶自增）、贴纸锚点换算（十几次浮点）→ 前台按
 *   [SamplingThrottle] 节流直跑，见 [ViewfinderHistogramAnalyzer] 与 [FaceStickerAnchorMapper]；
 * - **重计算**：大图（≥ [ViewfinderCpuEdgeDetector.MAX_SUGGESTED_PIXELS] 量级）Sobel 边缘检测
 *   → 必须走本对象，进 [BgJobRunner] 串行后台队列，分片 + [BgJob.guardMemory] + [BgJob.checkYield]。
 *
 * 任务类型默认用既有的 [BgTaskType.GENERIC]（**无需补丁即可编译运行**）；
 * 若集成阶段应用了补丁里的 `VIEWFINDER` 枚举，可传入该类型以获得独立频控/内存预算（互不干扰其它任务）。
 * 内存不足时不失败，而是**逐级降低工作分辨率**。
 */
object ViewfinderBgTasks {

    private const val TAG = "ViewfinderBgTasks"

    /** 工作分辨率降级序列（长边像素）。 */
    private val WORK_LONG_EDGE_LADDER = intArrayOf(2048, 1536, 1024, 768, 512)

    /** 大图边缘检测结果。 */
    data class EdgeDetectionResult(
        val width: Int,
        val height: Int,
        val magnitude: FloatArray,
        val coverageRatio: Float,
        /** 实际使用的工作分辨率长边（降级后可能小于请求值）。 */
        val workingLongEdge: Int,
    )

    /**
     * 提交大图边缘检测。
     *
     * @param pixels ARGB_8888 源像素（调用方需保证在本任务执行期间不被修改）
     * @param width / height 源图尺寸
     * @param config 峰值对焦配置（提供阈值/采样步长）
     * @param type 后台任务类型，默认 [BgTaskType.GENERIC]
     * @param priority 任务优先级，默认 LOW（不抢前台取景/拍照）
     * @param onResult 结果回调（后台线程）
     * @param onError 异常回调（后台线程）
     * @return 已提交的 [BgJob]，可 pause/resume/cancel
     */
    fun submitHeavyEdgeDetection(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: PeakingEnhanceConfig,
        type: BgTaskType = BgTaskType.GENERIC,
        priority: Int = BgJobPriority.LOW,
        onResult: (EdgeDetectionResult) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): BgJob = BgJobRunner.submit(
        name = "取景器边缘检测",
        chunkSize = 16,
        type = type,
        priority = priority,
        onFinish = { job ->
            if (job.state.value.status == com.hinnka.mycamera.computational.BgJobStatus.FAILED) {
                PLog.w(TAG, "边缘检测任务结束于失败态：${job.state.value.message}")
            }
        },
    ) { job ->
        val clamped = PeakingEnhanceConfig.clamp(config).value
        try {
            var workingLongEdge = WORK_LONG_EDGE_LADDER.first()
            var workingPixels: IntArray? = null
            var workingWidth = 0
            var workingHeight = 0

            // 逐级降工作分辨率，直到内存守卫放行（而不是直接失败）。
            for (candidate in WORK_LONG_EDGE_LADDER) {
                val scale = candidate.toFloat() / maxOf(width, height).toFloat()
                val candidateWidth = (width * scale).toInt().coerceAtLeast(3)
                val candidateHeight = (height * scale).toInt().coerceAtLeast(3)
                val requiredBytes = candidateWidth.toLong() * candidateHeight.toLong() * 4L * 3L
                if (!job.guardMemory(requiredBytes)) continue
                workingLongEdge = candidate
                workingWidth = candidateWidth
                workingHeight = candidateHeight
                workingPixels = downscale(pixels, width, height, candidateWidth, candidateHeight)
                break
            }

            val source = workingPixels
            if (source == null) {
                // 连最低工作分辨率都放不下：明确降级为「不检测」，由调用方回退 GL 路径。
                onError(IllegalStateException("内存不足，边缘检测已降级跳过"))
                return@submit
            }

            // 分片执行：按横向条带处理，条带之间各向外扩 step 行作为 Sobel 上下文，
            // 避免分片接缝处出现假边缘（比整图驻留内存更省，同时保持结果连续）。
            val step = clamped.stepScale.toInt().coerceAtLeast(1)
            val magnitude = FloatArray(workingWidth * workingHeight)
            val batchSize = job.batchSizeFor(workingHeight.coerceAtLeast(1))
            var rowStart = 0
            while (rowStart < workingHeight) {
                val rowEnd = minOf(rowStart + batchSize, workingHeight)
                val bandTop = (rowStart - step).coerceAtLeast(0)
                val bandBottom = (rowEnd + step).coerceAtMost(workingHeight)
                val bandHeight = bandBottom - bandTop
                val bandPixels = IntArray(workingWidth * bandHeight)
                System.arraycopy(
                    source, bandTop * workingWidth,
                    bandPixels, 0,
                    workingWidth * bandHeight,
                )
                val bandMagnitude = ViewfinderCpuEdgeDetector.edgeMagnitude(
                    pixels = bandPixels,
                    width = workingWidth,
                    height = bandHeight,
                    stepScale = step,
                )
                if (bandMagnitude.isNotEmpty()) {
                    for (row in rowStart until rowEnd) {
                        val sourceOffset = (row - bandTop) * workingWidth
                        if (sourceOffset + workingWidth > bandMagnitude.size) break
                        System.arraycopy(
                            bandMagnitude, sourceOffset,
                            magnitude, row * workingWidth,
                            workingWidth,
                        )
                    }
                }
                job.checkYield()
                job.report(rowEnd, workingHeight)
                rowStart = rowEnd
            }

            val mask = ViewfinderCpuEdgeDetector.peakingMask(magnitude, clamped.resolveEdgeThreshold())
            onResult(
                EdgeDetectionResult(
                    width = workingWidth,
                    height = workingHeight,
                    magnitude = magnitude,
                    coverageRatio = ViewfinderCpuEdgeDetector.coverageRatio(mask),
                    workingLongEdge = workingLongEdge,
                ),
            )
        } catch (error: Exception) {
            PLog.w(TAG, "边缘检测失败", error)
            onError(error)
        }
    }

    /**
     * 最近邻降采样（保持像素真实值，不插值造值）。
     */
    fun downscale(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return IntArray(0)
        val output = IntArray(targetWidth * targetHeight)
        val usable = minOf(pixels.size.toLong(), width.toLong() * height.toLong()).toInt()
        for (y in 0 until targetHeight) {
            val sourceY = (y.toLong() * height / targetHeight).toInt().coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val sourceX = (x.toLong() * width / targetWidth).toInt().coerceIn(0, width - 1)
                val index = sourceY * width + sourceX
                output[y * targetWidth + x] = if (index < usable) pixels[index] else 0
            }
        }
        return output
    }
}
