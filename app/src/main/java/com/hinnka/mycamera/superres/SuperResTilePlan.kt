package com.hinnka.mycamera.superres

/** 一个待推理的 tile（原图坐标系，单位像素）。 */
data class SuperResTile(
    val index: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val isValid: Boolean get() = width > 0 && height > 0
}

/**
 * Tiled 推理的切分/拼缝纯算法（不依赖 Android，可 JVM 单测）。
 *
 * Real-ESRGAN 这类超分网络对整幅大图直接推理会爆内存（12MP×4 通道 float ≈ 768MB），
 * 因此按 tile 切分逐块推理，再用**边缘加权累加**把结果拼回去，消除 tile 边界接缝。
 *
 * 关键不变量：
 *  - [plan] 产出的 tile 并集必须**完整覆盖** width×height，且不越界；
 *  - 相邻 tile 起始位置去重，避免同一区域被算两次导致耗时不均；
 *  - overlap 越大拼缝越淡，但有效步长 `tileSize - overlap` 至少为 1，防止死循环。
 *
 * 已知且刻意的行为：当图像尺寸不是 `tileSize-overlap` 的整数倍时，最后一列/行的 tile 会
 * **回退贴边**（保证覆盖到边缘），因此与前一列/行形成一条宽度 ≤ tileSize 的重叠带。
 * 该重叠带由 [accumulate] 的加权平均消掉，不会出现接缝，只是多算了一小条（可接受）。
 */
object SuperResTilePlan {

    /** 单 tile 覆盖不下的极端输入（如 tileSize > 图宽高）时返回 1 个整图 tile。 */
    fun plan(width: Int, height: Int, tileSize: Int, overlap: Int): List<SuperResTile> {
        if (width <= 0 || height <= 0) return emptyList()
        val tile = tileSize.coerceAtLeast(1)
        // overlap 至少比 tileSize 小 1，保证步长 >= 1（否则永远原地打转）。
        val ov = overlap.coerceIn(0, (tile - 1).coerceAtLeast(0))
        val step = (tile - ov).coerceAtLeast(1)
        val xs = startPositions(width, tile, step)
        val ys = startPositions(height, tile, step)

        val tiles = ArrayList<SuperResTile>(xs.size * ys.size)
        var index = 0
        for (y in ys) {
            for (x in xs) {
                val w = tile.coerceAtMost(width - x)
                val h = tile.coerceAtMost(height - y)
                if (w <= 0 || h <= 0) continue
                tiles.add(SuperResTile(index = index++, x = x, y = y, width = w, height = h))
            }
        }
        return tiles
    }

    /** 用 [SuperResConfig] 直接切分（自动 clamp，避免调用方忘记修参数）。 */
    fun plan(config: SuperResConfig, width: Int, height: Int): List<SuperResTile> =
        plan(width, height, config.tileSize, config.tileOverlap)

    /**
     * 一维起始坐标序列：从 0 开始按 step 前进，最后一个坐标**回退贴边**（= extent - tile），
     * 保证覆盖到右/下边缘且不重复。
     */
    private fun startPositions(extent: Int, tile: Int, step: Int): List<Int> {
        if (tile >= extent) return listOf(0)
        val positions = ArrayList<Int>(extent / step + 2)
        var p = 0
        while (p + tile < extent) {
            if (positions.isEmpty() || positions.last() != p) positions.add(p)
            p += step
        }
        val last = extent - tile
        if (positions.isEmpty() || positions.last() != last) positions.add(last)
        return positions
    }

    /**
     * tile 内某点的拼缝权重（0..1）。
     *
     * 只在 tile **不贴图像边缘**的那一侧做线性淡出：贴边侧不淡化（否则图像外缘会变暗）。
     * 权重按轴相乘，因此四角权重最低，正好抹掉 tile 的四角硬边。
     *
     * @param localX/localY tile 内坐标（像素）。
     * @param touchesLeft/touchesTop 该 tile 是否贴住图像的左/上边缘。
     * @param touchesRight/touchesBottom 该 tile 是否贴住图像的右/下边缘。
     * @param seam 淡出宽度（像素），<=0 时不做淡化（权重恒为 1）。
     */
    fun seamWeight(
        localX: Int,
        localY: Int,
        tileWidth: Int,
        tileHeight: Int,
        seam: Int,
        touchesLeft: Boolean,
        touchesTop: Boolean,
        touchesRight: Boolean,
        touchesBottom: Boolean,
    ): Float {
        if (seam <= 0) return 1f
        val wx = axisWeight(localX, tileWidth, seam, touchesLeft, touchesRight)
        val wy = axisWeight(localY, tileHeight, seam, touchesTop, touchesBottom)
        return wx * wy
    }

    private fun axisWeight(
        local: Int,
        size: Int,
        seam: Int,
        touchesStart: Boolean,
        touchesEnd: Boolean,
    ): Float {
        if (size <= 0) return 1f
        val fromStart = if (touchesStart) Float.MAX_VALUE else local.toFloat()
        val fromEnd = if (touchesEnd) Float.MAX_VALUE else (size - 1 - local).toFloat()
        val d = minOf(fromStart, fromEnd)
        if (d <= 0f) return EPS
        // 边缘 0.5px 内给一个极小权重而不是 0，避免权重累加和为 0 时整块空白。
        return (d / seam).coerceAtMost(1f).coerceAtLeast(EPS)
    }

    private const val EPS = 1e-4f

    /**
     * 把一块 AI 输出（`tile` 区域，已按 scale 放大）按拼缝权重累加进目标缓冲。
     *
     * 纯函数：`accum`（RGB 累加，长度 = outW*outH*3）与 `weight`（权重累加，长度 = outW*outH）
     * 就地修改。调用方全部 tile 处理完后调用 [normalize]。
     *
     * @param src 该 tile 的 AI 输出像素，长度必须 >= tile.width*scale*tile.height*scale*3。
     */
    fun accumulate(
        accum: FloatArray,
        weight: FloatArray,
        src: FloatArray,
        tile: SuperResTile,
        scale: Int,
        outWidth: Int,
        outHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        seam: Int,
    ) {
        val s = scale.coerceAtLeast(1)
        val tw = tile.width * s
        val th = tile.height * s
        // 源缓冲不足以铺满该 tile 时整块跳过：宁可不叠加，也不要把半截数据混进结果。
        if (src.size < tw * th * 3) return
        val touchesLeft = tile.x == 0
        val touchesTop = tile.y == 0
        val touchesRight = tile.x + tile.width >= imageWidth
        val touchesBottom = tile.y + tile.height >= imageHeight

        for (ly in 0 until th) {
            val oy = tile.y * s + ly
            if (oy < 0 || oy >= outHeight) continue
            val sy = ly / s
            for (lx in 0 until tw) {
                val ox = tile.x * s + lx
                if (ox < 0 || ox >= outWidth) continue
                val sx = lx / s
                val w = seamWeight(sx, sy, tile.width, tile.height, seam, touchesLeft, touchesTop, touchesRight, touchesBottom)
                val srcBase = (ly * tw + lx) * 3
                val dstBase = (oy * outWidth + ox) * 3
                if (srcBase + 2 >= src.size || dstBase + 2 >= accum.size) continue
                accum[dstBase] += src[srcBase] * w
                accum[dstBase + 1] += src[srcBase + 1] * w
                accum[dstBase + 2] += src[srcBase + 2] * w
                weight[oy * outWidth + ox] += w
            }
        }
    }

    /**
     * 归一化累加缓冲 → 交错 RGB FloatArray（0..1），权重为 0 的像素（理论上不该有）
     * 落回 0 而不是 NaN。
     */
    fun normalize(accum: FloatArray, weight: FloatArray): FloatArray {
        val out = FloatArray(accum.size)
        var i = 0
        while (i + 2 < accum.size) {
            val widx = i / 3
            val w = if (widx < weight.size) weight[widx] else 0f
            if (w > EPS) {
                out[i] = accum[i] / w
                out[i + 1] = accum[i + 1] / w
                out[i + 2] = accum[i + 2] / w
            }
            i += 3
        }
        return out
    }

    /** 按 [strength] 把 AI 结果与原图线性混合（0 = 原图，1 = 全 AI）。 */
    fun blendWithStrength(ai: FloatArray, original: FloatArray, strength: Float): FloatArray {
        val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 1f
        if (s >= 1f) return ai
        if (s <= 0f) return original.copyOf()
        val n = minOf(ai.size, original.size)
        val out = FloatArray(ai.size)
        for (i in 0 until n) out[i] = original[i] * (1f - s) + ai[i] * s
        // 长度不一致时（异常输入）尾部保留 ai 原值，避免留 0 造成黑边。
        for (i in n until ai.size) out[i] = ai[i]
        return out
    }

    /** 单 tile 工作缓冲字节数估算（输入 + 输出 + 累加缓冲），用于 `job.guardMemory`。 */
    fun estimateTileBytes(tileSize: Int, scale: Int): Long {
        val s = scale.coerceAtLeast(1).toLong()
        val t = tileSize.coerceAtLeast(1).toLong()
        val inBytes = t * t * 3L * 4L
        val outBytes = t * s * t * s * 3L * 4L
        return inBytes + outBytes * 2L
    }

    /** 整幅累加缓冲字节数（RGB float + 权重 float），用于内存守卫。 */
    fun estimateAccumulatorBytes(width: Int, height: Int): Long {
        val px = width.toLong().coerceAtLeast(0L) * height.toLong().coerceAtLeast(0L)
        return px * (3L * 4L + 4L)
    }
}
