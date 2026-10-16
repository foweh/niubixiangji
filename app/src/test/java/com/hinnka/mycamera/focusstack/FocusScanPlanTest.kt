package com.hinnka.mycamera.focusstack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对焦扫描规划单测：步进区间 / 自动近到远 / 帧数 2 与 200 边界 / 定焦镜头与非法输入。
 */
class FocusScanPlanTest {

    @Test
    fun plan_autoNearToFar_coversFullRange() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 6, autoNearToFar = true),
            minimumFocusDistanceDiopters = 10f,
        )
        assertFalse(plan.isEmpty)
        assertEquals(6, plan.frameCount)
        // 近端在前（屈光度大），远端在后（0 = 无穷远）。
        assertEquals(10f, plan.steps.first().diopters, 1e-4f)
        assertEquals(0f, plan.steps.last().diopters, 1e-4f)
        assertEquals(10f, plan.nearDiopters, 1e-4f)
        assertEquals(0f, plan.farDiopters, 1e-4f)
        // 单调递减（近 → 远）。
        for (i in 1 until plan.frameCount) {
            assertTrue(
                "step $i 应比上一步更远",
                plan.steps[i].diopters <= plan.steps[i - 1].diopters + 1e-5f,
            )
        }
        // 均匀铺满：中间步进值 = 端点线性插值。
        assertEquals(6f, plan.steps[2].diopters, 1e-3f)
    }

    @Test
    fun plan_frameCountLowerBoundary_twoSteps() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 2, autoNearToFar = true),
            minimumFocusDistanceDiopters = 8f,
        )
        assertEquals(2, plan.frameCount)
        // 只有两端点：近端在前，远端在后。
        assertEquals(8f, plan.steps[0].diopters, 1e-4f)
        assertEquals(0f, plan.steps[1].diopters, 1e-4f)
        assertEquals(0f, plan.steps[0].fraction, 1e-6f)
        assertEquals(1f, plan.steps[1].fraction, 1e-6f)
    }

    @Test
    fun plan_frameCountUpperBoundary_twoHundredSteps() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 200, autoNearToFar = true),
            minimumFocusDistanceDiopters = 12f,
        )
        assertEquals(200, plan.frameCount)
        assertEquals(12f, plan.steps.first().diopters, 1e-4f)
        assertEquals(0f, plan.steps.last().diopters, 1e-4f)
        assertEquals(0f, plan.steps.first().fraction, 1e-6f)
        assertEquals(1f, plan.steps.last().fraction, 1e-6f)
        // 步进间隔应均匀（首段与末段一致）。
        val firstGap = plan.steps[0].diopters - plan.steps[1].diopters
        val lastGap = plan.steps[198].diopters - plan.steps[199].diopters
        assertEquals(firstGap, lastGap, 1e-3f)
    }

    @Test
    fun plan_explicitRange_isRespected() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(
                frameCount = 5,
                autoNearToFar = false,
                focusStartDiopters = 2f,
                focusEndDiopters = 10f,
            ),
            minimumFocusDistanceDiopters = 20f,
        )
        assertEquals(5, plan.frameCount)
        assertEquals(10f, plan.steps.first().diopters, 1e-4f)
        assertEquals(2f, plan.steps.last().diopters, 1e-4f)
    }

    @Test
    fun plan_endBeyondLensCapability_isClampedToLens() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(
                frameCount = 4,
                autoNearToFar = false,
                focusStartDiopters = 3f,
                focusEndDiopters = 40f,
            ),
            minimumFocusDistanceDiopters = 9f,
        )
        assertEquals(9f, plan.nearDiopters, 1e-4f)
        assertEquals(9f, plan.steps.first().diopters, 1e-4f)
    }

    @Test
    fun plan_fixedFocusLens_returnsEmptyPlan() {
        // 定焦微距镜头（无 LENS_FOCUS_DISTANCE 可写）：给出空计划，交由手动序列模式。
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 6),
            minimumFocusDistanceDiopters = 0f,
        )
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.frameCount)
        assertTrue(plan.clampedFrom.any { it.contains("定焦") })
    }

    @Test
    fun plan_nonFiniteLensCapability_returnsEmptyPlan() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 6),
            minimumFocusDistanceDiopters = Float.NaN,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun plan_degenerateRange_keepsFrameCountAndReports() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(
                frameCount = 7,
                autoNearToFar = false,
                focusStartDiopters = 10f,
                focusEndDiopters = 10f,
            ),
            minimumFocusDistanceDiopters = 10f,
        )
        // 帧数不被静默改写。
        assertEquals(7, plan.frameCount)
        assertTrue(plan.clampedFrom.any { it.contains("退化") })
    }

    @Test
    fun plan_allStepsAreFiniteAndWithinRange() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 200, autoNearToFar = true),
            minimumFocusDistanceDiopters = 15f,
        )
        for (step in plan.steps) {
            assertTrue(step.diopters.isFinite())
            assertTrue(step.diopters >= -1e-4f)
            assertTrue(step.diopters <= 15f + 1e-3f)
            assertTrue(step.fraction in 0f..1f)
        }
    }

    @Test
    fun autoNearToFar_worksWithoutLensCapability() {
        val plan = FocusScanPlanner.autoNearToFar(FocusStackConfig(frameCount = 4))
        assertEquals(4, plan.frameCount)
        assertEquals(FocusScanPlanner.FALLBACK_MIN_FOCUS_DIOPTERS, plan.nearDiopters, 1e-4f)
        assertEquals(0f, plan.steps.last().diopters, 1e-4f)
    }

    @Test
    fun dioptersAndMeters_convertRoundTrip() {
        assertEquals(0.1f, FocusScanPlanner.dioptersToMeters(10f), 1e-6f)
        assertEquals(10f, FocusScanPlanner.metersToDiopters(0.1f), 1e-4f)
        assertTrue(FocusScanPlanner.dioptersToMeters(0f).isInfinite())
        assertEquals(0f, FocusScanPlanner.metersToDiopters(0f), 0f)
        assertEquals(0f, FocusScanPlanner.metersToDiopters(Float.NaN), 0f)
    }

    @Test
    fun dioptersAt_outOfRangeReturnsNull() {
        val plan = FocusScanPlanner.plan(
            FocusStackConfig(frameCount = 3),
            minimumFocusDistanceDiopters = 10f,
        )
        assertNotNull(plan.dioptersAt(0))
        assertNotNull(plan.dioptersAt(2))
        assertEquals(null, plan.dioptersAt(3))
        assertEquals(null, plan.dioptersAt(-1))
    }
}
