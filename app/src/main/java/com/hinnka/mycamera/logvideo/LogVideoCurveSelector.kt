package com.hinnka.mycamera.logvideo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R

/**
 * Log 视频参数面板（Log 视频 20 项 · 第 1/2/4 项的 UI 接线点）。
 *
 * 设计（与工程既有 `VideoLogLutModeSelector` 同一视觉语言：白字 + 半透明叠色，12sp/11sp）：
 *  - 两行档位选择：主推 LogC3 / V-Log / HLG，扩展 LogC4 / F-Log2 / Apple Log 2 / Log3G10 / L-Log；
 *  - 三条曲线微调滑杆（黑电平 / 峰值 / 斜率）+ LUT 强度滑杆，**默认值 = 档位原生**，
 *    并显示提示「默认值等同档位原生曲线（无改动）」；
 *  - 还原 LUT 尺寸（17/33/65）+ 「一键烘焙 Log→Rec.709 LUT」按钮（第 4 项新手入口）。
 *
 * 无状态（配置由 [LogVideoConfigStore.flow] 驱动、经 `onConfigChange` 回写），
 * 便于直接接入现有 `CameraScreen` 的 Log 分区（patch 的 UI 段给出锚点）。
 */
@Composable
internal fun LogVideoCurveSelector(
    config: LogVideoConfig,
    enabled: Boolean,
    baking: Boolean = false,
    bakedLutId: String? = null,
    packCount: Int = LogLutPackCatalog.packs.size,
    onConfigChange: (LogVideoConfig) -> Unit = {},
    onBakeRestoreLut: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        Text(
            stringResource(R.string.logvideo_section_title),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        PresetRow(
            title = stringResource(R.string.logvideo_curve_row1),
            presets = MAIN_PRESETS,
            selected = config.curve.preset,
            enabled = enabled,
            onSelected = { preset -> onConfigChange(config.withPreset(preset)) },
        )
        PresetRow(
            title = stringResource(R.string.logvideo_curve_row2),
            presets = EXTENDED_PRESETS,
            selected = config.curve.preset,
            enabled = enabled,
            onSelected = { preset -> onConfigChange(config.withPreset(preset)) },
        )

        ParameterSlider(
            label = stringResource(R.string.logvideo_black_level),
            value = config.curve.blackLevel,
            range = LogCurveRecipe.BLACK_LEVEL_MIN..LogCurveRecipe.BLACK_LEVEL_MAX,
            enabled = enabled,
            onValue = { value -> onConfigChange(config.withBlackLevel(value)) },
        )
        ParameterSlider(
            label = stringResource(R.string.logvideo_peak),
            value = config.curve.peak,
            range = LogCurveRecipe.PEAK_MIN..LogCurveRecipe.PEAK_MAX,
            enabled = enabled,
            onValue = { value -> onConfigChange(config.withPeak(value)) },
        )
        ParameterSlider(
            label = stringResource(R.string.logvideo_slope),
            value = config.curve.slope,
            range = LogCurveRecipe.SLOPE_MIN..LogCurveRecipe.SLOPE_MAX,
            enabled = enabled,
            onValue = { value -> onConfigChange(config.withSlope(value)) },
        )
        ParameterSlider(
            label = stringResource(R.string.logvideo_lut_intensity),
            value = config.lutIntensity,
            range = LogVideoConfig.LUT_INTENSITY_MIN..LogVideoConfig.LUT_INTENSITY_MAX,
            enabled = enabled,
            onValue = { value -> onConfigChange(config.clamped().copy(lutIntensity = value).clamped()) },
        )

        Text(
            stringResource(R.string.logvideo_trim_hint),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            LUT_SIZES.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = config.bakedRestoreLutSize == size,
                    onClick = {
                        onConfigChange(
                            config.clamped().copy(bakedRestoreLutSize = size).clamped(),
                        )
                    },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, LUT_SIZES.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color.White.copy(alpha = 0.25f),
                        activeContentColor = Color.White,
                        inactiveContainerColor = Color.Black.copy(alpha = 0.2f),
                        inactiveContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                ) {
                    Text("$size³", fontSize = 11.sp)
                }
            }
        }
        Text(
            stringResource(R.string.logvideo_lut_size),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onBakeRestoreLut,
                enabled = enabled && !baking,
            ) {
                Text(
                    stringResource(
                        if (baking) R.string.logvideo_bake_running else R.string.logvideo_bake_action,
                    ),
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = { onConfigChange(LogVideoConfig.DEFAULT.copy(enabled = config.enabled)) },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.logvideo_reset), fontSize = 11.sp)
            }
        }

        if (bakedLutId != null) {
            Text(
                stringResource(R.string.logvideo_bake_done, bakedLutId),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )
        } else {
            Text(
                stringResource(R.string.logvideo_baked_restore_hint),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
        Text(
            stringResource(R.string.logvideo_pack_hint, packCount),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PresetRow(
    title: String,
    presets: List<LogCurvePreset>,
    selected: LogCurvePreset,
    enabled: Boolean,
    onSelected: (LogCurvePreset) -> Unit,
) {
    Text(
        title,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = preset == selected,
                onClick = { onSelected(preset) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color.White.copy(alpha = 0.25f),
                    activeContentColor = Color.White,
                    inactiveContainerColor = Color.Black.copy(alpha = 0.2f),
                    inactiveContentColor = Color.White.copy(alpha = 0.7f),
                ),
            ) {
                Text(preset.shortLabel(), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValue: (Float) -> Unit,
) {
    Text(
        "$label  ${"%.2f".format(value)}",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        valueRange = range,
        enabled = enabled,
        onValueChange = { raw -> onValue(raw.coerceIn(range.start, range.endInclusive)) },
    )
}

/** 面板专用短标签（分段按钮宽度有限，不占用多语言资源）。 */
private fun LogCurvePreset.shortLabel(): String = when (this) {
    LogCurvePreset.LOG_C3 -> "LogC3"
    LogCurvePreset.V_LOG -> "V-Log"
    LogCurvePreset.HLG -> "HLG"
    LogCurvePreset.LOGC4 -> "LogC4"
    LogCurvePreset.FLOG2 -> "F-Log2"
    LogCurvePreset.APPLE_LOG2 -> "Apple"
    LogCurvePreset.LOG3G10 -> "3G10"
    LogCurvePreset.LLOG -> "L-Log"
}

/** 主推三档（用户需求明确指定的三档）。 */
internal val MAIN_PRESETS: List<LogCurvePreset> = listOf(
    LogCurvePreset.LOG_C3,
    LogCurvePreset.V_LOG,
    LogCurvePreset.HLG,
)

/** 扩展档位（复用工程 Log 还原组，与既有 Log 档位一一对应）。 */
internal val EXTENDED_PRESETS: List<LogCurvePreset> = listOf(
    LogCurvePreset.LOGC4,
    LogCurvePreset.FLOG2,
    LogCurvePreset.APPLE_LOG2,
    LogCurvePreset.LOG3G10,
    LogCurvePreset.LLOG,
)

/** 还原 LUT 尺寸选项（与工程内置 Log 还原组同规格 33 为默认）。 */
internal val LUT_SIZES: List<Int> = listOf(17, 33, 65)

/** 切档位：曲线微调保持（同一套参数语义），并同步默认还原 LUT id。 */
private fun LogVideoConfig.withPreset(preset: LogCurvePreset): LogVideoConfig {
    val next = clamped().copy(curve = curve.clamped().copy(preset = preset))
    val keepExplicitLut = restoreLutId.isNotBlank() &&
        restoreLutId != LogVideoConfig.defaultRestoreLutId(curve.preset) &&
        !restoreLutId.startsWith(LogLutPackCatalog.BUILT_IN_BAKED_ID)
    return next.copy(
        restoreLutId = if (keepExplicitLut) restoreLutId
        else LogVideoConfig.defaultRestoreLutId(preset),
    )
}

private fun LogVideoConfig.withBlackLevel(value: Float): LogVideoConfig =
    clamped().copy(curve = curve.clamped().copy(blackLevel = value)).clamped()

private fun LogVideoConfig.withPeak(value: Float): LogVideoConfig =
    clamped().copy(curve = curve.clamped().copy(peak = value)).clamped()

private fun LogVideoConfig.withSlope(value: Float): LogVideoConfig =
    clamped().copy(curve = curve.clamped().copy(slope = value)).clamped()
