# -*- coding: utf-8 -*-
"""第二轮 4 份补丁的集成操作：appgallerypreview / astronav / longexposure / uisettings。

用法：`python3 scripts/integration/apply.py scripts/integration/ops/round2.py [--dry-run]`
（**只允许实跑一次**：ops 非幂等，重复实跑会造成整段重复插入。）

op 参数：x_*_f 系列 = (kind, 补丁路径, 新块围栏号, 目标文件, 锚点围栏号, 期望次数)
"""

A = "app/src/main/java/com/hinnka/mycamera/"
AP = "delivery/patches/appgallerypreview_patch.md"
AN = "delivery/patches/astronav_patch.md"
LE = "delivery/patches/longexposure_patch.md"
UI = "delivery/patches/uisettings_patch.md"

MANIFEST = "app/src/main/AndroidManifest.xml"
CMAKE = "app/src/main/cpp/CMakeLists.txt"
STR = "app/src/main/res/values/strings.xml"
BJR = A + "computational/BgJobRunner.kt"
CAP = A + "aiplatform/AiCapability.kt"

OPS = [
    # ============ 1) appgallerypreview ============
    #   MANIFEST：补丁自述「可选（仅当以独立 Activity 承载预览页时）」→ 不应用（预览页走 NavHost 路由）
    #   DEPS / BUILD_CONFIG：无
    # ENUM：BgTaskType.GALLERY_PREVIEW 等
    ("x_insert_before_f", AP, 48, BJR, 44, 1),
    # STRINGS：61 条 agp_*（紧随既有 metering 锚点之后）
    ("x_insert_after_f", AP, 65, STR, 60, 1),
    # HOOK A：路由常量（紧随 MainActivity 的 PHOTO_EDIT 常量）
    ("x_insert_after_f", AP, 162, A + "MainActivity.kt", 158, 1),
    # HOOK B：NavHost 内新增预览页 composable（插在 GALLERY 路由之前）
    ("x_insert_before_f", AP, 192, A + "MainActivity.kt", 188, 1),
    # HOOK C：相机缩略图入口改为直达预览页（替换 3 行 else 分支）
    # 锚点实为 MainActivity 的缩略图 else 分支（补丁正文未写明文件，实测 CameraScreen 中不存在、
    # MainActivity 中命中数=1）→ 目标文件修正为 MainActivity.kt
    ("x_replace", AP, 235, 229, A + "MainActivity.kt", 1),
    #   HOOK D/E/F：D 属管理域、E 属编辑模块（非本代理）、F 可选 → 均不应用
    #   CONFIG：模块自带 codec，挂点为建议 → 不应用（登记）

    # ============ 2) astronav ============
    # MANIFEST：可选 uses-feature 三行（required=false，提升无传感器机型兼容性）
    ("x_insert_after", AN, 58, MANIFEST,
     '    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />', 1),
    #   BUILD_CONFIG：补丁自述「默认不采用」→ 不应用
    # ENUM-1：BgTaskType.ASTRONAV（插在 GENERIC 之前）
    ("x_insert_before_f", AN, 83, BJR, 79, 1),
    # ENUM-2：AiCapability.DECLINATION（插在 SCENE_RECOGNITION 之后、`;` 之前）
    ("x_insert_after_f", AN, 98, CAP, 94, 1),
    # STRINGS：26 条 astro_*（文件末尾 </resources> 之前）
    ("x_insert_before", AN, 111, STR, "</resources>", 1),
    # HOOK-1 A/B：AstroOverlay 新增可选参数 + Canvas 取矩阵表达式（默认零回归）
    ("x_replace", AN, 152, 144, A + "ui/astro/AstroOverlay.kt", 1),
    ("x_replace", AN, 173, 167, A + "ui/astro/AstroOverlay.kt", 1),
    # HOOK-2：CameraScreen 的 ASTRO 叠加层调用点
    ("x_replace", AN, 213, 194, A + "ui/camera/CameraScreen.kt", 1),
    # HOOK-4：本模块新文件内一行（补丁自带接线：GENERIC → ASTRONAV）
    ("replace", A + "astronav/AstroNavBackgroundSolver.kt",
     "            type = BgTaskType.GENERIC,", "            type = BgTaskType.ASTRONAV,", 1),
    #   HOOK-3：权限/手动兜底 UI 为「建议新增文件」→ 不应用（登记）

    # ============ 3) longexposure ============
    # CMake：3 个 .cpp 并入既有 my-native-lib 源清单
    ("x_insert_after_f", LE, 78, CMAKE, 69, 1),
    # ENUM：BgTaskType.LONG_EXPOSURE
    ("x_insert_after_f", LE, 105, BJR, 99, 1),
    # STRINGS：55 条 le_* / astro_plan_*
    ("x_insert_before", LE, 134, STR, "</resources>", 1),
    # H1a：ASTRO 场景「星空适宜度」提示（插在星轨变体块之后）
    ("x_insert_after_f", LE, 222, A + "ui/camera/CameraScreen.kt", 208, 1),
    # H1b：设置页「长曝光 / 日月位置计划」分区（替换 PHOTO_MODE 分支）
    ("x_replace", LE, 265, 257, A + "ui/settings/SettingsScreen.kt", 1),
    # H2（可选）：玩法页入口（场景条挂点）
    ("x_insert_after_f", LE, 329, A + "ui/camera/ComputationalSceneBar.kt", 324, 1),
    #   H3：补丁自述「默认不应用」→ 不应用

    # ============ 4) uisettings ============
    # PATCH 1.1：SettingsScreen 形参新增回调（默认值保证既有调用点可编译）
    ("x_replace", UI, 80, 64, A + "ui/settings/SettingsScreen.kt", 1),
    # PATCH 1.2：import 一行
    ("x_replace", UI, 100, 94, A + "ui/settings/SettingsScreen.kt", 1),
    # PATCH 1.3：专业模式页加一行入口项
    ("x_replace", UI, 120, 110, A + "ui/settings/SettingsScreen.kt", 1),
    # PATCH 2.1：路由常量
    ("x_replace", UI, 152, 146, A + "MainActivity.kt", 1),
    # PATCH 2.2：import 一行
    ("x_replace", UI, 168, 162, A + "MainActivity.kt", 1),
    # PATCH 2.3：给既有 SettingsScreen 调用接上回调
    ("x_replace", UI, 188, 178, A + "MainActivity.kt", 1),
    # PATCH 2.4：注册新页面 composable（插在锚点之前）
    ("x_insert_before_f", UI, 211, A + "MainActivity.kt", 204, 1),
    #   PORT 接线（配置搬运）：属集成层搬运工作，补丁未给锚点 → 不应用（登记）
]
