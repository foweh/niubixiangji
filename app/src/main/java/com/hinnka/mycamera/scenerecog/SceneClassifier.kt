package com.hinnka.mycamera.scenerecog

import com.hinnka.mycamera.camera.ComputationalScene
import java.util.Locale

/**
 * 场景标签（轻量规则分类输出）。
 * 与 `lut/LutRecommender.Scene` 的口径对齐（见 [SceneRecognitionService.toLutRecommenderScene]）。
 */
enum class SceneLabel {
    PORTRAIT,
    LANDSCAPE,
    NIGHT,
    STREET,
    TEXT,
    UNKNOWN,
}

/**
 * 分类结果。
 *
 * @param confidence ∈ [0, 1]；UNKNOWN 固定给低置信（0.25），调用方据此避免误应用
 * @param note 诊断说明（供日志 / 自检，不进 UI；UI 文案走 strings.xml）
 * @param features 参与判定的特征快照（可观测 / 可复算）
 */
data class SceneClassification(
    val label: SceneLabel,
    val confidence: Float,
    val note: String = "",
    val features: SceneFeatures = SceneFeatures.EMPTY,
)

/**
 * 轻量规则场景分类器（**纯 Kotlin 纯函数**，可 JVM 单测；不依赖 TFLite / 网络）。
 *
 * 参考思路（**仅参考，未采代码**）：
 *  - Google Camera (GCam) 的「场景检测 → 参数预设」分层思路：先用极廉价的全局统计
 *    做粗分类，再决定是否升级到重模型；
 *  - 与工程既有 `LutRecommender.classifyScene` 的阈值口径一致，避免两个「场景判断」
 *    给出相反结论。
 *
 * 判定顺序（首个命中即返回，全部阈值可配 + clamp）：
 *  1. 肤色占比 ≥ `portraitSkinFraction` 且亮度在有效区间 → PORTRAIT
 *  2. `textScore` ≥ `textScoreThreshold` → TEXT（高局部对比 + 低饱和 + 亮度双峰）
 *  3. `meanLuma` ≤ `nightMaxLuma` → NIGHT
 *  4. 明亮（≥ `landscapeMinLuma`）且够饱和（≥ `landscapeMinSaturation`）且偏冷
 *     （`warmth` ≤ `landscapeMaxWarmth`）→ LANDSCAPE
 *  5. 偏暖（≥ `streetMinWarmth`）或纹理密集且亮度居中 → STREET
 *  6. 其余 → UNKNOWN
 */
object SceneClassifier {

    fun classify(
        features: SceneFeatures,
        config: SceneRecognitionConfig = SceneRecognitionConfig(),
    ): SceneClassification {
        val clamped = SceneRecognitionConfig.clamp(config)
        if (!features.isUsable) {
            return SceneClassification(SceneLabel.UNKNOWN, UNKNOWN_CONFIDENCE, "无有效样本", features)
        }

        val meanLuma = features.meanLuma

        // 1. 人像
        if (features.skinFraction >= clamped.portraitSkinFraction &&
            meanLuma in SceneRecognitionConfig.PORTRAIT_MIN_LUMA..SceneRecognitionConfig.PORTRAIT_MAX_LUMA
        ) {
            val margin = (
                (features.skinFraction - clamped.portraitSkinFraction) /
                    clamped.portraitSkinFraction.coerceAtLeast(EPS)
                ).coerceIn(0f, 1f)
            return SceneClassification(
                SceneLabel.PORTRAIT,
                confidenceOf(margin),
                "肤色占比 ${format(features.skinFraction)} ≥ ${format(clamped.portraitSkinFraction)}",
                features,
            )
        }

        // 2. 文本 / 文档
        if (features.textScore >= clamped.textScoreThreshold) {
            val margin = (
                (features.textScore - clamped.textScoreThreshold) /
                    (1f - clamped.textScoreThreshold).coerceAtLeast(EPS)
                ).coerceIn(0f, 1f)
            return SceneClassification(
                SceneLabel.TEXT,
                confidenceOf(margin),
                "文本代理分 ${format(features.textScore)} ≥ ${format(clamped.textScoreThreshold)}",
                features,
            )
        }

        // 3. 夜景
        if (meanLuma <= clamped.nightMaxLuma) {
            val margin = (
                (clamped.nightMaxLuma - meanLuma) / clamped.nightMaxLuma.coerceAtLeast(EPS)
                ).coerceIn(0f, 1f)
            return SceneClassification(
                SceneLabel.NIGHT,
                confidenceOf(margin),
                "平均亮度 ${format(meanLuma)} ≤ ${format(clamped.nightMaxLuma)}",
                features,
            )
        }

        // 4. 风光
        if (meanLuma >= clamped.landscapeMinLuma &&
            features.saturation >= clamped.landscapeMinSaturation &&
            features.warmth <= clamped.landscapeMaxWarmth
        ) {
            val lumaMargin = (meanLuma - clamped.landscapeMinLuma) / (1f - clamped.landscapeMinLuma).coerceAtLeast(EPS)
            val saturationMargin = (features.saturation - clamped.landscapeMinSaturation) /
                clamped.landscapeMinSaturation.coerceAtLeast(EPS)
            val warmthMargin = (clamped.landscapeMaxWarmth - features.warmth) / WARMTH_SPAN
            val margin = minOf(lumaMargin, saturationMargin, warmthMargin).coerceIn(0f, 1f)
            return SceneClassification(
                SceneLabel.LANDSCAPE,
                confidenceOf(margin),
                "亮度 ${format(meanLuma)} / 饱和 ${format(features.saturation)} / 冷暖 ${format(features.warmth)}",
                features,
            )
        }

        // 5. 街景
        val warmthHit = features.warmth >= clamped.streetMinWarmth
        val edgeHit = features.edgeDensity >= clamped.streetMinEdgeDensity &&
            meanLuma in SceneRecognitionConfig.STREET_MIN_LUMA..SceneRecognitionConfig.STREET_MAX_LUMA
        if (warmthHit || edgeHit) {
            val warmthMargin = if (warmthHit) {
                ((features.warmth - clamped.streetMinWarmth) / WARMTH_SPAN).coerceIn(0f, 1f)
            } else {
                0f
            }
            val edgeMargin = if (edgeHit) {
                (
                    (features.edgeDensity - clamped.streetMinEdgeDensity) /
                        clamped.streetMinEdgeDensity.coerceAtLeast(EPS)
                    ).coerceIn(0f, 1f)
            } else {
                0f
            }
            val reason = when {
                warmthHit && edgeHit -> "偏暖 ${format(features.warmth)} + 纹理 ${format(features.edgeDensity)}"
                warmthHit -> "偏暖 ${format(features.warmth)}"
                else -> "纹理密集 ${format(features.edgeDensity)}"
            }
            return SceneClassification(
                SceneLabel.STREET,
                confidenceOf(maxOf(warmthMargin, edgeMargin)),
                reason,
                features,
            )
        }

        return SceneClassification(SceneLabel.UNKNOWN, UNKNOWN_CONFIDENCE, "未命中任何规则", features)
    }

    /** 场景标签 → 计算场景（跨代理契约符号 [ComputationalScene]）。仅人像/夜景有专用管线。 */
    fun toComputationalScene(label: SceneLabel): ComputationalScene = when (label) {
        SceneLabel.PORTRAIT -> ComputationalScene.PORTRAIT
        SceneLabel.NIGHT -> ComputationalScene.NIGHT
        SceneLabel.LANDSCAPE,
        SceneLabel.STREET,
        SceneLabel.TEXT,
        SceneLabel.UNKNOWN,
        -> ComputationalScene.STANDARD
    }

    /** ≥ 阈值后的裕度 → 置信度（0.5 起步，最多 1.0）。 */
    fun confidenceOf(margin: Float): Float = (0.5f + 0.5f * margin.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /** UNKNOWN 的固定低置信。 */
    const val UNKNOWN_CONFIDENCE = 0.25f

    private const val EPS = 1e-3f
    private const val WARMTH_SPAN = 0.4f

    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)
}
