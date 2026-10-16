package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型下载状态机测试：正常流程、非法事件吸收、异常/取消/重试分支。
 *
 * 关键约束：**任何非法事件都不得抛异常**，只能被吸收（返回当前状态），
 * 否则网络竞态/重复点击会让 UI 崩溃。
 */
class SuperResDownloadStateMachineTest {

    private val modelId = SuperResModelCatalog.ID_REALESRGAN_X2

    // ---- 正常流程 ----

    @Test
    fun happyPath_idleToReady() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        assertEquals(SuperResDownloadPhase.IDLE, s.phase)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        assertEquals(SuperResDownloadPhase.RESOLVING, s.phase)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
        assertEquals(SuperResDownloadPhase.DOWNLOADING, s.phase)
        assertEquals(1, s.attempt)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Progress(50, 100))
        assertEquals(0.5f, s.progress, 1e-6f)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.VerifyStarted)
        assertEquals(SuperResDownloadPhase.VERIFYING, s.phase)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Installed)
        assertEquals(SuperResDownloadPhase.READY, s.phase)
        assertTrue(s.installed)
        assertTrue(s.isTerminal)
        assertEquals(1f, s.progress, 1e-6f)
    }

    @Test
    fun progress_isMonotonic_andClampedToTotal() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Progress(80, 100))
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Progress(30, 100))
        assertEquals("进度不得回退", 80L, s.doneBytes)

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Progress(500, 100))
        assertEquals("不得超过总长", 100L, s.doneBytes)
        assertEquals(1f, s.progress, 1e-6f)
    }

    @Test
    fun totalLengthUnknown_progressStaysZeroButDoesNotCrash() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Progress(1024, -1))
        assertEquals("总长未知时进度保持 0（UI 显示不确定态）", 0f, s.progress, 0f)
        assertTrue(s.doneBytes >= 0L)
    }

    // ---- 边界 / 非法事件 ----

    @Test
    fun illegalEvents_areAbsorbedWithoutStateChange() {
        val idle = SuperResDownloadStateMachine.initial(modelId)
        // IDLE 阶段直接收到进度/完成：全部忽略。
        assertEquals(idle, SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.Progress(1, 2)))
        assertEquals(idle, SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.Installed))
        assertEquals(idle, SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.VerifyStarted))
        assertEquals(idle, SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.DownloadStarted))

        val ready = SuperResDownloadStateMachine.transition(
            SuperResDownloadStateMachine.transition(
                SuperResDownloadStateMachine.transition(
                    SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.ResolveStarted),
                    SuperResDownloadEvent.DownloadStarted,
                ),
                SuperResDownloadEvent.VerifyStarted,
            ),
            SuperResDownloadEvent.Installed,
        )
        // READY 后任何非 Reset 事件都不得改变状态（防止重复点击二次下载）。
        assertEquals(ready, SuperResDownloadStateMachine.transition(ready, SuperResDownloadEvent.Progress(9, 10)))
        assertEquals(ready, SuperResDownloadStateMachine.transition(ready, SuperResDownloadEvent.DownloadStarted))
    }

    @Test
    fun reset_returnsToIdleFromEveryTerminalState() {
        listOf(SuperResDownloadPhase.READY, SuperResDownloadPhase.FAILED, SuperResDownloadPhase.CANCELLED).forEach { phase ->
            val state = SuperResDownloadState(modelId = modelId, phase = phase)
            val reset = SuperResDownloadStateMachine.transition(state, SuperResDownloadEvent.Reset)
            assertEquals(SuperResDownloadPhase.IDLE, reset.phase)
            assertEquals(modelId, reset.modelId)
            assertEquals(0L, reset.doneBytes)
            assertEquals(0, reset.attempt)
        }
    }

    @Test
    fun canAccept_isConsistentWithTransition() {
        val idle = SuperResDownloadStateMachine.initial(modelId)
        assertTrue(SuperResDownloadStateMachine.canAccept(idle, SuperResDownloadEvent.ResolveStarted))
        assertFalse(SuperResDownloadStateMachine.canAccept(idle, SuperResDownloadEvent.Progress(1, 2)))
        val resolving = SuperResDownloadStateMachine.transition(idle, SuperResDownloadEvent.ResolveStarted)
        assertTrue(SuperResDownloadStateMachine.canAccept(resolving, SuperResDownloadEvent.CancelRequested))
    }

    // ---- 异常 ----

    @Test
    fun retryableFailure_canBeRetried() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Failed("network", retryable = true))
        assertEquals(SuperResDownloadPhase.FAILED, s.phase)
        assertTrue(s.retryable)
        assertTrue(SuperResDownloadStateMachine.canRetry(s))

        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Reset)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
        assertEquals("重试计数应累加", 1, s.attempt)
    }

    @Test
    fun nonRetryableFailure_isNotRetried() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveFailed("catalog broken"))
        assertEquals(SuperResDownloadPhase.FAILED, s.phase)
        assertFalse("目录损坏不该提示可重试", s.retryable)
        assertFalse(SuperResDownloadStateMachine.canRetry(s))
        // 仍可 Reset 重新开始（用户手动重试），但 canRetry=false 让 UI 不主动重试。
        assertEquals(
            SuperResDownloadPhase.IDLE,
            SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Reset).phase,
        )
    }

    @Test
    fun verifyFailure_isRetryable() {
        var s = SuperResDownloadStateMachine.initial(modelId)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.VerifyStarted)
        s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.Failed("verify_hash_mismatch", true))
        assertEquals(SuperResDownloadPhase.FAILED, s.phase)
        assertTrue(SuperResDownloadStateMachine.canRetry(s))
    }

    @Test
    fun cancelFromEachActivePhase_landsInCancelled() {
        listOf(
            SuperResDownloadPhase.RESOLVING,
            SuperResDownloadPhase.DOWNLOADING,
            SuperResDownloadPhase.VERIFYING,
        ).forEach { target ->
            var s = SuperResDownloadStateMachine.initial(modelId)
            s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.ResolveStarted)
            if (target != SuperResDownloadPhase.RESOLVING) {
                s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.DownloadStarted)
            }
            if (target == SuperResDownloadPhase.VERIFYING) {
                s = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.VerifyStarted)
            }
            assertEquals("应到达目标阶段: $target", target, s.phase)

            val cancelled = SuperResDownloadStateMachine.transition(s, SuperResDownloadEvent.CancelRequested)
            assertEquals(SuperResDownloadPhase.CANCELLED, cancelled.phase)
            assertTrue("取消必须可重试", SuperResDownloadStateMachine.canRetry(cancelled))
        }
    }

    @Test
    fun stateHelpers_areCoherent() {
        val ready = SuperResDownloadState(phase = SuperResDownloadPhase.READY)
        assertTrue(ready.installed)
        assertTrue(ready.isTerminal)
        assertFalse(ready.retryable)

        val running = SuperResDownloadState(phase = SuperResDownloadPhase.DOWNLOADING)
        assertFalse(running.isTerminal)
        assertFalse(running.installed)
    }
}
