package com.hinnka.mycamera.multiframesr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 多帧超分场景入口（挂在 [com.hinnka.mycamera.ui.camera.ComputationalSceneBar] 末尾的挂点）。
 *
 * 自包含实现：自身持有配置状态（[remember]）并把变更写回 [MfsrRuntime]，因此补丁对既有
 * 场景条只增加"一行调用"，不改动任何既有参数与状态。
 *
 * 交互：点「多帧超分」chip 展开参数面板 → 帧数 / 倍率 / IBP 迭代 / 对齐方法 / 工作分辨率 /
 * 强度 / 降噪 全部可循环步进（步进阶梯见 [MfsrUiLadder]，帧数可一路调到 1000）→「开始融合」
 * 交给 [MfsrRuntime] 走后台队列（[MultiFrameSrTask] / `BgTaskType.MFSR`）。
 */
@Composable
fun MfsrSceneChipHost(modifier: Modifier = Modifier) {
    var config by remember { mutableStateOf(MfsrRuntime.defaultConfig.coerced()) }
    var expanded by remember { mutableStateOf(false) }
    var lastHint by remember { mutableStateOf("") }
    val ready = MfsrRuntime.isReady()

    fun apply(next: MfsrConfig) {
        val (coerced, fixed) = next.coerceWithReport()
        config = coerced
        MfsrRuntime.defaultConfig = coerced
        lastHint = if (fixed.isEmpty()) "" else "已修正: ${fixed.joinToString(",")}"
    }

    Column(
        modifier = modifier.wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MfsrChip(label = "多帧超分", selected = expanded, enabled = true) {
            expanded = !expanded
        }

        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MfsrChip(label = "${config.frameCount} 帧", selected = false, enabled = true) {
                    apply(config.copy(frameCount = MfsrUiLadder.stepFrameCount(config.frameCount)))
                }
                MfsrChip(label = "×${formatScale(config.scale)}", selected = false, enabled = true) {
                    apply(config.copy(scale = MfsrUiLadder.stepScale(config.scale)))
                }
                MfsrChip(label = "IBP ${config.ibpIterations}", selected = false, enabled = true) {
                    apply(config.copy(ibpIterations = MfsrUiLadder.stepIbpIterations(config.ibpIterations)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MfsrChip(label = alignLabel(config), selected = config.alignmentActive, enabled = true) {
                    apply(config.copy(alignMethod = MfsrUiLadder.stepAlignMethod(config.alignMethod)))
                }
                MfsrChip(
                    label = "工作 ${config.workingResolution.label}",
                    selected = config.workingResolution != MfsrWorkingResolution.FULL,
                    enabled = true,
                ) {
                    apply(config.copy(workingResolution = MfsrUiLadder.stepWorkingResolution(config.workingResolution)))
                }
                MfsrChip(label = "强度 ${formatScale(config.strength)}", selected = false, enabled = true) {
                    apply(config.copy(strength = MfsrUiLadder.stepStrength(config.strength)))
                }
                MfsrChip(label = if (config.denoiseOn) "降噪:开" else "降噪:关", selected = config.denoiseOn, enabled = true) {
                    apply(config.copy(denoiseOn = !config.denoiseOn))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MfsrChip(label = if (ready) "开始融合" else "未接入帧源", selected = ready, enabled = ready) {
                    if (!MfsrRuntime.submit(config)) lastHint = "任务未提交"
                }
                Text(
                    text = MfsrConfigCodec.summary(config),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            if (lastHint.isNotEmpty()) {
                Text(
                    text = lastHint,
                    color = Color(0xFFE8B2B0),
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun alignLabel(config: MfsrConfig): String = when {
    !config.alignmentEnabled -> "对齐:关"
    config.alignMethod == MfsrAlignMethod.PHASE_CORRELATION -> "对齐:相位"
    config.alignMethod == MfsrAlignMethod.OFF -> "对齐:关"
    else -> "对齐:块匹配"
}

private fun formatScale(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.2f", v)

@Composable
private fun MfsrChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> Color.Black.copy(alpha = 0.28f)
        selected -> Color(0xFFE8B2B0).copy(alpha = 0.92f)
        else -> Color.Black.copy(alpha = 0.38f)
    }
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.45f)
        selected -> Color(0xFF2A0E0D)
        else -> Color.White.copy(alpha = 0.82f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = 0.5.dp,
                color = if (selected) Color(0xFFE8B2B0).copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
