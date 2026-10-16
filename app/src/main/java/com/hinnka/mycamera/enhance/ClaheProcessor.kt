package com.hinnka.mycamera.enhance

import com.hinnka.mycamera.nativecompute.NativeComputeBridge
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * CLAHE（Contrast Limited Adaptive Histogram Equalization）。
 *
 * 思路移植自 opencv/opencv（Apache-2.0，Star≈91191，已核实）`modules/imgproc/src/clahe.cpp`：
 * ① 把画面切成 [ClaheConfig.tilesX]×[ClaheConfig.tilesY] 个 tile；
 * ② 每 tile 建 256 bin 亮度直方图，按 `clipLimit × tileArea / 256` 裁剪并把余量重分配回各 bin
 *    （保证总像素数守恒，且无 bin 超过上限）；
 * ③ 由裁剪后直方图求 CDF → 映射表 LUT；
 * ④ 像素按所处 tile 位置在相邻 4 个 tile 的 LUT 之间做双线性插值，消除块状边界。
 *
 * 与 OpenCV 的差异（刻意的健壮性改进）：
 * - 常量 tile（`tileArea - cdfMin == 0`）OpenCV 会除零退化，本实现显式退化为**恒等映射**，
 *   保证纯色块不被压黑；
 * - 支持 [ClaheConfig.lumaOnly]（默认）：只对亮度通道均衡，再把 RGB 按增益缩放，避免逐通道
 *   均衡造成的色彩串扰。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object ClaheProcessor {

    /** 直方图 bin 数（8bit 亮度）。 */
    const val LUMA_BINS = 256

    /** 按配置对 ARGB_8888 像素做 CLAHE，返回新数组（不改入参）。 */
    fun process(pixels: IntArray, width: Int, height: Int, config: ClaheConfig): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = ClaheConfig.clamp(config)
        val out = IntArray(pixels.size)
        if (clamped.mix <= 0f) {
            pixels.copyInto(out)
            return out
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        if (clamped.lumaOnly) {
            equalizeLumaInPlace(image, width, height, clamped)
            // 逐通道独立均衡：直接对每个通道的取值做同样的 tile 均衡
        } else {
            for (c in 0 until 3) {
                val channel = image.channel(c)
                val equalized = equalize(channel, width, height, clamped)
                for (i in 0 until minOf(channel.size, equalized.size)) {
                    channel[i] = PlanarRgb.mix(channel[i], equalized[i], clamped.mix)
                }
            }
        }
        image.toArgb(out, pixels)
        return out
    }

    /**
     * 亮度通道均衡：先求亮度 L，均衡得 L'，再按增益 L'/L 缩放 RGB（保色）。
     * L 接近 0 时改用加法偏移，避免除零放大噪声。
     */
    private fun equalizeLumaInPlace(image: PlanarRgb, width: Int, height: Int, config: ClaheConfig) {
        val n = minOf(image.count, width * height)
        val luma = FloatArray(n) { image.luma(it) }
        // nativecompute: CLAHE 均衡（tile 直方图 → 裁剪重分配 → CDF → 双线性插值）下沉 C++；
        // 返回 null 时用原 `equalize` 兜底，等价无差异。
        val equalized = NativeComputeBridge.claheEqualize(
            luma,
            width,
            height,
            config.tilesX,
            config.tilesY,
            config.clipLimit,
        ) ?: equalize(luma, width, height, config)
        // nativecompute: 保色增益缩放（l > 1e-3 按比例、否则加性补偿，三通道就地改写）下沉 C++
        if (NativeComputeBridge.applyLumaGain(
                image.r,
                image.g,
                image.b,
                luma,
                equalized,
                n,
                config.mix,
            )
        ) {
            return
        }
        for (i in 0 until n) {
            val l = luma[i]
            val target = PlanarRgb.mix(l, equalized[i], config.mix)
            if (target == l) continue
            if (l > 1e-3f) {
                val gain = target / l
                image.r[i] = (image.r[i] * gain).coerceIn(0f, 1f)
                image.g[i] = (image.g[i] * gain).coerceIn(0f, 1f)
                image.b[i] = (image.b[i] * gain).coerceIn(0f, 1f)
            } else {
                // 极暗像素：加性补偿，避免把黑位噪声放大成彩噪
                val delta = target - l
                image.r[i] = (image.r[i] + delta).coerceIn(0f, 1f)
                image.g[i] = (image.g[i] + delta).coerceIn(0f, 1f)
                image.b[i] = (image.b[i] + delta).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * 对 0..1 亮度数组做 CLAHE，返回同尺寸新数组。
     * 这是模块的核心纯函数，也是单测入口。
     */
    fun equalize(luma: FloatArray, width: Int, height: Int, config: ClaheConfig): FloatArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(luma.size >= width * height) {
            "luma buffer too small: ${luma.size} < ${width * height}"
        }
        val clamped = ClaheConfig.clamp(config)
        // nativecompute: 同一算法的 C++ 实现（同一 clip/同一插值/同一恒定 tile 保护）
        NativeComputeBridge.claheEqualize(
            luma,
            width,
            height,
            clamped.tilesX,
            clamped.tilesY,
            clamped.clipLimit,
        )?.let { return it }
        val tilesX = clamped.tilesX.coerceAtMost(width)
        val tilesY = clamped.tilesY.coerceAtMost(height)
        val tileW = (width + tilesX - 1) / tilesX
        val tileH = (height + tilesY - 1) / tilesY

        // ① + ② + ③：逐 tile 生成映射表
        val luts = Array(tilesX * tilesY) { IntArray(LUMA_BINS) }
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * tileW
                val y0 = ty * tileH
                val x1 = minOf(x0 + tileW, width)
                val y1 = minOf(y0 + tileH, height)
                luts[ty * tilesX + tx] = buildTileLut(luma, width, x0, y0, x1, y1, clamped.clipLimit)
            }
        }

        // ④ 双线性插值
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val yf = (y + 0.5f) / tileH - 0.5f
            val yt0Raw = floor(yf).toInt()
            val fy = yf - yt0Raw
            val yt0 = yt0Raw.coerceIn(0, tilesY - 1)
            val yt1 = (yt0Raw + 1).coerceIn(0, tilesY - 1)
            for (x in 0 until width) {
                val xf = (x + 0.5f) / tileW - 0.5f
                val xt0Raw = floor(xf).toInt()
                val fx = xf - xt0Raw
                val xt0 = xt0Raw.coerceIn(0, tilesX - 1)
                val xt1 = (xt0Raw + 1).coerceIn(0, tilesX - 1)

                val bin = lumaBin(luma[y * width + x])
                val topLeft = luts[yt0 * tilesX + xt0][bin]
                val topRight = luts[yt0 * tilesX + xt1][bin]
                val bottomLeft = luts[yt1 * tilesX + xt0][bin]
                val bottomRight = luts[yt1 * tilesX + xt1][bin]
                val top = topLeft + (topRight - topLeft) * fx
                val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
                out[y * width + x] = (top + (bottom - top) * fy) / 255f
            }
        }
        return out
    }

    /**
     * 单 tile：直方图 → 裁剪重分配 → CDF 映射表。
     * 空 tile / 常量 tile 退化为恒等映射（恒等即 `lut[i] = i`）。
     */
    internal fun buildTileLut(
        luma: FloatArray,
        width: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        clipLimit: Float,
    ): IntArray {
        val lut = IntArray(LUMA_BINS)
        val area = (x1 - x0) * (y1 - y0)
        if (area <= 0) {
            for (i in 0 until LUMA_BINS) lut[i] = i
            return lut
        }
        val hist = IntArray(LUMA_BINS)
        for (y in y0 until y1) {
            val base = y * width
            for (x in x0 until x1) hist[lumaBin(luma[base + x])]++
        }
        // 退化保护：所有样本落在同一个 bin（纯色 tile）时直接恒等映射。
        // 若先裁剪再把余量分配回低位 bin，会造出伪计数并抬高（甚至推白）纯色块的整体亮度，
        // 所以常量判断必须基于**原始**直方图，早于裁剪。
        var occupiedBins = 0
        for (i in 0 until LUMA_BINS) if (hist[i] > 0) occupiedBins++
        if (occupiedBins <= 1) {
            for (i in 0 until LUMA_BINS) lut[i] = i
            return lut
        }
        val safeClipLimit = if (clipLimit.isFinite()) maxOf(0f, clipLimit) else 0f
        val cap = maxOf(1, (safeClipLimit * area / LUMA_BINS).roundToInt())
        val clipped = clipHistogram(hist, cap)

        var cdf = 0
        var cdfMin = -1
        val cdfArr = IntArray(LUMA_BINS)
        for (i in 0 until LUMA_BINS) {
            cdf += clipped[i]
            cdfArr[i] = cdf
            if (cdfMin < 0 && cdf > 0) cdfMin = cdf
        }
        val denominator = area - cdfMin
        if (cdfMin < 0 || denominator <= 0) {
            // 常量图 / 空图：恒等映射，避免整块被压到 0
            for (i in 0 until LUMA_BINS) lut[i] = i
            return lut
        }
        val scale = (LUMA_BINS - 1).toFloat() / denominator
        for (i in 0 until LUMA_BINS) {
            lut[i] = ((cdfArr[i] - cdfMin) * scale).roundToInt().coerceIn(0, LUMA_BINS - 1)
        }
        return lut
    }

    /**
     * 对比度裁剪 + 余量重分配。
     *
     * 契约：① 结果任何 bin 不超过 [clipLimit]（除非所有 bin 都到顶）；
     * ② 当 `clipLimit * bins >= 原始总数` 时，**总数严格守恒**（不凭空增删像素）。
     */
    internal fun clipHistogram(hist: IntArray, clipLimit: Int): IntArray {
        val out = hist.copyOf()
        if (clipLimit <= 0) return out
        var excess = 0
        for (i in out.indices) {
            if (out[i] > clipLimit) {
                excess += out[i] - clipLimit
                out[i] = clipLimit
            }
        }
        while (excess > 0) {
            var added = 0
            for (i in out.indices) {
                if (excess <= 0) break
                if (out[i] < clipLimit) {
                    out[i]++
                    excess--
                    added++
                }
            }
            if (added == 0) break // 所有 bin 都已到顶，余量无法再分配
        }
        return out
    }

    /** 0..1 浮点亮度 → 0..255 bin（NaN 归 0）。 */
    internal fun lumaBin(v: Float): Int = PlanarRgb.toByteChannel(v)

    /** 直方图（0..255 bin），供诊断/单测使用。 */
    internal fun histogram(luma: FloatArray, width: Int, height: Int): IntArray {
        val hist = IntArray(LUMA_BINS)
        val n = minOf(luma.size, width * height)
        for (i in 0 until n) hist[lumaBin(luma[i])]++
        return hist
    }
}
