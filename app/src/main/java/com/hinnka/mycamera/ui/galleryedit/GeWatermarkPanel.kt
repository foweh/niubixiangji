package com.hinnka.mycamera.ui.galleryedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.galleryedit.GeDateStampConfig
import com.hinnka.mycamera.galleryedit.GeDateStampFormat
import com.hinnka.mycamera.galleryedit.GeEditRanges
import com.hinnka.mycamera.galleryedit.GeWatermarkConfig
import com.hinnka.mycamera.galleryedit.GeWatermarkPlacement

/**
 * 水印 / 日期戳面板。
 *
 * 排版与绘制均为自研（参考 zhensherlock/watermark-js-plus 585★ MIT 的九宫格 + 平铺 + 旋转语义；
 * 该库是浏览器端 JS，Android 侧无同等 star 的现成实现）。所有文案由调用方通过 [labels] 注入，
 * 面板内不硬编码任何用户可见文字。
 */
@Composable
fun GeWatermarkPanel(
    watermark: GeWatermarkConfig,
    dateStamp: GeDateStampConfig,
    onWatermarkChange: (GeWatermarkConfig) -> Unit,
    onDateStampChange: (GeDateStampConfig) -> Unit,
    labels: GeWatermarkLabels,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GeSwitchRow(
            title = labels.enableWatermark,
            checked = watermark.enabled,
            onCheckedChange = { onWatermarkChange(watermark.copy(enabled = it)) },
        )
        OutlinedTextField(
            value = watermark.text,
            onValueChange = { onWatermarkChange(watermark.copy(text = it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            label = { Text(text = labels.watermarkText) },
            singleLine = true,
        )
        GeLabeledSlider(
            label = labels.fontSize,
            value = watermark.fontSizeSp,
            valueRange = GeEditRanges.WATERMARK_FONT_MIN..GeEditRanges.WATERMARK_FONT_MAX,
            onValueChange = { onWatermarkChange(watermark.copy(fontSizeSp = it)) },
            valueText = watermark.fontSizeSp.toInt().toString(),
        )
        GeLabeledSlider(
            label = labels.opacity,
            value = watermark.opacity,
            valueRange = GeEditRanges.OPACITY_MIN..GeEditRanges.OPACITY_MAX,
            onValueChange = { onWatermarkChange(watermark.copy(opacity = it)) },
            valueText = formatPercent(watermark.opacity),
        )
        GeSectionTitle(text = labels.placement)
        GeChipRow(
            items = labels.placements,
            selectedIndex = watermark.placement.ordinal.coerceIn(0, labels.placements.lastIndex),
            onSelect = { index ->
                val placement = GeWatermarkPlacement.values().getOrNull(index)
                if (placement != null) onWatermarkChange(watermark.copy(placement = placement))
            },
        )
        if (watermark.isTiled) {
            GeLabeledSlider(
                label = labels.rotation,
                value = watermark.rotationDegrees,
                valueRange = GeEditRanges.WATERMARK_ROTATION_MIN..GeEditRanges.WATERMARK_ROTATION_MAX,
                onValueChange = { onWatermarkChange(watermark.copy(rotationDegrees = it)) },
                valueText = "${watermark.rotationDegrees.toInt()}°",
            )
            GeLabeledSlider(
                label = labels.tileSpacing,
                value = watermark.tileSpacingFactor,
                valueRange = GeEditRanges.TILE_SPACING_MIN..GeEditRanges.TILE_SPACING_MAX,
                onValueChange = { onWatermarkChange(watermark.copy(tileSpacingFactor = it)) },
                valueText = "x" + String.format(java.util.Locale.US, "%.1f", watermark.tileSpacingFactor),
            )
        }

        GeSectionTitle(text = labels.dateStampSection)
        GeSwitchRow(
            title = labels.enableDateStamp,
            checked = dateStamp.enabled,
            onCheckedChange = { onDateStampChange(dateStamp.copy(enabled = it)) },
        )
        OutlinedTextField(
            value = dateStamp.pattern,
            onValueChange = { onDateStampChange(dateStamp.copy(pattern = GeDateStampFormat.normalizePattern(it))) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            label = { Text(text = labels.datePattern) },
            singleLine = true,
        )
        GeChipRow(
            items = GeDateStampFormat.PRESETS,
            selectedIndex = GeDateStampFormat.PRESETS.indexOf(dateStamp.pattern),
            onSelect = { index ->
                GeDateStampFormat.PRESETS.getOrNull(index)?.let { pattern ->
                    onDateStampChange(dateStamp.copy(pattern = pattern))
                }
            },
        )
        GeSwitchRow(
            title = labels.useExifTime,
            checked = dateStamp.useExifTakenTime,
            onCheckedChange = { onDateStampChange(dateStamp.copy(useExifTakenTime = it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labels.permanentHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 面板文案集合（由 Screen 用 stringResource 组装，避免硬编码）。 */
data class GeWatermarkLabels(
    val enableWatermark: String,
    val watermarkText: String,
    val fontSize: String,
    val opacity: String,
    val placement: String,
    val placements: List<String>,
    val rotation: String,
    val tileSpacing: String,
    val dateStampSection: String,
    val enableDateStamp: String,
    val datePattern: String,
    val useExifTime: String,
    val permanentHint: String,
)

private fun formatPercent(value: Float): String =
    String.format(java.util.Locale.US, "%.0f%%", (value.coerceIn(0f, 1f) * 100f))
