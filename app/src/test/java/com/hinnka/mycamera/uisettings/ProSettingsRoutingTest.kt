package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.ui.uisettings.ProSettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分栏路由：设置项 id / 分栏 id / 深链 → 页面路由（含未登记前缀回落「规划中」）。
 *
 * 覆盖任务要求的第三类单测：**分栏路由**。
 */
class ProSettingsRoutingTest {

    // ───────────────────────────── 1. 设置项 → 分栏

    @Test
    fun `未登记前缀回落到规划中节`() {
        assertEquals(ProSettingsTaxonomy.SECTION_PLAN, ProSettingsRouting.sectionIdOfSetting("typoModule.field"))
        val route = ProSettingsRouting.routeForSetting("typoModule.field")
        assertEquals(ProSettingsTaxonomy.SECTION_PLAN, route.sectionId)
        assertFalse("未登记前缀不应被当成已知设置项", route.hasSetting)
        assertEquals("typoModule", route.moduleId)
    }

    @Test
    fun `已登记设置项路由到所属分栏`() {
        val spec = ProSettingsRegistry.settingById.getValue("viewfinder.zebra.enabled")
        assertEquals(ProSettingsTaxonomy.SECTION_VIEWFINDER, ProSettingsRouting.sectionIdOfSetting(spec.id))
        val route = ProSettingsRouting.routeForSetting(spec.id)
        assertEquals(ProSettingsTaxonomy.SECTION_VIEWFINDER, route.sectionId)
        assertTrue(route.hasSetting)
        assertEquals("viewfinder", route.moduleId)
    }

    @Test
    fun `全库设置项的路由与自身分栏一致`() {
        ProSettingsRegistry.settings.forEach { spec ->
            val route = ProSettingsRouting.routeForSetting(spec.id)
            assertEquals("${spec.id} 路由分栏不一致", spec.sectionId, route.sectionId)
            assertTrue("${spec.id} 应可作为深链目标", route.hasSetting)
        }
    }

    @Test
    fun `空值路由不抛异常`() {
        listOf(null, "", "   ").forEach { raw ->
            val route = ProSettingsRouting.routeForSetting(raw)
            assertTrue(ProSettingsTaxonomy.sectionById.containsKey(route.sectionId))
            assertFalse(route.hasSetting)
        }
    }

    // ───────────────────────────── 2. 分栏回落

    @Test
    fun `非法分栏 id 回落到第一个有内容的分栏`() {
        val fallback = ProSettingsRouting.fallbackSectionId()
        listOf(null, "", "sec.不存在", "garbage").forEach { raw ->
            assertEquals(fallback, ProSettingsRouting.resolveSection(raw).id)
        }
    }

    @Test
    fun `合法分栏 id 原样返回`() {
        ProSettingsTaxonomy.sections.forEach { section ->
            assertEquals(section.id, ProSettingsRouting.resolveSection(section.id).id)
            assertTrue(ProSettingsRouting.isValidSection(section.id))
        }
        assertFalse(ProSettingsRouting.isValidSection("sec.nope"))
        assertFalse(ProSettingsRouting.isValidSection(null))
    }

    @Test
    fun `有内容的分栏按 order 升序且等于注册表口径`() {
        val ids = ProSettingsRouting.sectionsWithContent().map { it.id }
        assertEquals(ProSettingsRegistry.sectionContents.map { it.section.id }, ids)
        assertEquals(
            ProSettingsRegistry.sectionContents.map { it.section.order },
            ProSettingsRegistry.sectionContents.map { it.section.order }.sorted(),
        )
    }

    // ───────────────────────────── 3. 深链与状态恢复

    @Test
    fun `restore 丢弃跨分栏的脏设置项`() {
        val route = ProSettingsRouting.restore(
            ProSettingsTaxonomy.SECTION_VIEWFINDER,
            "funshot.gif.loopCount", // 属于「趣味拍摄」节，与传入分栏不符
        )
        assertEquals(ProSettingsTaxonomy.SECTION_VIEWFINDER, route.sectionId)
        assertFalse(route.hasSetting)
    }

    @Test
    fun `restore 接受同分栏的合法设置项`() {
        val route = ProSettingsRouting.restore(
            ProSettingsTaxonomy.SECTION_VIEWFINDER,
            "viewfinder.zebra.enabled",
        )
        assertEquals(ProSettingsTaxonomy.SECTION_VIEWFINDER, route.sectionId)
        assertEquals("viewfinder.zebra.enabled", route.settingId)
    }

    @Test
    fun `restore 对未知分栏回落但仍可深链`() {
        val route = ProSettingsRouting.restore("sec.nope", "viewfinder.zebra.enabled")
        assertEquals(ProSettingsTaxonomy.SECTION_VIEWFINDER, route.sectionId)
        assertEquals("viewfinder.zebra.enabled", route.settingId)
    }

    @Test
    fun `上下分栏翻页首尾相接`() {
        val all = ProSettingsRouting.sectionsWithContent()
        assertTrue(all.size >= 2)
        val first = all.first()
        val last = all.last()
        assertEquals(all[1].id, ProSettingsRouting.nextSection(first.id).id)
        assertEquals(last.id, ProSettingsRouting.previousSection(first.id).id)
        assertEquals(first.id, ProSettingsRouting.nextSection(last.id).id)
        assertEquals(last.id, ProSettingsRouting.previousSection(first.id).id)
    }

    @Test
    fun `面包屑给出分栏到设置项的三级路径`() {
        val crumb = ProSettingsRouting.breadcrumb("viewfinder.zebra.enabled")
        assertEquals(3, crumb.size)
        assertEquals("取景与监看", crumb[0])
        assertEquals("斑马纹", crumb[2])
    }

    @Test
    fun `面包屑对未知 id 不抛异常`() {
        assertEquals(emptyList<String>(), ProSettingsRouting.breadcrumb("nothing.here"))
        assertNotNull(ProSettingsRouting.breadcrumb(null))
    }

    // ───────────────────────────── 4. 搜索与统计

    @Test
    fun `搜索命中标题说明与键名`() {
        assertTrue(ProSettingsRouting.search("斑马纹").isNotEmpty())
        assertTrue(ProSettingsRouting.search("agp_").isNotEmpty())
        assertTrue(ProSettingsRouting.search("viewfinder.").isNotEmpty())
        assertTrue(ProSettingsRouting.search("zzzz-no-such-setting").isEmpty())
        assertTrue(ProSettingsRouting.search("").isEmpty())
        assertTrue(ProSettingsRouting.search(null).isEmpty())
    }

    @Test
    fun `分栏统计与注册表一致`() {
        ProSettingsRegistry.sectionContents.forEach { content ->
            val breakdown = ProSettingsRouting.moduleBreakdownOfSection(content.section.id)
            assertEquals(
                ProSettingsRouting.moduleCountOfSection(content.section.id),
                breakdown.size,
            )
            assertEquals(
                content.settings.count { it.control != ProSettingControl.PLACEHOLDER },
                breakdown.values.sum(),
            )
        }
    }

    // ───────────────────────────── 5. 路由字符串

    @Test
    fun `路由字符串构造与解析往返`() {
        assertEquals(ProSettingsRoute.BASE, ProSettingsRoute.build())
        val route = ProSettingsRoute.build(ProSettingsTaxonomy.SECTION_AI, "aiwb.strength")
        assertEquals(route, ProSettingsRoute.build(ProSettingsTaxonomy.SECTION_AI, "aiwb.strength"))
        val parsed = ProSettingsRoute.parse(route)
        assertEquals(ProSettingsTaxonomy.SECTION_AI, parsed.sectionId)
        assertEquals("aiwb.strength", parsed.settingId)
    }

    @Test
    fun `深链只带设置项时自动推出分栏`() {
        val parsed = ProSettingsRoute.parse(ProSettingsRoute.deepLink("funshot.interval.enabled"))
        assertEquals(ProSettingsTaxonomy.SECTION_FUN, parsed.sectionId)
        assertEquals("funshot.interval.enabled", parsed.settingId)
    }

    @Test
    fun `非法路由字符串一律回落且不崩`() {
        listOf(null, "", "   ", "garbage", "pro_settings?section=sec.nope", "pro_settings?setting=typo.x",
            "pro_settings?section&setting=", "???").forEach { raw ->
            val parsed = ProSettingsRoute.parse(raw)
            assertTrue(
                "路由 $raw 解析出的分栏不合法：${parsed.sectionId}",
                ProSettingsTaxonomy.sectionById.containsKey(parsed.sectionId),
            )
        }
    }

    @Test
    fun `入口文案与深链清单可用`() {
        val description = ProSettingsRoute.entryDescription()
        assertTrue(description.contains("统一专业设置"))
        assertTrue(description.contains("模块"))
        assertEquals(
            ProSettingsRegistry.editableSettings.size,
            ProSettingsRoute.deepLinks().size,
        )
        assertNull(ProSettingsRegistry.settingById["__not_exists__"])
    }
}
