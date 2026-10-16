package com.hinnka.mycamera.appgallerypreview

/**
 * 预览域 ↔ 管理域的数据源契约（跨代理，第 34/37/38 项共享）。
 *
 * 预览域需要"按序号取到下一张"才能在预览页内独立翻页；有两种落地方式：
 *  1. 管理域把小集合一次性塞进 [AppGalleryPreviewEntryContract.EXTRA_SOURCE_URIS]，
 *     用 [AppGalleryPreviewMediaSource.from] 直接构造本接口的实现；
 *  2. 超大集合由管理域实现本接口，按需解析（推荐，避免把上千条 URI 塞进 extras）。
 *
 * 预览域**只依赖本接口**，不依赖管理域任何符号；索引越界一律返回 null / 安全默认值，
 * 保证翻到边界时不崩溃。
 */
interface AppGalleryPreviewMediaSource {

    /** 可预览项总数。 */
    val count: Int

    /** 取第 [index] 项 URI；越界或未提供返回 null（UI 显示占位）。 */
    fun uriAt(index: Int): String?

    /** 取第 [index] 项媒体类型；无法判定返回 [AppGalleryPreviewMediaKind.UNSUPPORTED]。 */
    fun kindAt(index: Int): AppGalleryPreviewMediaKind

    /** 取第 [index] 项的 PERSISTABLE MIME（供编辑 intent 使用）；未知返回 null。 */
    fun mimeAt(index: Int): String? = null

    /** 取第 [index] 项的管理域资源 id；未知返回 null。 */
    fun mediaIdAt(index: Int): String? = null

    /** 第 [index] 项的"处理前原图"URI（用于对比）；无则返回 null。 */
    fun compareOriginalUriAt(index: Int): String? = null

    /** 第 [index] 项关联的媒体 URI（用于视频书签查询）；默认同 [uriAt]。 */
    fun bookmarkTargetAt(index: Int): String? = uriAt(index)

    companion object {
        /** 空数据源（无项）。 */
        val EMPTY: AppGalleryPreviewMediaSource = object : AppGalleryPreviewMediaSource {
            override val count: Int = 0
            override fun uriAt(index: Int): String? = null
            override fun kindAt(index: Int): AppGalleryPreviewMediaKind = AppGalleryPreviewMediaKind.UNSUPPORTED
        }

        /**
         * 由入口请求构造：优先用 [AppGalleryPreviewRequest.sourceUris]（按序一一对应），
         * 缺失的位置回落到首项 [AppGalleryPreviewRequest.mediaUri]，保证当前项一定能渲染。
         */
        fun from(request: AppGalleryPreviewRequest): AppGalleryPreviewMediaSource {
            val normalized = request.normalized()
            val uris = ArrayList<String>(normalized.total)
            for (i in 0 until normalized.total) {
                val fromList = normalized.sourceUris.getOrNull(i)
                uris += when {
                    !fromList.isNullOrBlank() -> fromList
                    i == normalized.index -> normalized.mediaUri
                    else -> ""
                }
            }
            // 首项必须可渲染：列表中若计划位为空则用 mediaUri 兜底。
            if (uris.getOrNull(normalized.index).isNullOrBlank()) {
                uris[normalized.index] = normalized.mediaUri
            }
            val types = normalized.sourceTypes
            val ids = normalized.sourceIds
            return object : AppGalleryPreviewMediaSource {
                override val count: Int = normalized.total.coerceAtLeast(1)

                override fun uriAt(index: Int): String? =
                    uris.getOrNull(index)?.takeIf { it.isNotBlank() }

                override fun kindAt(index: Int): AppGalleryPreviewMediaKind {
                    val uri = uriAt(index) ?: return AppGalleryPreviewMediaKind.UNSUPPORTED
                    val mime = types.getOrNull(index)?.takeIf { it.isNotBlank() }
                    return AppGalleryPreviewMediaKindResolver.resolve(mime, uri)
                }

                override fun mimeAt(index: Int): String? = types.getOrNull(index)?.takeIf { it.isNotBlank() }

                override fun mediaIdAt(index: Int): String? = ids.getOrNull(index)?.takeIf { it.isNotBlank() }

                override fun compareOriginalUriAt(index: Int): String? =
                    if (index == normalized.index) normalized.compareOriginalUri else null
            }
        }

        /**
         * 单项数据源（仅当前项可预览，翻页时不显示相邻项）。
         * 用于管理域只提供单张 URI 的场景。
         */
        fun single(request: AppGalleryPreviewRequest): AppGalleryPreviewMediaSource =
            object : AppGalleryPreviewMediaSource {
                override val count: Int = request.normalized().total.coerceAtLeast(1)

                override fun uriAt(index: Int): String? =
                    if (index == request.normalized().index) request.mediaUri else null

                override fun kindAt(index: Int): AppGalleryPreviewMediaKind =
                    if (index == request.normalized().index) {
                        request.mediaKind
                    } else {
                        AppGalleryPreviewMediaKind.UNSUPPORTED
                    }

                override fun mimeAt(index: Int): String? =
                    if (index == request.normalized().index) request.mediaType else null

                override fun mediaIdAt(index: Int): String? =
                    if (index == request.normalized().index) request.mediaId else null

                override fun compareOriginalUriAt(index: Int): String? =
                    if (index == request.normalized().index) request.compareOriginalUri else null
            }
    }
}

/**
 * 快捷操作上下文的构造工具：把数据源的第 [index] 项与管理域能力合并为
 * [AppGalleryPreviewActionContext]，供 [AppGalleryPreviewActionPolicy] 判定可用性。
 */
object AppGalleryPreviewActionContextFactory {

    fun create(
        source: AppGalleryPreviewMediaSource,
        index: Int,
        quickEditSupports: Boolean = true,
        /** 管理域声明"是否支持对此类条目做 RAW 导出"。 */
        rawExportEnabled: Boolean = false,
        slideshowEnabled: Boolean = false,
        isFavorite: Boolean? = null,
        supportsFavorite: Boolean = true,
        supportsDelete: Boolean = true,
        supportsShare: Boolean = true,
        supportsInfo: Boolean = true,
        supportsEdit: Boolean = true,
        compareAvailable: Boolean = false,
    ): AppGalleryPreviewActionContext = AppGalleryPreviewActionContext(
        kind = source.kindAt(index),
        isFavorite = isFavorite,
        itemCount = source.count,
        supportsFavorite = supportsFavorite,
        supportsDelete = supportsDelete,
        supportsShare = supportsShare,
        supportsInfo = supportsInfo,
        supportsEdit = supportsEdit,
        supportsQuickEdit = quickEditSupports,
        compareAvailable = compareAvailable,
        supportsRawExport = rawExportEnabled,
        slideshowEnabled = slideshowEnabled,
    )
}
