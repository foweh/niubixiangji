package com.hinnka.mycamera.ui.appgallerypreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewConfig
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewExposurePolicy
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewExposureVerdict
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewHistogram
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawExposure

/** RAW→JPEG 导出的 UI 状态（进度与结果提示）。 */
data class AppGalleryPreviewRawExportUiState(
    val running: Boolean = false,
    /** 0..1。 */
    val progress: Float = 0f,
    /** 输出文件路径（成功时）。 */
    val outputPath: String? = null,
    val failed: Boolean = false,
)

/**
 * RAW/DNG 查看与处理面板（相册第 36 项）。
 *
 * 内容：曝光元数据摘要 + 亮度直方图 + 过曝/欠曝结论与建议补偿 + RAW→JPEG 导出入口。
 * 直方图与曝光数据由 [com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewBgTasks] 在后台算好后传入，
 * 本组件只做绘制（无计算、无 IO），保证不阻塞主线程。
 */
@Composable
fun AppGalleryPreviewRawPanel(
    exposure: AppGalleryPreviewRawExposure?,
    histogram: AppGalleryPreviewHistogram?,
    exportState: AppGalleryPreviewRawExportUiState,
    onExportClick: () -> Unit,
    config: AppGalleryPreviewConfig,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val safe = config.appGalleryPreviewSafe().config
    val verdict = AppGalleryPreviewExposurePolicy.verdict(histogram ?: EMPTY_HISTOGRAM, safe)
    val suggestedEv = AppGalleryPreviewExposurePolicy.suggestedCompensationEv(histogram ?: EMPTY_HISTOGRAM, safe)

    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = exposure?.summary() ?: stringResource(R.string.agp_raw_metadata_unavailable),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )

            if (safe.rawShowHistogram && histogram != null && !histogram.isEmpty) {
                Spacer(modifier = Modifier.height(8.dp))
                AppGalleryPreviewHistogramChart(
                    histogram = histogram,
                    overexposureThreshold = safe.rawOverexposureThreshold,
                    underexposureThreshold = safe.rawUnderexposureThreshold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                )
            }

            if (safe.rawShowOverexposureHint && histogram != null && !histogram.isEmpty) {
                Spacer(modifier = Modifier.height(6.dp))
                val ratioPercent = (histogram.overexposedRatio * 100f).toInt()
                val underPercent = (histogram.underexposedRatio * 100f).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppGalleryPreviewExposureChip(verdict = verdict)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.agp_raw_clip_summary,
                            ratioPercent,
                            underPercent,
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                    )
                }
                if (suggestedEv != 0.0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.agp_raw_suggest_ev,
                            if (suggestedEv > 0) "+" else "",
                            kotlin.math.round(suggestedEv * 100) / 100.0,
                        ),
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onExportClick, enabled = enabled && !exportState.running) {
                    Text(text = stringResource(R.string.agp_action_raw_export), fontSize = 12.sp)
                }
                exportState.outputPath?.let {
                    Text(
                        text = stringResource(R.string.agp_raw_export_done),
                        color = Color(0xFF7ED39A),
                        fontSize = 11.sp,
                    )
                }
                if (exportState.failed) {
                    Text(
                        text = stringResource(R.string.agp_raw_export_failed),
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                    )
                }
            }
            if (exportState.running) {
                Spacer(modifier = Modifier.height(6.dp))
                // 自绘进度条：避免依赖不同 Material3 版本的 progress 参数签名。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(exportState.progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

/**
 * 亮度直方图绘制。
 *
 * 采用自绘 Canvas（无第三方图表依赖）：`bins` 已由后台按 Rec.709 亮度统计，
 * 归一化后按峰值缩放绘制；过曝/欠曝阈值位置画参考线，便于用户判断裁剪。
 */
@Composable
fun AppGalleryPreviewHistogramChart(
    histogram: AppGalleryPreviewHistogram,
    overexposureThreshold: Float,
    underexposureThreshold: Float,
    modifier: Modifier = Modifier,
) {
    val normalized = histogram.normalized()
    val barColor = Color.White.copy(alpha = 0.72f)
    val overColor = Color(0xFFE57373).copy(alpha = 0.85f)
    val underColor = Color(0xFF64B5F6).copy(alpha = 0.85f)
    val guideColor = Color.White.copy(alpha = 0.28f)
    val bins = normalized.size

    Canvas(modifier = modifier.background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(4.dp))) {
        if (bins <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val barWidth = size.width / bins.toFloat()
        val overIndex = (overexposureThreshold.coerceIn(0f, 1f) * bins).toInt().coerceIn(0, bins - 1)
        val underIndex = (underexposureThreshold.coerceIn(0f, 1f) * bins).toInt().coerceIn(0, bins - 1)
        for (i in 0 until bins) {
            val value = normalized[i].coerceIn(0f, 1f)
            val barHeight = value * size.height
            if (barHeight <= 0f) continue
            val color = when {
                i >= overIndex -> overColor
                i <= underIndex -> underColor
                else -> barColor
            }
            drawRect(
                color = color,
                topLeft = Offset(i * barWidth, size.height - barHeight),
                size = Size(barWidth.coerceAtLeast(0.5f), barHeight),
            )
        }
        // 阈值参考线
        drawLine(
            color = guideColor,
            start = Offset(overIndex * barWidth, 0f),
            end = Offset(overIndex * barWidth, size.height),
            strokeWidth = 1f,
        )
        drawLine(
            color = guideColor,
            start = Offset(underIndex * barWidth, 0f),
            end = Offset(underIndex * barWidth, size.height),
            strokeWidth = 1f,
        )
    }
}

@Composable
private fun AppGalleryPreviewExposureChip(verdict: AppGalleryPreviewExposureVerdict) {
    val (labelRes, tint) = when (verdict) {
        AppGalleryPreviewExposureVerdict.HIGHLIGHT_CLIPPED ->
            R.string.agp_raw_verdict_over to Color(0xFFE57373)

        AppGalleryPreviewExposureVerdict.SHADOW_CLIPPED ->
            R.string.agp_raw_verdict_under to Color(0xFF64B5F6)

        AppGalleryPreviewExposureVerdict.BOTH_CLIPPED ->
            R.string.agp_raw_verdict_both to Color(0xFFFFB74D)

        AppGalleryPreviewExposureVerdict.OK ->
            R.string.agp_raw_verdict_ok to Color(0xFF7ED39A)

        AppGalleryPreviewExposureVerdict.UNKNOWN ->
            R.string.agp_raw_verdict_unknown to Color.White.copy(alpha = 0.6f)
    }
    Surface(color = tint.copy(alpha = 0.22f), shape = RoundedCornerShape(4.dp)) {
        Text(
            text = stringResource(labelRes),
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 空直方图占位（避免在组合期反复构造对象，作为无数据时的常量）。 */
private val EMPTY_HISTOGRAM = AppGalleryPreviewHistogram(bins = IntArray(0))
