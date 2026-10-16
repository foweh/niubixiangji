package com.hinnka.mycamera.multiframesr

/**
 * 多帧超分辨率（Multi-Frame Super Resolution, MFSR）公共类型。
 *
 * 本包只做一件事：把 N 张**手持连拍**的低分辨率帧，通过「亚像素对齐 + 移位叠加投影 +
 * 迭代反投影（IBP）」融合成一张高分辨率图。N 完全开放（2 ~ 1000），资源问题用
 * 「流式累加 + 工作分辨率降级 + 帧缓存落盘」解决，**绝不因帧数拒绝任务**。
 */

/**
 * 亚像素对齐方法。
 * - [BLOCK_MATCH]：归一化互相关（NCC）整像素粗搜 + 抛物线插值 + 梯度法（Lucas-Kanade）精化。
 *   纯 Kotlin、无 FFT，最稳；默认。
 * - [PHASE_CORRELATION]：相位相关（FFT 互功率谱 + 抛物线亚像素峰值拟合），再叠加梯度法精化。
 *   平移量大、亮度不均时更鲁棒，代价是一次 FFT。
 * - [OFF]：不做对齐，直接叠加（等价于纯多帧平均降噪后再放大，用于静态三脚架场景或诊断）。
 */
enum class MfsrAlignMethod {
    BLOCK_MATCH,
    PHASE_CORRELATION,
    OFF,
}

/**
 * 工作分辨率档位 —— 内存降级阶梯（1.0x → 0.75x → 0.5x）。
 *
 * 含义：在「帧原始分辨率 × 档位系数」上做对齐与叠加，最终 HR 网格 =
 * 工作分辨率 × [MfsrConfig.scale]。内存不够时沿阶梯向下走，而不是拒绝任务。
 */
enum class MfsrWorkingResolution(val factor: Float, val label: String) {
    FULL(1.0f, "1.0x"),
    THREE_QUARTER(0.75f, "0.75x"),
    HALF(0.5f, "0.5x");

    /** 阶梯的下一档（已是最低档返回 null）。 */
    val nextLower: MfsrWorkingResolution?
        get() = when (this) {
            FULL -> THREE_QUARTER
            THREE_QUARTER -> HALF
            HALF -> null
        }

    companion object {
        /** 降级阶梯顺序，供内存策略按序尝试。 */
        val LADDER: List<MfsrWorkingResolution> = listOf(FULL, THREE_QUARTER, HALF)

        /** 宽松解析：非法系数按最近档位取，再做 clamp（不抛异常）。 */
        fun fromFactor(factor: Float): MfsrWorkingResolution {
            if (!factor.isFinite()) return HALF
            return LADDER.minByOrNull { kotlin.math.abs(it.factor - factor) } ?: FULL
        }
    }
}

/**
 * 一帧相对参考帧（第 0 帧）的亚像素平移估计。
 *
 * 约定：`tgt(x, y) = ref(x - dx, y - dy)`，即**参考帧内容沿 (dx, dy) 平移后得到目标帧**；
 * 因此把某一帧视为「参考帧平移了 (dx,dy)」，用它反向采样即可对齐回参考帧网格。
 *
 * @param confidence 0..1 置信度（纹理越丰富、峰越尖锐越高）。无纹理或退化帧为 0。
 */
data class MfsrShift(
    val dx: Float,
    val dy: Float,
    val confidence: Float,
) {
    val isFiniteShift: Boolean get() = dx.isFinite() && dy.isFinite()

    val magnitude: Float get() = kotlin.math.sqrt(dx * dx + dy * dy)

    /** 限量到 ±maxShift（防误设导致的越界采样）。 */
    fun clampedTo(maxShift: Float): MfsrShift {
        if (!isFiniteShift) return ZERO
        val m = magnitude
        if (m <= maxShift || m <= 0f) return this
        val k = maxShift / m
        return copy(dx = dx * k, dy = dy * k)
    }

    companion object {
        /** 无平移、全置信（参考帧自身）。 */
        val ZERO = MfsrShift(0f, 0f, 1f)

        /** 无法估计（退化帧）：按零平移处理，置信度 0 便于上层剔除。 */
        val UNDEFINED = MfsrShift(0f, 0f, 0f)
    }
}

/** 单帧处理结果（供进度与诊断上报）。 */
data class MfsrFrameReport(
    /** 帧序号（0 = 参考帧）。 */
    val index: Int,
    /** 估计到的亚像素平移；未接受对齐时为 [MfsrShift.ZERO]。 */
    val shift: MfsrShift,
    /** 该帧的对齐结果是否被采纳（对齐开关关闭 / 置信度不足时为 false）。 */
    val accepted: Boolean,
    /** 该帧写入 HR 网格的有效像素占比 0..1（越界比例高说明手持漂移大）。 */
    val coverage: Float,
)

/** 任务被取消时抛出（与 [com.hinnka.mycamera.computational.BgJobCancelledException] 语义对齐，但可纯 JVM 测试）。 */
class MfsrCancelledException(message: String = "mfsr cancelled") : RuntimeException(message)

/**
 * 分片检查点：MFSR 核心在每个「帧边界 / 迭代边界」回调一次。
 *
 * 实现方负责：响应取消（抛 [MfsrCancelledException]）、暂停时阻塞、节流让出 CPU。
 * - 生产环境用 [MfsrBgJobCheckpoint]（转调 `BgJob.checkYield()`）；
 * - 纯 JVM 场景 / 单测用 [MfsrJobGate]（自带 pause/resume/cancel 状态机）。
 */
interface MfsrCheckpoint {
    fun checkpoint(completedUnits: Int, totalUnits: Int)
}

/** 不做任何检查（前台同步调用/单测的默认值）。 */
object MfsrNoCheckpoint : MfsrCheckpoint {
    override fun checkpoint(completedUnits: Int, totalUnits: Int) = Unit
}

/** 帧缓存的驻留形态。 */
enum class MfsrFrameStoreKind {
    /** 全部驻留内存（帧少或工作分辨率低）。 */
    MEMORY,

    /** 超出内存预算的帧写入缓存目录（磁盘），内存只保留在途帧。 */
    SPILLED,
}

/** 融合结果。 */
class MfsrResult(
    val width: Int,
    val height: Int,
    /** 行主序 HR 亮度数据，长度 width*height，取值已 clamp 到 [0, 255]。 */
    val luma: FloatArray,
    /** 实际使用的帧数（= 送入的帧数，不做任何截断）。 */
    val framesUsed: Int,
    /** 每帧的对齐结果（长度 = framesUsed）。 */
    val shifts: List<MfsrShift>,
    /** 每轮 IBP 后的平均绝对残差（供收敛诊断）。 */
    val ibpResiduals: List<Float>,
    /** 实际落到的工作分辨率档位。 */
    val workingResolution: MfsrWorkingResolution,
    /** 是否发生过内存降级（档位下调或帧缓存落盘）。 */
    val degraded: Boolean,
    /** 是否因内存不足跳过 IBP（降级而非失败）。 */
    val ibpSkippedForMemory: Boolean,
    val storeKind: MfsrFrameStoreKind,
) {
    fun index(x: Int, y: Int): Int = y * width + x
}
