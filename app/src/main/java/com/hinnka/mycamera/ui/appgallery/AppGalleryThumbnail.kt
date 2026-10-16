package com.hinnka.mycamera.ui.appgallery

import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.FrameLayout
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.load
import com.hinnka.mycamera.appgallery.AppGalleryDisplay
import com.hinnka.mycamera.appgallery.AppGalleryItem

/**
 * 缩略图（相册项 1/13）。
 *
 * 复用工程既有做法：`AndroidView` + `ImageView` + `coil.load`（Coil 2.7.0，工程已依赖），
 * 而不是引入新的图片库；`thumbnailUri` 为空时回落原图 URI（Coil 会自动下采样）。
 * 右下角以轻量 `TextView` 叠加角标（分类/收藏/时长），避免在 Compose 侧再写一层 Overlay 布局。
 */
@Composable
fun AppGalleryThumbnail(
    item: AppGalleryItem,
    modifier: Modifier = Modifier,
    showBadges: Boolean = true,
    cornerRadiusDp: Int = 8,
) {
    val context = LocalContext.current
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadiusDp.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val image = ImageView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    val badge = TextView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.END or Gravity.BOTTOM,
                        )
                        setPadding(8, 4, 8, 4)
                        textSize = 11f
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#66000000"))
                    }
                    addView(image)
                    addView(badge)
                    tag = image
                }
            },
            update = { container ->
                val image = container.tag as? ImageView
                val badge = container.getChildAt(1) as? TextView
                val source = item.thumbnailUri?.takeIf { it.isNotBlank() } ?: item.uri
                // Coil 默认按视图尺寸下采样，列表里无需额外指定尺寸
                image?.load(source)
                val badges = if (showBadges) AppGalleryDisplay.badgesOf(item) else emptyList()
                badge?.text = badges.firstOrNull().orEmpty()
                badge?.visibility = if (badges.isEmpty()) TextView.GONE else TextView.VISIBLE
            },
        )

        if (item.isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFFFC107)),
            )
        }
    }
}

/** 空态占位（相册项 1/7/12 面板共用）。 */
@Composable
fun AppGalleryEmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
