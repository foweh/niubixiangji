package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * 开放参数 clamp 单测：验证"参数开放可调 + 默认值 + 范围 + clamp"在**每一个**配置对象上生效。
 *
 * 覆盖 PATTERN_SPEC §7.4 要求的"亮度对比度 clamp"与"水印排版边界（配置侧）"。
 */
class GeEditClampTest {

    private val fallbackPattern = GeEditRanges.DATE_PATTERN_DEFAULT

    @Test
    fun `像素调节参数 clamp 到正负 100`() {
        val (over, reportOver) = GePixelAdjustParams(brightness = 900, contrast = 900, saturation = 900, temperature = 900)
            .clamp()
        assertEquals(GeEditRanges.ADJUST_MAX, over.brightness)
        assertEquals(GeEditRanges.ADJUST_MAX, over.contrast)
        assertEquals(GeEditRanges.ADJUST_MAX, over.saturation)
        assertEquals(GeEditRanges.ADJUST_MAX, over.temperature)
        assertTrue(reportOver.changed)

        val (under, _) = GePixelAdjustParams(brightness = -900, contrast = -900, saturation = -900, temperature = -900)
            .clamp()
        assertEquals(GeEditRanges.ADJUST_MIN, under.brightness)
        assertEquals(GeEditRanges.ADJUST_MIN, under.temperature)
    }

    @Test
    fun `clampFloat 对 NaN 与无穷回落默认值`() {
        assertEquals(1f, GeEditRanges.clampFloat(Float.NaN, 0f, 2f, 1f), 1e-6f)
        assertEquals(1f, GeEditRanges.clampFloat(Float.POSITIVE_INFINITY, 0f, 2f, 1f), 1e-6f)
        assertEquals(1f, GeEditRanges.clampFloat(Float.NEGATIVE_INFINITY, 0f, 2f, 1f), 1e-6f)
        assertEquals(2f, GeEditRanges.clampFloat(99f, 0f, 2f, 1f), 1e-6f)
    }

    @Test
    fun `clampInt 与 clampAdjust 收敛越界整数`() {
        assertEquals(100, GeEditRanges.clampInt(1000, GeEditRanges.ADJUST_MIN, GeEditRanges.ADJUST_MAX))
        assertEquals(-100, GeEditRanges.clampAdjust(-1000))
        assertEquals(0, GeEditRanges.clampAdjust(0))
    }

    @Test
    fun `水印字号透明度间距角度全部收敛`() {
        val (over, report) = GeWatermarkConfig(
            fontSizeSp = 9999f,
            opacity = 9f,
            tileSpacingFactor = 999f,
            rotationDegrees = 999f,
            marginFraction = 999f,
        ).clamp()

        assertEquals(GeEditRanges.WATERMARK_FONT_MAX, over.fontSizeSp, 1e-4f)
        assertEquals(GeEditRanges.OPACITY_MAX, over.opacity, 1e-4f)
        assertEquals(GeEditRanges.TILE_SPACING_MAX, over.tileSpacingFactor, 1e-4f)
        assertEquals(GeEditRanges.WATERMARK_ROTATION_MAX, over.rotationDegrees, 1e-4f)
        assertEquals(GeEditRanges.MARGIN_FRACTION_MAX, over.marginFraction, 1e-4f)
        assertTrue(report.changed)

        val (under, _) = GeWatermarkConfig(
            fontSizeSp = -5f,
            opacity = -5f,
            tileSpacingFactor = -5f,
            rotationDegrees = -999f,
            marginFraction = -1f,
        ).clamp()
        assertEquals(GeEditRanges.WATERMARK_FONT_MIN, under.fontSizeSp, 1e-4f)
        assertEquals(GeEditRanges.OPACITY_MIN, under.opacity, 1e-4f)
        assertEquals(GeEditRanges.TILE_SPACING_MIN, under.tileSpacingFactor, 1e-4f)
        assertEquals(GeEditRanges.WATERMARK_ROTATION_MIN, under.rotationDegrees, 1e-4f)
        assertEquals(GeEditRanges.MARGIN_FRACTION_MIN, under.marginFraction, 1e-4f)
    }

    @Test
    fun `水印透明度 NaN 回落默认而其余字段保持不变`() {
        val (safe, _) = GeWatermarkConfig(opacity = Float.NaN, fontSizeSp = 40f).clamp()

        assertEquals(GeEditRanges.WATERMARK_OPACITY_DEFAULT, safe.opacity, 1e-4f)
        assertEquals(40f, safe.fontSizeSp, 1e-4f)
    }

    @Test
    fun `水印文案超长被截断`() {
        val long = "水".repeat(GeEditRanges.WATERMARK_TEXT_MAX_LENGTH + 50)
        val (safe, report) = GeWatermarkConfig(text = long).clamp()

        assertEquals(GeEditRanges.WATERMARK_TEXT_MAX_LENGTH, safe.text.length)
        assertTrue(report.changed)
    }

    @Test
    fun `水印开关与平铺判定`() {
        assertFalse(GeWatermarkConfig(text = "x").isActive)
        assertTrue(GeWatermarkConfig(enabled = true, text = "x").isActive)
        assertFalse(GeWatermarkConfig(enabled = true, text = "  ").isActive)
        assertTrue(
            GeWatermarkConfig(enabled = true, text = "x", placement = GeWatermarkPlacement.TILED).isTiled,
        )
    }

    @Test
    fun `日期戳格式串非法时回落默认值`() {
        assertEquals(fallbackPattern, GeDateStampFormat.normalizePattern(""))
        assertEquals(fallbackPattern, GeDateStampFormat.normalizePattern(null))
        assertEquals(fallbackPattern, GeDateStampFormat.normalizePattern("INVALID"))
        assertEquals(fallbackPattern, GeDateStampFormat.normalizePattern("yyyy-MM\ndd"))
        assertEquals(fallbackPattern, GeDateStampFormat.normalizePattern("y".repeat(GeEditRanges.DATE_PATTERN_MAX_LENGTH + 1)))
        assertEquals("yyyy-MM-dd", GeDateStampFormat.normalizePattern(" yyyy-MM-dd "))
    }

    @Test
    fun `日期戳预设格式全部合法`() {
        GeDateStampFormat.PRESETS.forEach { preset ->
            assertTrue("预设格式必须合法：$preset", GeDateStampFormat.isValidPattern(preset))
            assertEquals("合法格式不应被改写", preset, GeDateStampFormat.normalizePattern(preset))
        }
        assertFalse(GeDateStampFormat.isValidPattern(""))
    }

    @Test
    fun `日期戳按指定时区输出`() {
        val text = GeDateStampFormat.format(
            epochMillis = 0L,
            pattern = "yyyy-MM-dd HH:mm:ss",
            zoneId = ZoneId.of("UTC"),
            locale = Locale.US,
        )

        assertEquals("1970-01-01 00:00:00", text)
    }

    @Test
    fun `日期戳配置整体收敛`() {
        val (safe, report) = GeDateStampConfig(pattern = "BAD", fontSizeSp = 9999f, opacity = -3f).clamp()

        assertEquals(fallbackPattern, safe.pattern)
        assertEquals(GeEditRanges.WATERMARK_FONT_MAX, safe.fontSizeSp, 1e-4f)
        assertEquals(GeEditRanges.OPACITY_MIN, safe.opacity, 1e-4f)
        assertTrue(report.changed)
    }

    @Test
    fun `裁剪配置收敛触摸半径与最小边长`() {
        val (safe, report) = GeCropConfig(
            minFraction = 10f,
            handleTouchRadiusDp = 500f,
            initialPadding = 5f,
        ).clamp()

        assertEquals(GeEditRanges.CROP_MIN_FRACTION_LIMIT_MAX, safe.minFraction, 1e-4f)
        assertEquals(96f, safe.handleTouchRadiusDp, 1e-4f)
        assertEquals(0.4f, safe.initialPadding, 1e-4f)
        assertTrue(report.changed)

        val (lower, _) = GeCropConfig(handleTouchRadiusDp = 1f, minFraction = -1f, initialPadding = -1f).clamp()
        assertEquals(12f, lower.handleTouchRadiusDp, 1e-4f)
        assertEquals(GeEditRanges.CROP_MIN_FRACTION, lower.minFraction, 1e-4f)
        assertEquals(0f, lower.initialPadding, 1e-4f)
    }

    @Test
    fun `裁剪默认值保持三分线与吸附开关`() {
        val (safe, report) = GeCropConfig().clamp()

        assertEquals(GeEditRanges.CROP_MIN_FRACTION, safe.minFraction, 1e-4f)
        assertTrue(safe.snapToBounds)
        assertTrue(safe.showGridLines)
        assertFalse(report.changed)
    }

    @Test
    fun `图像管线配置收敛分片参数与工作分辨率`() {
        val (safe, _) = GeImagePipelineConfig(
            workingScale = 5f,
            stripHeight = 999_999,
            singlePassPixelLimit = 1L,
        ).clamp()

        assertEquals(GeEditRanges.WORK_SCALE_MAX, safe.workingScale, 1e-4f)
        assertEquals(GeImagePipelineConfig.MAX_STRIP_HEIGHT, safe.stripHeight)
        assertEquals(GeImagePipelineConfig.MIN_SINGLE_PASS_PIXELS, safe.singlePassPixelLimit)

        val (lower, _) = GeImagePipelineConfig(
            workingScale = 0.0001f,
            stripHeight = 1,
            singlePassPixelLimit = Long.MAX_VALUE,
        ).clamp()
        assertEquals(GeEditRanges.WORK_SCALE_MIN, lower.workingScale, 1e-4f)
        assertEquals(GeImagePipelineConfig.MIN_STRIP_HEIGHT, lower.stripHeight)
        assertEquals(GeImagePipelineConfig.MAX_SINGLE_PASS_PIXELS, lower.singlePassPixelLimit)
    }

    @Test
    fun `图像管线工作分辨率 NaN 回落默认`() {
        val (safe, _) = GeImagePipelineConfig(workingScale = Float.NaN).clamp()

        assertEquals(GeEditRanges.WORK_SCALE_DEFAULT, safe.workingScale, 1e-4f)
    }

    @Test
    fun `视频变速收敛到 0_25 至 4 倍`() {
        val (fast, reportFast) = GeVideoEditSpec(speed = 0.01f).clamp()
        assertEquals(GeEditRanges.VIDEO_SPEED_MIN, fast.speed, 1e-4f)
        assertTrue(reportFast.changed)

        val (slow, _) = GeVideoEditSpec(speed = 100f).clamp()
        assertEquals(GeEditRanges.VIDEO_SPEED_MAX, slow.speed, 1e-4f)

        val (broken, _) = GeVideoEditSpec(speed = Float.NaN).clamp()
        assertEquals(GeEditRanges.VIDEO_SPEED_DEFAULT, broken.speed, 1e-4f)
    }

    @Test
    fun `视频旋转收敛到最近合法档位`() {
        assertEquals(0, GeVideoEditSpec.normalizeRotation(0))
        assertEquals(0, GeVideoEditSpec.normalizeRotation(44))
        assertEquals(90, GeVideoEditSpec.normalizeRotation(70))
        assertEquals(90, GeVideoEditSpec.normalizeRotation(89))
        assertEquals(90, GeVideoEditSpec.normalizeRotation(90))
        assertEquals(180, GeVideoEditSpec.normalizeRotation(180))
        assertEquals(270, GeVideoEditSpec.normalizeRotation(270))
        assertEquals(270, GeVideoEditSpec.normalizeRotation(-90))
        assertEquals(0, GeVideoEditSpec.normalizeRotation(360))
        assertEquals(270, GeVideoEditSpec.normalizeRotation(-450))
        assertEquals(90, GeVideoEditSpec.normalizeRotation(450))
    }

    @Test
    fun `视频帧率与码率收敛`() {
        val (safe, _) = GeVideoEditSpec(
            outputFrameRate = 9999,
            targetBitrate = 1,
            lutIntensity = 9f,
        ).clamp()

        assertEquals(GeEditRanges.VIDEO_FRAME_RATE_MAX, safe.outputFrameRate)
        assertEquals(GeVideoEditSpec.MIN_BITRATE_BPS, safe.targetBitrate)
        assertEquals(2f, safe.lutIntensity, 1e-4f)

        val (lower, _) = GeVideoEditSpec(
            outputFrameRate = -10,
            targetBitrate = Int.MAX_VALUE,
        ).clamp()
        assertEquals(GeEditRanges.VIDEO_FRAME_RATE_MIN, lower.outputFrameRate)
        assertEquals(GeVideoEditSpec.MAX_BITRATE_BPS, lower.targetBitrate)
    }

    @Test
    fun `视频空片段被过滤且无片段时不可渲染`() {
        val (safe, report) = GeVideoEditSpec(
            segments = listOf(
                GeVideoSegment(uriString = ""),
                GeVideoSegment(uriString = "content://media/1", sourceDurationMs = -5L),
            ),
        ).clamp()

        assertEquals(1, safe.segments.size)
        assertEquals(0L, safe.segments.first().sourceDurationMs)
        assertTrue(report.changed)
        assertTrue(safe.isRenderable())

        assertFalse(GeVideoEditSpec(segments = emptyList()).isRenderable())
    }

    @Test
    fun `视频片段数量超上限被截断`() {
        val segments = List(GeEditRanges.VIDEO_SEGMENT_MAX + 25) { index ->
            GeVideoSegment(uriString = "content://media/$index", sourceDurationMs = 1000L)
        }

        val (safe, _) = GeVideoEditSpec(segments = segments).clamp()

        assertEquals(GeEditRanges.VIDEO_SEGMENT_MAX, safe.segments.size)
    }

    @Test
    fun `视频书签数量超上限被截断`() {
        val bookmarks = List(GeEditRanges.VIDEO_BOOKMARK_MAX + 30) { index ->
            GeVideoBookmark(timeMs = index * 1000L)
        }

        val (safe, _) = GeVideoEditSpec(bookmarks = bookmarks).clamp()

        assertEquals(GeEditRanges.VIDEO_BOOKMARK_MAX, safe.bookmarks.size)
    }

    @Test
    fun `分享请求文本与条目收敛`() {
        val (safe, report) = GeShareRequest(
            target = GeShareTarget.IMAGE,
            text = "a".repeat(GeShareRequest.MAX_TEXT_LENGTH + 10),
            mediaUris = listOf("a", " ", "a", "b"),
        ).clamp()

        assertEquals(GeShareRequest.MAX_TEXT_LENGTH, safe.text?.length)
        assertEquals(listOf("a", "b"), safe.mediaUris)
        assertTrue(report.changed)
    }

    @Test
    fun `分享请求条目数上限`() {
        val uris = List(GeShareRequest.MAX_SHARE_ITEMS + 5) { "content://media/$it" }

        val (safe, _) = GeShareRequest(target = GeShareTarget.IMAGE, mediaUris = uris).clamp()

        assertEquals(GeShareRequest.MAX_SHARE_ITEMS, safe.mediaUris.size)
    }

    @Test
    fun `分享可分享判定与 MIME 映射`() {
        assertTrue(GeShareRequest(target = GeShareTarget.TEXT, text = "hi").isShareable())
        assertFalse(GeShareRequest(target = GeShareTarget.TEXT).isShareable())
        assertFalse(GeShareRequest(target = GeShareTarget.IMAGE, mediaUris = listOf(" ")).isShareable())
        assertTrue(GeShareRequest(target = GeShareTarget.VIDEO, mediaUris = listOf("u")).isShareable())

        assertEquals("text/plain", GeShareRequest.mimeFor(GeShareTarget.TEXT))
        assertEquals("image/*", GeShareRequest.mimeFor(GeShareTarget.IMAGE))
        assertEquals("video/*", GeShareRequest.mimeFor(GeShareTarget.VIDEO))
    }

    @Test
    fun `对比配置收敛分割位置与间隙`() {
        val (safe, report) = GeCompareConfig(split = 9f, gapPx = 9999).clamp()

        assertEquals(GeEditRanges.OPACITY_MAX, safe.split, 1e-4f)
        assertEquals(GeCompareConfig.MAX_GAP_PX, safe.gapPx)
        assertTrue(report.changed)

        val (lower, _) = GeCompareConfig(split = -9f, gapPx = -50).clamp()
        assertEquals(GeEditRanges.OPACITY_MIN, lower.split, 1e-4f)
        assertEquals(0, lower.gapPx)

        val (broken, _) = GeCompareConfig(split = Float.NaN).clamp()
        assertEquals(GeEditRanges.COMPARE_SPLIT_DEFAULT, broken.split, 1e-4f)
    }

    @Test
    fun `视频导出选项收敛轮询间隔`() {
        val (fast, _) = GeVideoExportOptions(progressPollMs = 1L).clamp()
        assertEquals(GeVideoExportOptions.MIN_POLL_MS, fast.progressPollMs)

        val (slow, _) = GeVideoExportOptions(progressPollMs = 60_000L).clamp()
        assertEquals(GeVideoExportOptions.MAX_POLL_MS, slow.progressPollMs)

        val (empty, _) = GeVideoExportOptions(targetVideoMime = " ").clamp()
        assertEquals("video/avc", empty.targetVideoMime)
    }

    @Test
    fun `导出质量为 1 到 100 区间`() {
        assertEquals(1, GeMediaExporter.MIN_QUALITY)
        assertEquals(100, GeMediaExporter.MAX_QUALITY)
        assertEquals(95, GeMediaExporter.DEFAULT_QUALITY)
    }
}
