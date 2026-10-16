package com.hinnka.mycamera.computational

import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.aiplatform.executeWithFallback
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * ServerEnhancementClient — 可选「服务器端 AI 超分/增强」客户端（原则 C 兜底，用户定稿）。
 *
 * 定稿分工（用户 2026-10-15 确认）：
 *  - 手机本地职责 = 拍摄 / 多帧堆栈 / 预览 / 显示；
 *  - 超分由服务器计算（XPixelGroup/BasicSR=8395 优先部署 ESRGAN/SwinIR；
 *    akshaydudhane16/BIPNet=167 学术参考）；
 *  - **本地不内置任何超分大模型**（保轻量），纯 HTTP 客户端。
 *
 * 工作流：本地完成堆栈/线性 DNG 后，可选上传 → 服务器推理 → 回传 PNG/DNG → 本地显示。
 * 失败自动回退本地结果（返回 null，上层用本地堆栈图，不阻塞出片）。
 *
 * 配置：baseUrl 默认空 = 功能关闭（UI 据此门控）；可在设置里填入自部署服务器地址。
 */
class ServerEnhancementClient(
    @Volatile var baseUrl: String = "",
    @Volatile var model: String = DEFAULT_MODEL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
    /** AI 平台层配置源（aiplatform/）；非空时端点/密钥/超时/重试全部读配置，本类不再自带。 */
    private val aiConfigStore: com.hinnka.mycamera.aiplatform.AiConfigStore? = null,
) {
    /** 可观测状态（供 UI 门控/进度条）。 */
    enum class State { IDLE, UPLOADING, DOWNLOADING, FAILED }

    private val _state = AtomicReference(State.IDLE)
    val state: State get() = _state.get()

    @Volatile var lastError: String? = null
        private set

    /** 功能是否启用（配置了非空 URL）。UI 据此决定是否显示「超分」入口。 */
    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    /**
     * 同步发起一次增强（在后台线程调用）。返回增强结果文件；未配置/失败返回 null。
     *
     * 协议：POST {baseUrl}/enhance，multipart/form-data：
     *   model=basicsr-swinir | bipnet；file=打包帧(zip/dng)。
     *   成功 200 返回增强后 16-bit PNG / linear DNG 字节流。
     */
    fun enhance(inputFile: File, outputFile: File, job: BgJob? = null): File? {
        if (!isEnabled) return null
        return runCatching {
            _state.set(State.UPLOADING)
            val boundary = "----PhotonBoundary${System.nanoTime()}"
            val url = URL("$baseUrl/enhance")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.outputStream.use { out ->
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
                    out.write("$model\r\n".toByteArray())
                    out.write("--$boundary\r\n".toByteArray())
                    out.write(
                        ("Content-Disposition: form-data; name=\"file\"; " +
                            "filename=\"${inputFile.name}\"\r\n").toByteArray()
                    )
                    out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                    inputFile.inputStream().use { it.copyTo(out) }
                    out.write("\r\n--$boundary--\r\n".toByteArray())
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    PLog.w(TAG, "Server enhance HTTP ${conn.responseCode}")
                    _state.set(State.FAILED)
                    lastError = "HTTP ${conn.responseCode}"
                    return null
                }
                // P1-12：分块读取 + 每块 checkYield（响应暂停/取消/节流）；
                // M3：先落内存做类型/魔数/体积校验，通过后才写盘。
                val contentType = conn.contentType
                _state.set(State.DOWNLOADING)
                val buffer = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val chunk = ByteArray(64 * 1024)
                    var read = input.read(chunk)
                    while (read >= 0) {
                        buffer.write(chunk, 0, read)
                        job?.checkYield()   // PATTERN_SPEC §1.5：每个分片末尾必须 checkYield
                        read = input.read(chunk)
                    }
                }
                val bytes = buffer.toByteArray()
                // M3（aiplatform）：复用平台层校验器（Content-Type 白名单 + 魔数 + 体积），单一真值源。
                val validation = com.hinnka.mycamera.aiplatform.AiResponseValidator.validate(
                    head = bytes.copyOfRange(0, minOf(bytes.size, 16)),
                    actualBytes = bytes.size.toLong(),
                    contentType = contentType,
                    expectedContentTypes = com.hinnka.mycamera.aiplatform.AiResponseValidator.IMAGE_CONTENT_TYPES,
                    minBytes = 1L,
                )
                check(validation == com.hinnka.mycamera.aiplatform.AiResponseValidation.Ok) {
                    "response rejected: $validation (contentType=$contentType, size=${bytes.size})"
                }
                FileOutputStream(outputFile).use { it.write(bytes) }
                _state.set(State.IDLE)
                lastError = null
                outputFile
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            PLog.w(TAG, "Server enhance failed, falling back to local: ${it.message}")
            _state.set(State.FAILED)
            lastError = it.message
        }.getOrNull()
    }

    /**
     * 入队异步任务（非阻塞）。
     *
     * 走全局性能调度器 [BgJobRunner]（type=SERVER_ENHANCE，单消费者串行，可暂停/取消），
     * 结果通过回调返回，失败回调 null。返回值即 [BgJob]，界面可读 state 显示进度/门控。
     */
    fun enqueue(inputFile: File, outputFile: File, onDone: (File?) -> Unit): BgJob =
        BgJobRunner.submit(
            name = "Server Enhance",
            chunkSize = 4,   // 分片：每 4 次 read 轮询一次；配合 enhance 内的 checkYield 响应取消
            type = BgTaskType.SERVER_ENHANCE,
            priority = BgJobPriority.LOW,
        ) {
            report(0, 1)
            val result = if (isEnabled) enhance(inputFile, outputFile, this) else null
            report(1, 1)
            checkYield()
            onDone(result)
        }

    /**
     * 兼容保留：本客户端不再自持线程池（任务统一走 [BgJobRunner] 全局队列），无资源可释放。
     */
    @Deprecated("任务已统一走 BgJobRunner，无需显式释放", ReplaceWith(""))
    fun shutdown() = Unit

    /** 从配置读取端点/模型（默认空 = 功能关闭，UI 据此门控）。 */
    fun configureFrom(config: ServerEnhanceConfig) {
        baseUrl = config.baseUrl.trim().trimEnd('/')
        model = config.model.ifBlank { DEFAULT_MODEL }
    }

    /**
     * 经 AI 平台层执行一次服务器增强（aiplatform_patch.md 注入）。
     *
     * 相比原 [enhance] 的差异（逐条对应审计问题）：
     *  - M1：请求头带鉴权（`AiEndpointConfig.authScheme` / 自定义头名，密钥来自 DataStore 密文）；
     *  - M2：Offline/Timeout/408/429/5xx 指数退避重试（次数可配，抖动，协程取消即 `call.cancel()`）；
     *  - M3：非 2xx **不再当图片落盘**；响应需过 `Content-Type` 白名单 + 魔数 + 体积双阈值校验；
     *  - M4：明文 http 默认拒绝并以 `CleartextBlocked` 明确归因；
     *  - M5：失败归因经 `AiNotices` 用户可见文案上抛（`onNotice`）；
     *  - M8：任何失败都回退「本地堆栈结果」（`localFallback = { null }` → 上层沿用本地图）；
     *  - I16：日志脱敏（`AiLogRedactor`，响应体只截断片段且先过脱敏）。
     *
     * @return 成功返回写好的输出文件；未启用/失败返回 null（不抛异常）。
     */
    suspend fun enhanceViaPlatform(
        context: android.content.Context,
        inputFile: File,
        outputFile: File,
        onNotice: (com.hinnka.mycamera.aiplatform.AiFailureReason, Boolean) -> Unit = { _, _ -> },
    ): File? {
        val platform = com.hinnka.mycamera.aiplatform.AiPlatform.get(context)
        val config = platform.configStore.config(com.hinnka.mycamera.aiplatform.AiCapability.SERVER_ENHANCE)
        if (!config.isEnabled) {
            onNotice(com.hinnka.mycamera.aiplatform.AiFailureReason.Disabled, true)
            return null
        }
        inputFile.parentFile?.mkdirs()
        _state.set(State.UPLOADING)
        val result = platform.httpClient.executeWithFallback(
            capability = com.hinnka.mycamera.aiplatform.AiCapability.SERVER_ENHANCE,
            remote = {
                platform.httpClient.downloadToFile(
                    capability = com.hinnka.mycamera.aiplatform.AiCapability.SERVER_ENHANCE,
                    request = com.hinnka.mycamera.aiplatform.AiRawRequest(
                        method = "POST",
                        multipart = com.hinnka.mycamera.aiplatform.AiMultipartRequest(
                            fileName = inputFile.name,
                            fileContentType = "application/octet-stream",
                            fileProvider = { inputFile.inputStream() },
                            textFields = mapOf("model" to config.model),
                        ),
                    ),
                    destFile = outputFile,
                    expectedContentTypes = com.hinnka.mycamera.aiplatform.AiResponseValidator.IMAGE_CONTENT_TYPES,
                    minBytes = 1L,
                )
            },
            localFallback = { null },
            onNotice = { reason, _ ->
                _state.set(State.FAILED)
                lastError = com.hinnka.mycamera.aiplatform.AiNotices.describe(reason)
                onNotice(reason, true)
            },
        )
        return result.also {
            if (it != null) {
                _state.set(State.IDLE)
                lastError = null
            }
        }
    }

    /**
     * 入队执行 [enhanceViaPlatform]（非阻塞，走 BgJobRunner，type=SERVER_ENHANCE，LOW 优先级）。
     * 供 `CameraViewModel.processStacking` 出片后调用（见 H2）。
     */
    fun enqueueViaPlatform(
        context: android.content.Context,
        inputFile: File,
        outputFile: File,
        onNotice: (com.hinnka.mycamera.aiplatform.AiFailureReason, Boolean) -> Unit = { _, _ -> },
        onDone: (File?) -> Unit = {},
    ): BgJob = BgJobRunner.submit(
        name = "Server Enhance",
        chunkSize = 1,
        type = BgTaskType.SERVER_ENHANCE,
        priority = BgJobPriority.LOW,
    ) {
        report(0, 1)
        // BgJob 的 block 非 suspend，故在后台调度线程内桥接平台层 suspend 调用；
        // 该线程由 BgJobRunner 独占（非主线程），不会阻塞 UI。
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                enhanceViaPlatform(
                    context = context,
                    inputFile = inputFile,
                    outputFile = outputFile,
                    onNotice = onNotice,
                )
            }
        }.getOrNull()
        report(1, 1)
        checkYield()
        onDone(result)
    }
    companion object {
        const val DEFAULT_MODEL = "basicsr-swinir"
        private const val TAG = "ServerEnhancement"
    }
}


/**
 * 服务器增强配置（P1-12：端点/模型来自设置，不再恒为空 → 功能可真正开启）。
 * 默认 baseUrl 为空 = 功能关闭（UI 据此门控），与既有行为一致。
 */
data class ServerEnhanceConfig(
    val baseUrl: String = "",
    val model: String = ServerEnhancementClient.DEFAULT_MODEL,
)
