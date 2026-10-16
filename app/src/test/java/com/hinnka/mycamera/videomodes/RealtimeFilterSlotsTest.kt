package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §9 实时视频滤镜：档位/强度 clamp 与「与 Log 预览并存但不依赖 logvideo 符号」。 */
class RealtimeFilterSlotsTest {

    @Test
    fun intensityIsClampedToOpenRange() {
        val clamped = RealtimeFilterConfig(enabled = true, lutId = "lut_a", intensity = 5f).clamp()
        assertEquals(VideoModesRanges.FILTER_INTENSITY_MAX, clamped.intensity, 0.0001f)
        val low = RealtimeFilterConfig(enabled = true, lutId = "lut_a", intensity = -2f).clamp()
        assertEquals(VideoModesRanges.FILTER_INTENSITY_MIN, low.intensity, 0.0001f)
    }

    @Test
    fun intensityStepsAlongLadder() {
        assertEquals(1.25f, RealtimeFilterSlots.stepIntensity(1f, forward = true), 0.0001f)
        assertEquals(0.75f, RealtimeFilterSlots.stepIntensity(1f, forward = false), 0.0001f)
        assertEquals(VideoModesRanges.FILTER_INTENSITY_MAX, RealtimeFilterSlots.stepIntensity(2f, forward = true), 0.0001f)
        assertEquals(0.25f, RealtimeFilterSlots.stepIntensity(0.1f, forward = true), 0.0001f)
    }

    @Test
    fun disabledFilterProducesNoSlots() {
        val resolved = RealtimeFilterSlots.resolve(
            RealtimeFilterConfig(enabled = false, lutId = "lut_a"),
            logProfileEnabled = false,
        )
        assertTrue(resolved.slots.isEmpty())
        assertNull(resolved.activeFilter)
        assertFalse(resolved.logPreviewActive)
        assertFalse(resolved.appliesOnTopOfLog)
    }

    @Test
    fun enabledFilterIsActive() {
        val resolved = RealtimeFilterSlots.resolve(
            RealtimeFilterConfig(enabled = true, lutId = "lut_a", intensity = 1.5f),
            logProfileEnabled = false,
        )
        assertEquals(1, resolved.slots.size)
        assertEquals(VideoFilterSlotKind.LUT, resolved.slots.first().kind)
        assertEquals(1.5f, resolved.activeFilter?.intensity ?: 0f, 0.0001f)
    }

    @Test
    fun logPreviewCoexistsWithFilter() {
        val resolved = RealtimeFilterSlots.resolve(
            RealtimeFilterConfig(enabled = true, lutId = "lut_a", logPreviewCoexist = true),
            logProfileEnabled = true,
        )
        assertEquals(2, resolved.slots.size)
        assertTrue(resolved.logPreviewActive)
        assertTrue(resolved.appliesOnTopOfLog)
        assertEquals("lut_a", resolved.activeFilter?.id)
    }

    @Test
    fun exclusiveLogPreviewYieldsFilterSlot() {
        val resolved = RealtimeFilterSlots.resolve(
            RealtimeFilterConfig(enabled = true, lutId = "lut_a", logPreviewCoexist = false),
            logProfileEnabled = true,
        )
        // 插槽仍保留（配置不丢），但当前不叠加应用。
        assertEquals(2, resolved.slots.size)
        assertNull(resolved.activeFilter)
        assertFalse(resolved.appliesOnTopOfLog)
    }

    @Test
    fun blankLutIdIsIgnored() {
        val resolved = RealtimeFilterSlots.resolve(
            RealtimeFilterConfig(enabled = true, lutId = "  "),
            logProfileEnabled = false,
        )
        assertTrue(resolved.slots.isEmpty())
    }
}
