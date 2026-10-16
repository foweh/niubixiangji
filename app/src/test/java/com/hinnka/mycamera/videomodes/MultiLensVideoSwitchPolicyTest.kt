package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6 多镜头切换强化：录制中切换开放、白名单、无缝切换与稳定期。 */
class MultiLensVideoSwitchPolicyTest {

    private val lensIds = listOf("0", "1", "2")

    @Test
    fun switchSettleMsIsClampedToOpenRange() {
        val low = MultiLensSwitchConfig(switchSettleMs = -100L).clamp()
        val high = MultiLensSwitchConfig(switchSettleMs = 99_999L).clamp()
        assertEquals(VideoModesRanges.LENS_SWITCH_SETTLE_MS_MIN, low.switchSettleMs)
        assertEquals(VideoModesRanges.LENS_SWITCH_SETTLE_MS_MAX, high.switchSettleMs)
    }

    @Test
    fun recordingSwitchIsAllowedByDefault() {
        val decision = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(),
            targetLensId = "1",
            isRecording = true,
            availableLensIds = lensIds,
        )
        assertTrue(decision.allowed)
        assertFalse(decision.seamless)
        assertEquals(VideoModesRanges.DEFAULT_LENS_SWITCH_SETTLE_MS, decision.settleMs)
    }

    @Test
    fun recordingSwitchCanBeLockedDown() {
        val decision = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(switchDuringRecording = false),
            targetLensId = "1",
            isRecording = true,
            availableLensIds = lensIds,
        )
        assertFalse(decision.allowed)
        assertNull(decision.targetLensId)
        assertTrue(decision.reason.contains("录制中禁止切换"))
    }

    @Test
    fun unavailableOrWhitelistRejectedLensIsBlocked() {
        val unavailable = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(),
            targetLensId = "9",
            isRecording = false,
            availableLensIds = lensIds,
        )
        assertFalse(unavailable.allowed)
        assertTrue(unavailable.reason.contains("不可用"))

        val outsideWhitelist = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(allowedLensIds = listOf("0")),
            targetLensId = "1",
            isRecording = false,
            availableLensIds = lensIds,
        )
        assertFalse(outsideWhitelist.allowed)
        assertTrue(outsideWhitelist.reason.contains("白名单"))
    }

    @Test
    fun seamlessSwitchRemovesSettleWindowOnlyWhenHalSupportsIt() {
        val seamless = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(seamlessSwitch = true),
            targetLensId = "1",
            isRecording = true,
            availableLensIds = lensIds,
            seamlessSupported = true,
        )
        assertTrue(seamless.allowed)
        assertTrue(seamless.seamless)
        assertEquals(0L, seamless.settleMs)

        val notSeamless = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(seamlessSwitch = true),
            targetLensId = "1",
            isRecording = true,
            availableLensIds = lensIds,
            seamlessSupported = false,
        )
        assertTrue(notSeamless.allowed)
        assertFalse(notSeamless.seamless)
        assertTrue(notSeamless.settleMs > 0L)
    }

    @Test
    fun disabledConfigBlocksEverySwitch() {
        val decision = MultiLensVideoSwitchPolicy.resolve(
            config = MultiLensSwitchConfig(enabled = false),
            targetLensId = "1",
            isRecording = false,
            availableLensIds = lensIds,
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("关闭"))
    }

    @Test
    fun nextLensCyclesThroughCandidates() {
        assertEquals("1", MultiLensVideoSwitchPolicy.nextLens("0", lensIds))
        assertEquals("0", MultiLensVideoSwitchPolicy.nextLens("2", lensIds))
        assertEquals("0", MultiLensVideoSwitchPolicy.nextLens(null, lensIds))
        assertNull(MultiLensVideoSwitchPolicy.nextLens("0", emptyList()))
        assertEquals("2", MultiLensVideoSwitchPolicy.nextLens("0", lensIds, allowedLensIds = listOf("2")))
    }
}
