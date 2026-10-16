package com.hinnka.mycamera.appgallery

/**
 * 配置编解码（纯 Kotlin，可 JVM 单测）。
 *
 * 格式：一行一个 `key=value`，`#` 开头为注释，未知 key / 非法值一律忽略并保留默认，
 * 解析结束后统一走 [AppGalleryConfig.clamp]，因此**手改配置文件不可能把 App 弄崩**。
 *
 * 用途：
 * - DataStore 持久化（避免 40+ 个独立 key 的样板代码）；
 * - 高级用户/集成阶段的**外部配置文件**覆盖（读取路径见补丁 CONFIG 段：
 *   `filesDir/appgallery/config.txt`，存在时优先生效，便于不改代码调参）。
 */
object AppGalleryConfigCodec {

    /** 键名 = [AppGalleryConfig] 的属性名（一致，便于对照）。 */
    val KEY_ORDER: List<String> = listOf(
        "defaultViewMode", "gridColumnCount", "listRowHeightDp", "timelineGranularity",
        "rememberViewPerFolder", "viewMemorySlots",
        "thumbnailMaxEdgePx", "thumbnailQuality", "thumbnailPrefetchDistance",
        "autoGenerateMissingThumbnails", "thumbnailGenerationBatch",
        "trashEnabled", "trashRetentionDays", "trashAutoPurge", "trashPurgeCheckIntervalHours",
        "duplicateDetectionEnabled", "duplicateHashSize", "duplicateResizeEdge",
        "duplicateHammingThreshold", "duplicateUseAverageHash", "duplicateScanBatch",
        "statsDefaultGranularity", "statsIncludeVideos",
        "searchDebounceMs", "searchCaseSensitive", "searchIncludeTags",
        "defaultSortField", "defaultSortAscending",
        "maxRating", "maxTagCount", "maxTagLength",
        "placeClusterEnabled", "placeClusterRadiusKm", "placeClusterGridDecimals",
        "placeClusterUseOfflineCityIndex", "placeClusterOnlineLookupEnabled",
        "batchOperationBatchSize", "exportZipCompressionLevel", "exportFileNamePattern",
        "exportMaxNameLength", "backupEnabled", "backupTargetFolderName",
        "autoRescanIntervalMinutes", "watchCameraOutput",
        "themeMode",
    )

    fun encode(config: AppGalleryConfig): String = buildString {
        append("# PhotonCamera 应用内置相册配置（可手工编辑，越界值会被自动修正）\n")
        KEY_ORDER.forEach { key ->
            append(key).append('=').append(valueOf(config, key)).append('\n')
        }
    }

    /**
     * 解析：只覆盖出现且合法的键；未出现的键保留 [base] 的值。
     */
    fun decode(payload: String?, base: AppGalleryConfig = AppGalleryConfig()): AppGalleryConfig {
        if (payload.isNullOrBlank()) return AppGalleryConfig.clamp(base)
        var config = base
        payload.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            config = applyKey(config, key, value)
        }
        return AppGalleryConfig.clamp(config)
    }

    /** 读取单个键的字符串值（用于外部配置文件的「部分覆盖」展示）。 */
    fun valueOf(config: AppGalleryConfig, key: String): String = when (key) {
        "defaultViewMode" -> config.defaultViewMode.name
        "gridColumnCount" -> config.gridColumnCount.toString()
        "listRowHeightDp" -> config.listRowHeightDp.toString()
        "timelineGranularity" -> config.timelineGranularity.name
        "rememberViewPerFolder" -> config.rememberViewPerFolder.toString()
        "viewMemorySlots" -> config.viewMemorySlots.toString()
        "thumbnailMaxEdgePx" -> config.thumbnailMaxEdgePx.toString()
        "thumbnailQuality" -> config.thumbnailQuality.toString()
        "thumbnailPrefetchDistance" -> config.thumbnailPrefetchDistance.toString()
        "autoGenerateMissingThumbnails" -> config.autoGenerateMissingThumbnails.toString()
        "thumbnailGenerationBatch" -> config.thumbnailGenerationBatch.toString()
        "trashEnabled" -> config.trashEnabled.toString()
        "trashRetentionDays" -> config.trashRetentionDays.toString()
        "trashAutoPurge" -> config.trashAutoPurge.toString()
        "trashPurgeCheckIntervalHours" -> config.trashPurgeCheckIntervalHours.toString()
        "duplicateDetectionEnabled" -> config.duplicateDetectionEnabled.toString()
        "duplicateHashSize" -> config.duplicateHashSize.toString()
        "duplicateResizeEdge" -> config.duplicateResizeEdge.toString()
        "duplicateHammingThreshold" -> config.duplicateHammingThreshold.toString()
        "duplicateUseAverageHash" -> config.duplicateUseAverageHash.toString()
        "duplicateScanBatch" -> config.duplicateScanBatch.toString()
        "statsDefaultGranularity" -> config.statsDefaultGranularity.name
        "statsIncludeVideos" -> config.statsIncludeVideos.toString()
        "searchDebounceMs" -> config.searchDebounceMs.toString()
        "searchCaseSensitive" -> config.searchCaseSensitive.toString()
        "searchIncludeTags" -> config.searchIncludeTags.toString()
        "defaultSortField" -> config.defaultSortField.name
        "defaultSortAscending" -> config.defaultSortAscending.toString()
        "maxRating" -> config.maxRating.toString()
        "maxTagCount" -> config.maxTagCount.toString()
        "maxTagLength" -> config.maxTagLength.toString()
        "placeClusterEnabled" -> config.placeClusterEnabled.toString()
        "placeClusterRadiusKm" -> config.placeClusterRadiusKm.toString()
        "placeClusterGridDecimals" -> config.placeClusterGridDecimals.toString()
        "placeClusterUseOfflineCityIndex" -> config.placeClusterUseOfflineCityIndex.toString()
        "placeClusterOnlineLookupEnabled" -> config.placeClusterOnlineLookupEnabled.toString()
        "batchOperationBatchSize" -> config.batchOperationBatchSize.toString()
        "exportZipCompressionLevel" -> config.exportZipCompressionLevel.toString()
        "exportFileNamePattern" -> config.exportFileNamePattern
        "exportMaxNameLength" -> config.exportMaxNameLength.toString()
        "backupEnabled" -> config.backupEnabled.toString()
        "backupTargetFolderName" -> config.backupTargetFolderName
        "autoRescanIntervalMinutes" -> config.autoRescanIntervalMinutes.toString()
        "watchCameraOutput" -> config.watchCameraOutput.toString()
        "themeMode" -> config.themeMode.name
        else -> ""
    }

    private fun applyKey(config: AppGalleryConfig, key: String, value: String): AppGalleryConfig =
        when (key) {
            "defaultViewMode" -> config.copy(
                defaultViewMode = AppGalleryViewMode.fromPersistedName(value)
            )
            "gridColumnCount" -> config.copy(gridColumnCount = value.toIntOrNull() ?: config.gridColumnCount)
            "listRowHeightDp" -> config.copy(listRowHeightDp = value.toIntOrNull() ?: config.listRowHeightDp)
            "timelineGranularity" -> config.copy(
                timelineGranularity = TimelineGranularity.fromPersistedName(value)
            )
            "rememberViewPerFolder" -> config.copy(rememberViewPerFolder = parseBool(value, config.rememberViewPerFolder))
            "viewMemorySlots" -> config.copy(viewMemorySlots = value.toIntOrNull() ?: config.viewMemorySlots)
            "thumbnailMaxEdgePx" -> config.copy(thumbnailMaxEdgePx = value.toIntOrNull() ?: config.thumbnailMaxEdgePx)
            "thumbnailQuality" -> config.copy(thumbnailQuality = value.toIntOrNull() ?: config.thumbnailQuality)
            "thumbnailPrefetchDistance" -> config.copy(
                thumbnailPrefetchDistance = value.toIntOrNull() ?: config.thumbnailPrefetchDistance
            )
            "autoGenerateMissingThumbnails" -> config.copy(
                autoGenerateMissingThumbnails = parseBool(value, config.autoGenerateMissingThumbnails)
            )
            "thumbnailGenerationBatch" -> config.copy(
                thumbnailGenerationBatch = value.toIntOrNull() ?: config.thumbnailGenerationBatch
            )
            "trashEnabled" -> config.copy(trashEnabled = parseBool(value, config.trashEnabled))
            "trashRetentionDays" -> config.copy(
                trashRetentionDays = value.toIntOrNull() ?: config.trashRetentionDays
            )
            "trashAutoPurge" -> config.copy(trashAutoPurge = parseBool(value, config.trashAutoPurge))
            "trashPurgeCheckIntervalHours" -> config.copy(
                trashPurgeCheckIntervalHours = value.toIntOrNull() ?: config.trashPurgeCheckIntervalHours
            )
            "duplicateDetectionEnabled" -> config.copy(
                duplicateDetectionEnabled = parseBool(value, config.duplicateDetectionEnabled)
            )
            "duplicateHashSize" -> config.copy(duplicateHashSize = value.toIntOrNull() ?: config.duplicateHashSize)
            "duplicateResizeEdge" -> config.copy(
                duplicateResizeEdge = value.toIntOrNull() ?: config.duplicateResizeEdge
            )
            "duplicateHammingThreshold" -> config.copy(
                duplicateHammingThreshold = value.toIntOrNull() ?: config.duplicateHammingThreshold
            )
            "duplicateUseAverageHash" -> config.copy(
                duplicateUseAverageHash = parseBool(value, config.duplicateUseAverageHash)
            )
            "duplicateScanBatch" -> config.copy(
                duplicateScanBatch = value.toIntOrNull() ?: config.duplicateScanBatch
            )
            "statsDefaultGranularity" -> config.copy(
                statsDefaultGranularity = TimelineGranularity.fromPersistedName(value)
            )
            "statsIncludeVideos" -> config.copy(
                statsIncludeVideos = parseBool(value, config.statsIncludeVideos)
            )
            "searchDebounceMs" -> config.copy(searchDebounceMs = value.toIntOrNull() ?: config.searchDebounceMs)
            "searchCaseSensitive" -> config.copy(
                searchCaseSensitive = parseBool(value, config.searchCaseSensitive)
            )
            "searchIncludeTags" -> config.copy(
                searchIncludeTags = parseBool(value, config.searchIncludeTags)
            )
            "defaultSortField" -> config.copy(
                defaultSortField = runCatching { AppGallerySortField.valueOf(value.uppercase()) }
                    .getOrDefault(config.defaultSortField)
            )
            "defaultSortAscending" -> config.copy(
                defaultSortAscending = parseBool(value, config.defaultSortAscending)
            )
            "maxRating" -> config.copy(maxRating = value.toIntOrNull() ?: config.maxRating)
            "maxTagCount" -> config.copy(maxTagCount = value.toIntOrNull() ?: config.maxTagCount)
            "maxTagLength" -> config.copy(maxTagLength = value.toIntOrNull() ?: config.maxTagLength)
            "placeClusterEnabled" -> config.copy(
                placeClusterEnabled = parseBool(value, config.placeClusterEnabled)
            )
            "placeClusterRadiusKm" -> config.copy(
                placeClusterRadiusKm = value.toFloatOrNull() ?: config.placeClusterRadiusKm
            )
            "placeClusterGridDecimals" -> config.copy(
                placeClusterGridDecimals = value.toIntOrNull() ?: config.placeClusterGridDecimals
            )
            "placeClusterUseOfflineCityIndex" -> config.copy(
                placeClusterUseOfflineCityIndex = parseBool(value, config.placeClusterUseOfflineCityIndex)
            )
            "placeClusterOnlineLookupEnabled" -> config.copy(
                placeClusterOnlineLookupEnabled = parseBool(value, config.placeClusterOnlineLookupEnabled)
            )
            "batchOperationBatchSize" -> config.copy(
                batchOperationBatchSize = value.toIntOrNull() ?: config.batchOperationBatchSize
            )
            "exportZipCompressionLevel" -> config.copy(
                exportZipCompressionLevel = value.toIntOrNull() ?: config.exportZipCompressionLevel
            )
            "exportFileNamePattern" -> config.copy(exportFileNamePattern = value)
            "exportMaxNameLength" -> config.copy(
                exportMaxNameLength = value.toIntOrNull() ?: config.exportMaxNameLength
            )
            "backupEnabled" -> config.copy(backupEnabled = parseBool(value, config.backupEnabled))
            "backupTargetFolderName" -> config.copy(backupTargetFolderName = value)
            "autoRescanIntervalMinutes" -> config.copy(
                autoRescanIntervalMinutes = value.toIntOrNull() ?: config.autoRescanIntervalMinutes
            )
            "watchCameraOutput" -> config.copy(
                watchCameraOutput = parseBool(value, config.watchCameraOutput)
            )
            "themeMode" -> config.copy(
                themeMode = AppGalleryThemeMode.fromPersistedName(value)
            )
            else -> config
        }

    private fun parseBool(value: String, fallback: Boolean): Boolean = when (value.trim().lowercase()) {
        "1", "true", "yes", "on", "开" -> true
        "0", "false", "no", "off", "关" -> false
        else -> fallback
    }
}
