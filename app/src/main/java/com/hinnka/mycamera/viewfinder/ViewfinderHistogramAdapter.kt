package com.hinnka.mycamera.viewfinder

import android.graphics.Bitmap
import com.hinnka.mycamera.ui.components.CurveChannel
import com.hinnka.mycamera.ui.components.ImageHistogram
import com.hinnka.mycamera.utils.PLog

/**
 * Bitmap → [ViewfinderHistogram] 的适配层。
 *
 * 复用优先（PATTERN_SPEC §9）：当 bin 数为 256（工程既有 [ImageHistogram] 的固定 bin 数）时，
 * **直接复用** `ui/components/ImageHistogram.fromBitmap`（画廊/曲线编辑器同款、已验证的采样与平滑实现），
 * 本模块不重写像素统计；只有 64 / 128 两种 bin 数才走本模块的 [ViewfinderHistogramAnalyzer]，
 * 或在 256 结果上做**分桶降采样**。
 *
 * 任何异常（recycled bitmap、HARDWARE 配置不可读、尺寸非正）一律回退为空直方图，绝不抛给调用方。
 *
 * 已知差异（集成时需知）：256-bin 复用路径的 `sampleCount` 取位图像素总数（既有实现的采样数不外露），
 * 而 64/128 自研路径的 `sampleCount` 是**实际采样点数**；两者只影响统计口径展示，不影响归一化图形。
 * 既有 256-bin 实现还带 7 抽头显示平滑（`SMOOTHING_KERNEL`），自研路径不带平滑——
 * 若需要完全一致的观感，可在 UI 侧统一跑 [ViewfinderHistogramSmoother]。
 */
object ViewfinderHistogramAdapter {

    private const val TAG = "ViewfinderHistogram"

    /** 工程既有实现的固定 bin 数。 */
    private const val LEGACY_BIN_COUNT = 256

    fun fromBitmap(
        bitmap: Bitmap?,
        binCount: Int = ViewfinderParamRanges.HISTOGRAM_BIN_DEFAULT,
    ): ViewfinderHistogram {
        val bins = ViewfinderParamRanges.snapBinCount(binCount)
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return ViewfinderHistogram.empty(bins)
        }
        if (bins == LEGACY_BIN_COUNT) {
            reuseLegacy(bitmap)?.let { return it }
        }
        return analyzeFallback(bitmap, bins)
    }

    /** 复用工程既有 256-bin 直方图实现。 */
    private fun reuseLegacy(bitmap: Bitmap): ViewfinderHistogram? = try {
        val legacy = ImageHistogram.fromBitmap(bitmap)
        ViewfinderHistogram.create(
            binCount = LEGACY_BIN_COUNT,
            luminance = legacy.binsFor(CurveChannel.MASTER),
            red = legacy.binsFor(CurveChannel.RED),
            green = legacy.binsFor(CurveChannel.GREEN),
            blue = legacy.binsFor(CurveChannel.BLUE),
            sampleCount = bitmap.width * bitmap.height,
        )
    } catch (e: Exception) {
        PLog.w(TAG, "复用 ImageHistogram 失败，回退自研统计", e)
        null
    }

    /** 自研路径：读出像素后交给纯 Kotlin 分析器（必要时先做 256→N 分桶降采样）。 */
    private fun analyzeFallback(bitmap: Bitmap, bins: Int): ViewfinderHistogram = try {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        if (readable == null) {
            ViewfinderHistogram.empty(bins)
        } else {
            try {
                val pixels = IntArray(readable.width * readable.height)
                readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
                ViewfinderHistogramAnalyzer.analyze(pixels, readable.width, readable.height, bins)
            } finally {
                if (readable !== bitmap) readable.recycle()
            }
        }
    } catch (e: Exception) {
        PLog.w(TAG, "取景器直方图统计失败", e)
        ViewfinderHistogram.empty(bins)
    }

    /**
     * 256 bin → N bin 的分桶降采样（N 由 [ViewfinderParamRanges.snapBinCount] 吸附）。
     *
     * 实现委托给纯 Kotlin 的 [ViewfinderHistogram.downsample]，便于 JVM 单测（本对象含 Bitmap 依赖）。
     */
    fun downsample(source: IntArray, targetBins: Int): IntArray =
        ViewfinderHistogram.downsample(source, targetBins)
}
