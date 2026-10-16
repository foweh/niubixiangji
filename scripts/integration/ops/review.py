# -*- coding: utf-8 -*-
"""review_patch.md 集成操作（P0/P1/P2 交叉审查修复）。

合并/替代决策（详见 delivery/selfchecks/integration.md）：
- P0-3：采用「单批上限 = reader 容量」的安全语义（不做 gallery 侧帧追加，避免重复出片），
  未落地分片续跑链路 → 记入未决项。
- P0-7 + P0-8：新建统一路由器 ComputationalStackRouter（同步 route 供控制器/兼容，
  异步 routeAsync 供 gallery 出片路径），两者共用同一 scene→stacker 映射，消除"两份实现"。
- P0-9/P1-17：MEDIAN 工作分辨率改为 minOf(workLongEdge, DEFAULT_MEDIAN_WORK_LONG_EDGE=960)，
  默认行为逐字节不变。
- P1-14：除补丁列出的锚点外，同步替换 heightFraction 与两处 AF_READY_DEFER_MS 日志（同参数一致性）。
- P1-12：CameraScreen 的 serverEnhancement 开关改为 VM 状态（新增 VM 字段+setter，补丁未给出）。
- P1-8：LinearDngMerger 守卫改为「降级 Bayer 1/degrade 同相抽样」并同步 DNG 尺寸。
"""

A = "app/src/main/java/com/hinnka/mycamera/"
KF = A + "camera/MultiFrameConfig.kt"
CS = A + "camera/CameraState.kt"
VM = A + "viewmodel/CameraViewModel.kt"
SS = A + "ui/settings/SettingsScreen.kt"
DC = A + "data/DeviceConfiguration.kt"
SB = A + "ui/camera/ComputationalSceneBar.kt"
C2 = A + "camera/Camera2Controller.kt"
GM = A + "gallery/GalleryManager.kt"
MFS = A + "processor/MultiFrameStacker.kt"
BJR = A + "computational/BgJobRunner.kt"
AST = A + "computational/AstroStacker.kt"
HBC = A + "camera/HdrBracketConfig.kt"
MFEP = A + "camera/MultiFrameExposurePlanner.kt"
HGS = A + "hdr/HdrGainmapStrength.kt"
GDS = A + "ui/gallery/GalleryDetailScreen.kt"
SEC = A + "computational/ServerEnhancementClient.kt"
RSC = A + "stabilization/RealtimeStabilizationCoordinator.kt"
VCR = A + "video/VideoCapabilitiesResolver.kt"
ESG = A + "hdr/EstimatedSdrGainmapProducer.kt"
RGM = A + "hdr/RawGainmapMath.kt"
GRG = A + "hdr/GpuReferenceGainmapProducer.kt"
VR = A + "video/VideoRecorder.kt"
VEX = A + "lut/VideoExporter.kt"
GMG = A + "processor/GlesMgcRawSpatialStacker.kt"
LDM = A + "computational/LinearDngMerger.kt"
UPR = A + "data/UserPreferencesRepository.kt"
CSR = A + "processor/ComputationalStackRouter.kt"
CMS = A + "ui/camera/CameraScreen.kt"

OPS = []

# ============================================================ P0-1 帧数上限开放
OPS += [
    ("insert_after", KF, "    const val MAX_FRAME_COUNT = 20", '''    /** 帧数推荐档位上限（UI 滑杆默认刻度顶值）。默认 20 = 原 MAX_FRAME_COUNT，行为不变。 */
    const val RECOMMENDED_FRAME_COUNT_MAX = 20

    /** 帧数绝对安全上限：仅防误设/溢出，**不做总量截断**（资源问题交给分片 + 内存守卫）。 */
    const val ABSOLUTE_FRAME_COUNT_MAX = 4096

    /**
     * 运行时帧数上限（用户可配）。默认 = MAX_FRAME_COUNT（20），零回归；
     * 由设置页/DataStore 上调后才放大，clamp 到 [MIN_DENOISE_FRAME_COUNT, ABSOLUTE_FRAME_COUNT_MAX]。
     */
    @Volatile
    var userFrameCountLimit: Int = MAX_FRAME_COUNT

    /** 当前生效的帧数上限（clamp 后；调用方勿再各自 coerceIn 魔法数）。 */
    fun frameCountLimit(): Int =
        userFrameCountLimit.coerceIn(MIN_DENOISE_FRAME_COUNT, ABSOLUTE_FRAME_COUNT_MAX)''', 1),

    ("replace", KF, '''    fun normalizeDenoiseFrameCount(frameCount: Int): Int {
        return frameCount.coerceIn(MIN_DENOISE_FRAME_COUNT, MAX_FRAME_COUNT)
    }''', '''    fun normalizeDenoiseFrameCount(
        frameCount: Int,
        limit: Int = frameCountLimit(),
    ): Int {
        return frameCount.coerceIn(MIN_DENOISE_FRAME_COUNT, limit)
    }''', 1),

    ("replace", KF, '''    fun normalizeHdrPlusFrameCount(
        frameCount: Int,
        bracketExposureEnabled: Boolean = false,
    ): Int {''', '''    fun normalizeHdrPlusFrameCount(
        frameCount: Int,
        bracketExposureEnabled: Boolean = false,
        limit: Int = frameCountLimit(),
    ): Int {''', 1),

    ("replace", KF, "        return frameCount.coerceIn(minimumFrameCount, MAX_FRAME_COUNT)",
     "        return frameCount.coerceIn(minimumFrameCount, limit)", 1),

    # P0-5 对齐默认值统一
    ("insert_after", KF, "    const val DEFAULT_HDR_PLUS_BRACKET_EXPOSURE = false", '''    /**
     * PS/天文堆栈默认是否做全局平移对齐。
     * 默认 false：主流程为三脚架固定拍摄（星轨/星点的位移本身就是要保留的结果）；
     * 手持多帧降噪想要对齐时，由 UI 开关或本常量统一上调（三处调用点共用此真值源）。
     */
    const val DEFAULT_STACK_ALIGN = false''', 1),

    # P1-1
    ("insert_after", KF, "    const val LONG_FRAME_MAX_EXPOSURE_TIME_NS = 10_000_000L", '''    /** 长帧快门名义上限（默认 10ms 保持既有行为；可上调到 sensor 上限，由 clamp 保障）。 */
    @Volatile
    var userLongFrameMaxExposureTimeNs: Long = LONG_FRAME_MAX_EXPOSURE_TIME_NS
    fun longFrameMaxExposureTimeNs(): Long =
        userLongFrameMaxExposureTimeNs.coerceIn(1_000_000L, 10_000_000_000L)''', 1),

    ("insert_after", KF, "    const val LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY = 800", '''    @Volatile
    var userLongFrameFallbackMaxIso: Int = LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY
    fun longFrameFallbackMaxIso(): Int = userLongFrameFallbackMaxIso.coerceIn(100, 102_400)''', 1),

    ("insert_after", KF, "    const val SHORT_FRAME_COUNT = 1", '''    /** 短帧数量（默认 1；开放后由总帧数比例约束，clamp 到总数-2 以内）。 */
    @Volatile
    var userShortFrameCount: Int = SHORT_FRAME_COUNT
    fun shortFrameCount(total: Int): Int =
        userShortFrameCount.coerceIn(0, (normalizeHdrPlusFrameCount(total) - 2).coerceAtLeast(0))''', 1),

    ("insert_after", KF, "    const val LONG_FRAME_COUNT_DIVISOR = 4", '''    @Volatile
    var userLongFrameCountDivisor: Int = LONG_FRAME_COUNT_DIVISOR
    fun longFrameCountDivisor(): Int = userLongFrameCountDivisor.coerceIn(1, 16)''', 1),

    # P1-2
    ("insert_after", KF, "    const val MAX_OUTPUT_SCALE = 2f", '''    /** 输出倍率运行时上限（默认 2f 保持既有行为；上调后由内存守卫/分片保障）。 */
    @Volatile
    var userMaxOutputScale: Float = MAX_OUTPUT_SCALE
    fun maxOutputScale(): Float = userMaxOutputScale.coerceIn(MIN_OUTPUT_SCALE, 4f)''', 1),

    ("replace", KF, "            fallback.coerceIn(MIN_OUTPUT_SCALE, MAX_OUTPUT_SCALE)",
     "            fallback.coerceIn(MIN_OUTPUT_SCALE, maxOutputScale())", 1),
    ("replace", KF, "            outputScale.coerceIn(MIN_OUTPUT_SCALE, MAX_OUTPUT_SCALE)",
     "            outputScale.coerceIn(MIN_OUTPUT_SCALE, maxOutputScale())", 1),
]

# ============================================================ P0-1(VM) / P0-4(VM) / P1-12(VM) / P1-13
OPS += [
    ("replace", VM, "        val clamped = frameCount.coerceIn(1, 32)",
     "        val clamped = MultiFrameConfig.normalizeDenoiseFrameCount(frameCount)", 1),

    ("replace", VM, "    var stackAlign: Boolean by mutableStateOf(true)", '''    var stackAlign: Boolean by mutableStateOf(MultiFrameConfig.DEFAULT_STACK_ALIGN)

    /** 星轨叠加开关（拍照链路用；与 stackAlign 同级，转发到 gallery 的堆栈路由）。 */
    var starTrailsEnabled: Boolean by mutableStateOf(false)
        private set
    /** 星轨叠加方式（Lighten / Sum-Avg）。 */
    var trailAccumulationMode: AstroStacker.TrailAccumulationMode by
        mutableStateOf(AstroStacker.TrailAccumulationMode.LIGHTEN_MAX)
        private set

    fun setStarTrailsEnabled(enabled: Boolean) {
        if (starTrailsEnabled == enabled) return
        starTrailsEnabled = enabled
        // 星轨必须免对齐（星点位移就是要保留的结果）。
        if (enabled) setStackConfig(stackType, stackFrameCount, align = false)
    }

    fun setTrailAccumulationMode(mode: AstroStacker.TrailAccumulationMode) {
        trailAccumulationMode = mode
    }

    /**
     * 服务器端增强开关（P1-12：取代 CameraScreen 的纯局部 Compose 状态）。
     * 真正的可用性门控在 `AiConfigStore` 的 SERVER_ENHANCE 能力（默认关）；此处仅保存用户意图。
     */
    var serverEnhancementEnabled: Boolean by mutableStateOf(false)
        private set

    fun setServerEnhancementEnabled(enabled: Boolean) {
        serverEnhancementEnabled = enabled
    }''', 1),

    ("insert_after", VM, "                    psStackAlign = stackAlign,", '''                    starTrailsEnabled = starTrailsEnabled,
                    trailAccumulationMode = trailAccumulationMode,''', 1),

    ("replace", VM, "        val normalizedCount = count.coerceIn(2, 9)",
     "        val normalizedCount = count.coerceIn(2, MultipleExposureLimits.MAX_FRAMES)", 1),

    ("append", VM, '''

/** 多重曝光开放上限（磁盘分帧，仅防误设；默认行为不变）。 */
object MultipleExposureLimits {
    const val DEFAULT_MAX_FRAMES = 9
    @Volatile var userMaxFrames: Int = DEFAULT_MAX_FRAMES
    val MAX_FRAMES: Int get() = userMaxFrames.coerceIn(2, 256)
}
'''),

    # P1-13 读取路径补 clamp
    ("replace", UPR, "                multipleExposureCount = preferences[MULTIPLE_EXPOSURE_COUNT] ?: 2,",
     '''                multipleExposureCount = (preferences[MULTIPLE_EXPOSURE_COUNT] ?: 2)
                    .coerceIn(2, MultipleExposureLimits.MAX_FRAMES),''', 1),
]

# ============================================================ P0-1(SettingsScreen 上限) / P0-5(其它) / P0-6 / P0-2
OPS += [
    ("replace", SS, "                                MultiFrameConfig.MAX_FRAME_COUNT.toFloat(),",
     "                                MultiFrameConfig.frameCountLimit().toFloat(),", 2),

    ("replace", DC, "            integer(it, MultiFrameConfig.MIN_HDR_PLUS_FRAME_COUNT, MultiFrameConfig.MAX_FRAME_COUNT)",
     '''            integer(
                it,
                MultiFrameConfig.MIN_HDR_PLUS_FRAME_COUNT,
                MultiFrameConfig.ABSOLUTE_FRAME_COUNT_MAX,
            )''', 1),

    ("insert_after", SB, "import com.hinnka.mycamera.camera.PsStackType",
     "import com.hinnka.mycamera.camera.MultiFrameConfig", 1),

    ("replace", SB, '''private fun stepStackFrameCount(current: Int): Int {
    // 常用档位：3/5/8/16/24/32，循环步进。
    val steps = intArrayOf(3, 5, 8, 16, 24, 32)
    val idx = steps.indexOf(current)
    return steps[(idx + 1).coerceAtMost(steps.size - 1)]
}''', '''/** 帧数档位：按当前运行时上限动态生成（默认上限 20 时与原 ≤20 档位一致）。 */
private val STACK_FRAME_STEPS: IntArray
    get() {
        val max = MultiFrameConfig.frameCountLimit()
        return intArrayOf(3, 5, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512)
            .filter { it <= max }
            .toIntArray()
            .takeIf { it.isNotEmpty() }
            ?: intArrayOf(max)
    }

private fun stepStackFrameCount(current: Int): Int {
    val steps = STACK_FRAME_STEPS
    // 当前值可能来自滑杆（不在档位表内）：取"第一个比它大的档位"；已在顶则停住。
    val nextIndex = steps.indexOfFirst { it > current }
    return if (nextIndex >= 0) steps[nextIndex] else steps.last()
}''', 1),
]

# ============================================================ 统一路由器（P0-7 + P0-8 桥）
OPS += [
    ("create", CSR, '''package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.ComputationalScene
import com.hinnka.mycamera.camera.PsStackType
import com.hinnka.mycamera.computational.AstroStacker
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.model.SafeImage

/**
 * 计算摄影堆栈唯一路由器：scene + 星轨开关 → 具体堆栈实现。
 *
 * 由 [com.hinnka.mycamera.camera.Camera2Controller.dispatchComputationalStack]（同步 [route]）
 * 与 [com.hinnka.mycamera.gallery.GalleryManager] 出片路径（异步 [routeAsync]）共用，
 * 避免同一 scene→stacker 决策散落两处、参数来源不同而发散（审查项 P0-7）。
 *
 * 异步版走 `computation/BgJobRunner`：内存守卫 / 暂停 / 取消 / 分片节流对堆栈真正生效
 * （审查项 P0-8「异步调度入口无调用者」）。
 */
object ComputationalStackRouter {

    data class StackRequest(
        val scene: ComputationalScene,
        val stackType: PsStackType,
        val frameCount: Int,
        val align: Boolean,
        val starTrailsEnabled: Boolean = false,
        val trailAccumulationMode: AstroStacker.TrailAccumulationMode =
            AstroStacker.TrailAccumulationMode.LIGHTEN_MAX,
    )

    /** 该场景是否有后台（BgJobRunner）入口；false 表示调用方走既有同步 processBurst。 */
    fun supportsAsync(scene: ComputationalScene): Boolean = when (scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK,
        ComputationalScene.ASTRO -> true
        else -> false
    }

    /** 同步路由（控制器内部/兼容路径）。 */
    fun route(
        request: StackRequest,
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
    ): Bitmap? = when (request.scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK -> MultiFrameStacker.processPsStack(
            images = images,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
            type = request.stackType,
            frameCount = request.frameCount,
            align = request.align,
        )

        ComputationalScene.ASTRO ->
            if (request.starTrailsEnabled) {
                MultiFrameStacker.processStarTrailsBurst(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    accumulationMode = request.trailAccumulationMode,
                )
            } else {
                MultiFrameStacker.processAstroBurst(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    align = request.align,
                )
            }

        else -> null
    }

    /**
     * 异步路由：入 BgJobRunner 队列，返回 [BgJob]（可取消）；[onResult] 在后台线程回调。
     * 仅对 [supportsAsync] 为 true 的场景返回非空；STANDARD 等场景返回 null，
     * 调用方据此走既有同步 [MultiFrameStacker.processBurst]。
     */
    fun routeAsync(
        request: StackRequest,
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        onResult: (Bitmap?) -> Unit,
    ): BgJob? = when (request.scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK -> MultiFrameStacker.processPsStackAsync(
            images = images,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
            type = request.stackType,
            frameCount = request.frameCount,
            align = request.align,
            displayName = "PS Stack ${request.frameCount}F",
            onResult = onResult,
        )

        ComputationalScene.ASTRO ->
            if (request.starTrailsEnabled) {
                MultiFrameStacker.processStarTrailsAsync(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    accumulationMode = request.trailAccumulationMode,
                    onResult = onResult,
                )
            } else {
                MultiFrameStacker.processAstroBurstAsync(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    align = request.align,
                    onResult = onResult,
                )
            }

        else -> null
    }
}
'''),
]
