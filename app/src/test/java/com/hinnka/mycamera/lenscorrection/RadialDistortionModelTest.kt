package com.hinnka.mycamera.lenscorrection

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 径向畸变模型单测：恒等、单调性、导数正确性、正向/逆向映射互逆、非有限值防护。
 */
class RadialDistortionModelTest {

    private val ultraWide = LensProfileDefaults.presetFor(LensCameraDimension.ULTRA_WIDE)

    @Test
    fun identityModelIsExactlyIdentity() {
        val model = RadialDistortionModel.IDENTITY
        assertTrue(model.isIdentity)
        val mapped = model.mapToSource(0.3f, -0.7f)
        assertEquals(0.3f, mapped[0], 1e-7f)
        assertEquals(-0.7f, mapped[1], 1e-7f)
        val ideal = model.mapToIdeal(0.3f, -0.7f)
        assertEquals(0.3f, ideal[0], 1e-6f)
        assertEquals(-0.7f, ideal[1], 1e-6f)
    }

    @Test
    fun factorIsOneAtZeroRadius() {
        assertEquals(1f, RadialDistortionModel.IDENTITY.distortionFactor(0f), 1e-7f)
        assertEquals(1f, RadialDistortionModel(0.4f, -0.1f, 0.02f).distortionFactor(0f), 1e-7f)
    }

    @Test
    fun factorIncreasesMonotonicallyForBarrelCorrection() {
        val model = RadialDistortionModel(ultraWide.k1, ultraWide.k2, ultraWide.k3)
        var previous = model.distortionFactor(0f)
        var r2 = 0f
        while (r2 <= 1f) {
            val current = model.distortionFactor(r2)
            assertTrue("s must grow with r²: s($r2)=$current < $previous", current >= previous - 1e-6f)
            previous = current
            r2 += 0.05f
        }
        assertTrue("k1>0 must stretch the edge: s(1)=${model.distortionFactor(1f)}", model.distortionFactor(1f) > 1f)
    }

    @Test
    fun positiveK1PushesSamplePointOutward() {
        val model = RadialDistortionModel(ultraWide.k1, ultraWide.k2)
        val source = model.mapToSource(0.3f, 0.4f)
        val before = sqrt(0.3f * 0.3f + 0.4f * 0.4f)
        val after = sqrt(source[0] * source[0] + source[1] * source[1])
        assertTrue("radius must grow: $before -> $after", after > before)
        // 纯径向：方向不能变
        assertEquals(source[0] / source[1], 0.3f / 0.4f, 1e-4f)
    }

    @Test
    fun derivativeAtZeroIsOne() {
        assertEquals(1f, RadialDistortionModel(0.5f, -0.2f, 0.1f).distortionFactorDerivative(0f), 1e-7f)
    }

    @Test
    fun derivativeMatchesNumericalGradient() {
        val model = RadialDistortionModel(0.28f, -0.05f, 0.01f)
        val h = 1e-3f
        // f(r) = r · s(r²) → f'(r) 就是 distortionFactorDerivative(r²)
        for (r in listOf(0.05f, 0.2f, 0.5f, 0.8f, 1.2f)) {
            val forward = (r + h) * model.distortionFactor((r + h) * (r + h))
            val backward = (r - h) * model.distortionFactor((r - h) * (r - h))
            val numeric = (forward - backward) / (2f * h)
            val analytic = model.distortionFactorDerivative(r * r)
            assertEquals("derivative at r=$r", numeric, analytic, 5e-3f)
        }
    }

    @Test
    fun mapToIdealInvertsMapToSource() {
        val model = RadialDistortionModel(ultraWide.k1, ultraWide.k2, ultraWide.k3)
        val points = listOf(
            0.1f to 0.1f,
            0.3f to 0.4f,
            -0.5f to 0.2f,
            0.6f to -0.6f,
            0.9f to 0f,
        )
        for ((u, v) in points) {
            val source = model.mapToSource(u, v)
            val roundTrip = model.mapToIdeal(source[0], source[1])
            assertEquals("u round trip ($u,$v)", u, roundTrip[0], 2e-3f)
            assertEquals("v round trip ($u,$v)", v, roundTrip[1], 2e-3f)
        }
    }

    @Test
    fun mapToIdealAcceptsExplicitIterationCountAndClampsIt() {
        val model = RadialDistortionModel(ultraWide.k1, ultraWide.k2)
        val source = model.mapToSource(0.4f, 0.4f)
        // 迭代次数越界必须被 clamp，而不是抛异常或死循环
        assertEquals(0.4f, model.mapToIdeal(source[0], source[1], iterations = 0)[0], 5e-3f)
        assertEquals(0.4f, model.mapToIdeal(source[0], source[1], iterations = 10_000)[0], 5e-3f)
    }

    @Test
    fun mapToIdealHandlesDegenerateInput() {
        val model = RadialDistortionModel(ultraWide.k1, ultraWide.k2)
        assertEquals(0f, model.mapToIdeal(0f, 0f)[0], 0f)
        assertEquals(0f, model.mapToIdeal(Float.NaN, 0.5f)[0], 0f)
        assertEquals(0f, model.mapToIdeal(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)[1], 0f)
    }

    @Test
    fun factorTreatsInvalidRadiusSquaredAsZero() {
        val model = RadialDistortionModel(0.3f, 0.1f)
        assertEquals(1f, model.distortionFactor(Float.NaN), 1e-7f)
        assertEquals(1f, model.distortionFactor(-5f), 1e-7f)
    }

    @Test
    fun constructorRejectsNonFiniteCoefficients() {
        assertThrows(IllegalArgumentException::class.java) { RadialDistortionModel(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { RadialDistortionModel(0f, Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            RadialDistortionModel(0f, 0f, Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun fromCamera2RadialSanitizesNonFiniteValues() {
        val model = RadialDistortionModel.fromCamera2Radial(Float.NaN, 0.12f, Float.NEGATIVE_INFINITY)
        assertEquals(0f, model.k1, 0f)
        assertEquals(0.12f, model.k2, 1e-7f)
        assertEquals(0f, model.k3, 0f)
        assertFalse(model.isIdentity)
        assertEquals(0f, RadialDistortionModel.fromCamera2Radial(0f, 0f).k3, 0f)
    }

    @Test
    fun profileModelsMatchPresetCoefficients() {
        assertEquals(ultraWide.k1, ultraWide.model(1f).k1, 1e-7f)
        assertEquals(ultraWide.k2 * 0.5f, ultraWide.model(0.5f).k2, 1e-7f)
        assertTrue("0 强度必须退化为恒等模型", ultraWide.model(0f).isIdentity)
        // 强度越界被 clamp，不放大镜头参数
        assertEquals(ultraWide.k1, ultraWide.model(9f).k1, 1e-7f)
        assertEquals(ultraWide.k1, ultraWide.model(Float.NaN).k1, 1e-7f)
    }

    @Test
    fun ultraWideHasStrongerBarrelThanMain() {
        val main = LensProfileDefaults.presetFor(LensCameraDimension.MAIN)
        assertTrue("超广角桶形畸变必须明显强于主摄", ultraWide.k1 > main.k1)
        assertTrue("超广角建议裁切系数更大", ultraWide.suggestedCropScale > main.suggestedCropScale)
        assertTrue(abs(ultraWide.k2) < ultraWide.k1)
    }
}
