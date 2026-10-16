package com.hinnka.mycamera.lenscorrection

import kotlin.math.hypot

/**
 * 畸变校正重映射网格：为**每个输出像素**预计算它在源图中的浮点采样坐标。
 *
 * 用途：
 * ① CPU 重映射（[LensCorrectionCore.remap]）；
 * ② 导出为归一化重映射表（[toNormalizedTable]）交给 GPU 采样（同一份几何，CPU/GPU 一致）。
 *
 * 坐标约定（与 [RadialDistortionModel] 严格一致）：
 * `normHalf = 0.5·hypot(width, height)`，光心为 `(centerX·width, centerY·height)`；
 * 输出像素 `(x, y)` 的源坐标 `= center + u·s(r²)·cropScale·normHalf`。
 * 网格存**未截断**的原始坐标，越界处理交给采样阶段按 [DistortionEdgePolicy] 决定，
 * 这样同一网格既能用于 CLAMP 也能用于 TRANSPARENT_BLACK，且便于测试边界行为。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
class DistortionRemapGrid(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "grid size must be positive" }
    }

    val count: Int = width * height

    /** 每个输出像素对应的源图 x 坐标（像素，可为负、可超过 width-1）。 */
    val sourceX: FloatArray = FloatArray(count)

    /** 每个输出像素对应的源图 y 坐标（像素）。 */
    val sourceY: FloatArray = FloatArray(count)

    /** 归一化半对角线长度（网格构建后固定）。 */
    var normHalf: Float = 0.5f * hypot(width.toFloat(), height.toFloat())
        private set

    /**
     * 填充 `[rowStart, rowEnd)` 行（供 BgJobRunner 按行带分片构建）。
     * 越界行范围自动裁到 `[0, height]`，不抛异常。
     */
    fun fillRows(config: LensDistortionConfig, rowStart: Int = 0, rowEnd: Int = height) {
        val clamped = LensDistortionConfig.clamp(config)
        val model = clamped.model()
        val centerPixelX = clamped.centerX * width
        val centerPixelY = clamped.centerY * height
        val half = if (normHalf > 0f) normHalf else 1f
        val y0 = rowStart.coerceIn(0, height)
        val y1 = rowEnd.coerceIn(y0, height)
        for (y in y0 until y1) {
            val v = (y + 0.5f - centerPixelY) / half
            val base = y * width
            for (x in 0 until width) {
                val u = (x + 0.5f - centerPixelX) / half
                val r2 = u * u + v * v
                val scale = model.distortionFactor(r2) * clamped.cropScale
                sourceX[base + x] = centerPixelX + u * scale * half
                sourceY[base + x] = centerPixelY + v * scale * half
            }
        }
    }

    /**
     * 归一化重映射表（交错 `[u, v]`，值域 `[0, 1]`，相对源图尺寸），
     * 可直接作为 GPU 纹理采样坐标上传。长度 = `width · height · 2`。
     */
    fun toNormalizedTable(): FloatArray {
        val table = FloatArray(count * 2)
        val invW = if (width > 0) 1f / width else 0f
        val invH = if (height > 0) 1f / height else 0f
        for (i in 0 until count) {
            table[i * 2] = sourceX[i] * invW
            table[i * 2 + 1] = sourceY[i] * invH
        }
        return table
    }

    companion object {
        /** 一次构建整幅网格。 */
        fun create(width: Int, height: Int, config: LensDistortionConfig): DistortionRemapGrid =
            DistortionRemapGrid(width, height).also { it.fillRows(config, 0, height) }

        /**
         * 恒等网格（不校正），用于测试与「关闭校正」路径。
         *
         * 注意：这里必须显式把三个 k 覆盖为 0——`enabled = false` 只是「不启用」，
         * 预设系数仍在 [LensDistortionConfig.model] 里，不会自动变成恒等模型。
         */
        fun identity(width: Int, height: Int): DistortionRemapGrid =
            DistortionRemapGrid(width, height).also {
                it.fillRows(
                    LensDistortionConfig(
                        enabled = false,
                        strength = 0f,
                        k1Override = 0f,
                        k2Override = 0f,
                        k3Override = 0f,
                    )
                )
            }
    }
}
