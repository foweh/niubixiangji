package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * 端到端流水线 + 基础数值组件的契约测试：
 * 尺寸/帧数/报告、强度开关、色彩重组、平面采样与亮度换算。
 */
class MfsrCorePipelineTest {

    private fun scene(hrW: Int, hrH: Int, scale: Float, n: Int): Pair<MfsrPlane, List<MfsrPlane>> {
        val gt = MfsrTestFixtures.pattern(hrW, hrH)
        val rnd = Random(5)
        val frames = ArrayList<MfsrPlane>(n)
        frames += MfsrTestFixtures.synthLr(gt, 0f, 0f, scale)
        for (i in 1 until n) {
            frames += MfsrTestFixtures.synthLr(
                gt,
                (rnd.nextDouble() * 4 - 2).toFloat(),
                (rnd.nextDouble() * 4 - 2).toFloat(),
                scale,
            )
        }
        return gt to frames
    }

    private fun fuse(frames: List<MfsrPlane>, config: MfsrConfig): MfsrResult {
        val session = MfsrSession(rawConfig = config)
        frames.forEach { session.ingest(it) }
        return session.finish()
    }

    @Test
    fun endToEndFusionProducesHighResolutionResult() {
        val (gt, frames) = scene(64, 48, 2f, 8)
        val result = fuse(
            frames,
            MfsrConfig(scale = 2f, ibpIterations = 4, maxAlignShiftPx = 8, sharpenAmount = 0f),
        )
        assertEquals(8, result.framesUsed)
        assertEquals(8, result.shifts.size)
        // 输入 LR 为 32x24（scale2 由 scene 生成），工作分辨率 FULL → HR = 64x48
        assertEquals(64, result.width)
        assertEquals(48, result.height)
        assertEquals(64 * 48, result.luma.size)
        assertEquals(MfsrShift.ZERO, result.shifts[0])
        assertEquals(MfsrWorkingResolution.FULL, result.workingResolution)
        assertFalse(result.degraded)
        assertTrue(result.ibpResiduals.isNotEmpty())
        assertTrue(result.luma.all { it.isFinite() && it >= 0f && it <= 255f })
        assertEquals(96 * result.width + 5, result.index(5, 96))
        assertEquals(5, result.index(5, 0))
        assertEquals(result.width * result.height, result.luma.size)

        val psnr = MfsrTestFixtures.psnr(MfsrPlane(result.width, result.height, result.luma), gt)
        assertTrue("融合结果 PSNR 应显著优于单帧，实际 $psnr", psnr > 25.0)
    }

    @Test
    fun strengthZeroDisablesRefinementWhileStrengthOneAppliesIt() {
        val (_, frames) = scene(48, 36, 2f, 6)
        val weak = fuse(frames, MfsrConfig(scale = 2f, ibpIterations = 4, strength = 0f, sharpenAmount = 0.8f))
        val strong = fuse(frames, MfsrConfig(scale = 2f, ibpIterations = 4, strength = 1f, sharpenAmount = 0.8f))
        assertEquals(weak.width, strong.width)
        assertNotEquals("强度 0 与 1 的结果不应完全相同", weak.luma.toList(), strong.luma.toList())
        assertTrue(weak.luma.all { it in 0f..255f })
        assertTrue(strong.luma.all { it in 0f..255f })
    }

    @Test
    fun denoiseAndHeavySharpenKeepOutputInRange() {
        val (_, frames) = scene(48, 36, 2f, 4)
        val result = fuse(
            frames,
            MfsrConfig(
                scale = 2f,
                ibpIterations = 4,
                strength = 2f,
                sharpenAmount = 1f,
                sharpenSigma = 4f,
                denoiseOn = true,
                denoiseRadius = 4,
            ),
        )
        assertTrue("锐化/降噪后必须仍在 8bit 有效区间", result.luma.all { it >= 0f && it <= 255f })
        assertTrue(result.luma.all { it.isFinite() })
    }

    @Test
    fun allScaleVariantsProduceTheExpectedGridSize() {
        val frames = List(4) { MfsrTestFixtures.pattern(24, 18) }
        for (scale in listOf(1.25f, 1.5f, 2f, 2.5f, 3f)) {
            val result = fuse(frames, MfsrConfig(scale = scale, ibpIterations = 3, maxAlignShiftPx = 6))
            assertEquals(MfsrMemoryPolicy.hrSize(24, scale), result.width)
            assertEquals(MfsrMemoryPolicy.hrSize(18, scale), result.height)
            assertEquals(result.width * result.height, result.luma.size)
        }
    }

    @Test
    fun frameReportsExposeAlignmentAcceptanceAndCoverage() {
        val session = MfsrSession(MfsrConfig(scale = 1.25f, ibpIterations = 3, maxAlignShiftPx = 8))
        val ref = MfsrTestFixtures.pattern(32, 24)
        val r0 = session.ingest(ref)
        assertEquals(0, r0.index)
        assertTrue(r0.accepted)
        assertEquals(MfsrShift.ZERO, r0.shift)
        assertTrue("参考帧覆盖率应为 1", abs(r0.coverage - 1f) < 1e-3f)

        val r1 = session.ingest(MfsrTestFixtures.shift(ref, 2f, -1f))
        assertEquals(1, r1.index)
        assertTrue(r1.accepted)
        assertTrue(r1.coverage > 0f && r1.coverage <= 1f)
        assertEquals(2f, r1.shift.dx, 0.1f)
    }

    @Test
    fun untexturedFrameIsNotAcceptedForAlignmentButStillContributes() {
        val session = MfsrSession(MfsrConfig(scale = 1.25f, ibpIterations = 3))
        session.ingest(MfsrTestFixtures.pattern(32, 24))
        val flat = MfsrPlane(32, 24).also { it.fill(120f) }
        val report = session.ingest(flat)
        assertFalse("无纹理帧不得采纳对齐", report.accepted)
        assertEquals(MfsrShift.ZERO, report.shift)
        assertEquals(2, session.framesIngested)
        val result = session.finish()
        assertEquals(2, result.framesUsed)
    }

    @Test
    fun largeDriftReducesCoverage() {
        val session = MfsrSession(MfsrConfig(scale = 2f, ibpIterations = 3, maxAlignShiftPx = 32))
        val ref = MfsrTestFixtures.pattern(48, 36)
        session.ingest(ref)
        val small = session.ingest(MfsrTestFixtures.shift(ref, 1f, 1f))
        val large = session.ingest(MfsrTestFixtures.shift(ref, 10f, 8f))
        assertTrue("位移越大覆盖率越低: large=${large.coverage} small=${small.coverage}", large.coverage < small.coverage)
        assertTrue(large.accepted)
        session.abort()
    }

    // ---------------- 色彩 ----------------

    @Test
    fun ycbcrRoundTripReconstructsTheOriginalImageClosely() {
        val w = 32
        val h = 24
        val argb = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 8) % 256
                val g = (y * 10) % 256
                val b = ((x + y) * 5) % 256
                argb[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val planes = MfsrColor.fromArgb(argb, w, h)
        val out = IntArray(w * h)
        MfsrColor.combine(planes.y.data, planes.cb, planes.cr, out)
        for (i in argb.indices) {
            val dr = abs(((argb[i] shr 16) and 0xFF) - ((out[i] shr 16) and 0xFF))
            val dg = abs(((argb[i] shr 8) and 0xFF) - ((out[i] shr 8) and 0xFF))
            val db = abs((argb[i] and 0xFF) - (out[i] and 0xFF))
            assertTrue("像素 $i 颜色往返误差过大: $dr/$dg/$db", dr <= 3 && dg <= 3 && db <= 3)
            assertEquals(0xFF, (out[i] shr 24) and 0xFF)
        }
    }

    @Test
    fun chromaUpsampleAndCombineSizeContracts() {
        val y = MfsrPlane(8, 6).also { it.fill(128f) }
        val cb = MfsrPlane(4, 3).also { it.fill(128f) }
        val cr = MfsrPlane(4, 3).also { it.fill(128f) }
        val cbUp = MfsrColor.upsampleChroma(cb, 8, 6)
        val crUp = MfsrColor.upsampleChroma(cr, 8, 6)
        assertEquals(8, cbUp.width)
        assertEquals(6, cbUp.height)
        val out = IntArray(48)
        MfsrColor.combine(y.data, cbUp, crUp, out)
        assertTrue("中性色度应还原为灰阶", out.all { it == 0xFF808080.toInt() })
        assertThrows(IllegalArgumentException::class.java) {
            MfsrColor.combine(y.data, cb, crUp, out)
        }
    }

    @Test
    fun nonFiniteLumaAndChromaFallBackSafely() {
        val y = FloatArray(4) { if (it == 1) Float.NaN else 128f }
        val cb = MfsrPlane(2, 2).also { it.data[2] = Float.POSITIVE_INFINITY }
        val cr = MfsrPlane(2, 2).also { it.fill(128f) }
        val out = IntArray(4)
        MfsrColor.combine(y, cb, cr, out)
        assertTrue(out.all { (it shr 24) and 0xFF == 0xFF })
    }

    // ---------------- 平面基础 ----------------

    @Test
    fun planeSamplingAndStatisticsFollowTheDocumentedContract() {
        val p = MfsrPlane(4, 3)
        for (i in p.data.indices) p.data[i] = i.toFloat()
        assertEquals(0f, p.bilinear(0f, 0f), 0f)
        assertEquals(1f, p.bilinear(1f, 0f), 0f)
        // 像素中心之间的中点应为双线性插值
        assertEquals(0.5f, p.bilinear(0.5f, 0f), 1e-5f)
        // 越界返回 NaN（供覆盖率统计区分"帧外"与"真实 0"）
        assertTrue(p.bilinear(-1f, 0f).isNaN())
        assertTrue(p.bilinear(0f, 99f).isNaN())
        assertTrue(p.bilinear(Float.NaN, 0f).isNaN())
        assertEquals(5.5f, p.mean(), 1e-5f)
        assertTrue(p.variance() > 0f)

        val resized = p.resampleTo(8, 6)
        assertEquals(8, resized.width)
        assertEquals(6, resized.height)
        assertTrue(resized.mean() > 0f)
        val same = p.resampleTo(4, 3)
        assertEquals(5.5f, same.mean(), 1e-4f)

        val blurred = MfsrTestFixtures.pattern(16, 16).gaussianBlur(1.2f)
        assertEquals(16, blurred.width)
        assertTrue(blurred.data.all { it.isFinite() })
    }

    @Test
    fun clampInPlaceSanitisesDirtyBuffers() {
        val p = MfsrPlane(3, 1)
        p.data[0] = -10f
        p.data[1] = 999f
        p.data[2] = Float.NaN
        p.clampInPlace(0f, 255f)
        assertEquals(0f, p.data[0], 0f)
        assertEquals(255f, p.data[1], 0f)
        assertEquals(0f, p.data[2], 0f)
    }

    @Test
    fun lumaExtractionFollowsRec601() {
        val white = intArrayOf(0xFFFFFFFF.toInt())
        val black = intArrayOf(0xFF000000.toInt())
        val red = intArrayOf(0xFFFF0000.toInt())
        val green = intArrayOf(0xFF00FF00.toInt())
        val blue = intArrayOf(0xFF0000FF.toInt())
        assertEquals(255f, MfsrPlane.lumaFromArgb(white, 1, 1).data[0], 0.01f)
        assertEquals(0f, MfsrPlane.lumaFromArgb(black, 1, 1).data[0], 0.01f)
        assertEquals(0.299f * 255f, MfsrPlane.lumaFromArgb(red, 1, 1).data[0], 0.01f)
        assertEquals(0.587f * 255f, MfsrPlane.lumaFromArgb(green, 1, 1).data[0], 0.01f)
        assertEquals(0.114f * 255f, MfsrPlane.lumaFromArgb(blue, 1, 1).data[0], 0.01f)
    }

    @Test
    fun planeRejectsInconsistentConstruction() {
        assertThrows(IllegalArgumentException::class.java) { MfsrPlane(0, 5) }
        assertThrows(IllegalArgumentException::class.java) { MfsrPlane(4, 4, FloatArray(5)) }
        assertThrows(IllegalArgumentException::class.java) {
            MfsrPlane.lumaFromArgb(IntArray(3), 4, 4)
        }
    }
}
