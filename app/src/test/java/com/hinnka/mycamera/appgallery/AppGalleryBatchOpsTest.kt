package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选状态机与批量操作（相册项 8）：区间选择、反选、对齐、校验、分片与结果汇总。
 * 纯 JVM 测试。
 */
class AppGalleryBatchOpsTest {

    private val ordered = listOf("a", "b", "c", "d", "e")
    private val config = AppGalleryConfig()

    // ── 选择状态机 ──────────────────────────────────────────────

    @Test
    fun `切换选中并把锚点设为最后点击项`() {
        var selection = AppGallerySelection()
        selection = AppGallerySelectionMachine.toggle(selection, "b")
        assertEquals(setOf("b"), selection.selectedIds)
        assertEquals("b", selection.anchorId)

        selection = AppGallerySelectionMachine.toggle(selection, "d")
        assertEquals(setOf("b", "d"), selection.selectedIds)
        assertEquals("d", selection.anchorId)

        selection = AppGallerySelectionMachine.toggle(selection, "d")
        assertEquals(setOf("b"), selection.selectedIds)
        assertEquals("b", selection.anchorId)
    }

    @Test
    fun `区间选择包含锚点与目标两端`() {
        var selection = AppGallerySelectionMachine.select(AppGallerySelection(), "b")
        selection = AppGallerySelectionMachine.selectRange(selection, ordered, "d")
        assertEquals(setOf("b", "c", "d"), selection.selectedIds)

        // 反向区间等价
        var reversed = AppGallerySelectionMachine.select(AppGallerySelection(), "d")
        reversed = AppGallerySelectionMachine.selectRange(reversed, ordered, "b")
        assertEquals(setOf("b", "c", "d"), reversed.selectedIds)
    }

    @Test
    fun `无锚点或目标不存在时区间选择退化为单选`() {
        val single = AppGallerySelectionMachine.selectRange(AppGallerySelection(), ordered, "c")
        assertEquals(setOf("c"), single.selectedIds)

        val withAnchor = AppGallerySelectionMachine.select(AppGallerySelection(), "a")
        val missingTarget = AppGallerySelectionMachine.selectRange(withAnchor, ordered, "zzz")
        assertEquals(setOf("a", "zzz"), missingTarget.selectedIds)
    }

    @Test
    fun `全选反选与清空`() {
        var selection = AppGallerySelectionMachine.selectAll(AppGallerySelection(), ordered)
        assertEquals(ordered.toSet(), selection.selectedIds)
        assertEquals(5, selection.count)

        selection = AppGallerySelectionMachine.invert(selection, ordered)
        assertTrue(selection.isEmpty)

        selection = AppGallerySelectionMachine.toggle(AppGallerySelection(), "a")
        selection = AppGallerySelectionMachine.invert(selection, ordered)
        assertEquals(setOf("b", "c", "d", "e"), selection.selectedIds)

        assertTrue(AppGallerySelectionMachine.clear(selection).isEmpty)
    }

    @Test
    fun `对齐现存条目丢弃已删除项`() {
        var selection = AppGallerySelectionMachine.selectAll(AppGallerySelection(), ordered)
        // 全选会保留原锚点（此处为空）
        assertNull(selection.anchorId)
        selection = AppGallerySelectionMachine.reconcile(selection, listOf("a", "c", "e"))
        assertEquals(setOf("a", "c", "e"), selection.selectedIds)

        val stale = AppGallerySelection(selectedIds = linkedSetOf("a", "b"), anchorId = "b")
        val reconciled = AppGallerySelectionMachine.reconcile(stale, listOf("a"))
        assertEquals(setOf("a"), reconciled.selectedIds)
        assertNull(reconciled.anchorId)
    }

    @Test
    fun `取消选择会清空对应锚点`() {
        val selection = AppGallerySelectionMachine.select(AppGallerySelection(), "c")
        val after = AppGallerySelectionMachine.deselect(selection, "c")
        assertNull(after.anchorId)
        assertEquals(0, after.count)
    }

    // ── 批量请求校验 ────────────────────────────────────────────

    @Test
    fun `空选择与缺少必填参数被拒绝`() {
        val empty = AppGalleryBatchPlanner.validate(
            AppGalleryBatchRequest(AppGalleryBatchAction.DELETE, emptyList()),
            config,
        )
        assertFalse(empty.valid)
        assertEquals("未选择任何条目", empty.reason)

        val noTags = AppGalleryBatchPlanner.validate(
            AppGalleryBatchRequest(AppGalleryBatchAction.ADD_TAGS, listOf("a")),
            config,
        )
        assertFalse(noTags.valid)
        assertEquals("请先输入标签", noTags.reason)

        val noFolder = AppGalleryBatchPlanner.validate(
            AppGalleryBatchRequest(AppGalleryBatchAction.MOVE_TO_FOLDER, listOf("a")),
            config,
        )
        assertFalse(noFolder.valid)
        assertEquals("请先选择目标相册", noFolder.reason)
    }

    @Test
    fun `合法请求会去重并规范化标签与评分`() {
        val request = AppGalleryBatchRequest(
            action = AppGalleryBatchAction.ADD_TAGS,
            itemIds = listOf("a", "a", " ", "b"),
            tags = listOf("星空", "星空", "  "),
        )
        val validated = AppGalleryBatchPlanner.validate(request, config)
        assertTrue(validated.valid)
        assertEquals(listOf("a", "b"), validated.normalized?.itemIds)
        assertEquals(listOf("星空"), validated.normalized?.tags)
    }

    @Test
    fun `评分请求被 clamp 到配置上限`() {
        val request = AppGalleryBatchRequest(
            action = AppGalleryBatchAction.SET_RATING,
            itemIds = listOf("a"),
            rating = 99,
        )
        val validated = AppGalleryBatchPlanner.validate(request, config.copy(maxRating = 4))
        assertTrue(validated.valid)
        assertEquals(4, validated.normalized?.rating)
    }

    // ── 分片与报告 ──────────────────────────────────────────────

    @Test
    fun `分片按批大小切分且批大小被 clamp`() {
        val ids = (1..10).map { "id$it" }
        val chunks = AppGalleryBatchPlanner.chunk(ids, 3)
        assertEquals(4, chunks.size)
        assertEquals(3, chunks.first().size)
        assertEquals(1, chunks.last().size)
        assertEquals(ids, chunks.flatten())

        assertEquals(emptyList<List<String>>(), AppGalleryBatchPlanner.chunk(emptyList(), 4))
        // 批大小越界被 clamp 到合法范围
        assertTrue(AppGalleryBatchPlanner.chunk(ids, 0).size >= 1)
        assertEquals(1, AppGalleryBatchPlanner.chunk(ids, Int.MAX_VALUE).size)
    }

    @Test
    fun `结果汇总统计成功跳过与失败`() {
        val report = AppGalleryBatchPlanner.buildReport(
            AppGalleryBatchAction.SHARE,
            listOf(
                AppGalleryBatchItemResult("a", AppGalleryBatchOutcome.SUCCESS),
                AppGalleryBatchItemResult("b", AppGalleryBatchOutcome.SKIPPED, "系统相册不支持"),
                AppGalleryBatchItemResult("c", AppGalleryBatchOutcome.FAILED, "空间不足"),
            ),
        )
        assertEquals(3, report.total)
        assertEquals(1, report.succeeded)
        assertEquals(1, report.skipped)
        assertEquals(1, report.failed)
        assertTrue(report.hasFailure)
        assertTrue(report.summaryText().isNotEmpty())
    }

    @Test
    fun `销毁性动作需要二次确认`() {
        assertTrue(AppGalleryBatchPlanner.needsConfirmation(AppGalleryBatchAction.DELETE))
        assertTrue(AppGalleryBatchPlanner.needsConfirmation(AppGalleryBatchAction.PURGE))
        assertTrue(AppGalleryBatchPlanner.needsConfirmation(AppGalleryBatchAction.MOVE_TO_FOLDER))
        assertFalse(AppGalleryBatchPlanner.needsConfirmation(AppGalleryBatchAction.SHARE))
        assertTrue(AppGalleryBatchAction.ADD_TAGS.requiresTags)
        assertTrue(AppGalleryBatchAction.SET_RATING.requiresRating)
        assertTrue(AppGalleryBatchAction.PURGE.destructive)
    }

    @Test
    fun `结果构造辅助方法一致`() {
        val fromBoolean = AppGalleryBatchPlanner.resultsFrom(linkedMapOf("a" to true, "b" to false))
        assertEquals(1, AppGalleryBatchPlanner.buildReport(AppGalleryBatchAction.SHARE, fromBoolean).succeeded)
        val success = AppGalleryBatchPlanner.successResults(listOf("x", "y"))
        assertEquals(2, AppGalleryBatchPlanner.buildReport(AppGalleryBatchAction.SHARE, success).succeeded)
    }
}
