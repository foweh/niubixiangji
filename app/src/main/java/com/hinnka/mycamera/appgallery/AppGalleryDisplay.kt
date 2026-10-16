package com.hinnka.mycamera.appgallery

/**
 * 显示适配纯逻辑（相册项 19：横竖屏适配、深色模式跟随/手动）。
 *
 * 刻意放在 `appgallery` 包且**不依赖任何 Android 类型**，以便用纯 JVM 单测覆盖
 * 「列数换算 / 深色判定 / 双栏阈值」这些容易写错的分支。
 */
object AppGalleryDisplay {

    /** 双栏（列表 + 详情）的屏幕宽度阈值（dp）。 */
    const val TWO_PANE_MIN_WIDTH_DP = 720

    /** 平板/横屏时列数的放大上限（配置值 × 2）。 */
    const val MAX_DERIVED_COLUMNS = 20

    /**
     * 深色模式判定（相册项 19）。
     * - `FOLLOW_SYSTEM`：跟随系统；
     * - `LIGHT` / `DARK`：强制。
     */
    fun isDark(themeMode: AppGalleryThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
        AppGalleryThemeMode.FOLLOW_SYSTEM -> systemDark
        AppGalleryThemeMode.LIGHT -> false
        AppGalleryThemeMode.DARK -> true
    }

    /**
     * 自适应列数：竖屏用配置值；宽屏（横屏/平板）按宽度档位放大，上限 [MAX_DERIVED_COLUMNS]。
     *
     * 说明：配置项 [AppGalleryConfig.gridColumnCount] 的合法范围仍是 1..10（clamp 只作用于配置值），
     * 这里给出的是**渲染期派生值**，因此允许超过 10，但不超过 2 倍上限。
     */
    fun resolveGridColumnCount(config: AppGalleryConfig, screenWidthDp: Int): Int {
        val base = AppGalleryConfig.clamp(config).gridColumnCount
        val multiplier = when {
            screenWidthDp >= 1200 -> 3
            screenWidthDp >= 900 -> 2
            screenWidthDp >= 720 -> 2
            else -> 1
        }
        return (base * multiplier).coerceIn(
            AppGalleryConfig.GRID_COLUMN_MIN,
            MAX_DERIVED_COLUMNS,
        )
    }

    /** 列表行高：宽屏略放大，仍受配置范围约束（48..160）。 */
    fun resolveListRowHeightDp(config: AppGalleryConfig, screenWidthDp: Int): Int {
        val safe = AppGalleryConfig.clamp(config)
        val bonus = if (screenWidthDp >= TWO_PANE_MIN_WIDTH_DP) 16 else 0
        return (safe.listRowHeightDp + bonus).coerceIn(
            AppGalleryConfig.LIST_ROW_HEIGHT_MIN_DP,
            AppGalleryConfig.LIST_ROW_HEIGHT_MAX_DP,
        )
    }

    /** 是否启用双栏（左列表右详情）布局。 */
    fun shouldUseTwoPane(screenWidthDp: Int): Boolean = screenWidthDp >= TWO_PANE_MIN_WIDTH_DP

    /**
     * 列表内手势约定（相册项 20）：
     * 长按进入多选并选中该条；已处于多选时单击 = 切换选中；非多选时单击 = 打开预览。
     */
    fun tapBehavior(isSelectionMode: Boolean): AppGalleryTapBehavior =
        if (isSelectionMode) AppGalleryTapBehavior.TOGGLE_SELECTION else AppGalleryTapBehavior.OPEN_PREVIEW

    /** 徽标（分类 + 状态），顺序即渲染顺序。 */
    fun badgesOf(item: AppGalleryItem): List<String> = buildList {
        if (item.category != AppGalleryCategory.NORMAL) add(item.category.labelZh)
        if (item.isFavorite) add("收藏")
        if (item.rating > 0) add("★".repeat(item.rating.coerceAtMost(5)))
        if (item.hasRaw) add("RAW")
        if (item.isMotionPhoto) add("实况")
        if (item.isVideo && (item.durationMs ?: 0L) > 0L) {
            add(AppGalleryItem.formatDuration(item.durationMs ?: 0L))
        }
    }

    /** 时间线分组标题（相册项 1）：`2026年10月16日` / `2026年10月` / `2026年`。 */
    fun timelineTitle(key: String, granularity: TimelineGranularity): String = when (granularity) {
        TimelineGranularity.DAY -> readableDay(key)
        TimelineGranularity.MONTH -> key.split('-').let { parts ->
            if (parts.size < 2) key else "${parts[0]}年${parts[1].trimStart('0')}月"
        }
        TimelineGranularity.YEAR -> "${key}年"
    }

    private fun readableDay(key: String): String {
        val parts = key.split('-')
        if (parts.size < 3) return key
        val year = parts[0]
        val month = parts[1].trimStart('0')
        val day = parts[2].trimStart('0')
        return "${year}年${month}月${day}日"
    }

    /**
     * 统计面板的柱状高度比例（0..1），用最大桶做归一化，避免除零。
     */
    fun normalizedBarRatio(value: Long, maxValue: Long): Float {
        if (maxValue <= 0L) return 0f
        val safe = value.coerceAtLeast(0L)
        return (safe.toDouble() / maxValue.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

/** 列表内单击行为。 */
enum class AppGalleryTapBehavior {
    OPEN_PREVIEW,
    TOGGLE_SELECTION,
}
