package com.hinnka.mycamera.longexposure

/**
 * 慢门场景预设（车流光轨 / 瀑布拉丝 / 雨丝）。
 *
 * 所有字段**全部可改**（曝光时间 / ISO / 帧数 / 叠加类型 / 暗帧开关），预设本身也可增删
 * （见 [SlowShutterPresetRegistry]）。
 *
 * 取值范围（越界 clamp）：
 *  - [shutterSpeedNs] 1_000_000 .. 60_000_000_000（1ms .. 60s）
 *  - [iso] 50 .. 102400
 *  - [frameCount] 2 .. [LongExposureLimits.frameCountLimit]
 *  - [id]/[displayName] 空白时回退为兜底值
 */
data class SlowShutterPreset(
    val id: String,
    val displayName: String,
    val shutterSpeedNs: Long,
    val iso: Int,
    val frameCount: Int,
    val stackMode: LongExposureStackMode,
    val darkFrameEnabled: Boolean = false,
) {
    fun clampWithReport(): Pair<SlowShutterPreset, List<String>> {
        val reports = ArrayList<String>(4)
        val limit = LongExposureLimits.frameCountLimit()
        val fixedId = id.trim().ifBlank { FALLBACK_ID }
        if (fixedId != id) reports.add("preset.id: '$id' -> '$fixedId'")
        val fixedName = displayName.trim().ifBlank { fixedId }
        if (fixedName != displayName) reports.add("preset.displayName: '$displayName' -> '$fixedName'")
        val shutter = shutterSpeedNs.coerceIn(SHUTTER_MIN_NS, SHUTTER_MAX_NS)
        if (shutter != shutterSpeedNs) reports.add("preset.shutterSpeedNs: $shutterSpeedNs -> $shutter")
        val sensitivity = iso.coerceIn(ISO_MIN, ISO_MAX)
        if (sensitivity != iso) reports.add("preset.iso: $iso -> $sensitivity")
        val frames = frameCount.coerceIn(LongExposureLimits.FRAME_MIN, limit)
        LongExposureLimits.frameClampReport("preset.frameCount", frameCount, frames, reports)
        return copy(
            id = fixedId,
            displayName = fixedName,
            shutterSpeedNs = shutter,
            iso = sensitivity,
            frameCount = frames,
        ) to reports
    }

    fun clamped(): SlowShutterPreset = clampWithReport().first

    /** 曝光时间是否落在预设可表达范围内（UI 可据此标注"越界已修正"）。 */
    fun isShutterInRange(): Boolean = shutterSpeedNs in SHUTTER_MIN_NS..SHUTTER_MAX_NS

    companion object {
        const val SHUTTER_MIN_NS = 1_000_000L
        const val SHUTTER_MAX_NS = 60_000_000_000L
        const val ISO_MIN = 50
        const val ISO_MAX = 102_400
        const val FALLBACK_ID = "preset_unnamed"
    }
}

/**
 * 内置预设与预设仓库。
 *
 * 内置三套（可被用户改参数、可删除、可新增自定义）：
 *  - 车流光轨：4s / ISO100 / 20 帧 / MAX（光轨连续不断）；
 *  - 瀑布拉丝：0.5s / ISO100 / 40 帧 / MEAN（水流乳化、降噪）；
 *  - 雨丝：1/60s / ISO400 / 24 帧 / MAX（雨丝亮线累积）。
 */
object SlowShutterPresets {

    const val ID_CAR_LIGHT_TRAILS = "car_light_trails"
    const val ID_SILK_WATERFALL = "silk_waterfall"
    const val ID_RAIN_STREAKS = "rain_streaks"

    /** 车流光轨。 */
    val CAR_LIGHT_TRAILS = SlowShutterPreset(
        id = ID_CAR_LIGHT_TRAILS,
        displayName = "车流光轨",
        shutterSpeedNs = 4_000_000_000L,
        iso = 100,
        frameCount = 20,
        stackMode = LongExposureStackMode.MAX,
        darkFrameEnabled = false,
    )

    /** 瀑布拉丝。 */
    val SILK_WATERFALL = SlowShutterPreset(
        id = ID_SILK_WATERFALL,
        displayName = "瀑布拉丝",
        shutterSpeedNs = 500_000_000L,
        iso = 100,
        frameCount = 40,
        stackMode = LongExposureStackMode.MEAN,
        darkFrameEnabled = false,
    )

    /** 雨丝。 */
    val RAIN_STREAKS = SlowShutterPreset(
        id = ID_RAIN_STREAKS,
        displayName = "雨丝",
        shutterSpeedNs = 16_666_666L,
        iso = 400,
        frameCount = 24,
        stackMode = LongExposureStackMode.MAX,
        darkFrameEnabled = false,
    )

    /** 内置预设清单（顺序即 UI 展示顺序）。 */
    fun defaults(): List<SlowShutterPreset> = listOf(CAR_LIGHT_TRAILS, SILK_WATERFALL, RAIN_STREAKS)
}

/**
 * 预设仓库：内置三套 + 用户新增，全部落库前 clamp。
 *
 * 线程安全（内部锁），可在设置页与拍摄链路并发访问。
 */
class SlowShutterPresetRegistry(
    initial: List<SlowShutterPreset> = SlowShutterPresets.defaults(),
) {
    private val lock = Any()
    private val items = LinkedHashMap<String, SlowShutterPreset>()

    init {
        for (preset in initial) {
            val (clamped, _) = preset.clampWithReport()
            items[clamped.id] = clamped
        }
    }

    /** 当前预设快照（顺序 = 插入顺序）。 */
    fun all(): List<SlowShutterPreset> = synchronized(lock) { items.values.toList() }

    fun byId(id: String): SlowShutterPreset? = synchronized(lock) { items[id] }

    /**
     * 新增或覆盖（按 id）。返回实际存入（clamp 后）的预设；
     * id 与内置冲突时即为"改内置参数"。
     */
    fun upsert(preset: SlowShutterPreset): SlowShutterPreset {
        val (clamped, _) = preset.clampWithReport()
        synchronized(lock) { items[clamped.id] = clamped }
        return clamped
    }

    /** 删除。内置预设同样允许删除（用户可裁掉不用的场景）。 */
    fun remove(id: String): Boolean = synchronized(lock) { items.remove(id) != null }

    /** 只保留给定 id（用于"仅显示常用预设"）。 */
    fun retainOnly(ids: Collection<String>): List<SlowShutterPreset> = synchronized(lock) {
        items.keys.retainAll(ids.toSet())
        items.values.toList()
    }

    /** 恢复内置三套（清空用户自定义）。 */
    fun resetToDefaults(): List<SlowShutterPreset> = synchronized(lock) {
        items.clear()
        for (preset in SlowShutterPresets.defaults()) items[preset.id] = preset
        items.values.toList()
    }

    fun size(): Int = synchronized(lock) { items.size }
}
