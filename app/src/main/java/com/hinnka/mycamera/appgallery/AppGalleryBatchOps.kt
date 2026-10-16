package com.hinnka.mycamera.appgallery

import java.util.LinkedHashSet

/**
 * 多选状态（相册项 8：多选与批量操作）。
 *
 * UI 侧：长按进入选择模式、滑动手势扩展选择；本类只承载**纯状态与状态机**。
 */
data class AppGallerySelection(
    /** 已选条目 id（保持选中顺序）。 */
    val selectedIds: Set<String> = emptySet(),
    /** 锚点（区间选择的起点，即"最后点击"的条目）。 */
    val anchorId: String? = null,
) {
    val count: Int get() = selectedIds.size
    val isEmpty: Boolean get() = selectedIds.isEmpty()
    val isActive: Boolean get() = selectedIds.isNotEmpty()

    fun contains(itemId: String): Boolean = selectedIds.contains(itemId)
}

/** 多选状态机（纯函数，可 JVM 单测）。 */
object AppGallerySelectionMachine {

    fun toggle(selection: AppGallerySelection, itemId: String): AppGallerySelection {
        val ids = LinkedHashSet(selection.selectedIds)
        val removed = ids.remove(itemId)
        return if (removed) {
            AppGallerySelection(
                selectedIds = ids,
                anchorId = if (selection.anchorId == itemId) ids.lastOrNull() else selection.anchorId,
            )
        } else {
            ids.add(itemId)
            AppGallerySelection(selectedIds = ids, anchorId = itemId)
        }
    }

    fun select(selection: AppGallerySelection, itemId: String): AppGallerySelection =
        selection.copy(
            selectedIds = LinkedHashSet(selection.selectedIds).also { it.add(itemId) },
            anchorId = itemId,
        )

    fun deselect(selection: AppGallerySelection, itemId: String): AppGallerySelection =
        selection.copy(
            selectedIds = LinkedHashSet(selection.selectedIds).also { it.remove(itemId) },
            anchorId = if (selection.anchorId == itemId) null else selection.anchorId,
        )

    /**
     * 区间选择（从锚点到目标，含两端，按 [orderedIds] 的显示顺序）。
     * 无锚点时退化为单选。
     */
    fun selectRange(
        selection: AppGallerySelection,
        orderedIds: List<String>,
        targetId: String,
    ): AppGallerySelection {
        val anchor = selection.anchorId ?: return select(selection, targetId)
        val startIndex = orderedIds.indexOf(anchor)
        val endIndex = orderedIds.indexOf(targetId)
        if (startIndex < 0 || endIndex < 0) return select(selection, targetId)
        val from = minOf(startIndex, endIndex)
        val to = maxOf(startIndex, endIndex)
        val ids = LinkedHashSet(selection.selectedIds)
        for (index in from..to) ids.add(orderedIds[index])
        return AppGallerySelection(selectedIds = ids, anchorId = anchor)
    }

    fun selectAll(selection: AppGallerySelection, orderedIds: List<String>): AppGallerySelection =
        AppGallerySelection(selectedIds = LinkedHashSet(orderedIds), anchorId = selection.anchorId)

    fun clear(selection: AppGallerySelection): AppGallerySelection = AppGallerySelection()

    fun invert(selection: AppGallerySelection, orderedIds: List<String>): AppGallerySelection {
        val ids = LinkedHashSet<String>()
        orderedIds.forEach { id -> if (!selection.selectedIds.contains(id)) ids.add(id) }
        return AppGallerySelection(selectedIds = ids, anchorId = selection.anchorId)
    }

    /**
     * 与现存条目对齐：丢弃已不存在的选中项（删除/重扫后调用）。
     */
    fun reconcile(selection: AppGallerySelection, availableIds: Collection<String>): AppGallerySelection {
        val available = availableIds.toSet()
        val kept = selection.selectedIds.filter { available.contains(it) }
        return AppGallerySelection(
            selectedIds = LinkedHashSet(kept),
            anchorId = selection.anchorId?.takeIf { available.contains(it) },
        )
    }
}

/** 批量操作类型（相册项 8）。 */
enum class AppGalleryBatchAction(
    val labelZh: String,
    /** 是否会改动媒体内容/位置（UI 需二次确认）。 */
    val destructive: Boolean = false,
    /** 是否需要标签参数。 */
    val requiresTags: Boolean = false,
    /** 是否需要评分参数。 */
    val requiresRating: Boolean = false,
    /** 是否需要目标相册。 */
    val requiresFolder: Boolean = false,
) {
    DELETE("批量删除", destructive = true),
    RESTORE("批量恢复"),
    PURGE("彻底删除", destructive = true),
    FAVORITE_ON("加入收藏"),
    FAVORITE_OFF("取消收藏"),
    ADD_TAGS("添加标签", requiresTags = true),
    REMOVE_TAGS("移除标签", requiresTags = true),
    SET_RATING("设置评分", requiresRating = true),
    EXPORT("批量导出"),
    SHARE("批量分享"),
    MOVE_TO_FOLDER("移动到相册", requiresFolder = true),
    REMOVE_FROM_FOLDER("移出相册", requiresFolder = true),
}

/** 批量请求（校验后交给 Android 侧执行）。 */
data class AppGalleryBatchRequest(
    val action: AppGalleryBatchAction,
    val itemIds: List<String>,
    val tags: List<String> = emptyList(),
    val rating: Int = 0,
    val folderId: String? = null,
)

/** 校验结果。 */
data class AppGalleryBatchValidation(
    val valid: Boolean,
    /** 失败原因（中文，可直接展示）。 */
    val reason: String? = null,
    /** 归一化后的请求（无效时返回 null）。 */
    val normalized: AppGalleryBatchRequest? = null,
)

/** 单条执行结果。 */
enum class AppGalleryBatchOutcome { SUCCESS, SKIPPED, FAILED }

data class AppGalleryBatchItemResult(
    val itemId: String,
    val outcome: AppGalleryBatchOutcome,
    val message: String? = null,
)

/** 批量执行报告（UI 汇总提示 + 释放空间统计）。 */
data class AppGalleryBatchReport(
    val action: AppGalleryBatchAction,
    val results: List<AppGalleryBatchItemResult>,
) {
    val succeeded: Int get() = results.count { it.outcome == AppGalleryBatchOutcome.SUCCESS }
    val skipped: Int get() = results.count { it.outcome == AppGalleryBatchOutcome.SKIPPED }
    val failed: Int get() = results.count { it.outcome == AppGalleryBatchOutcome.FAILED }
    val total: Int get() = results.size
    val hasFailure: Boolean get() = failed > 0

    /** 单行汇总文案。 */
    fun summaryText(): String = when {
        results.isEmpty() -> "没有可处理的条目"
        failed == 0 && skipped == 0 -> "${action.labelZh}完成：$succeeded 项"
        failed == 0 -> "${action.labelZh}完成：$succeeded 项，跳过 $skipped 项"
        else -> "${action.labelZh}完成：$succeeded 项，失败 $failed 项，跳过 $skipped 项"
    }
}

/**
 * 批量操作规划器（纯函数）：校验 → 分片 → 报告聚合。
 */
object AppGalleryBatchPlanner {

    /** 校验请求（空选择、缺参数、超量都在此拦截，不抛异常）。 */
    fun validate(
        request: AppGalleryBatchRequest,
        config: AppGalleryConfig = AppGalleryConfig(),
    ): AppGalleryBatchValidation {
        val safeConfig = AppGalleryConfig.clamp(config)
        val ids = request.itemIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) {
            return AppGalleryBatchValidation(false, "未选择任何条目")
        }
        if (request.action.requiresTags && request.tags.isEmpty()) {
            return AppGalleryBatchValidation(false, "请先输入标签")
        }
        if (request.action.requiresRating && request.rating < 0) {
            return AppGalleryBatchValidation(false, "评分不能为负")
        }
        if (request.action.requiresFolder && request.folderId.isNullOrBlank()) {
            return AppGalleryBatchValidation(false, "请先选择目标相册")
        }
        val normalized = request.copy(
            itemIds = ids,
            tags = if (request.action.requiresTags) {
                AppGalleryAnnotationRules.normalizeTags(request.tags, safeConfig)
            } else {
                request.tags
            },
            rating = if (request.action.requiresRating) {
                AppGalleryAnnotationRules.normalizeRating(request.rating, safeConfig)
            } else {
                request.rating
            },
        )
        return AppGalleryBatchValidation(true, null, normalized)
    }

    /** 按配置的批大小切片（供 BgJobRunner 逐片处理）。 */
    fun chunk(
        itemIds: List<String>,
        batchSize: Int = AppGalleryConfig().batchOperationBatchSize,
    ): List<List<String>> {
        if (itemIds.isEmpty()) return emptyList()
        val size = batchSize.coerceIn(AppGalleryConfig.BATCH_SIZE_MIN, AppGalleryConfig.BATCH_SIZE_MAX)
        val out = ArrayList<List<String>>()
        var index = 0
        while (index < itemIds.size) {
            val end = minOf(index + size, itemIds.size)
            out.add(itemIds.subList(index, end).toList())
            index = end
        }
        return out
    }

    /** 聚合执行结果。 */
    fun buildReport(
        action: AppGalleryBatchAction,
        results: List<AppGalleryBatchItemResult>,
    ): AppGalleryBatchReport = AppGalleryBatchReport(action, results)

    /** 全部成功的结果（供"无异常路径"的简单实现使用）。 */
    fun successResults(itemIds: List<String>): List<AppGalleryBatchItemResult> =
        itemIds.map { AppGalleryBatchItemResult(it, AppGalleryBatchOutcome.SUCCESS) }

    /** 由布尔映射构造结果（true=成功，false=失败）。 */
    fun resultsFrom(outcomes: Map<String, Boolean>): List<AppGalleryBatchItemResult> =
        outcomes.entries.map { (id, ok) ->
            AppGalleryBatchItemResult(
                itemId = id,
                outcome = if (ok) AppGalleryBatchOutcome.SUCCESS else AppGalleryBatchOutcome.FAILED,
            )
        }

    /** 需要二次确认的动作（UI 弹窗用）。 */
    fun needsConfirmation(action: AppGalleryBatchAction): Boolean =
        action.destructive || action == AppGalleryBatchAction.MOVE_TO_FOLDER
}
