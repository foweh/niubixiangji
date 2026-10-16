package com.hinnka.mycamera.funshot

import com.hinnka.mycamera.utils.PLog

/**
 * 全景算法的 JNI 加速桥（PATTERN_SPEC §10）。
 *
 * 设计要点：
 *  1. **纯 Kotlin 兜底**：native 库加载失败或函数缺失时自动回退到
 *     [FunHarrisCorner] / [FunBriefDescriptor] / [FunFeatureMatcher] / [FunRansacHomography]
 *     的 Kotlin 实现，**不崩溃、不闪退**；
 *  2. 只把**最热的四段**下沉到 C++：角点检测、描述子、暴力匹配、RANSAC 单应；
 *     融合（warp/blend）仍在 Kotlin 侧统一实现，避免 JNI 搬运大位图缓冲（已在文档说明）；
 *  3. 工作分辨率 / 迭代次数 / 阈值由 Kotlin 侧（[FunshotPanoramaTask]）决定并传入，
 *     native 侧不做内存与调度决策（§10.6）。
 *
 * C++ 源文件：`app/src/main/cpp/funshot/funshot_panorama.{h,cpp}`（新增；CMakeLists 源文件清单改动走补丁）。
 * JNI 签名逐一对齐表见 `delivery/selfchecks/funshot.md`。
 */
internal object FunshotPanoramaNative {

    private const val TAG = "FunshotPanoramaNative"
    private const val NATIVE_LIB = "my-native-lib"

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var libraryLoaded = false

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

    /** 仅测试/诊断用：强制走 Kotlin 路径。 */
    internal fun forceKotlinForTest() {
        loadAttempted = true
        libraryLoaded = false
    }

    // ------------------------------------------------------------------ JNI 声明
    // 名称必须与 C++ 侧 Java_com_hinnka_mycamera_funshot_FunshotPanoramaNative_* 完全一致。
    // 全部返回可空：null 表示 native 不可用 / 校验失败，调用方回退 Kotlin。
    //
    // 注意：这里**不能**用 `internal external fun`——Kotlin 会对 `internal` 成员做名字修饰
    // （编译为 `nativeHarrisCorners$<module>`），JNI 符号名将不再匹配。
    // 与工程既有范式一致（`stabilization.MgcEisNativeBridge`、`longexposure.LongExposureNative`
    // 均为「internal object + public external fun」）。

    /** `[x, y, score] × n`；无角点返回空数组；失败返回 null。 */
    external fun nativeHarrisCorners(
        gray: FloatArray,
        width: Int,
        height: Int,
        maxCorners: Int,
        quality: Float,
        minDistance: Int,
    ): FloatArray?

    /** `keyPoints = [x, y] × m`，返回 `m × words` 的行主序 Long 描述子（words = bits/64）。 */
    external fun nativeBriefDescribe(
        gray: FloatArray,
        width: Int,
        height: Int,
        keyPoints: FloatArray,
        bits: Int,
        seed: Long,
    ): LongArray?

    /** 返回 `[indexA, indexB, hammingDistance] × k`；失败返回 null。 */
    external fun nativeMatchFeatures(
        descA: LongArray,
        countA: Int,
        descB: LongArray,
        countB: Int,
        words: Int,
        ratio: Float,
        maxMatches: Int,
        maxDistance: Int,
    ): IntArray?

    /**
     * 返回 `[inlierCount, h0..h8, flag0..flag(count-1)]`：
     * 首位为内点数（0 表示失败），其后 9 个为行主序单应矩阵，其余为逐点内点标记（1/0）。
     */
    external fun nativeEstimateHomography(
        src: DoubleArray,
        dst: DoubleArray,
        count: Int,
        iterations: Int,
        threshold: Double,
        minInliers: Int,
        minInlierRatio: Double,
        seed: Long,
    ): DoubleArray?
}

/**
 * 全景算法统一入口：native 优先、Kotlin 兜底（两条路径行为一致）。
 *
 * 调用方（[FunshotPanoramaSession]）只依赖本对象，因此 native 有/无都不影响功能与产物。
 */
object FunPanoramaBackend {

    /** 角点检测：native → Kotlin 兜底。 */
    fun detectCorners(
        gray: FunGrayImage,
        maxCorners: Int,
        quality: Float,
        minDistance: Int,
    ): List<FunKeyPoint> {
        if (FunshotPanoramaNative.isAvailable) {
            val raw = runCatching {
                FunshotPanoramaNative.nativeDetect(gray.pixels, gray.width, gray.height, maxCorners, quality, minDistance)
            }.getOrNull()
            if (raw != null) return raw
        }
        return FunHarrisCorner.detect(gray, maxCorners, quality, minDistance)
    }

    /** 描述子：native → Kotlin 兜底（返回长度与 [keyPoints] 一致）。 */
    fun describe(
        gray: FunGrayImage,
        keyPoints: List<FunKeyPoint>,
        bits: Int,
        seed: Long,
    ): Array<LongArray> {
        if (keyPoints.isEmpty()) return emptyArray()
        if (FunshotPanoramaNative.isAvailable) {
            val raw = runCatching { FunshotPanoramaNative.nativeDescribe(gray, keyPoints, bits, seed) }.getOrNull()
            if (raw != null && raw.size == keyPoints.size) return raw
        }
        return FunBriefDescriptor.describe(gray, keyPoints, bits, seed)
    }

    /** 特征匹配：native → Kotlin 兜底。 */
    fun match(
        descA: Array<LongArray>,
        descB: Array<LongArray>,
        ratio: Float,
        maxMatches: Int,
        maxDistanceFraction: Float = 0.35f,
    ): List<FunMatch> {
        if (descA.isEmpty() || descB.isEmpty()) return emptyList()
        if (FunshotPanoramaNative.isAvailable) {
            val raw = runCatching {
                FunshotPanoramaNative.nativeMatch(descA, descB, ratio, maxMatches, maxDistanceFraction)
            }.getOrNull()
            if (raw != null) return raw
        }
        return FunFeatureMatcher.match(descA, descB, ratio, maxMatches, maxDistanceFraction)
    }

    /** 单应估计：native → Kotlin 兜底（失败语义一致，均不抛异常）。 */
    fun estimateHomography(
        src: List<FunPointF>,
        dst: List<FunPointF>,
        iterations: Int,
        thresholdPx: Double,
        minInliers: Int,
        minInlierRatio: Double,
        seed: Long,
        checkpoint: FunshotCheckpoint? = null,
    ): FunHomographyResult {
        if (FunshotPanoramaNative.isAvailable && src.size == dst.size && src.size >= 4) {
            val raw = runCatching {
                FunshotPanoramaNative.nativeEstimate(src, dst, iterations, thresholdPx, minInliers, minInlierRatio, seed)
            }.getOrNull()
            if (raw != null) return raw
        }
        return FunRansacHomography.estimate(
            src = src,
            dst = dst,
            iterations = iterations,
            thresholdPx = thresholdPx,
            minInliers = minInliers,
            minInlierRatio = minInlierRatio,
            seed = seed,
            checkpoint = checkpoint,
        )
    }
}

// ---------------------------------------------------------------------- native 结果 → 领域类型
// 放在独立 internal 扩展里，保证 [FunPanoramaBackend] 读起来只有「尝试 native、否则兜底」两层。

private fun FunshotPanoramaNative.nativeDetect(
    gray: FloatArray,
    width: Int,
    height: Int,
    maxCorners: Int,
    quality: Float,
    minDistance: Int,
): List<FunKeyPoint>? {
    val raw = nativeHarrisCorners(gray, width, height, maxCorners, quality, minDistance) ?: return null
    if (raw.size % 3 != 0) return null
    val out = ArrayList<FunKeyPoint>(raw.size / 3)
    var i = 0
    while (i < raw.size) {
        out += FunKeyPoint(raw[i], raw[i + 1], raw[i + 2])
        i += 3
    }
    return out
}

private fun FunshotPanoramaNative.nativeDescribe(
    gray: FunGrayImage,
    keyPoints: List<FunKeyPoint>,
    bits: Int,
    seed: Long,
): Array<LongArray>? {
    val words = bits.coerceIn(64, 512) / 64
    val flatPoints = FloatArray(keyPoints.size * 2)
    for (i in keyPoints.indices) {
        flatPoints[i * 2] = keyPoints[i].x
        flatPoints[i * 2 + 1] = keyPoints[i].y
    }
    val raw = nativeBriefDescribe(gray.pixels, gray.width, gray.height, flatPoints, bits, seed) ?: return null
    if (raw.size != keyPoints.size * words) return null
    return Array(keyPoints.size) { index -> raw.copyOfRange(index * words, index * words + words) }
}

private fun FunshotPanoramaNative.nativeMatch(
    descA: Array<LongArray>,
    descB: Array<LongArray>,
    ratio: Float,
    maxMatches: Int,
    maxDistanceFraction: Float,
): List<FunMatch>? {
    val words = minOf(descA[0].size, descB[0].size)
    if (words <= 0) return null
    val bits = words * 64
    val distanceCap = (bits * maxDistanceFraction.coerceIn(0.01f, 1f)).toInt()
    val flatA = LongArray(descA.size * words)
    val flatB = LongArray(descB.size * words)
    for (i in descA.indices) System.arraycopy(descA[i], 0, flatA, i * words, words)
    for (i in descB.indices) System.arraycopy(descB[i], 0, flatB, i * words, words)
    val raw = nativeMatchFeatures(
        flatA, descA.size, flatB, descB.size, words, ratio, maxMatches.coerceAtLeast(1), distanceCap,
    ) ?: return null
    if (raw.size % 3 != 0) return null
    val out = ArrayList<FunMatch>(raw.size / 3)
    var i = 0
    while (i < raw.size) {
        out += FunMatch(raw[i], raw[i + 1], raw[i + 2])
        i += 3
    }
    return out
}

private fun FunshotPanoramaNative.nativeEstimate(
    src: List<FunPointF>,
    dst: List<FunPointF>,
    iterations: Int,
    thresholdPx: Double,
    minInliers: Int,
    minInlierRatio: Double,
    seed: Long,
): FunHomographyResult? {
    val count = src.size
    val flatSrc = DoubleArray(count * 2)
    val flatDst = DoubleArray(count * 2)
    for (i in 0 until count) {
        flatSrc[i * 2] = src[i].x
        flatSrc[i * 2 + 1] = src[i].y
        flatDst[i * 2] = dst[i].x
        flatDst[i * 2 + 1] = dst[i].y
    }
    val raw = nativeEstimateHomography(
        flatSrc, flatDst, count, iterations, thresholdPx, minInliers, minInlierRatio, seed,
    ) ?: return null
    if (raw.size < 10) return null
    val inlierCount = raw[0].toInt()
    if (inlierCount <= 0) {
        return FunHomographyResult(null, IntArray(0), 0, 0.0, false, "native: no valid hypothesis")
    }
    val matrix = FunMatrix3(DoubleArray(9) { raw[1 + it] })
    if (!matrix.isFinite()) return null
    val inliers = ArrayList<Int>(inlierCount)
    for (i in 0 until count) {
        val flagIndex = 10 + i
        if (flagIndex < raw.size && raw[flagIndex] >= 0.5) inliers += i
    }
    val ratio = inliers.size.toDouble() / count
    val success = inliers.size >= minInliers && ratio >= minInlierRatio
    return FunHomographyResult(
        homography = if (success) matrix else null,
        inliers = inliers.toIntArray(),
        inlierCount = inliers.size,
        inlierRatio = ratio,
        success = success,
        message = "native: inliers=${inliers.size}/$count",
    )
}
