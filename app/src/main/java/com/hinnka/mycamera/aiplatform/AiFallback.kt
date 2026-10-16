package com.hinnka.mycamera.aiplatform

import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 优雅回退执行器（对齐审计报告 §3.5 + PATTERN_SPEC §1.7「提示 + 降级本地」）。
 *
 * 契约：
 *  - 远端成功 → 直接返回远端值；
 *  - 远端失败 → 调用 [localFallback]（可为 null，表示该能力无本地替代，如 AI 照片评审），
 *    并通过 [onNotice] 把「失败归因 + 是否已降级」告知 UI；
 *  - **任何异常都不向上抛**（[CancellationException] 除外，必须继续传播以保证协程语义）；
 *  - [onNotice] 自身抛异常也被吞掉，避免通知回调把主流程炸掉。
 */
suspend fun <T> AiHttpClient.executeWithFallback(
    capability: AiCapability,
    remote: suspend () -> AiCallResult<T>,
    localFallback: (suspend () -> T?)? = null,
    onNotice: (AiFailureReason, Boolean) -> Unit = { _, _ -> },
): T? {
    val result: AiCallResult<T> = try {
        remote()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AiCallResult.Failure(AiFailureMapper.fromThrowable(t, AiFailureMapper.PHASE_CALL))
    }

    return when (result) {
        is AiCallResult.Success -> result.value
        is AiCallResult.Failure -> {
            PLog.w(TAG, "能力 ${capability.name} 远端不可用：${AiNotices.describe(result.reason)}")
            val fallback = try {
                currentCoroutineContext().ensureActive()
                localFallback?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PLog.w(TAG, "本地兜底执行失败：${AiLogRedactor.scrub(t.message)}")
                null
            }
            runCatching { onNotice(result.reason, fallback != null) }
            fallback
        }
    }
}

/**
 * 把任意挂起块收敛为 [AiCallResult]（服务不可用降级的最小落点）。
 * [CancellationException] 继续向上抛。
 */
suspend fun <T> runAiSafely(block: suspend () -> T): AiCallResult<T> = try {
    AiCallResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    AiCallResult.Failure(AiFailureMapper.fromThrowable(t, AiFailureMapper.PHASE_CALL))
}

/** 非挂起版本：本地兜底 / 解析等纯计算外包一层，保证不崩。 */
inline fun <T> runAiSafelyBlock(block: () -> T): AiCallResult<T> = try {
    AiCallResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    AiCallResult.Failure(AiFailureMapper.fromThrowable(t, AiFailureMapper.PHASE_CALL))
}

/** 对象式入口：便于从非扩展位置调用（等价于 [executeWithFallback]）。 */
object AiFallback {

    suspend fun <T> run(
        capability: AiCapability,
        remote: suspend () -> AiCallResult<T>,
        localFallback: (suspend () -> T?)? = null,
        onNotice: (AiFailureReason, Boolean) -> Unit = { _, _ -> },
        client: AiHttpClient? = null,
    ): T? {
        val effective = client ?: AiPlatform.peek()
        return if (effective != null) {
            with(effective) { executeWithFallback(capability, remote, localFallback, onNotice) }
        } else {
            // 平台未初始化（例如纯 JVM 环境）：直接走本地兜底，不崩。
            try {
                val value = localFallback?.invoke()
                onNotice(AiFailureReason.Disabled, value != null)
                value
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onNotice(AiFailureReason.Disabled, false)
                null
            }
        }
    }
}

private const val TAG = "AiFallback"
