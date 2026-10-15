package com.hinnka.mycamera.lut

import android.opengl.GLES30
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs

internal data class FilmTextureOutput(val textureId: Int, val framebufferId: Int)

/**
 * 胶片质感层后处理（暗角/漏光/亮度调制颗粒/柔光/锐化，单 pass）。
 *
 * 与 [FilmGrainGl] 同构：持有一个 context-local 输出 target；当 [params] 任一开关关闭时
 * 直接跳过（返回 null），保证无开销、低破坏。默认值"薄"，不掩盖细节。
 *
 * 接入点：录像/导出管线在 LUT 与既有 film-grain 之后调用 [renderToTexture]，
 * 把结果作为最终画面。所有层叠在增强/超分结果之上，不改分辨率。
 */
internal class FilmTextureLayersGl(private val logTag: String) {

    private var programId = 0
    private var inputLocation = -1
    private var resolutionLocation = -1
    private var frameSeedLocation = -1
    private var grainAmountLocation = -1
    private var grainSizeLocation = -1
    private var vignetteLocation = -1
    private var lightLeakLocation = -1
    private var leakWarmLocation = -1
    private var softGlowLocation = -1
    private var sharpenLocation = -1
    private var outputTextureId = 0
    private var outputFramebufferId = 0
    private var outputWidth = 0
    private var outputHeight = 0

    fun prepare(): Boolean = ensureProgram()

    /** 渲染到自有中间纹理，返回输出 target；无激活层时返回 null。 */
    fun renderToTexture(
        sourceTextureId: Int,
        width: Int,
        height: Int,
        params: FilmTextureParams,
        frameSeed: Float,
        drawQuad: (Int) -> Unit,
    ): FilmTextureOutput? {
        if (!params.isActive()) return null
        if (!ensureProgram() || !ensureOutput(width, height)) return null
        if (!drawToFramebuffer(
                targetFramebufferId = outputFramebufferId,
                sourceTextureId = sourceTextureId,
                width = width,
                height = height,
                params = params,
                frameSeed = frameSeed,
                drawQuad = drawQuad,
            )
        ) return null
        return FilmTextureOutput(outputTextureId, outputFramebufferId)
    }

    /** 直接画到指定 FBO（例如最终 encoder surface 的 framebuffer 0）。 */
    fun drawToFramebuffer(
        targetFramebufferId: Int,
        sourceTextureId: Int,
        width: Int,
        height: Int,
        params: FilmTextureParams,
        frameSeed: Float,
        drawQuad: (Int) -> Unit,
    ): Boolean {
        if (!params.isActive()) return false
        if (!ensureProgram()) return false
        val upstreamError = GLES30.glGetError()
        if (upstreamError != GLES30.GL_NO_ERROR) {
            PLog.e(logTag, "Film texture skipped after upstream glError $upstreamError")
            return false
        }

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTextureId)
        GLES30.glUniform1i(inputLocation, 0)
        GLES30.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
        GLES30.glUniform1f(frameSeedLocation, frameSeed)
        GLES30.glUniform1f(grainAmountLocation, params.grainAmount.coerceIn(0f, 1f))
        GLES30.glUniform1f(grainSizeLocation, params.grainSize)
        GLES30.glUniform1f(vignetteLocation, params.vignette.coerceIn(0f, 1f))
        GLES30.glUniform1f(lightLeakLocation, params.lightLeak.coerceIn(0f, 1f))
        GLES30.glUniform1f(leakWarmLocation, params.lightLeakWarm.coerceIn(0f, 1f))
        GLES30.glUniform1f(softGlowLocation, params.softGlow.coerceIn(0f, 1f))
        GLES30.glUniform1f(sharpenLocation, params.sharpen.coerceIn(0f, 1f))
        drawQuad(programId)
        val error = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
        if (error != GLES30.GL_NO_ERROR) {
            PLog.e(logTag, "Film texture pass failed: glError=$error")
            return false
        }
        return true
    }

    fun resetAfterContextLoss() {
        programId = 0
        inputLocation = -1
        resolutionLocation = -1
        frameSeedLocation = -1
        outputTextureId = 0
        outputFramebufferId = 0
        outputWidth = 0
        outputHeight = 0
    }

    fun release() {
        GlUtils.deleteProgram(programId)
        programId = 0
        releaseOutput()
    }

    private fun ensureProgram(): Boolean {
        if (programId != 0) return true
        val vertexShader = GlUtils.compileShader(GLES30.GL_VERTEX_SHADER, Shaders.SIMPLE_VERTEX_SHADER)
        val fragmentShader = GlUtils.compileShader(GLES30.GL_FRAGMENT_SHADER, FilmTextureShaders.FRAGMENT)
        if (vertexShader == 0 || fragmentShader == 0) {
            if (vertexShader != 0) GLES30.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES30.glDeleteShader(fragmentShader)
            PLog.e(logTag, "Failed to compile film texture shaders")
            return false
        }
        programId = GlUtils.linkProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (programId == 0) {
            PLog.e(logTag, "Failed to link film texture program")
            return false
        }
        inputLocation = GLES30.glGetUniformLocation(programId, "uInputTexture")
        resolutionLocation = GLES30.glGetUniformLocation(programId, "uResolution")
        frameSeedLocation = GLES30.glGetUniformLocation(programId, "uFrameSeed")
        grainAmountLocation = GLES30.glGetUniformLocation(programId, "uGrainAmount")
        grainSizeLocation = GLES30.glGetUniformLocation(programId, "uGrainSize")
        vignetteLocation = GLES30.glGetUniformLocation(programId, "uVignette")
        lightLeakLocation = GLES30.glGetUniformLocation(programId, "uLightLeak")
        leakWarmLocation = GLES30.glGetUniformLocation(programId, "uLeakWarm")
        softGlowLocation = GLES30.glGetUniformLocation(programId, "uSoftGlow")
        sharpenLocation = GLES30.glGetUniformLocation(programId, "uSharpen")
        return true
    }

    private fun ensureOutput(width: Int, height: Int): Boolean {
        if (outputTextureId != 0 && outputFramebufferId != 0 &&
            outputWidth == width && outputHeight == height
        ) return true
        val maxTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        if (width <= 0 || height <= 0 || width > maxTextureSize[0] || height > maxTextureSize[0]) {
            PLog.e(logTag, "Film texture target ${width}x$height exceeds GL_MAX_TEXTURE_SIZE=${maxTextureSize[0]}")
            return false
        }
        releaseOutput()
        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        outputTextureId = texture[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTextureId)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        outputFramebufferId = framebuffer[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFramebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, outputTextureId, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            PLog.e(logTag, "Film texture framebuffer incomplete: status=$status")
            releaseOutput()
            return false
        }
        outputWidth = width
        outputHeight = height
        return true
    }

    private fun releaseOutput() {
        if (outputFramebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(outputFramebufferId), 0)
            outputFramebufferId = 0
        }
        if (outputTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
            outputTextureId = 0
        }
        outputWidth = 0
        outputHeight = 0
    }

    private companion object {
        @Suppress("unused")
        const val ENABLE_EPSILON = 0.001f
    }
}
