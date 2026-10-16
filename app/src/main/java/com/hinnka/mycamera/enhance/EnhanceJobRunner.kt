package com.hinnka.mycamera.enhance

import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 经典后处理的后台任务接入（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 遵循 PATTERN_SPEC 第 1.5 节性能硬约束：
 * - 重算全部走 [BgJobRunner] 后台低优先级队列（`BgTaskType.ENHANCE`，由补丁注入枚举）；
 * - 每个分片末尾经 `onChunk` 回调 `job.checkYield()`（响应暂停/取消 + CPU 节流）；
 * - 分配大缓冲前用 `job.guardMemory(bytes)` 守卫，失败即**降工作分辨率**而非报错；
 * - 工作分辨率有下限（[EnhancePipeline.MIN_WORKING_SCALE]），不设图像尺寸硬上限。
 *
 * 注意：`BgTaskType.ENHANCE` 由交付补丁 `delivery/patches/enhance_patch.md` 注入；
 * 若集成方不想新增枚举值，把下面的 `BgTaskType.ENHANCE` 换成 `BgTaskType.GENERIC` 即可。
 */
object EnhanceJobRunner {

    const val JOB_NAME = "经典后处理"
    const val DEFAULT_BAND_HEIGHT = 64
    const val MIN_BAND_HEIGHT = 8
    const val MAX_BAND_HEIGHT = 2048

    private const val TAG = "EnhanceJobRunner"

    /** 一个 ARGB 整型像素 4 字节。 */
    private const val BYTES_PER_INT = 4L

    /** 行带高度 clamp（防误设 0 / 负数 / 超大值导致行为异常）。 */
    fun clampBandHeight(value: Int): Int = value.coerceIn(MIN_BAND_HEIGHT, MAX_BAND_HEIGHT)

    /**
     * 提交一次后处理任务。
     *
     * @param source 输入位图（不被修改；内部按需降采样副本）。
     * @param config 后处理配置（内部会 clamp，越界项记录日志）。
     * @param jobName 任务显示名（进后台进度 chip）。
     * @param bandHeight 行带高度，默认 [DEFAULT_BAND_HEIGHT]。
     * @param priority 任务优先级，默认低优先级不抢前台。
     * @param onFinish 结束回调（后台线程）；成功给处理后的 Bitmap，取消/失败给 null。
     * @return 已入队的 [BgJob]，调用方可 `pause()` / `resume()` / `cancel()`。
     */
    fun submitEnhance(
        source: Bitmap,
        config: EnhanceConfig,
        jobName: String = JOB_NAME,
        bandHeight: Int = DEFAULT_BAND_HEIGHT,
        priority: Int = BgJobPriority.LOW,
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob {
        val (clamped, reports) = config.clampWithReport()
        if (reports.isNotEmpty()) {
            PLog.i(TAG, "enhance config clamped: ${reports.joinToString()}")
        }
        val band = clampBandHeight(bandHeight)
        var output: Bitmap? = null

        return BgJobRunner.submit(
            name = jobName,
            chunkSize = 1,
            type = BgTaskType.ENHANCE,
            priority = priority,
            onFinish = { onFinish?.invoke(output) },
        ) {
            val job = this // 接收者即本次 BgJob，下面按名字使用
            val sourceWidth = source.width
            val sourceHeight = source.height
            if (sourceWidth <= 0 || sourceHeight <= 0) return@submit

            val budget = BgTaskType.ENHANCE.defaultMaxHeapBytes
            var scale = clamped.workingScale
            // 内存守卫循环：整数像素缓冲（入/出各一份）+ 浮点平面峰值 都放不下就降档
            while (scale > EnhancePipeline.MIN_WORKING_SCALE) {
                val (workWidth, workHeight) = EnhanceBitmapAdapter.scaledDimensions(
                    sourceWidth, sourceHeight, scale
                )
                // 入参像素 + 输出像素各一份 int（4 字节/像素）
                val pixelBytes = 2L * workWidth.toLong() * workHeight.toLong() * BYTES_PER_INT
                val planeBytes = EnhancePipeline.estimateBytes(workWidth, workHeight, 1f)
                if (job.guardMemory(pixelBytes + planeBytes)) break
                val next = max(EnhancePipeline.MIN_WORKING_SCALE, scale / 2f)
                PLog.w(
                    TAG,
                    "guardMemory reject at scale=$scale " +
                        "(need=${pixelBytes + planeBytes} budget=$budget), downgrade to $next",
                )
                if (next >= scale) break
                scale = next
            }

            val (workWidth, workHeight) = EnhanceBitmapAdapter.scaledDimensions(
                sourceWidth, sourceHeight, scale
            )
            PLog.i(TAG, "enhance start ${sourceWidth}x$sourceHeight -> ${workWidth}x$workHeight scale=$scale")

            val working = EnhanceBitmapAdapter.scaleTo(source, scale)
            val pixels = EnhanceBitmapAdapter.readPixels(working)
            val stageCount = clamped.stages().size.coerceAtLeast(1)
            val processed = EnhancePipeline.process(
                pixels = pixels,
                width = workWidth,
                height = workHeight,
                config = clamped,
                bandHeight = band,
                onStage = { stage, progress ->
                    // 阶段进度映射到 0..1（每阶段末尾还会由 onChunk 让出一次）
                    val fraction = (progress.coerceIn(0f, 1f))
                    job.report((fraction * 100f).roundToInt(), 100)
                    PLog.d(TAG, "stage=$stage progress=$fraction/${stageCount}")
                },
                onChunk = { job.checkYield() },
            )
            val result = EnhanceBitmapAdapter.toBitmap(processed, workWidth, workHeight)
            output = result
            job.report(100, 100)
            PLog.i(TAG, "enhance done ${result.width}x${result.height}")
        }
    }
}
