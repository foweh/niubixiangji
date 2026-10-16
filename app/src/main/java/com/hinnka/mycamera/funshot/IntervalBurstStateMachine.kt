package com.hinnka.mycamera.funshot

import kotlin.math.max

/** 定时连拍阶段。 */
enum class IntervalBurstPhase {
    IDLE,
    COUNTDOWN,
    BURSTING,
    DONE,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == DONE || this == CANCELLED
    val isRunning: Boolean get() = this == COUNTDOWN || this == BURSTING
}

/** 定时连拍状态快照（供 UI / 日志，避免直接读内部字段）。 */
data class IntervalBurstSnapshot(
    val phase: IntervalBurstPhase,
    val shotsTaken: Int,
    val totalShots: Int,
    /** 倒计时剩余（仅 [IntervalBurstPhase.COUNTDOWN] 有意义）。 */
    val startDelayRemainingMs: Long,
    /** 下一次需要唤醒的时间（`0` 表示无需再唤醒）。 */
    val nextEventAtMs: Long,
    val elapsedMs: Long,
) {
    val isTerminal: Boolean get() = phase.isTerminal
    val isRunning: Boolean get() = phase.isRunning
}

/** 一次 tick 的结果：[dueShotIndices] 为本轮**应当触发**的快门序号（0 基，升序）。 */
data class IntervalBurstTick(val dueShotIndices: List<Int>, val snapshot: IntervalBurstSnapshot) {
    val hasWork: Boolean get() = dueShotIndices.isNotEmpty()
}

/**
 * 定时连拍状态机（纯逻辑，无 Android 依赖，可单测）。
 *
 * 状态迁移：`IDLE → COUNTDOWN → BURSTING → DONE`；任何运行态可 `→ CANCELLED`。
 * 驱动方式是**推入时间戳**（[tick]），不做内部轮询 —— 执行器按 [IntervalBurstSnapshot.nextEventAtMs]
 * 精确调度下一次唤醒，因此倒计时/连拍期间几乎不占 CPU（前台轻量直跑）。
 *
 * 间隔抖动由 [IntervalBurstConfig.randomSeed] 决定的 LCG 产生（复用包内 [FunLcg]），
 * 同一 seed 同一参数下序列完全可复现，便于单测断言。
 */
class IntervalBurstStateMachine(config: IntervalBurstConfig) {

    private val effective: IntervalBurstConfig = config.coerceWithReport().value

    private var phase = IntervalBurstPhase.IDLE
    private var startedAtMs = 0L
    private var firstShotAtMs = 0L
    private var nextShotAtMs = 0L
    private var shotsTaken = 0
    private var lcg = FunLcg(effective.randomSeed)

    val config: IntervalBurstConfig get() = effective

    val currentPhase: IntervalBurstPhase get() = phase

    /** 开始（[IntervalBurstPhase.IDLE] / 终止态可再次开始）。 */
    fun start(nowMs: Long): IntervalBurstSnapshot {
        startedAtMs = nowMs
        firstShotAtMs = nowMs + effective.startDelayMs()
        nextShotAtMs = firstShotAtMs
        shotsTaken = 0
        lcg = FunLcg(effective.randomSeed)
        phase = if (effective.startDelayMs() > 0L) IntervalBurstPhase.COUNTDOWN else IntervalBurstPhase.BURSTING
        return snapshot(nowMs)
    }

    fun cancel(nowMs: Long): IntervalBurstSnapshot {
        if (phase.isRunning) phase = IntervalBurstPhase.CANCELLED
        nextShotAtMs = 0L
        return snapshot(nowMs)
    }

    fun reset(): IntervalBurstSnapshot {
        phase = IntervalBurstPhase.IDLE
        startedAtMs = 0L
        firstShotAtMs = 0L
        nextShotAtMs = 0L
        shotsTaken = 0
        lcg = FunLcg(effective.randomSeed)
        return snapshot(0L)
    }

    /** 推进到 [nowMs]，返回本轮到期的快门序号。 */
    fun tick(nowMs: Long): IntervalBurstTick {
        val due = ArrayList<Int>()
        if (phase == IntervalBurstPhase.COUNTDOWN && nowMs >= firstShotAtMs) {
            phase = IntervalBurstPhase.BURSTING
        }
        if (phase == IntervalBurstPhase.BURSTING) {
            var guard = 0
            val maxIterations = effective.shotCount + 8
            while (shotsTaken < effective.shotCount && nowMs >= nextShotAtMs && guard < maxIterations) {
                due += shotsTaken
                shotsTaken++
                guard++
                if (shotsTaken >= effective.shotCount) {
                    phase = IntervalBurstPhase.DONE
                    nextShotAtMs = 0L
                    break
                }
                nextShotAtMs += intervalForShot(shotsTaken)
            }
        }
        return IntervalBurstTick(due, snapshot(nowMs))
    }

    fun snapshot(nowMs: Long): IntervalBurstSnapshot {
        val countdownRemaining = if (phase == IntervalBurstPhase.COUNTDOWN) {
            max(0L, firstShotAtMs - nowMs)
        } else {
            0L
        }
        val nextEventAt = when (phase) {
            IntervalBurstPhase.COUNTDOWN -> firstShotAtMs
            IntervalBurstPhase.BURSTING -> nextShotAtMs
            else -> 0L
        }
        return IntervalBurstSnapshot(
            phase = phase,
            shotsTaken = shotsTaken,
            totalShots = effective.shotCount,
            startDelayRemainingMs = countdownRemaining,
            nextEventAtMs = nextEventAt,
            elapsedMs = if (startedAtMs == 0L) 0L else max(0L, nowMs - startedAtMs),
        )
    }

    /** 第 [index] 张之后到下一张的间隔（含确定性抖动）。 */
    private fun intervalForShot(index: Int): Long {
        val base = effective.intervalMs
        val jitter = if (effective.intervalJitterMs <= 0L) {
            0L
        } else {
            lcg.nextPositive() % (effective.intervalJitterMs + 1L)
        }
        return base + jitter
    }
}
