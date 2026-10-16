package com.hinnka.mycamera.superres

import android.graphics.Bitmap
import com.hinnka.mycamera.utils.PLog

/**
 * 推理后端抽象（超分/风格/修复统一入口）。
 *
 * 抽成接口的目的：tile 调度与拼缝逻辑只需要「一块进、一块出」的能力，
 * 与 ORT 会话生命周期解耦；[SuperResOnnxEngine] 是唯一的生产实现。
 */
interface SuperResInference {
    /** 单 tile 前向（NCHW 平面 RGB → 模型原始输出）。 */
    fun runTile(inputPlanar: FloatArray, width: Int, height: Int): FloatArray?

    /** 单 tile 修复前向（RGB + mask → 补全结果）。默认不支持时返回 null。 */
    fun runInpaintTile(inputPlanar: FloatArray, mask: FloatArray, width: Int, height: Int): FloatArray? = null
}

/** 内存守卫：返回 false 表示该次分配不安全，渲染器应降级而不是失败。 */
typealias SuperResMemoryGuard = (requiredBytes: Long) -> Boolean

/**
 * Tiled 渲染器：把「切分 → 逐 tile 推理 → 边缘加权拼缝 → 与原图混合 → 出 Bitmap」串起来。
 *
 * 这是超分/风格迁移/修复**共用**的执行内核（三者只差 scale、是否喂掩膜、输出后处理）。
 *
 * 内存与降级（PATTERN_SPEC 第 1.5 节）：
 *  - 分配累加缓冲前先问 [memoryGuard]；不通过则 [SuperResConfig.degradeForMemoryPressure]
 *    降 tile 与工作分辨率后重试，最多 [MAX_DEGRADE_ROUNDS] 轮；
 *  - 仍不通过才返回 null（调用方回退原图），**不会**因为内存不足而崩溃；
 *  - 单个 tile 推理失败只影响该 tile：用原图双线性上采样结果顶替，整图仍可出。
 *
 * 每个 tile 结束回调 [onTileProgress]，调用方（[SuperResPipeline]）在其中调用
 * `job.checkYield()`，从而获得「分片末尾必调」的暂停/取消/节流语义。
 */
class SuperResTileRenderer(
    config: SuperResConfig,
    private val engine: SuperResInference,
    private val scale: Int,
    /** 修复模式：非空时每个 tile 额外喂入对应区域的掩膜。 */
    private val maskSource: Bitmap? = null,
    private val memoryGuard: SuperResMemoryGuard? = null,
) {
    private val clamped: SuperResConfig = SuperResConfig.clamp(config)

    /**
     * 渲染。
     *
     * @param source 输入图。
     * @param onTileProgress (已完成 tile 数, 总 tile 数)。
     * @return 结果 Bitmap；输入非法或内存守卫在多轮降级后仍不通过时返回 null。
     */
    fun render(source: Bitmap, onTileProgress: ((Int, Int) -> Unit)? = null): Bitmap? {
        if (source.width <= 0 || source.height <= 0) return null
        val s = scale.coerceAtLeast(1)

        var cfg = clamped
        var working = SuperResAvailability.workingSizeFor(source.width, source.height, cfg.maxWorkingPixels)
        var accBytes = accumulatorBytes(working.first, working.second, s)

        var round = 0
        while (memoryGuard != null && !memoryGuard(accBytes) && cfg.degradeInsteadOfFail &&
            round < MAX_DEGRADE_ROUNDS
        ) {
            cfg = SuperResConfig.degradeForMemoryPressure(cfg)
            working = SuperResAvailability.workingSizeFor(source.width, source.height, cfg.maxWorkingPixels)
            accBytes = accumulatorBytes(working.first, working.second, s)
            PLog.i(TAG, "degrade round=$round tile=${cfg.tileSize} working=${working.first}x${working.second}")
            round++
        }
        if (memoryGuard != null && !memoryGuard(accBytes)) {
            PLog.w(TAG, "memory guard rejected after $round degrade rounds (need $accBytes bytes)")
            return null
        }

        val (inW, inH) = working
        if (inW <= 0 || inH <= 0) return null
        val outW = inW * s
        val outH = inH * s

        val tiles = SuperResTilePlan.plan(inW, inH, cfg.tileSize, cfg.tileOverlap)
        if (tiles.isEmpty()) return null
        val seam = cfg.effectiveSeamWidthPx

        val accum = FloatArray(outW * outH * 3)
        val weight = FloatArray(outW * outH)
        val tileInterleaved = FloatArray(cfg.tileSize.coerceAtLeast(1) * s * cfg.tileSize.coerceAtLeast(1) * s * 3)
        var done = 0

        try {
            for (tile in tiles) {
                val srcCrop = runCatching {
                    Bitmap.createBitmap(source, tile.x, tile.y, tile.width, tile.height)
                }.getOrNull() ?: continue

                val planar = SuperResBitmapCodec.decodeRgbPlanar(srcCrop, tile.width, tile.height)
                val raw = if (maskSource != null) {
                    val maskBmp = maskSource
                    val maskCrop = runCatching {
                        Bitmap.createBitmap(maskBmp, tile.x, tile.y, tile.width, tile.height)
                    }.getOrNull()
                    val mask = if (maskCrop != null) {
                        val m = SuperResBitmapCodec.decodeMask(
                            maskCrop, tile.width, tile.height, cfg.inpaintMaskThreshold,
                        )
                        maskCrop.recycle()
                        m
                    } else {
                        FloatArray(tile.width * tile.height)
                    }
                    engine.runInpaintTile(planar, mask, tile.width, tile.height)
                } else {
                    engine.runTile(planar, tile.width, tile.height)
                }

                if (raw != null && raw.isNotEmpty()) {
                    SuperResBitmapCodec.upscaledOutputToInterleaved(
                        output = raw,
                        inWidth = tile.width,
                        inHeight = tile.height,
                        scale = s,
                        outInterleaved = tileInterleaved,
                    )
                } else {
                    // 单 tile 失败 → 用原图该区域的双线性上采样顶替（画质略降但不留空洞）。
                    upsampleCropInto(srcCrop, tile, s, tileInterleaved)
                }

                SuperResTilePlan.accumulate(
                    accum = accum,
                    weight = weight,
                    src = tileInterleaved,
                    tile = tile,
                    scale = s,
                    outWidth = outW,
                    outHeight = outH,
                    imageWidth = inW,
                    imageHeight = inH,
                    seam = seam,
                )
                // createBitmap 在「整图正好等于该 tile」时可能直接返回 source 本身，
                // 此时绝不能 recycle，否则会把调用方的原图销毁。
                if (srcCrop !== source) srcCrop.recycle()
                done++
                onTileProgress?.invoke(done, tiles.size)
            }

            val ai = SuperResTilePlan.normalize(accum, weight)
            // strength >= 1 时无需解码原图基准，省一份 outW*outH*3 浮点缓冲。
            val blended = if (cfg.strength >= 1f) {
                ai
            } else {
                val base = SuperResBitmapCodec.decodeRgbInterleaved(source, outW, outH)
                SuperResTilePlan.blendWithStrength(ai, base, cfg.strength)
            }
            return SuperResBitmapCodec.encodeToBitmap(
                interleaved = blended,
                width = outW,
                height = outH,
                alphaSource = if (cfg.keepOutputAlpha && maskSource != null) maskSource else source,
                keepAlpha = cfg.keepOutputAlpha,
            )
        } catch (e: Throwable) {
            // 兜底：任何未预料的错误都不向上抛（上层据此回退原图）。
            PLog.w(TAG, "render failed", e)
            return null
        }
    }

    /** 原图区域 → 双线性放大到 tile*scale，写进交错缓冲（长度不足时按需保留原值）。 */
    private fun upsampleCropInto(crop: Bitmap, tile: SuperResTile, s: Int, out: FloatArray) {
        val tw = tile.width * s
        val th = tile.height * s
        val scaled = runCatching { Bitmap.createScaledBitmap(crop, tw, th, true) }.getOrNull() ?: return
        val interleaved = SuperResBitmapCodec.decodeRgbInterleaved(scaled, tw, th)
        System.arraycopy(interleaved, 0, out, 0, minOf(interleaved.size, out.size))
        if (scaled !== crop) scaled.recycle()
    }

    private fun accumulatorBytes(width: Int, height: Int, s: Int): Long {
        val outW = width.coerceAtLeast(0).toLong() * s.coerceAtLeast(1).toLong()
        val outH = height.coerceAtLeast(0).toLong() * s.coerceAtLeast(1).toLong()
        return outW * outH * (3L * 4L + 4L)
    }

    companion object {
        private const val TAG = "SuperResTileRenderer"

        /** 降级重试上限（降到 1/16 分辨率基本已不可能再 OOM）。 */
        private const val MAX_DEGRADE_ROUNDS = 4
    }
}
