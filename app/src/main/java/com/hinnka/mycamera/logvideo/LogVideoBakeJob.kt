package com.hinnka.mycamera.logvideo

import android.content.Context
import android.net.Uri
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.data.CustomImportManager
import com.hinnka.mycamera.lut.LutDownloadManager
import com.hinnka.mycamera.raw.ColorSpace
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * Log 视频重活调度（Log 视频 20 项 · 第 1 / 4 项）：曲线查表烘焙 + Log→Rec709 3D LUT 程序化烘焙。
 *
 * 全部走工程既有 `BgJobRunner`（**不新增线程/执行器**，PATTERN_SPEC §9 复用优先）：
 *  - 任务类型 `BgTaskType.AI_LUT`（6ms 节流 / 16 分片 / 256MB 预算）——够用，**无需新增枚举**；
 *  - 优先级 `BgJobPriority.LOW`：绝不与前台预览/录制抢 CPU；
 *  - 每个分片末尾 `checkYield()`（响应暂停/取消 + 让时间片）；
 *  - 内存守卫 `guardMemory(...)` 超预算时**降级而非失败**（LUT 尺寸折半，最低 [LogToRec709LutBaker.MIN_SIZE]）。
 *
 * 产物落盘与入库复用工程既有链路：`.cube` → `CustomImportManager.importLut(...)`
 * → 内部 `.plut` v3 → `LutManager` 注册表 → LUT 面板可选可套用。
 */
object LogVideoBakeJob {

    private const val TAG = "LogVideoBakeJob"

    /** `BgJobRunner` ↔ [LogFrameJobSink] 适配（纯 Kotlin 核心类不感知 Android）。 */
    private class BgJobSinkAdapter(private val job: BgJob) : LogFrameJobSink {
        override val isCancelled: Boolean get() = job.isCancelled
        override fun report(done: Int, total: Int) = job.report(done, total)
        override fun checkYield() = job.checkYield()
    }

    /**
     * 烘焙并发布 Log 曲线查表（第 1 项）。产物进 [ActiveLogCurveTable] 供 GL/CPU 共用。
     *
     * @param onFinish 回调在主线程外（后台线程），入参为发布结果或 null（被取消/内存不足）。
     */
    fun submitBakeCurveTable(
        recipe: LogCurveRecipe,
        onFinish: ((LogCurveTable?) -> Unit)? = null,
    ): BgJob {
        val safe = recipe.clamped()
        return BgJobRunner.submit(
            name = "log-curve-${safe.preset.name.lowercase()}",
            chunkSize = BgTaskType.AI_LUT.defaultMaxFrameBatch,
            type = BgTaskType.AI_LUT,
            priority = BgJobPriority.LOW,
            onFinish = { job ->
                if (job.isCancelled) {
                    PLog.w(TAG, "曲线烘焙被取消：${safe.describe()}")
                    onFinish?.invoke(null)
                }
            },
        ) {
            val requiredBytes = safe.tableSize * 4L
            if (!guardMemory(requiredBytes)) {
                val fallbackSize = (safe.tableSize / 2).coerceAtLeast(LogCurveRecipe.TABLE_SIZE_MIN)
                PLog.w(
                    TAG,
                    "内存守卫拒绝曲线表($requiredBytes B)，降级 tableSize ${safe.tableSize} → $fallbackSize",
                )
                val degraded = LogCurveTable.build(safe.copy(tableSize = fallbackSize)) { done, total ->
                    report(done, total)
                }
                ActiveLogCurveTable.publish(degraded)
                onFinish?.invoke(degraded)
                return@submit
            }
            val table = LogCurveTable.build(safe) { done, total ->
                report(done, total)
                checkYield()
            }
            val summary = ActiveLogCurveTable.publish(table)
            PLog.i(
                TAG,
                "曲线表就绪 $summary 单调=${table.isMonotonicNonDecreasing()} " +
                    "maxΔ=${table.maxAdjacentDelta()} ${safe.describe()}",
            )
            onFinish?.invoke(table)
        }
    }

    /**
     * 运行时程序化烘焙 Log→Rec709 3D LUT 并入库（第 4 项「新手一键套用」）。
     *
     * @param config 总配置（读 [LogVideoConfig.bakedRestoreLutSize] / [LogVideoConfig.autoApplyBakedRestore]）。
     * @param onFinish 返回入库后的 lutId（可直接作为 [LogVideoConfig.restoreLutId]）。
     */
    fun submitBakeRestoreLut(
        context: Context,
        config: LogVideoConfig,
        onFinish: ((String?) -> Unit)? = null,
    ): BgJob {
        val safe = config.clamped()
        val recipe = safe.curve
        return BgJobRunner.submit(
            name = "log-lut-${recipe.preset.name.lowercase()}",
            chunkSize = BgTaskType.AI_LUT.defaultMaxFrameBatch,
            type = BgTaskType.AI_LUT,
            priority = BgJobPriority.LOW,
            onFinish = { job ->
                if (job.isCancelled) {
                    PLog.w(TAG, "Log→Rec709 LUT 烘焙被取消：${recipe.preset}")
                    onFinish?.invoke(null)
                }
            },
        ) {
            val curve = recipe.preset.nativeCurve
            var dimension = safe.bakedRestoreLutSize
            // 内存守卫：3D LUT 数据 = size³ × 3 × 4B（33³≈0.43MB，65³≈3.3MB）
            while (dimension > LogToRec709LutBaker.MIN_SIZE &&
                !guardMemory(lutBytes(dimension))
            ) {
                val next = dimension / 2
                PLog.w(TAG, "内存守卫拒绝 LUT size=$dimension(${lutBytes(dimension)} B)，降级 → $next")
                dimension = next.coerceAtLeast(LogToRec709LutBaker.MIN_SIZE)
            }
            if (!guardMemory(lutBytes(dimension))) {
                val bytes = lutBytes(dimension)
                PLog.w(TAG, "内存守卫最终拒绝烘焙：需要 $bytes B（预算内无法降级）")
                onFinish?.invoke(null)
                return@submit
            }

            val lut = LogToRec709LutBaker.bake(
                recipe = recipe,
                size = dimension,
                oetf = LogDisplayOetf.REC709,
                // 入库时按档位原生曲线做轴预补偿，抵消 CustomImportManager 的重采样
                axisCompensation = curve,
                onProgress = { done, total ->
                    report(done, total)
                    checkYield()
                },
            )

            val description = LogToRec709LutBaker.describe(
                recipe = recipe,
                size = dimension,
                axisCompensation = curve,
            )
            val title = "Log709_${recipe.preset.name}_${dimension}"
            val cubeFile = File(context.cacheDir, "$title.cube")
            cubeFile.writeText(
                LogToRec709LutBaker.writeCubeText(
                    lut = lut,
                    size = dimension,
                    title = title,
                    comments = listOf(
                        "StormCam LogVideo 运行时烘焙 Log→Rec.709 LUT",
                        description,
                    ),
                ),
            )
            checkYield()

            val lutId = runCatching {
                CustomImportManager(context).importLut(
                    uri = Uri.fromFile(cubeFile),
                    displayName = title,
                    category = LOG_LUT_CATEGORY,
                    // 轴预补偿用 curve（原生档位曲线）；标签必须与档位一致才能在 Log 视频模式下被筛出
                    colorSpace = recipe.preset.gamut.projectColorSpace ?: ColorSpace.SRGB,
                    curve = curve ?: com.hinnka.mycamera.color.TransferCurve.SRGB,
                )
            }.onFailure { PLog.e(TAG, "Log→Rec709 LUT 入库失败", it) }.getOrNull()

            cubeFile.delete()

            if (lutId != null) {
                if (safe.autoApplyBakedRestore) {
                    LogVideoConfigStore.update { it.copy(restoreLutId = lutId) }
                    PLog.i(TAG, "已自动套用烘焙 LUT：$lutId($title)")
                } else {
                    PLog.i(TAG, "烘焙 LUT 已入库（未自动套用）：$lutId($title)")
                }
            }
            PLog.i(TAG, "Log→Rec709 烘焙完成 lutId=$lutId size=$dimension :: $description")
            onFinish?.invoke(lutId)
        }
    }

    /** 下载并安装一个 Log LUT 包（复用 `LutDownloadManager`，第 4 项「可下载 Log→Rec709 转换 LUT 包」）。 */
    suspend fun installPack(context: Context, pack: LogLutPack): String? {
        if (!pack.directlyDownloadable || pack.fileUrl.isBlank()) {
            PLog.w(
                TAG,
                "包 ${pack.id} 无直链（许可证=${pack.license}），请在源页面获取：${pack.sourceUrl}",
            )
            return null
        }
        val downloadable = LutDownloadManager.DownloadablePack(
            id = pack.id,
            nameEn = pack.nameEn,
            nameZh = pack.nameZh,
            sourceUrl = pack.sourceUrl,
            license = pack.license,
            starNote = pack.star,
            contents = pack.contents,
            fileUrl = pack.fileUrl,
            format = pack.format,
        )
        return LutDownloadManager(context).downloadAndInstall(downloadable)
    }

    private const val LOG_LUT_CATEGORY = "Log"

    private fun lutBytes(size: Int): Long = size.toLong() * size * size * 3L * 4L
}
