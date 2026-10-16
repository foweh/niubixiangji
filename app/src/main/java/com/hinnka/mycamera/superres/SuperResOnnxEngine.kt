package com.hinnka.mycamera.superres

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime Mobile 推理封装（**只做会话与单 tile 前向**，tile 调度在 [SuperResPipeline]）。
 *
 * 依赖：`com.microsoft.onnxruntime:onnxruntime-android`（microsoft/onnxruntime，22083★，MIT，
 * 见 delivery/patches/superres_patch.md 的 DEPS 段）。库未加载成功时（ABI 不支持 / 依赖缺失）
 * [isRuntimeAvailable] 返回 false，调用方走 [SuperResAction.UNAVAILABLE_RUNTIME] 回退原图，
 * **绝不因推理库缺失而崩溃**。
 *
 * 线程/内存策略：
 *  - 会话 INTRA/INTER 线程数由 [SuperResConfig.threads] 决定（开放可调）；
 *  - BALANCED/QUALITY 档开启内存复用与全图优化；FAST 档用基础优化换更短启动时间；
 *  - 单次前向的输入/输出缓冲外置复用（避免每个 tile 反复分配大数组）。
 */
class SuperResOnnxEngine(config: SuperResConfig) : AutoCloseable, SuperResInference {

    /** clamp 后的配置快照，防止任务执行途中被改写导致尺寸不一致。 */
    val config: SuperResConfig = SuperResConfig.clamp(config)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loadError: String? = null

    /** 会话层实际使用的输入/输出张量名（优先会话自带名，回落模型元信息）。 */
    var inputName: String = "input"
        private set
    var outputName: String = "output"
        private set

    /** 推理输出通道数（用于区分「模型内含 PixelShuffle」与「3*s² 外置重排」两种导出形态）。 */
    var outputChannels: Int = 3
        private set

    /**
     * 运行时能力探测：库是否可加载。用独立 try/catch 覆盖 [UnsatisfiedLinkError]，
     * 因为该错误是 Error 而非 Exception，runCatching 抓不到。
     */
    fun isRuntimeAvailable(): Boolean {
        if (env != null) return true
        return try {
            env = OrtEnvironment.getEnvironment()
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "onnxruntime native lib missing: ${e.message}"
            PLog.w(TAG, "ONNX Runtime unavailable", e)
            false
        } catch (e: Throwable) {
            loadError = "onnxruntime init failed: ${e.message}"
            PLog.w(TAG, "ONNX Runtime init failed", e)
            false
        }
    }

    /** 最近一次失败原因（供 UI/日志展示）。 */
    fun lastError(): String? = loadError

    /**
     * 打开模型会话。失败返回 false 并记录 [lastError]，不抛异常。
     *
     * @param modelFile 已下载/导入的 .onnx 权重文件。
     */
    fun open(modelFile: File): Boolean {
        if (!isRuntimeAvailable()) return false
        close()
        if (!modelFile.isFile || modelFile.length() <= 0L) {
            loadError = "model file missing or empty: ${modelFile.name}"
            return false
        }
        val environment = env ?: return false
        return runCatching {
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(config.threads.coerceAtLeast(1))
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(
                when (config.qualityTier) {
                    SuperResQualityTier.FAST -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                    SuperResQualityTier.BALANCED -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                    SuperResQualityTier.QUALITY -> OrtSession.SessionOptions.OptLevel.ALL_OPT
                }
            )
            // NNAPI 是可选加速：部分设备/算子集不支持会抛异常，此时静默回落 CPU（不失败）。
            if (config.accelMode == SuperResAccelMode.NNAPI) {
                runCatching { options.addNnapi() }
                    .onFailure { PLog.w(TAG, "NNAPI unavailable, fall back to CPU", it) }
            }
            val s = environment.createSession(modelFile.readBytes(), options)
            session = s
            inputName = s.inputNames.firstOrNull() ?: inputName
            outputName = s.outputNames.firstOrNull() ?: outputName
            outputChannels = detectOutputChannels(s)
            PLog.i(TAG, "session ready: in=$inputName out=$outputName ch=$outputChannels")
            true
        }.onFailure {
            loadError = "open session failed: ${it.message}"
            PLog.w(TAG, "open session failed", it)
        }.getOrDefault(false)
    }

    /** 会话是否已就绪。 */
    val isReady: Boolean get() = session != null

    /**
     * 单 tile 前向：NCHW 平面 RGB（0..1）→ 输出浮点缓冲。
     *
     * 使用 `FloatBuffer`/`LongBuffer` 自有内存创建张量，避免 Java 堆上再拷一份大数组；
     * 异常一律返回 null，由上层按「该 tile 保留原图上采样结果」降级，整图仍能出片。
     *
     * @return 模型原始输出（长度与导出形态相关）；失败返回 null。
     */
    override fun runTile(inputPlanar: FloatArray, width: Int, height: Int): FloatArray? {
        val s = session ?: return null
        if (width <= 0 || height <= 0) return null
        val expected = 3L * width.toLong() * height.toLong()
        if (inputPlanar.size < expected) {
            loadError = "input buffer too small: ${inputPlanar.size} < $expected"
            return null
        }
        val environment = env ?: return null
        return runCatching {
            val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
            val inputBuffer = FloatBuffer.wrap(inputPlanar, 0, expected.toInt())
            val tensor = OnnxTensor.createTensor(environment, inputBuffer, shape)
            tensor.use { input ->
                s.run(mapOf(inputName to input)).use { result ->
                    val out = result.get(0) as? OnnxTensor ?: return@use null
                    val buf = out.floatBuffer
                    val arr = FloatArray(buf.remaining())
                    buf.get(arr)
                    arr
                }
            }
        }.onFailure {
            loadError = "run tile failed: ${it.message}"
            PLog.w(TAG, "run tile failed", it)
        }.getOrNull()
    }

    /**
     * 图像修复（LaMa 思路）单 tile 前向：输入 [RGB, mask] 两通道张量，输出补全后的 RGB。
     * LaMa 系导出常用 4 通道（RGB+mask）单输入，这里按该形态喂入。
     */
    override fun runInpaintTile(
        inputPlanar: FloatArray,
        mask: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray? {
        val s = session ?: return null
        if (width <= 0 || height <= 0) return null
        val px = width.toLong() * height.toLong()
        val environment = env ?: return null
        return runCatching {
            // 4 通道：R,G,B,mask 顺序拼接（与 LaMa 官方导出的输入约定一致）。
            val merged = FloatArray((px * 4L).toInt())
            val plane = px.toInt()
            System.arraycopy(inputPlanar, 0, merged, 0, minOf(plane * 3, inputPlanar.size))
            for (i in 0 until plane) {
                merged[plane * 3 + i] = if (i < mask.size) mask[i] else 0f
            }
            val shape = longArrayOf(1, 4, height.toLong(), width.toLong())
            val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(merged), shape)
            tensor.use { input ->
                s.run(mapOf(inputName to input)).use { result ->
                    val out = result.get(0) as? OnnxTensor ?: return@use null
                    val buf = out.floatBuffer
                    val arr = FloatArray(buf.remaining())
                    buf.get(arr)
                    arr
                }
            }
        }.onFailure {
            loadError = "run inpaint tile failed: ${it.message}"
            PLog.w(TAG, "run inpaint tile failed", it)
        }.getOrNull()
    }

    private fun detectOutputChannels(s: OrtSession): Int = runCatching {
        val node = s.outputInfo.values.firstOrNull() ?: return@runCatching 3
        val info = runCatching { node.info as? ai.onnxruntime.TensorInfo }.getOrNull()
            ?: return@runCatching 3
        val dims = info.shape
        if (dims == null || dims.size != 4) return@runCatching 3
        // 动态维（-1/0）不落在通道区间，会被下面的区间判断自然排除。
        val d1 = dims[1].toInt()
        val d3 = dims[3].toInt()
        when {
            d1 in 3..64 -> d1
            d3 in 3..64 -> d3
            else -> 3
        }
    }.getOrDefault(3)

    override fun close() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val TAG = "SuperResOnnxEngine"
    }
}
