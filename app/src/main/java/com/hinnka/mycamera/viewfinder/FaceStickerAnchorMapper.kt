package com.hinnka.mycamera.viewfinder

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * FaceMesh 关键点 → 贴纸锚点/贴位的**纯几何换算**（无 Android 依赖，可 JVM 单测）。
 *
 * 换算链路：MediaPipe 输入图像坐标系 →（可选水平镜像）→（可选按预览旋转角旋转）→（可选裁切）
 * → 预览显示归一化坐标系。
 *
 * 调用方若已把**显示方向**的帧送给 MediaPipe（与 `PreviewEyeFocusProcessor` 的取帧方式一致），
 * 则 `mirror=false, rotationDegrees=0` 即可（默认值，恒等变换）。
 */
object FaceStickerAnchorMapper {

    /** 竖直方向外推比例：额头锚点相对头顶再向上抬一点，贴纸不至于压住眉毛。 */
    private const val FOREHEAD_LIFT_RATIO = 0.18f

    /** 贴纸宽度相对人脸宽度的下限/上限，防误设导致巨大或不可见。 */
    private const val MIN_PLACEMENT_WIDTH_FRACTION = 0.02f
    private const val MAX_PLACEMENT_WIDTH_FRACTION = 2.0f

    /** 关键点存在但落在裁切框外时的置信度折减。 */
    private const val CLAMPED_CONFIDENCE = 0.5f

    /** 人脸宽度低于该值视为退化（远距离/检测失败）。 */
    private const val MIN_VALID_FACE_WIDTH = 0.01f

    /**
     * 把扁平关键点数组换算成 5 个人脸锚点。
     *
     * @param landmarks 扁平归一化关键点 `[x0,y0,x1,y1,...]`，长度需 >= [FaceLandmarkIndices.MIN_FLAT_ARRAY_LENGTH]
     * @param mirror 是否水平镜像
     * @param rotationDegrees 预览相对输入帧的顺时针旋转角，非 90 的整数倍时按任意角处理
     * @param crop 预览可见裁切框（[NormalizedCrop.FULL] 表示不裁切）
     * @return 固定顺序 [FOREHEAD, NOSE, MOUTH, LEFT_EYE, RIGHT_EYE]；关键点不足时返回空列表
     */
    fun mapAnchors(
        landmarks: FloatArray,
        mirror: Boolean = false,
        rotationDegrees: Int = 0,
        crop: NormalizedCrop = NormalizedCrop.FULL,
    ): List<StickerAnchor> {
        if (landmarks.size < FaceLandmarkIndices.MIN_FLAT_ARRAY_LENGTH) return emptyList()

        val leftEyeOuter = point(landmarks, FaceLandmarkIndices.LEFT_EYE_OUTER) ?: return emptyList()
        val leftEyeInner = point(landmarks, FaceLandmarkIndices.LEFT_EYE_INNER) ?: return emptyList()
        val rightEyeInner = point(landmarks, FaceLandmarkIndices.RIGHT_EYE_INNER) ?: return emptyList()
        val rightEyeOuter = point(landmarks, FaceLandmarkIndices.RIGHT_EYE_OUTER) ?: return emptyList()
        val foreheadTop = point(landmarks, FaceLandmarkIndices.FOREHEAD_TOP) ?: return emptyList()
        val chin = point(landmarks, FaceLandmarkIndices.CHIN) ?: return emptyList()
        val noseTip = point(landmarks, FaceLandmarkIndices.NOSE_TIP) ?: return emptyList()
        val mouthUpper = point(landmarks, FaceLandmarkIndices.MOUTH_UPPER_INNER) ?: return emptyList()
        val mouthLower = point(landmarks, FaceLandmarkIndices.MOUTH_LOWER_INNER) ?: return emptyList()

        var anyClamped = false
        fun transform(source: LandmarkPoint): LandmarkPoint {
            val mapped = mapPoint(source, mirror, rotationDegrees, crop)
            if (mapped.clamped) anyClamped = true
            return mapped.point
        }

        val leftEye = transform(average(leftEyeOuter, leftEyeInner))
        val rightEye = transform(average(rightEyeOuter, rightEyeInner))
        val faceTop = transform(foreheadTop)
        val faceBottom = transform(chin)
        val nose = transform(noseTip)
        val mouth = transform(average(mouthUpper, mouthLower))

        val faceWidth = hypot(rightEye.x - leftEye.x, rightEye.y - leftEye.y)
        val faceHeight = hypot(faceBottom.x - faceTop.x, faceBottom.y - faceTop.y)
        if (faceWidth < MIN_VALID_FACE_WIDTH || faceHeight < MIN_VALID_FACE_WIDTH) {
            return emptyList()
        }

        val roll = Math.toDegrees(
            atan2((rightEye.y - leftEye.y).toDouble(), (rightEye.x - leftEye.x).toDouble()),
        ).toFloat()

        // 头顶再向上外推，让"耳朵/皇冠"类贴纸有落点空间。
        val liftY = faceTop.y - faceHeight * FOREHEAD_LIFT_RATIO
        val confidence = if (anyClamped) CLAMPED_CONFIDENCE else 1f

        return listOf(
            StickerAnchor(faceTop.x, liftY.coerceIn(0f, 1f), faceWidth, roll, confidence),
            StickerAnchor(nose.x, nose.y, faceWidth, roll, confidence),
            StickerAnchor(mouth.x, mouth.y, faceWidth, roll, confidence),
            StickerAnchor(leftEye.x, leftEye.y, faceWidth, roll, confidence),
            StickerAnchor(rightEye.x, rightEye.y, faceWidth, roll, confidence),
        )
    }

    /** 按部位取单个锚点；越界部位回落 [StickerAnchor.NONE]。 */
    fun anchorFor(mode: StickerAnchorMode, anchors: List<StickerAnchor>): StickerAnchor {
        val index = when (mode) {
            StickerAnchorMode.FOREHEAD -> 0
            StickerAnchorMode.NOSE -> 1
            StickerAnchorMode.MOUTH -> 2
            StickerAnchorMode.LEFT_EYE -> 3
            StickerAnchorMode.RIGHT_EYE -> 4
        }
        return anchors.getOrNull(index) ?: StickerAnchor.NONE
    }

    /**
     * 把锚点 + 贴纸配置 + 贴纸定义换算成最终贴位。
     *
     * 位移以**人脸宽度**为单位，因此不同距离/缩放下的偏移观感一致。
     */
    fun placementFor(
        anchor: StickerAnchor,
        config: StickerConfig,
        definition: FaceStickerDefinition,
    ): StickerPlacement {
        val faceWidth = anchor.faceWidth.coerceAtLeast(0f)
        val scale = config.scale.coerceIn(
            ViewfinderParamRanges.STICKER_SCALE_MIN,
            ViewfinderParamRanges.STICKER_SCALE_MAX,
        )
        val widthFraction = (faceWidth * definition.widthToFaceRatio * definition.defaultScale * scale)
            .coerceIn(MIN_PLACEMENT_WIDTH_FRACTION, MAX_PLACEMENT_WIDTH_FRACTION)
        val offsetX = config.offsetX.coerceIn(
            ViewfinderParamRanges.STICKER_OFFSET_MIN,
            ViewfinderParamRanges.STICKER_OFFSET_MAX,
        )
        val offsetY = config.offsetY.coerceIn(
            ViewfinderParamRanges.STICKER_OFFSET_MIN,
            ViewfinderParamRanges.STICKER_OFFSET_MAX,
        )
        return StickerPlacement(
            centerX = (anchor.x + (offsetX + definition.defaultOffsetX) * faceWidth).coerceIn(0f, 1f),
            centerY = (anchor.y + (offsetY + definition.defaultOffsetY) * faceWidth).coerceIn(0f, 1f),
            widthFraction = widthFraction,
            rollDegrees = anchor.rollDegrees,
            alpha = config.alpha.coerceIn(
                ViewfinderParamRanges.STICKER_ALPHA_MIN,
                ViewfinderParamRanges.STICKER_ALPHA_MAX,
            ),
            mirrored = config.mirror,
        )
    }

    /** EMA 平滑：`alpha` = 保留旧值的比例（0f = 不平滑）。 */
    fun smooth(previous: StickerAnchor, incoming: StickerAnchor, smoothing: Float): StickerAnchor {
        if (!previous.isValid) return incoming
        if (!incoming.isValid) return incoming
        val keep = smoothing.coerceIn(
            ViewfinderParamRanges.STICKER_SMOOTHING_MIN,
            ViewfinderParamRanges.STICKER_SMOOTHING_MAX,
        )
        return StickerAnchor(
            x = previous.x + (incoming.x - previous.x) * (1f - keep),
            y = previous.y + (incoming.y - previous.y) * (1f - keep),
            faceWidth = previous.faceWidth + (incoming.faceWidth - previous.faceWidth) * (1f - keep),
            rollDegrees = previous.rollDegrees + shortestAngleDelta(previous.rollDegrees, incoming.rollDegrees) * (1f - keep),
            confidence = incoming.confidence,
        )
    }

    /**
     * 单点变换：镜像 → 旋转 → 裁切。
     *
     * @return 变换后坐标 + 是否因落在裁切框外而被 clamp
     */
    fun mapPoint(
        source: LandmarkPoint,
        mirror: Boolean,
        rotationDegrees: Int,
        crop: NormalizedCrop,
    ): MappedPoint {
        var x = source.x
        var y = source.y
        if (mirror) x = 1f - x

        if (rotationDegrees % 360 != 0) {
            val radians = Math.toRadians(rotationDegrees.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val dx = x - 0.5f
            val dy = y - 0.5f
            // y 轴向下的屏幕坐标系里，该矩阵对应「顺时针」旋转。
            x = 0.5f + dx * c - dy * s
            y = 0.5f + dx * s + dy * c
        }

        val cropWidth = crop.width
        val cropHeight = crop.height
        if (cropWidth > 0f && cropHeight > 0f &&
            (crop.left != 0f || crop.top != 0f || crop.right != 1f || crop.bottom != 1f)
        ) {
            x = (x - crop.left) / cropWidth
            y = (y - crop.top) / cropHeight
        }

        val clampedX = x.coerceIn(0f, 1f)
        val clampedY = y.coerceIn(0f, 1f)
        return MappedPoint(
            point = LandmarkPoint(clampedX, clampedY),
            clamped = clampedX != x || clampedY != y,
        )
    }

    /** 关键点数量校验（供检测器前置判断）。 */
    fun hasEnoughLandmarks(landmarkCount: Int): Boolean =
        landmarkCount == FaceLandmarkIndices.FACE_MESH_LANDMARK_COUNT ||
            landmarkCount == FaceLandmarkIndices.FACE_MESH_LANDMARK_COUNT_WITH_IRIS

    private fun point(landmarks: FloatArray, index: Int): LandmarkPoint? {
        val offset = index * 2
        if (offset + 1 >= landmarks.size) return null
        val x = landmarks[offset]
        val y = landmarks[offset + 1]
        if (!x.isFinite() || !y.isFinite()) return null
        return LandmarkPoint(x, y)
    }

    private fun average(a: LandmarkPoint, b: LandmarkPoint): LandmarkPoint =
        LandmarkPoint((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

    /** 取到目标角的最短路径差值（-180..180），避免平滑时绕整圈。 */
    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}

/** 归一化关键点。 */
data class LandmarkPoint(val x: Float, val y: Float)

/** [FaceStickerAnchorMapper.mapPoint] 的结果。 */
data class MappedPoint(val point: LandmarkPoint, val clamped: Boolean)
