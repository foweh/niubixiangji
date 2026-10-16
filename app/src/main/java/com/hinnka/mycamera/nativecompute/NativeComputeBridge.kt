package com.hinnka.mycamera.nativecompute

import com.hinnka.mycamera.utils.PLog

/**
 * 计算层 JNI 桥 —— C++/NDK 计算密集模块的统一入口，**每个能力一个入口**。
 *
 * ## 设计要点
 * 1. **复用优先**：直接并入工程既有 native target `my-native-lib`（不新增 .so、不改打包），
 *    与既有 `LongExposureNative` / `FunshotPanoramaNative` 同一套加载与命名约定
 *    （`extern "C" JNIEXPORT ... JNICALL Java_<包>_<类>_<方法>`，不用 RegisterNatives）。
 * 2. **纯 Kotlin 兜底**：库加载失败、符号缺失、ABI 不匹配、参数非法、native 返回失败 —— 任一情况
 *    都只是返回 null/false/-1 让调用方走原来的纯 Kotlin 实现，**绝不崩溃、绝不闪退**
 *    （加载与调用全部包在 runCatching 里，JVM 单测环境同样安全）。
 * 3. **零硬编码上限**：跨 JNI 的参数一律经 [NativeComputeLimits] 做默认值 + 范围 + clamp；
 *    分片/工作分辨率/内存治理仍由 Kotlin 侧（`BgJobRunner` 的 `checkYield/guardMemory/batchSizeFor`）决定，
 *    本桥只接收"这一片要算多少"。
 *
 * ## 接线方式（详见 delivery/patches/nativecompute_patch.md）
 * 三个既有模块的算法入口各自只需要 3~5 行：
 * ```
 * NativeComputeBridge.xxx(...)?.let { return it }   // 命中 native 直接返回
 * // 否则继续走下面的纯 Kotlin 实现
 * ```
 * 建议统一用 `NativeComputeBridge` 的返回值形状（可为 null / Boolean / -1 哨兵）来判断是否命中。
 */
internal object NativeComputeBridge {

    private const val TAG = "NativeComputeBridge"

    /** 哨兵：不可用 / 参数非法（Float 返回值用）。 */
    const val UNAVAILABLE_FLOAT = -1f

    /** 哨兵：不可用 / 参数非法（Int 返回值用），与 C++ 侧 `accumulate::kInvalidInput` 一致。 */
    const val UNAVAILABLE_INT = -1

    private const val ABI_UNPROBED = Int.MIN_VALUE

    /** CONFIG key：JVM 属性总开关（见 [disabledByProperty]）。 */
    private const val PROPERTY_DISABLE = "photon.nativecompute"

    /** IBP 单帧返回数组的长度：`[errSum, weightSum]`。 */
    const val IBP_ACCUMULATE_RESULT_SIZE = 2

    @Volatile private var loadAttempted = false
    @Volatile private var libraryLoaded = false
    @Volatile private var kotlinForced = false
    @Volatile private var fallbackLogged = false
    @Volatile private var abiProbe = ABI_UNPROBED

    /**
     * CONFIG（总开关）：`-Dphoton.nativecompute=off` 可全局关闭计算层，所有入口立即回退纯 Kotlin。
     *
     * 桌面 JVM / 调试运行时可直接用 JVM 参数关闭，便于做"C++ 路径 vs 纯 Kotlin 路径"的对照回归；
     * Android 生产构建默认不设置该属性（即开启）。单测用 [forceKotlinForTest] 更直接。
     */
    private val disabledByProperty: Boolean =
        runCatching { System.getProperty(PROPERTY_DISABLE) == "off" }.getOrDefault(false)

    /** 日志兜底：PLog → android.util.Log，在纯 JVM 单测（无 Android 运行时）里不可用，包一层 runCatching。 */
    private fun warn(message: String, error: Throwable? = null) {
        runCatching { PLog.w(TAG, message, error) }
    }

    /** 降级只记一次日志：算法入口在热路径上，不能每帧都打日志。 */
    private fun logFallbackOnce() {
        if (fallbackLogged) return
        fallbackLogged = true
        warn("native compute layer off, using pure Kotlin implementations")
    }

    /**
     * 懒加载 native 库（幂等）：只在首次调用时尝试一次，失败即永久降级（由之后每次 [available]
     * 判断完成回退），避免每次算法调用都抛一次 UnsatisfiedLinkError 造成滚动丢帧。
     */
    fun ensureLoaded(): Boolean {
        if (kotlinForced || disabledByProperty) {
            logFallbackOnce()
            return false
        }
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    loadAttempted = true
                    libraryLoaded = runCatching {
                        System.loadLibrary(NativeComputeLimits.NATIVE_LIB)
                    }.onFailure { warn("native lib not loaded, falling back to Kotlin", it) }
                        .isSuccess
                    if (!libraryLoaded) logFallbackOnce()
                }
            }
        }
        return libraryLoaded
    }

    /** native ABI 版本探针：库加载成功但符号缺失时返回 [UNAVAILABLE_INT]（等价于"函数不可用"）。 */
    fun abiVersion(): Int {
        if (!ensureLoaded()) return UNAVAILABLE_INT
        var probe = abiProbe
        if (probe == ABI_UNPROBED) {
            probe = runCatching { nativeAbiVersion() }
                .onFailure { warn("nativeAbiVersion missing, falling back to Kotlin", it) }
                .getOrDefault(UNAVAILABLE_INT)
            abiProbe = probe
        }
        return probe
    }

    /**
     * native 计算层是否可用：库已加载 **且** ABI 版本与本 Kotlin 侧一致。
     *
     * 用途：让调用方（或降级日志）一次判断整层可用性；真正的回退判据仍是每个入口的返回值。
     */
    val available: Boolean
        get() = ensureLoaded() && abiVersion() == NativeComputeLimits.ABI_VERSION

    /** 供单测读取的加载状态（无副作用、不触发加载）。 */
    fun isLibraryLoaded(): Boolean = libraryLoaded

    /** 单测钩子：强制走纯 Kotlin 兜底分支（幂等）。 */
    fun forceKotlinForTest() {
        kotlinForced = true
    }

    /** 单测钩子：清除 [forceKotlinForTest] 的影响，恢复默认判定。 */
    fun resetTestHooks() {
        kotlinForced = false
    }

    // =====================================================================
    // MFSR：相位相关亚像素对齐 + shift-and-add + 迭代反投影 + 精修
    // =====================================================================

    /**
     * 相位相关估计 `tgt` 相对 `ref` 的亚像素平移（镜像 `MfsrPhaseCorrelation.estimate`）。
     *
     * @param alignMaxSide 期望的内部工作长边（会被 clamp 到 64..1024；native 侧在此分辨率下做 FFT）
     * @return `floatArrayOf(dx, dy, confidence)`；不可用或退化时返回 null（调用方保持既有纯 Kotlin 路径）
     */
    fun phaseCorrelate(
        ref: FloatArray,
        tgt: FloatArray,
        width: Int,
        height: Int,
        maxShiftPx: Int,
        alignMaxSide: Int,
    ): FloatArray? {
        if (!available) return null
        if (width <= 0 || height <= 0) return null
        if (!NativeComputeLimits.bufferCovers(ref.size, width, height)) return null
        if (!NativeComputeLimits.bufferCovers(tgt.size, width, height)) return null
        val shift = NativeComputeLimits.maxAlignShiftPx(maxShiftPx)
        val side = NativeComputeLimits.alignMaxSide(alignMaxSide)
        return runCatching {
            nativePhaseCorrelate(ref, tgt, width, height, shift, side)
        }.onFailure { warn("phaseCorrelate failed", it) }.getOrNull()
    }

    /**
     * 单帧的 shift-and-add 叠加（镜像 `MfsrHrAccumulator.add`）。
     *
     * @param lr   低分辨率帧（长度 >= lrWidth*lrHeight）
     * @param sum  高分辨率累加分子（**就地**累加，长度 >= hrWidth*hrHeight）
     * @param weight 高分辨率权重（**就地**累加，长度 >= hrWidth*hrHeight）
     * @return 该帧的有效覆盖率（0..1）；不可用或参数非法时返回 [UNAVAILABLE_FLOAT]
     */
    fun shiftAddAccumulate(
        lr: FloatArray,
        sum: FloatArray,
        weight: FloatArray,
        lrWidth: Int,
        lrHeight: Int,
        hrWidth: Int,
        hrHeight: Int,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Float {
        if (!available) return UNAVAILABLE_FLOAT
        if (lrWidth <= 0 || lrHeight <= 0 || hrWidth <= 0 || hrHeight <= 0) return UNAVAILABLE_FLOAT
        if (!NativeComputeLimits.bufferCovers(lr.size, lrWidth, lrHeight)) return UNAVAILABLE_FLOAT
        if (!NativeComputeLimits.bufferCovers(sum.size, hrWidth, hrHeight)) return UNAVAILABLE_FLOAT
        if (!NativeComputeLimits.bufferCovers(weight.size, hrWidth, hrHeight)) return UNAVAILABLE_FLOAT
        if (!scale.isFinite() || scale <= 0f) return UNAVAILABLE_FLOAT
        if (!dx.isFinite() || !dy.isFinite()) return UNAVAILABLE_FLOAT
        return runCatching {
            nativeShiftAddAccumulate(lr, sum, weight, lrWidth, lrHeight, hrWidth, hrHeight, scale, dx, dy)
        }.onFailure { warn("shiftAddAccumulate failed", it) }.getOrDefault(UNAVAILABLE_FLOAT)
    }

    /**
     * shift-and-add 收尾：求均值 + 空洞扩散填充（镜像 `MfsrHrAccumulator.toPlane` 的
     * `resolve` + `fillHoles`，不负责统计与质量阈值）。
     *
     * @param out 长度 >= hrWidth*hrHeight 的输出平面（**就地**写入）
     */
    fun resolveShiftAdd(
        sum: FloatArray,
        weight: FloatArray,
        out: FloatArray,
        hrWidth: Int,
        hrHeight: Int,
        holePasses: Int,
    ): Boolean {
        if (!available) return false
        if (hrWidth <= 0 || hrHeight <= 0) return false
        if (!NativeComputeLimits.bufferCovers(sum.size, hrWidth, hrHeight)) return false
        if (!NativeComputeLimits.bufferCovers(weight.size, hrWidth, hrHeight)) return false
        if (!NativeComputeLimits.bufferCovers(out.size, hrWidth, hrHeight)) return false
        val passes = NativeComputeLimits.holePasses(holePasses)
        return runCatching {
            nativeResolveShiftAdd(sum, weight, out, hrWidth, hrHeight, passes)
        }.onFailure { warn("resolveShiftAdd failed", it) }.getOrDefault(false)
    }

    /**
     * IBP 单帧：正向仿真误差 + 累积到 err/wsum（镜像 `MfsrIterativeBackProjection.refine` 的每帧内层）。
     *
     * 该帧完全在 HR 视口外或平移退化时，native 返回 null（= 本帧无贡献），这不是错误。
     *
     * @return `doubleArrayOf(errSum, weightSum)`；不可用或参数非法时返回 null
     */
    fun ibpAccumulateFrame(
        hr: FloatArray,
        lr: FloatArray,
        err: FloatArray,
        wsum: FloatArray,
        hrWidth: Int,
        hrHeight: Int,
        lrWidth: Int,
        lrHeight: Int,
        dx: Float,
        dy: Float,
        scale: Float,
    ): DoubleArray? {
        if (!available) return null
        if (hrWidth <= 0 || hrHeight <= 0 || lrWidth <= 0 || lrHeight <= 0) return null
        if (!NativeComputeLimits.bufferCovers(hr.size, hrWidth, hrHeight)) return null
        if (!NativeComputeLimits.bufferCovers(lr.size, lrWidth, lrHeight)) return null
        if (!NativeComputeLimits.bufferCovers(err.size, hrWidth, hrHeight)) return null
        if (!NativeComputeLimits.bufferCovers(wsum.size, hrWidth, hrHeight)) return null
        if (!dx.isFinite() || !dy.isFinite()) return null
        if (!scale.isFinite() || scale <= 0f) return null
        return runCatching {
            nativeIbpAccumulateFrame(
                hr, lr, err, wsum, hrWidth, hrHeight, lrWidth, lrHeight, dx, dy, scale,
            )
        }.onFailure { warn("ibpAccumulateFrame failed", it) }.getOrNull()
    }

    /**
     * IBP 单轮：`hr[k] += gain * err[k] / wsum[k]` 并 clamp（镜像 `refine` 的更新步）。
     *
     * @param pixelCount 本次要处理的像素数（<= hr/err/wsum 任一长度）
     * @param gain 步长（经 [NativeComputeLimits.ibpGain] clamp 到 0.05..1.2）
     */
    fun ibpApplyStep(
        hr: FloatArray,
        err: FloatArray,
        wsum: FloatArray,
        pixelCount: Int,
        gain: Float,
    ): Boolean {
        if (!available) return false
        if (pixelCount <= 0) return false
        if (hr.size < pixelCount || err.size < pixelCount || wsum.size < pixelCount) return false
        val clampedGain = NativeComputeLimits.ibpGain(gain)
        return runCatching {
            nativeIbpApplyStep(hr, err, wsum, pixelCount, clampedGain)
        }.onFailure { warn("ibpApplyStep failed", it) }.getOrDefault(false)
    }

    /**
     * MFSR 精修用双边（亮度域，镜像 `MfsrRefiner.bilateral`）。
     *
     * @param radius 空间半径（clamp 到 1..4）
     * @param sigmaRange 值域 σ（非有限 / <= 1e-3 时回落 10f，与 Kotlin 判据一致）
     * @param mix 与原图的混合系数（clamp 到 0..1）
     */
    fun bilateralLuma(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        sigmaRange: Float,
        mix: Float,
    ): Boolean {
        if (!available) return false
        if (!NativeComputeLimits.bufferCovers(src.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(dst.size, width, height)) return false
        val r = NativeComputeLimits.mfsrBilateralRadius(radius)
        val sigma = NativeComputeLimits.mfsrBilateralSigmaRange(sigmaRange)
        val k = NativeComputeLimits.mix(mix)
        return runCatching {
            nativeBilateralLuma(src, dst, width, height, r, sigma, k)
        }.onFailure { warn("bilateralLuma failed", it) }.getOrDefault(false)
    }

    /**
     * MFSR 精修用反锐化掩模（镜像 `MfsrRefiner.unsharp`，σ 经 `kotlin.math.max` 下限保护后传入）。
     *
     * @param amount 强度（clamp 到 0..3）
     */
    fun unsharpLuma(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        amount: Float,
        sigma: Float,
    ): Boolean {
        if (!available) return false
        if (!NativeComputeLimits.bufferCovers(src.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(dst.size, width, height)) return false
        val a = NativeComputeLimits.unsharpAmount(amount)
        if (a <= 0f) return false
        return runCatching {
            nativeUnsharpLuma(src, dst, width, height, a, sigma)
        }.onFailure { warn("unsharpLuma failed", it) }.getOrDefault(false)
    }

    /**
     * 平面 clamp（镜像 `MfsrPlane.clampInPlace` 的有限值分支：非有限值写 lo）。
     *
     * 注意：Kotlin 的逆序 clamp（lo > hi 时先按 hi 再按 lo）由调用方保证不出现，本入口只做
     * `coerceIn(lo, hi)`（C++ 侧同样按 `ClampFloat(value, lo, hi)` 实现，不依赖 std::clamp 前置条件）。
     */
    fun clampPlane(data: FloatArray, count: Int, lo: Float, hi: Float): Boolean {
        if (!available) return false
        if (count <= 0 || count > data.size) return false
        if (!lo.isFinite() || !hi.isFinite()) return false
        return runCatching {
            nativeClampPlane(data, count, lo, hi)
        }.onFailure { warn("clampPlane failed", it) }.getOrDefault(false)
    }

    // =====================================================================
    // 叠加层：MAX / MEAN 累加 + 暗帧扣除
    // =====================================================================

    /**
     * 暗帧扣除 + MAX 累加（融合一次遍历，镜像 `DarkFrameSubtractor` + `MaxAccumulator`）。
     *
     * 说明：MAX 路径此前已由 `LongExposureNative` 下沉到 C++；本入口是"统一计算层"版本，
     * 供需要单一入口的调用方使用，语义与既有实现逐位一致（见 accumulate_core.cpp 头注释）。
     *
     * @param seeded 累加器是否已有基线（首帧传 false）
     * @return 命中坏点掩膜的样本数；不可用或参数非法时返回 [UNAVAILABLE_INT]
     */
    fun accumulateMax(
        frame: ShortArray,
        dark: ShortArray?,
        accum: ShortArray,
        sampleCount: Int,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        seeded: Boolean,
    ): Int {
        if (!available) return UNAVAILABLE_INT
        if (sampleCount <= 0) return UNAVAILABLE_INT
        if (frame.size < sampleCount || accum.size < sampleCount) return UNAVAILABLE_INT
        val darkPlane = dark?.takeIf { it.size >= sampleCount }
        val g = NativeComputeLimits.accumulateGain(gain)
        return runCatching {
            nativeAccumulateMax(
                frame, darkPlane, accum, sampleCount, g, hotThreshold, maskHotPixels, seeded,
            )
        }.onFailure { warn("accumulateMax failed", it) }.getOrDefault(UNAVAILABLE_INT)
    }

    /**
     * MEAN 累加（**求和**，镜像 `MeanAccumulator.addFrame`）：`sum[i] += frame[i]`。
     *
     * 本入口是长曝光模块 MEAN 路径的主接入点（MAX 路径已在 `LongExposureNative` 中）。
     */
    fun accumulateMean(sum: IntArray, frame: ShortArray, sampleCount: Int): Boolean {
        if (!available) return false
        if (sampleCount <= 0) return false
        if (sum.size < sampleCount || frame.size < sampleCount) return false
        return runCatching {
            nativeAccumulateMean(sum, frame, sampleCount)
        }.onFailure { warn("accumulateMean failed", it) }.getOrDefault(false)
    }

    /**
     * 暗帧扣除 + MEAN 累加（融合一次遍历，镜像
     * `DarkFrameSubtractor.subtractInto(scratch)` + `MeanAccumulator.addFrame(scratch)`）。
     *
     * @return 命中坏点掩膜的样本数；不可用或参数非法时返回 [UNAVAILABLE_INT]
     */
    fun subtractMeanAccumulate(
        frame: ShortArray,
        dark: ShortArray?,
        sum: IntArray,
        sampleCount: Int,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
    ): Int {
        if (!available) return UNAVAILABLE_INT
        if (sampleCount <= 0) return UNAVAILABLE_INT
        if (frame.size < sampleCount || sum.size < sampleCount) return UNAVAILABLE_INT
        val darkPlane = dark?.takeIf { it.size >= sampleCount }
        val g = NativeComputeLimits.accumulateGain(gain)
        return runCatching {
            nativeSubtractMeanAccumulate(
                frame, darkPlane, sum, sampleCount, g, hotThreshold, maskHotPixels,
            )
        }.onFailure { warn("subtractMeanAccumulate failed", it) }.getOrDefault(UNAVAILABLE_INT)
    }

    // =====================================================================
    // 经典后处理：CLAHE + 快速双边 + 反锐化掩模
    // =====================================================================

    /**
     * CLAHE 均衡（镜像 `ClaheProcessor.equalize`）。
     *
     * @param tilesX/tilesY 会被 clamp 到 1..32，再由 native 侧收敛到不超过图像尺寸
     * @return 新的均衡亮度数组（长度 width*height）；不可用或参数非法时返回 null
     */
    fun claheEqualize(
        luma: FloatArray,
        width: Int,
        height: Int,
        tilesX: Int,
        tilesY: Int,
        clipLimit: Float,
    ): FloatArray? {
        if (!available) return null
        if (!NativeComputeLimits.bufferCovers(luma.size, width, height)) return null
        val tx = NativeComputeLimits.claheTilesX(tilesX)
        val ty = NativeComputeLimits.claheTilesY(tilesY)
        val clip = NativeComputeLimits.claheClipLimit(clipLimit)
        return runCatching {
            nativeClaheEqualize(luma, width, height, tx, ty, clip)
        }.onFailure { warn("claheEqualize failed", it) }.getOrNull()
    }

    /**
     * 亮度均衡后的逐通道增益缩放（保色，镜像 `ClaheProcessor.equalizeLumaInPlace`）。
     *
     * `r/g/b` 均**就地**改写；`count` 由调用方传 `min(image.count, width*height)`。
     */
    fun applyLumaGain(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        luma: FloatArray,
        equalized: FloatArray,
        count: Int,
        mix: Float,
    ): Boolean {
        if (!available) return false
        if (count <= 0) return false
        if (r.size < count || g.size < count || b.size < count) return false
        if (luma.size < count || equalized.size < count) return false
        val k = NativeComputeLimits.mix(mix)
        return runCatching {
            nativeApplyLumaGain(r, g, b, luma, equalized, count, k)
        }.onFailure { warn("applyLumaGain failed", it) }.getOrDefault(false)
    }

    /**
     * 快速双边滤波的行带（镜像 `BilateralFilterProcessor.filterRows`）。
     *
     * 只写 [rowStart, rowEnd) 行；输出为**滤波后**结果（与 strength 的混合仍由 Kotlin 侧完成，
     * 保持与既有分工一致，避免两条路径的混合语义分叉）。
     */
    fun bilateralRows(
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        dstR: FloatArray,
        dstG: FloatArray,
        dstB: FloatArray,
        width: Int,
        height: Int,
        rowStart: Int,
        rowEnd: Int,
        radius: Int,
        sigmaSpace: Float,
        sigmaColor: Float,
    ): Boolean {
        if (!available) return false
        if (width <= 0 || height <= 0) return false
        if (!NativeComputeLimits.bufferCovers(srcR.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(srcG.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(srcB.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(dstR.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(dstG.size, width, height)) return false
        if (!NativeComputeLimits.bufferCovers(dstB.size, width, height)) return false
        if (rowEnd <= rowStart) return false
        val r = NativeComputeLimits.bilateralRadius(radius)
        val sigmaS = NativeComputeLimits.bilateralSigmaSpace(sigmaSpace)
        val sigmaC = NativeComputeLimits.bilateralSigmaColor(sigmaColor)
        return runCatching {
            nativeBilateralRows(
                srcR, srcG, srcB, dstR, dstG, dstB, width, height,
                rowStart, rowEnd, r, sigmaS, sigmaC,
            )
        }.onFailure { warn("bilateralRows failed", it) }.getOrDefault(false)
    }

    /**
     * 单通道反锐化掩模（镜像 `UnsharpMaskProcessor` 的单通道分支 + `BoxBlur.gaussianApprox`）。
     *
     * @param radius 盒式半径（<= 0 视为退化，本入口直接返回 false 让调用方原样返回）
     */
    fun unsharpChannel(
        channel: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        amount: Float,
        threshold: Int,
    ): Boolean {
        if (!available) return false
        if (!NativeComputeLimits.bufferCovers(channel.size, width, height)) return false
        if (radius <= 0) return false
        val a = NativeComputeLimits.unsharpAmount(amount)
        if (a <= 0f) return false
        val t = NativeComputeLimits.unsharpThreshold(threshold)
        return runCatching {
            nativeUnsharpChannel(channel, width, height, radius, a, t)
        }.onFailure { warn("unsharpChannel failed", it) }.getOrDefault(false)
    }

    // =====================================================================
    // native 声明（与 cpp 侧 JNI 函数逐参对齐，见 selfchecks 对齐表）
    // =====================================================================

    private external fun nativeAbiVersion(): Int

    private external fun nativePhaseCorrelate(
        ref: FloatArray,
        tgt: FloatArray,
        width: Int,
        height: Int,
        maxShiftPx: Int,
        alignMaxSide: Int,
    ): FloatArray?

    private external fun nativeShiftAddAccumulate(
        lr: FloatArray,
        sum: FloatArray,
        weight: FloatArray,
        lrWidth: Int,
        lrHeight: Int,
        hrWidth: Int,
        hrHeight: Int,
        scale: Float,
        dx: Float,
        dy: Float,
    ): Float

    private external fun nativeResolveShiftAdd(
        sum: FloatArray,
        weight: FloatArray,
        out: FloatArray,
        hrWidth: Int,
        hrHeight: Int,
        holePasses: Int,
    ): Boolean

    private external fun nativeIbpAccumulateFrame(
        hr: FloatArray,
        lr: FloatArray,
        err: FloatArray,
        wsum: FloatArray,
        hrWidth: Int,
        hrHeight: Int,
        lrWidth: Int,
        lrHeight: Int,
        dx: Float,
        dy: Float,
        scale: Float,
    ): DoubleArray?

    private external fun nativeIbpApplyStep(
        hr: FloatArray,
        err: FloatArray,
        wsum: FloatArray,
        pixelCount: Int,
        gain: Float,
    ): Boolean

    private external fun nativeBilateralLuma(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        sigmaRange: Float,
        mix: Float,
    ): Boolean

    private external fun nativeUnsharpLuma(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        amount: Float,
        sigma: Float,
    ): Boolean

    private external fun nativeClampPlane(data: FloatArray, count: Int, lo: Float, hi: Float): Boolean

    private external fun nativeAccumulateMax(
        frame: ShortArray,
        dark: ShortArray?,
        accum: ShortArray,
        sampleCount: Int,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        seeded: Boolean,
    ): Int

    private external fun nativeAccumulateMean(
        sum: IntArray,
        frame: ShortArray,
        sampleCount: Int,
    ): Boolean

    private external fun nativeSubtractMeanAccumulate(
        frame: ShortArray,
        dark: ShortArray?,
        sum: IntArray,
        sampleCount: Int,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
    ): Int

    private external fun nativeClaheEqualize(
        luma: FloatArray,
        width: Int,
        height: Int,
        tilesX: Int,
        tilesY: Int,
        clipLimit: Float,
    ): FloatArray?

    private external fun nativeApplyLumaGain(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        luma: FloatArray,
        equalized: FloatArray,
        count: Int,
        mix: Float,
    ): Boolean

    private external fun nativeBilateralRows(
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        dstR: FloatArray,
        dstG: FloatArray,
        dstB: FloatArray,
        width: Int,
        height: Int,
        rowStart: Int,
        rowEnd: Int,
        radius: Int,
        sigmaSpace: Float,
        sigmaColor: Float,
    ): Boolean

    private external fun nativeUnsharpChannel(
        channel: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        amount: Float,
        threshold: Int,
    ): Boolean
}
