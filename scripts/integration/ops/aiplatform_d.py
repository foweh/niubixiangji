# -*- coding: utf-8 -*-
"""aiplatform_patch 集成操作（d）：H6 AI 白平衡写回 / H7 模型下载统一下载规格（含新文件）。"""

P = "delivery/patches/aiplatform_patch.md"
A = "app/src/main/java/com/hinnka/mycamera/"
BAR = A + "ui/camera/CameraParameterBar.kt"
C2 = A + "camera/Camera2Controller.kt"
DMM = A + "ml/DepthModelManager.kt"

OPS = [
    # ---- H6 文件 A：参数条展示 AI 白平衡建议值（零副作用）----
    ("x_replace", P, 1091, 1082, BAR, 1),
    ("insert_after", BAR, "import com.hinnka.mycamera.camera.CameraUtils",
     "import com.hinnka.mycamera.aiwb.AiWhiteBalanceAnalyzer", 1),

    # ---- H6 文件 B：写回捕获请求（默认关闭；缓存为空时不写任何参数）----
    ("x_insert_after", P, 1118, C2, "        applyWhiteBalanceSettings(builder, currentState, isCapture)", 1),
    ("insert_after", C2, "import com.hinnka.mycamera.processor.ComputationalStackRouter",
     "import com.hinnka.mycamera.aiwb.AiWhiteBalanceAnalyzer", 1),

    # ---- H7：统一下载规格新文件 + DepthModelManager 最小接入 ----
    ("extract", P, 1144, A + "net/HttpDownloadSpec.kt"),
    ("x_replace_lit", P, 1250, DMM, "    private fun downloadArchive(target: File) {", 1),
    ("insert_after", DMM, "import com.hinnka.mycamera.utils.PLog",
     "import com.hinnka.mycamera.net.HttpDownloadSpec", 1),
]
