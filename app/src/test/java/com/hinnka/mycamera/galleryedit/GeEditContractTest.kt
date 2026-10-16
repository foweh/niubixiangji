package com.hinnka.mycamera.galleryedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨代理 `EDIT_MEDIA` 入口协议单测。
 *
 * 该协议是独立相册 V4 与本模块之间**唯一**的耦合点，因此解析必须容错且可纯 JVM 验证。
 */
class GeEditContractTest {

    private val action = GeEditContract.ACTION_EDIT_MEDIA
    private val imageUri = "content://media/external/images/media/42"
    private val videoUri = "content://media/external/video/media/7"

    @Test
    fun `协议常量与契约一致`() {
        assertEquals("com.hinnka.mycamera.action.EDIT_MEDIA", GeEditContract.ACTION_EDIT_MEDIA)
        assertEquals("media_uri", GeEditContract.EXTRA_MEDIA_URI)
        assertEquals("media_type", GeEditContract.EXTRA_MEDIA_TYPE)
        assertEquals("image", GeEditContract.MEDIA_TYPE_IMAGE)
        assertEquals("video", GeEditContract.MEDIA_TYPE_VIDEO)
        assertEquals("image", GeMediaType.IMAGE.rawValue)
        assertEquals("video", GeMediaType.VIDEO.rawValue)
    }

    @Test
    fun `解析图片编辑请求`() {
        val request = GeEditContract.parse(
            action = action,
            mediaUriString = imageUri,
            mediaTypeString = GeEditContract.MEDIA_TYPE_IMAGE,
        )

        assertEquals(imageUri, request?.mediaUri)
        assertEquals(GeMediaType.IMAGE, request?.mediaType)
        assertFalse(request!!.isVideo)
    }

    @Test
    fun `解析视频编辑请求`() {
        val request = GeEditContract.parse(
            action = action,
            mediaUriString = videoUri,
            mediaTypeString = GeEditContract.MEDIA_TYPE_VIDEO,
        )

        assertEquals(GeMediaType.VIDEO, request?.mediaType)
        assertTrue(request!!.isVideo)
    }

    @Test
    fun `缺失 uri 时解析失败`() {
        assertNull(GeEditContract.parse(action, null, GeEditContract.MEDIA_TYPE_IMAGE))
        assertNull(GeEditContract.parse(action, "", GeEditContract.MEDIA_TYPE_IMAGE))
        assertNull(GeEditContract.parse(action, "   ", GeEditContract.MEDIA_TYPE_IMAGE))
    }

    @Test
    fun `action 不匹配时解析失败`() {
        assertNull(GeEditContract.parse(null, imageUri, GeEditContract.MEDIA_TYPE_IMAGE))
        assertNull(GeEditContract.parse("android.intent.action.VIEW", imageUri, GeEditContract.MEDIA_TYPE_IMAGE))
        assertNull(GeEditContract.parse("com.hinnka.mycamera.action.edit_media", imageUri, "image"))
        assertTrue(GeEditContract.isEditMediaAction(action))
        assertFalse(GeEditContract.isEditMediaAction("android.intent.action.VIEW"))
    }

    @Test
    fun `缺失 media_type 时按 MIME 兜底`() {
        val image = GeEditContract.parse(action, imageUri, null, "image/jpeg")
        assertEquals(GeMediaType.IMAGE, image?.mediaType)

        val video = GeEditContract.parse(action, videoUri, null, "video/mp4")
        assertEquals(GeMediaType.VIDEO, video?.mediaType)
    }

    @Test
    fun `缺失 media_type 与 MIME 时按扩展名兜底`() {
        assertEquals(
            GeMediaType.IMAGE,
            GeEditContract.parse(action, "file:///sdcard/a.JPG", null, null)?.mediaType,
        )
        assertEquals(
            GeMediaType.VIDEO,
            GeEditContract.parse(action, "file:///sdcard/a.mov", null, null)?.mediaType,
        )
        assertEquals(
            GeMediaType.IMAGE,
            GeEditContract.parse(action, "file:///sdcard/a.png?w=1#frag", null, null)?.mediaType,
        )
    }

    @Test
    fun `无法判定类型时解析失败而不是猜测`() {
        assertNull(GeEditContract.parse(action, "content://media/1", null, null))
        assertNull(GeEditContract.parse(action, "content://media/1", "unknown-type", "application/octet-stream"))
    }

    @Test
    fun `media_type 容忍大小写与常见别名`() {
        assertEquals(GeMediaType.IMAGE, GeEditContract.parse(action, imageUri, "IMAGE")?.mediaType)
        assertEquals(GeMediaType.IMAGE, GeEditContract.parse(action, imageUri, "photo")?.mediaType)
        assertEquals(GeMediaType.IMAGE, GeEditContract.parse(action, imageUri, " pic ")?.mediaType)
        assertEquals(GeMediaType.VIDEO, GeEditContract.parse(action, videoUri, "Movie")?.mediaType)
        assertEquals(GeMediaType.VIDEO, GeEditContract.parse(action, videoUri, "clip")?.mediaType)
    }

    @Test
    fun `uri 前后空白被裁掉`() {
        val request = GeEditContract.parse(action, "  $imageUri  ", GeEditContract.MEDIA_TYPE_IMAGE)

        assertEquals(imageUri, request?.mediaUri)
    }

    @Test
    fun `buildExtras 可被 parse 反向解析`() {
        val extras = GeEditContract.buildExtras(videoUri, GeMediaType.VIDEO)
        val request = GeEditContract.parse(
            action = action,
            mediaUriString = extras[GeEditContract.EXTRA_MEDIA_URI],
            mediaTypeString = extras[GeEditContract.EXTRA_MEDIA_TYPE],
        )

        assertEquals(videoUri, request?.mediaUri)
        assertEquals(GeMediaType.VIDEO, request?.mediaType)
    }

    @Test
    fun `MIME 推断规则`() {
        assertEquals(GeMediaType.IMAGE, GeMediaType.fromMime("image/heic"))
        assertEquals(GeMediaType.VIDEO, GeMediaType.fromMime("video/webm"))
        assertEquals(GeMediaType.VIDEO, GeMediaType.fromMime("audio/mpeg"))
        assertNull(GeMediaType.fromMime("text/plain"))
        assertNull(GeMediaType.fromMime(null))
        assertNull(GeMediaType.fromRaw("  "))
    }

    @Test
    fun `扩展名推断覆盖常见相机格式`() {
        listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "bmp", "gif", "avif").forEach { ext ->
            assertEquals("$ext 应判为图片", GeMediaType.IMAGE, GeMediaType.fromUriExtension("content://x/a.$ext"))
        }
        listOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp", "ts").forEach { ext ->
            assertEquals("$ext 应判为视频", GeMediaType.VIDEO, GeMediaType.fromUriExtension("content://x/a.$ext"))
        }
        assertNull(GeMediaType.fromUriExtension("content://x/a.txt"))
        assertNull(GeMediaType.fromUriExtension(null))
    }
}
