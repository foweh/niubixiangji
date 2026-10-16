package com.hinnka.mycamera.viewfinder

import android.content.Context
import com.hinnka.mycamera.utils.PLog
import java.io.File

/**
 * 外部贴纸素材包配置（**可配置下载或内置**）。
 *
 * 默认 `enabled = false`，即只使用 [FaceStickerCatalog] 的内置矢量贴纸：
 * 无网络请求、无权限、无素材授权风险。开启后从 [downloadUrlTemplate] 指向的地址获取素材，
 * 落盘到应用私有目录 `filesDir/[directory]`；任何失败都静默回落到内置贴纸（不崩溃、不弹错）。
 *
 * @param enabled 是否启用外部素材
 * @param downloadUrlTemplate 素材地址模板，`{id}` 会被贴纸 id 替换；留空表示不下载
 * @param directory 素材落盘目录名（相对 filesDir）
 * @param allowExternalAssets 是否允许读取已落盘的外部素材
 * @param maxAssetBytes 单个素材体积上限，默认 8MB
 */
data class StickerPackConfig(
    val enabled: Boolean = false,
    val downloadUrlTemplate: String = "",
    val directory: String = DEFAULT_PACK_DIRECTORY,
    val allowExternalAssets: Boolean = true,
    val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
) {
    companion object {
        const val DEFAULT_PACK_DIRECTORY = "viewfinder_stickers"
        const val DEFAULT_MAX_ASSET_BYTES = 8L * 1024L * 1024L
    }
}

/** 贴纸素材解析结果。 */
sealed interface ResolvedSticker {
    /** 使用内置矢量形态。 */
    data class BuiltIn(val definition: FaceStickerDefinition) : ResolvedSticker

    /** 使用外部位图素材。 */
    data class External(val definition: FaceStickerDefinition, val file: File) : ResolvedSticker
}

/**
 * 贴纸素材解析：优先外部已落盘素材，其次内置矢量贴纸。
 *
 * 只在磁盘上做一次 `File.exists()/length()` 判断，不做网络请求；
 * 下载本身由外部下载器（或后续模块）按 [StickerPackConfig] 完成，本模块只负责解析与兜底。
 */
object StickerPackResolver {

    private const val TAG = "ViewfinderSticker"

    /** 已支持的外部素材扩展名（按优先级）。 */
    private val SUPPORTED_EXTENSIONS = listOf("png", "webp", "jpg")

    fun resolve(
        context: Context?,
        stickerId: String,
        config: StickerPackConfig = StickerPackConfig(),
    ): ResolvedSticker {
        val definition = FaceStickerCatalog.find(stickerId)
        if (context == null || !config.enabled || !config.allowExternalAssets) {
            return ResolvedSticker.BuiltIn(definition)
        }
        return try {
            val file = findInstalledAsset(context, stickerId, config)
            if (file == null) {
                ResolvedSticker.BuiltIn(definition)
            } else {
                ResolvedSticker.External(
                    definition = definition.copy(variant = FaceStickerVariant.EXTERNAL_BITMAP),
                    file = file,
                )
            }
        } catch (e: Exception) {
            PLog.w(TAG, "外部贴纸素材解析失败，回落内置贴纸", e)
            ResolvedSticker.BuiltIn(definition)
        }
    }

    /** 素材落盘目录（绝对路径）；目录不可用时返回 null。 */
    fun assetDirectory(context: Context, config: StickerPackConfig): File? = try {
        val safeName = sanitize(config.directory)
        File(context.filesDir, safeName).apply { mkdirs() }.takeIf { it.isDirectory }
    } catch (e: Exception) {
        PLog.w(TAG, "贴纸素材目录不可用", e)
        null
    }

    /** 展开下载地址模板；模板为空或缺 `{id}` 时返回 null（表示不下载）。 */
    fun expandDownloadUrl(config: StickerPackConfig, stickerId: String): String? {
        if (!config.enabled) return null
        val template = config.downloadUrlTemplate.trim()
        if (template.isEmpty() || !template.contains(URL_PLACEHOLDER)) return null
        return template.replace(URL_PLACEHOLDER, sanitize(stickerId))
    }

    private fun findInstalledAsset(
        context: Context,
        stickerId: String,
        config: StickerPackConfig,
    ): File? {
        val directory = assetDirectory(context, config) ?: return null
        for (extension in SUPPORTED_EXTENSIONS) {
            val candidate = File(directory, "${sanitize(stickerId)}.$extension")
            if (candidate.isFile && candidate.length() in 1..config.maxAssetBytes) {
                return candidate
            }
        }
        return null
    }

    private fun sanitize(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "sticker" }

    private const val URL_PLACEHOLDER = "{id}"
}
