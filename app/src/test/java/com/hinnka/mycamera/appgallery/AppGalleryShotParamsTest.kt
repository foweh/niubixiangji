package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 拍摄参数（EXIF 概览）单元测试（相册项 4）：格式整形、脏数据净化、行列表与空态。
 * 纯 JVM 测试（不触碰 Android API；EXIF 解析发生在工程既有的 `MediaMetadata` 内，不在本层）。
 */
class AppGalleryShotParamsTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    // ── 快门 ────────────────────────────────────────────────────

    @Test
    fun `快门格式覆盖分数 小数 整秒与不可解析`() {
        assertEquals("1/250 s", AppGalleryShotParams.formatShutter("1/250"))
        assertEquals("1/200 s", AppGalleryShotParams.formatShutter("0.005"))
        assertEquals("2 s", AppGalleryShotParams.formatShutter("2"))
        assertEquals("2 s", AppGalleryShotParams.formatShutter("2s"))
        assertEquals("1 s", AppGalleryShotParams.formatShutter("1/1"))
        // 无法解析：原样展示（EXIF 字符串格式多样，不猜）
        assertEquals("unknown", AppGalleryShotParams.formatShutter("unknown"))
        assertEquals("0", AppGalleryShotParams.formatShutter("0"))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.formatShutter(null))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.formatShutter("   "))
    }

    // ── 光圈 ────────────────────────────────────────────────────

    @Test
    fun `光圈格式统一为 f 记法`() {
        assertEquals("f/1.8", AppGalleryShotParams.formatAperture("1.8"))
        assertEquals("f/1.8", AppGalleryShotParams.formatAperture("f/1.8"))
        assertEquals("f/1.8", AppGalleryShotParams.formatAperture("F/1.8"))
        assertEquals("f/1.8", AppGalleryShotParams.formatAperture("18/10"))
        assertEquals("f/2", AppGalleryShotParams.formatAperture("2.0"))
        assertEquals("bad", AppGalleryShotParams.formatAperture("bad"))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.formatAperture(null))
    }

    // ── 焦距 / 曝光补偿 ─────────────────────────────────────────

    @Test
    fun `焦距组合展示且等效与物理相同时不重复`() {
        val both = AppGalleryShotParams.of(focalLength = "24", focalLength35mm = "36")
        assertEquals("24mm · 等效 36mm", both.focalLabel())

        val same = AppGalleryShotParams.of(focalLength = "24.0", focalLength35mm = "24")
        assertEquals("24mm", same.focalLabel())

        val only35 = AppGalleryShotParams.of(focalLength35mm = "36mm")
        assertEquals("等效 36mm", only35.focalLabel())

        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of().focalLabel())
        assertNull(AppGalleryShotParams.normalizeMillimeters("0"))
        assertNull(AppGalleryShotParams.normalizeMillimeters("abc"))
    }

    @Test
    fun `曝光补偿符号 零值与非有限值`() {
        assertEquals("+0.3 EV", AppGalleryShotParams.of(exposureBias = 0.3f).exposureBiasLabel())
        assertEquals("-1 EV", AppGalleryShotParams.of(exposureBias = -1f).exposureBiasLabel())
        assertEquals("±0 EV", AppGalleryShotParams.of(exposureBias = 0f).exposureBiasLabel())
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of().exposureBiasLabel())
        assertEquals(
            AppGalleryShotParams.PLACEHOLDER,
            AppGalleryShotParams.of(exposureBias = Float.NaN).exposureBiasLabel(),
        )
    }

    // ── 坐标 / 时间 / 体积 ──────────────────────────────────────

    @Test
    fun `坐标校验与半球展示`() {
        val params = AppGalleryShotParams.of(latitude = 43.8123, longitude = 125.3456)
        assertTrue(params.hasGps())
        assertEquals("43.812300, 125.345600", params.coordinateLabel())
        assertEquals("43.8123° N, 125.3456° E", params.gpsLabel(4))
        assertEquals("43.81, 125.35", params.coordinateLabel(2))

        val southWest = AppGalleryShotParams.of(latitude = -33.8688, longitude = -70.6693)
        assertEquals("33.8688° S, 70.6693° W", southWest.gpsLabel(4))

        // 越界/半对/缺失 → 视为无定位
        assertFalse(AppGalleryShotParams.of(latitude = 91.0, longitude = 10.0).hasGps())
        assertFalse(AppGalleryShotParams.of(latitude = 20.0, longitude = 181.0).hasGps())
        assertFalse(AppGalleryShotParams.of(latitude = 20.0).hasGps())
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of().coordinateLabel())
        assertFalse(AppGalleryShotParams.isValidLatitude(Double.NaN))
    }

    @Test
    fun `拍摄时间按给定时区格式化且坏值回落`() {
        val params = AppGalleryShotParams.of(capturedAt = 1_700_000_000_000L)
        assertEquals("2023-11-15 06:13:20", params.capturedAtLabel(shanghai))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of(capturedAt = 0L).capturedAtLabel(shanghai))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of(capturedAt = -5L).capturedAtLabel(shanghai))
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of().capturedAtLabel(shanghai))
    }

    @Test
    fun `海拔与体积文案`() {
        assertEquals("520 m", AppGalleryShotParams.of(altitude = 520.0).altitudeLabel())
        assertEquals("12.5 m", AppGalleryShotParams.of(altitude = 12.5).altitudeLabel())
        assertEquals("1.0 KB", AppGalleryShotParams.of(sizeBytes = 1024L).sizeLabel())
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of(sizeBytes = 0L).sizeLabel())
        assertEquals(AppGalleryShotParams.PLACEHOLDER, AppGalleryShotParams.of().altitudeLabel())
    }

    // ── 净化 ────────────────────────────────────────────────────

    @Test
    fun `构造时净化脏数据`() {
        val dirty = AppGalleryShotParams.of(
            iso = -100,
            shutterSpeed = "  ",
            aperture = "",
            exposureBias = Float.POSITIVE_INFINITY,
            capturedAt = 0L,
            latitude = 200.0,
            longitude = 300.0,
            altitude = Double.NaN,
            locationName = "   ",
            sizeBytes = -1L,
        )
        assertNull(dirty.iso)
        assertNull(dirty.shutterSpeed)
        assertNull(dirty.aperture)
        assertNull(dirty.exposureBias)
        assertNull(dirty.capturedAt)
        assertNull(dirty.latitude)
        assertNull(dirty.longitude)
        assertNull(dirty.altitude)
        assertNull(dirty.locationName)
        assertNull(dirty.sizeBytes)
        assertTrue(dirty.isEmpty)
    }

    @Test
    fun `iso 与文本字段去空白`() {
        val params = AppGalleryShotParams.of(
            iso = 400,
            shutterSpeed = " 1/250 ",
            aperture = " 1.8 ",
            focalLength = " 24 ",
            locationName = " 长春 ",
            cameraId = " back ",
            colorSpace = " SRGB ",
            software = " PhotonCamera ",
            dimensions = " 4032x3024 ",
        )
        assertEquals(400, params.iso)
        assertEquals("1/250", params.shutterSpeed)
        assertEquals("1.8", params.aperture)
        assertEquals("24", params.focalLength)
        assertEquals("长春", params.locationName)
        assertEquals("back", params.cameraId)
        assertEquals("SRGB", params.colorSpace)
        assertEquals("PhotonCamera", params.software)
        assertEquals("4032x3024", params.dimensions)
        assertFalse(params.isEmpty)
    }

    // ── 行列表 ──────────────────────────────────────────────────

    @Test
    fun `行列表只含有值项且顺序稳定`() {
        val params = AppGalleryShotParams.of(
            iso = 100,
            dimensions = "4032x3024",
            capturedAt = 1_700_000_000_000L,
        )
        val rows = params.rows(shanghai)
        assertEquals(listOf("拍摄时间", "ISO", "分辨率"), rows.map { it.label })
        assertEquals("2023-11-15 06:13:20", rows[0].value)
        assertEquals("ISO 100", rows[1].value)
        assertEquals("4032x3024", rows[2].value)
    }

    @Test
    fun `全字段的行列表顺序固定`() {
        val params = AppGalleryShotParams.of(
            iso = 200,
            shutterSpeed = "1/125",
            aperture = "2.0",
            focalLength = "50",
            focalLength35mm = "75",
            exposureBias = -0.7f,
            capturedAt = 1_700_000_000_000L,
            latitude = 43.8,
            longitude = 125.3,
            altitude = 210.0,
            locationName = "长春",
            cameraId = "0",
            colorSpace = "SRGB",
            software = "PhotonCamera",
            dimensions = "4032x3024",
            sizeBytes = 2_097_152L,
        )
        assertEquals(
            listOf("拍摄时间", "光圈", "快门", "ISO", "焦距", "曝光补偿", "地点", "坐标", "海拔", "分辨率", "体积", "色彩空间", "相机", "软件"),
            params.rows(shanghai).map { it.label },
        )
        assertEquals(14, params.rows(shanghai).size)
    }

    @Test
    fun `空参数不产生任何行`() {
        assertTrue(AppGalleryShotParams.of().rows(shanghai).isEmpty())
        assertTrue(AppGalleryShotParams().isEmpty)
        assertTrue(AppGalleryShotParams.of(dimensions = "0x0").rows(shanghai).isEmpty())
    }

    @Test
    fun `trimNumber 去尾零且不产生科学计数`() {
        assertEquals("2", AppGalleryShotParams.trimNumber(2.0))
        assertEquals("1.8", AppGalleryShotParams.trimNumber(1.80))
        assertEquals("0.3", AppGalleryShotParams.trimNumber(0.3))
        assertEquals("0", AppGalleryShotParams.trimNumber(0.001))
        assertEquals("0", AppGalleryShotParams.trimNumber(Double.NaN))
        assertEquals("1000000", AppGalleryShotParams.trimNumber(1_000_000.0))
    }
}
