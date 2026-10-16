package com.hinnka.mycamera.enhance

import kotlin.math.roundToInt

/**
 * 经典后处理管线的执行阶段。
 *
 * 规范顺序见 [EnhanceConfig.stages]，顺序本身也是可配置的（[EnhanceConfig.stageOrder]）：
 * 1. [DENOISE]   先降噪：噪声若不去掉会被后续对比度/锐化放大；
 * 2. [DEHAZE]    去雾：雾是低频分量，放在锐化前更稳；
 * 3. [CLAHE]     局部对比度均衡；
 * 4. [SHARPEN]   反锐化掩模最后锐化（在降噪之后，避免把噪点锐成颗粒）；
 * 5. [FUN_FILTER] 趣味滤镜（移轴/素描/油画）做最终风格化。
 */
enum class EnhanceStage { DENOISE, DEHAZE, CLAHE, SHARPEN, FUN_FILTER }

/**
 * CLAHE（对比度受限自适应直方图均衡）配置。
 *
 * 算法移植自 opencv/opencv（Apache-2.0）`imgproc/src/clahe.cpp` 的思路：
 * 逐 tile 直方图 → 对比度裁剪 + 余量重分配 → CDF 映射表 → tile 间双线性插值。
 * OpenCV 原实现在「常量 tile」上会因 `tileArea - cdfMin == 0` 退化，本实现显式退化为恒等映射，
 * 保证均匀色块不会被压黑（见 [ClaheProcessor]）。
 */
data class ClaheConfig(
    val enabled: Boolean = true,
    /** tile 网格列数。范围 [1, 32]。 */
    val tilesX: Int = 8,
    /** tile 网格行数。范围 [1, 32]。 */
    val tilesY: Int = 8,
    /** 对比度限制倍数（相对 tile 平均 bin 高度）。范围 [0.1, 10]。 */
    val clipLimit: Float = 2.0f,
    /** true = 仅均衡亮度通道并按增益缩放 RGB（保色）；false = 逐 RGB 通道独立均衡（易串色）。 */
    val lumaOnly: Boolean = true,
    /** 均衡结果混合系数，0 = 原图，1 = 完全均衡。范围 [0, 1]。 */
    val mix: Float = 1f,
) {
    companion object {
        const val TILES_MIN = 1
        const val TILES_MAX = 32
        const val CLIP_LIMIT_MIN = 0.1f
        const val CLIP_LIMIT_MAX = 10f
        const val MIX_MIN = 0f
        const val MIX_MAX = 1f

        fun clamp(config: ClaheConfig): ClaheConfig = config.copy(
            tilesX = config.tilesX.coerceIn(TILES_MIN, TILES_MAX),
            tilesY = config.tilesY.coerceIn(TILES_MIN, TILES_MAX),
            clipLimit = finite(config.clipLimit, 2f).coerceIn(CLIP_LIMIT_MIN, CLIP_LIMIT_MAX),
            mix = finite(config.mix, 1f).coerceIn(MIX_MIN, MIX_MAX),
        )
    }
}

/**
 * 暗通道去雾的大气光 A 估计方式。
 *
 * 思路参考 He-Zhang/image_dehaze（MIT，Star≈380，仅参考思路不采代码）：
 * 取暗通道最亮的 0.1% 像素、在原图对应位置上取最亮值作为 A。
 */
enum class AtmosphereMode {
    /** He 等人原文：暗通道最亮 top-[DehazeConfig.atmospherePercentile] 像素对应原图 brightest。 */
    TOP_DARKEST,

    /** 直接对暗通道取高分位（更稳，抗孤立高光）。 */
    PERCENTILE,

    /** 固定大气光（拍摄已知雾况/批量处理时最可控）。 */
    FIXED,
}

/**
 * 暗通道先验去雾配置。
 *
 * 物理模型 I = J·t + A·(1 - t)，按 He et al. CVPR2009 的思路求解：
 * 暗通道 → 估计 A → 估计透射率 t = 1 - ω·minC(I/A) → 导向滤波细化 → J = (I - A)/max(t, t0) + A。
 * [strength] 为最终混合系数，0 表示完全保留原图（管线中可当总开关用）。
 */
data class DehazeConfig(
    val enabled: Boolean = false,
    /** 去雾强度（总滑杆）。范围 [0, 1]。 */
    val strength: Float = 0.6f,
    /** 透射率保留系数 ω，越大去雾越狠。范围 [0.5, 1.0]。 */
    val omega: Float = 0.95f,
    /** 暗通道窗口半径（像素），clamp 后强制为奇数。范围 [1, 31]。 */
    val patchRadius: Int = 7,
    /** 大气光估计方式。 */
    val atmosphereMode: AtmosphereMode = AtmosphereMode.TOP_DARKEST,
    /** [AtmosphereMode.TOP_DARKEST] / [AtmosphereMode.PERCENTILE] 的高分位。范围 [0.90, 1.0]。 */
    val atmospherePercentile: Float = 0.999f,
    /** [AtmosphereMode.FIXED] 下的大气光值（0..1）。范围 [0.4, 1.0]。 */
    val fixedAtmosphere: Float = 0.9f,
    /** 透射率下限 t0，防止分母趋零导致过曝。范围 [0.05, 0.5]。 */
    val minTransmission: Float = 0.1f,
    /** 透射率细化的导向滤波半径（0 = 不细化，直接用原始透射率）。范围 [0, 32]。 */
    val refineRadius: Int = 8,
    /** 导向滤波正则项 ε。范围 [1e-6, 1e-2]。 */
    val refineEpsilon: Float = 1e-4f,
) {
    companion object {
        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 1f
        const val OMEGA_MIN = 0.5f
        const val OMEGA_MAX = 1f
        const val PATCH_RADIUS_MIN = 1
        const val PATCH_RADIUS_MAX = 31
        const val PERCENTILE_MIN = 0.90f
        const val PERCENTILE_MAX = 1f
        const val FIXED_ATMOSPHERE_MIN = 0.4f
        const val FIXED_ATMOSPHERE_MAX = 1f
        const val MIN_TRANSMISSION_MIN = 0.05f
        const val MIN_TRANSMISSION_MAX = 0.5f
        const val REFINE_RADIUS_MIN = 0
        const val REFINE_RADIUS_MAX = 32
        const val REFINE_EPSILON_MIN = 1e-6f
        const val REFINE_EPSILON_MAX = 1e-2f

        fun clamp(config: DehazeConfig): DehazeConfig = config.copy(
            strength = finite(config.strength, 0.6f).coerceIn(STRENGTH_MIN, STRENGTH_MAX),
            omega = finite(config.omega, 0.95f).coerceIn(OMEGA_MIN, OMEGA_MAX),
            patchRadius = toOdd(config.patchRadius.coerceIn(PATCH_RADIUS_MIN, PATCH_RADIUS_MAX)),
            atmospherePercentile = finite(config.atmospherePercentile, 0.999f)
                .coerceIn(PERCENTILE_MIN, PERCENTILE_MAX),
            fixedAtmosphere = finite(config.fixedAtmosphere, 0.9f)
                .coerceIn(FIXED_ATMOSPHERE_MIN, FIXED_ATMOSPHERE_MAX),
            minTransmission = finite(config.minTransmission, 0.1f)
                .coerceIn(MIN_TRANSMISSION_MIN, MIN_TRANSMISSION_MAX),
            refineRadius = config.refineRadius.coerceIn(REFINE_RADIUS_MIN, REFINE_RADIUS_MAX),
            refineEpsilon = finite(config.refineEpsilon, 1e-4f)
                .coerceIn(REFINE_EPSILON_MIN, REFINE_EPSILON_MAX),
        )
    }
}

/**
 * 反锐化掩模（Unsharp Mask）锐化配置。
 *
 * out = I + amount·(I - blur(I))，模糊为三次分离式盒式滤波近似高斯。
 */
data class UnsharpMaskConfig(
    val enabled: Boolean = true,
    /** 模糊半径（像素，可为小数）。范围 [0.5, 20]。 */
    val radius: Float = 2f,
    /** 锐化强度。范围 [0, 3]；0 = 恒等。 */
    val amount: Float = 0.6f,
    /** 弱边抑制阈值（0..255 单位）：|I - blur| 小于该值不参与锐化，避免放大噪点。范围 [0, 64]。 */
    val threshold: Int = 2,
) {
    companion object {
        const val RADIUS_MIN = 0.5f
        const val RADIUS_MAX = 20f
        const val AMOUNT_MIN = 0f
        const val AMOUNT_MAX = 3f
        const val THRESHOLD_MIN = 0
        const val THRESHOLD_MAX = 64

        fun clamp(config: UnsharpMaskConfig): UnsharpMaskConfig = config.copy(
            radius = finite(config.radius, 2f).coerceIn(RADIUS_MIN, RADIUS_MAX),
            amount = finite(config.amount, 0.6f).coerceIn(AMOUNT_MIN, AMOUNT_MAX),
            threshold = config.threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX),
        )
    }
}

/**
 * 快速双边降噪配置。
 *
 * 「快速」体现在两点：① 值域权重只按亮度计算（三个通道共用权重，省 2/3 指数运算）；
 * ② 空间核用可分离的盒式核近似（[BilateralFilterProcessor]）。
 * σ_color 以 0..1 亮度为单位，便于与 UI 滑杆 1:1 映射。
 */
data class BilateralConfig(
    val enabled: Boolean = true,
    /** 邻域半径（像素）。范围 [1, 12]。 */
    val radius: Int = 3,
    /** 空间域 σ（像素）。范围 [1, 32]。 */
    val sigmaSpace: Float = 8f,
    /** 值域 σ（亮度 0..1 单位）。范围 [0.01, 1]。 */
    val sigmaColor: Float = 0.12f,
    /** 降噪强度（输出混合系数）。范围 [0, 1]。 */
    val strength: Float = 0.8f,
) {
    companion object {
        const val RADIUS_MIN = 1
        const val RADIUS_MAX = 12
        const val SIGMA_SPACE_MIN = 1f
        const val SIGMA_SPACE_MAX = 32f
        const val SIGMA_COLOR_MIN = 0.01f
        const val SIGMA_COLOR_MAX = 1f
        const val STRENGTH_MIN = 0f
        const val STRENGTH_MAX = 1f

        fun clamp(config: BilateralConfig): BilateralConfig = config.copy(
            radius = config.radius.coerceIn(RADIUS_MIN, RADIUS_MAX),
            sigmaSpace = finite(config.sigmaSpace, 8f).coerceIn(SIGMA_SPACE_MIN, SIGMA_SPACE_MAX),
            sigmaColor = finite(config.sigmaColor, 0.12f).coerceIn(SIGMA_COLOR_MIN, SIGMA_COLOR_MAX),
            strength = finite(config.strength, 0.8f).coerceIn(STRENGTH_MIN, STRENGTH_MAX),
        )
    }
}

/**
 * 经典后处理 + 趣味滤镜的统一配置入口。
 *
 * 开放度约定（PATTERN_SPEC 第 1.6 节）：所有可调参数均有「默认值 + 取值范围 + 越界 clamp」，
 * 越界/NaN/Inf 一律被 [clampWithReport] 修正并记录，调用方必须使用 clamp 后的配置。
 *
 * 读取路径：DataStore（`user_preferences`）中以 `enhance_` 前缀存浮点/整型，
 * 经 [fromPreferences] 解析；key 清单见交付补丁 `delivery/patches/enhance_patch.md` 的 CONFIG 节。
 */
data class EnhanceConfig(
    /** 模块总开关。false 时 [stages] 返回空列表。 */
    val enabled: Boolean = true,
    /**
     * 工作分辨率缩放（1.0 = 全分辨率）。范围 [0.1, 1]。
     * 内存不足时由 [EnhancePipeline.resolveWorkingScale] 自动降级，不失败。
     */
    val workingScale: Float = 1f,
    /** 执行顺序；默认 [DEFAULT_STAGE_ORDER]。允许调用方重排（如先锐化后降噪）。 */
    val stageOrder: List<EnhanceStage> = DEFAULT_STAGE_ORDER,
    val clahe: ClaheConfig = ClaheConfig(),
    val dehaze: DehazeConfig = DehazeConfig(),
    val sharpen: UnsharpMaskConfig = UnsharpMaskConfig(),
    val denoise: BilateralConfig = BilateralConfig(),
    val funFilter: FunFilterConfig = FunFilterConfig(),
) {
    /** 当前配置下实际会被执行、且已在 [stageOrder] 中声明的阶段（去重保序）。 */
    fun stages(): List<EnhanceStage> {
        if (!enabled) return emptyList()
        val active = buildSet {
            if (denoise.enabled) add(EnhanceStage.DENOISE)
            if (dehaze.enabled) add(EnhanceStage.DEHAZE)
            if (clahe.enabled) add(EnhanceStage.CLAHE)
            if (sharpen.enabled) add(EnhanceStage.SHARPEN)
            if (funFilter.enabled && funFilter.kind != FunFilterKind.NONE) add(EnhanceStage.FUN_FILTER)
        }
        return stageOrder.distinct().filter { it in active }
    }

    /** 是否有任何阶段会真正执行（空管线时调用方可直接跳过，省一次解码）。 */
    fun isActive(): Boolean = stages().isNotEmpty()

    /**
     * 全量 clamp：返回修正后的配置 + 被修正项的描述列表（供 PLog 记录）。
     */
    fun clampWithReport(): Pair<EnhanceConfig, List<String>> {
        val reports = mutableListOf<String>()
        fun note(name: String, before: Any?, after: Any?) {
            if (before != after) reports += "$name: $before -> $after"
        }

        val clampedClahe = ClaheConfig.clamp(clahe)
        note("clahe.tilesX", clahe.tilesX, clampedClahe.tilesX)
        note("clahe.tilesY", clahe.tilesY, clampedClahe.tilesY)
        note("clahe.clipLimit", clahe.clipLimit, clampedClahe.clipLimit)
        note("clahe.mix", clahe.mix, clampedClahe.mix)

        val clampedDehaze = DehazeConfig.clamp(dehaze)
        note("dehaze.strength", dehaze.strength, clampedDehaze.strength)
        note("dehaze.omega", dehaze.omega, clampedDehaze.omega)
        note("dehaze.patchRadius", dehaze.patchRadius, clampedDehaze.patchRadius)
        note("dehaze.atmospherePercentile", dehaze.atmospherePercentile, clampedDehaze.atmospherePercentile)
        note("dehaze.fixedAtmosphere", dehaze.fixedAtmosphere, clampedDehaze.fixedAtmosphere)
        note("dehaze.minTransmission", dehaze.minTransmission, clampedDehaze.minTransmission)
        note("dehaze.refineRadius", dehaze.refineRadius, clampedDehaze.refineRadius)
        note("dehaze.refineEpsilon", dehaze.refineEpsilon, clampedDehaze.refineEpsilon)

        val clampedSharpen = UnsharpMaskConfig.clamp(sharpen)
        note("sharpen.radius", sharpen.radius, clampedSharpen.radius)
        note("sharpen.amount", sharpen.amount, clampedSharpen.amount)
        note("sharpen.threshold", sharpen.threshold, clampedSharpen.threshold)

        val clampedDenoise = BilateralConfig.clamp(denoise)
        note("denoise.radius", denoise.radius, clampedDenoise.radius)
        note("denoise.sigmaSpace", denoise.sigmaSpace, clampedDenoise.sigmaSpace)
        note("denoise.sigmaColor", denoise.sigmaColor, clampedDenoise.sigmaColor)
        note("denoise.strength", denoise.strength, clampedDenoise.strength)

        val (clampedFun, funReports) = funFilter.clampWithReport()
        reports += funReports

        val scale = finite(workingScale, 1f).coerceIn(WORKING_SCALE_MIN, WORKING_SCALE_MAX)
        note("workingScale", workingScale, scale)

        val order = stageOrder.ifEmpty { DEFAULT_STAGE_ORDER }
        if (order != stageOrder) reports += "stageOrder: $stageOrder -> $order"

        return EnhanceConfig(
            enabled = enabled,
            workingScale = scale,
            stageOrder = order,
            clahe = clampedClahe,
            dehaze = clampedDehaze,
            sharpen = clampedSharpen,
            denoise = clampedDenoise,
            funFilter = clampedFun,
        ) to reports
    }

    companion object {
        const val WORKING_SCALE_MIN = 0.1f
        const val WORKING_SCALE_MAX = 1f

        /** 管线规范顺序（先降噪 → 去雾 → 局部对比度 → 锐化 → 风格化）。 */
        val DEFAULT_STAGE_ORDER: List<EnhanceStage> = listOf(
            EnhanceStage.DENOISE,
            EnhanceStage.DEHAZE,
            EnhanceStage.CLAHE,
            EnhanceStage.SHARPEN,
            EnhanceStage.FUN_FILTER,
        )

        val DEFAULT = EnhanceConfig()

        /**
         * 从「key → 值」映射构建配置（DataStore 读取后由集成层转换；也用于后台任务参数）。
         * 缺失的 key 用默认值；类型不符或非有限值直接忽略（回默认），不抛异常。
         */
        fun fromPreferences(values: Map<String, Any?>): EnhanceConfig {
            val base = DEFAULT
            return EnhanceConfig(
                enabled = EnhancePrefs.bool(values, KEY_ENABLED, base.enabled),
                workingScale = EnhancePrefs.float(values, KEY_WORKING_SCALE, base.workingScale),
                clahe = base.clahe.copy(
                    enabled = EnhancePrefs.bool(values, KEY_CLAHE_ENABLED, base.clahe.enabled),
                    tilesX = EnhancePrefs.int(values, KEY_CLAHE_TILES_X, base.clahe.tilesX),
                    tilesY = EnhancePrefs.int(values, KEY_CLAHE_TILES_Y, base.clahe.tilesY),
                    clipLimit = EnhancePrefs.float(values, KEY_CLAHE_CLIP_LIMIT, base.clahe.clipLimit),
                    lumaOnly = EnhancePrefs.bool(values, KEY_CLAHE_LUMA_ONLY, base.clahe.lumaOnly),
                    mix = EnhancePrefs.float(values, KEY_CLAHE_MIX, base.clahe.mix),
                ),
                dehaze = base.dehaze.copy(
                    enabled = EnhancePrefs.bool(values, KEY_DEHAZE_ENABLED, base.dehaze.enabled),
                    strength = EnhancePrefs.float(values, KEY_DEHAZE_STRENGTH, base.dehaze.strength),
                    omega = EnhancePrefs.float(values, KEY_DEHAZE_OMEGA, base.dehaze.omega),
                    patchRadius = EnhancePrefs.int(values, KEY_DEHAZE_PATCH_RADIUS, base.dehaze.patchRadius),
                    atmosphereMode = EnhancePrefs.enum(values, KEY_DEHAZE_ATMOSPHERE_MODE, base.dehaze.atmosphereMode),
                    atmospherePercentile = EnhancePrefs.float(
                        values, KEY_DEHAZE_ATMOSPHERE_PERCENTILE, base.dehaze.atmospherePercentile
                    ),
                    fixedAtmosphere = EnhancePrefs.float(
                        values, KEY_DEHAZE_FIXED_ATMOSPHERE, base.dehaze.fixedAtmosphere
                    ),
                    minTransmission = EnhancePrefs.float(
                        values, KEY_DEHAZE_MIN_TRANSMISSION, base.dehaze.minTransmission
                    ),
                    refineRadius = EnhancePrefs.int(values, KEY_DEHAZE_REFINE_RADIUS, base.dehaze.refineRadius),
                    refineEpsilon = EnhancePrefs.float(
                        values, KEY_DEHAZE_REFINE_EPSILON, base.dehaze.refineEpsilon
                    ),
                ),
                sharpen = base.sharpen.copy(
                    enabled = EnhancePrefs.bool(values, KEY_SHARPEN_ENABLED, base.sharpen.enabled),
                    radius = EnhancePrefs.float(values, KEY_SHARPEN_RADIUS, base.sharpen.radius),
                    amount = EnhancePrefs.float(values, KEY_SHARPEN_AMOUNT, base.sharpen.amount),
                    threshold = EnhancePrefs.int(values, KEY_SHARPEN_THRESHOLD, base.sharpen.threshold),
                ),
                denoise = base.denoise.copy(
                    enabled = EnhancePrefs.bool(values, KEY_DENOISE_ENABLED, base.denoise.enabled),
                    radius = EnhancePrefs.int(values, KEY_DENOISE_RADIUS, base.denoise.radius),
                    sigmaSpace = EnhancePrefs.float(values, KEY_DENOISE_SIGMA_SPACE, base.denoise.sigmaSpace),
                    sigmaColor = EnhancePrefs.float(values, KEY_DENOISE_SIGMA_COLOR, base.denoise.sigmaColor),
                    strength = EnhancePrefs.float(values, KEY_DENOISE_STRENGTH, base.denoise.strength),
                ),
                funFilter = FunFilterConfig.fromPreferences(values),
            ).let { it.clampWithReport().first }
        }

        // DataStore key ------------------------------------------------------------------
        const val KEY_ENABLED = "enhance_enabled"
        const val KEY_WORKING_SCALE = "enhance_working_scale"

        const val KEY_CLAHE_ENABLED = "enhance_clahe_enabled"
        const val KEY_CLAHE_TILES_X = "enhance_clahe_tiles_x"
        const val KEY_CLAHE_TILES_Y = "enhance_clahe_tiles_y"
        const val KEY_CLAHE_CLIP_LIMIT = "enhance_clahe_clip_limit"
        const val KEY_CLAHE_LUMA_ONLY = "enhance_clahe_luma_only"
        const val KEY_CLAHE_MIX = "enhance_clahe_mix"

        const val KEY_DEHAZE_ENABLED = "enhance_dehaze_enabled"
        const val KEY_DEHAZE_STRENGTH = "enhance_dehaze_strength"
        const val KEY_DEHAZE_OMEGA = "enhance_dehaze_omega"
        const val KEY_DEHAZE_PATCH_RADIUS = "enhance_dehaze_patch_radius"
        const val KEY_DEHAZE_ATMOSPHERE_MODE = "enhance_dehaze_atmosphere_mode"
        const val KEY_DEHAZE_ATMOSPHERE_PERCENTILE = "enhance_dehaze_atmosphere_percentile"
        const val KEY_DEHAZE_FIXED_ATMOSPHERE = "enhance_dehaze_fixed_atmosphere"
        const val KEY_DEHAZE_MIN_TRANSMISSION = "enhance_dehaze_min_transmission"
        const val KEY_DEHAZE_REFINE_RADIUS = "enhance_dehaze_refine_radius"
        const val KEY_DEHAZE_REFINE_EPSILON = "enhance_dehaze_refine_epsilon"

        const val KEY_SHARPEN_ENABLED = "enhance_sharpen_enabled"
        const val KEY_SHARPEN_RADIUS = "enhance_sharpen_radius"
        const val KEY_SHARPEN_AMOUNT = "enhance_sharpen_amount"
        const val KEY_SHARPEN_THRESHOLD = "enhance_sharpen_threshold"

        const val KEY_DENOISE_ENABLED = "enhance_denoise_enabled"
        const val KEY_DENOISE_RADIUS = "enhance_denoise_radius"
        const val KEY_DENOISE_SIGMA_SPACE = "enhance_denoise_sigma_space"
        const val KEY_DENOISE_SIGMA_COLOR = "enhance_denoise_sigma_color"
        const val KEY_DENOISE_STRENGTH = "enhance_denoise_strength"
    }
}

/** 把非有限值替换为回退值，再参与 clamp（NaN 的 coerceIn 结果仍是 NaN，必须先过滤）。 */
internal fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value else fallback

/** 强制为奇数（暗通道窗口等要求奇数边长）。 */
internal fun toOdd(value: Int): Int = if (value % 2 == 0) value + 1 else value

/**
 * DataStore 值解析工具：类型不符 / 非有限值一律回退默认值，绝不抛异常。
 * 纯 Kotlin（无 Android 依赖），可直接单测。
 */
internal object EnhancePrefs {
    fun float(values: Map<String, Any?>, key: String, fallback: Float): Float {
        val raw = values[key] ?: return fallback
        val parsed = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            is Boolean -> if (raw) 1f else 0f
            else -> null
        } ?: return fallback
        return if (parsed.isFinite()) parsed else fallback
    }

    fun int(values: Map<String, Any?>, key: String, fallback: Int): Int {
        val raw = values[key] ?: return fallback
        val parsed = when (raw) {
            is Number -> raw.toFloat().takeIf { it.isFinite() }?.roundToInt()
            is String -> raw.toFloatOrNull()?.takeIf { it.isFinite() }?.roundToInt()
            is Boolean -> if (raw) 1 else 0
            else -> null
        }
        return parsed ?: fallback
    }

    fun bool(values: Map<String, Any?>, key: String, fallback: Boolean): Boolean {
        val raw = values[key] ?: return fallback
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toFloat().takeIf { it.isFinite() }?.let { it != 0f } ?: fallback
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> fallback
            }
            else -> fallback
        }
    }

    /** 枚举按 name 解析（大小写不敏感）；无法解析回退默认，避免旧数据导致崩溃。 */
    inline fun <reified T : Enum<T>> enum(values: Map<String, Any?>, key: String, fallback: T): T {
        val raw = values[key] as? String ?: return fallback
        return enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback
    }
}
