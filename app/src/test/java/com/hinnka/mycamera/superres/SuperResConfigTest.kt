package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置开放度与 clamp 覆盖测试。
 *
 * 目标：**每一个**开放参数都验证「默认值合法 + 越界被 clamp + 极端值不崩溃」，
 * 对应 PATTERN_SPEC 第 1.6 节与第 7 节自检项「配置 clamp 覆盖全部开放参数」。
 */
class SuperResConfigTest {

    @Test
    fun defaults_areWithinRange() {
        val d = SuperResConfig()
        assertEquals(d, SuperResConfig.clamp(d))

        assertTrue(d.enabled)
        assertTrue(d.scale in SuperResConfig.SCALE_MIN..SuperResConfig.SCALE_MAX)
        assertTrue(d.tileSize in SuperResConfig.TILE_MIN..SuperResConfig.TILE_MAX)
        assertTrue(d.tileOverlap in 0..(d.tileSize / 2))
        assertEquals(1f, d.strength, 0f)
        assertTrue(d.threads in SuperResConfig.THREADS_MIN..SuperResConfig.THREADS_MAX)
        assertTrue(d.maxTilesPerChunk in SuperResConfig.TILES_PER_CHUNK_MIN..SuperResConfig.TILES_PER_CHUNK_MAX)
        assertTrue(d.memoryBudgetBytes in SuperResConfig.MEM_MIN..SuperResConfig.MEM_MAX)
        assertTrue(d.jpegQuality in SuperResConfig.JPEG_Q_MIN..SuperResConfig.JPEG_Q_MAX)
        assertEquals(2, d.scale)
        assertEquals(SuperResQualityTier.BALANCED, d.qualityTier)
    }

    // ---- 正常 ----

    @Test
    fun clamp_keepsLegalValuesUntouched() {
        val legal = SuperResConfig(
            scale = 4,
            tileSize = 512,
            tileOverlap = 64,
            strength = 0.5f,
            threads = 3,
            inpaintFeatherPx = 16,
            maxTilesPerChunk = 8,
            jpegQuality = 88,
        )
        val result = SuperResConfig.clampWithReport(legal)
        assertEquals(legal, result.config)
        assertTrue(result.corrected.isEmpty())
    }

    // ---- 边界 ----

    @Test
    fun clamp_acceptsBoundaryValues() {
        val low = SuperResConfig(
            scale = SuperResConfig.SCALE_MIN,
            tileSize = SuperResConfig.TILE_MIN,
            tileOverlap = 0,
            strength = 0f,
            threads = SuperResConfig.THREADS_MIN,
            inpaintFeatherPx = 0,
            inpaintMaskThreshold = 0f,
            maxTilesPerChunk = SuperResConfig.TILES_PER_CHUNK_MIN,
            jpegQuality = SuperResConfig.JPEG_Q_MIN,
            maxWorkingPixels = 0L,
            memoryBudgetBytes = SuperResConfig.MEM_MIN,
        )
        assertEquals(low, SuperResConfig.clamp(low))

        val high = SuperResConfig(
            scale = SuperResConfig.SCALE_MAX,
            tileSize = SuperResConfig.TILE_MAX,
            tileOverlap = SuperResConfig.TILE_MAX / 2,
            strength = 1f,
            threads = SuperResConfig.THREADS_MAX,
            inpaintFeatherPx = SuperResConfig.INPAINT_FEATHER_MAX,
            inpaintMaskThreshold = 1f,
            maxTilesPerChunk = SuperResConfig.TILES_PER_CHUNK_MAX,
            jpegQuality = SuperResConfig.JPEG_Q_MAX,
            maxWorkingPixels = SuperResConfig.MAX_WORKING_PIXELS_MAX,
            memoryBudgetBytes = SuperResConfig.MEM_MAX,
        )
        assertEquals(high, SuperResConfig.clamp(high))
    }

    @Test
    fun clamp_fixesEveryOutOfRangeField() {
        val bad = SuperResConfig(
            scale = 99,
            tileSize = 999_999,
            tileOverlap = 999_999,
            strength = 7.5f,
            threads = 64,
            styleTransferStrength = -3f,
            inpaintFeatherPx = 9999,
            inpaintMaskThreshold = 4f,
            maxWorkingPixels = Long.MAX_VALUE,
            memoryBudgetBytes = Long.MAX_VALUE,
            maxTilesPerChunk = 10_000,
            jpegQuality = 500,
        )
        val result = SuperResConfig.clampWithReport(bad)
        val c = result.config

        assertEquals(SuperResConfig.SCALE_MAX, c.scale)
        assertEquals(SuperResConfig.TILE_MAX, c.tileSize)
        assertEquals(c.tileSize / 2, c.tileOverlap)
        assertEquals(1f, c.strength, 0f)
        assertEquals(SuperResConfig.THREADS_MAX, c.threads)
        assertEquals(1f, c.styleTransferStrength, 0f)
        assertEquals(SuperResConfig.INPAINT_FEATHER_MAX, c.inpaintFeatherPx)
        assertEquals(1f, c.inpaintMaskThreshold, 0f)
        assertEquals(SuperResConfig.MAX_WORKING_PIXELS_MAX, c.maxWorkingPixels)
        assertEquals(SuperResConfig.MEM_MAX, c.memoryBudgetBytes)
        assertEquals(SuperResConfig.TILES_PER_CHUNK_MAX, c.maxTilesPerChunk)
        assertEquals(SuperResConfig.JPEG_Q_MAX, c.jpegQuality)

        // 每个被修正的字段都要在报告里出现（供日志/UI 提示）。
        listOf(
            "scale", "tileSize", "tileOverlap", "strength", "threads",
            "styleTransferStrength", "inpaintFeatherPx", "inpaintMaskThreshold",
            "maxWorkingPixels", "memoryBudgetBytes", "maxTilesPerChunk", "jpegQuality",
        ).forEach { assertTrue("应记录被修正字段: $it", it in result.corrected) }
    }

    @Test
    fun clamp_negativeValuesFallToMinimum() {
        val c = SuperResConfig(
            scale = -5,
            tileSize = -100,
            tileOverlap = -1,
            strength = -2f,
            threads = 0,
            inpaintFeatherPx = -9,
            inpaintMaskThreshold = -1f,
            maxWorkingPixels = -100L,
            memoryBudgetBytes = -5L,
            maxTilesPerChunk = 0,
            jpegQuality = -10,
            styleTransferStrength = Float.NEGATIVE_INFINITY,
        )
        // ±Infinity 会被 coerceIn 拉到区间边界（0f）；NaN 才回落字段默认值（见下一个用例）。
        val result = SuperResConfig.clampWithReport(c).config
        assertEquals(SuperResConfig.SCALE_MIN, result.scale)
        assertEquals(SuperResConfig.TILE_MIN, result.tileSize)
        assertEquals(0, result.tileOverlap)
        assertEquals(0f, result.strength, 0f)
        assertEquals(SuperResConfig.THREADS_MIN, result.threads)
        assertEquals(0, result.inpaintFeatherPx)
        assertEquals(0f, result.inpaintMaskThreshold, 0f)
        assertEquals(0L, result.maxWorkingPixels)
        assertEquals(SuperResConfig.MEM_MIN, result.memoryBudgetBytes)
        assertEquals(SuperResConfig.TILES_PER_CHUNK_MIN, result.maxTilesPerChunk)
        assertEquals(SuperResConfig.JPEG_Q_MIN, result.jpegQuality)
        assertEquals(0f, result.styleTransferStrength, 0f)
    }

    // ---- 异常 ----

    @Test
    fun clamp_nanFallsBackToDefaultAndDoesNotProduceNaN() {
        val c = SuperResConfig(
            strength = Float.NaN,
            styleTransferStrength = Float.NaN,
            inpaintMaskThreshold = Float.NaN,
        )
        val result = SuperResConfig.clampWithReport(c)
        assertEquals(1f, result.config.strength, 0f)
        assertEquals(0.8f, result.config.styleTransferStrength, 0f)
        assertEquals(0.5f, result.config.inpaintMaskThreshold, 0f)
        assertTrue(result.corrected.containsAll(listOf("strength", "styleTransferStrength", "inpaintMaskThreshold")))
    }

    @Test
    fun clamp_blankStyleModelIdBecomesNull() {
        val c = SuperResConfig(styleModelId = "   ")
        assertNull(SuperResConfig.clamp(c).styleModelId)
        assertEquals(
            "style_candy",
            SuperResConfig.clamp(SuperResConfig(styleModelId = "style_candy")).styleModelId,
        )
    }

    @Test
    fun degradeForMemoryPressure_shrinksTileAndWorkingPixelsInsideRange() {
        val c = SuperResConfig(tileSize = 512, tileOverlap = 200, maxWorkingPixels = 12_000_000L, maxTilesPerChunk = 8)
        val d = SuperResConfig.degradeForMemoryPressure(c)

        assertTrue("tile 必须变小", d.tileSize < c.tileSize)
        assertTrue(d.tileSize >= SuperResConfig.TILE_MIN)
        assertTrue("重叠不得超过新 tile 的一半", d.tileOverlap <= d.tileSize / 2)
        assertTrue("工作分辨率上限必须下调", d.maxWorkingPixels < c.maxWorkingPixels)
        assertTrue(d.maxTilesPerChunk < c.maxTilesPerChunk)
        assertEquals("结果必须仍在合法区间", d, SuperResConfig.clamp(d))
    }

    @Test
    fun degradeForMemoryPressure_isIdempotentAtFloorAndNeverIllegal() {
        var c = SuperResConfig(tileSize = SuperResConfig.TILE_MIN, maxTilesPerChunk = 1, maxWorkingPixels = 1L)
        repeat(10) { c = SuperResConfig.degradeForMemoryPressure(c) }
        assertEquals(SuperResConfig.TILE_MIN, c.tileSize)
        assertEquals(SuperResConfig.TILES_PER_CHUNK_MIN, c.maxTilesPerChunk)
        assertTrue(c.maxWorkingPixels >= 1L)
        assertEquals(c, SuperResConfig.clamp(c))
    }

    @Test
    fun tierHelpers_stayInRange() {
        SuperResQualityTier.entries.forEach { tier ->
            val threads = SuperResConfig.defaultThreadsFor(tier)
            val tile = SuperResConfig.recommendedTileSizeFor(tier)
            assertTrue(threads in SuperResConfig.THREADS_MIN..SuperResConfig.THREADS_MAX)
            assertTrue(tile in SuperResConfig.TILE_MIN..SuperResConfig.TILE_MAX)
        }
    }

    @Test
    fun effectiveSeamWidth_isHalfOfOverlap() {
        assertEquals(0, SuperResConfig(tileOverlap = 0).effectiveSeamWidthPx)
        assertEquals(16, SuperResConfig(tileOverlap = 32).effectiveSeamWidthPx)
        assertNotNull(SuperResConfig.clamp(SuperResConfig(tileOverlap = 31)))
        assertFalse(SuperResConfig().effectiveSeamWidthPx < 0)
    }
}
