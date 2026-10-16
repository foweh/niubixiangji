# -*- coding: utf-8 -*-
"""funshot_patch 集成操作：CMakeLists 2 行 + ENUM(PANORAMA/GIF_EXPORT) + 场景条 import/挂点（Hook 5/6 可选，未应用）。"""

P = "delivery/patches/funshot_patch.md"
CMAKE = "app/src/main/cpp/CMakeLists.txt"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
CSB = "app/src/main/java/com/hinnka/mycamera/ui/camera/ComputationalSceneBar.kt"

OPS = [
    # Hook 2：CMake 源清单（必须；锚点 lut_processor.cpp 唯一）
    ("x_insert_after", P, 72, CMAKE, "    lut_processor.cpp", 1),

    # Hook 1：ENUM 两条（必须，PanoramaTask/GifExportTask 已引用）
    ("x_insert_before", P, 96, BJR, "    /** 未分类。 */", 1),

    # Hook 3：场景条 import
    ("x_insert_after", P, 168, CSB, "import com.hinnka.mycamera.camera.PsStackType", 1),

    # Hook 4：场景条挂点（Row 末尾，自包含 chip 行）
    ("x_insert_after", P, 199, CSB, '''        if (serverEnhancementSupported) {
            SceneChip(
                label = "服务器增强",
                selected = serverEnhancementEnabled,
                onClick = { onServerEnhancementToggle(!serverEnhancementEnabled) }
            )
        }''', 1),
]
