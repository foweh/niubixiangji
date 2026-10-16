package com.hinnka.mycamera.ui.viewfinder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.hinnka.mycamera.viewfinder.ResolvedSticker
import com.hinnka.mycamera.viewfinder.StickerPlacement
import com.hinnka.mycamera.viewfinder.ViewfinderHistogram
import com.hinnka.mycamera.viewfinder.ViewfinderSettings

/**
 * 取景器辅助叠加层的**统一入口**（一行挂进 CameraScreen）。
 *
 * 只负责 Compose 侧的两种叠加：实时直方图 + AR 贴纸。
 * 斑马纹与峰值对焦强化属于 **GL 叠加**，由 `viewfinder/ViewfinderGlOverlay` 在
 * `lut/LutRenderer` 的预览链路里完成（见补丁说明 CODE_HOOKS），不走 Compose。
 *
 * 对齐约定：全部子叠加与既有 `GridOverlay` / `LevelIndicatorOverlay` 使用同一套
 * 「按 aspectRatio 居中裁切」的几何算法，因此网格、水平仪、直方图、贴纸天然对齐。
 *
 * @param aspectRatio 预览宽高比
 * @param histogram 最新直方图
 * @param settings 模块聚合配置
 * @param placements 贴位列表
 * @param resolvedSticker 贴纸解析结果
 * @param externalStickerImage 外部素材位图（命中外部位图贴纸时由调用方提供，本层不做 IO）
 */
@Composable
fun ViewfinderOverlayHost(
    aspectRatio: Float,
    histogram: ViewfinderHistogram?,
    settings: ViewfinderSettings,
    placements: List<StickerPlacement>,
    resolvedSticker: ResolvedSticker,
    modifier: Modifier = Modifier,
    externalStickerImage: ImageBitmap? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ViewfinderHistogramOverlay(
            histogram = histogram,
            aspectRatio = aspectRatio,
            config = settings.histogram,
            modifier = Modifier.fillMaxSize(),
        )
        FaceStickerOverlay(
            placements = placements,
            resolved = resolvedSticker,
            aspectRatio = aspectRatio,
            config = settings.sticker,
            modifier = Modifier.fillMaxSize(),
            externalImage = externalStickerImage,
        )
    }
}

/**
 * 直连 [ViewfinderOverlayController] 的便捷重载（推荐用法，减少调用方样板代码）。
 *
 * 注意：调用方仍需把预览帧喂给 `controller.onViewfinderFrame(...)`，
 * 并在离开组合时 `controller.close()`。
 */
@Composable
fun ViewfinderOverlayHost(
    controller: ViewfinderOverlayController,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    externalStickerImage: ImageBitmap? = null,
) {
    ViewfinderOverlayHost(
        aspectRatio = aspectRatio,
        histogram = controller.histogram,
        settings = controller.settings,
        placements = controller.placements,
        resolvedSticker = controller.resolvedSticker,
        modifier = modifier,
        externalStickerImage = externalStickerImage,
    )
}
