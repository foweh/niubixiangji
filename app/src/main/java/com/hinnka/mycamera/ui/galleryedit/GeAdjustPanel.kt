package com.hinnka.mycamera.ui.galleryedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.galleryedit.GeEditRanges
import kotlin.math.roundToInt

/**
 * 亮度 / 对比度 / 饱和度 / 色温 四个滑杆面板。
 *
 * 全部走 [GeEditRanges.ADJUST_MIN]..[ADJUST_MAX]（-100..100），越界由纯逻辑层 clamp。
 */
@Composable
fun GeAdjustPanel(
    brightness: Int,
    contrast: Int,
    saturation: Int,
    temperature: Int,
    onBrightnessChange: (Int) -> Unit,
    onContrastChange: (Int) -> Unit,
    onSaturationChange: (Int) -> Unit,
    onTemperatureChange: (Int) -> Unit,
    labelBrightness: String,
    labelContrast: String,
    labelSaturation: String,
    labelTemperature: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GeAdjustSlider(
            label = labelBrightness,
            value = brightness,
            onChange = onBrightnessChange,
        )
        GeAdjustSlider(
            label = labelContrast,
            value = contrast,
            onChange = onContrastChange,
        )
        GeAdjustSlider(
            label = labelSaturation,
            value = saturation,
            onChange = onSaturationChange,
        )
        GeAdjustSlider(
            label = labelTemperature,
            value = temperature,
            onChange = onTemperatureChange,
        )
    }
}

/** 单个 -100..100 滑杆（含数值显示，便于精确复现参数）。 */
@Composable
fun GeAdjustSlider(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                onChange(raw.roundToInt().coerceIn(GeEditRanges.ADJUST_MIN, GeEditRanges.ADJUST_MAX))
            },
            valueRange = GeEditRanges.ADJUST_MIN.toFloat()..GeEditRanges.ADJUST_MAX.toFloat(),
        )
    }
}
