package com.hinnka.mycamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.computational.BgJobRunner

/**
 * 后台长任务进度条 chip 组（星轨 200+ 帧堆栈、AI 超分回传等）。
 *
 * 集成自 computational/BgJobRunner（object，activeJobs: StateFlow<List<BgJob>>）。
 * 无任务时不渲染；有任务时在取景器顶部悬浮一行紧凑进度：名称 + 百分比，
 * RUNNING 时跑进度条，PAUSED 时灰显并可点按继续，DONE 后短暂显示「完成」。
 *
 * 注：BgJob.displayName / pause() / resume() 为计算摄影模块对外暴露的约定符号；
 * 若对方命名不同，集成时仅需在本文件映射一次。
 */
@Composable
fun BgJobProgressOverlay(modifier: Modifier = Modifier) {
    val jobs by BgJobRunner.activeJobs.collectAsState()
    if (jobs.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        jobs.forEach { job ->
            val state by job.state.collectAsState()
            BgJobChip(
                name = job.displayName,
                progress = state.progress,
                status = state.status.name,
                onPauseResume = {
                    when (state.status.name) {
                        "PAUSED" -> job.resume()
                        "RUNNING" -> job.pause()
                        else -> {}
                    }
                }
            )
        }
    }
}

@Composable
private fun BgJobChip(
    name: String,
    progress: Float,
    status: String,
    onPauseResume: () -> Unit
) {
    val isDone = status == "DONE"
    val isFailed = status == "FAILED"
    val isPaused = status == "PAUSED"
    val trackColor = when {
        isFailed -> Color(0xFFE57373)
        isDone -> Color(0xFF81C784)
        isPaused -> Color(0xFFB0BEC5)
        else -> Color(0xFFE8B2B0)
    }
    val pct = (progress * 100f).toInt().coerceIn(0, 100)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(enabled = isPaused || status == "RUNNING", onClick = onPauseResume)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    isDone -> "$name · 完成"
                    isFailed -> "$name · 失败"
                    isPaused -> "$name · 已暂停（点按继续）"
                    else -> name
                },
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            LinearProgressIndicator(
                progress = { if (isDone) 1f else progress },
                color = trackColor,
                trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
            )
        }
        Text(
            text = when {
                isDone -> "✓"
                isFailed -> "!"
                else -> "$pct%"
            },
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
