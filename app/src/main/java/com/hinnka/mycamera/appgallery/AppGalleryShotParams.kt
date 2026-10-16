package com.hinnka.mycamera.appgallery

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 拍摄参数（EXIF）快照（相册项 4），纯 Kotlin、可 JVM 单测。
 *
 * 数据来源：工程既有 `MediaMetadata`（其内部已用 `androidx.exifinterface` 解析过 EXIF，
 * 见 `MediaMetadata` 里的 `TAG_ISO_SPEED_RATINGS` / `TAG_EXPOSURE_TIME` / `TAG_F_NUMBER` /
 * `TAG_FOCAL_LENGTH` / `TAG_FOCAL_LENGTH_IN_35MM_FILM` 读取），以及列表项自带的尺寸/体积。
 * 本类只负责**取值整形与展示**，不做 EXIF 解析（避免重复解析、也保证可测）。
 *
 * 全部字段可空：任何缺失都渲染为「—」，不抛异常。
 */
data class AppGalleryShotParams(
    val iso: Int? = null,
    /** 快门字符串（工程既有格式，如 `1/250`、`0.005`、`2`）。 */
    val shutterSpeed: String? = null,
    /** 光圈字符串（如 `f/1.8`、`1.8`、`18/10`）。 */
    val aperture: String? = null,
    val focalLength: String? = null,
    val focalLength35mm: String? = null,
    /** 曝光补偿（EV）。 */
    val exposureBias: Float? = null,
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val locationName: String? = null,
    val cameraId: String? = null,
    val colorSpace: String? = null,
    val software: String? = null,
    /** 分辨率字符串（如 `4032x3024`）。 */
    val dimensions: String? = null,
    val sizeBytes: Long? = null,
) {

    /** 是否一个可用字段都没有（UI 显示空态）。 */
    val isEmpty: Boolean
        get() = iso == null && shutterSpeed.isNullOrBlank() && aperture.isNullOrBlank() &&
            focalLength.isNullOrBlank() && focalLength35mm.isNullOrBlank() && exposureBias == null &&
            capturedAt == null && !hasGps() && locationName.isNullOrBlank() &&
            cameraId.isNullOrBlank() && colorSpace.isNullOrBlank() && software.isNullOrBlank() &&
            dimensions.isNullOrBlank() && (sizeBytes == null || sizeBytes <= 0L)

    /** 是否有有效坐标。 */
    fun hasGps(): Boolean = isValidLatitude(latitude) && isValidLongitude(longitude)

    fun isoLabel(): String = iso?.takeIf { it > 0 }?.let { "ISO $it" } ?: PLACEHOLDER

    fun shutterLabel(): String = formatShutter(shutterSpeed)

    fun apertureLabel(): String = formatAperture(aperture)

    /** 焦距；有 35mm 等效且与物理焦距不同则一并展示（如 `24mm · 等效 35mm`）。 */
    fun focalLabel(): String {
        val physical = normalizeMillimeters(focalLength)
        val equivalent = normalizeMillimeters(focalLength35mm)
        return when {
            physical == null && equivalent == null -> PLACEHOLDER
            physical == null -> "等效 $equivalent"
            equivalent == null || equivalent == physical -> physical
            else -> "$physical · 等效 $equivalent"
        }
    }

    fun exposureBiasLabel(): String {
        val value = exposureBias?.takeIf { it.isFinite() } ?: return PLACEHOLDER
        if (value == 0f) return "±0 EV"
        val sign = if (value > 0f) "+" else "-"
        // Float → Double 显式转换（Kotlin 不做隐式数值提升）
        return "$sign${trimNumber(kotlin.math.abs(value).toDouble())} EV"
    }

    /** 拍摄时间（本地时区 `yyyy-MM-dd HH:mm:ss`）；时间戳 <= 0 视为未知。 */
    fun capturedAtLabel(zone: ZoneId = ZoneId.systemDefault()): String {
        val millis = capturedAt?.takeIf { it > 0L } ?: return PLACEHOLDER
        return runCatching {
            FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))
        }.getOrDefault(PLACEHOLDER)
    }

    /** 十进制度坐标（默认 6 位小数）；无有效坐标返回「—」。 */
    fun coordinateLabel(decimals: Int = 6): String {
        if (!hasGps()) return PLACEHOLDER
        val digits = decimals.coerceIn(0, 8)
        val lat = String.format(Locale.US, "%.${digits}f", latitude!!)
        val lon = String.format(Locale.US, "%.${digits}f", longitude!!)
        return "$lat, $lon"
    }

    /** 带半球标记的坐标（`43.812300° N, 125.345600° E`）。 */
    fun gpsLabel(decimals: Int = 6): String {
        if (!hasGps()) return PLACEHOLDER
        val digits = decimals.coerceIn(0, 8)
        val lat = String.format(Locale.US, "%.${digits}f", kotlin.math.abs(latitude!!))
        val lon = String.format(Locale.US, "%.${digits}f", kotlin.math.abs(longitude!!))
        val latHemisphere = if (latitude >= 0.0) "N" else "S"
        val lonHemisphere = if (longitude >= 0.0) "E" else "W"
        return "$lat° $latHemisphere, $lon° $lonHemisphere"
    }

    fun altitudeLabel(): String {
        val value = altitude?.takeIf { it.isFinite() } ?: return PLACEHOLDER
        return "${trimNumber(value)} m"
    }

    fun sizeLabel(): String =
        sizeBytes?.takeIf { it > 0L }?.let { AppGalleryItem.formatBytes(it) } ?: PLACEHOLDER

    /**
     * 详情面板的行列表（只含**有值**的行，顺序固定，便于 UI 稳定渲染与测试）。
     */
    fun rows(zone: ZoneId = ZoneId.systemDefault()): List<AppGalleryShotRow> {
        val out = ArrayList<AppGalleryShotRow>(12)
        fun add(label: String, value: String?) {
            value?.takeIf { it.isNotBlank() && it != PLACEHOLDER }?.let { out.add(AppGalleryShotRow(label, it)) }
        }
        add("拍摄时间", capturedAtLabel(zone))
        add("光圈", apertureLabel())
        add("快门", shutterLabel())
        add("ISO", isoLabel())
        add("焦距", focalLabel())
        add("曝光补偿", exposureBiasLabel())
        add("地点", locationName)
        add("坐标", gpsLabel())
        add("海拔", altitudeLabel())
        add("分辨率", dimensions)
        add("体积", sizeLabel())
        add("色彩空间", colorSpace?.takeIf { it.isNotBlank() })
        add("相机", cameraId?.takeIf { it.isNotBlank() })
        add("软件", software?.takeIf { it.isNotBlank() })
        return out
    }

    companion object {

        const val PLACEHOLDER = "—"

        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** 合法的纬度（-90..90）。 */
        fun isValidLatitude(value: Double?): Boolean =
            value != null && value.isFinite() && value in -90.0..90.0

        /** 合法的经度（-180..180）。 */
        fun isValidLongitude(value: Double?): Boolean =
            value != null && value.isFinite() && value in -180.0..180.0

        /**
         * 构造并**就地净化**：非法/占位值一律收敛为 null（负 ISO、0 ISO、越界坐标、
         * NaN/Inf 曝光补偿、负体积），保证 UI 与统计不会拿到脏数据。
         */
        fun of(
            iso: Int? = null,
            shutterSpeed: String? = null,
            aperture: String? = null,
            focalLength: String? = null,
            focalLength35mm: String? = null,
            exposureBias: Float? = null,
            capturedAt: Long? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            altitude: Double? = null,
            locationName: String? = null,
            cameraId: String? = null,
            colorSpace: String? = null,
            software: String? = null,
            dimensions: String? = null,
            sizeBytes: Long? = null,
        ): AppGalleryShotParams = AppGalleryShotParams(
            iso = iso?.takeIf { it > 0 },
            shutterSpeed = shutterSpeed?.trim()?.takeIf { it.isNotEmpty() },
            aperture = aperture?.trim()?.takeIf { it.isNotEmpty() },
            focalLength = focalLength?.trim()?.takeIf { it.isNotEmpty() },
            focalLength35mm = focalLength35mm?.trim()?.takeIf { it.isNotEmpty() },
            exposureBias = exposureBias?.takeIf { it.isFinite() },
            capturedAt = capturedAt?.takeIf { it > 0L },
            // 纬度/经度成对校验：只要一个非法就都当没有（避免半对的脏坐标进地图）
            latitude = latitude?.takeIf { isValidLatitude(it) && isValidLongitude(longitude) },
            longitude = longitude?.takeIf { isValidLongitude(it) && isValidLatitude(latitude) },
            altitude = altitude?.takeIf { it.isFinite() },
            locationName = locationName?.trim()?.takeIf { it.isNotEmpty() },
            cameraId = cameraId?.trim()?.takeIf { it.isNotEmpty() },
            colorSpace = colorSpace?.trim()?.takeIf { it.isNotEmpty() },
            software = software?.trim()?.takeIf { it.isNotEmpty() },
            dimensions = dimensions?.trim()?.takeIf { it.isNotEmpty() && it != DIMENSION_PLACEHOLDER },
            sizeBytes = sizeBytes?.takeIf { it > 0L },
        )

        private const val DIMENSION_PLACEHOLDER = "0x0"

        /**
         * 快门展示：`1/250` 原样；小数秒换算为 `1/N`；其它按 `N s`；无法解析则原样返回
         * （EXIF 字符串格式多样，宁可原样展示也不猜）。
         */
        fun formatShutter(raw: String?): String {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return PLACEHOLDER
            if (text.contains('/')) {
                val parts = text.split('/')
                val numerator = parts.getOrNull(0)?.toDoubleOrNull()
                val denominator = parts.getOrNull(1)?.toDoubleOrNull()
                if (numerator != null && denominator != null && numerator > 0.0 && denominator > 0.0) {
                    if (denominator == 1.0) return "${trimNumber(numerator)} s"
                    return "${trimNumber(numerator)}/${trimNumber(denominator)} s"
                }
                return text
            }
            val seconds = text.removeSuffix("s").trim().toDoubleOrNull() ?: return text
            if (seconds <= 0.0) return text
            return if (seconds >= 1.0) {
                "${trimNumber(seconds)} s"
            } else {
                "1/${(1.0 / seconds).let { kotlin.math.round(it) }.toLong()} s"
            }
        }

        /** 光圈展示：`1.8` / `f/1.8` / `18/10` 统一为 `f/1.8`；无法解析则原样返回。 */
        fun formatAperture(raw: String?): String {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return PLACEHOLDER
            val body = text.removePrefix("f/").removePrefix("F/")
            val value = when {
                body.contains('/') -> {
                    val parts = body.split('/')
                    val numerator = parts.getOrNull(0)?.toDoubleOrNull()
                    val denominator = parts.getOrNull(1)?.toDoubleOrNull()
                    if (numerator != null && denominator != null && denominator != 0.0) numerator / denominator else null
                }

                else -> body.toDoubleOrNull()
            } ?: return text
            if (value <= 0.0) return text
            return "f/${trimNumber(value)}"
        }

        /** `24` / `24.0mm` → `24mm`；无法解析返回 null。 */
        fun normalizeMillimeters(raw: String?): String? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val value = text.lowercase().removeSuffix("mm").trim().toDoubleOrNull() ?: return null
            if (value <= 0.0) return null
            return "${trimNumber(value)}mm"
        }

        /** 去尾零的数字文本（`2.0` → `2`，`1.80` → `1.8`，`0.3` → `0.3`）。 */
        fun trimNumber(value: Double): String {
            if (!value.isFinite()) return "0"
            val rounded = kotlin.math.round(value * 100.0) / 100.0
            return if (rounded == kotlin.math.floor(rounded)) {
                rounded.toLong().toString()
            } else {
                rounded.toString().trimEnd('0').trimEnd('.')
            }
        }
    }
}

/** 拍摄参数面板的一行（label / value）。 */
data class AppGalleryShotRow(val label: String, val value: String)
