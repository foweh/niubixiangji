# -*- coding: utf-8 -*-
"""superres_patch 集成操作：DEPS / ENUM / STRINGS / MainActivity 4 hook / SettingsScreen 3 hook。"""

P = "delivery/patches/superres_patch.md"
GRADLE = "app/build.gradle.kts"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
STRINGS = "app/src/main/res/values/strings.xml"
MA = "app/src/main/java/com/hinnka/mycamera/MainActivity.kt"
SS = "app/src/main/java/com/hinnka/mycamera/ui/settings/SettingsScreen.kt"

OPS = [
    # DEPS
    ("x_insert_after", P, 17, GRADLE,
     '    implementation("org.tensorflow:tensorflow-lite-support:0.5.0")', 1),

    # ENUM
    ("x_insert_after", P, 73, BJR, "    SERVER_ENHANCE(0L, 1, 256L * 1024 * 1024),", 1),

    # STRINGS
    ("x_insert_before", P, 92, STRINGS, "</resources>", 1),

    # MainActivity
    ("x_insert_before", P, 134, MA, "import com.hinnka.mycamera.ui.settings.SettingsScreen", 1),
    ("x_insert_after", P, 146, MA, '    const val PHANTOM_PIP_CROP = "phantom_pip_crop"', 1),
    ("x_insert_before", P, 157, MA, "                    onVideoFilterManagementClick = {", 1),
    ("x_insert_before", P, 170, MA, "            composable(Routes.LUT_CREATOR) {", 1),

    # SettingsScreen
    ("x_insert_after", P, 221, SS, "import com.hinnka.mycamera.ui.icons.AppIcons", 1),
    ("x_insert_after", P, 235, SS, "    onPresetManagementClick: () -> Unit,", 1),
    ("x_insert_after", P, 257, SS, '''                        NavigationSettingItem(
                            title = stringResource(R.string.settings_preset_management),
                            description = stringResource(R.string.settings_preset_management_description),
                            onClick = onPresetManagementClick
                        )''', 1),
    # 适配：用 LocalContext 显式取 Context，避免依赖 SettingsScreen 函数体是否持有 context 变量
    ("replace", SS, "                        val superResConfigStore = remember { SuperResConfigStore(context) }",
     '''                        val superResContext = LocalContext.current
                        val superResConfigStore = remember { SuperResConfigStore(superResContext) }''', 1),
    ("replace", SS, "                        val superResRuntime = remember { SuperResRuntime(context) }",
     "                        val superResRuntime = remember { SuperResRuntime(superResContext) }", 1),
]
