package com.hinnka.mycamera.focusstack

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 微距焦点堆叠处理器 —— 把「一叠不同焦点的帧」合成一张全焦图。
 *
 * 流水线（两遍扫描，常驻内存与总帧数无关）：
 * 1. **第一遍**：逐帧借出 → 降到工作分辨率 → 亮度 → 清晰度图（[FocusClarityMap] / native）
 *    → 稳健归一化 → 量化存 `ByteArray`（1B/帧/像素）；同时记录各帧清晰度均值以选出基准帧，
 *    并在开启对齐时逐帧估计相对参考帧的平移量。
 * 2. **掩模**：由量化清晰度生成软掩模（跨帧归一化 → 高斯平滑 → 邻域一致性滤波）。
 * 3. **第二遍**：逐帧借出 → （可选）按估计量平移 → 与（可上采样到全分辨率的）掩模加权累加；
 *    最后按 [FocusStackConfig.fusionStrength] 与基准帧混合输出 ARGB。
 *
 * 资源治理（PATTERN_SPEC §1.5 / §4）：
 * - **帧数不做总量上限**：循环数 = 用户配置帧数；
 * - 工作分辨率在 `guardMemory` 失败时**逐级减半降级**（不是失败）；
 * - 全分辨率输出累加器放不下时自动回退到「工作分辨率融合 + 结果上采样」，仍出图；
 * - 每个分片末尾 `job.checkYield()`，大缓冲分配前 `job.guardMemory()`。
 *
 * 纯逻辑入口 [process] 不依赖 Android（[BgJob] 可为 null），可直接 JVM 单测。
 */
object FocusStackProcessor {

    private const val TAG = "FocusStackProcessor"

    /** 输出累加器的下限保护：低于此分辨率不再降级（保证有输出）。 */
    private const val MIN_WORKING_SCALE = FocusStackConfig.WORKING_SCALE_MIN

    /** 每像素 4 字节（ARGB int）。 */
    private const val BYTES_PER_PIXEL = 4L

    /**
     * 执行一次焦点堆叠融合。
     *
     * @param source 帧源（全分辨率 ARGB）。
     * @param config 配置（内部 clamp；越界项记录日志）。
     * @param job 可选后台任务（提供 guardMemory / checkYield / report；为 null 时不做资源治理）。
     * @return 全焦 ARGB 像素（长度 = 输出宽×高）；帧数不足 / 尺寸非法时返回 null。
     */
    fun process(
        source: FocusStackFrameSource,
        config: FocusStackConfig,
        job: BgJob? = null,
    ): IntArray? {
        val (cfg, reports) = config.clampWithReport()
        if (reports.isNotEmpty()) {
            PLog.i(TAG, "focus stack config clamped: ${reports.joinToString()}")
        }
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            PLog.w(TAG, "invalid source size ${width}x$height")
            return null
        }
        val frames = minOf(cfg.frameCount, source.frameCount)
        if (frames < FocusStackConfig.FRAME_MIN) {
            PLog.w(TAG, "need >= ${FocusStackConfig.FRAME_MIN} frames, got $frames")
            return null
        }

        // ---- 工作分辨率 + 内存守卫降级 -------------------------------------------
        var scale = cfg.effectiveWorkingScale()
        var workWidth = scaledDim(width, scale)
        var workHeight = scaledDim(height, scale)
        while (job != null && scale > MIN_WORKING_SCALE) {
            val needed = maskBytes(frames, workWidth, workHeight) + planeBytes(workWidth, workHeight) * 3
            if (job.guardMemory(needed)) break
            val next = max(MIN_WORKING_SCALE, scale / 2f)
            if (next >= scale) break
            PLog.w(
                TAG,
                "guardMemory reject at scale=$scale (need=$needed), downgrade to $next",
            )
            scale = next
            workWidth = scaledDim(width, scale)
            workHeight = scaledDim(height, scale)
        }
        val workSize = workWidth * workHeight
        PLog.i(
            TAG,
            "focus stack start ${width}x$height x$frames frames, work=${workWidth}x$workHeight scale=$scale",
        )

        // ---- 第一遍：清晰度图（量化）+ 对齐位移 --------------------------------
        val quantized = ArrayList<ByteArray>(frames)
        val clarityMeans = ArrayList<Float>(frames)
        val shifts = IntArray(frames * 2)
        var referenceLuma: FloatArray? = null
        val referenceIndex = frames / 2
        var done = 0
        val total = frames * 2

        for (k in 0 until frames) {
            source.withFrame(k) { pixels ->
                val workPixels = FocusClarityMap.downsampleArgb(pixels, width, height, workWidth, workHeight)
                val luma = FocusClarityMap.lumaFromArgb(workPixels, workSize)

                val clarity = FocusStackNative.computeClarity(
                    luma = luma,
                    width = workWidth,
                    height = workHeight,
                    metric = cfg.clarityMetric,
                    radius = cfg.clarityRadius,
                ) ?: FocusClarityMap.computeClarity(
                    luma = luma,
                    width = workWidth,
                    height = workHeight,
                    metric = cfg.clarityMetric,
                    radius = cfg.clarityRadius,
                )

                val normalized = FocusClarityMap.normalize(clarity)
                quantized += FocusStackFusion.quantizeClarity(normalized)
                clarityMeans += FocusClarityMap.mean(normalized)

                if (cfg.alignEnabled) {
                    if (k == referenceIndex) {
                        referenceLuma = luma
                    } else {
                        referenceLuma?.let { ref ->
                            val shift = FocusStackFusion.estimateShift(
                                ref = ref,
                                tgt = luma,
                                width = workWidth,
                                height = workHeight,
                                maxShift = cfg.alignMaxShiftPx,
                            )
                            shifts[k * 2] = shift[0]
                            shifts[k * 2 + 1] = shift[1]
                        }
                    }
                }
            }
            done++
            job?.report(done, total)
            job?.checkYield()
        }

        // ---- 软掩模 -------------------------------------------------------------
        val clarities = ArrayList<FloatArray>(frames)
        for (q in quantized) clarities += FocusStackFusion.dequantizeClarity(q, workSize)
        val masks = FocusStackNative.softMask(
            clarities = clarities,
            width = workWidth,
            height = workHeight,
            exponent = cfg.clarityExponent,
            smoothRadius = cfg.maskSmoothRadius,
            consistency = cfg.maskConsistency,
        ) ?: FocusClarityMap.softMask(
            clarities = clarities,
            width = workWidth,
            height = workHeight,
            exponent = cfg.clarityExponent,
            smoothRadius = cfg.maskSmoothRadius,
            consistency = cfg.maskConsistency,
        )

        // 基准帧：清晰度均值最高者（融合强度<1 时与之混合）。
        var baseIndex = 0
        var bestMean = Float.NEGATIVE_INFINITY
        for (i in clarityMeans.indices) {
            if (clarityMeans[i] > bestMean) { bestMean = clarityMeans[i]; baseIndex = i }
        }

        // ---- 输出分辨率决策 -----------------------------------------------------
        var outputWidth = width
        var outputHeight = height
        var fullResolution = cfg.outputFullResolution && (workWidth != width || workHeight != height)
        if (fullResolution && job != null) {
            val accBytes = outputWidth.toLong() * outputHeight.toLong() * 3L * BYTES_PER_PIXEL +
                width.toLong() * height.toLong() * BYTES_PER_PIXEL
            if (!job.guardMemory(accBytes)) {
                PLog.w(
                    TAG,
                    "full-resolution accumulator rejected (need=$accBytes), fuse at working resolution",
                )
                fullResolution = false
            }
        }
        if (!fullResolution) {
            outputWidth = workWidth
            outputHeight = workHeight
        }
        val outputSize = outputWidth * outputHeight
        val outputScale = if (fullResolution) {
            outputWidth.toFloat() / workWidth.toFloat()
        } else {
            1f
        }

        // ---- 第二遍：平移 + 流式加权累加 ----------------------------------------
        val acc = FloatArray(outputSize * 3)
        var basePixels: IntArray? = null
        for (k in 0 until frames) {
            source.withFrame(k) { pixelsRaw ->
                var pixels = pixelsRaw
                if (cfg.alignEnabled && k != referenceIndex) {
                    val dx = (shifts[k * 2] * outputScale).roundToInt()
                    val dy = (shifts[k * 2 + 1] * outputScale).roundToInt()
                    if (dx != 0 || dy != 0) {
                        val shiftWidth = if (fullResolution) width else workWidth
                        val shiftHeight = if (fullResolution) height else workHeight
                        if (fullResolution) {
                            pixels = FocusStackFusion.shiftArgb(pixelsRaw, shiftWidth, shiftHeight, dx, dy)
                        } else {
                            val down = FocusClarityMap.downsampleArgb(
                                pixelsRaw, width, height, workWidth, workHeight,
                            )
                            pixels = FocusStackFusion.shiftArgb(down, shiftWidth, shiftHeight, dx, dy)
                        }
                    } else if (!fullResolution) {
                        pixels = FocusClarityMap.downsampleArgb(
                            pixelsRaw, width, height, workWidth, workHeight,
                        )
                    }
                } else if (!fullResolution) {
                    pixels = FocusClarityMap.downsampleArgb(
                        pixelsRaw, width, height, workWidth, workHeight,
                    )
                }

                val mask = if (fullResolution) {
                    FocusClarityMap.upsampleFloat(
                        masks[k], workWidth, workHeight, outputWidth, outputHeight,
                    )
                } else {
                    masks[k]
                }
                FocusStackFusion.accumulateWeighted(acc, mask, pixels, outputSize)
                if (k == baseIndex) basePixels = pixels.copyOf()
            }
            done++
            job?.report(done, total)
            job?.checkYield()
        }

        // ---- 落像素 -------------------------------------------------------------
        val fused = FocusStackFusion.finalizeArgb(
            acc = acc,
            count = outputSize,
            strength = cfg.fusionStrength,
            basePixels = basePixels,
        )

        // 非全分辨率输出时，把结果上采样回全分辨率（保证「输出全分辨率」语义）。
        val result = if (!fullResolution && cfg.outputFullResolution) {
            upscaleToFullResolution(fused, outputWidth, outputHeight, width, height)
        } else {
            fused
        }
        PLog.i(TAG, "focus stack done ${outputWidth}x$outputHeight -> ${width}x$height")
        return result
    }

    /** 结果上采样（最近邻整数放大；掩模级融合结果在放大后与原分辨率观感一致）。 */
    private fun upscaleToFullResolution(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): IntArray {
        if (srcWidth <= 0 || srcHeight <= 0) return src
        if (srcWidth == dstWidth && srcHeight == dstHeight) return src
        val out = IntArray(dstWidth * dstHeight)
        for (y in 0 until dstHeight) {
            val sy = (y.toLong() * srcHeight / dstHeight).toInt().coerceIn(0, srcHeight - 1)
            for (x in 0 until dstWidth) {
                val sx = (x.toLong() * srcWidth / dstWidth).toInt().coerceIn(0, srcWidth - 1)
                out[y * dstWidth + x] = src[sy * srcWidth + sx]
            }
        }
        return out
    }

    /** 掩模常驻字节：1B/像素/帧。 */
    private fun maskBytes(frames: Int, width: Int, height: Int): Long =
        frames.toLong() * width.toLong() * height.toLong()

    /** 单个工作分辨率浮点平面字节数。 */
    private fun planeBytes(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * BYTES_PER_PIXEL

    /** 缩放尺寸（至少 1）。 */
    fun scaledDim(edge: Int, scale: Float): Int {
        if (edge <= 0) return 1
        val s = if (scale.isFinite()) scale.coerceIn(MIN_WORKING_SCALE, 1f) else 1f
        return max(1, (edge * s).roundToInt())
    }
}
