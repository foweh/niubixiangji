package com.hinnka.mycamera.viewfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FaceStickerAnchorMapper] 单测：锚点坐标换算（镜像 / 旋转 / 裁切）、人脸退化、贴位换算与钳制、
 * 角度平滑（含跨 180° 的最短路径）。
 *
 * 关键点合成：构造 468 点（936 个 float）的扁平数组，只在需要的下标写字，其余为 0。
 * 所有期望值都由几何定义手工推导，不依赖 Android 运行时。
 */
class FaceStickerAnchorMapperTest {

    private val delta = 1e-4f

    // 人脸基准几何（未变换）：
    //   头顶(10)=(0.50,0.20)  下颌(152)=(0.50,0.95)  鼻尖(1)=(0.50,0.55)
    //   上唇(13)=(0.50,0.68)  下唇(14)=(0.50,0.72) → 嘴心(0.50,0.70)
    //   左眼外(33)=(0.35,0.40) 左眼内(133)=(0.45,0.40) → 左眼(0.40,0.40)
    //   右眼内(362)=(0.55,0.40) 右眼外(263)=(0.65,0.40) → 右眼(0.60,0.40)
    //   → 人脸宽度 0.20，人脸高度 0.75
    private fun faceLandmarks(): FloatArray {
        val array = FloatArray(FaceLandmarkIndices.MIN_FLAT_ARRAY_LENGTH)
        fun put(index: Int, x: Float, y: Float) {
            array[index * 2] = x
            array[index * 2 + 1] = y
        }
        put(FaceLandmarkIndices.FOREHEAD_TOP, 0.50f, 0.20f)
        put(FaceLandmarkIndices.CHIN, 0.50f, 0.95f)
        put(FaceLandmarkIndices.NOSE_TIP, 0.50f, 0.55f)
        put(FaceLandmarkIndices.MOUTH_UPPER_INNER, 0.50f, 0.68f)
        put(FaceLandmarkIndices.MOUTH_LOWER_INNER, 0.50f, 0.72f)
        put(FaceLandmarkIndices.LEFT_EYE_OUTER, 0.35f, 0.40f)
        put(FaceLandmarkIndices.LEFT_EYE_INNER, 0.45f, 0.40f)
        put(FaceLandmarkIndices.RIGHT_EYE_INNER, 0.55f, 0.40f)
        put(FaceLandmarkIndices.RIGHT_EYE_OUTER, 0.65f, 0.40f)
        put(FaceLandmarkIndices.FACE_CONTOUR_LEFT, 0.30f, 0.50f)
        put(FaceLandmarkIndices.FACE_CONTOUR_RIGHT, 0.70f, 0.50f)
        return array
    }

    // ------------------------------------------------------------ 恒等换算

    @Test
    fun mapAnchors_identityTransform() {
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks())
        assertEquals(5, anchors.size)

        // 顺序固定为 [FOREHEAD, NOSE, MOUTH, LEFT_EYE, RIGHT_EYE]
        val forehead = anchors[0]
        val nose = anchors[1]
        val mouth = anchors[2]
        val leftEye = anchors[3]
        val rightEye = anchors[4]

        // 头顶 = 额头点再向上抬 faceHeight * 0.18 = 0.75 * 0.18 = 0.135
        assertEquals(0.50f, forehead.x, delta)
        assertEquals(0.20f - 0.135f, forehead.y, delta)
        assertEquals(0.20f, forehead.faceWidth, delta)
        assertEquals(0f, forehead.rollDegrees, delta)
        assertEquals(1f, forehead.confidence, delta)

        assertEquals(0.50f, nose.x, delta)
        assertEquals(0.55f, nose.y, delta)
        assertEquals(0.50f, mouth.x, delta)
        assertEquals(0.70f, mouth.y, delta)
        assertEquals(0.40f, leftEye.x, delta)
        assertEquals(0.60f, rightEye.x, delta)
        assertEquals(0.40f, leftEye.y, delta)
        assertEquals(1f, leftEye.confidence, delta)
    }

    @Test
    fun foreheadAnchor_isClampedInsideFrame() {
        // 头顶离画面上边缘很近：外推后必须被压回 0f，不能出现负坐标
        val array = faceLandmarks()
        array[FaceLandmarkIndices.FOREHEAD_TOP * 2 + 1] = 0.02f
        val forehead = FaceStickerAnchorMapper.mapAnchors(array)[0]
        assertEquals(0f, forehead.y, delta)
    }

    // ---------------------------------------------------------------- 镜像

    @Test
    fun mirrorFlipsXAndRollsOneHundredEighty() {
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), mirror = true)
        // 画面左眼(0.40) ↔ 右眼(0.60) 互换
        assertEquals(0.60f, anchors[3].x, delta)
        assertEquals(0.40f, anchors[4].x, delta)
        // 宽度不变（镜像不改变距离）
        assertEquals(0.20f, anchors[3].faceWidth, delta)
        // 眼向量反向 → 滚转 ±180°
        assertEquals(180f, kotlin.math.abs(anchors[3].rollDegrees), 0.01f)
        // 中轴点（鼻/嘴）水平位置不变
        assertEquals(0.50f, anchors[1].x, delta)
        assertEquals(0.50f, anchors[2].x, delta)
    }

    // ---------------------------------------------------------------- 旋转

    @Test
    fun rotationNinetyDegreesMatchesHandComputedGeometry() {
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), rotationDegrees = 90)
        // 绕 (0.5,0.5) 顺时针 90°：左眼(0.40,0.40)→(0.60,0.40)，右眼(0.60,0.40)→(0.60,0.60)
        assertEquals(0.60f, anchors[3].x, delta)
        assertEquals(0.40f, anchors[3].y, delta)
        assertEquals(0.60f, anchors[4].x, delta)
        assertEquals(0.60f, anchors[4].y, delta)
        // 旋转保持距离 → 人脸宽度不变
        assertEquals(0.20f, anchors[3].faceWidth, delta)
        // 眼向量在旋转后竖直 → 滚转 90°
        assertEquals(90f, anchors[3].rollDegrees, 0.01f)
        // 嘴心(0.50,0.70)→(0.30,0.50)
        assertEquals(0.30f, anchors[2].x, delta)
        assertEquals(0.50f, anchors[2].y, delta)
    }

    @Test
    fun rotationMultipleOf360IsIdentity() {
        val identity = FaceStickerAnchorMapper.mapAnchors(faceLandmarks())
        for (angle in intArrayOf(0, 360, -360, 720)) {
            val other = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), rotationDegrees = angle)
            assertEquals(identity[2].x, other[2].x, delta)
            assertEquals(identity[3].y, other[3].y, delta)
        }
        // 非 360 倍数才旋转
        val rotated = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), rotationDegrees = 180)
        assertFalse(kotlin.math.abs(rotated[3].x - identity[3].x) < delta)
    }

    // ---------------------------------------------------------------- 裁切

    @Test
    fun cropRescalesCoordinatesAndWidth() {
        val crop = NormalizedCrop(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), crop = crop)

        // 左眼 (0.40,0.40) → ((0.40-0.25)/0.5, (0.40-0.25)/0.5) = (0.30,0.30)
        assertEquals(0.30f, anchors[3].x, delta)
        assertEquals(0.30f, anchors[3].y, delta)
        // 宽度同比放大 1/0.5 = 2 倍
        assertEquals(0.40f, anchors[3].faceWidth, delta)
        // 嘴心 (0.50,0.70) → (0.50,0.90)
        assertEquals(0.50f, anchors[2].x, delta)
        assertEquals(0.90f, anchors[2].y, delta)
        // 头顶与下颌都被裁到框外 → 置信度折减为 0.5
        assertEquals(0.5f, anchors[0].confidence, delta)
    }

    @Test
    fun cropClampedPointLowersConfidence() {
        val crop = NormalizedCrop(left = 0.45f, top = 0f, right = 1f, bottom = 1f)
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), crop = crop)

        // 左眼 x = (0.40-0.45)/0.55 = -0.09 → clamp 到 0
        assertEquals(0f, anchors[3].x, delta)
        assertEquals(0.5f, anchors[3].confidence, delta)
        assertTrue(anchors[3].isValid) // 仍可用，只是置信度折减
    }

    @Test
    fun fullCropIsTreatedAsIdentity() {
        val identity = FaceStickerAnchorMapper.mapAnchors(faceLandmarks())
        val full = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), crop = NormalizedCrop.FULL)
        assertEquals(identity[3].x, full[3].x, delta)
        assertEquals(identity[3].y, full[3].y, delta)
        assertEquals(identity[3].faceWidth, full[3].faceWidth, delta)
    }

    @Test
    fun degenerateCropIsIgnoredInsteadOfDividingByZero() {
        // 宽或高为 0 的非法裁切框：跳过裁切而不是产生 NaN/Inf
        val zeroWidth = NormalizedCrop(left = 0.5f, top = 0f, right = 0.5f, bottom = 1f)
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks(), crop = zeroWidth)
        assertEquals(0.40f, anchors[3].x, delta)
        assertTrue(anchors[3].confidence.isFinite())
    }

    // ------------------------------------------------------ 退化与异常输入

    @Test
    fun insufficientLandmarksReturnsEmptyList() {
        assertTrue(FaceStickerAnchorMapper.mapAnchors(FloatArray(0)).isEmpty())
        assertTrue(FaceStickerAnchorMapper.mapAnchors(FloatArray(10)).isEmpty())
        // 差一个点也不放过
        assertTrue(FaceStickerAnchorMapper.mapAnchors(FloatArray(935)).isEmpty())
    }

    @Test
    fun nonFiniteLandmarkReturnsEmptyList() {
        val array = faceLandmarks()
        array[FaceLandmarkIndices.FOREHEAD_TOP * 2] = Float.NaN
        assertTrue(FaceStickerAnchorMapper.mapAnchors(array).isEmpty())

        val infinite = faceLandmarks()
        infinite[FaceLandmarkIndices.NOSE_TIP * 2 + 1] = Float.POSITIVE_INFINITY
        assertTrue(FaceStickerAnchorMapper.mapAnchors(infinite).isEmpty())
    }

    @Test
    fun zeroSizedFaceIsRejected() {
        // 全 0 关键点 → 人脸宽度/高度为 0 → 视为退化
        assertTrue(
            FaceStickerAnchorMapper.mapAnchors(FloatArray(FaceLandmarkIndices.MIN_FLAT_ARRAY_LENGTH)).isEmpty(),
        )
    }

    @Test
    fun hasEnoughLandmarks_acceptsPairCountsOnly() {
        assertTrue(FaceStickerAnchorMapper.hasEnoughLandmarks(468))
        assertTrue(FaceStickerAnchorMapper.hasEnoughLandmarks(478))
        assertFalse(FaceStickerAnchorMapper.hasEnoughLandmarks(467))
        assertFalse(FaceStickerAnchorMapper.hasEnoughLandmarks(469))
        assertFalse(FaceStickerAnchorMapper.hasEnoughLandmarks(0))
    }

    // ------------------------------------------------------------ 锚点取值

    @Test
    fun anchorFor_picksFixedOrderIndex() {
        val anchors = FaceStickerAnchorMapper.mapAnchors(faceLandmarks())
        assertEquals(anchors[0], FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.FOREHEAD, anchors))
        assertEquals(anchors[1], FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.NOSE, anchors))
        assertEquals(anchors[2], FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.MOUTH, anchors))
        assertEquals(anchors[3], FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.LEFT_EYE, anchors))
        assertEquals(anchors[4], FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.RIGHT_EYE, anchors))
    }

    @Test
    fun anchorFor_emptyListReturnsNone() {
        assertEquals(StickerAnchor.NONE, FaceStickerAnchorMapper.anchorFor(StickerAnchorMode.MOUTH, emptyList()))
        assertFalse(StickerAnchor.NONE.isValid)
    }

    // ------------------------------------------------------------ 单点变换

    @Test
    fun mapPoint_reportsClamping() {
        val inside = FaceStickerAnchorMapper.mapPoint(LandmarkPoint(0.5f, 0.5f), false, 0, NormalizedCrop.FULL)
        assertFalse(inside.clamped)
        assertEquals(0.5f, inside.point.x, delta)

        val outside = FaceStickerAnchorMapper.mapPoint(LandmarkPoint(-0.2f, 1.4f), false, 0, NormalizedCrop.FULL)
        assertTrue(outside.clamped)
        assertEquals(0f, outside.point.x, delta)
        assertEquals(1f, outside.point.y, delta)
    }

    @Test
    fun mapPoint_mirrorBeforeRotation() {
        // 镜像后绕中心旋转 90°
        val mapped = FaceStickerAnchorMapper.mapPoint(LandmarkPoint(0.2f, 0.5f), true, 90, NormalizedCrop.FULL)
        // 镜像 → (0.8,0.5)，dx=0.3, dy=0 → x = 0.5, y = 0.5 + 0.3 = 0.8
        assertEquals(0.5f, mapped.point.x, delta)
        assertEquals(0.8f, mapped.point.y, delta)
    }

    // ------------------------------------------------------------ 贴位换算

    @Test
    fun placementFor_appliesScaleAndOffsets() {
        val definition = FaceStickerCatalog.find("cat_ears") // widthToFaceRatio 1.35, offsetY -0.22
        val anchor = StickerAnchor(x = 0.5f, y = 0.5f, faceWidth = 0.25f, rollDegrees = 0f, confidence = 1f)

        val base = FaceStickerAnchorMapper.placementFor(anchor, StickerConfig(), definition)
        assertEquals(0.25f * 1.35f, base.widthFraction, delta)
        assertEquals(0.5f, base.centerX, delta)
        assertEquals(0.5f + (-0.22f * 0.25f), base.centerY, delta)
        assertEquals(0f, base.rollDegrees, delta)
        assertEquals(1f, base.alpha, delta)
        assertFalse(base.mirrored)

        // 缩放翻倍
        val doubled = FaceStickerAnchorMapper.placementFor(anchor, StickerConfig(scale = 2f), definition)
        assertEquals(0.25f * 1.35f * 2f, doubled.widthFraction, delta)

        // 偏移以人脸宽度为单位
        val shifted = FaceStickerAnchorMapper.placementFor(anchor, StickerConfig(offsetY = 0.4f), definition)
        assertEquals(0.5f + (0.4f - 0.22f) * 0.25f, shifted.centerY, delta)

        // 镜像与透明度透传
        val styled = FaceStickerAnchorMapper.placementFor(anchor, StickerConfig(mirror = true, alpha = 0.5f), definition)
        assertTrue(styled.mirrored)
        assertEquals(0.5f, styled.alpha, delta)
    }

    @Test
    fun placementFor_clampsCenterAndWidth() {
        val definition = FaceStickerCatalog.find("flower_halo") // widthToFaceRatio 1.5

        // 中心越界 → 压回 0..1
        val lowAnchor = StickerAnchor(x = 0.02f, y = 0.05f, faceWidth = 0.25f, rollDegrees = 0f, confidence = 1f)
        val top = FaceStickerAnchorMapper.placementFor(lowAnchor, StickerConfig(offsetY = -1f), definition)
        assertEquals(0f, top.centerY, delta)

        val highAnchor = StickerAnchor(x = 0.95f, y = 0.95f, faceWidth = 0.25f, rollDegrees = 0f, confidence = 1f)
        val bottom = FaceStickerAnchorMapper.placementFor(highAnchor, StickerConfig(offsetY = 1f), definition)
        assertEquals(1f, bottom.centerY, delta)

        // 宽度上限 2.0 / 下限 0.02
        val bigFace = StickerAnchor(0.5f, 0.5f, 0.5f, 0f, 1f)
        assertEquals(2f, FaceStickerAnchorMapper.placementFor(bigFace, StickerConfig(scale = 4f), definition).widthFraction, delta)
        val tinyFace = StickerAnchor(0.5f, 0.5f, 0.02f, 0f, 1f)
        assertEquals(0.02f, FaceStickerAnchorMapper.placementFor(tinyFace, StickerConfig(scale = 0.2f), definition).widthFraction, delta)

        // 退化人脸宽度（0）不产生负宽
        val zeroFace = StickerAnchor(0.5f, 0.5f, 0f, 0f, 0f)
        assertTrue(FaceStickerAnchorMapper.placementFor(zeroFace, StickerConfig(), definition).widthFraction >= 0f)
    }

    // ---------------------------------------------------------------- 平滑

    @Test
    fun smooth_blendsTowardsIncoming() {
        val previous = StickerAnchor(0f, 0f, 0.2f, 0f, 1f)
        val incoming = StickerAnchor(1f, 1f, 0.4f, 90f, 1f)
        val smoothed = FaceStickerAnchorMapper.smooth(previous, incoming, 0.5f)

        assertEquals(0.5f, smoothed.x, delta)
        assertEquals(0.5f, smoothed.y, delta)
        assertEquals(0.3f, smoothed.faceWidth, delta)
        assertEquals(45f, smoothed.rollDegrees, delta)
        assertEquals(1f, smoothed.confidence, delta)
    }

    @Test
    fun smooth_zeroSmoothingTakesIncoming() {
        val previous = StickerAnchor(0f, 0f, 0.2f, 0f, 1f)
        val incoming = StickerAnchor(1f, 1f, 0.4f, 90f, 1f)
        val smoothed = FaceStickerAnchorMapper.smooth(previous, incoming, 0f)
        assertEquals(1f, smoothed.x, delta)
        assertEquals(90f, smoothed.rollDegrees, delta)
        // 越界平滑系数被 clamp（3f → 0.95，不是 1 保留全部旧值）
        val clamped = FaceStickerAnchorMapper.smooth(previous, incoming, 3f)
        assertEquals(0.05f, clamped.x, delta)
    }

    @Test
    fun smooth_invalidPreviousOrIncomingTakesIncoming() {
        val incoming = StickerAnchor(0.7f, 0.7f, 0.3f, 10f, 1f)
        // 上一帧无效（首次检测）→ 直接采用本帧
        assertEquals(incoming, FaceStickerAnchorMapper.smooth(StickerAnchor.NONE, incoming, 0.9f))
        // 本帧无效（这一帧没检测到脸）→ 返回 NONE，贴纸立即隐藏，不保留陈旧锚点
        assertEquals(StickerAnchor.NONE, FaceStickerAnchorMapper.smooth(incoming, StickerAnchor.NONE, 0.9f))
    }

    @Test
    fun smooth_usesShortestAnglePathAcrossWrap() {
        val previous = StickerAnchor(0.5f, 0.5f, 0.2f, 170f, 1f)
        val incoming = StickerAnchor(0.5f, 0.5f, 0.2f, -170f, 1f)
        // 最短路径为 +20°（而非 -340°）→ 170 + 20*0.5 = 180
        assertEquals(180f, FaceStickerAnchorMapper.smooth(previous, incoming, 0.5f).rollDegrees, delta)
        // 不做平滑时同样走最短路径：170 + 20 = 190（若按朴素差值会得到 -170）
        assertEquals(190f, FaceStickerAnchorMapper.smooth(previous, incoming, 0f).rollDegrees, delta)
    }
}
