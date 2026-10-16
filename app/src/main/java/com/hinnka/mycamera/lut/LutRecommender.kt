package com.hinnka.mycamera.lut

import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobRunner

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
 * 性能调度（用户 2026-10-16 硬约束「可拖延渲染时间，但单位时间 CPU/内存峰值受限」）：
 *  - 分析统一经 [BgJobRunner.submit] 走全局后台调度队列：单后台线程 + 最低优先级
 *    （THREAD_PRIORITY_BACKGROUND），不抢前台拍照/预览，不阻塞 UI。
 *  - 本分析是纯统计、极轻量，仍走队列兜底（轻量任务走队列兜底），便于统一限频/取消，
 *    避免任何分析逻辑直接占用前台线程；暂停/取消由返回的 [BgJob]（pause/resume）与
 *    [BgJobRunner.cancelAll] 控制。
 *
 * 设计：纯函数 + 一个队列入口；默认关闭（DEFAULT_ENABLED=false），可选开启。
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
    fun classifyScene(
        meanLuma: Float,
        warmth: Float,
        skinFraction: Float,
        config: LutRecommenderConfig = LutRecommenderConfig.DEFAULT,   // 默认值 = 原魔法数，行为不变
    ): Scene {
        val c = LutRecommenderConfig.clamp(config)
        if (skinFraction > c.skinFractionThreshold) return Scene.PORTRAIT
        if (meanLuma < c.nightLumaMax) return Scene.NIGHT
        if (meanLuma > c.landscapeLumaMin && warmth < c.landscapeWarmthMax) return Scene.LANDSCAPE
        if (warmth > c.streetWarmthMin) return Scene.STREET
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
    fun recommend(
        meanLuma: Float,
        warmth: Float,
        skinFraction: Float,
        config: LutRecommenderConfig = LutRecommenderConfig.DEFAULT,
    ): List<String> = recommendForScene(classifyScene(meanLuma, warmth, skinFraction, config))
    /** 默认 chunkSize（本分析为一次性纯统计，1 即最小分片；如需更细可增大分片扫描）。 */
    const val DEFAULT_CHUNK_SIZE = 1

    /**
     * 队列化分析入口（用户 2026-10-16 性能约束）：经 [BgJobRunner] 以最低优先级后台执行。
     *
     * @param statsProvider 在后台线程采集轻量统计，返回 [meanLuma, warmth, skinFraction]；
     *                      返回 null 表示采集失败（不产生推荐）。
     * @param enabled 开关；默认 [DEFAULT_ENABLED]（false）。开启才入队，默认关策略不变。
     * @param chunkSize 分片大小，默认 [DEFAULT_CHUNK_SIZE]。
     * @param onResult 完成回调（在后台线程触发，调用方需自行切回主线程更新 UI）。
     * @return 已提交的 [BgJob]（可 pause/resume/cancel）；未开启时返回 null。
     */
    fun submitAnalysis(
        statsProvider: () -> FloatArray?,
        enabled: Boolean = DEFAULT_ENABLED,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onResult: (List<String>) -> Unit = {},
        config: LutRecommenderConfig = LutRecommenderConfig.DEFAULT,   // 新增在末尾，避免破坏已有位置参数调用
    ): BgJob? {        if (!enabled) return null
        return BgJobRunner.submit(name = "AI LUT 推荐", chunkSize = chunkSize) {
            // 轻量任务也分片 yield，避免与前台渲染争抢瞬时 CPU。
            report(0, 2)
            checkYield()
            val stats = runCatching { statsProvider() }.getOrNull()
            report(1, 2)
            checkYield()
            val ids = if (stats != null && stats.size >= 3) {
                recommend(stats[0], stats[1], stats[2], config)
            } else {
                emptyList()
            }
            report(2, 2)
            onResult(ids)
        }
    }

    /** 默认关闭（用户明示：可选、可关、保轻量）。 */
    const val DEFAULT_ENABLED = false
}
