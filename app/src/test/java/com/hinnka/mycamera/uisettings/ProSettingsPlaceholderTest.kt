package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.ui.uisettings.InMemoryProSettingsPort
import com.hinnka.mycamera.ui.uisettings.ProSettingsValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位节逻辑（正常 / 边界 / 异常）。
 *
 * 背景：任务里点名「对尚未落盘的包不依赖其符号，页面留占位节并注明待对应模块落地后接线」。
 * 本工程 16 个新包实际都已落盘，唯一 PENDING 的是「微距焦点堆叠」（工程内确无对应包），
 * 因此占位节必须同时处理好「未登记前缀」这种**异常**输入 —— 那是未来新增模块时的常态。
 */
class ProSettingsPlaceholderTest {

    // ───────────────────────────── 1. 正常：已登记的 PENDING 模块

    @Test
    fun `pending 模块生成占位行并带上理由`() {
        val pending = ProSettingsTaxonomy.modules.filter { it.status == ProModuleStatus.PENDING }
        assertTrue("应当至少有一个 PENDING 模块用于占位节演示", pending.isNotEmpty())

        val module = pending.first()
        val row = ProSettingsPlaceholder.rowFor(module.id)
        assertEquals(ProSettingControl.PLACEHOLDER, row.control)
        assertEquals("${module.id}.placeholder", row.id)
        assertEquals(module.id, row.moduleId)
        assertEquals(module.displayName, row.title)
        assertNotNull(row.pendingReason)
        assertTrue("占位理由不能为空", !row.pendingReason.isNullOrBlank())
        assertTrue("占位理由应说明待落地", row.description.contains("待"))
    }

    @Test
    fun `占位行的理由优先取模块声明的 pendingReason`() {
        ProSettingsTaxonomy.modules
            .filter { it.status == ProModuleStatus.PENDING }
            .forEach { module ->
                val expected = module.pendingReason?.takeIf { it.isNotBlank() }
                    ?: ProSettingsPlaceholder.DEFAULT_REASON
                assertEquals(expected, ProSettingsPlaceholder.reasonOf(module))
            }
    }

    @Test
    fun `占位行不参与持久化也不会被当成可编辑项`() {
        ProSettingsPlaceholder.rows.forEach { row ->
            assertTrue("占位行不应有键", row.storageKey.isBlank())
            assertFalse("占位行不应被视为可持久化", ProSettingsCodec.isPersistable(row))
            assertEquals("占位行必须落在合法分栏", true, ProSettingsTaxonomy.sectionById.containsKey(row.sectionId))
        }
        assertTrue(
            "占位行不应出现在可编辑清单里",
            ProSettingsRegistry.editableSettings.none { it.control == ProSettingControl.PLACEHOLDER },
        )
    }

    // ───────────────────────────── 2. 边界：无分栏归属 / 无理由 / 已落盘

    @Test
    fun `pending 模块没有分栏归属时回落到规划中节`() {
        ProSettingsTaxonomy.modules
            .filter { it.status == ProModuleStatus.PENDING }
            .forEach { module ->
                assertEquals(
                    "PENDING 模块应落在规划中节",
                    ProSettingsTaxonomy.SECTION_PLAN,
                    ProSettingsPlaceholder.resolveSectionId(module.id),
                )
                assertEquals(
                    ProSettingsTaxonomy.SECTION_PLAN,
                    ProSettingsPlaceholder.rowFor(module.id).sectionId,
                )
            }
    }

    @Test
    fun `规划中节整体被判为纯占位节`() {
        assertTrue(ProSettingsPlaceholder.isPlaceholderOnly(ProSettingsTaxonomy.SECTION_PLAN))
        assertTrue(ProSettingsPlaceholder.rowsForSection(ProSettingsTaxonomy.SECTION_PLAN).isNotEmpty())
    }

    @Test
    fun `有真实设置项的分栏不是纯占位节`() {
        listOf(
            ProSettingsTaxonomy.SECTION_IMAGE,
            ProSettingsTaxonomy.SECTION_VIDEO,
            ProSettingsTaxonomy.SECTION_VIEWFINDER,
        ).forEach { sectionId ->
            assertFalse("$sectionId 有真实设置项，不应判为纯占位", ProSettingsPlaceholder.isPlaceholderOnly(sectionId))
        }
    }

    @Test
    fun `理由为空时回落默认文案`() {
        val blank = ProModuleSpec(
            id = "placeholder_test_module",
            displayName = "占位测试模块",
            sourcePackage = "（无对应包）",
            status = ProModuleStatus.PENDING,
            pendingReason = "   ",
        )
        assertEquals(ProSettingsPlaceholder.DEFAULT_REASON, ProSettingsPlaceholder.reasonOf(blank))
    }

    @Test
    fun `已落盘模块不生成占位行`() {
        assertNull(ProSettingsPlaceholder.rowForOrNull("enhance"))
        assertNull(ProSettingsPlaceholder.rowForOrNull("viewfinder"))
        assertEquals(
            ProSettingsPlaceholder.ALREADY_AVAILABLE_REASON,
            ProSettingsPlaceholder.reasonOf(ProSettingsTaxonomy.moduleById.getValue("enhance")),
        )
    }

    // ───────────────────────────── 3. 异常：未登记前缀 / 空字符串

    @Test
    fun `未登记模块前缀不抛异常且给出未登记提示`() {
        val row = ProSettingsPlaceholder.rowFor("typoModule")
        assertEquals("typoModule.placeholder", row.id)
        assertEquals(ProSettingsTaxonomy.SECTION_PLAN, row.sectionId)
        assertEquals(ProSettingsPlaceholder.UNKNOWN_MODULE_REASON, row.pendingReason)
        assertEquals(ProSettingsPlaceholder.UNKNOWN_MODULE_REASON, ProSettingsPlaceholder.reasonOf(null))
        assertNotNull(ProSettingsPlaceholder.rowForOrNull("typoModule"))
    }

    @Test
    fun `空字符串与空白模块 id 不抛异常`() {
        listOf("", "   ", ".").forEach { raw ->
            val row = ProSettingsPlaceholder.rowFor(raw)
            assertEquals(ProSettingControl.PLACEHOLDER, row.control)
            assertTrue(ProSettingsTaxonomy.sectionById.containsKey(row.sectionId))
            assertNotNull(row.pendingReason)
        }
        assertNotNull(ProSettingsPlaceholder.rowsForSection("sec.nope"))
        assertTrue(ProSettingsPlaceholder.rowsForSection("sec.nope").isEmpty())
    }

    @Test
    fun `占位行的 id 互不冲突`() {
        val ids = ProSettingsPlaceholder.rows.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { id ->
            assertTrue("占位 id 与真实设置项冲突：$id", ProSettingsRegistry.settingById[id] == null)
        }
    }

    // ───────────────────────────── 4. 渲染清单与接线说明

    @Test
    fun `渲染清单等于真实设置项加占位行`() {
        ProSettingsTaxonomy.sections.forEach { section ->
            val rendered = ProSettingsPlaceholder.renderListOf(section.id)
            assertEquals(
                ProSettingsRegistry.settingsOfSection(section.id).size +
                    ProSettingsPlaceholder.rowsForSection(section.id).size,
                rendered.size,
            )
        }
    }

    @Test
    fun `接线说明覆盖全部模块`() {
        val notes = ProSettingsPlaceholder.wiringNotes()
        assertEquals(ProSettingsTaxonomy.modules.size, notes.size)
        notes.forEach { assertTrue(it.isNotBlank()) }
        assertTrue(notes.any { it.contains("PENDING") || it.contains("待对应模块落地后接线") || it.contains("读 ") })
    }

    // ───────────────────────────── 5. 占位行与页面状态共存

    @Test
    fun `占位行不会污染值仓库的变更计数`() {
        val port = InMemoryProSettingsPort()
        val store = ProSettingsValueStore(port)
        store.loadAll()
        assertEquals(0, store.changedCount())

        // 对占位行写入（UI 理论上不会触发，这里验证即使触发也不会崩）
        val placeholder = ProSettingsPlaceholder.rows.first()
        store.setValue(placeholder.id, "whatever")
        assertEquals(0, store.changedCount())
        assertEquals(0, store.commit())
    }
}
