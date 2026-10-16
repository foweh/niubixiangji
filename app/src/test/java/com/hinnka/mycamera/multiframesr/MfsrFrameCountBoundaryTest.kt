package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧数**完全开放**的边界验证：2 / 8 / 20 / 1000 帧都要能跑完，且不存在任何总量硬上限。
 *
 * 1000 帧用例刻意使用小平面 + 关闭对齐（只为验证"帧数不设限 + 流式累加"），
 * 保证单测在纯 JVM 上秒级完成。
 */
class MfsrFrameCountBoundaryTest {

    private fun tinyFrame(w: Int, h: Int, seed: Int): MfsrPlane {
        val p = MfsrTestFixtures.pattern(w, h)
        // 轻微扰动，模拟手持漂移（对齐关闭时不影响流程）
        for (i in p.data.indices step 17) p.data[i] += (seed % 3)
        return p
    }

    private fun noAlignConfig(frames: Int) = MfsrConfig(
        frameCount = frames,
        scale = 1.25f,
        ibpIterations = 3,
        alignMethod = MfsrAlignMethod.OFF,
        sharpenAmount = 0f,
    )

    @Test
    fun configurationAcceptsTwoThroughThousandFrames() {
        for (n in listOf(2, 3, 8, 20, 100, 512, 999, 1000)) {
            assertEquals(n, MfsrConfig(frameCount = n).coerced().frameCount)
            assertTrue("帧数 $n 必须被接受", MfsrMemoryPolicy.frameCountAccepted(n))
        }
        assertTrue(MfsrMemoryPolicy.frameCountAccepted(1))
        assertFalse("0 帧是没有任务，不是帧数超限", MfsrMemoryPolicy.frameCountAccepted(0))
    }

    @Test
    fun everyBoundaryFrameCountFusesExactlyThatManyFrames() {
        for (n in listOf(2, 8, 20)) {
            val session = MfsrSession(noAlignConfig(n), expectedFrameCount = n)
            repeat(n) { i -> session.ingest(tinyFrame(12, 9, i)) }
            assertEquals(n, session.framesIngested)
            val result = session.finish()
            assertEquals("不得截断帧数", n, result.framesUsed)
            assertEquals(n, result.shifts.size)
            assertTrue(result.luma.all { it.isFinite() && it in 0f..255f })
        }
    }

    @Test
    fun thousandFramesAreProcessedWithoutAnyHardCap() {
        val n = 1000
        val session = MfsrSession(noAlignConfig(n), expectedFrameCount = n)
        repeat(n) { i -> session.ingest(tinyFrame(12, 9, i)) }
        assertEquals(n, session.framesIngested)
        val result = session.finish()
        assertEquals(n, result.framesUsed)
        assertEquals(n, result.shifts.size)
        assertEquals(MfsrMemoryPolicy.hrSize(MfsrMemoryPolicy.workSize(12, MfsrWorkingResolution.FULL), 1.25f), result.width)
        assertTrue(result.luma.all { it.isFinite() })
    }

    @Test
    fun thousandFramesSurviveTheWorstMemoryGuard() {
        val n = 1000
        val session = MfsrSession(noAlignConfig(n), guard = { false }, expectedFrameCount = n)
        repeat(n) { i -> session.ingest(tinyFrame(12, 9, i)) }
        val result = session.finish()
        // 内存极端受限 → 降级（跳到 IBP + 降工作分辨率），但**绝不因帧数拒绝任务**
        assertEquals(n, result.framesUsed)
        assertTrue(result.degraded)
        assertTrue(result.ibpSkippedForMemory)
        assertEquals(MfsrWorkingResolution.HALF, result.workingResolution)
    }

    @Test
    fun frameCountIsNotCappedByTheExpectedFrameCountHint() {
        // 提示值只是进度分母，绝不能变成隐式上限
        val session = MfsrSession(noAlignConfig(5), expectedFrameCount = 2)
        repeat(5) { i -> session.ingest(tinyFrame(8, 6, i)) }
        val result = session.finish()
        assertEquals(5, result.framesUsed)
    }

    @Test
    fun framesOfDifferentSizesAreNormalisedNotRejected() {
        val session = MfsrSession(noAlignConfig(3))
        session.ingest(tinyFrame(16, 12, 0))
        session.ingest(tinyFrame(8, 6, 1))
        session.ingest(tinyFrame(24, 18, 2))
        val result = session.finish()
        assertEquals(3, result.framesUsed)
        assertEquals(MfsrMemoryPolicy.hrSize(16, 1.25f), result.width)
    }

    @Test
    fun sessionGuardsAgainstMisuse() {
        val session = MfsrSession(noAlignConfig(2))
        session.ingest(tinyFrame(8, 6, 0))
        session.finish()
        assertThrows(IllegalStateException::class.java) { session.ingest(tinyFrame(8, 6, 1)) }
        assertThrows(IllegalStateException::class.java) { session.finish() }
    }

    @Test
    fun abortIsIdempotentAndAfterwardsTheSessionIsClosed() {
        val session = MfsrSession(noAlignConfig(2))
        session.ingest(tinyFrame(8, 6, 0))
        session.abort()
        session.abort()
        assertThrows(IllegalStateException::class.java) { session.finish() }
    }
}
