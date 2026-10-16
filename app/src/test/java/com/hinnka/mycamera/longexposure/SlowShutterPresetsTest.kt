package com.hinnka.mycamera.longexposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢门场景预设单测（纯 JVM）。
 *
 * 覆盖任务要求：三套预设（车流光轨 / 瀑布拉丝 / 雨丝）的**全部字段可改**，
 * 预设本身**可增删**（[SlowShutterPresetRegistry]），并且越界值一律 clamp。
 */
class SlowShutterPresetsTest {

    @Test
    fun builtInPresetsCoverThreeRequestedScenes() {
        val defaults = SlowShutterPresets.defaults()
        assertEquals(3, defaults.size)
        assertEquals(
            listOf(
                SlowShutterPresets.ID_CAR_LIGHT_TRAILS,
                SlowShutterPresets.ID_SILK_WATERFALL,
                SlowShutterPresets.ID_RAIN_STREAKS,
            ),
            defaults.map { it.id },
        )
    }

    @Test
    fun carLightTrailsUsesMaxAccumulationForContinuousTrails() {
        val preset = SlowShutterPresets.CAR_LIGHT_TRAILS
        assertEquals("车流光轨", preset.displayName)
        assertEquals(4_000_000_000L, preset.shutterSpeedNs)
        assertEquals(100, preset.iso)
        assertEquals(20, preset.frameCount)
        assertEquals(LongExposureStackMode.MAX, preset.stackMode)
        assertTrue(preset.isShutterInRange())
    }

    @Test
    fun silkWaterfallUsesMeanAccumulationAndManyFrames() {
        val preset = SlowShutterPresets.SILK_WATERFALL
        assertEquals("瀑布拉丝", preset.displayName)
        assertEquals(500_000_000L, preset.shutterSpeedNs)
        assertEquals(40, preset.frameCount)
        assertEquals(LongExposureStackMode.MEAN, preset.stackMode)
    }

    @Test
    fun rainStreaksUsesShortShutterHighIsoAndMaxAccumulation() {
        val preset = SlowShutterPresets.RAIN_STREAKS
        assertEquals("雨丝", preset.displayName)
        assertEquals(16_666_666L, preset.shutterSpeedNs)
        assertEquals(400, preset.iso)
        assertEquals(24, preset.frameCount)
        assertEquals(LongExposureStackMode.MAX, preset.stackMode)
    }

    @Test
    fun everyPresetFieldIsEditableAndClampedOnWrite() {
        val registry = SlowShutterPresetRegistry()
        val edited = registry.upsert(
            SlowShutterPresets.CAR_LIGHT_TRAILS.copy(
                shutterSpeedNs = 999_999_999_999L,
                iso = 0,
                frameCount = 5_000,
                stackMode = LongExposureStackMode.MEAN,
                darkFrameEnabled = true,
            ),
        )
        assertEquals(SlowShutterPreset.SHUTTER_MAX_NS, edited.shutterSpeedNs)
        assertEquals(SlowShutterPreset.ISO_MIN, edited.iso)
        assertEquals(LongExposureLimits.frameCountLimit(), edited.frameCount)
        assertEquals(LongExposureStackMode.MEAN, edited.stackMode)
        assertTrue(edited.darkFrameEnabled)
        // 覆盖仍按 id 命中同一个预设，数量不变。
        assertEquals(3, registry.size())
    }

    @Test
    fun customPresetCanBeAddedAndRemoved() {
        val registry = SlowShutterPresetRegistry()
        val custom = registry.upsert(
            SlowShutterPreset(
                id = "my_star_trail",
                displayName = "自制星轨",
                shutterSpeedNs = 8_000_000_000L,
                iso = 800,
                frameCount = 120,
                stackMode = LongExposureStackMode.MAX,
                darkFrameEnabled = true,
            ),
        )
        assertEquals(4, registry.size())
        assertEquals(custom, registry.byId("my_star_trail"))
        assertTrue(registry.remove("my_star_trail"))
        assertNull(registry.byId("my_star_trail"))
        assertEquals(3, registry.size())
        assertFalse(registry.remove("my_star_trail"))
    }

    @Test
    fun builtInPresetsCanAlsoBeRemoved() {
        val registry = SlowShutterPresetRegistry()
        assertTrue(registry.remove(SlowShutterPresets.ID_RAIN_STREAKS))
        assertEquals(2, registry.size())
        assertNull(registry.byId(SlowShutterPresets.ID_RAIN_STREAKS))
    }

    @Test
    fun retainOnlyKeepsRequestedSubset() {
        val registry = SlowShutterPresetRegistry()
        val kept = registry.retainOnly(listOf(SlowShutterPresets.ID_SILK_WATERFALL))
        assertEquals(1, kept.size)
        assertEquals(SlowShutterPresets.ID_SILK_WATERFALL, kept.first().id)
    }

    @Test
    fun resetToDefaultsRestoresBuiltInThree() {
        val registry = SlowShutterPresetRegistry()
        registry.upsert(SlowShutterPresets.CAR_LIGHT_TRAILS.copy(frameCount = 999))
        registry.upsert(
            SlowShutterPreset(
                id = "temp",
                displayName = "临时",
                shutterSpeedNs = 1_000_000_000L,
                iso = 100,
                frameCount = 10,
                stackMode = LongExposureStackMode.MAX,
            ),
        )
        val restored = registry.resetToDefaults()
        assertEquals(3, restored.size)
        assertEquals(20, registry.byId(SlowShutterPresets.ID_CAR_LIGHT_TRAILS)?.frameCount)
        assertNull(registry.byId("temp"))
    }

    @Test
    fun oversizedRegistryInitialListIsClampedOnConstruction() {
        val registry = SlowShutterPresetRegistry(
            listOf(
                SlowShutterPresets.CAR_LIGHT_TRAILS.copy(frameCount = 100_000, iso = 999_999),
            ),
        )
        val stored = registry.all().single()
        assertEquals(LongExposureLimits.frameCountLimit(), stored.frameCount)
        assertEquals(SlowShutterPreset.ISO_MAX, stored.iso)
    }

    @Test
    fun presetWithBlankNamesGetsFallbackIdentifiers() {
        val registry = SlowShutterPresetRegistry(emptyList())
        val stored = registry.upsert(
            SlowShutterPreset(
                id = "",
                displayName = "",
                shutterSpeedNs = 2_000_000_000L,
                iso = 200,
                frameCount = 10,
                stackMode = LongExposureStackMode.MAX,
            ),
        )
        assertEquals(SlowShutterPreset.FALLBACK_ID, stored.id)
        assertEquals(SlowShutterPreset.FALLBACK_ID, stored.displayName)
        assertNotNull(registry.byId(SlowShutterPreset.FALLBACK_ID))
    }

    @Test
    fun presetBackedSessionSpecCarriesStackModeAndFrameCount() {
        val spec = LongExposureSessionSpec.fromPreset(SlowShutterPresets.SILK_WATERFALL)
        assertEquals(40, spec.frameCountTarget)
        assertEquals(LongExposureStackMode.MEAN, spec.stackMode)
        assertFalse(spec.darkFrame.enabled) // 该预设默认不拍暗帧
    }
}
