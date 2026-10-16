package com.hinnka.mycamera.appgallery

/**
 * 记录编解码（纯 Kotlin，零依赖，可 JVM 单测）。
 *
 * 为什么不用 JSON：工程已依赖 `org.json`（Android 框架内建），但在纯 JVM 单测下
 * 只能拿到桩实现，无法覆盖编解码逻辑；因此本模块用**分隔符 + 转义**的纯字符串格式，
 * 与工程既有 [`com.hinnka.mycamera.gallery.db.GalleryDelimitedCodec`] 同一思路，
 * 差异是支持「记录 / 字段」两级结构与空值。
 *
 * 容错原则：任何脏数据、截断数据、字段缺失都跳过该条记录，绝不抛异常、绝不丢整份数据。
 */
object AppGalleryCodec {

    /** 字段分隔符（ASCII Unit Separator）。 */
    const val FIELD_SEPARATOR = '\u001F'

    /** 记录分隔符（ASCII Record Separator）。 */
    const val RECORD_SEPARATOR = '\u001E'

    /** 单份载荷上限（字符数），防止脏数据撑爆 DataStore。 */
    const val MAX_PAYLOAD_CHARS = 4 * 1024 * 1024

    fun escape(raw: String): String = buildString(raw.length) {
        raw.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                FIELD_SEPARATOR -> append("\\u")
                RECORD_SEPARATOR -> append("\\r")
                else -> append(char)
            }
        }
    }

    fun unescape(raw: String): String = buildString(raw.length) {
        var escaping = false
        raw.forEach { char ->
            if (escaping) {
                when (char) {
                    'u' -> append(FIELD_SEPARATOR)
                    'r' -> append(RECORD_SEPARATOR)
                    '\\' -> append('\\')
                    else -> append(char)
                }
                escaping = false
            } else if (char == '\\') {
                escaping = true
            } else {
                append(char)
            }
        }
        if (escaping) append('\\')
    }

    /** 编码一条记录（null 与空串等价）。 */
    fun encodeRecord(fields: List<String?>): String =
        fields.joinToString(FIELD_SEPARATOR.toString()) { escape(it ?: "") }

    /** 解码一条记录（字段数不校验，由上层按索引判空）。 */
    fun decodeRecord(record: String): List<String> {
        // 手工切分，避免 split 丢弃空字段（如首字段为空）
        val out = ArrayList<String>()
        val current = StringBuilder()
        var escaping = false
        record.forEach { char ->
            if (escaping) {
                current.append('\\').append(char)
                escaping = false
            } else if (char == '\\') {
                escaping = true
            } else if (char == FIELD_SEPARATOR) {
                out.add(unescape(current.toString()))
                current.clear()
            } else {
                current.append(char)
            }
        }
        if (escaping) current.append('\\')
        out.add(unescape(current.toString()))
        return out
    }

    fun encodeRecords(records: List<List<String?>>): String =
        records.joinToString(RECORD_SEPARATOR.toString()) { encodeRecord(it) }

    fun decodeRecords(payload: String?): List<List<String>> {
        if (payload.isNullOrEmpty()) return emptyList()
        val safe = if (payload.length > MAX_PAYLOAD_CHARS) payload.take(MAX_PAYLOAD_CHARS) else payload
        val out = ArrayList<List<String>>()
        val current = StringBuilder()
        var escaping = false
        safe.forEach { char ->
            if (escaping) {
                current.append('\\').append(char)
                escaping = false
            } else if (char == '\\') {
                escaping = true
            } else if (char == RECORD_SEPARATOR) {
                if (current.isNotEmpty()) out.add(decodeRecord(current.toString()))
                current.clear()
            } else {
                current.append(char)
            }
        }
        if (escaping) current.append('\\')
        if (current.isNotEmpty()) out.add(decodeRecord(current.toString()))
        return out
    }

    fun toLongOrNull(value: String?): Long? = value?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()

    fun toIntOrNull(value: String?): Int? = value?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    fun toDoubleOrNull(value: String?): Double? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun toBoolean(value: String?): Boolean =
        value?.trim()?.let { it == "1" || it.equals("true", true) } ?: false

    /** 空集合不落盘（返回 null，便于 DataStore 里直接删 key）。 */
    fun encodeOrNull(records: List<List<String?>>): String? =
        if (records.isEmpty()) null else encodeRecords(records)
}

/**
 * 收藏/标签/评分的持久化编解码（相册项 5）。
 * 记录格式：`itemId | favorite(0/1) | rating | tags(逗号分隔) | updatedAt`
 */
object AppGalleryAnnotationCodec {

    private const val FIELD_COUNT = 5

    fun encode(annotations: Map<String, AppGalleryAnnotation>): String? {
        val records = annotations.values
            .filterNot { it.isEmpty }
            .map { annotation ->
                listOf(
                    annotation.itemId,
                    if (annotation.isFavorite) "1" else "0",
                    annotation.rating.toString(),
                    annotation.tags.joinToString(","),
                    annotation.updatedAt.toString(),
                )
            }
        return AppGalleryCodec.encodeOrNull(records)
    }

    fun decode(payload: String?, config: AppGalleryConfig = AppGalleryConfig()): Map<String, AppGalleryAnnotation> {
        val out = LinkedHashMap<String, AppGalleryAnnotation>()
        AppGalleryCodec.decodeRecords(payload).forEach { fields ->
            if (fields.size < FIELD_COUNT) return@forEach
            val itemId = fields[0].trim()
            if (itemId.isEmpty()) return@forEach
            val tags = fields[3]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val annotation = AppGalleryAnnotation(
                itemId = itemId,
                isFavorite = AppGalleryCodec.toBoolean(fields[1]),
                rating = AppGalleryCodec.toIntOrNull(fields[2]) ?: 0,
                tags = tags,
                updatedAt = AppGalleryCodec.toLongOrNull(fields[4]) ?: 0L,
            )
            val normalized = AppGalleryAnnotationRules.normalizeAnnotation(annotation, config)
            if (!normalized.isEmpty) out[itemId] = normalized
        }
        return out
    }
}

/**
 * 视图记忆编解码（相册项 1）。
 * 记录格式：`scopeId | viewMode | sortField | sortOrder | granularity | anchorId | updatedAt`
 */
object AppGalleryViewMemoryCodec {

    private const val FIELD_COUNT = 7

    fun encode(memories: List<AppGalleryViewMemory>): String? {
        val records = memories.map { memory ->
            listOf(
                memory.scopeId,
                memory.viewMode.name,
                memory.sortField.name,
                memory.sortOrder.name,
                memory.timelineGranularity.name,
                memory.scrollAnchorId.orEmpty(),
                memory.updatedAt.toString(),
            )
        }
        return AppGalleryCodec.encodeOrNull(records)
    }

    fun decode(payload: String?): List<AppGalleryViewMemory> {
        val out = ArrayList<AppGalleryViewMemory>()
        AppGalleryCodec.decodeRecords(payload).forEach { fields ->
            if (fields.size < FIELD_COUNT) return@forEach
            val scopeId = fields[0].trim()
            if (scopeId.isEmpty()) return@forEach
            out.add(
                AppGalleryViewMemory(
                    scopeId = scopeId,
                    viewMode = AppGalleryViewMode.fromPersistedName(fields[1]),
                    sortField = runCatching { AppGallerySortField.valueOf(fields[2]) }
                        .getOrDefault(AppGallerySortField.DATE),
                    sortOrder = runCatching { AppGallerySortOrder.valueOf(fields[3]) }
                        .getOrDefault(AppGallerySortOrder.DESCENDING),
                    timelineGranularity = TimelineGranularity.fromPersistedName(fields[4]),
                    scrollAnchorId = fields[5].takeIf { it.isNotEmpty() },
                    updatedAt = AppGalleryCodec.toLongOrNull(fields[6]) ?: 0L,
                )
            )
        }
        return out
    }
}

/**
 * 相册编解码（相册项 11）。
 * 记录格式：`id | name | createdAt | isFavorite | isSmart | smartQuery | coverItemId | parentId | itemIds`
 */
object AppGalleryFolderCodec {

    private const val FIELD_COUNT = 9

    fun encode(folders: List<AppGalleryFolder>): String? {
        val records = folders.map { folder ->
            listOf(
                folder.id,
                folder.name,
                folder.createdAt.toString(),
                if (folder.isFavoriteFolder) "1" else "0",
                if (folder.isSmartFolder) "1" else "0",
                folder.smartQuery.orEmpty(),
                folder.coverItemId.orEmpty(),
                folder.parentId.orEmpty(),
                folder.itemIds.joinToString(","),
            )
        }
        return AppGalleryCodec.encodeOrNull(records)
    }

    fun decode(payload: String?): List<AppGalleryFolder> {
        val out = ArrayList<AppGalleryFolder>()
        AppGalleryCodec.decodeRecords(payload).forEach { fields ->
            if (fields.size < FIELD_COUNT) return@forEach
            val id = fields[0].trim()
            if (id.isEmpty()) return@forEach
            val name = fields[1].takeIf { it.isNotBlank() } ?: return@forEach
            val itemIds = fields[8].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            out.add(
                AppGalleryFolder(
                    id = id,
                    name = name,
                    createdAt = AppGalleryCodec.toLongOrNull(fields[2]) ?: 0L,
                    isFavoriteFolder = AppGalleryCodec.toBoolean(fields[3]),
                    isSmartFolder = AppGalleryCodec.toBoolean(fields[4]),
                    smartQuery = fields[5].takeIf { it.isNotEmpty() },
                    coverItemId = fields[6].takeIf { it.isNotEmpty() },
                    parentId = fields[7].takeIf { it.isNotEmpty() },
                    itemIds = itemIds,
                )
            )
        }
        return out
    }
}

/**
 * 回收站编解码（相册项 7）。
 * 记录格式：`itemId | displayName | originalUri | trashUri | category | size | deletedAt | expiresAt | folderIds | source | fav | rating | tags | updatedAt`
 */
object AppGalleryTrashCodec {

    private const val FIELD_COUNT = 14

    fun encode(entries: List<AppGalleryTrashEntry>): String? {
        val records = entries.map { entry ->
            listOf(
                entry.itemId,
                entry.displayName,
                entry.originalUri,
                entry.trashUri,
                entry.category.name,
                entry.sizeBytes.toString(),
                entry.deletedAt.toString(),
                entry.expiresAt.toString(),
                entry.originalFolderIds.joinToString(","),
                entry.source.name,
                if (entry.annotation?.isFavorite == true) "1" else "0",
                (entry.annotation?.rating ?: 0).toString(),
                entry.annotation?.tags?.joinToString(",").orEmpty(),
                (entry.annotation?.updatedAt ?: 0L).toString(),
            )
        }
        return AppGalleryCodec.encodeOrNull(records)
    }

    fun decode(payload: String?): List<AppGalleryTrashEntry> {
        val out = ArrayList<AppGalleryTrashEntry>()
        AppGalleryCodec.decodeRecords(payload).forEach { fields ->
            if (fields.size < FIELD_COUNT) return@forEach
            val itemId = fields[0].trim()
            if (itemId.isEmpty()) return@forEach
            val source = runCatching { AppGalleryItemSource.valueOf(fields[9]) }
                .getOrDefault(AppGalleryItemSource.APP_DATABASE)
            val tags = fields[12].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val rating = AppGalleryCodec.toIntOrNull(fields[11]) ?: 0
            val favorite = AppGalleryCodec.toBoolean(fields[10])
            val annotation = if (favorite || rating > 0 || tags.isNotEmpty()) {
                AppGalleryAnnotation(
                    itemId = itemId,
                    isFavorite = favorite,
                    rating = rating,
                    tags = tags,
                    updatedAt = AppGalleryCodec.toLongOrNull(fields[13]) ?: 0L,
                )
            } else {
                null
            }
            out.add(
                AppGalleryTrashEntry(
                    itemId = itemId,
                    displayName = fields[1],
                    originalUri = fields[2],
                    trashUri = fields[3],
                    category = AppGalleryCategory.fromPersistedName(fields[4])
                        ?: AppGalleryCategory.NORMAL,
                    sizeBytes = AppGalleryCodec.toLongOrNull(fields[5]) ?: 0L,
                    deletedAt = AppGalleryCodec.toLongOrNull(fields[6]) ?: 0L,
                    expiresAt = AppGalleryCodec.toLongOrNull(fields[7]) ?: 0L,
                    originalFolderIds = fields[8].split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    source = source,
                    annotation = annotation,
                )
            )
        }
        return out
    }
}
