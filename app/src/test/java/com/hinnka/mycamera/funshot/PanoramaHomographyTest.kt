package com.hinnka.mycamera.funshot

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单应估计的边界与正常路径（任务书要求的「单应估计边界」覆盖）。 */
class PanoramaHomographyTest {

    private val thresholdPx = 3.5

    private fun estimate(
        src: List<FunPointF>,
        dst: List<FunPointF>,
        minInliers: Int = 4,
        minInlierRatio: Double = 0.0,
    ): FunHomographyResult = FunRansacHomography.estimate(
        src = src,
        dst = dst,
        iterations = 600,
        thresholdPx = thresholdPx,
        minInliers = minInliers,
        minInlierRatio = minInlierRatio,
        seed = 20261016L,
    )

    // ------------------------------------------------------------- 边界：点数不足
    @Test
    fun fewerThanFourCorrespondencesFail() {
        val src = listOf(FunPointF(0.0, 0.0), FunPointF(10.0, 0.0), FunPointF(0.0, 10.0))
        val result = estimate(src, src)
        assertFalse(result.success)
        assertNull(result.homography)
        assertEquals(0, result.inlierCount)
        assertTrue(result.message.contains(">="))
    }

    @Test
    fun mismatchedSizesUseShorterSideAndStillFail() {
        val src = listOf(FunPointF(0.0, 0.0), FunPointF(10.0, 0.0))
        val dst = listOf(FunPointF(1.0, 1.0), FunPointF(11.0, 1.0), FunPointF(1.0, 11.0))
        val result = estimate(src, dst)
        assertFalse(result.success)
        assertNull(result.homography)
    }

    // ------------------------------------------------------------- 边界：退化输入
    @Test
    fun collinearCorrespondencesFail() {
        val src = (0 until 6).map { FunPointF(it * 10.0, it * 10.0) }
        val dst = src.map { FunPointF(it.x + 5.0, it.y + 5.0) }
        val result = estimate(src, dst)
        assertFalse("collinear inputs must not produce a homography", result.success)
        assertNull(result.homography)
    }

    @Test
    fun coincidentPointsAreDegenerate() {
        val duplicated = FunPointF(20.0, 20.0)
        val src = listOf(duplicated, duplicated, duplicated, duplicated, FunPointF(50.0, 60.0))
        val dst = src.map { FunPointF(it.x + 3.0, it.y + 3.0) }
        val result = estimate(src, dst)
        assertFalse(result.success)
        assertNull(result.homography)
    }

    @Test
    fun degeneratePredicateMatchesExpectations() {
        assertTrue(FunRansacHomography.isDegenerate(listOf(FunPointF(1.0, 1.0), FunPointF(1.0, 1.0))))
        assertTrue(
            FunRansacHomography.isDegenerate(
                (0 until 5).map { FunPointF(it * 3.0, it * 3.0) },
            ),
        )
        assertFalse(
            FunRansacHomography.isDegenerate(
                listOf(FunPointF(0.0, 0.0), FunPointF(40.0, 0.0), FunPointF(0.0, 40.0), FunPointF(40.0, 40.0)),
            ),
        )
    }

    // ------------------------------------------------------------- 边界：脏值（NaN/Inf）
    @Test
    fun dirtyValuesDoNotThrowAndFail() {
        val nan = Double.NaN
        val inf = Double.POSITIVE_INFINITY
        val src = listOf(
            FunPointF(nan, 0.0),
            FunPointF(10.0, inf),
            FunPointF(0.0, 10.0),
            FunPointF(10.0, 10.0),
        )
        val dst = listOf(
            FunPointF(1.0, 1.0),
            FunPointF(11.0, 1.0),
            FunPointF(1.0, 11.0),
            FunPointF(11.0, 11.0),
        )
        val result = estimate(src, dst)
        assertFalse(result.success)
        assertNull(result.homography)
    }

    @Test
    fun nonPositiveThresholdFallsBackToDefaultWithoutThrowing() {
        val src = FunshotTestFixtures.gridPoints(3, 3, 30.0, 30.0)
        val dst = FunshotTestFixtures.affine(src, 1.0, 4.0, 4.0)
        val result = FunRansacHomography.estimate(
            src = src,
            dst = dst,
            iterations = 200,
            thresholdPx = 0.0,
            minInliers = 4,
            minInlierRatio = 0.5,
            seed = 7L,
        )
        assertTrue(result.success)
    }

    // ------------------------------------------------------------- 正常：已知变换可恢复
    @Test
    fun fourPointSolveReproducesExactAffine() {
        val src = listOf(
            FunPointF(0.0, 0.0),
            FunPointF(50.0, 0.0),
            FunPointF(0.0, 40.0),
            FunPointF(50.0, 40.0),
        )
        val dst = FunshotTestFixtures.affine(src, 2.0, 10.0, 5.0)
        val h = FunRansacHomography.solveFourPoint(src, dst)
        assertNotNull(h)
        val projected = h!!.transform(src[3])
        assertNotNull(projected)
        assertEquals(dst[3].x, projected!!.x, 1e-6)
        assertEquals(dst[3].y, projected.y, 1e-6)
    }

    @Test
    fun affineTransformIsRecoveredWithinSubPixelError() {
        val src = FunshotTestFixtures.gridPoints(4, 3, 37.0, 29.0)
        val dst = FunshotTestFixtures.affine(src, 1.2, 25.0, -12.0)
        val result = estimate(src, dst, minInliers = 6, minInlierRatio = 0.5)
        assertTrue(result.message, result.success)
        val matrix = result.homography
        assertNotNull(matrix)
        var maxError = 0.0
        for (i in src.indices) {
            val projected = matrix!!.transform(src[i])
            assertNotNull(projected)
            val dx = projected!!.x - dst[i].x
            val dy = projected.y - dst[i].y
            maxError = kotlin.math.max(maxError, sqrt(dx * dx + dy * dy))
        }
        assertTrue("max reprojection error $maxError", maxError < 0.5)
        assertEquals(src.size, result.inlierCount)
        assertEquals(1.0, result.inlierRatio, 1e-9)
    }

    @Test
    fun singleOutlierIsExcludedFromInliers() {
        val src = FunshotTestFixtures.gridPoints(4, 3, 30.0, 30.0)
        val outlierIndex = 7
        val dst = src.mapIndexed { index, p ->
            if (index == outlierIndex) {
                FunPointF(p.x + 500.0, p.y - 400.0)
            } else {
                FunPointF(p.x + 7.0, p.y + 9.0)
            }
        }
        val result = estimate(src, dst, minInliers = 10, minInlierRatio = 0.5)
        assertTrue(result.message, result.success)
        assertEquals(src.size - 1, result.inlierCount)
        assertFalse(result.inliers.contains(outlierIndex))
    }

    @Test
    fun inlierMaskOnlyMarksReportedInliers() {
        val src = FunshotTestFixtures.gridPoints(3, 3, 25.0, 25.0)
        val dst = FunshotTestFixtures.affine(src, 1.0, 3.0, 6.0)
        val result = estimate(src, dst, minInliers = 4, minInlierRatio = 0.5)
        assertTrue(result.success)
        val mask = result.inlierMask(src.size)
        assertEquals(src.size, mask.count { it })
    }

    @Test
    fun homographyHelperTransformsAndInverts() {
        val identity = FunMatrix3.identity()
        val point = FunPointF(5.0, 7.0)
        val same = identity.transform(point)
        assertNotNull(same)
        assertEquals(5.0, same!!.x, 1e-12)
        assertEquals(7.0, same.y, 1e-12)
        val inverse = identity.inverse()
        assertNotNull(inverse)
        assertEquals(1.0, inverse!!.det(), 1e-12)
    }
}
