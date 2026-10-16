# -*- coding: utf-8 -*-
"""viewfinder_patch 集成操作：ENUM/STRINGS/LutRenderer(hooks 1.x)/CameraGLSurfaceView(2.x)/CameraPreviewGL(3.x)/CameraScreen(4.x)。"""

P = "delivery/patches/viewfinder_patch.md"
A = "app/src/main/java/com/hinnka/mycamera/"
BJR = A + "computational/BgJobRunner.kt"
STRINGS = "app/src/main/res/values/strings.xml"
LR = A + "lut/LutRenderer.kt"
GSV = A + "ui/camera/CameraGLSurfaceView.kt"
PGL = A + "ui/camera/CameraPreviewGL.kt"
CS = A + "ui/camera/CameraScreen.kt"

OPS = [
    # ENUM（可选增强，给了取景器边缘检测独立频控/内存预算）
    ("x_insert_before", P, 52, BJR, "    GENERIC(10L, 16, 384L * 1024 * 1024),", 1),

    # STRINGS
    ("x_insert_after", P, 73, STRINGS,
     '    <string name="camera_metering_point_label">Metering</string>', 1),

    # ============ 1. LutRenderer ============
    ("x_insert_after", P, 149, LR, "import kotlin.math.hypot", 1),
    ("x_insert_after", P, 165, LR, "    private var focusPeakingFboHeight: Int = 0", 1),
    ("x_insert_after", P, 203, LR, "    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {", 1),
    ("x_replace", P, 222, 213, LR, 1),
    ("x_insert_after", P, 267, LR, "        focusPeakingFboHeight = 0", 1),
    ("x_insert_after", P, 281, LR, "    fun release() {", 1),
    ("x_insert_after", P, 295, LR,
     "    var onEyeFocusInputAvailable: ((EyeFocusPreviewFrame) -> Unit)? = null", 1),
    ("x_insert_after", P, 317, LR,
     "        val eyeFocusSamplingNeeded = onEyeFocusInputAvailable != null && !isEyeFocusBusy", 1),
    ("x_replace_lit", P, 329, LR, "            eyeFocusSamplingNeeded ||", 1),
    ("x_insert_before", P, 349, LR, '''            capturePendingPreviewFrames(
                finalDisplayTextureId = finalDisplayTextureId,''', 1),
    ("x_insert_after", P, 380, LR, "    private var lastEyeFocusSampleTimeNanos = 0L", 1),

    # ============ 2. CameraGLSurfaceView ============
    ("x_insert_after", P, 400, GSV, "    var onHistogramUpdated: ((IntArray) -> Unit)? = null", 1),
    ("x_insert_after", P, 421, GSV, '''    fun setFocusPeakingEnabled(enabled: Boolean) {
        renderer.focusPeakingEnabled = enabled
        requestRender()
    }''', 1),
    ("x_insert_after", P, 455, GSV, '''        renderer.onHistogramUpdated = { histogram ->
            onHistogramUpdated?.invoke(histogram)
        }''', 1),
    # 补丁未列出的必要 import（新增参数/方法用到的类型）
    ("insert_after", GSV, "import com.hinnka.mycamera.preview.EyeFocusPreviewFrame",
     '''import com.hinnka.mycamera.viewfinder.PeakingEnhanceConfig
import com.hinnka.mycamera.viewfinder.ZebraConfig''', 1),

    # ============ 3. CameraPreviewGL ============
    ("x_insert_after", P, 474, PGL, "    focusPeakingEnabled: Boolean = true,", 1),
    ("x_insert_after", P, 491, PGL,
     "                        glSurfaceView.setFocusPeakingEnabled(focusPeakingEnabled)", 1),
    ("x_insert_after", P, 506, PGL,
     "                        glSurfaceView.onEyeFocusInputAvailable = onEyeFocusInputAvailable", 1),
    ("insert_after", PGL, "import com.hinnka.mycamera.preview.EyeFocusPreviewFrame",
     '''import com.hinnka.mycamera.viewfinder.PeakingEnhanceConfig
import com.hinnka.mycamera.viewfinder.ZebraConfig''', 1),

    # ============ 4. CameraScreen ============
    ("insert_after", CS, "import androidx.compose.ui.platform.LocalContext",
     '''import com.hinnka.mycamera.ui.viewfinder.ViewfinderOverlayController
import com.hinnka.mycamera.ui.viewfinder.ViewfinderOverlayHost
import com.hinnka.mycamera.viewfinder.ViewfinderSettings''', 1),
    # 4.1 控制器创建（锚点改为语义等价的 previewAspectRatio 之后）
    ("x_insert_after", P, 524, CS, "    val previewAspectRatio = state.getPreviewAspectRatio()", 1),
    # 4.2 + 4.4 参数下发（锚点适配：实际调用处带 isHyperfocalFocusEnabled 组合）
    ("insert_after", CS, "                            focusPeakingEnabled = focusPeakingEnabled && !state.isHyperfocalFocusEnabled,",
     '''                            onViewfinderInputAvailable = { frame ->
                                viewfinderController.onViewfinderFrame(
                                    frame.bitmap,
                                    frame.sampleStartedAtNanos / 1_000_000L
                                )
                                viewModel.glSurfaceView?.setViewfinderSampleBusy(false)
                            },
                            zebraConfig = viewfinderSettings.value.zebra,
                            peakingEnhance = viewfinderSettings.value.peaking,
                            peakingEnhancedEnabled = true,''', 1),
    # 4.3 叠加层挂载
    ("x_insert_after", P, 575, CS, '''                        // 水平仪覆盖
                        if (showLevelIndicator) {
                            LevelIndicatorOverlay(
                                aspectRatio = previewAspectRatio,
                                modifier = Modifier.fillMaxSize()
                            )
                        }''', 1),
]
