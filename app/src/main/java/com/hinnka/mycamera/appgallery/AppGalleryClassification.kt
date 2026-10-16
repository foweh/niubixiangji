package com.hinnka.mycamera.appgallery

/**
 * 应用内置相册 · 媒体分类（相册项 2：按类型筛选与分类）。
 *
 * 分类来源（按优先级）：① 显式声明 [AppGalleryClassifyInput.persistedProcessingType]（DB 补丁列）
 * 或元数据 `customProperties["processingType"]` → ② 目录/文件名规则
 * （[AppGalleryDirectoryRules] / [AppGalleryNameRules]）→ ③ 既有 DB 元数据字段
 * （hasDng / isMotionPhoto / dynamicRangeProfile / hasEmbeddedGainmap / lutId / 胶片字段 /
 * mimeType / 视频标志）→ ④ 兜底 [NORMAL]。
 *
 * 旧数据没有 `processingType` 列时为 null，Kotlin 层按 [NORMAL] 兼容（不崩溃、不丢项）。
 */
enum class AppGalleryCategory(
    /** 中文短标签（用于筛选 chip；多语言由集成阶段补 strings.xml）。 */
    val labelZh: String,
    /** 是否为视频类目。 */
    val isVideoCategory: Boolean = false,
) {
    /** 普通照片。 */
    NORMAL("普通照片"),

    /** RAW / DNG。 */
    RAW_DNG("RAW/DNG"),

    /** 多帧合成（STACK 均值/中值/最大/最小等）。 */
    MULTI_FRAME("多帧合成"),

    /** HDR（HDR 合成 / UltraHDR 增益图 / 手动 HDR 效果）。 */
    HDR("HDR"),

    /** 超分结果（superres / multiframesr 输出）。 */
    SUPER_RES("超分结果"),

    /** 堆栈 / 星空 / 星轨 / 光绘（长曝光与天文类）。 */
    ASTRO_TRAIL("星空/星轨/光绘"),

    /** LUT / 胶片处理产物。 */
    LUT_FILM("LUT/胶片"),

    /** Log 视频。 */
    LOG_VIDEO("Log视频", isVideoCategory = true),

    /** 实况照片（Motion Photo）。 */
    LIVE_PHOTO("实况照片"),

    /** 全景。 */
    PANORAMA("全景"),

    /** GIF（动图）。 */
    GIF("GIF"),

    /** 普通视频。 */
    VIDEO("视频", isVideoCategory = true),

    /** 连拍（附加类目：既有 isBurstPhoto 元数据直接可用）。 */
    BURST("连拍"),

    /** 无法判定（保留位，正常流程不会产出；用于规则扩展后的未知值兼容）。 */
    UNKNOWN("未分类");

    companion object {
        /** 从持久化字符串安全解析（不区分大小写；未知值 → null）。 */
        fun fromPersistedName(name: String?): AppGalleryCategory? {
            if (name.isNullOrBlank()) return null
            val trimmed = name.trim()
            entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
            // 兼容中文标签持久化（用户手工编辑配置文件的情况）
            entries.firstOrNull { it.labelZh == trimmed }?.let { return it }
            return null
        }

        /** 筛选条展示顺序（普通照片在最后，便于统计查看）。 */
        val FILTER_ORDER: List<AppGalleryCategory> = listOf(
            RAW_DNG, MULTI_FRAME, HDR, SUPER_RES, ASTRO_TRAIL, LUT_FILM,
            LIVE_PHOTO, PANORAMA, GIF, LOG_VIDEO, VIDEO, BURST, NORMAL,
        )
    }
}

/**
 * 分类输入快照（纯 Kotlin，不依赖 Android；由 Android 侧 [AppGalleryItemMapper] 从
 * `MediaData` + `MediaMetadata` + DB 补丁列填充）。
 */
data class AppGalleryClassifyInput(
    /** 文件名（含扩展名），可为空（系统相册项可能拿不到）。 */
    val fileName: String? = null,
    /** 所属目录名/相对目录标记（来自目录扫描或 DB 补丁列 `relativeDirTag`）。 */
    val relativeDirTag: String? = null,
    val isVideo: Boolean = false,
    val mimeType: String? = null,
    val hasDng: Boolean = false,
    val hasBurstFrames: Boolean = false,
    val isMotionPhoto: Boolean = false,
    val lutId: String? = null,
    /** 烘焙胶片 stock id（spectralFilmStock）。 */
    val filmStockId: String? = null,
    /** 动态范围 profile（Dolby / HDR10 等，工程既有字段）。 */
    val dynamicRangeProfile: String? = null,
    val hasEmbeddedGainmap: Boolean = false,
    val manualHdrEffectEnabled: Boolean = false,
    /** 多帧合成的帧数（工程既有字段 multipleExposureFrameCount）。 */
    val multipleExposureFrameCount: Int? = null,
    val hasAiDenoisedBase: Boolean = false,
    /**
     * DB 补丁列 `processingType`（旧数据为 null → 按 [AppGalleryCategory.NORMAL] 兼容）。
     * 与 `customProperties["processingType"]` 等价，二者任一存在即按其分类。
     */
    val persistedProcessingType: String? = null,
    val captureMode: String? = null,
    val customProperties: Map<String, String> = emptyMap(),
)

/** 单条命名规则。 */
data class AppGalleryNameRule(
    val category: AppGalleryCategory,
    /** 小写匹配 token；[isRegex] = true 时按正则解释。 */
    val token: String,
    val isRegex: Boolean = false,
    /** 仅对视频生效（如 Log 视频规则）。 */
    val videoOnly: Boolean = false,
)

/**
 * 目录名 → 分类规则表（可扩展）。
 *
 * 这些目录名是**本模块约定的默认落盘目录标记**（超分/多帧超分/堆栈/HDR/星空/光绘/LUT 胶片/
 * Log 视频/实况/全景/GIF/RAW/连拍）；集成阶段通过补丁让写入方在保存时带上同名 `dirTag`
 * （或写入 DB 补丁列 `processingType`），分类即可 100% 命中，不依赖文件名猜测。
 */
object AppGalleryDirectoryRules {
    /** 目录名（小写）→ 分类。 */
    val DEFAULT: Map<String, AppGalleryCategory> = mapOf(
        "superres" to AppGalleryCategory.SUPER_RES,
        "super_res" to AppGalleryCategory.SUPER_RES,
        "sr" to AppGalleryCategory.SUPER_RES,
        "upscale" to AppGalleryCategory.SUPER_RES,
        "multiframesr" to AppGalleryCategory.SUPER_RES,
        "mfsr" to AppGalleryCategory.SUPER_RES,
        "stack" to AppGalleryCategory.MULTI_FRAME,
        "ps_stack" to AppGalleryCategory.MULTI_FRAME,
        "multiframe" to AppGalleryCategory.MULTI_FRAME,
        "multi_frame" to AppGalleryCategory.MULTI_FRAME,
        "hdr" to AppGalleryCategory.HDR,
        "bracket" to AppGalleryCategory.HDR,
        "ultrahdr" to AppGalleryCategory.HDR,
        "astro" to AppGalleryCategory.ASTRO_TRAIL,
        "startrail" to AppGalleryCategory.ASTRO_TRAIL,
        "star_trail" to AppGalleryCategory.ASTRO_TRAIL,
        "lightpainting" to AppGalleryCategory.ASTRO_TRAIL,
        "light_painting" to AppGalleryCategory.ASTRO_TRAIL,
        "longexposure" to AppGalleryCategory.ASTRO_TRAIL,
        "lut" to AppGalleryCategory.LUT_FILM,
        "film" to AppGalleryCategory.LUT_FILM,
        "spectral" to AppGalleryCategory.LUT_FILM,
        "log" to AppGalleryCategory.LOG_VIDEO,
        "logvideo" to AppGalleryCategory.LOG_VIDEO,
        "live" to AppGalleryCategory.LIVE_PHOTO,
        "livephoto" to AppGalleryCategory.LIVE_PHOTO,
        "motion" to AppGalleryCategory.LIVE_PHOTO,
        "pano" to AppGalleryCategory.PANORAMA,
        "panorama" to AppGalleryCategory.PANORAMA,
        "gif" to AppGalleryCategory.GIF,
        "raw" to AppGalleryCategory.RAW_DNG,
        "dng" to AppGalleryCategory.RAW_DNG,
        "burst" to AppGalleryCategory.BURST,
    )

    /** 归一化目录名（去空白、转小写、去掉常见分隔符）。 */
    fun normalizeDirTag(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        return value.replace('-', '_').replace(' ', '_')
    }

    /** 解析目录标记对应分类（未命中返回 null）。 */
    fun categoryForDir(raw: String?, table: Map<String, AppGalleryCategory> = DEFAULT): AppGalleryCategory? {
        val normalized = normalizeDirTag(raw) ?: return null
        table[normalized]?.let { return it }
        // 目录名可能是复合形态（如 "photo_superres_2026"），做包含匹配
        return table.entries
            .firstOrNull { (key, _) -> normalized.contains(key) }
            ?.value
    }
}

/** 文件名规则表（可扩展；全部按小写子串匹配，正则规则需显式声明）。 */
object AppGalleryNameRules {
    val DEFAULT: List<AppGalleryNameRule> = listOf(
        // 超分（含多帧超分）
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "superres"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "super_res"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "mfsr"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "_mfs"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "_sr"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "upscale_x"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "_x2_"),
        AppGalleryNameRule(AppGalleryCategory.SUPER_RES, "_x4_"),
        // 多帧合成 / PS 堆栈
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "ps_stack"),
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "stack"),
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "_mean"),
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "_median"),
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "multiframe"),
        AppGalleryNameRule(AppGalleryCategory.MULTI_FRAME, "multi_frame"),
        // HDR
        AppGalleryNameRule(AppGalleryCategory.HDR, "ultrahdr"),
        AppGalleryNameRule(AppGalleryCategory.HDR, "_hdr"),
        AppGalleryNameRule(AppGalleryCategory.HDR, "hdr_"),
        AppGalleryNameRule(AppGalleryCategory.HDR, "bracket"),
        // 星空 / 星轨 / 光绘
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "startrail"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "star_trail"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "star-trail"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "lightpaint"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "light_paint"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "astro"),
        AppGalleryNameRule(AppGalleryCategory.ASTRO_TRAIL, "trail"),
        // LUT / 胶片
        AppGalleryNameRule(AppGalleryCategory.LUT_FILM, "_lut"),
        AppGalleryNameRule(AppGalleryCategory.LUT_FILM, "lut_"),
        AppGalleryNameRule(AppGalleryCategory.LUT_FILM, "spectral"),
        AppGalleryNameRule(AppGalleryCategory.LUT_FILM, "film_"),
        AppGalleryNameRule(AppGalleryCategory.LUT_FILM, "_film"),
        // Log 视频（仅视频）
        AppGalleryNameRule(AppGalleryCategory.LOG_VIDEO, "log_", videoOnly = true),
        AppGalleryNameRule(AppGalleryCategory.LOG_VIDEO, "_log", videoOnly = true),
        AppGalleryNameRule(AppGalleryCategory.LOG_VIDEO, "logvideo", videoOnly = true),
        // 全景 / 实况 / GIF / RAW / 连拍
        AppGalleryNameRule(AppGalleryCategory.PANORAMA, "panorama"),
        AppGalleryNameRule(AppGalleryCategory.PANORAMA, "pano"),
        AppGalleryNameRule(AppGalleryCategory.LIVE_PHOTO, "motionphoto"),
        AppGalleryNameRule(AppGalleryCategory.LIVE_PHOTO, "livephoto"),
        AppGalleryNameRule(AppGalleryCategory.GIF, ".gif"),
        AppGalleryNameRule(AppGalleryCategory.RAW_DNG, ".dng"),
        AppGalleryNameRule(AppGalleryCategory.RAW_DNG, ".arw"),
        AppGalleryNameRule(AppGalleryCategory.RAW_DNG, ".cr2"),
        AppGalleryNameRule(AppGalleryCategory.RAW_DNG, ".nef"),
        AppGalleryNameRule(AppGalleryCategory.BURST, "_burst"),
    )

    /** 规则表上限（防配置文件塞爆内存）。 */
    const val MAX_RULES = 512

    /** 单条 token 最大长度。 */
    const val MAX_TOKEN_LENGTH = 96
}

/**
 * 分类器规则（开放参数 + clamp）。
 */
data class AppGalleryClassifierRules(
    /** 目录规则表（key 必须已是小写归一化形态）。 */
    val directoryRules: Map<String, AppGalleryCategory> = AppGalleryDirectoryRules.DEFAULT,
    /** 文件名规则表。 */
    val nameRules: List<AppGalleryNameRule> = AppGalleryNameRules.DEFAULT,
    /** 有 lutId 就归为 LUT/胶片？默认 false（避免把大量普通编辑照片都算成胶片产物）。 */
    val treatLutEditedAsFilmCategory: Boolean = false,
    /** 有 DNG 就归为 RAW/DNG？默认 true。 */
    val treatDngAsRawCategory: Boolean = true,
    /** Motion Photo 归为实况照片？默认 true。 */
    val treatMotionPhotoAsLivePhoto: Boolean = true,
    /** 有增益图/手动 HDR 归为 HDR？默认 true。 */
    val treatGainmapAsHdr: Boolean = true,
) {
    companion object {
        const val MAX_DIRECTORY_RULES = AppGalleryNameRules.MAX_RULES

        /** 归一化 + clamp：规则条数、token 长度、非法正则一律剔除，不抛异常。 */
        fun clamp(rules: AppGalleryClassifierRules): AppGalleryClassifierRules {
            val dirRules = rules.directoryRules.entries
                .asSequence()
                .mapNotNull { (key, value) ->
                    val normalized = AppGalleryDirectoryRules.normalizeDirTag(key) ?: return@mapNotNull null
                    if (normalized.length > AppGalleryNameRules.MAX_TOKEN_LENGTH) return@mapNotNull null
                    normalized to value
                }
                .take(MAX_DIRECTORY_RULES)
                .toMap()
            val nameRules = rules.nameRules
                .asSequence()
                .filter { it.token.isNotBlank() }
                .filter { it.token.length <= AppGalleryNameRules.MAX_TOKEN_LENGTH }
                .filter { rule ->
                    if (!rule.isRegex) true
                    else runCatching { Regex(rule.token) }.isSuccess
                }
                .take(AppGalleryNameRules.MAX_RULES)
                .toList()
            return rules.copy(directoryRules = dirRules, nameRules = nameRules)
        }
    }
}

/**
 * 分类器（纯 Kotlin，可 JVM 单测）。
 *
 * 判定顺序见 [AppGalleryCategory] 的 KDoc；同一媒体命中多条规则时取列表中**先出现**的规则，
 * 因此 [AppGalleryClassifierRules.nameRules] 的顺序即优先级。
 */
object AppGalleryClassifier {

    /**
     * 生产方声明产物类型的元数据键（写入 `MediaMetadata.customProperties` 即随既有
     * `customProperties` 列持久化，**无需数据库列补丁**；补丁列只是可选加速项）。
     * 取值接受 [AppGalleryCategory] 的枚举名（`SUPER_RES`）或中文标签（`超分结果`）。
     */
    const val CUSTOM_PROPERTY_PROCESSING_TYPE = "processingType"

    /** 生产方声明的目录标记键（可选，同 [CUSTOM_PROPERTY_PROCESSING_TYPE] 的持久化通道）。 */
    const val CUSTOM_PROPERTY_DIR_TAG = "relativeDirTag"

    fun classify(
        input: AppGalleryClassifyInput,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
    ): AppGalleryCategory {
        val clamped = AppGalleryClassifierRules.clamp(rules)

        // ① 显式声明优先：DB 补丁列 `processingType` ≥ 元数据 `customProperties["processingType"]`
        //    （生产模块无需数据库列即可声明产物类型；未声明时回退下面的规则判定）
        AppGalleryCategory.fromPersistedName(input.persistedProcessingType)?.let { return it }
        AppGalleryCategory.fromPersistedName(input.customProperties[CUSTOM_PROPERTY_PROCESSING_TYPE])
            ?.let { return it }

        // ② 目录规则（目录标记：元数据声明 > 扫描/DB 传入值）
        val dirTag = input.customProperties[CUSTOM_PROPERTY_DIR_TAG]?.takeIf { it.isNotBlank() }
            ?: input.relativeDirTag
        AppGalleryDirectoryRules.categoryForDir(dirTag, clamped.directoryRules)?.let {
            // Log 规则只对视频生效，避免目录名 "log" 把图片误判
            if (it != AppGalleryCategory.LOG_VIDEO || input.isVideo) return it
        }

        val fileName = input.fileName?.lowercase()?.trim().orEmpty()
        val mime = input.mimeType?.lowercase()?.trim().orEmpty()

        // ③ 文件名 / 扩展名规则
        if (fileName.isNotEmpty()) {
            clamped.nameRules.firstOrNull { rule ->
                if (rule.videoOnly && !input.isVideo) return@firstOrNull false
                if (rule.isRegex) {
                    runCatching { Regex(rule.token).containsMatchIn(fileName) }.getOrDefault(false)
                } else {
                    fileName.contains(rule.token.lowercase())
                }
            }?.let { return it.category }
        }

        // ④ MIME 判定
        if (mime == "image/gif") return AppGalleryCategory.GIF
        if (mime.contains("dng") || mime.contains("x-adobe-dng")) return AppGalleryCategory.RAW_DNG

        // ⑤ 既有元数据字段判定（顺序即优先级）
        // HDR 优先于 RAW：HDR 合成本身常常带 DNG
        if (clamped.treatGainmapAsHdr &&
            (input.hasEmbeddedGainmap || input.manualHdrEffectEnabled ||
                !input.dynamicRangeProfile.isNullOrBlank())
        ) {
            return AppGalleryCategory.HDR
        }
        if (input.multipleExposureFrameCount?.let { it > 1 } == true) {
            return AppGalleryCategory.MULTI_FRAME
        }
        if (input.isVideo) {
            return if (fileName.contains("log") || input.customProperties["LOG_PROFILE"] != null) {
                AppGalleryCategory.LOG_VIDEO
            } else {
                AppGalleryCategory.VIDEO
            }
        }
        if (clamped.treatMotionPhotoAsLivePhoto && input.isMotionPhoto) {
            return AppGalleryCategory.LIVE_PHOTO
        }
        if (clamped.treatLutEditedAsFilmCategory &&
            (!input.lutId.isNullOrBlank() || !input.filmStockId.isNullOrBlank())
        ) {
            return AppGalleryCategory.LUT_FILM
        }
        if (clamped.treatDngAsRawCategory && input.hasDng) return AppGalleryCategory.RAW_DNG
        if (!input.filmStockId.isNullOrBlank()) return AppGalleryCategory.LUT_FILM
        if (input.hasBurstFrames) return AppGalleryCategory.BURST
        if (input.hasAiDenoisedBase) return AppGalleryCategory.NORMAL

        return AppGalleryCategory.NORMAL
    }

    /**
     * 批量分类（列表映射；保持输入顺序，不抛异常）。
     */
    fun classifyAll(
        inputs: List<AppGalleryClassifyInput>,
        rules: AppGalleryClassifierRules = AppGalleryClassifierRules(),
    ): List<AppGalleryCategory> = inputs.map { classify(it, rules) }

    /**
     * 校验持久化分类值是否可用（供写入方在保存前调用）。
     */
    fun normalizePersisted(value: String?): String =
        (AppGalleryCategory.fromPersistedName(value) ?: AppGalleryCategory.NORMAL).name
}
