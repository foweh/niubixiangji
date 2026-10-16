package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.enhance.EnhanceTestSupport.identical
import com.hinnka.mycamera.enhance.EnhanceTestSupport.noisy
import com.hinnka.mycamera.enhance.EnhanceTestSupport.ramp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管线编排单测：阶段顺序与回调、恒等快返、工作分辨率降级决策、配置 clamp 后不崩。
 */
class EnhancePipelineTest {

    private fun allStageConfig(
        bandDenoise: BilateralConfig = BilateralConfig(radius = 2, strength = 0.5f),
    ): EnhanceConfig = EnhanceConfig(
        denoise = bandDenoise,
        dehaze = DehazeConfig(
            enabled = true,
            atmosphereMode = AtmosphereMode.FIXED,
            fixedAtmosphere = 0.9f,
            strength = 0.3f,
        ),
        clahe = ClaheConfig(tilesX = 4, tilesY = 4),
        sharpen = UnsharpMaskConfig(radius = 1f, amount = 0.4f),
        funFilter = FunFilterConfig(kind = FunFilterKind.SKETCH),
    )

    // ------------------------------------------------------- 编排

    @Test
    fun pipelineAppliesEveryStageInConfiguredOrder() {
        val config = allStageConfig()
        val observed = mutableListOf<EnhanceStage>()
        EnhancePipeline.process(
            pixels = noisy(24, 24, 128, 30, seed = 13L),
            width = 24,
            height = 24,
            config = config,
            onStage = { stage, _ -> observed += stage },
        )
        // 五个阶段都启用 → 回调顺序 = 规范顺序，末尾追加一次「全部完成」
        val expected = config.stages()
        assertEquals(EnhanceStage.DENOISE, expected.first())
        assertEquals(EnhanceStage.FUN_FILTER, expected.last())
        assertEquals(expected + expected.last(), observed)
    }

    @Test
    fun disabledPipelineReturnsInputCopy() {
        var chunks = 0
        val pixels = ramp(12, 12, 20, 230)
        val out = EnhancePipeline.process(
            pixels = pixels,
            width = 12,
            height = 12,
            config = EnhanceConfig(
                clahe = ClaheConfig(enabled = false),
                dehaze = DehazeConfig(enabled = false),
                sharpen = UnsharpMaskConfig(enabled = false),
                denoise = BilateralConfig(enabled = false),
                funFilter = FunFilterConfig(enabled = false),
            ),
            onChunk = { chunks++ },
        )
        assertTrue("empty pipeline must be identity", identical(pixels, out))
        assertTrue("pixels must not be aliased", out !== pixels)
        assertEquals(1, chunks)
    }

    @Test
    fun chunkCallbackFiresForBandedAndWholeImageStages() {
        // 仅降噪：bandHeight=6 / height=24 → 4 个行带分片
        var chunks = 0
        EnhancePipeline.process(
            pixels = noisy(16, 24, 128, 20),
            width = 16,
            height = 24,
            config = EnhanceConfig(
                denoise = BilateralConfig(radius = 2, strength = 0.8f),
                clahe = ClaheConfig(enabled = false),
                dehaze = DehazeConfig(enabled = false),
                sharpen = UnsharpMaskConfig(enabled = false),
                funFilter = FunFilterConfig(enabled = false),
            ),
            bandHeight = 6,
            onChunk = { chunks++ },
        )
        assertEquals(4, chunks)
    }

    @Test
    fun wholeImageStagesAlsoYieldOnce() {
        // 仅 CLAHE（算法需全局）→ 恰好一个分片，保证长任务仍能 checkYield
        var chunks = 0
        EnhancePipeline.process(
            pixels = ramp(16, 16, 10, 240),
            width = 16,
            height = 16,
            config = EnhanceConfig(
                clahe = ClaheConfig(tilesX = 8, tilesY = 8),
                denoise = BilateralConfig(enabled = false),
                dehaze = DehazeConfig(enabled = false),
                sharpen = UnsharpMaskConfig(enabled = false),
                funFilter = FunFilterConfig(enabled = false),
            ),
            onChunk = { chunks++ },
        )
        assertEquals(1, chunks)
    }

    @Test
    fun oilPaintStageYieldsPerBand() {
        var chunks = 0
        EnhancePipeline.process(
            pixels = noisy(12, 12, 120, 30, seed = 2L),
            width = 12,
            height = 12,
            config = EnhanceConfig(
                denoise = BilateralConfig(enabled = false),
                clahe = ClaheConfig(enabled = false),
                dehaze = DehazeConfig(enabled = false),
                sharpen = UnsharpMaskConfig(enabled = false),
                funFilter = FunFilterConfig(
                    kind = FunFilterKind.OIL_PAINT,
                    oilPaint = OilPaintConfig(levels = 6, bilateralRadius = 2, paintMix = 1f),
                ),
            ),
            bandHeight = 4,
            onChunk = { chunks++ },
        )
        // 双边 3 个行带 + 量化 1 + 边缘/混合 3 个行带 = 7
        assertEquals(7, chunks)
    }

    @Test
    fun processRejectsTooSmallBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            EnhancePipeline.process(IntArray(10), 8, 8, EnhanceConfig())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnhancePipeline.process(IntArray(16), 0, 4, EnhanceConfig())
        }
    }

    @Test
    fun processClampsConfigBeforeRunning() {
        val pixels = noisy(20, 20, 128, 30)
        val out = EnhancePipeline.process(
            pixels, 20, 20,
            EnhanceConfig(
                workingScale = 77f,
                clahe = ClaheConfig(tilesX = 9999, clipLimit = Float.NaN),
                dehaze = DehazeConfig(enabled = true, strength = 9f, atmosphereMode = AtmosphereMode.PERCENTILE),
                sharpen = UnsharpMaskConfig(radius = Float.POSITIVE_INFINITY, amount = 1f),
                denoise = BilateralConfig(radius = -1, strength = 5f),
                funFilter = FunFilterConfig(kind = FunFilterKind.TILT_SHIFT, tiltShift = TiltShiftConfig(centerY = 5f)),
            ),
        )
        assertEquals(pixels.size, out.size)
    }

    // ------------------------------------------------------- 内存预算

    @Test
    fun estimateBytesGrowsWithScale() {
        val full = EnhancePipeline.estimateBytes(4000, 3000, 1f)
        val half = EnhancePipeline.estimateBytes(4000, 3000, 0.5f)
        val quarter = EnhancePipeline.estimateBytes(4000, 3000, 0.25f)
        assertTrue(full > half)
        assertTrue(half > quarter)
        // 12MP × 8 平面 × 4 字节 = 384MB
        assertEquals(384_000_000L, full)
        assertTrue(EnhancePipeline.estimateBytes(0, 0, 1f) > 0L)
    }

    @Test
    fun resolveWorkingScaleHonorsRequestWhenBudgetIsEnough() {
        assertEquals(
            0.5f,
            EnhancePipeline.resolveWorkingScale(4000, 3000, 0.5f, memoryBudgetBytes = 4L shl 30),
            1e-6f,
        )
        // 预算非正 → 视为无限制
        assertEquals(
            1f,
            EnhancePipeline.resolveWorkingScale(4000, 3000, 1f, memoryBudgetBytes = -1L),
            1e-6f,
        )
    }

    @Test
    fun resolveWorkingScaleStepsDownForTightBudget() {
        // 12MP@1.0 需 384MB：给 200MB 时应降到 0.75 以下且大于 0
        val scale = EnhancePipeline.resolveWorkingScale(4000, 3000, 1f, memoryBudgetBytes = 200L * 1024 * 1024)
        assertTrue("scale=$scale", scale < 1f && scale >= EnhancePipeline.MIN_WORKING_SCALE)
        assertTrue(EnhancePipeline.estimateBytes(4000, 3000, scale) <= 200L * 1024 * 1024)
    }

    @Test
    fun resolveWorkingScaleNeverGoesBelowMinimum() {
        val scale = EnhancePipeline.resolveWorkingScale(8000, 6000, 1f, memoryBudgetBytes = 1L)
        assertEquals(EnhancePipeline.MIN_WORKING_SCALE, scale, 1e-6f)
        val alsoTiny = EnhancePipeline.resolveWorkingScale(4000, 3000, Float.NaN, memoryBudgetBytes = 64L)
        assertEquals(EnhancePipeline.MIN_WORKING_SCALE, alsoTiny, 1e-6f)
    }
}
