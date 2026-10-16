package com.hinnka.mycamera.longexposure

/**
 * 长曝光**会话规格**：把"光绘 / 慢门预设 / 烟火"三种入口统一成一套可直接驱动会话的参数。
 *
 * 生成入口：[fromLightPainting] / [fromPreset] / [fromFireworks]。全部字段 clamp 后使用。
 */
data class LongExposureSessionSpec(
    val stackMode: LongExposureStackMode = LongExposureStackMode.MAX,
    /** 目标帧数（开放，不设总量上限；仅用于 UI 进度与采集侧目标）。 */
    val frameCountTarget: Int = 30,
    val darkFrame: DarkFrameConfig = DarkFrameConfig.DEFAULT,
    /** 光源画笔色温（K），仅光绘有色彩偏需求。 */
    val brushColorTemperatureK: Int = 3200,
    val brushTint: Float = 0f,
    val brushGain: Float = 1f,
    val previewIntervalFrames: Int = 4,
    val previewMaxLongEdge: Int = 480,
    val previewThrottleMs: Long = 120L,
    /** 工作分辨率长边上限；0 = 用输入帧原始分辨率。 */
    val workLongEdge: Int = 0,
) {
    fun clampWithReport(): Pair<LongExposureSessionSpec, List<String>> {
        val reports = ArrayList<String>(2)
        val limit = LongExposureLimits.frameCountLimit()
        val frames = frameCountTarget.coerceIn(LongExposureLimits.FRAME_MIN, limit)
        LongExposureLimits.frameClampReport("spec.frameCountTarget", frameCountTarget, frames, reports)
        val (dark, darkReports) = darkFrame.clampWithReport()
        reports.addAll(darkReports)
        return copy(frameCountTarget = frames, darkFrame = dark) to reports
    }

    fun clamped(): LongExposureSessionSpec = clampWithReport().first

    companion object {
        fun fromLightPainting(config: LightPaintingConfig): LongExposureSessionSpec {
            val c = config.clamped()
            return LongExposureSessionSpec(
                stackMode = c.stackMode,
                frameCountTarget = c.frameCount,
                darkFrame = c.darkFrame,
                brushColorTemperatureK = c.brushColorTemperatureK,
                brushTint = c.brushTint,
                brushGain = c.brushGain,
                previewIntervalFrames = c.previewIntervalFrames,
                previewMaxLongEdge = c.previewMaxLongEdge,
                previewThrottleMs = c.previewThrottleMs,
                workLongEdge = c.workLongEdge,
            )
        }

        /** 慢门预设：曝光/ISO 属采集侧参数，这里只取叠加/帧数/暗帧/预览。 */
        fun fromPreset(
            preset: SlowShutterPreset,
            previewConfig: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        ): LongExposureSessionSpec {
            val p = preset.clamped()
            val c = previewConfig.clamped()
            return LongExposureSessionSpec(
                stackMode = p.stackMode,
                frameCountTarget = p.frameCount,
                darkFrame = if (p.darkFrameEnabled) c.darkFrame else DarkFrameConfig.DEFAULT.copy(enabled = false),
                brushColorTemperatureK = c.brushColorTemperatureK,
                brushTint = c.brushTint,
                brushGain = 1f,
                previewIntervalFrames = c.previewIntervalFrames,
                previewMaxLongEdge = c.previewMaxLongEdge,
                previewThrottleMs = c.previewThrottleMs,
                workLongEdge = c.workLongEdge,
            )
        }

        fun fromFireworks(config: FireworksConfig): LongExposureSessionSpec {
            val c = config.clamped()
            return LongExposureSessionSpec(
                stackMode = c.stackMode,
                frameCountTarget = c.frameCount,
                darkFrame = c.darkFrame,
                brushColorTemperatureK = 3200,
                brushTint = 0f,
                brushGain = 1f,
                workLongEdge = c.workLongEdge,
            )
        }
    }
}

/**
 * 光绘 / 烟火**实时会话**：逐帧喂入 → 增量叠加 → 实时叠加预览。
 *
 * 用法（相机侧，任意采集线程）：
 * ```kotlin
 * val session = LightExposureEngine.createSession(spec) { preview, count -> previewBitmap = ... }
 * session.setDarkFrames(darkPlanes)          // 可选
 * repeat(frameCount) { session.addFrame(plane) }   // 帧数开放
 * val result = session.finish()              // 应用光源画笔色偏
 * session.close()
 * ```
 *
 * 特点：
 *  - **O(1) 内存增量**：只驻留 1 帧 + 1 个累加器，帧数增加不放大内存；
 *  - 预览走独立后台队列（[LightPaintingPreviewQueue]），与采集线程解耦，积压时丢旧帧；
 *  - 纯 Kotlin（除预览队列的线程外不依赖 Android），可 JVM 单测。
 */
class LightPaintingSession(
    spec: LongExposureSessionSpec = LongExposureSessionSpec(),
    private val onPreview: ((LinearRgbPlane, Int) -> Unit)? = null,
) : AutoCloseable {

    val spec: LongExposureSessionSpec = spec.clamped()

    private val colorGains: FloatArray? = if (
        ColorTemperatureGains.isNeutral(spec.brushColorTemperatureK, spec.brushTint, spec.brushGain)
    ) {
        null
    } else {
        val gains = ColorTemperatureGains.gainsFor(spec.brushColorTemperatureK, spec.brushTint)
        for (i in gains.indices) gains[i] = (gains[i] * spec.brushGain).coerceIn(0f, 8f)
        gains
    }

    private val previewQueue: LightPaintingPreviewQueue? = if (onPreview != null) {
        LightPaintingPreviewQueue(
            config = LightPaintingConfig.DEFAULT.copy(
                stackMode = spec.stackMode,
                frameCount = spec.frameCountTarget,
                darkFrame = spec.darkFrame,
                brushColorTemperatureK = spec.brushColorTemperatureK,
                brushTint = spec.brushTint,
                previewIntervalFrames = spec.previewIntervalFrames,
                previewMaxLongEdge = spec.previewMaxLongEdge,
                previewThrottleMs = spec.previewThrottleMs,
            ),
            onPreview = onPreview,
        )
    } else {
        null
    }

    private var stacker: LongExposureStacker? = null
    private var darkPlanes: List<LinearRgbPlane> = emptyList()
    private var averagedDark: ShortArray? = null
    private var rejected = 0

    /** 已并入的帧数。 */
    val accumulatedFrames: Int get() = stacker?.accumulatedFrameCount ?: 0

    /** 累计坏点样本数。 */
    val hotPixelCount: Int get() = stacker?.hotPixelCount ?: 0

    /** 因尺寸异常被拒绝的帧数。 */
    val rejectedFrames: Int get() = rejected + (stacker?.rejectedFrameCount ?: 0)

    /** 是否达到目标帧数（UI 可据此收尾；达到后仍可继续喂帧，帧数不设上限）。 */
    val reachedTargetFrames: Boolean get() = accumulatedFrames >= spec.frameCountTarget

    /**
     * 设置暗帧（**工作尺寸未知时先记录，首帧到达后自动生效**）。
     * @return 是否接受了暗帧集合（空集合返回 false）。
     */
    fun setDarkFrames(planes: List<LinearRgbPlane>): Boolean {
        darkPlanes = planes
        averagedDark = null
        if (stacker != null) ensureDark()
        return planes.isNotEmpty()
    }

    /**
     * 并入一帧。
     *
     * @param plane 任意分辨率的线性 RGB 平面；内部按工作尺寸重采样。
     * @return 当前已并入帧数。
     */
    fun addFrame(plane: LinearRgbPlane): Int {
        val target = stacker ?: createStacker(plane)
        val samples = LongExposureResampler.downsample(
            plane.samples,
            plane.width,
            plane.height,
            target.width,
            target.height,
        )
        target.addFrame(samples, averagedDark, spec.darkFrame)
        previewQueue?.offer(plane)
        return target.accumulatedFrameCount
    }

    /** 当前叠加结果（应用色偏，不调色映射）。 */
    fun snapshotPlane(): LinearRgbPlane = stacker?.result(colorGains) ?: error("no frame added yet")

    /** 立即取一张低分辨率预览（同步；与后台队列互不影响）。 */
    fun previewNow(): LinearRgbPlane? = stacker?.previewPlane(spec.previewMaxLongEdge, colorGains)

    /** 收尾：返回成片平面（应用光源画笔色偏与目标色温）。 */
    fun finish(): LinearRgbPlane? = stacker?.result(colorGains)

    override fun close() {
        previewQueue?.shutdown()
    }

    private fun createStacker(plane: LinearRgbPlane): LongExposureStacker {
        val longest = maxOf(plane.width, plane.height)
        val width: Int
        val height: Int
        if (spec.workLongEdge > 0 && longest > spec.workLongEdge) {
            val scale = spec.workLongEdge.toFloat() / longest.toFloat()
            width = maxOf(1, (plane.width * scale).toInt())
            height = maxOf(1, (plane.height * scale).toInt())
        } else {
            width = plane.width
            height = plane.height
        }
        val created = LongExposureStacker(width, height, spec.stackMode)
        stacker = created
        if (darkPlanes.isNotEmpty()) ensureDark()
        return created
    }

    private fun ensureDark(): Boolean {
        val target = stacker ?: return false
        val size = target.width * target.height * 3
        val resampled = darkPlanes.mapNotNull { plane ->
            if (plane.width == target.width && plane.height == target.height) {
                plane.samples
            } else {
                LongExposureResampler.downsample(
                    plane.samples,
                    plane.width,
                    plane.height,
                    target.width,
                    target.height,
                )
            }
        }
        averagedDark = DarkFrameSubtractor.averageDarkFrames(resampled, size)
        return averagedDark != null
    }
}
