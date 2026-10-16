package com.hinnka.mycamera.enhance

import android.graphics.Bitmap
import androidx.annotation.IntRange
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [EnhancePipeline] 与 Android `Bitmap` 之间的适配层（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 设计上把「像素算法」与「平台对象」隔离：算法全部在 `IntArray` 上跑（可 JVM 单测），
 * 本类只负责 Bitmap ↔ IntArray 与缩放，便于将来换 GPU/多线程实现而不动算法。
 */
object EnhanceBitmapAdapter {

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

    /** 按缩放系数返回缩放后的 Bitmap；scale ≥ 1 或尺寸非法时原样返回。 */
    fun scaleTo(bitmap: Bitmap, scale: Float): Bitmap {
        val (width, height) = scaledDimensions(bitmap.width, bitmap.height, scale)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** 计算缩放后的尺寸（至少 1×1，不放大）。 */
    fun scaledDimensions(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val s = if (scale.isFinite()) scale.coerceIn(EnhancePipeline.MIN_WORKING_SCALE, 1f) else 1f
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    /** 把整型像素写回新的 ARGB_8888 Bitmap。 */
    fun toBitmap(
        pixels: IntArray,
        @IntRange(from = 1) width: Int,
        @IntRange(from = 1) height: Int,
    ): Bitmap {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
