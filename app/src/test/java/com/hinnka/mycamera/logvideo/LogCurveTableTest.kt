package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Log 曲线查表数值测试（Log 视频 20 项 · 第 1 项）：
 * 已知点比对 + 分界连续性 + 与工程 `TransferCurve` 复用一致性 + 查表精度。
 *
 * 数值来源：ARRI LogC3（EI 800）官方公开常量、Panasonic V-Log 公开规格、ITU-R BT.2100 HLG。
 * 容差 1e-3（Float 精度 + 公开常量本身的 6 位有效数字）。
 */
class LogCurveTableTest {

    // ─────────────────── 已知点：ARRI LogC3 ───────────────────

    @Test
    fun logC3MatchesPublishedKnownPoints() {
        val preset = LogCurvePreset.LOG_C3
        // 输入 0（全黑）：线性支 → e*0 + f = 0.092809
        assertEquals(0.092809f, preset.encodeCode(0f), 1e-4f)
        // 18% 中灰 → 0.391（ARRI 公开规格）
        assertEquals(0.391f, preset.encodeCode(0.18f), 1e-3f)
        // 线性 1.0（场景白）→ 0.5706
        assertEquals(0.5706f, preset.encodeCode(1f), 1e-3f)
        // 分界点：两支在 cut 处连续（线性支 e*cut+f ≈ 0.14966，对数支 c*log10(a*cut+b)+d ≈ 0.14963）
        assertEquals(0.149658f, preset.encodeCode(LogCurvePreset.LOGC3_CUT_LINEAR), 1e-3f)
    }

    @Test
    fun logC3DecodeIsInverseOfEncode() {
        val preset = LogCurvePreset.LOG_C3
        listOf(0.01f, 0.18f, 0.391f, 0.5706f, 1f).forEach { scene ->
            val code = preset.encodeCode(scene)
            assertEquals(scene, preset.decodeLinear(code), 2e-3f)
        }
    }

    @Test
    fun logC3MiddleGreyInvariant() {
        // 中灰代码值口径：18% × displayWhiteInput（LogC3 白点 = 1.0）
        assertEquals(0.391f, LogCurvePreset.LOG_C3.middleGreyCode, 1e-3f)
    }

    // ─────────────────── 复用工程既有曲线 ───────────────────

    @Test
    fun vLogDelegatesToProjectTransferCurve() {
        val preset = LogCurvePreset.V_LOG
        assertEquals(TransferCurve.VLOG, preset.nativeCurve)
        // 与工程 TransferCurve 逐位一致（复用，零自研）
        assertEquals(TransferCurve.VLOG.linearToLog(0.18f), preset.encodeCode(0.18f), 1e-6f)
        assertEquals(0.4233f, preset.encodeCode(0.18f), 1e-3f)
        assertEquals(0.4233f, preset.middleGreyCode, 1e-3f)
        assertEquals(0.18f, preset.decodeLinear(preset.encodeCode(0.18f)), 2e-3f)
    }

    @Test
    fun hlgDiffuseWhiteSitsAtCode075() {
        val preset = LogCurvePreset.HLG
        assertEquals(TransferCurve.HLG, preset.nativeCurve)
        // ITU-R BT.2100 / BT.2408：漫反射白（场景线性 0.2649）= HLG 代码 0.75
        assertEquals(0.75f, preset.encodeCode(LogCurvePreset.HLG_DIFFUSE_WHITE_SCENE), 2e-3f)
        // 中灰口径修正后 = encodeCode(0.18 × 0.2649) ≈ 0.378（而非 0.672）
        assertEquals(0.378f, preset.middleGreyCode, 5e-3f)
        assertTrue(preset.displayWhiteInput < 0.5f)
    }

    @Test
    fun hlgMiddlegreyDivergesFromProjectCurveConventionOnPurpose() {
        // 工程 TransferCurve.HLG.middleGray 按"峰值归一"口径（= encodeCode(0.18) = 0.672358）；
        // 还原 LUT 按 BT.2408 显示域口径（= 0.378）。两者差异是**有意的**：
        // 若误用前者，HLG 还原 LUT 会让正常曝光画面偏暗约 1 档。
        assertEquals(0.672358f, TransferCurve.HLG.middleGray, 1e-5f)
        assertEquals(TransferCurve.HLG.middleGray, LogCurvePreset.HLG.encodeCode(0.18f), 1e-5f)
        assertEquals(0.378f, LogCurvePreset.HLG.middleGreyCode, 5e-3f)
        assertTrue(LogCurvePreset.HLG.middleGreyCode < TransferCurve.HLG.middleGray)
        // 其余档位两种口径一致（例：V-Log 0.4233 与工程 middleGray 逐位一致）
        assertEquals(TransferCurve.VLOG.middleGray, LogCurvePreset.V_LOG.middleGreyCode, 1e-4f)
    }

    @Test
    fun everyPresetMiddleGreyDecodesBackTo18PercentOfWhite() {
        LogCurvePreset.entries.forEach { preset ->
            val scene = preset.decodeLinear(preset.middleGreyCode)
            assertEquals(
                "preset=${preset.name}",
                0.18f,
                scene / preset.displayWhiteInput,
                6e-3f,
            )
        }
    }

    @Test
    fun extendedPresetsAllReuseProjectCurves() {
        listOf(
            LogCurvePreset.LOGC4,
            LogCurvePreset.FLOG2,
            LogCurvePreset.APPLE_LOG2,
            LogCurvePreset.LOG3G10,
            LogCurvePreset.LLOG,
        ).forEach { preset ->
            val curve = requireNotNull(preset.nativeCurve) { "${preset.name} 应复用工程曲线" }
            assertEquals(curve.linearToLog(0.18f), preset.encodeCode(0.18f), 1e-6f)
            assertEquals(curve.logToLinear(0.42f), preset.decodeLinear(0.42f), 1e-6f)
        }
    }

    @Test
    fun presetProfileNameResolvesSafelyBeforePatch() {
        // V-Log 档位在工程里已存在 → 必须能解析到
        assertEquals(
            com.hinnka.mycamera.video.VideoLogProfile.V_LOG,
            LogCurvePreset.V_LOG.videoLogProfile,
        )
        // HLG / LogC3 需补丁新增；未打补丁时返回 null 而不是抛异常（无悬空引用）
        if (com.hinnka.mycamera.video.VideoLogProfile.entries.none { it.name == "HLG_BT2020" }) {
            assertNull(LogCurvePreset.HLG.videoLogProfile)
        }
        assertFalse(LogCurvePreset.LOG_C3.videoLogProfileName.isBlank())
    }

    // ─────────────────── 查表精度与单调性 ───────────────────

    @Test
    fun tableSampleMatchesAnalyticCurveWithinInterpolationError() {
        val recipe = LogCurveRecipe(preset = LogCurvePreset.LOG_C3, tableSize = 1024)
        val table = LogCurveTable.build(recipe)
        assertEquals(1024, table.size)
        // 1024 分辨率下线性插值误差应远小于 1/1024
        var index = 0
        while (index <= 1000) {
            val t = index / 1000f
            val analytic = recipe.encode(t * recipe.peak)
            assertEquals(analytic, table.sample(t * recipe.peak), 2e-3f)
            index += 7
        }
    }

    @Test
    fun tableIsMonotonicAndSmoothForAllPresets() {
        LogCurvePreset.entries.forEach { preset ->
            val table = LogCurveTable.build(LogCurveRecipe(preset = preset, tableSize = 512))
            assertTrue("${preset.name} 应单调不减", table.isMonotonicNonDecreasing())
            assertTrue("${preset.name} 相邻跳变应平滑", table.maxAdjacentDelta() < 0.05f)
        }
    }

    @Test
    fun tableEndpointsAreClampedIntoCodeRange() {
        val table = LogCurveTable.build(
            LogCurveRecipe(preset = LogCurvePreset.LOG_C3, tableSize = LogCurveRecipe.TABLE_SIZE_MIN),
        )
        assertEquals(LogCurveRecipe.TABLE_SIZE_MIN, table.size)
        assertTrue(table.sampleNormalized(0f) in 0f..1f)
        assertTrue(table.sampleNormalized(1f) in 0f..1f)
        // 越界输入不崩、按端点取值
        assertEquals(table.sampleNormalized(1f), table.sampleNormalized(5f), 1e-6f)
        assertEquals(table.sampleNormalized(0f), table.sampleNormalized(-3f), 1e-6f)
    }

    @Test
    fun curvePointsExposeTableForGpuCurveTexture() {
        val table = LogCurveTable.build(LogCurveRecipe(preset = LogCurvePreset.V_LOG))
        val points = table.toCurvePoints(64)
        assertEquals(64, points.size)
        // 首尾与表端点一致（可直接喂既有 masterCurvePoints 1D 曲线通道）
        assertEquals(table.sampleNormalized(0f), points.first(), 1e-6f)
        assertEquals(table.sampleNormalized(1f), points.last(), 1e-6f)
    }

    @Test
    fun identityTableIsLinear() {
        val identity = LogCurveTable.identity(64)
        assertEquals(0f, identity.sampleNormalized(0f), 1e-6f)
        assertEquals(0.5f, identity.sampleNormalized(0.5f), 1e-3f)
        assertEquals(1f, identity.sampleNormalized(1f), 1e-6f)
    }

    @Test
    fun activeTableCachePublishesAndReuses() {
        ActiveLogCurveTable.clear()
        val recipe = LogCurveRecipe(preset = LogCurvePreset.HLG, tableSize = 256)
        val built = LogCurveTable.build(recipe)
        ActiveLogCurveTable.publish(built)
        assertEquals(built, ActiveLogCurveTable.current(recipe))
        ActiveLogCurveTable.clear()
    }
}
