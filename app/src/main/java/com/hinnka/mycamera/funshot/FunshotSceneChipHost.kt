package com.hinnka.mycamera.funshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「创意拍摄玩法」入口条（全景 / 声控 / 运动 / GIF / 定时连拍）。
 *
 * 设计原则（与 `ComputationalSceneBar` 保持一致）：
 * - 只做**开关与入口**，不承载算法与相机行为（全部在 funshot 包的各 Runtime 上）；
 * - 能力未就绪（如无加速度计、未注入帧来源）时对应 chip 不渲染，避免点了没反应；
 * - 尺寸/配色克制，不遮挡预览；文字全部可走 i18n key（见 patch 的 STRINGS 段）。
 */
@Composable
fun FunshotSceneChipRow(
    panoramaAvailable: Boolean,
    panoramaEnabled: Boolean,
    onPanoramaToggle: (Boolean) -> Unit,
    soundTriggerAvailable: Boolean,
    soundTriggerMode: FunshotTriggerMode,
    onSoundTriggerToggle: (FunshotTriggerMode) -> Unit,
    motionTriggerAvailable: Boolean,
    motionTriggerEnabled: Boolean,
    onMotionTriggerToggle: (Boolean) -> Unit,
    gifExportAvailable: Boolean,
    onGifExport: () -> Unit,
    intervalBurstEnabled: Boolean,
    onIntervalBurstToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.wrapContentHeight().padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (panoramaAvailable) {
            FunshotChip(
                label = "全景",
                selected = panoramaEnabled,
                onClick = { onPanoramaToggle(!panoramaEnabled) },
            )
        }
        if (soundTriggerAvailable) {
            FunshotChip(
                label = if (soundTriggerMode == FunshotTriggerMode.VIDEO) "声控录像" else "声控快门",
                selected = soundTriggerMode != FunshotTriggerMode.PHOTO,
                onClick = {
                    onSoundTriggerToggle(
                        if (soundTriggerMode == FunshotTriggerMode.PHOTO) {
                            FunshotTriggerMode.VIDEO
                        } else {
                            FunshotTriggerMode.PHOTO
                        },
                    )
                },
            )
        }
        if (motionTriggerAvailable) {
            FunshotChip(
                label = "动作触发",
                selected = motionTriggerEnabled,
                onClick = { onMotionTriggerToggle(!motionTriggerEnabled) },
            )
        }
        if (gifExportAvailable) {
            FunshotChip(
                label = "GIF",
                selected = false,
                onClick = onGifExport,
            )
        }
        FunshotChip(
            label = "间隔连拍",
            selected = intervalBurstEnabled,
            onClick = { onIntervalBurstToggle(!intervalBurstEnabled) },
        )
    }
}

/**
 * 自持状态的入口条：直接读各 Runtime，宿主侧一行接入即可
 * （见 `delivery/patches/funshot_patch.md` 的 CODE_HOOKS）。
 */
@Composable
fun FunshotSceneChipHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var panoramaEnabled by remember { mutableStateOf(false) }
    var soundMode by remember { mutableStateOf(SoundTriggerRuntime.config.mode) }
    var motionEnabled by remember { mutableStateOf(MotionTriggerRuntime.isRunning) }
    var intervalEnabled by remember { mutableStateOf(IntervalBurstRuntime.isRunning) }

    FunshotSceneChipRow(
        panoramaAvailable = PanoramaRuntime.isReady,
        panoramaEnabled = panoramaEnabled,
        onPanoramaToggle = { enabled ->
            panoramaEnabled = enabled
            // 全景为「采集完成后拼接」的一次性动作：入口只负责按当前帧来源发起后台任务。
            if (enabled) {
                PanoramaRuntime.startFromProvidedFrames()
                panoramaEnabled = false
            }
        },
        soundTriggerAvailable = true,
        soundTriggerMode = soundMode,
        onSoundTriggerToggle = { mode ->
            soundMode = mode
            SoundTriggerRuntime.config = SoundTriggerRuntime.config.copy(mode = mode, enabled = true)
            SoundTriggerRuntime.refreshConfig()
            if (SoundTriggerRuntime.isRunning) {
                SoundTriggerRuntime.stop()
            }
            SoundTriggerRuntime.start()
        },
        motionTriggerAvailable = true,
        motionTriggerEnabled = motionEnabled,
        onMotionTriggerToggle = { enabled ->
            motionEnabled = enabled
            MotionTriggerRuntime.config = MotionTriggerRuntime.config.copy(enabled = enabled)
            MotionTriggerRuntime.refreshConfig()
            if (enabled) {
                MotionTriggerRuntime.start()
            } else {
                MotionTriggerRuntime.stop()
            }
        },
        gifExportAvailable = GifRuntime.isVideoReady || GifRuntime.isBurstReady,
        onGifExport = {
            when {
                GifRuntime.isVideoReady -> GifRuntime.exportFromVideo(context)
                GifRuntime.isBurstReady -> GifRuntime.exportFromBurst()
                else -> Unit
            }
        },
        intervalBurstEnabled = intervalEnabled,
        onIntervalBurstToggle = { enabled ->
            intervalEnabled = enabled
            IntervalBurstRuntime.config = IntervalBurstRuntime.config.copy(enabled = enabled)
            if (enabled) {
                IntervalBurstRuntime.start()
            } else {
                IntervalBurstRuntime.cancel()
            }
        },
        modifier = modifier,
    )
}

/** 与 `ComputationalSceneBar.SceneChip` 同款视觉（本包内自持一份，避免改动既有文件）。 */
@Composable
private fun FunshotChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        Color(0xFFE8B2B0).copy(alpha = 0.92f)
    } else {
        Color.Black.copy(alpha = 0.38f)
    }
    val content = if (selected) Color(0xFF2A0E0D) else Color.White.copy(alpha = 0.82f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = 0.5.dp,
                color = if (selected) {
                    Color(0xFFE8B2B0).copy(alpha = 0.6f)
                } else {
                    Color.White.copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
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
