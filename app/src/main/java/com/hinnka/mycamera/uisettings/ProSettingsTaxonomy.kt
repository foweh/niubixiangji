package com.hinnka.mycamera.uisettings

/**
 * Pro 设置页的「分栏 / 模块 / 读写位置」三项字典。
 *
 * 只有本文件声明这些静态信息，[ProSettingBuilder] 与 [ProSettingsRegistry] 都从这里取，
 * 避免同一份映射写两遍。
 *
 * ### 关于跨包引用
 * 本文件**不引用**任何模块符号，纯静态描述；涉及模块常量（取值范围、键名）的只有各 `ProSettingsCatalog*`
 * 文件。这样模块改名时，只需改「目录文件」而不必动 UI 与模型。
 */
object ProSettingsTaxonomy {

    // ───────────────────────────────────────────────── 分栏

    const val SECTION_IMAGE = "sec.image"
    const val SECTION_SR = "sec.sr"
    const val SECTION_VIDEO = "sec.video"
    const val SECTION_FUN = "sec.fun"
    const val SECTION_LONG_EXPOSURE = "sec.longexp"
    const val SECTION_VIEWFINDER = "sec.viewfinder"
    const val SECTION_GALLERY = "sec.gallery"
    const val SECTION_AI = "sec.ai"
    const val SECTION_PLAN = "sec.plan"

    val sections: List<ProSectionSpec> = listOf(
        ProSectionSpec(SECTION_IMAGE, "画质增强", "CLAHE / 去雾 / 锐化 / 降噪 / 镜头校正", 0, "auto_fix"),
        ProSectionSpec(SECTION_SR, "超分与多帧", "端侧 AI 超分 + 多帧融合（手抖 / 低照增益）", 1, "hd"),
        ProSectionSpec(SECTION_VIDEO, "视频模式", "帧率 / 延时 / 防抖 / HDR / 编码 / 手动参数 / Log", 2, "videocam"),
        ProSectionSpec(SECTION_FUN, "趣味拍摄", "全景 / 声控 / 运动触发 / GIF / 间隔拍摄", 3, "auto_awesome"),
        ProSectionSpec(SECTION_LONG_EXPOSURE, "长曝光与星空", "光绘 / 暗帧 / 烟火 / 超级月亮 / 天文导航", 4, "nightlight"),
        ProSectionSpec(SECTION_VIEWFINDER, "取景与监看", "斑马纹 / 直方图 / 峰值对焦 / AR 贴纸", 5, "visibility"),
        ProSectionSpec(SECTION_GALLERY, "相册", "网格 / 缩略图 / 回收站 / 去重 / 地点聚类 / 预览", 6, "photo_library"),
        ProSectionSpec(SECTION_AI, "AI 与计算", "AI 端点 / 白平衡 / 场景识别 / 原生计算层", 7, "smart_toy"),
        ProSectionSpec(SECTION_PLAN, "规划中", "尚未落盘的模块（待对应模块落地后接线）", 8, "schedule"),
    )

    val sectionById: Map<String, ProSectionSpec> = sections.associateBy { it.id }

    // ───────────────────────────────────────────────── 模块

    /**
     * 模块清单。[ProModuleStatus.PENDING] 的模块只出占位节，不依赖其符号。
     */
    val modules: List<ProModuleSpec> = listOf(
        ProModuleSpec(
            "enhance", "画质增强", "com.hinnka.mycamera.enhance", ProModuleStatus.AVAILABLE,
            benchmark = "GCam / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "lenscorrection", "镜头畸变校正", "com.hinnka.mycamera.lenscorrection", ProModuleStatus.AVAILABLE,
            benchmark = "GCam / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "superres", "端侧 AI 超分", "com.hinnka.mycamera.superres", ProModuleStatus.AVAILABLE,
            benchmark = "Blackmagic Camera / MotionCam", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "multiframesr", "多帧超分", "com.hinnka.mycamera.multiframesr", ProModuleStatus.AVAILABLE,
            benchmark = "GCam（HDR+ / Super Res Zoom）", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "videomodes", "视频模式扩展", "com.hinnka.mycamera.videomodes", ProModuleStatus.AVAILABLE,
            benchmark = "Blackmagic Camera / MotionCam / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "logvideo", "Log 视频与 LUT", "com.hinnka.mycamera.logvideo", ProModuleStatus.AVAILABLE,
            benchmark = "Blackmagic Camera / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "funshot", "趣味拍摄", "com.hinnka.mycamera.funshot", ProModuleStatus.AVAILABLE,
            benchmark = "ProCam / MotionCam", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "longexposure", "长曝光", "com.hinnka.mycamera.longexposure", ProModuleStatus.AVAILABLE,
            benchmark = "ProCam / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "astronav", "天文导航", "com.hinnka.mycamera.astronav", ProModuleStatus.AVAILABLE,
            benchmark = "ProCam（星轨 / 银河对准）", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "viewfinder", "取景与监看", "com.hinnka.mycamera.viewfinder", ProModuleStatus.AVAILABLE,
            benchmark = "Blackmagic Camera / ProCam / OpenCamera", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "appgallery", "相册", "com.hinnka.mycamera.appgallery", ProModuleStatus.AVAILABLE,
            benchmark = "OpenCamera / 通用图库", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "appgallerypreview", "相册预览", "com.hinnka.mycamera.appgallerypreview", ProModuleStatus.AVAILABLE,
            benchmark = "通用图库浏览 / RAW 预览", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "aiplatform", "AI 能力平台", "com.hinnka.mycamera.aiplatform", ProModuleStatus.AVAILABLE,
            benchmark = "自研平台层", licenseNote = "自研",
        ),
        ProModuleSpec(
            "aiwb", "AI 白平衡", "com.hinnka.mycamera.aiwb", ProModuleStatus.AVAILABLE,
            benchmark = "GCam（AWB）/ Blackmagic Camera（WB 温度 · 色调）", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "scenerecog", "场景识别", "com.hinnka.mycamera.scenerecog", ProModuleStatus.AVAILABLE,
            benchmark = "GCam / ProCam", licenseNote = "功能对标，不采代码",
        ),
        ProModuleSpec(
            "nativecompute", "原生计算层", "com.hinnka.mycamera.nativecompute", ProModuleStatus.AVAILABLE,
            benchmark = "自研（JNI clamp 上界）", licenseNote = "自研",
        ),
        ProModuleSpec(
            "macrostack", "微距焦点堆叠", "com.hinnka.mycamera.focusstack", ProModuleStatus.AVAILABLE,
            benchmark = "PetteriAimonen/focus-stack（MIT, ★670）/ ProCam 微距（功能对标）",
            licenseNote = "复用开源改造（PetteriAimonen/focus-stack, MIT）+ 自研对齐/掩模；详见 changelog",
        ),
    )

    val moduleById: Map<String, ProModuleSpec> = modules.associateBy { it.id }

    // ───────────────────────────────────────────────── 读写位置

    /** 既有设置 DataStore（`data/UserPreferencesRepository.kt`）。 */
    val SLOT_USER_PREFS = ProStorageSlot(
        ProStorageKind.DATASTORE, "user_preferences",
        "既有设置 DataStore；键由各模块 Codec 定义",
    )

    val SLOT_SUPERRES = ProStorageSlot(
        ProStorageKind.DATASTORE, "superres_preferences",
        "SuperResConfigStore；单键 config（k=v 换行文本）",
    )

    val SLOT_AI_ENDPOINT = ProStorageSlot(
        ProStorageKind.DATASTORE, "ai_platform_preferences",
        "DataStoreAiConfigStore；键前缀 cap_<capability>；含密钥（keystore:v1: 密文）",
    )

    val SLOT_AI_FEATURE = ProStorageSlot(
        ProStorageKind.DATASTORE, "ai_feature_preferences",
        "AiParamStore；白平衡 / 场景识别业务参数",
    )

    val SLOT_FUNSHOT = ProStorageSlot(
        ProStorageKind.SHARED_PREFS, "funshot_config",
        "五组 Runtime 配置共用同一文件，用键前缀区分",
    )

    val SLOT_MFSR = ProStorageSlot(
        ProStorageKind.SHARED_PREFS, "mfsr_config",
        "MfsrPreferences.load / save",
    )

    val SLOT_APP_GALLERY = ProStorageSlot(
        ProStorageKind.DATASTORE, "app_gallery_preferences",
        "单键 config_payload（AppGalleryConfigCodec，全部参数打包）",
    )

    val SLOT_AGP = ProStorageSlot(
        ProStorageKind.INJECTED, "agp_*",
        "AppGalleryPreviewConfigCodec 统一前缀 agp_；容器挂点由集成层决定（补丁 CONFIG 节）",
    )

    val SLOT_VIEWFINDER = ProStorageSlot(
        ProStorageKind.DATASTORE, "user_preferences",
        "键名 viewfinder.*，由 ViewfinderSettingsCodec 编解码（Map<String,String> 可直接进 DataStore）",
    )

    val SLOT_ASTRONAV = ProStorageSlot(
        ProStorageKind.INJECTED, "AstroNavConfigProvider",
        "模块只声明 configProvider 注入点，未声明持久化键；本页键名约定 astronav.<field>",
    )

    val SLOT_NATIVE = ProStorageSlot(
        ProStorageKind.NONE, "-",
        "NativeComputeLimits 是跨 JNI 校验上界，不是持久化偏好；需可调则由集成层新增挂点",
    )

    val slotOfModule: Map<String, ProStorageSlot> = mapOf(
        "enhance" to SLOT_USER_PREFS,
        "lenscorrection" to SLOT_USER_PREFS,
        "superres" to SLOT_SUPERRES,
        "multiframesr" to SLOT_MFSR,
        "videomodes" to SLOT_USER_PREFS,
        "logvideo" to SLOT_USER_PREFS,
        "funshot" to SLOT_FUNSHOT,
        "longexposure" to SLOT_USER_PREFS,
        "astronav" to SLOT_ASTRONAV,
        "viewfinder" to SLOT_VIEWFINDER,
        "appgallery" to SLOT_APP_GALLERY,
        "appgallerypreview" to SLOT_AGP,
        "aiplatform" to SLOT_AI_ENDPOINT,
        "aiwb" to SLOT_AI_FEATURE,
        "scenerecog" to SLOT_AI_FEATURE,
        "nativecompute" to SLOT_NATIVE,
        "macrostack" to SLOT_USER_PREFS,
    )

    val sectionOfModule: Map<String, String> = mapOf(
        "enhance" to SECTION_IMAGE,
        "lenscorrection" to SECTION_IMAGE,
        "superres" to SECTION_SR,
        "multiframesr" to SECTION_SR,
        "videomodes" to SECTION_VIDEO,
        "logvideo" to SECTION_VIDEO,
        "funshot" to SECTION_FUN,
        "longexposure" to SECTION_LONG_EXPOSURE,
        "astronav" to SECTION_LONG_EXPOSURE,
        "viewfinder" to SECTION_VIEWFINDER,
        "appgallery" to SECTION_GALLERY,
        "appgallerypreview" to SECTION_GALLERY,
        "aiplatform" to SECTION_AI,
        "aiwb" to SECTION_AI,
        "scenerecog" to SECTION_AI,
        "nativecompute" to SECTION_AI,
        "macrostack" to SECTION_IMAGE,
    )

    /**
     * 允许「有真实设置项」的模块前缀集合。
     *
     * 用于 [ProSettingBuilder] 在遇到未登记前缀时**不抛异常**：moduleId 直接取 id 前缀，
     * sectionId 回落到「规划中」，并由 [ProSettingsRegistry.validate] 报出来（单测拦截笔误）。
     */
    fun moduleIdOf(settingId: String): String = settingId.substringBefore('.')

    fun sectionOf(moduleId: String): String = sectionOfModule[moduleId] ?: SECTION_PLAN

    fun slotOf(moduleId: String): ProStorageSlot =
        slotOfModule[moduleId] ?: ProStorageSlot(ProStorageKind.NONE, "-")

    /** 模块是否已登记。 */
    fun isKnownModule(moduleId: String): Boolean = moduleById.containsKey(moduleId)
}
