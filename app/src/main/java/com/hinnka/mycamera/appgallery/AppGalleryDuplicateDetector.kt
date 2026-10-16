package com.hinnka.mycamera.appgallery

/** 参与去重比对的条目（已算好哈希）。 */
data class AppGalleryHashEntry(
    val itemId: String,
    /** [AppGalleryPHash.hash] 的结果（8 位宽 = 1 个 Long，16 位宽 = 4 个 Long）。 */
    val hash: LongArray,
    val sizeBytes: Long = 0L,
    val sortDate: Long = 0L,
    /** 命中重复时优先保留的标记（如收藏/已编辑），越大越优先。 */
    val keepPriority: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppGalleryHashEntry) return false
        return itemId == other.itemId && hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int = itemId.hashCode() * 31 + hash.contentHashCode()
}

/** 一组近似重复的照片。 */
data class AppGalleryDuplicateGroup(
    /** 建议保留的那条（keepPriority 最大 → 体积最大 → 最早拍摄）。 */
    val anchorId: String,
    /** 同组其余条目（可被批量删除/移入回收站）。 */
    val duplicateIds: List<String>,
    /** 组内最大汉明距离（衡量相似度）。 */
    val maxDistance: Int,
    /** 组内总体积。 */
    val totalBytes: Long,
    /** 可释放体积 = 总体积 − anchor 体积。 */
    val reclaimableBytes: Long,
) {
    val allIds: List<String> get() = listOf(anchorId) + duplicateIds
}

/**
 * 重复照片检测（相册项 12）——纯 Kotlin，阈值可配，分片友好。
 *
 * 算法：并查集（union-find）聚合「汉明距离 ≤ 阈值」的连通分量；
 * 距离计算为 O(n²)，由调用方按 [AppGalleryConfig.duplicateScanBatch] 分片投喂
 * （Android 侧 [AppGalleryBgTasks.submitDuplicateScan] 已按 BgJobRunner 分片）。
 *
 * 阈值边界：`distance <= threshold` 即视为重复（含等于）；
 * 位宽不同的哈希**不可比**（距离 = Int.MAX_VALUE），不会被并入同一组。
 */
object AppGalleryDuplicateDetector {

    /**
     * 分组。
     *
     * @param entries 已算好哈希的条目（哈希为空的条目自动跳过）。
     * @param threshold 汉明距离阈值（越界 clamp 到 0..位宽上限）。
     * @param hashSize 位宽（8/16），用于 clamp 阈值。
     */
    fun group(
        entries: List<AppGalleryHashEntry>,
        threshold: Int = AppGalleryConfig().duplicateHammingThreshold,
        hashSize: Int = AppGalleryConfig().duplicateHashSize,
    ): List<AppGalleryDuplicateGroup> {
        val valid = entries.filter { it.hash.isNotEmpty() }
        if (valid.size < 2) return emptyList()

        val size = AppGalleryPHash.normalizeHashSize(hashSize)
        val limit = AppGalleryPHash.hammingLimit(size)
        val safeThreshold = threshold.coerceIn(0, limit)

        // 位宽必须一致才能比较：按 words 数量分桶，逐桶独立做并查集
        val byWords = valid.groupBy { it.hash.size }
        val groups = mutableListOf<AppGalleryDuplicateGroup>()
        byWords.forEach { (_, bucket) ->
            if (bucket.size < 2) return@forEach
            groups.addAll(groupBucket(bucket, safeThreshold))
        }
        return groups.sortedWith(
            compareByDescending<AppGalleryDuplicateGroup> { it.reclaimableBytes }
                .thenByDescending { it.duplicateIds.size }
                .thenBy { it.anchorId }
        )
    }

    private fun groupBucket(
        bucket: List<AppGalleryHashEntry>,
        threshold: Int,
    ): List<AppGalleryDuplicateGroup> {
        val parent = IntArray(bucket.size) { it }

        fun find(x: Int): Int {
            var current = x
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // 只做并查集聚合；组内最大距离在分组完成后按小组重算（避免根节点变化导致的归属漂移）
        for (i in bucket.indices) {
            for (j in i + 1 until bucket.size) {
                val distance = AppGalleryPHash.hammingDistance(bucket[i].hash, bucket[j].hash)
                if (distance == Int.MAX_VALUE) continue
                if (distance <= threshold) union(i, j)
            }
        }

        val components = LinkedHashMap<Int, MutableList<AppGalleryHashEntry>>()
        bucket.forEachIndexed { index, entry ->
            val root = find(index)
            components.getOrPut(root) { mutableListOf() }.add(entry)
        }

        return components.values
            .filter { it.size > 1 }
            .map { members -> buildGroup(members) }
    }

    private fun buildGroup(members: List<AppGalleryHashEntry>): AppGalleryDuplicateGroup {
        val sorted = members.sortedWith(
            compareByDescending<AppGalleryHashEntry> { it.keepPriority }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.sortDate }
                .thenBy { it.itemId }
        )
        val anchor = sorted.first()
        val duplicates = sorted.drop(1).map { it.itemId }
        val totalBytes = members.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        // 组内最大汉明距离：小组规模有限（同组=近似重复），重算代价可忽略
        var maxDistance = 0
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                val distance = AppGalleryPHash.hammingDistance(members[i].hash, members[j].hash)
                if (distance != Int.MAX_VALUE && distance > maxDistance) maxDistance = distance
            }
        }
        return AppGalleryDuplicateGroup(
            anchorId = anchor.itemId,
            duplicateIds = duplicates,
            maxDistance = maxDistance,
            totalBytes = totalBytes,
            reclaimableBytes = (totalBytes - anchor.sizeBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
        )
    }

    /**
     * 汇总：本次扫描可释放的总空间（用于结果提示）。
     */
    fun reclaimableBytes(groups: List<AppGalleryDuplicateGroup>): Long =
        groups.sumOf { it.reclaimableBytes }

    /**
     * 把哈希按「前缀桶」预先分桶以减少比较量（可选优化，供大厅库使用）。
     * 桶键 = 第一个 Long 的高 [prefixBits] 位；返回 id → 桶序号。
     */
    fun bucketIndex(
        entry: AppGalleryHashEntry,
        prefixBits: Int = 12,
    ): Int {
        if (entry.hash.isEmpty()) return -1
        val bits = prefixBits.coerceIn(1, 32)
        val shift = 64 - bits
        return (entry.hash[0] ushr shift).toInt()
    }
}
