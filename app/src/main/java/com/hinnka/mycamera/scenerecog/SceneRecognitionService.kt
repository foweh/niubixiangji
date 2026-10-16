package com.hinnka.mycamera.scenerecog

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
import com.hinnka.mycamera.lut.LutRecommender
import com.hinnka.mycamera.utils.PLog
import org.json.JSONObject
import java.io.File

/**
 * AI 场景识别服务（Android 侧胶水层；纯算法在 [SceneFeatureExtractor] + [SceneClassifier]）。
 *
 * 职责：
 *  - 位图 → 降采样 → 特征提取 → 规则分类（[analyze]，端侧、零网络）；
 *  - 可选远端分类（[AiCapability.SCENE_RECOGNITION]，端点未配置时自动回落端侧）；
 *  - 统一走 [BgJobRunner] 后台低优先级队列（[submitAnalysis]）。
 *
 * 远端契约（自建轻量分类服务）：`POST {baseUrl}/classify`，multipart `file=<jpeg>`，
 * 200 返回 JSON：`{"scene":"PORTRAIT","confidence":0.82}`（也接受 `{"label":"night"}`，大小写不敏感）。
 */
object SceneRecognitionService {

    const val JOB_NAME = "AI 场景识别"
    const val TAG = "SceneRecognition"

    /** 端侧分析长边上限。 */
    const val ANALYSIS_MAX_EDGE = 256

    /** 上行远端长边上限与 JPEG 质量。 */
    const val UPLOAD_MAX_EDGE = 1024
    const val UPLOAD_JPEG_QUALITY = 85

    private const val CACHE_SUBDIR = "scenerecog"

    /** 端侧分析（同步；须在后台线程调用）。内部异常收敛为 UNKNOWN，不抛。 */
    fun analyze(
        bitmap: Bitmap,
        config: SceneRecognitionConfig = SceneRecognitionConfig(),
    ): SceneClassification {
        val clamped = SceneRecognitionConfig.clamp(config)
        SceneRecognitionConfig.clampWithReport(config).reports.forEach { PLog.i(TAG, "配置已修正：$it") }
        val features = extractFeatures(bitmap, clamped) ?: return SceneClassification(SceneLabel.UNKNOWN, 0f)
        return SceneClassifier.classify(features, clamped)
    }

    /**
     * 入队端侧分析（后台低优先级；复用既有 `BgTaskType.AI_LUT`，无需新增枚举）。
     * @param onFinish 完成回调（后台线程）；失败/取消给 null
     */
    fun submitAnalysis(
        bitmap: Bitmap,
        config: SceneRecognitionConfig = SceneRecognitionConfig(),
        jobName: String = JOB_NAME,
        priority: Int = BgJobPriority.LOW,
        onFinish: (SceneClassification?) -> Unit = {},
    ): BgJob {
        val clamped = SceneRecognitionConfig.clamp(config)
        var output: SceneClassification? = null
        return BgJobRunner.submit(
            name = jobName,
            chunkSize = 1,
            type = BgTaskType.AI_LUT,
            priority = priority,
            onFinish = { onFinish(output) },
        ) {
            report(0, 2)
            checkYield()
            output = runCatching { analyze(bitmap, clamped) }
                .onFailure { PLog.w(TAG, "端侧场景识别失败：${it.message}") }
                .getOrNull()
            report(1, 2)
            checkYield()
            report(2, 2)
        }
    }

    /** 远端优先 / 失败降级端侧。 */
    suspend fun analyzeWithPlatform(
        context: Context,
        bitmap: Bitmap,
        config: SceneRecognitionConfig = SceneRecognitionConfig(),
        onNotice: (AiFailureReason, Boolean) -> Unit = { _, _ -> },
    ): SceneClassification {
        val clamped = SceneRecognitionConfig.clamp(config)
        if (!clamped.useRemoteApi) return analyze(bitmap, clamped)

        val platform = AiPlatform.get(context)
        val local = { analyze(bitmap, clamped) }
        return platform.httpClient.executeWithFallback(
            capability = AiCapability.SCENE_RECOGNITION,
            remote = { remoteClassification(context, platform.httpClient, bitmap, clamped) },
            localFallback = { local() },
            onNotice = onNotice,
        ) ?: SceneClassification(SceneLabel.UNKNOWN, 0f)
    }

    /** 远端响应解析（字段名容错；无法解析返回 null）。 */
    fun parseRemoteClassification(
        json: String,
        config: SceneRecognitionConfig = SceneRecognitionConfig(),
    ): SceneClassification? = runCatching {
        val obj = JSONObject(json)
        val wire = obj.optString("scene").ifBlank { obj.optString("label") }
        val confidence = obj.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)
        SceneClassification(
            label = labelFromWireName(wire),
            confidence = confidence,
            note = "远端分类",
            features = SceneFeatures.EMPTY,
        )
    }.getOrNull()

    /** 线上名称 → 标签（大小写 / 别名容错）。 */
    fun labelFromWireName(name: String?): SceneLabel {
        val normalized = name?.trim()?.uppercase().orEmpty()
        return when (normalized) {
            "PORTRAIT", "PERSON", "PEOPLE", "FACE" -> SceneLabel.PORTRAIT
            "LANDSCAPE", "SCENERY", "NATURE", "OUTDOOR" -> SceneLabel.LANDSCAPE
            "NIGHT", "LOWLIGHT", "LOW_LIGHT", "NIGHTSCENE" -> SceneLabel.NIGHT
            "STREET", "CITY", "URBAN" -> SceneLabel.STREET
            "TEXT", "DOCUMENT", "SCREENSHOT", "OCR" -> SceneLabel.TEXT
            else -> SceneLabel.UNKNOWN
        }
    }

    /**
     * 场景标签 → `lut/LutRecommender.Scene`（用于把场景识别接到既有 AI LUT 推荐；
     * 补丁在 LutRecommender 接线处调用它）。
     * TEXT 在 LutRecommender 无对应项，映射为 NEUTRAL。
     */
    fun toLutRecommenderScene(label: SceneLabel): LutRecommender.Scene = when (label) {
        SceneLabel.PORTRAIT -> LutRecommender.Scene.PORTRAIT
        SceneLabel.LANDSCAPE -> LutRecommender.Scene.LANDSCAPE
        SceneLabel.NIGHT -> LutRecommender.Scene.NIGHT
        SceneLabel.STREET -> LutRecommender.Scene.STREET
        SceneLabel.TEXT, SceneLabel.UNKNOWN -> LutRecommender.Scene.NEUTRAL
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun remoteClassification(
        context: Context,
        client: AiHttpClient,
        bitmap: Bitmap,
        config: SceneRecognitionConfig,
    ): AiCallResult<SceneClassification> {
        val upload = prepareUploadFile(context, bitmap)
            ?: return AiCallResult.Failure(AiFailureReason.Parse("无法生成上行图像"))
        return try {
            client.executeMultipart(
                capability = AiCapability.SCENE_RECOGNITION,
                request = AiMultipartRequest(
                    fileName = upload.name,
                    fileContentType = "image/jpeg",
                    fileProvider = { upload.inputStream() },
                ),
                parse = { text ->
                    parseRemoteClassification(text, config)
                        ?: error("响应无法解析为场景标签")
                },
            )
        } finally {
            runCatching { upload.delete() }
        }
    }

    private fun extractFeatures(bitmap: Bitmap, config: SceneRecognitionConfig): SceneFeatures? = runCatching {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val (width, height) = fitWithin(bitmap.width, bitmap.height, ANALYSIS_MAX_EDGE)
        val scaled = if (width != bitmap.width || height != bitmap.height) bitmap.scale(width, height) else bitmap
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) scaled.recycle()
        SceneFeatureExtractor.extract(pixels, scaled.width, scaled.height, config.sampleStride)
    }.onFailure { PLog.w(TAG, "特征提取失败：${it.message}") }.getOrNull()

    private fun prepareUploadFile(context: Context, bitmap: Bitmap): File? = runCatching {
        val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val file = File(dir, "scene_${System.nanoTime()}.jpg")
        val (width, height) = fitWithin(bitmap.width, bitmap.height, UPLOAD_MAX_EDGE)
        val scaled = if (width != bitmap.width || height != bitmap.height) bitmap.scale(width, height) else bitmap
        file.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        file
    }.onFailure { PLog.w(TAG, "生成上行图像失败：${it.message}") }.getOrNull()

    private fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val factor = maxEdge.toFloat() / longest
        return (width * factor).toInt().coerceAtLeast(1) to (height * factor).toInt().coerceAtLeast(1)
    }
}
