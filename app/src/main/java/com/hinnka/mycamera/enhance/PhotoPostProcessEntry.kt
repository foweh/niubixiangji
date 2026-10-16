package com.hinnka.mycamera.enhance

import android.graphics.Bitmap
import com.hinnka.mycamera.computational.BgJob
import com.hinnka.mycamera.computational.BgJobPriority
import com.hinnka.mycamera.lenscorrection.LensCorrectionBitmapAdapter
import com.hinnka.mycamera.lenscorrection.LensCorrectionCore
import com.hinnka.mycamera.lenscorrection.LensDistortionConfig
import com.hinnka.mycamera.utils.PLog
import kotlin.math.max

/**
 * 「经典后处理 + 镜头畸变校正」的统一入口（**非纯 Kotlin，不参与 JVM 单测**）。
 *
 * 集成层只需在挂点上调一个函数（挂点见补丁 `delivery/patches/enhance_patch.md` 的 CODE_HOOKS）：
 * - 拍照后处理管线（`gallery/PhotoProcessor.processBitmap` 末尾）→ [applySync] 或 [submitEnhance]；
 * - 相册二次处理（`viewmodel/GalleryViewModel.saveEditMetadata` 前）→ [applySync] 或 [submitEnhance]；
 * - 同一入口可重复调用，实现「可重放的二次处理」。
 *
 * **执行顺序有物理含义**：先镜头畸变校正（几何重映射），再经典后处理（色调/风格）。
 * 反过来会让模糊/锐化量按错误几何分布，且移轴等风格滤镜的「清晰带」会跟着畸变被扭曲。
 */
object PhotoPostProcessEntry {

    private const val TAG = "PhotoPostProcess"
    private const val BYTES_PER_INT = 4L

    /** 镜头校正的工作分辨率下限（与 LensCorrectionJobRunner 一致，此处独立声明避免交叉依赖）。 */
    private const val MIN_LENS_WORKING_SCALE = 0.4f

    /** 默认内存预算（与 `BgTaskType.ENHANCE` 建议预算同量级，可由调用方覆盖）。 */
    const val DEFAULT_MEMORY_BUDGET_BYTES: Long = 512L * 1024 * 1024

    /**
     * 同步执行（调用方需**已在后台线程**，例如 `withContext(Dispatchers.Default)`）。
     *
     * 无任何阶段启用时直接返回原图（不复制、不分配）。
     * 内存不足时按 [EnhancePipeline.resolveWorkingScale] / [resolveLensScale] 降工作分辨率，不抛异常。
     */
    fun applySync(
        source: Bitmap,
        enhance: EnhanceConfig = EnhanceConfig(),
        lens: LensDistortionConfig? = null,
        memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
        bandHeight: Int = EnhanceJobRunner.DEFAULT_BAND_HEIGHT,
    ): Bitmap {
        if (source.width <= 0 || source.height <= 0) return source
        val (clampedEnhance, reports) = enhance.clampWithReport()
        if (reports.isNotEmpty()) PLog.i(TAG, "enhance clamped: ${reports.joinToString()}")
        val clampedLens = lens?.let { LensDistortionConfig.clamp(it) }
        val lensActive = clampedLens?.isActive() == true
        if (!lensActive && !clampedEnhance.isActive()) {
            PLog.d(TAG, "no active stage, skip post process")
            return source
        }

        val band = EnhanceJobRunner.clampBandHeight(bandHeight)
        var current = source

        // ① 镜头畸变校正（几何）
        if (lensActive && clampedLens != null) {
            val scale = resolveLensScale(current.width, current.height, memoryBudgetBytes)
            current = LensCorrectionBitmapAdapter.scaleTo(current, scale)
            val pixels = LensCorrectionBitmapAdapter.readPixels(current)
            val corrected = LensCorrectionCore.remapWithConfig(
                source = pixels,
                srcWidth = current.width,
                srcHeight = current.height,
                config = clampedLens,
                bandHeight = band,
            )
            current = LensCorrectionBitmapAdapter.toBitmap(corrected, current.width, current.height)
            PLog.i(TAG, "lens corrected at scale=$scale size=${current.width}x${current.height}")
        }

        // ② 经典后处理（色调/风格）
        if (clampedEnhance.isActive()) {
            val scale = EnhancePipeline.resolveWorkingScale(
                current.width, current.height, clampedEnhance.workingScale, memoryBudgetBytes,
            )
            current = EnhanceBitmapAdapter.scaleTo(current, scale)
            val pixels = EnhanceBitmapAdapter.readPixels(current)
            val processed = EnhancePipeline.process(
                pixels = pixels,
                width = current.width,
                height = current.height,
                config = clampedEnhance,
                bandHeight = band,
            )
            current = EnhanceBitmapAdapter.toBitmap(processed, current.width, current.height)
            PLog.i(
                TAG,
                "enhance applied at scale=$scale size=${current.width}x${current.height} " +
                    "stages=${clampedEnhance.stages()}",
            )
        }
        return current
    }

    /** 异步：只跑经典后处理（走 [EnhanceJobRunner]，进度进后台 chip）。 */
    fun submitEnhance(
        source: Bitmap,
        config: EnhanceConfig = EnhanceConfig(),
        jobName: String = EnhanceJobRunner.JOB_NAME,
        priority: Int = BgJobPriority.LOW,
        onFinish: ((Bitmap?) -> Unit)? = null,
    ): BgJob = EnhanceJobRunner.submitEnhance(
        source = source,
        config = config,
        jobName = jobName,
        priority = priority,
        onFinish = onFinish,
    )

    /**
     * 镜头校正的工作分辨率：重映射必须按像素网格采样，降级会损失细节，
     * 因此只在内存确实放不下时才降，下限 [MIN_LENS_WORKING_SCALE]。
     */
    private fun resolveLensScale(width: Int, height: Int, memoryBudgetBytes: Long): Float {
        val budget = if (memoryBudgetBytes > 0L) memoryBudgetBytes else Long.MAX_VALUE
        var scale = 1f
        while (scale > MIN_LENS_WORKING_SCALE) {
            val (w, h) = LensCorrectionBitmapAdapter.scaledDimensions(width, height, scale)
            val bytes = 2L * w * h * BYTES_PER_INT + 2L * w * h * BYTES_PER_INT
            if (bytes <= budget) return scale
            val next = max(MIN_LENS_WORKING_SCALE, scale / 2f)
            if (next >= scale) return scale
            scale = next
        }
        return scale
    }
}
