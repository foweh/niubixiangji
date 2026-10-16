package com.hinnka.mycamera.focusstack

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [FocusStackProcessor] 与 Android `Bitmap` 之间的适配层（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 与 `enhance/EnhanceBitmapAdapter` 同一思路：算法只在 `IntArray` 上跑（可 JVM 单测），
 * 本类只负责 Bitmap ↔ IntArray、缩放与 JPEG 编码，便于将来换 GPU/多线程实现而不动算法。
 */
object FocusStackBitmapAdapter {

    /** 把 Bitmap 转成 ARGB_8888 整型像素（必要时先做格式转换）。 */
    fun readPixels(bitmap: Bitmap): IntArray {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    }

    /** 把整型像素写回新的 ARGB_8888 Bitmap（像素不足时以黑色补齐，不抛异常）。 */
    fun toBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val expected = width * height
        val source = if (pixels.size == expected) {
            pixels
        } else {
            IntArray(expected) { i -> pixels.getOrElse(i) { 0xFF000000.toInt() } }
        }
        bitmap.setPixels(source, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** 按长边上限缩放（不放大）；[maxLongEdge] <= 0 或已达标时原样返回。 */
    fun scaleTo(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        if (maxLongEdge <= 0) return bitmap
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap
        val ratio = maxLongEdge.toFloat() / longEdge.toFloat()
        val width = max(1, (bitmap.width * ratio).roundToInt())
        val height = max(1, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 位图列表 → 帧源。像素**按需读取**（不预先展开全部帧），保证常驻内存与帧数无关。
     */
    fun bitmapSource(bitmaps: List<Bitmap>): FocusStackFrameSource {
        require(bitmaps.isNotEmpty()) { "bitmaps must not be empty" }
        val width = bitmaps.first().width
        val height = bitmaps.first().height
        return object : FocusStackFrameSource {
            override val frameCount: Int get() = bitmaps.size
            override val width: Int get() = width
            override val height: Int get() = height

            override fun <T> withFrame(index: Int, block: (IntArray) -> T): T {
                require(index in bitmaps.indices) { "frame index $index out of range" }
                return block(readPixels(bitmaps[index]))
            }
        }
    }

    /** 全分辨率 JPEG 编码（质量 clamp 到 60..100）。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = FocusStackConfig.DEFAULT_JPEG_QUALITY): ByteArray {
        val q = quality.coerceIn(FocusStackConfig.JPEG_QUALITY_MIN, FocusStackConfig.JPEG_QUALITY_MAX)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, q, stream)
        return stream.toByteArray()
    }
}
