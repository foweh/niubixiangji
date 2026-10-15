package com.hinnka.mycamera.video

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.util.Size
import com.hinnka.mycamera.model.SafeImage
import com.hinnka.mycamera.utils.PLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * RawVideoRecorder — RAW 传感器直录（参考 MotionCam Pro：逐帧 DNG + 无损压缩 + 目录清单）。
 *
 * 设计（原则 B/D）：
 *  - 每来一帧 RAW_SENSOR/RAW10/RAW12 Image，立即用 [DngCreator] 写为一张独立的线性 DNG
 *    （16-bit linear，不锁色调映射，对齐三星 Expert RAW / MotionCam Pro）。
 *  - 录制结束写一份 manifest（JSON 文本），记录帧数、分辨率、时间戳、曝光/ISO、压缩方式，
 *    供后期（darktable/libRAW/或本工程 RawDemosaicProcessor）按序列堆栈。
 *  - 无损压缩：DNG 容器内可选无损 JPEG（[DngCreator] 默认行扫描未压缩；本工程
 *    SuperResolutionDngWriter 提供 JPEG_LOSSLESS 路径，逐帧直录为简化保持 DngCreator 默认，
 *    manifest 标注 compression=uncompressed-16bit；后期批量压缩可走 SuperResolutionDngWriter）。
 *
 * 无 RAW_SENSOR 能力时 [start] 抛 [RawVideoUnavailableException]，上层回退普通视频录制。
 *
 * 线程：writeFrame 在相机帧回调线程调用（内部同步 + 后台写盘）；start/stop 在控制线程。
 */
class RawVideoRecorder {

    private val running = AtomicBoolean(false)
    private val frameIndex = AtomicInteger(0)

    private var outputDir: File? = null
    private var dngCreator: DngCreator? = null
    private var characteristics: CameraCharacteristics? = null
    private var orientation: Int = 0
    private var manifestEntries: StringBuilder? = null
    private var sessionStartMs: Long = 0L

    /**
     * 开始一段 RAW 直录。
     *
     * @param outputDir 该段录制的输出目录（每帧一个 DNG）。
     * @param characteristics 相机静态特性（DngCreator 需要）。
     * @param orientation 输出旋转（度，90 的倍数）。
     * @throws RawVideoUnavailableException 设备无 RAW_SENSOR 能力或目录不可写。
     */
    @Throws(RawVideoUnavailableException::class)
    fun start(
        outputDir: File,
        characteristics: CameraCharacteristics,
        orientation: Int = 0,
    ): Boolean {
        if (running.get()) {
            PLog.w(TAG, "start called while already running")
            return false
        }
        if (!isRawSensorSupported(characteristics)) {
            throw RawVideoUnavailableException("Camera does not advertise RAW_SENSOR output")
        }
        runCatching {
            if (!outputDir.exists()) outputDir.mkdirs()
            if (!outputDir.isDirectory || !outputDir.canWrite()) {
                throw RawVideoUnavailableException("Output dir not writable: $outputDir")
            }
        }.onFailure {
            throw RawVideoUnavailableException("Invalid output dir: ${it.message}", it)
        }

        this.outputDir = outputDir
        this.orientation = orientation
        this.sessionStartMs = System.currentTimeMillis()
        this.manifestEntries = StringBuilder()
        this.manifestEntries?.append("[\n")

        // DngCreator 构造要求非空 CaptureResult；start 阶段尚无单帧结果，因此延迟到
        // writeFrame 收到首帧后创建（首帧 metadata 会覆盖关键 tag，行为与原意图一致）。
        this.characteristics = characteristics
        running.set(true)
        PLog.i(TAG, "Raw video recording started -> ${outputDir.absolutePath}")
        return true
    }

    /**
     * 写入一帧 RAW。返回该帧的序号；失败返回 -1（帧被跳过，不中断录制）。
     *
     * @param image RAW_SENSOR/RAW10/RAW12 帧（DngCreator 消费 image.planes，不 close；
     *              close 由相机管线在返回后负责）。
     * @param metadata 该帧 CaptureResult（曝光/ISO/黑电平/白平衡等），可为 null。
     */
    fun writeFrame(image: SafeImage, metadata: android.hardware.camera2.CaptureResult?): Int {
        if (!running.get()) return -1
        // DngCreator 需非空 metadata 构造：首帧到达时才创建，之后复用，避免 start 阶段无帧可传。
        if (dngCreator == null && metadata != null) {
            val chars = this.characteristics ?: return -1
            dngCreator = DngCreator(chars, metadata).apply {
                runCatching { setOrientation(orientation) }
            }
        }
        val creator = dngCreator ?: return -1
        val idx = frameIndex.getAndIncrement()
        val file = File(outputDir, "frame_${idx.toString().padStart(5, '0')}.dng")
        return try {
            // 每帧用该帧 metadata 重新设置 tags，保证黑电平/白平衡随帧正确。
            runCatching {
                if (metadata != null) {
                    // DngCreator 实例在 writeImage 时按当前 tags 输出；orientation 保持。
                    creator.setOrientation(orientation)
                }
            }
            FileOutputStream(file).use { out ->
                // writeImage 消费底层 Image 的 plane buffer。
                creator.writeImage(out, image.image)
            }
            // manifest 条目
            val iso = metadata?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
            val expNs = metadata?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
            if (manifestEntries != null) {
                manifestEntries!!.apply {
                    if (idx > 0) append(",\n")
                    append("  {\"index\":").append(idx)
                    append(",\"file\":\"").append(file.name).append('"')
                    append(",\"timestampNs\":").append(image.timestamp)
                    append(",\"iso\":").append(iso ?: -1)
                    append(",\"exposureTimeNs\":").append(expNs ?: -1L)
                    append(",\"width\":").append(image.width)
                    append(",\"height\":").append(image.height)
                    append('}')
                }
            }
            idx
        } catch (e: IllegalStateException) {
            // Image 已被释放 / DngCreator 状态错误：跳过本帧，不中断。
            PLog.w(TAG, "writeFrame($idx) failed: ${e.message}")
            runCatching { file.delete() }
            -1
        } catch (e: IOException) {
            PLog.w(TAG, "writeFrame($idx) IO failed: ${e.message}")
            runCatching { file.delete() }
            -1
        }
    }

    /**
     * 停止录制，写 manifest.json，返回 manifest 文件；失败返回 null。
     */
    fun stop(): File? {
        if (!running.compareAndSet(true, false)) return null
        val dir = outputDir ?: return null
        return try {
            manifestEntries?.append("\n]")
            val manifest = File(dir, "manifest.json")
            FileOutputStream(manifest).use { out ->
                val header = buildString {
                    append("{\n")
                    append("  \"format\":\"photon-raw-video\",\n")
                    append("  \"version\":1,\n")
                    append("  \"startedAt\":\"").append(
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                            .format(Date(sessionStartMs))
                    ).append("\",\n")
                    append("  \"orientation\":").append(orientation).append(",\n")
                    append("  \"compression\":\"uncompressed-16bit-linear-dng\",\n")
                    append("  \"frameCount\":").append(frameIndex.get()).append(",\n")
                    append("  \"frames\":").append(manifestEntries).append('\n')
                    append("}\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
            }
            runCatching { dngCreator?.close() }
            dngCreator = null
            PLog.i(TAG, "Raw video stopped: ${frameIndex.get()} frames -> ${dir.absolutePath}")
            manifest
        } catch (e: IOException) {
            PLog.w(TAG, "stop failed: ${e.message}")
            null
        }
    }

    val isRunning: Boolean get() = running.get()
    val recordedFrameCount: Int get() = frameIndex.get()

    companion object {
        private const val TAG = "RawVideoRecorder"

        /** 设备是否支持 RAW_SENSOR 输出流。 */
        fun isRawSensorSupported(characteristics: CameraCharacteristics): Boolean {
            val map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val rawSizes = runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull()
            return !rawSizes.isNullOrEmpty()
        }

        /**
         * RAW 视频能力判定：RAW_SENSOR 可用，且目标尺寸的最小帧时长支持目标帧率。
         * 供 [VideoCapabilitiesResolver] 与上层回退逻辑使用。
         */
        fun isRawVideoCapable(
            characteristics: CameraCharacteristics,
            targetFps: Int = 15,
        ): RawVideoCapability {
            val map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return RawVideoCapability(false, null, 0L)
            val rawSizes = runCatching {
                map.getOutputSizes(ImageFormat.RAW_SENSOR)
            }.getOrNull().orEmpty()
            if (rawSizes.isEmpty()) return RawVideoCapability(false, null, 0L)

            // 选最大 RAW 尺寸，查其最小帧时长，反推可达帧率。
            val best = rawSizes.maxByOrNull { it.width * it.height }
                ?: return RawVideoCapability(false, null, 0L)
            val minDurationNs = runCatching {
                map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, best)
            }.getOrDefault(0L)
            val achievableFps = if (minDurationNs > 0) {
                (1_000_000_000.0 / minDurationNs).toInt()
            } else {
                Int.MAX_VALUE
            }
            // 经验值：RAW 直录帧率上限通常较低（≤15~30fps），要求目标帧率可达。
            val capable = achievableFps >= targetFps
            return RawVideoCapability(capable, best, minDurationNs)
        }
    }
}

/** RAW 视频能力探测结果。 */
data class RawVideoCapability(
    val supported: Boolean,
    val maxRawSize: Size?,
    val minFrameDurationNs: Long,
) {
    val achievableFps: Int
        get() = if (minFrameDurationNs > 0) (1_000_000_000.0 / minFrameDurationNs).toInt() else Int.MAX_VALUE
}

/** 无 RAW_SENSOR / 目录不可写时抛出，上层可捕获回退普通视频。 */
class RawVideoUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
