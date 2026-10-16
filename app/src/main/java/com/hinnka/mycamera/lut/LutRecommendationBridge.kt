package com.hinnka.mycamera.lut

import android.graphics.Bitmap
import com.hinnka.mycamera.aiplatform.AiParamStore
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.computational.BgJobRunner
import com.hinnka.mycamera.computational.BgTaskType

/**
 * AI LUT 推荐的接线桥（aiplatform_patch.md 注入，解决审计 F2「零调用方」）。
 *
 * 数据流：Bitmap → 降采样统计（平均亮度 / 冷暖 / 肤色占比）→ [LutRecommender.submitAnalysis]
 *         → 过 [LutManager] 注册表过滤 → 回调可用 LUT id 列表。
 *
 * 性能：统计在 BgJobRunner（type=GENERIC, LOW 优先级）后台线程完成；降采样上限 [MAX_EDGE]。
 */
object LutRecommendationBridge {

    const val MAX_EDGE = 160
    const val ENABLED_KEY = "lut.recommend.enabled"

    /** 读取用户开关（设置页 `LutAiSettingsSection` 写同名 key）。 */
    suspend fun isEnabled(context: android.content.Context, default: Boolean = false): Boolean =
        AiParamStore.readBoolean(context, ENABLED_KEY, default)

    /**
     * 请求一次推荐。
     * @param onResult 在后台线程回调（调用方自行切主线程）；未开启时立即回调空表且不入队。
     */
    fun requestFor(
        bitmap: Bitmap,
        lutManager: LutManager,
        config: LutRecommenderConfig = LutRecommenderConfig.DEFAULT,
        enabled: Boolean = true,
        onResult: (List<String>) -> Unit = {},
    ): BgJob? = LutRecommender.submitAnalysis(
        statsProvider = { statsOf(bitmap) },
        enabled = enabled,
        chunkSize = 1,
        onResult = { recommended ->
            val registered = recommended.filter { lutManager.getLutInfo(it) != null }
            onResult(if (registered.isEmpty()) recommended else registered)
        },
        config = config,
    )

    /** 降采样后统计 [meanLuma, warmth, skinFraction]；失败返回 null（不产生推荐）。 */
    fun statsOf(bitmap: Bitmap): FloatArray? = runCatching {
        val scale = MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val w = (bitmap.width * scale).toInt().coerceIn(1, MAX_EDGE)
        val h = (bitmap.height * scale).toInt().coerceIn(1, MAX_EDGE)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            var lumaSum = 0f
            var warmSum = 0f
            var skinCount = 0
            pixels.forEach { px ->
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                lumaSum += luma
                warmSum += (r - b).toFloat() / 255f
                if (g > 60 && r > 95 && r > g && g > b && (r - b) > 15 && (g - b) > 5) skinCount++
            }
            val n = pixels.size.coerceAtLeast(1).toFloat()
            floatArrayOf(lumaSum / n, warmSum / n, skinCount / n)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }.getOrNull()
}
