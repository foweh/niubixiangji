package com.hinnka.mycamera.ui.galleryedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hinnka.mycamera.galleryedit.GeCompareConfig
import com.hinnka.mycamera.galleryedit.GeCompareOutput
import com.hinnka.mycamera.galleryedit.GeEditRanges
import kotlin.math.roundToInt

/**
 * 前后对比面板：开关预览、分割位置、排列方式、导出对比图。
 */
@Composable
fun GeComparePanel(
    compare: GeCompareConfig,
    showCompare: Boolean,
    onShowCompareChange: (Boolean) -> Unit,
    onCompareChange: (GeCompareConfig) -> Unit,
    onExportCompare: () -> Unit,
    canExport: Boolean,
    labels: GeCompareLabels,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GeSwitchRow(
            title = labels.enableCompare,
            checked = showCompare,
            onCheckedChange = onShowCompareChange,
        )
        GeLabeledSlider(
            label = labels.split,
            value = compare.split,
            valueRange = GeEditRanges.OPACITY_MIN..GeEditRanges.OPACITY_MAX,
            onValueChange = { onCompareChange(compare.copy(split = it)) },
            valueText = "${(compare.split * 100f).roundToInt()}%",
        )
        GeSectionTitle(text = labels.orientation)
        GeChipRow(
            items = listOf(labels.horizontal, labels.vertical),
            selectedIndex = if (compare.orientation) 0 else 1,
            onSelect = { index -> onCompareChange(compare.copy(orientation = index == 0)) },
        )
        GeSectionTitle(text = labels.outputMode)
        GeChipRow(
            items = listOf(labels.sideBySide, labels.splitOutput),
            selectedIndex = compare.output.ordinal.coerceIn(0, 1),
            onSelect = { index ->
                onCompareChange(
                    compare.copy(
                        output = GeCompareOutput.values().getOrNull(index) ?: GeCompareOutput.SIDE_BY_SIDE,
                    ),
                )
            },
        )
        Button(
            onClick = onExportCompare,
            enabled = canExport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = labels.exportCompare)
        }
        Text(
            text = labels.compareHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 分享面板：套用当前编辑开关、系统分享、保存到相册、导出对比图。
 */
@Composable
fun GeSharePanel(
    applyEdits: Boolean,
    onApplyEditsChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    labels: GeShareLabels,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GeSwitchRow(
            title = labels.applyEdits,
            checked = applyEdits,
            onCheckedChange = onApplyEditsChange,
        )
        Text(
            text = labels.applyEditsHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = labels.share)
        }
        Button(
            onClick = onExport,
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = if (exporting) labels.exporting else labels.export)
        }
    }
}

/** 对比面板文案集合。 */
data class GeCompareLabels(
    val enableCompare: String,
    val split: String,
    val orientation: String,
    val horizontal: String,
    val vertical: String,
    val outputMode: String,
    val sideBySide: String,
    val splitOutput: String,
    val exportCompare: String,
    val compareHint: String,
)

/** 分享面板文案集合。 */
data class GeShareLabels(
    val applyEdits: String,
    val applyEditsHint: String,
    val share: String,
    val export: String,
    val exporting: String,
)
