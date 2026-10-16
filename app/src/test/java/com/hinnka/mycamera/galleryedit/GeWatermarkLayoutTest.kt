package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水印排版单测：九宫格锚点、边距、平铺网格、旋转包围盒、边界夹取。
 *
 * 覆盖 PATTERN_SPEC §7.4 要求的"水印排版边界"。
 */
class GeWatermarkLayoutTest {

    private val container = GeSize(1000, 1000)
    private val content = GeSize(100, 50)

    @Test
    fun `九宫格锚点按边距定位`() {
        val margin = GeWatermarkLayout.marginPx(container, GeEditRanges.MARGIN_FRACTION_DEFAULT)
        assertEquals(30f, margin, 1e-4f)

        val topLeft = GeWatermarkLayout.anchor(container, content, GeWatermarkPlacement.TOP_LEFT, 0.03f)
        assertEquals(30f, topLeft.x, 1e-4f)
        assertEquals(30f, topLeft.y, 1e-4f)

        val bottomRight = GeWatermarkLayout.anchor(container, content, GeWatermarkPlacement.BOTTOM_RIGHT, 0.03f)
        assertEquals(1000f - 30f - 100f, bottomRight.x, 1e-4f)
        assertEquals(1000f - 30f - 50f, bottomRight.y, 1e-4f)

        val center = GeWatermarkLayout.anchor(container, content, GeWatermarkPlacement.CENTER, 0.03f)
        assertEquals((1000f - 100f) / 2f, center.x, 1e-4f)
        assertEquals((1000f - 50f) / 2f, center.y, 1e-4f)

        val bottomCenter = GeWatermarkLayout.anchor(container, content, GeWatermarkPlacement.BOTTOM_CENTER, 0.03f)
        assertEquals((1000f - 100f) / 2f, bottomCenter.x, 1e-4f)
        assertEquals(1000f - 30f - 50f, bottomCenter.y, 1e-4f)
    }

    @Test
    fun `内容大于容器时锚点退化为左上角`() {
        val point = GeWatermarkLayout.anchor(
            container = GeSize(100, 100),
            content = GeSize(600, 600),
            placement = GeWatermarkPlacement.CENTER,
            marginFraction = 0.03f,
        )

        assertEquals(0f, point.x, 1e-4f)
        assertEquals(0f, point.y, 1e-4f)
    }

    @Test
    fun `锚点始终落在容器内`() {
        GeWatermarkPlacement.values().forEach { placement ->
            val point = GeWatermarkLayout.anchor(container, content, placement, 0.03f)
            assertTrue("$placement x 越界：${point.x}", point.x >= 0f && point.x + content.width <= 1000f)
            assertTrue("$placement y 越界：${point.y}", point.y >= 0f && point.y + content.height <= 1000f)
        }
    }

    @Test
    fun `边距按短边折算并被上限约束`() {
        val huge = GeWatermarkLayout.marginPx(GeSize(1000, 800), 5f)

        assertEquals(GeEditRanges.MARGIN_FRACTION_MAX * 800f, huge, 1e-4f)
        assertEquals(0f, GeWatermarkLayout.marginPx(GeSize(0, 0), 0.05f), 1e-4f)
    }

    @Test
    fun `平铺网格覆盖整幅画面`() {
        val points = GeWatermarkLayout.tile(container, GeSize(100, 100), spacingFactor = 2f)

        // step = 100 * 2 = 200，cols = rows = (1000 + 200) / 200 = 6
        assertEquals(36, points.size)
        assertEquals(0f, points.first().x, 1e-4f)
        assertEquals(0f, points.first().y, 1e-4f)
        assertTrue(points.any { it.x >= 1000f - 200f })
        assertTrue(points.any { it.y >= 1000f - 200f })
    }

    @Test
    fun `平铺间距系数被 clamp 且步进不小于内容尺寸`() {
        val tight = GeWatermarkLayout.tile(container, GeSize(100, 100), spacingFactor = 0.001f)
        val loose = GeWatermarkLayout.tile(container, GeSize(100, 100), spacingFactor = 1e9f)

        val tightStepX = tight[1].x - tight[0].x
        assertEquals("间距倍数下限 0.5 → 步进不小于内容一半", 50f, tightStepX, 1e-3f)

        val looseStepX = loose[1].x - loose[0].x
        assertEquals("间距倍数上限 10 → 步进 1000", 1000f, looseStepX, 1e-3f)
        assertTrue(loose.size < tight.size)
    }

    @Test
    fun `平铺支持偏移起点`() {
        val points = GeWatermarkLayout.tile(container, GeSize(100, 100), spacingFactor = 2f, offsetX = 150f)

        assertEquals(150f, points.first().x, 1e-4f)
    }

    @Test
    fun `平铺数量受 MAX_TILE_COUNT 上限约束`() {
        val points = GeWatermarkLayout.tile(
            container = GeSize(100_000, 100_000),
            content = GeSize(1, 1),
            spacingFactor = GeEditRanges.TILE_SPACING_MIN,
        )

        assertEquals(GeEditRanges.MAX_TILE_COUNT, points.size)
    }

    @Test
    fun `平铺对退化输入返回空列表`() {
        assertTrue(GeWatermarkLayout.tile(GeSize(0, 100), GeSize(10, 10)).isEmpty())
        assertTrue(GeWatermarkLayout.tile(container, GeSize(0, 10)).isEmpty())
    }

    @Test
    fun `旋转包围盒在 90 度时交换宽高`() {
        val rotated = GeWatermarkLayout.rotatedBounds(GeSize(100, 50), 90f)

        assertEquals(50, rotated.width)
        assertEquals(100, rotated.height)
    }

    @Test
    fun `旋转 0 度保持原尺寸且非法角度按 0 处理`() {
        assertEquals(GeSize(100, 50), GeWatermarkLayout.rotatedBounds(GeSize(100, 50), 0f))
        assertEquals(GeSize(100, 50), GeWatermarkLayout.rotatedBounds(GeSize(100, 50), Float.NaN))
        assertEquals(GeSize(100, 50), GeWatermarkLayout.rotatedBounds(GeSize(100, 50), Float.POSITIVE_INFINITY))
    }

    @Test
    fun `旋转包围盒不小于原内容`() {
        listOf(-30f, 0f, 45f, 90f, 180f, 270f).forEach { angle ->
            val rotated = GeWatermarkLayout.rotatedBounds(GeSize(120, 40), angle)
            assertTrue("角度 $angle 包围盒过小", rotated.width >= 1 && rotated.height >= 1)
        }
    }

    @Test
    fun `clampPoint 把越界与 NaN 收敛到合法范围`() {
        val clamped = GeWatermarkLayout.clampPoint(GePoint(5000f, 5000f), container, content)
        assertEquals(900f, clamped.x, 1e-4f)
        assertEquals(950f, clamped.y, 1e-4f)

        val broken = GeWatermarkLayout.clampPoint(GePoint(Float.NaN, Float.NaN), container, content)
        assertEquals(0f, broken.x, 1e-4f)
        assertEquals(0f, broken.y, 1e-4f)
    }

    @Test
    fun `clampPoint 对退化容器返回原点`() {
        val point = GeWatermarkLayout.clampPoint(GePoint(10f, 10f), GeSize(0, 0), content)

        assertEquals(0f, point.x, 1e-4f)
        assertEquals(0f, point.y, 1e-4f)
    }

    @Test
    fun `尺寸辅助属性正确`() {
        assertEquals(50, GeSize(100, 50).shortEdge)
        assertEquals(2f, GeSize(100, 50).aspect, 1e-5f)
        assertTrue(GeSize(1, 1).isValid)
        assertTrue(!GeSize(0, 1).isValid)
    }
}
