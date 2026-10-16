package com.hinnka.mycamera.longexposure

import com.hinnka.mycamera.utils.PLog
import kotlin.math.roundToInt

/**
 * 长曝光重计算的 JNI 加速桥（PATTERN_SPEC §10）。
 *
 * 设计要点：
 *  1. **纯 Kotlin 兜底**：native 库加载失败或符号缺失时，自动回退到
 *     [DarkFrameSubtractor] / [SuperMoonToneMapper] 的 Kotlin 实现，**不崩溃、不闪退**；
 *  2. 只把**最热的两段**下沉到 C++：`暗帧减法 + MAX 增量累加`（逐帧 O(N) 主循环）
 *     与 `月亮局部色调映射`（肩部曲线 + 盒模糊 + 逐像素比例缩放）；
 *  3. 工作分辨率 / 批大小由 Kotlin 侧（[LongExposureJobRunner]）决定并传入，
 *     native 侧不做内存分配决策（§10.6）。
 *
 * C++ 源文件：`app/src/main/cpp/longexposure/light_painting_core.{h,cpp}` 与
 * `longexposure_jni.cpp`（新增文件；CMakeLists 源文件清单改动走补丁）。
 *
 * JNI 签名对齐表见 `delivery/selfchecks/longexposure.md`。
 */
internal object LongExposureNative {

    private const val TAG = "LongExposureNative"
    private const val NATIVE_LIB = "my-native-lib"

    /** native 是否可用（懒加载一次；失败即永久回退 Kotlin）。 */
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

    /**
     * 日志兜底：PLog → android.util.Log，在纯 JVM 单测（无 Android 运行时）里不可用，
     * 包一层 runCatching 保证**日志失败不影响算法**。
     */
    private fun warn(message: String) {
        runCatching { PLog.w(TAG, message) }
    }

    val isAvailable: Boolean get() = ensureLoaded()

    // ------------------------------------------------------------------ JNI 声明
    // 名称必须与 C++ 侧 Java_com_hinnka_mycamera_longexposure_LongExposureNative_* 完全一致。
    // 返回：BAD_PIXEL_COUNT（>=0）表示成功；-1 表示 native 侧校验失败（调用方回退）。

    private external fun nativeSubtractMaxAccumulate(
        frame: ShortArray,
        dark: ShortArray?,
        accum: ShortArray,
        sampleCount: Int,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        seeded: Boolean,
    ): Int

    private external fun nativeToneMap(
        samples: ShortArray,
        width: Int,
        height: Int,
        highlightThreshold: Float,
        highlightStrength: Float,
        shadowLift: Float,
        gamma: Float,
        localRadius: Int,
        localAmount: Float,
    ): Boolean

    // ------------------------------------------------------------------ 分发入口

    /**
     * 暗帧减法 + MAX 增量累加（融合为一次遍历）。
     *
     * 语义与 [DarkFrameSubtractor.subtractInto] + [MaxAccumulator.addFrame] 完全一致：
     *  - `out[i] = max(0, frame[i] - round(dark[i] * gain))`；
     *  - 坏点掩膜开启且 `dark[i] >= hotThreshold` → `out[i] = 0`；
     *  - `seeded == false`（首帧）→ 直接写入；否则与 [accum] 逐样本取最大。
     *
     * @return 本次坏点样本数；native 路径校验失败时回退 Kotlin，仍返回真实坏点数。
     */
    fun subtractMaxAccumulate(
        frame: ShortArray,
        dark: ShortArray?,
        accum: ShortArray,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        seeded: Boolean,
    ): Int {
        if (frame.size != accum.size) {
            throw IllegalArgumentException("frame size ${frame.size} != accum size ${accum.size}")
        }
        val effectiveDark = dark?.takeIf { it.size == frame.size }
        if (isAvailable) {
            val result = runCatching {
                nativeSubtractMaxAccumulate(
                    frame = frame,
                    dark = effectiveDark,
                    accum = accum,
                    sampleCount = frame.size,
                    gain = gain,
                    hotThreshold = hotThreshold,
                    maskHotPixels = maskHotPixels,
                    seeded = seeded,
                )
            }.getOrElse { error ->
                warn("native subtract+max failed, falling back: ${error.message}")
                -1
            }
            if (result >= 0) return result
        }
        return kotlinSubtractMax(frame, effectiveDark, accum, gain, hotThreshold, maskHotPixels, seeded)
    }

    /** 纯 Kotlin 兜底实现（同时作为 native 的参考实现，保证两条路径行为一致）。 */
    fun kotlinSubtractMax(
        frame: ShortArray,
        dark: ShortArray?,
        accum: ShortArray,
        gain: Float,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        seeded: Boolean,
    ): Int {
        val g = if (gain.isFinite()) gain.coerceIn(DarkFrameConfig.GAIN_MIN, DarkFrameConfig.GAIN_MAX) else 1f
        val mask = maskHotPixels && hotThreshold > 0 && dark != null
        var hotPixels = 0
        for (i in frame.indices) {
            val darkSample = dark?.get(i)?.toInt() ?: 0
            val value: Int
            if (mask && darkSample >= hotThreshold) {
                value = 0
                hotPixels++
            } else {
                value = (frame[i] - (darkSample * g).roundToInt())
                    .coerceIn(0, LinearRgbPlane.SAMPLE_MAX)
            }
            if (!seeded) {
                accum[i] = value.toShort()
            } else if (value > accum[i]) {
                accum[i] = value.toShort()
            }
        }
        return hotPixels
    }

    /**
     * 月亮局部色调映射（高光保留）。
     *
     * @return 结果数组；native 不可用/失败时返回 null（调用方走
     *   [SuperMoonToneMapper.toneMap] 的 Kotlin 实现）。
     */
    fun toneMap(samples: ShortArray, width: Int, height: Int, config: LocalToneMapConfig): ShortArray? {
        if (!config.enabled) return samples.copyOf()
        if (!isAvailable) return null
        val ok = runCatching {
            nativeToneMap(
                samples = samples,
                width = width,
                height = height,
                highlightThreshold = config.highlightThreshold,
                highlightStrength = config.highlightCompression,
                shadowLift = config.shadowLift,
                gamma = config.gamma,
                localRadius = config.localRadius,
                localAmount = config.localAmount,
            )
        }.getOrElse { error ->
            warn("native tone map failed, falling back: ${error.message}")
            false
        }
        // native 侧就地写入 samples（in-place），成功后直接返回。
        return if (ok) samples else null
    }

    /** 仅测试/诊断用：强制走 Kotlin 路径。 */
    internal fun forceKotlinForTest() {
        loadAttempted = true
        libraryLoaded = false
    }
}
