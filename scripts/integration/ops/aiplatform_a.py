# -*- coding: utf-8 -*-
"""aiplatform_patch 集成操作（a）：MANIFEST / network_security_config / BUILD_CONFIG / STRINGS / H1 设置页。"""

P = "delivery/patches/aiplatform_patch.md"
MANIFEST = "app/src/main/AndroidManifest.xml"
GRADLE = "app/build.gradle.kts"
STRINGS = "app/src/main/res/values/strings.xml"
SETTINGS = "app/src/main/java/com/hinnka/mycamera/ui/settings/SettingsScreen.kt"
NSC = "app/src/main/res/xml/network_security_config.xml"

OPS = [
    # ---- MANIFEST：networkSecurityConfig 属性（权限已足够，不新增）----
    ("x_insert_after", P, 77, MANIFEST, '            android:largeHeap="true"', 1),

    # ---- 新文件：network_security_config.xml（默认仅 https）----
    ("extract", P, 86, NSC),

    # ---- BUILD_CONFIG：内置端点/密钥默认值可空 + LUT_CATALOG_URL ----
    ("x_replace", P, 128, 113, GRADLE, 1),

    # ---- STRINGS：末尾整块追加（插在 </resources> 之前，等价于替换 </resources>）----
    ("x_insert_before", P, 191, STRINGS, "</resources>", 1),

    # ---- H1：设置页 AI 服务分支挂载平台设置 / 特征设置 / LUT 下载 / LUT 推荐开关 ----
    ("x_insert_after", P, 288, SETTINGS,
     "import com.hinnka.mycamera.ui.components.AppModalBottomSheet", 1),
    ("x_insert_before", P, 305, SETTINGS, '''                        TextInputSettingItem(
                            title = stringResource(R.string.settings_openai_api_key),
                            description = stringResource(R.string.settings_openai_api_key_desc),''', 1),
]
