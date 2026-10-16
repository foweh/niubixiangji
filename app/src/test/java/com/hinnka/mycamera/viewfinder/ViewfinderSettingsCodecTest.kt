package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewfinderSettingsCodec] 与 [StickerPackResolver.expandDownloadUrl] 的纯 JVM 单测。
 *
 * 覆盖：默认值往返、非默认值往返、脏数据（非法布尔/数字/枚举/越界）零崩溃降级、键位清单完整性、
 * 下载地址模板展开。
 */
class ViewfinderSettingsCodecTest {

    private val delta = 1e-6f

    // --------------------------------------------------------------- 往返

    @Test
    fun defaultSettingsRoundTrip() {
        val encoded = ViewfinderSettingsCodec.encode(ViewfinderSettings())
        val decoded = ViewfinderSettingsCodec.decode(encoded)
        assertFalse(decoded.corrected)
        assertEquals(ViewfinderSettings(), decoded.value)
        assertEquals(ViewfinderSettingsCodec.ALL_KEYS.size, encoded.size)
    }

    @Test
    fun customizedSettingsRoundTrip() {
        val custom = ViewfinderSettings(
            zebra = ZebraConfig(
                enabled = true,
                thresholdPercent = 105f,
                stripeWidthPx = 24f,
                stripeDuty = 0.25f,
                stripeAngleDeg = 60f,
                stripeColorR = 0f,
                stripeColorG = 1f,
                stripeColorB = 0.5f,
                stripeAlpha = 0.4f,
                metric = ZebraMetric.MAX_RGB,
            ),
            histogram = HistogramOverlayConfig(
                enabled = true,
                binCount = 128,
                sampleIntervalMs = 500L,
                anchor = HistogramAnchor.BOTTOM_LEFT,
                marginFraction = 0.1f,
                widthFraction = 0.4f,
                heightFraction = 0.2f,
                alpha = 0.5f,
                smoothing = 0.6f,
                showLuma = false,
                showRed = true,
                showGreen = false,
                showBlue = true,
            ),
            peaking = PeakingEnhanceConfig(
                enabled = false,
                sensitivity = 0.8f,
                edgeThresholdOverride = 0.5f,
                colorR = 0f,
                colorG = 1f,
                colorB = 0f,
                mixStrength = 0.5f,
                stepScale = 2f,
            ),
            sticker = StickerConfig(
                enabled = true,
                stickerId = "round_glasses",
                anchorMode = StickerAnchorMode.NOSE,
                scale = 1.5f,
                offsetX = -0.3f,
                offsetY = 0.2f,
                mirror = true,
                alpha = 0.8f,
                maxFaces = 3,
                detectIntervalMs = 200L,
                smoothing = 0.2f,
                modelAssetPath = "/data/local/tmp/custom.task",
                modelDownloadUrl = "",
                packDirectory = "my_pack",
                packDownloadUrl = "https://example.com/pack/{id}.png",
            ),
        )
        val decoded = ViewfinderSettingsCodec.decode(ViewfinderSettingsCodec.encode(custom))
        assertFalse(decoded.corrected)
        assertEquals(custom, decoded.value)
    }

    @Test
    fun emptyMapYieldsDefaultsWithoutCorrections() {
        val decoded = ViewfinderSettingsCodec.decode(emptyMap())
        assertEquals(ViewfinderSettings(), decoded.value)
        assertFalse(decoded.corrected)
    }

    // ------------------------------------------------------------- 脏数据

    @Test
    fun garbageValuesDegradeWithoutCrashing() {
        val decoded = ViewfinderSettingsCodec.decode(
            mapOf(
                ViewfinderSettingsCodec.KEY_ZEBRA_ENABLED to "yes",          // 非法布尔
                ViewfinderSettingsCodec.KEY_HISTOGRAM_BINS to "abc",         // 非法整数
                ViewfinderSettingsCodec.KEY_HISTOGRAM_ANCHOR to "SIDEWAYS",  // 非法枚举
                ViewfinderSettingsCodec.KEY_HISTOGRAM_INTERVAL_MS to "-5",   // 越界长整型
                ViewfinderSettingsCodec.KEY_STICKER_SCALE to "999",          // 越界浮点
                ViewfinderSettingsCodec.KEY_STICKER_ID to "not_a_sticker",   // 未注册 id
                ViewfinderSettingsCodec.KEY_PEAKING_THRESHOLD_OVERRIDE to "NaN",
            ),
        )
        // 非法布尔/数字 → 回落默认值
        assertFalse(decoded.value.zebra.enabled)
        assertEquals(ViewfinderParamRanges.HISTOGRAM_BIN_DEFAULT, decoded.value.histogram.binCount)
        // 非法枚举 → 回落默认锚点（枚举解析失败不记录 correction，此处只断言取值安全）
        assertEquals(HistogramAnchor.TOP_RIGHT, decoded.value.histogram.anchor)
        // 越界值 → clamp
        assertEquals(50L, decoded.value.histogram.sampleIntervalMs)
        assertEquals(4f, decoded.value.sticker.scale, delta)
        // 未注册贴纸 id → 回落内置默认
        assertEquals(FaceStickerCatalog.DEFAULT_STICKER_ID, decoded.value.sticker.stickerId)
        // "NaN" 能被 Float 解析，但 clamp 阶段会判定为非有限值 → 回落自动（-1）
        assertEquals(ViewfinderParamRanges.PEAKING_THRESHOLD_AUTO, decoded.value.peaking.edgeThresholdOverride, delta)
        // 必须给出可读说明，且包含解析失败项
        assertTrue(decoded.corrections.isNotEmpty())
        assertTrue(decoded.corrections.any { it.contains("解析失败") })
    }

    @Test
    fun blankValuesAreIgnoredRatherThanParsed() {
        val decoded = ViewfinderSettingsCodec.decode(
            mapOf(
                ViewfinderSettingsCodec.KEY_ZEBRA_THRESHOLD_PERCENT to "   ",
                ViewfinderSettingsCodec.KEY_STICKER_MODEL_PATH to "",
                ViewfinderSettingsCodec.KEY_STICKER_PACK_URL to "",
            ),
        )
        assertEquals(ViewfinderParamRanges.ZEBRA_THRESHOLD_PERCENT_DEFAULT, decoded.value.zebra.thresholdPercent, delta)
        assertEquals(FaceStickerDetector.DEFAULT_MODEL_ASSET_PATH, decoded.value.sticker.modelAssetPath)
        assertEquals("", decoded.value.sticker.packDownloadUrl)
        assertFalse(decoded.corrected)
    }

    @Test
    fun unknownKeysDoNotBreakDecoding() {
        val decoded = ViewfinderSettingsCodec.decode(
            mapOf(
                "some.future.key" to "whatever",
                ViewfinderSettingsCodec.KEY_ZEBRA_METRIC to "MAX_RGB",
            ),
        )
        assertEquals(ZebraMetric.MAX_RGB, decoded.value.zebra.metric)
        assertFalse(decoded.corrected)
    }

    @Test
    fun decodeIsIdempotent() {
        val once = ViewfinderSettingsCodec.decode(
            mapOf(ViewfinderSettingsCodec.KEY_STICKER_SCALE to "99"),
        ).value
        val twice = ViewfinderSettingsCodec.decode(ViewfinderSettingsCodec.encode(once))
        assertFalse(twice.corrected)
        assertEquals(once, twice.value)
    }

    // ------------------------------------------------------------ 键位清单

    @Test
    fun allKeysAreUniqueAndFullyCovered() {
        val keys = ViewfinderSettingsCodec.ALL_KEYS
        assertEquals(46, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        // 全部键位统一前缀，便于与既有设置项区分
        assertTrue(keys.all { it.startsWith("viewfinder.") })
        // encode 覆盖全部键位（不遗漏、不越界）
        assertEquals(keys.toSet(), ViewfinderSettingsCodec.encode(ViewfinderSettings()).keys)
    }

    // -------------------------------------------------- 外部贴纸包地址展开

    @Test
    fun expandDownloadUrlRequiresEnabledTemplateAndPlaceholder() {
        val config = StickerPackConfig(enabled = true, downloadUrlTemplate = "https://cdn.example.com/{id}.png")
        assertEquals("https://cdn.example.com/cat_ears.png", StickerPackResolver.expandDownloadUrl(config, "cat_ears"))

        // 未启用 → null
        assertEquals(null, StickerPackResolver.expandDownloadUrl(config.copy(enabled = false), "cat_ears"))
        // 模板为空 → null
        assertEquals(null, StickerPackResolver.expandDownloadUrl(config.copy(downloadUrlTemplate = "  "), "cat_ears"))
        // 缺少占位符 → null（避免所有贴纸指向同一张图）
        assertEquals(
            null,
            StickerPackResolver.expandDownloadUrl(config.copy(downloadUrlTemplate = "https://cdn.example.com/a.png"), "cat_ears"),
        )
    }

    @Test
    fun expandDownloadUrlSanitizesStickerId() {
        val config = StickerPackConfig(enabled = true, downloadUrlTemplate = "https://cdn.example.com/{id}.png")
        // 路径分隔符被替换为下划线，避免拼出多级路径
        assertEquals("https://cdn.example.com/a_b.png", StickerPackResolver.expandDownloadUrl(config, "a/b"))
        // 全空白 id → 固定回落 "sticker"
        assertEquals(
            "https://cdn.example.com/sticker.png",
            StickerPackResolver.expandDownloadUrl(config, "   "),
        )
        // 全非法字符 → 被逐个替换为下划线（确定性结果，不产生空路径段）
        assertEquals(
            "https://cdn.example.com/___.png",
            StickerPackResolver.expandDownloadUrl(config, "///"),
        )
    }
}
