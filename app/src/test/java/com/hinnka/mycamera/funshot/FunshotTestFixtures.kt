package com.hinnka.mycamera.funshot

import kotlin.math.max
import kotlin.math.min

/**
 * funshot 单测的合成素材（**纯 JVM**：不 import 任何 `android.*`）。
 *
 * 说明：本工程的既有约定是「无需添加单元测试用例」（`AGENTS.md`），
 * 但 `delivery/PATTERN_SPEC.md` §7.4 要求 funshot 模块交付纯 JVM 单测，
 * 二者冲突时以 PATTERN_SPEC 为准（已在 patch 与 selfcheck 中声明）。
 * 如后续要撤回，删除 `app/src/test/java/com/hinnka/mycamera/funshot/` 整个目录即可，
 * 主源码不依赖任何测试代码。
 */
object FunshotTestFixtures {

    /** 二维渐变帧（保证每个像素唯一，便于验证重采样/量化不串行）。 */
    fun gradientFrame(width: Int, height: Int, seed: Int = 0): FunArgbFrame {
        require(width > 0 && height > 0) { "frame must be non-empty" }
        val argb = IntArray(width * height)
        val maxX = max(1, width - 1)
        val maxY = max(1, height - 1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = ((x * 255) / maxX + seed) and 0xFF
                val g = ((y * 255) / maxY + seed * 3) and 0xFF
                val b = (((x + y) * 127) / max(1, width + height - 2) + 40) and 0xFF
                argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return FunArgbFrame(width, height, argb)
    }

    /** 棋盘帧（高频纹理，用于角点/描述子类逻辑的素材）。 */
    fun checkerFrame(width: Int, height: Int, cell: Int = 8): FunArgbFrame {
        require(width > 0 && height > 0) { "frame must be non-empty" }
        val size = cell.coerceAtLeast(1)
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val on = ((x / size) + (y / size)) % 2 == 0
                argb[y * width + x] = if (on) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
            }
        }
        return FunArgbFrame(width, height, argb)
    }

    /** 纯色帧。 */
    fun solidFrame(width: Int, height: Int, color: Int = 0xFF3366CC.toInt()): FunArgbFrame {
        require(width > 0 && height > 0) { "frame must be non-empty" }
        return FunArgbFrame(width, height, IntArray(width * height) { color })
    }

    /** 平移帧（越界补零），用于构造「已知位移」的拼接输入。 */
    fun shift(frame: FunArgbFrame, dx: Int, dy: Int): FunArgbFrame {
        val out = IntArray(frame.width * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val sx = x + dx
                val sy = y + dy
                out[y * frame.width + x] = if (sx < 0 || sy < 0 || sx >= frame.width || sy >= frame.height) {
                    0
                } else {
                    frame.argb[sy * frame.width + sx]
                }
            }
        }
        return FunArgbFrame(frame.width, frame.height, out)
    }

    /** 灰度视图（Rec.601，与算法层同一实现）。 */
    fun grayOf(frame: FunArgbFrame): FunGrayImage = frame.toGray()

    /** 规则网格点（用于单应估计的合成对应点，保证非共线）。 */
    fun gridPoints(columns: Int, rows: Int, stepX: Double, stepY: Double): List<FunPointF> {
        val cols = columns.coerceAtLeast(2)
        val rowCount = rows.coerceAtLeast(2)
        val points = ArrayList<FunPointF>(cols * rowCount)
        for (row in 0 until rowCount) {
            for (col in 0 until cols) {
                points += FunPointF(col * stepX, row * stepY)
            }
        }
        return points
    }

    /** 仿射（缩放 + 平移）对应点，用于「已知变换可被恢复」的断言。 */
    fun affine(points: List<FunPointF>, scale: Double, offsetX: Double, offsetY: Double): List<FunPointF> =
        points.map { FunPointF(it.x * scale + offsetX, it.y * scale + offsetY) }

    /** 生成定长 PCM（正弦，振幅 [amplitude]，用于声控电平判定）。 */
    fun tone(length: Int, amplitude: Int, periodSamples: Int = 16): ShortArray {
        val samples = ShortArray(length.coerceAtLeast(1))
        val period = periodSamples.coerceAtLeast(2)
        val clamped = min(32767, amplitude.coerceAtLeast(0))
        for (i in samples.indices) {
            val phase = (i % period).toDouble() / period
            val value = kotlin.math.sin(2.0 * Math.PI * phase) * clamped
            samples[i] = value.toInt().coerceIn(-32768, 32767).toShort()
        }
        return samples
    }

    /** 近静音 PCM（用于「不应触发」断言）。 */
    fun silence(length: Int, amplitude: Int = 4): ShortArray =
        ShortArray(length.coerceAtLeast(1)) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
}
