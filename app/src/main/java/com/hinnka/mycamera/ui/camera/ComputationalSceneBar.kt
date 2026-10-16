package com.hinnka.mycamera.ui.camera

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.camera.ComputationalScene
import com.hinnka.mycamera.camera.PsStackType
import com.hinnka.mycamera.funshot.FunshotSceneChipHost
import com.hinnka.mycamera.multiframesr.MfsrSceneChipHost
import com.hinnka.mycamera.camera.MultiFrameConfig

/**
 * 计算摄影场景入口条（HDR / 夜景 / 天文 / 人像 / 堆栈）。
 *
 * 设计原则：
 * - 只按 [ComputationalScene] 枚举渲染，不硬编码任何场景的拍摄行为细节（堆栈张数、
 *   曝光策略等全部在 controller / processor 侧）。
 * - 紧凑、低信息密度：一行可横滑的小圆角 chip，避免遮挡预览。
 * - RAW 视频开关仅在 VIDEO 模式出现；星轨变体开关仅在 ASTRO 场景出现。
 * - STACK 场景下展开 PS 堆栈类型（MEAN/MEDIAN/MAX/MIN）、帧数步进、对齐开关；
 *   仅当管线侧上报能力可用（[stackSupported]）时才渲染 STACK chip，多机型自适应。
 * - 「服务器增强」入口为计算摄影模块预留：仅当对方模块上报能力可用时才显示。
 */
@Composable
fun ComputationalSceneBar(

LongExposureEngine.submitLightPainting(  // 或 submitSlowShutter / submitFireworks / submitSuperMoon
    config = lightPaintingConfig, darkFrameConfig = darkConfig,
    frameSource = LongExposureJobRunner.FileFrameSource(paths, width, height),
    previewEnabled = true, onPreview = { plane, index -> /* 更新预览 Bitmap */ },
    onFinish = { plane -> /* 保存/送入 GalleryManager */ }
)    scene: ComputationalScene,
    onSceneSelected: (ComputationalScene) -> Unit,
    isVideoMode: Boolean,
    rawVideoEnabled: Boolean,
    onRawVideoToggle: (Boolean) -> Unit,
    starTrailsEnabled: Boolean,
    onStarTrailsToggle: (Boolean) -> Unit,
    serverEnhancementSupported: Boolean,
    serverEnhancementEnabled: Boolean,
    onServerEnhancementToggle: (Boolean) -> Unit,
    stackSupported: Boolean,
    stackType: PsStackType,
    stackFrameCount: Int,
    stackAlign: Boolean,
    onStackConfigChange: (PsStackType, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .wrapContentHeight()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 场景 chips：按枚举值顺序渲染；STACK 仅在能力可用时出现（机型适配）。
        ComputationalScene.entries.forEach { entry ->
            if (entry == ComputationalScene.STACK && !stackSupported) return@forEach
            SceneChip(
                label = sceneLabel(entry),
                selected = scene == entry,
                onClick = { onSceneSelected(entry) }
            )
        }

        // RAW 视频开关：仅 VIDEO 模式。
        if (isVideoMode) {
            SceneChip(
                label = "RAW 视频",
                selected = rawVideoEnabled,
                onClick = { onRawVideoToggle(!rawVideoEnabled) }
            )
        }

        // 星轨变体：仅 ASTRO 场景。
        if (scene == ComputationalScene.ASTRO) {
            SceneChip(
                label = "星轨",
                selected = starTrailsEnabled,
                onClick = { onStarTrailsToggle(!starTrailsEnabled) }
            )
        }

        // PS 堆栈子控件：仅 STACK 场景且能力可用。
        if (scene == ComputationalScene.STACK && stackSupported) {
            PsStackType.entries.forEach { type ->
                SceneChip(
                    label = stackTypeLabel(type),
                    selected = stackType == type,
                    onClick = { onStackConfigChange(type, stackFrameCount, stackAlign) }
                )
            }
            SceneChip(
                label = "${stackFrameCount} 帧",
                selected = false,
                onClick = { onStackConfigChange(stackType, stepStackFrameCount(stackFrameCount), stackAlign) }
            )
            SceneChip(
                label = if (stackAlign) "对齐:开" else "对齐:关",
                selected = stackAlign,
                onClick = { onStackConfigChange(stackType, stackFrameCount, !stackAlign) }
            )
        }

        // 服务器端增强（AI 超分 / 降噪）预留入口：对方模块未上报能力时不渲染。
        if (serverEnhancementSupported) {
            SceneChip(
                label = "服务器增强",
                selected = serverEnhancementEnabled,
                onClick = { onServerEnhancementToggle(!serverEnhancementEnabled) }
            )
        }

        // 创意拍摄玩法（全景拼接 / 声控 / 运动 / GIF / 间隔连拍）：自包含 chip 行，不新增也不重排本函数参数。
        FunshotSceneChipHost()

        // 多帧超分（帧数完全开放 2~1000）：自包含 chip + 参数面板，不改既有签名与状态。
        MfsrSceneChipHost()
    }
}

/** 帧数档位：按当前运行时上限动态生成（默认上限 20 时与原 ≤20 档位一致）。 */
private val STACK_FRAME_STEPS: IntArray
    get() {
        val max = MultiFrameConfig.frameCountLimit()
        return intArrayOf(3, 5, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512)
            .filter { it <= max }
            .toIntArray()
            .takeIf { it.isNotEmpty() }
            ?: intArrayOf(max)
    }

private fun stepStackFrameCount(current: Int): Int {
    val steps = STACK_FRAME_STEPS
    // 当前值可能来自滑杆（不在档位表内）：取"第一个比它大的档位"；已在顶则停住。
    val nextIndex = steps.indexOfFirst { it > current }
    return if (nextIndex >= 0) steps[nextIndex] else steps.last()
}

private fun sceneLabel(scene: ComputationalScene): String = when (scene) {
    ComputationalScene.STANDARD -> "标准"
    ComputationalScene.HDR -> "HDR"
    ComputationalScene.NIGHT -> "夜景"
    ComputationalScene.ASTRO -> "天文"
    ComputationalScene.PORTRAIT -> "人像"
    ComputationalScene.STACK -> "堆栈"
}

private fun stackTypeLabel(type: PsStackType): String = when (type) {
    PsStackType.MEAN -> "均值"
    PsStackType.MEDIAN -> "中值"
    PsStackType.MAX -> "最大"
    PsStackType.MIN -> "最小"
}

@Composable
private fun SceneChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        Color(0xFFE8B2B0).copy(alpha = 0.92f) // 暗红夜视友好色
    } else {
        Color.Black.copy(alpha = 0.38f)
    }
    val content = if (selected) {
        Color(0xFF2A0E0D)
    } else {
        Color.White.copy(alpha = 0.82f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = 0.5.dp,
                color = if (selected) Color(0xFFE8B2B0).copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 「清晰优先」指示：滤镜激活且底图为高清 RAW（DNG 捕获）时，在取景器角落给一个小标签，
 * 让用户确认色彩只是后处理叠加、底图仍是原始高分辨率数据（不糊、不软化）。
 */
@Composable
fun RawBaseIndicator(
    filterActive: Boolean,
    rawBaseAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    if (!filterActive || !rawBaseAvailable) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = "RAW 底图 · 清晰优先",
            color = Color(0xFFB7E4C7),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
