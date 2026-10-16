package com.hinnka.mycamera.focusstack

import com.hinnka.mycamera.utils.PLog

/**
 * 焦点堆叠的 JNI 加速桥（PATTERN_SPEC §10）。
 *
 * 设计要点（与 `nativecompute/NativeComputeBridge`、`longexposure/LongExposureNative` 同一约定）：
 * 1. **纯 Kotlin 兜底**：native 库加载失败 / 符号缺失 / 参数非法 / native 返回失败 —— 任一情况
 *    都返回 `null` 或 `false`，调用方无痛回退 [FocusClarityMap] 的纯 Kotlin 实现，
 *    **绝不崩溃、绝不闪退**（加载与调用全部包在 runCatching 里，JVM 单测环境同样安全）。
 * 2. 只把**最热的两段**下沉 C++：`逐帧清晰度图`（拉普拉斯/梯度 + 分离式模糊）
 *    与 `软掩模`（归一化 + 高斯平滑 + 一致性滤波）。
 * 3. 工作分辨率 / 批大小由 Kotlin 侧（[FocusStackProcessor] + `BgJobRunner`）决定，
 *    native 侧只接收「这一片要算多少」，不做内存分配决策（§10.6）。
 *
 * C++ 源文件：`app/src/main/cpp/focusstack/focusstack_core.{h,cpp}` 与 `focusstack_jni.cpp`
 * （新增文件；`CMakeLists.txt` 源清单改动走补丁 `delivery/patches/focusstack_patch.md`）。
 * JNI 签名与 Kotlin 声明逐一对齐，对齐表见 `delivery/selfchecks/focusstack.md`。
 */
internal object FocusStackNative {

    private const val TAG = "FocusStackNative"
    private const val NATIVE_LIB = "my-native-lib"

    /** 与 C++ 侧 `fs_metric` 枚举顺序严格一致（LAPLACIAN_VARIANCE=0, GRADIENT_ENERGY=1）。 */
    private const val METRIC_LAPLACIAN = 0
    private const val METRIC_GRADIENT = 1

    @Volatile
    private var libraryLoaded: Boolean = false

    @Volatile
    private var loadAttempted: Boolean = false

    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    libraryLoaded = runCatching { System.loadLibrary(NATIVE_LIB) }
                        .onFailure { warn("native lib unavailable, falling back to Kotlin: ${it.message}") }
                        .isSuccess
                    loadAttempted = true
                }
            }
        }
        return libraryLoaded
    }

    /** 日志兜底：纯 JVM 单测无 Android 运行时，日志失败不得影响算法。 */
    private fun warn(message: String) {
        runCatching { PLog.w(TAG, message) }
    }

    val isAvailable: Boolean get() = ensureLoaded()

    // ------------------------------------------------------------------ JNI 声明
    // 名称必须与 C++ 侧 Java_com_hinnka_mycamera_focusstack_FocusStackNative_* 完全一致。
    // 返回 false 表示「不可用 / 参数非法」，调用方回退纯 Kotlin。

    private external fun nativeComputeClarity(
        luma: FloatArray,
        out: FloatArray,
        width: Int,
        height: Int,
        metric: Int,
        radius: Int,
    ): Boolean

    private external fun nativeSoftMask(
        clarityFlat: FloatArray,
        maskOut: FloatArray,
        frameCount: Int,
        width: Int,
        height: Int,
        exponent: Float,
        smoothRadius: Int,
        consistency: Int,
    ): Boolean

    // ------------------------------------------------------------------ 分发入口

    /**
     * 逐帧清晰度图（native 加速；不可用时返回 null 由调用方走 [FocusClarityMap]）。
     *
     * @return 长度 `width*height` 的清晰度图；不可用/失败返回 null。
     */
    fun computeClarity(
        luma: FloatArray,
        width: Int,
        height: Int,
        metric: ClarityMetric,
        radius: Int,
    ): FloatArray? {
        val size = width * height
        if (size <= 0 || luma.size < size) return null
        if (!isAvailable) return null
        val out = FloatArray(size)
        val ordinal = when (metric) {
            ClarityMetric.LAPLACIAN_VARIANCE -> METRIC_LAPLACIAN
            ClarityMetric.GRADIENT_ENERGY -> METRIC_GRADIENT
        }
        val ok = runCatching {
            nativeComputeClarity(luma, out, width, height, ordinal, radius)
        }.getOrElse { error ->
            warn("native clarity failed, falling back: ${error.message}")
            false
        }
        return if (ok) out else null
    }

    /**
     * 软掩模（native 加速；不可用时返回 null 由调用方走 [FocusClarityMap.softMask]）。
     *
     * 入参/出参用**扁平**布局 `[frame][pixel]`（frame-major），减少 JNI 对象数组开销。
     *
     * @return 与 [clarities] 等长的掩模列表；不可用/失败返回 null。
     */
    fun softMask(
        clarities: List<FloatArray>,
        width: Int,
        height: Int,
        exponent: Float,
        smoothRadius: Int,
        consistency: Int,
    ): List<FloatArray>? {
        val n = clarities.size
        val size = width * height
        if (n <= 0 || size <= 0) return null
        if (!isAvailable) return null
        val flat = FloatArray(n * size)
        for (k in 0 until n) {
            val src = clarities[k]
            val limit = minOf(size, src.size)
            src.copyInto(flat, k * size, 0, limit)
        }
        val maskOut = FloatArray(n * size)
        val ok = runCatching {
            nativeSoftMask(flat, maskOut, n, width, height, exponent, smoothRadius, consistency)
        }.getOrElse { error ->
            warn("native soft mask failed, falling back: ${error.message}")
            false
        }
        if (!ok) return null
        return List(n) { k -> maskOut.copyOfRange(k * size, (k + 1) * size) }
    }

    /** 仅测试/诊断用：强制走 Kotlin 路径。 */
    internal fun forceKotlinForTest() {
        loadAttempted = true
        libraryLoaded = false
    }
}
