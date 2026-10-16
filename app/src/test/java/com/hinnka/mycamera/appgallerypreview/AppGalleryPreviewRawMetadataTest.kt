package com.hinnka.mycamera.appgallerypreview

import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.APERTURE_MAX
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.APERTURE_MIN
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.DIMENSION_MAX
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.EV_MAX
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.EV_MIN
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.EXPOSURE_TIME_MAX_SEC
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.EXPOSURE_TIME_MIN_SEC
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.FOCAL_MAX_MM
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.ISO_MAX
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_EXPOSURE_BIAS
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_EXPOSURE_TIME
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_F_NUMBER
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_FOCAL_LENGTH
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_IMAGE_LENGTH
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_IMAGE_WIDTH
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_ISO
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_PHOTOGRAPHIC_SENSITIVITY
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_PIXEL_X_DIMENSION
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawMetadataParser.TAG_PIXEL_Y_DIMENSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAW/DNG 曝光元数据解析边界测试（相册第 36 项）。
 *
 * 覆盖任务书指定项：**RAW 曝光解析边界**（有理数/分母 0/负数/越界 clamp/空值/非法值），
 * 全部要求"不抛异常 + 边界收敛"。纯 JVM（解析器不依赖 ExifInterface）。
 */
class AppGalleryPreviewRawMetadataTest {

    private val parser = AppGalleryPreviewRawMetadataParser

    // ---------------- 有理数解析 ----------------

    @Test
    fun parseRationalHandlesPlainAndFractionForms() {
        assertEquals(10.0, parser.parseRational("10")!!, 1e-9)
        assertEquals(0.008, parser.parseRational("1/125")!!, 1e-12)
        assertEquals(0.75, parser.parseRational("  3/4  ")!!, 1e-12)
        assertEquals(2.5, parser.parseRational("5/2")!!, 1e-12)
    }

    @Test
    fun parseRationalRejectsInvalidInput() {
        assertNull(parser.parseRational(null))
        assertNull(parser.parseRational(""))
        assertNull(parser.parseRational("   "))
        assertNull(parser.parseRational("abc"))
        assertNull(parser.parseRational("1/0"))
        assertNull(parser.parseRational("0/0"))
        assertNull(parser.parseRational("-1/2"))
        assertNull(parser.parseRational("1/-2"))
        assertNull(parser.parseRational("1/"))
        assertNull(parser.parseRational("/2"))
    }

    // ---------------- 快门时间 ----------------

    @Test
    fun exposureTimeClampsToBounds() {
        assertEquals(1.0 / 125.0, parser.parseExposureTimeSeconds("1/125")!!, 1e-12)
        assertEquals(EXPOSURE_TIME_MAX_SEC, parser.parseExposureTimeSeconds("600/1")!!, 1e-9)
        assertEquals(EXPOSURE_TIME_MIN_SEC, parser.parseExposureTimeSeconds("1/32000")!!, 1e-15)
        assertEquals(1.0, parser.parseExposureTimeSeconds("1/1")!!, 1e-9)
    }

    @Test
    fun exposureTimeRejectsZeroAndInvalid() {
        assertNull(parser.parseExposureTimeSeconds("0/1"))
        assertNull(parser.parseExposureTimeSeconds("0"))
        assertNull(parser.parseExposureTimeSeconds("abc"))
        assertNull(parser.parseExposureTimeSeconds(null))
    }

    @Test
    fun exposureTimeLabelIsHumanReadable() {
        assertEquals("1/125s", AppGalleryPreviewRawExposure(exposureTimeSec = 1.0 / 125.0).exposureTimeLabel())
        assertEquals("2.5s", AppGalleryPreviewRawExposure(exposureTimeSec = 2.5).exposureTimeLabel())
        assertEquals("2s", AppGalleryPreviewRawExposure(exposureTimeSec = 2.0).exposureTimeLabel())
        assertNull(AppGalleryPreviewRawExposure(exposureTimeSec = 0.0).exposureTimeLabel())
        assertNull(AppGalleryPreviewRawExposure().exposureTimeLabel())
    }

    // ---------------- ISO ----------------

    @Test
    fun isoParsesWithPrefixAndClamps() {
        assertEquals(6400, parser.parseIso("6400")!!)
        assertEquals(6400, parser.parseIso("ISO 6400")!!)
        assertEquals(100, parser.parseIso("iso100")!!)
        assertEquals(ISO_MAX, parser.parseIso("99999999")!!)
        assertEquals(6, parser.parseIso("6")!!)
        assertEquals(6, parser.parseIso("1")!!)
    }

    @Test
    fun isoRejectsZeroNegativeAndGarbage() {
        assertNull(parser.parseIso("0"))
        assertNull(parser.parseIso("-100"))
        assertNull(parser.parseIso("abc"))
        assertNull(parser.parseIso(""))
        assertNull(parser.parseIso(null))
    }

    // ---------------- 光圈 ----------------

    @Test
    fun apertureParsesWithPrefixAndClamps() {
        assertEquals(1.8, parser.parseAperture("f/1.8")!!, 1e-9)
        assertEquals(1.8, parser.parseAperture("F/1.8")!!, 1e-9)
        assertEquals(8.0, parser.parseAperture("8")!!, 1e-9)
        assertEquals(APERTURE_MIN, parser.parseAperture("0.1")!!, 1e-9)
        assertEquals(APERTURE_MAX, parser.parseAperture("500")!!, 1e-9)
    }

    @Test
    fun apertureRejectsZeroAndGarbage() {
        assertNull(parser.parseAperture("0"))
        assertNull(parser.parseAperture("f/0"))
        assertNull(parser.parseAperture("f/abc"))
        assertNull(parser.parseAperture(null))
    }

    // ---------------- 曝光补偿 ----------------

    @Test
    fun exposureBiasAcceptsSignAndClamps() {
        assertEquals(0.33, parser.parseExposureBiasEv("+0.33")!!, 1e-9)
        assertEquals(-2.0, parser.parseExposureBiasEv("-2")!!, 1e-9)
        assertEquals(-0.5, parser.parseExposureBiasEv("-1/2")!!, 1e-9)
        assertEquals(0.0, parser.parseExposureBiasEv("0")!!, 1e-9)
        assertEquals(EV_MAX, parser.parseExposureBiasEv("50")!!, 1e-9)
        assertEquals(EV_MIN, parser.parseExposureBiasEv("-50")!!, 1e-9)
    }

    @Test
    fun exposureBiasRejectsGarbage() {
        assertNull(parser.parseExposureBiasEv("+"))
        assertNull(parser.parseExposureBiasEv("-"))
        assertNull(parser.parseExposureBiasEv("abc"))
        assertNull(parser.parseExposureBiasEv(null))
    }

    @Test
    fun exposureStepsRoundToNearestStep() {
        assertEquals(0, AppGalleryPreviewRawExposure(exposureBiasEv = 0.4).toExposureSteps(1.0)!!)
        assertEquals(1, AppGalleryPreviewRawExposure(exposureBiasEv = 0.6).toExposureSteps(1.0)!!)
        assertEquals(-2, AppGalleryPreviewRawExposure(exposureBiasEv = -1.6).toExposureSteps(1.0)!!)
        assertEquals(3, AppGalleryPreviewRawExposure(exposureBiasEv = 1.0).toExposureSteps(1.0 / 3.0)!!)
        assertNull(AppGalleryPreviewRawExposure(exposureBiasEv = 1.0).toExposureSteps(0.0))
        assertNull(AppGalleryPreviewRawExposure().toExposureSteps(1.0))
    }

    // ---------------- 焦距 / 尺寸 ----------------

    @Test
    fun focalLengthParsesUnitAndClamps() {
        assertEquals(6.0, parser.parseFocalLengthMm("6.0 mm")!!, 1e-9)
        assertEquals(6.0, parser.parseFocalLengthMm("6000/1000")!!, 1e-9)
        assertEquals(24.0, parser.parseFocalLengthMm("24")!!, 1e-9)
        assertEquals(FOCAL_MAX_MM, parser.parseFocalLengthMm("99999")!!, 1e-9)
        assertNull(parser.parseFocalLengthMm("0"))
        assertNull(parser.parseFocalLengthMm("abc"))
    }

    @Test
    fun dimensionParsesAndClamps() {
        assertEquals(4032, parser.parseDimension("4032"))
        assertEquals(DIMENSION_MAX, parser.parseDimension("99999999"))
        assertEquals(0, parser.parseDimension("0"))
        assertEquals(0, parser.parseDimension("-5"))
        assertEquals(0, parser.parseDimension("abc"))
        assertEquals(0, parser.parseDimension(null))
    }

    // ---------------- 标签表 ----------------

    @Test
    fun fromTagsReadsFullTagSet() {
        val exposure = parser.fromTags(
            mapOf(
                TAG_EXPOSURE_TIME to "1/250",
                TAG_PHOTOGRAPHIC_SENSITIVITY to "200",
                TAG_F_NUMBER to "2.8",
                TAG_EXPOSURE_BIAS to "-1/3",
                TAG_FOCAL_LENGTH to "18.0 mm",
                TAG_PIXEL_X_DIMENSION to "6000",
                TAG_PIXEL_Y_DIMENSION to "4000",
            ),
        )
        assertEquals(1.0 / 250.0, exposure.exposureTimeSec!!, 1e-12)
        assertEquals(200, exposure.iso)
        assertEquals(2.8, exposure.aperture!!, 1e-9)
        assertEquals(-1.0 / 3.0, exposure.exposureBiasEv!!, 1e-9)
        assertEquals(18.0, exposure.focalLengthMm!!, 1e-9)
        assertEquals(6000, exposure.widthPx)
        assertEquals(4000, exposure.heightPx)
        assertTrue(exposure.hasCoreExposure)
        assertFalse(exposure.isEmpty)
        assertNotNull(exposure.summary())
    }

    @Test
    fun fromTagsFallsBackToLegacyIsoTag() {
        val exposure = parser.fromTags(mapOf(TAG_ISO to "800"))
        assertEquals(800, exposure.iso)
    }

    @Test
    fun fromTagsUsesMaxOfBothDimensionTags() {
        val exposure = parser.fromTags(
            mapOf(
                TAG_PIXEL_X_DIMENSION to "6000",
                TAG_IMAGE_WIDTH to "3000",
                TAG_PIXEL_Y_DIMENSION to "1",
                TAG_IMAGE_LENGTH to "4000",
            ),
        )
        assertEquals(6000, exposure.widthPx)
        assertEquals(4000, exposure.heightPx)
    }

    @Test
    fun fromTagsWithNothingKeepsEverythingUnknown() {
        val exposure = parser.fromTags(emptyMap())
        assertTrue(exposure.isEmpty)
        assertFalse(exposure.hasCoreExposure)
        assertNull(exposure.summary())
        assertEquals(0, exposure.widthPx)
        assertEquals(0, exposure.heightPx)
    }

    @Test
    fun fromTagsIgnoresGarbageValues() {
        val exposure = parser.fromTags(
            mapOf(
                TAG_EXPOSURE_TIME to "not-a-number",
                TAG_ISO to "0",
                TAG_F_NUMBER to "0/0",
                TAG_FOCAL_LENGTH to "",
            ),
        )
        assertNull(exposure.exposureTimeSec)
        assertNull(exposure.iso)
        assertNull(exposure.aperture)
        assertNull(exposure.focalLengthMm)
        assertTrue(exposure.isEmpty)
    }
}
