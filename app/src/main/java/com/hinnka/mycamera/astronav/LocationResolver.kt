/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：定位结果的来源/状态模型 + 手动输入与离线搜索兜底解析。
 * 纯 Kotlin（不引用 Android 框架）-> 可直接 JVM 单测。
 * 真机定位（LocationManager）见同包的 [AstroNavLocationProvider]。
 */
package com.hinnka.mycamera.astronav

/** 定位来源（精度优先：GPS > 网络；手动兜底）。 */
enum class LocationSource(val displayName: String, val isPrecise: Boolean) {
    /** 卫星定位（LocationManager.GPS_PROVIDER）。 */
    GPS("GPS 卫星定位", true),

    /** 网络定位（LocationManager.NETWORK_PROVIDER）。 */
    NETWORK("网络定位", false),

    /** 多提供者融合（本模块按精度择优后的统称）。 */
    FUSED("融合定位", true),

    /** 系统缓存的最后已知位置（可能已过时）。 */
    LAST_KNOWN("上次已知位置", false),

    /** 用户直接输入经纬度。 */
    MANUAL_INPUT("手动输入坐标", true),

    /** 用户搜索离线城市表。 */
    OFFLINE_SEARCH("离线城市搜索", true),

    /** 无定位（未获取到，等待或引导用户手动输入）。 */
    NONE("未定位", false),
}

/** 定位状态（UI 直接消费；补丁挂点据此渲染提示）。 */
enum class LocationStatus(val displayName: String) {
    /** 尚未开始。 */
    IDLE("未开始定位"),

    /** 正在请求权限/等待首次定位。 */
    SEARCHING("正在定位…"),

    /** 已获得可用位置。 */
    FIXED("定位成功"),

    /** 定位失败（超时/无提供者/权限被拒），可用手动兜底。 */
    FAILED("定位失败"),

    /** 权限被拒绝。 */
    PERMISSION_DENIED("缺少定位权限"),

    /** 系统定位服务被关闭。 */
    PROVIDER_DISABLED("系统定位服务已关闭"),

    /** 使用手动输入/离线搜索的结果。 */
    MANUAL("使用手动位置"),
}

/**
 * 一次定位的完整结果（供 UI + 姿态解算消费）。
 *
 * @param status 状态
 * @param point 位置（未定位时为 null）
 * @param source 来源
 * @param message 供 UI 展示的中文说明
 * @param accuracyMeters 精度（米；手动输入为 0）
 * @param updatedAtMs 获取时刻（epoch 毫秒）
 */
data class AstroNavLocation(
    val status: LocationStatus,
    val point: GeoPoint?,
    val source: LocationSource,
    val message: String,
    val accuracyMeters: Float = 0f,
    val updatedAtMs: Long = 0L,
) {
    /** 是否可用于星表解算。 */
    val isUsable: Boolean get() = point != null && status != LocationStatus.FAILED

    /** 纬度为默认值（未定位时星表仍可渲染，但方位不准）。 */
    val fallbackLatitude: Float get() = point?.latitudeDegrees?.toFloat() ?: DEFAULT_LATITUDE

    companion object {
        /** 未定位时的兜底纬度（北半球中纬度，与既有 AstroOverlayState 默认 35° 一致）。 */
        const val DEFAULT_LATITUDE = 35f

        fun idle() = AstroNavLocation(LocationStatus.IDLE, null, LocationSource.NONE, "尚未定位")

        fun searching() = AstroNavLocation(LocationStatus.SEARCHING, null, LocationSource.NONE, "正在获取位置…")

        fun permissionDenied() = AstroNavLocation(
            LocationStatus.PERMISSION_DENIED, null, LocationSource.NONE,
            "缺少定位权限，可在下方手动输入经纬度或搜索城市",
        )

        fun providerDisabled() = AstroNavLocation(
            LocationStatus.PROVIDER_DISABLED, null, LocationSource.NONE,
            "系统定位服务已关闭，请开启后重试，或手动输入位置",
        )

        fun failed(message: String) = AstroNavLocation(
            LocationStatus.FAILED, null, LocationSource.NONE,
            message.ifBlank { "定位失败，可手动输入位置" },
        )

        /** GPS/网络定位成功。 */
        fun fixed(
            point: GeoPoint,
            source: LocationSource,
            accuracyMeters: Float,
            updatedAtMs: Long,
        ) = AstroNavLocation(
            status = LocationStatus.FIXED,
            point = point,
            source = source,
            message = "${source.displayName}：${"%.4f".format(point.latitudeDegrees)}°, " +
                "${"%.4f".format(point.longitudeDegrees)}°" +
                if (accuracyMeters > 0f) "（±${accuracyMeters.toInt()}m）" else "",
            accuracyMeters = accuracyMeters,
            updatedAtMs = updatedAtMs,
        )

        /** 手动兜底定位成功。 */
        fun manual(point: GeoPoint, source: LocationSource, message: String, updatedAtMs: Long) =
            AstroNavLocation(
                status = LocationStatus.MANUAL,
                point = point,
                source = source,
                message = message,
                accuracyMeters = 0f,
                updatedAtMs = updatedAtMs,
            )
    }
}

/** 手动兜底解析结果（成功给位置，失败给中文归因）。 */
data class ManualLocationResult(
    val location: AstroNavLocation?,
    val errorMessage: String?,
    /** 可选的候选列表（搜索多条命中时给出，供 UI 选择）。 */
    val candidates: List<PlaceEntry> = emptyList(),
) {
    val isSuccess: Boolean get() = location != null

    companion object {
        fun ok(location: AstroNavLocation, candidates: List<PlaceEntry> = emptyList()) =
            ManualLocationResult(location, null, candidates)

        fun fail(message: String, candidates: List<PlaceEntry> = emptyList()) =
            ManualLocationResult(null, message, candidates)
    }
}

/**
 * 手动位置兜底解析器（定位失败/精度不足时的第二路径）。
 *
 * 两种输入：
 *  1. 经纬度文本 -> [resolveCoordinates]（支持十进制 / 度分 / 度分秒 / 方位后缀）；
 *  2. 城市名 -> [resolvePlaceName]（离线表精确名 + 模糊搜索）。
 *
 * 全部路径都不抛异常：错误以中文提示返回（[ManualLocationResult.errorMessage]）。
 */
class ManualLocationResolver(
    private val catalog: OfflinePlaceCatalog = OfflinePlaceCatalog,
) {

    /**
     * 解析「经纬度」文本。
     *
     * @param text 用户输入，如 "39.9042, 116.4074" / "39°54'15\"N 116°24'27\"E"
     * @param altitudeMeters 可选海拔（未知传 0）
     * @return 成功给出 [LocationSource.MANUAL_INPUT] 的位置；失败给出中文提示
     */
    fun resolveCoordinates(text: String?, altitudeMeters: Double = 0.0): ManualLocationResult {
        val parsed = GeoTextParser.parsePair(text)
        val point = parsed.point
        if (point == null) {
            return ManualLocationResult.fail(describe(parsed.error))
        }
        val withAlt = point.copy(altitudeMeters = safeAltitude(altitudeMeters)).normalized()
        val msg = buildString {
            append("手动坐标：")
            append("%.4f".format(withAlt.latitudeDegrees))
            append("°, ")
            append("%.4f".format(withAlt.longitudeDegrees))
            append("°")
            if (parsed.note.isNotBlank()) append("（").append(parsed.note).append("）")
        }
        return ManualLocationResult.ok(
            AstroNavLocation.manual(
                point = withAlt,
                source = LocationSource.MANUAL_INPUT,
                message = msg,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 解析「城市名 / 拼音 / 英文名」。
     *
     * @param query 用户输入（如 "北京" / "beijing" / "Bei Jing"）
     * @param exactOnly true = 只接受精确名；false = 允许模糊搜索（默认）
     * @return 唯一命中 -> 直接给位置；多条命中 -> 给出候选列表 + 提示；无命中 -> 提示
     */
    fun resolvePlaceName(query: String?, exactOnly: Boolean = false): ManualLocationResult {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            return ManualLocationResult.fail("请输入城市名（如「北京」或「beijing」）")
        }

        val exact = catalog.byAnyName(q)
        if (exact != null) {
            return ManualLocationResult.ok(toManualLocation(exact), listOf(exact))
        }
        if (exactOnly) {
            return ManualLocationResult.fail("离线地点表中没有「$q」")
        }

        val hits = catalog.search(q)
        return when (hits.size) {
            0 -> ManualLocationResult.fail(
                "未找到「$q」，可改用经纬度输入（如 39.9042, 116.4074）",
            )
            1 -> ManualLocationResult.ok(toManualLocation(hits[0]), hits)
            else -> ManualLocationResult.fail(
                "「$q」匹配到 ${hits.size} 个地点，请选择：" + hits.take(6).joinToString("、") { it.nameZh },
                hits,
            )
        }
    }

    /** 直接采用一条离线地点条目。 */
    fun resolvePlace(entry: PlaceEntry): AstroNavLocation = toManualLocation(entry)

    /** 综合入口：先按坐标解析，失败再按城市名解析（用户可能贴任意一种输入）。 */
    fun resolveAuto(input: String?): ManualLocationResult {
        val coord = resolveCoordinates(input)
        if (coord.isSuccess) return coord
        val place = resolvePlaceName(input)
        if (place.isSuccess) return place
        // 两者都失败：优先给坐标格式的错误说明（更具体）
        val coordErr = coord.errorMessage.orEmpty()
        return ManualLocationResult.fail(
            if (coordErr.isNotBlank()) "$coordErr；${place.errorMessage.orEmpty()}" else place.errorMessage.orEmpty(),
            place.candidates,
        )
    }

    private fun toManualLocation(entry: PlaceEntry): AstroNavLocation {
        val point = entry.toGeoPoint()
        return AstroNavLocation.manual(
            point = point,
            source = LocationSource.OFFLINE_SEARCH,
            message = "离线地点：${entry.nameZh}（${entry.region}）%.4f°, %.4f°"
                .format(point.latitudeDegrees, point.longitudeDegrees),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun safeAltitude(v: Double): Double =
        if (v.isFinite()) v.coerceIn(AstroNavConfig.ALTITUDE_MIN, AstroNavConfig.ALTITUDE_MAX) else 0.0

    private fun describe(error: GeoParseError?): String = when (error) {
        GeoParseError.EMPTY -> "请输入经纬度，如 39.9042, 116.4074"
        GeoParseError.NOT_A_NUMBER -> "经纬度必须是数字，如 39.9042, 116.4074"
        GeoParseError.LATITUDE_OUT_OF_RANGE -> "纬度必须在 -90 到 90 之间"
        GeoParseError.LONGITUDE_OUT_OF_RANGE -> "经度必须在 -180 到 180 之间"
        GeoParseError.UNRECOGNISED_FORMAT -> "无法识别该格式，请输入「纬度, 经度」或城市名"
        null -> "无法识别该输入"
    }
}

/**
 * 定位来源择优（精度优先策略的纯逻辑部分，便于单测）。
 *
 * 规则：
 *  1. 有 GPS 且有网络时：按 [preferGpsOverNetwork]，默认取 GPS；
 *  2. 只有一个可用则取之；
 *  3. 都没有则回落到 lastKnown（若允许），否则 null。
 */
object LocationSourceSelector {

    /**
     * @param gpsAccuracy GPS 精度（米；null = 无 GPS 结果）
     * @param networkAccuracy 网络精度（米；null = 无网络结果）
     * @param lastKnownAccuracy 上次已知位置精度（米；null = 无）
     * @param preferGpsOverNetwork 是否 GPS 优先（默认 true）
     * @return 选中的来源；全为 null 时返回 [LocationSource.NONE]
     */
    fun select(
        gpsAccuracy: Float?,
        networkAccuracy: Float?,
        lastKnownAccuracy: Float?,
        preferGpsOverNetwork: Boolean = true,
    ): LocationSource {
        val gps = gpsAccuracy?.takeIf { it.isFinite() && it >= 0f }
        val net = networkAccuracy?.takeIf { it.isFinite() && it >= 0f }
        val last = lastKnownAccuracy?.takeIf { it.isFinite() && it >= 0f }

        if (gps != null && net != null) {
            return if (preferGpsOverNetwork) LocationSource.GPS else LocationSource.NETWORK
        }
        if (gps != null) return LocationSource.GPS
        if (net != null) return LocationSource.NETWORK
        if (last != null) return LocationSource.LAST_KNOWN
        return LocationSource.NONE
    }

    /** 新旧定位是否应替换（新来源更优，或精度提升超过阈值）。 */
    fun shouldUpgrade(
        currentSource: LocationSource?,
        currentAccuracy: Float,
        newSource: LocationSource,
        newAccuracy: Float,
        preferGpsOverNetwork: Boolean = true,
        minImprovementMeters: Float = 5f,
    ): Boolean {
        if (currentSource == null) return true
        val better = select(
            gpsAccuracy = if (newSource == LocationSource.GPS) newAccuracy else null,
            networkAccuracy = if (newSource == LocationSource.NETWORK) newAccuracy else null,
            lastKnownAccuracy = null,
            preferGpsOverNetwork = preferGpsOverNetwork,
        )
        if (better != newSource) {
            // 来源于更低优先级提供者：仅在精度显著提升或当前为 LAST_KNOWN 时采用
            val currentIsBest = select(
                gpsAccuracy = if (currentSource == LocationSource.GPS) currentAccuracy else null,
                networkAccuracy = if (currentSource == LocationSource.NETWORK) currentAccuracy else null,
                lastKnownAccuracy = if (currentSource == LocationSource.LAST_KNOWN) currentAccuracy else null,
                preferGpsOverNetwork = preferGpsOverNetwork,
            )
            if (currentIsBest == newSource) return true
            if (currentSource == LocationSource.LAST_KNOWN) return true
            return newAccuracy.isFinite() && newAccuracy + minImprovementMeters < currentAccuracy
        }
        // 同优先级提供者：精度提升即可替换
        return newAccuracy.isFinite() &&
            (!currentAccuracy.isFinite() || newAccuracy + minImprovementMeters < currentAccuracy)
    }
}
