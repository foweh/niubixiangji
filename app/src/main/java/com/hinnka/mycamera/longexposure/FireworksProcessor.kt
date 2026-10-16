package com.hinnka.mycamera.longexposure

/**
 * 烟火处理器（短曝光 burst 叠加，**纯 Kotlin 同步参考实现**）。
 *
 * 语义（[FireworksConfig.preBurstFrames] 的实际作用）：
 *  1. 前 N 帧（`preBurstFrames`）是**发射前基线段**：用 MEAN 叠出"天空/城市底色"，
 *     避免 MAX 把首帧里的随机噪声（热噪点）当成亮点永久固化；
 *  2. 基线建立后，其余帧走 [FireworksConfig.stackMode]（默认 MAX）叠加 ——
 *     烟花炸开的亮部只增不减，光轨与花型完整保留；
 *  3. `stackMode == MEAN` 时跳过基线（整段平均，用于"雾化烟花/柔和拖尾"风格）。
 *
 * 暗帧扣除与坏点处理复用 [DarkFrameSubtractor] / [LongExposureStacker]；
 * 帧数**不设上限**（流式累加）。
 */
object FireworksProcessor {

    /**
     * @param frames 按采集顺序排列的 burst 帧。
     * @param dark 平均暗帧（null = 不做暗帧扣除）。
     * @param onProgress 进度回调（已处理帧数, 总帧数）。
     * @return 叠加结果；无有效帧返回 null。
     */
    fun process(
        frames: List<LinearRgbPlane>,
        dark: ShortArray? = null,
        config: FireworksConfig = FireworksConfig.DEFAULT,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): LinearRgbPlane? {
        if (frames.isEmpty()) return null
        val clamped = config.clamped()
        val first = frames.first()
        val target = clamped.frameCount.coerceAtMost(frames.size)
        val expectedSamples = first.width * first.height * 3
        val effectiveDark = dark?.takeIf { it.size == expectedSamples }

        fun samplesOf(plane: LinearRgbPlane): ShortArray =
            if (plane.width == first.width && plane.height == first.height) {
                plane.samples
            } else {
                LongExposureResampler.downsample(plane.samples, plane.width, plane.height, first.width, first.height)
            }

        val baselineFrames = if (clamped.stackMode == LongExposureStackMode.MAX) {
            clamped.preBurstFrames.coerceIn(0, maxOf(0, target - 1))
        } else {
            0
        }

        var processed = 0
        val stacker: LongExposureStacker

        if (baselineFrames > 0) {
            // 1) 基线段：MEAN 叠出底色。
            val baselineStacker = LongExposureStacker(
                first.width,
                first.height,
                LongExposureStackMode.MEAN,
            )
            for (i in 0 until baselineFrames) {
                val samples = DarkFrameSubtractor.subtract(
                    frame = samplesOf(frames[i]),
                    dark = effectiveDark,
                    gain = clamped.darkFrame.subtractGain,
                    hotPixelThreshold = clamped.darkFrame.hotPixelThreshold,
                    hotPixelMode = clamped.darkFrame.hotPixelMode,
                )
                baselineStacker.addFrame(samples)
                processed++
                onProgress?.invoke(processed, target)
            }
            // 2) 用基线**播种** MAX 累加器（此后不再做暗帧减法之外的处理）。
            stacker = LongExposureStacker(first.width, first.height, LongExposureStackMode.MAX)
            stacker.addFrame(
                baselineStacker.snapshot(),
                dark = null,
                darkConfig = clamped.darkFrame.copy(enabled = false),
            )
        } else {
            stacker = LongExposureStacker(first.width, first.height, clamped.stackMode)
        }

        for (i in baselineFrames until target) {
            stacker.addFrame(samplesOf(frames[i]), effectiveDark, clamped.darkFrame)
            processed++
            onProgress?.invoke(processed, target)
        }
        return stacker.result(null)
    }
}
