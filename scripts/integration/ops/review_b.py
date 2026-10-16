# -*- coding: utf-8 -*-
"""review_patch 集成操作（b）：Camera2Controller.kt 全部 hook。"""

A = "app/src/main/java/com/hinnka/mycamera/"
C2 = A + "camera/Camera2Controller.kt"

OPS = []

# ---- P0-3：单批提交上限（分片大小语义；>reader 容量的续跑链路未落地 → 未决项） ----
OPS += [
    ("insert_after", C2, "        private const val BURST_CAPTURE_BATCH_SIZE = 8", '''        /**
         * 单次 submitPhotoCapture 允许提交的最大请求数（受 ImageReader 容量限制）。
         * 这是**分片大小**，不是总帧数上限：取值 = CAPTURE_READER_MAX_IMAGES，
         * 因此默认配置下与改动前逐字节同行为（不再对超量请求直接拒拍）。
         */
        private const val MULTI_FRAME_CAPTURE_BATCH_MAX = CAPTURE_READER_MAX_IMAGES''', 1),

    ("replace", C2, "        state.requiresMultiFrameCaptureSequence -> state.activeMultiFrameCount",
     '''        state.requiresMultiFrameCaptureSequence ->
            minOf(state.activeMultiFrameCount, MULTI_FRAME_CAPTURE_BATCH_MAX)''', 1),

    # ---- P0-5：对齐默认值单一真值源 ----
    ("replace", C2, "    private var psStackAlign: Boolean = false",
     "    private var psStackAlign: Boolean = MultiFrameConfig.DEFAULT_STACK_ALIGN", 1),

    # ---- P0-6：场景策略快照/还原 ----
    ("insert_after", C2, "    private var activeScene: ComputationalScene = ComputationalScene.STANDARD", '''
    /** 星轨叠加开关（P0-4/P0-7：与 gallery 路由共用；由 setStarTrailsEnabled 写入）。 */
    @Volatile
    private var starTrailsEnabled: Boolean = false
    @Volatile
    private var trailAccumulationMode: AstroStacker.TrailAccumulationMode =
        AstroStacker.TrailAccumulationMode.LIGHTEN_MAX

    fun setStarTrailsEnabled(enabled: Boolean) {
        starTrailsEnabled = enabled
        if (enabled) {
            // 星轨必须免对齐（星点位移就是要保留的结果）。
            psStackAlign = false
        }
    }

    fun setTrailAccumulationMode(mode: AstroStacker.TrailAccumulationMode) {
        trailAccumulationMode = mode
    }

    /** 进入计算场景前的用户基线快照（离开场景时还原，避免策略静默改写用户设置）。 */
    private var sceneBaseline: SceneBaseline? = null

    private data class SceneBaseline(
        val useRaw: Boolean,
        val jpgFrames: Int,
        val hdrPlusFrames: Int,
        val previewStabilization: Boolean,
    )''', 1),

    ("replace", C2, '''        _state.value = state.copy(
            useRaw = preferRaw && supportsRawMultiFrame(),
            jpgMultiFrameDenoiseFrameCount =
                if (scene == ComputationalScene.STACK) psStackFrameCount else state.jpgMultiFrameDenoiseFrameCount,
            hdrPlusFrameCount =
                if (scene == ComputationalScene.STACK ||
                    scene == ComputationalScene.NIGHT ||
                    scene == ComputationalScene.ASTRO
                ) psStackFrameCount.coerceAtLeast(3) else state.hdrPlusFrameCount,
            isVirtualApertureEnabled = scene == ComputationalScene.PORTRAIT,
            photoPreviewStabilizationEnabled = if (tripodFixed) false else state.photoPreviewStabilizationEnabled,
        )''', '''        val baseline = sceneBaseline ?: SceneBaseline(
            useRaw = state.useRaw,
            jpgFrames = state.jpgMultiFrameDenoiseFrameCount,
            hdrPlusFrames = state.hdrPlusFrameCount,
            previewStabilization = state.photoPreviewStabilizationEnabled,
        ).also { sceneBaseline = it }

        val leaveScene = scene == ComputationalScene.STANDARD ||
            scene == ComputationalScene.HDR ||
            scene == ComputationalScene.PORTRAIT
        _state.value = state.copy(
            useRaw = if (leaveScene) baseline.useRaw else (preferRaw && supportsRawMultiFrame()),
            jpgMultiFrameDenoiseFrameCount = if (scene == ComputationalScene.STACK) {
                psStackFrameCount
            } else {
                baseline.jpgFrames
            },
            hdrPlusFrameCount = if (scene == ComputationalScene.STACK ||
                scene == ComputationalScene.NIGHT ||
                scene == ComputationalScene.ASTRO
            ) {
                // 帧数开放：不再用 coerceAtLeast(3) 魔法数，统一走配置侧最小帧数（包围曝光时最小 2）。
                MultiFrameConfig.normalizeHdrPlusFrameCount(
                    psStackFrameCount,
                    bracketExposureEnabled = state.isHdrPlusBracketExposureEnabled,
                )
            } else {
                baseline.hdrPlusFrames
            },
            isVirtualApertureEnabled = scene == ComputationalScene.PORTRAIT,
            photoPreviewStabilizationEnabled = if (tripodFixed) false else baseline.previewStabilization,
        )
        if (leaveScene) sceneBaseline = null''', 1),

    # ---- P0-7 + P2-3：堆栈分发改走唯一路由器（注释同步更正） ----
    ("replace", C2, '''     * 计算摄影堆栈调度（internal，gallery/同模块在连拍完成后调用；A1 提供的调用点）。''',
     '''     * 计算摄影堆栈调度（internal；由 GalleryManager 经 ComputationalStackRouter 调用，A1 提供的调用点）。''', 1),

    ("replace", C2, '''            when (activeScene) {
                ComputationalScene.STACK -> MultiFrameStacker.processPsStack(
                    images = frames,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    type = psStackType,
                    frameCount = psStackFrameCount,
                    align = psStackAlign,
                )
                ComputationalScene.ASTRO -> MultiFrameStacker.processAstroBurst(
                    images = frames,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                )
                else -> null
            }''', '''            ComputationalStackRouter.route(
                request = ComputationalStackRouter.StackRequest(
                    scene = activeScene,
                    stackType = psStackType,
                    frameCount = psStackFrameCount,
                    align = psStackAlign,
                    starTrailsEnabled = starTrailsEnabled,
                    trailAccumulationMode = trailAccumulationMode,
                ),
                images = frames,
                rotation = rotation,
                aspectRatio = aspectRatio,
                colorSpace = colorSpace,
            )''', 1),

    ("insert_after", C2, "import com.hinnka.mycamera.processor.MultiFrameStacker",
     "import com.hinnka.mycamera.processor.ComputationalStackRouter", 1),
]

# ---- P1-5：ASTRO 单帧曝光上限真正生效 ----
OPS += [
    ("replace", C2, '''    private val astroPerFrameMaxExposureNs = 500_000_000L''', '''    /** ASTRO 单帧曝光上限：默认 0.5s（可配；0 或负数表示不限制）。 */
    @Volatile
    var astroPerFrameMaxExposureNs: Long = 500_000_000L

    /** ASTRO 采帧时对单帧曝光做 clamp（单一真值源；不再有"声明未接线"的常量）。 */
    private fun clampAstroPerFrameExposure(ns: Long): Long =
        if (astroPerFrameMaxExposureNs <= 0L) ns else ns.coerceAtMost(astroPerFrameMaxExposureNs)''', 1),

    ("replace", C2, '''                val exposureTime = if (isCapture) {
                    state.shutterSpeed
                } else {
                    coercePreviewExposureTime(state)
                }''', '''                val exposureTime = if (isCapture) {
                    // ASTRO：单帧曝光上限（可配，默认 0.5s）——原 astroPerFrameMaxExposureNs 死常量在此真正生效。
                    if (activeScene == ComputationalScene.ASTRO) {
                        clampAstroPerFrameExposure(state.shutterSpeed)
                    } else {
                        state.shutterSpeed
                    }
                } else {
                    coercePreviewExposureTime(state)
                }''', 1),
]

# ---- P1-6：AWB 色温范围统一（设备上报优先，回退默认） ----
OPS += [
    ("replace", C2, '''    private fun resolveAwbTemperatureRange(): Range<Int> {
        return Range(AWB_TEMPERATURE_MIN, AWB_TEMPERATURE_MAX)
    }''', '''    private fun resolveAwbTemperatureRange(): Range<Int> {
        // 优先设备上报范围（Android 16 / COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE）；
        // 未上报时回退既有默认 2000-8000（零回归）。
        val advertised = awbColorTemperatureRange
        if (advertised != null && advertised.lower < advertised.upper) {
            return advertised
        }
        return Range(AWB_TEMPERATURE_MIN, AWB_TEMPERATURE_MAX)
    }''', 1),

    ("replace", C2, "        val temperature = kelvin.coerceIn(1000, 40000) / 100.0f",
     "        val temperature = kelvin.coerceIn(1000, 40_000) / 100.0f   // 仅算法域下限/上限，不参与 UI 范围", 1),
]

# ---- P1-14 / P2-2：对焦与传输超时参数开放 ----
OPS += [
    ("insert_after", C2, "        private const val AF_READY_DEFER_MS = 220L           // A1：AF 未收敛时快门最长推迟等待", '''        /** 以下对焦/测光/场景参数开放可配（默认值＝原常量，零回归）。 */
        @Volatile var userAfReadyDeferMs: Long = AF_READY_DEFER_MS
        @Volatile var userFocusLockSettleFrames: Int = FOCUS_LOCK_SETTLE_FRAMES
        @Volatile var userSceneChangeConfirmFrames: Int = SCENE_CHANGE_CONFIRM_FRAMES
        @Volatile var userAfRegionWidthFraction: Float = AF_REGION_WIDTH_FRACTION
        @Volatile var userAfRegionHeightFraction: Float = AF_REGION_HEIGHT_FRACTION
        @Volatile var userVideoAfRegionFraction: Float = VIDEO_AF_REGION_FRACTION
        @Volatile var userPrecaptureMaxWaitFrames: Long = PRECAPTURE_MAX_WAIT_FRAMES
        fun afReadyDeferMs(): Long = userAfReadyDeferMs.coerceIn(0L, 5_000L)
        fun focusLockSettleFrames(): Int = userFocusLockSettleFrames.coerceIn(0, 60)
        fun sceneChangeConfirmFrames(): Int = userSceneChangeConfirmFrames.coerceIn(1, 30)
        fun afRegionWidthFraction(): Float = userAfRegionWidthFraction.coerceIn(0.01f, 0.5f)
        fun afRegionHeightFraction(): Float = userAfRegionHeightFraction.coerceIn(0.01f, 0.5f)
        fun videoAfRegionFraction(): Float = userVideoAfRegionFraction.coerceIn(0.05f, 0.8f)
        fun precaptureMaxWaitFrames(): Long = userPrecaptureMaxWaitFrames.coerceIn(5L, 600L)''', 1),

    ("replace", C2, "        focusLockSettleFrames = FOCUS_LOCK_SETTLE_FRAMES",
     "        focusLockSettleFrames = focusLockSettleFrames()", 1),
    ("replace", C2, "        }, AF_READY_DEFER_MS)", "        }, afReadyDeferMs())", 1),
    ("replace", C2, "                widthFraction = AF_REGION_WIDTH_FRACTION,",
     "                widthFraction = afRegionWidthFraction(),", 1),
    ("replace", C2, "                heightFraction = AF_REGION_HEIGHT_FRACTION,",
     "                heightFraction = afRegionHeightFraction(),", 1),
    ("replace", C2, "        val w = (bounds.width() * VIDEO_AF_REGION_FRACTION).toInt().coerceAtLeast(1)",
     "        val w = (bounds.width() * videoAfRegionFraction()).toInt().coerceAtLeast(1)", 1),
    ("replace", C2, "        val h = (bounds.height() * VIDEO_AF_REGION_FRACTION).toInt().coerceAtLeast(1)",
     "        val h = (bounds.height() * videoAfRegionFraction()).toInt().coerceAtLeast(1)", 1),
    ("replace", C2, "        if (result.frameNumber - pending.firstResultFrameNumber!! > PRECAPTURE_MAX_WAIT_FRAMES) {",
     "        if (result.frameNumber - pending.firstResultFrameNumber!! > precaptureMaxWaitFrames()) {", 1),
    ("replace", C2, "                            if (sceneChangeFrameCount >= SCENE_CHANGE_CONFIRM_FRAMES) {",
     "                            if (sceneChangeFrameCount >= sceneChangeConfirmFrames()) {", 1),
    # 同参数日志一致性（避免上报旧常量）
    ("replace", C2, '                PLog.w(TAG, "AF ready wait timed out after ${AF_READY_DEFER_MS}ms, releasing shutter")',
     '                PLog.w(TAG, "AF ready wait timed out after ${afReadyDeferMs()}ms, releasing shutter")', 1),
    ("replace", C2, '            PLog.d(TAG, "AF not converged; gating shutter, deferring up to ${AF_READY_DEFER_MS}ms")',
     '            PLog.d(TAG, "AF not converged; gating shutter, deferring up to ${afReadyDeferMs()}ms")', 1),

    ("insert_after", C2, "        private const val CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS = 10_000L", '''        /** 单张图片交付超时（P2-2：开放可配，默认 10s 零回归）。 */
        @Volatile var userCaptureImageDeliveryTimeoutMs: Long = CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS
        fun captureImageDeliveryTimeoutMs(): Long =
            userCaptureImageDeliveryTimeoutMs.coerceIn(1_000L, 60_000L)''', 1),
    ("replace", C2, "            cameraHandler?.postDelayed(timeout, CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS)",
     "            cameraHandler?.postDelayed(timeout, captureImageDeliveryTimeoutMs())", 2),
    ("replace", C2, "                }, CAPTURE_IMAGE_DELIVERY_TIMEOUT_MS)",
     "                }, captureImageDeliveryTimeoutMs())", 1),
]

# ---- P1-1 调用点：长帧曝光/回退 ISO 读函数版 ----
OPS += [
    ("replace", C2, '                            "${MultiFrameConfig.LONG_FRAME_MAX_EXPOSURE_TIME_NS} " +',
     '                            "${MultiFrameConfig.longFrameMaxExposureTimeNs()} " +', 1),
    ("replace", C2, "                    ?: MultiFrameConfig.LONG_FRAME_FALLBACK_MAX_ANALOG_SENSITIVITY,",
     "                    ?: MultiFrameConfig.longFrameFallbackMaxIso(),", 1),
]

# ---- P1-3 调用点：HDR 包围 EV 读函数版 ----
OPS += [
    ("replace", C2, "            add(HdrBracketConfig.YUV_LONG_EV)",
     "            add(HdrBracketConfig.yuvLongEv())", 1),
    ("replace", C2, "            add(HdrBracketConfig.YUV_SHORT_EV)",
     "            add(HdrBracketConfig.yuvShortEv())", 1),
]
