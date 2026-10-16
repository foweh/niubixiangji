package com.hinnka.mycamera.longexposure

/**
 * 光绘处理器（**同步参考实现 / 纯 Kotlin**）。
 *
 * 职责：`暗帧扣除 → 增量 MAX（或 MEAN）叠加 → 光源画笔色温`。
 * 与 [LightPaintingSession] 的区别：本类面向"已拍完的一批帧"，一次调用跑完，无预览线程；
 * 与 [LongExposureJobRunner] 的区别：本类不依赖 `BgJobRunner`（可在 JVM 单测直接跑，
 * 也适合帧数较少、无需后台队列的场景）。
 *
 * 重计算主循环走 [LongExposureStacker]（内部优先 C++，不可用自动回退 Kotlin）；
 * 帧数**不设上限**，反正是 O(1) 增量的流式累加。
 */
object LightPaintingProcessor {

    /**
     * @param frames 待叠加帧（**必须分辨率一致**；不一致的帧按工作分辨率盒平均重采样）。
     * @param dark 平均暗帧（长度 = 工作样本数；null = 不做暗帧扣除）。
     * @param onProgress 进度回调（已处理帧数, 总帧数）；用于 UI 或测试观测。
     * @return 叠加结果；无有效帧返回 null。
     */
    fun process(
        frames: List<LinearRgbPlane>,
        dark: ShortArray? = null,
        config: LightPaintingConfig = LightPaintingConfig.DEFAULT,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): LinearRgbPlane? {
        if (frames.isEmpty()) return null
        val clamped = config.clamped()
        val first = frames.first()
        val stacker = LongExposureStacker(first.width, first.height, clamped.stackMode)
        val expected = stacker.expectedSampleSize
        val effectiveDark = dark?.takeIf { it.size == expected }
        val target = clamped.frameCount.coerceAtMost(frames.size)

        var processed = 0
        for (index in 0 until target) {
            val plane = frames[index]
            val samples = if (plane.width == first.width && plane.height == first.height) {
                plane.samples
            } else {
                LongExposureResampler.downsample(plane.samples, plane.width, plane.height, first.width, first.height)
            }
            stacker.addFrame(samples, effectiveDark, clamped.darkFrame)
            processed++
            onProgress?.invoke(processed, target)
        }
        return stacker.result(colorGains(clamped))
    }

    /** 光源画笔色偏增益（含 [LightPaintingConfig.brushGain]）；中性配置返回 null（跳过整幅遍历）。 */
    fun colorGains(config: LightPaintingConfig): FloatArray? {
        val clamped = config.clamped()
        if (ColorTemperatureGains.isNeutral(
                clamped.brushColorTemperatureK,
                clamped.brushTint,
                clamped.brushGain,
            )
        ) {
            return null
        }
        val gains = ColorTemperatureGains.gainsFor(clamped.brushColorTemperatureK, clamped.brushTint)
        if (clamped.brushGain == 1f) return gains
        for (i in gains.indices) gains[i] = (gains[i] * clamped.brushGain).coerceIn(0f, 8f)
        return gains
    }
}
