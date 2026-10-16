package com.hinnka.mycamera.enhance

/**
 * 暗通道先验（Dark Channel Prior）单图去雾。
 *
 * 思路来自 He-Zhang/image_dehaze（MIT，Star≈380，仅参考思路**不采代码**）与
 * opencv/opencv（Apache-2.0，Star≈91191，已核实）`photo/src/denoise_tvl1.cpp` 之外的
 * `ximgproc`/guided filter 用法：
 *
 * 雾成像模型 `I = J·t + A·(1 - t)`，其中 J 为无雾图、t 为透射率、A 为大气光。
 * ① 暗通道：`dark(x) = min_{c∈{R,G,B}} min_{y∈Ω(x)} I_c(y)`（Ω 为半径 [DehazeConfig.patchRadius] 窗口）；
 * ② 大气光估计 A（三种方式，见 [AtmosphereMode]）；
 * ③ 透射率 `t = 1 - ω · min_{c} min_{y∈Ω} I_c(y)/A_c`；
 * ④ 用导向滤波（[BoxBlur.guidedFilter]，引导图取亮度）细化 t，得到边缘保持的 t；
 * ⑤ 复原 `J_c = (I_c - A_c) / max(t, t0) + A_c`，再按 [DehazeConfig.strength] 与原图混合。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object DehazeProcessor {

    /** 按配置对 ARGB_8888 像素去雾，返回新数组（不改入参）。 */
    fun process(pixels: IntArray, width: Int, height: Int, config: DehazeConfig): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = DehazeConfig.clamp(config)
        val out = IntArray(pixels.size)
        // 强度为 0 时严格恒等（管线中可当总开关用）
        if (clamped.strength <= 0f) {
            pixels.copyInto(out)
            return out
        }
        val image = PlanarRgb.fromArgb(pixels, width, height)
        val dark = darkChannel(image, width, height, clamped.patchRadius)
        val atmosphere = estimateAtmosphere(image, width, height, dark, clamped)
        val transmission = transmissionMap(image, width, height, atmosphere, clamped)

        val n = minOf(image.count, width * height)
        for (i in 0 until n) {
            val t = transmission[i].coerceAtLeast(clamped.minTransmission)
            for (c in 0 until 3) {
                val channel = image.channel(c)
                val a = atmosphere[c]
                val restored = if (t > 1e-6f) (channel[i] - a) / t + a else channel[i]
                channel[i] = PlanarRgb.mix(channel[i], restored.coerceIn(0f, 1f), clamped.strength)
            }
        }
        image.toArgb(out, pixels)
        return out
    }

    /**
     * 暗通道：先逐像素取 RGB 最小值，再做半径 [radius] 的最小值滤波（可分离、滑动窗口 O(1)/像素）。
     */
    internal fun darkChannel(image: PlanarRgb, width: Int, height: Int, radius: Int): FloatArray {
        val n = width * height
        val raw = FloatArray(n)
        val limit = minOf(n, image.count)
        for (i in 0 until limit) {
            raw[i] = minOf(image.r[i], image.g[i], image.b[i])
        }
        return if (radius <= 0) raw else minFilter(raw, width, height, radius)
    }

    /**
     * 大气光 A 估计。
     *
     * - [AtmosphereMode.TOP_DARKEST]（He 原文）：取暗通道最大的 top-(1 - percentile) 像素，
     *   在其中挑原图亮度和最大的那个像素，其 RGB 即 A；
     * - [AtmosphereMode.PERCENTILE]：直接在原图各通道取 [DehazeConfig.atmospherePercentile] 分位，抗孤立高光；
     * - [AtmosphereMode.FIXED]：三通道都取 [DehazeConfig.fixedAtmosphere]。
     *
     * 任何模式下 A 都被限制在 [0.4, 1.0]，避免除以过小的大气光造成整图过曝。
     */
    internal fun estimateAtmosphere(
        image: PlanarRgb,
        width: Int,
        height: Int,
        dark: FloatArray,
        config: DehazeConfig,
    ): FloatArray = when (config.atmosphereMode) {
        AtmosphereMode.FIXED -> FloatArray(3) { config.fixedAtmosphere.coerceIn(0f, 1f) }

        AtmosphereMode.PERCENTILE -> FloatArray(3) { c ->
            BoxBlur.percentile(image.channel(c), config.atmospherePercentile).coerceIn(0f, 1f)
        }

        AtmosphereMode.TOP_DARKEST -> {
            val n = minOf(width * height, image.count, dark.size)
            if (n <= 0) {
                FloatArray(3) { config.fixedAtmosphere.coerceIn(0f, 1f) }
            } else {
                val threshold = BoxBlur.percentile(dark, config.atmospherePercentile)
                var bestIndex = -1
                var bestBrightness = -1f
                for (i in 0 until n) {
                    if (dark[i] < threshold) continue
                    val brightness = image.r[i] + image.g[i] + image.b[i]
                    if (brightness > bestBrightness) {
                        bestBrightness = brightness
                        bestIndex = i
                    }
                }
                if (bestIndex < 0) {
                    FloatArray(3) { config.fixedAtmosphere.coerceIn(0f, 1f) }
                } else {
                    floatArrayOf(
                        image.r[bestIndex].coerceIn(0.4f, 1f),
                        image.g[bestIndex].coerceIn(0.4f, 1f),
                        image.b[bestIndex].coerceIn(0.4f, 1f),
                    )
                }
            }
        }
    }

    /**
     * 透射率 t：`1 - ω·min_c min_Ω (I_c / A_c)`，再用引导滤波细化，
     * 最后 clamp 到 `[minTransmission, 1]`。
     */
    internal fun transmissionMap(
        image: PlanarRgb,
        width: Int,
        height: Int,
        atmosphere: FloatArray,
        config: DehazeConfig,
    ): FloatArray {
        val n = width * height
        val normalizedDark = FloatArray(n)
        val limit = minOf(n, image.count)
        for (i in 0 until limit) {
            val nr = image.r[i] / safeAtmosphere(atmosphere[0])
            val ng = image.g[i] / safeAtmosphere(atmosphere[1])
            val nb = image.b[i] / safeAtmosphere(atmosphere[2])
            normalizedDark[i] = minOf(nr, ng, nb)
        }
        val minFiltered = if (config.patchRadius > 0) {
            minFilter(normalizedDark, width, height, config.patchRadius)
        } else {
            normalizedDark
        }
        val rawTransmission = FloatArray(n) {
            (1f - config.omega * minFiltered[it]).coerceIn(config.minTransmission, 1f)
        }
        if (config.refineRadius <= 0) return rawTransmission

        // 引导图用亮度（边缘与透射率跳变高度相关，故细化后仍保边）
        val guide = FloatArray(n) { image.luma(it) }
        val refined = BoxBlur.guidedFilter(
            guide = guide,
            input = rawTransmission,
            width = width,
            height = height,
            radius = config.refineRadius,
            epsilon = config.refineEpsilon,
        )
        for (i in 0 until n) {
            refined[i] = refined[i].coerceIn(config.minTransmission, 1f)
        }
        return refined
    }

    private fun safeAtmosphere(a: Float): Float = when {
        a.isNaN() -> 1f
        a < 0.05f -> 0.05f
        else -> a
    }

    /**
     * 可分离滑动窗口最小值滤波（单调双端队列，摊还 O(1)/像素），边界 clamp。
     * 纯 JVM 实现，便于单测。
     */
    internal fun minFilter(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0 || width <= 0 || height <= 0) return src.copyOf()
        val horizontal = FloatArray(src.size)
        val deque = ArrayDeque<Int>()
        for (y in 0 until height) {
            val base = y * width
            deque.clear()
            // 预置 [0, radius-1]：否则左边界前 radius 个像素的窗口会缺掉自身左侧的样本
            // （x=0 时窗口应为 clamp([-r, r]) = [0, r]，不预置就只剩 [1, r]，边界最小值会偏大）
            for (pre in 0 until radius.coerceAtMost(width)) {
                val preValue = src[base + pre]
                while (deque.isNotEmpty() && src[base + deque.last()] >= preValue) deque.removeLast()
                deque.addLast(pre)
            }
            for (x in 0 until width) {
                val addIndex = (x + radius).coerceAtMost(width - 1)
                val value = src[base + addIndex]
                while (deque.isNotEmpty() && src[base + deque.last()] >= value) deque.removeLast()
                deque.addLast(addIndex)
                val removeBefore = x - radius
                while (deque.isNotEmpty() && deque.first() < removeBefore) deque.removeFirst()
                horizontal[base + x] = src[base + deque.first()]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until width) {
            deque.clear()
            for (pre in 0 until radius.coerceAtMost(height)) {
                val preValue = horizontal[pre * width + x]
                while (deque.isNotEmpty() && horizontal[deque.last() * width + x] >= preValue) deque.removeLast()
                deque.addLast(pre)
            }
            for (y in 0 until height) {
                val addIndex = (y + radius).coerceAtMost(height - 1)
                val value = horizontal[addIndex * width + x]
                while (deque.isNotEmpty() && horizontal[deque.last() * width + x] >= value) deque.removeLast()
                deque.addLast(addIndex)
                val removeBefore = y - radius
                while (deque.isNotEmpty() && deque.first() < removeBefore) deque.removeFirst()
                out[y * width + x] = horizontal[deque.first() * width + x]
            }
        }
        return out
    }
}
