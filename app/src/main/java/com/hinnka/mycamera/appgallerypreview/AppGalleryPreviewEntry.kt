package com.hinnka.mycamera.appgallerypreview

/**
 * 预览域入口契约（跨代理约定）。
 *
 * 用途：管理核心域（`appgallery` 包）在网格/列表/分类/收藏/回收站中点击任一项时，
 * 通过本契约跳转到预览体验域。**两端只共享本文件的键名与语义**，
 * 预览域不依赖管理域的任何符号，管理域也无需依赖预览域的类（只用字符串键）。
 *
 * 同一组键同时用于两种落地方式（由集成阶段选择其一即可）：
 *  1. Intent extras（Activity 方式）；
 *  2. Navigation Compose 的 route 参数（键名一致，值以 [toExtras] 生成的 Map 传入）。
 *
 * 键名一律小写蛇形，避免与既有 Bundle 键冲突。
 */
object AppGalleryPreviewEntryContract {

    // ---- 必需 ----
    /** 首屏展示的媒体 URI（content:// 或 file://）。缺失即无法进入预览。 */
    const val EXTRA_MEDIA_URI = "media_uri"

    // ---- 可选 ----
    /** MIME 类型；缺失时由扩展名推断（见 AppGalleryPreviewMediaKindResolver）。 */
    const val EXTRA_MEDIA_TYPE = "media_type"

    /** 管理域的资源 id；预览域原样透传给快捷操作回调，不做解析。 */
    const val EXTRA_MEDIA_ID = "media_id"

    /** 当前项在来源序列中的下标（0-based）；负值按 0 处理。 */
    const val EXTRA_INDEX = "index"

    /** 来源序列总数（用于翻页边界与"3/12"计数）。 */
    const val EXTRA_TOTAL = "total"

    /**
     * 来源序列的资源 id 列表，以 [LIST_SEPARATOR]（U+001F）分隔。
     * 预览域只按顺序持有 id 并透传，不查询内容。
     */
    const val EXTRA_SOURCE_IDS = "source_ids"

    /**
     * 来源序列的资源 URI 列表（可选，同样以 [LIST_SEPARATOR] 分隔）。
     *
     * 与 [EXTRA_SOURCE_IDS] **按同一顺序一一对应**。提供后预览域即可独立翻页，
     * 无需回调管理域（推荐小集合直接传；超大集合请改用
     * `AppGalleryPreviewMediaSource` 由管理域按需解析）。
     */
    const val EXTRA_SOURCE_URIS = "source_uris"

    /**
     * 来源序列的 MIME 类型列表（可选，以 [LIST_SEPARATOR] 分隔，空位用空串占位）。
     * 缺失时由各 URI 的扩展名推断。
     */
    const val EXTRA_SOURCE_TYPES = "source_types"

    /** 是否允许在预览页进入编辑（管理域可因权限/格式而禁止）。 */
    const val EXTRA_ALLOW_EDIT = "allow_edit"

    /** 是否允许进入快捷编辑。 */
    const val EXTRA_ALLOW_QUICK_EDIT = "allow_quick_edit"

    /** 进入后是否立即开始幻灯片放映。 */
    const val EXTRA_START_SLIDESHOW = "start_slideshow"

    /** 是否允许幻灯片开关可用。 */
    const val EXTRA_SLIDESHOW_ENABLED = "slideshow_enabled"

    /** 对比用的原图 URI（处理前版本）；缺失表示不可对比。 */
    const val EXTRA_COMPARE_ORIGINAL_URI = "compare_original_uri"

    /** 对比用的处理后 URI；缺失时默认用 [EXTRA_MEDIA_URI]。 */
    const val EXTRA_COMPARE_PROCESSED_URI = "compare_processed_uri"

    /** 是否支持 RAW→JPEG 导出。 */
    const val EXTRA_RAW_EXPORT_ENABLED = "raw_export_enabled"

    /** 发起方标识（如 "appgallery" / "camera_thumbnail" / "external_share"）。 */
    const val EXTRA_SOURCE = "source"

    /** 用户是否已收藏；未知则不传。 */
    const val EXTRA_FAVORITE = "favorite"

    /** 序列 id 分隔符（U+001F，Unit Separator）：不会出现在正常 URI 中。 */
    const val LIST_SEPARATOR = "\u001F"

    /** 预览页建议路由名（Navigation Compose 用）。 */
    const val ROUTE = "appgallery_preview"
}

/**
 * 一次预览会话的请求参数（纯数据，可 JVM 单测）。
 *
 * 由管理域构造，经 Intent extras / Navigation 参数进入预览域。
 */
data class AppGalleryPreviewRequest(
    val mediaUri: String,
    val mediaKind: AppGalleryPreviewMediaKind,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val index: Int = 0,
    val total: Int = 1,
    val sourceIds: List<String> = emptyList(),
    /** 来源序列的 URI（与 [sourceIds] 同序）；为空表示预览域需按需向管理域解析。 */
    val sourceUris: List<String> = emptyList(),
    /** 来源序列的 MIME（与 [sourceIds] 同序，可为空串占位）；为空时按 URI 扩展名推断。 */
    val sourceTypes: List<String> = emptyList(),
    val allowEdit: Boolean = true,
    val allowQuickEdit: Boolean = true,
    val startSlideshow: Boolean = false,
    val slideshowEnabled: Boolean = false,
    val compareOriginalUri: String? = null,
    val compareProcessedUri: String? = null,
    /** 是否允许 RAW→JPEG 导出；默认允许，管理域可置 false 关闭。 */
    val rawExportEnabled: Boolean = true,
    val source: String? = null,
    val favorite: Boolean? = null,
) {
    /** 规范化：index/total 落到一致区间（防误设导致越界或除零）。 */
    fun normalized(): AppGalleryPreviewRequest {
        val safeTotal = maxOf(total, 1)
        val safeIndex = index.coerceIn(0, safeTotal - 1)
        return copy(index = safeIndex, total = safeTotal)
    }

    /** 序列化进入端；只写非空/非默认项，保持 extras 精简。 */
    fun toExtras(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[AppGalleryPreviewEntryContract.EXTRA_MEDIA_URI] = mediaUri
        mediaType?.takeIf { it.isNotBlank() }?.let { out[AppGalleryPreviewEntryContract.EXTRA_MEDIA_TYPE] = it }
        mediaId?.takeIf { it.isNotBlank() }?.let { out[AppGalleryPreviewEntryContract.EXTRA_MEDIA_ID] = it }
        out[AppGalleryPreviewEntryContract.EXTRA_INDEX] = index.toString()
        out[AppGalleryPreviewEntryContract.EXTRA_TOTAL] = total.toString()
        if (sourceIds.isNotEmpty()) {
            out[AppGalleryPreviewEntryContract.EXTRA_SOURCE_IDS] =
                sourceIds.joinToString(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
        }
        if (sourceUris.isNotEmpty()) {
            out[AppGalleryPreviewEntryContract.EXTRA_SOURCE_URIS] =
                sourceUris.joinToString(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
        }
        if (sourceTypes.isNotEmpty()) {
            out[AppGalleryPreviewEntryContract.EXTRA_SOURCE_TYPES] =
                sourceTypes.joinToString(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
        }
        out[AppGalleryPreviewEntryContract.EXTRA_ALLOW_EDIT] = allowEdit.toString()
        out[AppGalleryPreviewEntryContract.EXTRA_ALLOW_QUICK_EDIT] = allowQuickEdit.toString()
        out[AppGalleryPreviewEntryContract.EXTRA_START_SLIDESHOW] = startSlideshow.toString()
        out[AppGalleryPreviewEntryContract.EXTRA_SLIDESHOW_ENABLED] = slideshowEnabled.toString()
        compareOriginalUri?.takeIf { it.isNotBlank() }
            ?.let { out[AppGalleryPreviewEntryContract.EXTRA_COMPARE_ORIGINAL_URI] = it }
        compareProcessedUri?.takeIf { it.isNotBlank() }
            ?.let { out[AppGalleryPreviewEntryContract.EXTRA_COMPARE_PROCESSED_URI] = it }
        out[AppGalleryPreviewEntryContract.EXTRA_RAW_EXPORT_ENABLED] = rawExportEnabled.toString()
        source?.takeIf { it.isNotBlank() }?.let { out[AppGalleryPreviewEntryContract.EXTRA_SOURCE] = it }
        favorite?.let { out[AppGalleryPreviewEntryContract.EXTRA_FAVORITE] = it.toString() }
        return out
    }

    companion object {
        /**
         * 从 extras（或导航参数）还原请求。
         *
         * 健壮性：`media_uri` 缺失/空白 → 返回 null（调用方应回退到列表页）；
         * 数字字段非法一律取默认；`media_type` 缺失时按扩展名推断。
         */
        fun fromExtras(extras: Map<String, String?>): AppGalleryPreviewRequest? {
            fun value(key: String): String? =
                extras[key]?.trim()?.takeIf { it.isNotEmpty() }

            val uri = value(AppGalleryPreviewEntryContract.EXTRA_MEDIA_URI) ?: return null
            val mime = value(AppGalleryPreviewEntryContract.EXTRA_MEDIA_TYPE)
            val kind = AppGalleryPreviewMediaKindResolver.resolve(mime, uri)
            val idsRaw = value(AppGalleryPreviewEntryContract.EXTRA_SOURCE_IDS)
            val ids = idsRaw
                ?.split(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            // URI 列表按原样切分（不过滤空位，保持与 ids 的下标一一对应）。
            val uris = value(AppGalleryPreviewEntryContract.EXTRA_SOURCE_URIS)
                ?.split(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
                ?.map { it.trim() }
                ?: emptyList()
            val types = value(AppGalleryPreviewEntryContract.EXTRA_SOURCE_TYPES)
                ?.split(AppGalleryPreviewEntryContract.LIST_SEPARATOR)
                ?.map { it.trim() }
                ?: emptyList()
            val processed = value(AppGalleryPreviewEntryContract.EXTRA_COMPARE_PROCESSED_URI) ?: uri
            val original = value(AppGalleryPreviewEntryContract.EXTRA_COMPARE_ORIGINAL_URI)

            return AppGalleryPreviewRequest(
                mediaUri = uri,
                mediaKind = kind,
                mediaType = mime,
                mediaId = value(AppGalleryPreviewEntryContract.EXTRA_MEDIA_ID),
                index = value(AppGalleryPreviewEntryContract.EXTRA_INDEX)?.toIntOrNull() ?: 0,
                total = value(AppGalleryPreviewEntryContract.EXTRA_TOTAL)?.toIntOrNull() ?: 1,
                sourceIds = ids,
                sourceUris = uris,
                sourceTypes = types,
                allowEdit = value(AppGalleryPreviewEntryContract.EXTRA_ALLOW_EDIT)?.toBooleanStrictOrNull() ?: true,
                allowQuickEdit = value(AppGalleryPreviewEntryContract.EXTRA_ALLOW_QUICK_EDIT)
                    ?.toBooleanStrictOrNull() ?: true,
                startSlideshow = value(AppGalleryPreviewEntryContract.EXTRA_START_SLIDESHOW)
                    ?.toBooleanStrictOrNull() ?: false,
                slideshowEnabled = value(AppGalleryPreviewEntryContract.EXTRA_SLIDESHOW_ENABLED)
                    ?.toBooleanStrictOrNull() ?: false,
                compareOriginalUri = original,
                compareProcessedUri = processed.takeIf { original != null } ?: null,
                rawExportEnabled = value(AppGalleryPreviewEntryContract.EXTRA_RAW_EXPORT_ENABLED)
                    ?.toBooleanStrictOrNull() ?: true,
                source = value(AppGalleryPreviewEntryContract.EXTRA_SOURCE),
                favorite = value(AppGalleryPreviewEntryContract.EXTRA_FAVORITE)?.toBooleanStrictOrNull(),
            ).normalized()
        }
    }
}
