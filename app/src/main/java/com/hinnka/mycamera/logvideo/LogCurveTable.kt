package com.hinnka.mycamera.logvideo

/**
 * Log 曲线查表（Log 视频 20 项 · 第 1 项「查表实现」）。
 *
 * 表轴 = **归一化场景线性输入** `t ∈ [0,1]`（真实线性输入 = `t * recipe.peak`）；
 * 表值 = [LogCurveRecipe.encode] 的 Log 代码值。表按 [LogCurveRecipe.tableSize] 分辨率构建。
 *
 * 用途（三处消费同一个表，保证预览/录制/烘焙完全一致）：
 *  1. GPU 1D 曲线纹理数据源（`toCurvePoints()` → 走工程既有 `CurveUtils.buildCurveTextureBuffer` 通道，
 *     补丁把它接在 `uVideoLogEnabled` 块内，见 patch 的 SHADER 段）；
 *  2. Log→Rec709 3D LUT 烘焙时的曲线半边（[LogToRec709LutBaker] 直接用 recipe 解析式 + 本表校验）；
 *  3. CPU 侧 Log 实况照片静止帧编码（[LogVideoFrameProcessor]）。
 *
 * 全部为纯 Kotlin（无 Android 依赖），可直接 JVM 单测逐点比对。
 */
class LogCurveTable private constructor(
    val recipe: LogCurveRecipe,
    private val samples: FloatArray,
) {
    val size: Int get() = samples.size

    /** 归一化输入（t ∈ [0,1]）→ Log 代码，线性插值。 */
    fun sampleNormalized(t: Float): Float {
        if (size == 1) return samples[0]
        val position = t.coerceIn(0f, 1f) * (size - 1)
        val index = position.toInt().coerceIn(0, size - 2)
        val fraction = position - index
        val a = samples[index]
        val b = samples[index + 1]
        return a + (b - a) * fraction
    }

    /** 场景线性输入 → Log 代码（内部按 `peak` 归一化）。 */
    fun sample(sceneLinear: Float): Float {
        val peak = recipe.peak.coerceIn(LogCurveRecipe.PEAK_MIN, LogCurveRecipe.PEAK_MAX)
        return sampleNormalized(sceneLinear / peak)
    }

    /** 表原始样本副本（上传 GL 纹理 / 落盘缓存用，长度 = [size]）。 */
    fun samplesCopy(): FloatArray = samples.copyOf()

    /**
     * 取样为曲线控制点（长度 [pointCount]，单调递增输入）。
     * 直接可喂工程既有 1D 曲线通道（`ColorRecipeParams.masterCurvePoints`），**复用既有渲染**。
     */
    fun toCurvePoints(pointCount: Int): FloatArray {
        val count = pointCount.coerceAtLeast(2)
        return FloatArray(count) { index -> sampleNormalized(index.toFloat() / (count - 1)) }
    }

    /** 单调性自检：Log 曲线在 slope>0 下必须单调不减（负 slope 由 clamp 排除）。 */
    fun isMonotonicNonDecreasing(): Boolean {
        for (index in 1 until size) {
            if (samples[index] < samples[index - 1] - 1e-6f) return false
        }
        return true
    }

    /** 与上一个样本的最大跳变（用于诊断「曲线不平滑/分辨率过低」）。 */
    fun maxAdjacentDelta(): Float {
        var max = 0f
        for (index in 1 until size) {
            val delta = kotlin.math.abs(samples[index] - samples[index - 1])
            if (delta > max) max = delta
        }
        return max
    }

    override fun equals(other: Any?): Boolean =
        other is LogCurveTable && other.recipe == recipe && other.samples.contentEquals(samples)

    override fun hashCode(): Int = 31 * recipe.hashCode() + samples.contentHashCode()

    override fun toString(): String =
        "LogCurveTable(preset=${recipe.preset}, size=$size, black=${recipe.blackLevel}, peak=${recipe.peak}, slope=${recipe.slope})"

    companion object {
        /**
         * 构建查表。recipe 先 clamp（配置开放，任何脏值都不崩）。
         *
         * @param onProgress 分片进度回调 `(已完成, 总数)`，供 `BgJobRunner` 上报；
         *        传 null 时一次性构建（预览线程小表场景）。
         */
        fun build(
            recipe: LogCurveRecipe,
            onProgress: ((done: Int, total: Int) -> Unit)? = null,
        ): LogCurveTable {
            val safe = recipe.clamped()
            val count = safe.tableSize
            val samples = FloatArray(count)
            val last = (count - 1).coerceAtLeast(1)
            for (index in 0 until count) {
                // 输入 t = index / last（归一化场景线性），真实线性输入 = t * peak
                val normalized = index.toFloat() / last
                samples[index] = safe.encode(normalized * safe.peak)
                if (onProgress != null && index % PROGRESS_STRIDE == 0) {
                    onProgress(index + 1, count)
                }
            }
            onProgress?.invoke(count, count)
            return LogCurveTable(safe, samples)
        }

        /** 恒等曲线（`blackLevel=0, peak=1, slope=1` 且预设为 SRGB 近似直线）；用于降级/对照。 */
        fun identity(size: Int = 1024): LogCurveTable {
            val count = size.coerceIn(LogCurveRecipe.TABLE_SIZE_MIN, LogCurveRecipe.TABLE_SIZE_MAX)
            val samples = FloatArray(count) { it.toFloat() / (count - 1) }
            return LogCurveTable(
                LogCurveRecipe(
                    preset = LogCurvePreset.LOG_C3,
                    blackLevel = 0f,
                    peak = 1f,
                    slope = 1f,
                    tableSize = count,
                ),
                samples,
            )
        }

        /** 进度上报步长（每 N 个样本回调一次，避免高频回调）。 */
        const val PROGRESS_STRIDE = 128
    }
}

/**
 * 当前生效的 Log 曲线表（进程内缓存，避免每次预览/录制重建）。
 *
 * 由 [LogVideoBakeJob] 在后台线程发布、由 GL 线程读取：
 * 只做「不可变对象整体替换 + @Volatile」，无需锁、无撕裂。
 */
object ActiveLogCurveTable {
    @Volatile
    private var table: LogCurveTable? = null

    /** 当前表；未发布时按默认配方即时构建（首次调用为 O(tableSize)，之后走缓存）。 */
    fun current(recipe: LogCurveRecipe = LogCurveRecipe.DEFAULT): LogCurveTable {
        table?.let { if (it.recipe == recipe.clamped()) return it }
        return LogCurveTable.build(recipe).also { table = it }
    }

    /** 后台线程发布新表；返回值用于日志。 */
    fun publish(newTable: LogCurveTable): String {
        table = newTable
        return "ActiveLogCurveTable ← ${newTable}"
    }

    /** 仅供测试/重置。 */
    fun clear() {
        table = null
    }
}
