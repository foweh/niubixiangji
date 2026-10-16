package com.hinnka.mycamera.galleryedit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 画廊快速编辑模块的**全部开放参数取值范围**集中定义处（用户硬约束：参数开放可调 + 默认值 + 范围 + clamp）。
 *
 * 设计约定：
 * - 所有取值均在此处声明常量，业务代码禁止裸写魔法数；
 * - 所有 `clamp*` 函数对异常输入（NaN / Infinity / 越界 / 反向区间）一律**修正**而不抛异常；
 * - 被修正的字段通过 [GeClampReport] 返回，由 Android 层写 PLog 便于真机排查。
 */
object GeEditRanges {

    /** -100..100 的相对调节区间（亮度/对比度/饱和度/色温共用）。 */
    const val ADJUST_MIN = -100
    const val ADJUST_MAX = 100
    const val ADJUST_DEFAULT = 0

    /** 亮度最大偏移（0..255 色阶）。±100 → ±64 级。 */
    const val BRIGHTNESS_MAX_LEVELS = 64f

    /** 对比度最大缩放系数（100 → 1.5，-100 → 0.5）。 */
    const val CONTRAST_MAX_GAIN = 0.5f

    /** 饱和度最大缩放（100 → 2.0，-100 → 0.0 即全灰）。 */
    const val SATURATION_MAX_GAIN = 1f

    /** 色温最大单通道增益偏移（100 → R+25% / B-25%）。 */
    const val TEMPERATURE_MAX_GAIN = 0.25f

    /** 对比度/亮度枢轴点（0..255 中灰）。 */
    const val TONE_PIVOT = 128f

    /** 裁剪最小边长（占源图短边比例），防裁剪框退化成零面积。 */
    const val CROP_MIN_FRACTION = 0.05f
    const val CROP_MIN_FRACTION_LIMIT_MAX = 0.5f

    /** 水印字号（sp）范围。 */
    const val WATERMARK_FONT_MIN = 8f
    const val WATERMARK_FONT_MAX = 240f
    const val WATERMARK_FONT_DEFAULT = 32f

    /** 水印/日期戳透明度范围（0..1）。 */
    const val OPACITY_MIN = 0f
    const val OPACITY_MAX = 1f
    const val WATERMARK_OPACITY_DEFAULT = 0.65f
    const val DATE_STAMP_OPACITY_DEFAULT = 0.85f

    /** 边距（占容器短边比例）。 */
    const val MARGIN_FRACTION_MIN = 0f
    const val MARGIN_FRACTION_MAX = 0.25f
    const val MARGIN_FRACTION_DEFAULT = 0.03f

    /** 平铺水印间距（占水印内容尺寸倍数）。 */
    const val TILE_SPACING_MIN = 0.5f
    const val TILE_SPACING_MAX = 10f
    const val TILE_SPACING_DEFAULT = 2f

    /** 平铺水印倾斜角（度）。 */
    const val WATERMARK_ROTATION_MIN = -90f
    const val WATERMARK_ROTATION_MAX = 90f
    const val WATERMARK_ROTATION_DEFAULT = -30f

    /** 平铺水印数量上限（防超大画布上算出天量绘制项）。 */
    const val MAX_TILE_COUNT = 4096

    /** 水印文案长度上限（字符）。 */
    const val WATERMARK_TEXT_MAX_LENGTH = 200

    /** 日期戳格式串长度上限与支持的格式样本。 */
    const val DATE_PATTERN_MAX_LENGTH = 64
    const val DATE_PATTERN_DEFAULT = "yyyy-MM-dd HH:mm:ss"

    /** 前后对比滑杆位置（0..1）。 */
    const val COMPARE_SPLIT_DEFAULT = 0.5f

    /** 对比图标签是否绘制。 */
    const val COMPARE_LABEL_DEFAULT = true

    /** 视频变速范围（0.25x..4x）。 */
    const val VIDEO_SPEED_MIN = 0.25f
    const val VIDEO_SPEED_MAX = 4f
    const val VIDEO_SPEED_DEFAULT = 1f

    /** 视频允许的旋转档位。 */
    val VIDEO_ROTATION_ALLOWED = intArrayOf(0, 90, 180, 270)

    /** 视频裁剪最短保留时长（毫秒），避免切出空片段。 */
    const val VIDEO_TRIM_MIN_DURATION_MS = 100L

    /** 单条视频书签数量上限（开放但安全）。 */
    const val VIDEO_BOOKMARK_MAX = 200
    const val VIDEO_BOOKMARK_MAX_LIMIT = 2000

    /** 拼接段数上限（开放，内存不足靠分片/落盘解决）。 */
    const val VIDEO_SEGMENT_MAX = 200

    /** 视频输出帧率上限（媒体3 setFrameRate 传入，0 = 不限）。 */
    const val VIDEO_FRAME_RATE_MIN = 0
    const val VIDEO_FRAME_RATE_MAX = 240
    const val VIDEO_FRAME_RATE_DEFAULT = 0

    /** 解码/编码工作分辨率长边上限（防超高分辨率中间缓冲 OOM）。 */
    const val WORK_SCALE_MIN = 0.1f
    const val WORK_SCALE_MAX = 1f
    const val WORK_SCALE_DEFAULT = 1f

    /** 是否整数且在 [min, max] 内。 */
    fun inRange(value: Int, min: Int, max: Int): Boolean = value in min..max

    /** 有限浮点且落在 [min, max]。NaN / Infinity 视为越界。 */
    fun inRange(value: Float, min: Float, max: Float): Boolean =
        value.isFinite() && value >= min && value <= max

    /** 浮点 clamp：NaN / Infinity 一律回落到 [fallback]。 */
    fun clampFloat(value: Float, min: Float, max: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else fallback

    /** 整数 clamp（越界收敛，不做二次猜测）。 */
    fun clampInt(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    /** -100..100 调节量 clamp。 */
    fun clampAdjust(value: Int): Int = clampInt(value, ADJUST_MIN, ADJUST_MAX)

    /** 浮点近似相等（用于 isIdentity 判定，避免浮点抖动导致无谓重算）。 */
    fun nearlyEqual(a: Float, b: Float, epsilon: Float = 1e-3f): Boolean = abs(a - b) <= epsilon

    /** 把 0..1 的比例四舍五入到像素；非有限值回落到 [fallback]。 */
    fun fractionToPx(fraction: Float, extent: Int, fallback: Int = 0): Int =
        if (fraction.isFinite() && extent > 0) {
            (fraction * extent).roundToInt().coerceIn(0, extent)
        } else {
            fallback
        }
}

/**
 * 一次 clamp 中被修正的字段记录。
 *
 * 纯数据（不依赖 Android），便于单测核对"越界一定被修正且修正值等于边界"。
 */
data class GeClampCorrection(
    val field: String,
    val requested: Double,
    val applied: Double,
)

/** clamp 结果汇总；Android 层据此写 PLog。 */
data class GeClampReport(val corrections: List<GeClampCorrection>) {

    val changed: Boolean get() = corrections.isNotEmpty()

    fun describe(): String =
        corrections.joinToString(separator = "; ") { "${it.field}: ${it.requested} -> ${it.applied}" }

    companion object {
        val EMPTY = GeClampReport(emptyList())

        /** 便捷构造：仅当"请求值 != 应用值"时收录，避免噪声日志。 */
        fun of(field: String, requested: Double, applied: Double): GeClampCorrection? =
            if (requested != applied) GeClampCorrection(field, requested, applied) else null
    }
}
