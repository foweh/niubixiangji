package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.ui.uisettings.InMemoryProSettingsPort
import com.hinnka.mycamera.ui.uisettings.ProSettingsValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置项定义解析：normalize（clamp）/ 范围描述 / 注册表自检 / 编解码。
 *
 * 覆盖任务要求的第一类单测：**设置项定义解析**（正常 / 边界 / 异常）。
 */
class ProSettingsModelTest {

    // ───────────────────────────── 1. registry.validate()：全库定义自检

    @Test
    fun `注册表自检通过`() {
        val problems = ProSettingsRegistry.validate()
        assertEquals("定义自检问题：$problems", emptyList<String>(), problems)
    }

    @Test
    fun `每个已落盘模块都至少有一条设置项`() {
        ProSettingsTaxonomy.modules
            .filter { it.status == ProModuleStatus.AVAILABLE }
            .forEach { module ->
                val count = ProSettingsRegistry.settingsOfModule[module.id]?.size ?: 0
                assertTrue("模块 ${module.id} 没有任何设置项", count > 0)
            }
    }

    @Test
    fun `设置项 id 全局唯一且可反查`() {
        val ids = ProSettingsRegistry.settings.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertNotNull(ProSettingsRegistry.settingById[it]) }
    }

    @Test
    fun `聚合口径摘要非空且包含分栏数`() {
        val line = ProSettingsRegistry.summaryLine()
        assertTrue(line.contains("分栏"))
        assertTrue(line.contains("模块"))
    }

    // ───────────────────────────── 2. 开关解析

    @Test
    fun `开关接受多种真值与假值写法`() {
        val spec = ProSettingBuilder.sw("enhance.claheEnabled", "enhance_clahe_enabled", "开关", "", true)
        assertEquals("true", spec.normalize("true").value)
        assertEquals("true", spec.normalize("YES").value)
        assertEquals("true", spec.normalize("1").value)
        assertEquals("false", spec.normalize("off").value)
        assertEquals("false", spec.normalize("否").value)
        assertFalse(spec.normalize("true").corrected)
    }

    @Test
    fun `开关遇到非法值回落默认并标记已修正`() {
        val spec = ProSettingBuilder.sw("enhance.claheEnabled", "enhance_clahe_enabled", "开关", "", true)
        val result = spec.normalize("maybe")
        assertEquals("true", result.value)
        assertTrue(result.corrected)
        assertEquals("非法布尔值", result.reason)
    }

    @Test
    fun `空值一律回落默认`() {
        val spec = ProSettingBuilder.fl("enhance.strength", "k", "强度", "", 0.5f, 0f, 1f)
        listOf(null, "", "   ").forEach { raw ->
            val result = spec.normalize(raw)
            assertEquals("0.50", result.value)
            assertTrue(result.corrected)
            assertEquals("空值回落默认", result.reason)
        }
    }

    // ───────────────────────────── 3. 数值 clamp

    @Test
    fun `浮点越界收敛到边界`() {
        val spec = ProSettingBuilder.fl("enhance.strength", "k", "强度", "", 0.5f, 0f, 1f, 2)
        assertEquals("1.00", spec.normalize("9").value)
        assertEquals("0.00", spec.normalize("-9").value)
        assertTrue(spec.normalize("9").corrected)
    }

    @Test
    fun `非有限数值与非法字符串回落默认`() {
        val spec = ProSettingBuilder.fl("enhance.strength", "k", "强度", "", 0.5f, 0f, 1f, 2)
        assertEquals("0.50", spec.normalize("NaN").value)
        assertEquals("0.50", spec.normalize("Infinity").value)
        assertEquals("0.50", spec.normalize("abc").value)
        assertEquals("非法数值", spec.normalize("abc").reason)
    }

    @Test
    fun `整型滑杆按步长吸附到 min 网格`() {
        // min=0, max=100, step=20 → 27 应吸附到 20
        val spec = ProSettingBuilder.it("enhance.claheTiles", "k", "格子", "", 8, 0, 100, 20)
        assertEquals("20", spec.normalize("27").value)
        assertEquals("40", spec.normalize("39").value)
        assertEquals("100", spec.normalize("999").value)
    }

    @Test
    fun `大整数用输入框而不是滑杆`() {
        val spec = ProSettingBuilder.lt("funshot.pano.memoryBudgetBytes", "k", "内存", "", 1024L, 0L, 4096L)
        assertEquals(ProSettingControl.NUMBER, spec.control)
        assertEquals("4096", spec.normalize("999999").value)
    }

    @Test
    fun `数值项格式化与精度一致`() {
        val spec = ProSettingBuilder.fl("enhance.strength", "k", "强度", "", 0.5f, 0f, 1f, 3)
        assertEquals("0.500", spec.normalize("0.5").value)
        assertEquals("1.000", spec.rangeText().substringAfter("~ ").substringBefore(" "))
        assertEquals("0", ProRange.format(2.9, 0))
        assertEquals("2.90", ProRange.format(2.9, 2))
    }

    // ───────────────────────────── 4. 下拉与文本

    @Test
    fun `下拉白名单大小写不敏感`() {
        val spec = ProSettingBuilder.dd("viewfinder.zebra.metric", "k", "基准", "", "LUMA", listOf("LUMA", "MAX_RGB"))
        assertEquals("LUMA", spec.normalize("luma").value)
        assertEquals("MAX_RGB", spec.normalize("max_rgb").value)
        assertFalse(spec.normalize("luma").corrected)
    }

    @Test
    fun `下拉非法值回落默认并给出理由`() {
        val spec = ProSettingBuilder.dd("viewfinder.zebra.metric", "k", "基准", "", "LUMA", listOf("LUMA", "MAX_RGB"))
        val result = spec.normalize("AVERAGE")
        assertEquals("LUMA", result.value)
        assertTrue(result.corrected)
        assertEquals("不在选项白名单内", result.reason)
    }

    @Test
    fun `文本超长截断`() {
        val spec = ProSettingBuilder.tx("aiplatform.baseUrl", "k", "地址", "", "", maxChars = 5)
        val result = spec.normalize("abcdefgh")
        assertEquals("abcde", result.value)
        assertTrue(result.corrected)
        assertFalse(spec.normalize("abc").corrected)
    }

    @Test
    fun `导航项不接收用户数值`() {
        val spec = ProSettingBuilder.nav("nativecompute.status", "状态", "", "nativecompute.diagnostics")
        assertEquals(ProSettingControl.NAV, spec.control)
        assertNotNull(spec.navTarget)
    }

    // ───────────────────────────── 5. 读写路径与编解码

    @Test
    fun `readFrom 缺键给默认 有键给收敛值`() {
        val spec = ProSettingBuilder.it("enhance.claheTiles", "enhance_clahe_tiles_x", "格子", "", 8, 1, 16, 1)
        assertEquals("8", spec.readFrom(emptyMap()).value)
        assertEquals("12", spec.readFrom(mapOf("enhance_clahe_tiles_x" to "12")).value)
        assertEquals("16", spec.readFrom(mapOf("enhance_clahe_tiles_x" to "99")).value)

        // 键不存在（快照里有别的键）→ 同样回落默认，且标记为已修正。
        val missing = spec.readFrom(mapOf("other_key" to "1"))
        assertEquals("8", missing.value)
        assertTrue(missing.corrected)
    }

    @Test
    fun `writeTo 先收敛再输出`() {
        val spec = ProSettingBuilder.fl("enhance.strength", "k", "强度", "", 0.5f, 0f, 1f, 2)
        assertEquals("1.00", spec.writeTo("5").value)
    }

    @Test
    fun `codec 只写非默认项且能往返`() {
        val spec = ProSettingBuilder.it("enhance.claheTiles", "enhance_clahe_tiles_x", "格子", "", 8, 1, 16, 1)
        val single = ProSettingsCodec.encode(
            ProSettingsCodec.defaults() + mapOf(spec.id to "8"),
        )
        assertTrue("默认值不应写盘", single.isEmpty())

        // 全库跑一遍：把每个可编辑项设成「默认值」，写盘结果必须为空。
        val defaults = ProSettingsCodec.defaults()
        assertTrue(ProSettingsCodec.encode(defaults).isEmpty())

        // 全库跑一遍：把每个可编辑项设成越界值，写盘结果必须全部落在范围内。
        val extreme = defaults.mapValues { (id, _) ->
            val s = ProSettingsRegistry.settingById[id]
            if (s != null && (s.control == ProSettingControl.SLIDER || s.control == ProSettingControl.NUMBER)) "999999999" else ""
        }
        ProSettingsCodec.encode(extreme).forEach { (key, value) ->
            val spec2 = ProSettingsCodec.settingOfKey(key)
            assertNotNull("键 $key 找不到归属设置项", spec2)
            if (spec2 != null) {
                val normalized = spec2.normalize(value).value
                assertEquals("键 $key 落盘值未收敛：$value", normalized, value)
            }
        }
    }

    @Test
    fun `codec merge 保留未登记的键`() {
        val existing = mapOf("some_other_module_key" to "keep-me")
        val merged = ProSettingsCodec.mergeInto(existing, emptyMap())
        assertEquals("keep-me", merged["some_other_module_key"])
    }

    @Test
    fun `codec 键归属反查与冲突检查`() {
        val spec = ProSettingsRegistry.settingById.values.first { ProSettingsCodec.isPersistable(it) }
        assertEquals(spec.id, ProSettingsCodec.settingOfKey(spec.storageKey)?.id)
        assertEquals(emptyList<String>(), ProSettingsCodec.duplicateKeyConflicts())
    }

    @Test
    fun `打包容器项被单独归类`() {
        assertTrue(ProSettingsCodec.packedSettings.isNotEmpty())
        assertTrue(ProSettingsCodec.packedContainers().containsKey("video_modes_config"))
        val spec = ProSettingsCodec.packedSettings.first()
        assertTrue(ProSettingsCodec.isPacked(spec))
        assertFalse(ProSettingsCodec.isPersistable(spec))
        assertTrue(ProSettingsCodec.isPackedPersistable(spec))
        assertEquals("video_modes_config", ProSettingsCodec.containerKeyOf(spec))
        assertTrue(ProSettingsCodec.fieldKeyOf(spec).isNotBlank())
    }

    // ───────────────────────────── 6. 端口 / 值仓库

    @Test
    fun `内存端口可完成一次完整读写`() {
        val port = InMemoryProSettingsPort()
        val store = ProSettingsValueStore(port)
        store.loadAll()

        val spec = ProSettingsRegistry.editableSettings.first { ProSettingsCodec.isPersistable(it) }
        store.setValue(spec.id, spec.defaultValue)
        assertEquals(spec.defaultValue, store.displayValueOf(spec.id))

        val coerced = store.setValue(spec.id, "___dirty___")
        assertTrue("脏值应触发修正", coerced)
        assertEquals(spec.defaultValue, store.displayValueOf(spec.id))
    }

    @Test
    fun `恢复默认后变更计数归零`() {
        val port = InMemoryProSettingsPort()
        val store = ProSettingsValueStore(port)
        store.loadAll()
        val spec = ProSettingsRegistry.editableSettings.first {
            ProSettingsCodec.isPersistable(it) && it.control == ProSettingControl.SLIDER
        }
        store.resetSection(spec.sectionId)
        assertEquals(0, store.changedCount())
    }
}
