package com.hinnka.mycamera.enhance

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 移轴滤镜：渐变模糊带 + 清晰带饱和度提升（微缩景观效果）。
 *
 * 思路参考 noeltock/tiltShift.js（Star≈497；jQuery + CSS3 filter 前端玩具实现，
 * **仅参考思路、不采代码**）：一条可旋转的清晰带，带外向两侧平滑过渡到全模糊。
 * 本实现为端侧像素级版本（分离式盒式模糊 + smoothstep 带内插值）。
 *
 * 几何约定：模糊系数在**归一化坐标**（u = x/width、v = y/height）内计算，
 * 清晰带中心为 (0.5, [TiltShiftConfig.centerY])，法线方向按 [TiltShiftConfig.angleDegrees] 旋转。
 * 非方形画幅下旋转带会有一点各向异性，这是该效果的常见约定，不影响观感。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object TiltShiftFilter {

    /** 按配置渲染移轴效果，返回新数组（不改入参）。 */
    fun process(pixels: IntArray, width: Int, height: Int, config: TiltShiftConfig): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = TiltShiftConfig.clamp(config)
        val out = IntArray(pixels.size)
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val factors = blurFactors(width, height, clamped)

        // 仅当需要模糊时才分配模糊缓冲（省一次全图拷贝）
        val needsBlur = clamped.blurStrength > 0f && clamped.blurRadius > 0
        val blurred = if (needsBlur) BoxBlur.blurRgb(image, clamped.blurRadius) else null
        val boost = clamped.saturationBoost

        val n = minOf(image.count, width * height)
        for (i in 0 until n) {
            val f = factors[i]
            val blurMix = f * clamped.blurStrength
            var r = image.r[i]
            var g = image.g[i]
            var b = image.b[i]
            if (blurred != null && blurMix > 0f) {
                r = PlanarRgb.mix(r, blurred.r[i], blurMix)
                g = PlanarRgb.mix(g, blurred.g[i], blurMix)
                b = PlanarRgb.mix(b, blurred.b[i], blurMix)
            }
            if (boost > 0f) {
                // 饱和度提升集中在清晰带（f 越小越提），越糊的区域越少提饱和
                val scale = 1f + boost * (1f - f)
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = (luma + (r - luma) * scale).coerceIn(0f, 1f)
                g = (luma + (g - luma) * scale).coerceIn(0f, 1f)
                b = (luma + (b - luma) * scale).coerceIn(0f, 1f)
            }
            image.r[i] = r
            image.g[i] = g
            image.b[i] = b
        }
        image.toArgb(out, pixels)
        return out
    }

    /**
     * 逐像素模糊系数：0 = 完全清晰（带内），1 = 完全模糊（带外）。
     * 由 [BoxBlur.smoothStep] 在 `bandWidth/2 → bandWidth/2 + transition` 之间平滑过渡。
     */
    internal fun blurFactors(width: Int, height: Int, config: TiltShiftConfig): FloatArray {
        val out = FloatArray(width * height)
        val halfBand = config.bandWidth / 2f
        val edge0 = halfBand
        val edge1 = halfBand + config.transition
        val angle = Math.toRadians(config.angleDegrees.toDouble())
        // 清晰带轴向（u,v 归一化空间）；法线为轴向逆时针 90°
        val axisU = cos(angle).toFloat()
        val axisV = sin(angle).toFloat()
        val normalU = -axisV
        val normalV = axisU
        for (y in 0 until height) {
            val v = (y + 0.5f) / height
            val dv = v - config.centerY
            for (x in 0 until width) {
                val u = (x + 0.5f) / width
                val du = u - 0.5f
                val signed = du * normalU + dv * normalV
                out[y * width + x] = BoxBlur.smoothStep(edge0, edge1, abs(signed))
            }
        }
        return out
    }
}
