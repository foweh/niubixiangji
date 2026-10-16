package com.hinnka.mycamera.nativecompute

/**
 * 计算层（nativecompute）参数层：**纯 Kotlin、零 Android 依赖**，因此可在纯 JVM 单测里直接跑。
 *
 * 存在理由（PATTERN_SPEC §1.6 开放度 + §10.3 JNI 契约）：
 *  1. 跨 JNI 的每一个数值参数都在这里先做「默认值 + 取值范围 + 越界 clamp」，
 *     误设（NaN / Inf / 越界）绝不崩溃，只是被修正；
 *  2. 这些取值必须与 C++ 侧的校验范围**一致**（双侧都做 clamp：Kotlin 侧保证语义可预期，
 *     C++ 侧保证即使被其它调用方直接使用也不会越界写内存）；
 *  3. clamp 规则集中一处，便于自检清单逐条核对（见 `delivery/selfchecks/nativecompute.md`）。
 *
 * 约定：与 C++ 侧同名的范围常量在 KDoc 中标注对应关系，改动必须两侧同步。
 */
internal object NativeComputeLimits {

    /** JNI ABI 版本：与 `nativecompute_common.h` 的 `kAbiVersion` 必须一致。 */
    const val ABI_VERSION = 1

    /** 存放 native 实现的动态库名（沿用工程既有 target `my-native-lib`）。 */
    const val NATIVE_LIB = "my-native-lib"

    // ---------------------------------------------------------------- MFSR

    /** 相位相关内部工作长边上限范围（C++ 侧 kAlignMaxSideMin/Max）。 */
    const val ALIGN_MAX_SIDE_MIN = 64
    const val ALIGN_MAX_SIDE_MAX = 1024

    /** 允许的最大平移搜索范围（像素）。 */
    const val MAX_ALIGN_SHIFT_MIN = 1
    const val MAX_ALIGN_SHIFT_MAX = 4096

    /** 空洞扩散轮数上限（C++ 侧 kHolePassesMax）。 */
    const val HOLE_PASSES_MIN = 0
    const val HOLE_PASSES_MAX = 64

    /** IBP 步长范围（与 Kotlin MfsrIterativeBackProjection.refine 的 gain clamp 一致）。 */
    const val IBP_GAIN_MIN = 0.05f
    const val IBP_GAIN_MAX = 1.2f
    const val IBP_GAIN_DEFAULT = 0.85f

    /** MFSR 精修双边的半径上限（MfsrConfig.DENOISE_RADIUS_MAX）。 */
    const val MFSR_BILATERAL_RADIUS_MIN = 1
    const val MFSR_BILATERAL_RADIUS_MAX = 4

    /** MFSR 精修双边值域 σ 的下限与默认（非有限值回落 10f，与 Kotlin 侧一致）。 */
    const val MFSR_BILATERAL_SIGMA_MIN = 1e-3f
    const val MFSR_BILATERAL_SIGMA_DEFAULT = 10f

    // ------------------------------------------------------- 经典后处理

    /** CLAHE tile 网格范围（ClaheConfig.TILES_MIN/TILES_MAX）。 */
    const val CLAHE_TILES_MIN = 1
    const val CLAHE_TILES_MAX = 32

    /** CLAHE 对比度限制倍数范围（ClaheConfig.CLIP_LIMIT_MIN/MAX）。 */
    const val CLAHE_CLIP_LIMIT_MIN = 0.1f
    const val CLAHE_CLIP_LIMIT_MAX = 10f

    /** 快速双边半径 / σ 范围（BilateralConfig）。 */
    const val BILATERAL_RADIUS_MIN = 1
    const val BILATERAL_RADIUS_MAX = 12
    const val BILATERAL_SIGMA_SPACE_MIN = 1e-3f
    const val BILATERAL_SIGMA_SPACE_MAX = 32f
    const val BILATERAL_SIGMA_SPACE_DEFAULT = 8f
    const val BILATERAL_SIGMA_COLOR_MIN = 1e-3f
    const val BILATERAL_SIGMA_COLOR_MAX = 1f
    const val BILATERAL_SIGMA_COLOR_DEFAULT = 0.12f

    /** 反锐化强度 / 阈值范围（UnsharpMaskConfig.AMOUNT_x / THRESHOLD_x）。 */
    const val UNSHARP_AMOUNT_MIN = 0f
    const val UNSHARP_AMOUNT_MAX = 3f
    const val UNSHARP_THRESHOLD_MIN = 0
    const val UNSHARP_THRESHOLD_MAX = 64

    // ----------------------------------------------------------- 叠加层

    /** 暗帧扣除增益范围（DarkFrameConfig.GAIN_MIN/MAX）。 */
    const val ACCUMULATE_GAIN_MIN = 0f
    const val ACCUMULATE_GAIN_MAX = 2f
    const val ACCUMULATE_GAIN_DEFAULT = 1f

    /** 混合系数范围（0 = 原图，1 = 完全处理）。 */
    const val MIX_MIN = 0f
    const val MIX_MAX = 1f

    // ------------------------------------------------------------- clamp

    /** 相位相关内部工作长边：越界收敛到 [64, 1024]。 */
    fun alignMaxSide(value: Int): Int = value.coerceIn(ALIGN_MAX_SIDE_MIN, ALIGN_MAX_SIDE_MAX)

    /** 最大平移搜索范围（像素）：越界收敛到 [1, 4096]。 */
    fun maxAlignShiftPx(value: Int): Int = value.coerceIn(MAX_ALIGN_SHIFT_MIN, MAX_ALIGN_SHIFT_MAX)

    /** 空洞扩散轮数：越界收敛到 [0, 64]。 */
    fun holePasses(value: Int): Int = value.coerceIn(HOLE_PASSES_MIN, HOLE_PASSES_MAX)

    /** IBP 步长：非有限值回落默认 0.85，其余收敛到 [0.05, 1.2]。 */
    fun ibpGain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(IBP_GAIN_MIN, IBP_GAIN_MAX) else IBP_GAIN_DEFAULT

    /** MFSR 精修双边半径：收敛到 [1, 4]。 */
    fun mfsrBilateralRadius(value: Int): Int =
        value.coerceIn(MFSR_BILATERAL_RADIUS_MIN, MFSR_BILATERAL_RADIUS_MAX)

    /** MFSR 精修双边值域 σ：非有限/过小回落 10f（与 MfsrRefiner 的判据一致）。 */
    fun mfsrBilateralSigmaRange(value: Float): Float =
        if (value.isFinite() && value > MFSR_BILATERAL_SIGMA_MIN) value else MFSR_BILATERAL_SIGMA_DEFAULT

    /** 混合系数：非有限值回落 0（= 不混合），其余收敛到 [0, 1]。 */
    fun mix(value: Float): Float =
        if (value.isFinite()) value.coerceIn(MIX_MIN, MIX_MAX) else MIX_MIN

    /** CLAHE tile 列数：收敛到 [1, 32]。 */
    fun claheTilesX(value: Int): Int = value.coerceIn(CLAHE_TILES_MIN, CLAHE_TILES_MAX)

    /** CLAHE tile 行数：收敛到 [1, 32]。 */
    fun claheTilesY(value: Int): Int = value.coerceIn(CLAHE_TILES_MIN, CLAHE_TILES_MAX)

    /**
     * CLAHE 对比度限制倍数：非有限值回落 0（C++ 侧同样按 0 处理），
     * 其余**先**收敛到 [0.1, 10]（与 ClaheConfig.clamp 一致）。
     */
    fun claheClipLimit(value: Float): Float =
        if (value.isFinite()) value.coerceIn(CLAHE_CLIP_LIMIT_MIN, CLAHE_CLIP_LIMIT_MAX) else 0f

    /** 快速双边半径：收敛到 [1, 12]。 */
    fun bilateralRadius(value: Int): Int =
        value.coerceIn(BILATERAL_RADIUS_MIN, BILATERAL_RADIUS_MAX)

    /** 快速双边空间 σ：非有限回落 8f，其余收敛到 [1e-3, 32]。 */
    fun bilateralSigmaSpace(value: Float): Float =
        if (value.isFinite()) {
            value.coerceIn(BILATERAL_SIGMA_SPACE_MIN, BILATERAL_SIGMA_SPACE_MAX)
        } else {
            BILATERAL_SIGMA_SPACE_DEFAULT
        }

    /** 快速双边值域 σ：非有限回落 0.12f，其余收敛到 [1e-3, 1]。 */
    fun bilateralSigmaColor(value: Float): Float =
        if (value.isFinite()) {
            value.coerceIn(BILATERAL_SIGMA_COLOR_MIN, BILATERAL_SIGMA_COLOR_MAX)
        } else {
            BILATERAL_SIGMA_COLOR_DEFAULT
        }

    /** 反锐化强度：非有限回落 0（= 恒等），其余收敛到 [0, 3]。 */
    fun unsharpAmount(value: Float): Float =
        if (value.isFinite()) value.coerceIn(UNSHARP_AMOUNT_MIN, UNSHARP_AMOUNT_MAX) else 0f

    /** 反锐化弱边抑制阈值：收敛到 [0, 64]，<= 0 关闭门控。 */
    fun unsharpThreshold(value: Int): Int =
        value.coerceIn(UNSHARP_THRESHOLD_MIN, UNSHARP_THRESHOLD_MAX)

    /** 暗帧扣除增益：非有限回落 1f，其余收敛到 [0, 2]。 */
    fun accumulateGain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(ACCUMULATE_GAIN_MIN, ACCUMULATE_GAIN_MAX) else ACCUMULATE_GAIN_DEFAULT

    /**
     * 工作缓冲长度校验：native 侧要求长度 >= 像素数。
     *
     * @return true = 长度足够，可以过 JNI；false = 调用方应直接回退纯 Kotlin 实现
     */
    fun bufferCovers(bufferSize: Int, width: Int, height: Int, planes: Int = 1): Boolean {
        if (width <= 0 || height <= 0 || planes <= 0) return false
        val needed = width.toLong() * height.toLong() * planes.toLong()
        return bufferSize.toLong() >= needed
    }
}
