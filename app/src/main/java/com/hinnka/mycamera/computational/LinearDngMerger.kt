package com.hinnka.mycamera.computational

import com.hinnka.mycamera.processor.RawStackFrame
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * LinearDngMerger — 多帧计算 RAW 合并（轻量 Kotlin 实现，原则 B/D）。
 *
 * 参考与移植：
 *  - jcelaya/hdrmerge（star~433，仅参考思路）：多曝光 RAW 按曝光时间归一化后，在浮点域
 *    平均/加权合并，输出线性浮点 DNG（不锁色调映射）。
 *  - f0enix/motioncam（star~77，仅参考设计思路，不直接采用）：多帧 RAW 对齐后做加权平均
 *    降噪；单张欠曝（短曝光）帧用于恢复高光。
 *  - GCam HDR+（Hasinoff 2016，经 ISP-pipeline-hdrplus 参考）：burst 短欠曝帧合并。
 *
 * 本类是 [com.hinnka.mycamera.processor.MultiFrameStacker.processBurstRaw]（GPU MGC
 * Wiener/Sabre 合并）的轻量 CPU 替代/补强路径：
 *  - 逐帧按 exposureProduct 归一化到线性域；
 *  - sigma-clip 离群剔除（去移动物体/卫星）；
 *  - 高光恢复 hook：若某像素在长曝帧 clipped（达到 whiteLevel），改用未 clipped 的短曝帧贡献。
 *
 * 输出 16-bit 线性 CFA 缓冲（little-endian），交 [com.hinnka.mycamera.utils.SuperResolutionDngWriter]
 * 以 CFA + UNCOMPRESSED/JPEG_LOSSLESS、无 tone curve 的方式写 linear DNG（对齐三星 Expert RAW）。
 */
object LinearDngMerger {
    private const val TAG = "LinearDngMerger"

    data class LinearDngResult(
        /** 合并后的 16-bit little-endian CFA 样本，长度 width*height。 */
        val cfaBuffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val mergedFrameCount: Int,
        /** 高光恢复被激活的像素数（诊断用）。 */
        val highlightRecoveredPixels: Long,
    )

    /**
     * 合并一组 RAW burst 帧为单张线性 16-bit CFA。
     *
     * 注意：当前为静态代码级实现，按帧顺序累加（未做 Bayer 域亚像素对齐——对齐 hook 预留，
     * 实际对齐由上游 [com.hinnka.mycamera.processor.RawStackFrame.preAlignmentToReference]
     * 或 GPU 路径完成）。本函数假设帧已粗对齐或固定机位。
     *
     * @param frames RAW burst（同尺寸、同 CFA）。
     * @param whiteLevel 饱和白电平（用于高光恢复/clipping 检测）。
     * @param sigmaClipSigma 离群剔除 sigma（<=0 关闭）。
     */
    fun merge(
        frames: List<RawStackFrame>,
        whiteLevel: Int,
        sigmaClipSigma: Float = 2.0f,
    ): LinearDngResult? {
        if (frames.isEmpty()) return null
        val first = frames[0].image
        val w = first.width
        val h = first.height
        val count = w * h

        // 每帧归一化后的贡献缓存（16-bit -> float）。为控内存，逐帧流式累加：
        // sum / sumSq / accepted 三通道（按 Bayer 位置分别统计以保留 CFA 结构）。
        val sum = LongArray(count)
        val sumSq = DoubleArray(count)
        val accepted = IntArray(count)
        var highlightRecovered = 0L

        // 归一化因子 = 1 / exposureProduct（hdrmerge 做法：按曝光乘积归一到相同辐照度）。
        val norms = frames.map { f ->
            val ep = f.exposureProduct.takeIf { it > 0.0 } ?: 1.0
            1.0 / ep
        }
        // 用参考帧（首帧）归一化基准：把每帧缩放到与参考帧同曝光量级，再乘回参考曝光乘积。
        val refNorm = norms[0]

        for ((fi, frame) in frames.withIndex()) {
            val plane = frame.image.planes[0]
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val buf = plane.buffer
            val scale = (norms[fi] / refNorm)
            for (y in 0 until h) {
                val rowBase = y * rowStride
                for (x in 0 until w) {
                    val p = y * w + x
                    // RAW16 样本按 pixelStride 读 2 字节小端。
                    val raw = buf.getShort(rowBase + x * pixStride).toInt().and(0xFFFF)
                    var v = raw * scale
                    // 高光恢复：长曝帧此点 clipped，则该帧该点不计入（由未 clipped 的短曝帧提供）。
                    val clipped = raw >= whiteLevel
                    if (clipped) {
                        highlightRecovered++
                        continue
                    }
                    sum[p] += v.toLong()
                    sumSq[p] += v * v
                    accepted[p]++
                }
            }
        }

        // sigma-clip 第二遍：剔除偏离均值过远的样本（移动物体/卫星）。
        val mean = FloatArray(count)
        val std = FloatArray(count)
        for (p in 0 until count) {
            val n = accepted[p]
            if (n > 0) {
                val m = sum[p].toFloat() / n
                val varr = (sumSq[p] - sum[p] * sum[p] / n) / n
                mean[p] = m
                std[p] = sqrt(varr.toFloat().coerceAtLeast(0f))
            }
        }
        val finalSum = FloatArray(count)
        val finalCount = IntArray(count)
        for ((fi, frame) in frames.withIndex()) {
            val plane = frame.image.planes[0]
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val buf = plane.buffer
            val scale = (norms[fi] / refNorm)
            for (y in 0 until h) {
                val rowBase = y * rowStride
                for (x in 0 until w) {
                    val p = y * w + x
                    val raw = buf.getShort(rowBase + x * pixStride).toInt().and(0xFFFF)
                    if (raw >= whiteLevel) continue
                    val v = raw * scale
                    val s = std[p]
                    if (sigmaClipSigma <= 0f || s <= 0f ||
                        kotlin.math.abs(v - mean[p]) <= sigmaClipSigma * s
                    ) {
                        finalSum[p] += v.toFloat()
                        finalCount[p]++
                    }
                }
            }
        }

        val out = ByteArray(count * 2)
        var o = 0
        for (p in 0 until count) {
            val n = finalCount[p]
            val v = if (n > 0) finalSum[p] / n else mean[p]
            val clamped = v.toInt().coerceIn(0, 65535)
            out[o++] = (clamped and 0xFF).toByte()
            out[o++] = ((clamped shr 8) and 0xFF).toByte()
        }
        PLog.i(TAG, "Linear DNG merge: ${frames.size} frames -> ${w}x$h recovered=$highlightRecovered")
        return LinearDngResult(
            cfaBuffer = ByteBuffer.wrap(out),
            width = w,
            height = h,
            mergedFrameCount = frames.size,
            highlightRecoveredPixels = highlightRecovered,
        )
    }
}
