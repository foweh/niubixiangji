package com.hinnka.mycamera.focusstack

import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog

/**
 * 微距焦点堆叠的后台任务接入（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 遵循 PATTERN_SPEC §1.5 性能硬约束：
 * - 重算全部走 [BgJobRunner] 后台低优先级队列（`BgTaskType.FOCUS_STACK`，由补丁注入枚举）；
 * - 每个分片末尾 `job.checkYield()`（在 [FocusStackProcessor] 内逐帧调用，粒度细于分片，符合契约）；
 * - 分配大缓冲前 `job.guardMemory(bytes)`，失败即**降工作分辨率**而非报错；
 * - **帧数不做总量上限**：循环数 = 用户配置帧数。
 *
 * 枚举说明：本模块使用新增的 [BgTaskType.FOCUS_STACK]（补丁
 * `delivery/patches/focusstack_patch.md` 的 ENUM 节给出完整条目）。
 * 若集成方不愿新增枚举值，把下面的 `BgTaskType.FOCUS_STACK` 换成 `BgTaskType.STACK` 即可
 * （堆栈类任务语义相近，预算 512MB 一致）。
 */
object FocusStackJobRunner {

    const val JOB_NAME = "微距焦点堆叠"

    /** 分片单元数（帧/分片）。处理器内部逐帧 `checkYield`，本值决定节流粒度上限。 */
    private const val CHUNK_SIZE = 4

    private const val TAG = "FocusStackJobRunner"

    /**
     * 提交一次焦点堆叠任务（帧源形态）。
     *
     * @param source 帧源（按需借出全分辨率帧，常驻内存与帧数无关）。
     * @param config 堆叠配置（内部会 clamp，越界项记录日志）。
     * @param jobName 任务显示名（进后台进度 chip）。
     * @param priority 任务优先级，默认低优先级不抢前台。
     * @param onFinish 结束回调（后台线程）；成功给融合后的 ARGB 像素，取消/失败给 null。
     * @return 已入队的 [BgJob]，调用方可 `pause()` / `resume()` / `cancel()`。
     */
    fun submit(
        source: FocusStackFrameSource,
        config: FocusStackConfig,
        jobName: String = JOB_NAME,
        priority: Int = BgJobPriority.LOW,
        onFinish: ((IntArray?) -> Unit)? = null,
    ): BgJob {
        var output: IntArray? = null
        return BgJobRunner.submit(
            name = jobName,
            chunkSize = CHUNK_SIZE,
            type = BgTaskType.FOCUS_STACK,
            priority = priority,
            onFinish = { onFinish?.invoke(output) },
        ) {
            val job = this
            output = FocusStackProcessor.process(source = source, config = config, job = job)
            job.report(100, 100)
            PLog.i(TAG, "focus stack job done, output=${output?.size ?: 0} px")
        }
    }

    /**
     * 便捷入口：位图列表 → 全焦 Bitmap。
     *
     * @param bitmaps 参与堆叠的帧（须同尺寸同方向；由调用方保证）。
     * @param onFinish 结束回调；成功给全焦位图，取消/失败给 null。
     */
    fun submitBitmaps(
        bitmaps: List<Bitmap>,
        config: FocusStackConfig,
        jobName: String = JOB_NAME,
        priority: Int = BgJobPriority.LOW,
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob {
        if (bitmaps.isEmpty()) {
            PLog.w(TAG, "submitBitmaps with empty frames")
        }
        val source = if (bitmaps.isEmpty()) null else FocusStackBitmapAdapter.bitmapSource(bitmaps)
        var bitmap: Bitmap? = null
        return BgJobRunner.submit(
            name = jobName,
            chunkSize = CHUNK_SIZE,
            type = BgTaskType.FOCUS_STACK,
            priority = priority,
            onFinish = { onFinish?.invoke(bitmap) },
        ) {
            val job = this
            if (source == null) {
                job.report(100, 100)
                return@submit
            }
            val pixels = FocusStackProcessor.process(source = source, config = config, job = job)
            if (pixels != null) {
                bitmap = FocusStackBitmapAdapter.toBitmap(pixels, source.width, source.height)
            }
            job.report(100, 100)
            PLog.i(TAG, "focus stack bitmaps job done, bitmap=${bitmap?.width}x${bitmap?.height}")
        }
    }
}
