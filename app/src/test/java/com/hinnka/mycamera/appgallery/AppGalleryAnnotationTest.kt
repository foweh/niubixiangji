package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏 / 标签 / 评分状态机（相册项 5）：单条、批量、规范化与空态收敛。
 * 纯 JVM 测试。
 */
class AppGalleryAnnotationTest {

    private val config = AppGalleryConfig()

    private fun annotated(vararg annotations: AppGalleryAnnotation): Map<String, AppGalleryAnnotation> =
        annotations.associateBy { it.itemId }

    @Test
    fun `收藏开关往返并记录更新时间`() {
        var state = emptyMap<String, AppGalleryAnnotation>()
        state = AppGalleryAnnotationMachine.toggleFavorite(state, "a", now = 100L, config = config)
        assertTrue(state.getValue("a").isFavorite)
        assertEquals(100L, state.getValue("a").updatedAt)

        state = AppGalleryAnnotationMachine.toggleFavorite(state, "a", now = 200L, config = config)
        assertFalse(state.getValue("a").isFavorite)
        assertEquals(200L, state.getValue("a").updatedAt)
    }

    @Test
    fun `空态被自动剔除，不污染持久化内容`() {
        var state = AppGalleryAnnotationMachine.toggleFavorite(emptyMap(), "a", 1L, config)
        assertEquals(1, state.size)
        state = AppGalleryAnnotationMachine.toggleFavorite(state, "a", 2L, config)
        assertTrue(state.isEmpty())
    }

    @Test
    fun `评分循环 0 到上限再回到 0`() {
        var state = emptyMap<String, AppGalleryAnnotation>()
        val small = config.copy(maxRating = 3)
        repeat(4) { index ->
            state = AppGalleryAnnotationMachine.cycleRating(state, "a", index.toLong(), small)
        }
        // 0 → 1 → 2 → 3 → 0
        assertEquals(0, state["a"]?.rating ?: 0)
        assertEquals(0, state.size)
    }

    @Test
    fun `评分越界被 clamp 到 0 到 maxRating`() {
        val state = AppGalleryAnnotationMachine.setRating(emptyMap(), "a", 99, 1L, config)
        assertEquals(5, state.getValue("a").rating)
        val negative = AppGalleryAnnotationMachine.setRating(emptyMap(), "b", -3, 1L, config)
        assertEquals(0, negative.size)
    }

    @Test
    fun `标签加去重且大小写不敏感`() {
        var state = emptyMap<String, AppGalleryAnnotation>()
        state = AppGalleryAnnotationMachine.addTag(state, "a", "星空", 1L, config)
        state = AppGalleryAnnotationMachine.addTag(state, "a", "  星空  ", 2L, config)
        state = AppGalleryAnnotationMachine.addTag(state, "a", "光绘", 3L, config)
        assertEquals(listOf("星空", "光绘"), state.getValue("a").tags)

        state = AppGalleryAnnotationMachine.removeTag(state, "a", "光绘")
        assertEquals(listOf("星空"), state.getValue("a").tags)

        state = AppGalleryAnnotationMachine.removeTag(state, "a", "星空")
        assertTrue(state.isEmpty())
    }

    @Test
    fun `标签超过上限后拒绝新增`() {
        val limited = config.copy(maxTagCount = 2)
        var state = emptyMap<String, AppGalleryAnnotation>()
        state = AppGalleryAnnotationMachine.addTag(state, "a", "t1", 1L, limited)
        state = AppGalleryAnnotationMachine.addTag(state, "a", "t2", 2L, limited)
        state = AppGalleryAnnotationMachine.addTag(state, "a", "t3", 3L, limited)
        assertEquals(listOf("t1", "t2"), state.getValue("a").tags)
    }

    @Test
    fun `空标签与控制字符被拒绝或清洗`() {
        assertNull(AppGalleryAnnotationRules.normalizeTag("   ", config))
        assertNull(AppGalleryAnnotationRules.normalizeTag(null, config))
        val cleaned = AppGalleryAnnotationRules.normalizeTag("星\u0000空", config)
        assertEquals("星 空", cleaned)
        val tag = AppGalleryAnnotationMachine.addTag(emptyMap(), "a", "   ", 1L, config)
        assertTrue(tag.isEmpty())
    }

    @Test
    fun `超长标签被截断到上限`() {
        val limited = config.copy(maxTagLength = 4)
        val normalized = AppGalleryAnnotationRules.normalizeTag("一二三四五六", limited)
        assertEquals("一二三四", normalized)
    }

    @Test
    fun `收藏与标签互不干扰`() {
        var state = AppGalleryAnnotationMachine.toggleFavorite(emptyMap(), "a", 1L, config)
        state = AppGalleryAnnotationMachine.addTag(state, "a", "精选", 2L, config)
        assertTrue(state.getValue("a").isFavorite)
        assertEquals(listOf("精选"), state.getValue("a").tags)
        assertEquals(1, state.size)
    }

    @Test
    fun `批量开关收藏与批量评分`() {
        var state = AppGalleryAnnotationMachine.batchSetFavorite(emptyMap(), listOf("a", "b"), true, 1L, config)
        assertTrue(state.getValue("a").isFavorite)
        assertTrue(state.getValue("b").isFavorite)

        state = AppGalleryAnnotationMachine.batchToggleFavorite(state, listOf("a", "b"), 2L, config)
        assertFalse(state.getValue("a").isFavorite)
        assertFalse(state.getValue("b").isFavorite)

        state = AppGalleryAnnotationMachine.batchSetRating(state, listOf("a"), 4, 3L, config)
        assertEquals(4, state.getValue("a").rating)
    }

    @Test
    fun `批量增删标签`() {
        var state = AppGalleryAnnotationMachine.batchAddTags(emptyMap(), listOf("a", "b"), listOf("精选", "待整理"), 1L, config)
        assertEquals(listOf("精选", "待整理"), state.getValue("a").tags)
        state = AppGalleryAnnotationMachine.batchRemoveTags(state, listOf("a", "b"), listOf("待整理"), 2L, config)
        assertEquals(listOf("精选"), state.getValue("b").tags)
    }

    @Test
    fun `删除条目时清空其注解`() {
        val state = annotated(
            AppGalleryAnnotation("a", isFavorite = true),
            AppGalleryAnnotation("b", rating = 3),
        )
        val after = AppGalleryAnnotationMachine.drop(state, listOf("a"))
        assertFalse(after.containsKey("a"))
        assertTrue(after.containsKey("b"))
    }

    @Test
    fun `合并注解时以更新时间较新者为准`() {
        val base = annotated(AppGalleryAnnotation("a", rating = 5, updatedAt = 100L))
        val incoming = listOf(AppGalleryAnnotation("a", rating = 1, updatedAt = 50L))
        val merged = AppGalleryAnnotationMachine.mergeAnnotations(base, incoming)
        assertEquals(5, merged.getValue("a").rating)

        val newer = listOf(AppGalleryAnnotation("a", rating = 1, updatedAt = 200L))
        assertEquals(1, AppGalleryAnnotationMachine.mergeAnnotations(base, newer).getValue("a").rating)
    }

    @Test
    fun `标签词表按出现次数降序`() {
        val state = annotated(
            AppGalleryAnnotation("a", tags = listOf("星空")),
            AppGalleryAnnotation("b", tags = listOf("星空", "光绘")),
        )
        val vocabulary = AppGalleryAnnotationMachine.tagVocabulary(state, config)
        assertEquals("星空", vocabulary.first().first)
        assertEquals(2, vocabulary.first().second)
        assertEquals(2, vocabulary.size)
    }

    @Test
    fun `注解编解码往返保持内容`() {
        var state = AppGalleryAnnotationMachine.toggleFavorite(emptyMap(), "id,with\\special", 1L, config)
        state = AppGalleryAnnotationMachine.addTag(state, "id,with\\special", "星空", 2L, config)
        state = AppGalleryAnnotationMachine.setRating(state, "id,with\\special", 4, 3L, config)

        val payload = AppGalleryAnnotationCodec.encode(state)
        val decoded = AppGalleryAnnotationCodec.decode(payload, config)
        assertEquals(state, decoded)
        assertNull(AppGalleryAnnotationCodec.encode(emptyMap()))
    }
}
