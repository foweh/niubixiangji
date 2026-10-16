package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 裁剪几何核心单测：边界、比例约束、最小尺寸、像素换算。
 *
 * 覆盖 PATTERN_SPEC §7.4 要求的"裁剪边界 / 比例约束"两项。
 */
class GeCropMathTest {

    private val squareSource = GeSize(1000, 1000)
    private val landscapeSource = GeSize(2000, 1000)
    private val photoSource = GeSize(4000, 3000)

    private fun assertPixelAspect(rect: GeCropRect, source: GeSize, expected: Float, tolerance: Float = 0.01f) {
        val pixelW = rect.width * source.width
        val pixelH = rect.height * source.height
        assertTrue("裁剪框必须有面积", pixelW > 0f && pixelH > 0f)
        assertEquals("像素宽高比应符合比例档位", expected, pixelW / pixelH, tolerance)
    }

    @Test
    fun `fitRatioCentered 在 4-3 源图上生成精确的 1-1 裁剪框`() {
        val rect = GeCropMath.fitRatioCentered(photoSource, GeCropRatioSpec.SQUARE)

        assertPixelAspect(rect, photoSource, 1f)
        assertEquals(3000f, rect.width * photoSource.width, 1f)
        assertEquals(3000f, rect.height * photoSource.height, 1f)
        // 居中：左右留白相等
        assertEquals(rect.left, 1f - rect.right, 1e-5f)
        assertEquals(rect.top, 1f - rect.bottom, 1e-5f)
    }

    @Test
    fun `fitRatioCentered 支持 9-16 竖版比例且完全落在源图内`() {
        val rect = GeCropMath.fitRatioCentered(photoSource, GeCropRatioSpec.RATIO_9_16)

        assertPixelAspect(rect, photoSource, 9f / 16f, 0.02f)
        assertTrue(rect.left >= 0f && rect.top >= 0f)
        assertTrue(rect.right <= 1f && rect.bottom <= 1f)
    }

    @Test
    fun `fitRatioCentered 对自由比例返回整图`() {
        val rect = GeCropMath.fitRatioCentered(photoSource, GeCropRatioSpec.FREE)

        assertEquals(GeCropRect.FULL, rect)
    }

    @Test
    fun `fitRatioCentered 在退化源尺寸下回落整图`() {
        val rect = GeCropMath.fitRatioCentered(GeSize(0, 0), GeCropRatioSpec.SQUARE)

        assertEquals(GeCropRect.FULL, rect)
    }

    @Test
    fun `clampToUnit 把越界框平移回单位方框内`() {
        val movedRight = GeCropMath.clampToUnit(GeCropRect(0.8f, 0.8f, 1.5f, 1.5f))

        assertEquals(0.3f, movedRight.left, 1e-5f)
        assertEquals(0.3f, movedRight.top, 1e-5f)
        assertEquals(1f, movedRight.right, 1e-5f)
        assertEquals(1f, movedRight.bottom, 1e-5f)
        assertEquals(0.7f, movedRight.width, 1e-5f)
    }

    @Test
    fun `clampToUnit 裁剪负向越界并保持尺寸`() {
        val rect = GeCropMath.clampToUnit(GeCropRect(-0.2f, -0.2f, 0.5f, 0.5f))

        assertEquals(GeCropRect(0f, 0f, 0.7f, 0.7f), rect)
    }

    @Test
    fun `sanitize 对 NaN 与退化框一律回落整图`() {
        assertEquals(GeCropRect.FULL, GeCropMath.sanitize(GeCropRect(Float.NaN, 0f, 1f, 1f)))
        assertEquals(GeCropRect.FULL, GeCropMath.sanitize(GeCropRect(0.5f, 0.5f, 0.5f, 0.5f)))
        assertEquals(
            GeCropRect.FULL,
            GeCropMath.sanitize(GeCropRect(0.6f, 0.1f, 0.4f, 0.9f)),
        )
    }

    @Test
    fun `move 越界时贴边而不缩小`() {
        val moved = GeCropMath.move(GeCropRect(0.1f, 0.1f, 0.5f, 0.5f), -1f, -1f)

        assertEquals(0f, moved.left, 1e-5f)
        assertEquals(0f, moved.top, 1e-5f)
        assertEquals(0.4f, moved.width, 1e-5f)
        assertEquals(0.4f, moved.height, 1e-5f)
    }

    @Test
    fun `move 忽略非有限位移量`() {
        val origin = GeCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = GeCropMath.move(origin, Float.NaN, Float.POSITIVE_INFINITY)

        assertEquals(origin, moved)
    }

    @Test
    fun `固定比例下手柄拖拽保持像素宽高比`() {
        // 2:1 像素源图 + 1:1 目标 → 归一化比例必为 0.5
        val rect = GeCropMath.resize(
            rect = GeCropRect(0.25f, 0.25f, 0.75f, 0.75f),
            handle = GeCropHandle.RIGHT,
            deltaX = -0.1f,
            deltaY = 0f,
            source = landscapeSource,
            spec = GeCropRatioSpec.SQUARE,
        )

        assertPixelAspect(rect, landscapeSource, 1f)
        assertTrue(rect.width >= GeEditRanges.CROP_MIN_FRACTION)
    }

    @Test
    fun `自由比例下手柄拖拽只改变被拖的边`() {
        val rect = GeCropMath.resize(
            rect = GeCropRect(0.25f, 0.25f, 0.75f, 0.75f),
            handle = GeCropHandle.LEFT,
            deltaX = -0.2f,
            deltaY = 0f,
            source = squareSource,
            spec = GeCropRatioSpec.FREE,
        )

        assertEquals(0.05f, rect.left, 1e-4f)
        assertEquals(0.75f, rect.right, 1e-4f)
        assertEquals(0.25f, rect.top, 1e-4f)
        assertEquals(0.75f, rect.bottom, 1e-4f)
    }

    @Test
    fun `手柄拖拽不会突破最小边长`() {
        val rect = GeCropMath.resize(
            rect = GeCropRect(0.25f, 0.25f, 0.75f, 0.75f),
            handle = GeCropHandle.LEFT,
            deltaX = 10f,
            deltaY = 0f,
            source = squareSource,
            spec = GeCropRatioSpec.FREE,
        )

        val expectedMin = GeCropMath.minFractionFor(squareSource, GeEditRanges.CROP_MIN_FRACTION, true)
        assertEquals(expectedMin, rect.width, 1e-4f)
        assertTrue(rect.width > 0f)
    }

    @Test
    fun `enforceMinSize 扩张退化框且仍在源图内`() {
        val rect = GeCropMath.enforceMinSize(
            rect = GeCropRect(0.5f, 0.5f, 0.5001f, 0.5001f),
            minFraction = GeEditRanges.CROP_MIN_FRACTION,
            source = photoSource,
        )

        assertTrue(rect.width >= GeCropMath.minFractionFor(photoSource, GeEditRanges.CROP_MIN_FRACTION, true) - 1e-5f)
        assertTrue(rect.left >= 0f && rect.right <= 1f)
    }

    @Test
    fun `toPixels 始终落在源图内且至少 1 像素`() {
        val full = GeCropMath.toPixels(GeCropRect.FULL, GeSize(3, 3))
        assertEquals(GeIntRect(0, 0, 3, 3), full)

        val tiny = GeCropMath.toPixels(GeCropRect(0.999f, 0.999f, 1f, 1f), GeSize(10, 10))
        assertTrue(tiny.width >= 1 && tiny.height >= 1)
        assertTrue(tiny.right <= 10 && tiny.bottom <= 10)
    }

    @Test
    fun `toPixels 对退化源尺寸返回空矩形`() {
        val rect = GeCropMath.toPixels(GeCropRect.FULL, GeSize(0, 100))

        assertEquals(GeIntRect(0, 0, 0, 0), rect)
    }

    @Test
    fun `fromPixels 与 toPixels 往返一致`() {
        val pixels = GeIntRect(250, 100, 1750, 900)
        val normalized = GeCropMath.fromPixels(pixels, landscapeSource)
        val roundTrip = GeCropMath.toPixels(normalized, landscapeSource)

        assertEquals(pixels.left, roundTrip.left)
        assertEquals(pixels.top, roundTrip.top)
        assertEquals(pixels.right, roundTrip.right)
        assertEquals(pixels.bottom, roundTrip.bottom)
    }

    @Test
    fun `settle 组合比例与最小尺寸后仍是合法 1-1`() {
        val rect = GeCropMath.settle(GeCropRect.FULL, GeCropRatioSpec.SQUARE, landscapeSource)

        assertPixelAspect(rect, landscapeSource, 1f)
        val pixels = GeCropMath.toPixels(rect, landscapeSource)
        assertEquals(pixels.width, pixels.height)
    }

    @Test
    fun `normalizedAspect 换算像素比例与归一化比例`() {
        assertEquals(0.5f, GeCropMath.normalizedAspect(1f, landscapeSource), 1e-6f)
        assertEquals(0f, GeCropMath.normalizedAspect(0f, landscapeSource), 1e-6f)
        assertEquals(0f, GeCropMath.normalizedAspect(1f, GeSize(0, 0)), 1e-6f)
    }

    @Test
    fun `resize 对手柄 NONE 返回原矩形`() {
        val origin = GeCropRect(0.2f, 0.3f, 0.6f, 0.7f)

        assertEquals(
            origin,
            GeCropMath.resize(
                rect = origin,
                handle = GeCropHandle.NONE,
                deltaX = 0.2f,
                deltaY = 0.2f,
                source = squareSource,
                spec = GeCropRatioSpec.SQUARE,
            ),
        )
    }

    @Test
    fun `自定义比例 clamp 到极端值区间`() {
        val (tooNarrow, reportNarrow) = GeCropRatioSpec.fromRatio(1f, 1000f).clamp()
        assertEquals(GeCropRatioSpec.MIN_CUSTOM_ASPECT, tooNarrow.pixelAspect, 1e-5f)
        assertTrue(reportNarrow.changed)

        val (tooWide, _) = GeCropRatioSpec.fromRatio(1000f, 1f).clamp()
        assertEquals(GeCropRatioSpec.MAX_CUSTOM_ASPECT, tooWide.pixelAspect, 1e-5f)

        val (degenerate, _) = GeCropRatioSpec.fromRatio(Float.NaN, 0f).clamp()
        assertTrue(degenerate.pixelAspect.isFinite())
        assertTrue(degenerate.pixelAspect >= GeCropRatioSpec.MIN_CUSTOM_ASPECT)
    }

    @Test
    fun `minFractionFor 按短边折算且受上限约束`() {
        val wide = GeSize(4000, 1000)
        val horizontal = GeCropMath.minFractionFor(wide, GeEditRanges.CROP_MIN_FRACTION, horizontal = true)
        assertEquals(0.05f * 1000f / 4000f, horizontal, 1e-6f)

        val clamped = GeCropMath.minFractionFor(wide, 10f, horizontal = false)
        assertEquals(GeEditRanges.CROP_MIN_FRACTION_LIMIT_MAX, clamped, 1e-6f)
    }

    @Test
    fun `裁剪框面积计算不为负`() {
        assertTrue(GeCropRect(0.6f, 0.6f, 0.4f, 0.4f).area == 0f)
        assertTrue(abs(GeCropRect(0.5f, 0.5f, 1f, 1f).area - 0.25f) < 1e-6f)
    }
}
