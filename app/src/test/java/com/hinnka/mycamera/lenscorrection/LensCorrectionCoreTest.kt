package com.hinnka.mycamera.lenscorrection

import com.hinnka.mycamera.lenscorrection.LensTestSupport.alpha
import com.hinnka.mycamera.lenscorrection.LensTestSupport.channel
import com.hinnka.mycamera.lenscorrection.LensTestSupport.checker
import com.hinnka.mycamera.lenscorrection.LensTestSupport.countTransparent
import com.hinnka.mycamera.lenscorrection.LensTestSupport.uniform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU 重映射单测：恒等复制、**边界策略**（CLAMP 无透明 / TRANSPARENT_BLACK 四角透明）、
 * 双线性插值、行带分片一致性。
 */
class LensCorrectionCoreTest {

    private val ultraWide = LensDistortionConfig(
        enabled = true,
        dimension = LensCameraDimension.ULTRA_WIDE,
        strength = 1f,
    )

    @Test
    fun identityGridReproducesSourcePixels() {
        val width = 16
        val height = 16
        val source = checker(width, height, cell = 4)
        val out = LensCorrectionCore.remap(
            source, width, height, DistortionRemapGrid.identity(width, height),
        )
        assertEquals(source.size, out.size)
        assertTrue(
            "恒等网格必须逐像素复制（允许 1 LSB 浮点误差）: ${LensTestSupport.maxChannelDiff(source, out)}",
            LensTestSupport.maxChannelDiff(source, out) <= 2,
        )
    }

    @Test
    fun identityRemapPreservesAlpha() {
        val source = uniform(8, 8, 120, 130, 140, a = 33)
        val out = LensCorrectionCore.remap(source, 8, 8, DistortionRemapGrid.identity(8, 8))
        for (p in out) assertEquals(33, alpha(p))
    }

    @Test
    fun clampPolicyNeverProducesTransparentPixels() {
        val width = 32
        val height = 32
        val source = checker(width, height)
        val out = LensCorrectionCore.remapWithConfig(
            source, width, height, ultraWide.copy(edgePolicy = DistortionEdgePolicy.CLAMP),
        )
        assertEquals(0, countTransparent(out))
        for (p in out) assertEquals(255, alpha(p))
    }

    @Test
    fun transparentBlackPolicyClearsOutOfBoundsCornersButKeepsCenter() {
        val width = 32
        val height = 32
        val source = uniform(width, height, 200, 100, 50)
        val out = LensCorrectionCore.remapWithConfig(
            source, width, height,
            ultraWide.copy(edgePolicy = DistortionEdgePolicy.TRANSPARENT_BLACK),
        )
        // 四角被推到源图之外 → 全透明黑
        assertEquals(0, out[0])
        assertEquals(0, out[width - 1])
        assertEquals(0, out[width * (height - 1)])
        assertEquals(0, out[width * height - 1])
        assertTrue("越界区域必须存在: ${countTransparent(out)}", countTransparent(out) > 0)
        // 画面中心必须不透明且保留原色
        val center = out[(height / 2) * width + width / 2]
        assertEquals(255, alpha(center))
        assertEquals(200, channel(center, 16))
    }

    @Test
    fun bilinearSamplingInterpolatesBetweenNeighbourPixels() {
        // 2×1 源图：像素 0 = 0，像素 1 = 255（像素中心分别在 0.5 / 1.5）
        val source = intArrayOf(LensTestSupport.argb(0, 0, 0), LensTestSupport.argb(255, 255, 255))
        val grid = DistortionRemapGrid(2, 1)
        grid.sourceX[0] = 1.0f // 正好落在两个像素的边界 → 50/50
        grid.sourceX[1] = 1.5f // 像素 1 的中心 → 精确取到像素 1
        grid.sourceY[0] = 0.5f
        grid.sourceY[1] = 0.5f

        val out = LensCorrectionCore.remap(source, 2, 1, grid)
        assertEquals("边界处应为两个像素的均值", 127, channel(out[0], 16))
        assertEquals("像素中心应精确命中该像素", 255, channel(out[1], 16))
    }

    @Test
    fun clampPolicyReplicatesEdgePixelsFarOutside() {
        val source = intArrayOf(
            LensTestSupport.argb(10, 10, 10), LensTestSupport.argb(20, 20, 20),
            LensTestSupport.argb(30, 30, 30), LensTestSupport.argb(40, 40, 40),
        )
        val grid = DistortionRemapGrid(1, 1)
        grid.sourceX[0] = -50f
        grid.sourceY[0] = -50f
        val clamped = LensCorrectionCore.remap(source, 2, 2, grid, DistortionEdgePolicy.CLAMP)
        assertEquals(10, channel(clamped[0], 16))
        assertEquals(255, alpha(clamped[0]))

        val transparent = LensCorrectionCore.remap(source, 2, 2, grid, DistortionEdgePolicy.TRANSPARENT_BLACK)
        assertEquals(0, transparent[0])
    }

    @Test
    fun nonFiniteSamplingCoordinatesYieldZero() {
        val source = uniform(2, 2, 50, 60, 70)
        val grid = DistortionRemapGrid(2, 1)
        grid.sourceX[0] = Float.NaN
        grid.sourceX[1] = Float.POSITIVE_INFINITY
        grid.sourceY[0] = 0f
        grid.sourceY[1] = 0f
        val out = LensCorrectionCore.remap(source, 2, 2, grid)
        assertEquals(0, out[0])
        assertEquals(0, out[1])
    }

    @Test
    fun bandedRemapMatchesWholeImageRemap() {
        val width = 32
        val height = 32
        val source = checker(width, height)
        val grid = DistortionRemapGrid.create(width, height, ultraWide)
        val whole = LensCorrectionCore.remap(source, width, height, grid, bandHeight = 0)
        val banded = LensCorrectionCore.remap(source, width, height, grid, bandHeight = 5)
        for (i in whole.indices) assertEquals("pixel $i", whole[i], banded[i])
    }

    @Test
    fun chunkCallbackIsInvokedOncePerBand() {
        var chunks = 0
        LensCorrectionCore.remap(
            source = checker(16, 16),
            srcWidth = 16,
            srcHeight = 16,
            grid = DistortionRemapGrid.identity(16, 16),
            bandHeight = 4,
            onChunk = { chunks++ },
        )
        assertEquals(4, chunks)
    }

    @Test
    fun remapWithConfigYieldsForGridAndForRemap() {
        var chunks = 0
        LensCorrectionCore.remapWithConfig(
            source = checker(16, 16),
            srcWidth = 16,
            srcHeight = 16,
            config = ultraWide,
            bandHeight = 4,
            onChunk = { chunks++ },
        )
        // 网格构建 4 个行带 + 重映射 4 个行带
        assertEquals(8, chunks)
    }

    @Test
    fun remapRejectsInvalidBuffers() {
        val grid = DistortionRemapGrid.identity(8, 8)
        assertThrows(IllegalArgumentException::class.java) {
            LensCorrectionCore.remap(IntArray(10), 8, 8, grid)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LensCorrectionCore.remap(IntArray(64), 0, 8, grid)
        }
    }
}
