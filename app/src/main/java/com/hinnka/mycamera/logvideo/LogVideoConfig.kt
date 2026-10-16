package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.video.VideoLogLutMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Log 视频模块总配置（StormCam 招牌功能 · 视频 20 项第 1-3 项）。
 *
 * 覆盖：
 *  - **第 1 项 Log 视频录制**：[enabled] + [curve]（LogC3/V-Log/HLG 三档 + 黑电平/峰值/斜率/查表分辨率）
 *    + [tenBitPreferred] + [gpuCurveTrimEnabled]；
 *  - **第 2 项 实时 LUT 预览**：[lutMode]（复用工程 `VideoLogLutMode`）+ [restoreLutId] + [lutIntensity]；
 *  - **第 3 项 Log 实况照片**：[livePhotoLogEnabled] + [stillFrameLogEnabled]；
 *  - **第 4 项 Log LUT 包强化**：[bakeRestoreLutOnDevice] + [bakedRestoreLutSize] + [autoApplyBakedRestore]。
 *
 * 所有参数开放可调，并带默认值、范围与 clamp（PATTERN_SPEC §1.6）。
 * 纯 Kotlin（无 Android 依赖），JVM 单测覆盖 clamp 与累计边界。
 */
data class LogVideoConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val curve: LogCurveRecipe = LogCurveRecipe.DEFAULT,
    /** 预览/录制 LUT 语义，复用工程既有 `VideoLogLutMode`（MONITOR_ONLY / BAKE_IN）。 */
    val lutMode: VideoLogLutMode = DEFAULT_LUT_MODE,
    /** 配套 Log 还原 LUT 的 id（如 `log_restore_vlog` / 运行时烘焙的 `log_baked_*`）；空 = 用档位默认。 */
    val restoreLutId: String = "",
    /** LUT 强度（还原 LUT 的混合量），0 = 不着色、1 = 满强度、>1 = 过冲（风格化）。 */
    val lutIntensity: Float = DEFAULT_LUT_INTENSITY,
    /** 是否允许运行时程序化烘焙 Log→Rec709 LUT（第 4 项）。 */
    val bakeRestoreLutOnDevice: Boolean = DEFAULT_BAKE_ON_DEVICE,
    /** 烘焙 3D LUT 尺寸（9..65，默认 33 = 与工程内置 Log 还原组同规格）。 */
    val bakedRestoreLutSize: Int = LogToRec709LutBaker.DEFAULT_SIZE,
    /** 烘焙完成是否自动套用（新手一键套用）。 */
    val autoApplyBakedRestore: Boolean = true,
    /** 录制是否优先 10bit（Log 调色余量；不支持时静默回落 8bit）。 */
    val tenBitPreferred: Boolean = true,
    /** GL 侧是否应用曲线微调（黑电平/斜率/峰值 uniform；关闭则只用档位原生曲线）。 */
    val gpuCurveTrimEnabled: Boolean = true,
    /** Log 实况照片：视频片段与静止帧是否都施加 Log（第 3 项）。 */
    val livePhotoLogEnabled: Boolean = true,
    /** Log 实况照片：静止帧是否经 CPU Log 编码（关闭则只对视频片段施加）。 */
    val stillFrameLogEnabled: Boolean = true,
) {
    /** clamp 到安全范围；脏值一律归位，不抛异常。 */
    fun clamped(): LogVideoConfig = copy(
        curve = curve.clamped(),
        lutIntensity = if (lutIntensity.isFinite()) {
            lutIntensity.coerceIn(LUT_INTENSITY_MIN, LUT_INTENSITY_MAX)
        } else {
            DEFAULT_LUT_INTENSITY
        },
        bakedRestoreLutSize = bakedRestoreLutSize
            .coerceIn(LogToRec709LutBaker.MIN_SIZE, LogToRec709LutBaker.MAX_SIZE),
    )

    /** 被 clamp 修正的字段（供 `PLog.w`）；正常配置返回空列表。 */
    fun clampReport(): List<String> = buildList {
        addAll(curve.clampReport().map { "curve.$it" })
        if (!lutIntensity.isFinite() || lutIntensity !in LUT_INTENSITY_MIN..LUT_INTENSITY_MAX) {
            add("lutIntensity=$lutIntensity")
        }
        if (bakedRestoreLutSize !in LogToRec709LutBaker.MIN_SIZE..LogToRec709LutBaker.MAX_SIZE) {
            add("bakedRestoreLutSize=$bakedRestoreLutSize")
        }
    }

    /** 实际生效的还原 LUT id：显式指定优先，否则给档位默认（含运行时烘焙包）。 */
    fun resolvedRestoreLutId(): String = when {
        restoreLutId.isNotBlank() -> restoreLutId
        bakeRestoreLutOnDevice -> LogLutPackCatalog.BUILT_IN_BAKED_ID + "_" + curve.preset.name.lowercase()
        else -> defaultRestoreLutId(curve.preset)
    }

    /** 仅当开启 + 档位需要 GL 曲线微调时，才向 GL 侧下发三个 trim uniform（避免无谓 uniform 写入）。 */
    val appliesGpuTrim: Boolean get() = enabled && gpuCurveTrimEnabled && !curve.isPresetNative()

    fun describe(): String = buildString {
        append("LogVideoConfig(enabled=$enabled, preset=${curve.preset}/${curve.preset.gamut}")
        append(", black=${curve.blackLevel}, peak=${curve.peak}, slope=${curve.slope}, table=${curve.tableSize}")
        append(", lutMode=$lutMode, lut=${resolvedRestoreLutId()}, intensity=$lutIntensity")
        append(", bake=$bakeRestoreLutOnDevice@$bakedRestoreLutSize, autoApply=$autoApplyBakedRestore")
        append(", tenBit=$tenBitPreferred, gpuTrim=$appliesGpuTrim")
        append(", livePhoto=$livePhotoLogEnabled(still=$stillFrameLogEnabled))")
    }

    companion object {
        const val DEFAULT_ENABLED = false
        val DEFAULT_LUT_MODE: VideoLogLutMode = VideoLogLutMode.MONITOR_ONLY

        /** LUT 强度范围：0（不着色）..2（过冲）。 */
        const val LUT_INTENSITY_MIN = 0f
        const val LUT_INTENSITY_MAX = 2f
        const val DEFAULT_LUT_INTENSITY = 1f

        const val DEFAULT_BAKE_ON_DEVICE = true

        val DEFAULT = LogVideoConfig()

        /** 档位 → 工程内置 Log 还原 LUT id（`assets/luts/config.json` 的 log 组）。 */
        fun defaultRestoreLutId(preset: LogCurvePreset): String = when (preset) {
            LogCurvePreset.LOG_C3 -> "log_restore_logc3"
            LogCurvePreset.V_LOG -> "log_restore_vlog"
            LogCurvePreset.HLG -> "log_restore_hlg"
            LogCurvePreset.LOGC4 -> "log_restore_logc4"
            LogCurvePreset.FLOG2 -> "log_restore_flog2"
            LogCurvePreset.APPLE_LOG2 -> "log_restore_apple_log2"
            LogCurvePreset.LOG3G10 -> "log_restore_log3g10"
            LogCurvePreset.LLOG -> "log_restore_llog"
        }

        /** 从偏好表恢复（读取即 clamp）。键名见 patch 的 CONFIG 段。 */
        fun fromPreferences(values: Map<String, Any?>): LogVideoConfig {
            val raw = LogVideoConfig(
                enabled = (values["log_video_enabled"] as? Boolean) ?: DEFAULT_ENABLED,
                curve = LogCurveRecipe.fromPreferences(values),
                lutMode = (values["log_video_lut_mode"] as? String)
                    ?.let { name -> VideoLogLutMode.entries.firstOrNull { it.name == name } }
                    ?: DEFAULT_LUT_MODE,
                restoreLutId = (values["log_video_restore_lut_id"] as? String) ?: "",
                lutIntensity = (values["log_video_lut_intensity"] as? Number)?.toFloat()
                    ?: DEFAULT_LUT_INTENSITY,
                bakeRestoreLutOnDevice = (values["log_video_bake_on_device"] as? Boolean)
                    ?: DEFAULT_BAKE_ON_DEVICE,
                bakedRestoreLutSize = (values["log_video_baked_lut_size"] as? Number)?.toInt()
                    ?: LogToRec709LutBaker.DEFAULT_SIZE,
                autoApplyBakedRestore = (values["log_video_auto_apply_baked"] as? Boolean) ?: true,
                tenBitPreferred = (values["log_video_ten_bit"] as? Boolean) ?: true,
                gpuCurveTrimEnabled = (values["log_video_gpu_trim"] as? Boolean) ?: true,
                livePhotoLogEnabled = (values["log_video_live_photo"] as? Boolean) ?: true,
                stillFrameLogEnabled = (values["log_video_still_log"] as? Boolean) ?: true,
            )
            return raw.clamped()
        }
    }
}

/**
 * 进程内 Log 视频配置源（GL 线程 / 录制线程 / Compose UI 三方共享）。
 *
 * 只做「不可变对象整体替换 + StateFlow」，无锁；GL 线程读 [current] 为一次 volatile 读。
 * 持久化由既有 DataStore 层负责（patch 的 CONFIG 段给出接线点），本对象不持有 Context。
 */
object LogVideoConfigStore {
    private val state = MutableStateFlow(LogVideoConfig.DEFAULT)

    /** Compose 可直接 collect（UI 与配置单一数据源）。 */
    val flow: StateFlow<LogVideoConfig> = state.asStateFlow()

    /** 当前配置（已 clamp）。 */
    val current: LogVideoConfig get() = state.value

    /** 更新配置；入参自动 clamp，返回被修正的字段清单（空 = 无需日志）。 */
    fun update(transform: (LogVideoConfig) -> LogVideoConfig): List<String> {
        val next = transform(state.value).clamped()
        state.value = next
        return next.clampReport()
    }

    /** 直接设置（用于偏好恢复）。 */
    fun set(config: LogVideoConfig): List<String> {
        val next = config.clamped()
        state.value = next
        return next.clampReport()
    }

    /** 仅供测试。 */
    fun reset() {
        state.value = LogVideoConfig.DEFAULT
    }
}
