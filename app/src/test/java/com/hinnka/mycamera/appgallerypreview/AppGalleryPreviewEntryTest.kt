package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览入口 / 跨代理数据源 / 编辑 intent 契约测试（相册第 39/40 项 + 管理域跳转契约）。
 *
 * 这些契约是"两个代理可独立落地"的关键：键名、默认值、越界收敛都必须稳定。纯 JVM。
 */
class AppGalleryPreviewEntryTest {

    private val contract = AppGalleryPreviewEntryContract
    private val edit = AppGalleryPreviewEditContract

    // ---------------- 请求规范化 ----------------

    @Test
    fun normalizedClampsIndexIntoTotal() {
        val high = AppGalleryPreviewRequest(mediaUri = "u", mediaKind = AppGalleryPreviewMediaKind.IMAGE, index = 99, total = 3)
        assertEquals(2, high.normalized().index)
        assertEquals(3, high.normalized().total)

        val negative = AppGalleryPreviewRequest(mediaUri = "u", mediaKind = AppGalleryPreviewMediaKind.IMAGE, index = -3, total = 5)
        assertEquals(0, negative.normalized().index)
    }

    @Test
    fun normalizedRepairsNonPositiveTotal() {
        val request = AppGalleryPreviewRequest(mediaUri = "u", mediaKind = AppGalleryPreviewMediaKind.IMAGE, index = 4, total = 0)
        assertEquals(1, request.normalized().total)
        assertEquals(0, request.normalized().index)
    }

    // ---------------- 序列化往返 ----------------

    @Test
    fun extrasRoundTripPreservesEveryField() {
        val original = AppGalleryPreviewRequest(
            mediaUri = "content://media/external/images/101",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            mediaType = "image/jpeg",
            mediaId = "101",
            index = 1,
            total = 3,
            sourceIds = listOf("100", "101", "102"),
            sourceUris = listOf("content://m/100", "content://m/101", "content://m/102"),
            sourceTypes = listOf("image/jpeg", "image/jpeg", "video/mp4"),
            allowEdit = false,
            allowQuickEdit = false,
            startSlideshow = true,
            slideshowEnabled = true,
            compareOriginalUri = "content://m/101_original",
            compareProcessedUri = "content://m/101",
            rawExportEnabled = false,
            source = "appgallery",
            favorite = true,
        )

        val restored = AppGalleryPreviewRequest.fromExtras(original.toExtras())
        assertNotNull(restored)
        restored!!
        assertEquals(original.mediaUri, restored.mediaUri)
        assertEquals(original.mediaType, restored.mediaType)
        assertEquals(original.mediaId, restored.mediaId)
        assertEquals(original.index, restored.index)
        assertEquals(original.total, restored.total)
        assertEquals(original.sourceIds, restored.sourceIds)
        assertEquals(original.sourceUris, restored.sourceUris)
        assertEquals(original.sourceTypes, restored.sourceTypes)
        assertFalse(restored.allowEdit)
        assertFalse(restored.allowQuickEdit)
        assertTrue(restored.startSlideshow)
        assertTrue(restored.slideshowEnabled)
        assertEquals(original.compareOriginalUri, restored.compareOriginalUri)
        assertEquals(original.compareProcessedUri, restored.compareProcessedUri)
        assertFalse(restored.rawExportEnabled)
        assertEquals(original.source, restored.source)
        assertEquals(original.favorite, restored.favorite)
        assertEquals(AppGalleryPreviewMediaKind.IMAGE, restored.mediaKind)
    }

    @Test
    fun extrasRoundTripKeepsListIndexesAligned() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "u0",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            index = 0,
            total = 3,
            sourceUris = listOf("u0", "", "u2"),
        )
        val restored = AppGalleryPreviewRequest.fromExtras(request.toExtras())!!
        assertEquals(3, restored.sourceUris.size)
        assertEquals("u0", restored.sourceUris[0])
        assertEquals("", restored.sourceUris[1])
        assertEquals("u2", restored.sourceUris[2])
    }

    @Test
    fun fromExtrasWithoutUriReturnsNull() {
        assertNull(AppGalleryPreviewRequest.fromExtras(emptyMap()))
        assertNull(AppGalleryPreviewRequest.fromExtras(mapOf(contract.EXTRA_MEDIA_URI to "   ")))
    }

    @Test
    fun fromExtrasToleratesGarbageNumbersAndBooleans() {
        val restored = AppGalleryPreviewRequest.fromExtras(
            mapOf(
                contract.EXTRA_MEDIA_URI to "content://m/1.jpg",
                contract.EXTRA_INDEX to "abc",
                contract.EXTRA_TOTAL to "",
                contract.EXTRA_ALLOW_EDIT to "maybe",
            ),
        )!!
        assertEquals(0, restored.index)
        assertEquals(1, restored.total)
        assertTrue(restored.allowEdit)
        assertEquals(AppGalleryPreviewMediaKind.IMAGE, restored.mediaKind)
    }

    @Test
    fun fromExtrasDropsProcessedWithoutOriginal() {
        val restored = AppGalleryPreviewRequest.fromExtras(
            mapOf(
                contract.EXTRA_MEDIA_URI to "content://m/1.jpg",
                contract.EXTRA_COMPARE_PROCESSED_URI to "content://m/1_processed.jpg",
            ),
        )!!
        assertNull(restored.compareProcessedUri)
    }

    @Test
    fun fromExtrasKeepsProcessedWhenOriginalPresent() {
        val restored = AppGalleryPreviewRequest.fromExtras(
            mapOf(
                contract.EXTRA_MEDIA_URI to "content://m/1.jpg",
                contract.EXTRA_COMPARE_ORIGINAL_URI to "content://m/1_original.jpg",
                contract.EXTRA_COMPARE_PROCESSED_URI to "content://m/1_processed.jpg",
            ),
        )!!
        assertEquals("content://m/1_original.jpg", restored.compareOriginalUri)
        assertEquals("content://m/1_processed.jpg", restored.compareProcessedUri)
    }

    @Test
    fun listSeparatorIsUnitSeparator() {
        assertEquals("\u001F", contract.LIST_SEPARATOR)
    }

    // ---------------- 跨代理数据源 ----------------

    @Test
    fun mediaSourceFromRequestWalksNeighbors() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/101",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            mediaType = "image/jpeg",
            mediaId = "101",
            index = 1,
            total = 3,
            sourceIds = listOf("100", "101", "102"),
            sourceUris = listOf("content://m/100", "content://m/101", "content://m/102"),
            sourceTypes = listOf("image/jpeg", "image/jpeg", "video/mp4"),
        )
        val source = AppGalleryPreviewMediaSource.from(request)

        assertEquals(3, source.count)
        assertEquals("content://m/100", source.uriAt(0))
        assertEquals("content://m/101", source.uriAt(1))
        assertEquals("content://m/102", source.uriAt(2))
        assertEquals("100", source.mediaIdAt(0))
        assertEquals("101", source.mediaIdAt(1))
        assertEquals("image/jpeg", source.mimeAt(0))
        assertEquals(AppGalleryPreviewMediaKind.IMAGE, source.kindAt(0))
        assertEquals(AppGalleryPreviewMediaKind.VIDEO, source.kindAt(2))
        assertEquals("content://m/102", source.bookmarkTargetAt(2))
    }

    @Test
    fun mediaSourceFromRequestIsSafeAtBounds() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/1",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            index = 0,
            total = 2,
        )
        val source = AppGalleryPreviewMediaSource.from(request)
        assertEquals(2, source.count)
        assertEquals("content://m/1", source.uriAt(0))
        assertNull(source.uriAt(1))
        assertNull(source.uriAt(-1))
        assertNull(source.uriAt(99))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, source.kindAt(99))
        assertNull(source.mediaIdAt(0))
    }

    @Test
    fun mediaSourceFallsBackToMediaUriWhenListIsBlank() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/current",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            index = 1,
            total = 3,
            sourceUris = listOf("content://m/a", "", "content://m/c"),
        )
        val source = AppGalleryPreviewMediaSource.from(request)
        assertEquals("content://m/a", source.uriAt(0))
        assertEquals("content://m/current", source.uriAt(1))
        assertEquals("content://m/c", source.uriAt(2))
    }

    @Test
    fun mediaSourceSingleExposesOnlyCurrentItem() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/7.mp4",
            mediaKind = AppGalleryPreviewMediaKind.VIDEO,
            mediaType = "video/mp4",
            mediaId = "7",
            index = 0,
            total = 9,
        )
        val source = AppGalleryPreviewMediaSource.single(request)
        assertEquals(9, source.count)
        assertEquals("content://m/7.mp4", source.uriAt(0))
        assertNull(source.uriAt(1))
        assertEquals(AppGalleryPreviewMediaKind.VIDEO, source.kindAt(0))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, source.kindAt(1))
        assertEquals("video/mp4", source.mimeAt(0))
        assertEquals("7", source.mediaIdAt(0))
    }

    @Test
    fun mediaSourceEmptyIsStable() {
        assertEquals(0, AppGalleryPreviewMediaSource.EMPTY.count)
        assertNull(AppGalleryPreviewMediaSource.EMPTY.uriAt(0))
        assertEquals(AppGalleryPreviewMediaKind.UNSUPPORTED, AppGalleryPreviewMediaSource.EMPTY.kindAt(0))
    }

    @Test
    fun mediaSourceExposesCompareOriginalOnlyForCurrentItem() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/2",
            mediaKind = AppGalleryPreviewMediaKind.IMAGE,
            index = 1,
            total = 3,
            sourceUris = listOf("content://m/1", "content://m/2", "content://m/3"),
            compareOriginalUri = "content://m/2_original",
        )
        val source = AppGalleryPreviewMediaSource.from(request)
        assertEquals("content://m/2_original", source.compareOriginalUriAt(1))
        assertNull(source.compareOriginalUriAt(0))
        assertNull(source.compareOriginalUriAt(2))
    }

    // ---------------- 快捷操作上下文工厂 ----------------

    @Test
    fun actionContextFactoryBridgesSourceAndCapabilities() {
        val request = AppGalleryPreviewRequest(
            mediaUri = "content://m/2.mp4",
            mediaKind = AppGalleryPreviewMediaKind.VIDEO,
            index = 0,
            total = 1,
            sourceUris = listOf("content://m/2.mp4"),
        )
        val source = AppGalleryPreviewMediaSource.from(request)
        val context = AppGalleryPreviewActionContextFactory.create(
            source = source,
            index = 0,
            rawExportEnabled = false,
            slideshowEnabled = true,
            isFavorite = false,
            supportsFavorite = true,
            supportsDelete = false,
            supportsShare = true,
        )
        assertEquals(AppGalleryPreviewMediaKind.VIDEO, context.kind)
        assertEquals(1, context.itemCount)
        assertTrue(context.supportsFavorite)
        assertFalse(context.supportsDelete)
        assertFalse(context.supportsRawExport)
        assertEquals(false, context.isFavorite)
    }

    // ---------------- 编辑 intent 契约 ----------------

    @Test
    fun editExtrasCarriesUriTypePresetAndSource() {
        val extras = edit.editExtras(
            mediaUri = "content://m/1.dng",
            mediaType = null,
            mediaId = "1",
            originalUri = "content://m/1_original.dng",
            preset = AppGalleryPreviewQuickEditPreset.CROP,
        )!!
        assertEquals("content://m/1.dng", extras[edit.EXTRA_MEDIA_URI])
        assertEquals("RAW", extras[edit.EXTRA_MEDIA_TYPE])
        assertEquals("1", extras[edit.EXTRA_MEDIA_ID])
        assertEquals("content://m/1_original.dng", extras[edit.EXTRA_ORIGINAL_URI])
        assertEquals("CROP", extras[edit.EXTRA_EDIT_PRESET])
        assertEquals(edit.SOURCE_VALUE, extras[edit.EXTRA_SOURCE])
    }

    @Test
    fun editExtrasRejectsBlankUri() {
        assertNull(edit.editExtras(mediaUri = null, mediaType = "image/jpeg"))
        assertNull(edit.editExtras(mediaUri = "   ", mediaType = "image/jpeg"))
    }

    @Test
    fun editExtrasParseRoundTrip() {
        val extras = edit.editExtras(
            mediaUri = "content://m/1.jpg",
            mediaType = "image/jpeg",
            mediaId = "1",
            preset = AppGalleryPreviewQuickEditPreset.ROTATE,
        )!!
        val parsed = edit.parseEditExtras(extras)!!
        assertEquals("content://m/1.jpg", parsed.mediaUri)
        assertEquals("image/jpeg", parsed.mediaType)
        assertEquals("1", parsed.mediaId)
        assertEquals(AppGalleryPreviewQuickEditPreset.ROTATE, parsed.preset)
    }

    @Test
    fun parseEditExtrasIsCaseInsensitiveAndTolerant() {
        val parsed = edit.parseEditExtras(
            mapOf(
                edit.EXTRA_MEDIA_URI to "content://m/1.jpg",
                edit.EXTRA_EDIT_PRESET to "contrast",
            ),
        )!!
        assertEquals(AppGalleryPreviewQuickEditPreset.CONTRAST, parsed.preset)

        val unknown = edit.parseEditExtras(
            mapOf(
                edit.EXTRA_MEDIA_URI to "content://m/1.jpg",
                edit.EXTRA_EDIT_PRESET to "not_a_preset",
            ),
        )!!
        assertEquals(AppGalleryPreviewQuickEditPreset.NONE, unknown.preset)

        assertNull(edit.parseEditExtras(emptyMap()))
    }

    @Test
    fun actionForSelectsGenericVersusQuickEdit() {
        assertEquals(edit.ACTION_EDIT_MEDIA, edit.actionFor(AppGalleryPreviewQuickEditPreset.NONE))
        assertEquals(edit.ACTION_QUICK_EDIT_MEDIA, edit.actionFor(AppGalleryPreviewQuickEditPreset.CROP))
        assertEquals(edit.ACTION_QUICK_EDIT_MEDIA, edit.actionFor(AppGalleryPreviewQuickEditPreset.LOOK))
    }

    @Test
    fun actionNamesFollowProjectConvention() {
        assertEquals("com.hinnka.mycamera.action.EDIT_MEDIA", edit.ACTION_EDIT_MEDIA)
        assertEquals("com.hinnka.mycamera.action.QUICK_EDIT_MEDIA", edit.ACTION_QUICK_EDIT_MEDIA)
        assertEquals("media_uri", edit.EXTRA_MEDIA_URI)
        assertEquals("media_type", edit.EXTRA_MEDIA_TYPE)
    }
}
