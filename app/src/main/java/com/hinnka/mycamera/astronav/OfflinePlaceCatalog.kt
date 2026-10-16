/*
 * Copyright 2024 bjzhou / PhotonCamera (Apache-2.0)
 *
 * 二次开发新增模块：星空/天文定位与传感器融合（astronav）。
 * 本文件为新增文件：离线地点表（城市名 -> 经纬度）+ 模糊搜索。
 *
 * 说明（PATTERN_SPEC §9.4：代码量控制）：
 *  - 数据为**自建小表**（约 80 条：中国省会/主要城市/地级市 + 若干国际城市），
 *    坐标取到小数点后 2–4 位（城市量级误差 < 2km，对星表方位影响 < 0.02°，
 *    远小于传感器指向误差），用于「定位失败时的手动兜底」。
 *  - 未引入任何地理编码库 / 未联网（无 Google Geocoder、无第三方 SDK），
 *    因此**不需要新增权限、不需要联网**；精度要求更高时用户可直接输入经纬度，
 *    或配置在线地点服务（见 changelog 的「可选项」）。
 *  - 海拔仅对少数高原/盆地城市给出近似值，其余为 0（海拔对磁偏角影响 < 0.05°）。
 */
package com.hinnka.mycamera.astronav

/**
 * 离线地点条目。
 *
 * @param nameZh 中文名（唯一，UI 主显示）
 * @param nameEn 英文/拼音名（搜索用）
 * @param pinyin 拼音（小写无空格，搜索用）
 * @param region 省/国家（辅助展示与消歧）
 * @param latitudeDegrees 纬度（度）
 * @param longitudeDegrees 经度（度，东正西负）
 * @param altitudeMeters 海拔近似值（米，未知为 0）
 */
data class PlaceEntry(
    val nameZh: String,
    val nameEn: String,
    val pinyin: String,
    val region: String,
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double = 0.0,
) {
    /** 转为观测点。 */
    fun toGeoPoint(): GeoPoint = GeoPoint.of(
        latitudeDegrees = latitudeDegrees,
        longitudeDegrees = longitudeDegrees,
        altitudeMeters = altitudeMeters,
        label = nameZh,
    )
}

/**
 * 离线地点目录（纯数据 + 纯函数搜索，可 JVM 单测）。
 */
object OfflinePlaceCatalog {

    /** 搜索命中上限（防 UI 一次渲染过多项）。 */
    const val MAX_RESULTS = 20

    /**
     * 离线地点表。中国主要城市在前，国际城市在后。
     * 坐标为城市中心量级近似值（±2km），仅供兜底定位。
     */
    val PLACES: List<PlaceEntry> = listOf(
        // ---- 中国：直辖市与省会 ----
        PlaceEntry("北京", "Beijing", "beijing", "北京市", 39.9042, 116.4074, 44.0),
        PlaceEntry("上海", "Shanghai", "shanghai", "上海市", 31.2304, 121.4737, 4.0),
        PlaceEntry("天津", "Tianjin", "tianjin", "天津市", 39.0842, 117.2009, 5.0),
        PlaceEntry("重庆", "Chongqing", "chongqing", "重庆市", 29.5630, 106.5516, 244.0),
        PlaceEntry("广州", "Guangzhou", "guangzhou", "广东省", 23.1291, 113.2644, 21.0),
        PlaceEntry("深圳", "Shenzhen", "shenzhen", "广东省", 22.5431, 114.0579, 6.0),
        PlaceEntry("成都", "Chengdu", "chengdu", "四川省", 30.5728, 104.0668, 500.0),
        PlaceEntry("杭州", "Hangzhou", "hangzhou", "浙江省", 30.2741, 120.1551, 10.0),
        PlaceEntry("南京", "Nanjing", "nanjing", "江苏省", 32.0603, 118.7969, 20.0),
        PlaceEntry("武汉", "Wuhan", "wuhan", "湖北省", 30.5928, 114.3055, 23.0),
        PlaceEntry("西安", "Xian", "xian", "陕西省", 34.3416, 108.9398, 400.0),
        PlaceEntry("郑州", "Zhengzhou", "zhengzhou", "河南省", 34.7466, 113.6254, 110.0),
        PlaceEntry("长沙", "Changsha", "changsha", "湖南省", 28.2282, 112.9388, 45.0),
        PlaceEntry("沈阳", "Shenyang", "shenyang", "辽宁省", 41.8057, 123.4315, 45.0),
        PlaceEntry("长春", "Changchun", "changchun", "吉林省", 43.8171, 125.3235, 222.0),
        PlaceEntry("哈尔滨", "Harbin", "haerbin", "黑龙江省", 45.8038, 126.5350, 150.0),
        PlaceEntry("济南", "Jinan", "jinan", "山东省", 36.6512, 117.1201, 52.0),
        PlaceEntry("合肥", "Hefei", "hefei", "安徽省", 31.8206, 117.2272, 40.0),
        PlaceEntry("福州", "Fuzhou", "fuzhou", "福建省", 26.0745, 119.2965, 15.0),
        PlaceEntry("南昌", "Nanchang", "nanchang", "江西省", 28.6820, 115.8579, 25.0),
        PlaceEntry("昆明", "Kunming", "kunming", "云南省", 25.0389, 102.7183, 1890.0),
        PlaceEntry("贵阳", "Guiyang", "guiyang", "贵州省", 26.6470, 106.6302, 1100.0),
        PlaceEntry("南宁", "Nanning", "nanning", "广西", 22.8170, 108.3665, 76.0),
        PlaceEntry("海口", "Haikou", "haikou", "海南省", 20.0442, 110.1999, 15.0),
        PlaceEntry("兰州", "Lanzhou", "lanzhou", "甘肃省", 36.0611, 103.8343, 1520.0),
        PlaceEntry("西宁", "Xining", "xining", "青海省", 36.6171, 101.7782, 2260.0),
        PlaceEntry("银川", "Yinchuan", "yinchuan", "宁夏", 38.4872, 106.2309, 1110.0),
        PlaceEntry("乌鲁木齐", "Urumqi", "wulumuqi", "新疆", 43.8256, 87.6168, 800.0),
        PlaceEntry("拉萨", "Lhasa", "lasa", "西藏", 29.6520, 91.1721, 3650.0),
        PlaceEntry("呼和浩特", "Hohhot", "huhehaote", "内蒙古", 40.8414, 111.7519, 1040.0),
        PlaceEntry("太原", "Taiyuan", "taiyuan", "山西省", 37.8706, 112.5489, 780.0),
        PlaceEntry("石家庄", "Shijiazhuang", "shijiazhuang", "河北省", 38.0428, 114.5149, 80.0),
        // ---- 中国：其它常用城市 ----
        PlaceEntry("青岛", "Qingdao", "qingdao", "山东省", 36.0671, 120.3826, 25.0),
        PlaceEntry("大连", "Dalian", "dalian", "辽宁省", 38.9140, 121.6147, 30.0),
        PlaceEntry("苏州", "Suzhou", "suzhou", "江苏省", 31.2989, 120.5853, 5.0),
        PlaceEntry("宁波", "Ningbo", "ningbo", "浙江省", 29.8683, 121.5440, 5.0),
        PlaceEntry("无锡", "Wuxi", "wuxi", "江苏省", 31.4912, 120.3119, 5.0),
        PlaceEntry("温州", "Wenzhou", "wenzhou", "浙江省", 27.9938, 120.6994, 10.0),
        PlaceEntry("厦门", "Xiamen", "xiamen", "福建省", 24.4798, 118.0894, 15.0),
        PlaceEntry("佛山", "Foshan", "foshan", "广东省", 23.0218, 113.1219, 8.0),
        PlaceEntry("东莞", "Dongguan", "dongguan", "广东省", 23.0207, 113.7518, 20.0),
        PlaceEntry("珠海", "Zhuhai", "zhuhai", "广东省", 22.2707, 113.5767, 10.0),
        PlaceEntry("三亚", "Sanya", "sanya", "海南省", 18.2528, 109.5119, 7.0),
        PlaceEntry("桂林", "Guilin", "guilin", "广西", 25.2736, 110.2900, 150.0),
        PlaceEntry("洛阳", "Luoyang", "luoyang", "河南省", 34.6197, 112.4540, 145.0),
        PlaceEntry("徐州", "Xuzhou", "xuzhou", "江苏省", 34.2058, 117.2848, 40.0),
        PlaceEntry("烟台", "Yantai", "yantai", "山东省", 37.4638, 121.4479, 30.0),
        PlaceEntry("唐山", "Tangshan", "tangshan", "河北省", 39.6304, 118.1802, 20.0),
        PlaceEntry("保定", "Baoding", "baoding", "河北省", 38.8740, 115.4646, 20.0),
        PlaceEntry("邯郸", "Handan", "handan", "河北省", 36.6253, 114.5391, 60.0),
        PlaceEntry("秦皇岛", "Qinhuangdao", "qinhuangdao", "河北省", 39.9354, 119.5998, 10.0),
        PlaceEntry("吉林", "Jilin", "jilin", "吉林省", 43.8378, 126.5495, 200.0),
        PlaceEntry("延吉", "Yanji", "yanji", "吉林省", 42.9084, 129.5096, 180.0),
        PlaceEntry("通化", "Tonghua", "tonghua", "吉林省", 41.7211, 125.9398, 400.0),
        PlaceEntry("四平", "Siping", "siping", "吉林省", 43.1704, 124.3505, 160.0),
        PlaceEntry("松原", "Songyuan", "songyuan", "吉林省", 45.1411, 124.8250, 130.0),
        PlaceEntry("白城", "Baicheng", "baicheng", "吉林省", 45.6196, 122.8391, 150.0),
        PlaceEntry("白山", "Baishan", "baishan", "吉林省", 41.9408, 126.4281, 480.0),
        PlaceEntry("齐齐哈尔", "Qiqihar", "qiqihaer", "黑龙江省", 47.3543, 123.9182, 145.0),
        PlaceEntry("大庆", "Daqing", "daqing", "黑龙江省", 46.5891, 125.1039, 145.0),
        PlaceEntry("包头", "Baotou", "baotou", "内蒙古", 40.6574, 109.8403, 1060.0),
        PlaceEntry("鄂尔多斯", "Ordos", "eerduosi", "内蒙古", 39.6086, 109.7813, 1300.0),
        PlaceEntry("格尔木", "Golmud", "geermu", "青海省", 36.4067, 94.9033, 2810.0),
        PlaceEntry("敦煌", "Dunhuang", "dunhuang", "甘肃省", 40.1421, 94.6618, 1140.0),
        PlaceEntry("丽江", "Lijiang", "lijiang", "云南省", 26.8721, 100.2299, 2400.0),
        PlaceEntry("香格里拉", "Shangri-La", "xianggelila", "云南省", 27.8253, 99.7069, 3280.0),
        PlaceEntry("稻城", "Daocheng", "daocheng", "四川省", 29.0378, 100.2975, 3750.0),
        // ---- 港澳台 ----
        PlaceEntry("香港", "Hong Kong", "xianggang", "香港", 22.3193, 114.1694, 20.0),
        PlaceEntry("澳门", "Macau", "aomen", "澳门", 22.1987, 113.5439, 10.0),
        PlaceEntry("台北", "Taipei", "taibei", "台湾", 25.0330, 121.5654, 10.0),
        PlaceEntry("高雄", "Kaohsiung", "gaoxiong", "台湾", 22.6273, 120.3014, 10.0),
        // ---- 国际（常用参考点）----
        PlaceEntry("东京", "Tokyo", "dongjing", "日本", 35.6762, 139.6503, 40.0),
        PlaceEntry("首尔", "Seoul", "shouer", "韩国", 37.5665, 126.9780, 38.0),
        PlaceEntry("新加坡", "Singapore", "xinjiapo", "新加坡", 1.3521, 103.8198, 15.0),
        PlaceEntry("曼谷", "Bangkok", "mangu", "泰国", 13.7563, 100.5018, 5.0),
        PlaceEntry("迪拜", "Dubai", "dibai", "阿联酋", 25.2048, 55.2708, 5.0),
        PlaceEntry("莫斯科", "Moscow", "mosike", "俄罗斯", 55.7558, 37.6173, 150.0),
        PlaceEntry("伦敦", "London", "lundun", "英国", 51.5074, -0.1278, 20.0),
        PlaceEntry("巴黎", "Paris", "bali", "法国", 48.8566, 2.3522, 35.0),
        PlaceEntry("柏林", "Berlin", "bolin", "德国", 52.5200, 13.4050, 34.0),
        PlaceEntry("纽约", "New York", "niuyue", "美国", 40.7128, -74.0060, 10.0),
        PlaceEntry("洛杉矶", "Los Angeles", "luoshanji", "美国", 34.0522, -118.2437, 70.0),
        PlaceEntry("温哥华", "Vancouver", "wengehua", "加拿大", 49.2827, -123.1207, 3.0),
        PlaceEntry("悉尼", "Sydney", "xini", "澳大利亚", -33.8688, 151.2093, 20.0),
        PlaceEntry("开罗", "Cairo", "kailuo", "埃及", 30.0444, 31.2357, 23.0),
    )

    private val byZh: Map<String, PlaceEntry> = PLACES.associateBy { it.nameZh }

    /** 全部条目数（自检用）。 */
    val size: Int get() = PLACES.size

    /** 精确按中文名取（未命中返回 null）。 */
    fun byName(name: String): PlaceEntry? = byZh[name.trim()]

    /** 按中文名/英文名/拼音取（大小写不敏感，去空格）。 */
    fun byAnyName(name: String): PlaceEntry? {
        val q = normalize(name)
        if (q.isEmpty()) return null
        return PLACES.firstOrNull { normalize(it.nameZh) == q } ?: PLACES.firstOrNull {
            normalize(it.nameEn) == q || it.pinyin == q
        }
    }

    /**
     * 模糊搜索：中文名 / 英文名 / 拼音 / 省份包含匹配。
     *
     * 排序规则（相关度由高到低）：
     *  1. 完全相等
     *  2. 前缀匹配（拼音/英文/中文）
     *  3. 包含匹配（拼音/英文）
     *  4. 中文名包含匹配
     * 同级按名称长度升序（更短 = 更可能是主城）。
     *
     * @param query 搜索词（空串返回空表，不返回全表）
     * @param limit 返回上限（1..[MAX_RESULTS]，越界自动 clamp）
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<PlaceEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val cap = limit.coerceIn(1, MAX_RESULTS)

        val scored = ArrayList<Pair<Int, PlaceEntry>>(16)
        for (p in PLACES) {
            val zh = normalize(p.nameZh)
            val en = normalize(p.nameEn)
            val score = when {
                zh == q || en == q || p.pinyin == q -> 0
                p.pinyin.startsWith(q) || en.startsWith(q) || zh.startsWith(q) -> 1
                p.pinyin.contains(q) || en.contains(q) -> 2
                zh.contains(q) -> 3
                else -> -1
            }
            if (score >= 0) scored += score to p
        }
        return scored
            .sortedWith(compareBy({ it.first }, { it.second.nameZh.length }, { it.second.nameZh }))
            .take(cap)
            .map { it.second }
    }

    /** 最近的地点（经纬度粗筛，用于「当前位置 ≈ 某城市」提示）。 */
    fun nearest(latitudeDegrees: Double, longitudeDegrees: Double): PlaceEntry? {
        if (!latitudeDegrees.isFinite() || !longitudeDegrees.isFinite()) return null
        val origin = GeoPoint.of(latitudeDegrees, longitudeDegrees)
        return PLACES.minByOrNull { origin.distanceMetersTo(it.toGeoPoint()) }
    }

    /** 归一化：去空格、转小写（中文不受影响）。 */
    private fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "").replace("　", "")
}
