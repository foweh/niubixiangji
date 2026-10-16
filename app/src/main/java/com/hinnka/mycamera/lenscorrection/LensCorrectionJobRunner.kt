package com.hinnka.mycamera.lenscorrection

import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max

/**
 * 镜头畸变校正的后台任务接入（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 与 `enhance/EnhanceJobRunner` 同一套性能契约（PATTERN_SPEC 第 1.5 节）：
 * - 重算全部走 [BgJobRunner] 后台低优先级队列（`BgTaskType.ENHANCE`，由补丁注入枚举）；
 * - 网格构建与逐行重映射都按行带分片，每片末尾回调 `job.checkYield()`；
 * - 分配大缓冲前 `job.guardMemory(bytes)` 守卫，失败即降工作分辨率而非报错；
 * - 工作分辨率有下限（[MIN_WORKING_SCALE]），不设尺寸硬上限。
 *
 * 注意：重映射是几何操作，工作分辨率降级会牺牲细节（与滤镜不同），
 * 因此这里的下限设得较高（0.4），优先保精度、必要时才降。
 */
object LensCorrectionJobRunner {

    const val JOB_NAME = "镜头畸变校正"

    /** 工作分辨率下限（几何操作不建议过度降采样）。 */
    const val MIN_WORKING_SCALE = 0.4f

    const val DEFAULT_BAND_HEIGHT = 64
    const val MIN_BAND_HEIGHT = 8
    const val MAX_BAND_HEIGHT = 2048

    private const val TAG = "LensCorrectionJob"
    private const val BYTES_PER_INT = 4L

    /** 行带高度 clamp（防误设 0/负数/超大值）。 */
    fun clampBandHeight(value: Int): Int = value.coerceIn(MIN_BAND_HEIGHT, MAX_BAND_HEIGHT)

    /**
     * 提交一次畸变校正。
     *
     * @param source 输入位图（不被修改；内部按需降采样副本）。
     * @param config 该相机维度对应的畸变配置（内部 clamp）。
     * @param onFinish 结束回调（后台线程）；成功给校正后的 Bitmap，取消/失败给 null。
     * @return 已入队的 [BgJob]，调用方可 `pause()` / `resume()` / `cancel()`。
     */
    fun submitCorrection(
        source: Bitmap,
        config: LensDistortionConfig,
        jobName: String = JOB_NAME,
        bandHeight: Int = DEFAULT_BAND_HEIGHT,
        priority: Int = BgJobPriority.LOW,
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob {
        val clamped = LensDistortionConfig.clamp(config)
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
            if (!clamped.isActive()) {
                PLog.i(TAG, "lens correction inactive, return original")
                output = source
                return@submit
            }

            val budget = BgTaskType.ENHANCE.defaultMaxHeapBytes
            var scale = 1f
            // 内存守卫：网格 2 个 Float 数组 + 入/出像素各一份 int
            while (scale > MIN_WORKING_SCALE) {
                val (workWidth, workHeight) = LensCorrectionBitmapAdapter.scaledDimensions(
                    sourceWidth, sourceHeight, scale
                )
                val bytes = gridBytes(workWidth, workHeight) +
                    2L * workWidth.toLong() * workHeight.toLong() * BYTES_PER_INT
                if (job.guardMemory(bytes)) break
                val next = max(MIN_WORKING_SCALE, scale / 2f)
                PLog.w(
                    TAG,
                    "guardMemory reject at scale=$scale (need=$bytes budget=$budget), downgrade to $next",
                )
                if (next >= scale) break
                scale = next
            }

            val (workWidth, workHeight) = LensCorrectionBitmapAdapter.scaledDimensions(
                sourceWidth, sourceHeight, scale
            )
            val working = LensCorrectionBitmapAdapter.scaleTo(source, scale)
            val pixels = LensCorrectionBitmapAdapter.readPixels(working)
            PLog.i(
                TAG,
                "correction start ${sourceWidth}x$sourceHeight -> ${workWidth}x$workHeight " +
                    "scale=$scale dim=${clamped.dimension} k1=${clamped.effectiveK1()} " +
                    "k2=${clamped.effectiveK2()} policy=${clamped.edgePolicy}",
            )

            // 单位 = 一个行带；总数 = 网格构建 + 重映射两趟
            val bandsPerPass = ((workHeight + band - 1) / band).coerceAtLeast(1)
            val totalUnits = bandsPerPass * 2
            var doneUnits = 0
            val processed = LensCorrectionCore.remapWithConfig(
                source = pixels,
                srcWidth = workWidth,
                srcHeight = workHeight,
                config = clamped,
                bandHeight = band,
                onChunk = {
                    doneUnits++
                    job.report(doneUnits.coerceAtMost(totalUnits), totalUnits)
                    job.checkYield()
                },
            )
            val result = LensCorrectionBitmapAdapter.toBitmap(processed, workWidth, workHeight)
            output = result
            job.report(totalUnits, totalUnits)
            PLog.i(TAG, "correction done ${result.width}x${result.height}")
        }
    }

    /** 重映射网格的内存占用（两个 Float 数组，4 字节/元素）。 */
    internal fun gridBytes(width: Int, height: Int): Long =
        2L * width.toLong() * height.toLong() * BYTES_PER_INT
}
