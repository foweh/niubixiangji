package com.hinnka.mycamera.viewfinder

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 斑马纹的**纯逻辑判定**（不依赖 GL / Android 框架），
 * 与 GLSL 侧的条纹数学保持一一对应，便于单测穷举边界。
 *
 * 阈值语义：`thresholdPercent` 50%..120%，>100% 在 8bit 预览源上 clamp 到满量程（255）。
 */
object ZebraRules {

    /** Rec.709 亮度（与工程测光 `calculateMeteringResults` 的权重一致）。 */
    fun lumaOf(red: Int, green: Int, blue: Int): Int {
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b + 0.5f).toInt().coerceIn(0, 255)
    }

    /** 阈值百分比 → 8bit 亮度阈值（等价于 [ViewfinderParamRanges.zebraThresholdPercentToLuma]）。 */
    fun lumaThresholdOf(thresholdPercent: Float): Int =
        ViewfinderParamRanges.zebraThresholdPercentToLuma(thresholdPercent)

    /** 单个像素是否过曝（按亮度判定）。 */
    fun isOverexposedLuma(luma: Int, thresholdPercent: Float): Boolean =
        luma.coerceIn(0, 255) >= lumaThresholdOf(thresholdPercent)

    /** 单个像素是否过曝（按配置的基准判定）。 */
    fun isOverexposed(red: Int, green: Int, blue: Int, config: ZebraConfig): Boolean =
        when (config.metric) {
            ZebraMetric.LUMA -> isOverexposedLuma(lumaOf(red, green, blue), config.thresholdPercent)
            ZebraMetric.MAX_RGB -> maxOf(red, green, blue).coerceIn(0, 255) >= config.lumaThreshold
        }

    /** 像素是否落在条纹实心相位上（与着色器同源数学）。 */
    fun stripeShowsAt(
        pixelX: Float,
        pixelY: Float,
        config: ZebraConfig,
    ): Boolean {
        val width = config.stripeWidthPx.coerceAtLeast(ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MIN)
        val duty = config.stripeDuty.coerceIn(
            ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MIN,
            ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MAX,
        )
        val angleRad = Math.toRadians(config.stripeAngleDeg.toDouble())
        val coordinate = pixelX * cos(angleRad).toFloat() + pixelY * sin(angleRad).toFloat()
        val period = width * 2f
        val phase = coordinate / period
        val fraction = phase - kotlin.math.floor(phase)
        return fraction < duty
    }

    /**
     * 在 ARGB 像素数组上统计斑马纹覆盖率（0f..1f）。
     *
     * 轻量任务（逐像素分支，无浮点卷积），可前台节流直跑；仅用于自检/降级路径，
     * GL 可用时以着色器结果为准。
     */
    fun coverageRatio(
        pixels: IntArray,
        thresholdPercent: Float,
        metric: ZebraMetric = ZebraMetric.LUMA,
    ): Float {
        if (pixels.isEmpty()) return 0f
        val threshold = lumaThresholdOf(thresholdPercent)
        var hit = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val value = when (metric) {
                ZebraMetric.LUMA -> lumaOf(r, g, b)
                ZebraMetric.MAX_RGB -> maxOf(r, g, b)
            }
            if (value >= threshold) hit++
        }
        return hit.toFloat() / pixels.size.toFloat()
    }

    /** 面板用文案：把阈值百分比渲染成 "95%（≈242/255）"。 */
    fun describeThreshold(thresholdPercent: Float): String {
        val percent = thresholdPercent.coerceIn(
            ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MIN,
            ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MAX,
        )
        val luma = lumaThresholdOf(percent)
        val shown = if (abs(percent - percent.roundToInt()) < 0.05f) {
            percent.roundToInt().toString()
        } else {
            // 固定 Locale.US：避免小数点分隔符随系统语言变化（也便于单测断言）。
            java.lang.String.format(java.util.Locale.US, "%.1f", percent)
        }
        return "$shown%（≈$luma/255）"
    }
}
