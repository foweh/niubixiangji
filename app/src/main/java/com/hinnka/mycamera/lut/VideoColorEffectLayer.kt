package com.hinnka.mycamera.lut

import com.hinnka.mycamera.model.ColorRecipeParams

/**
 * 可叠加的「胶片质感层」参数（OldRoll 式复古质感，参考 RNI Films / GCam）。
 *
 * 设计原则（用户 2026-10-15 硬约束：滤镜要"好看 + 清晰"）：
 * - 这些层是 LUT 之后的**后处理叠加**，不改分辨率、不做低通软化；
 * - 默认值一律"薄"，仅在用户调大时才明显，不掩盖细节；
 * - 颗粒为程序化高清纹理并按亮度调制（中灰最强、纯黑/纯白为零），不是模糊噪点；
 * - 滤镜管线末端可选轻量锐化，抵消叠加层带来的任何轻微柔化。
 *
 * 所有分量默认 0 = 关闭。任何一层 active 仅需对应分量 > 0。
 */
data class FilmTextureParams(
    /** 颗粒强度 [0,1]。亮度调制：中灰(luma=0.5)最强，纯黑/纯白为零。默认薄。 */
    val grainAmount: Float = 0f,
    /** 颗粒尺寸（纹元缩放，越大越粗）。[0.5, 2.0]。 */
    val grainSize: Float = 1.0f,
    /** 暗角强度 [0,1]：四角压暗。默认薄。 */
    val vignette: Float = 0f,
    /** 漏光 light leak 强度 [0,1]：边角暖色辉光。默认 0（关闭）。 */
    val lightLeak: Float = 0f,
    /** 漏光色温偏移 [0,1]：0=暖橙漏光，1=青蓝漏光。 */
    val lightLeakWarm: Float = 0.0f,
    /** 柔光/halation 强度 [0,1]：高光柔光晕染。默认薄。 */
    val softGlow: Float = 0f,
    /** 末端轻量锐化/细节增强 [0,1]，默认可选开启（见 DEFAULT_LIGHT_SHARPEN）。 */
    val sharpen: Float = 0f,
) {
    /** 是否有任意一层需要执行（用于快速跳过整条后处理）。 */
    fun isActive(): Boolean =
        grainAmount > 0.001f || vignette > 0.001f || lightLeak > 0.001f ||
            softGlow > 0.001f || sharpen > 0.001f

    companion object {
        /** OldRoll 复古胶片的"薄"默认组合：轻微颗粒 + 轻暗角 + 极轻柔光，不压细节。 */
        val OLD_ROLL_THIN = FilmTextureParams(
            grainAmount = 0.10f,
            grainSize = 0.9f,
            vignette = 0.12f,
            lightLeak = 0.0f,
            lightLeakWarm = 0.0f,
            softGlow = 0.08f,
            sharpen = 0.10f,
        )

        /** 关闭。 */
        val NONE = FilmTextureParams()
    }
}

/**
 * 一层可实时烘焙到录像的 LUT 与色彩配方快照 + 可选胶片质感层。
 *
 * @param lutIntensity 原图↔LUT 全映射混合强度：1.0=完整 3D LUT 映射（默认），
 *        0=无效果，>1.0（最高 2.0）沿 LUT 色差外推、强化反差/饱和轮廓。
 *        （用户实测反馈：默认全映射，可拉到比默认更强，避免"只偏色"。）
 */
data class VideoColorEffectLayer(
    val lutConfig: LutConfig?,
    val recipeParams: ColorRecipeParams?,
    /** 可叠加胶片质感层（颗粒/漏光/暗角/柔光/锐化）。null = 不叠加。 */
    val filmTexture: FilmTextureParams? = null,
    /** LUT 全映射强度 0f..2f，默认 1f。UI 滑杆由界面子代理负责。 */
    val lutIntensity: Float = 1f,
)
