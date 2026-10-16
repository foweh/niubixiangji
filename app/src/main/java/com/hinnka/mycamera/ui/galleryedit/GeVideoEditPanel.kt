package com.hinnka.mycamera.ui.galleryedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.galleryedit.GeBookmarkMath
import com.hinnka.mycamera.galleryedit.GeEditRanges
import com.hinnka.mycamera.galleryedit.GeVideoBookmark
import com.hinnka.mycamera.galleryedit.GeVideoEditSpec
import com.hinnka.mycamera.galleryedit.GeVideoSegment
import com.hinnka.mycamera.galleryedit.GeVideoTrimRange

/**
 * 视频编辑面板：区间裁剪 / 旋转 / 变速 / 去音 / 调色 / 书签 / 多段拼接。
 *
 * 所有操作最终收敛成 [GeVideoEditSpec]，交由 `media3-transformer` 执行（工程已依赖 media3 1.10.0，
 * 无需新增依赖）；重任务走 `BgTaskType.VIDEO_EDIT` 后台任务。
 */
@Composable
fun GeVideoEditPanel(
    spec: GeVideoEditSpec,
    durationMs: Long,
    sourceDurationMsOfCurrentSegment: Long,
    onTrimChange: (Long, Long?) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onRotationChange: (Int) -> Unit,
    onKeepAudioChange: (Boolean) -> Unit,
    onApplyColorChange: (Boolean) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onRemoveSegment: (Int) -> Unit,
    labels: GeVideoLabels,
    modifier: Modifier = Modifier,
) {
    val segment = spec.segments.firstOrNull()
    val trim = segment?.trim ?: GeVideoTrimRange.FULL
    val resolvedEnd = trim.resolvedEndMs(sourceDurationMsOfCurrentSegment)
    val outputDurationMs = GeBookmarkMath.scaledDurationMs(durationMs, spec.speed)

    Column(modifier = modifier.fillMaxWidth()) {
        GeSectionTitle(text = labels.trimSection)
        if (sourceDurationMsOfCurrentSegment > 0L) {
            GeLabeledSlider(
                label = labels.trimStart,
                value = trim.startMs.toFloat(),
                valueRange = 0f..sourceDurationMsOfCurrentSegment.toFloat(),
                onValueChange = { value ->
                    val start = value.toLong().coerceAtLeast(0L)
                    onTrimChange(start, resolvedEnd)
                },
                valueText = formatTime(trim.startMs),
            )
            GeLabeledSlider(
                label = labels.trimEnd,
                value = resolvedEnd.toFloat(),
                valueRange = 0f..sourceDurationMsOfCurrentSegment.toFloat(),
                onValueChange = { value ->
                    onTrimChange(trim.startMs, value.toLong())
                },
                valueText = formatTime(resolvedEnd),
            )
            Text(
                text = labels.trimKept + ": " + formatTime(resolvedEnd - trim.startMs),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            Text(
                text = labels.durationUnknown,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        GeSectionTitle(text = labels.speedSection)
        GeLabeledSlider(
            label = labels.speed,
            value = spec.speed,
            valueRange = GeEditRanges.VIDEO_SPEED_MIN..GeEditRanges.VIDEO_SPEED_MAX,
            onValueChange = onSpeedChange,
            valueText = "x" + String.format(java.util.Locale.US, "%.2f", spec.speed),
        )
        Text(
            text = labels.outputDuration + ": " + formatTime(outputDurationMs),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        GeSectionTitle(text = labels.rotationSection)
        GeChipRow(
            items = GeEditRanges.VIDEO_ROTATION_ALLOWED.map { "$it°" },
            selectedIndex = GeEditRanges.VIDEO_ROTATION_ALLOWED.indexOf(spec.rotationDegrees),
            onSelect = { index ->
                GeEditRanges.VIDEO_ROTATION_ALLOWED.getOrNull(index)?.let(onRotationChange)
            },
        )

        GeSwitchRow(
            title = labels.keepAudio,
            checked = spec.keepAudio,
            onCheckedChange = onKeepAudioChange,
        )
        GeSwitchRow(
            title = labels.applyColor,
            checked = spec.applyColorPipeline,
            onCheckedChange = onApplyColorChange,
        )

        GeSectionTitle(text = labels.bookmarkSection)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAddBookmark) { Text(text = labels.addBookmark) }
            if (spec.bookmarks.isEmpty()) {
                Text(
                    text = labels.noBookmark,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        spec.bookmarks.forEach { bookmark ->
            GeBookmarkRow(
                bookmark = bookmark,
                label = formatTime(bookmark.timeMs),
                removeText = labels.remove,
                onRemove = { onRemoveBookmark(bookmark.timeMs) },
            )
        }

        GeSectionTitle(text = labels.segmentSection)
        spec.segments.forEachIndexed { index, item ->
            GeSegmentRow(
                index = index,
                segment = item,
                label = formatTime(item.sourceDurationMs),
                removeText = labels.remove,
                removable = spec.segments.size > 1,
                onRemove = { onRemoveSegment(index) },
            )
        }
        Text(
            text = labels.concatHint,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun GeBookmarkRow(
    bookmark: GeVideoBookmark,
    label: String,
    removeText: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        Text(
            text = bookmark.label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
        TextButton(onClick = onRemove) { Text(text = removeText) }
    }
}

@Composable
private fun GeSegmentRow(
    index: Int,
    segment: GeVideoSegment,
    label: String,
    removeText: String,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#" + (index + 1),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        if (removable) {
            TextButton(onClick = onRemove) { Text(text = removeText) }
        }
    }
}

/** 视频面板文案集合（由 Screen 注入，避免硬编码）。 */
data class GeVideoLabels(
    val trimSection: String,
    val trimStart: String,
    val trimEnd: String,
    val trimKept: String,
    val durationUnknown: String,
    val speedSection: String,
    val speed: String,
    val outputDuration: String,
    val rotationSection: String,
    val keepAudio: String,
    val applyColor: String,
    val bookmarkSection: String,
    val addBookmark: String,
    val noBookmark: String,
    val segmentSection: String,
    val concatHint: String,
    val remove: String,
)

/** 毫秒 → mm:ss（超过 1 小时用 h:mm:ss）。 */
internal fun formatTime(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
