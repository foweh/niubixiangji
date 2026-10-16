package com.hinnka.mycamera.funshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定时连拍状态机（任务书要求的「定时状态机」覆盖）。 */
class IntervalBurstStateMachineTest {

    private fun config(
        delaySec: Int = 0,
        shots: Int = 3,
        intervalMs: Long = 1_000L,
        jitterMs: Long = 0L,
        seed: Long = 20261016L,
    ) = IntervalBurstConfig(
        enabled = true,
        startDelaySec = delaySec,
        shotCount = shots,
        intervalMs = intervalMs,
        intervalJitterMs = jitterMs,
        randomSeed = seed,
    )

    @Test
    fun zeroDelayFiresFirstShotOnFirstTick() {
        val machine = IntervalBurstStateMachine(config())
        val started = machine.start(1_000L)
        assertEquals(IntervalBurstPhase.BURSTING, started.phase)
        assertEquals(1_000L, started.nextEventAtMs)
        assertEquals(3, started.totalShots)

        val tick = machine.tick(1_000L)
        assertEquals(listOf(0), tick.dueShotIndices)
        assertEquals(1, tick.snapshot.shotsTaken)
        assertTrue(tick.snapshot.isRunning)
    }

    @Test
    fun shotsAreSpacedByConfiguredInterval() {
        val machine = IntervalBurstStateMachine(config())
        machine.start(1_000L)
        assertEquals(listOf(0), machine.tick(1_000L).dueShotIndices)
        assertFalse(machine.tick(1_999L).hasWork)
        assertEquals(listOf(1), machine.tick(2_000L).dueShotIndices)
        assertFalse(machine.tick(2_999L).hasWork)
        val last = machine.tick(3_000L)
        assertEquals(listOf(2), last.dueShotIndices)
        assertEquals(IntervalBurstPhase.DONE, last.snapshot.phase)
        assertTrue(last.snapshot.isTerminal)
    }

    @Test
    fun countdownBlocksShotsUntilDelayElapsed() {
        val machine = IntervalBurstStateMachine(config(delaySec = 3))
        val started = machine.start(0L)
        assertEquals(IntervalBurstPhase.COUNTDOWN, started.phase)
        assertEquals(3_000L, started.nextEventAtMs)
        assertEquals(3_000L, started.startDelayRemainingMs)

        assertFalse(machine.tick(2_999L).hasWork)
        assertEquals(IntervalBurstPhase.COUNTDOWN, machine.currentPhase)
        val first = machine.tick(3_000L)
        assertEquals(listOf(0), first.dueShotIndices)
        assertEquals(IntervalBurstPhase.BURSTING, first.snapshot.phase)
        assertEquals(0L, first.snapshot.startDelayRemainingMs)
    }

    @Test
    fun singleShotCompletesImmediately() {
        val machine = IntervalBurstStateMachine(config(shots = 1))
        machine.start(0L)
        val tick = machine.tick(0L)
        assertEquals(listOf(0), tick.dueShotIndices)
        assertEquals(IntervalBurstPhase.DONE, tick.snapshot.phase)
    }

    @Test
    fun cancelStopsFurtherShots() {
        val machine = IntervalBurstStateMachine(config())
        machine.start(0L)
        assertEquals(listOf(0), machine.tick(0L).dueShotIndices)
        val cancelled = machine.cancel(500L)
        assertEquals(IntervalBurstPhase.CANCELLED, cancelled.phase)
        assertFalse(machine.tick(10_000L).hasWork)
        assertEquals(1, machine.snapshot(10_000L).shotsTaken)
    }

    @Test
    fun terminalStateIgnoresCancelAndTicks() {
        val machine = IntervalBurstStateMachine(config(shots = 1))
        machine.start(0L)
        machine.tick(0L)
        assertEquals(IntervalBurstPhase.DONE, machine.currentPhase)
        assertEquals(IntervalBurstPhase.DONE, machine.cancel(1_000L).phase)
        assertFalse(machine.tick(20_000L).hasWork)
    }

    @Test
    fun resetReturnsToIdle() {
        val machine = IntervalBurstStateMachine(config())
        machine.start(0L)
        machine.tick(0L)
        val reset = machine.reset()
        assertEquals(IntervalBurstPhase.IDLE, reset.phase)
        assertEquals(0, reset.shotsTaken)
        assertFalse(machine.tick(5_000L).hasWork)
        // 复位后可再次开始。
        assertEquals(IntervalBurstPhase.BURSTING, machine.start(9_000L).phase)
    }

    @Test
    fun jitterIsDeterministicForSameSeed() {
        val first = IntervalBurstStateMachine(config(shots = 6, jitterMs = 400L, seed = 99L))
        val second = IntervalBurstStateMachine(config(shots = 6, jitterMs = 400L, seed = 99L))
        assertEquals(collectEventTimes(first), collectEventTimes(second))
    }

    @Test
    fun jitterStaysWithinConfiguredBound() {
        val machine = IntervalBurstStateMachine(config(shots = 5, intervalMs = 1_000L, jitterMs = 300L))
        val events = collectEventTimes(machine)
        assertEquals(5, events.size)
        for (i in 1 until events.size) {
            val gap = events[i] - events[i - 1]
            assertTrue("gap $gap out of [1000, 1300]", gap in 1_000L..1_300L)
        }
    }

    @Test
    fun phaseHelpersMatchSemantics() {
        assertTrue(IntervalBurstPhase.COUNTDOWN.isRunning)
        assertTrue(IntervalBurstPhase.BURSTING.isRunning)
        assertFalse(IntervalBurstPhase.IDLE.isRunning)
        assertTrue(IntervalBurstPhase.DONE.isTerminal)
        assertTrue(IntervalBurstPhase.CANCELLED.isTerminal)
        assertFalse(IntervalBurstPhase.BURSTING.isTerminal)
    }

    @Test
    fun configClampFixesOutOfRangeValues() {
        val report = IntervalBurstConfig(
            enabled = true,
            startDelaySec = 999,
            shotCount = -5,
            intervalMs = 1L,
            intervalJitterMs = 99_999L,
        ).coerceWithReport()
        val config = report.value
        assertEquals(FunshotRanges.INTERVAL_TIMER_MAX_SEC, config.startDelaySec)
        assertEquals(1, config.shotCount)
        assertEquals(FunshotRanges.INTERVAL_MIN_MS, config.intervalMs)
        assertEquals(FunshotRanges.INTERVAL_JITTER_MAX_MS, config.intervalJitterMs)
        assertTrue(report.changed)

        val upper = IntervalBurstConfig(shotCount = 9_999, intervalMs = 999_999L).coerceWithReport().value
        assertEquals(FunshotRanges.INTERVAL_SHOT_MAX, upper.shotCount)
        assertEquals(FunshotRanges.INTERVAL_MAX_MS, upper.intervalMs)
    }

    @Test
    fun legacyTimerMappingFollowsExistingButtons() {
        assertEquals(0, IntervalBurstConfig(startDelaySec = 0).toLegacyTimerSeconds())
        assertEquals(3, IntervalBurstConfig(startDelaySec = 3).toLegacyTimerSeconds())
        assertEquals(3, IntervalBurstConfig(startDelaySec = 4).toLegacyTimerSeconds())
        assertEquals(5, IntervalBurstConfig(startDelaySec = 7).toLegacyTimerSeconds())
        assertEquals(5, IntervalBurstConfig(startDelaySec = 9).toLegacyTimerSeconds())
        assertEquals(10, IntervalBurstConfig(startDelaySec = 10).toLegacyTimerSeconds())
        assertEquals(10, IntervalBurstConfig(startDelaySec = 59).toLegacyTimerSeconds())
        assertEquals(3_000L, IntervalBurstConfig(startDelaySec = 3).startDelayMs())
    }

    @Test
    fun codecRoundTripPreservesValues() {
        val source = IntervalBurstConfig(enabled = true, startDelaySec = 5, shotCount = 24, intervalMs = 750L)
        val encoded = IntervalBurstConfigCodec.encode(source)
        val decoded = IntervalBurstConfigCodec.decode { key -> encoded[key] }.value
        assertEquals(source.startDelaySec, decoded.startDelaySec)
        assertEquals(source.shotCount, decoded.shotCount)
        assertEquals(source.intervalMs, decoded.intervalMs)
        assertTrue(decoded.enabled)
    }

    /** 以 1ms 步进推进，收集每张快门的实际触发时刻。 */
    private fun collectEventTimes(machine: IntervalBurstStateMachine): List<Long> {
        machine.start(0L)
        val events = ArrayList<Long>()
        var now = 0L
        while (now <= 30_000L && !machine.currentPhase.isTerminal) {
            val tick = machine.tick(now)
            repeat(tick.dueShotIndices.size) { events += now }
            now += 1L
        }
        return events
    }
}
