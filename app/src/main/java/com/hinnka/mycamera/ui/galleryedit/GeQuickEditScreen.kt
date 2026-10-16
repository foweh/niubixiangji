package com.hinnka.mycamera.ui.galleryedit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hinnka.mycamera.R
import com.hinnka.mycamera.galleryedit.GeCropConfig
import com.hinnka.mycamera.galleryedit.GeMediaType
import com.hinnka.mycamera.ui.icons.AppIcons
import kotlinx.coroutines.delay

/** 编辑面板分页。 */
private enum class GeEditTab { ADJUST, CROP, WATERMARK, VIDEO, COMPARE, SHARE }

/** 面板提示的自动消隐时长（毫秒）。 */
private const val MESSAGE_AUTO_DISMISS_MS = 2_500L

/**
 * 画廊快速编辑主面板（对外唯一的 Compose 入口）。
 *
 * 调用方：
 * - `GeEditEntryActivity`（跨代理 `EDIT_MEDIA` intent 协议接收端）
 * - 独立相册 V4（通过该 intent 调起，不直接依赖本模块符号）
 *
 * 所有用户可见文案走 `stringResource`，资源 key 见交付补丁的 STRINGS 段。
 */
@Composable
fun GeQuickEditScreen(
    mediaUri: String,
    mediaType: GeMediaType,
    onClose: () -> Unit,
    viewModel: GeGalleryEditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(GeEditTab.ADJUST) }

    // 需要在下述非 composable 回调里使用的文案，一律提前求值（避免在 lambda 中调用 stringResource）
    val titleText = stringResource(R.string.ge_edit_title)
    val closeText = stringResource(R.string.ge_close)
    val shareText = stringResource(R.string.ge_share)
    val chooserTitle = stringResource(R.string.ge_share_chooser_title)
    val beforeLabel = stringResource(R.string.ge_compare_before)
    val afterLabel = stringResource(R.string.ge_compare_after)

    LaunchedEffect(mediaUri, mediaType) {
        viewModel.open(mediaUri, mediaType)
    }
    LaunchedEffect(state.isVideo) {
        if (state.isVideo) tab = GeEditTab.VIDEO
    }
    // 提示自动消隐：避免「已保存到相册」等状态长期占据画面。
    LaunchedEffect(state.message) {
        if (state.message != GeEditMessage.NONE) {
            delay(MESSAGE_AUTO_DISMISS_MS)
            viewModel.consumeMessage()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GeTopBar(
                title = titleText,
                closeText = closeText,
                shareText = shareText,
                onClose = onClose,
                onShare = { viewModel.share(chooserTitle) },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val before = state.previewBefore
                val after = state.previewAfter
                when {
                    state.showCompare && before != null && after != null -> GeBeforeAfterSlider(
                        before = before,
                        after = after,
                        config = state.compare,
                        onSplitChange = viewModel::setCompareSplit,
                        beforeLabel = beforeLabel,
                        afterLabel = afterLabel,
                        modifier = Modifier.fillMaxSize(),
                    )

                    after != null -> Image(
                        bitmap = after.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    state.loading -> Text(
                        text = stringResource(R.string.ge_loading),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )

                    state.isVideo -> Text(
                        text = stringResource(R.string.ge_video_preview_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (state.exporting) {
                Text(
                    text = stringResource(R.string.ge_export_progress, state.exportProgress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            val messageText = geMessageText(state.message)
            if (messageText != null) {
                Text(
                    text = messageText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    GeEditTab.ADJUST -> GeAdjustPanel(
                        brightness = state.image.pixel.brightness,
                        contrast = state.image.pixel.contrast,
                        saturation = state.image.pixel.saturation,
                        temperature = state.image.pixel.temperature,
                        onBrightnessChange = viewModel::setBrightness,
                        onContrastChange = viewModel::setContrast,
                        onSaturationChange = viewModel::setSaturation,
                        onTemperatureChange = viewModel::setTemperature,
                        labelBrightness = stringResource(R.string.ge_adj_brightness),
                        labelContrast = stringResource(R.string.ge_adj_contrast),
                        labelSaturation = stringResource(R.string.ge_adj_saturation),
                        labelTemperature = stringResource(R.string.ge_adj_temperature),
                    )

                    GeEditTab.CROP -> GeCropPanel(
                        source = state.previewBefore,
                        crop = state.image.crop,
                        ratio = viewModel.currentCropRatio,
                        config = GeCropConfig(),
                        onCropChange = viewModel::setCropRect,
                        onRatioChange = viewModel::setCropRatio,
                        onRotate = viewModel::rotateBy,
                        labelFree = stringResource(R.string.ge_crop_free),
                        labelOriginal = stringResource(R.string.ge_crop_original),
                        labelSquare = stringResource(R.string.ge_crop_square),
                        label3x4 = stringResource(R.string.ge_crop_3_4),
                        label9x16 = stringResource(R.string.ge_crop_9_16),
                        labelRotateLeft = stringResource(R.string.ge_crop_rotate_left),
                        labelRotateRight = stringResource(R.string.ge_crop_rotate_right),
                        hint = stringResource(R.string.ge_crop_hint),
                    )

                    GeEditTab.WATERMARK -> GeWatermarkPanel(
                        watermark = state.image.watermark,
                        dateStamp = state.image.dateStamp,
                        onWatermarkChange = { config ->
                            viewModel.setWatermarkEnabled(config.enabled)
                            viewModel.setWatermarkText(config.text)
                            viewModel.setWatermarkFontSize(config.fontSizeSp)
                            viewModel.setWatermarkOpacity(config.opacity)
                            viewModel.setWatermarkPlacement(config.placement)
                            viewModel.setWatermarkRotation(config.rotationDegrees)
                            viewModel.setWatermarkTileSpacing(config.tileSpacingFactor)
                        },
                        onDateStampChange = { config ->
                            viewModel.setDateStampEnabled(config.enabled)
                            viewModel.setDateStampPattern(config.pattern)
                            viewModel.setDateStampPlacement(config.placement)
                            viewModel.setDateStampFontSize(config.fontSizeSp)
                        },
                        labels = GeWatermarkLabels(
                            enableWatermark = stringResource(R.string.ge_wm_enable),
                            watermarkText = stringResource(R.string.ge_wm_text),
                            fontSize = stringResource(R.string.ge_wm_font),
                            opacity = stringResource(R.string.ge_wm_opacity),
                            placement = stringResource(R.string.ge_wm_placement),
                            placements = gePlacementLabels(),
                            rotation = stringResource(R.string.ge_wm_rotation),
                            tileSpacing = stringResource(R.string.ge_wm_spacing),
                            dateStampSection = stringResource(R.string.ge_wm_section_date),
                            enableDateStamp = stringResource(R.string.ge_date_enable),
                            datePattern = stringResource(R.string.ge_date_pattern),
                            useExifTime = stringResource(R.string.ge_date_use_exif),
                            permanentHint = stringResource(R.string.ge_stamp_permanent_hint),
                        ),
                    )

                    GeEditTab.VIDEO -> GeVideoEditPanel(
                        spec = state.video,
                        durationMs = state.sourceDurationMs,
                        sourceDurationMsOfCurrentSegment = state.video.segments
                            .firstOrNull()?.sourceDurationMs ?: state.sourceDurationMs,
                        onTrimChange = viewModel::setVideoTrim,
                        onSpeedChange = viewModel::setVideoSpeed,
                        onRotationChange = viewModel::setVideoRotation,
                        onKeepAudioChange = viewModel::setVideoKeepAudio,
                        onApplyColorChange = viewModel::setVideoApplyColor,
                        onAddBookmark = { viewModel.addVideoBookmark(0L) },
                        onRemoveBookmark = { timeMs ->
                            viewModel.setVideoBookmarks(
                                state.video.bookmarks.filterNot { it.timeMs == timeMs },
                            )
                        },
                        onRemoveSegment = viewModel::removeVideoSegment,
                        labels = GeVideoLabels(
                            trimSection = stringResource(R.string.ge_video_trim_section),
                            trimStart = stringResource(R.string.ge_video_trim_start),
                            trimEnd = stringResource(R.string.ge_video_trim_end),
                            trimKept = stringResource(R.string.ge_video_trim_kept),
                            durationUnknown = stringResource(R.string.ge_video_duration_unknown),
                            speedSection = stringResource(R.string.ge_video_speed_section),
                            speed = stringResource(R.string.ge_video_speed),
                            outputDuration = stringResource(R.string.ge_video_output_duration),
                            rotationSection = stringResource(R.string.ge_video_rotation_section),
                            keepAudio = stringResource(R.string.ge_video_keep_audio),
                            applyColor = stringResource(R.string.ge_video_apply_color),
                            bookmarkSection = stringResource(R.string.ge_video_bookmark_section),
                            addBookmark = stringResource(R.string.ge_video_add_bookmark),
                            noBookmark = stringResource(R.string.ge_video_no_bookmark),
                            segmentSection = stringResource(R.string.ge_video_segment_section),
                            concatHint = stringResource(R.string.ge_video_concat_hint),
                            remove = stringResource(R.string.ge_remove),
                        ),
                    )

                    GeEditTab.COMPARE -> GeComparePanel(
                        compare = state.compare,
                        showCompare = state.showCompare,
                        onShowCompareChange = viewModel::setShowCompare,
                        onCompareChange = viewModel::setCompareConfig,
                        onExportCompare = { viewModel.exportCompareImage(beforeLabel, afterLabel) },
                        canExport = state.previewBefore != null &&
                            state.previewAfter != null &&
                            !state.exporting,
                        labels = GeCompareLabels(
                            enableCompare = stringResource(R.string.ge_compare_enable),
                            split = stringResource(R.string.ge_compare_split),
                            orientation = stringResource(R.string.ge_compare_orientation),
                            horizontal = stringResource(R.string.ge_compare_horizontal),
                            vertical = stringResource(R.string.ge_compare_vertical),
                            outputMode = stringResource(R.string.ge_compare_output),
                            sideBySide = stringResource(R.string.ge_compare_side_by_side),
                            splitOutput = stringResource(R.string.ge_compare_split_output),
                            exportCompare = stringResource(R.string.ge_compare_export),
                            compareHint = stringResource(R.string.ge_compare_hint),
                        ),
                    )

                    GeEditTab.SHARE -> GeSharePanel(
                        applyEdits = state.shareApplyEdits,
                        onApplyEditsChange = viewModel::setShareApplyEdits,
                        onShare = { viewModel.share(chooserTitle) },
                        onExport = viewModel::exportToGallery,
                        exporting = state.exporting,
                        labels = GeShareLabels(
                            applyEdits = stringResource(R.string.ge_share_apply_edits),
                            applyEditsHint = stringResource(R.string.ge_share_apply_edits_hint),
                            share = shareText,
                            export = stringResource(R.string.ge_export),
                            exporting = stringResource(R.string.ge_exporting),
                        ),
                    )
                }
            }

            GeTabBar(
                current = tab,
                isVideo = state.isVideo,
                onSelect = { selected -> tab = selected },
            )
        }
    }
}

@Composable
private fun GeTopBar(
    title: String,
    closeText: String,
    shareText: String,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = AppIcons.AutoMirroredArrowLeft, contentDescription = closeText)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onShare) {
            Icon(imageVector = AppIcons.Output, contentDescription = shareText)
        }
    }
}

@Composable
private fun GeTabBar(current: GeEditTab, isVideo: Boolean, onSelect: (GeEditTab) -> Unit) {
    val tabs = remember(isVideo) {
        buildList {
            add(GeEditTab.ADJUST)
            add(GeEditTab.CROP)
            add(GeEditTab.WATERMARK)
            if (isVideo) add(GeEditTab.VIDEO)
            add(GeEditTab.COMPARE)
            add(GeEditTab.SHARE)
        }
    }
    // 显式逐个求值，避免在非 inline 的 map/sortedBy 等调用里使用 stringResource
    val adjustLabel = stringResource(R.string.ge_tab_adjust)
    val cropLabel = stringResource(R.string.ge_tab_crop)
    val watermarkLabel = stringResource(R.string.ge_tab_watermark)
    val videoLabel = stringResource(R.string.ge_tab_video)
    val compareLabel = stringResource(R.string.ge_tab_compare)
    val shareLabel = stringResource(R.string.ge_tab_share)
    val labels = if (isVideo) {
        listOf(adjustLabel, cropLabel, watermarkLabel, videoLabel, compareLabel, shareLabel)
    } else {
        listOf(adjustLabel, cropLabel, watermarkLabel, compareLabel, shareLabel)
    }
    GeChipRow(
        items = labels,
        selectedIndex = tabs.indexOf(current),
        onSelect = { index -> tabs.getOrNull(index)?.let(onSelect) },
    )
}

/** 位置标签顺序必须与 [com.hinnka.mycamera.galleryedit.GeWatermarkPlacement] 的 ordinal 一致。 */
@Composable
private fun gePlacementLabels(): List<String> = listOf(
    stringResource(R.string.ge_pos_top_left),
    stringResource(R.string.ge_pos_top_center),
    stringResource(R.string.ge_pos_top_right),
    stringResource(R.string.ge_pos_center_left),
    stringResource(R.string.ge_pos_center),
    stringResource(R.string.ge_pos_center_right),
    stringResource(R.string.ge_pos_bottom_left),
    stringResource(R.string.ge_pos_bottom_center),
    stringResource(R.string.ge_pos_bottom_right),
    stringResource(R.string.ge_pos_tiled),
)

/** 提示代码 → 本地化文案（null 表示无需展示）。 */
@Composable
private fun geMessageText(message: GeEditMessage): String? = when (message) {
    GeEditMessage.NONE -> null
    GeEditMessage.NO_SOURCE_IMAGE -> stringResource(R.string.ge_msg_no_source)
    GeEditMessage.NOTHING_TO_COMPARE -> stringResource(R.string.ge_msg_nothing_to_compare)
    GeEditMessage.EXPORT_DONE -> stringResource(R.string.ge_msg_export_done)
    GeEditMessage.EXPORT_FAILED -> stringResource(R.string.ge_msg_export_failed)
    GeEditMessage.SHARE_UNAVAILABLE -> stringResource(R.string.ge_msg_share_unavailable)
    GeEditMessage.SEGMENT_LIMIT_REACHED -> stringResource(R.string.ge_msg_segment_limit)
    GeEditMessage.VIDEO_SHARE_NEEDS_EXPORT -> stringResource(R.string.ge_msg_video_share_needs_export)
}
