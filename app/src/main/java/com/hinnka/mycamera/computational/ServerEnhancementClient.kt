package com.hinnka.mycamera.computational

import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    @Volatile var model: String = "basicsr-swinir",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) {
    /** 可观测状态（供 UI 门控/进度条）。 */
    enum class State { IDLE, UPLOADING, DOWNLOADING, FAILED }

    private val _state = AtomicReference(State.IDLE)
    val state: State get() = _state.get()

    @Volatile var lastError: String? = null
        private set

    /** 功能是否启用（配置了非空 URL）。UI 据此决定是否显示「超分」入口。 */
    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "server-enhance-worker").apply { isDaemon = true }
    }

    /**
     * 同步发起一次增强（在后台线程调用）。返回增强结果文件；未配置/失败返回 null。
     *
     * 协议：POST {baseUrl}/enhance，multipart/form-data：
     *   model=basicsr-swinir | bipnet；file=打包帧(zip/dng)。
     *   成功 200 返回增强后 16-bit PNG / linear DNG 字节流。
     */
    fun enhance(inputFile: File, outputFile: File): File? {
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
                _state.set(State.DOWNLOADING)
                conn.inputStream.use { input ->
                    FileOutputStream(outputFile).use { input.copyTo(it) }
                }
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

    /** 入队异步任务（非阻塞）；结果通过回调返回，失败回调 null。 */
    fun enqueue(inputFile: File, outputFile: File, onDone: (File?) -> Unit) {
        if (!isEnabled) { onDone(null); return }
        worker.execute { onDone(enhance(inputFile, outputFile)) }
    }

    fun shutdown() {
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
    }

    companion object {
        private const val TAG = "ServerEnhancement"
    }
}

