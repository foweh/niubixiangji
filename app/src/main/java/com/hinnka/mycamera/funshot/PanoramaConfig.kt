package com.hinnka.mycamera.funshot

/** 全景投影方式：[PLANAR] 平面拼接（默认），[CYLINDRICAL] 柱面投影后拼接（水平大角度更稳）。 */
enum class PanoramaQuality { PLANAR, CYLINDRICAL }

/**
 * 全景拼接配置（开放全部可调参数，默认值 + 范围 + clamp）。
 *
 * 字段范围（clamp 后一定合法）：
 * - [frameCount] 2 ~ 100（输入卫生上限，非算法上限）
 * - [focalLengthPx] 0（自动）或 64 ~ 8192；[autoFocalRatio] 0.4 ~ 2.0
 * - [maxCorners] 100 ~ 4000；[cornerQuality] 0.0001 ~ 0.2；[minCornerDistance] 2 ~ 64
 * - [descriptorBits] 64 ~ 512（64 的倍数）；[matchRatio] 0.5 ~ 0.95
 * - [ransacIterations] 100 ~ 20000；[ransacThresholdPx] 0.5 ~ 20
 * - [minInliers] 4 ~ 200；[minInlierRatio] 0.05 ~ 1
 * - [featherRadiusPx] 0 ~ 128；[gainLimit] 1 ~ 4；[blendGamma] 0.2 ~ 3
 * - [inputMaxSide] 512 ~ 4096；[maxOutputPixels] 4M ~ 256M
 * - [outputJpegQuality] 60 ~ 100；[memoryBudgetBytes] 32M ~ 2G
 */
data class PanoramaConfig(
    /** 参与拼接的帧数（2 ~ 100）。 */
    val frameCount: Int = 6,
    val quality: PanoramaQuality = PanoramaQuality.PLANAR,
    /** 柱面投影焦距（像素）；0 = 自动（短边 × [autoFocalRatio]）。 */
    val focalLengthPx: Float = 0f,
    val autoFocalRatio: Float = 0.9f,
    val maxCorners: Int = 800,
    val cornerQuality: Float = 0.01f,
    val minCornerDistance: Int = 8,
    /** BRIEF 描述子位数（64 的倍数）。 */
    val descriptorBits: Int = 256,
    val matchRatio: Float = 0.72f,
    val ransacIterations: Int = 2000,
    val ransacThresholdPx: Float = 3.5f,
    val minInliers: Int = 12,
    val minInlierRatio: Float = 0.2f,
    /** 拼接前逐帧曝光补偿（均值亮度对齐）。 */
    val exposureCompensation: Boolean = true,
    /** 曝光补偿增益上限（避免过曝/欠曝帧被拉爆）。 */
    val gainLimit: Float = 1.6f,
    val featherRadiusPx: Int = 24,
    val blendGamma: Float = 1f,
    val workingScale: FunshotWorkingScale = FunshotWorkingScale.THREE_QUARTER,
    val inputMaxSide: Int = 1600,
    val maxOutputPixels: Long = 64L * 1024 * 1024,
    val outputJpegQuality: Int = 95,
    val memoryBudgetBytes: Long = 768L * 1024 * 1024,
    /** RANSAC 随机种子（可配，便于复现）。 */
    val randomSeed: Long = 20261016L,
    /** 内存不足时允许自动降工作分辨率（关闭则不降级、直接分片重试）。 */
    val autoDegrade: Boolean = true,
) {

    /** 有效焦距像素值（用于柱面投影）。 */
    fun effectiveFocalPx(frameWidth: Int, frameHeight: Int): Float {
        val auto = minOf(frameWidth, frameHeight) * autoFocalRatio
        return if (focalLengthPx > 0f) focalLengthPx else auto
    }

    fun coerced(): PanoramaConfig = coerceWithReport().value

    fun coerceWithReport(): FunshotClampReport<PanoramaConfig> {
        val fixed = ArrayList<String>()
        val c = copy(
            frameCount = FunshotClamp.int(
                frameCount, FunshotRanges.PANO_FRAME_MIN, FunshotRanges.PANO_FRAME_MAX,
                "frameCount", fixed,
            ),
            focalLengthPx = FunshotClamp.float(focalLengthPx, 0f, 8192f, 0f, "focalLengthPx", fixed),
            autoFocalRatio = FunshotClamp.float(autoFocalRatio, 0.4f, 2f, 0.9f, "autoFocalRatio", fixed),
            maxCorners = FunshotClamp.int(maxCorners, 100, 4000, "maxCorners", fixed),
            cornerQuality = FunshotClamp.float(cornerQuality, 0.0001f, 0.2f, 0.01f, "cornerQuality", fixed),
            minCornerDistance = FunshotClamp.int(minCornerDistance, 2, 64, "minCornerDistance", fixed),
            descriptorBits = normalizeDescriptorBits(descriptorBits, fixed),
            matchRatio = FunshotClamp.float(matchRatio, 0.5f, 0.95f, 0.72f, "matchRatio", fixed),
            ransacIterations = FunshotClamp.int(ransacIterations, 100, 20000, "ransacIterations", fixed),
            ransacThresholdPx = FunshotClamp.float(ransacThresholdPx, 0.5f, 20f, 3.5f, "ransacThresholdPx", fixed),
            minInliers = FunshotClamp.int(minInliers, 4, 200, "minInliers", fixed),
            minInlierRatio = FunshotClamp.float(minInlierRatio, 0.05f, 1f, 0.2f, "minInlierRatio", fixed),
            gainLimit = FunshotClamp.float(gainLimit, 1f, 4f, 1.6f, "gainLimit", fixed),
            featherRadiusPx = FunshotClamp.int(featherRadiusPx, 0, 128, "featherRadiusPx", fixed),
            blendGamma = FunshotClamp.float(blendGamma, 0.2f, 3f, 1f, "blendGamma", fixed),
            inputMaxSide = FunshotClamp.int(
                inputMaxSide, FunshotRanges.INPUT_MAX_SIDE_MIN, FunshotRanges.INPUT_MAX_SIDE_MAX,
                "inputMaxSide", fixed,
            ),
            maxOutputPixels = FunshotClamp.long(
                maxOutputPixels, 4_000_000L, 256_000_000L, "maxOutputPixels", fixed,
            ),
            outputJpegQuality = FunshotClamp.int(outputJpegQuality, 60, 100, "outputJpegQuality", fixed),
            memoryBudgetBytes = FunshotClamp.long(
                memoryBudgetBytes, 32L * 1024 * 1024, 2L * 1024 * 1024 * 1024, "memoryBudgetBytes", fixed,
            ),
            workingScale = FunshotWorkingScale.fromFactor(workingScale.factor),
        )
        return FunshotClampReport(c, fixed)
    }

    /** 描述子位数必须是 64 的倍数（内部按 Long 打包），否则向下取整并登记修正。 */
    private fun normalizeDescriptorBits(raw: Int, fixed: MutableList<String>): Int {
        val clamped = raw.coerceIn(64, 512)
        val aligned = (clamped / 64) * 64
        if (aligned != raw) fixed += "descriptorBits"
        return aligned
    }

    companion object {
        const val DEFAULT = 6
    }
}

/** 全景配置与 `key -> value` 的编解码（不依赖 Android，供 SharedPreferences 适配层复用）。 */
object PanoramaConfigCodec {

    fun encode(config: PanoramaConfig): Map<String, String> = mapOf(
        KEY_FRAME_COUNT to config.frameCount.toString(),
        KEY_QUALITY to config.quality.name,
        KEY_FOCAL to config.focalLengthPx.toString(),
        KEY_AUTO_FOCAL to config.autoFocalRatio.toString(),
        KEY_MAX_CORNERS to config.maxCorners.toString(),
        KEY_CORNER_QUALITY to config.cornerQuality.toString(),
        KEY_MIN_DIST to config.minCornerDistance.toString(),
        KEY_DESC_BITS to config.descriptorBits.toString(),
        KEY_MATCH_RATIO to config.matchRatio.toString(),
        KEY_RANSAC_ITERS to config.ransacIterations.toString(),
        KEY_RANSAC_THRESHOLD to config.ransacThresholdPx.toString(),
        KEY_MIN_INLIERS to config.minInliers.toString(),
        KEY_MIN_INLIER_RATIO to config.minInlierRatio.toString(),
        KEY_EXPOSURE_COMP to config.exposureCompensation.toString(),
        KEY_GAIN_LIMIT to config.gainLimit.toString(),
        KEY_FEATHER to config.featherRadiusPx.toString(),
        KEY_BLEND_GAMMA to config.blendGamma.toString(),
        KEY_WORKING_SCALE to config.workingScale.name,
        KEY_INPUT_MAX_SIDE to config.inputMaxSide.toString(),
        KEY_MAX_OUTPUT_PIXELS to config.maxOutputPixels.toString(),
        KEY_JPEG_QUALITY to config.outputJpegQuality.toString(),
        KEY_MEMORY_BUDGET to config.memoryBudgetBytes.toString(),
        KEY_SEED to config.randomSeed.toString(),
        KEY_AUTO_DEGRADE to config.autoDegrade.toString(),
    )

    /** 解码：缺失 / 损坏 / 未知枚举一律回落默认值，最后统一 clamp（不崩溃）。 */
    fun decode(read: (String) -> String?): FunshotClampReport<PanoramaConfig> {
        val defaults = PanoramaConfig()
        val raw = PanoramaConfig(
            frameCount = intOf(read, KEY_FRAME_COUNT, defaults.frameCount),
            quality = enumOf(read, KEY_QUALITY, defaults.quality),
            focalLengthPx = floatOf(read, KEY_FOCAL, defaults.focalLengthPx),
            autoFocalRatio = floatOf(read, KEY_AUTO_FOCAL, defaults.autoFocalRatio),
            maxCorners = intOf(read, KEY_MAX_CORNERS, defaults.maxCorners),
            cornerQuality = floatOf(read, KEY_CORNER_QUALITY, defaults.cornerQuality),
            minCornerDistance = intOf(read, KEY_MIN_DIST, defaults.minCornerDistance),
            descriptorBits = intOf(read, KEY_DESC_BITS, defaults.descriptorBits),
            matchRatio = floatOf(read, KEY_MATCH_RATIO, defaults.matchRatio),
            ransacIterations = intOf(read, KEY_RANSAC_ITERS, defaults.ransacIterations),
            ransacThresholdPx = floatOf(read, KEY_RANSAC_THRESHOLD, defaults.ransacThresholdPx),
            minInliers = intOf(read, KEY_MIN_INLIERS, defaults.minInliers),
            minInlierRatio = floatOf(read, KEY_MIN_INLIER_RATIO, defaults.minInlierRatio),
            exposureCompensation = boolOf(read, KEY_EXPOSURE_COMP, defaults.exposureCompensation),
            gainLimit = floatOf(read, KEY_GAIN_LIMIT, defaults.gainLimit),
            featherRadiusPx = intOf(read, KEY_FEATHER, defaults.featherRadiusPx),
            blendGamma = floatOf(read, KEY_BLEND_GAMMA, defaults.blendGamma),
            workingScale = FunshotWorkingScale.fromFactor(
                FunshotWorkingScale.entries.firstOrNull { it.name == read(KEY_WORKING_SCALE) }?.factor
                    ?: defaults.workingScale.factor,
            ),
            inputMaxSide = intOf(read, KEY_INPUT_MAX_SIDE, defaults.inputMaxSide),
            maxOutputPixels = longOf(read, KEY_MAX_OUTPUT_PIXELS, defaults.maxOutputPixels),
            outputJpegQuality = intOf(read, KEY_JPEG_QUALITY, defaults.outputJpegQuality),
            memoryBudgetBytes = longOf(read, KEY_MEMORY_BUDGET, defaults.memoryBudgetBytes),
            randomSeed = longOf(read, KEY_SEED, defaults.randomSeed),
            autoDegrade = boolOf(read, KEY_AUTO_DEGRADE, defaults.autoDegrade),
        )
        return raw.coerceWithReport()
    }

    private fun intOf(read: (String) -> String?, key: String, fallback: Int): Int =
        read(key)?.toIntOrNull() ?: fallback

    private fun longOf(read: (String) -> String?, key: String, fallback: Long): Long =
        read(key)?.toLongOrNull() ?: fallback

    private fun floatOf(read: (String) -> String?, key: String, fallback: Float): Float =
        read(key)?.toFloatOrNull() ?: fallback

    private fun boolOf(read: (String) -> String?, key: String, fallback: Boolean): Boolean =
        read(key)?.toBooleanStrictOrNull() ?: fallback

    private inline fun <reified T : Enum<T>> enumOf(read: (String) -> String?, key: String, fallback: T): T =
        read(key)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

    const val KEY_FRAME_COUNT = "funshot_pano_frame_count"
    const val KEY_QUALITY = "funshot_pano_quality"
    const val KEY_FOCAL = "funshot_pano_focal"
    const val KEY_AUTO_FOCAL = "funshot_pano_auto_focal"
    const val KEY_MAX_CORNERS = "funshot_pano_max_corners"
    const val KEY_CORNER_QUALITY = "funshot_pano_corner_quality"
    const val KEY_MIN_DIST = "funshot_pano_min_corner_dist"
    const val KEY_DESC_BITS = "funshot_pano_desc_bits"
    const val KEY_MATCH_RATIO = "funshot_pano_match_ratio"
    const val KEY_RANSAC_ITERS = "funshot_pano_ransac_iters"
    const val KEY_RANSAC_THRESHOLD = "funshot_pano_ransac_threshold"
    const val KEY_MIN_INLIERS = "funshot_pano_min_inliers"
    const val KEY_MIN_INLIER_RATIO = "funshot_pano_min_inlier_ratio"
    const val KEY_EXPOSURE_COMP = "funshot_pano_exposure_comp"
    const val KEY_GAIN_LIMIT = "funshot_pano_gain_limit"
    const val KEY_FEATHER = "funshot_pano_feather"
    const val KEY_BLEND_GAMMA = "funshot_pano_blend_gamma"
    const val KEY_WORKING_SCALE = "funshot_pano_working_scale"
    const val KEY_INPUT_MAX_SIDE = "funshot_pano_input_max_side"
    const val KEY_MAX_OUTPUT_PIXELS = "funshot_pano_max_output_pixels"
    const val KEY_JPEG_QUALITY = "funshot_pano_jpeg_quality"
    const val KEY_MEMORY_BUDGET = "funshot_pano_memory_budget"
    const val KEY_SEED = "funshot_pano_seed"
    const val KEY_AUTO_DEGRADE = "funshot_pano_auto_degrade"
}
