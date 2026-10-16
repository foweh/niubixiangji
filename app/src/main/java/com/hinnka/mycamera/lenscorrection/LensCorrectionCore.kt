package com.hinnka.mycamera.lenscorrection

import kotlin.math.floor
import kotlin.math.min

/**
 * 镜头畸变校正的 CPU 重映射核心（纯 Kotlin，无 Android 依赖，可直接 JVM 单测）。
 *
 * 按 [DistortionRemapGrid] 的浮点坐标对源图做**双线性采样**，越界行为由
 * [DistortionEdgePolicy] 决定：`CLAMP` 复制边缘（无黑边）、`TRANSPARENT_BLACK` 输出透明。
 *
 * 支持行带分片（[bandHeight] + [onChunk]）：每个行带结束回调一次，集成层传
 * `{ job.checkYield() }` 即可让长任务响应暂停/取消并做 CPU 节流。
 */
object LensCorrectionCore {

    /**
     * 按给定重映射网格校正像素。
     *
     * @param source ARGB_8888 源像素（`srcWidth × srcHeight`）。
     * @param grid 重映射网格；其 `width/height` 决定**输出**尺寸。
     * @param bandHeight 行带高度；<= 0 表示不分片。
     * @param onChunk 每个行带结束回调（集成层传 `{ job.checkYield() }`）。
     */
    fun remap(
        source: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        grid: DistortionRemapGrid,
        policy: DistortionEdgePolicy = DistortionEdgePolicy.CLAMP,
        bandHeight: Int = 0,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        require(srcWidth > 0 && srcHeight > 0) { "source size must be positive" }
        require(source.size >= srcWidth * srcHeight) {
            "source buffer too small: ${source.size} < ${srcWidth * srcHeight}"
        }
        val out = IntArray(grid.count)
        val band = if (bandHeight <= 0) grid.height else bandHeight.coerceAtMost(grid.height)
        for (rowStart in 0 until grid.height step band) {
            val rowEnd = min(rowStart + band, grid.height)
            for (y in rowStart until rowEnd) {
                val base = y * grid.width
                for (x in 0 until grid.width) {
                    val index = base + x
                    out[index] = samplePixel(
                        source, srcWidth, srcHeight, grid.sourceX[index], grid.sourceY[index], policy,
                    )
                }
            }
            onChunk?.invoke()
        }
        return out
    }

    /** 便捷入口：按配置现建网格并校正（输出尺寸 = 源尺寸）。 */
    fun remapWithConfig(
        source: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        config: LensDistortionConfig,
        bandHeight: Int = 0,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        val clamped = LensDistortionConfig.clamp(config)
        val grid = DistortionRemapGrid(srcWidth, srcHeight)
        val band = if (bandHeight <= 0) srcHeight else bandHeight.coerceAtMost(srcHeight)
        // 网格构建也分片（K50G 超广角 8000×6000 时网格本身就是大缓冲）
        for (rowStart in 0 until srcHeight step band) {
            grid.fillRows(clamped, rowStart, min(rowStart + band, srcHeight))
            onChunk?.invoke()
        }
        return remap(source, srcWidth, srcHeight, grid, clamped.edgePolicy, bandHeight, onChunk)
    }

    /**
     * 双线性采样一个 ARGB 像素。
     * 越界判定用 ±1 像素的宽容度（双线性核跨越边界仍算「界内」），
     * `TRANSPARENT_BLACK` 下完全越界返回 `0`（全透明黑）。
     */
    internal fun samplePixel(
        source: IntArray,
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        policy: DistortionEdgePolicy,
    ): Int {
        if (!x.isFinite() || !y.isFinite()) return 0
        val outside = x < -1f || x > width.toFloat() || y < -1f || y > height.toFloat()
        if (outside && policy == DistortionEdgePolicy.TRANSPARENT_BLACK) return 0
        // 坐标约定（与 DistortionRemapGrid / GPU 采样表一致）：像素 k 覆盖 [k, k+1)，中心在 k+0.5。
        // 因此必须先减 0.5 再取整，否则恒等网格会整体偏半个像素并产生 50/50 混色（糊一层）。
        val cx = x - 0.5f
        val cy = y - 0.5f
        val x0Raw = floor(cx).toInt()
        val y0Raw = floor(cy).toInt()
        val fx = (cx - x0Raw).coerceIn(0f, 1f)
        val fy = (cy - y0Raw).coerceIn(0f, 1f)
        val x0 = x0Raw.coerceIn(0, width - 1)
        val y0 = y0Raw.coerceIn(0, height - 1)
        val x1 = (x0Raw + 1).coerceIn(0, width - 1)
        val y1 = (y0Raw + 1).coerceIn(0, height - 1)

        val p00 = source[y0 * width + x0]
        val p10 = source[y0 * width + x1]
        val p01 = source[y1 * width + x0]
        val p11 = source[y1 * width + x1]

        var packed = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val top = lerp(((p00 ushr shift) and 0xFF).toFloat(), ((p10 ushr shift) and 0xFF).toFloat(), fx)
            val bottom = lerp(((p01 ushr shift) and 0xFF).toFloat(), ((p11 ushr shift) and 0xFF).toFloat(), fx)
            val value = lerp(top, bottom, fy).toInt().coerceIn(0, 255)
            packed = packed or (value shl shift)
        }
        return packed
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
