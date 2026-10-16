package com.hinnka.mycamera.appgallerypreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览域交互策略测试：视频播放手势(第 41 项)、视频书签(第 38 项)、
 * 快捷操作策略与回调契约(第 42 项)、横竖屏/深色适配(第 43 项配套)。纯 JVM。
 */
class AppGalleryPreviewInteractionTest {

    private val config = AppGalleryPreviewConfig.DEFAULT

    // ---------------- 播放手势（第 41 项） ----------------

    @Test
    fun singleTapTogglesPlayback() {
        val paused = AppGalleryPreviewPlaybackGestureMath.resolveSingleTap(isPlaying = false, config = config)
        assertEquals(AppGalleryPreviewPlaybackGesture.TAP_TOGGLE, paused.gesture)
        assertEquals(true, paused.togglePlaying)

        val playing = AppGalleryPreviewPlaybackGestureMath.resolveSingleTap(isPlaying = true, config = config)
        assertEquals(false, playing.togglePlaying)
    }

    @Test
    fun singleTapRespectsGestureSwitches() {
        val disabled = AppGalleryPreviewPlaybackGestureMath.resolveSingleTap(
            isPlaying = false,
            config = config.copy(playbackGestureEnabled = false),
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.IGNORED, disabled.gesture)
        assertNull(disabled.togglePlaying)

        val tapOff = AppGalleryPreviewPlaybackGestureMath.resolveSingleTap(
            isPlaying = false,
            config = config.copy(singleTapTogglesPlayback = false),
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.IGNORED, tapOff.gesture)
    }

    @Test
    fun doubleTapLeftRewindsAndRightForwards() {
        val backward = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(
            currentMs = 5000L,
            tapXRatio = 0.2f,
            durationMs = 60_000L,
            config = config,
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_BACKWARD, backward.gesture)
        assertEquals(0L, backward.seekTargetMs)
        assertEquals(-5000L, backward.seekDeltaMs)

        val forward = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(
            currentMs = 50_000L,
            tapXRatio = 0.8f,
            durationMs = 60_000L,
            config = config,
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD, forward.gesture)
        assertEquals(60_000L, forward.seekTargetMs)
        assertEquals(10_000L, forward.seekDeltaMs)
    }

    @Test
    fun doubleTapSeeksAreClampedToDuration() {
        val atStart = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(1000L, 0.1f, 60_000L, config)
        assertEquals(0L, atStart.seekTargetMs)

        val unknownDuration = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(1000L, 0.1f, -1L, config)
        assertEquals(0L, unknownDuration.seekTargetMs)
    }

    @Test
    fun doubleTapCenterCountsAsForward() {
        val center = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(10_000L, 0.5f, 60_000L, config)
        assertEquals(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD, center.gesture)
        val nanRatio = AppGalleryPreviewPlaybackGestureMath.resolveDoubleTap(10_000L, Float.NaN, 60_000L, config)
        assertEquals(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD, nanRatio.gesture)
    }

    @Test
    fun horizontalDragMapsDistanceToSeekDelta() {
        val result = AppGalleryPreviewPlaybackGestureMath.resolveHorizontalDrag(
            startMs = 10_000L,
            dxPx = 100f,
            durationMs = 60_000L,
            density = 2f,
            config = config,
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.HORIZONTAL_SEEK, result.gesture)
        assertEquals(16_000L, result.seekTargetMs)
        assertEquals(6000L, result.seekDeltaMs)
    }

    @Test
    fun horizontalDragIsSafeWithBadInput() {
        val noMove = AppGalleryPreviewPlaybackGestureMath.resolveHorizontalDrag(10_000L, 0f, 60_000L, 2f, config)
        assertEquals(AppGalleryPreviewPlaybackGesture.IGNORED, noMove.gesture)

        val huge = AppGalleryPreviewPlaybackGestureMath.resolveHorizontalDrag(10_000L, -1_000_000f, 60_000L, 1f, config)
        assertEquals(0L, huge.seekTargetMs)

        val zeroDensity = AppGalleryPreviewPlaybackGestureMath.resolveHorizontalDrag(10_000L, 120f, 60_000L, 0f, config)
        assertEquals(10_000L + 120L * 120L, zeroDensity.seekTargetMs)
    }

    @Test
    fun verticalDragLeftIsBrightnessRightIsVolume() {
        val brightness = AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(
            startXRatio = 0.2f,
            dyPx = -200f,
            viewportHeightPx = 1000f,
            config = config,
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.VERTICAL_BRIGHTNESS, brightness.gesture)
        assertEquals(0.12f, brightness.brightnessDelta!!, 1e-3f)
        assertNull(brightness.volumeDelta)

        val volume = AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(
            startXRatio = 0.8f,
            dyPx = 500f,
            viewportHeightPx = 1000f,
            config = config,
        )
        assertEquals(AppGalleryPreviewPlaybackGesture.VERTICAL_VOLUME, volume.gesture)
        assertEquals(-0.3f, volume.volumeDelta!!, 1e-3f)
        assertNull(volume.brightnessDelta)
    }

    @Test
    fun verticalDragIsClampedToOne() {
        val result = AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(0.2f, -5000f, 1000f, config)
        assertEquals(1f * 0.6f, result.brightnessDelta!!, 1e-3f)
    }

    @Test
    fun verticalDragRejectsDegenerateInput() {
        assertEquals(
            AppGalleryPreviewPlaybackGesture.IGNORED,
            AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(0.2f, 0f, 1000f, config).gesture,
        )
        assertEquals(
            AppGalleryPreviewPlaybackGesture.IGNORED,
            AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(0.2f, -100f, 0f, config).gesture,
        )
        assertEquals(
            AppGalleryPreviewPlaybackGesture.IGNORED,
            AppGalleryPreviewPlaybackGestureMath.resolveVerticalDrag(0.2f, Float.NaN, 1000f, config).gesture,
        )
    }

    @Test
    fun deltaClampingStaysInUnitRange() {
        assertEquals(1f, AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(0.9f, 0.5f), 1e-6f)
        assertEquals(0f, AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(0.1f, -0.5f), 1e-6f)
        assertEquals(0.2f, AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(Float.NaN, 0.2f), 1e-6f)
        assertEquals(0.5f, AppGalleryPreviewPlaybackGestureMath.applyClampedDelta(0.5f, Float.NaN), 1e-6f)
    }

    @Test
    fun indicatorKeysMatchStringResources() {
        val forward = AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_FORWARD)
        assertEquals("agp_gesture_forward", AppGalleryPreviewPlaybackGestureMath.indicatorKey(forward))
        val back = AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.DOUBLE_TAP_BACKWARD)
        assertEquals("agp_gesture_backward", AppGalleryPreviewPlaybackGestureMath.indicatorKey(back))
        val volume = AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.VERTICAL_VOLUME)
        assertEquals("agp_gesture_volume", AppGalleryPreviewPlaybackGestureMath.indicatorKey(volume))
        val brightness = AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.VERTICAL_BRIGHTNESS)
        assertEquals("agp_gesture_brightness", AppGalleryPreviewPlaybackGestureMath.indicatorKey(brightness))
        val seek = AppGalleryPreviewGestureResult(AppGalleryPreviewPlaybackGesture.HORIZONTAL_SEEK)
        assertEquals("agp_gesture_seek", AppGalleryPreviewPlaybackGestureMath.indicatorKey(seek))
        assertNull(AppGalleryPreviewPlaybackGestureMath.indicatorKey(AppGalleryPreviewGestureResult()))
    }

    // ---------------- 视频书签（第 38 项，播放器层） ----------------

    private val bookmarks = listOf(
        AppGalleryPreviewBookmark(id = "a", positionMs = 3000L, label = "开场"),
        AppGalleryPreviewBookmark(id = "b", positionMs = 1000L),
    )

    @Test
    fun normalizeSortsAndDeduplicates() {
        val normalized = AppGalleryPreviewBookmarkMath.normalize(
            listOf(
                AppGalleryPreviewBookmark("a", 3000L),
                AppGalleryPreviewBookmark("b", 1000L),
                AppGalleryPreviewBookmark("b", 5000L),
                AppGalleryPreviewBookmark("c", -100L),
                AppGalleryPreviewBookmark("  ", 500L),
            ),
            durationMs = 10_000L,
        )
        assertEquals(2, normalized.size)
        assertEquals("b", normalized[0].id)
        assertEquals(1000L, normalized[0].positionMs)
        assertEquals("a", normalized[1].id)
    }

    @Test
    fun normalizeClampsToKnownDuration() {
        val normalized = AppGalleryPreviewBookmarkMath.normalize(
            listOf(AppGalleryPreviewBookmark("a", 99_999L)),
            durationMs = 10_000L,
        )
        assertEquals(10_000L, normalized.single().positionMs)
        assertEquals(99_999L, AppGalleryPreviewBookmarkMath.normalize(listOf(AppGalleryPreviewBookmark("a", 99_999L))).single().positionMs)
    }

    @Test
    fun clampSeekHandlesUnknownDuration() {
        assertEquals(0L, AppGalleryPreviewBookmarkMath.clampSeek(-5L, 1000L))
        assertEquals(1000L, AppGalleryPreviewBookmarkMath.clampSeek(5000L, 1000L))
        assertEquals(500L, AppGalleryPreviewBookmarkMath.clampSeek(500L, -1L))
        assertEquals(0L, AppGalleryPreviewBookmarkMath.clampSeek(-5L, -1L))
    }

    @Test
    fun nearestBookmarkUsesTolerance() {
        assertEquals("a", AppGalleryPreviewBookmarkMath.nearestBookmark(bookmarks, 2950L, 100L)?.id)
        assertNull(AppGalleryPreviewBookmarkMath.nearestBookmark(bookmarks, 2950L, 10L))
        assertNull(AppGalleryPreviewBookmarkMath.nearestBookmark(bookmarks, 5000L, 100L))
        assertNull(AppGalleryPreviewBookmarkMath.nearestBookmark(emptyList(), 1000L, 500L))
        assertNull("容差 0 视为不吸附", AppGalleryPreviewBookmarkMath.nearestBookmark(bookmarks, 1000L, 0L))
    }

    @Test
    fun nextAndPreviousBookmarkWalkTimeline() {
        assertEquals("a", AppGalleryPreviewBookmarkMath.nextBookmark(bookmarks, 1000L)?.id)
        assertEquals("b", AppGalleryPreviewBookmarkMath.nextBookmark(bookmarks, 0L)?.id)
        assertNull(AppGalleryPreviewBookmarkMath.nextBookmark(bookmarks, 3000L))
        assertEquals("a", AppGalleryPreviewBookmarkMath.nextBookmark(bookmarks, 3000L, inclusive = true)?.id)
        assertEquals("b", AppGalleryPreviewBookmarkMath.nextBookmark(bookmarks, 9999L, loop = true)?.id)

        assertEquals("b", AppGalleryPreviewBookmarkMath.previousBookmark(bookmarks, 3000L)?.id)
        assertNull(AppGalleryPreviewBookmarkMath.previousBookmark(bookmarks, 1000L))
        assertEquals("a", AppGalleryPreviewBookmarkMath.previousBookmark(bookmarks, 1000L, loop = true)?.id)
        assertEquals("b", AppGalleryPreviewBookmarkMath.previousBookmark(bookmarks, 1000L, inclusive = true)?.id)
    }

    @Test
    fun bookmarkFractionIsClamped() {
        assertEquals(0.3f, AppGalleryPreviewBookmarkMath.fractionOf(bookmarks[0], 10_000L), 1e-4f)
        assertEquals(0f, AppGalleryPreviewBookmarkMath.fractionOf(bookmarks[0], -1L), 1e-4f)
        assertEquals(0f, AppGalleryPreviewBookmarkMath.fractionOf(bookmarks[0], 0L), 1e-4f)
    }

    @Test
    fun defaultBookmarkSourceIsEmpty() {
        assertTrue(AppGalleryPreviewBookmarkSource.NONE.bookmarksFor("content://m/1.mp4").isEmpty())
    }

    // ---------------- 快捷操作策略（第 42 项） ----------------

    private fun context(
        kind: AppGalleryPreviewMediaKind = AppGalleryPreviewMediaKind.IMAGE,
        isFavorite: Boolean? = false,
        itemCount: Int = 5,
        supportsFavorite: Boolean = true,
        supportsDelete: Boolean = true,
        supportsShare: Boolean = true,
        supportsInfo: Boolean = true,
        supportsEdit: Boolean = true,
        supportsQuickEdit: Boolean = true,
        compareAvailable: Boolean = false,
        supportsRawExport: Boolean = false,
        slideshowEnabled: Boolean = false,
    ) = AppGalleryPreviewActionContext(
        kind = kind,
        isFavorite = isFavorite,
        itemCount = itemCount,
        supportsFavorite = supportsFavorite,
        supportsDelete = supportsDelete,
        supportsShare = supportsShare,
        supportsInfo = supportsInfo,
        supportsEdit = supportsEdit,
        supportsQuickEdit = supportsQuickEdit,
        compareAvailable = compareAvailable,
        supportsRawExport = supportsRawExport,
        slideshowEnabled = slideshowEnabled,
    )

    @Test
    fun actionListHasStableOrderAndSize() {
        val actions = AppGalleryPreviewActionPolicy.actions(context())
        assertEquals(12, actions.size)
        assertEquals(AppGalleryPreviewActionId.FAVORITE, actions[0].id)
        assertEquals(AppGalleryPreviewActionId.DELETE, actions[1].id)
        assertEquals(AppGalleryPreviewActionId.SHARE, actions[2].id)
        assertEquals(AppGalleryPreviewActionId.INFO, actions[3].id)
        assertEquals(AppGalleryPreviewActionId.EDIT, actions[4].id)
        assertEquals(AppGalleryPreviewActionId.QUICK_EDIT, actions[5].id)
        assertEquals(AppGalleryPreviewActionId.SLIDESHOW, actions[6].id)
        assertEquals(AppGalleryPreviewActionId.COMPARE, actions[7].id)
        assertEquals(AppGalleryPreviewActionId.RAW_EXPORT, actions[8].id)
        assertEquals(AppGalleryPreviewActionId.PLAY_PAUSE, actions[9].id)
        assertEquals(AppGalleryPreviewActionId.BOOKMARK, actions[10].id)
        assertEquals(AppGalleryPreviewActionId.SAVE_AS, actions[11].id)
    }

    @Test
    fun deleteRequiresConfirmation() {
        val delete = AppGalleryPreviewActionPolicy.actions(context()).first { it.id == AppGalleryPreviewActionId.DELETE }
        assertTrue(delete.requiresConfirm)
        assertTrue(delete.enabled)
        assertNull(delete.disabledReasonKey)
    }

    @Test
    fun capabilityFlagsControlFavoriteDeleteShareInfo() {
        val actions = AppGalleryPreviewActionPolicy.actions(
            context(supportsFavorite = false, supportsDelete = false, supportsShare = false, supportsInfo = false),
        )
        fun item(id: AppGalleryPreviewActionId) = actions.first { it.id == id }
        assertFalse(item(AppGalleryPreviewActionId.FAVORITE).enabled)
        assertFalse(item(AppGalleryPreviewActionId.DELETE).enabled)
        assertFalse(item(AppGalleryPreviewActionId.SHARE).enabled)
        assertFalse(item(AppGalleryPreviewActionId.INFO).enabled)
        assertEquals("unsupported", item(AppGalleryPreviewActionId.FAVORITE).disabledReasonKey)
        assertFalse(item(AppGalleryPreviewActionId.DELETE).requiresConfirm.not())
    }

    @Test
    fun slideshowNeedsEnabledFlagAndMultipleItems() {
        fun slideshow(ctx: AppGalleryPreviewActionContext) =
            AppGalleryPreviewActionPolicy.actions(ctx).first { it.id == AppGalleryPreviewActionId.SLIDESHOW }

        assertTrue(slideshow(context(slideshowEnabled = true)).enabled)
        assertFalse(slideshow(context(slideshowEnabled = false)).enabled)
        assertEquals("unsupported", slideshow(context(slideshowEnabled = false)).disabledReasonKey)
        assertEquals("single_item", slideshow(context(slideshowEnabled = true, itemCount = 1)).disabledReasonKey)
        assertEquals(
            "animated_kind",
            slideshow(
                context(
                    kind = AppGalleryPreviewMediaKind.VIDEO,
                    slideshowEnabled = true,
                ),
            ).disabledReasonKey,
        )
    }

    @Test
    fun rawExportAndRawQuickEditInterlock() {
        fun item(ctx: AppGalleryPreviewActionContext, id: AppGalleryPreviewActionId) =
            AppGalleryPreviewActionPolicy.actions(ctx).first { it.id == id }

        val imageCtx = context()
        assertFalse(item(imageCtx, AppGalleryPreviewActionId.RAW_EXPORT).enabled)
        assertEquals("not_raw", item(imageCtx, AppGalleryPreviewActionId.RAW_EXPORT).disabledReasonKey)

        val rawWithoutExport = context(kind = AppGalleryPreviewMediaKind.RAW)
        assertFalse(item(rawWithoutExport, AppGalleryPreviewActionId.RAW_EXPORT).enabled)
        assertEquals(
            "raw_requires_export",
            item(rawWithoutExport, AppGalleryPreviewActionId.QUICK_EDIT).disabledReasonKey,
        )

        val rawWithExport = context(kind = AppGalleryPreviewMediaKind.RAW, supportsRawExport = true)
        assertTrue(item(rawWithExport, AppGalleryPreviewActionId.RAW_EXPORT).enabled)
        assertTrue(item(rawWithExport, AppGalleryPreviewActionId.QUICK_EDIT).enabled)
    }

    @Test
    fun compareFollowsAvailability() {
        fun compare(ctx: AppGalleryPreviewActionContext) =
            AppGalleryPreviewActionPolicy.actions(ctx).first { it.id == AppGalleryPreviewActionId.COMPARE }

        assertFalse(compare(context()).enabled)
        assertEquals("needs_two_versions", compare(context()).disabledReasonKey)
        assertTrue(compare(context(compareAvailable = true)).enabled)
    }

    @Test
    fun playbackActionsFollowMediaKind() {
        fun item(ctx: AppGalleryPreviewActionContext, id: AppGalleryPreviewActionId) =
            AppGalleryPreviewActionPolicy.actions(ctx).first { it.id == id }

        assertTrue(item(context(kind = AppGalleryPreviewMediaKind.VIDEO), AppGalleryPreviewActionId.PLAY_PAUSE).enabled)
        assertTrue(item(context(kind = AppGalleryPreviewMediaKind.GIF), AppGalleryPreviewActionId.PLAY_PAUSE).enabled)
        assertEquals(
            "not_animated",
            item(context(kind = AppGalleryPreviewMediaKind.IMAGE), AppGalleryPreviewActionId.PLAY_PAUSE).disabledReasonKey,
        )
        assertTrue(item(context(kind = AppGalleryPreviewMediaKind.VIDEO), AppGalleryPreviewActionId.BOOKMARK).enabled)
        assertEquals(
            "not_video",
            item(context(kind = AppGalleryPreviewMediaKind.IMAGE), AppGalleryPreviewActionId.BOOKMARK).disabledReasonKey,
        )
    }

    @Test
    fun enabledActionsFiltersDisabledOnes() {
        val enabled = AppGalleryPreviewActionPolicy.enabledActions(context())
        assertFalse(enabled.any { it.id == AppGalleryPreviewActionId.COMPARE })
        assertFalse(enabled.any { it.id == AppGalleryPreviewActionId.SLIDESHOW })
        assertTrue(enabled.any { it.id == AppGalleryPreviewActionId.EDIT })
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun disabledActionsHideEditEntries() {
        val actions = AppGalleryPreviewActionPolicy.actions(context(supportsEdit = false, supportsQuickEdit = false))
        assertFalse(actions.first { it.id == AppGalleryPreviewActionId.EDIT }.enabled)
        assertFalse(actions.first { it.id == AppGalleryPreviewActionId.QUICK_EDIT }.enabled)
    }

    @Test
    fun favoriteToggleOnlyWhenStateKnown() {
        assertEquals(true, AppGalleryPreviewActionPolicy.nextFavoriteState(context(isFavorite = false)))
        assertEquals(false, AppGalleryPreviewActionPolicy.nextFavoriteState(context(isFavorite = true)))
        assertNull("收藏状态未知时不猜测", AppGalleryPreviewActionPolicy.nextFavoriteState(context(isFavorite = null)))
        assertNull(
            "管理域不支持收藏时不切换",
            AppGalleryPreviewActionPolicy.nextFavoriteState(context(isFavorite = false, supportsFavorite = false)),
        )
    }

    // ---------------- 横竖屏 / 深色适配 ----------------

    @Test
    fun portraitPhoneUsesBottomChrome() {
        val plan = AppGalleryPreviewLayoutPolicy.resolve(411f, 891f, systemDark = false, config = config)
        assertEquals(AppGalleryPreviewChromePlacement.BOTTOM, plan.chromePlacement)
        assertFalse(plan.isLandscape)
        assertFalse(plan.isWide)
        assertEquals(0f, plan.contentPaddingDp, 1e-4f)
        assertEquals(AppGalleryPreviewLayoutPolicy.LIGHT_CANVAS_ARGB, plan.canvasColorArgb)
        assertFalse(plan.darkCanvas)
    }

    @Test
    fun landscapePhoneUsesSideChromeWithPadding() {
        val plan = AppGalleryPreviewLayoutPolicy.resolve(891f, 411f, systemDark = false, config = config)
        assertEquals(AppGalleryPreviewChromePlacement.SIDE, plan.chromePlacement)
        assertTrue(plan.isLandscape)
        assertTrue(plan.isWide)
        assertTrue(plan.contentPaddingDp > 0f)
    }

    @Test
    fun tabletPortraitCountsAsWide() {
        val plan = AppGalleryPreviewLayoutPolicy.resolve(600f, 900f, systemDark = false, config = config)
        assertFalse(plan.isLandscape)
        assertTrue(plan.isWide)
        assertEquals(AppGalleryPreviewChromePlacement.SIDE, plan.chromePlacement)
    }

    @Test
    fun sideToolbarCanBeDisabled() {
        val plan = AppGalleryPreviewLayoutPolicy.resolve(
            891f,
            411f,
            systemDark = false,
            config = config.copy(wideLayoutToolbarOnSide = false),
        )
        assertEquals(AppGalleryPreviewChromePlacement.BOTTOM, plan.chromePlacement)
    }

    @Test
    fun darkModeFollowsSystemWhenConfigured() {
        val dark = AppGalleryPreviewLayoutPolicy.resolve(411f, 891f, systemDark = true, config = config)
        assertTrue(dark.darkCanvas)
        assertEquals(config.darkCanvasColorArgb, dark.canvasColorArgb)

        val lightOnly = AppGalleryPreviewLayoutPolicy.resolve(
            411f,
            891f,
            systemDark = true,
            config = config.copy(followSystemDarkTheme = false),
        )
        assertFalse(lightOnly.darkCanvas)
        assertEquals(AppGalleryPreviewLayoutPolicy.LIGHT_CANVAS_ARGB, lightOnly.canvasColorArgb)
    }

    @Test
    fun layoutCarriesConfiguredFlags() {
        val plan = AppGalleryPreviewLayoutPolicy.resolve(
            411f,
            891f,
            systemDark = false,
            config = config.copy(
                immersiveChrome = false,
                showMediaCounter = false,
                chromeAutoHideMs = 1500L,
                orientationLock = PreviewOrientationLock.PORTRAIT,
            ),
        )
        assertFalse(plan.immersive)
        assertFalse(plan.showMediaCounter)
        assertEquals(1500L, plan.chromeAutoHideMs)
        assertEquals(PreviewOrientationLock.PORTRAIT, plan.orientationLock)
    }

    @Test
    fun orientationAllowanceFollowsLock() {
        assertTrue(AppGalleryPreviewLayoutPolicy.orientationAllows(PreviewOrientationLock.AUTO, isPortraitNow = true))
        assertTrue(AppGalleryPreviewLayoutPolicy.orientationAllows(PreviewOrientationLock.AUTO, isPortraitNow = false))
        assertTrue(AppGalleryPreviewLayoutPolicy.orientationAllows(PreviewOrientationLock.PORTRAIT, true))
        assertFalse(AppGalleryPreviewLayoutPolicy.orientationAllows(PreviewOrientationLock.PORTRAIT, false))
        assertTrue(AppGalleryPreviewLayoutPolicy.orientationAllows(PreviewOrientationLock.LANDSCAPE, false))
    }

    @Test
    fun degenerateLayoutInputsAreSafe() {
        assertFalse(AppGalleryPreviewLayoutPolicy.isWide(Float.NaN, 900f))
        assertFalse(AppGalleryPreviewLayoutPolicy.isLandscape(Float.NaN, 900f))
        assertFalse(AppGalleryPreviewLayoutPolicy.isLandscape(100f, 100f))
        val plan = AppGalleryPreviewLayoutPolicy.resolve(0f, 0f, systemDark = false, config = config)
        assertNotNull(plan)
        assertFalse(plan.isLandscape)
    }
}
