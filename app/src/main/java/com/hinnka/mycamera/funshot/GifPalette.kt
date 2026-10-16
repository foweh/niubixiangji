package com.hinnka.mycamera.funshot

import kotlin.math.abs

/**
 * GIF 调色板量化（中位切分 median-cut + 15bit 缓存查表）。
 *
 * 自研说明见 `delivery/changelog_drafts/funshot.md`：
 * 现存 Android GIF 库中 nbadal/android-gif-encoder **无 license 声明**（不可直采），
 * natario1/GIFCompressor 仅 126★ 且方向为 GIF→MP4（不适用），故量化与 LZW 均为自研。
 */
object FunGifPalette {

    /** GIF 调色板最大项数（GIF89a 上限）。 */
    const val MAX_COLORS = 256

    /** 5bit/通道直方图桶数（32×32×32）。 */
    private const val HIST_SIZE = 32 * 32 * 32

    /**
     * 中位切分量化。
     * @param argb 采样像素（建议跨帧抽样，帧数多时抽稀以控耗时）
     * @param maxColors 目标颜色数（2..256）
     * @return 调色板（长度 1..maxColors，未凑满保持实际数量）
     */
    fun medianCut(argb: IntArray, maxColors: Int): IntArray {
        val target = maxColors.coerceIn(2, MAX_COLORS)
        if (argb.isEmpty()) return intArrayOf(0xFF000000.toInt())

        val hist = IntArray(HIST_SIZE)
        for (color in argb) {
            hist[binKey(color)]++
        }
        val bins = ArrayList<Bin>(256)
        for (i in 0 until HIST_SIZE) {
            val count = hist[i]
            if (count == 0) continue
            val r = ((i shr 10) and 0x1F) shl 3
            val g = ((i shr 5) and 0x1F) shl 3
            val b = (i and 0x1F) shl 3
            bins += Bin(r or 4, g or 4, b or 4, count)
        }
        if (bins.isEmpty()) return intArrayOf(0xFF000000.toInt())

        var boxes = listOf(Box(bins.indices.toList()))
        while (boxes.size < target) {
            val candidate = boxes.withIndex()
                .filter { it.value.indices.size > 1 }
                .maxByOrNull { it.value.totalCount(bins) }
                ?: break
            val channel = candidate.value.maxRangeChannel(bins)
            if (channel < 0) break
            val split = candidate.value.split(bins, channel) ?: break
            boxes = boxes.toMutableList().apply {
                this[candidate.index] = split.first
                add(split.second)
            }
        }

        val nonEmpty = boxes.filter { it.indices.isNotEmpty() }
        if (nonEmpty.isEmpty()) return intArrayOf(0xFF000000.toInt())
        return IntArray(nonEmpty.size) { nonEmpty[it].average(bins) }
    }

    /** 最近邻颜色索引（RGB 欧氏距离平方；调色板为空返回 0）。 */
    fun nearestIndex(palette: IntArray, argb: Int): Int {
        if (palette.isEmpty()) return 0
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in palette.indices) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dr = r - pr
            val dg = g - pg
            val db = b - pb
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
                if (distance == 0) break
            }
        }
        return best
    }

    /** palette 补齐到 2 的幂（GIF 的 GCT/LCT 必须为 2 的幂，最小 2）。 */
    fun padToPowerOfTwo(palette: IntArray): IntArray {
        var size = 2
        while (size < palette.size) size = size shl 1
        size = size.coerceAtMost(MAX_COLORS)
        if (size == palette.size) return palette
        val padded = IntArray(size)
        System.arraycopy(palette, 0, padded, 0, palette.size)
        // 空位用最后一项填充，避免出现黑色条纹
        for (i in palette.size until size) padded[i] = palette.last()
        return padded
    }

    /** GCT/LCT 需要 2 的幂的位宽（2 → 1，256 → 8）。 */
    fun sizeBits(size: Int): Int {
        var bits = 1
        var capacity = 2
        while (capacity < size && bits < 8) {
            capacity = capacity shl 1
            bits++
        }
        return bits.coerceIn(1, 8)
    }

    private fun binKey(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) shr 3
        val g = ((argb shr 8) and 0xFF) shr 3
        val b = (argb and 0xFF) shr 3
        return (r shl 10) or (g shl 5) or b
    }

    /** 直方图桶（5bit 桶中心色 + 计数）。 */
    private class Bin(val r: Int, val g: Int, val b: Int, val count: Int)

    /** 中位切分盒（保存桶下标）。 */
    private class Box(val indices: List<Int>) {

        fun totalCount(bins: List<Bin>): Int = indices.sumOf { bins[it].count }

        fun channelRange(bins: List<Bin>, channel: Int): Int {
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (index in indices) {
                val value = when (channel) {
                    0 -> bins[index].r
                    1 -> bins[index].g
                    else -> bins[index].b
                }
                if (value < min) min = value
                if (value > max) max = value
            }
            return if (min > max) 0 else max - min
        }

        fun maxRangeChannel(bins: List<Bin>): Int {
            var bestChannel = -1
            var bestRange = 0
            for (channel in 0..2) {
                val range = channelRange(bins, channel)
                if (range > bestRange) {
                    bestRange = range
                    bestChannel = channel
                }
            }
            return bestChannel
        }

        /** 按最长通道排序后在中位计数处切开；无法切分返回 null。 */
        fun split(bins: List<Bin>, channel: Int): Pair<Box, Box>? {
            val ordered = indices.sortedBy { index ->
                when (channel) {
                    0 -> bins[index].r
                    1 -> bins[index].g
                    else -> bins[index].b
                }
            }
            if (ordered.size < 2) return null
            val total = ordered.sumOf { bins[it].count }
            var accumulated = 0
            var splitAt = 0
            for (i in ordered.indices) {
                accumulated += bins[ordered[i]].count
                if (accumulated * 2 >= total) {
                    splitAt = i
                    break
                }
            }
            if (splitAt >= ordered.size - 1) splitAt = ordered.size - 2
            val left = ordered.subList(0, splitAt + 1)
            val right = ordered.subList(splitAt + 1, ordered.size)
            if (left.isEmpty() || right.isEmpty()) return null
            // 两侧颜色完全相同时放弃切分，避免死循环。
            if (left.all { sameColor(bins, it, right.first()) }) return null
            return Box(left.toList()) to Box(right.toList())
        }

        private fun sameColor(bins: List<Bin>, a: Int, b: Int): Boolean =
            bins[a].r == bins[b].r && bins[a].g == bins[b].g && bins[a].b == bins[b].b

        /** 计数加权平均色。 */
        fun average(bins: List<Bin>): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            for (index in indices) {
                val bin = bins[index]
                r += bin.r.toLong() * bin.count
                g += bin.g.toLong() * bin.count
                b += bin.b.toLong() * bin.count
                count += bin.count
            }
            if (count == 0L) return 0xFF000000.toInt()
            val ar = (r / count).toInt().coerceIn(0, 255)
            val ag = (g / count).toInt().coerceIn(0, 255)
            val ab = (b / count).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ar shl 16) or (ag shl 8) or ab
        }
    }
}

/**
 * 调色板索引器：15bit 颜色键缓存，反复映射同一调色板时接近 O(1)。
 * 非线程安全（每个编码任务单线程使用）。
 */
class FunGifPaletteIndex(val palette: IntArray) {

    private val cache = IntArray(32 * 32 * 32) { -1 }

    fun indexOf(argb: Int): Int {
        if (palette.size <= 1) return 0
        val key = ((argb shr 16 and 0xFF) shr 3 shl 10) or
            ((argb shr 8 and 0xFF) shr 3 shl 5) or
            (argb and 0xFF) shr 3
        val cached = cache[key]
        if (cached >= 0) return cached
        val index = FunGifPalette.nearestIndex(palette, argb)
        cache[key] = index
        return index
    }
}

/**
 * 帧打包：ARGB → 调色板索引字节流（可选 Floyd–Steinberg 抖动，[strength] 可调）。
 */
object FunGifFramePacker {

    /**
     * @param argb 行主序 ARGB
     * @param width / [height] 必须与 [argb] 长度一致
     * @param index 调色板索引器
     * @param dither 是否开启误差扩散
     * @param strength 0 ~ 1 抖动强度
     * @return 行主序索引（用于 LZW）
     */
    fun pack(
        argb: IntArray,
        width: Int,
        height: Int,
        index: FunGifPaletteIndex,
        dither: Boolean,
        strength: Float,
    ): ByteArray {
        require(width > 0 && height > 0) { "gif frame must be non-empty" }
        require(argb.size == width * height) { "argb size ${argb.size} != $width x $height" }
        val out = ByteArray(width * height)
        if (!dither) {
            for (i in argb.indices) out[i] = index.indexOf(argb[i]).toByte()
            return out
        }
        val s = strength.coerceIn(0f, 1f)
        val errors = IntArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val color = argb[i]
                val r = ((color shr 16) and 0xFF) + errors[i * 3]
                val g = ((color shr 8) and 0xFF) + errors[i * 3 + 1]
                val b = (color and 0xFF) + errors[i * 3 + 2]
                val cr = r.coerceIn(0, 255)
                val cg = g.coerceIn(0, 255)
                val cb = b.coerceIn(0, 255)
                val paletteIndex = index.indexOf((0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb)
                out[i] = paletteIndex.toByte()
                val chosen = index.paletteColor(paletteIndex)
                val er = ((r - chosen[0]) * s).toInt()
                val eg = ((g - chosen[1]) * s).toInt()
                val eb = ((b - chosen[2]) * s).toInt()
                distribute(errors, out, width, height, x, y, er, eg, eb)
            }
        }
        return out
    }

    private fun distribute(
        errors: IntArray,
        out: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        er: Int,
        eg: Int,
        eb: Int,
    ) {
        fun add(xx: Int, yy: Int, weight: Int) {
            if (xx < 0 || yy < 0 || xx >= width || yy >= height) return
            val i = yy * width + xx
            errors[i * 3] += er * weight / 16
            errors[i * 3 + 1] += eg * weight / 16
            errors[i * 3 + 2] += eb * weight / 16
        }
        // Floyd–Steinberg 权重
        add(x + 1, y, 7)
        add(x - 1, y + 1, 3)
        add(x, y + 1, 5)
        add(x + 1, y + 1, 1)
    }
}

/** 打包时读取调色板颜色的便捷扩展（避免每像素重建数组）。 */
private val packedColorCache = ThreadLocal.withInitial { IntArray(3) }

internal fun FunGifPaletteIndex.paletteColor(index: Int): IntArray {
    val array = packedColorCache.get()
    val size = palette.size
    val color = if (size == 0) 0xFF000000.toInt() else palette[index.coerceIn(0, size - 1)]
    array[0] = (color shr 16) and 0xFF
    array[1] = (color shr 8) and 0xFF
    array[2] = color and 0xFF
    return array
}

/** 内部使用：暴露调色板长度，供断言与诊断。 */
internal val FunGifPaletteIndex.paletteSize: Int get() = palette.size

/** 距离工具（诊断/测试用）。 */
internal fun colorDistanceSquared(a: Int, b: Int): Int {
    val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
    val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return abs(dr * dr + dg * dg + db * db)
}
