# -*- coding: utf-8 -*-
"""review_patch 集成操作（c）：AstroStacker / GalleryManager / MultiFrameStacker /
BgJobRunner / HdrBracketConfig / HdrGainmapStrength / GalleryDetailScreen。"""

A = "app/src/main/java/com/hinnka/mycamera/"
AST = A + "computational/AstroStacker.kt"
GM = A + "gallery/GalleryManager.kt"
HBC = A + "camera/HdrBracketConfig.kt"
MFEP = A + "camera/MultiFrameExposurePlanner.kt"
HGS = A + "hdr/HdrGainmapStrength.kt"
GDS = A + "ui/gallery/GalleryDetailScreen.kt"
BJR = A + "computational/BgJobRunner.kt"

OPS = []

# ============================================================ AstroStacker（P0-9 / P1-17 / P1-3）
OPS += [
    ("insert_after", AST, "    const val DEFAULT_WORK_LONG_EDGE = 1280", '''    /** MEDIAN 档位默认工作长边上限（原硬编码 960；保留以维持默认行为逐字节不变）。 */
    const val DEFAULT_MEDIAN_WORK_LONG_EDGE = 960

    /**
     * 工作分辨率长边（默认 1280 零回归，可上调；受内存守卫约束）。
     */
    @Volatile
    var userWorkLongEdge: Int = DEFAULT_WORK_LONG_EDGE

    fun workLongEdge(): Int = userWorkLongEdge.coerceIn(320, 8192)

    /** 对齐最大平移（像素）。 */
    @Volatile
    var userMaxAlignShiftPx: Int = DEFAULT_MAX_ALIGN_SHIFT_PX

    fun maxAlignShiftPx(): Int = userMaxAlignShiftPx.coerceIn(0, 256)

    /** 对齐搜索降采样倍数（越大越省内存/越慢）。 */
    @Volatile
    var userAlignDownscale: Int = DEFAULT_ALIGN_DOWNSCALE

    fun alignDownscale(): Int = userAlignDownscale.coerceIn(1, 8)''', 1),

    ("replace", AST, "        var cap = if (type == PsStackType.MEDIAN) 960 else workLongEdge",
     "        var cap = if (type == PsStackType.MEDIAN) minOf(workLongEdge, DEFAULT_MEDIAN_WORK_LONG_EDGE) else workLongEdge", 1),

    ("replace", AST, "        workLongEdge: Int = DEFAULT_WORK_LONG_EDGE,",
     "        workLongEdge: Int = workLongEdge(),", 3),
    ("replace", AST, "        maxAlignShiftPx: Int = DEFAULT_MAX_ALIGN_SHIFT_PX,",
     "        maxAlignShiftPx: Int = maxAlignShiftPx(),", 1),
    ("replace", AST, "        alignDownscale: Int = DEFAULT_ALIGN_DOWNSCALE,",
     "        alignDownscale: Int = alignDownscale(),", 1),

    # P0-9 ①：守卫估算与真实分配口径对齐（无装箱 2 字节样本 + 1 字节余量）
    ("replace", AST, "                // 估算 MEDIAN 峰值：每像素每帧约 2 字节样本 + ArrayList 开销，取 8 字节保守值。",
     "                // 估算 MEDIAN 峰值：每像素每帧 2 字节样本（P0-9 平坦缓冲，无对象头）；取 3 字节保守值。", 1),
    ("replace", AST, "                val estBytes = w.toLong() * h.toLong() * limited.size.toLong() * 8L",
     "                val estBytes = w.toLong() * h.toLong() * limited.size.toLong() * 3L", 1),

    # P0-9 ②：MEDIAN 无装箱平坦缓冲
    ("replace", AST, '''            PsStackType.MEDIAN -> run {
                // 收集每像素样本（工作分辨率 + 限帧以控内存）。
                val perPixel = Array(count) { ArrayList<Short>(frames.size) }
                for (i in frames.indices) {
                    val dx = shifts[i*2]; val dy = shifts[i*2+1]
                    for (y in 0 until workH) {
                        val sy = y + dy; if (sy < 0 || sy >= workH) continue
                        for (x in 0 until workW) {
                            val sx = x + dx; if (sx < 0 || sx >= workW) continue
                            perPixel[y*workW + x].add(frames[i].data[sy*workW + sx])
                        }
                    }
                }
                ByteArray(count) { p -> medianShort(perPixel[p]).toByte() }
            }''', '''            PsStackType.MEDIAN -> run {
                // 无装箱平坦样本缓冲：每像素每帧 2 字节（Short），无对象头 →
                // 峰值 = count * n * 2B（原 Array(count){ArrayList<Short>} 每样本 16B 头，守卫低估一倍以上）。
                val n = frames.size
                val samples = ShortArray(count * n)
                for (i in frames.indices) {
                    val dx = shifts[i*2]; val dy = shifts[i*2+1]
                    for (y in 0 until workH) {
                        val sy = y + dy; if (sy < 0 || sy >= workH) continue
                        for (x in 0 until workW) {
                            val sx = x + dx; if (sx < 0 || sx >= workW) continue
                            samples[(y*workW + x) * n + i] = frames[i].data[sy*workW + sx]
                        }
                    }
                    job?.let { it.report(i + 1, n); it.checkYield() }
                }
                ByteArray(count) { p -> medianShort(samples, p * n, n) }
            }''', 1),

    # P1-16：MIN 档位补进度与让出
    ("replace", AST, '''                for (i in frames.indices) {
                    accumulateMin(frames[i].data, workW, workH, shifts[i*2], shifts[i*2+1], best)
                }''', '''                for (i in frames.indices) {
                    accumulateMin(frames[i].data, workW, workH, shifts[i*2], shifts[i*2+1], best)
                    // 长任务分片让出，避免独占后台消费者（也让取消/暂停真正生效）。
                    job?.let { it.report(i + 1, frames.size); it.checkYield() }
                }''', 1),

    # MEDIAN 平坦实现（ByteArray）
    ("replace", AST, '''    private fun medianShort(samples: ArrayList<Short>): Int {
        if (samples.isEmpty()) return 0
        val sorted = samples.map { it.toInt().and(0xFFFF) }.sorted()
        return sorted[sorted.size / 2]
    }''', '''    private fun medianShort(samples: ArrayList<Short>): Int {
        if (samples.isEmpty()) return 0
        val sorted = samples.map { it.toInt().and(0xFFFF) }.sorted()
        return sorted[sorted.size / 2]
    }

    /** 平坦缓冲取中位数（P0-9：无装箱路径；按 [start, start+length) 切片）。 */
    private fun medianShort(samples: ShortArray, start: Int, length: Int): Byte {
        val slice = samples.copyOfRange(start, start + length)
        slice.sort()
        return slice[length / 2].toByte()
    }''', 1),
]

# ============================================================ HdrBracketConfig（P1-3）
OPS += [
    ("insert_after", HBC, "    const val YUV_LONG_EV = 2.2f", '''    /** HDR 包围 EV（默认＝原常量，零回归）。 */
    @Volatile
    var userYuvLongEv: Float = YUV_LONG_EV
    @Volatile
    var userYuvShortEv: Float = YUV_SHORT_EV
    @Volatile
    var userYuvLongMaxShutterNs: Long = YUV_LONG_MAX_SHUTTER_NS
    fun yuvShortEv(): Float = userYuvShortEv.coerceIn(-4f, -0.5f)
    fun yuvLongMaxShutterNs(): Long = userYuvLongMaxShutterNs.coerceIn(1_000_000L, 10_000_000_000L)
    /** 长帧 EV（提升后由 yuvLongMaxShutterNs() 兜底快门上限，避免拉爆长曝时间）。 */
    fun yuvLongEv(): Float = userYuvLongEv.coerceIn(0.5f, 4f)''', 1),

    ("replace", HBC, "            minOf(shutterUpperNs, YUV_LONG_MAX_SHUTTER_NS).coerceAtLeast(shutterLowerNs)",
     "            minOf(shutterUpperNs, yuvLongMaxShutterNs()).coerceAtLeast(shutterLowerNs)", 1),
]

# ============================================================ MultiFrameExposurePlanner（P1-1）
OPS += [
    ("replace", MFEP, "                MultiFrameConfig.LONG_FRAME_MAX_EXPOSURE_TIME_NS,",
     "                MultiFrameConfig.longFrameMaxExposureTimeNs(),", 1),
]

# ============================================================ HdrGainmapStrength（P1-4）
OPS += [
    ("insert_after", HGS, "    const val MAX = 2.0f", '''    /** 运行时上限（默认 2.0f 与原 MAX 一致，零回归）。 */
    @Volatile
    var userMax: Float = MAX

    fun maxStrength(): Float = userMax.coerceIn(MIN, 4f)''', 1),

    ("replace", HGS, "        return (value ?: DEFAULT).coerceIn(MIN, MAX)",
     "        return (value ?: DEFAULT).coerceIn(MIN, maxStrength())", 1),

    ("replace", GDS, "                                valueRange = HdrGainmapStrength.MIN..HdrGainmapStrength.MAX,",
     "                                valueRange = HdrGainmapStrength.MIN..HdrGainmapStrength.maxStrength(),", 1),
]

# ============================================================ GalleryManager（P0-4 / P0-5 / P0-7 / P0-8 / P2-6 / P1-3）
OPS += [
    ("insert_after", GM, "import com.hinnka.mycamera.processor.MultiFrameStacker", '''import com.hinnka.mycamera.computational.AstroStacker
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.processor.ComputationalStackRouter
import kotlin.coroutines.resume''', 1),

    ("insert_before", GM, "    suspend fun saveYuvStackedPhoto(", '''    /**
     * 同步桥：把 [BgJobRunner] 后台入口包成可挂起等待（P0-8）。
     * 取消时联动取消后台任务；任务内部失败给 null，不会把异常抛进出片链路。
     */
    private suspend fun <T> awaitBgJob(block: ((T?) -> Unit) -> BgJob): T? =
        suspendCancellableCoroutine { cont ->
            val job = block { result -> if (cont.isActive) cont.resume(result) }
            cont.invokeOnCancellation { job.cancel() }
        }

''', 1),

    # 出片路径：唯一路由器 + 后台队列
    ("replace", GM, '''            var result: Bitmap? = when (computationalScene) {
                ComputationalScene.NIGHT,
                ComputationalScene.STACK -> runCatching {
                    MultiFrameStacker.processPsStack(
                        images = images,
                        rotation = rotation,
                        aspectRatio = aspectRatio,
                        colorSpace = colorSpace,
                        type = psStackType,
                        frameCount = psStackFrameCount,
                        align = psStackAlign,
                    )
                }.onFailure { e ->
                    PLog.w(TAG, "PS stack failed (scene=$computationalScene), frames consumed", e)
                }.getOrNull()

                ComputationalScene.ASTRO -> runCatching {
                    MultiFrameStacker.processAstroBurst(
                        images = images,
                        rotation = rotation,
                        aspectRatio = aspectRatio,
                        colorSpace = colorSpace,
                    )
                }.onFailure { e ->
                    PLog.w(TAG, "Astro stack failed, frames consumed", e)
                }.getOrNull()

                else -> MultiFrameStacker.processBurst(''', '''            // P0-7 + P0-8：统一路由器（唯一 scene→stacker 映射）+ BgJobRunner 后台入口。
            // NIGHT/STACK/ASTRO 走后台队列（内存守卫 / 暂停 / 取消 / 分片节流真正生效）；
            // 其余场景保留既有同步 processBurst。
            val stackRequest = ComputationalStackRouter.StackRequest(
                scene = computationalScene,
                stackType = psStackType,
                frameCount = psStackFrameCount,
                align = psStackAlign,
                starTrailsEnabled = starTrailsEnabled,
                trailAccumulationMode = trailAccumulationMode,
            )
            var result: Bitmap? = if (ComputationalStackRouter.supportsAsync(computationalScene)) {
                awaitBgJob { cb ->
                    ComputationalStackRouter.routeAsync(
                        request = stackRequest,
                        images = images,
                        rotation = rotation,
                        aspectRatio = aspectRatio,
                        colorSpace = colorSpace,
                        onResult = cb,
                    ) ?: error("async stack route unavailable: $computationalScene")
                }.also { stacked ->
                    if (stacked == null) {
                        PLog.w(TAG, "async stack failed (scene=$computationalScene), frames consumed")
                    }
                }
            } else {
                MultiFrameStacker.processBurst(''', 1),

    # 两个出片入口的参数默认值与新增星轨参数
    ("replace", GM, '''        psStackAlign: Boolean = true,
    ) = withContext(Dispatchers.IO) {''', '''        psStackAlign: Boolean = MultiFrameConfig.DEFAULT_STACK_ALIGN,
        // P0-4/P0-5：星轨路由与对齐默认值统一到 MultiFrameConfig 单一真值源。
        starTrailsEnabled: Boolean = false,
        trailAccumulationMode: AstroStacker.TrailAccumulationMode =
            AstroStacker.TrailAccumulationMode.LIGHTEN_MAX,
    ) = withContext(Dispatchers.IO) {''', 2),

    ("replace", GM, '''                    psStackFrameCount = psStackFrameCount,
                    psStackAlign = psStackAlign,
                )''', '''                    psStackFrameCount = psStackFrameCount,
                    psStackAlign = psStackAlign,
                    starTrailsEnabled = starTrailsEnabled,
                    trailAccumulationMode = trailAccumulationMode,
                )''', 1),

    # P0-8 HDR：最重的一步进后台队列
    ("replace", GM, '''    private fun processHdrBracketYuvFrames(
        images: List<SafeImage>,
        frameSelection: YuvHdrFrameSelection,
        rotation: Int,
        aspectRatio: AspectRatio,
        shouldMirror: Boolean,
        colorSpace: ColorSpace,
    ): Bitmap {
        val stackResult = MultiFrameStacker.processHdrBurstYuv(
            frames = buildYuvHdrStackFrames(
                images = images,
                frameSelection = frameSelection,
            ),
            fusionExposureProducts = frameSelection.fusionExposureProducts,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
        ) ?: throw IllegalStateException("Failed to stack and compose aligned HDR YUV frames")''',
     '''    private suspend fun processHdrBracketYuvFrames(
        images: List<SafeImage>,
        frameSelection: YuvHdrFrameSelection,
        rotation: Int,
        aspectRatio: AspectRatio,
        shouldMirror: Boolean,
        colorSpace: ColorSpace,
    ): Bitmap {
        // P0-8：HDR 合并（最重的一步）改走 BgJobRunner 异步入口，内存守卫/取消/节流生效。
        val stackResult = awaitBgJob { cb ->
            MultiFrameStacker.processHdrBurstYuvAsync(
                frames = buildYuvHdrStackFrames(
                    images = images,
                    frameSelection = frameSelection,
                ),
                fusionExposureProducts = frameSelection.fusionExposureProducts,
                rotation = rotation,
                aspectRatio = aspectRatio,
                colorSpace = colorSpace,
                displayName = "HDR Merge",
                onResult = cb,
            )
        } ?: throw IllegalStateException("Failed to stack and compose aligned HDR YUV frames")''', 1),

    # P2-6：多重曝光预览分辨率参数说明
    ("insert_after", GM, "        maxEdge: Int = 1440,", '''        // P2-6：预览/快照复用同一分辨率（默认 1440 与原硬编码一致；与
        // CameraScreen 的 PreviewCaptureLimits.previewMaxLongEdge() 成对）。''', 1),

    ("replace", GM, "            HdrBracketConfig.YUV_LONG_EV.toDouble(),",
     "            HdrBracketConfig.yuvLongEv().toDouble(),", 1),
    ("replace", GM, "            HdrBracketConfig.YUV_SHORT_EV.toDouble(),",
     "            HdrBracketConfig.yuvShortEv().toDouble(),", 1),
]

# ============================================================ BgJobRunner（P0-10 + P2-9 + 编译修复）
OPS += [
    ("insert_after", BJR, "    STACK(12L, 8, 512L * 1024 * 1024),", '''    // P0-10：内存预算与设备解耦（见 submit 处 effectiveMaxHeapBytes 应用）。
''', 1),

    ("insert_after", BJR, "    private val heapOverrides = HashMap<BgTaskType, Long>()", '''    /** 内存预算占可用堆比例上限（P0-10：低内存机不再给出无法达成的绝对预算）。 */
    private const val HEAP_BUDGET_FRACTION = 0.45

    private fun deviceHeapBudgetBytes(): Long =
        (Runtime.getRuntime().maxMemory() * HEAP_BUDGET_FRACTION).toLong()''', 1),

    ("replace", BJR, "        heapOverrides[type]?.let { job.maxHeapBytes = it }",
     "        // P0-10：设备感知预算（绝对值 / 覆写值 / 可用堆×比例 三者取小）。\n        job.maxHeapBytes = effectiveMaxHeapBytes(type)", 1),

    ("replace", BJR, "        val job = BgJob(name, chunkSize.coerceAtLeast(1), type, priority, block)",
     '''        // P2-9：chunkSize<=0（未显式传入）时按任务类型取默认批大小。
        val effectiveChunk = if (chunkSize <= 0) type.defaultMaxFrameBatch else chunkSize
        val job = BgJob(name, effectiveChunk.coerceAtLeast(1), type, priority, block)''', 1),

    ("replace", BJR, '''    internal fun effectiveMaxHeapBytes(type: BgTaskType): Long =
        synchronized(heapOverrides) { heapOverrides[type] } ?: type.defaultMaxHeapBytes''',
     '''    internal fun effectiveMaxHeapBytes(type: BgTaskType): Long = minOf(
        type.defaultMaxHeapBytes,
        synchronized(heapOverrides) { heapOverrides[type] } ?: Long.MAX_VALUE,
        deviceHeapBudgetBytes(),
    )''', 1),
]
