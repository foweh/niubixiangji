package com.hinnka.mycamera.multiframesr

import android.graphics.Bitmap
import com.hinnka.mycamera.utils.PLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MFSR 的 Android 桥接：Bitmap ⇄ 亮度平面，并把结果还原成高分辨率 Bitmap / JPEG。
 *
 * 算法全在 `multiframesr` 包内的纯 Kotlin 类里（[MfsrSession] / [MfsrIterativeBackProjection] …），
 * 本类只负责：
 *  1. 送帧前的尺寸治理（长边不超过 [MfsrConfig.inputMaxSide]，且内存守卫不过时继续降级）；
 *  2. 亮度平面抽取（Rec.601）；
 *  3. 结果色彩重组（HR 亮度 + 参考帧色度升采样）与 Bitmap/JPEG 输出。
 */
object MultiFrameSrProcessor {

    private const val TAG = "MfsrProcessor"

    /** 输入缩放的降级下限（px）：再低就没有超分意义了。 */
    const val MIN_INPUT_SIDE = MfsrConfig.INPUT_MAX_SIDE_MIN

    /**
     * 逐帧流式融合（**不驻留全部输入帧**，只保留在途帧 + 参考帧色度）。
     *
     * @param frames 连拍帧（顺序即采集顺序，第 0 帧为参考帧）
     * @param guard 内存守卫（生产环境传 `{ job.guardMemory(it) }`）
     * @param checkpoint 分片检查点（生产环境转调 `BgJob.checkYield()`）
     * @param spillDir 帧缓存落盘目录（null = 不落盘）
     * @param onProgress (已完成帧数, 总帧数) 回调
     * @param batchSizeHint 进度上报的批粒度（来自 `BgJob.batchSizeFor(n)`，仅影响上报频率）
     * @return 高分辨率 Bitmap；无有效帧时返回 null（不抛异常）
     */
    fun run(
        frames: List<Bitmap>,
        config: MfsrConfig,
        guard: (Long) -> Boolean = { true },
        checkpoint: MfsrCheckpoint? = null,
        spillDir: File? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        batchSizeHint: Int = 0,
    ): Bitmap? {
        if (frames.isEmpty()) return null
        val cfg = config.coerced()
        var savedSide: Bitmap? = null
        var firstScaled: Boolean = false
        return try {
            val side = resolveInputSide(frames[0], cfg.inputMaxSide, guard)
            val ref = scaleForInput(frames[0], side)
            savedSide = ref
            firstScaled = ref !== frames[0]
            val refPixels = IntArray(ref.width * ref.height)
            ref.getPixels(refPixels, 0, ref.width, 0, 0, ref.width, ref.height)
            val refPlane = MfsrPlane.lumaFromArgb(refPixels, ref.width, ref.height)
            val refColor = MfsrColor.fromArgb(refPixels, ref.width, ref.height)

            val session = MfsrSession(
                rawConfig = cfg,
                guard = guard,
                checkpoint = checkpoint,
                spillDir = spillDir,
                expectedFrameCount = frames.size,
            )
            session.ingest(refPlane)

            for (i in 1 until frames.size) {
                val bmp = scaleForInput(frames[i], side)
                try {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    session.ingest(MfsrPlane.lumaFromArgb(px, bmp.width, bmp.height))
                } finally {
                    if (bmp !== frames[i]) bmp.recycle()
                }
                // 批粒度上报：分片末尾的 checkYield 由 checkpoint 内部完成。
                val done = i + 1
                if (batchSizeHint <= 1 || done % batchSizeHint == 0 || done == frames.size) {
                    onProgress?.invoke(done, frames.size)
                }
            }

            val result = session.finish()
            val cbHr = MfsrColor.upsampleChroma(refColor.cb, result.width, result.height)
            val crHr = MfsrColor.upsampleChroma(refColor.cr, result.width, result.height)
            val out = IntArray(result.width * result.height)
            MfsrColor.combine(result.luma, cbHr, crHr, out)
            PLog.i(
                TAG,
                "mfsr done: frames=${result.framesUsed} hr=${result.width}x${result.height} " +
                    "work=${result.workingResolution.label} ibp=${result.ibpResiduals.size} " +
                    "degraded=${result.degraded} store=${result.storeKind} " +
                    "ibpSkipped=${result.ibpSkippedForMemory} inputSide=$side",
            )
            Bitmap.createBitmap(out, result.width, result.height, Bitmap.Config.ARGB_8888)
        } catch (e: MfsrCancelledException) {
            // 取消必须向上冒泡（否则任务会被误判为成功）。
            throw e
        } catch (e: Throwable) {
            PLog.w(TAG, "mfsr failed: ${e.message}", e)
            null
        } finally {
            if (firstScaled) runCatching { savedSide?.recycle() }
        }
    }

    /** 融合并直接编码为 JPEG（quality 来自 [MfsrConfig.jpegQuality]）。 */
    fun runToJpeg(
        frames: List<Bitmap>,
        config: MfsrConfig,
        guard: (Long) -> Boolean = { true },
        checkpoint: MfsrCheckpoint? = null,
        spillDir: File? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): ByteArray? {
        val bitmap = run(frames, config, guard, checkpoint, spillDir, onProgress) ?: return null
        return try {
            toJpeg(bitmap, config.coerced().jpegQuality)
        } finally {
            bitmap.recycle()
        }
    }

    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        val q = quality.coerceIn(MfsrConfig.JPEG_QUALITY_MIN, MfsrConfig.JPEG_QUALITY_MAX)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * 判定实际可用的输入长边：请求值优先，内存守卫不过就逐级减半（2048→1024→512），
     * 到 [MIN_INPUT_SIDE] 为止；仍不过就按最小值执行（降级而非失败）。
     */
    fun resolveInputSide(bitmap: Bitmap, requested: Int, guard: (Long) -> Boolean): Int {
        var side = requested.coerceIn(MIN_INPUT_SIDE, MfsrConfig.INPUT_MAX_SIDE_MAX)
        val longEdge = max(bitmap.width, bitmap.height)
        while (side > MIN_INPUT_SIDE) {
            val scale = if (longEdge > side) side.toFloat() / longEdge else 1f
            val px = (bitmap.width * scale).roundToInt().coerceAtLeast(1).toLong() *
                (bitmap.height * scale).roundToInt().coerceAtLeast(1).toLong()
            if (guard(px * 4L)) return side
            side /= 2
        }
        return MIN_INPUT_SIDE
    }

    /** 按长边上限缩放；无需缩放时原样返回（调用方按引用不等判断是否 recycle）。 */
    internal fun scaleForInput(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longEdge
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
