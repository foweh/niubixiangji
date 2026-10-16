# -*- coding: utf-8 -*-
"""logvideo_patch 集成操作：ENUM 3 文件（TransferCurve/ColorSpace/VideoTypes）+ Hook 1~10（STRINGS 零改动）。

Hook 7.3（LutRenderer 的 Log 曲线 trim uniform 下发）补丁给的是含省略号的伪代码，
本文件按「缓存字段 + 最小 setter（不下发 uniform，恒等语义）」落盘并登记为部分应用。
"""

P = "delivery/patches/logvideo_patch.md"
A = "app/src/main/java/com/hinnka/mycamera/"
TC = A + "color/TransferCurve.kt"
CS = A + "raw/ColorSpace.kt"
VT = A + "video/VideoTypes.kt"
PCSM = A + "lut/PreviewColorShaderModules.kt"
LSM = A + "lut/LutShaderMappings.kt"
VECC = A + "video/VideoEncoderColorConfig.kt"
C2 = A + "camera/Camera2Controller.kt"
CSCREEN = A + "ui/camera/CameraScreen.kt"
GSV = A + "ui/camera/CameraGLSurfaceView.kt"
PGL = A + "ui/camera/CameraPreviewGL.kt"
LR = A + "lut/LutRenderer.kt"
LPR = A + "livephoto/LivePhotoRecorder.kt"
CFG = "app/src/main/assets/luts/config.json"
DLS = "app/src/main/assets/luts/downloadable_sources.json"

OPS = [
    # ---- ENUM 1: TransferCurve.LOGC3（放在 LLOG 之后、`;` 之前）----
    ("x_replace_lit2", P, 77, TC, "    );", "    ),\n", "\n    ;", 1),

    # ---- ENUM 2: ColorSpace.ARRI3（放在 HNCS 之后、`;` 之前，ordinal=12）----
    ("x_insert_before", P, 107, CS, "    ),\n;", 1),

    # ---- ENUM 3: VideoLogProfile 两档（替换 LLOG_BT2020 那行）----
    ("x_replace_lit", P, 130, VT, '    LLOG_BT2020("L-Log", TransferCurve.LLOG, ColorSpace.BT2020);', 1),

    # ---- Hook 1: GLSL LogC3 曲线分支（插在 curveType==10 分支之前）----
    ("x_insert_before", P, 210, PCSM, '''            if (curveType == 10) {
                return mix(8.0 * l + 0.09, 0.27 * log10(max(1.3 * l + 0.0115, vec3(1e-6))) + 0.6, step(0.006, l));
            }
            return l;''', 1),

    # ---- Hook 2: GLSL ARRI Wide Gamut 3 色域矩阵（插在 colorSpace==9 分支之前）----
    ("x_insert_before", P, 233, PCSM, '''            if (colorSpace == 9) {
                return mat3(0.541973, 0.076993, 0.058875, 0.360148, 0.767969, 0.273495, 0.097891, 0.155019, 0.667533) * rgb;
            }
            return rgb;''', 1),

    # ---- Hook 3: LutShaderMappings 显式 when 分支（接住 colorSpaceId=12）----
    ("x_insert_after", P, 258, LSM, "            ColorSpace.FilmLightEGamut -> 0", 1),

    # ---- Hook 4: 容器色彩标准（ARRI3 → BT.709 基色）----
    ("x_insert_after", P, 276, VECC, "        ColorSpace.SRGB -> MediaFormat.COLOR_STANDARD_BT709", 1),

    # ---- Hook 5.1: 录制色彩层组装改由 LogVideoLayerFactory 决定（统一降级语义）----
    ("x_replace", P, 301, 291, C2, 1),

    # ---- Hook 5.2: 编码器色彩请求（纯 Log → hasActiveLut=false）----
    ("x_replace", P, 341, 332, C2, 1),

    # ---- Hook 5.3: 档位切换时同步本模块配置 ----
    ("x_insert_after", P, 360, C2, "    fun setVideoLogProfile(logProfile: VideoLogProfile) {", 1),

    # ---- Hook 5.4: 依赖 import ----
    ("x_insert_after", P, 376, C2, "import com.hinnka.mycamera.lut.VideoColorEffectLayer", 1),

    # ---- Hook 6: CameraScreen 的 Log 参数面板（插在 VideoLogLutModeSelector 调用之后）----
    ("x_insert_after", P, 399, CSCREEN, '''                            VideoLogLutModeSelector(
                                mode = state.videoConfig.logLutMode,
                                enabled = !state.videoRecordingState.isRecording &&
                                    !state.videoRecordingState.isProcessing,
                                onModeSelected = viewModel::setVideoLogLutMode
                            )''', 1),

    # ---- Hook 7.1: CameraGLSurfaceView 新增 setLogCurveTrim 直通方法 ----
    ("x_insert_after", P, 432, GSV, '''    fun setVideoLogProfile(profile: VideoLogProfile) {
        renderer.videoLogProfile = profile
        requestRender()
    }''', 1),
    ("insert_after", GSV, "import com.hinnka.mycamera.lut.LutRenderer",
     "import com.hinnka.mycamera.logvideo.LogCurveTrim", 1),

    # ---- Hook 7.2: CameraPreviewGL 下发（新增 logVideoConfig 入参，默认值 = 恒等，零回归）----
    ("x_insert_after", P, 451, PGL, "                        glSurfaceView.setVideoLogProfile(videoLogProfile)", 1),
    ("insert_after", PGL, "    videoLogProfile: VideoLogProfile = VideoLogProfile.OFF,",
     '''    /** Log 视频配置（Log 曲线微调下发的来源；默认值 → trim 恒等，不改变既有画面）。 */
    logVideoConfig: LogVideoConfig = LogVideoConfig(),''', 1),
    ("insert_after", PGL, "import com.hinnka.mycamera.preview.EyeFocusPreviewFrame",
     '''import com.hinnka.mycamera.logvideo.LogVideoConfig
import com.hinnka.mycamera.logvideo.LogVideoLayerFactory''', 1),

    # ---- Hook 7.3（部分应用）: LutRenderer 的 trim 缓存 + 最小 setter ----
    ("insert_after", LR, "    var videoLogProfile: VideoLogProfile = VideoLogProfile.OFF",
     '''    /**
     * Log 曲线微调缓存（黑电平 / 斜率 / 峰值）。
     *
     * 集成说明（logvideo_patch.md Hook 7.3）：补丁给的是含省略号的伪代码，
     * GLSL（`uVideoLogBlack/Slope/Peak` 三个 uniform 的声明 + `applyLutCurve` 之后的应用点）
     * 未给出可直接落盘的实现，因此本处只落「缓存 + setter」，**不写入任何 uniform**，
     * 行为恒等（IDENTITY = 0/1/1）。GLSL 侧落地后在此补 glUniform1f 下发即可。
     */
    @Volatile
    var logCurveTrim: LogCurveTrim = LogCurveTrim.IDENTITY

    /** 设置 Log 曲线微调（当前仅缓存；GLSL 下发见上）。 */
    fun setLogCurveTrim(trim: LogCurveTrim) {
        logCurveTrim = trim
    }''', 1),
    ("insert_after", LR, "import com.hinnka.mycamera.preview.EyeFocusPreviewFrame",
     "import com.hinnka.mycamera.logvideo.LogCurveTrim", 1),

    # ---- Hook 8: 实况照片容器色彩标记（Log 片段非显示域）----
    ("x_replace", P, 495, 489, LPR, 1),

    # ---- Hook 8b: 静止帧 CPU Log 编码 ----
    ("x_insert_after", P, 529, LPR, "    fun snapshot(): Long? {", 1),

    # ---- Hook 9: 内置 Log 还原 LUT 注册（insert after log_restore_llog 条目）----
    ("x_insert_after", P, 576, CFG, '''    {
      "id": "log_restore_llog",
      "name": {
        "en": "L-Log Restore",
        "zh": "L-Log 还原"
      },
      "path": "log/log_restore_llog.plut",
      "category": "Log",
      "isVip": false
    },''', 1),

    # ---- Hook 10: Log LUT 包（追加到 packs 数组末尾）----
    ("x_replace_lit2", P, 608, DLS, "    }\n  ]\n}", "    },\n", "\n  ]\n}", 1),
]
