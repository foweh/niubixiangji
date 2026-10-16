package com.hinnka.mycamera.viewfinder

/**
 * MediaPipe FaceMesh（tasks-vision 1.0.0）关键点下标常量。
 *
 * FaceMesh 输出 468 个归一化关键点（开启 iris 细分后 478），下表为贴纸锚定所需的少量下标。
 * 命名一律以**画面方位**为准（"LEFT" = 画面左侧），避免与"被摄者自身左/右"混淆。
 */
object FaceLandmarkIndices {

    /** 基础 FaceMesh 关键点数量（不含 iris 细分）。 */
    const val FACE_MESH_LANDMARK_COUNT = 468

    /** 含 iris 细分的数量。 */
    const val FACE_MESH_LANDMARK_COUNT_WITH_IRIS = 478

    /** 头顶（额头最高点）。 */
    const val FOREHEAD_TOP = 10

    /** 鼻尖。 */
    const val NOSE_TIP = 1

    /** 上唇内缘 / 下唇内缘（取中点即嘴心）。 */
    const val MOUTH_UPPER_INNER = 13
    const val MOUTH_LOWER_INNER = 14

    /** 画面左侧眼：外眼角 / 内眼角。 */
    const val LEFT_EYE_OUTER = 33
    const val LEFT_EYE_INNER = 133

    /** 画面右侧眼：内眼角 / 外眼角。 */
    const val RIGHT_EYE_INNER = 362
    const val RIGHT_EYE_OUTER = 263

    /** 下颌尖（人脸高度基准之一）。 */
    const val CHIN = 152

    /** 脸部左右轮廓（人脸宽度基准）。 */
    const val FACE_CONTOUR_LEFT = 234
    const val FACE_CONTOUR_RIGHT = 454

    /** 贴纸锚定所依赖的全部下标（自检用）。 */
    val REQUIRED_INDICES: IntArray = intArrayOf(
        FOREHEAD_TOP, NOSE_TIP, MOUTH_UPPER_INNER, MOUTH_LOWER_INNER,
        LEFT_EYE_OUTER, LEFT_EYE_INNER, RIGHT_EYE_INNER, RIGHT_EYE_OUTER,
        CHIN, FACE_CONTOUR_LEFT, FACE_CONTOUR_RIGHT,
    )

    /** 关键点数组（[x0,y0,x1,y1,...] 扁平存储）所需的最小长度。 */
    const val MIN_FLAT_ARRAY_LENGTH = FACE_MESH_LANDMARK_COUNT * 2
}

/** 贴纸锚定的人脸部位（可配）。 */
enum class StickerAnchorMode { FOREHEAD, NOSE, MOUTH, LEFT_EYE, RIGHT_EYE }

/** 归一化裁切框（预览可见区域），左上原点，取值 0f..1f。 */
data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = NormalizedCrop()
    }
}

/**
 * 一个已换算到**预览显示坐标系**的人脸锚点。
 *
 * @param x / y 归一化坐标（0f..1f，左上原点）
 * @param faceWidth 归一化人脸宽度（两眼外角距离，作为贴纸尺寸的尺度基准）
 * @param rollDegrees 人脸滚转角（度，顺时针为正，来自两眼中点连线）
 * @param confidence 置信度；0f 表示该锚点不可用（关键点缺失或人脸退化）
 */
data class StickerAnchor(
    val x: Float,
    val y: Float,
    val faceWidth: Float,
    val rollDegrees: Float,
    val confidence: Float,
) {
    val isValid: Boolean get() = confidence > 0f && faceWidth > 0f

    companion object {
        val NONE = StickerAnchor(0.5f, 0.5f, 0f, 0f, 0f)
    }
}

/**
 * 一枚贴纸在预览中的最终绘制位置（已套用缩放/位移/镜像/透明度）。
 *
 * @param centerX / centerY 归一化中心（0f..1f）
 * @param widthFraction 绘制宽度占预览**宽度**的比例
 * @param rollDegrees 旋转角（度，顺时针为正）
 * @param alpha 不透明度 0f..1f
 * @param mirrored 是否水平镜像
 */
data class StickerPlacement(
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val rollDegrees: Float,
    val alpha: Float,
    val mirrored: Boolean,
)
