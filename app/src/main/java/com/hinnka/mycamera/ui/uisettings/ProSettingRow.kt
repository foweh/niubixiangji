package com.hinnka.mycamera.ui.uisettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.ui.components.SliderSettingItem
import com.hinnka.mycamera.ui.settings.DropdownSettingItem
import com.hinnka.mycamera.ui.settings.NavigationSettingItem
import com.hinnka.mycamera.ui.settings.SwitchSettingItem
import com.hinnka.mycamera.ui.settings.TextInputSettingItem
import com.hinnka.mycamera.uisettings.ProRange
import com.hinnka.mycamera.uisettings.ProSettingControl
import com.hinnka.mycamera.uisettings.ProSettingSpec
import com.hinnka.mycamera.uisettings.ProSettingsTaxonomy

/**
 * 单条设置项 → 控件。
 *
 * 控件一律复用工程既有组件（`ui.settings.*` / `ui.components.SliderSettingItem`），
 * 保证「Pro 设置」与既有设置页的视觉语言完全一致；本文件只做「定义 → 控件」的映射与文案拼接。
 */

/** 数值控件用到的闭区间（非数值项返回 null）。 */
fun ProSettingSpec.sliderRangeOrNull(): ClosedFloatingPointRange<Float>? = when (val r = range) {
    ProRange.Unbounded -> null
    is ProRange.Continuous -> r.min.toFloat()..r.max.toFloat()
    is ProRange.Stepped -> r.min.toFloat()..r.max.toFloat()
}

/** 密钥类字段（输入框需要遮罩）。 */
val ProSettingSpec.isSecretLike: Boolean
    get() {
        val probe = (id + " " + storageKey + " " + title).lowercase()
        return probe.contains("apikey") || probe.contains("api_key") ||
            probe.contains("secret") || probe.contains("token") || probe.contains("password")
    }

/** 控件后缀：范围 + 键 + 对标来源，都拼在副标题里（不额外占一行）。 */
fun ProSettingSpec.subtitleText(): String {
    val parts = mutableListOf<String>()
    if (description.isNotBlank()) parts += description
    if (control == ProSettingControl.SLIDER || control == ProSettingControl.NUMBER) {
        parts += "范围 ${rangeText()}"
    }
    val slot = storage.displayName
    if (storageKey.isNotBlank()) {
        parts += "$slot · $storageKey"
    } else {
        parts += slot
    }
    if (pendingReason != null && control == ProSettingControl.PLACEHOLDER) {
        // 占位行的理由已经写在 description 里，不重复。
    } else if (pendingReason != null) {
        parts += pendingReason
    }
    if (benchmarkRefs.isNotEmpty()) parts += "对标 ${benchmarkRefs.joinToString("/")}"
    return parts.joinToString("  ·  ")
}

/** 模块标签（分栏内的小节标题用）。 */
fun moduleLabel(moduleId: String): String =
    ProSettingsTaxonomy.moduleById[moduleId]?.displayName ?: moduleId

/**
 * 渲染一条设置项。
 *
 * @param value 当前值（页面状态里的字符串；null → 用默认值展示）
 * @param onValueChange 值变更（已由页面负责收敛）
 * @param expanded 下拉项是否展开（由调用方持有，[DropdownSettingItem] 不自己管状态）
 * @param onExpandChange 下拉展开状态变更
 * @param onNavigate 跳转（NAV 项）
 */
@Composable
fun ProSettingRow(
    spec: ProSettingSpec,
    value: String?,
    onValueChange: (String) -> Unit,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    enabled: Boolean = true,
    onResetValue: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val subtitle = spec.subtitleText()
    when (spec.control) {
        ProSettingControl.SWITCH -> SwitchSettingItem(
            title = spec.title,
            description = subtitle,
            checked = spec.valueAsBoolean(value),
            onCheckedChange = { onValueChange(it.toString()) },
            modifier = modifier,
            enabled = enabled,
        )

        ProSettingControl.SLIDER -> {
            val range = spec.sliderRangeOrNull()
            if (range == null) {
                // 定义有问题时不崩：降级成只读文本，validate() 会在单测里报出来。
                TextInputSettingItem(
                    title = spec.title,
                    description = "$subtitle（定义缺少范围，已降级为文本）",
                    value = value.orEmpty(),
                    onValueChange = onValueChange,
                    modifier = modifier,
                )
            } else {
                val current = spec.valueAsDouble(value).toFloat().coerceIn(range.start, range.endInclusive)
                // 复位回调：既有 SliderSettingItem 的 onResetValue 是 (Float) -> Unit，这里做一次适配。
                val resetHandler: ((Float) -> Unit)? = onResetValue?.let { reset -> { _: Float -> reset() } }
                SliderSettingItem(
                    title = spec.title,
                    description = subtitle,
                    value = current,
                    valueRange = range,
                    onValueChange = { f -> onValueChange(ProSettingSpec.formatNumber(f.toDouble(), spec.decimals)) },
                    valueTextFormatter = { f ->
                        val text = ProSettingSpec.formatNumber(f.toDouble(), spec.decimals)
                        if (spec.unit.isBlank()) text else "$text ${spec.unit}"
                    },
                    resetValue = spec.defaultValue.toFloatOrNull(),
                    onResetValue = resetHandler,
                    enabled = enabled,
                    modifier = modifier,
                )
            }
        }

        ProSettingControl.NUMBER, ProSettingControl.TEXT -> TextInputSettingItem(
            title = spec.title,
            description = subtitle,
            value = value ?: spec.defaultValue,
            onValueChange = onValueChange,
            modifier = modifier,
            isSecret = spec.isSecretLike,
        )

        ProSettingControl.DROPDOWN -> DropdownSettingItem(
            title = spec.title,
            description = subtitle,
            value = value ?: spec.defaultValue,
            options = spec.options,
            isLoading = false,
            onExpanded = { onExpandChange(true) },
            onOptionSelected = { selected ->
                onExpandChange(false)
                onValueChange(selected)
            },
            enabled = enabled,
            modifier = modifier,
        )

        ProSettingControl.NAV -> NavigationSettingItem(
            title = spec.title,
            description = subtitle,
            onClick = { spec.navTarget?.let(onNavigate) },
            enabled = enabled && spec.navTarget != null,
            modifier = modifier,
        )

        ProSettingControl.PLACEHOLDER -> ProPlaceholderRow(spec = spec, modifier = modifier)
    }
}

/**
 * 占位行：灰态、不可交互，用来说明「哪个模块落地后这里会出现什么」。
 *
 * 不调用既有组件：既有组件都没有「不可编辑 + 说明」的形态，硬套会让可访问性语义失真。
 */
@Composable
fun ProPlaceholderRow(
    spec: ProSettingSpec,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (spec.description.isNotBlank()) {
                Text(
                    text = spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "待接线",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
