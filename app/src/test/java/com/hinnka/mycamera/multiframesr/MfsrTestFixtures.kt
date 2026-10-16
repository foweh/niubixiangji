package com.hinnka.mycamera.multiframesr

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * MFSR 单测共用夹具：合成纹理、亚像素平移、理想降采样（LR 合成）、PSNR。
 *
 * 都是纯数学，不依赖 Android 框架，因此可在 `test` source set 直接跑。
 * 纹理刻意做成多频正弦叠加（含斜向高频），既有强梯度又不周期化到单峰，
 * 这样对齐精度与 IBP 收敛才有区分度。
 */
internal object MfsrTestFixtures {

    /** 解析式平滑纹理（0..255，中心约 128）。 */
    fun pattern(w: Int, h: Int, seed: Int = 0): MfsrPlane {
        val p = MfsrPlane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = x.toDouble()
                val fy = y.toDouble()
                val v = 128.0 +
                    40.0 * sin(2 * PI * fx / 23.0) +
                    35.0 * cos(2 * PI * fy / 17.0) +
                    25.0 * sin(2 * PI * (fx + fy) / 31.0) +
                    15.0 * sin(2 * PI * (2 * fx - fy) / 13.0) +
                    (seed * 0.0)
                p.data[y * w + x] = v.toFloat()
            }
        }
        return p
    }

    /** 按 `tgt(x,y) = ref(x - dx, y - dy)` 生成平移帧（越界填 0，与算法内部跳过越界样本等价）。 */
    fun shift(src: MfsrPlane, dx: Float, dy: Float): MfsrPlane {
        val out = MfsrPlane(src.width, src.height)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val v = src.bilinear(x - dx, y - dy)
                out.data[y * src.width + x] = if (v.isFinite()) v else 0f
            }
        }
        return out
    }

    /** 由 HR 真值按理想成像模型合成一帧 LR（与叠加器/IBP 的正向模型严格互逆）。 */
    fun synthLr(hr: MfsrPlane, dx: Float, dy: Float, scale: Float): MfsrPlane {
        val lw = Math.round(hr.width / scale)
        val lh = Math.round(hr.height / scale)
        val out = MfsrPlane(lw, lh)
        val fill = hr.mean()
        for (y in 0 until lh) {
            for (x in 0 until lw) {
                val hx = (x - dx + 0.5f) * scale - 0.5f
                val hy = (y - dy + 0.5f) * scale - 0.5f
                val v = hr.bilinear(hx, hy)
                out.data[y * lw + x] = if (v.isFinite()) v else fill
            }
        }
        return out
    }

    /** 加性高斯噪声（固定种子，保证测试可复现）。 */
    fun addNoise(plane: MfsrPlane, sigma: Float, seed: Long = 42L): MfsrPlane {
        val rnd = java.util.Random(seed)
        val out = plane.copy()
        for (i in out.data.indices) {
            val g = (rnd.nextGaussian() * sigma).toFloat()
            out.data[i] = (out.data[i] + g).coerceIn(0f, 255f)
        }
        return out
    }

    fun psnr(a: MfsrPlane, b: MfsrPlane): Double {
        require(a.pixels == b.pixels) { "psnr requires equal size" }
        var mse = 0.0
        for (i in a.data.indices) {
            val ta = a.data[i].coerceIn(0f, 255f)
            val tb = b.data[i].coerceIn(0f, 255f)
            val d = (ta - tb).toDouble()
            mse += d * d
        }
        mse /= a.pixels
        return if (mse < 1e-9) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }

    /** 临时目录（测试结束由调用方清理）。 */
    fun tempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    fun deleteRecursively(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        }
    }
}
