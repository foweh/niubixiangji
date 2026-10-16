package com.hinnka.mycamera.appgallerypreview

/**
 * 快捷编辑预设（第 40 项）。预览页把用户意图作为 intent extra 传给画廊编辑模块，
 * 由编辑模块决定实际面板。
 */
enum class AppGalleryPreviewQuickEditPreset {
    /** 不指定，交给编辑模块默认面板。 */
    NONE,

    /** 裁剪。 */
    CROP,

    /** 旋转/翻转。 */
    ROTATE,

    /** 亮度。 */
    BRIGHTNESS,

    /** 对比度。 */
    CONTRAST,

    /** 透视校正。 */
    PERSPECTIVE,

    /** 滤镜/LUT。 */
    LOOK,
}

/**
 * 预览域 → 画廊编辑模块的 Intent 契约（第 39/40 项）。
 *
 * 契约说明：预览域**只发 intent**，不引用画廊编辑模块（`galleryedit` 包）的任何符号；
 * 编辑模块负责在 AndroidManifest/导航中声明对应 action 的入口。
 * 因此两个代理可独立落地，集成阶段把 action 注册到编辑模块即可。
 *
 * 键名与工程既有 action 命名风格一致（`com.hinnka.mycamera.action.*`）。
 */
object AppGalleryPreviewEditContract {

    /** 二次编辑（通用编辑入口，第 39 项）。 */
    const val ACTION_EDIT_MEDIA = "com.hinnka.mycamera.action.EDIT_MEDIA"

    /** 快捷编辑（携带预设，第 40 项）。 */
    const val ACTION_QUICK_EDIT_MEDIA = "com.hinnka.mycamera.action.QUICK_EDIT_MEDIA"

    /** 待编辑媒体 URI。 */
    const val EXTRA_MEDIA_URI = "media_uri"

    /** 待编辑媒体 MIME 类型。 */
    const val EXTRA_MEDIA_TYPE = "media_type"

    /** 管理域资源 id（编辑完成后回写用）。 */
    const val EXTRA_MEDIA_ID = "media_id"

    /** 处理前原图 URI（编辑模块用于生成"对比"与无损回退）。 */
    const val EXTRA_ORIGINAL_URI = "original_uri"

    /** 快捷编辑预设名（[AppGalleryPreviewQuickEditPreset] 的 name）。 */
    const val EXTRA_EDIT_PRESET = "edit_preset"

    /** 发起方标识，便于编辑模块区分来源。 */
    const val EXTRA_SOURCE = "source"

    /** 本模块作为发起方的标识值。 */
    const val SOURCE_VALUE = "appgallerypreview"

    /**
     * 构造 intent extras。
     *
     * @return uri 非法时返回 null（调用方应提示而不是崩溃）
     */
    fun editExtras(
        mediaUri: String?,
        mediaType: String?,
        mediaId: String? = null,
        originalUri: String? = null,
        preset: AppGalleryPreviewQuickEditPreset = AppGalleryPreviewQuickEditPreset.NONE,
    ): Map<String, String>? {
        val uri = mediaUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val out = LinkedHashMap<String, String>()
        out[EXTRA_MEDIA_URI] = uri
        out[EXTRA_MEDIA_TYPE] = mediaType?.trim()?.takeIf { it.isNotEmpty() }
            ?: AppGalleryPreviewMediaKindResolver.resolve(null, uri).name
        mediaId?.trim()?.takeIf { it.isNotEmpty() }?.let { out[EXTRA_MEDIA_ID] = it }
        originalUri?.trim()?.takeIf { it.isNotEmpty() }?.let { out[EXTRA_ORIGINAL_URI] = it }
        out[EXTRA_EDIT_PRESET] = preset.name
        out[EXTRA_SOURCE] = SOURCE_VALUE
        return out
    }

    /**
     * 解析回编辑参数（编辑模块侧可直接复用本函数，保证两端键名一致）。
     * uri 缺失返回 null。
     */
    fun parseEditExtras(extras: Map<String, String?>): AppGalleryPreviewEditParameters? {
        val uri = extras[EXTRA_MEDIA_URI]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val preset = extras[EXTRA_EDIT_PRESET]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { name -> AppGalleryPreviewQuickEditPreset.entries.firstOrNull { it.name == name.uppercase() } }
            ?: AppGalleryPreviewQuickEditPreset.NONE
        return AppGalleryPreviewEditParameters(
            mediaUri = uri,
            mediaType = extras[EXTRA_MEDIA_TYPE]?.trim()?.takeIf { it.isNotEmpty() },
            mediaId = extras[EXTRA_MEDIA_ID]?.trim()?.takeIf { it.isNotEmpty() },
            originalUri = extras[EXTRA_ORIGINAL_URI]?.trim()?.takeIf { it.isNotEmpty() },
            preset = preset,
        )
    }

    /**
     * 选择应发的 action：预设为 [AppGalleryPreviewQuickEditPreset.NONE] 时走通用编辑。
     */
    fun actionFor(preset: AppGalleryPreviewQuickEditPreset): String =
        if (preset == AppGalleryPreviewQuickEditPreset.NONE) ACTION_EDIT_MEDIA else ACTION_QUICK_EDIT_MEDIA
}

/** 解析出的编辑参数（供编辑模块消费，纯数据）。 */
data class AppGalleryPreviewEditParameters(
    val mediaUri: String,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val originalUri: String? = null,
    val preset: AppGalleryPreviewQuickEditPreset = AppGalleryPreviewQuickEditPreset.NONE,
)
