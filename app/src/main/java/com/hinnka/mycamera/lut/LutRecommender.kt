package com.hinnka.mycamera.lut

/**
 * AI 自动匹配 / 推荐 LUT —— 轻量、可选、默认关闭。
 *
 * 成熟度评估（2026-10-15）：
 *  - 本工程已内置 mgc_hdrnet（assets/mgc_hdrnet/hdrnet_coefficients.tflite ≈1.6MB，Google HDRNet 类），
 *    它已接入 raw/ 与 processor/（DngPhotonProfileGainTable*、MgcHdrNetOutputCurve、
 *    PhotonCoreImagingTuning）做**本地色调映射 / 局部动态范围增强**，零额外体积。
 *    结论 a：HDRNet 自动增强可直接作为底座——它已经在拍/后处理链路里跑，无需重接；
 *            缺口仅是「是否对预览/录像也开启」的开关（由管线/界面模块控制，默认随系统）。
 *  - 本工程 LutManager.recommendLutsForColor(targetColor) 已存在并被 CameraViewModel/
 *    ColorWalkScreen 调用（基于 LutColorAnalyzer 色彩倾向），说明「按色彩推荐」的底座已有。
 *  - 本类补齐缺口 b：**轻量场景识别 → 风格胶片 LUT 推荐**。
 *    为保轻量（原则3），先用图像统计特征（平均亮度 + 冷暖平衡）粗分类，不引重 TFLite；
 *    后续可替换为轻量 TFLite 场景分类器（ml/ 包已有 TFLite 基建：MlDelegateCache 等）。
 *
 * 设计：纯函数、零依赖、可单测；默认关闭（DEFAULT_ENABLED=false）。
 */
object LutRecommender {

    enum class Scene {
        PORTRAIT,   // 人像：肤色友好
        LANDSCAPE,  // 风光：浓郁饱和
        NIGHT,      // 夜景：电影感青橙/高感
        STREET,     // 街头：交叉冲印/复古
        NEUTRAL,    // 无法判定：标准
    }

    /**
     * 由轻量图像统计推断场景。
     * @param meanLuma  画面平均亮度 [0,1]（display-referred）
     * @param warmth    冷暖平衡：>0 偏暖(红黄多)，<0 偏冷(青蓝多)，0 中性
     * @param skinFraction 画面检测到肤色像素占比 [0,1]（无检测时传 0）
     */
    fun classifyScene(meanLuma: Float, warmth: Float, skinFraction: Float): Scene {
        if (skinFraction > 0.12f) return Scene.PORTRAIT          // 有人脸/肤色 => 人像
        if (meanLuma < 0.25f) return Scene.NIGHT                  // 很暗 => 夜景
        if (meanLuma > 0.55f && warmth < -0.05f) return Scene.LANDSCAPE // 明亮偏冷 => 风光
        if (warmth > 0.15f) return Scene.STREET                   // 偏暖 => 街头/复古
        return Scene.NEUTRAL
    }

    /** 场景 -> 推荐的胶片 LUT id（映射到内置/可下载胶片预设，t3mujinpack/G'MIC 思路）。 */
    fun recommendForScene(scene: Scene): List<String> = when (scene) {
        Scene.PORTRAIT -> listOf("film_portra_400", "Hasselblad_portrait", "portra_400")
        Scene.LANDSCAPE -> listOf("film_fuji_velvia", "astia", "film_fuji_pro400h")
        Scene.NIGHT -> listOf("film_cinestill_800t", "film_cross_processed")
        Scene.STREET -> listOf("film_cross_processed", "film_kodak_gold_200", "cc")
        Scene.NEUTRAL -> listOf("standard", "film_portra_400")
    }

    /** 一键推荐：统计 -> 场景 -> LUT id。 */
    fun recommend(meanLuma: Float, warmth: Float, skinFraction: Float): List<String> =
        recommendForScene(classifyScene(meanLuma, warmth, skinFraction))

    /** 默认关闭（用户明示：可选、可关、保轻量）。 */
    const val DEFAULT_ENABLED = false
}
