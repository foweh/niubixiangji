# -*- coding: utf-8 -*-
"""galleryedit_patch 集成操作：MANIFEST(EDIT_MEDIA 入口 Activity) + ENUM(VIDEO_EDIT) + STRINGS(87 条, A~H)。"""

P = "delivery/patches/galleryedit_patch.md"
MANIFEST = "app/src/main/AndroidManifest.xml"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
STRINGS = "app/src/main/res/values/strings.xml"

OPS = [
    # M1：编辑入口 Activity（EDIT_MEDIA 协议接收端），插在 MgcFilterSelectionActivity 之后
    ("x_insert_after", P, 50, MANIFEST, '''                <action android:name="com.hinnka.mycamera.action.SELECT_MGC_LOOK" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>''', 1),

    # ENUM：VIDEO_EDIT（GeVideoEditRunner.kt 已引用，编译必需）
    ("replace", BJR, "    GENERIC(10L, 16, 384L * 1024 * 1024),",
     '''    GENERIC(10L, 16, 384L * 1024 * 1024),

    /**
     * 视频编辑导出（media3 Transformer 转码：区间裁剪 / 旋转 / 变速 / 去音 / 多段拼接 / LUT 调色）。
     *
     * 取值理由：编码与合流是「长时 IO + 硬编」混合负载，单批只跑 1 段（批量无收益，且 Transformer
     * 实例不应并行）；沿用 10ms 节流与前台任务同级但优先级更低（BgJobPriority.LOW，见调用点）；
     * 768MB 预算对应「编码缓冲 + 音频混流 + 单帧纹理」峰值，与 RAW_MERGE/ASTRO 同量级。
     */
    VIDEO_EDIT(10L, 1, 768L * 1024 * 1024),''', 1),

    # STRINGS：87 条（A~H 共 8 个代码块）
    ("x_insert_before", P, 126, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 143, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 154, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 163, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 176, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 207, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 229, STRINGS, "</resources>", 1),
    ("x_insert_before", P, 248, STRINGS, "</resources>", 1),
]
