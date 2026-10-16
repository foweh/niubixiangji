package com.hinnka.mycamera.superres

/** 模型下载生命周期阶段。 */
enum class SuperResDownloadPhase {
    /** 未开始 / 已重置。 */
    IDLE,

    /** 正在解析远端目录（含重试）。 */
    RESOLVING,

    /** 正在下载权重文件。 */
    DOWNLOADING,

    /** 正在校验大小/哈希。 */
    VERIFYING,

    /** 校验通过，已落到模型目录（可被推理使用）。 */
    READY,

    /** 失败（含不可重试失败）；message 说明原因。 */
    FAILED,

    /** 用户取消。 */
    CANCELLED,
}

/** 下载状态（可被 UI 直接渲染）。 */
data class SuperResDownloadState(
    val modelId: String = "",
    val phase: SuperResDownloadPhase = SuperResDownloadPhase.IDLE,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** 已尝试次数（含失败重试）。 */
    val attempt: Int = 0,
    val message: String = "",
    /** 失败后是否允许重试（网络类错误可重试；校验失败但可重下的也允许）。 */
    val retryable: Boolean = false,
) {
    /** 0f..1f；总长未知时返回 0。 */
    val progress: Float
        get() = if (totalBytes > 0L) (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val isTerminal: Boolean
        get() = phase == SuperResDownloadPhase.READY ||
            phase == SuperResDownloadPhase.FAILED ||
            phase == SuperResDownloadPhase.CANCELLED

    val installed: Boolean get() = phase == SuperResDownloadPhase.READY
}

/** 驱动 [SuperResDownloadState] 的事件。 */
sealed interface SuperResDownloadEvent {
    /** 请求开始（进入目录解析）。 */
    data object ResolveStarted : SuperResDownloadEvent

    /** 目录解析失败（不可重试，多半是本地目录缺失/JSON 非法）。 */
    data class ResolveFailed(val message: String) : SuperResDownloadEvent

    /** 开始写盘下载。 */
    data object DownloadStarted : SuperResDownloadEvent

    /** 下载进度。 */
    data class Progress(val doneBytes: Long, val totalBytes: Long) : SuperResDownloadEvent

    /** 下载失败；[retryable] 决定能否重试。 */
    data class Failed(val message: String, val retryable: Boolean = true) : SuperResDownloadEvent

    /** 开始校验。 */
    data object VerifyStarted : SuperResDownloadEvent

    /** 校验通过并登记为已安装。 */
    data object Installed : SuperResDownloadEvent

    /** 用户取消（任意非终态阶段均可）。 */
    data object CancelRequested : SuperResDownloadEvent

    /** 重置回 IDLE（重试入口）。 */
    data object Reset : SuperResDownloadEvent
}

/**
 * 纯状态机：下载流程的合法迁移与非法事件吸收（不依赖 Android/网络，可 JVM 单测）。
 *
 * 设计意图：网络/文件 IO 一定会有异常与竞态，把「什么阶段可以接受什么事件」抽成纯逻辑后，
 * 即使下载中断、重复点击、取消竞态，状态也不会跳到非法组合（例如 READY 后又收到 Progress）。
 * 非法事件一律**忽略并原样返回当前状态**，绝不抛异常 —— 保证任何情况下不崩溃。
 */
object SuperResDownloadStateMachine {

    fun initial(modelId: String): SuperResDownloadState =
        SuperResDownloadState(modelId = modelId, phase = SuperResDownloadPhase.IDLE)

    /** 合法迁移判定（供测试与断言使用）。 */
    fun canAccept(state: SuperResDownloadState, event: SuperResDownloadEvent): Boolean {
        return when (state.phase) {
            SuperResDownloadPhase.IDLE ->
                event is SuperResDownloadEvent.ResolveStarted || event is SuperResDownloadEvent.Reset
            SuperResDownloadPhase.RESOLVING ->
                event is SuperResDownloadEvent.DownloadStarted ||
                    event is SuperResDownloadEvent.ResolveFailed ||
                    event is SuperResDownloadEvent.CancelRequested
            SuperResDownloadPhase.DOWNLOADING ->
                event is SuperResDownloadEvent.Progress ||
                    event is SuperResDownloadEvent.Failed ||
                    event is SuperResDownloadEvent.VerifyStarted ||
                    event is SuperResDownloadEvent.CancelRequested
            SuperResDownloadPhase.VERIFYING ->
                event is SuperResDownloadEvent.Installed ||
                    event is SuperResDownloadEvent.Failed ||
                    event is SuperResDownloadEvent.CancelRequested
            // 终态只接受 Reset（重试/重新下载的唯一入口）。
            SuperResDownloadPhase.READY,
            SuperResDownloadPhase.FAILED,
            SuperResDownloadPhase.CANCELLED,
            -> event is SuperResDownloadEvent.Reset
        }
    }

    /**
     * 应用事件。非法事件（阶段不匹配、字段非法）被吸收：返回当前状态（或仅修正 message），
     * 不抛异常。
     */
    fun transition(
        state: SuperResDownloadState,
        event: SuperResDownloadEvent,
    ): SuperResDownloadState {
        if (!canAccept(state, event)) return state
        return when (event) {
            is SuperResDownloadEvent.ResolveStarted ->
                state.copy(
                    phase = SuperResDownloadPhase.RESOLVING,
                    doneBytes = 0L,
                    totalBytes = 0L,
                    message = "",
                    retryable = false,
                )

            is SuperResDownloadEvent.ResolveFailed ->
                state.copy(
                    phase = SuperResDownloadPhase.FAILED,
                    message = event.message,
                    retryable = false,
                )

            is SuperResDownloadEvent.DownloadStarted ->
                state.copy(
                    phase = SuperResDownloadPhase.DOWNLOADING,
                    doneBytes = 0L,
                    totalBytes = 0L,
                    attempt = state.attempt + 1,
                    message = "",
                    retryable = false,
                )

            is SuperResDownloadEvent.Progress -> {
                // 乱序/回退进度（例如多线程写盘导致的乱序回调）不回退进度条。
                val done = event.doneBytes.coerceAtLeast(0L).coerceAtLeast(state.doneBytes)
                val total = event.totalBytes.coerceAtLeast(0L).let {
                    if (state.totalBytes > 0L) it.coerceAtLeast(state.totalBytes) else it
                }
                val clampedDone = if (total > 0L) done.coerceAtMost(total) else done
                state.copy(
                    phase = SuperResDownloadPhase.DOWNLOADING,
                    doneBytes = clampedDone,
                    totalBytes = total,
                )
            }

            is SuperResDownloadEvent.Failed -> state.copy(
                phase = SuperResDownloadPhase.FAILED,
                message = event.message,
                retryable = event.retryable,
            )

            is SuperResDownloadEvent.VerifyStarted ->
                state.copy(phase = SuperResDownloadPhase.VERIFYING, message = "")

            is SuperResDownloadEvent.Installed ->
                state.copy(
                    phase = SuperResDownloadPhase.READY,
                    doneBytes = if (state.totalBytes > 0L) state.totalBytes else state.doneBytes,
                    message = "",
                    retryable = false,
                )

            is SuperResDownloadEvent.CancelRequested ->
                state.copy(
                    phase = SuperResDownloadPhase.CANCELLED,
                    message = "cancelled",
                    retryable = true,
                )

            is SuperResDownloadEvent.Reset -> initial(state.modelId)
        }
    }

    /** 可供重试的失败态判定。 */
    fun canRetry(state: SuperResDownloadState): Boolean =
        state.phase == SuperResDownloadPhase.FAILED && state.retryable ||
            state.phase == SuperResDownloadPhase.CANCELLED
}
