package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 像素调节数学单测：亮度/对比度/饱和度/色温的矩阵、clamp 与组合顺序。
 *
 * 覆盖 PATTERN_SPEC §7.4 要求的"旋转矩阵（此处为颜色矩阵）/ 亮度对比度 clamp"两项。
 */
class GePixelAdjustMathTest {

    private fun assertMatrixEquals(expected: GeColorMatrix, actual: GeColorMatrix, tolerance: Float = 1e-4f) {
        expected.values.indices.forEach { index ->
            assertTrue(
                "矩阵元素 $index 不一致：expected=${expected.values[index]} actual=${actual.values[index]}",
                abs(expected.values[index] - actual.values[index]) <= tolerance,
            )
        }
    }

    @Test
    fun `默认参数为单位变换`() {
        val matrix = GePixelAdjustMath.buildMatrix(GePixelAdjustParams())

        assertTrue(matrix.isIdentity())
        assertTrue(GePixelAdjustParams().isIdentity)
    }

    @Test
    fun `亮度在两端给出正负最大偏移`() {
        val plus = GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MAX)
        val minus = GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MIN)

        assertEquals(GeEditRanges.BRIGHTNESS_MAX_LEVELS, plus.values[4], 1e-4f)
        assertEquals(GeEditRanges.BRIGHTNESS_MAX_LEVELS, plus.values[9], 1e-4f)
        assertEquals(GeEditRanges.BRIGHTNESS_MAX_LEVELS, plus.values[14], 1e-4f)
        assertEquals(-GeEditRanges.BRIGHTNESS_MAX_LEVELS, minus.values[4], 1e-4f)
    }

    @Test
    fun `亮度越界输入等价于边界值`() {
        val outOfRange = GePixelAdjustMath.brightnessMatrix(9999)
        val boundary = GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MAX)

        assertMatrixEquals(boundary, outOfRange)
        assertMatrixEquals(
            GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MIN),
            GePixelAdjustMath.brightnessMatrix(-9999),
        )
    }

    @Test
    fun `亮度整体平移像素值并被 clamp 到合法色阶`() {
        val plus = GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MAX)

        val dark = plus.applyToRgb(0, 0, 0)
        assertEquals(64, dark.first)
        assertEquals(64, dark.second)
        assertEquals(64, dark.third)

        val bright = plus.applyToRgb(255, 255, 255)
        assertEquals(255, bright.first)
        assertEquals(255, bright.second)
        assertEquals(255, bright.third)
    }

    @Test
    fun `亮度下限让黑色保持黑色`() {
        val minus = GePixelAdjustMath.brightnessMatrix(GeEditRanges.ADJUST_MIN)

        val black = minus.applyToRgb(0, 0, 0)
        assertEquals(0, black.first)
        assertEquals(0, black.second)
        assertEquals(0, black.third)
    }

    @Test
    fun `对比度以中灰为枢轴`() {
        val minus = GePixelAdjustMath.contrastMatrix(GeEditRanges.ADJUST_MIN)

        val pivot = minus.applyToRgb(128, 128, 128)
        assertEquals(128, pivot.first)
        assertEquals(128, pivot.second)
        assertEquals(128, pivot.third)
    }

    @Test
    fun `对比度降低压缩动态范围`() {
        val flat = GePixelAdjustMath.contrastMatrix(GeEditRanges.ADJUST_MIN)

        val white = flat.applyToRgb(255, 255, 255)
        val black = flat.applyToRgb(0, 0, 0)

        assertEquals(192, white.first)
        assertEquals(64, black.first)
        assertTrue("范围必须被压缩", (white.first - black.first) < 255)
    }

    @Test
    fun `对比度提高扩大动态范围`() {
        val punchy = GePixelAdjustMath.contrastMatrix(GeEditRanges.ADJUST_MAX)

        val pivot = punchy.applyToRgb(128, 128, 128)
        assertEquals(128, pivot.first)

        val white = punchy.applyToRgb(255, 255, 255)
        val black = punchy.applyToRgb(0, 0, 0)
        assertEquals(255, white.first)
        assertEquals(0, black.first)
    }

    @Test
    fun `饱和度下限把彩色压成灰度`() {
        val gray = GePixelAdjustMath.saturationMatrix(GeEditRanges.ADJUST_MIN)

        val red = gray.applyToRgb(255, 0, 0)
        assertEquals(red.first, red.second)
        assertEquals(red.second, red.third)
        assertEquals(GePixelAdjustMath.LUMA_R * 255f, red.first.toFloat(), 1f)

        val luma = GePixelAdjustMath.LUMA_R + GePixelAdjustMath.LUMA_G + GePixelAdjustMath.LUMA_B
        assertTrue("Rec.709 权重和为 1", abs(luma - 1f) < 1e-4f)
    }

    @Test
    fun `饱和度上限增强色差而白色不变`() {
        val vivid = GePixelAdjustMath.saturationMatrix(GeEditRanges.ADJUST_MAX)

        val white = vivid.applyToRgb(255, 255, 255)
        assertEquals(255, white.first)
        assertEquals(255, white.second)
        assertEquals(255, white.third)
    }

    @Test
    fun `色温正向偏暖反向偏冷`() {
        val warm = GePixelAdjustMath.temperatureMatrix(GeEditRanges.ADJUST_MAX)
        val cool = GePixelAdjustMath.temperatureMatrix(GeEditRanges.ADJUST_MIN)

        val warmOut = warm.applyToRgb(128, 128, 128)
        assertTrue("暖色调应当抬红", warmOut.first > 128)
        assertTrue("暖色调应当压蓝", warmOut.third < 128)

        val coolOut = cool.applyToRgb(128, 128, 128)
        assertTrue("冷色调应当压红", coolOut.first < 128)
        assertTrue("冷色调应当抬蓝", coolOut.third > 128)
    }

    @Test
    fun `色温不改变绿通道`() {
        val warm = GePixelAdjustMath.temperatureMatrix(GeEditRanges.ADJUST_MAX)

        assertEquals(0f, warm.values[5], 1e-6f)
        assertEquals(1f, warm.values[6], 1e-6f)
        assertEquals(0f, warm.values[7], 1e-6f)
        assertEquals(0f, warm.values[9], 1e-6f)
    }

    @Test
    fun `buildMatrix 仅在单一参数时等价于对应单矩阵`() {
        assertMatrixEquals(
            GePixelAdjustMath.brightnessMatrix(40),
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(brightness = 40)),
        )
        assertMatrixEquals(
            GePixelAdjustMath.contrastMatrix(40),
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(contrast = 40)),
        )
        assertMatrixEquals(
            GePixelAdjustMath.saturationMatrix(40),
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(saturation = 40)),
        )
        assertMatrixEquals(
            GePixelAdjustMath.temperatureMatrix(40),
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(temperature = 40)),
        )
    }

    @Test
    fun `buildMatrix 组合顺序为亮度到对比度到饱和度到色温`() {
        val params = GePixelAdjustParams(brightness = 30, contrast = 20, saturation = 10, temperature = -15)

        val expected = GePixelAdjustMath.brightnessMatrix(30)
            .compose(GePixelAdjustMath.contrastMatrix(20))
            .compose(GePixelAdjustMath.saturationMatrix(10))
            .compose(GePixelAdjustMath.temperatureMatrix(-15))

        assertMatrixEquals(expected, GePixelAdjustMath.buildMatrix(params), tolerance = 1e-2f)
    }

    @Test
    fun `buildMatrix 对越界参数先 clamp 再计算`() {
        assertMatrixEquals(
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(contrast = GeEditRanges.ADJUST_MAX)),
            GePixelAdjustMath.buildMatrix(GePixelAdjustParams(contrast = 500)),
        )
    }

    @Test
    fun `compose 与单位矩阵结合保持原矩阵`() {
        val matrix = GePixelAdjustMath.buildMatrix(
            GePixelAdjustParams(brightness = 25, saturation = -40),
        )

        assertMatrixEquals(matrix, matrix.compose(GeColorMatrix.identity()), 1e-3f)
        assertMatrixEquals(matrix, GeColorMatrix.identity().compose(matrix), 1e-3f)
    }

    @Test
    fun `compose 结果等价于逐次应用`() {
        val brightness = GePixelAdjustMath.brightnessMatrix(50)
        val contrast = GePixelAdjustMath.contrastMatrix(50)
        val composed = brightness.compose(contrast)

        val input = floatArrayOf(40f, 120f, 200f)
        val stepByStep = contrast.applyToChannel(brightness.applyToChannel(input))
        val inOneGo = composed.applyToChannel(input)

        assertEquals(stepByStep[0], inOneGo[0], 1e-3f)
        assertEquals(stepByStep[1], inOneGo[1], 1e-3f)
        assertEquals(stepByStep[2], inOneGo[2], 1e-3f)
    }

    @Test
    fun `applyToChannel 总是输出 0 到 255`() {
        val extreme = GePixelAdjustMath.buildMatrix(
            GePixelAdjustParams(brightness = 100, contrast = 100, saturation = 100, temperature = 100),
        )

        val out = extreme.applyToChannel(floatArrayOf(255f, 0f, 255f))
        out.forEach { channel ->
            assertTrue("通道值必须被 clamp：$channel", channel >= 0f && channel <= 255f)
        }
    }

    @Test
    fun `applyToRgb 先 clamp 输入再运算`() {
        val identity = GeColorMatrix.identity()

        val out = identity.applyToRgb(300, -20, 128)
        assertEquals(255, out.first)
        assertEquals(0, out.second)
        assertEquals(128, out.third)
    }

    @Test
    fun `颜色矩阵必须恰好 20 个元素`() {
        assertEquals(20, GeColorMatrix.MATRIX_SIZE)

        var threw = false
        try {
            GeColorMatrix(FloatArray(4))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `非单位矩阵被正确识别`() {
        assertFalse(GePixelAdjustMath.buildMatrix(GePixelAdjustParams(brightness = 1)).isIdentity())
        assertTrue(GeColorMatrix.identity().isIdentity())

        val copy = GePixelAdjustMath.buildMatrix(GePixelAdjustParams(saturation = 10)).copy()
        assertFalse(copy.isIdentity())
    }
}
