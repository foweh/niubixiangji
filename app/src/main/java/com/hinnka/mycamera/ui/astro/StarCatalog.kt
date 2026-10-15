/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发：Star Walk 2 风格星空叠加组件（ui/astro）。
 * 本文件为新增文件，内嵌常用亮星目录与北半球主要星座连线数据。
 * 纯 Kotlin 常量，无第三方依赖，不读写任何资源文件。
 */
package com.hinnka.mycamera.ui.astro

/**
 * 一颗亮星的静态目录项。
 *
 * @property zhName 中文名（渲染标签用）
 * @property enName 英文名（星座连线按英文名索引，须唯一）
 * @property raHours 赤经，单位：小时（0..24）
 * @property decDeg 赤纬，单位：度（-90..90，北正南负）
 * @property magnitude 视星等（数值越小越亮，负数为极亮星）
 * @property constellation 所属星座代码（如 Ori=猎户座）
 */
data class StarEntry(
    val zhName: String,
    val enName: String,
    val raHours: Float,
    val decDeg: Float,
    val magnitude: Float,
    val constellation: String,
)

/**
 * 一个星座连线图形：由若干条线段组成，端点用恒星英文名引用。
 */
data class ConstellationLines(
    val zhName: String,
    val enName: String,
    val lines: List<Pair<String, String>>,
)

/**
 * 内嵌亮星目录（约 59 颗，覆盖北半球中纬度可见的主要亮星）。
 * 坐标为近似 J2000 平位置，精度足以做屏幕叠加演示（误差 < 1°，
 * 配合用户手动方向微调即可对齐真实星空）。
 */
object StarCatalog {

    val STARS: List<StarEntry> = listOf(
        // ---- 全天最亮星 ----
        StarEntry("天狼星", "Sirius", 6.75f, -16.7f, -1.46f, "CMa"),
        StarEntry("老人星", "Canopus", 6.40f, -52.7f, -0.72f, "Car"),
        StarEntry("南门二", "RigilKent", 14.07f, -60.8f, -0.27f, "Cen"),
        StarEntry("大角星", "Arcturus", 14.26f, 19.2f, -0.05f, "Boo"),
        StarEntry("织女星", "Vega", 18.62f, 38.78f, 0.03f, "Lyr"),
        StarEntry("五车二", "Capella", 5.28f, 46.0f, 0.08f, "Aur"),
        StarEntry("参宿七", "Rigel", 5.24f, -8.2f, 0.13f, "Ori"),
        StarEntry("南河三", "Procyon", 7.66f, 5.2f, 0.34f, "CMi"),
        StarEntry("水委一", "Achernar", 1.63f, -57.2f, 0.46f, "Eri"),
        StarEntry("参宿四", "Betelgeuse", 5.92f, 7.4f, 0.50f, "Ori"),
        StarEntry("十字架二", "Acrux", 12.45f, -63.1f, 0.77f, "Cru"),
        StarEntry("牛郎星", "Altair", 19.85f, 8.9f, 0.77f, "Aql"),
        StarEntry("角宿一", "Spica", 13.42f, -11.2f, 0.98f, "Vir"),
        StarEntry("心宿二", "Antares", 16.49f, -26.4f, 1.06f, "Sco"),
        StarEntry("北河三", "Pollux", 7.76f, 28.0f, 1.14f, "Gem"),
        StarEntry("北落师门", "Fomalhaut", 22.96f, -29.6f, 1.16f, "PsA"),
        StarEntry("天津四", "Deneb", 20.69f, 45.3f, 1.25f, "Cyg"),
        StarEntry("十字架三", "Mimosa", 12.79f, -59.7f, 1.25f, "Cru"),
        StarEntry("轩辕十四", "Regulus", 10.14f, 12.0f, 1.36f, "Leo"),
        StarEntry("参宿五", "Bellatrix", 5.42f, 6.35f, 1.64f, "Ori"),
        StarEntry("十字架一", "Gacrux", 12.52f, -57.1f, 1.63f, "Cru"),
        StarEntry("参宿二", "Alnilam", 5.60f, -1.21f, 1.69f, "Ori"),
        StarEntry("参宿一", "Alnitak", 5.68f, -1.94f, 1.77f, "Ori"),
        StarEntry("玉衡", "Alioth", 12.90f, 55.96f, 1.77f, "UMa"),
        StarEntry("摇光", "Alkaid", 13.79f, 49.31f, 1.86f, "UMa"),
        StarEntry("尾宿八", "Shaula", 17.56f, -37.1f, 1.62f, "Sco"),
        StarEntry("尾宿五", "Sargas", 17.62f, -43.0f, 1.87f, "Sco"),
        StarEntry("天枢", "Dubhe", 11.06f, 61.75f, 1.79f, "UMa"),
        StarEntry("天樽二", "Alhena", 6.63f, 16.4f, 1.93f, "Gem"),
        StarEntry("五车三", "Menkalinan", 5.99f, 44.95f, 1.90f, "Aur"),
        StarEntry("北极星", "Polaris", 2.53f, 89.26f, 1.98f, "UMi"),
        StarEntry("参宿六", "Saiph", 5.80f, -9.67f, 2.09f, "Ori"),
        StarEntry("五帝座一", "Denebola", 11.82f, 14.57f, 2.11f, "Leo"),
        StarEntry("北极二", "Kochab", 14.85f, 74.16f, 2.08f, "UMi"),
        StarEntry("轩辕十二", "Algieba", 10.33f, 19.84f, 2.08f, "Leo"),
        StarEntry("开阳", "Mizar", 13.40f, 54.93f, 2.27f, "UMa"),
        StarEntry("王良四", "Schedar", 0.67f, 56.5f, 2.24f, "Cas"),
        StarEntry("参宿三", "Mintaka", 5.52f, -0.30f, 2.23f, "Ori"),
        StarEntry("天津一", "Sadr", 20.37f, 40.26f, 2.23f, "Cyg"),
        StarEntry("王良一", "Caph", 0.15f, 59.15f, 2.28f, "Cas"),
        StarEntry("天璇", "Merak", 11.03f, 56.38f, 2.37f, "UMa"),
        StarEntry("房宿三", "Dschubba", 16.00f, -22.6f, 2.32f, "Sco"),
        StarEntry("尾宿九", "Lesath", 17.51f, -37.3f, 2.39f, "Sco"),
        StarEntry("策", "GammaCas", 0.93f, 60.7f, 2.47f, "Cas"),
        StarEntry("天玑", "Phecda", 11.90f, 53.69f, 2.44f, "UMa"),
        StarEntry("西上相", "Zosma", 11.23f, 20.53f, 2.56f, "Leo"),
        StarEntry("房宿四", "Acrab", 16.08f, -19.8f, 2.62f, "Sco"),
        StarEntry("王良二", "Ruchbah", 1.43f, 60.2f, 2.68f, "Cas"),
        StarEntry("河鼓三", "Tarazed", 19.77f, 10.6f, 2.72f, "Aql"),
        StarEntry("天津九", "Fawaris", 20.70f, 45.13f, 2.87f, "Cyg"),
        StarEntry("天权", "Megrez", 12.26f, 57.03f, 3.31f, "UMa"),
        StarEntry("阁道三", "Segin", 1.91f, 63.7f, 3.38f, "Cas"),
        StarEntry("天津增十九", "GienahCyg", 20.78f, 33.97f, 2.46f, "Cyg"),
        StarEntry("渐台三", "Sulafat", 18.98f, 32.70f, 3.25f, "Lyr"),
        StarEntry("辇道增七", "Albireo", 19.51f, 27.96f, 3.18f, "Cyg"),
        StarEntry("河鼓一", "Alshain", 19.93f, 6.4f, 3.71f, "Aql"),
        StarEntry("渐台二", "Sheliak", 18.83f, 33.36f, 3.52f, "Lyr"),
        StarEntry("北极一", "Pherkad", 15.35f, 71.83f, 3.05f, "UMi"),
        StarEntry("北河二", "Castor", 7.58f, 31.9f, 1.58f, "Gem"),
    )

    /** 英文名 -> 星表下标，供星座连线 O(1) 索引。 */
    val INDEX_BY_EN: Map<String, Int> = STARS.mapIndexed { i, s -> s.enName to i }.toMap()

    /**
     * 北半球主要星座连线（11 个）：北斗、小熊、仙后、猎户、天鹅、天琴、
     * 天蝎、天鹰、狮子、双子、南十字。
     */
    val CONSTELLATIONS: List<ConstellationLines> = listOf(
        // 北斗七星（大熊座的核心）：斗身四边形 + 斗柄
        ConstellationLines(
            "北斗七星", "BigDipper", listOf(
                "Dubhe" to "Merak",
                "Merak" to "Phecda",
                "Phecda" to "Megrez",
                "Megrez" to "Dubhe",
                "Megrez" to "Alioth",
                "Alioth" to "Mizar",
                "Mizar" to "Alkaid",
            ),
        ),
        // 小熊座：北极星 -> 太子 -> 勾陈一（斗身简化为两线）
        ConstellationLines(
            "小熊座", "LittleDipper", listOf(
                "Polaris" to "Pherkad",
                "Pherkad" to "Kochab",
            ),
        ),
        // 仙后座 W 形
        ConstellationLines(
            "仙后座", "Cassiopeia", listOf(
                "Caph" to "Schedar",
                "Schedar" to "GammaCas",
                "GammaCas" to "Ruchbah",
                "Ruchbah" to "Segin",
            ),
        ),
        // 猎户座：双肩 + 腰带三星 + 双脚
        ConstellationLines(
            "猎户座", "Orion", listOf(
                "Betelgeuse" to "Bellatrix",
                "Betelgeuse" to "Alnitak",
                "Bellatrix" to "Mintaka",
                "Alnitak" to "Alnilam",
                "Alnilam" to "Mintaka",
                "Alnitak" to "Saiph",
                "Mintaka" to "Saiph",
            ),
        ),
        // 天鹅座（北十字）
        ConstellationLines(
            "天鹅座", "Cygnus", listOf(
                "Deneb" to "Sadr",
                "Sadr" to "Albireo",
                "Sadr" to "Fawaris",
                "Sadr" to "GienahCyg",
            ),
        ),
        // 天琴座：织女 + 小平行四边形（简化为三角）
        ConstellationLines(
            "天琴座", "Lyra", listOf(
                "Vega" to "Sheliak",
                "Sheliak" to "Sulafat",
                "Sulafat" to "Vega",
            ),
        ),
        // 天蝎座：头部蟹钳 + 尾部毒针
        ConstellationLines(
            "天蝎座", "Scorpius", listOf(
                "Acrab" to "Dschubba",
                "Dschubba" to "Antares",
                "Shaula" to "Lesath",
                "Lesath" to "Sargas",
            ),
        ),
        // 天鹰座：河鼓三星（牛郎星及其两侧小星）
        ConstellationLines(
            "天鹰座", "Aquila", listOf(
                "Tarazed" to "Altair",
                "Altair" to "Alshain",
            ),
        ),
        // 狮子座：镰刀 + 后发
        ConstellationLines(
            "狮子座", "Leo", listOf(
                "Regulus" to "Algieba",
                "Algieba" to "Zosma",
                "Zosma" to "Denebola",
            ),
        ),
        // 双子座：双胞胎头部连线
        ConstellationLines(
            "双子座", "Gemini", listOf(
                "Castor" to "Pollux",
                "Pollux" to "Alhena",
            ),
        ),
        // 南十字（南半球可见，北方低空）
        ConstellationLines(
            "南十字", "Crux", listOf(
                "Acrux" to "Gacrux",
                "Acrux" to "Mimosa",
            ),
        ),
    )
}
