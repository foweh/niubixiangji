package com.hinnka.mycamera.multiframesr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 暂停 / 恢复 / 取消契约（正常 · 边界 · 异常）。
 *
 * 生产路径由 `MfsrBgJobCheckpoint` 转调 `BgJob.checkYield()`；这里验证的是同一套语义的
 * 纯 JVM 实现 [MfsrJobGate] 以及与 [MfsrSession] 的组合行为，确保"分片末尾的检查点"
 * 真的能暂停、能恢复、能取消，而且不会死锁。
 */
class MfsrJobGateContractTest {

    // ---------------- 正常 ----------------

    @Test
    fun normalRunWalksEveryCheckpoint() {
        val gate = MfsrJobGate("normal")
        val calls = ArrayList<Pair<Int, Int>>()
        gate.onCheckpoint = { done, total -> calls += done to total }
        for (i in 1..5) gate.checkpoint(i, 5)
        assertEquals(5, calls.size)
        assertEquals(1 to 5, calls.first())
        assertEquals(5 to 5, calls.last())
        assertFalse(gate.isCancelled)
        assertFalse(gate.isPaused)
    }

    @Test
    fun checkpointThrottlesConsecutiveCallsByTheConfiguredInterval() {
        val gate = MfsrJobGate("throttled")
        gate.throttleIntervalMs = 20L
        val start = System.currentTimeMillis()
        for (i in 1..3) gate.checkpoint(i, 3)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("节流应产生 >=25ms 的占空比间隔，实际 ${elapsed}ms", elapsed >= 25L)
        assertTrue("不应出现僵尸等待，实际 ${elapsed}ms", elapsed < 2_000L)
    }

    @Test
    fun zeroThrottleDoesNotSleep() {
        val gate = MfsrJobGate("fast")
        gate.throttleIntervalMs = 0L
        val start = System.currentTimeMillis()
        repeat(50) { gate.checkpoint(it, 50) }
        assertTrue(System.currentTimeMillis() - start < 1_000L)
    }

    // ---------------- 边界 ----------------

    @Test
    fun pauseBlocksAtTheCheckpointUntilResume() {
        val gate = MfsrJobGate("pausable")
        // 先暂停再启动，保证"阻塞在检查点上"是被确定验证的（避免竞态）
        gate.pause()
        val finished = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val worker = thread(start = true) {
            try {
                for (i in 1..3) gate.checkpoint(i, 3)
                finished.set(true)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        Thread.sleep(150)
        assertFalse("暂停后检查点必须阻塞", finished.get())
        assertTrue(gate.isPaused)
        gate.resume()
        worker.join(3_000)
        assertTrue("恢复后必须继续跑完", finished.get())
        assertFalse(gate.isPaused)
        assertNull(error.get())
    }

    @Test
    fun pauseResumeAreIdempotent() {
        val gate = MfsrJobGate("idem")
        gate.pause()
        gate.pause()
        gate.resume()
        gate.resume()
        assertFalse(gate.isPaused)
        gate.checkpoint(1, 1)
        gate.pause()
        gate.resume()
        gate.pause()
        gate.resume()
        gate.checkpoint(1, 1)
        assertFalse(gate.isCancelled)
    }

    @Test
    fun checkpointWithDegenerateTotalsDoesNotCrash() {
        val gate = MfsrJobGate("degenerate")
        gate.checkpoint(0, 0)
        gate.checkpoint(-5, -5)
        gate.checkpoint(Int.MAX_VALUE, Int.MAX_VALUE)
        assertFalse(gate.isCancelled)
    }

    @Test
    fun pauseAndResumeBetweenFramesKeepsTheWholeFrameCount() {
        val gate = MfsrJobGate("session-pause")
        val session = MfsrSession(
            rawConfig = MfsrConfig(alignMethod = MfsrAlignMethod.OFF, ibpIterations = 3),
            checkpoint = gate,
        )
        session.ingest(MfsrTestFixtures.pattern(16, 12))
        gate.pause()
        val blocked = AtomicBoolean(true)
        val error = AtomicReference<Throwable?>(null)
        val worker = thread(start = true) {
            try {
                repeat(3) { session.ingest(MfsrTestFixtures.pattern(16, 12)) }
                blocked.set(false)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        Thread.sleep(150)
        assertTrue("暂停时送入帧必须停在检查点上", blocked.get())
        gate.resume()
        worker.join(3_000)
        assertFalse(blocked.get())
        assertEquals(null, error.get())
        val result = session.finish()
        assertEquals(4, result.framesUsed)
    }

    // ---------------- 异常 ----------------

    @Test
    fun cancelMakesTheNextCheckpointThrow() {
        val gate = MfsrJobGate("cancelled")
        gate.checkpoint(1, 3)
        gate.cancel()
        assertTrue(gate.isCancelled)
        assertThrows(MfsrCancelledException::class.java) { gate.checkpoint(2, 3) }
        assertThrows(MfsrCancelledException::class.java) { gate.checkpoint(3, 3) }
    }

    @Test
    fun cancelWhilePausedWakesTheWaiterImmediately() {
        val gate = MfsrJobGate("cancel-while-paused")
        gate.pause()
        val error = AtomicReference<Throwable?>(null)
        val exited = CountDownLatch(1)
        val worker = thread(start = true) {
            try {
                gate.checkpoint(1, 5)
                gate.checkpoint(2, 5)
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                exited.countDown()
            }
        }
        Thread.sleep(100)
        assertTrue("线程应停在暂停的检查点上", worker.isAlive)
        gate.cancel()
        assertTrue("取消必须立刻唤醒暂停中的等待者", exited.await(2, TimeUnit.SECONDS))
        worker.join(1_000)
        assertNotNull("等待者应收到取消异常", error.get())
        assertTrue(error.get() is MfsrCancelledException)
    }

    @Test
    fun cancelDuringThrottleSleepAlsoThrows() {
        val gate = MfsrJobGate("cancel-during-throttle")
        gate.throttleIntervalMs = 300L
        val error = AtomicReference<Throwable?>(null)
        val exited = CountDownLatch(1)
        val worker = thread(start = true) {
            try {
                gate.checkpoint(1, 2)
                gate.checkpoint(2, 2)
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                exited.countDown()
            }
        }
        Thread.sleep(60) // 让第一轮完成、第二轮进入节流睡眠
        gate.cancel()
        assertTrue(exited.await(3, TimeUnit.SECONDS))
        worker.join(1_000)
        assertTrue("节流睡眠中的取消也要抛异常", error.get() is MfsrCancelledException)
    }

    @Test
    fun resumeAfterCancelStaysCancelled() {
        val gate = MfsrJobGate("resume-after-cancel")
        gate.pause()
        gate.cancel()
        gate.resume() // 幂等：不得让取消状态失效，也不得造成无限等待
        assertTrue(gate.isCancelled)
        assertFalse(gate.isPaused)
        assertThrows(MfsrCancelledException::class.java) { gate.checkpoint(1, 1) }
    }

    @Test
    fun cancelIsIdempotentAndCancellableBeforeStart() {
        val gate = MfsrJobGate("early-cancel")
        gate.cancel()
        gate.cancel()
        assertThrows(MfsrCancelledException::class.java) { gate.checkpoint(1, 1) }
    }

    @Test
    fun sessionPropagatesCancellationOutOfIngest() {
        val gate = MfsrJobGate("session-cancel")
        val session = MfsrSession(rawConfig = MfsrConfig(alignMethod = MfsrAlignMethod.OFF), checkpoint = gate)
        session.ingest(MfsrTestFixtures.pattern(16, 12))
        gate.cancel()
        val counter = AtomicInteger(0)
        val error = AtomicReference<Throwable?>(null)
        val worker = thread(start = true) {
            try {
                repeat(5) {
                    session.ingest(MfsrTestFixtures.pattern(16, 12))
                    counter.incrementAndGet()
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        worker.join(3_000)
        assertTrue("取消后不得继续消化帧", counter.get() <= 1)
        assertTrue("取消必须向上冒泡", error.get() is MfsrCancelledException)
        // 调用方清理中间态（BG 任务里由 job 的失败分支负责）
        session.abort()
        assertThrows(IllegalStateException::class.java) { session.finish() }
    }

    @Test
    fun noCheckpointImplementationIsANoOp() {
        MfsrNoCheckpoint.checkpoint(1, 1)
        MfsrNoCheckpoint.checkpoint(0, 0)
        MfsrNoCheckpoint.checkpoint(-1, -1)
        assertTrue(true)
    }
}
