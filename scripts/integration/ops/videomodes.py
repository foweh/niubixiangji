# -*- coding: utf-8 -*-
"""videomodes_patch 集成操作（部分应用：仅补丁给出自包含代码块且锚点唯一的部分）。

已应用：ENUM 4 条、STRINGS、H0（CameraState 状态位 + import）、H2-a1（fps 能力兜底）、
        H2-a4（手动快门上限常量注释化）、H4-a1（码率 clamp）、CONFIG（持久化键）。
未应用（登记于 delivery/selfchecks/integration.md）：H1 / H2-a2 / H2-a3 / H3 / H4-a2 / H5 /
        H6 / H7 / H8 / H9 / H10 / H11 / H12 / H13 —— 补丁在这些处给的是散文式描述或引用
        目标作用域内不存在的局部量（config / videoModes / hdrDecision / manager），
        禁编译条件下无法验证作用域，注入将产生无法检测的编译错误。
"""

P = "delivery/patches/videomodes_patch.md"
BJR = "app/src/main/java/com/hinnka/mycamera/computational/BgJobRunner.kt"
STR = "app/src/main/res/values/strings.xml"
CST = "app/src/main/java/com/hinnka/mycamera/camera/CameraState.kt"
C2 = "app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt"
VR = "app/src/main/java/com/hinnka/mycamera/video/VideoRecorder.kt"
UPR = "app/src/main/java/com/hinnka/mycamera/data/UserPreferencesRepository.kt"

OPS = [
    # ENUM：4 个新 BgTaskType（VIDEO_TIMELAPSE / VIDEO_SR / VIDEO_DENOISE / VIDEO_SNAPSHOT）
    ("x_insert_before", P, 108, BJR, "    GENERIC(10L, 16, 384L * 1024 * 1024),", 1),

    # STRINGS
    ("x_insert_after", P, 137, STR,
     '    <string name="camera_metering_point_label">Metering</string>', 1),

    # H0：CameraState 状态位 + import
    ("x_insert_after", P, 184, CST, "    val videoConfig: VideoConfig = VideoConfig(),", 1),
    ("insert_after", CST, "import com.hinnka.mycamera.video.VideoConfig",
     '''import com.hinnka.mycamera.videomodes.DualCameraPipDecision
import com.hinnka.mycamera.videomodes.FpsCapability
import com.hinnka.mycamera.videomodes.HdrVideoDecision
import com.hinnka.mycamera.videomodes.ManualVideoProbe
import com.hinnka.mycamera.videomodes.VideoModesConfig''', 1),

    # H2-a1：帧率按 HAL 能力兜底
    ("x_replace", P, 231, 226, C2, 1),

    # H2-a4：手动快门上限改为策略 clamp 的注释说明（常量值保持原样）
    ("x_replace", P, 276, 271, CST, 1),

    # H4-a1：码率 clamp 走 VideoEncodePolicy
    # 冲突消解：review P1 已把该行改成 ''...* 1_000_000)'' + ''.coerceIn(2_000_000, VideoBitrateLimits.maxBps())''
    # 两行式写法，videomodes 的原锚点已消失 → 合并为「输入侧 clampBitrateMbps + 上界仍走 maxBps()」。
    ("replace", VR, """        val videoBitrate = (requestedBitrateMbps * 1_000_000)
            .coerceIn(2_000_000, VideoBitrateLimits.maxBps())""",
     """        // videomodes §14 + review P1 合并：输入侧开放 1~200Mbps（clampBitrateMbps），
        // 上界仍由 VideoBitrateLimits.maxBps() 兜底（相对 review 版本，上界语义未变）。
        val videoBitrate = (VideoEncodePolicy.clampBitrateMbps(requestedBitrateMbps) * 1_000_000)
            .coerceIn(2_000_000, VideoBitrateLimits.maxBps())""", 1),
    ("insert_after", VR, "import android.media.AudioManager",
     "import com.hinnka.mycamera.videomodes.VideoEncodePolicy", 1),

    # CONFIG：单键 JSON 持久化键
    ("x_insert_after", P, 540, UPR,
     '        private val VIDEO_CODEC = stringPreferencesKey("video_codec")', 1),
]
