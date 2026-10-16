package com.hinnka.mycamera.superres

/**
 * [SuperResConfig] ↔ 扁平字符串表的纯编解码。
 *
 * 用途：DataStore / SharedPreferences / 命令行参数都能承载 `Map<String,String>`，
 * 把序列化抽成纯函数后即可 JVM 单测（不依赖 Android），也便于集成阶段把配置
 * 落到既有设置体系里。
 *
 * 约定：枚举用 `name` 存；缺字段回落到默认值；值非法（非数字/枚举名错）时**忽略该字段**
 * 并记录 key，绝不抛异常 —— 用户误改配置文件不会导致启动崩溃。
 */
object SuperResConfigCodec {

    const val KEY_ENABLED = "enabled"
    const val KEY_SCALE = "scale"
    const val KEY_TILE_SIZE = "tileSize"
    const val KEY_TILE_OVERLAP = "tileOverlap"
    const val KEY_STRENGTH = "strength"
    const val KEY_QUALITY_TIER = "qualityTier"
    const val KEY_ACCEL_MODE = "accelMode"
    const val KEY_THREADS = "threads"
    const val KEY_STYLE_ENABLED = "enableStyleTransfer"
    const val KEY_STYLE_STRENGTH = "styleTransferStrength"
    const val KEY_STYLE_MODEL_ID = "styleModelId"
    const val KEY_INPAINT_ENABLED = "enableInpainting"
    const val KEY_INPAINT_FEATHER = "inpaintFeatherPx"
    const val KEY_INPAINT_THRESHOLD = "inpaintMaskThreshold"
    const val KEY_MAX_WORKING_PIXELS = "maxWorkingPixels"
    const val KEY_MEMORY_BUDGET = "memoryBudgetBytes"
    const val KEY_TILES_PER_CHUNK = "maxTilesPerChunk"
    const val KEY_KEEP_ALPHA = "keepOutputAlpha"
    const val KEY_DEGRADE = "degradeInsteadOfFail"
    const val KEY_JPEG_QUALITY = "jpegQuality"

    /** 编码：始终写出全部字段，便于人工查看与运维比对。 */
    fun encode(config: SuperResConfig): Map<String, String> {
        val c = SuperResConfig.clamp(config)
        return linkedMapOf(
            KEY_ENABLED to c.enabled.toString(),
            KEY_SCALE to c.scale.toString(),
            KEY_TILE_SIZE to c.tileSize.toString(),
            KEY_TILE_OVERLAP to c.tileOverlap.toString(),
            KEY_STRENGTH to c.strength.toString(),
            KEY_QUALITY_TIER to c.qualityTier.name,
            KEY_ACCEL_MODE to c.accelMode.name,
            KEY_THREADS to c.threads.toString(),
            KEY_STYLE_ENABLED to c.enableStyleTransfer.toString(),
            KEY_STYLE_STRENGTH to c.styleTransferStrength.toString(),
            KEY_STYLE_MODEL_ID to (c.styleModelId ?: ""),
            KEY_INPAINT_ENABLED to c.enableInpainting.toString(),
            KEY_INPAINT_FEATHER to c.inpaintFeatherPx.toString(),
            KEY_INPAINT_THRESHOLD to c.inpaintMaskThreshold.toString(),
            KEY_MAX_WORKING_PIXELS to c.maxWorkingPixels.toString(),
            KEY_MEMORY_BUDGET to c.memoryBudgetBytes.toString(),
            KEY_TILES_PER_CHUNK to c.maxTilesPerChunk.toString(),
            KEY_KEEP_ALPHA to c.keepOutputAlpha.toString(),
            KEY_DEGRADE to c.degradeInsteadOfFail.toString(),
            KEY_JPEG_QUALITY to c.jpegQuality.toString(),
        )
    }

    /** 解码 + clamp。非法值被忽略（回落默认）并计入 [SuperResConfigDecodeResult.ignoredKeys]。 */
    fun decode(map: Map<String, String>): SuperResConfigDecodeResult {
        val ignored = ArrayList<String>(4)
        val d = SuperResConfig()

        fun bool(key: String, def: Boolean): Boolean {
            val raw = map[key] ?: return def
            return when (raw.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> { ignored.add(key); def }
            }
        }

        fun int(key: String, def: Int): Int {
            val raw = map[key] ?: return def
            val v = raw.trim().toIntOrNull()
            if (v == null) { ignored.add(key); return def }
            return v
        }

        fun long(key: String, def: Long): Long {
            val raw = map[key] ?: return def
            val v = raw.trim().toLongOrNull()
            if (v == null) { ignored.add(key); return def }
            return v
        }

        fun float(key: String, def: Float): Float {
            val raw = map[key] ?: return def
            val v = raw.trim().toFloatOrNull()
            if (v == null || !v.isFinite()) { ignored.add(key); return def }
            return v
        }

        val tier = map[KEY_QUALITY_TIER]?.let { raw ->
            SuperResQualityTier.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: run { ignored.add(KEY_QUALITY_TIER); null }
        } ?: d.qualityTier

        val accel = map[KEY_ACCEL_MODE]?.let { raw ->
            SuperResAccelMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: run { ignored.add(KEY_ACCEL_MODE); null }
        } ?: d.accelMode

        val decoded = SuperResConfig(
            enabled = bool(KEY_ENABLED, d.enabled),
            scale = int(KEY_SCALE, d.scale),
            tileSize = int(KEY_TILE_SIZE, d.tileSize),
            tileOverlap = int(KEY_TILE_OVERLAP, d.tileOverlap),
            strength = float(KEY_STRENGTH, d.strength),
            qualityTier = tier,
            accelMode = accel,
            threads = int(KEY_THREADS, d.threads),
            enableStyleTransfer = bool(KEY_STYLE_ENABLED, d.enableStyleTransfer),
            styleTransferStrength = float(KEY_STYLE_STRENGTH, d.styleTransferStrength),
            styleModelId = map[KEY_STYLE_MODEL_ID]?.trim()?.takeIf { it.isNotEmpty() },
            enableInpainting = bool(KEY_INPAINT_ENABLED, d.enableInpainting),
            inpaintFeatherPx = int(KEY_INPAINT_FEATHER, d.inpaintFeatherPx),
            inpaintMaskThreshold = float(KEY_INPAINT_THRESHOLD, d.inpaintMaskThreshold),
            maxWorkingPixels = long(KEY_MAX_WORKING_PIXELS, d.maxWorkingPixels),
            memoryBudgetBytes = long(KEY_MEMORY_BUDGET, d.memoryBudgetBytes),
            maxTilesPerChunk = int(KEY_TILES_PER_CHUNK, d.maxTilesPerChunk),
            keepOutputAlpha = bool(KEY_KEEP_ALPHA, d.keepOutputAlpha),
            degradeInsteadOfFail = bool(KEY_DEGRADE, d.degradeInsteadOfFail),
            jpegQuality = int(KEY_JPEG_QUALITY, d.jpegQuality),
        )
        val report = SuperResConfig.clampWithReport(decoded)
        return SuperResConfigDecodeResult(report.config, ignored, report.corrected)
    }

    /** 往返（encode → decode）应保持配置不变（已 clamp 的配置为前提）。 */
    fun roundTrip(config: SuperResConfig): SuperResConfig = decode(encode(config)).config
}

/** 解码结果：修正后的配置 + 被忽略的非法 key + 被 clamp 修正的字段名。 */
data class SuperResConfigDecodeResult(
    val config: SuperResConfig,
    val ignoredKeys: List<String> = emptyList(),
    val correctedFields: List<String> = emptyList(),
) {
    val hadCorrections: Boolean get() = ignoredKeys.isNotEmpty() || correctedFields.isNotEmpty()
}
