package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 感知哈希（pHash / aHash）与重复判定阈值（相册项 12）。
 * 纯 JVM 测试：哈希全部为纯 Kotlin 实现，不依赖 AWT / Bitmap。
 */
class AppGalleryDuplicateTest {

    private val config = AppGalleryConfig()

    /** 生成合成灰度图：横向渐变（0..255）。 */
    private fun gradient(size: Int): IntArray = IntArray(size * size) { index ->
        val x = index % size
        x * 255 / (size - 1)
    }

    /** 生成合成灰度图：全黑。 */
    private fun flat(size: Int, value: Int = 0): IntArray = IntArray(size * size) { value }

    private fun hashOf(pixels: IntArray, width: Int, height: Int, size: Int = 8): LongArray =
        AppGalleryPHash.dctHash(pixels, width, height, size, 32)

    // ── 哈希基本性质 ────────────────────────────────────────────

    @Test
    fun `相同图像哈希一致且汉明距离为 0`() {
        val pixels = gradient(32)
        val a = hashOf(pixels, 32, 32)
        val b = hashOf(pixels, 32, 32)
        assertEquals(0, AppGalleryPHash.hammingDistance(a, b))
        assertEquals(1, a.size)
    }

    @Test
    fun `不同图像哈希存在差异`() {
        val gradientHash = hashOf(gradient(32), 32, 32)
        val flatHash = hashOf(flat(32), 32, 32)
        assertTrue(AppGalleryPHash.hammingDistance(gradientHash, flatHash) > 0)
    }

    @Test
    fun `16 位宽哈希使用四个 64 位字`() {
        val hash = hashOf(gradient(32), 32, 32, size = 16)
        assertEquals(4, hash.size)
        assertEquals(4, AppGalleryPHash.packBits(BooleanArray(256)).size)
        assertEquals(1, AppGalleryPHash.wordsFor(8))
        assertEquals(4, AppGalleryPHash.wordsFor(16))
    }

    @Test
    fun `位宽不同的哈希距离为最大值而不是错误值`() {
        val small = hashOf(gradient(32), 32, 32, size = 8)
        val big = hashOf(gradient(32), 32, 32, size = 16)
        assertEquals(Int.MAX_VALUE, AppGalleryPHash.hammingDistance(small, big))
    }

    @Test
    fun `位宽与重采样边长被 clamp 到支持范围`() {
        assertEquals(8, AppGalleryPHash.normalizeHashSize(3))
        assertEquals(16, AppGalleryPHash.normalizeHashSize(99))
        assertEquals(16, AppGalleryPHash.normalizeResizeEdge(1))
        assertEquals(64, AppGalleryPHash.normalizeResizeEdge(999))
        assertEquals(64, AppGalleryPHash.hammingLimit(8))
        assertEquals(256, AppGalleryPHash.hammingLimit(16))
        assertTrue(AppGalleryConfig.DUPLICATE_HASH_SIZES.toList().contains(8))
        assertTrue(AppGalleryConfig.DUPLICATE_HASH_SIZES.toList().contains(16))
    }

    @Test
    fun `十六进制往返保持位模式`() {
        val hash = hashOf(gradient(32), 32, 32)
        val hex = AppGalleryPHash.toHex(hash)
        assertEquals(16, hex.length)
        val restored = AppGalleryPHash.fromHex(hex, 8)
        assertNotNull(restored)
        assertEquals(0, AppGalleryPHash.hammingDistance(hash, restored!!))
        assertNull(AppGalleryPHash.fromHex(null, 8))
        assertNull(AppGalleryPHash.fromHex("zz", 8))
    }

    @Test
    fun `配置分派决定使用均值哈希还是 DCT 哈希`() {
        val pixels = gradient(32)
        val dct = AppGalleryPHash.hash(pixels, 32, 32, config.copy(duplicateUseAverageHash = false))
        val average = AppGalleryPHash.hash(pixels, 32, 32, config.copy(duplicateUseAverageHash = true))
        assertTrue(abs(AppGalleryPHash.hammingDistance(dct, average)) in 0..64)
    }

    @Test
    fun `灰度转换遵循 Rec601 权重`() {
        assertEquals(0, AppGalleryPHash.grayFromRgb(0, 0, 0))
        assertEquals(255, AppGalleryPHash.grayFromRgb(255, 255, 255))
        // 绿色权重最高、蓝色最低
        assertTrue(AppGalleryPHash.grayFromRgb(0, 255, 0) > AppGalleryPHash.grayFromRgb(255, 0, 0))
        assertTrue(AppGalleryPHash.grayFromRgb(255, 0, 0) > AppGalleryPHash.grayFromRgb(0, 0, 255))
    }

    // ── 分组阈值边界 ────────────────────────────────────────────

    @Test
    fun `相同哈希必然同组合并`() {
        val entries = listOf(
            AppGalleryHashEntry("a", longArrayOf(0b1010L), sizeBytes = 100L, sortDate = 10L),
            AppGalleryHashEntry("b", longArrayOf(0b1010L), sizeBytes = 200L, sortDate = 20L),
            AppGalleryHashEntry("c", longArrayOf(0b1010L), sizeBytes = 300L, sortDate = 30L),
        )
        val groups = AppGalleryDuplicateDetector.group(entries, threshold = 0, hashSize = 8)
        assertEquals(1, groups.size)
        assertEquals(3, groups.first().allIds.size)
        assertEquals(0, groups.first().maxDistance)
        // 保留项 = 体积最大者（300），可释放 = 100 + 200
        assertEquals("c", groups.first().anchorId)
        assertEquals(300L, groups.first().reclaimableBytes)
        assertEquals(300L, AppGalleryDuplicateDetector.reclaimableBytes(groups))
    }

    @Test
    fun `阈值边界距离等于阈值算重复 超过阈值不算`() {
        // a 与 b 距离 2；a 与 c 距离 3
        val a = AppGalleryHashEntry("a", longArrayOf(0b0000L), sizeBytes = 10L, sortDate = 30L)
        val b = AppGalleryHashEntry("b", longArrayOf(0b0011L), sizeBytes = 20L, sortDate = 20L)
        val c = AppGalleryHashEntry("c", longArrayOf(0b0111L), sizeBytes = 30L, sortDate = 10L)

        val atTwo = AppGalleryDuplicateDetector.group(listOf(a, b, c), threshold = 2, hashSize = 8)
        assertEquals(2, AppGalleryPHash.hammingDistance(a.hash, b.hash))
        assertEquals(3, AppGalleryPHash.hammingDistance(a.hash, c.hash))
        // 距离 2 的 b 进组，距离 3 的 c 不进组
        assertEquals(1, atTwo.size)
        assertEquals(setOf("a", "b"), atTwo.first().allIds.toSet())

        val atThree = AppGalleryDuplicateDetector.group(listOf(a, b, c), threshold = 3, hashSize = 8)
        assertEquals(1, atThree.size)
        assertEquals(setOf("a", "b", "c"), atThree.first().allIds.toSet())
    }

    @Test
    fun `阈值越界被 clamp 到位宽上限`() {
        val a = AppGalleryHashEntry("a", longArrayOf(0L), sizeBytes = 10L, sortDate = 30L)
        val b = AppGalleryHashEntry("b", longArrayOf(0b1111L), sizeBytes = 20L, sortDate = 10L)
        // 阈值传负数 → clamp 到 0，位模式不同则不成组
        assertTrue(AppGalleryDuplicateDetector.group(listOf(a, b), threshold = -5, hashSize = 8).isEmpty())
        // 阈值传超大 → clamp 到 64，必然成组
        assertEquals(1, AppGalleryDuplicateDetector.group(listOf(a, b), threshold = 9_999, hashSize = 8).size)
    }

    @Test
    fun `位宽不同的哈希不会互相成组`() {
        val small = AppGalleryHashEntry("small", longArrayOf(0L), sizeBytes = 10L)
        val big = AppGalleryHashEntry("big", longArrayOf(0L, 0L), sizeBytes = 20L)
        assertTrue(AppGalleryDuplicateDetector.group(listOf(small, big), threshold = 64, hashSize = 16).isEmpty())
    }

    @Test
    fun `空哈希条目被自动跳过`() {
        val empty = AppGalleryHashEntry("empty", LongArray(0), sizeBytes = 10L)
        val good = AppGalleryHashEntry("good", longArrayOf(0L), sizeBytes = 20L)
        assertTrue(AppGalleryDuplicateDetector.group(listOf(empty, good), threshold = 10, hashSize = 8).isEmpty())
        assertTrue(AppGalleryDuplicateDetector.group(emptyList(), 10, 8).isEmpty())
        assertEquals(0L, AppGalleryDuplicateDetector.reclaimableBytes(emptyList()))
    }

    @Test
    fun `保留项优先选收藏与高评分条目`() {
        val favorite = AppGalleryHashEntry("fav", longArrayOf(0b1010L), sizeBytes = 10L, sortDate = 1L, keepPriority = 4)
        val plain = AppGalleryHashEntry("plain", longArrayOf(0b1010L), sizeBytes = 20L, sortDate = 99L, keepPriority = 0)
        val group = AppGalleryDuplicateDetector.group(listOf(plain, favorite), threshold = 0, hashSize = 8).first()
        assertEquals("fav", group.anchorId)
        assertEquals(listOf("plain"), group.duplicateIds)
        assertEquals(20L, group.reclaimableBytes)
    }

    @Test
    fun `多组结果按可释放空间降序`() {
        val entries = listOf(
            AppGalleryHashEntry("a1", longArrayOf(0L), sizeBytes = 10L, keepPriority = 1),
            AppGalleryHashEntry("a2", longArrayOf(0L), sizeBytes = 15L, keepPriority = 0),
            AppGalleryHashEntry("b1", longArrayOf(0b1111111111L), sizeBytes = 100L, keepPriority = 1),
            AppGalleryHashEntry("b2", longArrayOf(0b1111111111L), sizeBytes = 5_000L, keepPriority = 0),
        )
        val groups = AppGalleryDuplicateDetector.group(entries, threshold = 0, hashSize = 8)
        assertEquals(2, groups.size)
        assertTrue(groups.first().reclaimableBytes >= groups.last().reclaimableBytes)
        assertFalse(groups.any { it.duplicateIds.isEmpty() })
    }

    @Test
    fun `分组结果随配置位宽与阈值联动`() {
        val a = AppGalleryHashEntry("a", longArrayOf(0b0000L), sizeBytes = 10L)
        val b = AppGalleryHashEntry("b", longArrayOf(0b0011L), sizeBytes = 10L)
        val distance = AppGalleryPHash.hammingDistance(a.hash, b.hash)
        // 阈值 = 距离 − 1 → 判为非重复
        assertEquals(
            0,
            AppGalleryDuplicateDetector.group(
                listOf(a, b),
                threshold = distance - 1,
                hashSize = config.duplicateHashSize,
            ).size,
        )
        // 阈值 = 距离 → 判为重复（含等于）
        assertEquals(
            1,
            AppGalleryDuplicateDetector.group(
                listOf(a, b),
                threshold = distance,
                hashSize = config.duplicateHashSize,
            ).size,
        )
        // 16 位宽配置下同一对位模式仍然可比（同宽分桶）
        val limit16 = AppGalleryDuplicateDetector.group(
            listOf(
                AppGalleryHashEntry("x", longArrayOf(0L, 0L, 0L, 0L), sizeBytes = 1L),
                AppGalleryHashEntry("y", longArrayOf(0L, 0L, 0L, 0L), sizeBytes = 2L),
            ),
            threshold = AppGalleryConfig.hammingLimitFor(16),
            hashSize = 16,
        )
        assertEquals(1, limit16.size)
        assertEquals(0, limit16.first().maxDistance)
        assertEquals(1L, limit16.first().reclaimableBytes)
    }
}
