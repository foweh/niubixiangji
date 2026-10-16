package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览域重处理后端（RAW 直方图 / 过曝判定 / RAW→JPEG 导出数学 / 媒体类型判定）测试。
 *
 * 任务书要求"重任务走 BgJobRunner 的纯数学部分可单测"：这里覆盖**分片、采样率、
 * 目标尺寸、文件名清洗、直方图分箱与曝光判定**等可在纯 JVM 下验证的分支（相册第 36/38 项）。
 */
class AppGalleryPreviewProcessingTest {

    private val whitePixel = 0xFFFFFFFF.toInt()
    private val blackPixel = 0xFF000000.toInt()
    private val midGrayPixel = 0xFF808080.toInt()

    // ---------------- 直方图 ----------------

    @Test
    fun allWhitePixelsLandInTopBinAndCountAsOverexposed() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { whitePixel })
        assertEquals(256, histogram.binCount)
        assertEquals(100, histogram.totalPx)
        assertEquals(100, histogram.bins[255])
        assertEquals(100, histogram.overexposedPx)
        assertEquals(0, histogram.underexposedPx)
        assertEquals(1f, histogram.overexposedRatio, 1e-4f)
        assertFalse(histogram.isEmpty)
    }

    @Test
    fun allBlackPixelsCountAsUnderexposed() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(64) { blackPixel })
        assertEquals(64, histogram.totalPx)
        assertEquals(0, histogram.bins[255])
        assertEquals(64, histogram.underexposedPx)
        assertEquals(1f, histogram.underexposedRatio, 1e-4f)
    }

    @Test
    fun midGrayPixelsAreNeitherClipped() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(32) { midGrayPixel })
        assertEquals(0, histogram.overexposedPx)
        assertEquals(0, histogram.underexposedPx)
        assertEquals(32, histogram.bins[128])
    }

    @Test
    fun emptyPixelArrayYieldsEmptyHistogram() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(0))
        assertTrue(histogram.isEmpty)
        assertEquals(0L, histogram.totalPx.toLong())
    }

    @Test
    fun binCountIsClampedIntoSupportedRange() {
        assertEquals(32, AppGalleryPreviewHistogramCalculator.compute(IntArray(4) { whitePixel }, bins = 4).binCount)
        assertEquals(
            1024,
            AppGalleryPreviewHistogramCalculator.compute(IntArray(4) { whitePixel }, bins = 100_000).binCount,
        )
    }

    @Test
    fun sliceRangeRestrictsCountedPixels() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(
            argb = IntArray(100) { whitePixel },
            from = 0,
            until = 40,
        )
        assertEquals(40, histogram.totalPx)

        val reversed = AppGalleryPreviewHistogramCalculator.compute(
            argb = IntArray(100) { whitePixel },
            from = 80,
            until = 20,
        )
        assertTrue("反向区间按空处理", reversed.isEmpty)

        val outOfBounds = AppGalleryPreviewHistogramCalculator.compute(
            argb = IntArray(10) { whitePixel },
            from = -100,
            until = 999,
        )
        assertEquals(10, outOfBounds.totalPx)
    }

    @Test
    fun normalizedScalesPeakToOne() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(50) { whitePixel })
        val normalized = histogram.normalized()
        assertEquals(256, normalized.size)
        assertEquals(1f, normalized[255], 1e-4f)
        assertEquals(0f, normalized[0], 1e-4f)
    }

    @Test
    fun rgbChannelsAreCollectedOnDemand() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(
            argb = IntArray(10) { whitePixel },
            withRgb = true,
        )
        assertEquals(3, histogram.rgbBins.size)
        assertEquals(10, histogram.rgbBins[0][255])
        assertEquals(10, histogram.rgbBins[1][255])
        assertEquals(10, histogram.rgbBins[2][255])

        val withoutRgb = AppGalleryPreviewHistogramCalculator.compute(IntArray(10) { whitePixel })
        assertEquals(0, withoutRgb.rgbBins.size)
    }

    @Test
    fun accumulatorMatchesBatchComputation() {
        val pixels = IntArray(200) { index -> if (index % 2 == 0) whitePixel else midGrayPixel }
        val batch = AppGalleryPreviewHistogramCalculator.compute(pixels)

        val accumulator = AppGalleryPreviewHistogramAccumulator(bins = 256)
        pixels.forEach { accumulator.add(it) }
        val streamed = accumulator.toHistogram()

        assertEquals(batch.totalPx, streamed.totalPx)
        assertEquals(batch.bins[255], streamed.bins[255])
        assertEquals(batch.bins[128], streamed.bins[128])
    }

    // ---------------- 曝光判定 ----------------

    @Test
    fun verdictDetectsHighlightClipping() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { whitePixel })
        assertEquals(AppGalleryPreviewExposureVerdict.HIGHLIGHT_CLIPPED, AppGalleryPreviewExposurePolicy.verdict(histogram))
    }

    @Test
    fun verdictDetectsShadowClipping() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { blackPixel })
        assertEquals(AppGalleryPreviewExposureVerdict.SHADOW_CLIPPED, AppGalleryPreviewExposurePolicy.verdict(histogram))
    }

    @Test
    fun verdictDetectsBothEndsClipped() {
        val pixels = IntArray(100) { index -> if (index < 50) whitePixel else blackPixel }
        val histogram = AppGalleryPreviewHistogramCalculator.compute(pixels)
        assertEquals(AppGalleryPreviewExposureVerdict.BOTH_CLIPPED, AppGalleryPreviewExposurePolicy.verdict(histogram))
    }

    @Test
    fun verdictIsOkForBalancedImage() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { midGrayPixel })
        assertEquals(AppGalleryPreviewExposureVerdict.OK, AppGalleryPreviewExposurePolicy.verdict(histogram))
    }

    @Test
    fun verdictIsUnknownWithoutData() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(0))
        assertEquals(AppGalleryPreviewExposureVerdict.UNKNOWN, AppGalleryPreviewExposurePolicy.verdict(histogram))
    }

    @Test
    fun verdictWarnRatioBoundaryIsExclusive() {
        val histogram = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { whitePixel })
        // 占比 1.0，阈值 1.0 → 不触发（判定为"大于"）。
        assertEquals(
            AppGalleryPreviewExposureVerdict.OK,
            AppGalleryPreviewExposurePolicy.verdict(histogram, warnRatio = 1f),
        )
        // 阈值 0.5 → 触发。
        assertEquals(
            AppGalleryPreviewExposureVerdict.HIGHLIGHT_CLIPPED,
            AppGalleryPreviewExposurePolicy.verdict(histogram, warnRatio = 0.5f),
        )
    }

    @Test
    fun suggestedCompensationPointsToRecoveryDirection() {
        val over = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { whitePixel })
        assertTrue(AppGalleryPreviewExposurePolicy.suggestedCompensationEv(over) < 0.0)

        val under = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { blackPixel })
        assertTrue(AppGalleryPreviewExposurePolicy.suggestedCompensationEv(under) > 0.0)

        val ok = AppGalleryPreviewHistogramCalculator.compute(IntArray(100) { midGrayPixel })
        assertEquals(0.0, AppGalleryPreviewExposurePolicy.suggestedCompensationEv(ok), 1e-9)

        assertEquals(0.0, AppGalleryPreviewExposurePolicy.suggestedCompensationEv(AppGalleryPreviewHistogram(IntArray(0))), 1e-9)
    }

    @Test
    fun histogramConfigBoundsAreClamped() {
        val result = AppGalleryPreviewConfig(
            rawHistogramBins = 4096,
            rawOverexposureThreshold = 5f,
            rawUnderexposureThreshold = -1f,
        ).appGalleryPreviewSafe()
        assertEquals(1024, result.config.rawHistogramBins)
        assertEquals(1f, result.config.rawOverexposureThreshold, 1e-6f)
        assertEquals(0f, result.config.rawUnderexposureThreshold, 1e-6f)
    }

    // ---------------- RAW→JPEG 导出数学 ----------------

    @Test
    fun targetSizeOnlyShrinks() {
        assertEquals(AppGalleryPreviewIntSize(1000, 800), AppGalleryPreviewRawExportMath.targetSize(1000, 800, 4096))
        assertEquals(AppGalleryPreviewIntSize(2048, 1536), AppGalleryPreviewRawExportMath.targetSize(4000, 3000, 2048))
        assertEquals(AppGalleryPreviewIntSize(0, 0), AppGalleryPreviewRawExportMath.targetSize(0, 10, 100))
        assertEquals(AppGalleryPreviewIntSize(0, 0), AppGalleryPreviewRawExportMath.targetSize(-5, -5, 100))
    }

    @Test
    fun targetSizeKeepsAspectRoughly() {
        val target = AppGalleryPreviewRawExportMath.targetSize(6000, 4000, 3000)
        assertEquals(3000, target.width)
        assertEquals(2000, target.height)
        assertEquals(1.5f, target.width.toFloat() / target.height.toFloat(), 1e-3f)
    }

    @Test
    fun estimatedBytesUsesFourBytesPerPixel() {
        assertEquals(399_424L, AppGalleryPreviewRawExportMath.estimatedBytes(316, 316))
        assertEquals(0L, AppGalleryPreviewRawExportMath.estimatedBytes(0, 100))
        assertEquals(400L, AppGalleryPreviewRawExportMath.estimatedBytes(10, 10))
    }

    @Test
    fun decodeSampleSizeIsPowerOfTwoAndRespectsEdge() {
        assertEquals(1, AppGalleryPreviewRawExportMath.decodeSampleSize(100, 100, 100))
        assertEquals(2, AppGalleryPreviewRawExportMath.decodeSampleSize(4000, 3000, 1000))
        assertEquals(1, AppGalleryPreviewRawExportMath.decodeSampleSize(0, 10, 100))
        // 不设长边限制时也应满足内存预算。
        assertEquals(
            4,
            AppGalleryPreviewRawExportMath.decodeSampleSize(4000, 3000, targetLongEdge = 0, budgetBytes = 3_000_000L),
        )
    }

    @Test
    fun chunkRowRangesCoversEveryRowExactlyOnce() {
        assertEquals(listOf(0..3, 4..7, 8..9), AppGalleryPreviewRawExportMath.chunkRowRanges(10, 4))
        assertEquals(listOf(0..0, 1..1, 2..2), AppGalleryPreviewRawExportMath.chunkRowRanges(3, 0))
        assertTrue(AppGalleryPreviewRawExportMath.chunkRowRanges(0, 4).isEmpty())
        assertTrue(AppGalleryPreviewRawExportMath.chunkRowRanges(-5, 4).isEmpty())
        assertEquals(AppGalleryPreviewRawExportMath.chunkRowRanges(10, 4), AppGalleryPreviewRawExportMath.pixelBandRanges(10, 4))
    }

    @Test
    fun sanitizeFileNameRemovesPathAndIllegalChars() {
        assertEquals("a_b.jpg", AppGalleryPreviewRawExportMath.sanitizeFileName("dir/a:b?.jpg"))
        assertEquals("photo.png", AppGalleryPreviewRawExportMath.sanitizeFileName("photo.png"))
        assertEquals("raw_export.jpg", AppGalleryPreviewRawExportMath.sanitizeFileName(null))
        assertEquals("raw_export.jpg", AppGalleryPreviewRawExportMath.sanitizeFileName("   "))
        assertEquals("IMG_0001.jpg", AppGalleryPreviewRawExportMath.sanitizeFileName("IMG_0001"))
        assertEquals("noext.jpg", AppGalleryPreviewRawExportMath.sanitizeFileName("noext"))
    }

    @Test
    fun exportFileNameAppendsSuffix() {
        assertEquals("IMG_0001_export.jpg", AppGalleryPreviewRawExportMath.exportFileNameFor("IMG_0001.dng"))
        assertEquals("raw_export_export.jpg", AppGalleryPreviewRawExportMath.exportFileNameFor(null))
    }

    @Test
    fun exportRequestIsNormalizedIntoBounds() {
        val normalized = AppGalleryPreviewRawExportRequest(
            mediaUri = "content://m/1.dng",
            outputFileName = "dir/a:b?.jpg",
            maxDimension = 1,
            jpegQuality = 0,
            chunkRows = 1,
            workScale = 5f,
        ).normalized()
        assertEquals(512, normalized.maxDimension)
        assertEquals(1, normalized.jpegQuality)
        assertEquals(16, normalized.chunkRows)
        assertEquals(1f, normalized.workScale, 1e-6f)
        assertEquals("a_b.jpg", normalized.outputFileName)

        val tooBig = AppGalleryPreviewRawExportRequest(
            mediaUri = "content://m/1.dng",
            outputFileName = "x.jpg",
            maxDimension = 999_999,
            jpegQuality = 500,
            chunkRows = 999_999,
        ).normalized()
        assertEquals(4096, tooBig.maxDimension)
        assertEquals(100, tooBig.jpegQuality)
        assertEquals(4096, tooBig.chunkRows)
    }

    // ---------------- 媒体类型判定 ----------------

    @Test
    fun mimeTypeTakesPrecedenceOverExtension() {
        assertEquals(
            AppGalleryPreviewMediaKind.GIF,
            AppGalleryPreviewMediaKindResolver.resolve("image/gif", "content://m/1.bin"),
        )
        assertEquals(
            AppGalleryPreviewMediaKind.RAW,
            AppGalleryPreviewMediaKindResolver.resolve("image/x-adobe-dng", null),
        )
        assertEquals(
            AppGalleryPreviewMediaKind.VIDEO,
            AppGalleryPreviewMediaKindResolver.resolve("video/mp4", null),
        )
    }

    @Test
    fun genericImageMimeFallsBackToExtensionForRawAndGif() {
        assertEquals(
            AppGalleryPreviewMediaKind.RAW,
            AppGalleryPreviewMediaKindResolver.resolve("image/*", "content://m/shot.DNG"),
        )
        assertEquals(
            AppGalleryPreviewMediaKind.GIF,
            AppGalleryPreviewMediaKindResolver.resolve("image/*", "content://m/anim.gif"),
        )
        assertEquals(
            AppGalleryPreviewMediaKind.IMAGE,
            AppGalleryPreviewMediaKindResolver.resolve("image/jpeg", "content://m/1.jpg"),
        )
    }

    @Test
    fun extensionResolutionHandlesQueryAndCase() {
        assertEquals(AppGalleryPreviewMediaKind.IMAGE, AppGalleryPreviewMediaKindResolver.resolveByExtension("a.JPG?x=1"))
        assertEquals(AppGalleryPreviewMediaKind.RAW, AppGalleryPreviewMediaKindResolver.resolveByExtension("a.CR3#frag"))
        assertEquals(AppGalleryPreviewMediaKind.VIDEO, AppGalleryPreviewMediaKindResolver.resolveByExtension("a.mov"))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, AppGalleryPreviewMediaKindResolver.resolveByExtension("a.xyz"))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, AppGalleryPreviewMediaKindResolver.resolveByExtension(null))
    }

    @Test
    fun kindCapabilityFlagsAreConsistent() {
        assertTrue(AppGalleryPreviewMediaKindResolver.requiresBackgroundDecode(AppGalleryPreviewMediaKind.RAW))
        assertTrue(AppGalleryPreviewMediaKindResolver.requiresBackgroundDecode(AppGalleryPreviewMediaKind.IMAGE))
        assertFalse(AppGalleryPreviewMediaKindResolver.requiresBackgroundDecode(AppGalleryPreviewMediaKind.VIDEO))
        assertTrue(AppGalleryPreviewMediaKindResolver.supportsSlideshow(AppGalleryPreviewMediaKind.IMAGE))
        assertTrue(AppGalleryPreviewMediaKindResolver.supportsSlideshow(AppGalleryPreviewMediaKind.RAW))
        assertFalse(AppGalleryPreviewMediaKindResolver.supportsSlideshow(AppGalleryPreviewMediaKind.GIF))
        assertTrue(AppGalleryPreviewMediaKindResolver.isAnimated(AppGalleryPreviewMediaKind.GIF))
        assertTrue(AppGalleryPreviewMediaKindResolver.isAnimated(AppGalleryPreviewMediaKind.VIDEO))
        assertFalse(AppGalleryPreviewMediaKindResolver.isAnimated(AppGalleryPreviewMediaKind.IMAGE))
        assertTrue(AppGalleryPreviewMediaKindResolver.isRaw(AppGalleryPreviewMediaKind.RAW))
        assertFalse(AppGalleryPreviewMediaKindResolver.isRaw(AppGalleryPreviewMediaKind.IMAGE))
        assertTrue(AppGalleryPreviewMediaKindResolver.decodableByPlatform(AppGalleryPreviewMediaKind.IMAGE))
        assertFalse(AppGalleryPreviewMediaKindResolver.decodableByPlatform(AppGalleryPreviewMediaKind.RAW))
    }

    @Test
    fun unresolvedKindIsUnsupported() {
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, AppGalleryPreviewMediaKindResolver.resolve(null, null))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, AppGalleryPreviewMediaKindResolver.resolve("text/plain", "a.txt"))
    }

    @Test
    fun backgroundChunkBudgetIsClamped() {
        val result = AppGalleryPreviewConfig(bgChunkPixels = 1, bgMaxHeapMb = 1_000_000).appGalleryPreviewSafe()
        assertEquals(4096, result.config.bgChunkPixels)
        assertEquals(4096, result.config.bgMaxHeapMb)
        assertEquals(262144, AppGalleryPreviewConfig.DEFAULT.bgChunkPixels)
    }
}
