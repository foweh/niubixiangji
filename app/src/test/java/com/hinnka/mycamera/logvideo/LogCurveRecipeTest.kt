package com.hinnka.mycamera.logvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线配方参数 clamp 边界测试（Log 视频 20 项 · 第 1 项「曲线参数黑电平/峰值/斜率可配」）。
 *
 * 覆盖：正常值 / 边界值（恰好落在 min/max）/ 越界值 / 异常值（NaN、±Inf）/ 偏好反序列化脏数据。
 * 约定：clamp **不抛异常、不产生 NaN**，只把值收进安全区间。
 */
class LogCurveRecipeTest {

    // ─────────────────── 正常路径 ───────────────────

    @Test
    fun defaultRecipeIsPresetNative() {
        val recipe = LogCurveRecipe.DEFAULT
        assertTrue(recipe.isPresetNative())
        assertTrue(recipe.clampReport().isEmpty())
        assertEquals(recipe, recipe.clamped())
        assertEquals(LogCurveRecipe.DEFAULT_TABLE_SIZE, recipe.tableSize)
    }

    @Test
    fun defaultRecipeEncodeEqualsPresetDirectly() {
        val recipe = LogCurveRecipe(preset = LogCurvePreset.LOG_C3)
        assertEquals(LogCurvePreset.LOG_C3.encodeCode(0.18f), recipe.encode(0.18f), 1e-6f)
        assertEquals(0.18f, recipe.decode(recipe.encode(0.18f)), 2e-3f)
    }

    @Test
    fun customParametersSurviveClamp() {
        val recipe = LogCurveRecipe(
            preset = LogCurvePreset.LOG_C3,
            blackLevel = 0.125f,
            peak = 4f,
            slope = 2f,
            tableSize = 2048,
        )
        assertEquals(recipe, recipe.clamped())
        assertTrue(recipe.clampReport().isEmpty())
        assertFalse(recipe.isPresetNative())
    }

    // ─────────────────── 边界值：恰好落在区间端点 ───────────────────

    @Test
    fun boundaryValuesAreInclusive() {
        val atMax = LogCurveRecipe(
            preset = LogCurvePreset.V_LOG,
            blackLevel = LogCurveRecipe.BLACK_LEVEL_MAX,
            peak = LogCurveRecipe.PEAK_MAX,
            slope = LogCurveRecipe.SLOPE_MAX,
            tableSize = LogCurveRecipe.TABLE_SIZE_MAX,
        )
        assertEquals(atMax, atMax.clamped())
        assertTrue(atMax.clampReport().isEmpty())

        val atMin = LogCurveRecipe(
            preset = LogCurvePreset.V_LOG,
            blackLevel = LogCurveRecipe.BLACK_LEVEL_MIN,
            peak = LogCurveRecipe.PEAK_MIN,
            slope = LogCurveRecipe.SLOPE_MIN,
            tableSize = LogCurveRecipe.TABLE_SIZE_MIN,
        )
        assertEquals(atMin, atMin.clamped())
        assertTrue(atMin.clampReport().isEmpty())
    }

    // ─────────────────── 越界 ───────────────────

    @Test
    fun outOfRangeValuesAreClampedToBounds() {
        val dirty = LogCurveRecipe(
            preset = LogCurvePreset.HLG,
            blackLevel = 9f,
            peak = 0f,
            slope = 100f,
            tableSize = 1,
        ).clamped()
        assertEquals(LogCurveRecipe.BLACK_LEVEL_MAX, dirty.blackLevel, 1e-6f)
        assertEquals(LogCurveRecipe.PEAK_MIN, dirty.peak, 1e-6f)
        assertEquals(LogCurveRecipe.SLOPE_MAX, dirty.slope, 1e-6f)
        assertEquals(LogCurveRecipe.TABLE_SIZE_MIN, dirty.tableSize)

        val dirtyNegative = LogCurveRecipe(
            preset = LogCurvePreset.HLG,
            blackLevel = -9f,
            peak = -1f,
            slope = -2f,
            tableSize = -5,
        ).clamped()
        assertEquals(LogCurveRecipe.BLACK_LEVEL_MIN, dirtyNegative.blackLevel, 1e-6f)
        assertEquals(LogCurveRecipe.PEAK_MIN, dirtyNegative.peak, 1e-6f)
        assertEquals(LogCurveRecipe.SLOPE_MIN, dirtyNegative.slope, 1e-6f)
        assertEquals(LogCurveRecipe.TABLE_SIZE_MIN, dirtyNegative.tableSize)
    }

    @Test
    fun clampReportListsExactlyTheFixedFields() {
        val report = LogCurveRecipe(
            preset = LogCurvePreset.LOG_C3,
            blackLevel = 1f,
            peak = 100f,
            slope = 1f,
            tableSize = 999_999,
        ).clampReport()
        assertEquals(3, report.size)
        assertTrue(report.any { it.startsWith("blackLevel=") })
        assertTrue(report.any { it.startsWith("peak=") })
        assertTrue(report.any { it.startsWith("tableSize=") })
    }

    // ─────────────────── 异常值（NaN / Inf） ───────────────────

    @Test
    fun nonFiniteParametersFallBackToDefaults() {
        val dirty = LogCurveRecipe(
            preset = LogCurvePreset.LOG_C3,
            blackLevel = Float.NaN,
            peak = Float.POSITIVE_INFINITY,
            slope = Float.NEGATIVE_INFINITY,
            tableSize = 1024,
        ).clamped()
        assertEquals(LogCurveRecipe.DEFAULT_BLACK_LEVEL, dirty.blackLevel, 1e-6f)
        assertEquals(LogCurveRecipe.DEFAULT_PEAK, dirty.peak, 1e-6f)
        assertEquals(LogCurveRecipe.DEFAULT_SLOPE, dirty.slope, 1e-6f)
        val report = LogCurveRecipe(
            preset = LogCurvePreset.LOG_C3,
            blackLevel = Float.NaN,
            peak = Float.NaN,
            slope = Float.NaN,
        ).clampReport()
        assertEquals(3, report.size)
    }

    @Test
    fun clampedRecipeNeverProducesNaNCode() {
        val recipes = listOf(
            LogCurveRecipe(preset = LogCurvePreset.LOG_C3, blackLevel = Float.NaN, peak = Float.NaN, slope = Float.NaN),
            LogCurveRecipe(preset = LogCurvePreset.HLG, blackLevel = -1f, peak = 0.01f, slope = 99f),
            LogCurveRecipe(preset = LogCurvePreset.V_LOG, blackLevel = 0.25f, peak = 8f, slope = 0.25f),
        )
        recipes.forEach { recipe ->
            val safe = recipe.clamped()
            listOf(0f, 0.18f, 1f).forEach { scene ->
                val code = safe.encode(scene)
                assertTrue("code=$code", code.isFinite())
                assertTrue(code in 0f..1f)
            }
            val table = LogCurveTable.build(safe, onProgress = null)
            assertTrue(table.samplesCopy().all { it.isFinite() })
        }
    }

    @Test
    fun encodeIsMonotonicUnderExtremeParameters() {
        val recipe = LogCurveRecipe(
            preset = LogCurvePreset.LOG_C3,
            blackLevel = LogCurveRecipe.BLACK_LEVEL_MAX,
            peak = LogCurveRecipe.PEAK_MIN,
            slope = LogCurveRecipe.SLOPE_MAX,
        ).clamped()
        var previous = -1f
        var scene = 0f
        while (scene <= 1f) {
            val code = recipe.encode(scene)
            assertTrue("scene=$scene code=$code prev=$previous", code >= previous)
            previous = code
            scene += 0.01f
        }
    }

    @Test
    fun peakScalesTheInputRange() {
        // peak=2 → 输入 2.0 才到白（1 档余量留给高光），中灰位形随之右移
        val wide = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, peak = 2f)
        assertEquals(LogCurvePreset.LOG_C3.encodeCode(0.5f), wide.encode(1f), 1e-5f)
        assertEquals(1f, wide.encode(2f), 1e-4f)
        // 逆变换输出受场景线性 [0,1] 契约约束（>1 的高光余量在烘焙侧统一由软膝裁切）
        assertEquals(1f, wide.decode(1f), 1e-4f)
        assertTrue(wide.decode(0.9f) <= 1f)
    }

    // ─────────────────── 偏好表反序列化 ───────────────────

    @Test
    fun fromPreferencesUsesDefaultsForMissingAndUnknownKeys() {
        val config = LogCurveRecipe.fromPreferences(emptyMap())
        assertEquals(LogCurvePreset.LOG_C3, config.preset)
        assertTrue(config.isPresetNative())

        val unknown = LogCurveRecipe.fromPreferences(mapOf("log_curve_preset" to "NOT_A_PRESET"))
        assertEquals(LogCurvePreset.LOG_C3, unknown.preset)
    }

    @Test
    fun fromPreferencesClampsDirtyPersistedValues() {
        val config = LogCurveRecipe.fromPreferences(
            mapOf(
                "log_curve_preset" to LogCurvePreset.HLG.name,
                "log_curve_black_level" to 5.0,
                "log_curve_peak" to "not-a-number",
                "log_curve_slope" to 0.01,
                "log_curve_table_size" to 100_000,
            ),
        )
        assertEquals(LogCurvePreset.HLG, config.preset)
        assertEquals(LogCurveRecipe.BLACK_LEVEL_MAX, config.blackLevel, 1e-6f)
        assertEquals(LogCurveRecipe.PEAK_MIN, config.peak, 1e-6f)
        assertEquals(LogCurveRecipe.SLOPE_MIN, config.slope, 1e-6f)
        assertEquals(LogCurveRecipe.TABLE_SIZE_MAX, config.tableSize)
    }

    @Test
    fun fromPersistedNameRoundTripsEveryPreset() {
        LogCurvePreset.entries.forEach { preset ->
            assertEquals(preset, LogCurvePreset.fromPersistedName(preset.name))
        }
        assertEquals(LogCurvePreset.LOG_C3, LogCurvePreset.fromPersistedName("bogus"))
        assertEquals(LogCurvePreset.LOG_C3, LogCurvePreset.fromPersistedName(null))
    }

    @Test
    fun describeMentionsPresetAndParameters() {
        val text = LogCurveRecipe(preset = LogCurvePreset.HLG, peak = 4f).describe()
        assertTrue(text.contains("HLG"))
        assertTrue(text.contains("peak=4"))
    }
}
