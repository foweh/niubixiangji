package com.hinnka.mycamera.multiframesr

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import java.io.File

/**
 * MFSR 核心会话（纯 Kotlin，无 Android 依赖）—— 整条链路的编排者。
 *
 * 流水线：**逐帧流式送入** → 亚像素对齐（相对第 0 帧）→ 移位叠加投影到 HR 网格
 * → 迭代反投影（IBP）→ 双边降噪 + 反锐化 → [MfsrResult]。
 *
 * 帧数完全开放：[ingest] 调多少次就融合多少帧，**没有任何总量上限**，
 * 资源压力通过 [MfsrMemoryPolicy] 的工作分辨率降级与 [MfsrFrameStore] 的磁盘落盘化解。
 * 进度/暂停/取消统一走 [MfsrCheckpoint]（生产环境转调 `BgJob.checkYield()`）。
 *
 * @param rawConfig 用户配置（内部统一 clamp，非法值不会导致崩溃）
 * @param guard 内存守卫：分配大缓冲前询问（生产环境 = `BgJob.guardMemory`）
 * @param checkpoint 分片检查点，可为 null（前台/单测）
 * @param spillDir 帧缓存落盘目录；null 表示不可落盘（帧全驻内存，仍不拒绝任务）
 * @param expectedFrameCount 期望帧数，仅用于进度分母；不做任何截断
 */
class MfsrSession(
    rawConfig: MfsrConfig,
    private val guard: (Long) -> Boolean = { true },
    private val checkpoint: MfsrCheckpoint? = null,
    private val spillDir: File? = null,
    private val expectedFrameCount: Int = 0,
) {
    /** clamp 后的有效配置。 */
    val config: MfsrConfig = rawConfig.coerced()

    private var decision: MfsrMemoryDecision? = null
    private var acc: MfsrHrAccumulator? = null
    private var store: MfsrFrameStore? = null
    private var reference: MfsrPlane? = null
    private val shifts = ArrayList<MfsrShift>()
    private var finished = false

    /** 内存决策（首帧送入后才有值）。 */
    val plan: MfsrMemoryDecision? get() = decision

    /** 已送入并参与融合的帧数（无上限）。 */
    val framesIngested: Int get() = shifts.size

    /** HR 输出尺寸（首帧送入后才有值）。 */
    val hrWidth: Int get() = decision?.hrWidth ?: 0
    val hrHeight: Int get() = decision?.hrHeight ?: 0

    /** 参考帧（工作分辨率，第 0 帧）；供上层做色度重采样。 */
    val referencePlane: MfsrPlane? get() = reference

    /**
     * 送入一帧亮度平面（允许与首帧尺寸不同，内部按工作分辨率重采样对齐）。
     *
     * @throws MfsrCancelledException [MfsrCheckpoint] 报告取消时
     * @throws IllegalStateException 会话已 [finish] 或 [abort] 后再送入
     */
    fun ingest(luma: MfsrPlane): MfsrFrameReport {
        check(!finished) { "session already finished" }
        val index = shifts.size
        if (index == 0) {
            val dec = MfsrMemoryPolicy.decide(
                lrWidth = luma.width,
                lrHeight = luma.height,
                scale = config.scale,
                requested = config.workingResolution,
                budgetBytes = config.memoryBudgetBytes,
                guard = guard,
                spillAvailable = spillDir != null,
            )
            decision = dec
            acc = MfsrHrAccumulator(dec.hrWidth, dec.hrHeight, config.scale)
            store = MfsrFrameStore(spillDir, dec.residentFrameBudgetBytes)
            val ref = luma.resampleTo(dec.workWidth, dec.workHeight)
            reference = ref
            shifts += MfsrShift.ZERO
            val coverage = acc!!.add(ref, MfsrShift.ZERO)
            store!!.put(0, ref)
            return MfsrFrameReport(0, MfsrShift.ZERO, true, coverage)
        }

        val dec = decision!!
        val work = luma.resampleTo(dec.workWidth, dec.workHeight)
        val estimate = if (config.alignmentActive) {
            MfsrSubpixelAligner.estimate(config, reference!!, work)
        } else {
            MfsrShift.ZERO
        }
        val accepted = config.alignmentActive && estimate.isFiniteShift &&
            estimate.confidence >= config.minAlignConfidence
        val applied = if (accepted) estimate else MfsrShift.ZERO
        val coverage = acc!!.add(work, applied)
        store!!.put(index, work)
        shifts += applied
        checkpoint?.checkpoint(
            index + 1,
            if (expectedFrameCount > 0) expectedFrameCount else index + 1,
        )
        return MfsrFrameReport(index, applied, accepted, coverage)
    }

    /** 融合并输出 HR 结果；完成后会话不可再用。 */
    fun finish(): MfsrResult {
        check(!finished) { "session already finished" }
        val dec = decision ?: throw IllegalStateException("no frames ingested")
        finished = true
        val storeLocal = store!!
        val hr = acc!!.toPlane()

        // IBP 步长：强度映射为增益（0.2..1.2 倍），默认 0.85 稳定收敛。
        val lambda = (0.85f * config.strength.coerceIn(0.2f, 1.2f)).coerceIn(0.1f, 1.2f)
        val outcome = MfsrIterativeBackProjection.refine(
            initial = hr,
            store = storeLocal,
            shifts = shifts,
            scale = config.scale,
            iterations = config.ibpIterations,
            lambda = lambda,
            stride = config.effectiveIbpStride(shifts.size),
            guard = guard,
            checkpoint = checkpoint,
        )

        val refined = MfsrRefiner.apply(outcome.plane, config)
        // nativecompute: 逐点截断（非有限值回落 lo）下沉 C++；native 失败时用原实现兜底（幂等）
        if (!NativeComputeBridge.clampPlane(
                refined.data,
                refined.data.size,
                MfsrIterativeBackProjection.CLAMP_LO,
                MfsrIterativeBackProjection.CLAMP_HI,
            )
        ) {
            refined.clampInPlace(MfsrIterativeBackProjection.CLAMP_LO, MfsrIterativeBackProjection.CLAMP_HI)
        }

        val storeKind = storeLocal.kind
        val result = MfsrResult(
            width = refined.width,
            height = refined.height,
            luma = refined.data,
            framesUsed = shifts.size,
            shifts = shifts.toList(),
            ibpResiduals = outcome.residuals,
            workingResolution = dec.resolution,
            degraded = dec.degraded || storeKind == MfsrFrameStoreKind.SPILLED,
            ibpSkippedForMemory = outcome.skippedForMemory,
            storeKind = storeKind,
        )
        storeLocal.clear()
        store = null
        acc = null
        reference = null
        return result
    }

    /** 取消/异常路径清理：删除落盘文件、释放引用（可重复调用）。 */
    fun abort() {
        finished = true
        runCatching { store?.clear() }
        store = null
        acc = null
        reference = null
    }
}
