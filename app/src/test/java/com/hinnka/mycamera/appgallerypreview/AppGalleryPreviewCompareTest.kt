package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前后/原图对比滑杆映射测试（相册第 37 项）。
 *
 * 覆盖任务书指定项：**对比滑杆映射（正常/边界/异常）**；并补齐可用性判定（缺版本/同源/加载失败）。
 * 纯 JVM。
 */
class AppGalleryPreviewCompareTest {

    private val mapping = AppGalleryPreviewCompareMapping
    private val config = AppGalleryPreviewConfig.DEFAULT

    // ---------------- 位置 → 比例（正常） ----------------

    @Test
    fun centerPositionMapsToHalf() {
        assertEquals(0.5f, mapping.ratioForPosition(500f, 1000f), 1e-4f)
        assertEquals(0.25f, mapping.ratioForPosition(250f, 1000f), 1e-4f)
        assertEquals(0.75f, mapping.ratioForPosition(750f, 1000f), 1e-4f)
    }

    @Test
    fun handleHalfWidthShrinksUsableSpan() {
        // 视口 1000、手柄半宽 100 → 可用区间 [100, 900]，跨度 800。
        assertEquals(0f, mapping.ratioForPosition(100f, 1000f, 100f), 1e-4f)
        assertEquals(0.5f, mapping.ratioForPosition(500f, 1000f, 100f), 1e-4f)
        assertEquals(1f, mapping.ratioForPosition(900f, 1000f, 100f), 1e-4f)
    }

    // ---------------- 位置 → 比例（边界） ----------------

    @Test
    fun positionOutsideViewportIsClamped() {
        assertEquals(0f, mapping.ratioForPosition(-500f, 1000f), 1e-4f)
        assertEquals(1f, mapping.ratioForPosition(99999f, 1000f), 1e-4f)
        assertEquals(0f, mapping.ratioForPosition(0f, 1000f), 1e-4f)
        assertEquals(1f, mapping.ratioForPosition(1000f, 1000f), 1e-4f)
    }

    @Test
    fun degenerateViewportFallsBackToCenter() {
        assertEquals(0.5f, mapping.ratioForPosition(500f, 0f), 1e-4f)
        assertEquals(0.5f, mapping.ratioForPosition(500f, -10f), 1e-4f)
        // 手柄吃掉全部宽度，跨度 <= 0。
        assertEquals(0.5f, mapping.ratioForPosition(500f, 1000f, 600f), 1e-4f)
    }

    // ---------------- 位置 → 比例（异常输入） ----------------

    @Test
    fun nonFiniteInputsFallBackToCenter() {
        assertEquals(0.5f, mapping.ratioForPosition(Float.NaN, 1000f), 1e-4f)
        assertEquals(0.5f, mapping.ratioForPosition(500f, Float.NaN), 1e-4f)
        assertEquals(0.5f, mapping.ratioForPosition(Float.POSITIVE_INFINITY, 1000f), 1e-4f)
        assertEquals(0.5f, mapping.ratioForPosition(500f, Float.POSITIVE_INFINITY), 1e-4f)
        // 非法手柄半宽退化为 0，不影响主映射。
        assertEquals(0.5f, mapping.ratioForPosition(500f, 1000f, Float.NaN), 1e-4f)
    }

    @Test
    fun clampRatioSanitizesNonFinite() {
        assertEquals(0.5f, mapping.clampRatio(Float.NaN), 1e-4f)
        assertEquals(0.5f, mapping.clampRatio(Float.NEGATIVE_INFINITY), 1e-4f)
        assertEquals(0f, mapping.clampRatio(-3f), 1e-4f)
        assertEquals(1f, mapping.clampRatio(3f), 1e-4f)
        assertEquals(0.42f, mapping.clampRatio(0.42f), 1e-4f)
    }

    // ---------------- 比例 → 位置（往返一致性） ----------------

    @Test
    fun ratioAndPositionRoundTrip() {
        val ratios = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (ratio in ratios) {
            val position = mapping.positionForRatio(ratio, 1000f, 0f)
            assertEquals(ratio, mapping.ratioForPosition(position, 1000f, 0f), 1e-4f)
        }
    }

    @Test
    fun positionForRatioHandlesDegenerateInput() {
        assertEquals(0f, mapping.positionForRatio(0.5f, 0f), 1e-4f)
        assertEquals(500f, mapping.positionForRatio(0.5f, 1000f, 600f), 1e-4f)
        assertEquals(500f, mapping.positionForRatio(Float.NaN, 1000f), 1e-4f)
    }

    // ---------------- 拖动映射 ----------------

    @Test
    fun draggedRatioMovesWithDeltaAndClamps() {
        assertEquals(0.6f, mapping.draggedRatio(0.5f, 100f, 1000f), 1e-4f)
        assertEquals(0.4f, mapping.draggedRatio(0.5f, -100f, 1000f), 1e-4f)
        assertEquals(1f, mapping.draggedRatio(0.95f, 500f, 1000f), 1e-4f)
        assertEquals(0f, mapping.draggedRatio(0.05f, -500f, 1000f), 1e-4f)
    }

    @Test
    fun draggedRatioKeepsStartOnInvalidViewportOrDefaults() {
        assertEquals(0.3f, mapping.draggedRatio(0.3f, 100f, 0f), 1e-4f)
        assertEquals(0.3f, mapping.draggedRatio(0.3f, Float.NaN, 1000f), 1e-4f)
        assertEquals(0.5f, mapping.draggedRatio(Float.NaN, 100f, 0f), 1e-4f)
    }

    // ---------------- 手柄命中 ----------------

    @Test
    fun handleHitTestUsesTouchSlop() {
        assertTrue(mapping.hitTestHandle(500f, 500f, 24f))
        assertTrue(mapping.hitTestHandle(524f, 500f, 24f))
        assertFalse(mapping.hitTestHandle(525f, 500f, 24f))
        assertFalse(mapping.hitTestHandle(Float.NaN, 500f, 24f))
        assertFalse(mapping.hitTestHandle(500f, Float.NaN, 24f))
        // 非法 slop 收敛为 0（只精确命中）。
        assertTrue(mapping.hitTestHandle(500f, 500f, Float.NaN))
        assertFalse(mapping.hitTestHandle(501f, 500f, Float.NaN))
    }

    @Test
    fun horizontalDragIsConsumedOnlyWhenComparingOnHandle() {
        assertTrue(mapping.shouldConsumeHorizontalDrag(isComparing = true, handleHit = true))
        assertFalse(mapping.shouldConsumeHorizontalDrag(isComparing = true, handleHit = false))
        assertFalse(mapping.shouldConsumeHorizontalDrag(isComparing = false, handleHit = true))
    }

    // ---------------- 可用性判定 ----------------

    @Test
    fun availabilityTrueWhenBothVariantsPresent() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants("content://a/original.jpg", "content://a/processed.jpg"),
            config,
        )
        assertTrue(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.NONE, availability.reason)
        assertEquals(config.compareSliderDefaultRatio, availability.initialRatio, 1e-4f)
    }

    @Test
    fun availabilityFalseWhenOriginalMissing() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants(null, "content://a/processed.jpg"),
            config,
        )
        assertFalse(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL, availability.reason)
    }

    @Test
    fun availabilityFalseWhenProcessedMissing() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants("content://a/original.jpg", null),
            config,
        )
        assertFalse(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.MISSING_PROCESSED, availability.reason)
    }

    @Test
    fun availabilityFalseWhenBothMissing() {
        val availability = mapping.availability(AppGalleryPreviewCompareVariants(null, null), config)
        assertFalse(availability.available)
        assertTrue(availability.reason != AppGalleryPreviewCompareUnavailableReason.NONE)
    }

    @Test
    fun availabilityFalseForSameSource() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants("content://a/same.jpg", "content://a/same.jpg"),
            config,
        )
        assertFalse(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.SAME_SOURCE, availability.reason)
    }

    @Test
    fun availabilityFalseOnLoadFailure() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants("content://a/original.jpg", "content://a/processed.jpg"),
            config,
            loadFailed = true,
        )
        assertFalse(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.LOAD_FAILED, availability.reason)
    }

    @Test
    fun blankUrisCountAsMissing() {
        val availability = mapping.availability(AppGalleryPreviewCompareVariants("   ", "content://a/p.jpg"), config)
        assertFalse(availability.available)
        assertEquals(AppGalleryPreviewCompareUnavailableReason.MISSING_ORIGINAL, availability.reason)
    }

    @Test
    fun initialRatioIsClampedFromConfig() {
        val availability = mapping.availability(
            AppGalleryPreviewCompareVariants("content://a/o.jpg", "content://a/p.jpg"),
            config.copy(compareSliderDefaultRatio = 5f),
        )
        assertEquals(1f, availability.initialRatio, 1e-4f)
    }

    @Test
    fun compareSliderRatioAndTouchSlopAreClamped() {
        val result = AppGalleryPreviewConfig(
            compareSliderDefaultRatio = -1f,
            compareHandleTouchSlopDp = 500f,
        ).appGalleryPreviewSafe()
        assertEquals(0f, result.config.compareSliderDefaultRatio, 1e-4f)
        assertEquals(96f, result.config.compareHandleTouchSlopDp, 1e-4f)
        assertTrue(result.correctedFields.contains("compareSliderDefaultRatio"))
        assertTrue(result.correctedFields.contains("compareHandleTouchSlopDp"))
    }
}
