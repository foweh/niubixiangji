# -*- coding: utf-8 -*-
"""review_patch 集成操作（d）：ServerEnhancementClient / 防抖 / 视频能力 / hdr 降采样 /
VideoRecorder / VideoExporter / GlesMgcRawSpatialStacker / LinearDngMerger / CameraScreen /
MultiFrameStacker(HDR 最小帧) 。"""

A = "app/src/main/java/com/hinnka/mycamera/"
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
CMS = A + "ui/camera/CameraScreen.kt"
MFS = A + "processor/MultiFrameStacker.kt"

OPS = []

# ============================================================ P1-12 + aiplatform H3 合并：ServerEnhancementClient
OPS += [
    ("replace", SEC, '''    @Volatile var baseUrl: String = "",
    @Volatile var model: String = "basicsr-swinir",''', '''    @Volatile var baseUrl: String = "",
    @Volatile var model: String = DEFAULT_MODEL,''', 1),

    ("replace", SEC, '''    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "server-enhance-worker").apply { isDaemon = true }
    }

''', "", 1),

    ("replace", SEC, '''    fun shutdown() {
        worker.shutdown()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
    }''', '''    /**
     * 兼容保留：本客户端不再自持线程池（任务统一走 [BgJobRunner] 全局队列），无资源可释放。
     */
    @Deprecated("任务已统一走 BgJobRunner，无需显式释放", ReplaceWith(""))
    fun shutdown() = Unit

    /** 从配置读取端点/模型（默认空 = 功能关闭，UI 据此门控）。 */
    fun configureFrom(config: ServerEnhanceConfig) {
        baseUrl = config.baseUrl.trim().trimEnd('/')
        model = config.model.ifBlank { DEFAULT_MODEL }
    }

    /**
     * 最小校验：响应类型 / 魔数 / 体积。防止把 200 的错误体（HTML/JSON）当图片落盘。
     * @return null 表示通过；否则返回失败原因。
     */
    private fun validateEnhancedBody(contentType: String?, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "empty body"
        if (bytes.size > MAX_OUTPUT_BYTES) return "oversized body ${bytes.size}B"
        val magicOk = (bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) || // PNG
            (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || // JPEG
            (bytes.size >= 2 && bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte()) || // TIFF/DNG (II)
            (bytes.size >= 2 && bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte())    // TIFF/DNG (MM)
        if (!magicOk) return "unexpected magic"
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mediaType != null && mediaType.isNotEmpty() &&
            !mediaType.startsWith("image/") && mediaType != "application/octet-stream"
        ) {
            return "unexpected content-type $mediaType"
        }
        return null
    }''', 1),

    ("replace", SEC, "    fun enhance(inputFile: File, outputFile: File): File? {",
     "    fun enhance(inputFile: File, outputFile: File, job: BgJob? = null): File? {", 1),

    ("replace", SEC, '''                _state.set(State.DOWNLOADING)
                conn.inputStream.use { input ->
                    FileOutputStream(outputFile).use { input.copyTo(it) }
                }''', '''                // P1-12：分块读取 + 每块 checkYield（响应暂停/取消/节流）；
                // M3：先落内存做类型/魔数/体积校验，通过后才写盘。
                val contentType = conn.contentType
                _state.set(State.DOWNLOADING)
                val buffer = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val chunk = ByteArray(64 * 1024)
                    var read = input.read(chunk)
                    while (read >= 0) {
                        buffer.write(chunk, 0, read)
                        job?.checkYield()   // PATTERN_SPEC §1.5：每个分片末尾必须 checkYield
                        read = input.read(chunk)
                    }
                }
                val bytes = buffer.toByteArray()
                val invalidReason = validateEnhancedBody(contentType, bytes)
                if (invalidReason != null) {
                    PLog.w(TAG, "Server enhance response rejected: $invalidReason")
                    _state.set(State.FAILED)
                    lastError = invalidReason
                    return null
                }
                FileOutputStream(outputFile).use { it.write(bytes) }''', 1),

    ("replace", SEC, "            chunkSize = 1,",
     "            chunkSize = 4,   // 分片：每 4 次 read 轮询一次；配合 enhance 内的 checkYield 响应取消", 1),

    ("replace", SEC, "            val result = if (isEnabled) enhance(inputFile, outputFile) else null",
     "            val result = if (isEnabled) enhance(inputFile, outputFile, this) else null", 1),

    ("replace", SEC, '''    companion object {
        private const val TAG = "ServerEnhancement"
    }''', '''    companion object {
        const val DEFAULT_MODEL = "basicsr-swinir"
        private const val TAG = "ServerEnhancement"
        private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L
    }''', 1),

    ("replace", SEC, '''import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
''', "", 1),

    ("append", SEC, '''
/**
 * 服务器增强配置（P1-12：端点/模型来自设置，不再恒为空 → 功能可真正开启）。
 * 默认 baseUrl 为空 = 功能关闭（UI 据此门控），与既有行为一致。
 */
data class ServerEnhanceConfig(
    val baseUrl: String = "",
    val model: String = ServerEnhancementClient.DEFAULT_MODEL,
)
'''),
]

# ============================================================ P1-15 防抖参数
OPS += [
    ("insert_after", RSC, "const val MAX_VIDEO_STABILIZATION_LOOKAHEAD = 10", '''@Volatile var userMaxStabilizationLookahead: Int = MAX_VIDEO_STABILIZATION_LOOKAHEAD
@Volatile var userMaxStabilizationStrength: Float = 1f
@Volatile var userMaxStabilizationBufferCount: Int = 90
fun maxStabilizationLookahead(): Int = userMaxStabilizationLookahead.coerceIn(1, 64)
fun maxStabilizationStrength(): Float = userMaxStabilizationStrength.coerceIn(0f, 2f)
fun maxStabilizationBufferCount(): Int = userMaxStabilizationBufferCount.coerceIn(16, 4096)''', 1),

    ("replace", RSC, "    if (strength.isFinite()) strength.coerceIn(0f, 1f) else DEFAULT_VIDEO_STABILIZATION_STRENGTH",
     "    if (strength.isFinite()) strength.coerceIn(0f, maxStabilizationStrength()) else DEFAULT_VIDEO_STABILIZATION_STRENGTH", 1),
    ("replace", RSC, "    lookahead.coerceIn(MIN_VIDEO_STABILIZATION_LOOKAHEAD, MAX_VIDEO_STABILIZATION_LOOKAHEAD)",
     "    lookahead.coerceIn(1, maxStabilizationLookahead())", 1),
    ("replace", RSC, "        while (nativeResults.size > MAX_RESULT_COUNT) nativeResults.removeFirst()",
     "        while (nativeResults.size > maxStabilizationBufferCount()) nativeResults.removeFirst()", 1),
    ("replace", RSC, "            trimOldest(frameMetadata, MAX_METADATA_COUNT)",
     "            trimOldest(frameMetadata, maxStabilizationBufferCount())", 1),
]

# ============================================================ P1-16 视频预览尺寸
OPS += [
    ("insert_after", VCR, "    private const val VIDEO_PREVIEW_SHORT_EDGE = 1080", '''    /** 预览尺寸短边上限：默认 1080 保持既有行为；可上调（4K 预览需 HAL 支持）。 */
    @Volatile var userVideoPreviewShortEdge: Int = VIDEO_PREVIEW_SHORT_EDGE
    private fun videoPreviewShortEdge(): Int = userVideoPreviewShortEdge.coerceIn(480, 2160)

    private const val OPTIMISTIC_FPS_THRESHOLD = 50   // 广告帧率达标即不再"乐观追加"档位''', 1),
    ("replace", VCR, "            minOf(it.width, it.height) <= VIDEO_PREVIEW_SHORT_EDGE",
     "            minOf(it.width, it.height) <= videoPreviewShortEdge()", 1),
    ("replace", VCR, "            if (advertisedMaxFps >= 50) {",
     "            if (advertisedMaxFps >= OPTIMISTIC_FPS_THRESHOLD) {", 1),
]

# ============================================================ P1-18 增益图降采样
OPS += [
    ("insert_after", ESG, "        private const val DOWNSAMPLE = 4", '''        /** 增益图降采样系数：默认 4（保持既有行为）；可配 1/2/4/8。 */
        @Volatile var userDownsample: Int = DOWNSAMPLE
        fun downsample(): Int = userDownsample.takeIf { it in intArrayOf(1, 2, 4, 8) } ?: DOWNSAMPLE''', 1),
    ("replace", ESG, "        val width = (sdrBase.width / DOWNSAMPLE).coerceAtLeast(1)",
     "        val width = (sdrBase.width / downsample()).coerceAtLeast(1)", 1),
    ("replace", ESG, "        val height = (sdrBase.height / DOWNSAMPLE).coerceAtLeast(1)",
     "        val height = (sdrBase.height / downsample()).coerceAtLeast(1)", 1),

    ("insert_after", RGM, "    const val DOWNSAMPLE = 2", '''    /** RAW 增益图降采样（默认 2 保持既有行为；可配 1/2/4/8）。 */
    @Volatile var userDownsample: Int = DOWNSAMPLE

    fun downsample(): Int = userDownsample.takeIf { it in intArrayOf(1, 2, 4, 8) } ?: DOWNSAMPLE''', 1),
    ("replace", GRG, "        private const val RAW_DOWNSAMPLE = RawGainmapMath.DOWNSAMPLE",
     "        private val RAW_DOWNSAMPLE get() = RawGainmapMath.downsample()", 1),
]

# ============================================================ P2-4 视频比特率
OPS += [
    ("replace", VR, "        val videoBitrate = (requestedBitrateMbps * 1_000_000).coerceIn(2_000_000, 300_000_000)",
     '''        val videoBitrate = (requestedBitrateMbps * 1_000_000)
            .coerceIn(2_000_000, VideoBitrateLimits.maxBps())''', 1),
    ("append", VR, '''

/** 视频比特率开放上限（默认 300Mbps 保持既有行为）。 */
object VideoBitrateLimits {
    const val DEFAULT_MAX_BPS = 300_000_000L
    @Volatile var userMaxBps: Long = DEFAULT_MAX_BPS
    fun maxBps(): Long = userMaxBps.coerceIn(2_000_000L, 2_000_000_000L)
}
'''),
]

# ============================================================ P2-5 导出 4K 阈值
OPS += [
    ("replace", VEX, "            resolvedOutputSize.width.toLong() * resolvedOutputSize.height.toLong() > 3840L * 2160L",
     '''            resolvedOutputSize.width.toLong() * resolvedOutputSize.height.toLong() >
                VideoExportResolution.UHD_4K.longEdge.toLong() * VideoExportResolution.UHD_4K.shortEdge.toLong()''', 1),
]

# ============================================================ P1-7 / P1-9 GlesMgcRawSpatialStacker
OPS += [
    ("insert_after", GMG, "        const val RGB_TEXTURE_ADVISORY_BYTES = 640L * 1024L * 1024L", '''        /** GPU 纹理建议预算：默认 640MB 保持既有行为；低内存机应下调（由设备能力/设置写入）。 */
        @Volatile
        var userRgbTextureAdvisoryBytes: Long = RGB_TEXTURE_ADVISORY_BYTES
        fun rgbTextureAdvisoryBytes(): Long =
            userRgbTextureAdvisoryBytes.coerceIn(64L * 1024 * 1024, 4L * 1024 * 1024 * 1024)''', 1),

    ("replace", GMG, "                advisoryBytes = RGB_TEXTURE_ADVISORY_BYTES,",
     "                advisoryBytes = rgbTextureAdvisoryBytes(),", 1),
    # 三处日志（1865 / 6814 / 7624）统一改函数版：16 空格锚点是 24 空格行的子串，
    # 替换后缩进仍对齐（count=3 覆盖全部三处）。
    ("replace", GMG, '                "advisoryBytes=$RGB_TEXTURE_ADVISORY_BYTES " +',
     '                "advisoryBytes=${rgbTextureAdvisoryBytes()} " +', 3),
    ("replace", GMG, '                "${bandPlan.projectedGpuBytes > RGB_TEXTURE_ADVISORY_BYTES} " +',
     '                "${bandPlan.projectedGpuBytes > rgbTextureAdvisoryBytes()} " +', 1),
    ("replace", GMG, '                        "${temporalGpuBytes > RGB_TEXTURE_ADVISORY_BYTES}",',
     '                        "${temporalGpuBytes > rgbTextureAdvisoryBytes()}",', 1),
    ("replace", GMG, '                "estimateExceedsAdvisory=${projectedGpuBytes > RGB_TEXTURE_ADVISORY_BYTES}",',
     '                "estimateExceedsAdvisory=${projectedGpuBytes > rgbTextureAdvisoryBytes()}",', 1),

    ("insert_before", GMG, "    private fun maxSabreAccumulatedWeight(frameCount: Int): Float = when {",
     '''    /** 累积权重分档阈值（默认与移植自 MGC 的既有分档一致；集中此处便于长序列平滑与核对）。 */
    private val SABRE_WEIGHT_TIERS = intArrayOf(2, 4, 6, 16, 18, 52, 86)

''', 1),
    ("replace", GMG, "            else -> 255f", '''            // >86 帧：R8 掩码量化上限 255（数学上支持 ≤255 帧的权重分辨率）。
            // 若需 >255 帧，请改用 R16F 累积缓冲（由 slice 配置决定，不在本函数内硬编码）。
            else -> 255f''', 1),
]

# ============================================================ P1-8 LinearDngMerger 降级而非失败
OPS += [
    ("replace", LDM, '''        // 内存守卫：sum(Long,8) + sumSq(Double,8) + accepted(Int,4) + mean/std/finalSum(Float,4*3)
        // ≈ 36 字节/像素；超预算则拒绝（调用方应减少帧数/分块或落盘）。
        if (job != null) {
            val estBytes = count.toLong() * 36L
            if (!job.guardMemory(estBytes)) {
                PLog.w(TAG, "RAW merge rejected by memory guard: need ${estBytes}B for ${w}x$h")
                throw BgJobMemoryLimitException(job.displayName, estBytes, job.maxHeapBytes)
            }
        }''', '''        // 内存守卫：超预算时**降级**（Bayer 同相抽样到 1/degrade 分辨率）而不是失败（PATTERN_SPEC §4）。
        var degrade = 1
        if (job != null) {
            var estBytes = count.toLong() * 36L
            while (!job.guardMemory(estBytes) && degrade < 8) {
                degrade *= 2
                estBytes /= 4L
                PLog.w(TAG, "RAW merge over budget: degrade Bayer binning to 1/$degrade")
            }
        }
        // 降级后的工作分辨率（Bayer 同相抽样，CFA 结构保持）。
        val workW = (w / degrade).coerceAtLeast(1)
        val workH = (h / degrade).coerceAtLeast(1)
        val workCount = workW * workH''', 1),

    ("replace", LDM, '''        val sum = LongArray(count)
        val sumSq = DoubleArray(count)
        val accepted = IntArray(count)''', '''        val sum = LongArray(workCount)
        val sumSq = DoubleArray(workCount)
        val accepted = IntArray(workCount)''', 1),

    ("replace", LDM, '''            for (y in 0 until h) {
                val rowBase = y * rowStride
                for (x in 0 until w) {
                    val p = y * w + x
                    // RAW16 样本按 pixelStride 读 2 字节小端。
                    val raw = buf.getShort(rowBase + x * pixStride).toInt().and(0xFFFF)''',
     '''            for (y in 0 until workH) {
                val rowBase = y * degrade * rowStride
                for (x in 0 until workW) {
                    val p = y * workW + x
                    // RAW16 样本按 pixelStride 读 2 字节小端（降级时按 1/degrade 同相抽样，保留 CFA 相位）。
                    val raw = buf.getShort(rowBase + x * degrade * pixStride).toInt().and(0xFFFF)''', 1),

    ("replace", LDM, '''        for (p in 0 until count) {
            val n = accepted[p]''', '''        for (p in 0 until workCount) {
            val n = accepted[p]''', 1),

    ("replace", LDM, '''            for (y in 0 until h) {
                val rowBase = y * rowStride
                for (x in 0 until w) {
                    val p = y * w + x
                    val raw = buf.getShort(rowBase + x * pixStride).toInt().and(0xFFFF)
                    if (raw >= whiteLevel) continue''', '''            for (y in 0 until workH) {
                val rowBase = y * degrade * rowStride
                for (x in 0 until workW) {
                    val p = y * workW + x
                    val raw = buf.getShort(rowBase + x * degrade * pixStride).toInt().and(0xFFFF)
                    if (raw >= whiteLevel) continue''', 1),

    ("replace", LDM, '''        val out = ByteArray(count * 2)
        var o = 0
        for (p in 0 until count) {''', '''        val out = ByteArray(workCount * 2)
        var o = 0
        for (p in 0 until workCount) {''', 1),

    ("replace", LDM, '''        PLog.i(TAG, "Linear DNG merge: ${frames.size} frames -> ${w}x$h recovered=$highlightRecovered")
        return LinearDngResult(
            cfaBuffer = ByteBuffer.wrap(out),
            width = w,
            height = h,''', '''        PLog.i(
            TAG,
            "Linear DNG merge: ${frames.size} frames -> ${workW}x$workH (degrade=1/$degrade) " +
                "recovered=$highlightRecovered"
        )
        return LinearDngResult(
            cfaBuffer = ByteBuffer.wrap(out),
            width = workW,
            height = workH,''', 1),
]

# ============================================================ P1-10 HDR 最小帧数
OPS += [
    ("replace", MFS, "        if (frames.size < 3) return null", '''        // 最小帧数由配置侧决定（非包围 1 帧、包围 2 帧）；<2 帧无法构成 HDR，交回上层回退单帧。
        val minFrames = MultiFrameConfig.MIN_HDR_PLUS_BRACKET_FRAME_COUNT // = 2
        if (frames.size < minFrames) {
            PLog.w(TAG, "HDR YUV stack needs >=$minFrames frames, got ${frames.size}; fallback single frame")
            frames.forEach { it.image.close() }
            return null
        }''', 1),
]

# ============================================================ P0-4 / P1-12 / P2-6 / P2-8 CameraScreen
OPS += [
    ("replace", CMS, "    var starTrailsEnabled by remember { mutableStateOf(false) }",
     "    val starTrailsEnabled = viewModel.starTrailsEnabled", 1),
    ("replace", CMS, "                                onStarTrailsToggle = { starTrailsEnabled = it },",
     "                                onStarTrailsToggle = viewModel::setStarTrailsEnabled,", 1),
    ("replace", CMS, "                                starTrailsEnabled = starTrailsEnabled,",
     "                                starTrailsEnabled = viewModel.starTrailsEnabled,", 1),

    ("replace", CMS, "    var serverEnhancementEnabled by remember { mutableStateOf(false) }",
     "    val serverEnhancementEnabled = viewModel.serverEnhancementEnabled", 1),
    ("replace", CMS, "                                onServerEnhancementToggle = { serverEnhancementEnabled = it },",
     "                                onServerEnhancementToggle = viewModel::setServerEnhancementEnabled,", 1),

    ("replace", CMS, "                        viewModel.glSurfaceView?.capturePreviewFrame(maxLongEdge = 1440) { bitmap ->",
     '''                        viewModel.glSurfaceView?.capturePreviewFrame(
                            maxLongEdge = PreviewCaptureLimits.thumbLongEdge()
                        ) { bitmap ->''', 1),

    ("replace", CMS, '''                            val darkEnvironment = state.iso >= 1600 ||
                                state.shutterSpeed > 1_500_000_000L''',
     '''                            val darkEnvironment = state.iso >= PhotographyThresholds.darkIso() ||
                                state.shutterSpeed > PhotographyThresholds.darkShutterNs()''', 1),

    ("append", CMS, '''

/** 缩略图/预览长边上限（P2-6：默认 1440 与原硬编码一致，零回归）。 */
object PreviewCaptureLimits {
    const val DEFAULT_THUMB_LONG_EDGE = 1440
    @Volatile var userThumbLongEdge: Int = DEFAULT_THUMB_LONG_EDGE
    fun thumbLongEdge(): Int = userThumbLongEdge.coerceIn(320, 8192)
}

/** 取景器「暗环境」提示阈值（P2-8：默认 1600 / 1.5s 与原硬编码一致，零回归）。 */
object PhotographyThresholds {
    const val DEFAULT_DARK_ISO = 1600
    const val DEFAULT_DARK_SHUTTER_NS = 1_500_000_000L
    @Volatile var userDarkIso: Int = DEFAULT_DARK_ISO
    @Volatile var userDarkShutterNs: Long = DEFAULT_DARK_SHUTTER_NS
    fun darkIso(): Int = userDarkIso.coerceIn(100, 102_400)
    fun darkShutterNs(): Long = userDarkShutterNs.coerceIn(1_000_000L, 60_000_000_000L)
}
'''),
]
