package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.enhance.AtmosphereMode
import com.hinnka.mycamera.enhance.BilateralConfig
import com.hinnka.mycamera.enhance.ClaheConfig
import com.hinnka.mycamera.enhance.DehazeConfig
import com.hinnka.mycamera.enhance.EnhanceConfig
import com.hinnka.mycamera.enhance.UnsharpMaskConfig
import com.hinnka.mycamera.lenscorrection.DistortionEdgePolicy
import com.hinnka.mycamera.lenscorrection.LensCameraDimension
import com.hinnka.mycamera.lenscorrection.LensCorrectionSettings
import com.hinnka.mycamera.lenscorrection.LensDistortionConfig

/**
 * 目录①：画质增强（`enhance`）+ 镜头畸变校正（`lenscorrection`）。
 *
 * 取值范围与默认值**全部取自模块 Config 常量**，此处只做「搬运 + 分组 + 文案」，
 * 不重新定义任何业务边界；单测 `ProSettingsClampConsistencyTest` 会把这些值与模块常量逐一比对。
 *
 * enhance 的键由 `EnhanceConfig.Companion.KEY_*` 给定（落既有 `user_preferences`，键前缀 `enhance_`）；
 * lenscorrection 的键由 `LensCorrectionSettings.keyOf(维度, 字段)` 生成（前缀 `lens_correction_`）。
 */
object ProSettingsCatalogImage {

    val entries: List<ProSettingSpec> = buildList {

        // ───────────────────────────────────────── 画质增强（enhance）

        add(ProSettingBuilder.sw("enhance.enabled", EnhanceConfig.KEY_ENABLED, "画质增强总开关",
            "在线增强链总开关（CLAHE / 去雾 / 锐化 / 双边降噪）；模块默认开，接入首版建议先关", true, "GCam,OpenCam"))
        add(ProSettingBuilder.fl("enhance.workingScale", EnhanceConfig.KEY_WORKING_SCALE, "工作分辨率系数",
            "1.0 = 全分辨率；越小越快，细节略降", 1f,
            EnhanceConfig.WORKING_SCALE_MIN, EnhanceConfig.WORKING_SCALE_MAX, 2, "", "GCam"))

        add(ProSettingBuilder.sw("enhance.clahe.enabled", EnhanceConfig.KEY_CLAHE_ENABLED, "CLAHE 局部对比度",
            "限制对比度的自适应直方图均衡", true, "GCam,OpenCam"))
        add(ProSettingBuilder.it("enhance.clahe.tilesX", EnhanceConfig.KEY_CLAHE_TILES_X, "CLAHE 网格宽",
            "横向 tile 数", 8, ClaheConfig.TILES_MIN, ClaheConfig.TILES_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.it("enhance.clahe.tilesY", EnhanceConfig.KEY_CLAHE_TILES_Y, "CLAHE 网格高",
            "纵向 tile 数", 8, ClaheConfig.TILES_MIN, ClaheConfig.TILES_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.clahe.clipLimit", EnhanceConfig.KEY_CLAHE_CLIP_LIMIT, "CLAHE 对比度上限",
            "越大对比越强，过大会放大噪点", 2f, ClaheConfig.CLIP_LIMIT_MIN, ClaheConfig.CLIP_LIMIT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.sw("enhance.clahe.lumaOnly", EnhanceConfig.KEY_CLAHE_LUMA_ONLY, "CLAHE 仅亮度",
            "只在 Y 通道做，避免偏色", true, "GCam"))
        add(ProSettingBuilder.fl("enhance.clahe.mix", EnhanceConfig.KEY_CLAHE_MIX, "CLAHE 混合强度",
            "与原图的混合比例", 1f, ClaheConfig.MIX_MIN, ClaheConfig.MIX_MAX, 2, "", "GCam"))

        add(ProSettingBuilder.sw("enhance.dehaze.enabled", EnhanceConfig.KEY_DEHAZE_ENABLED, "暗通道去雾",
            "雾霾 / 逆光场景的对比度恢复；默认关", false, "GCam,OpenCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.strength", EnhanceConfig.KEY_DEHAZE_STRENGTH, "去雾强度",
            "0 = 不改动，1 = 完全应用", 0.6f,
            DehazeConfig.STRENGTH_MIN, DehazeConfig.STRENGTH_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.omega", EnhanceConfig.KEY_DEHAZE_OMEGA, "去雾保留率 ω",
            "暗通道保留比例，越大去雾越狠", 0.95f, DehazeConfig.OMEGA_MIN, DehazeConfig.OMEGA_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("enhance.dehaze.patchRadius", EnhanceConfig.KEY_DEHAZE_PATCH_RADIUS, "去雾窗口半径",
            "clamp 后强制奇数，保证窗口有中心", 7,
            DehazeConfig.PATCH_RADIUS_MIN, DehazeConfig.PATCH_RADIUS_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.dd("enhance.dehaze.atmosphereMode", EnhanceConfig.KEY_DEHAZE_ATMOSPHERE_MODE,
            "大气光估计方式", "TOP_DARKEST = 取最暗 0.1%；PERCENTILE = 按分位；FIXED = 固定值",
            AtmosphereMode.TOP_DARKEST.name, AtmosphereMode.entries.map { it.name }, "GCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.atmospherePercentile", EnhanceConfig.KEY_DEHAZE_ATMOSPHERE_PERCENTILE,
            "大气光分位", "仅 PERCENTILE 模式生效", 0.999f,
            DehazeConfig.PERCENTILE_MIN, DehazeConfig.PERCENTILE_MAX, 3, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.fixedAtmosphere", EnhanceConfig.KEY_DEHAZE_FIXED_ATMOSPHERE,
            "固定大气光", "仅 FIXED 模式生效", 0.9f,
            DehazeConfig.FIXED_ATMOSPHERE_MIN, DehazeConfig.FIXED_ATMOSPHERE_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.minTransmission", EnhanceConfig.KEY_DEHAZE_MIN_TRANSMISSION,
            "最小透射率", "下限越大越保守，避免暗部死黑", 0.1f,
            DehazeConfig.MIN_TRANSMISSION_MIN, DehazeConfig.MIN_TRANSMISSION_MAX, 3, "", "GCam"))
        add(ProSettingBuilder.it("enhance.dehaze.refineRadius", EnhanceConfig.KEY_DEHAZE_REFINE_RADIUS, "透射率精修半径",
            "引导滤波半径，0 = 不精修", 8,
            DehazeConfig.REFINE_RADIUS_MIN, DehazeConfig.REFINE_RADIUS_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.dehaze.refineEpsilon", EnhanceConfig.KEY_DEHAZE_REFINE_EPSILON,
            "透射率精修 ε", "越小越保边，越大越平滑", 1e-4f,
            DehazeConfig.REFINE_EPSILON_MIN, DehazeConfig.REFINE_EPSILON_MAX, 6, "", "GCam"))

        add(ProSettingBuilder.sw("enhance.sharpen.enabled", EnhanceConfig.KEY_SHARPEN_ENABLED, "USM 锐化",
            "反锐化掩模锐化", true, "GCam,OpenCam"))
        add(ProSettingBuilder.fl("enhance.sharpen.radius", EnhanceConfig.KEY_SHARPEN_RADIUS, "锐化半径",
            "高斯半径，越大越像局部对比", 2f,
            UnsharpMaskConfig.RADIUS_MIN, UnsharpMaskConfig.RADIUS_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("enhance.sharpen.amount", EnhanceConfig.KEY_SHARPEN_AMOUNT, "锐化强度",
            "0 = 关，建议 0.3 ~ 0.8", 0.6f,
            UnsharpMaskConfig.AMOUNT_MIN, UnsharpMaskConfig.AMOUNT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("enhance.sharpen.threshold", EnhanceConfig.KEY_SHARPEN_THRESHOLD, "锐化阈值",
            "低于该亮差的细节不锐化，用于抑制噪点", 2,
            UnsharpMaskConfig.THRESHOLD_MIN, UnsharpMaskConfig.THRESHOLD_MAX, 1, "", "GCam"))

        add(ProSettingBuilder.sw("enhance.denoise.enabled", EnhanceConfig.KEY_DENOISE_ENABLED, "双边降噪",
            "保边平滑，抑制暗部噪点", true, "OpenCam,GCam"))
        add(ProSettingBuilder.it("enhance.denoise.radius", EnhanceConfig.KEY_DENOISE_RADIUS, "降噪半径",
            "双边滤波核半径", 3, BilateralConfig.RADIUS_MIN, BilateralConfig.RADIUS_MAX, 1, "", "OpenCam"))
        add(ProSettingBuilder.fl("enhance.denoise.sigmaSpace", EnhanceConfig.KEY_DENOISE_SIGMA_SPACE, "空域 σ",
            "越大越平滑（越慢）", 8f,
            BilateralConfig.SIGMA_SPACE_MIN, BilateralConfig.SIGMA_SPACE_MAX, 2, "", "OpenCam"))
        add(ProSettingBuilder.fl("enhance.denoise.sigmaColor", EnhanceConfig.KEY_DENOISE_SIGMA_COLOR, "值域 σ",
            "越小越保边，越大越糊", 0.12f,
            BilateralConfig.SIGMA_COLOR_MIN, BilateralConfig.SIGMA_COLOR_MAX, 3, "", "OpenCam"))
        add(ProSettingBuilder.fl("enhance.denoise.strength", EnhanceConfig.KEY_DENOISE_STRENGTH, "降噪混合强度",
            "与原图混合比例", 0.8f,
            BilateralConfig.STRENGTH_MIN, BilateralConfig.STRENGTH_MAX, 2, "", "OpenCam"))

        // ───────────────────────────────────────── 镜头畸变校正（lenscorrection）
        // 键名规则：lens_correction_<维度>_<字段>。下面以 ULTRA_WIDE 维度展示键名，其余维度字段同名。

        add(ProSettingBuilder.dd("lenscorrection.dimension", "", "目标镜头维度",
            "页面内的维度选择器（不落盘）：下列各项实际按所选维度读写",
            LensCameraDimension.ULTRA_WIDE.name, LensCameraDimension.entries.map { it.name }))
        add(ProSettingBuilder.sw("lenscorrection.enabled",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "enabled"),
            "畸变校正开关", "默认关（与既有行为一致）", false, "GCam,OpenCam"))
        add(ProSettingBuilder.fl("lenscorrection.strength",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "strength"), "校正强度",
            "0..1 滑杆；最终系数 = (override ?: 预设) × 强度", 1f,
            LensDistortionConfig.STRENGTH_MIN, LensDistortionConfig.STRENGTH_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.tx("lenscorrection.k1Override",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "k1"), "k1 覆盖值",
            "留空 = 用该维度预设；范围 -1 ~ 1", "", 12, "GCam"))
        add(ProSettingBuilder.tx("lenscorrection.k2Override",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "k2"), "k2 覆盖值",
            "留空 = 用该维度预设；范围 -1 ~ 1", "", 12, "GCam"))
        add(ProSettingBuilder.tx("lenscorrection.k3Override",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "k3"), "k3 覆盖值",
            "留空 = 用该维度预设；范围 -1 ~ 1", "", 12, "GCam"))
        add(ProSettingBuilder.fl("lenscorrection.centerX",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "center_x"), "光心 X",
            "宽度归一化光心", 0.5f,
            LensDistortionConfig.CENTER_MIN, LensDistortionConfig.CENTER_MAX, 4, "", "GCam"))
        add(ProSettingBuilder.fl("lenscorrection.centerY",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "center_y"), "光心 Y",
            "高度归一化光心", 0.5f,
            LensDistortionConfig.CENTER_MIN, LensDistortionConfig.CENTER_MAX, 4, "", "GCam"))
        add(ProSettingBuilder.fl("lenscorrection.cropScale",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "crop_scale"), "输出缩放",
            ">1 = 放大裁掉黑边", 1f,
            LensDistortionConfig.CROP_SCALE_MIN, LensDistortionConfig.CROP_SCALE_MAX, 3, "", "GCam"))
        add(ProSettingBuilder.dd("lenscorrection.edgePolicy",
            LensCorrectionSettings.keyOf(LensCameraDimension.ULTRA_WIDE, "edge_policy"), "越界采样策略",
            "CLAMP = 复制边缘；TRANSPARENT_BLACK = 几何正确但四角黑边",
            DistortionEdgePolicy.CLAMP.name, DistortionEdgePolicy.entries.map { it.name }, "OpenCam"))
    }
}
