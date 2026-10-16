package com.hinnka.mycamera.appgallery

/**
 * 「列表 → 预览」跳转契约（相册项 3）。
 *
 * 本模块**不实现**缩放/幻灯片/RAW 查看/前后对比/二次编辑（那属于预览体验域），
 * 只提供一个**零符号依赖**的请求模型与宿主接口：预览域把自己的页面注册成
 * [AppGalleryPreviewHost] 的实现即可，双方不需要引用对方的类。
 *
 * 同时给出一条约定路由模板（[ROUTE_TEMPLATE]）与 Intent extras 键名，
 * 供 Compose 导航或 Activity 跳转使用。
 */
data class AppGalleryPreviewLaunch(
    /** 当前展示的条目 id。 */
    val itemId: String,
    /** 当前列表的有序 id 序列（用于左右滑动切换）。 */
    val orderedIds: List<String>,
    /** [orderedIds] 中的下标（已 clamp）。 */
    val index: Int,
    val source: AppGalleryItemSource = AppGalleryItemSource.APP_DATABASE,
    /** 来源容器（相册/智能分组 id；`null` = 全部）。 */
    val containerId: String? = null,
    val viewMode: AppGalleryViewMode = AppGalleryViewMode.GRID,
    /** 缩放（双指）由预览域渲染，这里只声明期望能力。 */
    val zoomEnabled: Boolean = true,
    val slideShowEnabled: Boolean = true,
    val comparisonEnabled: Boolean = true,
    val rawViewerEnabled: Boolean = true,
) {
    val count: Int get() = orderedIds.size
}

/** 预览域实现该接口后可被列表页调用（注册方式见补丁 CODE_HOOKS）。 */
interface AppGalleryPreviewHost {
    fun openPreview(request: AppGalleryPreviewLaunch)
}

object AppGalleryPreviewContract {

    /** 约定路由（预览域若已有自己的路由，忽略此常量即可）。 */
    const val ROUTE_TEMPLATE = "app_gallery_preview/{itemId}"

    const val EXTRA_ITEM_ID = "app_gallery.preview.item_id"
    const val EXTRA_ORDERED_IDS = "app_gallery.preview.ordered_ids"
    const val EXTRA_INDEX = "app_gallery.preview.index"
    const val EXTRA_SOURCE = "app_gallery.preview.source"
    const val EXTRA_CONTAINER_ID = "app_gallery.preview.container_id"

    /** 传递 id 序列的上限（防止超大列表撑爆 Intent/Bundle）。 */
    const val ORDER_LIMIT = 2000

    /** 全屏预览容器 id（无相册上下文时使用）。 */
    const val CONTAINER_ALL = "all"

    // ── 与预览体验域（`appgallerypreview`）的**字符串级**约定 ──────────────────
    // 预览域已发布同一组键名（其 `AppGalleryPreviewEntryContract`）；两端只共享键名与语义，
    // 互不引用对方的类（本模块不 import 该包）。下方常量必须与其保持逐字一致。

    /** 预览页路由名（与预览域约定一致；本模块的 [ROUTE_TEMPLATE] 内部仍可用自带模板）。 */
    const val PREVIEW_ROUTE = "appgallery_preview"

    /** 发起方标识（用于预览域区分列表来源）。 */
    const val PREVIEW_SOURCE_TAG = "appgallery"

    /** 序列分隔符（U+001F，与预览域约定一致）。 */
    const val PREVIEW_LIST_SEPARATOR = "\u001F"

    const val PREVIEW_KEY_MEDIA_URI = "media_uri"
    const val PREVIEW_KEY_MEDIA_TYPE = "media_type"
    const val PREVIEW_KEY_MEDIA_ID = "media_id"
    const val PREVIEW_KEY_INDEX = "index"
    const val PREVIEW_KEY_TOTAL = "total"
    const val PREVIEW_KEY_SOURCE_IDS = "source_ids"
    const val PREVIEW_KEY_SOURCE_URIS = "source_uris"
    const val PREVIEW_KEY_SOURCE_TYPES = "source_types"
    const val PREVIEW_KEY_SOURCE = "source"
    const val PREVIEW_KEY_FAVORITE = "favorite"
    const val PREVIEW_KEY_ALLOW_EDIT = "allow_edit"

    /** 一次跳转携带的字符预算（防超大列表撑爆 Intent/Bundle；超出即按锚点窗口化）。 */
    const val PREVIEW_EXTRA_CHAR_BUDGET = 120_000

    fun routeFor(itemId: String): String =
        ROUTE_TEMPLATE.replace("{itemId}", encodeId(itemId))

    /**
     * 轻量 id 编码（只处理会破坏路由/路径的字符，保持纯 JVM 可测，不依赖 `android.net.Uri`）。
     */
    fun encodeId(itemId: String): String = buildString(itemId.length) {
        itemId.forEach { char ->
            when {
                char.isLetterOrDigit() || char == '_' || char == '-' || char == '.' -> append(char)
                else -> append('%').append(char.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    /**
     * 从当前列表构造预览请求。
     *
     * @param anchorId 用户点击的条目；不在 [items] 中或为空则返回 null（不跳转）。
     * @param visibleIds 传入可见/过滤后的顺序（默认取 items 顺序）。
     */
    fun build(
        items: List<AppGalleryItem>,
        anchorId: String,
        containerId: String? = null,
        viewMode: AppGalleryViewMode = AppGalleryViewMode.GRID,
        visibleIds: List<String>? = null,
    ): AppGalleryPreviewLaunch? {
        if (items.isEmpty()) return null
        val ordered = visibleIds ?: items.map { it.id }
        val bounded = if (ordered.size > ORDER_LIMIT) ordered.take(ORDER_LIMIT) else ordered
        val index = bounded.indexOf(anchorId)
        if (index < 0) return null
        return AppGalleryPreviewLaunch(
            itemId = anchorId,
            orderedIds = bounded,
            index = index,
            source = items.firstOrNull { it.id == anchorId }?.source
                ?: AppGalleryItemSource.APP_DATABASE,
            containerId = containerId,
            viewMode = viewMode,
        )
    }

    /** 在请求内前后翻页（预览域可复用该纯函数决定相邻 id）。 */
    fun neighborId(request: AppGalleryPreviewLaunch, offset: Int): String? {
        if (request.orderedIds.isEmpty()) return null
        val target = request.index + offset
        return request.orderedIds.getOrNull(target)
    }

    /** 该类型是否有 RAW 源可看（RAW/DNG 分类）。 */
    fun supportsRawViewing(category: AppGalleryCategory): Boolean =
        category == AppGalleryCategory.RAW_DNG

    /** 该类型是否建议启用「前后对比」（HDR/多帧/超分/LUT 类产物）。 */
    fun suggestsComparison(category: AppGalleryCategory): Boolean = when (category) {
        AppGalleryCategory.HDR,
        AppGalleryCategory.MULTI_FRAME,
        AppGalleryCategory.SUPER_RES,
        AppGalleryCategory.LUT_FILM,
        AppGalleryCategory.ASTRO_TRAIL,
        -> true

        else -> false
    }

    /** 视频/动图等需要播放器而非静态查看器。 */
    fun needsPlayer(item: AppGalleryItem): Boolean =
        item.isVideo || item.category == AppGalleryCategory.GIF

    /**
     * 组装**预览域约定键名**的 extras（只含字符串，可直接
     * `intent.putExtra(key, value)` 或喂给预览域的 `fromExtras`）。
     *
     * 缺失的键由预览域取默认值；本函数只写「有真实信息」的键：
     * - 要求 `media_uri` 可解析，否则返回空表（调用方应回退停留在列表页）；
     * - `source_uris` / `source_types` 仅在**整段序列都拿得到 URI** 时才随行传递
     *   （保证与 `source_ids` 下标一一对应），否则留给预览域按需解析；
     * - `media_type` 仅对视频/GIF 显式给出，静态图片交由预览域按扩展名推断。
     */
    fun toPreviewEntryExtras(
        launch: AppGalleryPreviewLaunch,
        items: List<AppGalleryItem>,
        allowEdit: Boolean = true,
    ): Map<String, String> {
        val byId = items.associateBy { it.id }
        val anchor = byId[launch.itemId] ?: return emptyMap()
        if (anchor.uri.isBlank()) return emptyMap()

        val orderedIds = launch.orderedIds.take(ORDER_LIMIT)
        val anchorIndex = orderedIds.indexOf(launch.itemId).takeIf { it >= 0 }
            ?: launch.index.coerceAtLeast(0)
        val (ids, index) = fitWindow(orderedIds, anchorIndex)
        if (ids.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, String>()
        out[PREVIEW_KEY_MEDIA_URI] = anchor.uri
        previewMimeOf(anchor)?.let { out[PREVIEW_KEY_MEDIA_TYPE] = it }
        out[PREVIEW_KEY_MEDIA_ID] = anchor.id
        out[PREVIEW_KEY_INDEX] = index.toString()
        out[PREVIEW_KEY_TOTAL] = ids.size.toString()
        out[PREVIEW_KEY_SOURCE_IDS] = ids.joinToString(PREVIEW_LIST_SEPARATOR)

        val uris = ids.map { byId[it]?.uri }
        if (uris.all { !it.isNullOrBlank() }) {
            out[PREVIEW_KEY_SOURCE_URIS] = uris.joinToString(PREVIEW_LIST_SEPARATOR) { it.orEmpty() }
            out[PREVIEW_KEY_SOURCE_TYPES] = ids
                .joinToString(PREVIEW_LIST_SEPARATOR) { byId[it]?.let(::previewMimeOf).orEmpty() }
        }
        out[PREVIEW_KEY_SOURCE] = PREVIEW_SOURCE_TAG
        out[PREVIEW_KEY_FAVORITE] = anchor.isFavorite.toString()
        out[PREVIEW_KEY_ALLOW_EDIT] = (allowEdit && !anchor.isVideo).toString()
        return out
    }

    /**
     * 把 id 序列裁到 [PREVIEW_EXTRA_CHAR_BUDGET] 之内：从**离锚点较远的一端**开始丢弃，
     * 锚点始终保留在窗口内，返回「窗口序列 + 锚点在窗口内的新下标」。
     */
    fun fitWindow(ids: List<String>, anchorIndex: Int): Pair<List<String>, Int> {
        if (ids.isEmpty()) return emptyList<String>() to 0
        var from = 0
        var to = ids.size
        var index = anchorIndex.coerceIn(0, ids.size - 1)
        while (joinedLength(ids, from, to) > PREVIEW_EXTRA_CHAR_BUDGET && to - from > 1) {
            // 左侧距离 >= 右侧距离 → 左侧更远（或等距），先丢左侧；否则丢右侧
            if (index - from >= to - 1 - index) from++ else to--
            index = index.coerceIn(from, to - 1)
        }
        return ids.subList(from, to).toList() to (index - from)
    }

    /** 仅对需要显式 MIME 的类型给出值，静态图片返回 null（由预览域按扩展名推断）。 */
    fun previewMimeOf(item: AppGalleryItem): String? = when {
        item.isVideo -> "video/*"
        item.category == AppGalleryCategory.GIF -> "image/gif"
        else -> null
    }

    private fun joinedLength(ids: List<String>, from: Int, to: Int): Int {
        var total = 0
        for (i in from until to) {
            total += ids[i].length + 1
            if (total > PREVIEW_EXTRA_CHAR_BUDGET) return total
        }
        return total
    }
}
