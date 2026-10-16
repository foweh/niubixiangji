package com.hinnka.mycamera.funshot

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hinnka.mycamera.utils.PLog

/**
 * 定时连拍宿主：由宿主（`CameraViewModel` / 相机屏幕）实现，把一次「按快门」映射到既有拍照通道。
 *
 * 之所以用回调而不是直接依赖 `CameraController`：本包不新增对既有相机链路的编译期依赖，
 * 仅通过已有入口接线（见 `delivery/patches/funshot_patch.md` 的 CODE_HOOKS）。
 */
interface IntervalBurstHost {

    /** 触发第 [index] 张（0 基）快门；返回 false 表示该次拍摄失败。 */
    fun onShot(index: Int, total: Int): Boolean

    /** 状态变化（倒计时秒数 / 已拍张数），用于刷新既有的倒计时覆盖层。 */
    fun onStateChanged(snapshot: IntervalBurstSnapshot)

    /** 结束（完成 / 取消 / 失败）。 */
    fun onFinished(snapshot: IntervalBurstSnapshot)
}

/**
 * 定时连拍执行器。
 *
 * **前台轻量节流直跑**：不在主线程做轮询循环，而是由状态机给出
 * [IntervalBurstSnapshot.nextEventAtMs]，用 `Handler.postDelayed` 精确唤醒下一次；
 * 倒计时/间隔期间线程零占用，唤醒时只做 O(1) 的状态推进与一次快门调用。
 * （拼接/GIF 编码等重活才走 `BgJobRunner`，见 PATTERN_SPEC §1.6。）
 */
class IntervalBurstRunner(
    private val configProvider: () -> IntervalBurstConfig,
    private val host: IntervalBurstHost,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val handler = Handler(Looper.getMainLooper())

    private var machine: IntervalBurstStateMachine? = null

    private var failed = false

    val isRunning: Boolean get() = machine?.currentPhase?.isRunning == true

    private val tickRunnable = Runnable { tick() }

    /** 开始；已有任务在跑时先取消旧任务。 */
    fun start(): Boolean {
        val config = configProvider().coerceWithReport().value
        if (!config.enabled) {
            PLog.i(TAG, "interval burst disabled by config")
            return false
        }
        if (isRunning) cancel()
        failed = false
        val stateMachine = IntervalBurstStateMachine(config)
        machine = stateMachine
        val snapshot = stateMachine.start(clock())
        PLog.i(
            TAG,
            "interval burst start: delay=${config.startDelaySec}s shots=${config.shotCount} interval=${config.intervalMs}ms",
        )
        host.onStateChanged(snapshot)
        scheduleNext(snapshot)
        return true
    }

    /** 取消（幂等）。 */
    fun cancel() {
        val stateMachine = machine ?: return
        if (!stateMachine.currentPhase.isRunning) return
        handler.removeCallbacks(tickRunnable)
        val snapshot = stateMachine.cancel(clock())
        PLog.i(TAG, "interval burst cancelled at ${snapshot.shotsTaken}/${snapshot.totalShots}")
        host.onStateChanged(snapshot)
        host.onFinished(snapshot)
    }

    fun snapshot(): IntervalBurstSnapshot? = machine?.snapshot(clock())

    private fun tick() {
        val stateMachine = machine ?: return
        val tick = stateMachine.tick(clock())
        for (index in tick.dueShotIndices) {
            val ok = runCatching { host.onShot(index, tick.snapshot.totalShots) }.getOrDefault(false)
            if (!ok) {
                failed = true
                if (stateMachine.config.autoStopOnError) {
                    PLog.w(TAG, "shot $index failed, auto stop")
                    val snapshot = stateMachine.cancel(clock())
                    host.onStateChanged(snapshot)
                    host.onFinished(snapshot)
                    handler.removeCallbacks(tickRunnable)
                    return
                }
            }
        }
        host.onStateChanged(tick.snapshot)
        if (tick.snapshot.phase.isTerminal) {
            PLog.i(
                TAG,
                "interval burst finished: phase=${tick.snapshot.phase} shots=${tick.snapshot.shotsTaken} failed=$failed",
            )
            handler.removeCallbacks(tickRunnable)
            host.onFinished(tick.snapshot)
            return
        }
        scheduleNext(tick.snapshot)
    }

    private fun scheduleNext(snapshot: IntervalBurstSnapshot) {
        if (!snapshot.isRunning) return
        val now = clock()
        val delay = (snapshot.nextEventAtMs - now).coerceAtLeast(MIN_WAKEUP_INTERVAL_MS)
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }

    companion object {
        private const val TAG = "IntervalBurstRunner"

        /** 最短唤醒间隔：避免时钟抖动导致忙等。 */
        private const val MIN_WAKEUP_INTERVAL_MS = 16L
    }
}

/** 定时连拍接线点。 */
object IntervalBurstRuntime {

    @Volatile
    var config: IntervalBurstConfig = IntervalBurstConfig()

    private var runner: IntervalBurstRunner? = null

    @Volatile
    private var host: IntervalBurstHost? = null

    val isRunning: Boolean get() = runner?.isRunning == true

    val isReady: Boolean get() = host != null

    fun attach(host: IntervalBurstHost) {
        this.host = host
        runner = IntervalBurstRunner({ config }, host)
    }

    fun start(): Boolean = runner?.start() ?: false

    fun cancel() {
        runner?.cancel()
    }

    fun snapshot(): IntervalBurstSnapshot? = runner?.snapshot()

    /** 复用既有定时器语义（0/3/5/10 秒）时的映射值。 */
    fun legacyTimerSeconds(): Int = config.toLegacyTimerSeconds()
}

/** 定时连拍配置持久化（`funshot_config`）。 */
object IntervalBurstPreferences {

    private const val PREFS_NAME = "funshot_config"

    fun load(context: Context): IntervalBurstConfig {
        val prefs = prefs(context)
        return IntervalBurstConfigCodec.decode { key -> prefs.getString(key, null) }.value
    }

    fun save(context: Context, config: IntervalBurstConfig) {
        val effective = config.coerceWithReport().value
        prefs(context).edit().apply {
            IntervalBurstConfigCodec.encode(effective).forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
