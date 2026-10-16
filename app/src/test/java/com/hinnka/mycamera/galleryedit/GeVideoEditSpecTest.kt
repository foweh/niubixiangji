package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频编辑规格单测：时间区间裁剪参数、变速边界（正常/边界/异常）、书签集合运算。
 *
 * 覆盖 PATTERN_SPEC §7.4 要求的"视频区间裁剪参数 / 变速边界"。
 */
class GeVideoEditSpecTest {

    private val duration = 10_000L
    private val minDuration = GeEditRanges.VIDEO_TRIM_MIN_DURATION_MS

    @Test
    fun `合法裁剪区间保持不变`() {
        val (safe, report) = GeTrimMath.clamp(GeVideoTrimRange(1_000L, 3_000L), duration, minDuration)

        assertEquals(1_000L, safe.startMs)
        assertEquals(3_000L, safe.endMs)
        assertFalse(report.changed)
    }

    @Test
    fun `负数起点被收敛到片头`() {
        val (safe, report) = GeTrimMath.clamp(GeVideoTrimRange(-5_000L, 3_000L), duration, minDuration)

        assertEquals(0L, safe.startMs)
        assertEquals(3_000L, safe.endMs)
        assertTrue(report.changed)
    }

    @Test
    fun `终点越界被收敛到片尾`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(1_000L, 999_999L), duration, minDuration)

        assertEquals(1_000L, safe.startMs)
        assertEquals(duration, safe.endMs)
    }

    @Test
    fun `起点越界时贴到片尾前的最短时长`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(999_999L, null), duration, minDuration)

        assertEquals(duration - minDuration, safe.startMs)
        assertEquals(duration, safe.endMs)
    }

    @Test
    fun `终点早于起点时补足最短时长`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(5_000L, 1_000L), duration, minDuration)

        assertEquals(5_000L, safe.startMs)
        assertEquals(5_000L + minDuration, safe.endMs)
    }

    @Test
    fun `终点等于起点时补足最短时长`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(5_000L, 5_000L), duration, minDuration)

        assertEquals(5_000L, safe.startMs)
        assertEquals(5_000L + minDuration, safe.endMs)
    }

    @Test
    fun `终点缺省表示到片尾`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(2_000L, null), duration, minDuration)

        assertEquals(2_000L, safe.startMs)
        assertEquals(duration, safe.endMs)
        assertEquals(
            duration,
            GeVideoTrimRange(2_000L, null).resolvedEndMs(duration),
        )
    }

    @Test
    fun `退化源时长返回整片`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(100L, 200L), 0L, minDuration)

        assertEquals(GeVideoTrimRange.FULL, safe)
        assertEquals(
            GeVideoTrimRange.FULL,
            GeTrimMath.clamp(GeVideoTrimRange(100L, 200L), -1L, minDuration).first,
        )
    }

    @Test
    fun `最短时长大于源时长时按源时长收敛`() {
        val (safe, _) = GeTrimMath.clamp(GeVideoTrimRange(0L, 0L), 50L, 100L)

        assertEquals(0L, safe.startMs)
        assertEquals(50L, safe.endMs)
    }

    @Test
    fun `越界判定覆盖负数 超长 与空区间`() {
        assertFalse(GeTrimMath.isOutOfBounds(GeVideoTrimRange.FULL, duration))
        assertFalse(GeTrimMath.isOutOfBounds(GeVideoTrimRange(0L, duration), duration))
        assertTrue(GeTrimMath.isOutOfBounds(GeVideoTrimRange(-1L, null), duration))
        assertTrue(GeTrimMath.isOutOfBounds(GeVideoTrimRange(0L, 0L), duration))
        assertTrue(GeTrimMath.isOutOfBounds(GeVideoTrimRange(0L, duration + 1L), duration))
        assertTrue(GeTrimMath.isOutOfBounds(GeVideoTrimRange(duration + 1L, null), duration))
    }

    @Test
    fun `变速在正常区间保持原值`() {
        assertEquals(1f, GeVideoEditSpec(speed = 1f).clamp().first.speed, 1e-4f)
        assertEquals(2f, GeVideoEditSpec(speed = 2f).clamp().first.speed, 1e-4f)
        assertEquals(0.5f, GeVideoEditSpec(speed = 0.5f).clamp().first.speed, 1e-4f)
    }

    @Test
    fun `变速边界严格等于上下限`() {
        assertEquals(
            GeEditRanges.VIDEO_SPEED_MIN,
            GeVideoEditSpec(speed = GeEditRanges.VIDEO_SPEED_MIN).clamp().first.speed,
            1e-5f,
        )
        assertEquals(
            GeEditRanges.VIDEO_SPEED_MAX,
            GeVideoEditSpec(speed = GeEditRanges.VIDEO_SPEED_MAX).clamp().first.speed,
            1e-5f,
        )
    }

    @Test
    fun `变速异常输入回落默认一倍速`() {
        assertEquals(
            GeEditRanges.VIDEO_SPEED_DEFAULT,
            GeVideoEditSpec(speed = Float.NaN).clamp().first.speed,
            1e-5f,
        )
        assertEquals(
            GeEditRanges.VIDEO_SPEED_DEFAULT,
            GeVideoEditSpec(speed = Float.POSITIVE_INFINITY).clamp().first.speed,
            1e-5f,
        )
        assertEquals(
            GeEditRanges.VIDEO_SPEED_MIN,
            GeVideoEditSpec(speed = -3f).clamp().first.speed,
            1e-5f,
        )
    }

    @Test
    fun `变速后的输出时长换算正确`() {
        assertEquals(5_000L, GeBookmarkMath.scaledDurationMs(10_000L, 2f))
        assertEquals(20_000L, GeBookmarkMath.scaledDurationMs(10_000L, 0.5f))
        assertEquals(40_000L, GeBookmarkMath.scaledDurationMs(10_000L, GeEditRanges.VIDEO_SPEED_MIN))
        assertEquals(2_500L, GeBookmarkMath.scaledDurationMs(10_000L, GeEditRanges.VIDEO_SPEED_MAX))
        assertEquals(10_000L, GeBookmarkMath.scaledDurationMs(10_000L, 1f))
    }

    @Test
    fun `变速时长换算对异常输入先 clamp`() {
        assertEquals(10_000L, GeBookmarkMath.scaledDurationMs(10_000L, Float.NaN))
        assertEquals(40_000L, GeBookmarkMath.scaledDurationMs(10_000L, 0f))
        assertEquals(0L, GeBookmarkMath.scaledDurationMs(0L, 2f))
        assertEquals(0L, GeBookmarkMath.scaledDurationMs(-100L, 2f))
    }

    @Test
    fun `书签按时间升序且邻近视作重复`() {
        val (safe, report) = GeBookmarkMath.normalize(
            bookmarks = listOf(
                GeVideoBookmark(500L),
                GeVideoBookmark(100L),
                GeVideoBookmark(120L),
            ),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = duration,
        )

        assertEquals(2, safe.size)
        assertEquals(100L, safe.first().timeMs)
        assertEquals(500L, safe.last().timeMs)
        assertTrue(report.changed)
    }

    @Test
    fun `非法书签被过滤`() {
        val (safe, _) = GeBookmarkMath.normalize(
            bookmarks = listOf(GeVideoBookmark(-1L), GeVideoBookmark(1_000L)),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = duration,
        )

        assertEquals(1, safe.size)
        assertEquals(1_000L, safe.first().timeMs)
        assertFalse(GeVideoBookmark(-1L).isUsable)
        assertTrue(GeVideoBookmark(0L).isUsable)
    }

    @Test
    fun `超出片长的书签被收敛到片尾并去重`() {
        val (safe, report) = GeBookmarkMath.normalize(
            bookmarks = listOf(GeVideoBookmark(20_000L), GeVideoBookmark(30_000L)),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = duration,
        )

        assertEquals(1, safe.size)
        assertEquals(duration, safe.first().timeMs)
        assertTrue(report.changed)
    }

    @Test
    fun `书签数量上限生效且上限本身被 clamp 到合法区间`() {
        val many = List(500) { index -> GeVideoBookmark(index * 1_000L) }
        val (safe, _) = GeBookmarkMath.normalize(many, maxCount = 200, totalDurationMs = 1_000_000L)
        assertEquals(200, safe.size)

        val (single, _) = GeBookmarkMath.normalize(many, maxCount = 0, totalDurationMs = 1_000_000L)
        assertEquals(1, single.size)

        val (capped, _) = GeBookmarkMath.normalize(many, maxCount = Int.MAX_VALUE, totalDurationMs = 1_000_000L)
        assertTrue(capped.size <= GeEditRanges.VIDEO_BOOKMARK_MAX_LIMIT)
    }

    @Test
    fun `新增书签遵循去重窗口`() {
        val (kept, _) = GeBookmarkMath.add(
            bookmarks = listOf(GeVideoBookmark(1_000L)),
            bookmark = GeVideoBookmark(1_050L),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = duration,
        )
        assertEquals(2, kept.size)

        val (merged, _) = GeBookmarkMath.add(
            bookmarks = listOf(GeVideoBookmark(1_000L)),
            bookmark = GeVideoBookmark(1_030L),
            maxCount = GeEditRanges.VIDEO_BOOKMARK_MAX,
            totalDurationMs = duration,
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `删除最近书签受窗口约束`() {
        val (removed, didRemove) = GeBookmarkMath.removeNearest(
            bookmarks = listOf(GeVideoBookmark(1_000L), GeVideoBookmark(5_000L)),
            timeMs = 1_020L,
        )
        assertTrue(didRemove)
        assertEquals(listOf(5_000L), removed.map { it.timeMs })

        val (untouched, secondTry) = GeBookmarkMath.removeNearest(
            bookmarks = listOf(GeVideoBookmark(1_000L)),
            timeMs = 9_000L,
        )
        assertFalse(secondTry)
        assertEquals(listOf(1_000L), untouched.map { it.timeMs })

        val (empty, thirdTry) = GeBookmarkMath.removeNearest(emptyList(), 0L)
        assertTrue(empty.isEmpty())
        assertFalse(thirdTry)
    }

    @Test
    fun `片段可用性判定`() {
        assertTrue(GeVideoSegment(uriString = "content://media/1").isUsable)
        assertFalse(GeVideoSegment(uriString = "   ").isUsable)
        assertFalse(GeVideoSegment(uriString = "").isUsable)
    }

    @Test
    fun `规格可渲染判定要求至少一个有效片段`() {
        assertFalse(GeVideoEditSpec().isRenderable())
        assertTrue(GeVideoEditSpec(segments = listOf(GeVideoSegment("content://a"))).isRenderable())
    }
}
