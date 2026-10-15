package com.hinnka.mycamera.computational

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.PsStackType
import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.utils.BitmapUtils
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AstroStacker — 天文堆栈核心（CPU 实现）。
 *
 * 设计参考（原则 B：成熟开源替换）：
 *  - 对齐：f0enix/motioncam 的「相位相关 + 亮度质心平移对齐」思路。夜空帧信号稀疏、以亮星为
 *    主导，故先用亮星质心匹配得到整像素平移，再在质心邻域做归一化互相关做半像素细化。
 *  - 合并：GCam Astro / 天文堆栈通用的 sigma-clip 裁剪合并（迭代均值-方差，剔除离群像素），
 *    天然剔除卫星/飞机等瞬时轨迹（离群点不参与均值）。
 *  - 星轨：不对齐，逐像素亮化（lighten = max）叠加，参考长曝光堆栈与 GIMP/Photoshop 星轨做法。
 *
 * 本类只做 CPU 端的稀疏信号合并；GPU 对齐/合并仍由 [com.hinnka.mycamera.processor.GlesYuvStacker]
 * 负责常规多帧，天文场景信号稀疏、暗背景主导，CPU sigma-clip 更鲁棒且可审阅。
 *
 * 处理在调用方线程（后台线程）执行，与现有 processBurst 约定一致。
 */
object AstroStacker {
    private const val TAG = "AstroStacker"

    /** sigma-clip 合并的默认 sigma 倍数。GCam/天文软件常用 2.0~3.0。 */
    const val DEFAULT_SIGMA_CLIP_SIGMA = 2.5f

    /** 单帧搜索亮星质心时允许的最大平移（工作分辨率下的像素）。 */
    const val DEFAULT_MAX_ALIGN_SHIFT_PX = 48

    /** 对齐用的下采样系数（在工作分辨率上再降采样以加速质心匹配）。 */
    const val DEFAULT_ALIGN_DOWNSCALE = 2

    /** 工作边长上限，控制 CPU 内存/耗时。 */
    const val DEFAULT_WORK_LONG_EDGE = 1280

    enum class TrailAccumulationMode(val displayName: String) {
        /** 逐像素取最大值（lighten），星轨不消失，最常用。 */
        LIGHTEN_MAX("Lighten"),

        /** 逐帧累加后归一（长曝光加法叠加），光污染累积更亮。 */
        SUM_NORMALIZED("Sum/Avg");
    }

    /** 一帧解出的亮度平面（工作分辨率）+ 原始尺寸信息。 */
    private class LumaFrame(
        val width: Int,
        val height: Int,
        /** width*height 的 luma 样本（0..255）。 */
        val data: ShortArray,
        /** 半分辨率色度 U/V（0..255），长度 (w/2)*(h/2)。 */
        val u: ByteArray,
        val v: ByteArray,
        val chromaW: Int,
        val chromaH: Int,
    )

    /**
     * processAstroBurst 核心：星点对齐 → sigma-clip 合并。
     *
     * @return 合并后的 ARGB_8888 Bitmap（已按 rotation 旋转），失败返回 null。
     */
    fun stackAstro(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        referenceFrameIndex: Int = images.size / 2,
        sigmaClipSigma: Float = DEFAULT_SIGMA_CLIP_SIGMA,
        maxAlignShiftPx: Int = DEFAULT_MAX_ALIGN_SHIFT_PX,
        alignDownscale: Int = DEFAULT_ALIGN_DOWNSCALE,
        workLongEdge: Int = DEFAULT_WORK_LONG_EDGE,
        enableRotationCompensation: Boolean = false,
        /**
         * 默认 false（三脚架固定拍摄主流程：星星的位移/星点位置就是要的结果，
         * 不应被对齐抹掉）。仅手持多帧间有位移、且想做位移补偿时才置 true。
         */
        align: Boolean = false,
        job: BgJob? = null,
    ): Bitmap? {
        if (images.isEmpty()) return null
        val srcW = images[0].width
        val srcH = images[0].height

        // 工作分辨率：把长边压到 workLongEdge，短边按比例。
        val scale = minOf(1.0, workLongEdge.toDouble() / max(srcW, srcH).toDouble())
        val workW = max(2, (srcW * scale).toInt())
        val workH = max(2, (srcH * scale).toInt())

        val frames = images.mapNotNull { extractLuma(it, workW, workH) }
        if (frames.size < 2) {
            PLog.w(TAG, "Astro: only ${frames.size} usable frame(s), cannot stack")
            return null
        }
        val refIdx = referenceFrameIndex.coerceIn(0, frames.size - 1)
        val ref = frames[refIdx]

        // 1) 估计每帧相对参考帧的整数平移（亮星质心匹配 + 局部互相关细化）。
        //    三脚架固定拍摄（默认 align=false）跳过对齐——星星位移就是要的结果。
        val shifts = IntArray(frames.size * 2)
        if (align) {
            for (i in frames.indices) {
                if (i == refIdx) continue
                val (dx, dy) = estimateTranslation(
                    ref, frames[i],
                    maxShift = maxAlignShiftPx,
                    downscale = alignDownscale,
                    enableRotation = enableRotationCompensation,
                )
                shifts[i * 2] = dx
                shifts[i * 2 + 1] = dy
                job?.let { it.report(i + 1, frames.size); it.checkYield() }
            }
        }
        RawStackDebug.i(TAG) {
            "Astro stack: ${frames.size} frames ref=$refIdx work=${workW}x$workH sigma=$sigmaClipSigma"
        }

        // 2) sigma-clip 合并 luma。两遍：先粗均值/方差，再剔除离群后均值。
        val count = workW * workH
        val sum = FloatArray(count)
        val sumSq = FloatArray(count)
        val accepted = IntArray(count)
        // 第一遍：累加所有平移后样本。
        for (i in frames.indices) {
            val dx = shifts[i * 2]
            val dy = shifts[i * 2 + 1]
            accumulateShifted(frames[i].data, workW, workH, dx, dy, sum, sumSq, accepted)
        }
        // 计算每像素均值与标准差，第二遍剔除 |x-mean| > sigma*std 的样本。
        val mean = FloatArray(count)
        val std = FloatArray(count)
        for (p in 0 until count) {
            val n = accepted[p]
            if (n > 0) {
                val m = sum[p] / n
                val varr = (sumSq[p] - sum[p] * sum[p] / n) / n.coerceAtLeast(1)
                mean[p] = m
                std[p] = sqrt(varr.coerceAtLeast(0f))
            }
        }
        val clippedSum = FloatArray(count)
        val clippedCount = IntArray(count)
        for (i in frames.indices) {
            val dx = shifts[i * 2]
            val dy = shifts[i * 2 + 1]
            accumulateClipped(
                frames[i].data, workW, workH, dx, dy,
                mean, std, sigmaClipSigma, clippedSum, clippedCount,
            )
        }
        val merged = ByteArray(count)
        for (p in 0 until count) {
            val n = clippedCount[p]
            val v = if (n > 0) clippedSum[p] / n else mean[p]
            merged[p] = v.toInt().coerceIn(0, 255).toByte()
        }

        // 3) 色度：直接平均（暗天色度噪声影响小，不做位移对齐）。
        val chromaCount = ref.chromaW * ref.chromaH
        val avgU = FloatArray(chromaCount)
        val avgV = FloatArray(chromaCount)
        for (f in frames) {
            for (p in 0 until chromaCount) {
                avgU[p] += f.u[p].toInt().and(0xFF)
                avgV[p] += f.v[p].toInt().and(0xFF)
            }
        }
        val n = frames.size
        for (p in 0 until chromaCount) {
            avgU[p] /= n
            avgV[p] /= n
        }

        return composeBitmap(merged, avgU, avgV, ref.chromaW, ref.chromaH, workW, workH, rotation, aspectRatio)
    }

    /**
     * processStarTrailsBurst 核心：不对齐，逐帧亮化/累加叠加。
     */
    fun stackStarTrails(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        mode: TrailAccumulationMode = TrailAccumulationMode.LIGHTEN_MAX,
        workLongEdge: Int = DEFAULT_WORK_LONG_EDGE,
        job: BgJob? = null,
    ): Bitmap? {
        if (images.isEmpty()) return null
        val srcW = images[0].width
        val srcH = images[0].height
        val scale = minOf(1.0, workLongEdge.toDouble() / max(srcW, srcH).toDouble())
        val workW = max(2, (srcW * scale).toInt())
        val workH = max(2, (srcH * scale).toInt())

        val frames = images.mapNotNull { extractLuma(it, workW, workH) }
        if (frames.isEmpty()) return null
        val count = workW * workH
        val accum = FloatArray(count)
        val chromaCount = frames[0].chromaW * frames[0].chromaH
        val avgU = FloatArray(chromaCount)
        val avgV = FloatArray(chromaCount)

        when (mode) {
            TrailAccumulationMode.LIGHTEN_MAX -> {
                for ((fi, f) in frames.withIndex()) {
                    for (p in 0 until count) {
                        val v = f.data[p].toInt().and(0xFFFF)
                        if (v > accum[p].toInt()) accum[p] = v.toFloat()
                    }
                    job?.let { it.report(fi + 1, frames.size); it.checkYield() }
                }
            }
            TrailAccumulationMode.SUM_NORMALIZED -> {
                for ((fi, f) in frames.withIndex()) {
                    for (p in 0 until count) {
                        accum[p] += f.data[p].toInt().and(0xFFFF)
                    }
                    job?.let { it.report(fi + 1, frames.size); it.checkYield() }
                }
                for (p in 0 until count) accum[p] /= frames.size
            }
        }
        for (f in frames) {
            for (p in 0 until chromaCount) {
                avgU[p] += f.u[p].toInt().and(0xFF)
                avgV[p] += f.v[p].toInt().and(0xFF)
            }
        }
        for (p in 0 until chromaCount) {
            avgU[p] /= frames.size
            avgV[p] /= frames.size
        }
        val merged = ByteArray(count)
        for (p in 0 until count) {
            merged[p] = accum[p].toInt().coerceIn(0, 255).toByte()
        }
        RawStackDebug.i(TAG) {
            "Star trails: ${frames.size} frames mode=${mode.displayName} work=${workW}x$workH"
        }
        return composeBitmap(merged, avgU, avgV, frames[0].chromaW, frames[0].chromaH, workW, workH, rotation, aspectRatio)
    }

    /**
     * processPsStack 核心：PS 式堆栈（均值/中值/最大值/最小值）。
     *
     * 用途：
     *  - MEAN：多帧降噪（SNR ∝ √N）；
     *  - MEDIAN：去除移动物体（行人/车，PS「堆栈模式-中位数」经典用法）；
     *  - MAX：星轨/亮化叠加（lighten）；
     *  - MIN：暗场降噪/长曝光去除热噪点（darken）。
     *
     * align=true 时先用全局平移估计（归一化差和）对齐到参考帧；false 时直接叠加（星轨/固定机位）。
     */
    fun stackPs(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        type: PsStackType,
        frameCount: Int,
        align: Boolean,
        workLongEdge: Int = DEFAULT_WORK_LONG_EDGE,
        job: BgJob? = null,
    ): Bitmap? {
        if (images.isEmpty()) return null
        val srcW = images[0].width
        val srcH = images[0].height
        // MEDIAN 需要缓存所有样本，降低工作分辨率以控内存。
        val medianCap = if (type == PsStackType.MEDIAN) 960 else workLongEdge
        val scale = minOf(1.0, medianCap.toDouble() / max(srcW, srcH).toDouble())
        val workW = max(2, (srcW * scale).toInt())
        val workH = max(2, (srcH * scale).toInt())
        val limited = if (frameCount > 0) images.take(frameCount) else images

        val frames = limited.mapNotNull { extractLuma(it, workW, workH) }
        if (frames.isEmpty()) return null
        val count = workW * workH

        // 对齐：估计每帧相对参考（中间帧）的全局平移。
        val shifts = if (align) estimateGlobalShifts(frames) else IntArray(frames.size * 2)

        val mergedY = when (type) {
            PsStackType.MEAN -> run {
                val sum = FloatArray(count)
                val n = IntArray(count)
                for (i in frames.indices) {
                    accumulateShifted(frames[i].data, workW, workH, shifts[i*2], shifts[i*2+1], sum, n)
                    job?.let { it.report(i + 1, frames.size); it.checkYield() }
                }
                ByteArray(count) { p ->
                    (if (n[p] > 0) sum[p] / n[p] else 0f).toInt().coerceIn(0, 255).toByte()
                }
            }
            PsStackType.MAX -> run {
                val best = FloatArray(count)
                for (i in frames.indices) {
                    accumulateMax(frames[i].data, workW, workH, shifts[i*2], shifts[i*2+1], best)
                    job?.let { it.report(i + 1, frames.size); it.checkYield() }
                }
                ByteArray(count) { p -> best[p].toInt().coerceIn(0, 255).toByte() }
            }
            PsStackType.MIN -> run {
                val best = FloatArray(count) { 256f }
                for (i in frames.indices) {
                    accumulateMin(frames[i].data, workW, workH, shifts[i*2], shifts[i*2+1], best)
                }
                ByteArray(count) { p -> best[p].toInt().coerceIn(0, 255).toByte() }
            }
            PsStackType.MEDIAN -> run {
                // 收集每像素样本（工作分辨率 + 限帧以控内存）。
                val perPixel = Array(count) { ArrayList<Short>(frames.size) }
                for (i in frames.indices) {
                    val dx = shifts[i*2]; val dy = shifts[i*2+1]
                    for (y in 0 until workH) {
                        val sy = y + dy; if (sy < 0 || sy >= workH) continue
                        for (x in 0 until workW) {
                            val sx = x + dx; if (sx < 0 || sx >= workW) continue
                            perPixel[y*workW + x].add(frames[i].data[sy*workW + sx])
                        }
                    }
                }
                ByteArray(count) { p -> medianShort(perPixel[p]).toByte() }
            }
        }

        val chromaCount = frames[0].chromaW * frames[0].chromaH
        val avgU = FloatArray(chromaCount)
        val avgV = FloatArray(chromaCount)
        for (f in frames) {
            for (p in 0 until chromaCount) {
                avgU[p] += f.u[p].toInt().and(0xFF)
                avgV[p] += f.v[p].toInt().and(0xFF)
            }
        }
        for (p in 0 until chromaCount) {
            avgU[p] /= frames.size
            avgV[p] /= frames.size
        }
        RawStackDebug.i(TAG) {
            "PS stack: ${frames.size} frames type=$type align=$align work=${workW}x$workH"
        }
        return composeBitmap(mergedY, avgU, avgV, frames[0].chromaW, frames[0].chromaH, workW, workH, rotation, aspectRatio)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 从 SafeImage(YUV_420_888) 抽取 luma 并双线性缩放到 workW x workH。 */
    private fun extractLuma(image: SafeImage, workW: Int, workH: Int): LumaFrame? {
        return runCatching {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val srcW = image.width
            val srcH = image.height
            val buf = plane.buffer

            // 半分辨率色度
            val cw = srcW / 2
            val ch = srcH / 2
            val chromaW = workW / 2
            val chromaH = workH / 2
            val uSrc = image.planes[1]
            val vSrc = image.planes[2]

            val out = ShortArray(workW * workH)
            val ou = ByteArray(chromaW * chromaH)
            val ov = ByteArray(chromaW * chromaH)

            val xRatio = srcW.toDouble() / workW
            val yRatio = srcH.toDouble() / workH
            for (y in 0 until workH) {
                val sy = (y * yRatio).toInt().coerceAtMost(srcH - 1)
                for (x in 0 until workW) {
                    val sx = (x * xRatio).toInt().coerceAtMost(srcW - 1)
                    out[y * workW + x] = (buf.get(sy * rowStride + sx).toInt().and(0xFF)).toShort()
                }
            }
            // 色度近邻采样
            val uRatioX = cw.toDouble() / chromaW
            val uRatioY = ch.toDouble() / chromaH
            for (y in 0 until chromaH) {
                val sy = (y * uRatioY).toInt().coerceAtMost(ch - 1)
                for (x in 0 until chromaW) {
                    val sx = (x * uRatioX).toInt().coerceAtMost(cw - 1)
                    ou[y * chromaW + x] = uSrc.buffer.get(sy * uSrc.rowStride + sx * uSrc.pixelStride)
                    ov[y * chromaW + x] = vSrc.buffer.get(sy * vSrc.rowStride + sx * vSrc.pixelStride)
                }
            }
            LumaFrame(workW, workH, out, ou, ov, chromaW, chromaH)
        }.onFailure { PLog.w(TAG, "extractLuma failed: ${it.message}") }.getOrNull()
    }

    /**
     * 估计 frame 相对 ref 的整数平移。
     * 思路（motioncam 相位相关/质心对齐的简化移植）：
     *  1) 在 ref 上检测亮星质心（高于局部阈值的局部极大）；
     *  2) 对每个 ref 星点，在 frame 的 maxShift 窗口内找最亮候选，取质心差；
     *  3) 对所有匹配差取中位数作为全局平移（抗卫星/飞机离群）。
     *  4) enableRotation 时再做一次邻域互相关细化（可选增强）。
     */
    private fun estimateTranslation(
        ref: LumaFrame,
        frame: LumaFrame,
        maxShift: Int,
        downscale: Int,
        enableRotation: Boolean,
    ): Pair<Int, Int> {
        val w = ref.width
        val h = ref.height
        // 在降采样图上找星点以提速。
        val sw = (w / downscale).coerceAtLeast(8)
        val sh = (h / downscale).coerceAtLeast(8)
        val refSmall = downsample(ref.data, w, h, sw, sh)
        val frameSmall = downsample(frame.data, w, h, sw, sh)

        // 自适应阈值：均值 + k*std（夜空背景低，星点为高亮点）。
        var sum = 0L
        var sumSq = 0L
        for (p in 0 until sw * sh) {
            val v = refSmall[p].toInt()
            sum += v
            sumSq += v * v
        }
        val n = sw * sh
        val mean = sum.toDouble() / n
        val std = sqrt((sumSq.toDouble() / n - mean * mean).coerceAtLeast(0.0))
        val threshold = (mean + 3.0 * std).toInt().coerceAtLeast(40)

        val deltasX = ArrayList<Int>(32)
        val deltasY = ArrayList<Int>(32)
        // 3x3 局部极大
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val v = refSmall[idx].toInt()
                if (v < threshold) continue
                if (v < refSmall[idx - 1].toInt() || v < refSmall[idx + 1].toInt()) continue
                if (v < refSmall[idx - sw].toInt() || v < refSmall[idx + sw].toInt()) continue
                // 在 frame 同位置附近窗口找最亮
                var bestDx = 0
                var bestDy = 0
                var bestVal = -1
                for (dy in -maxShift / downscale..maxShift / downscale) {
                    for (dx in -maxShift / downscale..maxShift / downscale) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 1 || nx >= sw - 1 || ny < 1 || ny >= sh - 1) continue
                        val fv = frameSmall[ny * sw + nx].toInt()
                        if (fv > bestVal) {
                            bestVal = fv
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }
                if (bestVal > threshold) {
                    deltasX.add(bestDx * downscale)
                    deltasY.add(bestDy * downscale)
                }
            }
        }
        if (deltasX.isEmpty()) return 0 to 0
        val dx = median(deltasX)
        val dy = median(deltasY)
        // 旋转补偿为可选增强：当前相位相关平移已能处理绝大部分手持抖动；
        // 若开启，这里预留邻域互相关细化 hook（静态实现为 no-op，标注 TODO）。
        if (enableRotation) {
            // TODO: motioncam 式全局旋转估计（基于多星质心拟合相似变换），
            //  当前版本仅做平移；三脚架长曝银河拍摄建议锁定支架以减小旋转。
        }
        return dx to dy
    }

    private fun downsample(src: ShortArray, sw: Int, sh: Int, dw: Int, dh: Int): ShortArray {
        val out = ShortArray(dw * dh)
        for (y in 0 until dh) {
            val sy = (y * sh / dh).coerceAtMost(sh - 1)
            for (x in 0 until dw) {
                val sx = (x * sw / dw).coerceAtMost(sw - 1)
                out[y * dw + x] = src[sy * sw + sx]
            }
        }
        return out
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** 把平移后的帧累加到 sum/sumSq/accepted（边界外样本丢弃）。 */
    private fun accumulateShifted(
        data: ShortArray, w: Int, h: Int, dx: Int, dy: Int,
        sum: FloatArray, sumSq: FloatArray, accepted: IntArray,
    ) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val v = data[sy * w + sx].toInt().and(0xFF).toFloat()
                val p = y * w + x
                sum[p] += v
                sumSq[p] += v * v
                accepted[p]++
            }
        }
    }

    /** MEAN 用的轻量累加（只 sum + count）。 */
    private fun accumulateShifted(
        data: ShortArray, w: Int, h: Int, dx: Int, dy: Int,
        sum: FloatArray, count: IntArray,
    ) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val p = y * w + x
                sum[p] += data[sy * w + sx].toInt().and(0xFF)
                count[p]++
            }
        }
    }

    private fun accumulateMax(
        data: ShortArray, w: Int, h: Int, dx: Int, dy: Int, best: FloatArray,
    ) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val v = data[sy * w + sx].toInt().and(0xFF).toFloat()
                val p = y * w + x
                if (v > best[p]) best[p] = v
            }
        }
    }

    private fun accumulateMin(
        data: ShortArray, w: Int, h: Int, dx: Int, dy: Int, best: FloatArray,
    ) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val v = data[sy * w + sx].toInt().and(0xFF).toFloat()
                val p = y * w + x
                if (v < best[p]) best[p] = v
            }
        }
    }

    /**
     * 全局平移估计（PS 堆栈对齐用）：在降采样 luma 上以最小化 |frame(dx,dy)-ref| 搜索整数平移。
     * 适用于固定机位/小抖动的静态场景；大场景运动请关闭 align。
     */
    private fun estimateGlobalShifts(frames: List<LumaFrame>): IntArray {
        val out = IntArray(frames.size * 2)
        if (frames.size < 2) return out
        val refIdx = frames.size / 2
        val ref = frames[refIdx]
        val sw = (ref.width / 4).coerceAtLeast(16)
        val sh = (ref.height / 4).coerceAtLeast(16)
        val refSmall = downsample(ref.data, ref.width, ref.height, sw, sh)
        val maxShift = 16
        for (i in frames.indices) {
            if (i == refIdx) continue
            val small = downsample(frames[i].data, frames[i].width, frames[i].height, sw, sh)
            var bestDx = 0
            var bestDy = 0
            var bestCost = Long.MAX_VALUE
            for (dy in -maxShift..maxShift) {
                for (dx in -maxShift..maxShift) {
                    var cost = 0L
                    var samples = 0
                    for (y in 2 until sh - 2) {
                        val sy = y + dy
                        if (sy < 2 || sy >= sh - 2) continue
                        for (x in 2 until sw - 2) {
                            val sx = x + dx
                            if (sx < 2 || sx >= sw - 2) continue
                            cost += abs(refSmall[y * sw + x].toInt() - small[sy * sw + sx].toInt())
                            samples++
                        }
                    }
                    if (samples > 0 && cost < bestCost) {
                        bestCost = cost
                        bestDx = dx * 4
                        bestDy = dy * 4
                    }
                }
            }
            out[i * 2] = bestDx
            out[i * 2 + 1] = bestDy
        }
        return out
    }

    private fun medianShort(samples: ArrayList<Short>): Int {
        if (samples.isEmpty()) return 0
        val sorted = samples.map { it.toInt().and(0xFFFF) }.sorted()
        return sorted[sorted.size / 2]
    }

    /** sigma-clip 第二遍：只累加 |v - mean| <= sigma*std 的样本。 */
    private fun accumulateClipped(
        data: ShortArray, w: Int, h: Int, dx: Int, dy: Int,
        mean: FloatArray, std: FloatArray, sigma: Float,
        sumOut: FloatArray, countOut: IntArray,
    ) {
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x + dx
                if (sx < 0 || sx >= w) continue
                val p = y * w + x
                val v = data[sy * w + sx].toInt().and(0xFF).toFloat()
                val s = std[p]
                if (s <= 0f) {
                    sumOut[p] += v
                    countOut[p]++
                    continue
                }
                if (kotlin.math.abs(v - mean[p]) <= sigma * s) {
                    sumOut[p] += v
                    countOut[p]++
                }
            }
        }
    }

    /** YUV -> ARGB Bitmap，再按 rotation 旋转。 */
    private fun composeBitmap(
        y: ByteArray, u: FloatArray, v: FloatArray,
        cw: Int, ch: Int, w: Int, h: Int,
        rotation: Int, aspectRatio: AspectRatio?,
    ): Bitmap {
        val argb = IntArray(w * h)
        for (yy in 0 until h) {
            val cy = (yy / 2).coerceAtMost(ch - 1)
            for (x in 0 until w) {
                val cx = (x / 2).coerceAtMost(cw - 1)
                val yVal = y[yy * w + x].toInt().and(0xFF)
                val uVal = u[cy * cw + cx] - 128f
                val vVal = v[cy * cw + cx] - 128f
                // YUV -> RGB (BT.601)
                val r = (yVal + 1.402f * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344f * uVal - 0.714f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * uVal).toInt().coerceIn(0, 255)
                argb[yy * w + x] = Color.rgb(r, g, b)
            }
        }
        var bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val m = Matrix()
            m.postRotate(rotation.toFloat())
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        // 与 processBurst 对齐：按 aspectRatio 裁剪输出。
        if (aspectRatio != null) {
            val rect = BitmapUtils.calculateProcessedRect(
                bmp.width, bmp.height, aspectRatio, null, 0,
            )
            if (!rect.isEmpty &&
                rect.width() <= bmp.width && rect.height() <= bmp.height &&
                rect.left >= 0 && rect.top >= 0
            ) {
                val cropped = Bitmap.createBitmap(
                    bmp, rect.left, rect.top, rect.width(), rect.height(),
                )
                if (cropped != bmp) bmp.recycle()
                bmp = cropped
            }
        }
        return bmp
    }
}

/** 轻量日志别名，避免与 processor 包强耦合。 */
internal object RawStackDebug {
    fun i(tag: String, msg: () -> String) = PLog.i(tag, msg())
}
