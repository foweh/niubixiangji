package com.hinnka.mycamera.nativecompute

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C++ 计算层数值约定的**等价参考模型**单测（纯 JVM）。
 *
 * ## 诚实标注（重要）
 * 本机为 3.9G 受限环境，**禁止编译、禁止 gradle、禁止 ndk-build**，因此本测试**不执行任何 C++ 代码**。
 * 它做的是另一件确定性的工作：把 `app/src/main/cpp/nativecompute/*.cpp` 里逐条实现、
 * 且与既有纯 Kotlin 实现共享的**数值约定**用 Kotlin 复写一遍，并把它们钉死成断言。
 * C++ 侧必须复现同样的等式（静态逐行核对表见 `delivery/selfchecks/nativecompute.md`，
 * 每条断言都在表里有对应的 C++ 函数/行）。
 *
 * 覆盖的约定：
 *  - `roundHalfUp`：`floor(x + 0.5)`，与 `kotlin.math.roundToInt()` 一致（负半值也不偏移）；
 *  - 两种浮点收敛语义的区别：`ClampFloat`（NaN → 下界）vs `CoerceInFloat`（NaN 透传）；
 *  - 暗帧扣减 + MAX / MEAN 叠加（含坏点掩膜）的精确结果；
 *  - CLAHE：常量 tile 恒等、tile 数收敛、输出范围与单调性；
 *  - 三次盒式模糊的**缓冲别名契约**（水平趟读 src 写 scratch、垂直趟读 scratch 写 dst）；
 *  - IBP 更新步 `hr += gain * err / wsum` 且 clamp 到 0..255。
 */
class NativeComputeReferenceModelTest {

    // ------------------------------------------------------------ 数值约定模型

    /** 对应 C++ `RoundHalfUp`（= Kotlin `Float.roundToInt()` = `Math.round`）。 */
    private fun roundHalfUp(value: Float): Int = floor(value.toDouble() + 0.5).toInt()

    /** 对应 C++ `ClampFloat`：NaN 落到下界。 */
    private fun clampFloat(value: Float, low: Float, high: Float): Float =
        if (value.isNaN()) low else if (value < low) low else if (value > high) high else value

    /** 对应 C++ `CoerceInFloat`：NaN 原样透传（Kotlin `coerceIn` 语义）。 */
    private fun coerceInFloat(value: Float, low: Float, high: Float): Float =
        if (value < low) low else if (value > high) high else value

    /** 对应 C++ `ToByteChannel`：0..1 → 0..255（NaN/<=0 → 0，>=1 → 255）。 */
    private fun toByteChannel(value: Float): Int = when {
        value.isNaN() -> 0
        value <= 0f -> 0
        value >= 1f -> 255
        else -> (value * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    @Test
    fun roundHalfUpMatchesKotlinRoundToIntIncludingNegativeHalves() {
        assertEquals(1, roundHalfUp(0.5f))
        assertEquals(0, roundHalfUp(-0.5f)) // Math.round(-0.5f) == 0，不能取 -1
        assertEquals(1, roundHalfUp(0.4999f))
        assertEquals(0, roundHalfUp(-0.4999f))
        assertEquals(2, roundHalfUp(1.5f))
        assertEquals(0, roundHalfUp(0f))
        // 与 Kotlin 官方实现逐值对齐
        for (v in intArrayOf(-3, -2, -1, 0, 1, 2, 3)) {
            val f = v * 0.5f
            assertEquals(f.roundToInt(), roundHalfUp(f))
        }
    }

    @Test
    fun clampAndCoerceDifferOnlyOnNaN() {
        assertEquals(0f, clampFloat(Float.NaN, 0f, 1f), 0f)
        assertTrue(coerceInFloat(Float.NaN, 0f, 1f).isNaN())
        // 有限值上两者必须完全一致（这是 MFSR / 后处理两条路径能同值的前提）
        for (v in floatArrayOf(-5f, -0.01f, 0f, 0.5f, 1f, 1.01f, 9f)) {
            assertEquals(clampFloat(v, 0f, 1f), coerceInFloat(v, 0f, 1f), 0f)
        }
    }

    @Test
    fun toByteChannelHandlesNonFiniteAndOutOfRange() {
        assertEquals(0, toByteChannel(Float.NaN))
        assertEquals(0, toByteChannel(-1f))
        assertEquals(0, toByteChannel(0f))
        assertEquals(255, toByteChannel(1f))
        assertEquals(255, toByteChannel(2f))
        assertEquals(128, toByteChannel(0.5f)) // 0.5*255+0.5 = 128.0 → 128
        assertEquals(0, toByteChannel(0.001f)) // 0.255+0.5 = 0.755 → truncate = 0
        assertEquals(1, toByteChannel(0.003f)) // 0.765+0.5 = 1.265 → truncate = 1
    }

    // ------------------------------------------------------ 叠加层参考模型

    /** 对应 C++ `accumulate::SubtractSample`。 */
    private fun referenceSubtractSample(
        sample: Int,
        darkSample: Int,
        hotThreshold: Int,
        maskHotPixels: Boolean,
        hasDark: Boolean,
        gain: Float,
    ): Pair<Int, Int> {
        if (maskHotPixels && hotThreshold > 0 && hasDark && darkSample >= hotThreshold) {
            return 0 to 1
        }
        val raw = sample - roundHalfUp(darkSample.toFloat() * gain)
        return raw.coerceIn(0, 4095) to 0
    }

    @Test
    fun darkSubtractMaxAccumulateMatchesHandComputedValues() {
        val frame = intArrayOf(1200, 3000, 200, 10)
        val dark = intArrayOf(100, 100, 100, 100)
        val gain = 1.5f // roundHalfUp(100 * 1.5) = roundHalfUp(150f) = 150
        val first = IntArray(frame.size) { Int.MIN_VALUE }
        var hot = 0
        // 首帧：!seeded → 直接写入
        for (i in frame.indices) {
            val (value, isHot) = referenceSubtractSample(frame[i], dark[i], 0, false, true, gain)
            hot += isHot
            first[i] = value
        }
        assertEquals(0, hot)
        // 1200-150=1050；3000-150=2850；200-150=50；10-150=-140 → clamp 到 0
        assertTrue(first.contentEquals(intArrayOf(1050, 2850, 50, 0)))

        // 第二帧：seeded → 逐样本取最大（亮部只增不减）
        val secondFrame = intArrayOf(900, 3000, 400, 0)
        for (i in secondFrame.indices) {
            val (value, _) = referenceSubtractSample(secondFrame[i], dark[i], 0, false, true, gain)
            first[i] = maxOf(first[i], value)
        }
        // 900-150=750 < 1050 → 保持；3000-150=2850 → 相等；400-150=250 > 50 → 抬升
        assertTrue(first.contentEquals(intArrayOf(1050, 2850, 250, 0)))
    }

    @Test
    fun hotPixelMaskZeroesSampleAndCountsIt() {
        val (value, hot) = referenceSubtractSample(500, 400, 300, true, true, 1f)
        assertEquals(0, value)
        assertEquals(1, hot)
        // 未开启掩膜时按普通暗帧扣减
        val (value2, hot2) = referenceSubtractSample(500, 400, 300, false, true, 1f)
        assertEquals(100, value2)
        assertEquals(0, hot2)
        // 无暗帧时不产生坏点
        val (value3, hot3) = referenceSubtractSample(500, 400, 300, true, false, 1f)
        assertEquals(500, value3)
        assertEquals(0, hot3)
    }

    @Test
    fun meanAccumulateSumsWithoutSaturation() {
        // 4096 帧 × 4095（12bit 满量程）≈ 1.677e7，Int 求和远未溢出（与 Kotlin MeanAccumulator 一致）
        val sum = IntArray(2)
        repeat(4096) {
            sum[0] += 4095
            sum[1] += 1
        }
        assertEquals(4095 * 4096, sum[0])
        assertEquals(4096, sum[1])
        assertTrue(sum[0] > 0) // 无符号反转
    }

    // ------------------------------------------------------ CLAHE 参考模型

    private fun referenceClahe(
        luma: FloatArray,
        width: Int,
        height: Int,
        tilesX: Int,
        tilesY: Int,
        clipLimit: Float,
    ): FloatArray {
        val tx = tilesX.coerceIn(1, 32).coerceAtMost(width)
        val ty = tilesY.coerceIn(1, 32).coerceAtMost(height)
        val tileW = (width + tx - 1) / tx
        val tileH = (height + ty - 1) / ty
        val luts = Array(ty * tx) { IntArray(256) { it } }
        for (gy in 0 until ty) {
            for (gx in 0 until tx) {
                val x0 = gx * tileW
                val y0 = gy * tileH
                val x1 = minOf(x0 + tileW, width)
                val y1 = minOf(y0 + tileH, height)
                val area = (x1 - x0) * (y1 - y0)
                if (area <= 0) continue
                val hist = IntArray(256)
                for (y in y0 until y1) for (x in x0 until x1) hist[toByteChannel(luma[y * width + x])]++
                if (hist.count { it > 0 } <= 1) continue // 常量 tile → 恒等
                val safeClip = if (clipLimit.isFinite()) maxOf(0f, clipLimit) else 0f
                val cap = maxOf(1, roundHalfUp(safeClip * area / 256f))
                val clipped = hist.copyOf()
                var excess = 0
                for (i in 0 until 256) if (clipped[i] > cap) {
                    excess += clipped[i] - cap
                    clipped[i] = cap
                }
                while (excess > 0) {
                    var added = 0
                    for (i in 0 until 256) {
                        if (excess <= 0) break
                        if (clipped[i] < cap) {
                            clipped[i]++
                            excess--
                            added++
                        }
                    }
                    if (added == 0) break
                }
                var cdf = 0
                var cdfMin = -1
                val cdfArr = IntArray(256)
                for (i in 0 until 256) {
                    cdf += clipped[i]
                    cdfArr[i] = cdf
                    if (cdfMin < 0 && cdf > 0) cdfMin = cdf
                }
                val denominator = area - cdfMin
                if (cdfMin < 0 || denominator <= 0) continue // 退化 → 恒等
                val scale = 255f / denominator
                for (i in 0 until 256) {
                    luts[gy * tx + gx][i] = roundHalfUp((cdfArr[i] - cdfMin) * scale).coerceIn(0, 255)
                }
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val yf = (y + 0.5f) / tileH - 0.5f
            val yt0Raw = floor(yf.toDouble()).toInt()
            val fy = yf - yt0Raw
            val yt0 = yt0Raw.coerceIn(0, ty - 1)
            val yt1 = (yt0Raw + 1).coerceIn(0, ty - 1)
            for (x in 0 until width) {
                val xf = (x + 0.5f) / tileW - 0.5f
                val xt0Raw = floor(xf.toDouble()).toInt()
                val fx = xf - xt0Raw
                val xt0 = xt0Raw.coerceIn(0, tx - 1)
                val xt1 = (xt0Raw + 1).coerceIn(0, tx - 1)
                val bin = toByteChannel(luma[y * width + x])
                val tl = luts[yt0 * tx + xt0][bin]
                val tr = luts[yt0 * tx + xt1][bin]
                val bl = luts[yt1 * tx + xt0][bin]
                val br = luts[yt1 * tx + xt1][bin]
                val top = tl + (tr - tl) * fx
                val bottom = bl + (br - bl) * fx
                out[y * width + x] = (top + (bottom - top) * fy) / 255f
            }
        }
        return out
    }

    @Test
    fun claheIsIdentityOnConstantLuma() {
        val width = 64
        val height = 48
        for (level in floatArrayOf(0f, 0.25f, 0.5f, 1f)) {
            val luma = FloatArray(width * height) { level }
            val out = referenceClahe(luma, width, height, 8, 8, 2f)
            for (v in out) {
                assertEquals("constant tile must stay identical", level, v, 1e-6f)
            }
        }
    }

    @Test
    fun claheKeepsOutputInUnitRangeAndIsMonotoneOnGradients() {
        val width = 96
        val height = 64
        val luma = FloatArray(width * height) { i ->
            val x = i % width
            (x.toFloat() / (width - 1))
        }
        val out = referenceClahe(luma, width, height, 6, 6, 3f)
        for (v in out) {
            assertTrue("clamped to [0,1]: $v", v in 0f..1f)
        }
        // 同一行内必须单调不降（CLAHE 的 CDF 映射单调，插值保持单调）
        var monotoneViolations = 0
        for (y in 0 until height) {
            for (x in 1 until width) {
                if (out[y * width + x] + 1e-6f < out[y * width + x - 1]) monotoneViolations++
            }
        }
        assertEquals(0, monotoneViolations)
    }

    @Test
    fun claheTileCountsAreClampedLikeTheCppSide() {
        val width = 32
        val height = 32
        val luma = FloatArray(width * height) { (it % width) / (width - 1f) }
        // tilesX = 0 / 负数 → 收敛到 1（与显式传 1 完全等价，不得崩溃、不得除零）
        val single = referenceClahe(luma, width, height, 1, 1, 2f)
        val zero = referenceClahe(luma, width, height, 0, -5, 2f)
        assertTrue(single.contentEquals(zero))
        // tilesX = 999 → 收敛到 32（与显式传 32 完全等价）
        val clampedMax = referenceClahe(luma, width, height, 32, 32, 2f)
        val huge = referenceClahe(luma, width, height, 999, 999, 2f)
        for (i in clampedMax.indices) assertEquals(clampedMax[i], huge[i], 1e-6f)
    }

    @Test
    fun claheWithOneTilePerPixelDegeneratesToIdentityMapping() {
        // 32×32 图像 + 32×32 tile → 每个 tile 只有 1 个像素（常量 tile）→ 全部恒等，
        // 输出应等于 ToByteChannel(luma)/255（这是"常量块不被压黑"的直接体现）。
        val width = 32
        val height = 32
        val luma = FloatArray(width * height) { (it % width) / (width - 1f) }
        val out = referenceClahe(luma, width, height, 32, 32, 2f)
        for (i in luma.indices) {
            assertEquals(toByteChannel(luma[i]) / 255f, out[i], 1e-6f)
        }
    }

    @Test
    fun claheNonFiniteClipLimitBehavesLikeZeroLimit() {
        val width = 32
        val height = 32
        val luma = FloatArray(width * height) { (it % width) / (width - 1f) }
        val nan = referenceClahe(luma, width, height, 4, 4, Float.NaN)
        val zero = referenceClahe(luma, width, height, 4, 4, 0f)
        for (i in nan.indices) assertEquals(zero[i], nan[i], 1e-6f)
    }

    // --------------------------------------------------- 盒式模糊别名契约

    /** 对应 C++ `enhance::BoxBlurInto`（水平趟读 src 写 scratch，垂直趟读 scratch 写 dst）。 */
    private fun boxBlurInto(src: FloatArray, scratch: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) {
            src.copyInto(dst)
            return
        }
        val window = (2 * radius + 1).toFloat()
        for (y in 0 until h) {
            val base = y * w
            var sum = 0f
            for (k in -radius..radius) sum += src[base + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                scratch[base + x] = sum / window
                val addIndex = minOf(x + radius + 1, w - 1)
                val removeIndex = maxOf(x - radius, 0)
                sum += src[base + addIndex] - src[base + removeIndex]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) sum += scratch[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / window
                val addIndex = minOf(y + radius + 1, h - 1)
                val removeIndex = maxOf(y - radius, 0)
                sum += scratch[addIndex * w + x] - scratch[removeIndex * w + x]
            }
        }
    }

    @Test
    fun threePassBoxBlurPreservesConstantImageExactly() {
        val w = 24
        val h = 18
        val constant = 0.37f
        val channel = FloatArray(w * h) { constant }
        val blur = FloatArray(w * h)
        val scratch = FloatArray(w * h)
        // 三趟：趟 1 与趟 2/3 允许 dst == src（垂直趟只读 scratch），但 scratch 必须独立
        boxBlurInto(channel, scratch, blur, w, h, 2)
        boxBlurInto(blur, scratch, blur, w, h, 2)
        boxBlurInto(blur, scratch, blur, w, h, 2)
        for (v in blur) assertEquals(constant, v, 1e-5f)
    }

    @Test
    fun boxBlurKeepsRampInsideUnitRangeAndMonotone() {
        // 契约测试：合法的三趟调用（scratch 独立）必须保持值域与单调性；
        // 这也是 C++ 侧 "scratch 必须与 src/dst 都不同" 这条注释要保护的性质
        // —— 若把 scratch 与 src 混用，滑动窗口和会读到已被覆盖的样本，结果不可预测。
        val w = 16
        val h = 16
        val ramp = FloatArray(w * h) { (it % w) / (w - 1f) }
        val blur = FloatArray(w * h)
        val scratch = FloatArray(w * h)
        boxBlurInto(ramp, scratch, blur, w, h, 1)
        boxBlurInto(blur, scratch, blur, w, h, 1)
        boxBlurInto(blur, scratch, blur, w, h, 1)
        for (v in blur) assertTrue("blur must stay in [0,1]: $v", v in 0f..1f)
        var monotoneViolations = 0
        for (y in 0 until h) {
            for (x in 1 until w) {
                if (blur[y * w + x] + 1e-6f < blur[y * w + x - 1]) monotoneViolations++
            }
        }
        assertEquals(0, monotoneViolations)
        // 三次盒式模糊对线性渐变几乎不改变内部取值（边界 clamp 除外）
        var maxInteriorDelta = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                maxInteriorDelta = maxOf(maxInteriorDelta, abs(blur[y * w + x] - ramp[y * w + x]))
            }
        }
        assertTrue("linear ramp should survive box blur: $maxInteriorDelta", maxInteriorDelta <= 0.2f)
    }

    // -------------------------------------------------------- IBP 更新步

    @Test
    fun ibpUpdateStepMatchesKotlinFormula() {
        val hr = floatArrayOf(100f, 200f, 250f, 0f)
        val err = floatArrayOf(10f, -20f, 100f, 5f)
        val wsum = floatArrayOf(2f, 4f, 1f, 0f) // 最后一列为 0 → 不得被更新（防止除零）
        val gain = 0.85f
        for (k in hr.indices) {
            val w = wsum[k]
            if (w <= 0f) continue
            val v = hr[k] + gain * (err[k] / w)
            hr[k] = if (v.isFinite()) v.coerceIn(0f, 255f) else hr[k]
        }
        assertEquals(104.25f, hr[0], 1e-4f)
        assertEquals(195.75f, hr[1], 1e-4f)
        assertEquals(255f, hr[2], 1e-4f) // 250 + 85 = 335 → clamp 到上限 255
        assertEquals(0f, hr[3], 1e-4f) // wsum 为 0 的像素保持原值
    }
}
