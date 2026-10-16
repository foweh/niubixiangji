package com.hinnka.mycamera.enhance

/**
 * 趣味滤镜分发器：按 [FunFilterConfig.kind] 选择具体滤镜。
 *
 * 统一入口的意义：管线（[EnhancePipeline]）与 UI 只需面对一个配置对象，
 * 新增滤镜时只改这里 + 枚举，不动调用方。
 *
 * 纯 Kotlin（无 Android 依赖），可直接 JVM 单测。
 */
object FunFilterProcessor {

    /**
     * 按配置渲染趣味滤镜，返回新数组（不改入参）。
     * `kind = NONE` 或总开关关闭时严格返回输入副本（恒等）。
     *
     * @param bandHeight 行带高度；<= 0 表示不分片。
     * @param onChunk 每个分片完成后回调（集成层传 `{ job.checkYield() }`）。
     */
    fun process(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: FunFilterConfig,
        bandHeight: Int = 0,
        onChunk: (() -> Unit)? = null,
    ): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val clamped = config.clampWithReport().first
        if (!clamped.isActive()) {
            onChunk?.invoke()
            return pixels.copyOf()
        }
        return when (clamped.kind) {
            FunFilterKind.NONE -> pixels.copyOf()
            FunFilterKind.TILT_SHIFT ->
                TiltShiftFilter.process(pixels, width, height, clamped.tiltShift)
            FunFilterKind.SKETCH ->
                SketchFilter.process(pixels, width, height, clamped.sketch)
            FunFilterKind.OIL_PAINT ->
                OilPaintFilter.process(pixels, width, height, clamped.oilPaint, bandHeight, onChunk)
        }.also { if (clamped.kind != FunFilterKind.OIL_PAINT) onChunk?.invoke() }
    }
}
