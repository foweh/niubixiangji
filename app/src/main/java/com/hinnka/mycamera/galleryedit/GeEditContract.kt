package com.hinnka.mycamera.galleryedit

/** 媒体类型（图片 / 视频）。 */
enum class GeMediaType(val rawValue: String) {
    IMAGE("image"),
    VIDEO("video"),
    ;

    /** 是否视频（含音频文件按视频处理）。 */
    val isVideo: Boolean get() = this == VIDEO

    companion object {
        /**
         * 解析 media_type extra。
         *
         * 容错：大小写、常见别名（photo/picture/pic → IMAGE；movie/clip/mp4 → VIDEO）。
         */
        fun fromRaw(raw: String?): GeMediaType? {
            val value = raw?.trim()?.lowercase().orEmpty()
            return when (value) {
                "image", "photo", "picture", "pic", "img" -> IMAGE
                "video", "movie", "clip", "film", "mp4" -> VIDEO
                else -> null
            }
        }

        /** 依据 MIME 推断（image/* → IMAGE，video/*、audio/* → VIDEO）。 */
        fun fromMime(mimeType: String?): GeMediaType? {
            val mime = mimeType?.trim()?.lowercase().orEmpty()
            return when {
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> VIDEO
                else -> null
            }
        }

        /** 依据 URI 扩展名推断（最后一道兜底）。 */
        fun fromUriExtension(uriString: String?): GeMediaType? {
            val lower = uriString?.lowercase().orEmpty()
            if (lower.isEmpty()) return null
            val path = lower.substringBefore('?').substringBefore('#')
            return when (path.substringAfterLast('.', missingDelimiterValue = "")) {
                "jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "bmp", "gif", "avif" -> IMAGE
                "mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "ts" -> VIDEO
                else -> null
            }
        }
    }
}

/** 一次编辑请求（由 EDIT_MEDIA intent 解析而来）。 */
data class GeEditRequest(
    val mediaUri: String,
    val mediaType: GeMediaType,
) {
    val isVideo: Boolean get() = mediaType.isVideo
}

/**
 * 跨代理编辑入口协议。
 *
 * **契约（必须遵守，只依赖本文件，不依赖任何其他代理的符号）**：
 * - action：`com.hinnka.mycamera.action.EDIT_MEDIA`
 * - extras：`media_uri`（String / Uri）、`media_type`（String：`image` | `video`）
 * - 独立相册 V4 以该 intent 调起本模块的编辑面板；
 * - 解析失败（action 不匹配 / uri 缺失）返回 null，调用方应静默忽略（不得崩溃）。
 *
 * [parse] 接收原始字符串而不是 `Intent`，因此可在纯 JVM 单测中完整覆盖。
 */
object GeEditContract {

    const val ACTION_EDIT_MEDIA = "com.hinnka.mycamera.action.EDIT_MEDIA"
    const val EXTRA_MEDIA_URI = "media_uri"
    const val EXTRA_MEDIA_TYPE = "media_type"

    const val MEDIA_TYPE_IMAGE = "image"
    const val MEDIA_TYPE_VIDEO = "video"

    /**
     * 解析编辑请求。
     *
     * @param action intent action（必须与 [ACTION_EDIT_MEDIA] 完全一致）。
     * @param mediaUriString `media_uri` extra 的字符串化值。
     * @param mediaTypeString `media_type` extra 的原始值（可为 null）。
     * @param mimeType intent type（可为 null，用于兜底推断）。
     * @return 合法请求；任一项不满足时返回 null。
     */
    fun parse(
        action: String?,
        mediaUriString: String?,
        mediaTypeString: String? = null,
        mimeType: String? = null,
    ): GeEditRequest? {
        if (action != ACTION_EDIT_MEDIA) return null
        val uri = mediaUriString?.trim().orEmpty()
        if (uri.isEmpty()) return null
        val type = GeMediaType.fromRaw(mediaTypeString)
            ?: GeMediaType.fromMime(mimeType)
            ?: GeMediaType.fromUriExtension(uri)
            ?: return null
        return GeEditRequest(mediaUri = uri, mediaType = type)
    }

    /** 当前 intent 是否为编辑入口（用于 Activity 快速判断）。 */
    fun isEditMediaAction(action: String?): Boolean = action == ACTION_EDIT_MEDIA

    /** 构造调起用的 extras（供 V4 或本模块测试用；键名即契约）。 */
    fun buildExtras(mediaUriString: String, mediaType: GeMediaType): Map<String, String> = mapOf(
        EXTRA_MEDIA_URI to mediaUriString,
        EXTRA_MEDIA_TYPE to mediaType.rawValue,
    )
}

/** 分享目标类型。 */
enum class GeShareTarget { TEXT, IMAGE, VIDEO }

/** 前后对比输出形式。 */
enum class GeCompareOutput {
    /** 左右并排（原图 | 处理图）。 */
    SIDE_BY_SIDE,

    /** 中间分割线：左半原图 + 右半处理图。 */
    SPLIT,
}

/**
 * 前后对比配置（编辑面板滑杆预览 + 导出对比图共用）。
 *
 * @param split 预览分割位置 0..1（滑杆）。
 * @param orientation true = 垂直分割线（左右对比），false = 水平分割线（上下对比）。
 * @param output 导出形式。
 * @param drawLabels 是否在导出图上标注"原图 / 处理后"。
 * @param gapPx 并排模式下的间隙像素。
 */
data class GeCompareConfig(
    val split: Float = GeEditRanges.COMPARE_SPLIT_DEFAULT,
    val orientation: Boolean = true,
    val output: GeCompareOutput = GeCompareOutput.SIDE_BY_SIDE,
    val drawLabels: Boolean = GeEditRanges.COMPARE_LABEL_DEFAULT,
    val gapPx: Int = 0,
) {
    fun clamp(): Pair<GeCompareConfig, GeClampReport> {
        val safeSplit = GeEditRanges.clampFloat(
            split,
            GeEditRanges.OPACITY_MIN,
            GeEditRanges.OPACITY_MAX,
            GeEditRanges.COMPARE_SPLIT_DEFAULT,
        )
        val gapCorrections = mutableListOf<GeClampCorrection>()
        val safeGap = gapPx.coerceIn(0, MAX_GAP_PX)
        GeClampReport.of("compare.gapPx", gapPx.toDouble(), safeGap.toDouble())?.let(gapCorrections::add)
        GeClampReport.of("compare.split", split.toDouble(), safeSplit.toDouble())?.let(gapCorrections::add)
        return copy(split = safeSplit, gapPx = safeGap) to GeClampReport(gapCorrections)
    }

    companion object {
        /** 并排间隙上限（像素）。 */
        const val MAX_GAP_PX = 128
    }
}

/**
 * 分享请求（纯模型 + 纯校验；Intent 构造在 Android 层）。
 *
 * @param applyEdits 是否套用当前编辑再分享（用户要求可勾选）。
 * @param text 文本分享内容。
 * @param mediaUris 媒体 uri 列表（文本分享时忽略）。
 */
data class GeShareRequest(
    val target: GeShareTarget,
    val text: String? = null,
    val mediaUris: List<String> = emptyList(),
    val applyEdits: Boolean = false,
) {
    /** 是否可分享（文本分享不要求 uri；媒体分享至少一条）。 */
    fun isShareable(): Boolean = when (target) {
        GeShareTarget.TEXT -> !text.isNullOrBlank() || mediaUris.isNotEmpty()
        GeShareTarget.IMAGE, GeShareTarget.VIDEO -> mediaUris.any { it.isNotBlank() }
    }

    fun clamp(): Pair<GeShareRequest, GeClampReport> {
        val safeText = text?.take(MAX_TEXT_LENGTH)
        val corrections = mutableListOf<GeClampCorrection>()
        if (safeText != null && text != null && safeText.length != text.length) {
            corrections.add(GeClampCorrection("share.text.length", text.length.toDouble(), safeText.length.toDouble()))
        }
        val safeUris = mediaUris.filter { it.isNotBlank() }.distinct().take(MAX_SHARE_ITEMS)
        if (safeUris.size != mediaUris.size) {
            corrections.add(
                GeClampCorrection("share.items", mediaUris.size.toDouble(), safeUris.size.toDouble())
            )
        }
        return copy(text = safeText, mediaUris = safeUris) to GeClampReport(corrections)
    }

    companion object {
        /** 文本长度上限。 */
        const val MAX_TEXT_LENGTH = 2000

        /** 单次分享条目上限（与系统 ACTION_SEND_MULTIPLE 实践一致）。 */
        const val MAX_SHARE_ITEMS = 100

        /** 解析后的 MIME（媒体分享用）。 */
        fun mimeFor(target: GeShareTarget): String = when (target) {
            GeShareTarget.TEXT -> "text/plain"
            GeShareTarget.IMAGE -> "image/*"
            GeShareTarget.VIDEO -> "video/*"
        }
    }
}
