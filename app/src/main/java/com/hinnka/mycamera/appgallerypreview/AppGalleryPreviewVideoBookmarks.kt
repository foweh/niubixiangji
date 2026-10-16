package com.hinnka.mycamera.appgallerypreview

/**
 * 视频书签（第 38 项：视频书签时间点跳转）。
 *
 * 只描述"播放器层需要的数据"，书签的落库与增删由画廊编辑模块负责。
 */
data class AppGalleryPreviewBookmark(
    val id: String,
    /** 时间点（毫秒，必 >= 0）。 */
    val positionMs: Long,
    val label: String? = null,
)

/**
 * 书签数据源契约（跨代理）。
 *
 * 预览域只声明接口；画廊编辑模块（`galleryedit` 包）实现它并注入，
 * 因此预览域不引用编辑模块的任何符号。默认实现返回空列表（无书签，功能自然降级）。
 */
interface AppGalleryPreviewBookmarkSource {

    /** 读取某媒体的书签（无序、可能越界，调用方应先 [AppGalleryPreviewBookmarkMath.normalize]）。 */
    fun bookmarksFor(mediaUri: String): List<AppGalleryPreviewBookmark>

    companion object {
        /** 无书签的默认实现（编辑模块未接入时使用，保证不崩溃）。 */
        val NONE: AppGalleryPreviewBookmarkSource = object : AppGalleryPreviewBookmarkSource {
            override fun bookmarksFor(mediaUri: String): List<AppGalleryPreviewBookmark> = emptyList()
        }
    }
}

/**
 * 书签时间计算（纯函数，可 JVM 单测）。
 */
object AppGalleryPreviewBookmarkMath {

    /** 未知时长（播放器尚未 prepare）时的占位值。 */
    const val DURATION_UNKNOWN = -1L

    /**
     * 归一化：过滤非法项、按时间排序、去重（同 id 保留首个）、按已知时长 clamp。
     *
     * @param durationMs 视频时长；[DURATION_UNKNOWN] 或 <=0 表示未知，不做上界裁剪
     */
    fun normalize(
        bookmarks: List<AppGalleryPreviewBookmark>,
        durationMs: Long = DURATION_UNKNOWN,
    ): List<AppGalleryPreviewBookmark> {
        val byId = LinkedHashMap<String, AppGalleryPreviewBookmark>()
        for (bookmark in bookmarks) {
            val id = bookmark.id.trim()
            if (id.isEmpty()) continue
            if (bookmark.positionMs < 0L) continue
            byId.putIfAbsent(id, bookmark.copy(id = id))
        }
        val hasDuration = durationMs > 0L
        return byId.values
            .map { if (hasDuration) it.copy(positionMs = it.positionMs.coerceAtMost(durationMs)) else it }
            .sortedWith(compareBy({ it.positionMs }, { it.id }))
    }

    /** 跳转目标时间：负值→0；超出时长→时长；时长未知→原样（>=0）。 */
    fun clampSeek(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 0L) return 0L
        return if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
    }

    /**
     * 吸附到容差内的最近书签。
     * @param toleranceMs 容差；<=0 视为不吸附
     * @return 吸附到的书签；无命中返回 null
     */
    fun nearestBookmark(
        bookmarks: List<AppGalleryPreviewBookmark>,
        positionMs: Long,
        toleranceMs: Long,
    ): AppGalleryPreviewBookmark? {
        if (bookmarks.isEmpty() || toleranceMs <= 0L) return null
        var best: AppGalleryPreviewBookmark? = null
        var bestDistance = Long.MAX_VALUE
        for (bookmark in bookmarks) {
            val distance = kotlin.math.abs(bookmark.positionMs - positionMs)
            if (distance <= toleranceMs && distance < bestDistance) {
                best = bookmark
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * 下一个书签。
     * @param inclusive true 时允许返回当前位置（用于"跳到书签后重放"）
     * @param loop 末尾是否回到第一个
     */
    fun nextBookmark(
        bookmarks: List<AppGalleryPreviewBookmark>,
        positionMs: Long,
        inclusive: Boolean = false,
        loop: Boolean = false,
    ): AppGalleryPreviewBookmark? {
        if (bookmarks.isEmpty()) return null
        val sorted = normalize(bookmarks)
        val hit = sorted.firstOrNull {
            if (inclusive) it.positionMs >= positionMs else it.positionMs > positionMs
        }
        if (hit != null) return hit
        return if (loop) sorted.first() else null
    }

    /** 上一个书签；[loop] 时首个之前回到最后一个。 */
    fun previousBookmark(
        bookmarks: List<AppGalleryPreviewBookmark>,
        positionMs: Long,
        inclusive: Boolean = false,
        loop: Boolean = false,
    ): AppGalleryPreviewBookmark? {
        if (bookmarks.isEmpty()) return null
        val sorted = normalize(bookmarks)
        val hit = sorted.lastOrNull {
            if (inclusive) it.positionMs <= positionMs else it.positionMs < positionMs
        }
        if (hit != null) return hit
        return if (loop) sorted.last() else null
    }

    /** 书签在时间轴上的比例位置 0..1（时长未知返回 0）。 */
    fun fractionOf(bookmark: AppGalleryPreviewBookmark, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (bookmark.positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
