package com.hinnka.mycamera.appgallery

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/** 灰度图（方形降采样结果）。 */
data class AppGalleryGrayImage(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppGalleryGrayImage) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
}

/**
 * 感知哈希（pHash / aHash）——纯 Kotlin 自研实现（相册项 12：重复照片检测）。
 *
 * 为什么自研（PATTERN_SPEC §9 要求写明理由）：
 * 现成方案检索结论——`KilianB/JImageHash`（444★，MIT）与
 * `samfch/Perceptual-Image-Similarity` 等均基于 `java.awt.image.BufferedImage` / AWT，
 * **Android 运行时无 AWT**，无法直接接入；`gavinliu/SimilarPhoto`（103★，无 LICENSE 声明）
 * 属"低 star + 未声明许可"，按本工程规范只能参考、不可直采。
 * 因此本模块自研纯 Kotlin DCT pHash（约 120 行，零依赖，JVM 可测）。
 *
 * 位宽约定：`hashSize = 8` → 64 bit（[LongArray] 长度 1）；`hashSize = 16` → 256 bit（长度 4）。
 */
object AppGalleryPHash {

    /** 允许的哈希边长。 */
    val SUPPORTED_HASH_SIZES = intArrayOf(8, 16)

    /**
     * 计算 pHash（DCT 版）。
     *
     * @param gray 灰度像素（长度必须 = width*height；值域 0..255，越界按 clamp）。
     * @param hashSize 取 DCT 左上角 hashSize×hashSize 系数（8 或 16，其它值就近吸附）。
     * @param resizeEdge 先降采样到 resizeEdge×resizeEdge（16..64，默认 32）。
     * @return 位数组（8 → 1 个 Long，16 → 4 个 Long）；非法输入返回空数组（调用方跳过该条目）。
     */
    fun dctHash(
        gray: IntArray,
        width: Int,
        height: Int,
        hashSize: Int = 8,
        resizeEdge: Int = 32,
    ): LongArray {
        val size = normalizeHashSize(hashSize)
        val edge = normalizeResizeEdge(resizeEdge)
        val square = toSquareGray(gray, width, height, edge) ?: return LongArray(0)
        val matrix = toDoubleMatrix(square)
        val dct = dct2d(matrix)
        // 取左上 size×size，排除 DC 后取中位数
        val coeffs = ArrayList<Double>(size * size - 1)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (x == 0 && y == 0) continue
                coeffs.add(dct[y][x])
            }
        }
        if (coeffs.isEmpty()) return LongArray(0)
        val median = medianOf(coeffs)
        val bits = BooleanArray(size * size)
        var index = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                bits[index] = if (x == 0 && y == 0) false else dct[y][x] > median
                index++
            }
        }
        return packBits(bits)
    }

    /**
     * 计算 aHash（均值哈希，更快但更粗糙）。
     */
    fun averageHash(
        gray: IntArray,
        width: Int,
        height: Int,
        hashSize: Int = 8,
        resizeEdge: Int = 32,
    ): LongArray {
        val size = normalizeHashSize(hashSize)
        val edge = normalizeResizeEdge(resizeEdge)
        val square = toSquareGray(gray, width, height, edge) ?: return LongArray(0)
        // 只取左上 size×size 的平均作为阈值
        var sum = 0L
        var count = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                sum += square.pixels[y * square.width + x]
                count++
            }
        }
        if (count == 0) return LongArray(0)
        val average = sum.toDouble() / count
        val bits = BooleanArray(size * size)
        var index = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                bits[index] = square.pixels[y * square.width + x] > average
                index++
            }
        }
        return packBits(bits)
    }

    /**
     * 按配置选择算法（[AppGalleryConfig.duplicateUseAverageHash]）。
     */
    fun hash(
        gray: IntArray,
        width: Int,
        height: Int,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): LongArray {
        val safeConfig = AppGalleryConfig.clamp(config)
        return if (safeConfig.duplicateUseAverageHash) {
            averageHash(gray, width, height, safeConfig.duplicateHashSize, safeConfig.duplicateResizeEdge)
        } else {
            dctHash(gray, width, height, safeConfig.duplicateHashSize, safeConfig.duplicateResizeEdge)
        }
    }

    /**
     * 汉明距离。位数组长度不一致（位宽不同）时返回 [Int.MAX_VALUE]，表示不可比。
     */
    fun hammingDistance(a: LongArray, b: LongArray): Int {
        if (a.isEmpty() || b.isEmpty()) return Int.MAX_VALUE
        if (a.size != b.size) return Int.MAX_VALUE
        var distance = 0
        for (i in a.indices) {
            distance += java.lang.Long.bitCount(a[i] xor b[i])
        }
        return distance
    }

    /** 位宽对应的汉明距离上限。 */
    fun hammingLimit(hashSize: Int): Int = AppGalleryConfig.hammingLimitFor(normalizeHashSize(hashSize))

    /** 位数组 → 十六进制字符串（用于持久化）。 */
    fun toHex(hash: LongArray): String =
        hash.joinToString(separator = "") { java.lang.String.format("%016x", it) }

    /** 十六进制字符串 → 位数组（长度不符返回 null）。 */
    fun fromHex(hex: String?, hashSize: Int = 8): LongArray? {
        val value = hex?.trim() ?: return null
        val expectedWords = wordsFor(hashSize)
        val expectedChars = expectedWords * 16
        if (value.length != expectedChars) return null
        return runCatching {
            LongArray(expectedWords) { index ->
                val start = index * 16
                java.lang.Long.parseUnsignedLong(value.substring(start, start + 16), 16)
            }
        }.getOrNull()
    }

    /**
     * 位宽对应需要的 Long 字数：8 → 64 位 → 1 个；16 → 256 位 → 4 个。
     * （与 [packBits] 的分字数规则保持一致，供 [fromHex] / 持久化校验复用。）
     */
    fun wordsFor(hashSize: Int): Int {
        val size = normalizeHashSize(hashSize)
        return (size * size + 63) / 64
    }

    /** RGB → 灰度（Rec.601 定点近似，与工程既有着色器口径一致）。 */
    fun grayFromRgb(r: Int, g: Int, b: Int): Int {
        val rr = r.coerceIn(0, 255)
        val gg = g.coerceIn(0, 255)
        val bb = b.coerceIn(0, 255)
        return (rr * 299 + gg * 587 + bb * 114) / 1000
    }

    /** 把 ARGB Int 数组转灰度（供 Android 侧 Bitmap.getPixels 直接喂入）。 */
    fun grayFromArgb(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val argb = pixels[i]
            out[i] = grayFromRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        }
        return out
    }

    // ── 内部实现 ────────────────────────────────────────────────

    fun normalizeHashSize(hashSize: Int): Int =
        if (hashSize in SUPPORTED_HASH_SIZES) {
            hashSize
        } else {
            SUPPORTED_HASH_SIZES.minByOrNull { kotlin.math.abs(it - hashSize) } ?: 8
        }

    fun normalizeResizeEdge(edge: Int): Int = edge.coerceIn(
        AppGalleryConfig.DUPLICATE_RESIZE_EDGE_MIN,
        AppGalleryConfig.DUPLICATE_RESIZE_EDGE_MAX,
    )

    /** 盒式平均降采样到 target×target 方图（忽略原始宽高比，符合 pHash 惯例）。 */
    fun toSquareGray(gray: IntArray, width: Int, height: Int, target: Int): AppGalleryGrayImage? {
        if (width <= 0 || height <= 0) return null
        if (gray.size < width * height) return null
        val size = target.coerceAtLeast(1)
        val out = IntArray(size * size)
        for (ty in 0 until size) {
            val sy0 = ty * height / size
            val sy1 = ((ty + 1) * height / size).coerceAtLeast(sy0 + 1).coerceAtMost(height)
            for (tx in 0 until size) {
                val sx0 = tx * width / size
                val sx1 = ((tx + 1) * width / size).coerceAtLeast(sx0 + 1).coerceAtMost(width)
                var sum = 0L
                var count = 0
                for (y in sy0 until sy1) {
                    val rowOffset = y * width
                    for (x in sx0 until sx1) {
                        sum += gray[rowOffset + x].coerceIn(0, 255)
                        count++
                    }
                }
                out[ty * size + tx] = if (count == 0) 0 else (sum / count).toInt()
            }
        }
        return AppGalleryGrayImage(out, size, size)
    }

    private fun toDoubleMatrix(image: AppGalleryGrayImage): Array<DoubleArray> =
        Array(image.height) { y ->
            DoubleArray(image.width) { x -> image.pixels[y * image.width + x].toDouble() }
        }

    /** 二维 DCT-II（可分离：先行后列；32×32 规模约 6.5 万次乘加，纯 Kotlin 可接受）。 */
    fun dct2d(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val height = matrix.size
        if (height == 0) return emptyArray()
        val width = matrix[0].size
        if (width == 0) return emptyArray()
        val cosX = precomputeCos(width)
        val cosY = precomputeCos(height)
        val temp = Array(height) { DoubleArray(width) }
        for (y in 0 until height) {
            for (u in 0 until width) {
                var sum = 0.0
                for (x in 0 until width) {
                    sum += matrix[y][x] * cosX[u][x]
                }
                temp[y][u] = sum * alpha(u, width)
            }
        }
        val out = Array(height) { DoubleArray(width) }
        for (v in 0 until height) {
            for (u in 0 until width) {
                var sum = 0.0
                for (y in 0 until height) {
                    sum += temp[y][u] * cosY[v][y]
                }
                out[v][u] = sum * alpha(v, height)
            }
        }
        return out
    }

    private fun precomputeCos(length: Int): Array<DoubleArray> = Array(length) { u ->
        DoubleArray(length) { x -> cos((2.0 * x + 1.0) * u * PI / (2.0 * length)) }
    }

    private fun alpha(u: Int, length: Int): Double =
        if (u == 0) sqrt(1.0 / length) else sqrt(2.0 / length)

    /** 中位数（偶数个取偏低的中位，保证结果确定）。 */
    fun medianOf(values: Collection<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    /** 位序列 → LongArray（每 64 位一个 Long，高位在前）。 */
    fun packBits(bits: BooleanArray): LongArray {
        if (bits.isEmpty()) return LongArray(0)
        val words = (bits.size + 63) / 64
        val out = LongArray(words)
        bits.forEachIndexed { index, bit ->
            if (bit) {
                val word = index / 64
                val offset = index % 64
                out[word] = out[word] or (1L shl offset)
            }
        }
        return out
    }

    /** 位数组 → 位序列（长度 = words*64）。 */
    fun unpackBits(hash: LongArray): BooleanArray {
        val bits = BooleanArray(hash.size * 64)
        hash.forEachIndexed { word, value ->
            for (offset in 0 until 64) {
                bits[word * 64 + offset] = (value ushr offset) and 1L == 1L
            }
        }
        return bits
    }
}
