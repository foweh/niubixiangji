package com.hinnka.mycamera.uisettings

import com.hinnka.mycamera.multiframesr.MfsrAlignMethod
import com.hinnka.mycamera.multiframesr.MfsrConfig
import com.hinnka.mycamera.multiframesr.MfsrConfigCodec
import com.hinnka.mycamera.multiframesr.MfsrWorkingResolution
import com.hinnka.mycamera.superres.SuperResAccelMode
import com.hinnka.mycamera.superres.SuperResConfig
import com.hinnka.mycamera.superres.SuperResQualityTier

/**
 * 目录②：端侧 AI 超分（`superres`）+ 多帧超分（`multiframesr`）。
 *
 * superres 落专有 DataStore `superres_preferences`（单键 `config`，k=v 文本，键名见 `SuperResConfigCodec`）；
 * multiframesr 落 SharedPreferences `mfsr_config`（键名见 `MfsrConfigCodec.KEY_*`）。
 */
object ProSettingsCatalogSr {

    val entries: List<ProSettingSpec> = buildList {

        // ───────────────────────────────────────── 端侧 AI 超分（superres）

        add(ProSettingBuilder.sw("superres.enabled", "enabled", "AI 超分总开关",
            "总开关；模型未下载时不生效", true, "BMD,MotionCam"))
        add(ProSettingBuilder.it("superres.scale", "scale", "放大倍率",
            "1 = 仅增强不放大；2 = 默认档；4 = 可选档",
            2, SuperResConfig.SCALE_MIN, SuperResConfig.SCALE_MAX, 1, "×", "BMD"))
        add(ProSettingBuilder.it("superres.tileSize", "tileSize", "Tile 边长",
            "单 tile 边长", SuperResConfig.TILE_DEFAULT,
            SuperResConfig.TILE_MIN, SuperResConfig.TILE_MAX, 64, "px", "BMD"))
        add(ProSettingBuilder.it("superres.tileOverlap", "tileOverlap", "Tile 重叠",
            "拼缝淡化宽度：越大接缝越淡、耗时越长；clamp 上限 = tileSize/2",
            SuperResConfig.DEFAULT_TILE_OVERLAP, 0, SuperResConfig.TILE_MAX, 8, "px", "BMD"))
        add(ProSettingBuilder.fl("superres.strength", "strength", "混合强度",
            "AI 结果与原图混合比例", 1f, 0f, 1f, 2, "", "GCam"))
        add(ProSettingBuilder.dd("superres.qualityTier", "qualityTier", "速度 / 质量档",
            "决定 ORT 优化级别与建议 tile", SuperResQualityTier.BALANCED.name,
            SuperResQualityTier.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.dd("superres.accelMode", "accelMode", "加速后端",
            "NNAPI 不可用时自动回退 CPU", SuperResAccelMode.CPU.name,
            SuperResAccelMode.entries.map { it.name }, "BMD"))
        add(ProSettingBuilder.it("superres.threads", "threads", "推理线程数",
            "ORT intra-op 线程", 2, SuperResConfig.THREADS_MIN, SuperResConfig.THREADS_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.sw("superres.enableStyleTransfer", "enableStyleTransfer", "风格迁移",
            "额外模型算子（风格化）", false, "MotionCam"))
        add(ProSettingBuilder.fl("superres.styleTransferStrength", "styleTransferStrength", "风格化强度",
            "0..1", 0.8f, 0f, 1f, 2, "", "MotionCam"))
        add(ProSettingBuilder.tx("superres.styleModelId", "styleModelId", "风格模型 id",
            "留空 = 用默认风格模型", "", 64, "MotionCam"))
        add(ProSettingBuilder.sw("superres.enableInpainting", "enableInpainting", "图像修复",
            "掩膜区域重绘", false, "MotionCam"))
        add(ProSettingBuilder.it("superres.inpaintFeatherPx", "inpaintFeatherPx", "掩膜羽化半径",
            "像素", 8, 0, SuperResConfig.INPAINT_FEATHER_MAX, 2, "px", "MotionCam"))
        add(ProSettingBuilder.fl("superres.inpaintMaskThreshold", "inpaintMaskThreshold", "掩膜判定阈值",
            "按 alpha / 亮度判定", 0.5f, 0f, 1f, 2, "", "MotionCam"))
        add(ProSettingBuilder.lt("superres.maxWorkingPixels", "maxWorkingPixels", "工作分辨率上限",
            "0 = 不限；超限自动降采样", SuperResConfig.DEFAULT_MAX_WORKING_PIXELS.toLong(),
            0L, SuperResConfig.MAX_WORKING_PIXELS_MAX.toLong(), "px", "BMD"))
        add(ProSettingBuilder.lt("superres.memoryBudgetBytes", "memoryBudgetBytes", "内存预算",
            "本模块中间缓冲预算（字节）", SuperResConfig.DEFAULT_MEMORY_BUDGET.toLong(),
            SuperResConfig.MEM_MIN.toLong(), SuperResConfig.MEM_MAX.toLong(), "B", "BMD"))
        add(ProSettingBuilder.it("superres.maxTilesPerChunk", "maxTilesPerChunk", "每分片 tile 数",
            "后台分片的 tile 数", 4,
            SuperResConfig.TILES_PER_CHUNK_MIN, SuperResConfig.TILES_PER_CHUNK_MAX, 1, "", "BMD"))
        add(ProSettingBuilder.sw("superres.keepOutputAlpha", "keepOutputAlpha", "保留 Alpha",
            "输出保 alpha（修复用）", true, "MotionCam"))
        add(ProSettingBuilder.sw("superres.degradeInsteadOfFail", "degradeInsteadOfFail", "内存不足时降级",
            "降级而非失败", true, "BMD"))
        add(ProSettingBuilder.it("superres.jpegQuality", "jpegQuality", "输出 JPEG 质量",
            "60..100", 95, SuperResConfig.JPEG_Q_MIN, SuperResConfig.JPEG_Q_MAX, 1, "", "OpenCam"))

        // ───────────────────────────────────────── 多帧超分（multiframesr）

        add(ProSettingBuilder.it("multiframesr.frameCount", MfsrConfigCodec.KEY_FRAME_COUNT, "融合帧数",
            "参与融合的帧数；不设算法上限，内存不够请降工作档", MfsrConfig.DEFAULT_FRAME_COUNT,
            MfsrConfig.FRAME_MIN, MfsrConfig.FRAME_MAX, 1, "帧", "GCam"))
        add(ProSettingBuilder.fl("multiframesr.scale", MfsrConfigCodec.KEY_SCALE, "超分倍率",
            "HR 相对工作分辨率的倍率", MfsrConfig.DEFAULT_SCALE,
            MfsrConfig.SCALE_MIN, MfsrConfig.SCALE_MAX, 2, "×", "GCam"))
        add(ProSettingBuilder.it("multiframesr.ibpIterations", MfsrConfigCodec.KEY_IBP_ITERATIONS, "IBP 迭代轮数",
            "迭代反投影轮数，越多越锐也越慢", MfsrConfig.DEFAULT_IBP_ITERATIONS,
            MfsrConfig.IBP_MIN, MfsrConfig.IBP_MAX, 1, "轮", "GCam"))
        add(ProSettingBuilder.sw("multiframesr.alignEnabled", MfsrConfigCodec.KEY_ALIGN_ENABLED, "帧对齐",
            "关闭 = 直接叠加（适合三脚架）", true, "GCam"))
        add(ProSettingBuilder.dd("multiframesr.alignMethod", MfsrConfigCodec.KEY_ALIGN_METHOD, "对齐方法",
            "BLOCK_MATCH 稳；PHASE_CORRELATION 快；OFF 关",
            MfsrAlignMethod.BLOCK_MATCH.name, MfsrAlignMethod.entries.map { it.name }, "GCam"))
        add(ProSettingBuilder.it("multiframesr.subpixelRefineIterations", MfsrConfigCodec.KEY_SUBPIXEL_REFINE,
            "亚像素精化迭代", "梯度法精化最大迭代", MfsrConfig.DEFAULT_SUBPIXEL_REFINE_ITERATIONS,
            MfsrConfig.SUBPIXEL_REFINE_MIN, MfsrConfig.SUBPIXEL_REFINE_MAX, 1, "次", "GCam"))
        add(ProSettingBuilder.fl("multiframesr.minAlignConfidence", MfsrConfigCodec.KEY_MIN_ALIGN_CONFIDENCE,
            "对齐置信度下限", "低于此值按零平移叠加（防误对齐）", MfsrConfig.DEFAULT_MIN_ALIGN_CONFIDENCE,
            MfsrConfig.MIN_ALIGN_CONFIDENCE_MIN, MfsrConfig.MIN_ALIGN_CONFIDENCE_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("multiframesr.maxAlignShiftPx", MfsrConfigCodec.KEY_MAX_ALIGN_SHIFT, "最大平移",
            "工作分辨率像素；超过视为无效对齐", MfsrConfig.DEFAULT_MAX_ALIGN_SHIFT_PX,
            MfsrConfig.MAX_ALIGN_SHIFT_MIN, MfsrConfig.MAX_ALIGN_SHIFT_MAX, 1, "px", "GCam"))
        add(ProSettingBuilder.dd("multiframesr.workingResolution", MfsrConfigCodec.KEY_WORKING_RESOLUTION,
            "起始工作档", "内存不足自动降级", MfsrWorkingResolution.FULL.name,
            MfsrWorkingResolution.entries.map { it.name }, "GCam"))
        add(ProSettingBuilder.fl("multiframesr.strength", MfsrConfigCodec.KEY_STRENGTH, "总强度",
            "IBP 步长 + 最终锐化", MfsrConfig.DEFAULT_STRENGTH,
            MfsrConfig.STRENGTH_MIN, MfsrConfig.STRENGTH_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.sw("multiframesr.denoiseOn", MfsrConfigCodec.KEY_DENOISE_ON, "最终降噪",
            "融合后双边降噪", false, "GCam"))
        add(ProSettingBuilder.it("multiframesr.denoiseRadius", MfsrConfigCodec.KEY_DENOISE_RADIUS, "降噪半径",
            "双边滤波半径", 2, MfsrConfig.DENOISE_RADIUS_MIN, MfsrConfig.DENOISE_RADIUS_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.fl("multiframesr.sharpenAmount", MfsrConfigCodec.KEY_SHARPEN_AMOUNT, "USM 强度",
            "反锐化强度", MfsrConfig.DEFAULT_SHARPEN_AMOUNT,
            MfsrConfig.SHARPEN_AMOUNT_MIN, MfsrConfig.SHARPEN_AMOUNT_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.fl("multiframesr.sharpenSigma", MfsrConfigCodec.KEY_SHARPEN_SIGMA, "USM 高斯半径",
            "高斯 σ", MfsrConfig.DEFAULT_SHARPEN_SIGMA,
            MfsrConfig.SHARPEN_SIGMA_MIN, MfsrConfig.SHARPEN_SIGMA_MAX, 2, "", "GCam"))
        add(ProSettingBuilder.it("multiframesr.ibpFrameStride", MfsrConfigCodec.KEY_IBP_STRIDE, "IBP 采样步长",
            "0 = 自动（按帧数摊薄到约 96 帧/轮）；不限制总帧数", MfsrConfig.DEFAULT_IBP_FRAME_STRIDE,
            0, MfsrConfig.IBP_STRIDE_MAX, 1, "", "GCam"))
        add(ProSettingBuilder.lt("multiframesr.memoryBudgetBytes", MfsrConfigCodec.KEY_MEMORY_BUDGET, "内存预算",
            "中间缓冲 / 帧缓存预算（字节）", MfsrConfig.DEFAULT_MEMORY_BUDGET_BYTES,
            MfsrConfig.MEMORY_BUDGET_MIN_BYTES, MfsrConfig.MEMORY_BUDGET_MAX_BYTES, "B", "GCam"))
        add(ProSettingBuilder.it("multiframesr.inputMaxSide", MfsrConfigCodec.KEY_INPUT_MAX_SIDE, "输入长边上限",
            "先缩放再取亮度，控读图峰值", MfsrConfig.DEFAULT_INPUT_MAX_SIDE,
            MfsrConfig.INPUT_MAX_SIDE_MIN, MfsrConfig.INPUT_MAX_SIDE_MAX, 64, "px", "GCam"))
        add(ProSettingBuilder.it("multiframesr.jpegQuality", MfsrConfigCodec.KEY_JPEG_QUALITY, "输出 JPEG 质量",
            "60..100", MfsrConfig.DEFAULT_JPEG_QUALITY,
            MfsrConfig.JPEG_QUALITY_MIN, MfsrConfig.JPEG_QUALITY_MAX, 1, "", "OpenCam"))
    }
}
