package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.aiplatform.AiCapability
import com.hinnka.mycamera.aiplatform.AiEndpointConfig
import com.hinnka.mycamera.aiwb.AiWhiteBalanceConfig
import com.hinnka.mycamera.aiwb.AiWhiteBalanceSettings
import com.hinnka.mycamera.appgallery.AppGalleryConfig
import com.hinnka.mycamera.appgallery.AppGallerySortField
import com.hinnka.mycamera.appgallery.AppGalleryThemeMode
import com.hinnka.mycamera.appgallery.AppGalleryViewMode
import com.hinnka.mycamera.appgallery.TimelineGranularity
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfigCodec
import com.hinnka.mycamera.appgallerypreview.PreviewOrientationLock
import com.hinnka.mycamera.appgallerypreview.PreviewScaleRenderer
import com.hinnka.mycamera.nativecompute.NativeComputeLimits
import com.hinnka.mycamera.scenerecog.SceneRecognitionConfig
import com.hinnka.mycamera.scenerecog.SceneRecognitionSettings
import com.hinnka.mycamera.viewfinder.ViewfinderParamRanges

/**
 * 目录⑤：取景与监看（`viewfinder`）+ 相册（`appgallery`）+ 相册预览（`appgallerypreview`）
 * + AI 与计算（`aiplatform` / `aiwb` / `scenerecog` / `nativecompute`）。
 *
 * 键名说明：
 * - viewfinder / appgallery：键为模块已定义的 `viewfinder.*` 字面量与 `config_payload` 打包键。
 * - appgallerypreview：键 = `AppGalleryPreviewConfigCodec.KEY_PREFIX + <内部字段名>`（直接引用常量）。
 * - aiplatform：键 = `AiCapability.storagePrefix + ".<字段>"`（本页以 WHITE_BALANCE 为例展示）。
 * - aiwb / scenerecog：键 = 模块 `KEY_*` 常量。
 * - nativecompute：`NativeComputeLimits` 是跨 JNI 校验上界（非持久化偏好），storage 记为「内存态」。
 */
object ProSettingsCatalogUtility {

    /** 相册打包键（AppGallerySettingsStore 的 `config_payload`）。 */
    private fun gk(field: String): String = "config_payload :: $field"

    /** 相册预览的内部键（直接引用模块前缀常量）。 */
    private fun pk(inner: String): String = AppGalleryPreviewConfigCodec.KEY_PREFIX + inner

    /** AI 端点键（以 WHITE_BALANCE 能力为例展示前缀规则）。 */
    private fun ck(field: String): String = AiCapability.WHITE_BALANCE.storagePrefix + "." + field

    val entries: List<ProSettingSpec> = buildList {

        // ═════════════════════════════════════════ 斑马纹（viewfinder.zebra.*）

        add(ProSettingBuilder.sw("viewfinder.zebra.enabled", "viewfinder.zebra.enabled", "斑马纹",
            "高光超阈值区域的条纹提示；默认关，不打扰取景", false, "BMD,ProCam"))
        add(ProSettingBuilder.fl("viewfinder.zebra.thresholdPercent", "viewfinder.zebra.threshold_percent",
            "触发阈值", "百分比；>100% 在 8bit 源上等于 255", 95f,
            ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MIN,
            ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MAX, 1, "%", "BMD"))
        add(ProSettingBuilder.it("viewfinder.zebra.stripeWidthPx", "viewfinder.zebra.stripe_width_px",
            "条纹宽度", "预览像素", 12,
            ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MIN.toInt(),
            ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MAX.toInt(), 1, "px", "BMD"))
        add(ProSettingBuilder.fl("viewfinder.zebra.stripeDuty", "viewfinder.zebra.stripe_duty", "条纹占空比",
            "0.1..0.9", 0.5f,
            ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MIN, ViewfinderParamRanges.ZEBRA_STRIPE_DUTY_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.fl("viewfinder.zebra.stripeAngleDeg", "viewfinder.zebra.stripe_angle_deg",
            "条纹角度", "度", 45f,
            ViewfinderParamRanges.ZEBRA_STRIPE_ANGLE_MIN, ViewfinderParamRanges.ZEBRA_STRIPE_ANGLE_MAX, 1, "°", "BMD"))
        add(ProSettingBuilder.fl("viewfinder.zebra.stripeAlpha", "viewfinder.zebra.stripe_alpha", "条纹不透明度",
            "0..1", 0.85f,
            ViewfinderParamRanges.ZEBRA_STRIPE_ALPHA_MIN, ViewfinderParamRanges.ZEBRA_STRIPE_ALPHA_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.dd("viewfinder.zebra.metric", "viewfinder.zebra.metric", "判定基准",
            "MAX_RGB 更易在高饱和纯色处触发", "LUMA", listOf("LUMA", "MAX_RGB"), "BMD"))

        // ═════════════════════════════════════════ 实时直方图（viewfinder.histogram.*）

        add(ProSettingBuilder.sw("viewfinder.histogram.enabled", "viewfinder.histogram.enabled", "实时直方图",
            "预览叠加直方图；默认关", false, "ProCam,BMD"))
        add(ProSettingBuilder.dd("viewfinder.histogram.binCount", "viewfinder.histogram.bin_count", "分箱数",
            "非法值就近吸附（96→64，100→128）", "256", listOf("64", "128", "256"), "ProCam"))
        add(ProSettingBuilder.lt("viewfinder.histogram.sampleIntervalMs", "viewfinder.histogram.sample_interval_ms",
            "采样间隔", "毫秒（节流）", 300L,
            ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS,
            ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS, "ms", "ProCam"))
        add(ProSettingBuilder.dd("viewfinder.histogram.anchor", "viewfinder.histogram.anchor", "叠加方位",
            "四角 + 上中", "TOP_RIGHT",
            listOf("TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"), "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.histogram.marginFraction", "viewfinder.histogram.margin_fraction",
            "边缘留白", "屏幕短边比例", 0.04f,
            ViewfinderParamRanges.HISTOGRAM_MARGIN_MIN, ViewfinderParamRanges.HISTOGRAM_MARGIN_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.histogram.widthFraction", "viewfinder.histogram.width_fraction",
            "宽度占比", "占预览宽度", 0.28f,
            ViewfinderParamRanges.HISTOGRAM_WIDTH_FRACTION_MIN,
            ViewfinderParamRanges.HISTOGRAM_WIDTH_FRACTION_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.histogram.heightFraction", "viewfinder.histogram.height_fraction",
            "高度占比", "占预览高度", 0.14f,
            ViewfinderParamRanges.HISTOGRAM_HEIGHT_FRACTION_MIN,
            ViewfinderParamRanges.HISTOGRAM_HEIGHT_FRACTION_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.histogram.alpha", "viewfinder.histogram.alpha", "不透明度",
            "0.1..1", 0.72f,
            ViewfinderParamRanges.HISTOGRAM_ALPHA_MIN, ViewfinderParamRanges.HISTOGRAM_ALPHA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.histogram.smoothing", "viewfinder.histogram.smoothing",
            "EMA 平滑", "0 = 不平滑", 0.3f,
            ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MIN,
            ViewfinderParamRanges.HISTOGRAM_SMOOTHING_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.sw("viewfinder.histogram.showLuma", "viewfinder.histogram.show_luma",
            "显示亮度通道", "", true, "ProCam"))
        add(ProSettingBuilder.sw("viewfinder.histogram.showRed", "viewfinder.histogram.show_red",
            "显示红通道", "", true, "ProCam"))
        add(ProSettingBuilder.sw("viewfinder.histogram.showGreen", "viewfinder.histogram.show_green",
            "显示绿通道", "", true, "ProCam"))
        add(ProSettingBuilder.sw("viewfinder.histogram.showBlue", "viewfinder.histogram.show_blue",
            "显示蓝通道", "", true, "ProCam"))

        // ═════════════════════════════════════════ 峰值对焦（viewfinder.peaking.*）

        add(ProSettingBuilder.sw("viewfinder.peaking.enabled", "viewfinder.peaking.enabled", "峰值对焦",
            "默认与既有 focusPeakingEnabled 一致", true, "BMD,OpenCam"))
        add(ProSettingBuilder.fl("viewfinder.peaking.sensitivity", "viewfinder.peaking.sensitivity",
            "灵敏度", "0.5 → 阈值 0.80（刻意等于既有硬编码值）",
            ViewfinderParamRanges.PEAKING_SENSITIVITY_DEFAULT,
            ViewfinderParamRanges.PEAKING_SENSITIVITY_MIN,
            ViewfinderParamRanges.PEAKING_SENSITIVITY_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.num("viewfinder.peaking.thresholdOverride", "viewfinder.peaking.threshold_override",
            "阈值覆盖", "-1 = 自动（按灵敏度推导）；否则 0.05..0.99",
            ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO,
            ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO,
            ViewfinderParamRanges.PEAKING_THRESHOLD_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.fl("viewfinder.peaking.mixStrength", "viewfinder.peaking.mix_strength",
            "混合强度", "增强着色器", ViewfinderParamRanges.PEAKING_MIX_DEFAULT,
            ViewfinderParamRanges.PEAKING_MIX_MIN, ViewfinderParamRanges.PEAKING_MIX_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.fl("viewfinder.peaking.stepScale", "viewfinder.peaking.step_scale",
            "采样步长倍数", "越大越省但越粗", ViewfinderParamRanges.PEAKING_STEP_SCALE_DEFAULT,
            ViewfinderParamRanges.PEAKING_STEP_SCALE_MIN,
            ViewfinderParamRanges.PEAKING_STEP_SCALE_MAX, 1, "", "BMD"))

        // ═════════════════════════════════════════ AR 趣味贴纸（viewfinder.sticker.*）

        add(ProSettingBuilder.sw("viewfinder.sticker.enabled", "viewfinder.sticker.enabled", "AR 趣味贴纸",
            "人脸锚定贴纸；默认关", false, "ProCam"))
        add(ProSettingBuilder.tx("viewfinder.sticker.id", "viewfinder.sticker.id", "贴纸 id",
            "未注册 id 回落 cat_ears", "cat_ears", 64, "ProCam"))
        add(ProSettingBuilder.dd("viewfinder.sticker.anchorMode", "viewfinder.sticker.anchor_mode", "锚定部位",
            "额头 / 鼻 / 嘴 / 左右眼", "FOREHEAD",
            listOf("FOREHEAD", "NOSE", "MOUTH", "LEFT_EYE", "RIGHT_EYE"), "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.sticker.scale", "viewfinder.sticker.scale", "缩放",
            "0.2..4", ViewfinderParamRanges.STICKER_SCALE_DEFAULT,
            ViewfinderParamRanges.STICKER_SCALE_MIN, ViewfinderParamRanges.STICKER_SCALE_MAX, 2, "×", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.sticker.offsetX", "viewfinder.sticker.offset_x", "水平偏移",
            "相对人脸宽度", ViewfinderParamRanges.STICKER_OFFSET_DEFAULT,
            ViewfinderParamRanges.STICKER_OFFSET_MIN, ViewfinderParamRanges.STICKER_OFFSET_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.sticker.offsetY", "viewfinder.sticker.offset_y", "垂直偏移",
            "相对人脸宽度", ViewfinderParamRanges.STICKER_OFFSET_DEFAULT,
            ViewfinderParamRanges.STICKER_OFFSET_MIN, ViewfinderParamRanges.STICKER_OFFSET_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.sw("viewfinder.sticker.mirror", "viewfinder.sticker.mirror", "水平镜像",
            "", false, "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.sticker.alpha", "viewfinder.sticker.alpha", "不透明度",
            "0..1", ViewfinderParamRanges.STICKER_ALPHA_DEFAULT,
            ViewfinderParamRanges.STICKER_ALPHA_MIN, ViewfinderParamRanges.STICKER_ALPHA_MAX, 2, "", "ProCam"))
        add(ProSettingBuilder.it("viewfinder.sticker.maxFaces", "viewfinder.sticker.max_faces", "最大贴纸人脸数",
            "1..5", ViewfinderParamRanges.STICKER_MAX_FACES_DEFAULT,
            ViewfinderParamRanges.STICKER_MAX_FACES_MIN,
            ViewfinderParamRanges.STICKER_MAX_FACES_MAX, 1, "人", "ProCam"))
        add(ProSettingBuilder.lt("viewfinder.sticker.detectIntervalMs", "viewfinder.sticker.detect_interval_ms",
            "检测节流间隔", "毫秒", ViewfinderParamRanges.STICKER_INTERVAL_DEFAULT_MS,
            ViewfinderParamRanges.STICKER_INTERVAL_MIN_MS,
            ViewfinderParamRanges.STICKER_INTERVAL_MAX_MS, "ms", "ProCam"))
        add(ProSettingBuilder.fl("viewfinder.sticker.smoothing", "viewfinder.sticker.smoothing",
            "锚点 EMA 平滑", "0..0.95", ViewfinderParamRanges.STICKER_SMOOTHING_DEFAULT,
            ViewfinderParamRanges.STICKER_SMOOTHING_MIN,
            ViewfinderParamRanges.STICKER_SMOOTHING_MAX, 2, "", "ProCam"))

        // ═════════════════════════════════════════ 相册（app_gallery_preferences / config_payload）

        add(ProSettingBuilder.dd("appgallery.themeMode", gk("themeMode"), "主题",
            "跟随系统 / 浅色 / 深色", AppGalleryThemeMode.FOLLOW_SYSTEM.name,
            AppGalleryThemeMode.entries.map { it.name }, "OpenCam"))
        add(ProSettingBuilder.dd("appgallery.defaultViewMode", gk("defaultViewMode"), "默认视图",
            "网格 / 列表 / 时间线", AppGalleryViewMode.GRID.name,
            AppGalleryViewMode.entries.map { it.name }, "OpenCam"))
        add(ProSettingBuilder.dd("appgallery.timelineGranularity", gk("timelineGranularity"), "时间线粒度",
            "时间线视图的聚类粒度", TimelineGranularity.DAY.name,
            TimelineGranularity.entries.map { it.name }, "OpenCam"))
        add(ProSettingBuilder.dd("appgallery.defaultSortField", gk("defaultSortField"), "默认排序字段",
            "非法值回落默认", AppGallerySortField.DATE.name,
            AppGallerySortField.entries.map { it.name }, "OpenCam"))
        add(ProSettingBuilder.it("appgallery.gridColumnCount", gk("gridColumnCount"), "网格列数",
            "列", AppGalleryConfig().gridColumnCount,
            AppGalleryConfig.GRID_COLUMN_MIN, AppGalleryConfig.GRID_COLUMN_MAX, 1, "列", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.listRowHeightDp", gk("listRowHeightDp"), "列表行高",
            "dp", 72, AppGalleryConfig.LIST_ROW_HEIGHT_MIN_DP,
            AppGalleryConfig.LIST_ROW_HEIGHT_MAX_DP, 8, "dp", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.viewMemorySlots", gk("viewMemorySlots"), "视图记忆槽位",
            "记住每目录的视图配置数", 16,
            AppGalleryConfig.VIEW_MEMORY_SLOTS_MIN, AppGalleryConfig.VIEW_MEMORY_SLOTS_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.thumbnailMaxEdgePx", gk("thumbnailMaxEdgePx"), "缩略图长边上限",
            "像素", 512, AppGalleryConfig.THUMBNAIL_EDGE_MIN,
            AppGalleryConfig.THUMBNAIL_EDGE_MAX, 64, "px", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.thumbnailQuality", gk("thumbnailQuality"), "缩略图质量",
            "40..100", 85, AppGalleryConfig.THUMBNAIL_QUALITY_MIN,
            AppGalleryConfig.THUMBNAIL_QUALITY_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.thumbnailPrefetchDistance", gk("thumbnailPrefetchDistance"),
            "缩略图预取距离", "屏幕外的预取条目数", 12,
            AppGalleryConfig.THUMBNAIL_PREFETCH_MIN, AppGalleryConfig.THUMBNAIL_PREFETCH_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.thumbnailGenerationBatch", gk("thumbnailGenerationBatch"),
            "缩略图生成批大小", "每批生成数量", 32,
            AppGalleryConfig.THUMBNAIL_BATCH_MIN, AppGalleryConfig.THUMBNAIL_BATCH_MAX, 8, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.trashRetentionDays", gk("trashRetentionDays"), "回收站保留天数",
            "超过自动清空", 30, AppGalleryConfig.TRASH_RETENTION_MIN_DAYS,
            AppGalleryConfig.TRASH_RETENTION_MAX_DAYS, 1, "天", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.trashPurgeCheckIntervalHours", gk("trashPurgeCheckIntervalHours"),
            "回收站清理检查间隔", "小时", 12,
            AppGalleryConfig.TRASH_PURGE_INTERVAL_MIN_HOURS,
            AppGalleryConfig.TRASH_PURGE_INTERVAL_MAX_HOURS, 1, "h", "OpenCam"))
        add(ProSettingBuilder.dd("appgallery.duplicateHashSize", gk("duplicateHashSize"), "去重哈希位数",
            "仅 8 或 16（就近吸附）", "8", listOf("8", "16"), "OpenCam"))
        add(ProSettingBuilder.it("appgallery.duplicateResizeEdge", gk("duplicateResizeEdge"), "去重缩略边长",
            "像素", 32, AppGalleryConfig.DUPLICATE_RESIZE_EDGE_MIN,
            AppGalleryConfig.DUPLICATE_RESIZE_EDGE_MAX, 8, "px", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.duplicateHammingThreshold", gk("duplicateHammingThreshold"),
            "汉明距离阈值", "8 位哈希 0..64 / 16 位 0..256", 10, 0, 256, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.duplicateScanBatch", gk("duplicateScanBatch"), "去重扫描批大小",
            "每批处理条目数", 24, AppGalleryConfig.DUPLICATE_SCAN_BATCH_MIN,
            AppGalleryConfig.DUPLICATE_SCAN_BATCH_MAX, 8, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.searchDebounceMs", gk("searchDebounceMs"), "搜索防抖",
            "毫秒；0 = 不防抖", 250, AppGalleryConfig.SEARCH_DEBOUNCE_MIN_MS,
            AppGalleryConfig.SEARCH_DEBOUNCE_MAX_MS, 50, "ms", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.maxRating", gk("maxRating"), "最大评分",
            "星级上限", 5, AppGalleryConfig.MAX_RATING_MIN, AppGalleryConfig.MAX_RATING_MAX, 1, "星", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.maxTagCount", gk("maxTagCount"), "标签数量上限",
            "每张照片", 20, AppGalleryConfig.MAX_TAG_COUNT_MIN,
            AppGalleryConfig.MAX_TAG_COUNT_MAX, 5, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.maxTagLength", gk("maxTagLength"), "标签长度上限",
            "字符", 24, AppGalleryConfig.MAX_TAG_LENGTH_MIN,
            AppGalleryConfig.MAX_TAG_LENGTH_MAX, 4, "字", "OpenCam"))
        add(ProSettingBuilder.fl("appgallery.placeClusterRadiusKm", gk("placeClusterRadiusKm"), "地点聚类半径",
            "公里", 25f, AppGalleryConfig.PLACE_RADIUS_MIN_KM,
            AppGalleryConfig.PLACE_RADIUS_MAX_KM, 1, "km", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.placeClusterGridDecimals", gk("placeClusterGridDecimals"),
            "地点网格小数位", "0..4（越大越细）", 1,
            AppGalleryConfig.PLACE_DECIMALS_MIN, AppGalleryConfig.PLACE_DECIMALS_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.batchOperationBatchSize", gk("batchOperationBatchSize"),
            "批处理批大小", "批量操作每批条目数", 16,
            AppGalleryConfig.BATCH_SIZE_MIN, AppGalleryConfig.BATCH_SIZE_MAX, 8, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.exportZipCompressionLevel", gk("exportZipCompressionLevel"),
            "导出 ZIP 压缩级别", "0 = 不压缩，9 = 最高", 6,
            AppGalleryConfig.ZIP_LEVEL_MIN, AppGalleryConfig.ZIP_LEVEL_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.exportMaxNameLength", gk("exportMaxNameLength"), "导出文件名长度上限",
            "字符", 96, AppGalleryConfig.EXPORT_NAME_MIN_LENGTH,
            AppGalleryConfig.EXPORT_NAME_MAX_LENGTH, 8, "字", "OpenCam"))
        add(ProSettingBuilder.it("appgallery.autoRescanIntervalMinutes", gk("autoRescanIntervalMinutes"),
            "自动重扫间隔", "分钟；0 = 关闭", 5,
            AppGalleryConfig.AUTO_RESCAN_MIN_MINUTES, AppGalleryConfig.AUTO_RESCAN_MAX_MINUTES, 5, "min", "OpenCam"))

        // ═════════════════════════════════════════ 相册预览（appgallerypreview / agp_*）

        add(ProSettingBuilder.fl("appgallerypreview.minZoomFactor", pk("min_zoom"), "最小缩放",
            "适配后基准", 1f,
            0.25f, 4f, 2, "×", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.maxZoomFactor", pk("max_zoom"), "最大缩放",
            "2..32", 8f, 2f, 32f, 1, "×", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.doubleTapZoomFactor", pk("tap_zoom"), "双击缩放倍率",
            "第一档", 2.5f, 1f, 16f, 2, "×", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.doubleTapZoomFactorSecondary", pk("tap_zoom2"),
            "双击缩放（第二档）", "再次双击可放大到该档", 4f, 1f, 32f, 2, "×", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.panOverscrollFactor", pk("pan_overscroll"), "拖动越界系数",
            "0 = 完全回弹锁定", 0.35f, 0f, 1f, 2, "", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.zoomResetOnPageChange", pk("zoom_reset_on_page"),
            "翻页重置缩放", "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.swipeSwitchEnabled", pk("swipe_switch"), "滑动切换",
            "左右滑动切上/下一张", true, "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.swipeSwitchThresholdFraction", pk("swipe_threshold"),
            "滑动切换阈值", "占屏宽比例", 0.22f, 0.05f, 0.9f, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.swipeVelocityThresholdDpPerSec", pk("swipe_velocity"),
            "滑动速度阈值", "dp/s", 320f, 0f, 5000f, 0, "dp/s", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.swipeFollowFactor", pk("swipe_follow"), "跟手系数",
            "0..1", 0.85f, 0f, 1f, 2, "", "ProCam"))
        add(ProSettingBuilder.it("appgallerypreview.decodeTargetMaxPx", pk("decode_max_px"), "解码长边上限",
            "像素", 2048, 256, 16384, 128, "px", "OpenCam"))
        add(ProSettingBuilder.dd("appgallerypreview.previewScaleRenderer", pk("scale_renderer"), "缩放渲染器",
            "TELEPHOTO 用 Telephoto 库；COMPOSE_GESTURE 用 Compose 手势",
            PreviewScaleRenderer.TELEPHOTO.name, PreviewScaleRenderer.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.slideshowIntervalMs", pk("slideshow_interval_ms"),
            "幻灯片间隔", "毫秒", 3000L, 500L, 60_000L, "ms", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.slideshowLoop", pk("slideshow_loop"), "幻灯片循环",
            "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.slideshowShuffle", pk("slideshow_shuffle"), "幻灯片随机",
            "", false, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.slideshowAutoStart", pk("slideshow_autostart"),
            "进入即自动播放", "", false, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.slideshowKeepScreenOn", pk("slideshow_keep_screen_on"),
            "播放时保持亮屏", "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.slideshowShowProgress", pk("slideshow_progress"),
            "显示播放进度", "", true, "ProCam"))
        add(ProSettingBuilder.it("appgallerypreview.rawPreviewMaxDimension", pk("raw_preview_max_dim"),
            "RAW 预览长边上限", "像素", 2048, 512, 8192, 256, "px", "MotionCam"))
        add(ProSettingBuilder.fl("appgallerypreview.rawRenderWorkingScale", pk("raw_work_scale"),
            "RAW 渲染工作系数", "0.1..1", 1f, 0.1f, 1f, 2, "", "MotionCam"))
        add(ProSettingBuilder.it("appgallerypreview.rawHistogramBins", pk("raw_hist_bins"), "RAW 直方图分箱",
            "64 / 128 / 256", 256, 64, 256, 64, "", "MotionCam"))
        add(ProSettingBuilder.fl("appgallerypreview.rawOverexposureThreshold", pk("raw_over_threshold"),
            "过曝阈值", "0..1", 0.98f, 0f, 1f, 3, "", "MotionCam"))
        add(ProSettingBuilder.fl("appgallerypreview.rawUnderexposureThreshold", pk("raw_under_threshold"),
            "欠曝阈值", "0..1", 0.02f, 0f, 1f, 3, "", "MotionCam"))
        add(ProSettingBuilder.fl("appgallerypreview.rawOverexposureWarnRatio", pk("raw_over_warn_ratio"),
            "过曝告警占比", "超过该比例才提示", 0.01f, 0f, 1f, 3, "", "MotionCam"))
        add(ProSettingBuilder.sw("appgallerypreview.rawShowHistogram", pk("raw_show_hist"), "RAW 显示直方图",
            "", true, "MotionCam"))
        add(ProSettingBuilder.sw("appgallerypreview.rawShowOverexposureHint", pk("raw_show_over_hint"),
            "显示过曝提示", "", true, "MotionCam"))
        add(ProSettingBuilder.it("appgallerypreview.rawExportMaxDimension", pk("raw_export_max_dim"),
            "RAW 导出长边", "像素", 4096, 512, 16384, 256, "px", "MotionCam"))
        add(ProSettingBuilder.it("appgallerypreview.rawExportJpegQuality", pk("raw_export_quality"),
            "RAW 导出质量", "1..100", 95, 1, 100, 1, "", "MotionCam"))
        add(ProSettingBuilder.sw("appgallerypreview.rawExportPreserveExif", pk("raw_export_keep_exif"),
            "导出保留 EXIF", "", true, "MotionCam"))
        add(ProSettingBuilder.sw("appgallerypreview.rawExportUseEmbeddedPreview", pk("raw_export_use_embedded"),
            "用内嵌预览导出", "更快但对画质有损", true, "MotionCam"))
        add(ProSettingBuilder.it("appgallerypreview.rawExportChunkRows", pk("raw_export_chunk_rows"),
            "RAW 导出分块行数", "控制内存峰值", 256, 16, 4096, 16, "行", "MotionCam"))
        add(ProSettingBuilder.fl("appgallerypreview.compareSliderDefaultRatio", pk("compare_default_ratio"),
            "对比滑块默认位置", "0..1", 0.5f, 0f, 1f, 2, "", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.compareHandleTouchSlopDp", pk("compare_touch_slop_dp"),
            "对比手柄触摸容差", "dp", 24f, 4f, 96f, 2, "dp", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.compareRequireBothVariants", pk("compare_require_both"),
            "对比需两个变体", "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.compareShowLabels", pk("compare_show_labels"),
            "对比显示标签", "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.gifAutoPlay", pk("gif_autoplay"), "GIF 自动播放",
            "", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.gifLoop", pk("gif_loop"), "GIF 循环播放", "", true, "ProCam"))
        add(ProSettingBuilder.it("appgallerypreview.gifMaxDecodeDimensionPx", pk("gif_max_dim"),
            "GIF 解码长边上限", "像素", 1080, 256, 4096, 128, "px", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.videoAutoPlay", pk("video_autoplay"), "视频自动播放",
            "", false, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.videoLoop", pk("video_loop"), "视频循环播放", "", false, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.videoControls", pk("video_controls"), "显示播放控件",
            "", true, "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.videoBookmarkToleranceMs", pk("video_bookmark_tolerance"),
            "书签吸附容差", "毫秒", 500L, 0L, 10_000L, "ms", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.videoBookmarkLoopEnd", pk("video_bookmark_loop_end"),
            "书签到末尾即循环", "", false, "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.doubleTapSeekMs", pk("gesture_double_tap_seek"),
            "双击快进时长", "毫秒", 10_000L, 0L, 120_000L, "ms", "ProCam"))
        add(ProSettingBuilder.fl("appgallerypreview.dragSeekSensitivityMsPerDp", pk("gesture_drag_seek"),
            "拖动快进灵敏度", "毫秒 / dp", 120f, 10f, 2000f, 0, "ms/dp", "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.gestureVolume", pk("gesture_volume"),
            "竖滑调音量", "视频预览手势", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.gestureBrightness", pk("gesture_brightness"),
            "竖滑调亮度", "视频预览手势", true, "ProCam"))
        add(ProSettingBuilder.sw("appgallerypreview.gestureTapToggle", pk("gesture_tap_toggle"),
            "单击切换控件", "", true, "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.gestureIndicatorMs", pk("gesture_indicator_ms"),
            "手势指示停留", "毫秒", 800L, 0L, 10_000L, "ms", "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.bgThrottleMs", pk("bg_throttle_ms"),
            "后台任务节流", "毫秒", 120L, 0L, 10_000L, "ms", "OpenCam"))
        add(ProSettingBuilder.it("appgallerypreview.bgChunkPixels", pk("bg_chunk_px"), "后台分块像素数",
            "控制内存峰值", 262_144, 16_384, 4_194_304, 16_384, "px", "OpenCam"))
        add(ProSettingBuilder.it("appgallerypreview.bgMaxHeapMb", pk("bg_max_heap_mb"), "后台最大堆占用",
            "MB", 128, 16, 2048, 16, "MB", "OpenCam"))
        add(ProSettingBuilder.it("appgallerypreview.bgMaxBatch", pk("bg_max_batch"), "后台批处理上限",
            "条目", 8, 1, 256, 1, "", "OpenCam"))
        add(ProSettingBuilder.dd("appgallerypreview.orientationLock", pk("orientation_lock"), "方向锁定",
            "AUTO 跟随传感器", PreviewOrientationLock.AUTO.name,
            PreviewOrientationLock.entries.map { it.name }, "ProCam"))
        add(ProSettingBuilder.lt("appgallerypreview.chromeAutoHideMs", pk("chrome_auto_hide_ms"),
            "控件自动隐藏", "毫秒", 4000L, 0L, 60_000L, "ms", "ProCam"))

        // ═════════════════════════════════════════ AI 能力平台（ai_platform_preferences）

        // 页面内的能力选择器：纯 UI 状态，不落盘（故把 storage 覆写为 NONE、清空键）。
        add(ProSettingBuilder.dd("aiplatform.capability", "", "目标能力",
            "页面内的能力选择器（不落盘）：下列端点项按其存储前缀 cap_<capability> 读写",
            AiCapability.WHITE_BALANCE.name, AiCapability.entries.map { it.name }, "GCam").copy(
            storage = ProStorageSlot(ProStorageKind.NONE, "UI 状态（不落盘）"),
            storageKey = "",
        ))
        add(ProSettingBuilder.sw("aiplatform.enabled", ck("userEnabled"), "启用该能力",
            "总开关；关闭后回落端侧 / 既有实现", true, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.baseUrl", ck("baseUrl"), "服务基地址",
            "留空 = 未配置（该能力不启用）；http 明文需显式放行", "", 256, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.path", ck("path"), "请求路径",
            "必须以 / 开头", AiCapability.WHITE_BALANCE.defaultPath, 256, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.apiKey", ck("apiKey"), "API 密钥",
            "密文落盘（keystore:v1: 前缀）", "", 512, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.authScheme", ck("authScheme"), "鉴权方式",
            "默认 Bearer；可填 x-api-key 等", "Bearer", 64, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.authHeaderName", ck("authHeaderName"), "鉴权头名",
            "留空 = 不鉴权", "Authorization", 64, "GCam"))
        add(ProSettingBuilder.tx("aiplatform.model", ck("model"), "模型名",
            "该能力无模型字段时可留空", AiCapability.WHITE_BALANCE.defaultModel, 128, "GCam"))
        add(ProSettingBuilder.it("aiplatform.connectTimeoutMs", ck("connectTimeoutMs"), "连接超时",
            "毫秒", AiCapability.WHITE_BALANCE.defaultConnectTimeoutMs,
            AiEndpointConfig.TIMEOUT_MIN_MS, AiEndpointConfig.TIMEOUT_MAX_MS, 1_000, "ms", "GCam"))
        add(ProSettingBuilder.it("aiplatform.readTimeoutMs", ck("readTimeoutMs"), "读超时",
            "毫秒", AiCapability.WHITE_BALANCE.defaultReadTimeoutMs,
            AiEndpointConfig.TIMEOUT_MIN_MS, AiEndpointConfig.TIMEOUT_MAX_MS, 1_000, "ms", "GCam"))
        add(ProSettingBuilder.it("aiplatform.writeTimeoutMs", ck("writeTimeoutMs"), "写超时",
            "毫秒", AiCapability.WHITE_BALANCE.defaultWriteTimeoutMs,
            AiEndpointConfig.TIMEOUT_MIN_MS, AiEndpointConfig.TIMEOUT_MAX_MS, 1_000, "ms", "GCam"))
        add(ProSettingBuilder.it("aiplatform.maxRetries", ck("maxRetries"), "最大重试次数",
            "次", AiCapability.DEFAULT_MAX_RETRIES,
            AiEndpointConfig.RETRY_MIN, AiEndpointConfig.RETRY_MAX, 1, "次", "GCam"))
        add(ProSettingBuilder.lt("aiplatform.retryBaseDelayMs", ck("retryBaseDelayMs"), "重试退避基数",
            "毫秒", AiCapability.DEFAULT_RETRY_BASE_DELAY_MS,
            AiEndpointConfig.RETRY_BASE_MIN_MS, AiEndpointConfig.RETRY_BASE_MAX_MS, "ms", "GCam"))
        add(ProSettingBuilder.sw("aiplatform.allowCleartext", ck("allowCleartext"), "允许明文 HTTP",
            "默认禁止；仅自部署局域网需要", AiCapability.DEFAULT_ALLOW_CLEARTEXT, "GCam"))

        // ═════════════════════════════════════════ AI 白平衡（ai_feature_preferences）

        add(ProSettingBuilder.sw("aiwb.enabled", AiWhiteBalanceSettings.KEY_ENABLED, "AI 白平衡",
            "端侧估计或远端 API 修正白平衡；默认关", false, "GCam,BMD"))
        add(ProSettingBuilder.fl("aiwb.strength", AiWhiteBalanceSettings.KEY_STRENGTH, "应用强度",
            "0 = 不改动，1 = 完全应用", AiWhiteBalanceConfig.DEFAULT_STRENGTH,
            AiWhiteBalanceConfig.STRENGTH_MIN, AiWhiteBalanceConfig.STRENGTH_MAX, 2, "", "BMD"))
        add(ProSettingBuilder.it("aiwb.minKelvin", AiWhiteBalanceSettings.KEY_MIN_KELVIN, "色温下限",
            "开尔文", AiWhiteBalanceConfig.DEFAULT_MIN_KELVIN,
            AiWhiteBalanceConfig.KELVIN_MIN, AiWhiteBalanceConfig.KELVIN_MAX, 100, "K", "BMD"))
        add(ProSettingBuilder.it("aiwb.maxKelvin", AiWhiteBalanceSettings.KEY_MAX_KELVIN, "色温上限",
            "开尔文（需 ≥ 下限）", AiWhiteBalanceConfig.DEFAULT_MAX_KELVIN,
            AiWhiteBalanceConfig.KELVIN_MIN, AiWhiteBalanceConfig.KELVIN_MAX, 100, "K", "BMD"))
        add(ProSettingBuilder.fl("aiwb.maxGain", AiWhiteBalanceSettings.KEY_MAX_GAIN, "单通道增益上限",
            "绝对值上限", AiWhiteBalanceConfig.DEFAULT_MAX_GAIN,
            AiWhiteBalanceConfig.MAX_GAIN_MIN, AiWhiteBalanceConfig.MAX_GAIN_MAX, 2, "×", "GCam"))
        add(ProSettingBuilder.fl("aiwb.highlightWeight", AiWhiteBalanceSettings.KEY_HIGHLIGHT_WEIGHT,
            "高光权重", "0 = 纯灰世界；1 = 纯高光 / 白点估计", AiWhiteBalanceConfig.DEFAULT_HIGHLIGHT_WEIGHT,
            AiWhiteBalanceConfig.HIGHLIGHT_WEIGHT_MIN, AiWhiteBalanceConfig.HIGHLIGHT_WEIGHT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("aiwb.highlightLumaThreshold", AiWhiteBalanceSettings.KEY_HIGHLIGHT_LUMA,
            "高光亮度阈值", "0.30..1.0", AiWhiteBalanceConfig.DEFAULT_HIGHLIGHT_LUMA_THRESHOLD,
            AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MIN, AiWhiteBalanceConfig.HIGHLIGHT_LUMA_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("aiwb.sampleStride", AiWhiteBalanceSettings.KEY_SAMPLE_STRIDE, "采样步长",
            ">1 跳采样降 CPU 峰值", AiWhiteBalanceConfig.DEFAULT_SAMPLE_STRIDE,
            AiWhiteBalanceConfig.SAMPLE_STRIDE_MIN, AiWhiteBalanceConfig.SAMPLE_STRIDE_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.sw("aiwb.useRemoteApi", AiWhiteBalanceSettings.KEY_USE_REMOTE, "优先远端 API",
            "端点未配置时自动回落端侧估计", false, "GCam"))

        // ═════════════════════════════════════════ 场景识别（ai_feature_preferences）

        add(ProSettingBuilder.sw("scenerecog.enabled", SceneRecognitionSettings.KEY_ENABLED, "场景识别",
            "本地规则 + 可选远端分类；默认关", false, "GCam,ProCam"))
        add(ProSettingBuilder.fl("scenerecog.portraitSkinFraction", SceneRecognitionSettings.KEY_PORTRAIT_SKIN,
            "人像肤色占比阈值", "与既有 LUT 推荐口径一致（0.12）",
            SceneRecognitionConfig.DEFAULT_PORTRAIT_SKIN_FRACTION,
            SceneRecognitionConfig.SKIN_MIN, SceneRecognitionConfig.SKIN_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.textScoreThreshold", SceneRecognitionSettings.KEY_TEXT_THRESHOLD,
            "文字得分阈值", "文档场景判定", SceneRecognitionConfig.DEFAULT_TEXT_SCORE_THRESHOLD,
            SceneRecognitionConfig.TEXT_MIN, SceneRecognitionConfig.TEXT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.nightMaxLuma", SceneRecognitionSettings.KEY_NIGHT_LUMA,
            "夜景亮度上限", "平均亮度低于此值判夜景", SceneRecognitionConfig.DEFAULT_NIGHT_MAX_LUMA,
            SceneRecognitionConfig.NIGHT_MIN, SceneRecognitionConfig.NIGHT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.landscapeMinLuma", SceneRecognitionSettings.KEY_LANDSCAPE_LUMA,
            "风景亮度下限", "", SceneRecognitionConfig.DEFAULT_LANDSCAPE_MIN_LUMA,
            SceneRecognitionConfig.LANDSCAPE_LUMA_MIN, SceneRecognitionConfig.LANDSCAPE_LUMA_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.landscapeMinSaturation",
            SceneRecognitionSettings.KEY_LANDSCAPE_SATURATION, "风景饱和度下限", "",
            SceneRecognitionConfig.DEFAULT_LANDSCAPE_MIN_SATURATION,
            SceneRecognitionConfig.SATURATION_MIN, SceneRecognitionConfig.SATURATION_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.landscapeMaxWarmth",
            SceneRecognitionSettings.KEY_LANDSCAPE_WARMTH, "风景暖度上限", "负值 = 偏冷",
            SceneRecognitionConfig.DEFAULT_LANDSCAPE_MAX_WARMTH,
            SceneRecognitionConfig.WARMTH_MIN, SceneRecognitionConfig.WARMTH_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.streetMinWarmth", SceneRecognitionSettings.KEY_STREET_WARMTH,
            "街头暖度下限", "", SceneRecognitionConfig.DEFAULT_STREET_MIN_WARMTH,
            0f, SceneRecognitionConfig.WARMTH_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("scenerecog.streetMinEdgeDensity", SceneRecognitionSettings.KEY_STREET_EDGE,
            "街头边缘密度下限", "", SceneRecognitionConfig.DEFAULT_STREET_MIN_EDGE_DENSITY,
            SceneRecognitionConfig.EDGE_MIN, SceneRecognitionConfig.EDGE_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("scenerecog.sampleStride", SceneRecognitionSettings.KEY_SAMPLE_STRIDE,
            "采样步长", ">1 跳采样", SceneRecognitionConfig.DEFAULT_SAMPLE_STRIDE,
            SceneRecognitionConfig.SAMPLE_STRIDE_MIN, SceneRecognitionConfig.SAMPLE_STRIDE_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.sw("scenerecog.useRemoteApi", SceneRecognitionSettings.KEY_USE_REMOTE,
            "优先远端 API", "端点未配置时自动回落端侧规则", false, "GCam"))

        // ═════════════════════════════════════════ 原生计算层（跨 JNI 校验上界）

        add(ProSettingBuilder.nav("nativecompute.status", "原生计算层状态",
            "查看 ABI 版本 / 库加载状态 / 是否已回退纯 Kotlin", "nativecompute.diagnostics"))
        add(ProSettingBuilder.fl("nativecompute.ibpGain", "", "MFSR IBP 增益",
            "跨 JNI 上界；C++ 侧同值再 clamp 一次", NativeComputeLimits.IBP_GAIN_DEFAULT,
            NativeComputeLimits.IBP_GAIN_MIN, NativeComputeLimits.IBP_GAIN_MAX, 2, "", "自研"))
        add(ProSettingBuilder.it("nativecompute.alignMaxSide", "", "对齐最长边",
            "像素", 512, NativeComputeLimits.ALIGN_MAX_SIDE_MIN,
            NativeComputeLimits.ALIGN_MAX_SIDE_MAX, 64, "px", "自研"))
        add(ProSettingBuilder.it("nativecompute.maxAlignShift", "", "最大平移",
            "像素", 32, NativeComputeLimits.MAX_ALIGN_SHIFT_MIN,
            NativeComputeLimits.MAX_ALIGN_SHIFT_MAX, 1, "px", "自研"))
        add(ProSettingBuilder.it("nativecompute.holePasses", "", "空洞填补轮数",
            "轮", 4, NativeComputeLimits.HOLE_PASSES_MIN,
            NativeComputeLimits.HOLE_PASSES_MAX, 1, "轮", "自研"))
        add(ProSettingBuilder.it("nativecompute.mfsrBilateralRadius", "", "MFSR 精修双边半径",
            "像素", 2, NativeComputeLimits.MFSR_BILATERAL_RADIUS_MIN,
            NativeComputeLimits.MFSR_BILATERAL_RADIUS_MAX, 1, "px", "自研"))
        add(ProSettingBuilder.num("nativecompute.mfsrBilateralSigma", "", "MFSR 精修双边 σ",
            "非有限值回落默认", NativeComputeLimits.MFSR_BILATERAL_SIGMA_DEFAULT,
            NativeComputeLimits.MFSR_BILATERAL_SIGMA_MIN, 32f, 3, "", "自研"))
        add(ProSettingBuilder.it("nativecompute.claheTiles", "", "CLAHE tile 网格",
            "格", 8, NativeComputeLimits.CLAHE_TILES_MIN,
            NativeComputeLimits.CLAHE_TILES_MAX, 1, "格", "自研"))
        add(ProSettingBuilder.fl("nativecompute.claheClipLimit", "", "CLAHE 对比度上限",
            "非有限值回落 0", 2f, NativeComputeLimits.CLAHE_CLIP_LIMIT_MIN,
            NativeComputeLimits.CLAHE_CLIP_LIMIT_MAX, 2, "", "自研"))
        add(ProSettingBuilder.it("nativecompute.bilateralRadius", "", "双边半径",
            "像素", 3, NativeComputeLimits.BILATERAL_RADIUS_MIN,
            NativeComputeLimits.BILATERAL_RADIUS_MAX, 1, "px", "自研"))
        add(ProSettingBuilder.num("nativecompute.bilateralSigmaSpace", "", "双边空域 σ",
            "", NativeComputeLimits.BILATERAL_SIGMA_SPACE_DEFAULT,
            NativeComputeLimits.BILATERAL_SIGMA_SPACE_MIN,
            NativeComputeLimits.BILATERAL_SIGMA_SPACE_MAX, 3, "", "自研"))
        add(ProSettingBuilder.num("nativecompute.bilateralSigmaColor", "", "双边值域 σ",
            "", NativeComputeLimits.BILATERAL_SIGMA_COLOR_DEFAULT,
            NativeComputeLimits.BILATERAL_SIGMA_COLOR_MIN,
            NativeComputeLimits.BILATERAL_SIGMA_COLOR_MAX, 4, "", "自研"))
        add(ProSettingBuilder.fl("nativecompute.unsharpAmount", "", "USM 强度",
            "0..3", 0.6f, NativeComputeLimits.UNSHARP_AMOUNT_MIN,
            NativeComputeLimits.UNSHARP_AMOUNT_MAX, 2, "", "自研"))
        add(ProSettingBuilder.it("nativecompute.unsharpThreshold", "", "USM 阈值",
            "0..64", 2, NativeComputeLimits.UNSHARP_THRESHOLD_MIN,
            NativeComputeLimits.UNSHARP_THRESHOLD_MAX, 1, "", "自研"))
        add(ProSettingBuilder.fl("nativecompute.accumulateGain", "", "暗帧累加增益",
            "0..2", NativeComputeLimits.ACCUMULATE_GAIN_DEFAULT,
            NativeComputeLimits.ACCUMULATE_GAIN_MIN, NativeComputeLimits.ACCUMULATE_GAIN_MAX, 2, "", "自研"))
        add(ProSettingBuilder.fl("nativecompute.mix", "", "混合系数",
            "0..1", 1f, NativeComputeLimits.MIX_MIN, NativeComputeLimits.MIX_MAX, 2, "", "自研"))
    }
}
