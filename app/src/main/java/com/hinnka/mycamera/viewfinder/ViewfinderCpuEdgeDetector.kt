package com.hinnka.mycamera.viewfinder

import kotlin.math.sqrt

/**
 * 峰值对焦的 **CPU 兜底 / 大图离线边缘检测**（纯 Kotlin，可 JVM 单测）。
 *
 * 用途：
 * 1. GL 不可用（着色器编译失败、上下文丢失）时的降级路径；
 * 2. **大图**（如已拍照片回看）的边缘检测——这是本模块唯一的**重计算**任务，
 *    必须走 [ViewfinderBgTasks]（BgJobRunner 后台队列 + 分片 + guardMemory + checkYield），
 *    严禁在 GL/UI 线程整图跑。
 *
 * 算法：3×3 Sobel（Rec.709 亮度），与 GLSL 侧同阶；不做高斯预平滑（由调用方按需降工作分辨率）。
 */
object ViewfinderCpuEdgeDetector {

    /** 单次分配的安全上限参考：超过则应由调用方降工作分辨率。 */
    const val MAX_SUGGESTED_PIXELS = 4_000_000

    /**
     * 计算边缘强度图（0f..1f，Sobel 梯度归一化到 0..1）。
     *
     * @param pixels ARGB_8888 像素（行优先）
     * @param width / height 图像尺寸
     * @param stepScale 采样步长倍数（>=1），1 = 逐像素
     * @return 边缘强度数组（长度 = width*height，越界处按边缘像素处理），输入非法时返回空数组
     */
    fun edgeMagnitude(
        pixels: IntArray,
        width: Int,
        height: Int,
        stepScale: Int = 1,
    ): FloatArray {
        if (width <= 2 || height <= 2 || pixels.isEmpty()) return FloatArray(0)
        val usable = minOf(pixels.size.toLong(), width.toLong() * height.toLong()).toInt()
        if (usable < width * height) return FloatArray(0)

        val step = stepScale.coerceAtLeast(1)
        val output = FloatArray(width * height)
        val luma = IntArray(width * height)
        for (index in 0 until width * height) {
            val pixel = pixels[index]
            luma[index] = ZebraRules.lumaOf(
                (pixel shr 16) and 0xFF,
                (pixel shr 8) and 0xFF,
                pixel and 0xFF,
            )
        }

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val x0 = (x - step).coerceAtLeast(0)
                val x2 = (x + step).coerceAtMost(width - 1)
                val y0 = (y - step).coerceAtLeast(0)
                val y2 = (y + step).coerceAtMost(height - 1)

                val l00 = luma[y0 * width + x0]
                val l10 = luma[y0 * width + x]
                val l20 = luma[y0 * width + x2]
                val l01 = luma[y * width + x0]
                val l21 = luma[y * width + x2]
                val l02 = luma[y2 * width + x0]
                val l12 = luma[y2 * width + x]
                val l22 = luma[y2 * width + x2]

                val gx = l00 + 2 * l01 + l02 - l20 - 2 * l21 - l22
                val gy = l00 + 2 * l10 + l20 - l02 - 2 * l12 - l22
                // 最大可能梯度约 1020（4 * 255），除以它归一到 0..1。
                val magnitude = sqrt((gx * gx + gy * gy).toFloat()) / 1020f
                output[y * width + x] = magnitude.coerceIn(0f, 1f)
                x += step
            }
            y += step
        }
        return output
    }

    /**
     * 按边缘阈值生成峰值掩码（true = 该像素应被点亮），阈值语义与 GLSL 的 `uThreshold` 一致。
     */
    fun peakingMask(
        edgeMagnitude: FloatArray,
        threshold: Float,
    ): BooleanArray {
        val safeThreshold = threshold.coerceIn(
            ViewfinderParamRanges.PEAKING_THRESHOLD_MIN,
            ViewfinderParamRanges.PEAKING_THRESHOLD_MAX,
        )
        return BooleanArray(edgeMagnitude.size) { index -> edgeMagnitude[index] >= safeThreshold }
    }

    /** 统计覆盖率（0f..1f），可用于自适应灵敏度自检。 */
    fun coverageRatio(mask: BooleanArray): Float {
        if (mask.isEmpty()) return 0f
        var hit = 0
        for (value in mask) if (value) hit++
        return hit.toFloat() / mask.size.toFloat()
    }
}
