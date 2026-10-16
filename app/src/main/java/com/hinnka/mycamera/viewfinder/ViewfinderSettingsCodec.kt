package com.hinnka.mycamera.viewfinder

/**
 * [ViewfinderSettings] 的纯字符串编解码（DataStore / SharedPreferences 通用）。
 *
 * 用途（PATTERN_SPEC §6 开放度 ②）：参数既可从 UI 改，也可从配置键读写。
 * 完整键位清单见 `delivery/patches/viewfinder_patch.md` 的 CONFIG 段。
 *
 * 解码始终走 [ViewfinderSettings.clamp]，因此**任何脏数据都不会导致崩溃**，
 * 非法值会被修正并通过 `corrections` 上报，便于日志留痕。
 */
object ViewfinderSettingsCodec {

    const val KEY_ZEBRA_ENABLED = "viewfinder.zebra.enabled"
    const val KEY_ZEBRA_THRESHOLD_PERCENT = "viewfinder.zebra.threshold_percent"
    const val KEY_ZEBRA_STRIPE_WIDTH = "viewfinder.zebra.stripe_width_px"
    const val KEY_ZEBRA_STRIPE_DUTY = "viewfinder.zebra.stripe_duty"
    const val KEY_ZEBRA_STRIPE_ANGLE = "viewfinder.zebra.stripe_angle_deg"
    const val KEY_ZEBRA_COLOR_R = "viewfinder.zebra.color_r"
    const val KEY_ZEBRA_COLOR_G = "viewfinder.zebra.color_g"
    const val KEY_ZEBRA_COLOR_B = "viewfinder.zebra.color_b"
    const val KEY_ZEBRA_ALPHA = "viewfinder.zebra.stripe_alpha"
    const val KEY_ZEBRA_METRIC = "viewfinder.zebra.metric"

    const val KEY_HISTOGRAM_ENABLED = "viewfinder.histogram.enabled"
    const val KEY_HISTOGRAM_BINS = "viewfinder.histogram.bin_count"
    const val KEY_HISTOGRAM_INTERVAL_MS = "viewfinder.histogram.sample_interval_ms"
    const val KEY_HISTOGRAM_ANCHOR = "viewfinder.histogram.anchor"
    const val KEY_HISTOGRAM_MARGIN = "viewfinder.histogram.margin_fraction"
    const val KEY_HISTOGRAM_WIDTH_FRACTION = "viewfinder.histogram.width_fraction"
    const val KEY_HISTOGRAM_HEIGHT_FRACTION = "viewfinder.histogram.height_fraction"
    const val KEY_HISTOGRAM_ALPHA = "viewfinder.histogram.alpha"
    const val KEY_HISTOGRAM_SMOOTHING = "viewfinder.histogram.smoothing"
    const val KEY_HISTOGRAM_SHOW_LUMA = "viewfinder.histogram.show_luma"
    const val KEY_HISTOGRAM_SHOW_RED = "viewfinder.histogram.show_red"
    const val KEY_HISTOGRAM_SHOW_GREEN = "viewfinder.histogram.show_green"
    const val KEY_HISTOGRAM_SHOW_BLUE = "viewfinder.histogram.show_blue"

    const val KEY_PEAKING_ENABLED = "viewfinder.peaking.enabled"
    const val KEY_PEAKING_SENSITIVITY = "viewfinder.peaking.sensitivity"
    const val KEY_PEAKING_THRESHOLD_OVERRIDE = "viewfinder.peaking.threshold_override"
    const val KEY_PEAKING_COLOR_R = "viewfinder.peaking.color_r"
    const val KEY_PEAKING_COLOR_G = "viewfinder.peaking.color_g"
    const val KEY_PEAKING_COLOR_B = "viewfinder.peaking.color_b"
    const val KEY_PEAKING_MIX = "viewfinder.peaking.mix_strength"
    const val KEY_PEAKING_STEP_SCALE = "viewfinder.peaking.step_scale"

    const val KEY_STICKER_ENABLED = "viewfinder.sticker.enabled"
    const val KEY_STICKER_ID = "viewfinder.sticker.id"
    const val KEY_STICKER_ANCHOR = "viewfinder.sticker.anchor_mode"
    const val KEY_STICKER_SCALE = "viewfinder.sticker.scale"
    const val KEY_STICKER_OFFSET_X = "viewfinder.sticker.offset_x"
    const val KEY_STICKER_OFFSET_Y = "viewfinder.sticker.offset_y"
    const val KEY_STICKER_MIRROR = "viewfinder.sticker.mirror"
    const val KEY_STICKER_ALPHA = "viewfinder.sticker.alpha"
    const val KEY_STICKER_MAX_FACES = "viewfinder.sticker.max_faces"
    const val KEY_STICKER_INTERVAL_MS = "viewfinder.sticker.detect_interval_ms"
    const val KEY_STICKER_SMOOTHING = "viewfinder.sticker.smoothing"
    const val KEY_STICKER_MODEL_PATH = "viewfinder.sticker.model_asset_path"
    const val KEY_STICKER_MODEL_URL = "viewfinder.sticker.model_download_url"
    const val KEY_STICKER_PACK_DIR = "viewfinder.sticker.pack_directory"
    const val KEY_STICKER_PACK_URL = "viewfinder.sticker.pack_download_url"

    /** 全部键位（供设置页遍历与自检核对）。 */
    val ALL_KEYS: List<String> = listOf(
        KEY_ZEBRA_ENABLED, KEY_ZEBRA_THRESHOLD_PERCENT, KEY_ZEBRA_STRIPE_WIDTH,
        KEY_ZEBRA_STRIPE_DUTY, KEY_ZEBRA_STRIPE_ANGLE, KEY_ZEBRA_COLOR_R, KEY_ZEBRA_COLOR_G,
        KEY_ZEBRA_COLOR_B, KEY_ZEBRA_ALPHA, KEY_ZEBRA_METRIC,
        KEY_HISTOGRAM_ENABLED, KEY_HISTOGRAM_BINS, KEY_HISTOGRAM_INTERVAL_MS,
        KEY_HISTOGRAM_ANCHOR, KEY_HISTOGRAM_MARGIN, KEY_HISTOGRAM_WIDTH_FRACTION,
        KEY_HISTOGRAM_HEIGHT_FRACTION, KEY_HISTOGRAM_ALPHA, KEY_HISTOGRAM_SMOOTHING,
        KEY_HISTOGRAM_SHOW_LUMA, KEY_HISTOGRAM_SHOW_RED, KEY_HISTOGRAM_SHOW_GREEN,
        KEY_HISTOGRAM_SHOW_BLUE,
        KEY_PEAKING_ENABLED, KEY_PEAKING_SENSITIVITY, KEY_PEAKING_THRESHOLD_OVERRIDE,
        KEY_PEAKING_COLOR_R, KEY_PEAKING_COLOR_G, KEY_PEAKING_COLOR_B, KEY_PEAKING_MIX,
        KEY_PEAKING_STEP_SCALE,
        KEY_STICKER_ENABLED, KEY_STICKER_ID, KEY_STICKER_ANCHOR, KEY_STICKER_SCALE,
        KEY_STICKER_OFFSET_X, KEY_STICKER_OFFSET_Y, KEY_STICKER_MIRROR, KEY_STICKER_ALPHA,
        KEY_STICKER_MAX_FACES, KEY_STICKER_INTERVAL_MS, KEY_STICKER_SMOOTHING,
        KEY_STICKER_MODEL_PATH, KEY_STICKER_MODEL_URL, KEY_STICKER_PACK_DIR,
        KEY_STICKER_PACK_URL,
    )

    fun encode(settings: ViewfinderSettings): Map<String, String> = mapOf(
        KEY_ZEBRA_ENABLED to settings.zebra.enabled.toString(),
        KEY_ZEBRA_THRESHOLD_PERCENT to settings.zebra.thresholdPercent.toString(),
        KEY_ZEBRA_STRIPE_WIDTH to settings.zebra.stripeWidthPx.toString(),
        KEY_ZEBRA_STRIPE_DUTY to settings.zebra.stripeDuty.toString(),
        KEY_ZEBRA_STRIPE_ANGLE to settings.zebra.stripeAngleDeg.toString(),
        KEY_ZEBRA_COLOR_R to settings.zebra.stripeColorR.toString(),
        KEY_ZEBRA_COLOR_G to settings.zebra.stripeColorG.toString(),
        KEY_ZEBRA_COLOR_B to settings.zebra.stripeColorB.toString(),
        KEY_ZEBRA_ALPHA to settings.zebra.stripeAlpha.toString(),
        KEY_ZEBRA_METRIC to settings.zebra.metric.name,
        KEY_HISTOGRAM_ENABLED to settings.histogram.enabled.toString(),
        KEY_HISTOGRAM_BINS to settings.histogram.binCount.toString(),
        KEY_HISTOGRAM_INTERVAL_MS to settings.histogram.sampleIntervalMs.toString(),
        KEY_HISTOGRAM_ANCHOR to settings.histogram.anchor.name,
        KEY_HISTOGRAM_MARGIN to settings.histogram.marginFraction.toString(),
        KEY_HISTOGRAM_WIDTH_FRACTION to settings.histogram.widthFraction.toString(),
        KEY_HISTOGRAM_HEIGHT_FRACTION to settings.histogram.heightFraction.toString(),
        KEY_HISTOGRAM_ALPHA to settings.histogram.alpha.toString(),
        KEY_HISTOGRAM_SMOOTHING to settings.histogram.smoothing.toString(),
        KEY_HISTOGRAM_SHOW_LUMA to settings.histogram.showLuma.toString(),
        KEY_HISTOGRAM_SHOW_RED to settings.histogram.showRed.toString(),
        KEY_HISTOGRAM_SHOW_GREEN to settings.histogram.showGreen.toString(),
        KEY_HISTOGRAM_SHOW_BLUE to settings.histogram.showBlue.toString(),
        KEY_PEAKING_ENABLED to settings.peaking.enabled.toString(),
        KEY_PEAKING_SENSITIVITY to settings.peaking.sensitivity.toString(),
        KEY_PEAKING_THRESHOLD_OVERRIDE to settings.peaking.edgeThresholdOverride.toString(),
        KEY_PEAKING_COLOR_R to settings.peaking.colorR.toString(),
        KEY_PEAKING_COLOR_G to settings.peaking.colorG.toString(),
        KEY_PEAKING_COLOR_B to settings.peaking.colorB.toString(),
        KEY_PEAKING_MIX to settings.peaking.mixStrength.toString(),
        KEY_PEAKING_STEP_SCALE to settings.peaking.stepScale.toString(),
        KEY_STICKER_ENABLED to settings.sticker.enabled.toString(),
        KEY_STICKER_ID to settings.sticker.stickerId,
        KEY_STICKER_ANCHOR to settings.sticker.anchorMode.name,
        KEY_STICKER_SCALE to settings.sticker.scale.toString(),
        KEY_STICKER_OFFSET_X to settings.sticker.offsetX.toString(),
        KEY_STICKER_OFFSET_Y to settings.sticker.offsetY.toString(),
        KEY_STICKER_MIRROR to settings.sticker.mirror.toString(),
        KEY_STICKER_ALPHA to settings.sticker.alpha.toString(),
        KEY_STICKER_MAX_FACES to settings.sticker.maxFaces.toString(),
        KEY_STICKER_INTERVAL_MS to settings.sticker.detectIntervalMs.toString(),
        KEY_STICKER_SMOOTHING to settings.sticker.smoothing.toString(),
        KEY_STICKER_MODEL_PATH to settings.sticker.modelAssetPath,
        KEY_STICKER_MODEL_URL to settings.sticker.modelDownloadUrl,
        KEY_STICKER_PACK_DIR to settings.sticker.packDirectory,
        KEY_STICKER_PACK_URL to settings.sticker.packDownloadUrl,
    )

    /**
     * 从键值表解码；缺失键用默认值，脏数据被 clamp 修正。
     *
     * @return 修正后的设置 + 修正说明（空表表示全部合法）
     */
    fun decode(values: Map<String, String>): ClampResult<ViewfinderSettings> {
        val defaults = ViewfinderSettings()
        val raw = ViewfinderSettings(
            zebra = defaults.zebra.copy(
                enabled = values.boolean(KEY_ZEBRA_ENABLED, defaults.zebra.enabled),
                thresholdPercent = values.float(KEY_ZEBRA_THRESHOLD_PERCENT, defaults.zebra.thresholdPercent),
                stripeWidthPx = values.float(KEY_ZEBRA_STRIPE_WIDTH, defaults.zebra.stripeWidthPx),
                stripeDuty = values.float(KEY_ZEBRA_STRIPE_DUTY, defaults.zebra.stripeDuty),
                stripeAngleDeg = values.float(KEY_ZEBRA_STRIPE_ANGLE, defaults.zebra.stripeAngleDeg),
                stripeColorR = values.float(KEY_ZEBRA_COLOR_R, defaults.zebra.stripeColorR),
                stripeColorG = values.float(KEY_ZEBRA_COLOR_G, defaults.zebra.stripeColorG),
                stripeColorB = values.float(KEY_ZEBRA_COLOR_B, defaults.zebra.stripeColorB),
                stripeAlpha = values.float(KEY_ZEBRA_ALPHA, defaults.zebra.stripeAlpha),
                metric = values.enum(KEY_ZEBRA_METRIC, defaults.zebra.metric) { ZebraMetric.valueOf(it) },
            ),
            histogram = defaults.histogram.copy(
                enabled = values.boolean(KEY_HISTOGRAM_ENABLED, defaults.histogram.enabled),
                binCount = values.int(KEY_HISTOGRAM_BINS, defaults.histogram.binCount),
                sampleIntervalMs = values.long(KEY_HISTOGRAM_INTERVAL_MS, defaults.histogram.sampleIntervalMs),
                anchor = values.enum(KEY_HISTOGRAM_ANCHOR, defaults.histogram.anchor) { HistogramAnchor.valueOf(it) },
                marginFraction = values.float(KEY_HISTOGRAM_MARGIN, defaults.histogram.marginFraction),
                widthFraction = values.float(KEY_HISTOGRAM_WIDTH_FRACTION, defaults.histogram.widthFraction),
                heightFraction = values.float(KEY_HISTOGRAM_HEIGHT_FRACTION, defaults.histogram.heightFraction),
                alpha = values.float(KEY_HISTOGRAM_ALPHA, defaults.histogram.alpha),
                smoothing = values.float(KEY_HISTOGRAM_SMOOTHING, defaults.histogram.smoothing),
                showLuma = values.boolean(KEY_HISTOGRAM_SHOW_LUMA, defaults.histogram.showLuma),
                showRed = values.boolean(KEY_HISTOGRAM_SHOW_RED, defaults.histogram.showRed),
                showGreen = values.boolean(KEY_HISTOGRAM_SHOW_GREEN, defaults.histogram.showGreen),
                showBlue = values.boolean(KEY_HISTOGRAM_SHOW_BLUE, defaults.histogram.showBlue),
            ),
            peaking = defaults.peaking.copy(
                enabled = values.boolean(KEY_PEAKING_ENABLED, defaults.peaking.enabled),
                sensitivity = values.float(KEY_PEAKING_SENSITIVITY, defaults.peaking.sensitivity),
                edgeThresholdOverride = values.float(
                    KEY_PEAKING_THRESHOLD_OVERRIDE, defaults.peaking.edgeThresholdOverride,
                ),
                colorR = values.float(KEY_PEAKING_COLOR_R, defaults.peaking.colorR),
                colorG = values.float(KEY_PEAKING_COLOR_G, defaults.peaking.colorG),
                colorB = values.float(KEY_PEAKING_COLOR_B, defaults.peaking.colorB),
                mixStrength = values.float(KEY_PEAKING_MIX, defaults.peaking.mixStrength),
                stepScale = values.float(KEY_PEAKING_STEP_SCALE, defaults.peaking.stepScale),
            ),
            sticker = defaults.sticker.copy(
                enabled = values.boolean(KEY_STICKER_ENABLED, defaults.sticker.enabled),
                stickerId = values[KEY_STICKER_ID]?.takeIf { it.isNotBlank() } ?: defaults.sticker.stickerId,
                anchorMode = values.enum(KEY_STICKER_ANCHOR, defaults.sticker.anchorMode) {
                    StickerAnchorMode.valueOf(it)
                },
                scale = values.float(KEY_STICKER_SCALE, defaults.sticker.scale),
                offsetX = values.float(KEY_STICKER_OFFSET_X, defaults.sticker.offsetX),
                offsetY = values.float(KEY_STICKER_OFFSET_Y, defaults.sticker.offsetY),
                mirror = values.boolean(KEY_STICKER_MIRROR, defaults.sticker.mirror),
                alpha = values.float(KEY_STICKER_ALPHA, defaults.sticker.alpha),
                maxFaces = values.int(KEY_STICKER_MAX_FACES, defaults.sticker.maxFaces),
                detectIntervalMs = values.long(KEY_STICKER_INTERVAL_MS, defaults.sticker.detectIntervalMs),
                smoothing = values.float(KEY_STICKER_SMOOTHING, defaults.sticker.smoothing),
                modelAssetPath = values[KEY_STICKER_MODEL_PATH]?.takeIf { it.isNotBlank() }
                    ?: defaults.sticker.modelAssetPath,
                modelDownloadUrl = values[KEY_STICKER_MODEL_URL] ?: defaults.sticker.modelDownloadUrl,
                packDirectory = values[KEY_STICKER_PACK_DIR]?.takeIf { it.isNotBlank() }
                    ?: defaults.sticker.packDirectory,
                packDownloadUrl = values[KEY_STICKER_PACK_URL] ?: "",
            ),
        )
        val clamped = ViewfinderSettings.clamp(raw)
        return ClampResult(clamped.value, parseNotes(values) + clamped.corrections)
    }

    /** 记录「字符串解析失败 → 回落默认值」的键，与 clamp 阶段的修正合并上报。 */
    private fun parseNotes(values: Map<String, String>): List<String> {
        val notes = mutableListOf<String>()
        for ((key, raw) in values) {
            if (key !in ALL_KEYS || raw.isBlank()) continue
            val trimmed = raw.trim()
            val parsed = when {
                key == KEY_ZEBRA_ENABLED || key == KEY_HISTOGRAM_ENABLED ||
                    key == KEY_PEAKING_ENABLED || key == KEY_STICKER_ENABLED ||
                    key == KEY_STICKER_MIRROR || key == KEY_HISTOGRAM_SHOW_LUMA ||
                    key == KEY_HISTOGRAM_SHOW_RED || key == KEY_HISTOGRAM_SHOW_GREEN ||
                    key == KEY_HISTOGRAM_SHOW_BLUE -> trimmed.toBooleanStrictOrNull() != null

                key == KEY_ZEBRA_METRIC || key == KEY_HISTOGRAM_ANCHOR ||
                    key == KEY_STICKER_ANCHOR || key == KEY_STICKER_ID ||
                    key == KEY_STICKER_MODEL_PATH || key == KEY_STICKER_MODEL_URL ||
                    key == KEY_STICKER_PACK_DIR || key == KEY_STICKER_PACK_URL -> true

                key == KEY_HISTOGRAM_BINS || key == KEY_STICKER_MAX_FACES -> trimmed.toIntOrNull() != null
                key == KEY_HISTOGRAM_INTERVAL_MS || key == KEY_STICKER_INTERVAL_MS ->
                    trimmed.toLongOrNull() != null

                else -> trimmed.toFloatOrNull() != null
            }
            if (!parsed) notes += "$key=\"$raw\" 解析失败 → 使用默认值"
        }
        return notes
    }

    private fun Map<String, String>.float(key: String, fallback: Float): Float =
        this[key]?.trim()?.toFloatOrNull() ?: fallback

    private fun Map<String, String>.int(key: String, fallback: Int): Int =
        this[key]?.trim()?.toIntOrNull() ?: fallback

    private fun Map<String, String>.long(key: String, fallback: Long): Long =
        this[key]?.trim()?.toLongOrNull() ?: fallback

    private fun Map<String, String>.boolean(key: String, fallback: Boolean): Boolean =
        this[key]?.trim()?.toBooleanStrictOrNull() ?: fallback

    private fun <T> Map<String, String>.enum(key: String, fallback: T, parse: (String) -> T): T {
        val raw = this[key]?.trim() ?: return fallback
        if (raw.isEmpty()) return fallback
        return try {
            parse(raw)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
