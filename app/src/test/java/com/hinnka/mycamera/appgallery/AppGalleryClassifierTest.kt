package com.hinnka.mycamera.appgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 类型分类：覆盖工程各类产物（普通 / RAW / 多帧 / HDR / 超分 / 星轨 / LUT / Log 视频 /
 * 实况 / 全景 / GIF / 视频 / 连拍），并验证规则优先级与规则表 clamp。
 * 纯 JVM 测试。
 */
class AppGalleryClassifierTest {

    // ── ① 持久化值优先 ──────────────────────────────────────────

    @Test
    fun `持久化 processingType 优先级最高`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0001.dng",
            relativeDirTag = "superres",
            hasDng = true,
            persistedProcessingType = "HDR",
        )
        assertEquals(AppGalleryCategory.HDR, AppGalleryClassifier.classify(input))
    }

    @Test
    fun `持久化值支持中文标签且非法值回落 null`() {
        assertEquals(AppGalleryCategory.SUPER_RES, AppGalleryCategory.fromPersistedName("超分结果"))
        assertEquals(AppGalleryCategory.RAW_DNG, AppGalleryCategory.fromPersistedName("raw_dng"))
        assertNull(AppGalleryCategory.fromPersistedName("not_a_category"))
        assertNull(AppGalleryCategory.fromPersistedName("   "))
    }

    @Test
    fun `normalizePersisted 把未知值收敛为普通照片`() {
        assertEquals("NORMAL", AppGalleryClassifier.normalizePersisted(null))
        assertEquals("NORMAL", AppGalleryClassifier.normalizePersisted("乱写的值"))
        assertEquals("GIF", AppGalleryClassifier.normalizePersisted("gif"))
    }

    // ── ② 目录规则 ──────────────────────────────────────────────

    @Test
    fun `目录规则命中各类输出目录`() {
        assertEquals(
            AppGalleryCategory.SUPER_RES,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "out.jpg", relativeDirTag = "multiframesr")),
        )
        assertEquals(
            AppGalleryCategory.SUPER_RES,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "out.jpg", relativeDirTag = "superres")),
        )
        assertEquals(
            AppGalleryCategory.MULTI_FRAME,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "out.jpg", relativeDirTag = "stack")),
        )
        assertEquals(
            AppGalleryCategory.ASTRO_TRAIL,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "out.jpg", relativeDirTag = "light-painting")),
        )
        assertEquals(
            AppGalleryCategory.PANORAMA,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "out.jpg", relativeDirTag = "pano")),
        )
    }

    @Test
    fun `目录名复合形态做包含匹配`() {
        assertEquals(AppGalleryCategory.SUPER_RES, AppGalleryDirectoryRules.categoryForDir("photo_superres_2026"))
        assertEquals(AppGalleryCategory.HDR, AppGalleryDirectoryRules.categoryForDir("hdr_bracket_01"))
        assertNull(AppGalleryDirectoryRules.categoryForDir("photos"))
        assertNull(AppGalleryDirectoryRules.categoryForDir(null))
    }

    @Test
    fun `log 目录不会把图片误判为 Log 视频`() {
        val image = AppGalleryClassifyInput(fileName = "IMG_0001.jpg", relativeDirTag = "log")
        assertEquals(AppGalleryCategory.NORMAL, AppGalleryClassifier.classify(image))

        val video = AppGalleryClassifyInput(
            fileName = "video.mp4",
            relativeDirTag = "log",
            isVideo = true,
            mimeType = "video/mp4",
        )
        assertEquals(AppGalleryCategory.LOG_VIDEO, AppGalleryClassifier.classify(video))
    }

    // ── ③ 文件名 / MIME 规则 ────────────────────────────────────

    @Test
    fun `文件名规则覆盖常见导出命名`() {
        assertEquals(
            AppGalleryCategory.SUPER_RES,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0007_superres.jpg")),
        )
        assertEquals(
            AppGalleryCategory.MULTI_FRAME,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "PS_STACK_median_0003.jpg")),
        )
        assertEquals(
            AppGalleryCategory.HDR,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0009_ultrahdr.jpg")),
        )
        assertEquals(
            AppGalleryCategory.ASTRO_TRAIL,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "startrail_0002.jpg")),
        )
        assertEquals(
            AppGalleryCategory.LUT_FILM,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0011_spectral.jpg")),
        )
        assertEquals(
            AppGalleryCategory.BURST,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0012_burst_3.jpg")),
        )
    }

    @Test
    fun `Log 视频文件名规则只对视频生效`() {
        val video = AppGalleryClassifyInput(fileName = "log_0001.mp4", isVideo = true)
        assertEquals(AppGalleryCategory.LOG_VIDEO, AppGalleryClassifier.classify(video))
        val image = AppGalleryClassifyInput(fileName = "log_0001.jpg", isVideo = false)
        assertEquals(AppGalleryCategory.NORMAL, AppGalleryClassifier.classify(image))
    }

    @Test
    fun `扩展名与 MIME 判定 RAW 和 GIF`() {
        assertEquals(
            AppGalleryCategory.RAW_DNG,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0001.dng")),
        )
        assertEquals(
            AppGalleryCategory.RAW_DNG,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_0001.arw")),
        )
        assertEquals(
            AppGalleryCategory.GIF,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "clip.gif")),
        )
        assertEquals(
            AppGalleryCategory.GIF,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "noext", mimeType = "image/gif")),
        )
        assertEquals(
            AppGalleryCategory.RAW_DNG,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "noext", mimeType = "image/x-adobe-dng")),
        )
    }

    // ── ④ 元数据字段（含优先级） ────────────────────────────────

    @Test
    fun `HDR 优先于 RAW（HDR 合成常带 DNG）`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0001.jpg",
            hasDng = true,
            manualHdrEffectEnabled = true,
        )
        assertEquals(AppGalleryCategory.HDR, AppGalleryClassifier.classify(input))
    }

    @Test
    fun `增益图与动态范围档案判为 HDR`() {
        assertEquals(
            AppGalleryCategory.HDR,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", hasEmbeddedGainmap = true)),
        )
        assertEquals(
            AppGalleryCategory.HDR,
            AppGalleryClassifier.classify(
                AppGalleryClassifyInput(fileName = "a.jpg", dynamicRangeProfile = "HLG")
            ),
        )
    }

    @Test
    fun `多帧曝光帧数大于 1 判为多帧合成`() {
        assertEquals(
            AppGalleryCategory.MULTI_FRAME,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", multipleExposureFrameCount = 8)),
        )
        // 边界：恰好 1 帧不算多帧合成
        assertEquals(
            AppGalleryCategory.NORMAL,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", multipleExposureFrameCount = 1)),
        )
    }

    @Test
    fun `视频按 Log 属性分流`() {
        assertEquals(
            AppGalleryCategory.LOG_VIDEO,
            AppGalleryClassifier.classify(
                AppGalleryClassifyInput(
                    fileName = "video.mp4",
                    isVideo = true,
                    mimeType = "video/mp4",
                    customProperties = mapOf("LOG_PROFILE" to "S-Log3"),
                )
            ),
        )
        assertEquals(
            AppGalleryCategory.VIDEO,
            AppGalleryClassifier.classify(
                AppGalleryClassifyInput(fileName = "video.mp4", isVideo = true, mimeType = "video/mp4")
            ),
        )
    }

    @Test
    fun `实况照片与 LUT 元数据判定`() {
        assertEquals(
            AppGalleryCategory.LIVE_PHOTO,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", isMotionPhoto = true)),
        )
        assertEquals(
            AppGalleryCategory.RAW_DNG,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", hasDng = true)),
        )
        // 关闭 RAW 归类开关后，带 DNG 的条目退化为普通照片 → 胶片元数据优先
        assertEquals(
            AppGalleryCategory.LUT_FILM,
            AppGalleryClassifier.classify(
                AppGalleryClassifyInput(fileName = "a.jpg", hasDng = true, filmStockId = "kodak_portra"),
                AppGalleryClassifierRules(treatDngAsRawCategory = false),
            ),
        )
    }

    @Test
    fun `连拍元数据判为连拍`() {
        assertEquals(
            AppGalleryCategory.BURST,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "a.jpg", hasBurstFrames = true)),
        )
    }

    @Test
    fun `无法判定的条目回落到普通照片`() {
        assertEquals(AppGalleryCategory.NORMAL, AppGalleryClassifier.classify(AppGalleryClassifyInput()))
        assertEquals(
            AppGalleryCategory.NORMAL,
            AppGalleryClassifier.classify(AppGalleryClassifyInput(fileName = "IMG_20261016_120000.jpg")),
        )
    }

    @Test
    fun `关闭实况与 HDR 开关后判定随之改变`() {
        val motionPhoto = AppGalleryClassifyInput(fileName = "a.jpg", isMotionPhoto = true)
        assertEquals(
            AppGalleryCategory.NORMAL,
            AppGalleryClassifier.classify(motionPhoto, AppGalleryClassifierRules(treatMotionPhotoAsLivePhoto = false)),
        )
        val gainmap = AppGalleryClassifyInput(fileName = "a.jpg", hasEmbeddedGainmap = true)
        assertEquals(
            AppGalleryCategory.NORMAL,
            AppGalleryClassifier.classify(gainmap, AppGalleryClassifierRules(treatGainmapAsHdr = false)),
        )
    }

    // ── 规则表 clamp ────────────────────────────────────────────

    @Test
    fun `规则表 clamp 去掉空 token 与超长 token 并归一化目录键`() {
        val rules = AppGalleryClassifierRules(
            directoryRules = mapOf(
                "  SuperRes  " to AppGalleryCategory.SUPER_RES,
                "" to AppGalleryCategory.HDR,
                "star-trail" to AppGalleryCategory.ASTRO_TRAIL,
            ),
            nameRules = listOf(
                AppGalleryNameRule(AppGalleryCategory.GIF, ".gif"),
                AppGalleryNameRule(AppGalleryCategory.HDR, ""),
                AppGalleryNameRule(AppGalleryCategory.HDR, "x".repeat(AppGalleryNameRules.MAX_TOKEN_LENGTH + 10)),
            ),
        )
        val clamped = AppGalleryClassifierRules.clamp(rules)
        assertTrue(clamped.directoryRules.containsKey("superres"))
        assertTrue(clamped.directoryRules.containsKey("star_trail"))
        assertTrue(!clamped.directoryRules.containsKey(""))
        assertEquals(1, clamped.nameRules.size)
        assertEquals(".gif", clamped.nameRules.first().token)
    }

    @Test
    fun `批量分类保持输入顺序`() {
        val inputs = listOf(
            AppGalleryClassifyInput(fileName = "a.dng"),
            AppGalleryClassifyInput(fileName = "b.gif"),
            AppGalleryClassifyInput(fileName = "c.jpg", isMotionPhoto = true),
        )
        assertEquals(
            listOf(AppGalleryCategory.RAW_DNG, AppGalleryCategory.GIF, AppGalleryCategory.LIVE_PHOTO),
            AppGalleryClassifier.classifyAll(inputs),
        )
    }

    // ── ①b customProperties 声明通道（无需数据库列） ──────────────

    @Test
    fun `customProperties 声明优先于目录与文件名规则`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0002.gif",
            relativeDirTag = "superres",
            customProperties = mapOf(AppGalleryClassifier.CUSTOM_PROPERTY_PROCESSING_TYPE to "LUT_FILM"),
        )
        assertEquals(AppGalleryCategory.LUT_FILM, AppGalleryClassifier.classify(input))
    }

    @Test
    fun `DB 补丁列优先于 customProperties 声明`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0003.jpg",
            persistedProcessingType = "HDR",
            customProperties = mapOf(AppGalleryClassifier.CUSTOM_PROPERTY_PROCESSING_TYPE to "PANORAMA"),
        )
        assertEquals(AppGalleryCategory.HDR, AppGalleryClassifier.classify(input))
    }

    @Test
    fun `customProperties 非法值静默回落到规则判定`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0004.dng",
            customProperties = mapOf(AppGalleryClassifier.CUSTOM_PROPERTY_PROCESSING_TYPE to "乱写的值"),
        )
        assertEquals(AppGalleryCategory.RAW_DNG, AppGalleryClassifier.classify(input))
    }

    @Test
    fun `customProperties 可声明目录标记并参与目录规则`() {
        val input = AppGalleryClassifyInput(
            fileName = "IMG_0005.jpg",
            customProperties = mapOf(AppGalleryClassifier.CUSTOM_PROPERTY_DIR_TAG to "superres"),
        )
        assertEquals(AppGalleryCategory.SUPER_RES, AppGalleryClassifier.classify(input))
    }
}
