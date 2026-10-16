# -*- coding: utf-8 -*-
"""multiframesr_patch 集成操作：ENUM(MFSR) + 场景条 import + 场景条挂点。"""

P = "delivery/patches/multiframesr_patch.md"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
CSB = "app/src/main/java/com/hinnka/mycamera/ui/camera/ComputationalSceneBar.kt"

OPS = [
    # ENUM：MFSR 条目（MultiFrameSrTask.kt 直接引用 BgTaskType.MFSR，必须）
    ("x_insert_before", P, 57, BJR, "    /** 未分类。 */", 1),

    # Hook 2：import
    ("insert_after", CSB, "import com.hinnka.mycamera.camera.PsStackType",
     "import com.hinnka.mycamera.multiframesr.MfsrSceneChipHost", 1),

    # Hook 3：Row 末尾挂载自包含 chip（不改既有签名与状态）
    ("x_insert_after", P, 147, CSB, '''        if (serverEnhancementSupported) {
            SceneChip(
                label = "服务器增强",
                selected = serverEnhancementEnabled,
                onClick = { onServerEnhancementToggle(!serverEnhancementEnabled) }
            )
        }''', 1),
]
