package com.hinnka.mycamera.aiwb

import android.content.Context
import com.hinnka.mycamera.aiplatform.AiParamStore

/**
 * AI 白平衡参数的持久化与**纯函数映射**部分。
 *
 * 设计：真正的读写走 [AiParamStore]（DataStore）；[configFrom] / [toEntries] /
 * [describe] 为纯函数，便于 JVM 单测覆盖「默认值 / 越界 / 异常输入」。
 */
object AiWhiteBalanceSettings {

    const val KEY_ENABLED = "aiwb.enabled"
    const val KEY_STRENGTH = "aiwb.strength"
    const val KEY_MIN_KELVIN = "aiwb.minKelvin"
    const val KEY_MAX_KELVIN = "aiwb.maxKelvin"
    const val KEY_MAX_GAIN = "aiwb.maxGain"
    const val KEY_HIGHLIGHT_WEIGHT = "aiwb.highlightWeight"
    const val KEY_HIGHLIGHT_LUMA = "aiwb.highlightLuma"
    const val KEY_SAMPLE_STRIDE = "aiwb.sampleStride"
    const val KEY_USE_REMOTE = "aiwb.useRemoteApi"

    /** 键清单（设置页 / 自检 / 清理复用）。 */
    val keys: List<String> = listOf(
        KEY_ENABLED,
        KEY_STRENGTH,
        KEY_MIN_KELVIN,
        KEY_MAX_KELVIN,
        KEY_MAX_GAIN,
        KEY_HIGHLIGHT_WEIGHT,
        KEY_HIGHLIGHT_LUMA,
        KEY_SAMPLE_STRIDE,
        KEY_USE_REMOTE,
    )

    /** 读取（缺项回落默认值，读取失败也不抛）。 */
    suspend fun load(context: Context): AiWhiteBalanceConfig {
        val defaults = AiWhiteBalanceConfig.DEFAULT
        return AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(
                enabled = AiParamStore.readBoolean(context, KEY_ENABLED, defaults.enabled),
                strength = AiParamStore.readFloat(context, KEY_STRENGTH, defaults.strength),
                minKelvin = AiParamStore.readInt(context, KEY_MIN_KELVIN, defaults.minKelvin),
                maxKelvin = AiParamStore.readInt(context, KEY_MAX_KELVIN, defaults.maxKelvin),
                maxGain = AiParamStore.readFloat(context, KEY_MAX_GAIN, defaults.maxGain),
                highlightWeight = AiParamStore.readFloat(context, KEY_HIGHLIGHT_WEIGHT, defaults.highlightWeight),
                highlightLumaThreshold = AiParamStore.readFloat(context, KEY_HIGHLIGHT_LUMA, defaults.highlightLumaThreshold),
                sampleStride = AiParamStore.readInt(context, KEY_SAMPLE_STRIDE, defaults.sampleStride),
                useRemoteApi = AiParamStore.readBoolean(context, KEY_USE_REMOTE, defaults.useRemoteApi),
            ),
        )
    }

    /** 保存（写入前先 clamp，避免把越界值落盘）。 */
    suspend fun save(context: Context, config: AiWhiteBalanceConfig) {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        AiParamStore.writeBoolean(context, KEY_ENABLED, clamped.enabled)
        AiParamStore.writeFloat(context, KEY_STRENGTH, clamped.strength)
        AiParamStore.writeInt(context, KEY_MIN_KELVIN, clamped.minKelvin)
        AiParamStore.writeInt(context, KEY_MAX_KELVIN, clamped.maxKelvin)
        AiParamStore.writeFloat(context, KEY_MAX_GAIN, clamped.maxGain)
        AiParamStore.writeFloat(context, KEY_HIGHLIGHT_WEIGHT, clamped.highlightWeight)
        AiParamStore.writeFloat(context, KEY_HIGHLIGHT_LUMA, clamped.highlightLumaThreshold)
        AiParamStore.writeInt(context, KEY_SAMPLE_STRIDE, clamped.sampleStride)
        AiParamStore.writeBoolean(context, KEY_USE_REMOTE, clamped.useRemoteApi)
    }

    /** 纯函数：字符串字典 → 配置（缺失 / 非法 → 默认值；越界 → clamp）。 */
    fun configFrom(entries: Map<String, String>): AiWhiteBalanceConfig {
        val defaults = AiWhiteBalanceConfig.DEFAULT
        return AiWhiteBalanceConfig.clamp(
            AiWhiteBalanceConfig(
                enabled = entries[KEY_ENABLED]?.toBooleanStrictOrNull() ?: defaults.enabled,
                strength = entries[KEY_STRENGTH]?.toFloatOrNull() ?: defaults.strength,
                minKelvin = entries[KEY_MIN_KELVIN]?.toIntOrNull() ?: defaults.minKelvin,
                maxKelvin = entries[KEY_MAX_KELVIN]?.toIntOrNull() ?: defaults.maxKelvin,
                maxGain = entries[KEY_MAX_GAIN]?.toFloatOrNull() ?: defaults.maxGain,
                highlightWeight = entries[KEY_HIGHLIGHT_WEIGHT]?.toFloatOrNull() ?: defaults.highlightWeight,
                highlightLumaThreshold = entries[KEY_HIGHLIGHT_LUMA]?.toFloatOrNull() ?: defaults.highlightLumaThreshold,
                sampleStride = entries[KEY_SAMPLE_STRIDE]?.toIntOrNull() ?: defaults.sampleStride,
                useRemoteApi = entries[KEY_USE_REMOTE]?.toBooleanStrictOrNull() ?: defaults.useRemoteApi,
            ),
        )
    }

    /** 纯函数：配置 → 字符串字典。 */
    fun toEntries(config: AiWhiteBalanceConfig): Map<String, String> {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        return linkedMapOf(
            KEY_ENABLED to clamped.enabled.toString(),
            KEY_STRENGTH to clamped.strength.toString(),
            KEY_MIN_KELVIN to clamped.minKelvin.toString(),
            KEY_MAX_KELVIN to clamped.maxKelvin.toString(),
            KEY_MAX_GAIN to clamped.maxGain.toString(),
            KEY_HIGHLIGHT_WEIGHT to clamped.highlightWeight.toString(),
            KEY_HIGHLIGHT_LUMA to clamped.highlightLumaThreshold.toString(),
            KEY_SAMPLE_STRIDE to clamped.sampleStride.toString(),
            KEY_USE_REMOTE to clamped.useRemoteApi.toString(),
        )
    }

    /** UI 显示的简短状态（自适应/关闭 + 强度）。 */
    fun describe(config: AiWhiteBalanceConfig): String {
        val clamped = AiWhiteBalanceConfig.clamp(config)
        return if (!clamped.enabled) {
            "关闭"
        } else {
            "强度 ${(clamped.strength * 100).toInt()}% · ${clamped.minKelvin}-${clamped.maxKelvin}K · " +
                if (clamped.useRemoteApi) "远端优先" else "端侧"
        }
    }
}
