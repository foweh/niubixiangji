package com.hinnka.mycamera.appgallery

import com.hinnka.mycamera.utils.PLog

/**
 * 应用内置独立相册 · 主题模式（深色模式策略）。
 *
 * - [FOLLOW_SYSTEM]：跟随系统（默认）。
 * - [LIGHT] / [DARK]：手动强制，用于用户明确锁定外观。
 */
enum class AppGalleryThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromPersistedName(name: String?): AppGalleryThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FOLLOW_SYSTEM
    }
}

/**
 * 应用内置独立相册 · 全量可调参数（开放度硬约束：默认值 + 范围 + clamp）。
 *
 * 本类是**纯 Kotlin**（不依赖 Android 框架），因此可被 JVM 单测直接覆盖；
 * Android 侧通过 [AppGallerySettingsStore] 读写 DataStore，经 [clamp] 后再落到运行时。
 *
 * 所有参数都可在 UI（设置页挂点见补丁）或 DataStore 中修改；越界值一律 clamp，不崩溃。
 */
data class AppGalleryConfig(
    // ── 视图与浏览 ───────────────────────────────────────────────
    /** 默认视图模式。 */
    val defaultViewMode: AppGalleryViewMode = AppGalleryViewMode.GRID,
    /** 网格列数（1..10）。 */
    val gridColumnCount: Int = 3,
    /** 列表行高（dp，48..160）。 */
    val listRowHeightDp: Int = 72,
    /** 时间线聚类粒度。 */
    val timelineGranularity: TimelineGranularity = TimelineGranularity.DAY,
    /** 是否按相册/文件夹分别记忆视图模式（切回时恢复上次的视图 + 滚动锚点）。 */
    val rememberViewPerFolder: Boolean = true,
    /** 视图模式记忆条目的上限（1..64），超出按最近使用淘汰。 */
    val viewMemorySlots: Int = 16,

    // ── 缩略图（重任务：批量生成走 BgJobRunner） ─────────────────
    /** 生成缩略图的长边像素（128..2048）。 */
    val thumbnailMaxEdgePx: Int = 512,
    /** 缩略图 JPEG 质量（40..100）。 */
    val thumbnailQuality: Int = 85,
    /** 列表预取距离（0..100，单位=条目数）。 */
    val thumbnailPrefetchDistance: Int = 12,
    /** 是否在后台补齐缺失缩略图。 */
    val autoGenerateMissingThumbnails: Boolean = true,
    /** 缩略图批量生成的每片条目数（1..256）。 */
    val thumbnailGenerationBatch: Int = 32,

    // ── 回收站（删除/恢复/到期自动清空） ─────────────────────────
    /** 删除是否先入回收站（关=直接物理删除）。 */
    val trashEnabled: Boolean = true,
    /** 回收站保留期（天，1..3650，默认 30）。 */
    val trashRetentionDays: Int = 30,
    /** 是否到期自动清空。 */
    val trashAutoPurge: Boolean = true,
    /** 自动清理的检查间隔（小时，1..168）。 */
    val trashPurgeCheckIntervalHours: Int = 12,

    // ── 重复检测（pHash，后台任务，阈值可配） ────────────────────
    val duplicateDetectionEnabled: Boolean = true,
    /** 哈希边长（仅 8 或 16；8→64bit，16→256bit）。 */
    val duplicateHashSize: Int = 8,
    /** 计算哈希前的降采样边长（16..64）。 */
    val duplicateResizeEdge: Int = 32,
    /** 判重汉明距离阈值（8 位宽 0..64 / 16 位宽 0..256）。 */
    val duplicateHammingThreshold: Int = 10,
    /** true=aHash（更快、更糙）；false=pHash（DCT，默认）。 */
    val duplicateUseAverageHash: Boolean = false,
    /** 重复检测每片处理的条目数（1..256）。 */
    val duplicateScanBatch: Int = 24,

    // ── 统计 ────────────────────────────────────────────────────
    val statsDefaultGranularity: TimelineGranularity = TimelineGranularity.MONTH,
    val statsIncludeVideos: Boolean = true,

    // ── 搜索与排序 ──────────────────────────────────────────────
    /** 搜索输入防抖（毫秒，0..2000）。 */
    val searchDebounceMs: Int = 250,
    val searchCaseSensitive: Boolean = false,
    /** 搜索是否包含标签。 */
    val searchIncludeTags: Boolean = true,
    val defaultSortField: AppGallerySortField = AppGallerySortField.DATE,
    val defaultSortAscending: Boolean = false,

    // ── 收藏 / 标签 / 评分 ──────────────────────────────────────
    /** 评分上限（1..10，默认 5）。 */
    val maxRating: Int = 5,
    /** 单张照片标签数上限（1..100，默认 20）。 */
    val maxTagCount: Int = 20,
    /** 单个标签最大长度（1..64，默认 24）。 */
    val maxTagLength: Int = 24,

    // ── 地点聚类 ────────────────────────────────────────────────
    val placeClusterEnabled: Boolean = true,
    /** 同簇半径（公里，0.5..2000，默认 25）。 */
    val placeClusterRadiusKm: Float = 25f,
    /** 网格聚类保留的小数位（0..4），越大簇越细。 */
    val placeClusterGridDecimals: Int = 1,
    /** 是否使用内置离线城市边界表做「坐标→城市名」反查。 */
    val placeClusterUseOfflineCityIndex: Boolean = true,
    /** 是否允许联网反查（默认关：需网络/用户自选，见 [AppGalleryPlaceClustering]）。 */
    val placeClusterOnlineLookupEnabled: Boolean = false,

    // ── 批量 / 导出 / 备份 ──────────────────────────────────────
    /** 批量操作每批条目数（1..512）。 */
    val batchOperationBatchSize: Int = 16,
    /** 导出 zip 压缩级别（0..9，0=仅存储）。 */
    val exportZipCompressionLevel: Int = 6,
    /** 导出文件名模式，支持 `{date}` / `{name}` / `{index}` 占位符。 */
    val exportFileNamePattern: String = "{date}_{name}",
    /** 导出文件名最大长度（16..200）。 */
    val exportMaxNameLength: Int = 96,
    /** 备份/转存开关。 */
    val backupEnabled: Boolean = false,
    /** 备份目标子目录名（用户目录下）。 */
    val backupTargetFolderName: String = "PhotonCameraBackup",

    // ── 与相机输出联动 ──────────────────────────────────────────
    /** 自动重扫间隔（分钟，0..1440；0=不自动重扫）。 */
    val autoRescanIntervalMinutes: Int = 5,
    /** 是否监听相机输出目录，新产物落盘后自动归类。 */
    val watchCameraOutput: Boolean = true,

    // ── 外观 ────────────────────────────────────────────────────
    val themeMode: AppGalleryThemeMode = AppGalleryThemeMode.FOLLOW_SYSTEM,
) {
    companion object {
        /**
         * clamp 修正日志的输出通道。默认走 [PLog]；JVM 单测里可替换为纯内存收集器
         * （PLog 内部依赖 android.util.Log，纯 JVM 下不可用）。生产代码请勿修改。
         */
        @JvmStatic
        var clampLogSink: (String) -> Unit = { message -> PLog.w(TAG, message) }

        const val GRID_COLUMN_MIN = 1
        const val GRID_COLUMN_MAX = 10
        const val LIST_ROW_HEIGHT_MIN_DP = 48
        const val LIST_ROW_HEIGHT_MAX_DP = 160
        const val VIEW_MEMORY_SLOTS_MIN = 1
        const val VIEW_MEMORY_SLOTS_MAX = 64

        const val THUMBNAIL_EDGE_MIN = 128
        const val THUMBNAIL_EDGE_MAX = 2048
        const val THUMBNAIL_QUALITY_MIN = 40
        const val THUMBNAIL_QUALITY_MAX = 100
        const val THUMBNAIL_PREFETCH_MIN = 0
        const val THUMBNAIL_PREFETCH_MAX = 100
        const val THUMBNAIL_BATCH_MIN = 1
        const val THUMBNAIL_BATCH_MAX = 256

        const val TRASH_RETENTION_MIN_DAYS = 1
        const val TRASH_RETENTION_MAX_DAYS = 3650
        const val TRASH_PURGE_INTERVAL_MIN_HOURS = 1
        const val TRASH_PURGE_INTERVAL_MAX_HOURS = 168

        /** 允许的哈希边长（其余值就近吸附到 8）。 */
        val DUPLICATE_HASH_SIZES = intArrayOf(8, 16)
        const val DUPLICATE_RESIZE_EDGE_MIN = 16
        const val DUPLICATE_RESIZE_EDGE_MAX = 64
        const val DUPLICATE_SCAN_BATCH_MIN = 1
        const val DUPLICATE_SCAN_BATCH_MAX = 256

        const val SEARCH_DEBOUNCE_MIN_MS = 0
        const val SEARCH_DEBOUNCE_MAX_MS = 2000

        const val MAX_RATING_MIN = 1
        const val MAX_RATING_MAX = 10
        const val MAX_TAG_COUNT_MIN = 1
        const val MAX_TAG_COUNT_MAX = 100
        const val MAX_TAG_LENGTH_MIN = 1
        const val MAX_TAG_LENGTH_MAX = 64

        const val PLACE_RADIUS_MIN_KM = 0.5f
        const val PLACE_RADIUS_MAX_KM = 2000f
        const val PLACE_DECIMALS_MIN = 0
        const val PLACE_DECIMALS_MAX = 4

        const val BATCH_SIZE_MIN = 1
        const val BATCH_SIZE_MAX = 512
        const val ZIP_LEVEL_MIN = 0
        const val ZIP_LEVEL_MAX = 9
        const val EXPORT_NAME_MIN_LENGTH = 16
        const val EXPORT_NAME_MAX_LENGTH = 200

        const val AUTO_RESCAN_MIN_MINUTES = 0
        const val AUTO_RESCAN_MAX_MINUTES = 1440

        /** 8 位宽的汉明距离上限 = 8*8。 */
        const val HAMMING_LIMIT_8 = 64

        /** 16 位宽的汉明距离上限 = 16*16。 */
        const val HAMMING_LIMIT_16 = 256

        /** 任意位宽下的汉明距离上限。 */
        fun hammingLimitFor(hashSize: Int): Int =
            if (hashSize == 16) HAMMING_LIMIT_16 else HAMMING_LIMIT_8

        /**
         * 全参数 clamp：任何越界/非法输入都被修正到安全区间，绝不抛异常。
         * 修正项会写入日志（见 [clampAndLog]）。
         */
        fun clamp(config: AppGalleryConfig): AppGalleryConfig {
            val hashSize = if (config.duplicateHashSize in DUPLICATE_HASH_SIZES) {
                config.duplicateHashSize
            } else {
                DUPLICATE_HASH_SIZES.minByOrNull { kotlin.math.abs(it - config.duplicateHashSize) } ?: 8
            }
            val hammingLimit = hammingLimitFor(hashSize)
            val pattern = config.exportFileNamePattern
                .takeIf { it.isNotBlank() } ?: "{date}_{name}"
            val backupName = config.backupTargetFolderName
                .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }
                .trim()
                .takeIf { it.isNotEmpty() } ?: "PhotonCameraBackup"
            return config.copy(
                gridColumnCount = config.gridColumnCount.coerceIn(GRID_COLUMN_MIN, GRID_COLUMN_MAX),
                listRowHeightDp = config.listRowHeightDp.coerceIn(
                    LIST_ROW_HEIGHT_MIN_DP, LIST_ROW_HEIGHT_MAX_DP
                ),
                viewMemorySlots = config.viewMemorySlots.coerceIn(
                    VIEW_MEMORY_SLOTS_MIN, VIEW_MEMORY_SLOTS_MAX
                ),
                thumbnailMaxEdgePx = config.thumbnailMaxEdgePx.coerceIn(
                    THUMBNAIL_EDGE_MIN, THUMBNAIL_EDGE_MAX
                ),
                thumbnailQuality = config.thumbnailQuality.coerceIn(
                    THUMBNAIL_QUALITY_MIN, THUMBNAIL_QUALITY_MAX
                ),
                thumbnailPrefetchDistance = config.thumbnailPrefetchDistance.coerceIn(
                    THUMBNAIL_PREFETCH_MIN, THUMBNAIL_PREFETCH_MAX
                ),
                thumbnailGenerationBatch = config.thumbnailGenerationBatch.coerceIn(
                    THUMBNAIL_BATCH_MIN, THUMBNAIL_BATCH_MAX
                ),
                trashRetentionDays = config.trashRetentionDays.coerceIn(
                    TRASH_RETENTION_MIN_DAYS, TRASH_RETENTION_MAX_DAYS
                ),
                trashPurgeCheckIntervalHours = config.trashPurgeCheckIntervalHours.coerceIn(
                    TRASH_PURGE_INTERVAL_MIN_HOURS, TRASH_PURGE_INTERVAL_MAX_HOURS
                ),
                duplicateHashSize = hashSize,
                duplicateResizeEdge = config.duplicateResizeEdge.coerceIn(
                    DUPLICATE_RESIZE_EDGE_MIN, DUPLICATE_RESIZE_EDGE_MAX
                ),
                duplicateHammingThreshold = config.duplicateHammingThreshold
                    .coerceIn(0, hammingLimit),
                duplicateScanBatch = config.duplicateScanBatch.coerceIn(
                    DUPLICATE_SCAN_BATCH_MIN, DUPLICATE_SCAN_BATCH_MAX
                ),
                searchDebounceMs = config.searchDebounceMs.coerceIn(
                    SEARCH_DEBOUNCE_MIN_MS, SEARCH_DEBOUNCE_MAX_MS
                ),
                maxRating = config.maxRating.coerceIn(MAX_RATING_MIN, MAX_RATING_MAX),
                maxTagCount = config.maxTagCount.coerceIn(MAX_TAG_COUNT_MIN, MAX_TAG_COUNT_MAX),
                maxTagLength = config.maxTagLength.coerceIn(MAX_TAG_LENGTH_MIN, MAX_TAG_LENGTH_MAX),
                placeClusterRadiusKm = config.placeClusterRadiusKm.coerceIn(
                    PLACE_RADIUS_MIN_KM, PLACE_RADIUS_MAX_KM
                ),
                placeClusterGridDecimals = config.placeClusterGridDecimals.coerceIn(
                    PLACE_DECIMALS_MIN, PLACE_DECIMALS_MAX
                ),
                batchOperationBatchSize = config.batchOperationBatchSize.coerceIn(
                    BATCH_SIZE_MIN, BATCH_SIZE_MAX
                ),
                exportZipCompressionLevel = config.exportZipCompressionLevel.coerceIn(
                    ZIP_LEVEL_MIN, ZIP_LEVEL_MAX
                ),
                exportFileNamePattern = pattern,
                exportMaxNameLength = config.exportMaxNameLength.coerceIn(
                    EXPORT_NAME_MIN_LENGTH, EXPORT_NAME_MAX_LENGTH
                ),
                backupTargetFolderName = backupName,
                autoRescanIntervalMinutes = config.autoRescanIntervalMinutes.coerceIn(
                    AUTO_RESCAN_MIN_MINUTES, AUTO_RESCAN_MAX_MINUTES
                ),
            )
        }

        /**
         * clamp 并记录被修正的项（PATTERN_SPEC §5：clamp 后日志记录被修正的项）。
         * 无修正时静默返回，避免刷日志。
         */
        fun clampAndLog(config: AppGalleryConfig, tag: String = TAG): AppGalleryConfig {
            val clamped = clamp(config)
            if (clamped != config) {
                val diffs = buildList {
                    if (clamped.gridColumnCount != config.gridColumnCount) {
                        add("gridColumnCount ${config.gridColumnCount}→${clamped.gridColumnCount}")
                    }
                    if (clamped.thumbnailMaxEdgePx != config.thumbnailMaxEdgePx) {
                        add("thumbnailMaxEdgePx ${config.thumbnailMaxEdgePx}→${clamped.thumbnailMaxEdgePx}")
                    }
                    if (clamped.trashRetentionDays != config.trashRetentionDays) {
                        add("trashRetentionDays ${config.trashRetentionDays}→${clamped.trashRetentionDays}")
                    }
                    if (clamped.duplicateHashSize != config.duplicateHashSize) {
                        add("duplicateHashSize ${config.duplicateHashSize}→${clamped.duplicateHashSize}")
                    }
                    if (clamped.duplicateHammingThreshold != config.duplicateHammingThreshold) {
                        add(
                            "duplicateHammingThreshold ${config.duplicateHammingThreshold}→" +
                                "${clamped.duplicateHammingThreshold}"
                        )
                    }
                    if (clamped.placeClusterRadiusKm != config.placeClusterRadiusKm) {
                        add("placeClusterRadiusKm ${config.placeClusterRadiusKm}→${clamped.placeClusterRadiusKm}")
                    }
                    if (clamped.exportZipCompressionLevel != config.exportZipCompressionLevel) {
                        add("exportZipCompressionLevel ${config.exportZipCompressionLevel}→" +
                            "${clamped.exportZipCompressionLevel}")
                    }
                }
                // 经由可替换的 sink 输出，保证纯 JVM 下可测且不触碰 android.util.Log。
                runCatching { clampLogSink("[$tag] AppGalleryConfig clamped: ${diffs.joinToString()}") }
            }
            return clamped
        }

        private const val TAG = "AppGalleryConfig"
    }
}
