package com.hinnka.mycamera.lenscorrection

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重映射网格单测：**映射边界**（恒等、四角越界、光心、分片一致性、归一化表布局）。
 */
class DistortionRemapGridTest {

    private val ultraWide = LensDistortionConfig(
        enabled = true,
        dimension = LensCameraDimension.ULTRA_WIDE,
        strength = 1f,
    )

    private fun assertGridEquals(expected: DistortionRemapGrid, actual: DistortionRemapGrid, tolerance: Float) {
        assertEquals(expected.count, actual.count)
        for (i in 0 until expected.count) {
            assertEquals("sourceX[$i]", expected.sourceX[i], actual.sourceX[i], tolerance)
            assertEquals("sourceY[$i]", expected.sourceY[i], actual.sourceY[i], tolerance)
        }
    }

    @Test
    fun identityGridMapsEveryPixelToItsOwnCenter() {
        val width = 8
        val height = 8
        val grid = DistortionRemapGrid.identity(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                assertEquals("sourceX@($x,$y)", x + 0.5f, grid.sourceX[i], 1e-3f)
                assertEquals("sourceY@($x,$y)", y + 0.5f, grid.sourceY[i], 1e-3f)
            }
        }
    }

    @Test
    fun kZeroGridIsIdentityEvenWhenEnabled() {
        val grid = DistortionRemapGrid.create(
            12, 12,
            LensDistortionConfig(
                enabled = true,
                strength = 1f,
                k1Override = 0f,
                k2Override = 0f,
                k3Override = 0f,
            ),
        )
        assertGridEquals(DistortionRemapGrid.identity(12, 12), grid, 1e-3f)
    }

    @Test
    fun normHalfIsHalfDiagonal() {
        val grid = DistortionRemapGrid(8, 6)
        assertEquals(0.5f * hypot(8f, 6f), grid.normHalf, 1e-4f)
    }

    @Test
    fun centerPixelStaysNearItsOwnPosition() {
        val size = 32
        val grid = DistortionRemapGrid.create(size, size, ultraWide)
        // 画面正中 4 个像素的采样点应几乎不动（r ≈ 0）
        for (y in 15..16) {
            for (x in 15..16) {
                val i = y * size + x
                assertEquals("center x", x + 0.5f, grid.sourceX[i], 0.05f)
                assertEquals("center y", y + 0.5f, grid.sourceY[i], 0.05f)
            }
        }
    }

    @Test
    fun barrelCorrectionPushesCornerSamplesOutwardAndOutOfBounds() {
        val size = 32
        val center = size / 2f
        val corrected = DistortionRemapGrid.create(size, size, ultraWide)
        val neutral = DistortionRemapGrid.identity(size, size)

        // 左上角被推到源图之外（需要靠 edgePolicy 处理 → 正是边界场景的由来）
        assertTrue("corner x must go negative: ${corrected.sourceX[0]}", corrected.sourceX[0] < 0f)
        assertTrue("corner y must go negative: ${corrected.sourceY[0]}", corrected.sourceY[0] < 0f)
        // 且比不校正时明显更远离光心（= 拉伸被压扁的边缘 = 校正桶形）
        assertTrue(
            "corrected corner must sample farther than neutral",
            abs(corrected.sourceX[0] - center) > abs(neutral.sourceX[0] - center) + 1f,
        )
    }

    @Test
    fun cropScaleScalesTheSamplingRadius() {
        val none = DistortionRemapGrid.create(
            24, 24, LensDistortionConfig(k1Override = 0f, k2Override = 0f, cropScale = 1f)
        )
        val zoomedOut = DistortionRemapGrid.create(
            24, 24, LensDistortionConfig(k1Override = 0f, k2Override = 0f, cropScale = 1.2f)
        )
        val zoomedIn = DistortionRemapGrid.create(
            24, 24, LensDistortionConfig(k1Override = 0f, k2Override = 0f, cropScale = 0.8f)
        )
        val center = 12f
        val base = abs(none.sourceX[0] - center)
        assertTrue(abs(zoomedOut.sourceX[0] - center) > base)
        assertTrue(abs(zoomedIn.sourceX[0] - center) < base)
    }

    @Test
    fun shiftedLightCenterChangesMapping() {
        val centered = DistortionRemapGrid.create(24, 24, ultraWide.copy(centerX = 0.5f, centerY = 0.5f))
        val shifted = DistortionRemapGrid.create(24, 24, ultraWide.copy(centerX = 0.3f, centerY = 0.5f))
        assertTrue(
            "光心横移必须改变采样坐标",
            abs(centered.sourceX[0] - shifted.sourceX[0]) > 0.5f,
        )
    }

    @Test
    fun fillRowsOnlyTouchesRequestedRows() {
        val size = 8
        val grid = DistortionRemapGrid(size, size)
        grid.fillRows(ultraWide, rowStart = 2, rowEnd = 4)
        for (i in 0 until size * size) {
            val y = i / size
            if (y < 2 || y >= 4) {
                assertEquals("row $y must stay untouched (x)", 0f, grid.sourceX[i], 0f)
                assertEquals("row $y must stay untouched (y)", 0f, grid.sourceY[i], 0f)
            } else {
                assertTrue("row $y must be filled", grid.sourceX[i] != 0f)
            }
        }
    }

    @Test
    fun fillRowsOutOfRangeIsClampedWithoutThrowing() {
        val grid = DistortionRemapGrid(8, 8)
        grid.fillRows(ultraWide, rowStart = -5, rowEnd = 999)
        assertTrue(grid.sourceX[4 * 8 + 4] != 0f)
        grid.fillRows(ultraWide, rowStart = 6, rowEnd = 3) // rowEnd < rowStart → 什么都不做
        assertEquals(0f, DistortionRemapGrid(8, 8).sourceX[0], 0f)
    }

    @Test
    fun bandedFillMatchesWholeFill() {
        val size = 24
        val whole = DistortionRemapGrid.create(size, size, ultraWide)
        val banded = DistortionRemapGrid(size, size)
        for (rowStart in 0 until size step 5) {
            banded.fillRows(ultraWide, rowStart, min(rowStart + 5, size))
        }
        assertGridEquals(whole, banded, 1e-5f)
    }

    @Test
    fun normalizedTableIsTexelCenterAlignedAndInterleaved() {
        val grid = DistortionRemapGrid.identity(8, 8)
        val table = grid.toNormalizedTable()
        assertEquals(8 * 8 * 2, table.size)
        // 交错 [u, v]，且用 GPU 约定的纹素中心：(x+0.5)/width
        assertEquals(0.5f / 8f, table[0], 1e-4f)
        assertEquals(0.5f / 8f, table[1], 1e-4f)
        val lastIndex = (8 * 8 - 1) * 2
        assertEquals(7.5f / 8f, table[lastIndex], 1e-4f)
        assertEquals(7.5f / 8f, table[lastIndex + 1], 1e-4f)
    }

    @Test
    fun gridRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { DistortionRemapGrid(0, 8) }
        assertThrows(IllegalArgumentException::class.java) { DistortionRemapGrid(8, -1) }
    }
}
