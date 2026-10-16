# -*- coding: utf-8 -*-
"""aiplatform_patch 集成操作（c）：H4 LUT 推荐（含 3 个新文件）/ H4.6 画廊接入 / H5 LUT 下载修复 + 资源替换。"""

P = "delivery/patches/aiplatform_patch.md"
A = "app/src/main/java/com/hinnka/mycamera/"
LUT_LIB = A + "lut/"
LR = LUT_LIB + "LutRecommender.kt"
GDS = A + "ui/gallery/GalleryDetailScreen.kt"
LDM = LUT_LIB + "LutDownloadManager.kt"
JSON = "app/src/main/assets/luts/downloadable_sources.json"

OPS = [
    # ---- H4.1 / H4.4 / H4.5 新文件 ----
    ("extract", P, 628, LUT_LIB + "LutRecommenderConfig.kt"),
    ("extract", P, 759, LUT_LIB + "LutRecommendationBridge.kt"),
    ("extract", P, 842, LUT_LIB + "LutAiSettingsSection.kt"),

    # ---- H4.2 classifyScene 接受配置 ----
    ("x_replace", P, 681, 669, LR, 1),

    # ---- H4.3 submitAnalysis / recommend 增加 config ----
    ("x_replace", P, 712, 701, LR, 1),
    ("replace", LR, "                recommend(stats[0], stats[1], stats[2])",
     "                recommend(stats[0], stats[1], stats[2], config)", 1),
    ("x_replace", P, 746, 739, LR, 1),

    # ---- H4.6 F2 生效通路（画廊 AI 评分面板）----
    ("x_insert_after", P, 908, GDS, '''    var requestToken by remember(photo.id) { mutableIntStateOf(0) }
    var uiState by remember(photo.id) { mutableStateOf<AiEvaluationUiState>(AiEvaluationUiState.Loading) }''', 1),
    # 适配：GalleryViewModel 无 loadBitmapForAi，改用既有公开挂起方法 getPreviewBitmap（签名返回 Bitmap?）
    ("replace", GDS, "runCatching { viewModel.loadBitmapForAi(photo) }.getOrNull()",
     '''runCatching {
                viewModel.getPreviewBitmap(photo = photo, showOrigin = false, ignoreDenoise = true, maxEdge = 512)
            }.getOrNull()''', 1),
    ("insert_after", GDS, "import com.hinnka.mycamera.lut.isVideoTransformerExportSupported",
     '''import com.hinnka.mycamera.lut.LutManager
import com.hinnka.mycamera.lut.LutRecommendationBridge
import com.hinnka.mycamera.lut.LutRecommenderConfig''', 1),

    # ---- H5 LutDownloadManager：状态码校验 + 远端目录运行时配置 + .part 原子改名 ----
    ("x_replace", P, 962, 956, LDM, 1),
    ("x_replace", P, 981, 972, LDM, 1),
    ("x_replace", P, 1008, 1002, LDM, 1),
    ("x_replace", P, 1022, 1016, LDM, 1),
    ("x_replace", P, 1046, 1040, LDM, 1),
    ("insert_after", LDM, "import com.hinnka.mycamera.utils.PLog",
     '''import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.aiplatform.AiParamStore''', 1),

    # ---- 资源文件整体替换（F3 根因：fileUrl 全空）----
    ("x_write", P, 1328, JSON),
]
