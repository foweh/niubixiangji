package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tiled 推理切分/拼缝的边界测试。
 *
 * 核心不变量：tile 必须**完整覆盖**图像、不越界、不重复起点；overlap 越大拼缝越淡；
 * 累加-归一化后权重为 0 的像素不产生 NaN。
 */
class SuperResTilePlanTest {

    // ---- 正常 ----

    @Test
    fun plan_exactMultiple_producesAlignedTiles() {
        val tiles = SuperResTilePlan.plan(width = 512, height = 512, tileSize = 256, overlap = 0)
        assertEquals(4, tiles.size)
        assertEquals(listOf(0, 256), tiles.map { it.x }.distinct().sorted())
        assertEquals(listOf(0, 256), tiles.map { it.y }.distinct().sorted())
        assertTrue(tiles.all { it.width == 256 && it.height == 256 })
        assertTrue(tiles.all { it.isValid })
    }

    @Test
    fun plan_lastTileSnapsToEdge_soCoverageIsComplete() {
        // 600 宽、tile 256、无重叠 → 起点 0,256 后回退贴边到 344，右边缘不丢像素。
        val tiles = SuperResTilePlan.plan(width = 600, height = 300, tileSize = 256, overlap = 0)
        val xs = tiles.map { it.x }.distinct().sorted()
        assertEquals(listOf(0, 256, 344), xs)
        assertTrue("最右 tile 必须贴住右边缘", tiles.any { it.x + it.width == 600 })
        assertTrue("不得越界", tiles.all { it.x >= 0 && it.y >= 0 })
        assertTrue(tiles.all { it.x + it.width <= 600 && it.y + it.height <= 300 })
    }

    @Test
    fun plan_coversEveryPixel_andOverlapOnlyInPullbackBand() {
        val w = 1000
        val h = 700
        val coverage = IntArray(w * h)
        SuperResTilePlan.plan(w, h, 256, 0).forEach { tile ->
            for (y in tile.y until tile.y + tile.height) {
                for (x in tile.x until tile.x + tile.width) coverage[y * w + x]++
            }
        }
        assertTrue("不允许有像素漏算", coverage.all { it >= 1 })
        // overlap=0 时不应出现人为重叠，唯一的重复来自「最后一列/行贴边回退」的重叠带。
        assertEquals("最多只允许两层重叠（回退带）", 2, coverage.max())
        assertEquals("绝大多数像素只被算一次", 1, coverage.min())
    }

    @Test
    fun plan_divisibleSize_hasNoOverlapAtAll() {
        val w = 512
        val h = 256
        val coverage = IntArray(w * h)
        SuperResTilePlan.plan(w, h, 256, 0).forEach { tile ->
            for (y in tile.y until tile.y + tile.height) {
                for (x in tile.x until tile.x + tile.width) coverage[y * w + x]++
            }
        }
        assertTrue("整除尺寸下每个像素恰好一次", coverage.all { it == 1 })
    }

    // ---- 边界 ----

    @Test
    fun plan_tileLargerThanImage_yieldsSingleFullTile() {
        val tiles = SuperResTilePlan.plan(width = 100, height = 80, tileSize = 1024, overlap = 32)
        assertEquals(1, tiles.size)
        val t = tiles.single()
        assertEquals(0, t.x)
        assertEquals(0, t.y)
        assertEquals(100, t.width)
        assertEquals(80, t.height)
    }

    @Test
    fun plan_overlapNotSmallerThanTile_doesNotHangAndStillCovers() {
        // 病态输入：overlap >= tileSize 时必须被内部收敛（步长 >= 1），且不得死循环。
        val tiles = SuperResTilePlan.plan(width = 300, height = 40, tileSize = 64, overlap = 999)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.width > 0 && it.height > 0 })
        assertTrue(tiles.all { it.x + it.width <= 300 })
        assertEquals("必须覆盖到右边缘", 300, tiles.maxOf { it.x + it.width })
    }

    @Test
    fun plan_indicesAreSequentialAndUnique() {
        val tiles = SuperResTilePlan.plan(777, 555, 200, 40)
        assertEquals(tiles.indices.toList(), tiles.map { it.index })
        assertEquals(tiles.size, tiles.map { it.x to it.y }.distinct().size)
    }

    @Test
    fun plan_zeroOrNegativeDimensions_returnsEmpty() {
        assertTrue(SuperResTilePlan.plan(0, 100, 256, 32).isEmpty())
        assertTrue(SuperResTilePlan.plan(100, 0, 256, 32).isEmpty())
        assertTrue(SuperResTilePlan.plan(-5, -5, 256, 32).isEmpty())
    }

    @Test
    fun planFromConfig_appliesClampedValues() {
        val cfg = SuperResConfig(tileSize = 128, tileOverlap = 16)
        val tiles = SuperResTilePlan.plan(cfg, 400, 400)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.width <= 128 && it.height <= 128 })
    }

    // ---- 拼缝权重 ----

    @Test
    fun seamWeight_interiorTileIsLowAtEdgeAndHighAtCenter() {
        val edge = SuperResTilePlan.seamWeight(
            localX = 0, localY = 50, tileWidth = 100, tileHeight = 100, seam = 10,
            touchesLeft = false, touchesTop = true, touchesRight = false, touchesBottom = true,
        )
        val center = SuperResTilePlan.seamWeight(
            localX = 50, localY = 50, tileWidth = 100, tileHeight = 100, seam = 10,
            touchesLeft = false, touchesTop = true, touchesRight = false, touchesBottom = true,
        )
        assertTrue("内部 tile 的左边缘权重应远低于中心", edge < center)
        assertTrue(center > 0f && center <= 1f)
        assertTrue(edge > 0f)
    }

    @Test
    fun seamWeight_imageEdgeIsNotFaded() {
        // 贴图像上/左边缘的 tile：不应淡化，否则整幅图外缘会变暗。
        val w = SuperResTilePlan.seamWeight(
            localX = 0, localY = 0, tileWidth = 100, tileHeight = 100, seam = 20,
            touchesLeft = true, touchesTop = true, touchesRight = false, touchesBottom = false,
        )
        assertEquals(1f, w, 1e-6f)
    }

    @Test
    fun seamWeight_zeroSeamMeansNoFading() {
        val w = SuperResTilePlan.seamWeight(
            localX = 0, localY = 0, tileWidth = 64, tileHeight = 64, seam = 0,
            touchesLeft = false, touchesTop = false, touchesRight = false, touchesBottom = false,
        )
        assertEquals(1f, w, 0f)
    }

    // ---- 累加 / 归一化 ----

    @Test
    fun accumulateAndNormalize_twoTilesReconstructConstantField() {
        val w = 64
        val h = 32
        val scale = 1
        val outW = w * scale
        val outH = h * scale
        val accum = FloatArray(outW * outH * 3)
        val weight = FloatArray(outW * outH)
        val tiles = SuperResTilePlan.plan(w, h, 32, 8)

        val src = FloatArray(32 * 32 * 3) { 0.25f }
        tiles.forEach { tile ->
            SuperResTilePlan.accumulate(
                accum = accum, weight = weight, src = src, tile = tile, scale = scale,
                outWidth = outW, outHeight = outH, imageWidth = w, imageHeight = h, seam = 4,
            )
        }
        val normalized = SuperResTilePlan.normalize(accum, weight)
        assertEquals(outW * outH * 3, normalized.size)
        normalized.forEach { v ->
            assertTrue("常值域应被无损重建", kotlin.math.abs(v - 0.25f) < 1e-3f)
        }
    }

    @Test
    fun normalize_zeroWeightPixelsAreZeroNotNaN() {
        val accum = FloatArray(3)
        val weight = FloatArray(1) // 权重为 0
        val out = SuperResTilePlan.normalize(accum, weight)
        assertEquals(3, out.size)
        out.forEach { assertFalse("不能出现 NaN", it.isNaN()) }
        assertEquals(0f, out[0], 0f)
    }

    @Test
    fun accumulate_withScaleMapping_usesInputSpaceSeam() {
        val scale = 2
        val tile = SuperResTile(x = 0, y = 0, width = 4, height = 4, index = 0)
        val outW = 8
        val outH = 8
        val accum = FloatArray(outW * outH * 3)
        val weight = FloatArray(outW * outH)
        val src = FloatArray(8 * 8 * 3) { 1f }
        SuperResTilePlan.accumulate(
            accum = accum, weight = weight, src = src, tile = tile, scale = scale,
            outWidth = outW, outHeight = outH, imageWidth = 4, imageHeight = 4, seam = 0,
        )
        val nz = weight.count { it > 0f }
        assertEquals("scale=2 时 4x4 输入应铺满 8x8 输出", outW * outH, nz)
    }

    @Test
    fun accumulate_srcTooSmall_isSkippedWithoutThrowing() {
        val tile = SuperResTile(x = 0, y = 0, width = 16, height = 16, index = 0)
        val accum = FloatArray(16 * 16 * 3)
        val weight = FloatArray(16 * 16)
        SuperResTilePlan.accumulate(
            accum = accum, weight = weight, src = FloatArray(4), tile = tile, scale = 1,
            outWidth = 16, outHeight = 16, imageWidth = 16, imageHeight = 16, seam = 2,
        )
        assertTrue("畸形输入不得崩溃，且整块被跳过（不混入半截数据）", weight.all { it == 0f })
    }

    // ---- 混合强度 ----

    @Test
    fun blendWithStrength_endpointsAndMidpoint() {
        val ai = floatArrayOf(1f, 1f, 1f)
        val original = floatArrayOf(0f, 0f, 0f)

        assertEquals(1f, SuperResTilePlan.blendWithStrength(ai, original, 1f)[0], 1e-6f)
        assertEquals(0f, SuperResTilePlan.blendWithStrength(ai, original, 0f)[0], 1e-6f)
        assertEquals(0.5f, SuperResTilePlan.blendWithStrength(ai, original, 0.5f)[0], 1e-6f)
    }

    @Test
    fun blendWithStrength_invalidStrengthAndLengthMismatchAreSafe() {
        val ai = floatArrayOf(1f, 1f, 1f, 1f)
        val original = floatArrayOf(0f, 0f)
        // NaN 强度 → 视作 1（完全 AI），不能产出 NaN。
        val out = SuperResTilePlan.blendWithStrength(ai, original, Float.NaN)
        out.forEach { assertFalse(it.isNaN()) }
        // 越界强度同样被 clamp。
        assertEquals(1f, SuperResTilePlan.blendWithStrength(ai, original, 5f)[0], 1e-6f)
        assertEquals(ai.size, SuperResTilePlan.blendWithStrength(ai, original, 0.5f).size)
    }

    // ---- 内存估算 ----

    @Test
    fun memoryEstimates_growWithTileAndScale() {
        val small = SuperResTilePlan.estimateTileBytes(256, 2)
        val big = SuperResTilePlan.estimateTileBytes(512, 2)
        val x4 = SuperResTilePlan.estimateTileBytes(256, 4)
        assertTrue(big > small)
        assertTrue("4 倍放大的输出缓冲应显著更大", x4 > small)

        assertTrue(SuperResTilePlan.estimateTileBytes(0, 0) > 0L)
        val acc = SuperResTilePlan.estimateAccumulatorBytes(1000, 1000)
        assertEquals(1000L * 1000L * 16L, acc)
        assertEquals(0L, SuperResTilePlan.estimateAccumulatorBytes(0, 0))
    }
}
