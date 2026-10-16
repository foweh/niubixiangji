package com.hinnka.mycamera.superres

import android.graphics.Bitmap
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 图像修复 / inpainting（参考 advimman/lama 的**思路**，不采其代码）。
 *
 * 思路：把「掩膜区 + 上下文」一次性喂进一个单次前向的补全网（LaMa 系导出的 ONNX 通常是
 * 4 通道输入：RGB + mask），输出同尺寸的补全结果，再按 [SuperResConfig.inpaintFeatherPx]
 * 羽化边界后与原图合成（羽化在拼缝权重里已由 tile 重叠 + 边缘淡化承担，这里做最终边界过渡）。
 *
 * 许可与来源：
 *  - advimman/lama（10315★，Apache-2.0，2026-10-16 核实）：**代码** Apache-2.0 可直采，
 *    但**预训练权重许可另计**，因此本模块不内置、不下载权重，只支持用户用「导入模型」放入
 *    自备的 ONNX（[SuperResModelCatalog.ID_INPAINT_LAMA] 的 `directFileUrl` 刻意留空）。
 *
 * 模型未安装 / 运行时不可用 → 返回 null，调用方回退原图。
 */
class SuperResInpainting(private val config: SuperResConfig) {

    private val clamped: SuperResConfig = SuperResConfig.clamp(config)

    /**
     * 执行修复。
     *
     * @param source 原图。
     * @param mask 掩膜图：alpha 低或亮度低的像素视为「待补全」（见 [SuperResBitmapCodec.decodeMask]）。
     * @param modelFile 已安装的修复模型（.onnx）。
     * @param onTileDone (已完成 tile, 总 tile)。
     * @return 修复结果；不可用时返回 null。
     */
    fun run(
        source: Bitmap,
        mask: Bitmap,
        modelFile: File,
        onTileDone: ((done: Int, total: Int) -> Unit)? = null,
    ): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        if (mask.width <= 0 || mask.height <= 0) return null
        val engine = SuperResOnnxEngine(clamped)
        try {
            if (!engine.isRuntimeAvailable() || !engine.open(modelFile)) {
                PLog.w(TAG, "inpainting unavailable: ${engine.lastError()}")
                return null
            }
            val renderer = SuperResTileRenderer(
                config = clamped.copy(scale = 1),
                engine = engine,
                scale = 1,
                maskSource = mask,
            )
            val result = runCatching { renderer.render(source, onTileDone) }
                .onFailure { PLog.w(TAG, "inpainting failed", it) }
                .getOrNull() ?: return null
            return featherBoundary(source, mask, result, clamped.inpaintFeatherPx)
        } finally {
            engine.close()
        }
    }

    /**
     * 边界羽化：按掩膜到保留区的距离做线性过渡（算法细节见 [SuperResMaskFeather]）。
     *
     * 目的：LaMa 系模型会给整幅图重绘，但用户只希望被涂抹的区域改变；
     * 距离加权混合让「补全区中心 = 全 AI、边界 = 全原图」，硬边彻底消失。
     */
    private fun featherBoundary(source: Bitmap, mask: Bitmap, result: Bitmap, featherPx: Int): Bitmap {
        if (result.width != source.width || result.height != source.height) return result
        val w = result.width
        val h = result.height
        if (featherPx <= 0) return result
        val maskScaled = if (mask.width == w && mask.height == h) {
            mask
        } else {
            runCatching { Bitmap.createScaledBitmap(mask, w, h, true) }.getOrNull() ?: return result
        }
        val maskPixels = IntArray(w * h)
        maskScaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
        val alpha = SuperResMaskFeather.alphaForArgb(
            argb = maskPixels,
            width = w,
            height = h,
            threshold = clamped.inpaintMaskThreshold,
            featherPx = featherPx,
        )
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val resPixels = IntArray(w * h)
        result.getPixels(resPixels, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            out[i] = mix(resPixels[i], srcPixels[i], alpha[i])
        }
        val feathered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        feathered.setPixels(out, 0, w, 0, 0, w, h)
        if (maskScaled !== mask) maskScaled.recycle()
        return feathered
    }

    private fun mix(ai: Int, original: Int, alpha: Float): Int {
        val a = if (alpha.isFinite()) alpha.coerceIn(0f, 1f) else 1f
        val ia = 1f - a
        val r = (((ai shr 16) and 0xFF) * a + ((original shr 16) and 0xFF) * ia).toInt().coerceIn(0, 255)
        val g = (((ai shr 8) and 0xFF) * a + ((original shr 8) and 0xFF) * ia).toInt().coerceIn(0, 255)
        val b = ((ai and 0xFF) * a + (original and 0xFF) * ia).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private const val TAG = "SuperResInpainting"
    }
}
