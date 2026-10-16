package com.hinnka.mycamera.superres

/**
 * 掩膜羽化的纯算法（不依赖 Android，可 JVM 单测）。
 *
 * 用**两趟 chamfer 距离变换**（棋盘距离，近似欧氏）求每个待补全像素到保留区的距离，
 * 再按 [featherPx] 归一化得到 0..1 的混合系数：
 *  - 远处（距离 >= featherPx）系数为 1，完全采用 AI 结果；
 *  - 边界处系数趋于 0，完全保留原图像素 → 消除硬边接缝；
 *  - 复杂度 O(w·h)，与羽化半径无关（不会因为半径 64 就慢 64 倍）。
 */
object SuperResMaskFeather {

    /** 距离变换中代表「无穷远」的大值（大于任何可能的图像边长即可）。 */
    private const val FAR = 1 shl 20

    /**
     * 判定每个像素是否属于「待补全区」。
     *
     * @param argb ARGB_8888 像素（长度需 >= width*height，短则按 0 处理）。
     * @param threshold 归一化阈值：alpha <= t 或亮度 <= t 视为待补全。
     */
    fun maskFromArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        threshold: Float,
    ): BooleanArray {
        val n = width.coerceAtLeast(0) * height.coerceAtLeast(0)
        val out = BooleanArray(n)
        if (n <= 0) return out
        val t = if (threshold.isFinite()) threshold.coerceIn(0f, 1f) else 0.5f
        for (i in 0 until n) {
            val c = if (i < argb.size) argb[i] else 0
            val a = ((c ushr 24) and 0xFF) / 255f
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            out[i] = a <= t || luma <= t
        }
        return out
    }

    /**
     * 计算羽化系数（0..1，长度 = width*height）。
     *
     * @param inside 待补全掩膜（true = 需要 AI 补全）。
     * @param featherPx 羽化半径（像素）；<=0 时退化为硬边（inside 恒为 1，outside 恒为 0）。
     */
    fun alphaFor(inside: BooleanArray, width: Int, height: Int, featherPx: Int): FloatArray {
        val n = width.coerceAtLeast(0) * height.coerceAtLeast(0)
        val alpha = FloatArray(n)
        if (n <= 0) return alpha
        if (featherPx <= 0) {
            for (i in 0 until n) alpha[i] = if (inside[i]) 1f else 0f
            return alpha
        }

        val dist = IntArray(n) { if (it < inside.size && inside[it]) FAR else 0 }

        // 正向趟：左上/上/右上/左。
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (y > 0) {
                    if (x > 0) best = minOf(best, dist[(y - 1) * width + x - 1] + 1)
                    best = minOf(best, dist[(y - 1) * width + x] + 1)
                    if (x + 1 < width) best = minOf(best, dist[(y - 1) * width + x + 1] + 1)
                }
                if (x > 0) best = minOf(best, dist[y * width + x - 1] + 1)
                dist[i] = best
            }
        }
        // 反向趟：右下/下/左下/右。
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val i = y * width + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (y + 1 < height) {
                    if (x > 0) best = minOf(best, dist[(y + 1) * width + x - 1] + 1)
                    best = minOf(best, dist[(y + 1) * width + x] + 1)
                    if (x + 1 < width) best = minOf(best, dist[(y + 1) * width + x + 1] + 1)
                }
                if (x + 1 < width) best = minOf(best, dist[y * width + x + 1] + 1)
                dist[i] = best
            }
        }

        val radius = featherPx.toFloat()
        for (i in 0 until n) {
            if (!inside[i]) {
                alpha[i] = 0f
                continue
            }
            val d = if (dist[i] >= FAR) radius else dist[i].toFloat()
            alpha[i] = (d / radius).coerceIn(0f, 1f)
        }
        return alpha
    }

    /** 便捷入口：ARGB 像素 → 羽化系数（内部串起 maskFromArgb + alphaFor）。 */
    fun alphaForArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        threshold: Float,
        featherPx: Int,
    ): FloatArray = alphaFor(maskFromArgb(argb, width, height, threshold), width, height, featherPx)
}
