package com.hinnka.mycamera.appgallery

/** 排序字段（相册项 6：排序）。 */
enum class AppGallerySortField {
    /** 时间（dateTaken 优先，回落 dateAdded）。 */
    DATE,

    /** 名称（不区分大小写；数字按自然序，避免 "IMG_10" 排在 "IMG_9" 前）。 */
    NAME,

    /** 文件大小。 */
    SIZE,

    /** 类型/分类（按 [AppGalleryCategory] 声明顺序聚拢同类）。 */
    CATEGORY,

    /** 评分（未评分为 0）。 */
    RATING,
}

/** 排序方向。 */
enum class AppGallerySortOrder {
    ASCENDING,
    DESCENDING;

    /** 反向。 */
    fun flipped(): AppGallerySortOrder =
        if (this == ASCENDING) DESCENDING else ASCENDING
}

/** 排序参数（开放可配；由 DataStore 记忆）。 */
data class AppGallerySortSpec(
    val field: AppGallerySortField = AppGallerySortField.DATE,
    val order: AppGallerySortOrder = AppGallerySortOrder.DESCENDING,
) {
    companion object {
        fun from(config: AppGalleryConfig): AppGallerySortSpec = AppGallerySortSpec(
            field = config.defaultSortField,
            order = if (config.defaultSortAscending) {
                AppGallerySortOrder.ASCENDING
            } else {
                AppGallerySortOrder.DESCENDING
            },
        )
    }
}

/**
 * 排序器（纯函数，稳定排序 + 以 id 兜底保证确定性）。
 *
 * NAME 字段使用自然序比较（NUMERIC 切分），因此 `IMG_2` < `IMG_10`。
 */
object AppGallerySorter {

    fun sort(
        items: List<AppGalleryItem>,
        spec: AppGallerySortSpec,
    ): List<AppGalleryItem> {
        val comparator = comparatorFor(spec)
        return items.sortedWith(comparator)
    }

    /** 生成比较器（先按字段，再按 id 兜底，保证结果确定）。 */
    fun comparatorFor(spec: AppGallerySortSpec): Comparator<AppGalleryItem> {
        val base: Comparator<AppGalleryItem> = when (spec.field) {
            AppGallerySortField.DATE -> compareBy { it.sortDate }
            AppGallerySortField.NAME -> Comparator { a, b ->
                compareNatural(a.displayName, b.displayName)
            }
            AppGallerySortField.SIZE -> compareBy { it.sizeBytes }
            AppGallerySortField.CATEGORY -> compareBy { it.category.ordinal }
            AppGallerySortField.RATING -> compareBy { it.rating }
        }
        val directed = if (spec.order == AppGallerySortOrder.DESCENDING) base.reversed() else base
        return directed.thenBy { it.id }
    }

    /**
     * 自然序比较：把字符串切成「数字段 / 非数字段」交替序列，数字段按数值比较。
     * 纯 Kotlin 实现（不引第三方依赖）。
     */
    fun compareNatural(a: String, b: String): Int {
        val la = a.lowercase()
        val lb = b.lowercase()
        var i = 0
        var j = 0
        while (i < la.length && j < lb.length) {
            val ca = la[i]
            val cb = lb[j]
            val aDigit = ca.isDigit()
            val bDigit = cb.isDigit()
            if (aDigit && bDigit) {
                var ia = i
                while (ia < la.length && la[ia].isDigit()) ia++
                var jb = j
                while (jb < lb.length && lb[jb].isDigit()) jb++
                val na = la.substring(i, ia).trimStart('0')
                val nb = lb.substring(j, jb).trimStart('0')
                val byLength = na.length.compareTo(nb.length)
                if (byLength != 0) return byLength
                val byValue = na.compareTo(nb)
                if (byValue != 0) return byValue
                // 数值相等时（如 007 与 7），用原始数字串长度做稳定区分
                val byRaw = (ia - i).compareTo(jb - j)
                if (byRaw != 0) return byRaw
                i = ia
                j = jb
                continue
            }
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
        return (la.length - i).compareTo(lb.length - j)
    }
}
