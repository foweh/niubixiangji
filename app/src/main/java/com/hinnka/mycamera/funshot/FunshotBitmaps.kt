package com.hinnka.mycamera.funshot

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Android 位图 ⇄ [FunArgbFrame] 桥接（全景 / GIF 共用）。
 *
 * 拆成独立对象的原因：算法层（[FunArgbFrame] 等）保持纯 JVM 可测，
 * 所有 `android.graphics` 依赖集中在这一个文件里。
 */
object FunshotBitmaps {

    /** Bitmap → ARGB 帧（不修改原图；宽高非法时抛异常由调用方兜底）。 */
    fun toArgbFrame(bitmap: Bitmap): FunArgbFrame {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 0 && height > 0) { "bitmap must be non-empty" }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return FunArgbFrame(width, height, pixels)
    }

    /** ARGB 帧 → 不可变 Bitmap；尺寸非法返回 null。 */
    fun toBitmap(frame: FunArgbFrame): Bitmap? {
        if (frame.width <= 0 || frame.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /** 拼接近似结果 → Bitmap（空结果返回 null）。 */
    fun toBitmap(result: FunshotPanoramaResult): Bitmap? {
        if (result.width <= 0 || result.height <= 0 || result.argb.isEmpty()) return null
        return runCatching {
            Bitmap.createBitmap(result.argb, result.width, result.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    /** JPEG 编码（质量 clamp 60..100）；失败返回 null。 */
    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), stream)
            stream.toByteArray()
        }
    }.getOrNull()
}
