# -*- coding: utf-8 -*-
"""nativecompute_patch 集成操作：CMakeLists 2 处 + 23 处 Kotlin 接线（10 个既有文件，纯 Kotlin 兜底保留）。"""

P = "delivery/patches/nativecompute_patch.md"
CMAKE = "app/src/main/cpp/CMakeLists.txt"
MF = "app/src/main/java/com/hinnka/mycamera/multiframesr/"
EN = "app/src/main/java/com/hinnka/mycamera/enhance/"
LE = "app/src/main/java/com/hinnka/mycamera/longexposure/"

OPS = [
    # ---- A. C++ 侧 ----
    ("x_insert_after", P, 55, CMAKE, "    dcp_parser.cpp", 1),
    ("x_insert_before", P, 73, CMAKE, "# 启用优化和 OpenMP", 1),

    # ---- B1 MfsrFft.kt ----
    ("x_insert_before", P, 102, MF + "MfsrFft.kt", "import kotlin.math.PI", 1),
    ("x_insert_before", P, 109, MF + "MfsrFft.kt", "        val (a, b) = prepare(ref, tgt, alignMaxSide)", 1),

    # ---- B2 MfsrShiftAndAdd.kt ----
    ("x_insert_before", P, 132, MF + "MfsrShiftAndAdd.kt", "import kotlin.math.max", 1),
    ("x_insert_after", P, 139, MF + "MfsrShiftAndAdd.kt",
     "        val sy = if (shift.dy.isFinite()) shift.dy else 0f", 1),
    ("x_insert_after", P, 162, MF + "MfsrShiftAndAdd.kt", "        val out = FloatArray(weight.size)", 1),

    # ---- B3 MfsrIbp.kt ----
    ("x_insert_before", P, 182, MF + "MfsrIbp.kt", "import kotlin.math.max", 1),
    ("x_insert_after", P, 191, MF + "MfsrIbp.kt", "                    val dy = sh.dy", 1),
    ("x_replace", P, 228, 218, MF + "MfsrIbp.kt", 1),

    # ---- B4 MfsrRefiner.kt ----
    ("x_insert_before", P, 246, MF + "MfsrRefiner.kt", "import kotlin.math.abs", 1),
    ("x_insert_after", P, 254, MF + "MfsrRefiner.kt",
     "        val sr = if (sigmaRange.isFinite() && sigmaRange > 1e-3f) sigmaRange else 10f", 1),
    ("x_insert_before", P, 274, MF + "MfsrRefiner.kt", "        val blur = plane.gaussianBlur(sigma)", 1),

    # ---- B5 MfsrCore.kt ----
    ("x_insert_before", P, 295, MF + "MfsrCore.kt", "import java.io.File", 1),
    ("x_replace_lit", P, 304, MF + "MfsrCore.kt",
     "        refined.clampInPlace(MfsrIterativeBackProjection.CLAMP_LO, MfsrIterativeBackProjection.CLAMP_HI)", 1),

    # ---- B6 ClaheProcessor.kt ----
    ("x_insert_before", P, 322, EN + "ClaheProcessor.kt", "import kotlin.math.floor", 1),
    ("x_replace_lit", P, 330, EN + "ClaheProcessor.kt",
     "        val equalized = equalize(luma, width, height, config)", 1),
    ("x_insert_before", P, 359, EN + "ClaheProcessor.kt", "        val tilesX = clamped.tilesX.coerceAtMost(width)", 1),

    # ---- B7 BilateralFilterProcessor.kt ----
    ("x_insert_before", P, 376, EN + "BilateralFilterProcessor.kt", "import kotlin.math.exp", 1),
    ("x_insert_before", P, 383, EN + "BilateralFilterProcessor.kt", "        for (y in y0 until y1) {", 1),

    # ---- B8 UnsharpMaskProcessor.kt ----
    ("x_insert_before", P, 410, EN + "UnsharpMaskProcessor.kt", "import kotlin.math.abs", 1),
    ("x_replace", P, 425, 417, EN + "UnsharpMaskProcessor.kt", 1),

    # ---- B9 LongExposureStacker.kt ----
    ("x_insert_before", P, 454, LE + "LongExposureStacker.kt", "import kotlin.math.max", 1),
    ("x_replace", P, 479, 461, LE + "LongExposureStacker.kt", 1),

    # ---- B10 DarkFrameSubtractor.kt ----
    ("x_insert_after", P, 538, LE + "DarkFrameSubtractor.kt", '''    internal fun sampleAt(index: Int): Int =
        if (frameCount == 0) 0 else (sum[index] / frameCount).coerceIn(0, LinearRgbPlane.SAMPLE_MAX)''', 1),
]
