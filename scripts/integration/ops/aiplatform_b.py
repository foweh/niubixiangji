# -*- coding: utf-8 -*-
"""aiplatform_patch 集成操作（b）：H2 CameraViewModel F1 生效链路 / H3 ServerEnhancementClient 平台化。"""

P = "delivery/patches/aiplatform_patch.md"
VM = "app/src/main/java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt"
SEC = "app/src/main/java/com/hinnka/mycamera/computational/ServerEnhancementClient.kt"

OPS = [
    # ================= H2：堆栈出片后触发服务器增强（F1） =================
    # 锚点已按 review_patch 的插入（starTrails 两行）重新定位（冲突消解）。
    ("replace", VM, '''                    psStackAlign = stackAlign,
                    starTrailsEnabled = starTrailsEnabled,
                    trailAccumulationMode = trailAccumulationMode,
                )
            }
            PLog.d(TAG, "Image saved: $photoId, LUT: $lutIdToSave, Frame: $frameIdToSave")''',
     '''                    psStackAlign = stackAlign,
                    starTrailsEnabled = starTrailsEnabled,
                    trailAccumulationMode = trailAccumulationMode,
                )
            }
            // === AI 平台层接线（aiplatform_patch.md）：解决审计 F1「零调用方」===
            // 出片之后才决定是否上送服务器增强；未配置/失败只提示 + 保留本地结果，不阻塞出片。
            maybeEnqueueServerEnhancement(photoId)
            PLog.d(TAG, "Image saved: $photoId, LUT: $lutIdToSave, Frame: $frameIdToSave")''', 1),

    ("x_insert_before", P, 348, VM, "    fun fetchAvailableAIModels() {", 1),

    ("insert_after", VM, "    private val _imageSavedEvent = MutableSharedFlow<String?>()",
     '''    /** AI 平台层接线：本进程内提交过的后台 AI 任务（便于排查 / 后续统一取消）。 */
    private val backgroundAiJobs = mutableListOf<BgJob>()''', 1),

    ("insert_after", VM, "import com.hinnka.mycamera.gallery.GalleryManager", '''import android.widget.Toast
import com.hinnka.mycamera.aiplatform.AiNotices
import com.hinnka.mycamera.aiplatform.AiPlatform
import com.hinnka.mycamera.computational.BgJob
import java.io.File''', 1),

    # ================= H3：配置化 + 鉴权 + 校验 + 归因 =================
    ("replace", SEC, '''    private val readTimeoutMs: Int = 120_000,
) {''', '''    private val readTimeoutMs: Int = 120_000,
    /** AI 平台层配置源（aiplatform/）；非空时端点/密钥/超时/重试全部读配置，本类不再自带。 */
    private val aiConfigStore: com.hinnka.mycamera.aiplatform.AiConfigStore? = null,
) {''', 1),

    # 平台化执行入口 + 入队入口（插在 companion object 之前）
    ("x_insert_before", P, 469, SEC, "    companion object {", 1),

    # 扩展函数 executeWithFallback 需显式 import（补丁未给，属集成阶段编译修复）
    ("insert_after", SEC, "import com.hinnka.mycamera.utils.PLog",
     "import com.hinnka.mycamera.aiplatform.executeWithFallback", 1),

    # 手工路径的响应校验改为复用平台层校验器（与 review_patch 的分块可取消读取合并）
    ("replace", SEC, '''                val bytes = buffer.toByteArray()
                val invalidReason = validateEnhancedBody(contentType, bytes)
                if (invalidReason != null) {
                    PLog.w(TAG, "Server enhance response rejected: $invalidReason")
                    _state.set(State.FAILED)
                    lastError = invalidReason
                    return null
                }
                FileOutputStream(outputFile).use { it.write(bytes) }''',
     '''                val bytes = buffer.toByteArray()
                // M3（aiplatform）：复用平台层校验器（Content-Type 白名单 + 魔数 + 体积），单一真值源。
                val validation = com.hinnka.mycamera.aiplatform.AiResponseValidator.validate(
                    head = bytes.copyOfRange(0, minOf(bytes.size, 16)),
                    actualBytes = bytes.size.toLong(),
                    contentType = contentType,
                    expectedContentTypes = com.hinnka.mycamera.aiplatform.AiResponseValidator.IMAGE_CONTENT_TYPES,
                    minBytes = 1L,
                )
                check(validation == com.hinnka.mycamera.aiplatform.AiResponseValidation.Ok) {
                    "response rejected: $validation (contentType=$contentType, size=${bytes.size})"
                }
                FileOutputStream(outputFile).use { it.write(bytes) }''', 1),

    # 平台校验已接管 → 移除 review 阶段的自建校验（避免两套校验真值源 + 死代码）
    ("delete", SEC, '''    /**
     * 最小校验：响应类型 / 魔数 / 体积。防止把 200 的错误体（HTML/JSON）当图片落盘。
     * @return null 表示通过；否则返回失败原因。
     */
    private fun validateEnhancedBody(contentType: String?, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "empty body"
        if (bytes.size > MAX_OUTPUT_BYTES) return "oversized body ${bytes.size}B"
        val magicOk = (bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) || // PNG
            (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || // JPEG
            (bytes.size >= 2 && bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte()) || // TIFF/DNG (II)
            (bytes.size >= 2 && bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte())    // TIFF/DNG (MM)
        if (!magicOk) return "unexpected magic"
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mediaType != null && mediaType.isNotEmpty() &&
            !mediaType.startsWith("image/") && mediaType != "application/octet-stream"
        ) {
            return "unexpected content-type $mediaType"
        }
        return null
    }

''', 1),
    ("delete", SEC, "        private const val MAX_OUTPUT_BYTES = 512L * 1024L * 1024L\n", 1),
]
