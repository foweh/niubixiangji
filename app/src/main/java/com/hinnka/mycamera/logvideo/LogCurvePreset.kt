package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import kotlin.math.log10
import kotlin.math.pow

/**
 * Log 视频录制曲线档位（Log 视频 20 项 · 第 1 项）。
 *
 * 三档主推（用户需求）：
 *  - [LOG_C3]：ARRI LogC3（工程 [TransferCurve] 缺该项 → 本模块按 ARRI 公开曲线规格实现，见常量）；
 *  - [V_LOG]：Panasonic V-Log（**复用** `TransferCurve.VLOG`，零自研）；
 *  - [HLG]  ：ITU-R BT.2100 HLG（**复用** `TransferCurve.HLG`）。
 * 其余 Log 组（LogC4 / F-Log2 / Apple Log 2 / Log3G10 / L-Log）同样**复用**工程既有
 * [TransferCurve]，供 Log→Rec709 LUT 烘焙与曲线查表使用——与工程 Log 还原 LUT 组完全对齐。
 *
 * 复用优先（PATTERN_SPEC §9）：本枚举只做「档位 → 曲线/色域」的映射，曲线数学全部走
 * `TransferCurve.linearToLog / logToLinear`；仅 LogC3 因工程缺失而自实现（12 行公式）。
 *
 * 来源：ARRI LogC3 曲线规格（ARRI ALEXA 官方公开公式，EI800）、Panasonic V-Log 公开曲线规格、
 * ITU-R BT.2100-2 HLG。GPL 项目 cameramanben/LUTCalc（350★）仅作**曲线参数交叉核对**，
 * 未采用其任何代码（GPL 只参考思路，PATTERN_SPEC §1.4）。
 */
enum class LogCurvePreset(
    val displayName: String,
    val gamut: LogGamut,
    /** 工程既有同规格曲线实现（复用）；null = 工程缺失，由本模块实现。 */
    val nativeCurve: TransferCurve?,
    /** 该档位对应的 `VideoLogProfile` 枚举名（补丁新增后自动接入；缺失时不用）。 */
    val videoLogProfileName: String,
) {
    /** ARRI LogC3（AWG3 色域）。工程 TransferCurve 只有 LogC4 → 本模块按公开规格实现。 */
    LOG_C3("LogC3 (ARRI)", LogGamut.AWRI_WIDE_GAMUT_3, null, "LOGC3_ARRI3"),

    /** Panasonic V-Log（V-Gamut）。复用 `TransferCurve.VLOG`。 */
    V_LOG("V-Log (Panasonic)", LogGamut.V_GAMUT, TransferCurve.VLOG, "V_LOG"),

    /** ITU-R BT.2100 HLG（BT.2020 色域）。复用 `TransferCurve.HLG`。 */
    HLG("HLG (BT.2100)", LogGamut.BT2020, TransferCurve.HLG, "HLG_BT2020"),

    /** ARRI LogC4（AWG4）。复用 `TransferCurve.LOGC4`。 */
    LOGC4("LogC4 (ARRI)", LogGamut.AWRI_WIDE_GAMUT_4, TransferCurve.LOGC4, "LOGC4_ARRI4"),

    /** Fujifilm F-Log2（BT.2020）。复用 `TransferCurve.FLOG2`。 */
    FLOG2("F-Log2 (Fujifilm)", LogGamut.BT2020, TransferCurve.FLOG2, "FLOG2_BT2020"),

    /** Apple Log 2。复用 `TransferCurve.APPLE_LOG`。 */
    APPLE_LOG2("Apple Log 2", LogGamut.APPLE_LOG, TransferCurve.APPLE_LOG, "APPLE_LOG2"),

    /** RED Log3G10（RED Wide Gamut）。复用 `TransferCurve.LOG3G10`。 */
    LOG3G10("Log3G10 (RED)", LogGamut.RED_WIDE_GAMUT, TransferCurve.LOG3G10, "LOG3G10_RED"),

    /** Leica L-Log（BT.2020）。复用 `TransferCurve.LLOG`。 */
    LLOG("L-Log (Leica)", LogGamut.BT2020, TransferCurve.LLOG, "LLOG_BT2020"),
    ;

    /**
     * 场景线性反射率 → Log 编码代码值（查表前的解析式）。
     *
     * 输入 [sceneLinear] 为场景线性光（0 = 全黑，1 = 场景白），允许 >1（高光余量，曲线自然压缩）。
     * 返回值域 [0,1]（超界由调用方 clamp）。
     */
    fun encodeCode(sceneLinear: Float): Float {
        val value = sceneLinear.coerceAtLeast(0f)
        nativeCurve?.let { return it.linearToLog(value) }
        // 全工程唯一自实现段：ARRI LogC3（工程 TransferCurve 无此项）。
        return if (value > LOGC3_CUT_LINEAR) {
            LOGC3_C * log10(LOGC3_A * value + LOGC3_B) + LOGC3_D
        } else {
            LOGC3_E * value + LOGC3_F
        }
    }

    /**
     * Log 编码代码值 → 场景线性反射率（供 Log→Rec709 烘焙的解码半边使用）。
     */
    fun decodeLinear(logCode: Float): Float {
        val value = logCode.coerceIn(0f, 1f)
        nativeCurve?.let { return it.logToLinear(value) }
        return if (value > LOGC3_CODE_AT_CUT) {
            (10.0.pow(((value - LOGC3_D) / LOGC3_C).toDouble()).toFloat() - LOGC3_B) / LOGC3_A
        } else {
            (value - LOGC3_F) / LOGC3_E
        }
    }

    /** 输入为 0（全黑）时的原生代码值：黑电平偏置的默认参考点。 */
    val nativeBlackCode: Float
        get() = encodeCode(0f)

    /** 输入为 1（场景白）时的原生代码值：峰值/白点的默认参考点。 */
    val nativeWhiteCode: Float
        get() = encodeCode(1f)

    /**
     * 该档位 **18% 中灰**（= 显示白的 18%）对应的 Log 代码值。
     *
     * 统一口径：中灰场景值 = `0.18 × displayWhiteInput`。
     *  - LogC3/V-Log/…（场景白 = 线性 1.0）：即 `encodeCode(0.18)` → LogC3 = 0.391、V-Log = 0.423；
     *  - HLG（BT.2408 漫反射白 = 代码 0.75）：中灰 = `encodeCode(0.18 × 0.2649)` = 代码 0.378
     *    ——注意**不是** `encodeCode(0.18)`（那会算成 0.672，即 68% 灰，误差近 2 档）。
     *
     * ⚠️ **与工程 `TransferCurve.middleGray` 的口径差异（有意为之，勿"修正"）**：
     * 工程 `TransferCurve.HLG.middleGray = 0.672358`（= `encodeCode(0.18)`，即把 HLG 的
     * OETF 输入按"峰值归一"读：E=1.0 = 标称峰值 = 12× 漫反射白）。该字段服务于工程自身的
     * 测光/曲线逻辑；而**还原 LUT 必须按显示域锚定**（BT.2408：漫反射白在代码 0.75 → SDR 100%），
     * 否则正常曝光的 HLG 画面还原后会整体偏暗约 1 档。其余档位两种口径一致（如 V-Log 均为 0.4233）。
     *
     * 不变量（单测锁定）：所有档位经 Log→Rec.709 烘焙后，中灰都落在
     * `Rec.709 OETF(0.18) ≈ 0.409` 信号处。
     */
    val middleGreyCode: Float
        get() = encodeCode(SCENE_MIDDLE_GREY * displayWhiteInput)

    /**
     * **映射到显示白（100% 信号）的场景线性值**——Log→Rec709 烘焙的白点锚。
     *
     * 对场景参考的 Log 曲线（LogC3/V-Log/LogC4/…）：场景白 = 线性 1.0（默认）。
     * 对 HLG：BT.2408 规定**漫反射白（diffuse white）在 HLG 代码 0.75**，
     * 对应场景线性 0.2649（= `(exp((0.75-C)/A)+B)/12`）；若直接按"HLG 代码 1.0 = 白"烘焙，
     * 正常曝光的 HLG 画面在还原后会整体偏暗约 1 档。故 HLG 的白点锚取 0.2649，
     * 代码 1.0（BT.2100 峰值）由高光软膝裁切到白——与 BT.2408 的 SDR 参考转换一致。
     */
    val displayWhiteInput: Float
        get() = if (this == HLG) HLG_DIFFUSE_WHITE_SCENE else 1f

    /** 该档位的 `VideoLogProfile`（补丁未应用时返回 null；不做悬空引用，见 SelfCheck）。 */
    val videoLogProfile: com.hinnka.mycamera.video.VideoLogProfile?
        get() = runCatching {
            com.hinnka.mycamera.video.VideoLogProfile.valueOf(videoLogProfileName)
        }.getOrNull()

    companion object {
        /** ARRI LogC3（EI 800）官方公开常量。 */
        const val LOGC3_A = 5.555556f
        const val LOGC3_B = 0.052272f
        const val LOGC3_C = 0.247190f
        const val LOGC3_D = 0.385537f
        const val LOGC3_E = 5.367655f
        const val LOGC3_F = 0.092809f

        /** 线性支/对数支的分界（线性域）。 */
        const val LOGC3_CUT_LINEAR = 0.010591f

        /** 分界点对应的代码值 = LOGC3_E * LOGC3_CUT_LINEAR + LOGC3_F（两支在舍入误差内连续）。 */
        const val LOGC3_CODE_AT_CUT = 0.149658f

        /** LogC3 中灰（18% 反射）参考代码值，用于单测已知点比对。 */
        const val LOGC3_MIDDLE_GREY_CODE = 0.391f

        /** 场景中灰（18% 反射率），各档位中灰代码与 Log→709 中灰校验的基准。 */
        const val SCENE_MIDDLE_GREY = 0.18f

        /** BT.2408 HLG 漫反射白（代码 0.75）对应的场景线性值。 */
        const val HLG_DIFFUSE_WHITE_CODE = 0.75f
        const val HLG_DIFFUSE_WHITE_SCENE = 0.2649f

        /** 由持久化名恢复档位（容错，脏值回默认 LOG_C3）。 */
        fun fromPersistedName(name: String?): LogCurvePreset =
            entries.firstOrNull { it.name == name } ?: LOG_C3
    }
}
