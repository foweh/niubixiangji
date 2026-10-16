package com.hinnka.mycamera.appgallerypreview

/**
 * DNG/RAW 曝光元数据（纯数据，无 Android 依赖，可 JVM 单测）。
 *
 * 字段均为"缺失即 null"，不对缺项做猜测；[widthPx]/[heightPx] 为 0 表示未知。
 */
data class AppGalleryPreviewRawExposure(
    val exposureTimeSec: Double? = null,
    val iso: Int? = null,
    val aperture: Double? = null,
    /** 曝光补偿（EV）。 */
    val exposureBiasEv: Double? = null,
    val focalLengthMm: Double? = null,
    /** DNG BaselineExposure（EV），用于估算整体偏移。 */
    val baselineExposureEv: Double? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    /** 是否具备展示"曝光三要素"所需的最小信息。 */
    val hasCoreExposure: Boolean
        get() = exposureTimeSec != null || iso != null || aperture != null

    /** 任一字段是否存在。 */
    val isEmpty: Boolean
        get() = this == AppGalleryPreviewRawExposure()

    /** 快门速度文案，如 `1/125s` / `2.5s`；缺失返回 null。 */
    fun exposureTimeLabel(): String? {
        val t = exposureTimeSec?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return if (t >= 1.0) {
            val rounded = kotlin.math.round(t * 10.0) / 10.0
            if (rounded == kotlin.math.floor(rounded)) "${rounded.toInt()}s" else "${rounded}s"
        } else {
            val denominator = kotlin.math.round(1.0 / t).toInt().coerceAtLeast(1)
            "1/${denominator}s"
        }
    }

    /**
     * 曝光补偿折算为指定步长的档数（用于 RAW 面板的"过曝 N 档"提示）。
     * @param stepEv 步长（EV）；<=0 返回 null
     */
    fun toExposureSteps(stepEv: Double): Int? {
        if (!stepEv.isFinite() || stepEv <= 0.0) return null
        val ev = exposureBiasEv?.takeIf { it.isFinite() } ?: return null
        return kotlin.math.round(ev / stepEv).toInt()
    }

    /**
     * 曝光三要素摘要；无核心信息返回 null。
     * 供 UI 直接展示，避免在 Compose 内拼字符串。
     */
    fun summary(): String? {
        if (!hasCoreExposure) return null
        val parts = ArrayList<String>(4)
        exposureTimeLabel()?.let { parts += it }
        iso?.let { parts += "ISO $it" }
        aperture?.let { parts += "f/" + formatAperture(it) }
        focalLengthMm?.let { parts += formatFocal(it) + "mm" }
        exposureBiasEv?.let {
            val sign = if (it > 0) "+" else ""
            parts += sign + (kotlin.math.round(it * 100.0) / 100.0) + "EV"
        }
        return parts.joinToString(" · ")
    }

    private fun formatAperture(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }

    private fun formatFocal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
    }
}

/**
 * DNG/EXIF 曝光字段解析（自研纯解析层，复用 androidx.exifinterface 负责读字节）。
 *
 * 复用说明：字节读取与缩略图提取复用工程已依赖的 `androidx.exifinterface:exifinterface:1.4.2`
 * （Apache-2.0）的 `ExifInterface.getAttribute/getThumbnailBitmap`；本对象只负责把字符串值
 * 解析成可校验的数值并 clamp，从而可在纯 JVM 下覆盖边界/异常。标签名使用 TIFF/DNG 标准名
 * 字面量（不引用框架常量），保证解析层无 Android 依赖。
 */
object AppGalleryPreviewRawMetadataParser {

    // EXIF / DNG 标准标签名（TIFF 语义）。
    const val TAG_EXPOSURE_TIME = "ExposureTime"
    const val TAG_ISO = "ISOSpeedRatings"
    const val TAG_PHOTOGRAPHIC_SENSITIVITY = "PhotographicSensitivity"
    const val TAG_F_NUMBER = "FNumber"
    const val TAG_EXPOSURE_BIAS = "ExposureBiasValue"
    const val TAG_FOCAL_LENGTH = "FocalLength"
    const val TAG_BASELINE_EXPOSURE = "BaselineExposure"
    const val TAG_PIXEL_X_DIMENSION = "PixelXDimension"
    const val TAG_PIXEL_Y_DIMENSION = "PixelYDimension"
    const val TAG_IMAGE_WIDTH = "ImageWidth"
    const val TAG_IMAGE_LENGTH = "ImageLength"

    /** 快门范围（秒）：1/16000 至 120s（覆盖手机到长曝光）。 */
    const val EXPOSURE_TIME_MIN_SEC = 1.0 / 16000.0
    const val EXPOSURE_TIME_MAX_SEC = 120.0

    /** ISO 范围。 */
    const val ISO_MIN = 6
    const val ISO_MAX = 409_600

    /** 光圈范围（f 值）。 */
    const val APERTURE_MIN = 0.5
    const val APERTURE_MAX = 64.0

    /** 曝光补偿范围（EV）。 */
    const val EV_MIN = -10.0
    const val EV_MAX = 10.0

    /** 焦距范围（mm）。 */
    const val FOCAL_MIN_MM = 0.1
    const val FOCAL_MAX_MM = 2000.0

    /** 尺寸范围（px）。 */
    const val DIMENSION_MIN = 1
    const val DIMENSION_MAX = 1_000_000

    /**
     * 解析"分子/分母"或纯数字有理数。
     *
     * 规则：空白裁剪；支持 `a/b`、`a`；分母为 0 → null；出现负号 → null；
     * 非法字符/溢出 → null。绝不抛异常。
     */
    fun parseRational(text: String?): Double? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.contains('-')) return null
        val slash = raw.indexOf('/')
        return try {
            if (slash < 0) {
                val value = raw.toDoubleOrNull() ?: return null
                if (value.isFinite()) value else null
            } else {
                val numerator = raw.substring(0, slash).trim().toDoubleOrNull() ?: return null
                val denominator = raw.substring(slash + 1).trim().toDoubleOrNull() ?: return null
                if (denominator == 0.0) return null
                val value = numerator / denominator
                if (value.isFinite()) value else null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 快门时间（秒）：解析有理数后 clamp 到 [EXPOSURE_TIME_MIN_SEC, EXPOSURE_TIME_MAX_SEC]。 */
    fun parseExposureTimeSeconds(text: String?): Double? {
        val value = parseRational(text) ?: return null
        if (value <= 0.0) return null
        return value.coerceIn(EXPOSURE_TIME_MIN_SEC, EXPOSURE_TIME_MAX_SEC)
    }

    /** ISO：容忍 `ISO 100` / `100`；越界 clamp。 */
    fun parseIso(text: String?): Int? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = raw.uppercase().removePrefix("ISO").trim()
        val value = digits.toIntOrNull() ?: digits.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() } ?: return null
        if (value <= 0) return null
        return value.coerceIn(ISO_MIN, ISO_MAX)
    }

    /** 光圈 f 值：容忍 `f/2.0` / `2.0`；越界 clamp。 */
    fun parseAperture(text: String?): Double? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = raw.removePrefix("f/").removePrefix("F/").removePrefix("f").trim()
        val value = parseRational(normalized) ?: return null
        if (value <= 0.0) return null
        return value.coerceIn(APERTURE_MIN, APERTURE_MAX)
    }

    /** 曝光补偿（EV）：容忍 `+0.33` / `-1`；越界 clamp。 */
    fun parseExposureBiasEv(text: String?): Double? {
        val value = parseRationalSigned(text) ?: return null
        return value.coerceIn(EV_MIN, EV_MAX)
    }

    /** 焦距（mm）：容忍 `6.0 mm`；越界 clamp。 */
    fun parseFocalLengthMm(text: String?): Double? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = raw.removeSuffix("mm").removeSuffix("MM").trim()
        val value = parseRational(normalized) ?: return null
        if (value <= 0.0) return null
        return value.coerceIn(FOCAL_MIN_MM, FOCAL_MAX_MM)
    }

    /** 像素尺寸：越界 clamp。 */
    fun parseDimension(text: String?): Int {
        val value = text?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return 0
        if (!value.isFinite() || value <= 0.0) return 0
        return kotlin.math.round(value).toInt().coerceIn(DIMENSION_MIN, DIMENSION_MAX)
    }

    /** 带符号有理数（EV 专用：允许前置 `+`/`-`）。 */
    private fun parseRationalSigned(text: String?): Double? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sign = when (raw.first()) {
            '+' -> 1.0
            '-' -> -1.0
            else -> 1.0
        }
        val body = if (raw.first() == '+' || raw.first() == '-') raw.drop(1).trim() else raw
        if (body.isEmpty() || body.contains('-')) return null
        val value = parseRational(body) ?: return null
        return sign * value
    }

    /**
     * 从标签表构建曝光信息；缺失字段保持 null，不猜测。
     *
     * 标签名见本对象的 `TAG_*` 常量（与 `ExifInterface` 的 `getAttribute` 入参一致）。
     */
    fun fromTags(tags: Map<String, String?>): AppGalleryPreviewRawExposure {
        val width = maxOf(
            parseDimension(tags[TAG_PIXEL_X_DIMENSION]),
            parseDimension(tags[TAG_IMAGE_WIDTH]),
        )
        val height = maxOf(
            parseDimension(tags[TAG_PIXEL_Y_DIMENSION]),
            parseDimension(tags[TAG_IMAGE_LENGTH]),
        )
        return AppGalleryPreviewRawExposure(
            exposureTimeSec = parseExposureTimeSeconds(tags[TAG_EXPOSURE_TIME]),
            iso = parseIso(tags[TAG_PHOTOGRAPHIC_SENSITIVITY]) ?: parseIso(tags[TAG_ISO]),
            aperture = parseAperture(tags[TAG_F_NUMBER]),
            exposureBiasEv = parseExposureBiasEv(tags[TAG_EXPOSURE_BIAS]),
            focalLengthMm = parseFocalLengthMm(tags[TAG_FOCAL_LENGTH]),
            baselineExposureEv = parseExposureBiasEv(tags[TAG_BASELINE_EXPOSURE]),
            widthPx = width,
            heightPx = height,
        )
    }
}
