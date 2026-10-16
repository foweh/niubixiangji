package com.hinnka.mycamera.appgallery

/**
 * 单条目的收藏/标签/评分状态（相册项 5：DataStore 持久化的状态机模型）。
 */
data class AppGalleryAnnotation(
    val itemId: String,
    val isFavorite: Boolean = false,
    /** 0 = 未评分；上限见 [AppGalleryConfig.maxRating]。 */
    val rating: Int = 0,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = !isFavorite && rating <= 0 && tags.isEmpty()
}

/**
 * 标签/评分的规范化规则（纯函数 + clamp，防脏数据/超长/超量）。
 */
object AppGalleryAnnotationRules {

    /** 标签中的非法控制字符（统一替换为空格）。 */
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]+")

    /** 归一化单个标签：去控制字符、压缩空白、截断到上限；空则返回 null。 */
    fun normalizeTag(raw: String?, config: AppGalleryConfig = AppGalleryConfig()): String? {
        val safeConfig = AppGalleryConfig.clamp(config)
        if (raw == null) return null
        val cleaned = CONTROL_CHARS.replace(raw, " ")
            .replace('\n', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        return cleaned.take(safeConfig.maxTagLength).trim().takeIf { it.isNotEmpty() }
    }

    /** 归一化标签集合：去重（忽略大小写，保留首个写法）+ 截断到数量上限。 */
    fun normalizeTags(
        raw: Iterable<String>,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): List<String> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val seen = LinkedHashMap<String, String>()
        raw.forEach { candidate ->
            val tag = normalizeTag(candidate, safeConfig) ?: return@forEach
            val key = tag.lowercase()
            if (!seen.containsKey(key)) seen[key] = tag
        }
        return seen.values.take(safeConfig.maxTagCount)
    }

    /** 归一化评分（0..maxRating；非法值按 0）。 */
    fun normalizeRating(raw: Int, config: AppGalleryConfig = AppGalleryConfig()): Int {
        val safeConfig = AppGalleryConfig.clamp(config)
        return raw.coerceIn(0, safeConfig.maxRating)
    }

    /** 归一化整条状态（加载持久化数据时调用，兼容脏数据）。 */
    fun normalizeAnnotation(
        annotation: AppGalleryAnnotation,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): AppGalleryAnnotation = annotation.copy(
        rating = normalizeRating(annotation.rating, config),
        tags = normalizeTags(annotation.tags, config),
    )
}

/**
 * 收藏 / 标签 / 评分状态机（纯函数，可 JVM 单测）。
 *
 * 所有方法都返回**新状态**，不做原地修改；集合以 `Map<itemId, annotation>` 表示，
 * 空状态会被自动剔除（保持 DataStore 内容精简）。
 */
object AppGalleryAnnotationMachine {

    /** 词表容量的相对余量：上限 = maxTagCount × 该系数（避免词表无限膨胀）。 */
    private const val VOCABULARY_SLACK = 5

    fun empty(itemId: String): AppGalleryAnnotation = AppGalleryAnnotation(itemId = itemId)

    private fun upsert(
        annotations: Map<String, AppGalleryAnnotation>,
        annotation: AppGalleryAnnotation,
        config: AppGalleryConfig,
    ): Map<String, AppGalleryAnnotation> {
        val normalized = AppGalleryAnnotationRules.normalizeAnnotation(annotation, config)
        if (normalized.isEmpty) return annotations - normalized.itemId
        return annotations + (normalized.itemId to normalized)
    }

    private fun current(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
    ): AppGalleryAnnotation = annotations[itemId] ?: empty(itemId)

    // ── 单个条目 ────────────────────────────────────────────────

    fun toggleFavorite(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val existing = current(annotations, itemId)
        return upsert(
            annotations,
            existing.copy(isFavorite = !existing.isFavorite, updatedAt = now),
            config,
        )
    }

    fun setFavorite(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        favorite: Boolean,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> = upsert(
        annotations,
        current(annotations, itemId).copy(isFavorite = favorite, updatedAt = now),
        config,
    )

    fun setRating(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        rating: Int,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> = upsert(
        annotations,
        current(annotations, itemId).copy(
            rating = AppGalleryAnnotationRules.normalizeRating(rating, config),
            updatedAt = now,
        ),
        config,
    )

    /** 点击循环：0 → 1 → … → max → 0。 */
    fun cycleRating(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val existing = current(annotations, itemId)
        val next = if (existing.rating >= safeConfig.maxRating) 0 else existing.rating + 1
        return upsert(annotations, existing.copy(rating = next, updatedAt = now), safeConfig)
    }

    /** 加标签（已存在则忽略；超上限则丢弃新标签）。 */
    fun addTag(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        tag: String,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val normalized = AppGalleryAnnotationRules.normalizeTag(tag, safeConfig)
            ?: return annotations
        val existing = current(annotations, itemId)
        if (existing.tags.size >= safeConfig.maxTagCount) return annotations
        if (existing.tags.any { it.equals(normalized, ignoreCase = true) }) return annotations
        return upsert(
            annotations,
            existing.copy(tags = existing.tags + normalized, updatedAt = now),
            safeConfig,
        )
    }

    fun removeTag(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        tag: String,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val existing = current(annotations, itemId)
        val normalized = AppGalleryAnnotationRules.normalizeTag(tag, config) ?: return annotations
        val remaining = existing.tags.filterNot { it.equals(normalized, ignoreCase = true) }
        if (remaining.size == existing.tags.size) return annotations
        return upsert(annotations, existing.copy(tags = remaining, updatedAt = now), config)
    }

    fun toggleTag(
        annotations: Map<String, AppGalleryAnnotation>,
        itemId: String,
        tag: String,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val existing = current(annotations, itemId)
        val normalized = AppGalleryAnnotationRules.normalizeTag(tag, config) ?: return annotations
        val has = existing.tags.any { it.equals(normalized, ignoreCase = true) }
        return if (has) {
            removeTag(annotations, itemId, normalized, now, config)
        } else {
            addTag(annotations, itemId, normalized, now, config)
        }
    }

    // ── 批量（相册项 8：多选与批量操作） ─────────────────────────

    fun batchSetFavorite(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
        favorite: Boolean,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        var result = annotations
        itemIds.forEach { id -> result = setFavorite(result, id, favorite, now, config) }
        return result
    }

    fun batchToggleFavorite(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        var result = annotations
        itemIds.forEach { id -> result = toggleFavorite(result, id, now, config) }
        return result
    }

    fun batchSetRating(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
        rating: Int,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        var result = annotations
        itemIds.forEach { id -> result = setRating(result, id, rating, now, config) }
        return result
    }

    fun batchAddTags(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
        tags: Collection<String>,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val normalized = AppGalleryAnnotationRules.normalizeTags(tags, safeConfig)
        if (normalized.isEmpty()) return annotations
        var result = annotations
        itemIds.forEach { id ->
            normalized.forEach { tag -> result = addTag(result, id, tag, now, safeConfig) }
        }
        return result
    }

    fun batchRemoveTags(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
        tags: Collection<String>,
        now: Long = 0L,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        var result = annotations
        itemIds.forEach { id ->
            tags.forEach { tag -> result = removeTag(result, id, tag, now, config) }
        }
        return result
    }

    /** 丢弃某些条目的状态（删除/清空回收站时调用）。 */
    fun drop(
        annotations: Map<String, AppGalleryAnnotation>,
        itemIds: Collection<String>,
    ): Map<String, AppGalleryAnnotation> {
        if (itemIds.isEmpty()) return annotations
        return annotations.filterKeys { it !in itemIds }
    }

    /** 合并持久化数据（外部数据优先，避免旧缓存覆盖新写入）。 */
    fun mergeAnnotations(
        base: Map<String, AppGalleryAnnotation>,
        incoming: Collection<AppGalleryAnnotation>,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): Map<String, AppGalleryAnnotation> {
        var result = base
        incoming.forEach { annotation ->
            val existing = base[annotation.itemId]
            val merged = if (existing != null && existing.updatedAt > annotation.updatedAt) {
                existing
            } else {
                annotation
            }
            result = upsert(result, merged, config)
        }
        return result
    }

    /** 全库标签词表（去重、按出现次数降序，供筛选/自动补全）。 */
    fun tagVocabulary(
        annotations: Map<String, AppGalleryAnnotation>,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        annotations.values.forEach { annotation ->
            annotation.tags.forEach { tag ->
                val key = tag.lowercase()
                val existing = counts[key]
                counts[key] = if (existing == null) tag to 1 else existing.first to (existing.second + 1)
            }
        }
        return counts.values
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(AppGalleryConfig.clamp(config).maxTagCount * VOCABULARY_SLACK)
    }
}
