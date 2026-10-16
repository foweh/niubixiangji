package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置开放度与 clamp 契约：**每一个开放参数**都要有默认值、范围与越界保护，
 * 且任何非法输入（含 NaN/Infinity/极大值）都不得抛异常。
 */
class MfsrConfigClampTest {

    @Test
    fun defaultsAreSelfConsistentAndUntouchedByClamp() {
        val d = MfsrConfig()
        val (c, fixed) = d.coerceWithReport()
        assertEquals("默认配置不应被修正", 0, fixed.size)
        assertEquals(d, c)
        assertEquals(d, d.coerced())
        assertEquals(8, d.frameCount)
        assertEquals(4, d.ibpIterations)
        assertEquals(2f, d.scale, 0f)
        assertEquals(MfsrWorkingResolution.FULL, d.workingResolution)
        assertTrue(d.alignmentActive)
    }

    @Test
    fun frameCountIsOpenUpToThousandAndClampedOutside() {
        val c = MfsrConfig()
        assertEquals(2, c.copy(frameCount = -99).coerced().frameCount)
        assertEquals(2, c.copy(frameCount = 0).coerced().frameCount)
        assertEquals(2, c.copy(frameCount = 1).coerced().frameCount)
        assertEquals(2, c.copy(frameCount = 2).coerced().frameCount)
        assertEquals(8, c.copy(frameCount = 8).coerced().frameCount)
        assertEquals(20, c.copy(frameCount = 20).coerced().frameCount)
        assertEquals(100, c.copy(frameCount = 100).coerced().frameCount)
        // 1000 是一致性上界，不是"能力上界"：它就是合法输入。
        assertEquals(1000, c.copy(frameCount = 1000).coerced().frameCount)
        assertEquals(1000, c.copy(frameCount = 1001).coerced().frameCount)
        assertEquals(1000, c.copy(frameCount = Int.MAX_VALUE).coerced().frameCount)
    }

    @Test
    fun scaleIsClampedIntoSupportedRange() {
        val c = MfsrConfig()
        assertEquals(1.25f, c.copy(scale = 0.1f).coerced().scale, 0f)
        assertEquals(1.25f, c.copy(scale = -3f).coerced().scale, 0f)
        assertEquals(1.25f, c.copy(scale = MfsrConfig.SCALE_MIN).coerced().scale, 0f)
        assertEquals(2f, c.copy(scale = 2f).coerced().scale, 0f)
        assertEquals(3f, c.copy(scale = 3f).coerced().scale, 0f)
        assertEquals(3f, c.copy(scale = 99f).coerced().scale, 0f)
        assertEquals(2f, c.copy(scale = Float.NaN).coerced().scale, 0f)
        assertEquals(2f, c.copy(scale = Float.POSITIVE_INFINITY).coerced().scale, 0f)
    }

    @Test
    fun ibpIterationsClampToThreeThroughEight() {
        val c = MfsrConfig()
        assertEquals(3, c.copy(ibpIterations = 0).coerced().ibpIterations)
        assertEquals(3, c.copy(ibpIterations = 2).coerced().ibpIterations)
        assertEquals(3, c.copy(ibpIterations = -5).coerced().ibpIterations)
        assertEquals(4, c.copy(ibpIterations = 4).coerced().ibpIterations)
        assertEquals(8, c.copy(ibpIterations = 8).coerced().ibpIterations)
        assertEquals(8, c.copy(ibpIterations = 9).coerced().ibpIterations)
        assertEquals(8, c.copy(ibpIterations = 500).coerced().ibpIterations)
    }

    @Test
    fun remainingOpenParametersAreClamped() {
        val c = MfsrConfig()
        assertEquals(1, c.copy(subpixelRefineIterations = 0).coerced().subpixelRefineIterations)
        assertEquals(32, c.copy(subpixelRefineIterations = 999).coerced().subpixelRefineIterations)

        assertEquals(0f, c.copy(minAlignConfidence = -1f).coerced().minAlignConfidence, 0f)
        assertEquals(1f, c.copy(minAlignConfidence = 5f).coerced().minAlignConfidence, 0f)
        assertEquals(0.15f, c.copy(minAlignConfidence = Float.NaN).coerced().minAlignConfidence, 0f)

        assertEquals(1, c.copy(maxAlignShiftPx = 0).coerced().maxAlignShiftPx)
        assertEquals(256, c.copy(maxAlignShiftPx = 100000).coerced().maxAlignShiftPx)

        assertEquals(0f, c.copy(strength = -2f).coerced().strength, 0f)
        assertEquals(2f, c.copy(strength = 9f).coerced().strength, 0f)
        assertEquals(1f, c.copy(strength = Float.NaN).coerced().strength, 0f)

        assertEquals(1, c.copy(denoiseRadius = 0).coerced().denoiseRadius)
        assertEquals(4, c.copy(denoiseRadius = 77).coerced().denoiseRadius)

        assertEquals(0f, c.copy(sharpenAmount = -1f).coerced().sharpenAmount, 0f)
        assertEquals(1f, c.copy(sharpenAmount = 3f).coerced().sharpenAmount, 0f)
        assertEquals(0.4f, c.copy(sharpenSigma = 0f).coerced().sharpenSigma, 0f)
        assertEquals(4f, c.copy(sharpenSigma = 50f).coerced().sharpenSigma, 0f)

        assertEquals(0, c.copy(ibpFrameStride = -4).coerced().ibpFrameStride)
        assertEquals(64, c.copy(ibpFrameStride = 100000).coerced().ibpFrameStride)

        assertEquals(MfsrConfig.MEMORY_BUDGET_MIN_BYTES, c.copy(memoryBudgetBytes = 1L).coerced().memoryBudgetBytes)
        assertEquals(
            MfsrConfig.MEMORY_BUDGET_MAX_BYTES,
            c.copy(memoryBudgetBytes = Long.MAX_VALUE).coerced().memoryBudgetBytes,
        )

        assertEquals(512, c.copy(inputMaxSide = 1).coerced().inputMaxSide)
        assertEquals(4096, c.copy(inputMaxSide = 1_000_000).coerced().inputMaxSide)

        assertEquals(60, c.copy(jpegQuality = 0).coerced().jpegQuality)
        assertEquals(100, c.copy(jpegQuality = 5000).coerced().jpegQuality)
    }

    @Test
    fun clampReportsWhichFieldsWereCorrected() {
        val (_, fixed) = MfsrConfig(frameCount = 5, ibpIterations = 99, scale = Float.NaN).coerceWithReport()
        assertTrue(fixed.contains("ibpIterations"))
        assertTrue(fixed.contains("scale"))
        assertFalse("合法帧数不应被标记", fixed.contains("frameCount"))
    }

    @Test
    fun effectiveIbpStrideIsAutoForLargeFrameCountsAndBounded() {
        val c = MfsrConfig()
        assertEquals(1, c.effectiveIbpStride(8))
        assertEquals(1, c.effectiveIbpStride(96))
        assertEquals(2, c.effectiveIbpStride(192))
        assertEquals(10, c.effectiveIbpStride(1000))
        // 显式步长优先且被 clamp
        assertEquals(64, c.copy(ibpFrameStride = 500).effectiveIbpStride(1000))
        assertEquals(4, c.copy(ibpFrameStride = 4).effectiveIbpStride(1000))
    }

    @Test
    fun alignmentActiveFollowsSwitchAndMethod() {
        assertTrue(MfsrConfig().alignmentActive)
        assertFalse(MfsrConfig(alignmentEnabled = false).alignmentActive)
        assertFalse(MfsrConfig(alignMethod = MfsrAlignMethod.OFF).alignmentActive)
        assertTrue(MfsrConfig(alignMethod = MfsrAlignMethod.PHASE_CORRELATION).alignmentActive)
    }

    @Test
    fun codecRoundTripsAndSurvivesGarbage() {
        val c = MfsrConfig(
            frameCount = 137,
            scale = 2.5f,
            ibpIterations = 7,
            alignmentEnabled = false,
            alignMethod = MfsrAlignMethod.PHASE_CORRELATION,
            workingResolution = MfsrWorkingResolution.THREE_QUARTER,
            strength = 1.5f,
            denoiseOn = true,
            inputMaxSide = 1536,
            jpegQuality = 88,
        )
        val encoded = MfsrConfigCodec.encode(c)
        assertEquals(c, MfsrConfigCodec.decode(encoded))
        assertTrue(encoded.containsKey(MfsrConfigCodec.KEY_FRAME_COUNT))

        // 空表 → 默认
        assertEquals(MfsrConfig(), MfsrConfigCodec.decode(emptyMap()))
        // 脏数据 → 回落默认 + clamp，不抛异常
        val junk = mapOf(
            MfsrConfigCodec.KEY_FRAME_COUNT to "abc",
            MfsrConfigCodec.KEY_SCALE to "-1",
            MfsrConfigCodec.KEY_IBP_ITERATIONS to "9999",
            MfsrConfigCodec.KEY_ALIGN_METHOD to "NOT_A_METHOD",
            MfsrConfigCodec.KEY_WORKING_RESOLUTION to "HALF_TIER",
            "mfsr_unknown_key_from_future_version" to "whatever",
        )
        val decoded = MfsrConfigCodec.decode(junk)
        assertEquals(8, decoded.frameCount)
        assertEquals(1.25f, decoded.scale, 0f)
        assertEquals(8, decoded.ibpIterations)
        assertEquals(MfsrAlignMethod.BLOCK_MATCH, decoded.alignMethod)
        assertEquals(MfsrWorkingResolution.FULL, decoded.workingResolution)
        assertNotNull(MfsrConfigCodec.summary(decoded))
    }

    @Test
    fun uiLadderReachesHundredsOfFramesWithoutHiddenCap() {
        var n = MfsrConfig.DEFAULT_FRAME_COUNT
        val seen = LinkedHashSet<Int>()
        repeat(MfsrUiLadder.FRAME_STEPS.size) {
            seen += n
            n = MfsrUiLadder.stepFrameCount(n)
        }
        assertTrue("UI 必须能调到上百帧", seen.any { it >= 128 })
        assertTrue("帧数阶梯必须覆盖 2 ~ 1000", seen.contains(1000) && MfsrUiLadder.FRAME_STEPS.contains(2))
        assertEquals(2, MfsrUiLadder.FRAME_STEPS.first())
        assertEquals(1000, MfsrUiLadder.FRAME_STEPS.last())
        assertEquals(1000, MfsrUiLadder.stepFrameCount(768))
        // 到顶停在 1000（刻意不回卷，避免误触掉回 2 帧）
        assertEquals(1000, MfsrUiLadder.stepFrameCount(1000))
        // 越界值先 clamp 到 2，再跳到第一个更大档位
        assertEquals(3, MfsrUiLadder.stepFrameCount(0))
        assertEquals(3, MfsrUiLadder.stepFrameCount(2))

        assertEquals(1.5f, MfsrUiLadder.stepScale(1.25f), 0f)
        assertEquals(1.25f, MfsrUiLadder.stepScale(3f), 0f)
        // NaN 先回落到默认倍率 2.0，再步进到 2.5
        assertEquals(2.5f, MfsrUiLadder.stepScale(Float.NaN), 0f)

        // 迭代档位是循环的：到顶回到 3，可来回调
        assertEquals(5, MfsrUiLadder.stepIbpIterations(4))
        assertEquals(3, MfsrUiLadder.stepIbpIterations(8))
        assertEquals(3, MfsrUiLadder.stepIbpIterations(99))
        assertEquals(MfsrConfig.IBP_MIN, MfsrUiLadder.stepIbpIterations(-7))

        assertEquals(MfsrWorkingResolution.THREE_QUARTER, MfsrUiLadder.stepWorkingResolution(MfsrWorkingResolution.FULL))
        assertEquals(MfsrWorkingResolution.HALF, MfsrUiLadder.stepWorkingResolution(MfsrWorkingResolution.THREE_QUARTER))
        assertEquals(MfsrWorkingResolution.FULL, MfsrUiLadder.stepWorkingResolution(MfsrWorkingResolution.HALF))

        assertEquals(MfsrAlignMethod.PHASE_CORRELATION, MfsrUiLadder.stepAlignMethod(MfsrAlignMethod.BLOCK_MATCH))
        assertEquals(MfsrAlignMethod.OFF, MfsrUiLadder.stepAlignMethod(MfsrAlignMethod.PHASE_CORRELATION))
        assertEquals(MfsrAlignMethod.BLOCK_MATCH, MfsrUiLadder.stepAlignMethod(MfsrAlignMethod.OFF))

        assertEquals(1.5f, MfsrUiLadder.stepStrength(1f), 0f)
        assertEquals(0f, MfsrUiLadder.stepStrength(2f), 0f)
    }
}
