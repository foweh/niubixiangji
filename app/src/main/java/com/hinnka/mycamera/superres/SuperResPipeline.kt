package com.hinnka.mycamera.superres

import android.content.Context
import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 后台管线：把超分/风格迁移/修复三种任务**全部**丢进 `computational/BgJobRunner` 串行队列。
 *
 * 性能硬约束对齐（PATTERN_SPEC 第 1.5 / 第 4 节）：
 *  - 每个 tile = 一个分片单元；tile 结束回调里调 `job.checkYield()`
 *    （= 响应暂停/取消 + 按 `BgTaskType.SUPER_RES` 的节流间隔做占空比限制 + 让出时间片）；
 *  - 分配累加缓冲前用 `job.guardMemory(bytes)` 守卫，不通过则降 tile/降工作分辨率
 *    （[SuperResConfig.degradeForMemoryPressure]）而不是失败；
 *  - 批大小用 `job.batchSizeFor(n)` 决定，不硬编码；
 *  - **不设总工作量上限**：tile 数量由用户配置的 tile 尺寸与图像尺寸决定，
 *    内存不够靠降级与分片解决，而不是限制用户能处理多大的图。
 *
 * 结果通过 [Result] 回调：`output == null` 表示已回退原图（[Result.fallbackReason] 说明原因），
 * 任何异常（含取消）都收敛为「回退原图」，绝不把异常抛给 UI。
 */
object SuperResPipeline {

    private const val TAG = "SuperResPipeline"

    /** 任务结果：`output` 为空表示已回退原图（[fallbackReason] 说明原因）。 */
    data class Result(
        val decision: SuperResDecision,
        val output: Bitmap?,
        val fallbackReason: String = "",
    )

    /** 任务种类（与 [SuperResAction] 一一对应，供调用方选择入口）。 */
    enum class TaskKind { SUPER_RES, STYLE_TRANSFER, INPAINTING }

    /** 提交超分任务。 */
    fun submitSuperRes(
        source: Bitmap,
        config: SuperResConfig,
        modelFile: File?,
        installedIds: Set<String> = emptySet(),
        runtimeAvailable: Boolean = true,
        priority: Int = BgJobPriority.LOW,
        onResult: (Result) -> Unit,
    ): BgJob = submit(
        kind = TaskKind.SUPER_RES,
        source = source,
        mask = null,
        config = config,
        modelFile = modelFile,
        installedIds = installedIds,
        runtimeAvailable = runtimeAvailable,
        priority = priority,
        onResult = onResult,
    )

    /** 提交风格迁移任务。 */
    fun submitStyleTransfer(
        source: Bitmap,
        config: SuperResConfig,
        modelFile: File?,
        installedIds: Set<String> = emptySet(),
        runtimeAvailable: Boolean = true,
        priority: Int = BgJobPriority.LOW,
        onResult: (Result) -> Unit,
    ): BgJob = submit(
        kind = TaskKind.STYLE_TRANSFER,
        source = source,
        mask = null,
        config = config,
        modelFile = modelFile,
        installedIds = installedIds,
        runtimeAvailable = runtimeAvailable,
        priority = priority,
        onResult = onResult,
    )

    /** 提交图像修复任务（需掩膜图）。 */
    fun submitInpainting(
        source: Bitmap,
        mask: Bitmap?,
        config: SuperResConfig,
        modelFile: File?,
        installedIds: Set<String> = emptySet(),
        runtimeAvailable: Boolean = true,
        priority: Int = BgJobPriority.LOW,
        onResult: (Result) -> Unit,
    ): BgJob = submit(
        kind = TaskKind.INPAINTING,
        source = source,
        mask = mask,
        config = config,
        modelFile = modelFile,
        installedIds = installedIds,
        runtimeAvailable = runtimeAvailable,
        priority = priority,
        onResult = onResult,
    )

    private fun submit(
        kind: TaskKind,
        source: Bitmap,
        mask: Bitmap?,
        config: SuperResConfig,
        modelFile: File?,
        installedIds: Set<String>,
        runtimeAvailable: Boolean,
        priority: Int,
        onResult: (Result) -> Unit,
    ): BgJob {
        val clamped = SuperResConfig.clamp(config)
        val decision = decide(kind, clamped, installedIds, runtimeAvailable, source, mask)
        val jobName = jobNameFor(kind)
        val batchSize = clamped.maxTilesPerChunk.coerceAtLeast(1)

        return BgJobRunner.submit(
            name = jobName,
            chunkSize = batchSize,
            type = BgTaskType.SUPER_RES,
            priority = priority,
        ) {
            val effectiveBatch = batchSizeFor(batchSize).coerceAtLeast(1)
            PLog.i(TAG, "$jobName start batch=$effectiveBatch degraded=${decision.degradedConfig != null}")
            report(0, 1)
            try {
                val result = when {
                    // 1) 能力不足/被关闭/输入非法：不占算力，直接回退原图。
                    !decision.runsInference ->
                        Result(decision, null, decision.reason)

                    // 2) 模型文件缺失（理论上已被 decision 拦住，双保险）：回退并提示下载。
                    modelFile == null || !modelFile.isFile ->
                        Result(decision, null, "model_missing")

                    else -> runTask(this, kind, source, mask, modelFile, decision, clamped)
                }
                onResult(result)
            } catch (e: Throwable) {
                // 含 BgJobCancelledException：取消/异常统一回退原图。
                PLog.w(TAG, "$jobName failed: ${e::class.java.simpleName} ${e.message}")
                onResult(Result(decision, null, e::class.java.simpleName))
            }
        }
    }

    private fun runTask(
        job: BgJob,
        kind: TaskKind,
        source: Bitmap,
        mask: Bitmap?,
        modelFile: File,
        decision: SuperResDecision,
        clamped: SuperResConfig,
    ): Result {
        val effective = SuperResConfig.clamp(decision.degradedConfig ?: clamped)
        val output: Bitmap? = when (kind) {
            TaskKind.SUPER_RES -> SuperResTileRenderer(
                config = effective,
                engine = openEngine(effective, modelFile),
                scale = effective.scale,
                memoryGuard = { bytes -> job.guardMemory(bytes) },
            ).render(source) { done, total ->
                job.report(done, total)
                // 每个 tile（分片）末尾必调：暂停/取消/节流/让出时间片。
                job.checkYield()
            }

            TaskKind.STYLE_TRANSFER -> SuperResStyleTransfer(effective).run(source, modelFile) { _, _ ->
                job.checkYield()
            }

            TaskKind.INPAINTING -> {
                if (mask == null) {
                    null
                } else {
                    SuperResInpainting(effective).run(source, mask, modelFile) { _, _ ->
                        job.checkYield()
                    }
                }
            }
        }
        return if (output == null) {
            PLog.w(TAG, "${jobNameFor(kind)} produced no output, fallback to original")
            Result(decision, null, "render_failed_or_oom")
        } else {
            Result(decision, output, "")
        }
    }

    /** 打开会话；失败返回未就绪的引擎（渲染时每 tile 失败→原图上采样顶替，整图仍可出）。 */
    private fun openEngine(cfg: SuperResConfig, modelFile: File?): SuperResInference {
        val engine = SuperResOnnxEngine(cfg)
        if (modelFile != null && engine.isRuntimeAvailable()) {
            engine.open(modelFile)
        }
        return engine
    }

    private fun decide(
        kind: TaskKind,
        config: SuperResConfig,
        installedIds: Set<String>,
        runtimeAvailable: Boolean,
        source: Bitmap,
        mask: Bitmap?,
    ): SuperResDecision {
        val w = source.width
        val h = source.height
        return when (kind) {
            TaskKind.SUPER_RES -> SuperResAvailability.decideSuperRes(
                config = config,
                installedModelIds = installedIds,
                runtimeAvailable = runtimeAvailable,
                inputWidth = w,
                inputHeight = h,
            )

            TaskKind.STYLE_TRANSFER -> SuperResAvailability.decideStyleTransfer(
                config = config,
                installedModelIds = installedIds,
                runtimeAvailable = runtimeAvailable,
                inputWidth = w,
                inputHeight = h,
            )

            TaskKind.INPAINTING -> {
                val maskW = mask?.width ?: 0
                val maskH = mask?.height ?: 0
                if (mask == null || maskW <= 0 || maskH <= 0) {
                    SuperResDecision(SuperResAction.INVALID_INPUT, reason = "mask_missing")
                } else {
                    SuperResAvailability.decideInpainting(
                        config = config,
                        installedModelIds = installedIds,
                        runtimeAvailable = runtimeAvailable,
                        inputWidth = w,
                        inputHeight = h,
                    )
                }
            }
        }
    }

    private fun jobNameFor(kind: TaskKind): String = when (kind) {
        TaskKind.SUPER_RES -> "端侧超分"
        TaskKind.STYLE_TRANSFER -> "风格迁移"
        TaskKind.INPAINTING -> "图像修复"
    }

    /**
     * 便捷入口：一次调用完成「探测运行时 → 选模型 → 排队」。
     * UI 侧推荐用它，避免自己拼装 [SuperResRuntime] 的各部分。
     */
    fun submitAuto(
        context: Context,
        source: Bitmap,
        mask: Bitmap?,
        kind: TaskKind,
        config: SuperResConfig,
        priority: Int = BgJobPriority.LOW,
        onResult: (Result) -> Unit,
    ): BgJob {
        val runtime = SuperResRuntime(context)
        val clamped = SuperResConfig.clamp(config)
        val catalog = runtime.catalog()
        val installed = runtime.installedIds(catalog)
        val model = runtime.modelFileFor(kind, clamped, installed)
        val runtimeOk = runtime.runtimeAvailable()
        return when (kind) {
            TaskKind.SUPER_RES -> submitSuperRes(
                source, clamped, model, installed, runtimeOk, priority, onResult,
            )

            TaskKind.STYLE_TRANSFER -> submitStyleTransfer(
                source, clamped, model, installed, runtimeOk, priority, onResult,
            )

            TaskKind.INPAINTING -> submitInpainting(
                source, mask, clamped, model, installed, runtimeOk, priority, onResult,
            )
        }
    }
}
