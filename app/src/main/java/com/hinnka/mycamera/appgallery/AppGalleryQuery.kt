package com.hinnka.mycamera.appgallery

/**
 * 相册查询条件（相册项 2/6：类型筛选 + 搜索）。
 *
 * 除了结构化筛选项，[text] 还支持**前缀语法**（见 [AppGallerySearchParser]）：
 * `type:` 类型、`tag:` 标签、`rating:` 评分、`fav:` 收藏、`is:` 图片/视频、`since:`/`until:` 时间。
 * 语法解析失败不会报错，原文按普通关键字处理。
 */
data class AppGalleryQuery(
    /** 原始输入（含前缀语法）。 */
    val rawText: String = "",
    /** 解析后的纯文本关键字（由 [AppGallerySearchParser] 填充）。 */
    val text: String = "",
    /** 类型筛选（空集 = 全选）。 */
    val categories: Set<AppGalleryCategory> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** 多标签是否要求全部命中（默认 false = 任一命中）。 */
    val tagMatchAll: Boolean = false,
    /** 仅收藏。 */
    val favoritesOnly: Boolean = false,
    /** 最低评分（0 = 不限）。 */
    val minRating: Int = 0,
    /** 媒体种类限制（空集 = 不限）。 */
    val kinds: Set<AppGalleryMediaKind> = emptySet(),
    /** 仅带 GPS 的条目。 */
    val hasLocationOnly: Boolean = false,
    /** 限定相册（null = 不限）。 */
    val folderId: String? = null,
    /** 时间范围（含端点，毫秒；null = 不限）。 */
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    /** 体积范围（字节；null = 不限）。 */
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
) {
    /** 是否存在任何生效条件（UI 用于显示「清除筛选」）。 */
    val isActive: Boolean
        get() = rawText.isNotBlank() || categories.isNotEmpty() || tags.isNotEmpty() ||
            favoritesOnly || minRating > 0 || kinds.isNotEmpty() || hasLocationOnly ||
            folderId != null || dateFrom != null || dateTo != null ||
            minSizeBytes != null || maxSizeBytes != null

    companion object {
        /**
         * clamp：评分/时间/体积区间做安全修正（区间反了就交换），不抛异常。
         */
        fun clamp(query: AppGalleryQuery, config: AppGalleryConfig): AppGalleryQuery {
            val safeConfig = AppGalleryConfig.clamp(config)
            val (from, to) = if (
                query.dateFrom != null && query.dateTo != null && query.dateFrom > query.dateTo
            ) {
                query.dateTo to query.dateFrom
            } else {
                query.dateFrom to query.dateTo
            }
            val (minSize, maxSize) = if (
                query.minSizeBytes != null && query.maxSizeBytes != null &&
                query.minSizeBytes > query.maxSizeBytes
            ) {
                query.maxSizeBytes to query.minSizeBytes
            } else {
                query.minSizeBytes to query.maxSizeBytes
            }
            return query.copy(
                minRating = query.minRating.coerceIn(0, safeConfig.maxRating),
                dateFrom = from,
                dateTo = to,
                minSizeBytes = minSize?.coerceAtLeast(0L),
                maxSizeBytes = maxSize?.coerceAtLeast(0L),
            )
        }
    }
}

/**
 * `type:` 前缀可接受的写法 → 分类（中文标签、英文名、常用别名都支持）。
 */
object AppGalleryTypeAliases {
    val TABLE: Map<String, AppGalleryCategory> = buildMap {
        AppGalleryCategory.entries.forEach { category ->
            put(category.name.lowercase(), category)
            put(category.labelZh.lowercase(), category)
        }
        put("raw", AppGalleryCategory.RAW_DNG)
        put("dng", AppGalleryCategory.RAW_DNG)
        put("stack", AppGalleryCategory.MULTI_FRAME)
        put("multiframe", AppGalleryCategory.MULTI_FRAME)
        put("多帧", AppGalleryCategory.MULTI_FRAME)
        put("hdr", AppGalleryCategory.HDR)
        put("sr", AppGalleryCategory.SUPER_RES)
        put("superres", AppGalleryCategory.SUPER_RES)
        put("超分", AppGalleryCategory.SUPER_RES)
        put("astro", AppGalleryCategory.ASTRO_TRAIL)
        put("星轨", AppGalleryCategory.ASTRO_TRAIL)
        put("光绘", AppGalleryCategory.ASTRO_TRAIL)
        put("lut", AppGalleryCategory.LUT_FILM)
        put("film", AppGalleryCategory.LUT_FILM)
        put("胶片", AppGalleryCategory.LUT_FILM)
        put("log", AppGalleryCategory.LOG_VIDEO)
        put("live", AppGalleryCategory.LIVE_PHOTO)
        put("实况", AppGalleryCategory.LIVE_PHOTO)
        put("pano", AppGalleryCategory.PANORAMA)
        put("全景", AppGalleryCategory.PANORAMA)
        put("gif", AppGalleryCategory.GIF)
        put("video", AppGalleryCategory.VIDEO)
        put("视频", AppGalleryCategory.VIDEO)
        put("burst", AppGalleryCategory.BURST)
        put("连拍", AppGalleryCategory.BURST)
        put("normal", AppGalleryCategory.NORMAL)
        put("photo", AppGalleryCategory.NORMAL)
        put("照片", AppGalleryCategory.NORMAL)
    }
}

/**
 * 搜索语法解析器（纯 Kotlin，可 JVM 单测）。
 *
 * 支持 token（空格分隔，大小写不敏感）：
 * - `type:raw,hdr` / `type:RAW`（逗号分隔多值，别名见 [AppGalleryTypeAliases]）
 * - `tag:旅行`（多值用逗号；出现多次 = 并集）
 * - `rating:>=3` / `rating:3`
 * - `fav:true` / `fav:1` / `fav`（等价 true）
 * - `is:video` / `is:image`
 * - `since:2026-01-01` / `until:2026-06-30`（本地日期，按当天 00:00 / 23:59:59.999 解释）
 * - `size:>10mb` / `size:<500kb`（B/KB/MB/GB，可选比较符，默认 >=）
 *
 * 无法识别的 token 原样留在自由文本里参与关键字匹配。
 */
object AppGallerySearchParser {

    fun parse(
        raw: String,
        base: AppGalleryQuery = AppGalleryQuery(),
        config: AppGalleryConfig = AppGalleryConfig(),
    ): AppGalleryQuery {
        val safeConfig = AppGalleryConfig.clamp(config)
        val categories = base.categories.toMutableSet()
        val tags = base.tags.toMutableSet()
        val kinds = base.kinds.toMutableSet()
        val freeText = StringBuilder()
        var favoritesOnly = base.favoritesOnly
        var minRating = base.minRating
        var dateFrom = base.dateFrom
        var dateTo = base.dateTo
        var minSize = base.minSizeBytes
        var maxSize = base.maxSizeBytes

        raw.split(' ', '\n', '\t', '\u3000').forEach { rawToken ->
            val token = rawToken.trim()
            if (token.isEmpty()) return@forEach
            val separator = token.indexOf(':')
            if (separator <= 0) {
                // 无前缀的独立 token：`fav` 作为收藏快捷词
                if (token.equals("fav", true) || token.equals("收藏", true)) {
                    favoritesOnly = true
                } else {
                    freeText.append(' ').append(token)
                }
                return@forEach
            }
            val key = token.substring(0, separator).lowercase()
            val value = token.substring(separator + 1).trim()
            when (key) {
                "type" -> splitValues(value).forEach { v ->
                    AppGalleryTypeAliases.TABLE[v.lowercase()]?.let { categories.add(it) }
                }
                "tag" -> splitValues(value).forEach { v ->
                    if (v.isNotBlank()) tags.add(v)
                }
                "rating" -> parseRating(value, safeConfig.maxRating)?.let { minRating = it }
                "fav" -> favoritesOnly = value.isEmpty() || value.equals("true", true) ||
                    value == "1" || value.equals("yes", true)
                "is" -> when (value.lowercase()) {
                    "video", "视频" -> kinds.add(AppGalleryMediaKind.VIDEO)
                    "image", "photo", "图片", "照片" -> kinds.add(AppGalleryMediaKind.IMAGE)
                    else -> freeText.append(' ').append(token)
                }
                "since", "from" -> parseDateStart(value)?.let { dateFrom = it }
                    ?: freeText.append(' ').append(token)
                "until", "to" -> parseDateEnd(value)?.let { dateTo = it }
                    ?: freeText.append(' ').append(token)
                "size" -> parseSize(value)?.let { (compare, bytes) ->
                    when (compare) {
                        ">" -> minSize = bytes + 1
                        ">=" -> minSize = bytes
                        "<" -> maxSize = bytes - 1
                        "<=" -> maxSize = bytes
                        else -> minSize = bytes
                    }
                } ?: freeText.append(' ').append(token)
                else -> freeText.append(' ').append(token)
            }
        }

        return AppGalleryQuery.clamp(
            base.copy(
                rawText = raw,
                text = freeText.toString().trim(),
                categories = categories,
                tags = tags,
                kinds = kinds,
                favoritesOnly = favoritesOnly,
                minRating = minRating,
                dateFrom = dateFrom,
                dateTo = dateTo,
                minSizeBytes = minSize,
                maxSizeBytes = maxSize,
            ),
            safeConfig,
        )
    }

    private fun splitValues(value: String): List<String> =
        value.split(',', '，', '|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseRating(value: String, maxRating: Int): Int? {
        val digits = value.removePrefix(">=").removePrefix(">").removePrefix("=").trim()
        val parsed = digits.toIntOrNull() ?: return null
        return parsed.coerceIn(0, maxRating)
    }

    /** `2026-01-01` → 当天 00:00:00.000（本地时区）。 */
    fun parseDateStart(value: String): Long? = parseLocalDateMillis(value, endOfDay = false)

    /** `2026-06-30` → 当天 23:59:59.999（本地时区）。 */
    fun parseDateEnd(value: String): Long? = parseLocalDateMillis(value, endOfDay = true)

    private fun parseLocalDateMillis(value: String, endOfDay: Boolean): Long? {
        val normalized = value.trim().replace('/', '-').replace('.', '-')
        val parts = normalized.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            val date = java.time.LocalDate.of(year, month, day)
            val time = if (endOfDay) java.time.LocalTime.MAX else java.time.LocalTime.MIN
            date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * `>10mb` / `<500kb` / `2gb` → (比较符, 字节数)。无比较符默认 `>=`。
     */
    fun parseSize(value: String): Pair<String, Long>? {
        val trimmed = value.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val compare = when {
            trimmed.startsWith(">=") -> ">="
            trimmed.startsWith("<=") -> "<="
            trimmed.startsWith(">") -> ">"
            trimmed.startsWith("<") -> "<"
            else -> ">="
        }
        val numberPart = trimmed.removePrefix(">=").removePrefix("<=")
            .removePrefix(">").removePrefix("<").trim()
        val unit = when {
            numberPart.endsWith("gb") -> 1024L * 1024L * 1024L
            numberPart.endsWith("mb") -> 1024L * 1024L
            numberPart.endsWith("kb") -> 1024L
            numberPart.endsWith("b") -> 1L
            else -> 1L
        }
        val numeric = numberPart
            .removeSuffix("gb").removeSuffix("mb").removeSuffix("kb").removeSuffix("b")
            .trim()
        val amount = numeric.toDoubleOrNull() ?: return null
        if (amount < 0) return null
        return compare to (amount * unit).toLong()
    }
}

/**
 * 过滤器（纯函数）：把查询条件应用到条目列表上。
 */
object AppGalleryFilter {

    fun apply(
        items: List<AppGalleryItem>,
        query: AppGalleryQuery,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): List<AppGalleryItem> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val q = AppGalleryQuery.clamp(query, safeConfig)
        val keyword = q.text.trim().let {
            if (safeConfig.searchCaseSensitive) it else it.lowercase()
        }
        val keywords = keyword.split(' ').filter { it.isNotBlank() }

        return items.filter { item -> matches(item, q, keywords, safeConfig) }
    }

    /** 单条目匹配（供 UI 做「为什么被过滤掉」的诊断）。 */
    fun matches(
        item: AppGalleryItem,
        query: AppGalleryQuery,
        keywords: List<String>,
        config: AppGalleryConfig,
    ): Boolean {
        if (query.categories.isNotEmpty() && item.category !in query.categories) return false
        if (query.kinds.isNotEmpty() && item.kind !in query.kinds) return false
        if (query.favoritesOnly && !item.isFavorite) return false
        if (query.minRating > 0 && item.rating < query.minRating) return false
        if (query.hasLocationOnly && (item.latitude == null || item.longitude == null)) return false
        if (query.folderId != null && item.folderIds.none { it == query.folderId }) return false
        val date = item.sortDate
        if (query.dateFrom != null && date < query.dateFrom) return false
        if (query.dateTo != null && date > query.dateTo) return false
        if (query.minSizeBytes != null && item.sizeBytes < query.minSizeBytes) return false
        if (query.maxSizeBytes != null && item.sizeBytes > query.maxSizeBytes) return false

        if (query.tags.isNotEmpty()) {
            val normalizedTags = query.tags.map { normalize(it, config) }
            val itemTags = item.tags.map { normalize(it, config) }
            val hit = if (query.tagMatchAll) {
                normalizedTags.all { itemTags.contains(it) }
            } else {
                normalizedTags.any { itemTags.contains(it) }
            }
            if (!hit) return false
        }

        if (keywords.isNotEmpty()) {
            val haystack = buildString {
                append(item.displayName).append(' ')
                append(item.category.name).append(' ')
                append(item.category.labelZh).append(' ')
                append(item.locationName.orEmpty()).append(' ')
                append(item.tags.joinToString(" "))
            }.let { if (config.searchCaseSensitive) it else it.lowercase() }
            val allHit = keywords.all { haystack.contains(it) }
            if (!allHit) return false
        }
        return true
    }

    private fun normalize(value: String, config: AppGalleryConfig): String {
        val trimmed = value.trim()
        return if (config.searchCaseSensitive) trimmed else trimmed.lowercase()
    }
}
