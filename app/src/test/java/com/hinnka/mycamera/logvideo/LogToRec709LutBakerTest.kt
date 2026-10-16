package com.hinnka.mycamera.logvideo

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.raw.ColorSpace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Log→Rec.709 烘焙器测试（Log 视频 20 项 · 第 4 项「Log LUT 包强化：运行时程序化烘焙」）。
 *
 * 三层校验：
 *  1. **已知点**：LogC3 / HLG 中灰、白点、黑位落点与公开规格一致（数值独立复算，非自证）；
 *  2. **色域矩阵**：与工程 GLSL `applyLutColorSpace` 既有分支数值逐位一致（复用而非自研的证明）；
 *  3. **产物格式**：`.plut` 头部/长度/UINT16 编码 + `.cube` 文本行数，保证能被工程 `LutParser` 读回。
 */
class LogToRec709LutBakerTest {

    private val size = 33
    private val step = 1f / (size - 1)

    private fun neutralAt(lut: FloatArray, index: Int, size: Int = 33): Float {
        val offset = ((index * size + index) * size + index) * 3
        return lut[offset]
    }

    // ─────────────────── 1. 已知点比对 ───────────────────

    @Test
    fun logC3RestoreLutHitsKnownCodes() {
        val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.LOG_C3), size)
        assertEquals(size * size * size * 3, lut.size)
        // 索引 0 = 全黑；索引 12 = 代码 0.375；索引 32 = 代码 1.0
        assertEquals(0f, neutralAt(lut, 0), 1e-4f)
        assertEquals(0.374f, neutralAt(lut, 12), 2e-3f)
        // 场景白必须落在 100% 信号（软膝端点精确，不允许 0.98 之类的"到不了白"）
        assertEquals(1f, neutralAt(lut, size - 1), 1e-4f)
    }

    @Test
    fun logC3MiddleGreyLandsOnRec709Grey() {
        val recipe = LogCurveRecipe(preset = LogCurvePreset.LOG_C3)
        // 中灰代码 0.391 落在网格 12/32=0.375 与 13/32=0.406 之间，取两格插值近似
        val g0 = neutralAt(LogToRec709LutBaker.bake(recipe, size), 12)
        val g1 = neutralAt(LogToRec709LutBaker.bake(recipe, size), 13)
        val interpolated = g0 + (g1 - g0) * ((0.391f - 12 * step) / step)
        assertEquals(0.409f, interpolated, 4e-3f)
    }

    @Test
    fun hlgRestoreLutAnchorsDiffuseWhiteAtFullSignal() {
        val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.HLG), size)
        assertEquals(0f, neutralAt(lut, 0), 1e-4f)
        // BT.2408：HLG 代码 0.75 = 漫反射白 = 100% 显示信号（索引 24）
        assertEquals(1f, neutralAt(lut, 24), 1e-4f)
        // 代码 0.375 → 场景 0.0469 → 约 40.5% 信号（18% 灰量级）
        assertEquals(0.405f, neutralAt(lut, 12), 2e-3f)
        // BT.2100 峰值（代码 1.0）被软膝/裁切收进白
        assertEquals(1f, neutralAt(lut, size - 1), 1e-4f)
    }

    @Test
    fun neutralAxisIsMonotonicForEveryPreset() {
        LogCurvePreset.entries.forEach { preset ->
            val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = preset), size)
            var previous = -1f
            for (index in 0 until size) {
                val value = neutralAt(lut, index)
                assertTrue("${preset.name} idx=$index", value >= previous - 1e-6f)
                previous = value
            }
        }
    }

    @Test
    fun redCornerStaysPureRedAfterGamutMatrix() {
        val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.LOG_C3), size)
        // 红变化最快：r=32,g=0,b=0 的偏移 = ((0*size+0)*size+32)*3
        val offset = ((0 * size + 0) * size + (size - 1)) * 3
        assertEquals(1f, lut[offset], 1e-4f)
        assertEquals(0f, lut[offset + 1], 1e-4f)
        assertEquals(0f, lut[offset + 2], 1e-4f)
    }

    @Test
    fun allNeutralSamplesAreFiniteAndInRange() {
        LogCurvePreset.entries.forEach { preset ->
            val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = preset), size)
            assertTrue("${preset.name}", lut.all { it.isFinite() && it in 0f..1f })
        }
    }

    // ─────────────────── 2. 色域矩阵与工程 GLSL 一致 ───────────────────

    /** 工程 `PreviewColorShaderModules.applyLutColorSpace` 中 colorSpace==2 分支的原始数字（GLSL mat3 = 列主序）。 */
    private val shippedBt2020ByGlsl = floatArrayOf(
        0.627404f, 0.069097f, 0.016391f,
        0.329283f, 0.919540f, 0.088013f,
        0.043313f, 0.011362f, 0.895595f,
    )

    /** colorSpace==3（ARRI4）分支。 */
    private val shippedArri4ByGlsl = floatArrayOf(
        0.565837f, 0.088626f, 0.017750f,
        0.340331f, 0.809347f, 0.102028f,
        0.093832f, 0.109448f, 0.872802f,
    )

    /** colorSpace==7（V-Gamut）分支。 */
    private val shippedVGamutByGlsl = floatArrayOf(
        0.585196f, 0.078589f, 0.022794f,
        0.322642f, 0.819627f, 0.114217f,
        0.092162f, 0.101784f, 0.862989f,
    )

    private fun transpose(m: FloatArray): FloatArray = floatArrayOf(
        m[0], m[3], m[6],
        m[1], m[4], m[7],
        m[2], m[5], m[8],
    )

    private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray, tag: String) {
        assertEquals("$tag 长度", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("$tag[$index]", expected[index], actual[index], 1e-5f)
        }
    }

    @Test
    fun gamutMatrixMatchesExistingShaderBranches() {
        assertMatrixEquals(
            shippedBt2020ByGlsl,
            transpose(LogGamutMatrix.conversion(LogGamut.REC709, LogGamut.BT2020)),
            "709→BT2020",
        )
        assertMatrixEquals(
            shippedArri4ByGlsl,
            transpose(LogGamutMatrix.conversion(LogGamut.REC709, LogGamut.ARRI4)),
            "709→ARRI4",
        )
        assertMatrixEquals(
            shippedVGamutByGlsl,
            transpose(LogGamutMatrix.conversion(LogGamut.REC709, LogGamut.VGamut)),
            "709→V-Gamut",
        )
    }

    @Test
    fun newArri3MatrixIsStableForPatchSnippet() {
        // 补丁将把这组数字（列主序）贴进 applyLutColorSpace 的 colorSpace==12 分支
        val expectedRowMajor = floatArrayOf(
            0.631321f, 0.270801f, 0.097878f,
            0.036820f, 0.793037f, 0.170143f,
            0.017370f, 0.148789f, 0.833841f,
        )
        assertMatrixEquals(expectedRowMajor, LogGamutMatrix.conversion(LogGamut.REC709, LogGamut.AWRI_WIDE_GAMUT_3), "709→AWG3")
    }

    @Test
    fun matrixPreservesNeutralWhiteAndInverseCancels() {
        LogGamut.entries.forEach { gamut ->
            val forward = LogGamutMatrix.conversion(LogGamut.REC709, gamut)
            val white = LogGamutMatrix.apply(forward, 1f, 1f, 1f)
            white.forEach { assertEquals(1f, it, 2e-4f) }
            val back = LogGamutMatrix.conversion(gamut, LogGamut.REC709)
            val roundTrip = LogGamutMatrix.apply(back, white[0], white[1], white[2])
            assertEquals(1f, roundTrip[0], 2e-4f)
            assertEquals(1f, roundTrip[1], 2e-4f)
            assertEquals(1f, roundTrip[2], 2e-4f)
        }
        assertMatrixEquals(LogGamutMatrix.identity(), LogGamutMatrix.conversion(LogGamut.REC709, LogGamut.REC709), "同名恒等")
    }

    @Test
    fun singularMatrixReturnsNullInsteadOfCrashing() {
        // 退化矩阵（全 0）必须返回 null，调用方走 identity 兜底
        assertNull(LogGamutMatrix.invert3x3(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
    }

    // ─────────────────── 3. 产物格式（.plut / .cube） ───────────────────

    @Test
    fun plutBytesCarryProjectCompatibleHeaderAndPayload() {
        val small = 9
        val count = small * small * small * 3
        val lut = FloatArray(count) { it.toFloat() / (count - 1) }
        val bytes = LogToRec709LutBaker.writePlutBytes(lut, small, TransferCurve.HLG, ColorSpace.BT2020)
        assertEquals(24 + count * 2, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x54554C50, buffer.int)          // 'PLUT' magic
        assertEquals(3, buffer.int)                   // version（LutParser 支持 1..4）
        assertEquals(small, buffer.int)               // size
        assertEquals(1, buffer.int)                   // dataType = UINT16
        assertEquals(TransferCurve.HLG.storageId, buffer.int)   // 曲线标签
        assertEquals(ColorSpace.BT2020.ordinal, buffer.int)     // 色域标签
        assertEquals(0, buffer.short.toInt() and 0xFFFF)        // 首个样本 0.0 → 0
        var last = 0
        while (buffer.hasRemaining()) last = buffer.short.toInt() and 0xFFFF
        assertEquals(65535, last)                     // 最后样本 1.0 → 满值
    }

    @Test
    fun plutBytesRejectOutOfRangeSize() {
        try {
            LogToRec709LutBaker.writePlutBytes(FloatArray(3), LogToRec709LutBaker.MIN_SIZE - 1, TransferCurve.HLG, ColorSpace.BT2020)
            fail("应抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size"))
        }
        try {
            LogToRec709LutBaker.writePlutBytes(FloatArray(3), LogToRec709LutBaker.DEFAULT_SIZE, TransferCurve.HLG, ColorSpace.BT2020)
            fail("数据过小应抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too small"))
        }
    }

    @Test
    fun cubeTextIsWellFormed() {
        val small = 9
        val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.HLG), small)
        val text = LogToRec709LutBaker.writeCubeText(lut, small, "HLG Restore", listOf("preset=HLG"))
        val lines = text.trim().lines()
        assertTrue(text.contains("LUT_3D_SIZE 9"))
        assertTrue(text.contains("TITLE \"HLG Restore\""))
        assertTrue(text.contains("# preset=HLG"))
        val dataLines = lines.count { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2 + small * small * small, dataLines)  // TITLE + LUT_3D_SIZE + 数据
        assertEquals(0f, lut[0], 1e-4f)
    }

    @Test
    fun describeIncludesAxisAndKnee() {
        val text = LogToRec709LutBaker.describe(
            LogCurveRecipe(preset = LogCurvePreset.V_LOG),
            33,
            axisCompensation = TransferCurve.VLOG,
        )
        assertTrue(text.contains("V_LOG"))
        assertTrue(text.contains("size=33"))
        assertTrue(text.contains("axis=VLOG"))
    }

    // ─────────────────── 4. 显示编码与软膝边界 ───────────────────

    @Test
    fun rec709OetfMatchesSpecAtKnownPoints() {
        assertEquals(0.409f, LogToRec709LutBaker.rec709Oetf(0.18f), 1e-3f)
        assertEquals(1f, LogToRec709LutBaker.rec709Oetf(1f), 1e-3f)
        assertEquals(0f, LogToRec709LutBaker.rec709Oetf(0f), 1e-6f)
        listOf(0.01f, 0.18f, 0.5f, 0.9f, 1f).forEach { linear ->
            val code = LogToRec709LutBaker.rec709Oetf(linear)
            assertEquals(linear, LogToRec709LutBaker.rec709InverseOetf(code), 2e-3f)
        }
    }

    @Test
    fun softKneeIsExactAtBothEndsAndOffAtZero() {
        assertEquals(1f, LogToRec709LutBaker.softKnee(1f, 0.9f), 1e-6f)
        assertEquals(0.18f, LogToRec709LutBaker.softKnee(0.18f, 0.9f), 1e-6f)
        assertEquals(0.95f, LogToRec709LutBaker.softKnee(0.95f, 0f), 1e-6f)
        assertEquals(0.9f, LogToRec709LutBaker.softKnee(0.9f, 0.9f), 1e-6f)
        // 软膝单调且不压缩中灰以下
        var previous = -1f
        var x = 0f
        while (x <= 1f) {
            val y = LogToRec709LutBaker.softKnee(x, 0.9f)
            assertTrue(y >= previous - 1e-6f)
            previous = y
            x += 0.01f
        }
    }

    @Test
    fun nonFiniteKneeDegradesToHardClipAndNeverProducesNaN() {
        val nanKnee = LogToRec709LutBaker.encodeDisplay(0.995f, Float.NaN, LogDisplayOetf.REC709)
        val zeroKnee = LogToRec709LutBaker.encodeDisplay(0.995f, 0f, LogDisplayOetf.REC709)
        assertEquals(zeroKnee, nanKnee, 1e-6f)
        assertTrue(nanKnee.isFinite())
        assertEquals(0f, LogToRec709LutBaker.safeKnee(Float.NaN), 1e-6f)
        assertEquals(0f, LogToRec709LutBaker.safeKnee(Float.NEGATIVE_INFINITY), 1e-6f)
        assertEquals(LogToRec709LutBaker.MAX_HIGHLIGHT_KNEE, LogToRec709LutBaker.safeKnee(9f), 1e-6f)
        // 整条 LUT 在 NaN 膝下仍全部有限
        val lut = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.HLG), 9, highlightKnee = Float.NaN)
        assertTrue(lut.all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun sRGBOetfIsSelectableForConsistencyWithExistingRestoreLuts() {
        val rec709 = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.HLG), 9, oetf = LogDisplayOetf.REC709)
        val srgb = LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.HLG), 9, oetf = LogDisplayOetf.SRGB)
        val mid = ((4 * 9 + 4) * 9 + 4) * 3
        assertTrue("sRGB 口味应比 Rec.709 略亮（对齐既有 6 款内置 LUT）", srgb[mid] > rec709[mid])
        assertEquals(1f, srgb[((8 * 9 + 8) * 9 + 8) * 3], 1e-4f)
    }

    @Test
    fun bakeSizeIsClampedIntoSupportedRange() {
        assertEquals(LogToRec709LutBaker.MIN_SIZE * LogToRec709LutBaker.MIN_SIZE * LogToRec709LutBaker.MIN_SIZE * 3,
            LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.V_LOG), 1).size)
        assertEquals(LogToRec709LutBaker.MAX_SIZE * LogToRec709LutBaker.MAX_SIZE * LogToRec709LutBaker.MAX_SIZE * 3,
            LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.V_LOG), 9999).size)
    }

    @Test
    fun progressCallbackReportsEverySliceAndFinishes() {
        var lastDone = 0
        var lastTotal = 0
        var calls = 0
        LogToRec709LutBaker.bake(LogCurveRecipe(preset = LogCurvePreset.LOG_C3), 17) { done, total ->
            lastDone = done
            lastTotal = total
            calls++
        }
        assertEquals(17, lastTotal)
        assertEquals(17, lastDone)
        assertEquals(17, calls)   // 每个蓝切片回调一次
    }

    // ─────────────────── 5. 轴预补偿（走 CustomImportManager 入库时） ───────────────────

    @Test
    fun axisCompensationIsIdentityForSRGBImportCurve() {
        listOf(0f, 0.1f, 0.5f, 0.9f, 1f).forEach { axis ->
            assertEquals(axis, LogToRec709LutBaker.compensatedAxis(axis, TransferCurve.SRGB), 1e-5f)
        }
        // null = 不补偿（直接写 .plut / 导出 .cube）
        assertEquals(0.42f, LogToRec709LutBaker.compensatedAxis(0.42f, null), 1e-6f)
    }

    @Test
    fun axisCompensationShiftsAxisForLogImportCurve() {
        val shifted = LogToRec709LutBaker.compensatedAxis(0.5f, TransferCurve.VLOG)
        assertTrue("VLog 导入会重采样输入轴，必须补偿", kotlin.math.abs(shifted - 0.5f) > 0.05f)
        assertTrue(shifted in 0f..1f)
        // 端点在库内仍映射到端点（黑=黑、白=白）
        assertEquals(0f, LogToRec709LutBaker.compensatedAxis(0f, TransferCurve.VLOG), 1e-4f)
        assertEquals(1f, LogToRec709LutBaker.compensatedAxis(1f, TransferCurve.VLOG), 1e-3f)
    }

    @Test
    fun displayReferredDividesByPresetWhiteAnchor() {
        // non-HLG：白点锚 = 1.0 → 与配方解码一致
        val logC3 = LogCurveRecipe(preset = LogCurvePreset.LOG_C3)
        assertEquals(
            logC3.decode(LogCurvePreset.LOG_C3.middleGreyCode),
            LogToRec709LutBaker.displayReferred(logC3, LogCurvePreset.LOG_C3.middleGreyCode),
            1e-6f,
        )
        // HLG：按 BT.2408 漫反射白锚（0.2649）归一，使 18% 中灰回到 0.18
        val hlg = LogCurveRecipe(preset = LogCurvePreset.HLG)
        assertEquals(0.18f, LogToRec709LutBaker.displayReferred(hlg, LogCurvePreset.HLG.middleGreyCode), 4e-3f)
        assertFalse(LogCurvePreset.HLG.displayWhiteInput == LogCurvePreset.LOG_C3.displayWhiteInput)
    }
}
