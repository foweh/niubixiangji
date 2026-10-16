package com.hinnka.mycamera.superres

/**
 * 超分任务的速度/质量档位（同时决定 ORT 线程数与默认 tile 尺寸，CPU 占用与画质的折中）。
 *
 * FAST     = 少线程、小 tile、低 tiling 重叠，最快出图（后台批量预览）；
 * BALANCED = 默认档，画质/耗时折中；
 * QUALITY  = 多线程、大 tile、高重叠，拼缝最不明显（成片导出用）。
 */
enum class SuperResQualityTier { FAST, BALANCED, QUALITY }

/** 推理后端：CPU 全局可用；NNAPI 仅部分设备/算子集可用，不可用时自动回退 CPU。 */
enum class SuperResAccelMode { CPU, NNAPI }

/**
 * 端侧 AI 超分 / 风格迁移 / 图像修复模块的**全开放配置**。
 *
 * 设计约定（PATTERN_SPEC 第 1.6 / 第 5 节）：
 *  - 每个可调参数都有默认值、`MIN..MAX` 取值范围与 [clamp] 越界修正；越界不崩溃。
 *  - 所有数值单位在字段 KDoc 里写明；不以魔法数散落在算法实现里。
 *  - [clampWithReport] 会把被修正的字段名回报出来，供调用方写日志。
 *
 * 读取路径（两条，二选一或并存）：
 *  ① 配置文件/DataStore：[SuperResConfigStore]（DataStore 文件名 `superres_preferences`）；
 *  ② BgJobRunner 任务参数：调用方直接构造本对象传给 [SuperResPipeline]。
 */
data class SuperResConfig(
    /** 总开关。关闭时模块入口对用户灰化，走原图。 */
    val enabled: Boolean = true,

    /** 超分倍率。1 = 仅增强不放大，2/4 = 需对应模型；范围 [SCALE_MIN]..[SCALE_MAX]。 */
    val scale: Int = 2,

    /** 单 tile 边长（像素）。范围 [TILE_MIN]..[TILE_MAX]。 */
    val tileSize: Int = TILE_DEFAULT,

    /** 相邻 tile 重叠宽度（像素）。范围 0..tileSize/2，重叠越大拼缝越淡、耗时越长。 */
    val tileOverlap: Int = DEFAULT_TILE_OVERLAP,

    /** AI 结果与原图/双边参考的混合强度。0 = 完全保留原图，1 = 完全采用 AI 结果。 */
    val strength: Float = 1f,

    /** 速度/质量档位，决定线程与拼缝淡化的默认激进程度。 */
    val qualityTier: SuperResQualityTier = SuperResQualityTier.BALANCED,

    /** 推理后端。NNAPI 不可用时由引擎自动回退 CPU，不抛异常。 */
    val accelMode: SuperResAccelMode = SuperResAccelMode.CPU,

    /** ORT 单次会话内部线程数。范围 [THREADS_MIN]..[THREADS_MAX]。 */
    val threads: Int = 2,

    /** 风格迁移开关（需先下载/导入对应的 ONNX 风格模型）。 */
    val enableStyleTransfer: Boolean = false,

    /** 风格迁移强度。0 = 原图，1 = 完全风格化。 */
    val styleTransferStrength: Float = 0.8f,

    /** 选中的风格模型 id（null = 用该类型下的默认模型）。 */
    val styleModelId: String? = null,

    /** 图像修复（inpainting）开关（需先下载/导入 LaMa 思路的 ONNX 模型）。 */
    val enableInpainting: Boolean = false,

    /** 掩膜羽化半径（像素）。0 = 硬边，范围 0..[INPAINT_FEATHER_MAX]。 */
    val inpaintFeatherPx: Int = 8,

    /** 掩膜判定阈值（alpha/亮度归一化后）。范围 0..1，越大掩膜越“瘦”。 */
    val inpaintMaskThreshold: Float = 0.5f,

    /**
     * 工作分辨率像素上限（宽×高）。超出时先把输入降采样到该上限再推理，
     * 内存不足时进一步下调。0 = 不限（不推荐，64MP 图会 OOM）。
     */
    val maxWorkingPixels: Long = DEFAULT_MAX_WORKING_PIXELS,

    /** 本模块在 BgJobRunner 的内存预算（字节）。范围 [MEM_MIN]..[MEM_MAX]。 */
    val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET,

    /** 每个后台分片最多处理多少个 tile。范围 [TILES_PER_CHUNK_MIN]..[TILES_PER_CHUNK_MAX]。 */
    val maxTilesPerChunk: Int = 4,

    /** 输出是否保留 alpha 通道（修复场景需要）。 */
    val keepOutputAlpha: Boolean = true,

    /** 内存不足时是否自动降级（降 tile / 降工作分辨率）而不是失败。 */
    val degradeInsteadOfFail: Boolean = true,

    /** 输出 JPEG 质量（仅当输出为 JPEG 时生效）。范围 [JPEG_Q_MIN]..[JPEG_Q_MAX]。 */
    val jpegQuality: Int = 95,
) {
    /** 拼缝淡化宽度（像素）：重叠的一半在两侧对分，重叠为 0 时退化为不淡化。 */
    val effectiveSeamWidthPx: Int get() = (tileOverlap / 2).coerceAtLeast(0)

    companion object {
        const val SCALE_MIN = 1
        const val SCALE_MAX = 4

        const val TILE_MIN = 64
        const val TILE_MAX = 1024
        const val TILE_DEFAULT = 256

        const val DEFAULT_TILE_OVERLAP = 32

        const val THREADS_MIN = 1
        const val THREADS_MAX = 8

        const val INPAINT_FEATHER_MAX = 64

        const val TILES_PER_CHUNK_MIN = 1
        const val TILES_PER_CHUNK_MAX = 64

        const val JPEG_Q_MIN = 60
        const val JPEG_Q_MAX = 100

        const val MEM_MIN = 16L * 1024 * 1024
        const val MEM_MAX = 2048L * 1024 * 1024
        const val DEFAULT_MEMORY_BUDGET = 192L * 1024 * 1024

        /** 默认工作分辨率上限 ≈ 12MP（K50G 的 64MP 直出必须降采样后再推理）。 */
        const val DEFAULT_MAX_WORKING_PIXELS = 12_000_000L
        const val MAX_WORKING_PIXELS_MAX = 200_000_000L

        /** 档位 → 默认 ORT 线程数。 */
        fun defaultThreadsFor(tier: SuperResQualityTier): Int = when (tier) {
            SuperResQualityTier.FAST -> 1
            SuperResQualityTier.BALANCED -> 2
            SuperResQualityTier.QUALITY -> 4
        }

        /** 档位 → 建议 tile 边长（像素）。 */
        fun recommendedTileSizeFor(tier: SuperResQualityTier): Int = when (tier) {
            SuperResQualityTier.FAST -> 128
            SuperResQualityTier.BALANCED -> TILE_DEFAULT
            SuperResQualityTier.QUALITY -> 512
        }

        /**
         * 越界修正：任何取值都落在合法区间内，保证后续算法不会因误设而崩溃。
         * 注意 clamp 顺序——`tileOverlap` 的上界依赖 `tileSize`，因此先修 tileSize。
         */
        fun clamp(config: SuperResConfig): SuperResConfig = clampWithReport(config).config

        /** 同 [clamp]，但额外回报被修正的字段名（供日志/UI 提示）。 */
        fun clampWithReport(config: SuperResConfig): SuperResClampResult {
            val corrected = ArrayList<String>(8)

            fun <T> fix(name: String, before: T, after: T): T {
                if (before != after) corrected.add(name)
                return after
            }

            val tileSize = fix(
                "tileSize",
                config.tileSize,
                config.tileSize.coerceIn(TILE_MIN, TILE_MAX),
            )
            // 重叠不得超过 tile 的一半，否则 tiling 会退化为无效切分。
            val overlapMax = (tileSize / 2).coerceAtLeast(0)
            val overlap = fix(
                "tileOverlap",
                config.tileOverlap,
                config.tileOverlap.coerceIn(0, overlapMax),
            )
            val maxWorking = fix(
                "maxWorkingPixels",
                config.maxWorkingPixels,
                config.maxWorkingPixels.coerceIn(0L, MAX_WORKING_PIXELS_MAX),
            )
            val memBudget = fix(
                "memoryBudgetBytes",
                config.memoryBudgetBytes,
                config.memoryBudgetBytes.coerceIn(MEM_MIN, MEM_MAX),
            )

            val clamped = config.copy(
                scale = fix("scale", config.scale, config.scale.coerceIn(SCALE_MIN, SCALE_MAX)),
                tileSize = tileSize,
                tileOverlap = overlap,
                strength = fix("strength", config.strength, config.strength.coerceIn(0f, 1f)),
                threads = fix(
                    "threads",
                    config.threads,
                    config.threads.coerceIn(THREADS_MIN, THREADS_MAX),
                ),
                styleTransferStrength = fix(
                    "styleTransferStrength",
                    config.styleTransferStrength,
                    config.styleTransferStrength.coerceIn(0f, 1f),
                ),
                inpaintFeatherPx = fix(
                    "inpaintFeatherPx",
                    config.inpaintFeatherPx,
                    config.inpaintFeatherPx.coerceIn(0, INPAINT_FEATHER_MAX),
                ),
                inpaintMaskThreshold = fix(
                    "inpaintMaskThreshold",
                    config.inpaintMaskThreshold,
                    config.inpaintMaskThreshold.coerceIn(0f, 1f),
                ),
                maxWorkingPixels = maxWorking,
                memoryBudgetBytes = memBudget,
                maxTilesPerChunk = fix(
                    "maxTilesPerChunk",
                    config.maxTilesPerChunk,
                    config.maxTilesPerChunk.coerceIn(TILES_PER_CHUNK_MIN, TILES_PER_CHUNK_MAX),
                ),
                jpegQuality = fix(
                    "jpegQuality",
                    config.jpegQuality,
                    config.jpegQuality.coerceIn(JPEG_Q_MIN, JPEG_Q_MAX),
                ),
                styleModelId = config.styleModelId?.takeIf { it.isNotBlank() },
            )
            // 非有限浮点（NaN/Inf）会让后续混合运算产出全黑图，直接拉回默认值。
            return SuperResClampResult(
                config = clamped.copy(
                    strength = sanitizeFloat(clamped.strength, 1f, "strength", corrected),
                    styleTransferStrength = sanitizeFloat(
                        clamped.styleTransferStrength, 0.8f, "styleTransferStrength", corrected,
                    ),
                    inpaintMaskThreshold = sanitizeFloat(
                        clamped.inpaintMaskThreshold, 0.5f, "inpaintMaskThreshold", corrected,
                    ),
                ),
                corrected = corrected,
            )
        }

        private fun sanitizeFloat(
            value: Float,
            fallback: Float,
            name: String,
            corrected: MutableList<String>,
        ): Float {
            if (value.isFinite()) return value.coerceIn(0f, 1f)
            if (name !in corrected) corrected.add(name)
            return fallback
        }

        /**
         * 内存压力降级：减小 tile（峰值内存与 tile² 成正比）并压低工作分辨率，
         * 返回结果一定经过 [clamp]。用于 `job.guardMemory()` 返回 false 时**降级而非失败**。
         */
        fun degradeForMemoryPressure(config: SuperResConfig): SuperResConfig {
            val tierTile = recommendedTileSizeFor(config.qualityTier).coerceAtMost(config.tileSize)
            val nextTile = (config.tileSize / 2).coerceAtLeast(TILE_MIN).coerceAtMost(tierTile)
            val nextWorking = if (config.maxWorkingPixels <= 0L) {
                DEFAULT_MAX_WORKING_PIXELS
            } else {
                (config.maxWorkingPixels * 3L / 4L).coerceAtLeast(1_000_000L)
            }
            return clamp(
                config.copy(
                    tileSize = nextTile,
                    tileOverlap = config.tileOverlap.coerceAtMost(nextTile / 2),
                    maxWorkingPixels = nextWorking,
                    maxTilesPerChunk = (config.maxTilesPerChunk / 2).coerceAtLeast(TILES_PER_CHUNK_MIN),
                )
            )
        }
    }
}

/** [SuperResConfig.clampWithReport] 的结果：修正后的配置 + 被修正的字段名。 */
data class SuperResClampResult(
    val config: SuperResConfig,
    val corrected: List<String>,
)
