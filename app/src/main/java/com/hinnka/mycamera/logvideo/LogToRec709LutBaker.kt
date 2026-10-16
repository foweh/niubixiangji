package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.raw.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Log→Rec709 转换链的输出显示编码（两者 18% 中灰均落在 ~0.41，工程显示域为 sRGB）。 */
enum class LogDisplayOetf(val displayName: String) {
    /** ITU-R BT.709 OETF（`1.099x^0.45-0.099`，线性段 4.5x）。技术转换的规范目标。 */
    REC709("Rec.709 OETF"),

    /** sRGB 传输函数（与工程预览/编码器显示域一致时的可选目标）。 */
    SRGB("sRGB"),
    ;

    companion object {
        fun fromPersistedName(name: String?): LogDisplayOetf =
            entries.firstOrNull { it.name == name } ?: REC709
    }
}

/**
 * Log → Rec.709 3D LUT 烘焙器（Log 视频 20 项 · 第 4 项「运行时程序化烘焙 Log→Rec709 3D LUT」）。
 *
 * 转换链（**与工程 GLSL `applyLutColorSpace` + `applyLutCurve` 完全同构，只是方向取反**）：
 * ```
 * Log 代码 g  --(recipe.decode, Log 曲线逆变换)-->  场景线性（Log 原生色域）
 *            --(LogGamutMatrix, 如 AWG3→Rec709)--> 线性 Rec.709
 *            --(可选高光软膝)-->  显示编码 [0,1]
 * ```
 * 输出 3D LUT 的**输入轴 = Log 代码值**——这正是工程 shader 在 `uVideoLogEnabled` 时的
 * `lutInColor = color.rgb`（Log 代码）约定，也与内置 `assets/luts/log/log_restore_*.plut` 一致。
 *
 * 关键约定（**已按工程内置 LUT 实测反推核对**）：
 *  - 18% 中灰：各档位 Log 代码的中灰 → 线性 0.18 → Rec.709 OETF(0.18) = 0.4097 ≈ 41% 信号，
 *    与 ARRI「LogC3-to-Gamma24-Rec709」/ Panasonic「V-709」的 41% 中灰规范一致；
 *  - 场景白（线性 1.0）→ 100% 信号；线性 >1.0（Log 的高光余量）由 [highlightKnee] 软压缩，
 *    技术转换语义下等价于高光裁切（Panasonic/ARRI 官方技术 LUT 同样裁切）。
 *
 * 数据布局：`index = ((b * size + g) * size + r) * 3`（红变化最快），与工程
 * `LutConverter.convertCubeToplut` / `LutParser` 的 3D 纹理顺序一致。
 *
 * 纯 Kotlin（无 Android 依赖），JVM 单测可逐点验证；重活由 `LogVideoBakeJob` 经 `BgJobRunner` 调度。
 */
object LogToRec709LutBaker {

    /** 3D LUT 尺寸范围：9（快速预览/低内存）..65（高精度），默认 33（与工程内置 LUT 同规格）。 */
    const val MIN_SIZE = 9
    const val MAX_SIZE = 65
    const val DEFAULT_SIZE = 33

    /** 默认高光软膝起点（线性域）。0 = 关闭（硬裁切，完全技术转换）。 */
    const val DEFAULT_HIGHLIGHT_KNEE = 0.9f

    /**
     * 烘焙 Log→Rec709 3D LUT。
     *
     * @param axisCompensation 若该 LUT 将经 `CustomImportManager.importLut(curve=…)` 入库，
     *        传入该 curve 以**预补偿**导入重采样对输入轴的改写（见 [compensatedAxis]）；
     *        直接写成 .plut / 导出 .cube 给外部调色软件时传 null（轴 = Log 代码）。
     * @param onProgress 分片进度回调 `(已完成, 总数)`，供 BgJobRunner 上报。
     */
    fun bake(
        recipe: LogCurveRecipe,
        size: Int = DEFAULT_SIZE,
        target: LogGamut = LogGamut.REC709,
        oetf: LogDisplayOetf = LogDisplayOetf.REC709,
        highlightKnee: Float = DEFAULT_HIGHLIGHT_KNEE,
        axisCompensation: TransferCurve? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): FloatArray {
        val safeRecipe = recipe.clamped()
        val dimension = size.coerceIn(MIN_SIZE, MAX_SIZE)
        val matrix = LogGamutMatrix.conversion(safeRecipe.preset.gamut, target)
        val step = 1f / (dimension - 1)
        val knee = safeKnee(highlightKnee)
        val total = dimension
        val data = FloatArray(dimension * dimension * dimension * 3)

        for (bIndex in 0 until dimension) {
            for (gIndex in 0 until dimension) {
                // 绿/蓝通道先在 Log 曲线域解码一次（与红通道对称，循环内避免重复解码）
                val linearG = displayReferred(safeRecipe, gIndex * step)
                val linearB = displayReferred(safeRecipe, bIndex * step)
                for (rIndex in 0 until dimension) {
                    val linearR = displayReferred(safeRecipe, rIndex * step)
                    val linear = LogGamutMatrix.apply(matrix, linearR, linearG, linearB)
                    val offset = ((bIndex * dimension + gIndex) * dimension + rIndex) * 3
                    data[offset] = encodeDisplay(linear[0], knee, oetf)
                    data[offset + 1] = encodeDisplay(linear[1], knee, oetf)
                    data[offset + 2] = encodeDisplay(linear[2], knee, oetf)
                }
            }
            onProgress?.invoke(bIndex + 1, total)
        }

        // 轴预补偿：把「导入重采样后的实际采样点」映射回我们要的 Log 代码语义
        if (axisCompensation != null) {
            return reaxis(data, dimension, safeRecipe, matrix, knee, oetf, axisCompensation)
        }
        return data
    }

    /**
     * 生成可直接落盘为内置 LUT 的 `.plut` v3 字节（UINT16，输入曲线/色域标签 = Log 档位本身）。
     *
     * 标签必须写成 **Log 档位对应的 [TransferCurve] / [ColorSpace]**：工程
     * `CameraViewModel` 在 Log 视频模式下按 `VideoLogProfile.matchesLut(curve, colorSpace)` 过滤可选项，
     * 标签正确的 LUT 才会出现在「配套还原 LUT」列表里（一键套用）。
     */
    fun writePlutBytes(
        lut: FloatArray,
        size: Int,
        curve: TransferCurve,
        colorSpace: ColorSpace,
    ): ByteArray {
        require(size in MIN_SIZE..MAX_SIZE) { "LUT size out of range: $size" }
        val dimension = size
        val count = dimension * dimension * dimension * 3
        require(lut.size >= count) { "LUT data too small: ${lut.size} < $count" }

        val buffer = ByteBuffer.allocate(PLUT_HEADER_BYTES + count * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PLUT_MAGIC)
        buffer.putInt(PLUT_VERSION)
        buffer.putInt(dimension)
        buffer.putInt(PLUT_DATA_TYPE_UINT16)
        buffer.putInt(curve.storageId)
        buffer.putInt(colorSpace.ordinal)
        for (index in 0 until count) {
            buffer.putShort(toUnsignedShort(lut[index]))
        }
        return buffer.array()
    }

    /** 生成 Adobe `.cube` 文本（1D 曲线坐标轴为 Log 代码；可给外部调色软件/自查用）。 */
    fun writeCubeText(
        lut: FloatArray,
        size: Int,
        title: String,
        comments: List<String> = emptyList(),
    ): String {
        val dimension = size.coerceIn(MIN_SIZE, MAX_SIZE)
        val builder = StringBuilder()
        builder.append("# Log→Rec709 3D LUT generated by StormCam LogVideo\n")
        comments.forEach { builder.append("# ").append(it).append('\n') }
        builder.append("TITLE \"").append(title).append("\"\n")
        builder.append("LUT_3D_SIZE ").append(dimension).append('\n')
        val count = dimension * dimension * dimension * 3
        for (index in 0 until count step 3) {
            builder.append(formatComponent(lut[index])).append(' ')
                .append(formatComponent(lut[index + 1])).append(' ')
                .append(formatComponent(lut[index + 2])).append('\n')
        }
        return builder.toString()
    }

    /** 人类可读的烘焙参数描述（写进 .cube 注释与 SelfCheck 日志）。 */
    fun describe(
        recipe: LogCurveRecipe,
        size: Int,
        target: LogGamut = LogGamut.REC709,
        oetf: LogDisplayOetf = LogDisplayOetf.REC709,
        highlightKnee: Float = DEFAULT_HIGHLIGHT_KNEE,
        axisCompensation: TransferCurve? = null,
    ): String = buildString {
        append("preset=${recipe.preset}(gamut=${recipe.preset.gamut})")
        append(" black=${recipe.blackLevel} peak=${recipe.peak} slope=${recipe.slope}")
        append(" size=${size.coerceIn(MIN_SIZE, MAX_SIZE)}")
        append(" target=$target oetf=${oetf.displayName} knee=$highlightKnee")
        append(" axis=${axisCompensation?.name ?: "LOG_CODE"}")
    }

    /** 显示编码（Rec.709 / sRGB OETF，含可选高光软膝）。 */
    fun encodeDisplay(linear: Float, highlightKnee: Float, oetf: LogDisplayOetf): Float {
        val clamped = linear.coerceIn(0f, 1f)
        val softened = softKnee(clamped, safeKnee(highlightKnee))
        val code = when (oetf) {
            LogDisplayOetf.REC709 -> rec709Oetf(softened)
            LogDisplayOetf.SRGB -> TransferCurve.SRGB.linearToLog(softened)
        }
        return code.coerceIn(0f, 1f)
    }

    /**
     * 软膝参数安全化：非有限值（NaN/±Inf，来自外部反序列化）一律退化为 **0 = 硬裁切**。
     *
     * 必须显式处理：Kotlin 的 `Float.coerceIn` 对 NaN **原样返回 NaN**，
     * 会让整条 LUT 变成 NaN 数据（写进 .plut 就是全 0 图形）。这是异常输入的兜底。
     */
    fun safeKnee(highlightKnee: Float): Float =
        if (highlightKnee.isFinite()) {
            highlightKnee.coerceIn(0f, MAX_HIGHLIGHT_KNEE)
        } else {
            0f
        }

    /**
     * 高光软膝：`k` 以下恒等、`k` 处连续、**1.0 精确映射到 1.0**（保证场景白 = 100% 信号）。
     *
     * 采用端点精确的二次形式：`f(x) = k + (1-k)·(1-(1-t)²)`，`t = (x-k)/(1-k)`。
     * （指数形式 `k+(1-k)(1-e^{-t})` 在 x=1 处只到 0.963，会让 LUT 永远到不了纯白，故不采用。）
     * `knee = 0` 时完全恒等 = 纯技术转换（硬裁切，ARRI/Panasonic 官方技术 LUT 同语义）。
     */
    fun softKnee(linear: Float, highlightKnee: Float): Float {
        val knee = safeKnee(highlightKnee)
        val x = linear.coerceIn(0f, 1f)
        if (knee <= 0f || x <= knee) return x
        val span = 1f - knee
        val t = (x - knee) / span
        return knee + span * (1f - (1f - t) * (1f - t))
    }

    /** ITU-R BT.709 OETF。 */
    fun rec709Oetf(linear: Float): Float {
        val value = linear.coerceAtLeast(0f)
        return if (value < 0.018f) {
            4.5f * value
        } else {
            1.099f * value.pow(0.45f) - 0.099f
        }
    }

    /** ITU-R BT.709 OETF 的逆（自查/单测用）。 */
    fun rec709InverseOetf(code: Float): Float {
        val value = code.coerceIn(0f, 1f)
        return if (value < 0.081f) {
            value / 4.5f
        } else {
            ((value + 0.099f) / 1.099f).pow(1f / 0.45f)
        }
    }

    /**
     * 预补偿导入重采样的输入轴（与 `LutConverter.convertCubeToplut` 的轴变换严格互逆）。
     *
     * 工程导入流程对输出格点 `v` 取样本于 `srcCoord = C.linearToLog(SRGB.logToLinear(v))`，
     * 其中 `C` 是导入时传入的 `curve`。要让导入后的 LUT 满足 `out(v) = desired(v)`，
     * 作者侧必须写 `src(x) = desired(SRGB.linearToLog(C.logToLinear(x)))`。
     * `C = SRGB` 时该变换为恒等（可用一条单测锁死）。
     */
    fun compensatedAxis(authoredAxis: Float, importCurve: TransferCurve?): Float {
        if (importCurve == null) return authoredAxis
        val linear = importCurve.logToLinear(authoredAxis.coerceIn(0f, 1f))
        return TransferCurve.SRGB.linearToLog(linear.coerceIn(0f, 1f))
    }

    private fun reaxis(
        data: FloatArray,
        dimension: Int,
        recipe: LogCurveRecipe,
        matrix: FloatArray,
        knee: Float,
        oetf: LogDisplayOetf,
        importCurve: TransferCurve,
    ): FloatArray {
        val step = 1f / (dimension - 1)
        val out = FloatArray(data.size)
        for (bIndex in 0 until dimension) {
            val axisB = compensatedAxis(bIndex * step, importCurve)
            val linearB = displayReferred(recipe, axisB)
            for (gIndex in 0 until dimension) {
                val axisG = compensatedAxis(gIndex * step, importCurve)
                val linearG = displayReferred(recipe, axisG)
                for (rIndex in 0 until dimension) {
                    val axisR = compensatedAxis(rIndex * step, importCurve)
                    val linearR = displayReferred(recipe, axisR)
                    val linear = LogGamutMatrix.apply(matrix, linearR, linearG, linearB)
                    val offset = ((bIndex * dimension + gIndex) * dimension + rIndex) * 3
                    out[offset] = encodeDisplay(linear[0], knee, oetf)
                    out[offset + 1] = encodeDisplay(linear[1], knee, oetf)
                    out[offset + 2] = encodeDisplay(linear[2], knee, oetf)
                }
            }
        }
        return out
    }

    /**
     * Log 代码 → **显示参考场景线性**（烘焙用）。
     *
     * 与 [LogCurveRecipe.decode] 的唯一区别：再除以档位的 [LogCurvePreset.displayWhiteInput]，
     * 使「显示白」锚定到该档位的规范白点（HLG 走 BT.2408 漫反射白 0.2649，其余为场景白 1.0）。
     * 结果**不上界截断**（>1 表示超出显示白的高光），由 [encodeDisplay] 的软膝/裁切处理。
     */
    fun displayReferred(recipe: LogCurveRecipe, logCode: Float): Float {
        val white = recipe.preset.displayWhiteInput
        val scene = recipe.decode(logCode)
        return if (white in 0.001f..0.999f) scene / white else scene
    }

    private fun toUnsignedShort(value: Float): Short =
        (value.coerceIn(0f, 1f) * 65535f + 0.5f).toInt().coerceIn(0, 65535).toShort()

    private fun formatComponent(value: Float): String =
        String.format(java.util.Locale.US, "%.6f", value.coerceIn(0f, 1f))

    /** `PLUT`（小端）。 */
    private const val PLUT_MAGIC = 0x54554C50
    private const val PLUT_VERSION = 3
    private const val PLUT_DATA_TYPE_UINT16 = 1

    /** 头部：magic + version + size + dataType + curveStorageId + colorSpaceOrdinal。 */
    private const val PLUT_HEADER_BYTES = 24

    /** 软膝上限（保留 k..1.0 至少 1% 的过渡区，避免数值退化）。 */
    const val MAX_HIGHLIGHT_KNEE = 0.99f
}
