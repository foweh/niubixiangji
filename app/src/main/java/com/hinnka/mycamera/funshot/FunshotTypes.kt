package com.hinnka.mycamera.funshot

/**
 * 「创意拍摄玩法」（funshot）公共类型与 clamp 工具。
 *
 * 本包覆盖 6 项玩法（全景拼接 / 声控触发快门 / 声控触发录制 / 运动触发 /
 * GIF 与动态贴纸导出（含视频转 GIF）/ 定时连拍强化）。
 *
 * 约定（PATTERN_SPEC §5）：**所有可调参数**都进各自 `*Config` 数据类，
 * 提供「默认值 + 取值范围 + clamp」，越界/脏值只修正不崩溃。
 */

/** 触发来源：声控或运动。 */
enum class FunshotTriggerKind { SOUND, MOTION }

/** 触发模式：拍照，或「奇数次触发开始录制、偶数次触发停止录制」（声控与运动共用）。 */
enum class FunshotTriggerMode { PHOTO, VIDEO }

/**
 * 一次触发最终要执行的动作。
 *
 * - [CAPTURE_PHOTO]：触发一次拍照（声控快门 / 运动触发）；
 * - [VIDEO_START] / [VIDEO_STOP]：声控触发录制的开始与停止（视频能力复用）。
 */
enum class FunshotTriggerAction { CAPTURE_PHOTO, VIDEO_START, VIDEO_STOP }

/** 工作分辨率档位（内存不足时逐级降级，而不是拒绝任务）。 */
enum class FunshotWorkingScale(val factor: Float) {
    FULL(1f),
    THREE_QUARTER(0.75f),
    HALF(0.5f),
    ;

    companion object {
        fun fromFactor(factor: Float): FunshotWorkingScale {
            if (!factor.isFinite()) return FULL
            return entries.minByOrNull { kotlin.math.abs(it.factor - factor) } ?: FULL
        }
    }
}

/**
 * 分片检查点：把 `BgJob.checkYield()` 抽象成纯接口，
 * 使算法层可在纯 JVM 单测里注入空实现（PATTERN_SPEC §4）。
 */
fun interface FunshotCheckpoint {
    fun checkpoint()
}

/**
 * 大缓冲内存守卫：返回 false 表示调用方**必须降级**
 * （降工作分辨率 / 流式 / 跳过可选步骤），禁止直接失败（PATTERN_SPEC §1.5）。
 */
fun interface FunshotMemoryGuard {
    fun allow(requiredBytes: Long): Boolean
}

/** clamp 结果：修正后的配置 + 被修正字段名（供 UI「已修正」提示与日志）。 */
data class FunshotClampReport<T>(val value: T, val fixed: List<String>) {
    val changed: Boolean get() = fixed.isNotEmpty()
}

/** 配置 clamp 工具：统一处理越界与 NaN/Infinity，避免各处重复写魔法比较。 */
object FunshotClamp {

    fun int(value: Int, min: Int, max: Int, name: String, fixed: MutableList<String>): Int {
        val clamped = value.coerceIn(min, max)
        if (clamped != value) fixed += name
        return clamped
    }

    fun long(value: Long, min: Long, max: Long, name: String, fixed: MutableList<String>): Long {
        val clamped = value.coerceIn(min, max)
        if (clamped != value) fixed += name
        return clamped
    }

    /** Float 先做有限性回退（NaN / ±Infinity → [fallback]），再 clamp。 */
    fun float(
        value: Float,
        min: Float,
        max: Float,
        fallback: Float,
        name: String,
        fixed: MutableList<String>,
    ): Float {
        val safe = if (value.isFinite()) value else fallback
        val clamped = safe.coerceIn(min, max)
        if (clamped != value) fixed += name
        return clamped
    }

    fun double(
        value: Double,
        min: Double,
        max: Double,
        fallback: Double,
        name: String,
        fixed: MutableList<String>,
    ): Double {
        val safe = if (value.isFinite()) value else fallback
        val clamped = safe.coerceIn(min, max)
        if (clamped != value) fixed += name
        return clamped
    }
}

/** 参数范围常量集中管理（避免散落魔法数）。 */
object FunshotRanges {
    // 全景
    const val PANO_FRAME_MIN = 2
    const val PANO_FRAME_MAX = 100
    const val INPUT_MAX_SIDE_MIN = 512
    const val INPUT_MAX_SIDE_MAX = 4096

    // 声控
    const val SOUND_SAMPLE_RATE_MIN = 8_000
    const val SOUND_SAMPLE_RATE_MAX = 48_000

    // 运动
    const val MOTION_AXIS_MASK_MIN = 1
    const val MOTION_AXIS_MASK_MAX = 7

    // GIF
    const val GIF_FRAME_MIN = 1
    const val GIF_FRAME_MAX = 1000

    // 定时连拍
    const val INTERVAL_TIMER_MAX_SEC = 60
    const val INTERVAL_SHOT_MAX = 200
    const val INTERVAL_MIN_MS = 100L
    const val INTERVAL_MAX_MS = 60_000L
    const val INTERVAL_JITTER_MAX_MS = 1_000L
}
