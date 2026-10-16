package com.hinnka.mycamera.videomodes

/**
 * §15 防闪烁（50Hz / 60Hz / 关 / 自动）。
 *
 * 核对结论：工程内**无任何**反闪烁/抗干扰相关设置（`grep -rn "ANTI_FLICKER|ANTIBANDING"` 无命中），
 * 故本模块为新增开放项。
 *
 * **接口事实（已在 `android-36/android.jar` 上核实，不是猜测）**：
 * Android Camera2 里与「消除 50/60Hz 灯光闪烁/条纹」对应的正式 API 是 **anti-banding**：
 * - 能力查询：`CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES`（`IntArray`）；
 * - 写入请求：`CaptureRequest.CONTROL_AE_ANTIBANDING_MODE`，取值来自
 *   `CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF / _50HZ / _60HZ / _AUTO`
 *   （= 0 / 1 / 2 / 3，`javap -constants` 已核对）。
 *
 * 因此本文件**不 import android**（便于纯 JVM 单测），[AntiFlickerMode.metadataValue] 存的就是
 * 上述 Camera2 原始 int 值，由探测层/接线处直接使用。
 */

/**
 * 反闪烁（anti-banding）模式。
 *
 * @param metadataValue 写入 `CaptureRequest.CONTROL_AE_ANTIBANDING_MODE` 的原始值；
 *        OFF=0 / 50HZ=1 / 60HZ=2 / AUTO=3（与 `CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_*` 一致）。
 */
enum class AntiFlickerMode(val metadataValue: Int, val label: String) {
    AUTO(3, "自动"),
    HZ_50(1, "50Hz"),
    HZ_60(2, "60Hz"),
    OFF(0, "关闭");

    companion object {
        fun fromMetadata(value: Int?): AntiFlickerMode? =
            entries.firstOrNull { it.metadataValue == value }

        fun fromName(name: String?): AntiFlickerMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO

        /** 自动模式下的地区兜底：50Hz 电网地区（中国大陆）优先。 */
        fun autoDefaultForRegion(use50HzGrid: Boolean): AntiFlickerMode = if (use50HzGrid) HZ_50 else HZ_60
    }
}

/**
 * 防闪烁开放配置。
 *
 * @param mode 期望模式，默认 [AntiFlickerMode.AUTO]（= 不写该 key，保持 HAL 默认 → 零回归）。
 * @param resolutionAutoFallback 设备未上报所请求档位时是否退到「同电网频率的另一档」或 AUTO，默认 true。
 * @param use50HzGrid 若自动回退到具体频率，是否优先 50Hz（中国大陆默认 true）。
 */
data class AntiFlickerConfig(
    val mode: AntiFlickerMode = AntiFlickerMode.AUTO,
    val resolutionAutoFallback: Boolean = true,
    val use50HzGrid: Boolean = true,
)

/** 反闪烁决策。 */
data class AntiFlickerDecision(
    /** 最终模式。 */
    val mode: AntiFlickerMode,
    /** 是否需要向请求写入该 key（false = 保持 HAL 默认，等价既有行为）。 */
    val applyToRequest: Boolean,
    val reason: String,
)

/** 反闪烁策略（纯逻辑）。 */
object AntiFlickerPolicy {

    /**
     * 解析最终反闪烁模式。
     *
     * @param availableModes 设备上报的可用模式原始值集合（空集 = 设备未上报该能力）。
     */
    fun resolve(config: AntiFlickerConfig, availableModes: Set<Int>): AntiFlickerDecision {
        // 未上报（低版本或 HAL 不提供）：不写 key，避免「写了不支持的值」导致请求被拒。
        if (availableModes.isEmpty()) {
            return AntiFlickerDecision(
                mode = AntiFlickerMode.AUTO,
                applyToRequest = false,
                reason = "设备未上报 CONTROL_AE_AVAILABLE_ANTIBANDING_MODES，" +
                    "保持 HAL 默认（AUTO）",
            )
        }

        if (config.mode == AntiFlickerMode.AUTO) {
            val available = availableModes.contains(AntiFlickerMode.AUTO.metadataValue)
            return AntiFlickerDecision(
                mode = AntiFlickerMode.AUTO,
                applyToRequest = available,
                reason = if (available) {
                    "使用 HAL 自动反闪烁"
                } else {
                    "设备未提供 AUTO 档，保持 HAL 默认"
                },
            )
        }

        if (availableModes.contains(config.mode.metadataValue)) {
            return AntiFlickerDecision(
                mode = config.mode,
                applyToRequest = true,
                reason = "使用 ${config.mode.label}",
            )
        }

        if (!config.resolutionAutoFallback) {
            return AntiFlickerDecision(
                mode = AntiFlickerMode.AUTO,
                applyToRequest = false,
                reason = "设备不支持 ${config.mode.label}，且未开启自动回退（保持 HAL 默认）",
            )
        }

        // 回退：先试同电网的另一个频率，再退 AUTO。
        val fallbackFrequency = if (config.use50HzGrid) AntiFlickerMode.HZ_50 else AntiFlickerMode.HZ_60
        val fallback = if (availableModes.contains(fallbackFrequency.metadataValue)) {
            fallbackFrequency
        } else {
            AntiFlickerMode.AUTO
        }
        val apply = availableModes.contains(fallback.metadataValue)
        return AntiFlickerDecision(
            mode = fallback,
            applyToRequest = apply,
            reason = "设备不支持 ${config.mode.label}，已回退 ${fallback.label}",
        )
    }

    /** 把上报的原始值数组归一化成集合（供探测层调用）。 */
    fun normalizeAvailableModes(raw: IntArray?): Set<Int> = raw?.toSet() ?: emptySet()
}
