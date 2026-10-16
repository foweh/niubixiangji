package com.hinnka.mycamera.ui.uisettings

import com.hinnka.mycamera.uisettings.ProSettingControl
import com.hinnka.mycamera.uisettings.ProSettingsCodec
import com.hinnka.mycamera.uisettings.ProSettingsRegistry

/**
 * 「Pro 设置」页与持久化层之间的**唯一契约**。
 *
 * 为什么不是直接依赖各模块的 Repository / DataStore：
 * 1) 页面会跨 8 个容器（`user_preferences` / `superres_preferences` / `ai_platform_preferences` /
 *    `ai_feature_preferences` / `app_gallery_preferences` / `mfsr_config` / `funshot_config` / `viewfinder` 前缀），
 *    逐模块依赖会让设置页与模块实现强耦合；
 * 2) 任务约束要求不改各模块 Config、也不改其存储实现，只做「键 → 值」读写；
 * 3) 打包容器（`video_modes_config` / `config_payload`）需要模块 Codec 参与拆装，
 *    所以单独开 [loadPacked] / [savePacked] 两个方法，由集成层实现。
 *
 * 未接线时用 [InMemoryProSettingsPort]（页面可完整交互，只是不落盘）。
 */
interface ProSettingsPort {

    /** 读一批扁平键（缺键可返回 null / 不返回该键）。 */
    fun load(keys: List<String>): Map<String, String?>

    /** 写一批扁平键（调用方已保证值合法；等于默认值的项不会出现在这里）。 */
    fun save(values: Map<String, String>)

    /**
     * 读一个打包容器的字段表。
     *
     * 集成层实现示例：取 `video_modes_config` 字符串 → 调模块 Codec 解成字段表。
     */
    fun loadPacked(containerKey: String, moduleId: String): Map<String, String?>

    /** 写一个打包容器的字段表（集成层负责用模块 Codec 序列化后落盘）。 */
    fun savePacked(containerKey: String, moduleId: String, fields: Map<String, String>)

    companion object {

        /** 本页会读写的全部扁平键（按容器可分组的全集）。 */
        fun allFlatKeys(): List<String> = ProSettingsRegistry.settings
            .filter { ProSettingsCodec.isPersistable(it) }
            .map { it.storageKey }
            .distinct()

        /** 本页会读写的全部打包容器键。 */
        fun allPackedContainers(): List<String> =
            ProSettingsCodec.packedSettings.map { ProSettingsCodec.containerKeyOf(it) }.distinct()

        /** 该模块需要的打包容器键（没有则空）。 */
        fun packedContainersOf(moduleId: String): List<String> = ProSettingsCodec.packedSettings
            .filter { it.moduleId == moduleId }
            .map { ProSettingsCodec.containerKeyOf(it) }
            .distinct()
    }
}

/**
 * 内存实现：未接线时的默认端口。
 *
 * 用途：让「Pro 设置」页在集成层接线前也能完整跑通（改动只保存在内存里），
 * 同时作为 UI 层的单元测试替身。
 */
class InMemoryProSettingsPort(
    initial: Map<String, String> = emptyMap(),
) : ProSettingsPort {

    private val flat = LinkedHashMap<String, String>(initial)
    private val packed = LinkedHashMap<String, LinkedHashMap<String, String>>()

    override fun load(keys: List<String>): Map<String, String?> =
        keys.associateWith { flat[it] }

    override fun save(values: Map<String, String>) {
        flat.putAll(values)
    }

    override fun loadPacked(containerKey: String, moduleId: String): Map<String, String?> =
        packed[containerKey].orEmpty()

    override fun savePacked(containerKey: String, moduleId: String, fields: Map<String, String>) {
        packed.getOrPut(containerKey) { LinkedHashMap() }.putAll(fields)
    }

    /** 测试与自检用：当前内存快照。 */
    fun snapshot(): Map<String, String> = LinkedHashMap(flat)

    /** 测试与自检用：当前打包快照。 */
    fun packedSnapshot(): Map<String, Map<String, String>> =
        packed.mapValues { (_, v) -> LinkedHashMap<String, String>(v) }
}

/**
 * 页面值仓库（Compose 无关）：把 port 的键值读写与页面状态接起来。
 *
 * 之所以独立成类而不是写在 Composable 里：下拉项需要「编辑中的草稿值」、
 * 数值输入框需要「先记草稿、失焦再收敛」，这些规则值得被单测覆盖。
 */
class ProSettingsValueStore(private val port: ProSettingsPort) {

    private val values = LinkedHashMap<String, String>()

    /** 全量加载：扁平键 + 打包容器字段。 */
    fun loadAll() {
        values.clear()
        values.putAll(ProSettingsCodec.defaults())
        val flatSnapshot = port.load(ProSettingsPort.allFlatKeys())
        ProSettingsCodec.decode(flatSnapshot).forEach { (id, v) -> values[id] = v }

        ProSettingsCodec.packedSettings.groupBy { it.moduleId }.forEach { (moduleId, list) ->
            val container = ProSettingsCodec.containerKeyOf(list.first())
            val fields = port.loadPacked(container, moduleId)
            ProSettingsCodec.decodePacked(moduleId, fields).forEach { (id, v) -> values[id] = v }
        }
    }

    fun valueOf(settingId: String): String? = values[settingId]

    /** 页面展示值（缺省回落默认）。 */
    fun displayValueOf(settingId: String): String =
        values[settingId] ?: ProSettingsRegistry.settingById[settingId]?.defaultValue.orEmpty()

    /**
     * 提交一个值：先收敛，合法则写入内存（非法值同样被收敛，页面不会显示脏值）。
     *
     * 返回是否触发了修正（用于页面提示「已按范围修正」）。
     */
    fun setValue(settingId: String, raw: String?): Boolean {
        val spec = ProSettingsRegistry.settingById[settingId] ?: return false
        val coerced = spec.writeTo(raw)
        values[settingId] = coerced.value
        return coerced.corrected
    }

    /** 恢复某一项到默认值。 */
    fun resetValue(settingId: String): String? {
        val spec = ProSettingsRegistry.settingById[settingId] ?: return null
        values[settingId] = spec.defaultValue
        return spec.defaultValue
    }

    /** 恢复某一分栏的全部项。 */
    fun resetSection(sectionId: String) {
        ProSettingsRegistry.settingsOfSection(sectionId).forEach { setting ->
            values[setting.id] = setting.defaultValue
        }
    }

    /** 提交：扁平项与打包项分别写回 port。返回写出的键数。 */
    fun commit(): Int {
        val flat = ProSettingsCodec.encode(values)
        if (flat.isNotEmpty()) port.save(flat)
        var packedCount = 0
        ProSettingsCodec.packedSettings.groupBy { it.moduleId }.forEach { (moduleId, list) ->
            val container = ProSettingsCodec.containerKeyOf(list.first())
            val fields = ProSettingsCodec.encodePacked(moduleId, values)
            if (fields.isNotEmpty()) {
                port.savePacked(container, moduleId, fields)
                packedCount += fields.size
            }
        }
        return flat.size + packedCount
    }

    /** 当前与默认值的差异条数（页面顶部展示「N 项已修改」）。 */
    fun changedCount(): Int = ProSettingsCodec.allDiffs(values).size

    /** 当前全部值（只读快照，便于测试断言）。 */
    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    /** 该控件是否需要「提交后收起键盘」之类的外壳处理（数值 / 文本输入框）。 */
    fun needsCommit(settingId: String): Boolean {
        val control = ProSettingsRegistry.settingById[settingId]?.control ?: return false
        return control == ProSettingControl.NUMBER || control == ProSettingControl.TEXT
    }
}
