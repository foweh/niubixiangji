package com.hinnka.mycamera.superres

import android.content.Context
import android.graphics.Bitmap
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 模块门面：UI 与业务侧只需与它打交道，完成「读配置 → 判可用性 → 灰化/排队 → 回退原图」。
 *
 * 典型用法（UI 侧）：
 * ```
 * val runtime = SuperResRuntime(context)
 * val gating = runtime.gate(SuperResPipeline.TaskKind.SUPER_RES, config)   // 决定入口是否灰化
 * if (gating.enabled) {
 *     runtime.submit(context, bitmap, mask = null, kind, config) { result ->
 *         val out = result.output ?: bitmap   // 未就绪/失败一律回退原图
 *     }
 * }
 * ```
 *
 * 三条进入路径的能力判定完全一致（都走 [SuperResAvailability]），因此
 * 「能力不足 → 灰化 + 回退原图」这条规则在全模块只有一份实现。
 */
class SuperResRuntime(private val context: Context) {

    /** 入口门控结果，供 UI 决定是否灰化 + 展示什么提示。 */
    data class Gate(
        val enabled: Boolean,
        val action: SuperResAction,
        val hint: String = "",
        val modelId: String? = null,
    ) {
        companion object {
            fun of(decision: SuperResDecision): Gate = Gate(
                enabled = decision.runsInference,
                action = decision.action,
                hint = decision.reason,
                modelId = decision.modelId,
            )
        }
    }

    private val downloads = SuperResModelDownloadManager(context)
    private val configStore = SuperResConfigStore(context)

    /** 运行时能力（ONNX Runtime 是否可加载）；结果缓存，避免每帧探测。 */
    @Volatile
    private var runtimeProbe: Boolean? = null

    fun runtimeAvailable(): Boolean {
        runtimeProbe?.let { return it }
        val ok = SuperResOnnxEngine(SuperResConfig()).let { engine ->
            val available = engine.isRuntimeAvailable()
            engine.close()
            available
        }
        runtimeProbe = ok
        if (!ok) PLog.w(TAG, "ONNX Runtime unavailable: superres module falls back to original")
        return ok
    }

    /** 最近一次加载的目录（可含远端覆盖）；未加载过时回落编译期内置目录。 */
    @Volatile
    private var cachedCatalog: List<SuperResModelInfo>? = null

    /** 同步访问的目录（供 gate()/modelFileFor 这类非挂起路径使用）。 */
    fun catalog(): List<SuperResModelInfo> = cachedCatalog ?: SuperResModelCatalog.models

    fun installedIds(catalog: List<SuperResModelInfo> = catalog()): Set<String> =
        downloads.installedIds(catalog)

    /** 当前机型的模型是否已安装（入口灰化判定用）。 */
    fun isInstalled(id: String): Boolean = downloads.isInstalled(id)

    fun modelFile(info: SuperResModelInfo): File = downloads.modelFile(info)

    /** 按任务种类与配置挑选可用模型文件；没有可用模型时返回 null。 */
    fun modelFileFor(
        kind: SuperResPipeline.TaskKind,
        config: SuperResConfig,
        installedIds: Set<String> = installedIds(),
    ): File? {
        val info = modelInfoFor(kind, config, installedIds) ?: return null
        val file = downloads.modelFile(info)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /** 与 [modelFileFor] 配套：返回选中的模型元信息（UI 展示名称/来源/许可）。 */
    fun modelInfoFor(
        kind: SuperResPipeline.TaskKind,
        config: SuperResConfig,
        installedIds: Set<String> = installedIds(),
    ): SuperResModelInfo? {
        val clamped = SuperResConfig.clamp(config)
        return when (kind) {
            SuperResPipeline.TaskKind.SUPER_RES -> SuperResModelCatalog.selectInstalled(
                kind = SuperResModelKind.SUPER_RESOLUTION,
                scale = clamped.scale,
                installedIds = installedIds,
            )

            SuperResPipeline.TaskKind.STYLE_TRANSFER -> SuperResModelCatalog.selectInstalled(
                kind = SuperResModelKind.STYLE_TRANSFER,
                scale = 1,
                installedIds = installedIds,
                preferredId = clamped.styleModelId,
            )

            SuperResPipeline.TaskKind.INPAINTING -> SuperResModelCatalog.selectInstalled(
                kind = SuperResModelKind.INPAINTING,
                scale = 1,
                installedIds = installedIds,
            )
        }
    }

    /**
     * 入口门控：能力不足时 [Gate.enabled] = false，UI 应把入口灰化并用 [Gate.hint] 提示，
     * 结果统一回退原图。
     */
    fun gate(
        kind: SuperResPipeline.TaskKind,
        config: SuperResConfig,
        inputWidth: Int = 1,
        inputHeight: Int = 1,
    ): Gate {
        val clamped = SuperResConfig.clamp(config)
        val installed = installedIds()
        val runtimeOk = runtimeAvailable()
        val decision = when (kind) {
            SuperResPipeline.TaskKind.SUPER_RES -> SuperResAvailability.decideSuperRes(
                clamped, installed, runtimeOk, inputWidth, inputHeight,
            )

            SuperResPipeline.TaskKind.STYLE_TRANSFER -> SuperResAvailability.decideStyleTransfer(
                clamped, installed, runtimeOk, inputWidth, inputHeight,
            )

            SuperResPipeline.TaskKind.INPAINTING -> SuperResAvailability.decideInpainting(
                clamped, installed, runtimeOk, inputWidth, inputHeight,
            )
        }
        return Gate.of(decision)
    }

    /** 提交任务（内部走 [SuperResPipeline.submitAuto]）。 */
    fun submit(
        source: Bitmap,
        mask: Bitmap?,
        kind: SuperResPipeline.TaskKind,
        config: SuperResConfig,
        priority: Int = com.hinnka.mycamera.computational.BgJobPriority.LOW,
        onResult: (SuperResPipeline.Result) -> Unit,
    ) = SuperResPipeline.submitAuto(context, source, mask, kind, config, priority, onResult)

    // ---- 下载 / 配置透传（UI 直接用） ----

    suspend fun loadCatalog(forceRefresh: Boolean = false): List<SuperResModelInfo> {
        val loaded = downloads.loadCatalog(forceRefresh)
        cachedCatalog = loaded
        return loaded
    }

    suspend fun downloadAndInstall(
        info: SuperResModelInfo,
        onState: ((SuperResDownloadState) -> Unit)? = null,
    ): SuperResDownloadState = downloads.downloadAndInstall(info, onState)

    suspend fun importLocalModel(
        sourceUri: android.net.Uri,
        info: SuperResModelInfo,
        onState: ((SuperResDownloadState) -> Unit)? = null,
    ): SuperResDownloadState = downloads.importLocalModel(sourceUri, info, onState)

    fun deleteModel(id: String): Boolean = downloads.deleteModel(id)

    fun installedBytes(): Long = downloads.installedBytes()

    /** 配置流（设置页读写）。 */
    val configFlow = configStore.configFlow

    suspend fun currentConfig(): SuperResConfig = configStore.current()

    suspend fun setConfig(config: SuperResConfig) = configStore.set(config)

    companion object {
        private const val TAG = "SuperResRuntime"
    }
}
