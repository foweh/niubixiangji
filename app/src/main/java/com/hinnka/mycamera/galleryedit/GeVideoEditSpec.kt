package com.hinnka.mycamera.galleryedit

import kotlin.math.abs

/**
 * 视频裁剪区间（毫秒）。
 *
 * 语义：保留 `[startMs, endMs]`；[endMs] 为 null 表示"到片尾"。
 */
data class GeVideoTrimRange(
    val startMs: Long = 0L,
    val endMs: Long? = null,
) {
    /** 在给定源时长下的实际结束点（null → 源时长）。 */
    fun resolvedEndMs(sourceDurationMs: Long): Long = endMs ?: sourceDurationMs

    /** 在给定源时长下的实际时长。 */
    fun durationMs(sourceDurationMs: Long): Long =
        (resolvedEndMs(sourceDurationMs) - startMs).coerceAtLeast(0L)

    fun isValid(sourceDurationMs: Long): Boolean =
        startMs >= 0L && resolvedEndMs(sourceDurationMs) > startMs

    companion object {
        /** 全片（不裁剪）。 */
        val FULL = GeVideoTrimRange(0L, null)
    }
}

/** 视频书签 / 关键帧收藏点。 */
data class GeVideoBookmark(
    val timeMs: Long,
    val label: String = "",
) {
    val isUsable: Boolean get() = timeMs >= 0L
}

/** 多段拼接中的一段。 */
data class GeVideoSegment(
    val uriString: String,
    val trim: GeVideoTrimRange? = null,
    val sourceDurationMs: Long = 0L,
) {
    val isUsable: Boolean get() = uriString.isNotBlank()
}

/**
 * 视频编辑规格（**纯数据 + 纯校验**，可 JVM 单测）。
 *
 * 覆盖用户要求的"视频 20 项"中的：裁剪（时间区间）、旋转、变速、去音频、多段拼接、书签。
 * 调色（LUT/曲线）通过 [colorSourceUri] / [lutId] 由上层 lut/ 管线解析后注入，
 * 本规格只负责"是否套用调色"这一开关与参数传递。
 */
data class GeVideoEditSpec(
    val segments: List<GeVideoSegment> = emptyList(),
    /** 变速倍率，范围 [GeEditRanges.VIDEO_SPEED_MIN, VIDEO_SPEED_MAX]。 */
    val speed: Float = GeEditRanges.VIDEO_SPEED_DEFAULT,
    /** 旋转档位，仅允许 [GeEditRanges.VIDEO_ROTATION_ALLOWED]。 */
    val rotationDegrees: Int = 0,
    /** 是否保留原声（false = 去音频/静音）。 */
    val keepAudio: Boolean = true,
    /** 是否套用 lut/ 管线的 LUT 与色彩配方。 */
    val applyColorPipeline: Boolean = false,
    /** 调色所选 LUT id（applyColorPipeline = true 时生效；null = 仅色彩配方）。 */
    val lutId: String? = null,
    /** LUT 强度 0..2（用户硬约束：默认全映射，可拉到更强）。 */
    val lutIntensity: Float = 1f,
    /** 书签列表。 */
    val bookmarks: List<GeVideoBookmark> = emptyList(),
    /** 输出帧率上限（0 = 不限）。范围 [VIDEO_FRAME_RATE_MIN, VIDEO_FRAME_RATE_MAX]。 */
    val outputFrameRate: Int = GeEditRanges.VIDEO_FRAME_RATE_DEFAULT,
    /** 目标码率（bps），null = 交给编码器默认。 */
    val targetBitrate: Int? = null,
) {
    /** 是否需要驱动 Transformer（只要不是"原片直出"就需要）。 */
    val requiresTranscode: Boolean
        get() = segments.any { it.trim != null } ||
            !GeEditRanges.nearlyEqual(speed, 1f) ||
            rotationDegrees != 0 ||
            !keepAudio ||
            applyColorPipeline ||
            segments.size > 1

    fun clamp(): Pair<GeVideoEditSpec, GeClampReport> {
        val corrections = mutableListOf<GeClampCorrection>()

        val safeSpeed = GeEditRanges.clampFloat(
            speed,
            GeEditRanges.VIDEO_SPEED_MIN,
            GeEditRanges.VIDEO_SPEED_MAX,
            GeEditRanges.VIDEO_SPEED_DEFAULT,
        )
        GeClampReport.of("video.speed", speed.toDouble(), safeSpeed.toDouble())?.let(corrections::add)

        val safeRotation = normalizeRotation(rotationDegrees)
        if (safeRotation != rotationDegrees) {
            corrections.add(
                GeClampCorrection("video.rotationDegrees", rotationDegrees.toDouble(), safeRotation.toDouble())
            )
        }

        val safeFrameRate = GeEditRanges.clampInt(
            outputFrameRate,
            GeEditRanges.VIDEO_FRAME_RATE_MIN,
            GeEditRanges.VIDEO_FRAME_RATE_MAX,
        )
        GeClampReport.of("video.outputFrameRate", outputFrameRate.toDouble(), safeFrameRate.toDouble())
            ?.let(corrections::add)

        val safeIntensity = GeEditRanges.clampFloat(lutIntensity, 0f, 2f, 1f)
        GeClampReport.of("video.lutIntensity", lutIntensity.toDouble(), safeIntensity.toDouble())
            ?.let(corrections::add)

        val safeBitrate = targetBitrate?.let { rate ->
            val clamped = rate.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
            GeClampReport.of("video.targetBitrate", rate.toDouble(), clamped.toDouble())?.let(corrections::add)
            clamped
        }

        val safeSegments = segments
            .filter { it.isUsable }
            .take(GeEditRanges.VIDEO_SEGMENT_MAX)
            .map { segment ->
                val duration = segment.sourceDurationMs.coerceAtLeast(0L)
                val safeTrim = segment.trim?.let { trim ->
                    GeTrimMath.clamp(trim, duration, GeEditRanges.VIDEO_TRIM_MIN_DURATION_MS).first
                }
                segment.copy(trim = safeTrim, sourceDurationMs = duration)
            }
        if (safeSegments.size != segments.size) {
            corrections.add(
                GeClampCorrection("video.segments.size", segments.size.toDouble(), safeSegments.size.toDouble())
            )
        }

        val safeBookmarks = GeBookmarkMath.normalize(
            bookmarks = bookmarks,
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = safeSegments.sumOf { it.sourceDurationMs },
        ).first
        if (safeBookmarks.size != bookmarks.size) {
            corrections.add(
                GeClampCorrection("video.bookmarks.size", bookmarks.size.toDouble(), safeBookmarks.size.toDouble())
            )
        }

        return copy(
            segments = safeSegments,
            speed = safeSpeed,
            rotationDegrees = safeRotation,
            lutIntensity = safeIntensity,
            targetBitrate = safeBitrate,
            bookmarks = safeBookmarks,
            outputFrameRate = safeFrameRate,
        ) to GeClampReport(corrections)
    }

    /** 规格是否可用于渲染（至少一段有效媒体）。 */
    fun isRenderable(): Boolean = segments.any { it.isUsable }

    companion object {
        const val MIN_BITRATE_BPS = 100_000
        const val MAX_BITRATE_BPS = 400_000_000

        /** 归一化到 {0, 90, 180, 270}：越界取最近档位，NaN 不可能（Int）。 */
        fun normalizeRotation(degrees: Int): Int {
            val normalized = ((degrees % 360) + 360) % 360
            return GeEditRanges.VIDEO_ROTATION_ALLOWED.minByOrNull { candidate ->
                val diff = abs(candidate - normalized)
                minOf(diff, 360 - diff)
            } ?: 0
        }
    }
}

/** 时间区间裁剪的纯校验/收敛。 */
object GeTrimMath {

    /**
     * 收敛裁剪区间到 [0, sourceDurationMs] 且保证长度 >= [minDurationMs]。
     *
     * 处理：负数起点 → 0；起点越界 → 贴到片尾前 [minDurationMs]；终点为空 → 片尾；
     * 终点越界 → 片尾；终点 <= 起点 → 起点 + [minDurationMs]。
     * 退化源（duration <= 0）→ 返回全片（不裁剪）。
     */
    fun clamp(
        range: GeVideoTrimRange,
        sourceDurationMs: Long,
        minDurationMs: Long = GeEditRanges.VIDEO_TRIM_MIN_DURATION_MS,
    ): Pair<GeVideoTrimRange, GeClampReport> {
        val corrections = mutableListOf<GeClampCorrection>()
        val duration = sourceDurationMs
        if (duration <= 0L) {
            if (range.startMs != 0L || range.endMs != null) {
                corrections.add(
                    GeClampCorrection("trim.range", range.startMs.toDouble(), 0.0)
                )
            }
            return GeVideoTrimRange.FULL to GeClampReport(corrections)
        }
        val minDuration = minDurationMs.coerceIn(1L, duration)

        var start = range.startMs
        if (start < 0L) start = 0L
        if (start > duration - minDuration) start = (duration - minDuration).coerceAtLeast(0L)

        var end = range.endMs ?: duration
        if (end > duration) end = duration
        if (end < start + minDuration) end = (start + minDuration).coerceAtMost(duration)
        if (end <= start) end = (start + minDuration).coerceAtMost(duration)

        if (start != range.startMs) {
            corrections.add(GeClampCorrection("trim.startMs", range.startMs.toDouble(), start.toDouble()))
        }
        if (range.endMs != null && end != range.endMs) {
            corrections.add(GeClampCorrection("trim.endMs", range.endMs.toDouble(), end.toDouble()))
        }
        return GeVideoTrimRange(start, end) to GeClampReport(corrections)
    }

    /** 越界判定（供参数校验/日志）。 */
    fun isOutOfBounds(range: GeVideoTrimRange, sourceDurationMs: Long): Boolean {
        if (range.startMs < 0L) return true
        if (range.startMs > sourceDurationMs) return true
        val end = range.resolvedEndMs(sourceDurationMs)
        return end > sourceDurationMs || end <= range.startMs
    }
}

/** 书签集合的纯操作（排序 / 去重 / 容量约束）。 */
object GeBookmarkMath {

    /**
     * 归一化：过滤非法、按时间升序、邻近视作重复去重、超出上限截断。
     *
     * @param dedupeWindowMs 去重窗口（默认 50ms，同一帧内的重复收藏合并）。
     */
    fun normalize(
        bookmarks: List<GeVideoBookmark>,
        maxCount: Int = GeEditRanges.VIDEO_BOOKMARK_MAX,
        totalDurationMs: Long = 0L,
        dedupeWindowMs: Long = DEFAULT_DEDUPE_WINDOW_MS,
    ): Pair<List<GeVideoBookmark>, GeClampReport> {
        val corrections = mutableListOf<GeClampCorrection>()
        val limit = maxCount.coerceIn(1, GeEditRanges.VIDEO_BOOKMARK_MAX_LIMIT)
        val upperBound = if (totalDurationMs > 0L) totalDurationMs else Long.MAX_VALUE

        val usable = bookmarks
            .asSequence()
            .filter { it.isUsable }
            .map { bookmark ->
                if (bookmark.timeMs > upperBound) {
                    corrections.add(
                        GeClampCorrection("bookmark.timeMs", bookmark.timeMs.toDouble(), upperBound.toDouble())
                    )
                    bookmark.copy(timeMs = upperBound)
                } else {
                    bookmark
                }
            }
            .sortedBy { it.timeMs }
            .toList()

        val deduped = mutableListOf<GeVideoBookmark>()
        for (bookmark in usable) {
            val last = deduped.lastOrNull()
            if (last != null && bookmark.timeMs - last.timeMs < dedupeWindowMs) {
                corrections.add(
                    GeClampCorrection("bookmark.dedupe", bookmark.timeMs.toDouble(), last.timeMs.toDouble())
                )
                continue
            }
            deduped.add(bookmark)
        }

        var result: List<GeVideoBookmark> = deduped
        if (result.size > limit) {
            result = result.take(limit)
            corrections.add(GeClampCorrection("bookmark.count", deduped.size.toDouble(), limit.toDouble()))
        }
        return result to GeClampReport(corrections)
    }

    /** 新增书签并立即归一化。 */
    fun add(
        bookmarks: List<GeVideoBookmark>,
        bookmark: GeVideoBookmark,
        maxCount: Int = GeEditRanges.VIDEO_BOOKMARK_MAX,
        totalDurationMs: Long = 0L,
    ): Pair<List<GeVideoBookmark>, GeClampReport> = normalize(
        bookmarks = bookmarks + bookmark,
        maxCount = maxCount,
        totalDurationMs = totalDurationMs,
    )

    /** 删除最接近 [timeMs] 的书签（返回新列表与是否删除）。 */
    fun removeNearest(
        bookmarks: List<GeVideoBookmark>,
        timeMs: Long,
        windowMs: Long = DEFAULT_DEDUPE_WINDOW_MS * 4,
    ): Pair<List<GeVideoBookmark>, Boolean> {
        if (bookmarks.isEmpty()) return bookmarks to false
        val target = bookmarks.minByOrNull { abs(it.timeMs - timeMs) } ?: return bookmarks to false
        if (abs(target.timeMs - timeMs) > windowMs) return bookmarks to false
        return bookmarks.filterNot { it === target } to true
    }

    /** 视频在变速后的输出时长（用于时间轴/进度显示）。 */
    fun scaledDurationMs(sourceDurationMs: Long, speed: Float): Long {
        val safeSpeed = GeEditRanges.clampFloat(
            speed,
            GeEditRanges.VIDEO_SPEED_MIN,
            GeEditRanges.VIDEO_SPEED_MAX,
            GeEditRanges.VIDEO_SPEED_DEFAULT,
        )
        if (sourceDurationMs <= 0L) return 0L
        return (sourceDurationMs / safeSpeed).toLong().coerceAtLeast(0L)
    }

    const val DEFAULT_DEDUPE_WINDOW_MS = 50L
}
