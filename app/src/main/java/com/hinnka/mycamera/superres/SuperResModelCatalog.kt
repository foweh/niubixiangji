package com.hinnka.mycamera.superres

/** 模型能力分类：超分 / 风格迁移 / 图像修复。 */
enum class SuperResModelKind { SUPER_RESOLUTION, STYLE_TRANSFER, INPAINTING }

/**
 * 一个可拆卸下载的端侧模型描述（纯数据，无 Android 依赖）。
 *
 * 只描述**元信息**，不包含权重：权重一律不进 APK，由 [SuperResModelDownloadManager]
 * 按需下载到应用私有目录，用户可随时删除（仿 lut/LutDownloadManager.kt 的可拆卸模式）。
 *
 * @param scale 超分倍率；非超分类模型固定为 1（不做尺寸放大）。
 * @param directFileUrl 可直接下载的权重直链；为空表示只有来源仓库（许可或镜像不明确），
 *        由用户自行导出 ONNX 后用「导入模型」放入模型目录。
 * @param inputNames/outputNames ONNX 输入输出张量名，作为首选名；实际会以会话自带名优先。
 */
data class SuperResModelInfo(
    val id: String,
    val kind: SuperResModelKind,
    val nameEn: String,
    val nameZh: String,
    val scale: Int,
    val fileName: String,
    val approxSizeBytes: Long,
    val sourceUrl: String,
    val license: String,
    val starNote: String,
    val note: String,
    val directFileUrl: String = "",
    val inputNames: List<String> = listOf("input"),
    val outputNames: List<String> = listOf("output"),
    val sha256: String = "",
) {
    val downloadable: Boolean get() = directFileUrl.isNotBlank()
    val sizeMb: Long get() = approxSizeBytes / (1024L * 1024L)
}

/**
 * 内置模型目录（**不含权重，只含元信息**）。
 *
 * 来源与许可（2026-10-16 用 GitHub API 核实，详见 delivery/changelog_drafts/superres.md）：
 *  - Real-ESRGAN（xinntao/Real-ESRGAN，37101★，BSD-3-Clause）：x2 为默认下载档，x4 为可选项。
 *    权重官方发布在 GitHub Releases（BSD-3-Clause 覆盖代码；权重随仓库发布）。
 *  - fast-neural-style（jcjohnson/fast-neural-style，4362★）：**仓库无 LICENSE 文件，许可不明确**，
 *    按 PATTERN_SPEC 第 1.4 节「许可不明确只参考思路、不采代码」，本模块仅参考其
 *    「每个风格一个前馈网络、推理即一次卷积前向」的移动端化思路，模型由用户自行导出 ONNX。
 *  - LaMa（advimman/lama，10315★，Apache-2.0）：代码 Apache-2.0，但**预训练权重另有许可**，
 *    同样只采用「掩膜 + 单次前向补全」的思路，权重由用户自行导出自备。
 */
object SuperResModelCatalog {

    const val ID_REALESRGAN_X2 = "sr_realesrgan_x2"
    const val ID_REALESRGAN_X4 = "sr_realesrgan_x4"
    const val ID_STYLE_CANDY = "style_candy"
    const val ID_STYLE_MOSAIC = "style_mosaic"
    const val ID_INPAINT_LAMA = "inpaint_lama"

    /** x2 为默认下载档（体积/耗时/收益最均衡）。 */
    val models: List<SuperResModelInfo> = listOf(
        SuperResModelInfo(
            id = ID_REALESRGAN_X2,
            kind = SuperResModelKind.SUPER_RESOLUTION,
            nameEn = "Real-ESRGAN x2 (general)",
            nameZh = "Real-ESRGAN x2 通用超分",
            scale = 2,
            fileName = "realesrgan_x2.onnx",
            approxSizeBytes = 18L * 1024 * 1024,
            sourceUrl = "https://github.com/xinntao/Real-ESRGAN",
            license = "BSD-3-Clause",
            starNote = "37101★（2026-10-16 核实）",
            note = "默认档：RRDBNet 轻量变体导出的 ONNX，2 倍放大 + 去噪去压缩伪影。",
            directFileUrl = "",
        ),
        SuperResModelInfo(
            id = ID_REALESRGAN_X4,
            kind = SuperResModelKind.SUPER_RESOLUTION,
            nameEn = "Real-ESRGAN x4 (general)",
            nameZh = "Real-ESRGAN x4 通用超分",
            scale = 4,
            fileName = "realesrgan_x4.onnx",
            approxSizeBytes = 34L * 1024 * 1024,
            sourceUrl = "https://github.com/xinntao/Real-ESRGAN",
            license = "BSD-3-Clause",
            starNote = "37101★（2026-10-16 核实）",
            note = "可选档：4 倍放大，耗时与内存约为 x2 的 4 倍，建议 QUALITY 档使用。",
            directFileUrl = "",
        ),
        SuperResModelInfo(
            id = ID_STYLE_CANDY,
            kind = SuperResModelKind.STYLE_TRANSFER,
            nameEn = "Style: Candy",
            nameZh = "风格：糖果",
            scale = 1,
            fileName = "style_candy.onnx",
            approxSizeBytes = 8L * 1024 * 1024,
            sourceUrl = "https://github.com/jcjohnson/fast-neural-style",
            license = "不明确（仓库无 LICENSE，仅参考思路，不采代码）",
            starNote = "4362★（2026-10-16 核实）",
            note = "参考 fast-neural-style 的「每风格一个前馈网」思路；ONNX 由用户自行导出。",
            directFileUrl = "",
        ),
        SuperResModelInfo(
            id = ID_STYLE_MOSAIC,
            kind = SuperResModelKind.STYLE_TRANSFER,
            nameEn = "Style: Mosaic",
            nameZh = "风格：马赛克",
            scale = 1,
            fileName = "style_mosaic.onnx",
            approxSizeBytes = 8L * 1024 * 1024,
            sourceUrl = "https://github.com/jcjohnson/fast-neural-style",
            license = "不明确（仓库无 LICENSE，仅参考思路，不采代码）",
            starNote = "4362★（2026-10-16 核实）",
            note = "同上前馈风格网思路的第二个档位。",
            directFileUrl = "",
        ),
        SuperResModelInfo(
            id = ID_INPAINT_LAMA,
            kind = SuperResModelKind.INPAINTING,
            scale = 1,
            nameEn = "LaMa Inpainting",
            nameZh = "LaMa 图像修复",
            fileName = "lama_inpaint.onnx",
            approxSizeBytes = 200L * 1024 * 1024,
            sourceUrl = "https://github.com/advimman/lama",
            license = "Apache-2.0（代码）；预训练权重许可另计，需用户自备",
            starNote = "10315★（2026-10-16 核实）",
            note = "参考 LaMa 的「掩膜 + 单次前向补全」思路；权重不进 APK，用户自行导出 ONNX。",
            directFileUrl = "",
        ),
    )

    fun byId(id: String?): SuperResModelInfo? =
        if (id.isNullOrBlank()) null else models.firstOrNull { it.id == id }

    fun byKind(kind: SuperResModelKind): List<SuperResModelInfo> = models.filter { it.kind == kind }

    /** 超分模型：按倍率精确匹配；scale=1 表示「不放大」，无对应模型。 */
    fun superResModel(scale: Int): SuperResModelInfo? =
        models.firstOrNull { it.kind == SuperResModelKind.SUPER_RESOLUTION && it.scale == scale }

    /** 该类型下的默认模型（超分取 2x，其余取目录第一个）。 */
    fun defaultFor(kind: SuperResModelKind): SuperResModelInfo? = when (kind) {
        SuperResModelKind.SUPER_RESOLUTION -> superResModel(2) ?: byKind(kind).firstOrNull()
        else -> byKind(kind).firstOrNull()
    }

    /**
     * 挑选可用模型：优先用户指定 id（且类型与倍率匹配、已安装），否则取该类型默认模型。
     * 返回 null 表示没有可用（已安装）模型 → 上层走灰化/回退原图。
     */
    fun selectInstalled(
        kind: SuperResModelKind,
        scale: Int,
        installedIds: Set<String>,
        preferredId: String? = null,
    ): SuperResModelInfo? {
        val preferred = byId(preferredId)
        if (preferred != null &&
            preferred.kind == kind &&
            preferred.id in installedIds &&
            (kind != SuperResModelKind.SUPER_RESOLUTION || preferred.scale == scale)
        ) {
            return preferred
        }
        val candidate = if (kind == SuperResModelKind.SUPER_RESOLUTION) superResModel(scale) else defaultFor(kind)
        return candidate?.takeIf { it.id in installedIds }
    }

    /** 供 UI 展示的「缺失模型提示」文案（不写死资源 id，交由调用方决定展示方式）。 */
    fun missingHint(kind: SuperResModelKind, scale: Int): String = when (kind) {
        SuperResModelKind.SUPER_RESOLUTION -> "需要下载 Real-ESRGAN x$scale 模型后才能启用"
        SuperResModelKind.STYLE_TRANSFER -> "需要下载风格迁移模型后才能启用"
        SuperResModelKind.INPAINTING -> "需要下载图像修复模型后才能启用"
    }
}
