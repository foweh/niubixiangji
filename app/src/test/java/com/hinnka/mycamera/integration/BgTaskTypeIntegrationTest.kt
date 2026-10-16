package com.hinnka.mycamera.integration

import com.hinnka.mycamera.integration.IntegrationSources.count
import com.hinnka.mycamera.integration.IntegrationSources.filesContaining
import com.hinnka.mycamera.integration.IntegrationSources.readMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 1 组：BgTaskType 枚举完整性与各模块引用一致性。
 *
 * 背景：12 个集成补丁里有 9 个往 `computational/BgJobRunner.kt` 的 `BgTaskType` 追加条目，
 * 是本轮集成冲突最密集的文件（8 个条目分三批插在 3 个不同锚点）。本组用例锁定集成后的结果：
 * 条目总数、名称唯一性、节流/批大小/预算三元组、以及「枚举声明了但无人提交」的空档位。
 */
class BgTaskTypeIntegrationTest {

    private val runner = "java/com/hinnka/mycamera/computational/BgJobRunner.kt"

    /** 本轮集成新增的 12 个条目（9 个补丁贡献）与其 (节流ms, 批大小, 预算MB)。 */
    private val expectedNew = mapOf(
        "SUPER_RES" to Triple(10L, 4, 256),
        "MFSR" to Triple(20L, 4, 640),
        "ENHANCE" to Triple(10L, 8, 512),
        "VIEWFINDER" to Triple(20L, 16, 256),
        "PANORAMA" to Triple(18L, 4, 768),
        "GIF_EXPORT" to Triple(12L, 8, 512),
        "GALLERY" to Triple(8L, 32, 256),
        "VIDEO_EDIT" to Triple(10L, 1, 768),
        "VIDEO_TIMELAPSE" to Triple(8L, 16, 384),
        "VIDEO_SR" to Triple(10L, 4, 768),
        "VIDEO_DENOISE" to Triple(12L, 6, 512),
        "VIDEO_SNAPSHOT" to Triple(4L, 8, 256),
    )

    /** 解析 `枚举名(节流L, 批大小, 预算MB)` 三元组。 */
    private fun parseEntries(src: String): Map<String, Triple<Long, Int, Long>> {
        val re = Regex("""^\s{4}([A-Z][A-Z0-9_]+)\(\s*(\d+)L,\s*(\d+),\s*(\d+)L\s*\*\s*1024\s*\*\s*1024\s*\)""", RegexOption.MULTILINE)
        return re.findAll(src).associate {
            it.groupValues[1] to Triple(
                it.groupValues[2].toLong(),
                it.groupValues[3].toInt(),
                it.groupValues[4].toLong(),
            )
        }
    }

    @Test
    fun enumEntriesAreParsableAndUnique() {
        val src = readMain(runner)
        assertTrue("BgJobRunner.kt 未找到（路径解析失败）", src.isNotEmpty())
        val entries = parseEntries(src)
        // 集成前已有 7 条（含 GENERIC/AI_LUT/SERVER_ENHANCE 等）＋本轮 12 条 = 19 条
        assertEquals("BgTaskType 条目数变化，请核对集成结果", 19, entries.size)
        assertTrue("缺少既有条目 GENERIC", entries.containsKey("GENERIC"))
        assertTrue("缺少既有条目 AI_LUT（logvideo 复用）", entries.containsKey("AI_LUT"))
    }

    @Test
    fun newEntriesExistExactlyOnce() {
        val src = readMain(runner)
        expectedNew.keys.forEach { name ->
            // 每行声明一次即为 1 次；重复插入会 >1，漏应用会 =0
            assertEquals("条目 $name 未恰好声明一次", 1, count(src, "\n    $name("))
        }
    }

    @Test
    fun newEntryThrottleBatchBudgetMatchPatchDeclarations() {
        val entries = parseEntries(readMain(runner))
        expectedNew.forEach { (name, want) ->
            val got = entries[name] ?: error("缺少条目 $name")
            assertEquals("$name 节流毫秒不符", want.first, got.first)
            assertEquals("$name 批大小不符", want.second, got.second)
            assertEquals("$name 内存预算 MB 不符", want.third, got.third)
        }
    }

    @Test
    fun everyNewEntryIsReferencedOutsideTheRunner() {
        // 一致性：枚举声明了却无人提交 = 空档位（本轮集成遗留，见 integration.md 未决清单）
        val referenced = expectedNew.keys.filter { name ->
            filesContaining("BgTaskType.$name").any { !it.endsWith("computational/BgJobRunner.kt") }
        }
        val orphan = expectedNew.keys - referenced.toSet()
        assertTrue("至少应有 11 个新条目被模块引用，实际 ${referenced.size}", referenced.size >= 11)
        // 允许存在的空档位必须在自查文档里登记（本轮实测：VIEWFINDER 暂无提交点）
        assertEquals("空档位集合与自查文档不一致（VIEWFINDER）", setOf("VIEWFINDER"), orphan)
    }

    @Test
    fun runnerInternalsFeedBudgetFromDeviceHeap() {
        val src = readMain(runner)
        // review P0-10：预算 = min(条目预算, 覆盖值, 设备堆比例预算)
        assertTrue("缺少设备堆预算函数", src.contains("deviceHeapBudgetBytes"))
        assertTrue("缺少堆预算比例常量", src.contains("HEAP_BUDGET_FRACTION"))
        assertTrue("submit 未应用 effectiveMaxHeapBytes", src.contains("effectiveMaxHeapBytes"))
        // enhance 的 MUST-FIX：Kotlin 2.3.0 下 runCatching { job.block() } 不能编译
        assertTrue("BgJobRunner 仍未修复 block 调用（MUST-FIX）", src.contains("job.block.invoke(job)"))
        assertTrue("MUST-FIX 残留旧写法", !src.contains("runCatching { job.block() }"))
    }

    @Test
    fun videoModesEntriesAreContiguousBeforeGeneric() {
        // videomodes 的 4 条插在 GENERIC 之前；logvideo 复用 AI_LUT 不新增条目
        val src = readMain(runner)
        val genericIdx = src.indexOf("GENERIC(10L, 16,")
        assertTrue(genericIdx > 0)
        listOf("VIDEO_TIMELAPSE", "VIDEO_SR", "VIDEO_DENOISE", "VIDEO_SNAPSHOT").forEach { name ->
            val idx = src.indexOf("\n    $name(")
            assertTrue("$name 应位于 GENERIC 之前", idx in 1 until genericIdx)
        }
        assertTrue("logvideo 不应新增 BgTaskType 条目", !src.contains("LOG_VIDEO("))
    }
}
