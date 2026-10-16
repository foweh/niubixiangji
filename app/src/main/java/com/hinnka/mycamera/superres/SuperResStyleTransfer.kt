package com.hinnka.mycamera.superres

import android.graphics.Bitmap
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 风格迁移（参考 jcjohnson/fast-neural-style 的**移动端化思路**，不采其代码）。
 *
 * 思路：每个风格 = 一个前馈卷积网络，推理 = 一次前向 + 与原图按强度线性混合；
 * 因此可以复用 [SuperResOnnxEngine] 的会话与 tile 调度，只把「输出通道/尺寸约定」设为
 * 同尺寸 3 通道（scale = 1）。
 *
 * 许可与来源说明（PATTERN_SPEC 第 1.4 节）：
 *  - jcjohnson/fast-neural-style（4362★，2026-10-16 核实）**仓库内没有 LICENSE 文件，
 *    许可不明确** → 只参考思路，不采代码，ONNX 权重由用户自行导出/自备（[SuperResModelCatalog]
 *    中的风格档位 `directFileUrl` 刻意留空，只能走「导入模型」）。
 *
 * 模型未安装 / 运行时不可用 → 返回 null，调用方回退原图（入口在 UI 侧灰化）。
 */
class SuperResStyleTransfer(private val config: SuperResConfig) {

    private val clamped: SuperResConfig = SuperResConfig.clamp(config)

    /**
     * 执行风格迁移。[onTileDone] 每完成一个 tile 回调一次（进度用），可在其中取消。
     *
     * @param source 原图（任意尺寸，会按工作分辨率上限降采样）。
     * @param modelFile 已安装的风格模型（.onnx）。
     * @return 风格化结果；模型/运行时不可用或全部 tile 失败时返回 null（调用方回退原图）。
     */
    fun run(
        source: Bitmap,
        modelFile: File,
        onTileDone: ((done: Int, total: Int) -> Unit)? = null,
    ): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val engine = SuperResOnnxEngine(clamped)
        try {
            if (!engine.isRuntimeAvailable() || !engine.open(modelFile)) {
                PLog.w(TAG, "style transfer unavailable: ${engine.lastError()}")
                return null
            }
            // 风格网是 scale=1 的同尺寸前馈网：tile 切分与超分共用同一套拼缝逻辑。
            val styleConfig = clamped.copy(scale = 1)
            val styled = runCatching {
                SuperResTileRenderer(
                    config = styleConfig,
                    engine = engine,
                    scale = 1,
                    maskSource = null,
                ).render(source, onTileDone)
            }.onFailure { PLog.w(TAG, "style transfer failed", it) }.getOrNull() ?: return null
            return applyStrength(source, styled, clamped.styleTransferStrength)
        } finally {
            engine.close()
        }
    }

    /** 按强度把风格化结果与原图混合（0 = 原图）。 */
    private fun applyStrength(source: Bitmap, styled: Bitmap, strength: Float): Bitmap {
        val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 1f
        if (s >= 0.999f) return styled
        // 工作分辨率上限会让结果尺寸小于原图：先统一尺寸再做逐像素混合，避免错位鬼影。
        val base = if (source.width == styled.width && source.height == styled.height) {
            source
        } else {
            runCatching { Bitmap.createScaledBitmap(source, styled.width, styled.height, true) }.getOrNull()
                ?: return styled
        }
        val n = styled.width * styled.height
        val a = IntArray(n)
        val b = IntArray(n)
        styled.getPixels(a, 0, styled.width, 0, 0, styled.width, styled.height)
        base.getPixels(b, 0, base.width, 0, 0, base.width, base.height)
        val out = IntArray(n)
        val w1 = s
        val w0 = 1f - s
        for (i in 0 until n) {
            val r = (((a[i] shr 16) and 0xFF) * w1 + ((b[i] shr 16) and 0xFF) * w0).toInt().coerceIn(0, 255)
            val g = (((a[i] shr 8) and 0xFF) * w1 + ((b[i] shr 8) and 0xFF) * w0).toInt().coerceIn(0, 255)
            val bl = ((a[i] and 0xFF) * w1 + (b[i] and 0xFF) * w0).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val mixed = Bitmap.createBitmap(styled.width, styled.height, Bitmap.Config.ARGB_8888)
        mixed.setPixels(out, 0, styled.width, 0, 0, styled.width, styled.height)
        if (base !== source) base.recycle()
        return mixed
    }

    companion object {
        private const val TAG = "SuperResStyleTransfer"
    }
}
