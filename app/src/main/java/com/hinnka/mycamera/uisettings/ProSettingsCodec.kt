package com.hinnka.mycamera.uisettings

/**
 * 「Pro 设置」的键 → 值编解码。
 *
 * 页面本身**不持有**任何 DataStore / SharedPreferences 实例（由集成层注入读写端口），
 * 这里只负责把「偏好快照 Map」与「页面值 Map」互相转换，保证：
 * - 读：缺键 → 默认值；脏值 → [ProSettingSpec.normalize] 收敛；
 * - 写：先收敛再落盘；等于默认值的项**不写**（与各模块 Codec「只写非默认值」的约定一致）；
 * - 无键项（内存态 / 纯 UI 状态 / 占位 / 导航）既不读键也不写盘，直接从快照里剔除；
 * - 未登记的键（模块私有键，例如 `lens_correction_*` 之外的旧键）原样保留，不做清理。
 */
object ProSettingsCodec {

    /**
     * 「打包容器」分隔符。
     *
     * 少数模块把全部参数序列化成**一个**字符串键（videomodes 的 `video_modes_config`、
     * appgallery 的 `config_payload`），其字段在本页统一写成 `容器 :: 字段`。
     * 这类项**不能**用扁平快照直接读写（见 [encodePacked] / [decodePacked]），
     * 容器内 `字符串 ⇄ 字段表` 的拆装由集成层调用对应模块 Codec 完成。
     */
    const val PACKED_SEPARATOR = " :: "

    /** 值差异（供「改动预览 / 提交前确认」使用）。 */
    data class ProSettingDiff(
        val id: String,
        val title: String,
        val from: String,
        val to: String,
        val storageKey: String,
    )

    /** 是否「打包容器」项。 */
    fun isPacked(setting: ProSettingSpec): Boolean = setting.storageKey.contains(PACKED_SEPARATOR)

    /** 该设置项的容器键（打包项取 `::` 之前，其余等于自身）。 */
    fun containerKeyOf(setting: ProSettingSpec): String =
        if (isPacked(setting)) setting.storageKey.substringBefore(PACKED_SEPARATOR) else setting.storageKey

    /** 该设置项在容器内的字段名（非打包项返回空串）。 */
    fun fieldKeyOf(setting: ProSettingSpec): String =
        if (isPacked(setting)) setting.storageKey.substringAfter(PACKED_SEPARATOR) else ""

    /** 该设置项是否参与**扁平**持久化（打包项、无键项、占位、导航都不参与）。 */
    fun isPersistable(setting: ProSettingSpec): Boolean =
        setting.storageKey.isNotBlank() &&
            !isPacked(setting) &&
            setting.control != ProSettingControl.PLACEHOLDER &&
            setting.control != ProSettingControl.NAV

    /** 该设置项是否属于「打包容器」项（可编辑，但要走 [encodePacked] / [decodePacked]）。 */
    fun isPackedPersistable(setting: ProSettingSpec): Boolean =
        isPacked(setting) &&
            setting.control != ProSettingControl.PLACEHOLDER &&
            setting.control != ProSettingControl.NAV

    /** 全部打包容器项。 */
    val packedSettings: List<ProSettingSpec>
        get() = ProSettingsRegistry.settings.filter { isPackedPersistable(it) }

    /** 全部设置项的默认值快照。 */
    fun defaults(): Map<String, String> =
        ProSettingsRegistry.settings.associate { it.id to it.defaultValue }

    /**
     * 读：把偏好快照解成页面值。
     *
     * @param preferences 键 → 原始字符串（模块 Codec 的输出；值可为 null 表示缺键）
     */
    fun decode(preferences: Map<String, String?>): Map<String, String> =
        ProSettingsRegistry.settings.associate { setting ->
            setting.id to if (isPersistable(setting)) setting.readFrom(preferences).value else setting.defaultValue
        }

    /** 读单键（页面局部刷新用）。 */
    fun decodeKey(preferences: Map<String, String?>, settingId: String): String? =
        ProSettingsRegistry.settingById[settingId]?.let { setting ->
            if (isPersistable(setting)) setting.readFrom(preferences).value else setting.defaultValue
        }

    /**
     * 写：把页面值编成偏好快照。
     *
     * 只输出「有键」且「非默认」的项 —— 这样一次提交不会覆盖其它页面/模块写的同容器键。
     */
    fun encode(values: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ProSettingsRegistry.settings.forEach { setting ->
            if (!isPersistable(setting)) return@forEach
            val raw = values[setting.id] ?: return@forEach
            val coerced = setting.writeTo(raw).value
            if (coerced != setting.defaultValue) out[setting.storageKey] = coerced
        }
        return out
    }

    /** 逐项收敛（提交前统一过一遍，返回「id → 收敛结果」；扁平与打包项都覆盖）。 */
    fun coerceAll(values: Map<String, String?>): Map<String, ProCoerceResult> =
        ProSettingsRegistry.settings
            .filter { isPersistable(it) || isPackedPersistable(it) }
            .associate { it.id to it.writeTo(values[it.id]) }

    /** 单键收敛。 */
    fun coerce(settingId: String, raw: String?): ProCoerceResult? =
        ProSettingsRegistry.settingById[settingId]?.let { it.normalize(raw) }

    /** 相对默认值的差异列表（按注册顺序）。 */
    fun diff(values: Map<String, String?>): List<ProSettingDiff> =
        ProSettingsRegistry.settings.mapNotNull { setting ->
            if (!isPersistable(setting)) return@mapNotNull null
            val coerced = setting.writeTo(values[setting.id]).value
            if (coerced == setting.defaultValue) {
                null
            } else {
                ProSettingDiff(
                    id = setting.id,
                    title = setting.title,
                    from = setting.defaultValue,
                    to = coerced,
                    storageKey = setting.storageKey,
                )
            }
        }

    /** 差异按容器（DataStore / SharedPreferences / 打包容器）分组：集成层可一次性提交同一容器。 */
    fun diffByContainer(values: Map<String, String?>): Map<String, List<ProSettingDiff>> =
        allDiffs(values).groupBy { diff ->
            val setting = ProSettingsRegistry.settingById[diff.id]
            val slot = setting?.storage?.displayName.orEmpty()
            if (setting != null && isPacked(setting)) {
                "$slot → ${containerKeyOf(setting)}（打包）"
            } else {
                slot
            }
        }

    /** 扁平 + 打包的全部差异（[diff] 只给扁平项，本函数给全量）。 */
    fun allDiffs(values: Map<String, String?>): List<ProSettingDiff> =
        ProSettingsRegistry.settings.mapNotNull { setting ->
            val persistable = isPersistable(setting) || isPackedPersistable(setting)
            if (!persistable) return@mapNotNull null
            val coerced = setting.writeTo(values[setting.id]).value
            if (coerced == setting.defaultValue) {
                null
            } else {
                ProSettingDiff(
                    id = setting.id,
                    title = setting.title,
                    from = setting.defaultValue,
                    to = coerced,
                    storageKey = setting.storageKey,
                )
            }
        }

    // ─────────────────────────────── 打包容器（单键 JSON / 序列化字符串）

    /**
     * 读（打包容器）。
     *
     * @param fields 集成层用模块 Codec 把容器字符串解出的「字段名 → 值」表
     */
    fun decodePacked(moduleId: String, fields: Map<String, String?>): Map<String, String> =
        (ProSettingsRegistry.settingsOfModule[moduleId] ?: emptyList())
            .filter { isPackedPersistable(it) }
            .associate { setting -> setting.id to setting.normalize(fields[fieldKeyOf(setting)]).value }

    /**
     * 写（打包容器）：返回「字段名 → 收敛后的值」，只含非默认项。
     *
     * 调用方再用模块 Codec 把它序列化回容器字符串（本页不碰模块 Codec 的类型）。
     */
    fun encodePacked(moduleId: String, values: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        (ProSettingsRegistry.settingsOfModule[moduleId] ?: emptyList())
            .filter { isPackedPersistable(it) }
            .forEach { setting ->
                val raw = values[setting.id] ?: return@forEach
                val coerced = setting.writeTo(raw).value
                if (coerced != setting.defaultValue) out[fieldKeyOf(setting)] = coerced
            }
        return out
    }

    /** 打包容器的容器键清单（集成层据此知道要读写哪个 DataStore 键）。 */
    fun packedContainers(): Map<String, List<String>> =
        packedSettings.groupBy { containerKeyOf(it) }
            .mapValues { (_, list) -> list.map { it.moduleId }.distinct() }

    /** 只导出某一分栏的写入集（页面「应用本栏」按钮用）。 */
    fun encodeSection(sectionId: String, values: Map<String, String?>): Map<String, String> {
        val ids = ProSettingsRegistry.settingsOfSection(sectionId).map { it.id }.toSet()
        return encode(values.filterKeys { it in ids })
    }

    /** 只导出某一模块的写入集（模块页「应用」按钮用）。 */
    fun encodeModule(moduleId: String, values: Map<String, String?>): Map<String, String> {
        val ids = (ProSettingsRegistry.settingsOfModule[moduleId] ?: emptyList()).map { it.id }.toSet()
        return encode(values.filterKeys { it in ids })
    }

    /**
     * 合并：把页面值落进已有快照。
     *
     * 未登记的键（不属于本页的模块私有键）**原样保留**，避免误删。
     */
    fun mergeInto(existing: Map<String, String>, values: Map<String, String?>): Map<String, String> {
        val merged = LinkedHashMap(existing)
        val owned = ProSettingsRegistry.settings
            .filter { isPersistable(it) }
            .map { it.storageKey }
            .toSet()
        owned.forEach { key -> merged.remove(key) }
        merged.putAll(encode(values))
        return merged
    }

    /** 反查：某个持久化键属于哪个设置项（集成层收到脏值时可定位）。 */
    fun settingOfKey(key: String): ProSettingSpec? =
        ProSettingsRegistry.settings.firstOrNull { isPersistable(it) && it.storageKey == key }

    /** 某个模块声明的全部键（与 delivery/patches 目录下 *_patch.md 的 CONFIG 节对账用）。 */
    fun keysOfModule(moduleId: String): List<String> =
        (ProSettingsRegistry.settingsOfModule[moduleId] ?: emptyList())
            .filter { isPersistable(it) }
            .map { it.storageKey }
            .distinct()

    /** 是否存在「同一容器 + 同一键」被两条设置项占用（对接线冲突的静态检查）。 */
    fun duplicateKeyConflicts(): List<String> {
        val problems = mutableListOf<String>()
        ProSettingsRegistry.settings
            .filter { isPersistable(it) || isPackedPersistable(it) }
            .groupBy { it.storageKey }
            .filterValues { list -> list.map { it.id }.distinct().size > 1 }
            .forEach { (key, list) ->
                problems += "键 $key 被多条设置项占用：${list.map { it.id }}"
            }
        return problems
    }
}
