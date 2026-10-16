package com.hinnka.mycamera.superres

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 端侧 AI 超分/风格迁移/修复的**设置区块**（本模块自带 UI，集成阶段只需在设置页插一行）。
 *
 * 为什么 UI 放在本包：PATTERN_SPEC 第 1.1 节禁止改动既有文件，因此 UI 组件随模块一起交付，
 * 集成阶段通过补丁在设置页挂一个 [SuperResSettingsSection] 即可（见 patches/superres_patch.md）。
 *
 * 开放度：区块里每个参数都有滑杆/开关 + 数值显示，写入走 [SuperResRuntime.setConfig]（内部 clamp），
 * 越界值不会导致崩溃，只会被静默修正并在 UI 上重新显示修正后的值。
 */
@Composable
fun SuperResSettingsSection(
    runtime: SuperResRuntime,
    initial: SuperResConfig,
    onOpenModelManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var config by remember { mutableStateOf(SuperResConfig.clamp(initial)) }
    val scope = rememberCoroutineScope()

    fun apply(transform: (SuperResConfig) -> SuperResConfig) {
        val next = SuperResConfig.clamp(transform(config))
        config = next
        scope.launch { runtime.setConfig(next) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.superres_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.superres_section_description),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = stringResource(R.string.superres_enable),
            checked = config.enabled,
            onCheckedChange = { v -> apply { it.copy(enabled = v) } },
        )

        // 倍率：1（仅增强不放大）/2（默认下载档）/4（可选档）
        IntSliderRow(
            title = stringResource(R.string.superres_scale),
            value = config.scale,
            min = SuperResConfig.SCALE_MIN,
            max = SuperResConfig.SCALE_MAX,
            enabled = config.enabled,
            onValue = { v -> apply { it.copy(scale = v) } },
        )

        IntSliderRow(
            title = stringResource(R.string.superres_tile_size),
            value = config.tileSize,
            min = SuperResConfig.TILE_MIN,
            max = SuperResConfig.TILE_MAX,
            step = 64,
            enabled = config.enabled,
            onValue = { v -> apply { it.copy(tileSize = v) } },
        )

        IntSliderRow(
            title = stringResource(R.string.superres_tile_overlap),
            value = config.tileOverlap,
            min = 0,
            max = (config.tileSize / 2).coerceAtLeast(0),
            step = 4,
            enabled = config.enabled,
            onValue = { v -> apply { it.copy(tileOverlap = v) } },
        )

        FloatSliderRow(
            title = stringResource(R.string.superres_strength),
            value = config.strength,
            enabled = config.enabled,
            onValue = { v -> apply { it.copy(strength = v) } },
        )

        IntSliderRow(
            title = stringResource(R.string.superres_threads),
            value = config.threads,
            min = SuperResConfig.THREADS_MIN,
            max = SuperResConfig.THREADS_MAX,
            enabled = config.enabled,
            onValue = { v -> apply { it.copy(threads = v) } },
        )

        // 速度/质量档位
        Text(
            text = stringResource(R.string.superres_quality_tier),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuperResQualityTier.entries.forEach { tier ->
                TextButton(
                    onClick = { apply { it.copy(qualityTier = tier) } },
                    enabled = config.enabled,
                ) {
                    Text(
                        text = tierTitle(tier),
                        color = if (tier == config.qualityTier) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        SwitchRow(
            title = stringResource(R.string.superres_style_transfer),
            checked = config.enableStyleTransfer,
            enabled = config.enabled,
            onCheckedChange = { v -> apply { it.copy(enableStyleTransfer = v) } },
        )
        FloatSliderRow(
            title = stringResource(R.string.superres_style_strength),
            value = config.styleTransferStrength,
            enabled = config.enabled && config.enableStyleTransfer,
            onValue = { v -> apply { it.copy(styleTransferStrength = v) } },
        )

        SwitchRow(
            title = stringResource(R.string.superres_inpainting),
            checked = config.enableInpainting,
            enabled = config.enabled,
            onCheckedChange = { v -> apply { it.copy(enableInpainting = v) } },
        )
        IntSliderRow(
            title = stringResource(R.string.superres_inpaint_feather),
            value = config.inpaintFeatherPx,
            min = 0,
            max = SuperResConfig.INPAINT_FEATHER_MAX,
            enabled = config.enabled && config.enableInpainting,
            onValue = { v -> apply { it.copy(inpaintFeatherPx = v) } },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenModelManager() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.superres_model_manager),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.superres_model_manager_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            Text(text = stringResource(R.string.superres_model_manager_action))
        }
    }
}

/**
 * 模型管理页：列出可下载/可导入的模型（含来源、star、许可），支持下载与删除。
 *
 * 「可拆卸」的用户侧入口：不下载就不占空间，随时可删。
 *
 * [onImportRequested] 为 null 时不显示「导入」按钮（避免出现点了没反应的入口）；
 * 集成方接入 `ActivityResultContracts.OpenDocument` 后传入回调即出现该按钮，
 * 回调里调用 [SuperResRuntime.importLocalModel] 完成导入。
 */
@Composable
fun SuperResModelManagerScreen(
    runtime: SuperResRuntime,
    onBack: () -> Unit,
    onImportRequested: ((SuperResModelInfo) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<SuperResModelInfo>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            catalog = runtime.loadCatalog()
            installed = runtime.installedIds()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.superres_back)) }
            Text(
                text = stringResource(R.string.superres_model_manager),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(R.string.superres_model_manager_notice),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
        )
        if (message.isNotBlank()) {
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }

        catalog.forEach { info ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(info.nameEn.ifBlank { info.id }, fontWeight = FontWeight.Medium)
                if (info.nameZh.isNotBlank()) {
                    Text(info.nameZh, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = "${info.license} · ${info.starNote} · ${info.sizeMb}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = info.sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(info.note, style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info.id in installed) {
                        TextButton(onClick = {
                            runtime.deleteModel(info.id)
                            refresh()
                        }) { Text(stringResource(R.string.superres_model_delete)) }
                    } else {
                        TextButton(
                            onClick = {
                                busy = info.id
                                progress = 0
                                scope.launch {
                                    val state = runtime.downloadAndInstall(info) { s ->
                                        progress = (s.progress * 100).toInt()
                                    }
                                    message = when (state.phase) {
                                        SuperResDownloadPhase.READY -> "${info.id}: ok"
                                        else -> "${info.id}: ${state.phase} ${state.message}"
                                    }
                                    busy = null
                                    refresh()
                                }
                            },
                            enabled = busy == null,
                        ) { Text(stringResource(R.string.superres_model_download)) }
                    }
                    if (onImportRequested != null) {
                        TextButton(
                            onClick = { onImportRequested(info) },
                            enabled = busy == null,
                        ) { Text(stringResource(R.string.superres_model_import)) }
                    }
                }
                if (busy == info.id) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        SuperResImportHint()
        TextButton(onClick = { refresh() }, enabled = busy == null) {
            Text(stringResource(R.string.superres_refresh_catalog))
        }
        Text(
            text = stringResource(R.string.superres_installed_bytes) + ": " + runtime.installedBytes() / (1024 * 1024) + "MB",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("$progress%")
            }
        }
    }
}

/** 导入自备 ONNX 的提示行（实际选文件由挂点用 ActivityResultContracts.OpenDocument 完成）。 */
@Composable
fun SuperResImportHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.superres_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun IntSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    enabled: Boolean = true,
    onValue: (Int) -> Unit,
) {
    val safeMax = max.coerceAtLeast(min)
    val steps = ((safeMax - min) / step.coerceAtLeast(1) - 1).coerceAtLeast(0)
    Column {
        Row {
            Text(text = title, modifier = Modifier.weight(1f))
            Text(text = value.toString())
        }
        Slider(
            value = value.coerceIn(min, safeMax).toFloat(),
            valueRange = min.toFloat()..safeMax.toFloat(),
            steps = steps,
            enabled = enabled,
            onValueChange = { v ->
                val snapped = (v / step.coerceAtLeast(1)).toInt() * step.coerceAtLeast(1)
                onValue(snapped.coerceIn(min, safeMax))
            },
        )
    }
}

@Composable
private fun FloatSliderRow(
    title: String,
    value: Float,
    enabled: Boolean = true,
    onValue: (Float) -> Unit,
) {
    Column {
        Row {
            Text(text = title, modifier = Modifier.weight(1f))
            Text(text = "%.2f".format(value))
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            enabled = enabled,
            onValueChange = { onValue(it.coerceIn(0f, 1f)) },
        )
    }
}

@Composable
private fun tierTitle(tier: SuperResQualityTier): String = when (tier) {
    SuperResQualityTier.FAST -> stringResource(R.string.superres_tier_fast)
    SuperResQualityTier.BALANCED -> stringResource(R.string.superres_tier_balanced)
    SuperResQualityTier.QUALITY -> stringResource(R.string.superres_tier_quality)
}
