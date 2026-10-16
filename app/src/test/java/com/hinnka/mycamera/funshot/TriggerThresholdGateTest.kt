package com.hinnka.mycamera.funshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发阈值判定：声控与运动共用的 [TriggerThresholdGate]，以及两侧的声明式映射
 * （任务书要求的「触发阈值判定」覆盖）。
 */
class TriggerThresholdGateTest {

    private fun photoGate(
        threshold: Double = -32.0,
        release: Double = -38.0,
        minDurationMs: Long = 150L,
        cooldownMs: Long = 1_500L,
    ) = TriggerThresholdGate(
        threshold = threshold,
        releaseThreshold = release,
        minDurationMs = minDurationMs,
        mode = FunshotTriggerMode.PHOTO,
        cooldownMs = cooldownMs,
        minRecordingMs = 2_000L,
        maxRecordingMs = 120_000L,
        autoStopEnabled = true,
    )

    private fun videoGate(
        minRecordingMs: Long = 2_000L,
        maxRecordingMs: Long = 120_000L,
        cooldownMs: Long = 1_500L,
    ) = TriggerThresholdGate(
        threshold = -32.0,
        releaseThreshold = -38.0,
        minDurationMs = 150L,
        mode = FunshotTriggerMode.VIDEO,
        cooldownMs = cooldownMs,
        minRecordingMs = minRecordingMs,
        maxRecordingMs = maxRecordingMs,
        autoStopEnabled = true,
    )

    // ------------------------------------------------------------------ 阈值 / 持续时间
    @Test
    fun belowThresholdNeverFires() {
        val gate = photoGate()
        assertFalse(gate.onLevel(-50.0, 0L).fired)
        assertFalse(gate.onLevel(-60.0, 10_000L).fired)
        assertFalse(gate.isRecording)
    }

    @Test
    fun sustainedLevelFiresOnlyAfterMinDuration() {
        val gate = photoGate()
        assertFalse(gate.onLevel(-30.0, 0L).fired)
        assertFalse(gate.onLevel(-30.0, 149L).fired)
        val decision = gate.onLevel(-30.0, 150L)
        assertTrue(decision.fired)
        assertEquals(FunshotTriggerAction.CAPTURE_PHOTO, decision.action)
    }

    // ------------------------------------------------------------------ 冷却
    @Test
    fun cooldownBlocksRepeatTrigger() {
        val gate = photoGate()
        gate.onLevel(-30.0, 0L)
        assertTrue(gate.onLevel(-30.0, 150L).fired)

        // 冷却期内即使电平持续高于阈值也不再触发。
        gate.onLevel(-30.0, 1_000L)
        val blocked = gate.onLevel(-30.0, 1_400L)
        assertFalse(blocked.fired)
        assertTrue(blocked.reason.startsWith("cooldown"))

        // 冷却结束后恢复触发。
        assertTrue(gate.onLevel(-30.0, 1_660L).fired)
    }

    // ------------------------------------------------------------------ 迟滞带
    @Test
    fun hysteresisBandKeepsAccumulation() {
        val gate = photoGate()
        gate.onLevel(-30.0, 0L)
        // 落在释放线与阈值之间：不清零累积。
        gate.onLevel(-35.0, 100L)
        assertTrue(gate.onLevel(-30.0, 150L).fired)

        // 低于释放线：清零，需要重新累积。
        val second = photoGate()
        second.onLevel(-30.0, 0L)
        second.onLevel(-40.0, 100L)
        assertFalse(second.onLevel(-30.0, 150L).fired)
    }

    // ------------------------------------------------------------------ 录制开关（视频能力）
    @Test
    fun videoModeAlternatesStartAndStop() {
        val gate = videoGate()
        gate.onLevel(-30.0, 0L)
        val start = gate.onLevel(-30.0, 150L)
        assertEquals(FunshotTriggerAction.VIDEO_START, start.action)
        assertTrue(gate.isRecording)

        // 最短录制时长内不允许被下一次触发停掉。
        gate.onLevel(-30.0, 300L)
        assertFalse(gate.onLevel(-30.0, 1_000L).fired)

        val stop = gate.onLevel(-30.0, 2_160L)
        assertEquals(FunshotTriggerAction.VIDEO_STOP, stop.action)
        assertFalse(gate.isRecording)

        // 停止后需要重新累积最短持续时间，才能再次开录。
        gate.onLevel(-30.0, 4_300L)
        val restart = gate.onLevel(-30.0, 4_500L)
        assertEquals(FunshotTriggerAction.VIDEO_START, restart.action)
        assertTrue(gate.isRecording)
    }

    @Test
    fun videoRecordingAutoStopsAtMaxDuration() {
        val gate = videoGate(minRecordingMs = 500L, maxRecordingMs = 1_000L, cooldownMs = 200L)
        gate.onLevel(-30.0, 0L)
        assertEquals(FunshotTriggerAction.VIDEO_START, gate.onLevel(-30.0, 150L).action)

        assertFalse(gate.tick(1_000L).fired)
        val autoStop = gate.tick(1_150L)
        assertTrue(autoStop.fired)
        assertEquals(FunshotTriggerAction.VIDEO_STOP, autoStop.action)
        assertTrue(autoStop.reason.contains("auto"))
        assertFalse(gate.isRecording)
        assertFalse(gate.tick(2_000L).fired)
    }

    @Test
    fun resetClearsRecordingState() {
        val gate = videoGate()
        gate.onLevel(-30.0, 0L)
        gate.onLevel(-30.0, 150L)
        assertTrue(gate.isRecording)
        gate.reset()
        assertFalse(gate.isRecording)
        assertFalse(gate.tick(Long.MAX_VALUE / 2).fired)
    }

    @Test
    fun snapshotReflectsLevelAndCooldown() {
        val gate = photoGate()
        gate.onLevel(-30.0, 0L)
        assertTrue(gate.onLevel(-30.0, 150L).fired)
        val snapshot = gate.snapshot(1_000L, -30.0)
        // 触发时刻 150ms + 冷却 1500ms = 1650ms，因此此刻还剩 650ms。
        assertEquals(650L, snapshot.cooldownRemainingMs)
        assertEquals(-32.0, snapshot.threshold, 1e-9)
        assertFalse(snapshot.recording)
    }

    // ------------------------------------------------------------------ 声控映射（含电平测量）
    @Test
    fun soundGateTriggersOnLoudToneNotOnSilence() {
        val gate = SoundTriggerGate(SoundTriggerConfig())
        val loud = FunshotTestFixtures.tone(1024, 20_000)
        val quiet = FunshotTestFixtures.silence(1024, 4)

        // 先来一段静音：不触发。
        assertFalse(gate.onSamples(quiet, 0L).fired)
        // 再累积一段强音：达到最短持续时间后触发。
        assertFalse(gate.onSamples(loud, 100L).fired)
        val decision = gate.onSamples(loud, 260L)
        assertTrue(decision.fired)
        assertEquals(FunshotTriggerAction.CAPTURE_PHOTO, decision.action)
    }

    @Test
    fun soundGateAccumulationResetsAfterSilence() {
        val gate = SoundTriggerGate(SoundTriggerConfig())
        val loud = FunshotTestFixtures.tone(1024, 20_000)
        val quiet = FunshotTestFixtures.silence(1024, 4)
        gate.onSamples(loud, 0L)
        gate.onSamples(quiet, 100L)
        // 静音把累积清零，因此同一时刻重新累积还未满足持续时间。
        assertFalse(gate.onSamples(loud, 150L).fired)
        assertTrue(gate.onSamples(loud, 400L).fired)
    }

    @Test
    fun soundGateDisabledByConfigNeverFires() {
        val gate = SoundTriggerGate(SoundTriggerConfig(enabled = false))
        val loud = FunshotTestFixtures.tone(2048, 32_000)
        assertFalse(gate.onSamples(loud, 0L).fired)
        assertFalse(gate.onSamples(loud, 5_000L).fired)
    }

    @Test
    fun soundLevelMeterHandlesEmptyAndOutOfRangeInput() {
        assertEquals(AudioLevelMeter.SILENCE_DBFS, AudioLevelMeter.dbfs(ShortArray(0)), 1e-9)
        assertEquals(AudioLevelMeter.SILENCE_DBFS, AudioLevelMeter.dbfs(ShortArray(8), 8, 4), 1e-9)
        val peak = AudioLevelMeter.peakDbfs(FunshotTestFixtures.tone(256, 32_767))
        assertTrue(peak <= 0.0 && peak.isFinite())
    }

    @Test
    fun soundGateIgnoresNonFiniteLevels() {
        val gate = SoundTriggerGate(SoundTriggerConfig())
        assertFalse(gate.onLevel(Double.NaN, 0L).fired)
        assertFalse(gate.onLevel(Double.NEGATIVE_INFINITY, 200L).fired)
        assertFalse(gate.onLevel(-2_000.0, 400L).fired)
    }

    // ------------------------------------------------------------------ 运动映射（含运动数学）
    @Test
    fun motionGateRequiresMagnitudeAboveThreshold() {
        val gate = MotionTriggerGate(MotionTriggerConfig())
        // 灵敏度 0.5 → ×2.5 增益：0.5 → 1.25 < 1.3，不触发。
        assertFalse(gate.onMagnitude(0.5f, 0L).fired)
        // 1.0 → 2.5 ≥ 1.3，但持续时间窗口未满足。
        assertFalse(gate.onMagnitude(1.0f, 0L).fired)
        val decision = gate.onMagnitude(1.0f, 80L)
        assertTrue(decision.fired)
        assertEquals(FunshotTriggerAction.CAPTURE_PHOTO, decision.action)
    }

    @Test
    fun motionChildGateFirstSampleOnlySeedsGravity() {
        val gate = MotionTriggerGate(MotionTriggerConfig())
        val first = gate.onSample(0f, 0f, 9.8f, 0L)
        assertFalse("first sample must only seed gravity", first.fired)
        val second = gate.onSample(12f, 0f, 9.8f, 100L)
        assertFalse(second.fired)
        val third = gate.onSample(12f, 0f, 9.8f, 200L)
        assertTrue(third.fired)
    }

    @Test
    fun motionMathLowPassAndAxisMask() {
        val gravity = FunshotMotionMath.lowPassGravity(floatArrayOf(0f, 0f, 9.8f), 0f, 0f, 0f, 0.08f)
        assertEquals(0f, gravity[0], 1e-6f)
        assertEquals(9.016f, gravity[2], 1e-4f)

        val zero = floatArrayOf(0f, 0f, 0f)
        assertEquals(3f, FunshotMotionMath.linearMagnitude(3f, 4f, 0f, zero, 0b001), 1e-5f)
        assertEquals(5f, FunshotMotionMath.linearMagnitude(3f, 4f, 0f, zero, 0b011), 1e-5f)
        // 掩码 0 视为全轴，避免配置错误导致永不触发。
        assertEquals(5f, FunshotMotionMath.linearMagnitude(3f, 4f, 0f, zero, 0), 1e-5f)
    }

    @Test
    fun triggerSnapshotsExposeDurationAndCooldown() {
        val gate = photoGate()
        gate.onLevel(-30.0, 0L)
        val accumulating = gate.snapshot(100L, -30.0)
        assertEquals(100L, accumulating.aboveDurationMs)
        assertNotNull(accumulating)
    }
}
