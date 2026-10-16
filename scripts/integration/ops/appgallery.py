# -*- coding: utf-8 -*-
"""appgallery_patch 集成操作：ENUM(GALLERY) + DB v50→51 迁移 + Entity/Store/DAO 四列 + file_paths + MainActivity 路由。"""

P = "delivery/patches/appgallery_patch.md"
A = "app/src/main/java/com/hinnka/mycamera/"
BJR = A + "computational/BgJobRunner.kt"
DB = A + "gallery/db/GalleryDatabase.kt"
ENT = A + "gallery/db/GalleryMediaEntity.kt"
STORE = A + "gallery/db/GalleryMediaStore.kt"
DAO = A + "gallery/db/GalleryMediaDao.kt"
FP = "app/src/main/res/xml/file_paths.xml"
MA = A + "MainActivity.kt"

OPS = [
    # ENUM：GALLERY（可选；AppGalleryBgTasks 默认 GENERIC，追加后获独立频控/预算）
    ("x_insert_before", P, 60, BJR, "    /** 未分类。 */", 1),

    # ---- §1 数据库版本 + 迁移（与 §2/§3 同批）----
    ("x_replace_lit", P, 121, DB, "    version = 50,", 1),
    ("x_insert_before", P, 133, DB, "        fun getInstance(context: Context): GalleryDatabase {", 1),
    ("x_replace_lit", P, 165, DB, '''                        MIGRATION_48_49,
                        MIGRATION_49_50
                    )''', 1),

    # ---- §2 Entity 四列 ----
    ("x_insert_after", P, 184, ENT, '''    val spectralFilmYDensityGain: Float
)''', 1),

    # ---- §3 Store：buildEntity 写列 ----
    ("x_replace_lit", P, 213, STORE, '''            spectralFilmYDensityGain = metadata.spectralFilmYDensityGain
        )''', 1),
    # ---- §3 Store：toMetadata 读列 ----
    ("x_replace_lit", P, 237, STORE, '''            spectralFilmYDensityGain = spectralFilmYDensityGain
        )''', 1),
    # ---- §3 Store：私有辅助（放在 toMetadata 之后、rectOrNull 之前）----
    ("x_insert_before", P, 245, STORE, "    private fun rectOrNull(left: Int?, top: Int?, right: Int?, bottom: Int?): Rect? {", 1),

    # ---- §4 DAO 可选查询 ----
    ("x_insert_before", P, 279, DAO, '''    @Query("DELETE FROM gallery_media WHERE id = :id")
    suspend fun deleteById(id: String)''', 1),

    # ---- §5 FileProvider 路径（建议应用）----
    ("x_replace_lit", P, 312, FP,
     '    <external-files-path name="downloaded_updates" path="Download/updates/" />', 1),

    # ---- §6 MainActivity 路由（必需）----
    ("x_replace_lit", P, 335, MA, '    const val GALLERY = "gallery"', 1),
    ("x_insert_before", P, 349, MA, "            composable(Routes.GALLERY) {", 1),
    ("insert_after", MA, "import androidx.compose.runtime.getValue",
     '''import com.hinnka.mycamera.appgallery.AppGalleryPreviewContract
import com.hinnka.mycamera.appgallery.AppGalleryShareKit
import com.hinnka.mycamera.ui.appgallery.AppGalleryScreen
import com.hinnka.mycamera.ui.appgallery.AppGalleryViewModel''', 1),
]
