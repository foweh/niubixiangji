package com.hinnka.mycamera.longexposure

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 月亮局部色调映射（高光保留）。
 *
 * 目标：月亮本体极亮、天空极暗（动态范围可达 14+ EV），直接堆栈后月亮会顶到白点、
 * 月面环形山细节全失。本映射在**亮度域**做单调传递，保证：
 *  1. 高光不被截断：超过 [LocalToneMapConfig.highlightThreshold] 的部分走肩部曲线，
 *     越亮压缩越多，但**严格单调**（月面纹理的相对次序完整保留）；
 *  2. 纯黑保持纯黑（`0 → 0`），天空不会被提亮成灰雾；
 *  3. 局部对比只在中低亮度叠加，并按 `(1 - L)` 衰减，**不会把高光再推爆**；
 *  4. 逐 RGB 通道按同一亮度比例缩放，**保持色相**（避免月亮边缘出现彩边）。
 *
 * 纯 Kotlin（不依赖 Android），可 JVM 单测。整幅处理只额外占用两个 FloatArray（亮度 + 模糊临时）。
 */
object SuperMoonToneMapper {

    /** 全局曲线查找表精度（亮度量化级数，越大越精确、越慢）。 */
    const val GLOBAL_LUT_SIZE = 1024

    /** 单像素色调映射可放大的最大比例（防止除极小亮度时出现爆炸增益）。 */
    const val MAX_RATIO = 8f

    /**
     * 对线性 RGB 平面做高光保留的局部色调映射，返回新数组。
     *
     * @param samples 交错 RGB 样本，长度 = width*height*3。
     * @param config 已 clamp 的映射配置（[LocalToneMapConfig.clamped]）；`enabled=false` 时原样返回副本。
     */
    fun toneMap(samples: ShortArray, width: Int, height: Int, config: LocalToneMapConfig): ShortArray {
        require(width > 0 && height > 0) { "invalid plane size: ${width}x$height" }
        require(samples.size == width * height * 3) { "invalid sample count" }
        if (!config.enabled) return samples.copyOf()

        val pixelCount = width * height
        val curve = buildGlobalCurve(config)
        val luma = FloatArray(pixelCount)
        val global = FloatArray(pixelCount)
        val maxLuma = LinearRgbPlane.SAMPLE_MAX.toFloat()

        for (px in 0 until pixelCount) {
            val base = px * 3
            var l = (0.2126f * samples[base] + 0.7152f * samples[base + 1] + 0.0722f * samples[base + 2]) /
                maxLuma
            if (!l.isFinite()) l = 0f
            l = l.coerceIn(0f, 1f)
            luma[px] = l
            global[px] = curve.lookup(l)
        }

        // 局部对比：全局曲线结果与盒模糊之差（非锐化），高光区自动衰减。
        val blurred = if (config.localAmount > 0f) {
            boxBlur(global, width, height, config.localRadius)
        } else {
            null
        }

        val out = ShortArray(samples.size)
        for (px in 0 until pixelCount) {
            val base = px * 3
            val l = luma[px]
            if (l <= 0f) {
                // 纯黑保持纯黑（不放大噪声、不提亮天空）。
                out[base] = 0
                out[base + 1] = 0
                out[base + 2] = 0
                continue
            }
            var mapped = global[px]
            if (blurred != null) {
                val detail = global[px] - blurred[px]
                mapped += config.localAmount * detail * (1f - global[px])
            }
            mapped = mapped.coerceIn(0f, 1f)
            val ratio = (mapped / l).coerceIn(0f, MAX_RATIO)
            out[base] = scale(samples[base], ratio)
            out[base + 1] = scale(samples[base + 1], ratio)
            out[base + 2] = scale(samples[base + 2], ratio)
        }
        return out
    }

    /** [toneMap] 的平面版。 */
    fun toneMap(plane: LinearRgbPlane, config: LocalToneMapConfig): LinearRgbPlane =
        LinearRgbPlane(plane.width, plane.height, toneMap(plane.samples, plane.width, plane.height, config))

    /**
     * 统计近顶（>99% 满量程）样本比例，用于自检"高光是否被保留"。
     * 返回值 0..1；越高说明高光越接近截断。
     */
    fun clippedHighlightRatio(samples: ShortArray, threshold: Float = 0.99f): Float {
        if (samples.isEmpty()) return 0f
        val limit = (LinearRgbPlane.SAMPLE_MAX * threshold.coerceIn(0f, 1f)).roundToInt()
        var clipped = 0
        for (s in samples) if (s >= limit) clipped++
        return clipped.toFloat() / samples.size
    }

    /**
     * 构建全局传递曲线查找表（肩部压缩 → 暗部提升 → gamma），保证 `curve(0)=0`。
     */
    private fun buildGlobalCurve(config: LocalToneMapConfig): CurveLut {
        val table = FloatArray(GLOBAL_LUT_SIZE + 1)
        val threshold = config.highlightThreshold.coerceIn(
            LocalToneMapConfig.THRESHOLD_MIN,
            LocalToneMapConfig.THRESHOLD_MAX,
        )
        val strength = config.highlightCompression
        val liftExponent = 1f / (1f + config.shadowLift)
        for (i in table.indices) {
            val l = i.toFloat() / GLOBAL_LUT_SIZE
            // 1) 高光肩部：严格单调、上界 < 1（不截断）。
            val shaped = if (l > threshold) {
                val x = (l - threshold) / (1f - threshold)
                val shoulder = if (strength > 0f) x / (1f + strength * x) else x
                threshold + (1f - threshold) * shoulder
            } else {
                l
            }
            // 2) 暗部提升（幂函数：0→0、1→1，不污染纯黑）。
            val lifted = if (liftExponent != 1f) shaped.coerceIn(0f, 1f).pow(liftExponent) else shaped
            // 3) gamma。
            val toned = if (config.gamma != 1f) lifted.coerceIn(0f, 1f).pow(config.gamma) else lifted
            table[i] = toned.coerceIn(0f, 1f)
        }
        table[0] = 0f
        return CurveLut(table)
    }

    /** 可分离盒模糊（clamp-to-edge）。半径 <= 0 时返回副本。 */
    internal fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val r = radius
        val window = (2 * r + 1).toFloat()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)

        // 水平 pass：逐行滑动窗口。
        for (y in 0 until height) {
            val rowStart = y * width
            var sum = 0f
            for (dx in -r..r) {
                sum += src[rowStart + dx.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                tmp[rowStart + x] = sum / window
                val remove = src[rowStart + (x - r).coerceIn(0, width - 1)]
                val add = src[rowStart + (x + r + 1).coerceIn(0, width - 1)]
                sum += add - remove
            }
        }
        // 垂直 pass。
        for (x in 0 until width) {
            var sum = 0f
            for (dy in -r..r) {
                sum += tmp[dy.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val remove = tmp[(y - r).coerceIn(0, height - 1) * width + x]
                val add = tmp[(y + r + 1).coerceIn(0, height - 1) * width + x]
                sum += add - remove
            }
        }
        return out
    }

    private fun scale(sample: Short, ratio: Float): Short =
        (sample * ratio).roundToInt().coerceIn(0, LinearRgbPlane.SAMPLE_MAX).toShort()

    /** 量化查找表包装。 */
    private class CurveLut(private val table: FloatArray) {
        fun lookup(l: Float): Float {
            val index = (l * (table.size - 1)).roundToInt().coerceIn(0, table.size - 1)
            return table[index]
        }
    }
}
