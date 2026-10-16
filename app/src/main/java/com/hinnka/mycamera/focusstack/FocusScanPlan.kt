package com.hinnka.mycamera.focusstack

/**
 * 单步对焦位置。
 *
 * @property index 帧序号（0 基，捕获顺序）。
 * @property diopters 目标对焦距离（屈光度，Camera2 的 LENS_FOCUS_DISTANCE 单位）。
 *   0 = 无穷远；数值越大越近（1/diopters = 米，例如 10 diopters = 10cm）。
 * @property fraction 在扫描区间内的归一化位置（0 = 起点，1 = 终点）。
 */
data class FocusScanStep(
    val index: Int,
    val diopters: Float,
    val fraction: Float,
)

/**
 * 一次焦点扫描计划（把「用户参数」翻译成每一步的 LENS_FOCUS_DISTANCE）。
 *
 * @property steps 按捕获顺序排列的步进（长度 = 帧数）。
 * @property nearDiopters 起点（近端）。
 * @property farDiopters 终点（远端）。
 * @property clampedFrom 被 clamp 修正过的参数描述（供日志，空 = 无修正）。
 */
data class FocusScanPlan(
    val steps: List<FocusScanStep>,
    val nearDiopters: Float,
    val farDiopters: Float,
    val clampedFrom: List<String> = emptyList(),
) {
    val frameCount: Int get() = steps.size

    /** 是否为空计划（镜头不支持变焦/对焦或帧数非法）。 */
    val isEmpty: Boolean get() = steps.isEmpty()

    /**
     * 帧序号 → 对焦距离（越界返回 null，调用方回退自动对焦）。
     */
    fun dioptersAt(index: Int): Float? = steps.getOrNull(index)?.diopters
}

/**
 * 焦点扫描规划器 —— 把 [FocusStackConfig] 的（起点 / 终点 / 步数 / 自动近到远）翻译成逐步屈光度。
 *
 * 物理约定（Camera2）：
 * - `LENS_INFO_MINIMUM_FOCUS_DISTANCE`（diopters）是该镜头**能对焦的最近距离**（0 = 定焦/仅无穷远）；
 * - `LENS_FOCUS_DISTANCE` 可写范围是 `[0, minimumFocusDistance]`，0 = 无穷远。
 *
 * 因此「微距焦点堆叠」的扫描区间是 `[0（或用户给定远端）, minimumFocusDistance（近端）]`；
 * 若镜头上报 `minimumFocusDistance <= 0`（定焦微距镜头的常见情况：GC02M1 类微距多为定焦），
 * 本规划器返回空计划，由调用方改为「物理移动机身/被摄物」的手动序列模式（见补丁 NOTES）。
 *
 * 纯 Kotlin、无 Android 依赖，可直接 JVM 单测。
 */
object FocusScanPlanner {

    /** 镜头能力未知时的保守最近对焦距离（10cm ≈ 10 屈光度），仅用于「自动」兜底。 */
    const val FALLBACK_MIN_FOCUS_DIOPTERS = 10f

    /**
     * 规划一次扫描。
     *
     * @param config 已 clamp 或未 clamp 的配置（内部会再 clamp，保证不崩）。
     * @param minimumFocusDistanceDiopters 镜头上报的最近对焦距离（0 = 定焦）。
     * @return 扫描计划；镜头不支持变焦时返回空计划（[FocusScanPlan.isEmpty] == true）。
     */
    fun plan(
        config: FocusStackConfig,
        minimumFocusDistanceDiopters: Float,
    ): FocusScanPlan {
        val (cfg, reports) = config.clampWithReport()
        val frames = cfg.frameCount

        val minFocus = if (minimumFocusDistanceDiopters.isFinite() && minimumFocusDistanceDiopters > 0f) {
            minimumFocusDistanceDiopters
        } else {
            0f
        }

        if (minFocus <= 0f) {
            // 定焦微距镜头：没有可写的对焦距离，交给「手动序列」模式。
            return FocusScanPlan(
                steps = emptyList(),
                nearDiopters = 0f,
                farDiopters = 0f,
                clampedFrom = reports + "minimumFocusDistance<=0（定焦）→ 空计划",
            )
        }

        // 远端（diopters 小者）
        val far = when {
            cfg.focusStartDiopters > 0f -> cfg.focusStartDiopters.coerceAtMost(minFocus)
            else -> 0f
        }
        // 近端（diopters 大者）
        val near = if (cfg.autoNearToFar || cfg.focusEndDiopters < 0f) {
            minFocus
        } else {
            cfg.focusEndDiopters.coerceIn(far, minFocus)
        }

        if (near <= far) {
            // 区间退化（例如用户把起点设到最近处）：退化为单点扫描，仍给出 frames 个相同步进，
            // 保证「帧数 = 用户配置」不被静默改写；融合侧对全同对焦帧会退化为均值降噪。
            val flat = List(frames) { FocusScanStep(it, near, 0f) }
            return FocusScanPlan(
                steps = flat,
                nearDiopters = near,
                farDiopters = far,
                clampedFrom = reports + "扫描区间退化(near<=far) → 单点重复",
            )
        }

        // 近 → 远 均匀铺满（近端在前，符合「先拍近处细节再拉到远景」的操作直觉）。
        val steps = ArrayList<FocusScanStep>(frames)
        for (i in 0 until frames) {
            val fraction = if (frames <= 1) 0f else i.toFloat() / (frames - 1).toFloat()
            val diopters = near + (far - near) * fraction
            steps += FocusScanStep(
                index = i,
                diopters = diopters.coerceIn(far, near),
                fraction = fraction,
            )
        }
        return FocusScanPlan(steps = steps, nearDiopters = near, farDiopters = far, clampedFrom = reports)
    }

    /**
     * 自动近到远的步进序列（不依赖镜头能力，供「手动序列」模式按提示推送对焦距离，
     * 或供 UI 展示取景提示）。
     */
    fun autoNearToFar(config: FocusStackConfig): FocusScanPlan {
        val (cfg, reports) = config.clampWithReport()
        val frames = cfg.frameCount
        val near = FALLBACK_MIN_FOCUS_DIOPTERS.coerceIn(
            FocusStackConfig.FOCUS_DIOPTERS_MIN,
            FocusStackConfig.FOCUS_DIOPTERS_MAX,
        )
        val steps = ArrayList<FocusScanStep>(frames)
        for (i in 0 until frames) {
            val fraction = if (frames <= 1) 0f else i.toFloat() / (frames - 1).toFloat()
            // 近 → 远：屈光度从大到小。
            steps += FocusScanStep(i, near * (1f - fraction), fraction)
        }
        return FocusScanPlan(
            steps = steps,
            nearDiopters = near,
            farDiopters = 0f,
            clampedFrom = reports,
        )
    }

    /** 屈光度 → 米（便于 UI 显示；0 表示为无穷远）。 */
    fun dioptersToMeters(diopters: Float): Float {
        if (!diopters.isFinite() || diopters <= 0f) return Float.POSITIVE_INFINITY
        return 1f / diopters
    }

    /** 米 → 屈光度（0 表示无穷远）。 */
    fun metersToDiopters(meters: Float): Float {
        if (!meters.isFinite() || meters <= 0f) return 0f
        return 1f / meters
    }
}
