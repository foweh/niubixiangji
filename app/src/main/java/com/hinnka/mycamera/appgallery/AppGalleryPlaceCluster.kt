package com.hinnka.mycamera.appgallery

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 带坐标的条目（相册项 10 / 16：地点聚类与地图定位）。 */
data class AppGalleryGeoPoint(
    val itemId: String,
    val latitude: Double,
    val longitude: Double,
)

/** 地点聚类结果。 */
data class AppGalleryPlaceCluster(
    /** 稳定 key（网格坐标）。 */
    val key: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    /** 城市/地点名（无则 null → UI 显示「未知地点」并提示可开启联网反查）。 */
    val cityName: String?,
    val itemIds: List<String>,
    /** 簇半径（公里，来自配置）。 */
    val radiusKm: Float,
) {
    val count: Int get() = itemIds.size
}

/**
 * 地点聚类（相册项 10：按地点聚类；相册项 16：地图定位）。
 *
 * 「坐标 → 城市名」三种途径，按可用性依次回退：
 * ① 注入的 [reverseGeocoder]（Android 侧可接 `android.location.Geocoder`，**可能需网络**）；
 * ② 内置离线城市边界表 [AppGalleryOfflineCityIndex]（纯离线，粗粒度）；
 * ③ 返回 null，UI 显示「未知地点」。
 *
 * 诚实说明：离线表按**外接矩形**匹配，边界附近可能落到邻市；精度要求高时可
 * ① 关闭离线表并开启联网反查（`placeClusterOnlineLookupEnabled`，需网络/用户自选），
 * ② 或通过 [AppGalleryOfflineCityIndex.parseCustomTable] 导入自备的精细城市表。
 */
object AppGalleryPlaceClustering {

    /** 地球平均半径（公里）。 */
    const val EARTH_RADIUS_KM = 6371.0

    /** haversine 球面距离（公里）。 */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /** 网格 key（保留 [decimals] 位小数）。 */
    fun gridKey(latitude: Double, longitude: Double, decimals: Int): String {
        val digits = decimals.coerceIn(
            AppGalleryConfig.PLACE_DECIMALS_MIN,
            AppGalleryConfig.PLACE_DECIMALS_MAX,
        )
        val lat = round(latitude, digits)
        val lon = round(longitude, digits)
        return String.format(java.util.Locale.US, "%.${digits}f,%.${digits}f", lat, lon)
    }

    private fun round(value: Double, digits: Int): Double {
        val factor = 10.0.pow(digits)
        return kotlin.math.round(value * factor) / factor
    }

    /** 质心。 */
    fun centroid(points: List<AppGalleryGeoPoint>): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        return points.sumOf { it.latitude } / points.size to
            points.sumOf { it.longitude } / points.size
    }

    /**
     * 聚类：把 [points] 归入半径 ≤ `placeClusterRadiusKm` 的簇。
     *
     * 算法：先按配置网格粗分桶，再对桶内点做「就近归并」。
     * 结果按条目数降序（UI 列表顺序），同数量按 key 升序保证稳定。
     * 非法坐标（NaN / 越界）会被静默跳过。
     */
    fun cluster(
        points: List<AppGalleryGeoPoint>,
        config: AppGalleryConfig = AppGalleryConfig(),
        reverseGeocoder: ((Double, Double) -> String?)? = null,
        cityIndex: List<AppGalleryOfflineCityIndex.CityBoundary> =
            AppGalleryOfflineCityIndex.BUILT_IN,
    ): List<AppGalleryPlaceCluster> {
        val safeConfig = AppGalleryConfig.clamp(config)
        val radius = safeConfig.placeClusterRadiusKm
        val valid = points.filter { isValidCoordinate(it.latitude, it.longitude) }
        if (valid.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, MutableList<AppGalleryGeoPoint>>()
        valid.forEach { point ->
            val key = gridKey(point.latitude, point.longitude, safeConfig.placeClusterGridDecimals)
            buckets.getOrPut(key) { mutableListOf() }.add(point)
        }

        val clusters = ArrayList<AppGalleryPlaceCluster>()
        buckets.entries.sortedBy { it.key }.forEach { (gridKeyValue, bucketPoints) ->
            val (lat, lon) = centroid(bucketPoints)
            val name = resolvePlaceName(
                lat,
                lon,
                safeConfig,
                reverseGeocoder,
                cityIndex,
            )
            clusters.add(
                AppGalleryPlaceCluster(
                    key = gridKeyValue,
                    centerLatitude = lat,
                    centerLongitude = lon,
                    cityName = name,
                    itemIds = bucketPoints.map { it.itemId },
                    radiusKm = radius,
                )
            )
        }

        // 半径内相邻网格的簇再合并（避免同一地点被网格切碎）
        val merged = mergeNeighbours(clusters, radius)
        return merged
            .sortedWith(compareByDescending<AppGalleryPlaceCluster> { it.count }.thenBy { it.key })
    }

    private fun mergeNeighbours(
        clusters: List<AppGalleryPlaceCluster>,
        radiusKm: Float,
    ): List<AppGalleryPlaceCluster> {
        val result = ArrayList<AppGalleryPlaceCluster>()
        val consumed = BooleanArray(clusters.size)
        for (i in clusters.indices) {
            if (consumed[i]) continue
            val group = mutableListOf(clusters[i])
            for (j in i + 1 until clusters.size) {
                if (consumed[j]) continue
                val distance = haversineKm(
                    clusters[i].centerLatitude,
                    clusters[i].centerLongitude,
                    clusters[j].centerLatitude,
                    clusters[j].centerLongitude,
                )
                if (distance <= radiusKm) {
                    group.add(clusters[j])
                    consumed[j] = true
                }
            }
            consumed[i] = true
            if (group.size == 1) {
                result.add(clusters[i])
            } else {
                val itemIds = group.flatMap { it.itemIds }
                val lat = group.sumOf { it.centerLatitude } / group.size
                val lon = group.sumOf { it.centerLongitude } / group.size
                result.add(
                    AppGalleryPlaceCluster(
                        key = group.minOf { it.key },
                        centerLatitude = lat,
                        centerLongitude = lon,
                        cityName = group.firstNotNullOfOrNull { it.cityName },
                        itemIds = itemIds,
                        radiusKm = radiusKm,
                    )
                )
            }
        }
        return result
    }

    /** 名称解析（注入反查 → 离线表 → null）。 */
    fun resolvePlaceName(
        latitude: Double,
        longitude: Double,
        config: AppGalleryConfig = AppGalleryConfig(),
        reverseGeocoder: ((Double, Double) -> String?)? = null,
        cityIndex: List<AppGalleryOfflineCityIndex.CityBoundary> =
            AppGalleryOfflineCityIndex.BUILT_IN,
    ): String? {
        val safeConfig = AppGalleryConfig.clamp(config)
        if (safeConfig.placeClusterOnlineLookupEnabled && reverseGeocoder != null) {
            runCatching { reverseGeocoder(latitude, longitude) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        if (safeConfig.placeClusterUseOfflineCityIndex) {
            AppGalleryOfflineCityIndex.lookup(latitude, longitude, cityIndex)?.let { return it }
        }
        return null
    }

    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        !latitude.isNaN() && !longitude.isNaN() &&
            abs(latitude) <= 90.0 && abs(longitude) <= 180.0

    /**
     * 外部地图跳转 URI（相册项 16：外部地图跳转入口）。
     * 返回通用 geo: URI，由 Android 侧 `Intent(ACTION_VIEW)` 消费（不引入地图 SDK）。
     */
    fun externalMapUri(latitude: Double, longitude: Double, label: String? = null): String {
        val query = if (label.isNullOrBlank()) {
            "$latitude,$longitude"
        } else {
            "$latitude,$longitude(${label.replace("(", "_").replace(")", "_")})"
        }
        return "geo:$query?z=14"
    }
}

/**
 * 离线城市边界表（相册项 10 的离线反查）。
 *
 * 数据形态：外接矩形 + 优先级（面积越小越优先）。
 * 表内容为**粗粒度内置参考数据**（覆盖中国主要城市与常见境外城市），
 * 可通过 [parseCustomTable] 用自备精细表整体替换（配置文件路径见补丁 CONFIG 段）。
 */
object AppGalleryOfflineCityIndex {

    data class CityBoundary(
        val name: String,
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double,
    ) {
        /** 外接矩形面积（平方度），用于优先级排序（越小越具体）。 */
        val areaDegrees: Double
            get() = (maxLatitude - minLatitude) * (maxLongitude - minLongitude)

        fun contains(latitude: Double, longitude: Double): Boolean =
            latitude >= minLatitude && latitude <= maxLatitude &&
                longitude >= minLongitude && longitude <= maxLongitude
    }

    /** 内置表（粗粒度）。 */
    val BUILT_IN: List<CityBoundary> = listOf(
        CityBoundary("北京市", 39.4, 41.1, 115.4, 117.5),
        CityBoundary("上海市", 30.6, 31.9, 120.8, 122.1),
        CityBoundary("广州市", 22.5, 23.9, 112.9, 114.1),
        CityBoundary("深圳市", 22.4, 22.9, 113.7, 114.7),
        CityBoundary("天津市", 38.5, 40.3, 116.7, 118.1),
        CityBoundary("重庆市", 28.2, 32.2, 105.3, 110.2),
        CityBoundary("成都市", 30.0, 31.5, 103.0, 104.9),
        CityBoundary("杭州市", 29.2, 30.6, 119.0, 120.7),
        CityBoundary("南京市", 31.1, 32.6, 118.3, 119.3),
        CityBoundary("武汉市", 29.9, 31.4, 113.7, 115.1),
        CityBoundary("西安市", 33.7, 34.7, 107.6, 109.8),
        CityBoundary("沈阳市", 41.1, 43.0, 122.2, 123.8),
        CityBoundary("长春市", 43.4, 44.5, 124.6, 125.9),
        CityBoundary("吉林市", 42.8, 44.0, 125.6, 127.5),
        CityBoundary("哈尔滨市", 44.4, 46.0, 125.8, 128.0),
        CityBoundary("乌鲁木齐市", 43.4, 44.2, 86.9, 88.2),
        CityBoundary("拉萨市", 29.3, 30.2, 90.7, 91.5),
        CityBoundary("昆明市", 24.4, 25.7, 102.4, 103.3),
        CityBoundary("长沙市", 27.9, 28.6, 112.6, 113.4),
        CityBoundary("郑州市", 34.4, 35.1, 113.2, 114.2),
        CityBoundary("福州市", 25.7, 26.5, 118.9, 119.8),
        CityBoundary("厦门市", 24.4, 24.8, 117.9, 118.4),
        CityBoundary("济南市", 36.4, 37.0, 116.6, 117.4),
        CityBoundary("青岛市", 35.9, 36.6, 119.9, 120.8),
        CityBoundary("石家庄市", 37.7, 38.4, 114.1, 114.9),
        CityBoundary("太原市", 37.5, 38.2, 112.2, 113.0),
        CityBoundary("合肥市", 31.5, 32.2, 116.9, 117.6),
        CityBoundary("南昌市", 28.4, 29.1, 115.6, 116.2),
        CityBoundary("贵阳市", 26.3, 27.0, 106.3, 107.1),
        CityBoundary("南宁市", 22.5, 23.1, 108.0, 108.7),
        CityBoundary("海口市", 19.7, 20.3, 110.0, 110.7),
        CityBoundary("兰州市", 35.7, 36.4, 103.4, 104.1),
        CityBoundary("银川市", 38.2, 38.8, 105.9, 106.6),
        CityBoundary("西宁市", 36.3, 37.1, 101.3, 102.2),
        CityBoundary("呼和浩特市", 40.5, 41.1, 111.3, 112.1),
        CityBoundary("香港", 22.15, 22.55, 113.80, 114.40),
        CityBoundary("澳门", 22.10, 22.25, 113.50, 113.65),
        CityBoundary("台北市", 24.90, 25.20, 121.40, 121.70),
        CityBoundary("东京", 35.40, 35.90, 139.40, 140.00),
        CityBoundary("首尔", 37.40, 37.70, 126.70, 127.20),
        CityBoundary("新加坡", 1.20, 1.50, 103.60, 104.10),
        CityBoundary("曼谷", 13.60, 13.90, 100.30, 100.70),
        CityBoundary("纽约", 40.50, 40.90, -74.30, -73.70),
        CityBoundary("洛杉矶", 33.70, 34.30, -118.70, -118.00),
        CityBoundary("伦敦", 51.30, 51.70, -0.50, 0.20),
        CityBoundary("巴黎", 48.70, 49.00, 2.10, 2.60),
        CityBoundary("悉尼", -34.10, -33.60, 150.80, 151.40),
    )

    /**
     * 反查（命中面积最小的矩形；未命中返回 null）。
     */
    fun lookup(
        latitude: Double,
        longitude: Double,
        table: List<CityBoundary> = BUILT_IN,
    ): String? {
        if (!AppGalleryPlaceClustering.isValidCoordinate(latitude, longitude)) return null
        return table
            .asSequence()
            .filter { it.contains(latitude, longitude) }
            .minByOrNull { it.areaDegrees }
            ?.name
    }

    /**
     * 解析自备城市表（每行：`名称,minLat,maxLat,minLon,maxLon`，`#` 开头为注释）。
     * 非法行跳过，不抛异常；解析结果为空时调用方应回退到 [BUILT_IN]。
     */
    fun parseCustomTable(content: String?): List<CityBoundary> {
        if (content.isNullOrBlank()) return emptyList()
        val out = ArrayList<CityBoundary>()
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(',', '，').map { it.trim() }
            if (parts.size != 5) return@forEach
            val name = parts[0].takeIf { it.isNotEmpty() } ?: return@forEach
            val minLat = parts[1].toDoubleOrNull() ?: return@forEach
            val maxLat = parts[2].toDoubleOrNull() ?: return@forEach
            val minLon = parts[3].toDoubleOrNull() ?: return@forEach
            val maxLon = parts[4].toDoubleOrNull() ?: return@forEach
            if (abs(minLat) > 90 || abs(maxLat) > 90) return@forEach
            if (abs(minLon) > 180 || abs(maxLon) > 180) return@forEach
            out.add(
                CityBoundary(
                    name = name,
                    minLatitude = minOf(minLat, maxLat),
                    maxLatitude = maxOf(minLat, maxLat),
                    minLongitude = minOf(minLon, maxLon),
                    maxLongitude = maxOf(minLon, maxLon),
                )
            )
            if (out.size >= MAX_ENTRIES) return out
        }
        return out
    }

    /** 自备表条目上限（防配置文件塞爆内存）。 */
    const val MAX_ENTRIES = 20_000
}
