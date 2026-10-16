package com.hinnka.mycamera.logvideo

/**
 * 可下载 Log→Rec.709 转换 LUT 包条目（Log 视频 20 项 · 第 4 项）。
 *
 * 字段与工程 `app/src/main/assets/luts/downloadable_sources.json` 的 `packs[]` **一一对应**
 * （见该文件 `_comment` / `_source_config` 约定），补丁把本目录条目追加进该 JSON。
 *
 * 合规原则（本任务硬约束「直链必须合规可访问」）：
 *  - 厂商官方 LUT（ARRI / Panasonic）：**只登记官方源页面**（`fileUrl = ""`，仅展示源链接），
 *    不镜像、不转载——厂商条款通常允许个人使用但限制再分发；
 *  - GPL / MIT 社区包：登记仓库源页面，与工程既有目录（t3mujin GPL-3.0、cedeber MIT）同规格；
 *  - cameramanben/LUTCalc（350★，GPL-2.0）：**仅作 Log 曲线参数交叉核对的参考**，
 *    不采任何代码、不作为可下载 LUT 包提供（`format = "reference_only"`，`fileUrl = ""`）。
 *
 * @param logPreset 该包对应的 Log 档位（用于「一键套用」时自动匹配当前录制档位）。
 */
data class LogLutPack(
    val id: String,
    val nameEn: String,
    val nameZh: String,
    val sourceUrl: String,
    val license: String,
    val star: String,
    val contents: String,
    val fileUrl: String,
    val format: String,
    val logPreset: LogCurvePreset?,
    /** 是否可在 App 内直接下载（false = 仅展示官方源页面，需用户到官网获取）。 */
    val directlyDownloadable: Boolean,
)

/**
 * Log→Rec.709 转换 LUT 包目录（可拆卸下载，`LutDownloadManager` 消费）。
 *
 * 三档主推档位（LogC3 / V-Log / HLG）+ 工程 Log 还原组全覆盖。
 */
object LogLutPackCatalog {

    /** 内置程序化烘焙包的 id（不走网络，运行时由 [LogToRec709LutBaker] + `LogVideoBakeJob` 生成）。 */
    const val BUILT_IN_BAKED_ID = "log_baked_restore_on_device"

    val packs: List<LogLutPack> = listOf(
        LogLutPack(
            id = "pack_logc3_arri_official",
            nameEn = "ARRI LogC3 → Rec.709 (Gamma24) LUT Package",
            nameZh = "ARRI LogC3 → Rec.709 官方 LUT 包",
            sourceUrl = "https://www.arri.com/en/learn-help/learn-help-camera-system/tools/lut-generator",
            license = "ARRI 官方免费提供（LUT Generator / LUT Package，再分发受限，仅官方源）",
            star = "厂商官方（无 GitHub star）",
            contents = "LogC3/AWG3 → Rec.709 Gamma24 技术转换 3D LUT（.cube，含 33³ 规格）",
            fileUrl = "",
            format = "cube_bundle",
            logPreset = LogCurvePreset.LOG_C3,
            directlyDownloadable = false,
        ),
        LogLutPack(
            id = "pack_vlog709_panasonic_official",
            nameEn = "Panasonic V-Log → V-709 LUT",
            nameZh = "Panasonic V-Log → V-709 官方 LUT",
            sourceUrl = "https://av.jpn.support.panasonic.com/support/global/cs/dsc/download/lut/",
            license = "Panasonic 官方免费提供（官网下载，见官网条款）",
            star = "厂商官方（无 GitHub star）",
            contents = "VLog_to_V709 3D LUT（.cube），18% 中灰 → 41% 信号，含高光软剪",
            fileUrl = "",
            format = "cube_bundle",
            logPreset = LogCurvePreset.V_LOG,
            directlyDownloadable = false,
        ),
        LogLutPack(
            id = "pack_varicam_lut_library",
            nameEn = "Panasonic VariCam LUT Library (.cube)",
            nameZh = "Panasonic VariCam LUT 库",
            sourceUrl = "https://pro-av.panasonic.net/en/cinema_camera_varicam_eva/support/lut/",
            license = "Panasonic 官方免费提供（官网下载）",
            star = "厂商官方（无 GitHub star）",
            contents = "V-Log/VarCam → Rec.709 技术转换与风格 LUT，提供 .cube / .vlt 多格式",
            fileUrl = "",
            format = "cube_bundle",
            logPreset = LogCurvePreset.V_LOG,
            directlyDownloadable = false,
        ),
        LogLutPack(
            id = "pack_hlg_bt2100_reference",
            nameEn = "HLG (BT.2100) → SDR Rec.709 reference CLUTs",
            nameZh = "HLG → Rec.709 参考转换 CLUT",
            sourceUrl = "https://gmic.eu/gallery.shtml",
            license = "GPL（G'MIC colour CLUT 库）",
            star = "高（1100+ CLUT，社区广泛使用）",
            contents = "含 HLG/BT.2100 与广播级 log→709 参考 CLUT，可转 .cube",
            fileUrl = "",
            format = "clut_bundle",
            logPreset = LogCurvePreset.HLG,
            directlyDownloadable = false,
        ),
        LogLutPack(
            id = "pack_log3g10_red_community",
            nameEn = "Community Log3G10 → Rec.709 CLUTs (hald-clut)",
            nameZh = "社区 Log3G10 → Rec.709 CLUT（hald-clut）",
            sourceUrl = "https://github.com/cedeber/hald-clut",
            license = "MIT",
            star = "320",
            contents = "相机/Log 输入到 Rec.709 的 Hald CLUT 集合（MIT，可自由转换与再分发）",
            fileUrl = "",
            format = "hald_clut",
            logPreset = LogCurvePreset.LOG3G10,
            directlyDownloadable = false,
        ),
        LogLutPack(
            id = "pack_lutcalc_reference",
            nameEn = "LUTCalc (Log curve reference, code NOT used)",
            nameZh = "LUTCalc（Log 曲线参数参考，不采代码）",
            sourceUrl = "https://github.com/cameramanben/LUTCalc",
            license = "GPL-2.0",
            star = "350",
            contents = "Log 曲线参数与 Log→709 变换的交叉核对参考；GPL 项目，StormCam 未采用其任何代码",
            fileUrl = "",
            format = "reference_only",
            logPreset = null,
            directlyDownloadable = false,
        ),
    )

    /** 按当前 Log 档位筛选可推荐包（一键套用面板数据源）。 */
    fun forPreset(preset: LogCurvePreset): List<LogLutPack> =
        packs.filter { it.logPreset == preset }

    fun byId(id: String): LogLutPack? = packs.firstOrNull { it.id == id }

    /** 至少存在一个可直接下载的包时为 true（开启「在线补充」入口）。 */
    val hasDirectDownload: Boolean get() = packs.any { it.directlyDownloadable }

    /**
     * 生成 downloadable_sources.json 的 `packs[]` 条目 JSON 片段（补丁内容与单测共用）。
     * 字段严格对齐既有 5 个条目，**不新增未知字段**（避免旧解析器严格模式报错）。
     */
    fun toCatalogJsonEntries(): String = buildString {
        append('[')
        packs.filter { it.format != "reference_only" }.forEachIndexed { index, pack ->
            if (index > 0) append(',')
            append('\n')
            append("    {\n")
            append("      \"id\": \"").append(pack.id).append("\",\n")
            append("      \"name\": { \"en\": \"").append(pack.nameEn).append("\", \"zh\": \"")
                .append(pack.nameZh).append("\" },\n")
            append("      \"sourceUrl\": \"").append(pack.sourceUrl).append("\",\n")
            append("      \"license\": \"").append(pack.license).append("\",\n")
            append("      \"star\": \"").append(pack.star).append("\",\n")
            append("      \"contents\": \"").append(pack.contents).append("\",\n")
            append("      \"fileUrl\": \"").append(pack.fileUrl).append("\",\n")
            append("      \"format\": \"").append(pack.format).append("\"\n")
            append("    }")
        }
        append("\n  ]")
    }
}
