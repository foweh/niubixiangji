package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §16 画中画双摄并发：能力不足自动隐藏（绝不强行）、镜头对解析与回退。 */
class DualCameraPipGateTest {

    private val concurrentCapability = ConcurrentCameraCapability(
        concurrentCameraIdSets = listOf(setOf("0", "1", "2")),
        maxConcurrentStreamsPerCamera = 1,
    )

    @Test
    fun singleFpsIsClamped() {
        val low = DualCameraPipConfig(secondaryFps = 1).clamp()
        val high = DualCameraPipConfig(secondaryFps = 9999).clamp()
        assertEquals(VideoModesRanges.FPS_MIN, low.secondaryFps)
        assertEquals(VideoModesRanges.FPS_MAX, high.secondaryFps)
    }

    @Test
    fun noConcurrentSupportHidesFeature() {
        val decision = DualCameraPipGate.resolve(
            config = DualCameraPipConfig(enabled = true),
            capability = ConcurrentCameraCapability(),
            availableLensIds = listOf("0", "1"),
        )
        assertFalse(decision.available)
        assertFalse(decision.enabled)
        assertTrue(decision.reason.contains("自动隐藏"))
        assertTrue(decision.reason.contains("不会强行开启"))
    }

    @Test
    fun noUsablePairIsReported() {
        val decision = DualCameraPipGate.resolve(
            config = DualCameraPipConfig(),
            capability = ConcurrentCameraCapability(concurrentCameraIdSets = listOf(setOf("7", "8"))),
            availableLensIds = listOf("0", "1"),
        )
        assertFalse(decision.available)
        assertTrue(decision.reason.contains("没有本工程可用的镜头对"))
    }

    @Test
    fun requestedPairIsUsedWhenSupported() {
        val decision = DualCameraPipGate.resolve(
            config = DualCameraPipConfig(enabled = true, mainLensId = "0", secondaryLensId = "2"),
            capability = concurrentCapability,
            availableLensIds = listOf("0", "1", "2"),
        )
        assertTrue(decision.available)
        assertTrue(decision.enabled)
        assertEquals("0", decision.mainLensId)
        assertEquals("2", decision.secondaryLensId)
    }

    @Test
    fun unsupportedPairFallsBackToAvailableCombination() {
        val decision = DualCameraPipGate.resolve(
            config = DualCameraPipConfig(mainLensId = "0", secondaryLensId = "9"),
            capability = concurrentCapability,
            availableLensIds = listOf("0", "1"),
        )
        assertTrue(decision.available)
        assertFalse(decision.enabled)
        assertEquals("0", decision.mainLensId)
        assertEquals("1", decision.secondaryLensId)
        assertTrue(decision.reason.contains("已改用可用组合"))
    }

    @Test
    fun pairsAreEnumeratedInBothDirections() {
        val pairs = DualCameraPipGate.pairsFor(
            capability = ConcurrentCameraCapability(concurrentCameraIdSets = listOf(setOf("0", "1", "2"))),
            availableLensIds = listOf("0", "1", "2"),
        )
        assertEquals(6, pairs.size)
        assertTrue(pairs.contains("0" to "1"))
        assertTrue(pairs.contains("1" to "0"))
    }

    @Test
    fun concurrentSupportIsDetectedFromSets() {
        assertTrue(concurrentCapability.hasConcurrentSupport)
        assertFalse(ConcurrentCameraCapability(concurrentCameraIdSets = listOf(setOf("0"))).hasConcurrentSupport)
    }
}
