package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * 迭代反投影（IBP）的收敛性与迭代边界。
 *
 * 判定依据全部来自公开 API（[MfsrResult.ibpResiduals] 长度 = 实际执行轮数，
 * 数值 = 每轮平均绝对残差），因此测试不依赖任何内部实现细节。
 */
class MfsrIbpConvergenceTest {

    /** HR 真值 + 由它按理想成像模型合成的若干 LR 帧。 */
    private fun makeScene(
        hrW: Int,
        hrH: Int,
        scale: Float,
        n: Int,
        noise: Float,
    ): Pair<MfsrPlane, List<MfsrPlane>> {
        val gt = MfsrTestFixtures.pattern(hrW, hrH)
        val rnd = Random(7)
        val frames = ArrayList<MfsrPlane>(n)
        frames += MfsrTestFixtures.synthLr(gt, 0f, 0f, scale)
        for (i in 1 until n) {
            val dx = (rnd.nextDouble() * 5.0 - 2.5).toFloat()
            val dy = (rnd.nextDouble() * 5.0 - 2.5).toFloat()
            frames += MfsrTestFixtures.synthLr(gt, dx, dy, scale)
        }
        if (noise > 0f) {
            for (i in frames.indices) frames[i] = MfsrTestFixtures.addNoise(frames[i], noise, 100L + i)
        }
        return gt to frames
    }

    private fun runSession(frames: List<MfsrPlane>, config: MfsrConfig, guard: (Long) -> Boolean = { true }): MfsrResult {
        val session = MfsrSession(rawConfig = config, guard = guard)
        frames.forEach { session.ingest(it) }
        return session.finish()
    }

    private fun cleanConfig(
        scale: Float = 2f,
        iterations: Int = 4,
        maxShift: Int = 8,
    ) = MfsrConfig(
        scale = scale,
        ibpIterations = iterations,
        maxAlignShiftPx = maxShift,
        sharpenAmount = 0f, // 关掉反锐化，隔离 IBP 本身的收敛行为
    )

    @Test
    fun residualsDecreaseMonotonicallyAndLastIsLowest() {
        val (gt, frames) = makeScene(96, 72, 2f, 8, 0f)
        val result = runSession(frames, cleanConfig())

        assertEquals("4 轮迭代应产出 4 个残差", 4, result.ibpResiduals.size)
        assertTrue("残差必须有限且为正", result.ibpResiduals.all { it.isFinite() && it > 0f })
        assertTrue(
            "残差应严格下降: ${result.ibpResiduals}",
            result.ibpResiduals.last() < result.ibpResiduals.first(),
        )
        assertTrue(
            "后续轮次不得高于首轮: ${result.ibpResiduals}",
            result.ibpResiduals.all { it <= result.ibpResiduals.first() + 1e-3f },
        )

        // 分辨率增益：IBP 结果必须明显优于"单帧升采样"
        val single = runSession(listOf(frames[0]), cleanConfig())
        val psnrIbp = MfsrTestFixtures.psnr(MfsrPlane(result.width, result.height, result.luma), gt)
        val psnrSingle = MfsrTestFixtures.psnr(MfsrPlane(single.width, single.height, single.luma), gt)
        assertTrue(
            "IBP 应显著优于单帧升采样: ibp=$psnrIbp single=$psnrSingle",
            psnrIbp > psnrSingle + 2.0,
        )
    }

    @Test
    fun residualsStillConvergeWithNoisyFrames() {
        val (_, frames) = makeScene(96, 72, 2f, 8, 1.5f)
        val result = runSession(frames, cleanConfig())
        assertEquals(4, result.ibpResiduals.size)
        assertTrue(result.ibpResiduals.last() < result.ibpResiduals.first())
        // 对齐在噪声下仍须守住 0.1px
        assertTrue(result.shifts.all { it.isFiniteShift })
    }

    @Test
    fun iterationBoundsAreClampedIntoThreeThroughEight() {
        val (_, frames) = makeScene(48, 36, 2f, 4, 0f)
        assertEquals(3, runSession(frames, cleanConfig(iterations = 0)).ibpResiduals.size)
        assertEquals(3, runSession(frames, cleanConfig(iterations = 1)).ibpResiduals.size)
        assertEquals(3, runSession(frames, cleanConfig(iterations = 2)).ibpResiduals.size)
        assertEquals(3, runSession(frames, cleanConfig(iterations = 3)).ibpResiduals.size)
        assertEquals(4, runSession(frames, cleanConfig(iterations = 4)).ibpResiduals.size)
        assertEquals(8, runSession(frames, cleanConfig(iterations = 8)).ibpResiduals.size)
        assertEquals(8, runSession(frames, cleanConfig(iterations = 99)).ibpResiduals.size)
    }

    @Test
    fun moreIterationsNeverWorsenTheResidual() {
        val (_, frames) = makeScene(64, 48, 2f, 8, 0f)
        val four = runSession(frames, cleanConfig(iterations = 4))
        val eight = runSession(frames, cleanConfig(iterations = 8))
        assertEquals(4, four.ibpResiduals.size)
        assertEquals(8, eight.ibpResiduals.size)
        assertTrue(
            "8 轮终值应不高于 4 轮终值: ${eight.ibpResiduals.last()} vs ${four.ibpResiduals.last()}",
            eight.ibpResiduals.last() <= four.ibpResiduals.last() + 1e-3f,
        )
    }

    @Test
    fun memoryGuardFailureSkipsIbpAndDegradesInsteadOfFailing() {
        val (_, frames) = makeScene(48, 36, 2f, 4, 0f)
        val result = runSession(frames, cleanConfig(), guard = { false })

        assertTrue("内存守卫不过时应跳过 IBP", result.ibpSkippedForMemory)
        assertEquals("跳过后不应有残差记录", 0, result.ibpResiduals.size)
        assertTrue("应标记为降级", result.degraded)
        assertEquals("应降到最低工作分辨率档位", MfsrWorkingResolution.HALF, result.workingResolution)
        // makeScene 的 HR 真值是 48x36 → 送入的 LR 帧是 24x18；降级后工作尺寸再减半
        val expectedW = MfsrMemoryPolicy.hrSize(
            MfsrMemoryPolicy.workSize(frames[0].width, MfsrWorkingResolution.HALF),
            2f,
        )
        assertEquals(24, expectedW)
        assertEquals(expectedW, result.width)
        assertTrue("仍然必须产出可用结果", result.luma.all { it.isFinite() })
        assertEquals(4, result.framesUsed)
    }

    @Test
    fun finishingWithoutFramesIsAnExplicitErrorNotASilentEmptyResult() {
        val session = MfsrSession(rawConfig = MfsrConfig())
        assertThrows(IllegalStateException::class.java) { session.finish() }
    }

    @Test
    fun singleFrameInputStillProducesUpsampledResultWithoutIbpCrash() {
        val (_, frames) = makeScene(48, 36, 2f, 1, 0f)
        val result = runSession(frames, cleanConfig())
        assertEquals(1, result.framesUsed)
        assertEquals(1, result.shifts.size)
        assertEquals(0f, result.shifts[0].dx, 0f)
        // 送进来的是 24x18 的 LR 帧，scale=2 → 输出 48x36
        assertEquals(48, result.width)
        assertEquals(36, result.height)
    }

    @Test
    fun alignmentIsStableAcrossScaleVariants() {
        for (scale in listOf(1.25f, 2f, 3f)) {
            val (gt, frames) = makeScene(Math.round(64 * scale), Math.round(48 * scale), scale, 6, 0f)
            val result = runSession(frames, cleanConfig(scale = scale, iterations = 4))
            assertTrue(
                "scale=$scale 输出尺寸应为工作尺寸×倍率",
                result.width >= Math.round(64 * scale) - 1 && result.width <= Math.round(64 * scale) + 1,
            )
            assertTrue(result.ibpResiduals.isNotEmpty())
            val psnr = MfsrTestFixtures.psnr(MfsrPlane(result.width, result.height, result.luma), gt)
            assertTrue("scale=$scale PSNR 应大于 20dB，实际 $psnr", psnr > 20.0)
        }
    }

    @Test
    fun shiftEstimationInsideSessionMeetsTheSubPixelContract() {
        val gt = MfsrTestFixtures.pattern(96, 72)
        val truth = listOf(0f to 0f, 1.7f to -2.3f, -0.6f to 0.9f, 0.25f to 0.35f)
        val frames = truth.map { (dx, dy) -> MfsrTestFixtures.synthLr(gt, dx, dy, 2f) }
        val result = runSession(frames, cleanConfig(iterations = 4, maxShift = 8))
        for (i in truth.indices) {
            val (dx, dy) = truth[i]
            val err = hypot(result.shifts[i].dx - dx, result.shifts[i].dy - dy)
            assertTrue("第 $i 帧对齐误差 $err 超过 0.1px", err <= MfsrSubpixelAligner.TARGET_ACCURACY_PX)
        }
    }
}
