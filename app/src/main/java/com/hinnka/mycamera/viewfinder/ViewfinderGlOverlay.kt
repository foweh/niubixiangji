package com.hinnka.mycamera.viewfinder

import android.opengl.GLES30
import com.hinnka.mycamera.lut.GlUtils
import com.hinnka.mycamera.utils.PLog

/**
 * 取景器 GL 叠加层：斑马纹 + 峰值对焦增强。
 *
 * 设计取舍（PATTERN_SPEC §9 最小改动）：把 program / FBO / uniform 定位等全部 GL 细节
 * 封在**本模块自有类**里，`LutRenderer` 侧只需两处注入——
 * ① 一个字段 `val viewfinderOverlay = ViewfinderGlOverlay()`；
 * ② 在既有 focus peaking 调用之后追加一次 `renderZebra(...)`（详见补丁说明 CODE_HOOKS）。
 *
 * 全部方法必须在 **GL 线程**调用；任何一步失败（着色器编译失败、FBO 不完整）都**原样返回输入纹理**，
 * 保证渲染链路不中断。
 */
class ViewfinderGlOverlay {

    companion object {
        private const val TAG = "ViewfinderGlOverlay"

        /** 与 LutRenderer 既有缓冲区布局一致：位置 2 float、纹理坐标 2 float、索引 6 short。 */
        private const val POSITION_COMPONENT_COUNT = 2
        private const val TEXTURE_COORD_COMPONENT_COUNT = 2

        /** 全屏四边形索引（两个三角形）。 */
        private const val INDEX_COUNT = 6
    }

    @Volatile
    var zebraConfig: ZebraConfig = ZebraConfig()
        private set

    @Volatile
    var peakingConfig: PeakingEnhanceConfig = PeakingEnhanceConfig()
        private set

    // ---- 斑马纹 program ----
    private var zebraProgramId = 0
    private var zebraInputTexLoc = 0
    private var zebraThresholdLoc = 0
    private var zebraResolutionLoc = 0
    private var zebraStripeWidthLoc = 0
    private var zebraStripeDutyLoc = 0
    private var zebraStripeAngleLoc = 0
    private var zebraStripeColorLoc = 0
    private var zebraStripeAlphaLoc = 0
    private var zebraUseMaxRgbLoc = 0
    private var zebraPositionLoc = 0
    private var zebraTexCoordLoc = 0
    private var zebraFboId = 0
    private var zebraTextureId = 0
    private var zebraFboWidth = 0
    private var zebraFboHeight = 0

    // ---- 峰值对焦增强 program ----
    private var peakingProgramId = 0
    private var peakingInputTexLoc = 0
    private var peakingTexelSizeLoc = 0
    private var peakingThresholdLoc = 0
    private var peakingColorLoc = 0
    private var peakingStepScaleLoc = 0
    private var peakingMixLoc = 0
    private var peakingPositionLoc = 0
    private var peakingTexCoordLoc = 0
    private var peakingFboId = 0
    private var peakingTextureId = 0
    private var peakingFboWidth = 0
    private var peakingFboHeight = 0

    /** 是否需要在预览链路上执行斑马纹 pass。 */
    val zebraActive: Boolean get() = zebraConfig.enabled

    /** 增强着色器是否可用（编译成功过）。 */
    val peakingEnhancementReady: Boolean get() = peakingProgramId != 0

    fun updateZebraConfig(config: ZebraConfig) {
        zebraConfig = ZebraConfig.clamp(config).value
    }

    fun updatePeakingConfig(config: PeakingEnhanceConfig) {
        peakingConfig = PeakingEnhanceConfig.clamp(config).value
    }

    /**
     * 在 [inputTextureId] 上叠加斑马纹，返回叠加后的纹理 id。
     *
     * @param vertexBufferId / texCoordBufferId / indexBufferId 复用 LutRenderer 既有缓冲区
     * @return 叠加结果纹理；未启用或失败时原样返回 [inputTextureId]
     */
    fun renderZebra(
        inputTextureId: Int,
        width: Int,
        height: Int,
        vertexBufferId: Int,
        texCoordBufferId: Int,
        indexBufferId: Int,
    ): Int {
        val config = zebraConfig
        if (!config.enabled) return inputTextureId
        if (inputTextureId == 0 || width <= 0 || height <= 0) return inputTextureId
        if (!ensureZebraProgram()) return inputTextureId
        if (!ensureTarget(
                kind = TargetKind.ZEBRA,
                width = width,
                height = height,
            )
        ) {
            return inputTextureId
        }

        val targetFbo = if (zebraFboId != 0) zebraFboId else return inputTextureId
        val targetTexture = if (zebraTextureId != 0) zebraTextureId else return inputTextureId

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(zebraProgramId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTextureId)
        GLES30.glUniform1i(zebraInputTexLoc, 0)
        GLES30.glUniform1f(zebraThresholdLoc, config.normalizedThreshold)
        GLES30.glUniform2f(zebraResolutionLoc, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(zebraStripeWidthLoc, config.stripeWidthPx)
        GLES30.glUniform1f(zebraStripeDutyLoc, config.stripeDuty)
        GLES30.glUniform1f(zebraStripeAngleLoc, config.stripeAngleDeg)
        GLES30.glUniform3f(zebraStripeColorLoc, config.stripeColorR, config.stripeColorG, config.stripeColorB)
        GLES30.glUniform1f(zebraStripeAlphaLoc, config.stripeAlpha)
        GLES30.glUniform1f(
            zebraUseMaxRgbLoc,
            if (config.metric == ZebraMetric.MAX_RGB) 1f else 0f,
        )

        drawFullQuad(
            vertexBufferId = vertexBufferId,
            texCoordBufferId = texCoordBufferId,
            indexBufferId = indexBufferId,
            positionLoc = zebraPositionLoc,
            texCoordLoc = zebraTexCoordLoc,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return targetTexture
    }

    /**
     * 峰值对焦增强 pass（用增强着色器替换既有硬编码版本）。
     *
     * 与 `LutRenderer.renderFocusPeaking` 的差别仅在可调项：阈值（灵敏度/直填）、颜色、混合强度、采样步长。
     * 若增强着色器编译失败，返回 [inputTextureId]，由调用方决定是否退回既有实现。
     */
    fun renderPeakingEnhanced(
        inputTextureId: Int,
        width: Int,
        height: Int,
        vertexBufferId: Int,
        texCoordBufferId: Int,
        indexBufferId: Int,
    ): Int {
        val config = peakingConfig
        if (!config.enabled) return inputTextureId
        if (inputTextureId == 0 || width <= 0 || height <= 0) return inputTextureId
        if (!ensurePeakingProgram()) return inputTextureId
        if (!ensureTarget(TargetKind.PEAKING, width, height)) return inputTextureId

        val targetFbo = if (peakingFboId != 0) peakingFboId else return inputTextureId
        val targetTexture = if (peakingTextureId != 0) peakingTextureId else return inputTextureId

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(peakingProgramId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTextureId)
        GLES30.glUniform1i(peakingInputTexLoc, 0)
        GLES30.glUniform2f(peakingTexelSizeLoc, 1f / width.toFloat(), 1f / height.toFloat())
        GLES30.glUniform1f(peakingThresholdLoc, config.resolveEdgeThreshold())
        GLES30.glUniform3f(peakingColorLoc, config.colorR, config.colorG, config.colorB)
        GLES30.glUniform1f(peakingStepScaleLoc, config.stepScale)
        GLES30.glUniform1f(peakingMixLoc, config.mixStrength)

        drawFullQuad(
            vertexBufferId = vertexBufferId,
            texCoordBufferId = texCoordBufferId,
            indexBufferId = indexBufferId,
            positionLoc = peakingPositionLoc,
            texCoordLoc = peakingTexCoordLoc,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return targetTexture
    }

    /**
     * GL 上下文丢失（surface 重建）后清空句柄——**不要**调用 glDelete*，
     * 此时上下文已销毁，删除调用无意义且可能污染新上下文状态。
     * 由补丁在 `LutRenderer` 既有 `resetAfterContextLoss` 位置与本类其他 reset 并列调用。
     */
    fun resetAfterContextLoss() {
        zebraProgramId = 0
        zebraPositionLoc = 0
        zebraTexCoordLoc = 0
        zebraFboId = 0
        zebraTextureId = 0
        zebraFboWidth = 0
        zebraFboHeight = 0
        peakingProgramId = 0
        peakingPositionLoc = 0
        peakingTexCoordLoc = 0
        peakingFboId = 0
        peakingTextureId = 0
        peakingFboWidth = 0
        peakingFboHeight = 0
    }

    /** 真正释放 GL 资源（在 GL 线程调用）。 */
    fun release() {
        val programs = intArrayOf(zebraProgramId, peakingProgramId).filter { it != 0 }
        if (programs.isNotEmpty()) {
            GLES30.glDeleteProgram(programs[0])
            if (programs.size > 1) GLES30.glDeleteProgram(programs[1])
        }
        for (fbo in intArrayOf(zebraFboId, peakingFboId)) {
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }
        for (texture in intArrayOf(zebraTextureId, peakingTextureId)) {
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
        resetAfterContextLoss()
    }

    private enum class TargetKind { ZEBRA, PEAKING }

    private fun ensureZebraProgram(): Boolean {
        if (zebraProgramId != 0) return true
        val vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, ViewfinderShaders.OVERLAY_VERTEX_SHADER)
        val fragmentShader = GlUtils.compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            ViewfinderShaders.ZEBRA_FRAGMENT_SHADER,
        )
        val program = GlUtils.linkProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (program == 0) {
            PLog.e(TAG, "斑马纹着色器编译/链接失败，本次跳过叠加")
            return false
        }
        zebraProgramId = program
        zebraInputTexLoc = GLES30.glGetUniformLocation(program, "uInputTexture")
        zebraThresholdLoc = GLES30.glGetUniformLocation(program, "uLumaThreshold")
        zebraResolutionLoc = GLES30.glGetUniformLocation(program, "uResolution")
        zebraStripeWidthLoc = GLES30.glGetUniformLocation(program, "uStripeWidthPx")
        zebraStripeDutyLoc = GLES30.glGetUniformLocation(program, "uStripeDuty")
        zebraStripeAngleLoc = GLES30.glGetUniformLocation(program, "uStripeAngleDeg")
        zebraStripeColorLoc = GLES30.glGetUniformLocation(program, "uStripeColor")
        zebraStripeAlphaLoc = GLES30.glGetUniformLocation(program, "uStripeAlpha")
        zebraUseMaxRgbLoc = GLES30.glGetUniformLocation(program, "uUseMaxRgb")
        zebraPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        zebraTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        return true
    }

    private fun ensurePeakingProgram(): Boolean {
        if (peakingProgramId != 0) return true
        val vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, ViewfinderShaders.OVERLAY_VERTEX_SHADER)
        val fragmentShader = GlUtils.compileShader(
            GLES30.GL_FRAGMENT_SHADER,
            ViewfinderShaders.PEAKING_ENHANCED_FRAGMENT_SHADER,
        )
        val program = GlUtils.linkProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (program == 0) {
            PLog.e(TAG, "峰值对焦增强着色器编译/链接失败，退回既有实现")
            return false
        }
        peakingProgramId = program
        peakingInputTexLoc = GLES30.glGetUniformLocation(program, "uInputTexture")
        peakingTexelSizeLoc = GLES30.glGetUniformLocation(program, "uTexelSize")
        peakingThresholdLoc = GLES30.glGetUniformLocation(program, "uThreshold")
        peakingColorLoc = GLES30.glGetUniformLocation(program, "uPeakColor")
        peakingStepScaleLoc = GLES30.glGetUniformLocation(program, "uStepScale")
        peakingMixLoc = GLES30.glGetUniformLocation(program, "uMixStrength")
        peakingPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        peakingTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        return true
    }

    /** 懒建目标 FBO（尺寸变化时重建）。 */
    private fun ensureTarget(kind: TargetKind, width: Int, height: Int): Boolean {
        val currentFbo = if (kind == TargetKind.ZEBRA) zebraFboId else peakingFboId
        val currentWidth = if (kind == TargetKind.ZEBRA) zebraFboWidth else peakingFboWidth
        val currentHeight = if (kind == TargetKind.ZEBRA) zebraFboHeight else peakingFboHeight
        if (currentFbo != 0 && currentWidth == width && currentHeight == height) return true

        if (currentFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(currentFbo), 0)
            GLES30.glDeleteTextures(
                1,
                intArrayOf(if (kind == TargetKind.ZEBRA) zebraTextureId else peakingTextureId),
                0,
            )
        }

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        if (fboIds[0] == 0 || textureIds[0] == 0) {
            PLog.e(TAG, "生成叠加 FBO/纹理失败（$kind）")
            return false
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height,
            0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, textureIds[0], 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            PLog.e(TAG, "叠加 FBO 不完整（$kind, status=$status）")
            GLES30.glDeleteFramebuffers(1, fboIds, 0)
            GLES30.glDeleteTextures(1, textureIds, 0)
            return false
        }

        when (kind) {
            TargetKind.ZEBRA -> {
                zebraFboId = fboIds[0]
                zebraTextureId = textureIds[0]
                zebraFboWidth = width
                zebraFboHeight = height
            }

            TargetKind.PEAKING -> {
                peakingFboId = fboIds[0]
                peakingTextureId = textureIds[0]
                peakingFboWidth = width
                peakingFboHeight = height
            }
        }
        return true
    }

    private fun drawFullQuad(
        vertexBufferId: Int,
        texCoordBufferId: Int,
        indexBufferId: Int,
        positionLoc: Int,
        texCoordLoc: Int,
    ) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
        GLES30.glEnableVertexAttribArray(positionLoc)
        GLES30.glVertexAttribPointer(
            positionLoc, POSITION_COMPONENT_COUNT, GLES30.GL_FLOAT, false, 0, 0,
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, texCoordBufferId)
        GLES30.glEnableVertexAttribArray(texCoordLoc)
        GLES30.glVertexAttribPointer(
            texCoordLoc, TEXTURE_COORD_COMPONENT_COUNT, GLES30.GL_FLOAT, false, 0, 0,
        )

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, INDEX_COUNT, GLES30.GL_UNSIGNED_SHORT, 0)

        GLES30.glDisableVertexAttribArray(positionLoc)
        GLES30.glDisableVertexAttribArray(texCoordLoc)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}
