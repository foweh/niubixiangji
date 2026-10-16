package com.hinnka.mycamera.viewfinder

/**
 * 取景器实时直方图分析器（亮度 + RGB 三通道）。
 *
 * 设计要点：
 * - **纯 Kotlin，无 Android 依赖**，便于 JVM 单测穷举边界；
 * - 采样走二维等距 stride（长边上限 [MAX_SAMPLE_EDGE]），与工程既有
 *   `ui/components/ImageHistogram.fromBitmap` 的采样策略一致，避免重采样凭空造值；
 * - bin 数开放（64 / 128 / 256），非法值由 [ViewfinderParamRanges.snapBinCount] 吸附。
 *
 * 性能取舍：本类只做一次线性扫描 + 4 次桶自增，属**轻量任务**，
 * 由调用方按 `sampleIntervalMs` 前台节流直跑（见 [SamplingThrottle]），不进后台队列。
 */
object ViewfinderHistogramAnalyzer {

    /** 采样长边上限，超过则按整数 stride 抽稀。 */
    const val MAX_SAMPLE_EDGE = 512

    /**
     * 统计 ARGB_8888 像素数组。
     *
     * @param pixels 像素数组（`0xAARRGGBB`），按行优先
     * @param width 图像宽度（<=0 或 [pixels] 为空时返回全零直方图）
     * @param height 图像高度
     * @param binCount 64 / 128 / 256，非法值就近吸附
     */
    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        binCount: Int = ViewfinderParamRanges.HISTOGRAM_BIN_DEFAULT,
    ): ViewfinderHistogram {
        val bins = ViewfinderParamRanges.snapBinCount(binCount)
        if (width <= 0 || height <= 0 || pixels.isEmpty()) {
            return ViewfinderHistogram.empty(bins)
        }
        // 防御：调用方可能传入与 width*height 不一致（更短）的数组，绝不越界。
        val usable = minOf(pixels.size, width.toLong() * height.toLong()).toInt()
        if (usable <= 0) return ViewfinderHistogram.empty(bins)

        val luminance = IntArray(bins)
        val red = IntArray(bins)
        val green = IntArray(bins)
        val blue = IntArray(bins)

        val longestEdge = maxOf(width, height)
        val step = ((longestEdge + MAX_SAMPLE_EDGE - 1) / MAX_SAMPLE_EDGE).coerceAtLeast(1)
        val offset = step / 2

        var samples = 0
        var y = offset.coerceAtMost(height - 1)
        while (y < height) {
            val rowStart = y * width
            var x = offset.coerceAtMost(width - 1)
            while (x < width) {
                val index = rowStart + x
                if (index in 0 until usable) {
                    val pixel = pixels[index]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    luminance[binIndexOf(ZebraRules.lumaOf(r, g, b), bins)]++
                    red[binIndexOf(r, bins)]++
                    green[binIndexOf(g, bins)]++
                    blue[binIndexOf(b, bins)]++
                    samples++
                }
                x += step
            }
            y += step
        }

        return ViewfinderHistogram.create(bins, luminance, red, green, blue, samples)
    }

    /** 8bit 通道值 → bin 下标。 */
    fun binIndexOf(value: Int, binCount: Int): Int {
        val bins = binCount.coerceAtLeast(1)
        val clamped = value.coerceIn(0, 255)
        return ((clamped.toLong() * bins) / 256L).toInt().coerceIn(0, bins - 1)
    }
}
