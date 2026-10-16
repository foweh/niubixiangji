package com.hinnka.mycamera.scenerecog

import android.content.Context
import com.hinnka.mycamera.aiplatform.AiParamStore

/**
 * 场景识别参数的持久化与**纯函数映射**部分（同 `AiWhiteBalanceSettings` 结构）。
 */
object SceneRecognitionSettings {

    const val KEY_ENABLED = "scenerecog.enabled"
    const val KEY_PORTRAIT_SKIN = "scenerecog.portraitSkinFraction"
    const val KEY_TEXT_THRESHOLD = "scenerecog.textScoreThreshold"
    const val KEY_NIGHT_LUMA = "scenerecog.nightMaxLuma"
    const val KEY_LANDSCAPE_LUMA = "scenerecog.landscapeMinLuma"
    const val KEY_LANDSCAPE_SATURATION = "scenerecog.landscapeMinSaturation"
    const val KEY_LANDSCAPE_WARMTH = "scenerecog.landscapeMaxWarmth"
    const val KEY_STREET_WARMTH = "scenerecog.streetMinWarmth"
    const val KEY_STREET_EDGE = "scenerecog.streetMinEdgeDensity"
    const val KEY_SAMPLE_STRIDE = "scenerecog.sampleStride"
    const val KEY_USE_REMOTE = "scenerecog.useRemoteApi"

    val keys: List<String> = listOf(
        KEY_ENABLED,
        KEY_PORTRAIT_SKIN,
        KEY_TEXT_THRESHOLD,
        KEY_NIGHT_LUMA,
        KEY_LANDSCAPE_LUMA,
        KEY_LANDSCAPE_SATURATION,
        KEY_LANDSCAPE_WARMTH,
        KEY_STREET_WARMTH,
        KEY_STREET_EDGE,
        KEY_SAMPLE_STRIDE,
        KEY_USE_REMOTE,
    )

    suspend fun load(context: Context): SceneRecognitionConfig {
        val defaults = SceneRecognitionConfig.DEFAULT
        return SceneRecognitionConfig.clamp(
            SceneRecognitionConfig(
                enabled = AiParamStore.readBoolean(context, KEY_ENABLED, defaults.enabled),
                portraitSkinFraction = AiParamStore.readFloat(context, KEY_PORTRAIT_SKIN, defaults.portraitSkinFraction),
                textScoreThreshold = AiParamStore.readFloat(context, KEY_TEXT_THRESHOLD, defaults.textScoreThreshold),
                nightMaxLuma = AiParamStore.readFloat(context, KEY_NIGHT_LUMA, defaults.nightMaxLuma),
                landscapeMinLuma = AiParamStore.readFloat(context, KEY_LANDSCAPE_LUMA, defaults.landscapeMinLuma),
                landscapeMinSaturation = AiParamStore.readFloat(
                    context,
                    KEY_LANDSCAPE_SATURATION,
                    defaults.landscapeMinSaturation,
                ),
                landscapeMaxWarmth = AiParamStore.readFloat(context, KEY_LANDSCAPE_WARMTH, defaults.landscapeMaxWarmth),
                streetMinWarmth = AiParamStore.readFloat(context, KEY_STREET_WARMTH, defaults.streetMinWarmth),
                streetMinEdgeDensity = AiParamStore.readFloat(context, KEY_STREET_EDGE, defaults.streetMinEdgeDensity),
                sampleStride = AiParamStore.readInt(context, KEY_SAMPLE_STRIDE, defaults.sampleStride),
                useRemoteApi = AiParamStore.readBoolean(context, KEY_USE_REMOTE, defaults.useRemoteApi),
            ),
        )
    }

    suspend fun save(context: Context, config: SceneRecognitionConfig) {
        val clamped = SceneRecognitionConfig.clamp(config)
        AiParamStore.writeBoolean(context, KEY_ENABLED, clamped.enabled)
        AiParamStore.writeFloat(context, KEY_PORTRAIT_SKIN, clamped.portraitSkinFraction)
        AiParamStore.writeFloat(context, KEY_TEXT_THRESHOLD, clamped.textScoreThreshold)
        AiParamStore.writeFloat(context, KEY_NIGHT_LUMA, clamped.nightMaxLuma)
        AiParamStore.writeFloat(context, KEY_LANDSCAPE_LUMA, clamped.landscapeMinLuma)
        AiParamStore.writeFloat(context, KEY_LANDSCAPE_SATURATION, clamped.landscapeMinSaturation)
        AiParamStore.writeFloat(context, KEY_LANDSCAPE_WARMTH, clamped.landscapeMaxWarmth)
        AiParamStore.writeFloat(context, KEY_STREET_WARMTH, clamped.streetMinWarmth)
        AiParamStore.writeFloat(context, KEY_STREET_EDGE, clamped.streetMinEdgeDensity)
        AiParamStore.writeInt(context, KEY_SAMPLE_STRIDE, clamped.sampleStride)
        AiParamStore.writeBoolean(context, KEY_USE_REMOTE, clamped.useRemoteApi)
    }

    fun configFrom(entries: Map<String, String>): SceneRecognitionConfig {
        val defaults = SceneRecognitionConfig.DEFAULT
        return SceneRecognitionConfig.clamp(
            SceneRecognitionConfig(
                enabled = entries[KEY_ENABLED]?.toBooleanStrictOrNull() ?: defaults.enabled,
                portraitSkinFraction = entries[KEY_PORTRAIT_SKIN]?.toFloatOrNull() ?: defaults.portraitSkinFraction,
                textScoreThreshold = entries[KEY_TEXT_THRESHOLD]?.toFloatOrNull() ?: defaults.textScoreThreshold,
                nightMaxLuma = entries[KEY_NIGHT_LUMA]?.toFloatOrNull() ?: defaults.nightMaxLuma,
                landscapeMinLuma = entries[KEY_LANDSCAPE_LUMA]?.toFloatOrNull() ?: defaults.landscapeMinLuma,
                landscapeMinSaturation = entries[KEY_LANDSCAPE_SATURATION]?.toFloatOrNull()
                    ?: defaults.landscapeMinSaturation,
                landscapeMaxWarmth = entries[KEY_LANDSCAPE_WARMTH]?.toFloatOrNull() ?: defaults.landscapeMaxWarmth,
                streetMinWarmth = entries[KEY_STREET_WARMTH]?.toFloatOrNull() ?: defaults.streetMinWarmth,
                streetMinEdgeDensity = entries[KEY_STREET_EDGE]?.toFloatOrNull() ?: defaults.streetMinEdgeDensity,
                sampleStride = entries[KEY_SAMPLE_STRIDE]?.toIntOrNull() ?: defaults.sampleStride,
                useRemoteApi = entries[KEY_USE_REMOTE]?.toBooleanStrictOrNull() ?: defaults.useRemoteApi,
            ),
        )
    }

    fun toEntries(config: SceneRecognitionConfig): Map<String, String> {
        val clamped = SceneRecognitionConfig.clamp(config)
        return linkedMapOf(
            KEY_ENABLED to clamped.enabled.toString(),
            KEY_PORTRAIT_SKIN to clamped.portraitSkinFraction.toString(),
            KEY_TEXT_THRESHOLD to clamped.textScoreThreshold.toString(),
            KEY_NIGHT_LUMA to clamped.nightMaxLuma.toString(),
            KEY_LANDSCAPE_LUMA to clamped.landscapeMinLuma.toString(),
            KEY_LANDSCAPE_SATURATION to clamped.landscapeMinSaturation.toString(),
            KEY_LANDSCAPE_WARMTH to clamped.landscapeMaxWarmth.toString(),
            KEY_STREET_WARMTH to clamped.streetMinWarmth.toString(),
            KEY_STREET_EDGE to clamped.streetMinEdgeDensity.toString(),
            KEY_SAMPLE_STRIDE to clamped.sampleStride.toString(),
            KEY_USE_REMOTE to clamped.useRemoteApi.toString(),
        )
    }

    fun describe(config: SceneRecognitionConfig): String {
        val clamped = SceneRecognitionConfig.clamp(config)
        return if (!clamped.enabled) {
            "关闭"
        } else {
            "规则分类 · " + if (clamped.useRemoteApi) "远端优先" else "端侧"
        }
    }
}
