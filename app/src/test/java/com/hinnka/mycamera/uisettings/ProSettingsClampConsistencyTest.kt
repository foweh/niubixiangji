package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.aiplatform.AiCapability
import com.hinnka.mycamera.aiplatform.AiEndpointConfig
import com.hinnka.mycamera.aiwb.AiWhiteBalanceConfig
import com.hinnka.mycamera.aiwb.AiWhiteBalanceSettings
import com.hinnka.mycamera.appgallery.AppGalleryConfig
import com.hinnka.mycamera.astronav.AstroNavConfig
import com.hinnka.mycamera.funshot.FunshotRanges
import com.hinnka.mycamera.longexposure.DarkFrameConfig
import com.hinnka.mycamera.longexposure.FireworksConfig
import com.hinnka.mycamera.longexposure.LightPaintingConfig
import com.hinnka.mycamera.longexposure.LongExposureConfigCodec
import com.hinnka.mycamera.longexposure.LongExposureLimits
import com.hinnka.mycamera.longexposure.LocalToneMapConfig
import com.hinnka.mycamera.longexposure.SuperMoonConfig
import com.hinnka.mycamera.nativecompute.NativeComputeLimits
import com.hinnka.mycamera.scenerecog.SceneRecognitionConfig
import com.hinnka.mycamera.scenerecog.SceneRecognitionSettings
import com.hinnka.mycamera.viewfinder.ViewfinderParamRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * clamp 显示一致性：把「Pro 设置」里每条数值项的**范围 / 默认值 / 键**与所属模块 Config 的公开常量逐条对账。
 *
 * 为什么这条测试最值钱：设置页的取值边界一旦与模块不一致，用户就会调到「模块会再改一次」的值 ——
 * 表现是「滑到中间却不生效」。本测试就是拦这种漂移。
 */
class ProSettingsClampConsistencyTest {

    private fun specOf(id: String): ProSettingSpec {
        val spec = ProSettingsRegistry.settingById[id]
        assertNotNull("设置项不存在：$id", spec)
        return spec!!
    }

    private fun continuous(id: String): ProRange.Continuous {
        val range = specOf(id).range
        assertTrue("$id 期望连续范围，实际 $range", range is ProRange.Continuous)
        return range as ProRange.Continuous
    }

    private fun stepped(id: String): ProRange.Stepped {
        val range = specOf(id).range
        assertTrue("$id 期望离散范围，实际 $range", range is ProRange.Stepped)
        return range as ProRange.Stepped
    }

    // ───────────────────────────── 1. 全局不变量

    @Test
    fun `所有数值项默认值都落在范围内`() {
        ProSettingsRegistry.settings
            .filter { it.control == ProSettingControl.SLIDER || it.control == ProSettingControl.NUMBER }
            .forEach { spec ->
                val range = spec.range
                assertTrue("${spec.id} 缺范围", range !is ProRange.Unbounded)
                val default = spec.defaultValue.toDoubleOrNull()
                assertNotNull("${spec.id} 默认值不是数值：${spec.defaultValue}", default)
                assertTrue("${spec.id} 默认值 $default 超范围 ${spec.rangeText()}", range.contains(default!!))
            }
    }

    @Test
    fun `所有数值项把越界值收敛回范围内且幂等`() {
        ProSettingsRegistry.settings
            .filter { it.control == ProSettingControl.SLIDER || it.control == ProSettingControl.NUMBER }
            .forEach { spec ->
                listOf("999999999", "-999999999", "0", "NaN", "abc").forEach { raw ->
                    val once = spec.normalize(raw).value
                    val twice = spec.normalize(once).value
                    assertEquals("${spec.id} 收敛不幂等（$raw → $once → $twice）", once, twice)
                    val value = once.toDoubleOrNull()
                    assertNotNull("${spec.id} 收敛结果不是数值：$once", value)
                    assertTrue("${spec.id} 收敛后仍越界：$once（范围 ${spec.rangeText()}）", spec.range.contains(value!!))
                }
            }
    }

    @Test
    fun `显示精度与 decimals 一致`() {
        val pattern0 = Regex("^-?\\d+$")
        val patternN = Regex("^-?\\d+\\.\\d+$")
        ProSettingsRegistry.settings
            .filter { it.control == ProSettingControl.SLIDER || it.control == ProSettingControl.NUMBER }
            .forEach { spec ->
                val (minText, maxText) = spec.rangeText().let { text ->
                    val head = text.substringBefore("（").trim()
                    val parts = head.split("~").map { it.trim() }
                    val min = parts[0]
                    val max = parts[1].substringBefore(" ").trim()
                    min to max
                }
                listOf(minText, maxText).forEach { text ->
                    val ok = if (spec.decimals <= 0) {
                        pattern0.matches(text)
                    } else {
                        patternN.matches(text) && text.substringAfter('.').length == spec.decimals
                    }
                    assertTrue("${spec.id} 显示 $text 与 decimals=${spec.decimals} 不一致", ok)
                }
            }
    }

    @Test
    fun `下拉项默认值都在白名单内且选项非空`() {
        ProSettingsRegistry.settings
            .filter { it.control == ProSettingControl.DROPDOWN }
            .forEach { spec ->
                assertTrue("${spec.id} 无选项", spec.options.isNotEmpty())
                assertTrue(
                    "${spec.id} 默认值不在白名单：${spec.defaultValue}",
                    spec.options.any { it.equals(spec.defaultValue, ignoreCase = true) },
                )
            }
    }

    // ───────────────────────────── 2. 取景与监看：ViewfinderParamRanges

    @Test
    fun `斑马纹与直方图范围取自 ViewfinderParamRanges`() {
        val zebra = continuous("viewfinder.zebra.thresholdPercent")
        assertEquals(ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MIN.toDouble(), zebra.min, 1e-6)
        assertEquals(ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_MAX.toDouble(), zebra.max, 1e-6)
        assertEquals(
            ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_DEFAULT.toDouble(),
            specOf("viewfinder.zebra.thresholdPercent").defaultValue.toDouble(),
            1e-6,
        )

        val stripe = stepped("viewfinder.zebra.stripeWidthPx")
        assertEquals(ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MIN.toInt().toLong(), stripe.min)
        assertEquals(ViewfinderParamRanges.ZEBRA_STRIPE_WIDTH_MAX.toInt().toLong(), stripe.max)

        val interval = stepped("viewfinder.histogram.sampleIntervalMs")
        assertEquals(ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MIN_MS, interval.min)
        assertEquals(ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_MAX_MS, interval.max)
        assertEquals(
            ViewfinderParamRanges.HISTOGRAM_SAMPLE_INTERVAL_DEFAULT_MS.toLong(),
            specOf("viewfinder.histogram.sampleIntervalMs").defaultValue.toLong(),
        )
    }

    @Test
    fun `峰值对焦与贴纸范围取自 ViewfinderParamRanges`() {
        val sensitivity = continuous("viewfinder.peaking.sensitivity")
        assertEquals(ViewfinderParamRanges.PEAKING_SENSITIVITY_MIN.toDouble(), sensitivity.min, 1e-6)
        assertEquals(ViewfinderParamRanges.PEAKING_SENSITIVITY_MAX.toDouble(), sensitivity.max, 1e-6)
        assertEquals(ViewfinderParamRanges.PEAKING_SENSITIVITY_DEFAULT.toDouble(), sensitivity.min, 1e-6)

        val sticker = stepped("viewfinder.sticker.maxFaces")
        assertEquals(ViewfinderParamRanges.STICKER_MAX_FACES_MIN.toLong(), sticker.min)
        assertEquals(ViewfinderParamRanges.STICKER_MAX_FACES_MAX.toLong(), sticker.max)

        val detect = stepped("viewfinder.sticker.detectIntervalMs")
        assertEquals(ViewfinderParamRanges.STICKER_INTERVAL_MIN_MS, detect.min)
        assertEquals(ViewfinderParamRanges.STICKER_INTERVAL_MAX_MS, detect.max)
    }

    // ───────────────────────────── 3. 长曝光：LongExposureLimits 与各子配置

    @Test
    fun `长曝光帧数范围取自 LongExposureLimits`() {
        stepped("longexposure.lightPainting.frameCount").let { range ->
            assertEquals(LongExposureLimits.FRAME_MIN.toLong(), range.min)
            assertEquals(LongExposureLimits.ABSOLUTE_FRAME_MAX.toLong(), range.max)
        }
        stepped("longexposure.frameCountLimit").let { range ->
            assertEquals(LongExposureLimits.FRAME_MIN.toLong(), range.min)
            assertEquals(LongExposureLimits.ABSOLUTE_FRAME_MAX.toLong(), range.max)
        }
    }

    @Test
    fun `暗帧与光绘范围取自各自子配置`() {
        stepped("longexposure.darkFrame.frameCount").let { range ->
            assertEquals(DarkFrameConfig.FRAME_MIN.toLong(), range.min)
            assertEquals(DarkFrameConfig.FRAME_MAX.toLong(), range.max)
        }
        val gain = continuous("longexposure.darkFrame.subtractGain")
        assertEquals(DarkFrameConfig.GAIN_MIN.toDouble(), gain.min, 1e-6)
        assertEquals(DarkFrameConfig.GAIN_MAX.toDouble(), gain.max, 1e-6)

        val temperature = stepped("longexposure.lightPainting.brushTemperatureK")
        assertEquals(LightPaintingConfig.COLOR_TEMP_MIN.toLong(), temperature.min)
        assertEquals(LightPaintingConfig.COLOR_TEMP_MAX.toLong(), temperature.max)
        assertEquals(100L, temperature.step)

        val tint = continuous("longexposure.lightPainting.brushTint")
        assertEquals(LightPaintingConfig.TINT_MIN.toDouble(), tint.min, 1e-6)
        assertEquals(LightPaintingConfig.TINT_MAX.toDouble(), tint.max, 1e-6)
    }

    @Test
    fun `烟火与超级月亮范围取自各自子配置`() {
        val shutter = stepped("longexposure.fireworks.burstShutterSpeedNs")
        assertEquals(FireworksConfig.SHUTTER_MIN_NS, shutter.min)
        assertEquals(FireworksConfig.SHUTTER_MAX_NS, shutter.max)

        val iso = stepped("longexposure.fireworks.iso")
        assertEquals(FireworksConfig.ISO_MIN.toLong(), iso.min)
        assertEquals(FireworksConfig.ISO_MAX.toLong(), iso.max)

        val sigma = continuous("longexposure.superMoon.sigmaClipSigma")
        assertEquals(SuperMoonConfig.SIGMA_MIN.toDouble(), sigma.min, 1e-6)
        assertEquals(SuperMoonConfig.SIGMA_MAX.toDouble(), sigma.max, 1e-6)

        val threshold = continuous("longexposure.superMoon.highlightThreshold")
        assertEquals(LocalToneMapConfig.THRESHOLD_MIN.toDouble(), threshold.min, 1e-6)
        assertEquals(LocalToneMapConfig.THRESHOLD_MAX.toDouble(), threshold.max, 1e-6)
    }

    // ───────────────────────────── 4. 趣味拍摄：FunshotRanges

    @Test
    fun `funshot 范围取自 FunshotRanges`() {
        stepped("funshot.interval.intervalMs").let { range ->
            assertEquals(FunshotRanges.INTERVAL_MIN_MS, range.min)
            assertEquals(FunshotRanges.INTERVAL_MAX_MS, range.max)
        }
        stepped("funshot.interval.intervalJitterMs").let { range ->
            assertEquals(0L, range.min)
            assertEquals(FunshotRanges.INTERVAL_JITTER_MAX_MS, range.max)
        }
        stepped("funshot.sound.sampleRate").let { range ->
            assertEquals(FunshotRanges.SOUND_SAMPLE_RATE_MIN.toLong(), range.min)
            assertEquals(FunshotRanges.SOUND_SAMPLE_RATE_MAX.toLong(), range.max)
            assertEquals(1_000L, range.step)
        }
        stepped("funshot.pano.maxCorners").let { range ->
            assertEquals(100L, range.min)
            assertEquals(4_000L, range.max)
        }
    }

    // ───────────────────────────── 5. 天文导航：AstroNavConfig

    @Test
    fun `astronav 范围取自 AstroNavConfig`() {
        val offset = continuous("astronav.manualOffsetDegrees")
        assertEquals(AstroNavConfig.MANUAL_OFFSET_MIN.toDouble(), offset.min, 1e-6)
        assertEquals(AstroNavConfig.MANUAL_OFFSET_MAX.toDouble(), offset.max, 1e-6)

        val altitude = continuous("astronav.altitudeMeters")
        assertEquals(AstroNavConfig.ALTITUDE_MIN, altitude.min, 1e-6)
        assertEquals(AstroNavConfig.ALTITUDE_MAX, altitude.max, 1e-6)

        stepped("astronav.recordMaxSamples").let { range ->
            assertEquals(AstroNavConfig.RECORD_SAMPLES_MIN.toLong(), range.min)
            assertEquals(AstroNavConfig.RECORD_SAMPLES_MAX.toLong(), range.max)
            assertEquals(10L, range.step)
        }

        stepped("astronav.magCalibrationQualityMin").let { range ->
            assertEquals(AstroNavConfig.MAG_QUALITY_MIN.toLong(), range.min)
            assertEquals(AstroNavConfig.MAG_QUALITY_MAX.toLong(), range.max)
        }

        stepped("astronav.declinationCacheTtlMs").let { range ->
            assertEquals(AstroNavConfig.DECLINATION_CACHE_TTL_MIN, range.min)
            assertEquals(AstroNavConfig.DECLINATION_CACHE_TTL_MAX, range.max)
        }

        // 约定键：模块没有声明持久化常量，页面按 astronav.<字段> 命名（见 changelog 说明）。
        assertEquals("astronav.manualOffsetDegrees", specOf("astronav.manualOffsetDegrees").storageKey)
    }

    // ───────────────────────────── 6. 相册与预览

    @Test
    fun `相册范围取自 AppGalleryConfig 且键落在打包容器`() {
        stepped("appgallery.gridColumnCount").let { range ->
            assertEquals(AppGalleryConfig.GRID_COLUMN_MIN.toLong(), range.min)
            assertEquals(AppGalleryConfig.GRID_COLUMN_MAX.toLong(), range.max)
        }
        val radius = continuous("appgallery.placeClusterRadiusKm")
        assertEquals(AppGalleryConfig.PLACE_RADIUS_MIN_KM.toDouble(), radius.min, 1e-6)
        assertEquals(AppGalleryConfig.PLACE_RADIUS_MAX_KM.toDouble(), radius.max, 1e-6)

        assertEquals("config_payload :: gridColumnCount", specOf("appgallery.gridColumnCount").storageKey)
    }

    @Test
    fun `相册预览范围与键前缀取自 agp 常量与模块注释`() {
        val minZoom = continuous("appgallerypreview.minZoomFactor")
        assertEquals(0.25, minZoom.min, 1e-6)
        assertEquals(4.0, minZoom.max, 1e-6)

        val maxZoom = continuous("appgallerypreview.maxZoomFactor")
        assertEquals(2.0, maxZoom.min, 1e-6)
        assertEquals(32.0, maxZoom.max, 1e-6)

        assertEquals(
            com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfigCodec.KEY_PREFIX + "min_zoom",
            specOf("appgallerypreview.minZoomFactor").storageKey,
        )
    }

    // ───────────────────────────── 7. AI 与计算

    @Test
    fun `AI 端点范围取自 AiEndpointConfig 键取自 AiCapability 前缀`() {
        stepped("aiplatform.connectTimeoutMs").let { range ->
            assertEquals(AiEndpointConfig.TIMEOUT_MIN_MS.toLong(), range.min)
            assertEquals(AiEndpointConfig.TIMEOUT_MAX_MS.toLong(), range.max)
        }
        stepped("aiplatform.maxRetries").let { range ->
            assertEquals(AiEndpointConfig.RETRY_MIN.toLong(), range.min)
            assertEquals(AiEndpointConfig.RETRY_MAX.toLong(), range.max)
        }
        stepped("aiplatform.retryBaseDelayMs").let { range ->
            assertEquals(AiEndpointConfig.RETRY_BASE_MIN_MS, range.min)
            assertEquals(AiEndpointConfig.RETRY_BASE_MAX_MS, range.max)
        }
        assertEquals(
            AiCapability.WHITE_BALANCE.storagePrefix + ".connectTimeoutMs",
            specOf("aiplatform.connectTimeoutMs").storageKey,
        )
    }

    @Test
    fun `AI 白平衡范围与键取自 aiwb 常量`() {
        val strength = continuous("aiwb.strength")
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MIN.toDouble(), strength.min, 1e-6)
        assertEquals(AiWhiteBalanceConfig.STRENGTH_MAX.toDouble(), strength.max, 1e-6)
        assertEquals(AiWhiteBalanceSettings.KEY_STRENGTH, specOf("aiwb.strength").storageKey)

        val kelvin = stepped("aiwb.minKelvin")
        assertEquals(AiWhiteBalanceConfig.KELVIN_MIN.toLong(), kelvin.min)
        assertEquals(AiWhiteBalanceConfig.KELVIN_MAX.toLong(), kelvin.max)
        assertEquals(AiWhiteBalanceSettings.KEY_MIN_KELVIN, specOf("aiwb.minKelvin").storageKey)
    }

    @Test
    fun `场景识别范围与键取自 scenerecog 常量`() {
        val skin = continuous("scenerecog.portraitSkinFraction")
        assertEquals(SceneRecognitionConfig.SKIN_MIN.toDouble(), skin.min, 1e-6)
        assertEquals(SceneRecognitionConfig.SKIN_MAX.toDouble(), skin.max, 1e-6)
        assertEquals(SceneRecognitionSettings.KEY_PORTRAIT_SKIN, specOf("scenerecog.portraitSkinFraction").storageKey)

        stepped("scenerecog.sampleStride").let { range ->
            assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MIN.toLong(), range.min)
            assertEquals(SceneRecognitionConfig.SAMPLE_STRIDE_MAX.toLong(), range.max)
        }
    }

    @Test
    fun `原生计算层范围取自 NativeComputeLimits 且不落盘`() {
        val gain = continuous("nativecompute.ibpGain")
        assertEquals(NativeComputeLimits.IBP_GAIN_MIN.toDouble(), gain.min, 1e-6)
        assertEquals(NativeComputeLimits.IBP_GAIN_MAX.toDouble(), gain.max, 1e-6)

        stepped("nativecompute.alignMaxSide").let { range ->
            assertEquals(NativeComputeLimits.ALIGN_MAX_SIDE_MIN.toLong(), range.min)
            assertEquals(NativeComputeLimits.ALIGN_MAX_SIDE_MAX.toLong(), range.max)
        }

        // 无持久化键：避免把「跨 JNI 校验上界」误当成用户偏好写盘。
        val spec = specOf("nativecompute.ibpGain")
        assertEquals(ProStorageKind.NONE, spec.storage.kind)
        assertTrue(spec.storageKey.isBlank())
    }

    // ───────────────────────────── 8. 键名与模块常量对账

    @Test
    fun `长曝光的键直接取自 LongExposureConfigCodec`() {
        assertEquals(LongExposureConfigCodec.KEY_LP_FRAMES, specOf("longexposure.lightPainting.frameCount").storageKey)
        assertEquals("long_exposure_light_painting_work_scale", specOf("longexposure.lightPainting.workScale").storageKey)
        assertEquals(LongExposureConfigCodec.KEY_FRAME_COUNT_LIMIT, specOf("longexposure.frameCountLimit").storageKey)
    }

    @Test
    fun `模块清单里的每个模块都在 UI 侧有归属分栏`() {
        ProSettingsTaxonomy.modules.forEach { module ->
            val section = ProSettingsTaxonomy.sectionOfModule[module.id]
            assertNotNull("模块 ${module.id} 没有分栏", section)
            assertTrue("模块 ${module.id} 的分栏不存在", ProSettingsTaxonomy.sectionById.containsKey(section!!))
        }
    }

    @Test
    fun `对标来源逐项标注`() {
        // 至少 90% 的设置项带对标来源（自研的 nativecompute 与 UI 选择器除外）。
        val total = ProSettingsRegistry.editableSettings.size
        val tagged = ProSettingsRegistry.editableSettings.count { it.benchmarkRefs.isNotEmpty() }
        assertTrue("对标标注覆盖率过低：$tagged/$total", tagged * 10 >= total * 9)
        // BMD = Blackmagic Camera 的简写；本工程只做功能对标、不采代码。
        val allowed = setOf("GCam", "ProCam", "Blackmagic", "BMD", "MotionCam", "OpenCam", "自研")
        ProSettingsRegistry.editableSettings.forEach { spec ->
            spec.benchmarkRefs.forEach { ref ->
                assertTrue("${spec.id} 出现未知对标来源：$ref", allowed.contains(ref))
            }
        }
    }
}
