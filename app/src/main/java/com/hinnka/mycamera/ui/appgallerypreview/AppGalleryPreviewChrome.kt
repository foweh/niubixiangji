package com.hinnka.mycamera.ui.appgallerypreview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.R
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewActionId
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewLayoutPlan
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaKind
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewMediaKindResolver
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewQuickAction
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewRawExposure
import com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewSlideshowState

/**
 * 预览页顶/底 chrome（顶栏、快捷操作栏、详情弹窗、删除确认）。
 *
 * 图标只使用 `material-icons-core` 中确实存在的矢量（工程仅依赖 core artifact），
 * 避免为几个图标引入 `material-icons-extended`（数千个矢量、体积开销大）。
 */

/** 顶栏：返回 + 标题/计数 + 放映状态。 */
@Composable
fun AppGalleryPreviewTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    counter: String? = null,
    slideshowState: AppGalleryPreviewSlideshowState? = null,
) {
    Surface(color = Color.Black.copy(alpha = 0.42f), modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.agp_back),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = counter ?: stringResource(R.string.agp_title_preview),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (slideshowState != null && slideshowState != AppGalleryPreviewSlideshowState.IDLE) {
                Text(
                    text = stringResource(R.string.agp_slideshow_running),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

/**
 * 快捷操作栏（相册第 39/40/42 项）。
 *
 * @param vertical 宽屏时竖向排列（由 [AppGalleryPreviewLayoutPlan.chromePlacement] 决定）
 * @param favorite 当前收藏状态（用于切换图标）
 */
@Composable
fun AppGalleryPreviewActionBar(
    actions: List<AppGalleryPreviewQuickAction>,
    onAction: (AppGalleryPreviewActionId) -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean? = null,
    vertical: Boolean = false,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.42f),
        modifier = modifier,
    ) {
        if (vertical) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                actions.forEach { action ->
                    AppGalleryPreviewActionItem(action = action, favorite = favorite, onClick = onAction)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    AppGalleryPreviewActionItem(action = action, favorite = favorite, onClick = onAction)
                }
            }
        }
    }
}

@Composable
private fun AppGalleryPreviewActionItem(
    action: AppGalleryPreviewQuickAction,
    favorite: Boolean?,
    onClick: (AppGalleryPreviewActionId) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = { if (action.enabled) onClick(action.id) },
            enabled = action.enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = appGalleryPreviewActionIcon(action.id, favorite),
                contentDescription = stringResource(appGalleryPreviewActionLabelRes(action.id)),
                tint = if (action.enabled) Color.White else Color.White.copy(alpha = 0.32f),
            )
        }
        Text(
            text = stringResource(appGalleryPreviewActionLabelRes(action.id)),
            color = if (action.enabled) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.32f),
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

/** 快捷操作图标（仅使用 material-icons-core 已包含的矢量）。 */
internal fun appGalleryPreviewActionIcon(id: AppGalleryPreviewActionId, favorite: Boolean?): ImageVector =
    when (id) {
        AppGalleryPreviewActionId.FAVORITE ->
            if (favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder

        AppGalleryPreviewActionId.DELETE -> Icons.Default.Delete
        AppGalleryPreviewActionId.SHARE -> Icons.Default.Share
        AppGalleryPreviewActionId.INFO -> Icons.Default.Info
        AppGalleryPreviewActionId.EDIT -> Icons.Default.Edit
        AppGalleryPreviewActionId.QUICK_EDIT -> Icons.Default.Build
        AppGalleryPreviewActionId.SLIDESHOW -> Icons.Default.PlayArrow
        AppGalleryPreviewActionId.COMPARE -> Icons.Default.Menu
        AppGalleryPreviewActionId.RAW_EXPORT -> Icons.Default.Check
        AppGalleryPreviewActionId.PLAY_PAUSE -> Icons.Default.PlayArrow
        AppGalleryPreviewActionId.BOOKMARK -> Icons.Default.Star
        AppGalleryPreviewActionId.SAVE_AS -> Icons.Default.Add
    }

/** 快捷操作文案资源（新增 key 见补丁 STRINGS 节）。 */
internal fun appGalleryPreviewActionLabelRes(id: AppGalleryPreviewActionId): Int = when (id) {
    AppGalleryPreviewActionId.FAVORITE -> R.string.agp_action_favorite
    AppGalleryPreviewActionId.DELETE -> R.string.agp_action_delete
    AppGalleryPreviewActionId.SHARE -> R.string.agp_action_share
    AppGalleryPreviewActionId.INFO -> R.string.agp_action_info
    AppGalleryPreviewActionId.EDIT -> R.string.agp_action_edit
    AppGalleryPreviewActionId.QUICK_EDIT -> R.string.agp_action_quick_edit
    AppGalleryPreviewActionId.SLIDESHOW -> R.string.agp_action_slideshow
    AppGalleryPreviewActionId.COMPARE -> R.string.agp_action_compare
    AppGalleryPreviewActionId.RAW_EXPORT -> R.string.agp_action_raw_export
    AppGalleryPreviewActionId.PLAY_PAUSE -> R.string.agp_action_play_pause
    AppGalleryPreviewActionId.BOOKMARK -> R.string.agp_action_bookmark
    AppGalleryPreviewActionId.SAVE_AS -> R.string.agp_action_save_as
}

/** 删除前的二次确认（删除类操作 requiresConfirm=true 时弹出）。 */
@Composable
fun AppGalleryPreviewDeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.agp_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.agp_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.agp_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.agp_cancel)) }
        },
    )
}

/**
 * 详情/信息弹窗（相册第 42 项的 INFO 动作）。
 *
 * 只展示预览域能确定的信息（URI、类型、曝光元数据、导航计数）；
 * 完整 EXIF 由管理域填充其自己的详情页（跨域契约见 [com.hinnka.mycamera.appgallerypreview.AppGalleryPreviewEntryContract]）。
 */
@Composable
fun AppGalleryPreviewInfoDialog(
    mediaUri: String,
    mediaKind: AppGalleryPreviewMediaKind,
    exposure: AppGalleryPreviewRawExposure?,
    onDismiss: () -> Unit,
    index: Int = 0,
    total: Int = 1,
) {
    val kindLabel = when (mediaKind) {
        AppGalleryPreviewMediaKind.IMAGE -> stringResource(R.string.agp_kind_image)
        AppGalleryPreviewMediaKind.GIF -> stringResource(R.string.agp_kind_gif)
        AppGalleryPreviewMediaKind.RAW -> stringResource(R.string.agp_kind_raw)
        AppGalleryPreviewMediaKind.VIDEO -> stringResource(R.string.agp_kind_video)
        AppGalleryPreviewMediaKind.UNSUPPORTED -> stringResource(R.string.agp_kind_unsupported)
    }
    val lines = buildList {
        add(stringResource(R.string.agp_info_kind, kindLabel))
        add(stringResource(R.string.agp_info_position, index + 1, total))
        exposure?.summary()?.let { add(it) }
        if (exposure != null && (exposure.widthPx > 0 || exposure.heightPx > 0)) {
            add(stringResource(R.string.agp_info_dimension, exposure.widthPx, exposure.heightPx))
        }
        exposure?.baselineExposureEv?.let {
            add(stringResource(R.string.agp_info_baseline_ev, it.toString()))
        }
        if (AppGalleryPreviewMediaKindResolver.isRaw(mediaKind)) {
            add(stringResource(R.string.agp_info_raw_pipeline))
        }
        add(mediaUri)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.agp_action_info)) },
        text = {
            Column {
                lines.forEach { line ->
                    Text(text = line, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.agp_confirm)) }
        },
    )
}

/** 幻灯片间隔的紧凑显示（形如 "3.0s"）。 */
@Composable
fun AppGalleryPreviewSlideshowIndicator(intervalMs: Long, state: AppGalleryPreviewSlideshowState, modifier: Modifier = Modifier) {
    val seconds = (intervalMs / 100L).toDouble() / 10.0
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Text(
            text = when (state) {
                AppGalleryPreviewSlideshowState.PLAYING -> stringResource(R.string.agp_slideshow_playing, seconds)
                AppGalleryPreviewSlideshowState.PAUSED -> stringResource(R.string.agp_slideshow_paused)
                AppGalleryPreviewSlideshowState.FINISHED -> stringResource(R.string.agp_slideshow_finished)
                AppGalleryPreviewSlideshowState.IDLE -> stringResource(R.string.agp_slideshow_idle)
            },
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
