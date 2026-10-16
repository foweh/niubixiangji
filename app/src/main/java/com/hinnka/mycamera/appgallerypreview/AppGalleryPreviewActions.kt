package com.hinnka.mycamera.appgallerypreview

/** 预览页快捷操作标识（第 42 项）。 */
enum class AppGalleryPreviewActionId {
    /** 收藏/取消收藏。 */
    FAVORITE,

    /** 删除（进回收站）。 */
    DELETE,

    /** 分享/发送。 */
    SHARE,

    /** 详情信息面板。 */
    INFO,

    /** 二次编辑（发 EDIT_MEDIA intent 给画廊编辑模块）。 */
    EDIT,

    /** 快捷编辑（裁剪/旋转/亮度/对比度，发 QUICK_EDIT_MEDIA intent）。 */
    QUICK_EDIT,

    /** 幻灯片放映开关。 */
    SLIDESHOW,

    /** 原图/处理后对比开关。 */
    COMPARE,

    /** RAW→JPEG 导出。 */
    RAW_EXPORT,

    /** 视频/动图播放暂停。 */
    PLAY_PAUSE,

    /** 视频书签跳转入口。 */
    BOOKMARK,

    /** 另存为副本（导出当前渲染结果）。 */
    SAVE_AS,
}

/**
 * 一个可展示的快捷操作项。
 *
 * @param enabled false 时 UI 应置灰但仍展示（给出为什么不可用的反馈）
 * @param requiresConfirm 是否需二次确认（删除类）
 */
data class AppGalleryPreviewQuickAction(
    val id: AppGalleryPreviewActionId,
    val enabled: Boolean = true,
    val requiresConfirm: Boolean = false,
    /** 不可用原因（已本地化的 key 后缀，供 strings 拼接）；enabled=true 时为 null。 */
    val disabledReasonKey: String? = null,
)

/**
 * 快捷操作可用性上下文。
 *
 * 由管理域（appgallery 包）填充：它知道收藏状态、是否支持收藏/删除、是否在回收站等；
 * 本模块只消费该上下文做策略判定，不反向依赖管理域符号。
 */
data class AppGalleryPreviewActionContext(
    val kind: AppGalleryPreviewMediaKind,
    /** 当前项是否已收藏；不支持收藏时为 null。 */
    val isFavorite: Boolean? = null,
    /** 总数（用于判断是否允许放映/切换）。 */
    val itemCount: Int = 1,
    val supportsFavorite: Boolean = true,
    val supportsDelete: Boolean = true,
    val supportsShare: Boolean = true,
    val supportsInfo: Boolean = true,
    val supportsEdit: Boolean = true,
    val supportsQuickEdit: Boolean = true,
    /** 对比是否可用（见 [AppGalleryPreviewCompareMapping.availability]）。 */
    val compareAvailable: Boolean = false,
    /** 是否支持 RAW→JPEG 导出（仅 RAW 类型有意义）。 */
    val supportsRawExport: Boolean = false,
    val slideshowEnabled: Boolean = false,
)

/**
 * 快捷操作策略（纯函数，可 JVM 单测）。
 *
 * 自研理由：策略需要同时理解本模块的媒体类型/对比可用性/RAW 能力与管理域的权限能力，
 * 无通用库可复用；实现只是查表，不值得引入依赖。
 */
object AppGalleryPreviewActionPolicy {

    /**
     * 生成当前项应展示的快捷操作清单（顺序即 UI 顺序）。
     *
     * 规则：
     *  - 收藏/删除/分享/信息：按管理域能力开关；
     *  - 编辑/快捷编辑：静图与视频可用，RAW 仅在可导出时开放；
     *  - 对比：需 [AppGalleryPreviewActionContext.compareAvailable]；
     *  - RAW 导出：仅 RAW 类型且管理域声明支持；
     *  - 幻灯片：需管理域声明启用且项数 > 1；
     *  - 播放控制/书签：仅动效内容。
     */
    fun actions(context: AppGalleryPreviewActionContext): List<AppGalleryPreviewQuickAction> {
        val out = ArrayList<AppGalleryPreviewQuickAction>(12)
        val isRaw = AppGalleryPreviewMediaKindResolver.isRaw(context.kind)
        val isAnimated = AppGalleryPreviewMediaKindResolver.isAnimated(context.kind)

        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.FAVORITE,
            enabled = context.supportsFavorite,
            disabledReasonKey = if (context.supportsFavorite) null else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.DELETE,
            enabled = context.supportsDelete,
            requiresConfirm = true,
            disabledReasonKey = if (context.supportsDelete) null else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.SHARE,
            enabled = context.supportsShare,
            disabledReasonKey = if (context.supportsShare) null else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.INFO,
            enabled = context.supportsInfo,
            disabledReasonKey = if (context.supportsInfo) null else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.EDIT,
            enabled = context.supportsEdit,
            disabledReasonKey = if (context.supportsEdit) null else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.QUICK_EDIT,
            // RAW 需要先转出可编辑版本；这里与 RAW_EXPORT 能力联动。
            enabled = context.supportsQuickEdit && (!isRaw || context.supportsRawExport),
            disabledReasonKey = when {
                !context.supportsQuickEdit -> "unsupported"
                isRaw && !context.supportsRawExport -> "raw_requires_export"
                else -> null
            },
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.SLIDESHOW,
            enabled = context.slideshowEnabled &&
                context.itemCount > 1 &&
                AppGalleryPreviewMediaKindResolver.supportsSlideshow(context.kind),
            disabledReasonKey = when {
                context.itemCount <= 1 -> "single_item"
                !AppGalleryPreviewMediaKindResolver.supportsSlideshow(context.kind) -> "animated_kind"
                !context.slideshowEnabled -> "unsupported"
                else -> null
            },
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.COMPARE,
            enabled = context.compareAvailable,
            disabledReasonKey = if (context.compareAvailable) null else "needs_two_versions",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.RAW_EXPORT,
            enabled = isRaw && context.supportsRawExport,
            disabledReasonKey = if (!isRaw) "not_raw" else "unsupported",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.PLAY_PAUSE,
            enabled = isAnimated,
            disabledReasonKey = if (isAnimated) null else "not_animated",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.BOOKMARK,
            enabled = context.kind == AppGalleryPreviewMediaKind.VIDEO,
            disabledReasonKey = if (context.kind == AppGalleryPreviewMediaKind.VIDEO) null else "not_video",
        )
        out += AppGalleryPreviewQuickAction(
            id = AppGalleryPreviewActionId.SAVE_AS,
            enabled = context.kind == AppGalleryPreviewMediaKind.IMAGE || isRaw,
            disabledReasonKey = "unsupported",
        )
        return out
    }

    /** 只取可用项（用于紧凑工具栏）。 */
    fun enabledActions(context: AppGalleryPreviewActionContext): List<AppGalleryPreviewQuickAction> =
        actions(context).filter { it.enabled }

    /**
     * 收藏切换的乐观状态计算（管理域确认前先更新 UI）。
     * 收藏能力缺失或状态未知时返回 null，UI 应保持原状。
     */
    fun nextFavoriteState(context: AppGalleryPreviewActionContext): Boolean? {
        if (!context.supportsFavorite) return null
        val current = context.isFavorite ?: return null
        return !current
    }
}

/**
 * 管理域 ↔ 预览域的快捷操作回调契约（第 42 项）。
 *
 * 契约说明：预览域只定义接口与调用时机，**实现由管理核心域（appgallery 包）提供**并注入；
 * 预览域不引用管理域的任何符号，因此两个代理可独立落地（详见补丁 CODE_HOOKS 的注入点）。
 *
 * 所有回调应在主线程被调用；删除/分享等长耗时动作由实现方自行投递后台任务。
 */
interface AppGalleryPreviewActionHost {

    /**
     * 切换收藏状态。
     * @param mediaId 管理域的资源 id（预览域原样透传，不解析）
     * @return 切换后的收藏状态；不支持收藏时返回 null
     */
    fun onToggleFavorite(mediaId: String): Boolean?

    /**
     * 请求删除（管理域决定是直接删除还是移入回收站）。
     * @param mediaIds 待删除资源 id（可多选）
     * @return 是否受理（受理后管理域负责刷新列表与通知预览域页数变化）
     */
    fun onRequestDelete(mediaIds: List<String>): Boolean

    /** 请求分享/发送。 */
    fun onRequestShare(mediaIds: List<String>): Boolean

    /** 请求展示详情（EXIF/尺寸/路径等，由管理域的数据层提供）。 */
    fun onRequestInfo(mediaId: String): Boolean

    /** 读取当前收藏状态；未知返回 null。 */
    fun favoriteState(mediaId: String): Boolean?

    /** 失效订阅：当管理域侧数据变化（删除/收藏变更）时通知预览域刷新。 */
    fun observeInvalidation(onInvalidated: (List<String>) -> Unit): AutoCloseable
}
