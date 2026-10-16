package com.hinnka.mycamera.lenscorrection

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 镜头畸变校正的 Bitmap 适配层（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 与 `enhance` 包同样的分层：几何/采样算法跑在 `IntArray` 上（可 JVM 单测），
 * 本类只处理 Bitmap ↔ IntArray 与缩放。
 */
object LensCorrectionBitmapAdapter {

    /** Bitmap → ARGB_8888 整型像素（必要时先做格式转换）。 */
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

    fun toBitmap(pixels: IntArray, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun scaledDimensions(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val s = if (scale.isFinite()) {
            scale.coerceIn(LensCorrectionJobRunner.MIN_WORKING_SCALE, 1f)
        } else {
            1f
        }
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    fun scaleTo(bitmap: Bitmap, scale: Float): Bitmap {
        val (width, height) = scaledDimensions(bitmap.width, bitmap.height, scale)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
