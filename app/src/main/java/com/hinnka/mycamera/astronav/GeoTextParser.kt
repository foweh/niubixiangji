/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：地理坐标模型与文本解析（经纬度直接输入 / 度分秒 / 带方位后缀）。
 * 纯 Kotlin，无 Android 依赖 -> 可直接 JVM 单测。
 */
package com.hinnka.mycamera.astronav

import kotlin.math.abs

/**
 * 一个地理观测点。
 *
 * @param latitudeDegrees 纬度（度，-90..90，北正南负）
 * @param longitudeDegrees 经度（度，-180..180，东正西负）
 * @param altitudeMeters 海拔（米，WGS-84 椭球高；未知用 0）
 * @param accuracyMeters 水平精度（米；手动输入视为 0 = 精确）
 * @param label 展示名（城市名 / "手动输入" / 提供者名）
 */
data class GeoPoint(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val label: String = "",
) {
    val isFinite: Boolean
        get() = latitudeDegrees.isFinite() && longitudeDegrees.isFinite() && altitudeMeters.isFinite()

    /** 收敛到合法范围（纬度 clamp、经度归一化、海拔 clamp）。 */
    fun normalized(): GeoPoint = copy(
        latitudeDegrees = if (latitudeDegrees.isFinite()) latitudeDegrees.coerceIn(-90.0, 90.0) else 0.0,
        longitudeDegrees = Wmm2025Model.normalizeLongitude(longitudeDegrees),
        altitudeMeters = if (altitudeMeters.isFinite()) {
            altitudeMeters.coerceIn(AstroNavConfig.ALTITUDE_MIN, AstroNavConfig.ALTITUDE_MAX)
        } else {
            0.0
        },
        accuracyMeters = if (accuracyMeters.isFinite()) accuracyMeters.coerceAtLeast(0f) else 0f,
    )

    /** 与另一点的粗略距离（米，等距圆柱近似；1° 纬度 ≈ 111_320m）。足够做「位置是否大改」判断。 */
    fun distanceMetersTo(other: GeoPoint): Double {
        val dLat = other.latitudeDegrees - latitudeDegrees
        val dLon = AstroNavAngles.delta(longitudeDegrees.toFloat(), other.longitudeDegrees.toFloat()).toDouble()
        val meanLat = Math.toRadians((latitudeDegrees + other.latitudeDegrees) / 2.0)
        val x = Math.toRadians(dLon) * kotlin.math.cos(meanLat) * EARTH_RADIUS_M
        val y = Math.toRadians(dLat) * EARTH_RADIUS_M
        return kotlin.math.sqrt(x * x + y * y)
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8

        /** 归一化后的点（链式构造用）。 */
        fun of(
            latitudeDegrees: Double,
            longitudeDegrees: Double,
            altitudeMeters: Double = 0.0,
            label: String = "",
        ): GeoPoint = GeoPoint(latitudeDegrees, longitudeDegrees, altitudeMeters, 0f, label).normalized()
    }
}

/** 文本解析失败的归因（UI 直接展示，不抛异常）。 */
enum class GeoParseError {
    /** 空输入。 */
    EMPTY,

    /** 纬度/经度数值非有限或缺失。 */
    NOT_A_NUMBER,

    /** 纬度越界（|lat| > 90）。 */
    LATITUDE_OUT_OF_RANGE,

    /** 经度越界（|lon| > 180，且无法通过 ±360 归一化解释为合法经度）。 */
    LONGITUDE_OUT_OF_RANGE,

    /** 无法识别的格式。 */
    UNRECOGNISED_FORMAT,
}

/** 解析结果：成功给点，失败给归因（互斥）。 */
data class GeoParseResult(
    val point: GeoPoint?,
    val error: GeoParseError?,
    /** 修正说明（例如"经度已归一化"），可为空。 */
    val note: String = "",
) {
    val isSuccess: Boolean get() = point != null

    companion object {
        fun ok(point: GeoPoint, note: String = "") = GeoParseResult(point, null, note)

        fun fail(error: GeoParseError) = GeoParseResult(null, error)
    }
}

/**
 * 地理坐标文本解析（手动输入兜底用）。
 *
 * 支持的输入形式（大小写、全角逗号、多余空格都容错）：
 *  1. 十进制："39.9042, 116.4074" / "39.9042 116.4074" / "39.9042N, 116.4074E"
 *  2. 度分："39°54.25'N 116°24.45'E" / "39 54.25 N, 116 24.45 E"
 *  3. 度分秒："39°54'15\"N, 116°24'27\"E"
 *  4. 带符号的十进制："-33.8688, 151.2093"（南纬/西经为负）
 *
 * 边界规则（均有单测覆盖）：
 *  - 纬度超 ±90 -> [GeoParseError.LATITUDE_OUT_OF_RANGE]（不 clamp，避免用户误以为已修正）；
 *  - 经度超 ±180 但能被 ±360 归一化（如 361）-> 接受并给出 note；
 *  - 其余超界 -> [GeoParseError.LONGITUDE_OUT_OF_RANGE]。
 */
object GeoTextParser {

    private val LAT_LON_SPLIT = Regex("[,，;；\\s]+")

    /** 方位字母集合（大写比较）。 */
    private val HEMISPHERES = listOf('N', 'S', 'E', 'W')

    /**
     * 一个完整 DMS 分量的匹配式（无方位字母时用于把 "39°54'15\" 116°24'27\"" 切成两段）。
     * 分隔符与分/秒均为可选，所以 "39°"、"39°54'"、"39°54'15\"" 都能匹配。
     */
    private val DMS_COMPONENT = Regex(
        "[-+]?\\d+(?:\\.\\d+)?\\s*(?:[°度]\\s*(?:[-+]?\\d+(?:\\.\\d+)?)?\\s*(?:['′]\\s*(?:[-+]?\\d+(?:\\.\\d+)?)?\\s*[\"″]?)?)?",
    )

    /** 解析 "纬度,经度"（第一个分量按纬度解析，第二个按经度）。 */
    fun parsePair(text: String?): GeoParseResult {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return GeoParseResult.fail(GeoParseError.EMPTY)

        // 先尝试 DMS/度分 形式（含 ° ' \" 或 N/S/E/W 后缀）
        val dms = parseDmsPair(raw)
        if (dms != null) return dms

        // 十进制（可能带 N/S/E/W 后缀，也可能纯符号）
        val parts = raw.split(LAT_LON_SPLIT).filter { it.isNotBlank() }
        if (parts.size < 2) return GeoParseResult.fail(GeoParseError.UNRECOGNISED_FORMAT)

        val latToken = parts[0]
        val lonToken = parts[1]

        val lat = parseDecimalComponent(latToken, isLatitude = true)
            ?: return GeoParseResult.fail(classifyFailure(latToken, isLatitude = true))
        val lon = parseDecimalComponent(lonToken, isLatitude = false)
            ?: return GeoParseResult.fail(classifyFailure(lonToken, isLatitude = false))

        return combine(lat, lon)
    }

    /** 只解析一个带后缀的分量（如 "39.9N" / "-8.7"）。 */
    fun parseDecimalComponent(token: String?, isLatitude: Boolean): Double? {
        val t = token?.trim()?.removeSuffix("°").orEmpty()
        if (t.isEmpty()) return null
        var body = t
        var sign = 1.0
        val last = t.last().uppercaseChar()
        when (last) {
            'N', 'E' -> body = t.dropLast(1).trim()
            'S', 'W' -> {
                sign = -1.0
                body = t.dropLast(1).trim()
            }
            else -> Unit
        }
        val v = body.trim().toDoubleOrNull() ?: return null
        if (!v.isFinite()) return null
        return sign * v
    }

    /** 合并纬度/经度并做范围裁决。 */
    fun combine(latRaw: Double, lonRaw: Double, altitudeMeters: Double = 0.0): GeoParseResult {
        if (!latRaw.isFinite() || !lonRaw.isFinite()) return GeoParseResult.fail(GeoParseError.NOT_A_NUMBER)
        if (abs(latRaw) > 90.0) return GeoParseResult.fail(GeoParseError.LATITUDE_OUT_OF_RANGE)

        // 经度：允许 ±360 归一化（如 361 -> 1），但 |lon| > 360 判非法
        if (abs(lonRaw) > 360.0) return GeoParseResult.fail(GeoParseError.LONGITUDE_OUT_OF_RANGE)
        val lonNormalized = Wmm2025Model.normalizeLongitude(lonRaw)
        val note = if (abs(lonRaw - lonNormalized) > 1e-9) "经度已归一化到 ${lonNormalized}°" else ""

        return GeoParseResult.ok(
            GeoPoint.of(latRaw, lonNormalized, altitudeMeters, "手动输入"),
            note,
        )
    }

    /** 判断一个失败 token 属于哪种归因（用于精确的错误提示）。 */
    private fun classifyFailure(token: String, isLatitude: Boolean): GeoParseError {
        val v = parseDecimalComponent(token, isLatitude)
        if (v != null) {
            return if (isLatitude) {
                if (abs(v) > 90.0) GeoParseError.LATITUDE_OUT_OF_RANGE else GeoParseError.NOT_A_NUMBER
            } else {
                if (abs(v) > 360.0) GeoParseError.LONGITUDE_OUT_OF_RANGE else GeoParseError.NOT_A_NUMBER
            }
        }
        // 能解析出数字但越界的情况单独归因
        val numeric = token.trim().trimEnd('°').toDoubleOrNull()
        if (numeric != null) {
            return if (isLatitude) {
                if (abs(numeric) > 90.0) GeoParseError.LATITUDE_OUT_OF_RANGE else GeoParseError.NOT_A_NUMBER
            } else {
                if (abs(numeric) > 360.0) GeoParseError.LONGITUDE_OUT_OF_RANGE else GeoParseError.NOT_A_NUMBER
            }
        }
        return GeoParseError.UNRECOGNISED_FORMAT
    }

    /**
     * 解析度分 / 度分秒 对：`39°54'15"N, 116°24'27"E`、`39 54.25 N 116 24.45 E`。
     * 解析不出（或不是 DMS 形式）返回 null，交给十进制分支处理。
     */
    private fun parseDmsPair(text: String): GeoParseResult? {
        val s = text.replace('，', ',').replace("\u3000", " ").trim()
        val hasDmsMarker = s.any {
            it == '°' || it == '\'' || it == '"' || it == '′' || it == '″' || it == '度'
        }
        val hasHemisphere = s.any { it.uppercaseChar() in HEMISPHERES }
        // 只有真正的 DMS/方位角写法才走这里；纯十进制交给下面的十进制分支
        if (!hasDmsMarker && !hasHemisphere) return null

        val latComp: String
        val lonComp: String
        if (hasHemisphere) {
            // 约定：纬度在前。第一个 N/S 字母结束纬度分量；其后的第一个 E/W 结束经度分量。
            val latEnd = s.indexOfFirst { it.uppercaseChar() == 'N' || it.uppercaseChar() == 'S' }
            if (latEnd < 0) return null
            latComp = s.substring(0, latEnd + 1)
            val rest = s.substring(latEnd + 1)
            val lonEnd = rest.indexOfFirst { it.uppercaseChar() == 'E' || it.uppercaseChar() == 'W' }
            lonComp = if (lonEnd >= 0) rest.substring(0, lonEnd + 1) else rest
        } else {
            // 无方位字母、有 ° 标记：按「度[分[秒]]」分组整体匹配
            val comps = DMS_COMPONENT.findAll(s)
                .map { it.value.trim() }
                .filter { comp -> comp.any { c -> c.isDigit() } }
                .toList()
            if (comps.size < 2) return null
            latComp = comps[0]
            lonComp = comps[1]
        }

        val lat = parseDmsComponent(latComp, isLatitude = true) ?: return null
        val lon = parseDmsComponent(lonComp, isLatitude = false) ?: return null
        return combine(lat, lon)
    }

    /**
     * 解析单个 DMS / 度分 分量（"39°54'15\"N"、"39 54.25 N"、"39.9042N"）。
     * 方位字母决定正负：N/E 正，S/W 负。
     */
    fun parseDmsComponent(token: String?, isLatitude: Boolean): Double? {
        val t = token?.trim().orEmpty()
        if (t.isEmpty()) return null
        var sign = 1.0
        var body = t
        val last = t.last().uppercaseChar()
        if (last in listOf('N', 'S', 'E', 'W')) {
            if (last == 'S' || last == 'W') sign = -1.0
            body = t.dropLast(1)
        }
        val nums = Regex("[-+]?\\d+(?:\\.\\d+)?")
            .findAll(body.replace('，', ' '))
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (nums.isEmpty()) return null
        val deg = nums[0]
        val min = nums.getOrNull(1) ?: 0.0
        val sec = nums.getOrNull(2) ?: 0.0
        if (min.isNaN() || sec.isNaN()) return null
        if (abs(min) >= 60.0 || abs(sec) >= 60.0) return null // 分/秒必须 < 60
        val magnitude = abs(deg) + abs(min) / 60.0 + abs(sec) / 3600.0
        // 原式带负号时以符号为准（如 "-33 52 8"）
        val explicitSign = if (deg < 0.0) -1.0 else 1.0
        return sign * explicitSign * magnitude
    }
}
