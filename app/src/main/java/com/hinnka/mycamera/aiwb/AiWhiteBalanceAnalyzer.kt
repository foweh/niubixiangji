package com.hinnka.mycamera.aiwb

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.hinnka.mycamera.aiplatform.AiCallResult
import com.hinnka.mycamera.aiplatform.AiCapability
import com.hinnka.mycamera.aiplatform.AiFailureReason
import com.hinnka.mycamera.aiplatform.AiHttpClient
import com.hinnka.mycamera.aiplatform.AiMultipartRequest
import com.hinnka.mycamera.aiplatform.AiPlatform
import com.hinnka.mycamera.aiplatform.executeWithFallback
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType
import com.hinnka.mycamera.utils.PLog
import org.json.JSONObject
import java.io.File

/**
 * AI 白平衡分析器（Android 侧胶水层；纯算法在 [AiWhiteBalanceEstimator]）。
 *
 * 职责：
 *  - 位图 → 降采样 → 像素统计 → 端侧估计（[estimateFromBitmap]）；
 *  - 可选远端估计（[AiCapability.WHITE_BALANCE]，端点未配置时自动回落端侧）；
 *  - 统一走 [BgJobRunner] 后台低优先级队列（[submitEstimate]），不抢前台。
 *
 * **端侧优先**：不配置端点即可用（默认行为，零网络）。
 * 远端契约（自建轻量服务）：`POST {baseUrl}/white-balance`，multipart `file=<jpeg>`，
 * 200 返回 JSON：`{"kelvin":5200,"gain_r":1.06,"gain_g":1.0,"gain_b":0.94,"tint":0.02,"confidence":0.8}`
 * （字段名容错，缺省项回落到 1.0 / 中性色温）。
 */
object AiWhiteBalanceAnalyzer {

    const val JOB_NAME = "AI 白平衡"
    const val TAG = "AiWhiteBalance"

    /** 端侧分析用的长边上限（降采样，控 CPU 与内存峰值）。 */
    const val ANALYSIS_MAX_EDGE = 256

    /** 上行到远端时的长边上限与 JPEG 质量。 */
    const val UPLOAD_MAX_EDGE = 1024
    const val UPLOAD_JPEG_QUALITY = 85

    private const val CACHE_SUBDIR = "aiwb"

    /** 写入拍摄请求所需的最低置信度（低于它的估计只展示、不参与捕获）。 */
    const val MIN_CONFIDENCE = 0.35f

    /** `COLOR_CORRECTION_GAINS` 的合法区间（Camera2 硬件通常限 1/4..4 倍以内）。 */
    private const val SENSOR_GAIN_MIN = 0.25f
    private const val SENSOR_GAIN_MAX = 4f

    /**
     * 最近一次可用估计（进程内缓存，供 Camera2 侧只读使用）。
     *
     * 写入时机：[estimateFromBitmap] 成功产出可用结果时。
     * 读取时机：`Camera2Controller` 的 AI 白平衡补丁（[applyToCaptureRequest]）。
     * 为什么缓存而不是每帧重算：白平衡是低频量，逐帧重算既费 CPU 也无收益。
     */
    @Volatile
    var lastEstimate: AiWhiteBalanceEstimate? = null
        private set

    /** 手动写入缓存（后台估计完成后调用；不可用/恒等结果不缓存，避免写入无效参数）。 */
    fun record(estimate: AiWhiteBalanceEstimate?) {
        lastEstimate = estimate?.takeIf { it.isUsable && !it.isIdentity }
    }

    /** 清空缓存（关闭功能 / 切镜头 / 重置参数时调用）。 */
    fun clearEstimate() {
        lastEstimate = null
    }

    /**
     * 端侧估计（同步；须在后台线程调用）。
     * 任何内部异常都收敛为「恒等结果」，不抛。
     */
    fun estimateFromBitmap(
        bitmap: Bitmap,
        config: AiWhiteBalanceConfig = AiWhiteBalanceConfig(),
    ): AiWhiteBalanceEstimate {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        AiWhiteBalanceConfig.clampWithReport(config).reports.forEach { PLog.i(TAG, "配置已修正：$it") }
        val stats = extractStats(bitmap, clamped) ?: return AiWhiteBalanceEstimate.IDENTITY
        val estimate = AiWhiteBalanceEstimator.estimate(stats, clamped)
        // 只在开启时缓存：关闭时缓存恒为空，捕获链路自然走原分支（零行为变化）。
        record(if (clamped.enabled) estimate else null)
        return estimate
    }

    /**
     * 把缓存的估计结果写入拍摄请求（补丁接线：`Camera2Controller` 白平衡段）。
     *
     * 生效条件（任一不满足即**一个参数都不写**，保持既有拍照行为）：
     *  1. [lastEstimate] 非空且可用；
     *  2. `confidence >= [minConfidence]`（低置信度场景如大面积蓝天不参与）；
     *  3. `gainR` / `gainB` 与 1 的偏离大于 1e-3。
     *
     * 写入内容：`CONTROL_AWB_LOCK = true`（冻结自动白平衡，避免被 AWB 覆盖）
     * + `COLOR_CORRECTION_GAINS`（RggbChannelVector，R / G / G / B 增益；估计的增益口径即
     * 「绿色参考的校正增益」，与 Camera2 通道增益语义一致）。
     *
     * 注意：估计在 sRGB 口径、增益落到传感器口径，二者只是近似映射，故本功能默认关闭；
     * 且它是**捕获级**改动（锁 AWB + 写增益），不参与预览显示的实时调整。
     *
     * @return true 表示确实写入了请求参数。
     */
    fun applyToCaptureRequest(
        builder: android.hardware.camera2.CaptureRequest.Builder,
        minConfidence: Float = MIN_CONFIDENCE,
    ): Boolean {
        val estimate = lastEstimate ?: return false
        if (!estimate.isUsable || estimate.confidence < minConfidence) return false
        if (kotlin.math.abs(estimate.gainR - 1f) < 1e-3f && kotlin.math.abs(estimate.gainB - 1f) < 1e-3f) return false
        return runCatching {
            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true)
            builder.set(
                android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(
                    estimate.gainR.coerceIn(SENSOR_GAIN_MIN, SENSOR_GAIN_MAX),
                    estimate.gainG.coerceIn(SENSOR_GAIN_MIN, SENSOR_GAIN_MAX),
                    estimate.gainG.coerceIn(SENSOR_GAIN_MIN, SENSOR_GAIN_MAX),
                    estimate.gainB.coerceIn(SENSOR_GAIN_MIN, SENSOR_GAIN_MAX),
                ),
            )
            PLog.i(TAG, "AI 白平衡已写入拍摄请求：${estimate.kelvin}K conf=${estimate.confidence}")
            true
        }.onFailure { PLog.w(TAG, "写入 AI 白平衡失败（保持原白平衡）：${it.message}") }.getOrDefault(false)
    }

    /**
     * 入队端侧估计（后台低优先级；复用既有 `BgTaskType.AI_LUT` 轻量类型，无需新增枚举）。
     * @param onFinish 完成回调（后台线程）；失败/取消给 null
     */
    fun submitEstimate(
        bitmap: Bitmap,
        config: AiWhiteBalanceConfig = AiWhiteBalanceConfig(),
        jobName: String = JOB_NAME,
        priority: Int = BgJobPriority.LOW,
        onFinish: (AiWhiteBalanceEstimate?) -> Unit = {},
    ): BgJob {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        var output: AiWhiteBalanceEstimate? = null
        return BgJobRunner.submit(
            name = jobName,
            chunkSize = 1,
            type = BgTaskType.AI_LUT,
            priority = priority,
            onFinish = { onFinish(output) },
        ) {
            report(0, 2)
            checkYield()
            output = runCatching { estimateFromBitmap(bitmap, clamped) }
                .onFailure { PLog.w(TAG, "端侧白平衡估计失败：${it.message}") }
                .getOrNull()
            report(1, 2)
            checkYield()
            report(2, 2)
        }
    }

    /**
     * 远端优先 / 失败降级端侧（[executeWithFallback] 的标准用法）。
     * 返回恒等结果表示「无法估计，维持原白平衡」——调用方据此保持现行为，不崩不改色。
     */
    suspend fun estimateWithPlatform(
        context: Context,
        bitmap: Bitmap,
        config: AiWhiteBalanceConfig = AiWhiteBalanceConfig(),
        onNotice: (AiFailureReason, Boolean) -> Unit = { _, _ -> },
    ): AiWhiteBalanceEstimate {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        if (!clamped.useRemoteApi) return estimateFromBitmap(bitmap, clamped)

        val platform = AiPlatform.get(context)
        val local = { estimateFromBitmap(bitmap, clamped) }
        val result = platform.httpClient.executeWithFallback(
            capability = AiCapability.WHITE_BALANCE,
            remote = { remoteEstimate(context, platform.httpClient, bitmap, clamped) },
            localFallback = { local() },
            onNotice = onNotice,
        ) ?: AiWhiteBalanceEstimate.IDENTITY
        // 远端成功时也要更新缓存（否则捕获链路读到的仍是端侧旧值）。
        record(if (clamped.enabled) result else null)
        return result
    }

    /** 远端响应解析（字段名容错；不可解析返回 null）。 */
    fun parseRemoteEstimate(
        json: String,
        config: AiWhiteBalanceConfig = AiWhiteBalanceConfig(),
    ): AiWhiteBalanceEstimate? {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        return runCatching {
            val obj = JSONObject(json)
            val kelvin = obj.optInt("kelvin", obj.optInt("cct", AiWhiteBalanceEstimator.NEUTRAL_KELVIN))
            val gainR = obj.optDouble("gain_r", obj.optDouble("gainR", 1.0)).toFloat()
            val gainG = obj.optDouble("gain_g", obj.optDouble("gainG", 1.0)).toFloat()
            val gainB = obj.optDouble("gain_b", obj.optDouble("gainB", 1.0)).toFloat()
            val tint = obj.optDouble("tint", 0.0).toFloat()
            val confidence = obj.optDouble("confidence", 0.5).toFloat()
            AiWhiteBalanceEstimate(
                kelvin = kelvin.coerceIn(clamped.minKelvin, clamped.maxKelvin),
                gainR = gainR.coerceIn(1f / clamped.maxGain, clamped.maxGain),
                gainG = gainG.coerceIn(1f / clamped.maxGain, clamped.maxGain),
                gainB = gainB.coerceIn(1f / clamped.maxGain, clamped.maxGain),
                tint = tint.coerceIn(-1f, 1f),
                confidence = confidence.coerceIn(0f, 1f),
                source = AiWhiteBalanceEstimator.SOURCE_REMOTE,
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun remoteEstimate(
        context: Context,
        client: AiHttpClient,
        bitmap: Bitmap,
        config: AiWhiteBalanceConfig,
    ): AiCallResult<AiWhiteBalanceEstimate> {
        val upload = prepareUploadFile(context, bitmap)
            ?: return AiCallResult.Failure(AiFailureReason.Parse("无法生成上行图像"))
        return try {
            client.executeMultipart(
                capability = AiCapability.WHITE_BALANCE,
                request = AiMultipartRequest(
                    fileName = upload.name,
                    fileContentType = "image/jpeg",
                    fileProvider = { upload.inputStream() },
                ),
                parse = { text ->
                    parseRemoteEstimate(text, config)
                        ?: error("响应无法解析为白平衡结果")
                },
            )
        } finally {
            runCatching { upload.delete() }
        }
    }

    private fun prepareUploadFile(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val file = File(dir, "wb_${System.nanoTime()}.jpg")
        val (width, height) = fitWithin(bitmap.width, bitmap.height, UPLOAD_MAX_EDGE)
        val scaled = if (width != bitmap.width || height != bitmap.height) bitmap.scale(width, height) else bitmap
        file.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        file
    }.onFailure { PLog.w(TAG, "生成上行图像失败：${it.message}") }.getOrNull()

    private fun extractStats(bitmap: Bitmap, config: AiWhiteBalanceConfig): AiWbStats? = runCatching {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val (width, height) = fitWithin(bitmap.width, bitmap.height, ANALYSIS_MAX_EDGE)
        val scaled = if (width != bitmap.width || height != bitmap.height) bitmap.scale(width, height) else bitmap
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        computeAiWbStats(
            pixels = pixels,
            highlightLumaThreshold = config.highlightLumaThreshold,
            stride = config.sampleStride,
        )
    }.onFailure { PLog.w(TAG, "像素统计失败：${it.message}") }.getOrNull()

    private fun clampReports(config: AiWhiteBalanceConfig): List<String> =
        AiWhiteBalanceConfig.clampWithReport(config).reports

    /** 等比缩放到长边不超过 [maxEdge]；已足够小时原样返回。 */
    private fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val factor = maxEdge.toFloat() / longest
        return (width * factor).toInt().coerceAtLeast(1) to (height * factor).toInt().coerceAtLeast(1)
    }
}
