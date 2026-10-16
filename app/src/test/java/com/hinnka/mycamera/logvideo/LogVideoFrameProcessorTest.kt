package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.video.VideoLogLutMode
import com.hinnka.mycamera.video.VideoLogProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静止帧 CPU Log 编码 + Log 实况照片计划测试
 * （Log 视频 20 项 · 第 3 项「Log 实况照片」）。
 *
 * 背景：实况照片的**片段半边**直接复用 `LutRenderer` 的 Log 编码输出（接线即用），
 * 但**静止帧**走拍照管线、不经 GL，必须在 CPU 侧做同样的 sRGB→线性→Log 曲线编码，
 * 否则「封面帧是彩色、回放片段是灰片」。本文件同时锁定这条链路的数值与分片/取消行为。
 */
class LogVideoFrameProcessorTest {

    private val table = LogCurveTable.build(LogCurveRecipe(preset = LogCurvePreset.LOG_C3), onProgress = null)

    // ─────────────────── 单通道：sRGB 代码 → Log 代码 ───────────────────

    @Test
    fun encodeChannelMapsEndpointsAndMiddleGrey() {
        assertEquals(0, LogVideoFrameProcessor.encodeChannel(0, table))
        assertEquals(255, LogVideoFrameProcessor.encodeChannel(255, table))
        // 中灰：sRGB 代码 0.46 ≈ 线性 0.18 → LogC3 代码 0.391 → 100 左右
        val mid = LogVideoFrameProcessor.encodeChannel(118, table)
        assertTrue("mid=$mid", mid in 92..108)
    }

    @Test
    fun encodeChannelClampsOutOfRangeCodes() {
        assertEquals(0, LogVideoFrameProcessor.encodeChannel(-5, table))
        assertEquals(255, LogVideoFrameProcessor.encodeChannel(999, table))
    }

    @Test
    fun encodingIsMonotonicAcrossTheFullCodeRange() {
        var previous = -1
        for (code in 0..255) {
            val out = LogVideoFrameProcessor.encodeChannel(code, table)
            assertTrue("code=$code out=$out", out >= previous)
            previous = out
        }
    }

    @Test
    fun encodeArgbPixelPreservesAlphaAndChannelOrder() {
        val argb = (0x80 shl 24) or (255 shl 16) or (0 shl 8) or 0
        val out = LogVideoFrameProcessor.encodeArgbPixel(argb, table)
        assertEquals(0x80, (out ushr 24) and 0xFF)
        assertEquals(255, (out shr 16) and 0xFF)
        assertEquals(0, (out shr 8) and 0xFF)
        assertEquals(0, out and 0xFF)
    }

    // ─────────────────── 整帧：就地编码 / 保源编码 / 分片 ───────────────────

    @Test
    fun encodeArgbInPlaceMutatesAndReturnsSameBuffer() {
        val width = 8
        val height = 4
        val pixels = IntArray(width * height) { 0xFF7F7F7F.toInt() }
        val returned = LogVideoFrameProcessor.encodeArgbInPlace(pixels, width, height, table)
        assertTrue(returned === pixels)
        // 统一灰输入 → 统一 Log 灰输出（3 通道一致）
        assertEquals(returned[0], LogVideoFrameProcessor.encodeArgbPixel(0xFF7F7F7F.toInt(), table))
        assertEquals(returned[0], returned[width * height - 1])
    }

    @Test
    fun encodeArgbKeepsSourceUntouched() {
        val source = IntArray(4) { 0xFF808080.toInt() }
        val encoded = LogVideoFrameProcessor.encodeArgb(source, 2, 2, table)
        assertFalse(encoded === source)
        assertTrue(source.all { it == 0xFF808080.toInt() })
        assertTrue(encoded.all { it != 0xFF808080.toInt() })
    }

    @Test
    fun frameEncodingRequiresValidSizeAndBuffer() {
        try {
            LogVideoFrameProcessor.encodeArgbInPlace(IntArray(4), 0, 4, table)
            org.junit.Assert.fail("宽度为 0 应抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("invalid frame size"))
        }
        try {
            LogVideoFrameProcessor.encodeArgbInPlace(IntArray(3), 4, 4, table)
            org.junit.Assert.fail("缓冲区过小应抛异常")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("smaller than"))
        }
    }

    @Test
    fun chunkRowsAreClampedSoZeroStillCompletes() {
        val pixels = IntArray(16) { 0xFF404040.toInt() }
        val out = LogVideoFrameProcessor.encodeArgbInPlace(pixels, 4, 4, table, sink = null, chunkRows = 0)
        assertEquals(16, out.size)
        assertTrue(out.all { it != 0xFF404040.toInt() })
    }

    @Test
    fun cancelledSinkStopsEarlyWithoutThrowing() {
        val pixels = IntArray(64 * 64) { 0xFF808080.toInt() }
        val sink = object : LogFrameJobSink {
            override val isCancelled: Boolean = true
            override fun report(done: Int, total: Int) = Unit
            override fun checkYield() = Unit
        }
        val out = LogVideoFrameProcessor.encodeArgbInPlace(pixels, 64, 64, table, sink)
        assertTrue("取消后原样返回（不改数据、不抛异常）", out[0] == 0xFF808080.toInt())
    }

    @Test
    fun sinkReceivesProgressReports() {
        var lastDone = 0
        var lastTotal = 0
        val sink = object : LogFrameJobSink {
            override val isCancelled: Boolean = false
            override fun report(done: Int, total: Int) {
                lastDone = done
                lastTotal = total
            }
            override fun checkYield() = Unit
        }
        LogVideoFrameProcessor.encodeArgbInPlace(IntArray(32 * 8) { 0xFF808080.toInt() }, 32, 8, table, sink)
        assertEquals(8, lastTotal)
        assertEquals(8, lastDone)
    }

    // ─────────────────── 浮点线性帧 ───────────────────

    @Test
    fun encodeLinearRgbMatchesTableSamples() {
        val linear = floatArrayOf(0f, 0.18f, 0.5f, 1f)
        val out = LogVideoFrameProcessor.encodeLinearRgb(linear, table)
        linear.indices.forEach { index ->
            assertEquals(table.sample(linear[index]), out[index], 1e-6f)
        }
    }

    @Test
    fun encodeLinearRgbHonoursCancellation() {
        val sink = object : LogFrameJobSink {
            override val isCancelled: Boolean = true
            override fun report(done: Int, total: Int) = Unit
            override fun checkYield() = Unit
        }
        val out = LogVideoFrameProcessor.encodeLinearRgb(FloatArray(64) { 0.5f }, table, sink)
        assertTrue(out.all { it == 0f })
    }
}

/**
 * Log 实况照片接线计划测试（第 3 项「缺的接线走补丁」的判断依据）。
 */
class LogLivePhotoIntegratorTest {

    private val base = LogVideoConfig(
        enabled = true,
        curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3),
        lutMode = VideoLogLutMode.MONITOR_ONLY,
    )

    @Test
    fun disabledLivePhotoProducesEmptyPlan() {
        val plan = LogLivePhotoIntegrator.plan(base, VideoLogProfile.V_LOG, isLivePhotoEnabled = false, lutAvailable = true)
        assertFalse(plan.isLogLivePhoto)
        assertFalse(plan.needsStillFrameJob)
        assertNull(plan.stillCurve)
        assertEquals(LogCurveTrim.IDENTITY, plan.videoTrim)
        assertTrue(plan.notes.isNotEmpty())
    }

    @Test
    fun logOffProducesEmptyPlan() {
        val plan = LogLivePhotoIntegrator.plan(
            base.copy(enabled = false), VideoLogProfile.V_LOG, isLivePhotoEnabled = true, lutAvailable = true,
        )
        assertFalse(plan.isLogLivePhoto)
        assertFalse(plan.previewLutApplied)

        val planOffProfile = LogLivePhotoIntegrator.plan(
            base, VideoLogProfile.OFF, isLivePhotoEnabled = true, lutAvailable = true,
        )
        assertFalse(planOffProfile.isLogLivePhoto)
    }

    @Test
    fun monitorOnlyKeepsPureLogClipButStillEncodesStillFrame() {
        val plan = LogLivePhotoIntegrator.plan(base, VideoLogProfile.LOGC4_ARRI4, isLivePhotoEnabled = true, lutAvailable = true)
        assertTrue(plan.videoClipLogEncoded)
        assertTrue(plan.stillFrameLogEncoded)
        assertTrue("预览始终套还原 LUT（边拍边看）", plan.previewLutApplied)
        assertTrue(plan.needsStillFrameJob)
        assertEquals(base.curve.clamped(), plan.stillCurve)
        assertTrue(plan.notes.any { it.contains("MONITOR_ONLY") })
    }

    @Test
    fun bakeInMarksClipAsLogPlusLut() {
        val plan = LogLivePhotoIntegrator.plan(
            base.copy(lutMode = VideoLogLutMode.BAKE_IN),
            VideoLogProfile.LOGC4_ARRI4,
            isLivePhotoEnabled = true,
            lutAvailable = true,
        )
        assertTrue(plan.videoClipLogEncoded)
        assertTrue(plan.notes.any { it.contains("BAKE_IN") })
    }

    @Test
    fun missingRestoreLutDoesNotBreakThePlan() {
        val plan = LogLivePhotoIntegrator.plan(base, VideoLogProfile.LOGC4_ARRI4, isLivePhotoEnabled = true, lutAvailable = false)
        assertTrue(plan.videoClipLogEncoded)
        assertFalse(plan.previewLutApplied)
        assertTrue(plan.notes.any { it.contains("不可用") })
        assertEquals(base.resolvedRestoreLutId(), plan.restoreLutId)
    }

    @Test
    fun stillFrameLogCanBeDisabledIndependently() {
        val plan = LogLivePhotoIntegrator.plan(
            base.copy(stillFrameLogEnabled = false), VideoLogProfile.LOGC4_ARRI4, isLivePhotoEnabled = true, lutAvailable = true,
        )
        assertFalse(plan.stillFrameLogEncoded)
        assertFalse(plan.needsStillFrameJob)
        assertNull(plan.stillCurve)
        assertTrue("片段半边仍按 Log 输出", plan.videoClipLogEncoded)
        assertTrue(plan.isLogLivePhoto)
    }

    @Test
    fun livePhotoLogSwitchClosesBothHalves() {
        val plan = LogLivePhotoIntegrator.plan(
            base.copy(livePhotoLogEnabled = false), VideoLogProfile.LOGC4_ARRI4, isLivePhotoEnabled = true, lutAvailable = true,
        )
        assertFalse(plan.isLogLivePhoto)
        assertFalse(plan.previewLutApplied)
        assertTrue(plan.notes.any { it.contains("关闭") })
    }

    @Test
    fun backgroundJobDecisionFollowsPixelBudget() {
        assertFalse(LogLivePhotoIntegrator.shouldUseBackgroundJob(1280 * 720))
        assertTrue(LogLivePhotoIntegrator.shouldUseBackgroundJob(1920 * 1080))
        assertTrue(LogLivePhotoIntegrator.shouldUseBackgroundJob(3840 * 2160))
        assertFalse(LogLivePhotoIntegrator.shouldUseBackgroundJob(LogLivePhotoIntegrator.INLINE_PIXEL_BUDGET))
        assertTrue(LogLivePhotoIntegrator.shouldUseBackgroundJob(LogLivePhotoIntegrator.INLINE_PIXEL_BUDGET + 1))
    }

    @Test
    fun trimIsExportedOnlyWhenCurveIsCustomised() {
        val native = LogLivePhotoIntegrator.plan(base, VideoLogProfile.LOGC4_ARRI4, true, true)
        assertEquals(LogCurveTrim.IDENTITY, native.videoTrim)

        val custom = LogLivePhotoIntegrator.plan(
            base.copy(curve = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, slope = 1.4f)),
            VideoLogProfile.LOGC4_ARRI4,
            true,
            true,
        )
        assertFalse(custom.videoTrim.isIdentity)
        assertEquals(1.4f, custom.videoTrim.slope, 1e-6f)
        assertTrue(custom.notes.any { it.contains("trim uniform") })
    }

    @Test
    fun fourKStillFrameEstimateIsAvailableForJobGuard() {
        assertEquals(33_177_600L, LogLivePhotoIntegrator.DEFAULT_STILL_FRAME_BYTES)
    }
}
