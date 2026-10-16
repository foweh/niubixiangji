package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 掩膜判定与羽化距离变换的测试（修复功能的边界质量直接取决于它）。
 *
 * 覆盖：全内/全外、单像素、阈值边界、羽化半径、零/负半径、空输入、非法阈值。
 */
class SuperResMaskFeatherTest {

    private val transparentWhite = 0x00FFFFFF
    private val opaqueBlack = 0xFF000000.toInt()
    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val justBelowHalf = 0xFF7F7F7F.toInt() // 127/255 ≈ 0.498
    private val justAboveHalf = 0xFF808080.toInt() // 128/255 ≈ 0.502

    // ---- 正常 ----

    @Test
    fun maskFromArgb_marksTransparentAndDarkPixelsAsToFill() {
        val argb = intArrayOf(transparentWhite, opaqueBlack, opaqueWhite, justBelowHalf, justAboveHalf)
        val mask = SuperResMaskFeather.maskFromArgb(argb, width = 5, height = 1, threshold = 0.5f)
        assertEquals(5, mask.size)
        assertTrue("透明像素需要补全", mask[0])
        assertTrue("纯黑（用户涂抹）需要补全", mask[1])
        assertFalse("不透明白不需要补全", mask[2])
        assertTrue("恰在阈值以下的灰需要补全", mask[3])
        assertFalse("恰在阈值以上的灰不需要补全", mask[4])
    }

    @Test
    fun alphaFor_hardEdgeWhenFeatherIsZero() {
        val inside = booleanArrayOf(false, true)
        val alpha = SuperResMaskFeather.alphaFor(inside, width = 2, height = 1, featherPx = 0)
        assertEquals(0f, alpha[0], 0f)
        assertEquals(1f, alpha[1], 0f)
    }

    @Test
    fun alphaFor_singlePixelMask_scalesWithFeatherRadius() {
        val inside = BooleanArray(9)
        inside[4] = true // 3x3 正中
        val tight = SuperResMaskFeather.alphaFor(inside, 3, 3, featherPx = 1)
        assertEquals(1f, tight[4], 1e-6f)

        val loose = SuperResMaskFeather.alphaFor(inside, 3, 3, featherPx = 4)
        assertEquals(0.25f, loose[4], 1e-6f)
        assertEquals("掩膜外恒为 0", 0f, loose[0], 0f)
    }

    // ---- 边界 ----

    @Test
    fun alphaFor_boundaryPixelIsHalfAndNextPixelIsFull() {
        // 9x1：右侧 5 个像素待补全，羽化半径 2 → 边界像素 0.5，内侧第 2 个像素 1.0。
        val inside = BooleanArray(9) { it >= 4 }
        val alpha = SuperResMaskFeather.alphaFor(inside, width = 9, height = 1, featherPx = 2)
        assertEquals(0f, alpha[3], 0f)
        assertEquals(0.5f, alpha[4], 1e-6f)
        assertEquals(1f, alpha[5], 1e-6f)
        assertEquals(1f, alpha[8], 1e-6f)
    }

    @Test
    fun alphaFor_fullyInsideMaskBecomesFullyAi() {
        val inside = BooleanArray(4) { true }
        val alpha = SuperResMaskFeather.alphaFor(inside, 2, 2, featherPx = 3)
        alpha.forEach { assertEquals("无边界时应全量采用 AI 结果", 1f, it, 1e-6f) }
    }

    @Test
    fun alphaFor_fullyOutsideMaskIsAllZero() {
        val alpha = SuperResMaskFeather.alphaFor(BooleanArray(4), 2, 2, featherPx = 3)
        alpha.forEach { assertEquals(0f, it, 0f) }
    }

    @Test
    fun alphaForArgb_endToEndRamp() {
        // 4x1：前两个待补全（透明），后两个保留（白）。距离从左向右递减。
        val argb = intArrayOf(transparentWhite, transparentWhite, opaqueWhite, opaqueWhite)
        val alpha = SuperResMaskFeather.alphaForArgb(argb, 4, 1, threshold = 0.5f, featherPx = 2)
        assertEquals("掩膜外恒为 0", 0f, alpha[2], 0f)
        assertTrue(alpha[0] > 0f && alpha[0] <= 1f)
        assertTrue("越远离边界越接近 1", alpha[0] >= alpha[1])
        assertTrue("贴边界处应小于内部", alpha[1] < alpha[0])
    }

    // ---- 异常 ----

    @Test
    fun alphaFor_zeroSizedInputReturnsEmpty() {
        assertTrue(SuperResMaskFeather.alphaFor(BooleanArray(0), 0, 0, 4).isEmpty())
        assertTrue(SuperResMaskFeather.alphaFor(BooleanArray(4), -1, 2, 4).isEmpty())
    }

    @Test
    fun alphaFor_negativeFeatherBehavesLikeHardEdge() {
        val inside = booleanArrayOf(true, false)
        val alpha = SuperResMaskFeather.alphaFor(inside, 2, 1, featherPx = -5)
        assertEquals(1f, alpha[0], 0f)
        assertEquals(0f, alpha[1], 0f)
    }

    @Test
    fun alphaFor_shortMaskArrayIsTreatedAsOutside() {
        // 掩膜长度不足时按「不补全」处理，不得越界崩溃。
        val alpha = SuperResMaskFeather.alphaFor(BooleanArray(1) { true }, 3, 1, featherPx = 2)
        assertEquals(3, alpha.size)
        alpha.forEach { assertFalse(it.isNaN()) }
        assertEquals(0f, alpha[2], 0f)
    }

    @Test
    fun maskFromArgb_invalidThresholdIsClampedOrDefaults() {
        val argb = intArrayOf(opaqueWhite, opaqueBlack)
        // 阈值 > 1 → clamp 到 1 → 全部视为待补全。
        val all = SuperResMaskFeather.maskFromArgb(argb, 2, 1, threshold = 9f)
        assertTrue(all.all { it })
        // NaN 阈值 → 回落 0.5 → 白色保留、黑色补全。
        val nan = SuperResMaskFeather.maskFromArgb(argb, 2, 1, threshold = Float.NaN)
        assertFalse(nan[0])
        assertTrue(nan[1])
    }

    @Test
    fun maskFromArgb_shorterArrayThanSizeDoesNotThrow() {
        val mask = SuperResMaskFeather.maskFromArgb(intArrayOf(opaqueWhite), width = 3, height = 2, threshold = 0.5f)
        assertEquals(6, mask.size)
        assertFalse(mask[0])
        assertTrue("缺失像素按 0 处理 → 视为待补全", mask[5])
    }
}
