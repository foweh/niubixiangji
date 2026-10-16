package com.hinnka.mycamera.processor

import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.hinnka.mycamera.camera.AspectRatio
import com.hinnka.mycamera.camera.ComputationalScene
import com.hinnka.mycamera.camera.PsStackType
import com.hinnka.mycamera.computational.AstroStacker
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.model.SafeImage

/**
 * 计算摄影堆栈唯一路由器：scene + 星轨开关 → 具体堆栈实现。
 *
 * 由 [com.hinnka.mycamera.camera.Camera2Controller.dispatchComputationalStack]（同步 [route]）
 * 与 [com.hinnka.mycamera.gallery.GalleryManager] 出片路径（异步 [routeAsync]）共用，
 * 避免同一 scene→stacker 决策散落两处、参数来源不同而发散（审查项 P0-7）。
 *
 * 异步版走 `computation/BgJobRunner`：内存守卫 / 暂停 / 取消 / 分片节流对堆栈真正生效
 * （审查项 P0-8「异步调度入口无调用者」）。
 */
object ComputationalStackRouter {

    data class StackRequest(
        val scene: ComputationalScene,
        val stackType: PsStackType,
        val frameCount: Int,
        val align: Boolean,
        val starTrailsEnabled: Boolean = false,
        val trailAccumulationMode: AstroStacker.TrailAccumulationMode =
            AstroStacker.TrailAccumulationMode.LIGHTEN_MAX,
    )

    /** 该场景是否有后台（BgJobRunner）入口；false 表示调用方走既有同步 processBurst。 */
    fun supportsAsync(scene: ComputationalScene): Boolean = when (scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK,
        ComputationalScene.ASTRO -> true
        else -> false
    }

    /** 同步路由（控制器内部/兼容路径）。 */
    fun route(
        request: StackRequest,
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
    ): Bitmap? = when (request.scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK -> MultiFrameStacker.processPsStack(
            images = images,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
            type = request.stackType,
            frameCount = request.frameCount,
            align = request.align,
        )

        ComputationalScene.ASTRO ->
            if (request.starTrailsEnabled) {
                MultiFrameStacker.processStarTrailsBurst(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    accumulationMode = request.trailAccumulationMode,
                )
            } else {
                MultiFrameStacker.processAstroBurst(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    align = request.align,
                )
            }

        else -> null
    }

    /**
     * 异步路由：入 BgJobRunner 队列，返回 [BgJob]（可取消）；[onResult] 在后台线程回调。
     * 仅对 [supportsAsync] 为 true 的场景返回非空；STANDARD 等场景返回 null，
     * 调用方据此走既有同步 [MultiFrameStacker.processBurst]。
     */
    fun routeAsync(
        request: StackRequest,
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
        onResult: (Bitmap?) -> Unit,
    ): BgJob? = when (request.scene) {
        ComputationalScene.NIGHT,
        ComputationalScene.STACK -> MultiFrameStacker.processPsStackAsync(
            images = images,
            rotation = rotation,
            aspectRatio = aspectRatio,
            colorSpace = colorSpace,
            type = request.stackType,
            frameCount = request.frameCount,
            align = request.align,
            displayName = "PS Stack ${request.frameCount}F",
            onResult = onResult,
        )

        ComputationalScene.ASTRO ->
            if (request.starTrailsEnabled) {
                MultiFrameStacker.processStarTrailsAsync(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    accumulationMode = request.trailAccumulationMode,
                    onResult = onResult,
                )
            } else {
                MultiFrameStacker.processAstroBurstAsync(
                    images = images,
                    rotation = rotation,
                    aspectRatio = aspectRatio,
                    colorSpace = colorSpace,
                    align = request.align,
                    onResult = onResult,
                )
            }

        else -> null
    }
}
