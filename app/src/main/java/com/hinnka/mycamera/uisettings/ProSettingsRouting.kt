package com.hinnka.mycamera.uisettings

/**
 * 「Pro 设置」的分栏路由。
 *
 * 职责：把「设置项 id / 分栏 id / 深链」解析成页面可用的路由，且**任何输入都不抛异常**：
 * - 未登记模块前缀 → 回落「规划中」节；
 * - 不存在的分栏 id → 回落第一个有内容的分栏；
 * - 空 / 空白字符串 → 视为「无偏好」，同样回落。
 *
 * 与 UI 的关系：`ProSettingsRoute`（Compose 层）只负责把这里的 [ProRoute] 转成导航动作，
 * 具体路由规则全部在本文件，便于 JVM 单测。
 */
data class ProRoute(
    val sectionId: String,
    val settingId: String? = null,
    val moduleId: String? = null,
) {
    val hasSetting: Boolean get() = !settingId.isNullOrBlank()
}

object ProSettingsRouting {

    /** 分栏 id 是否存在。 */
    fun isValidSection(sectionId: String?): Boolean =
        sectionId != null && ProSettingsTaxonomy.sectionById.containsKey(sectionId)

    /** 设置项 id → 分栏 id（未登记前缀回落「规划中」）。 */
    fun sectionIdOfSetting(settingId: String?): String {
        val id = settingId?.trim().orEmpty()
        if (id.isEmpty()) return fallbackSectionId()
        ProSettingsRegistry.settingById[id]?.let { return it.sectionId }
        return ProSettingsTaxonomy.sectionOf(ProSettingsTaxonomy.moduleIdOf(id))
    }

    /** 有内容（真实设置项或占位）的分栏，按 order 升序。 */
    fun sectionsWithContent(): List<ProSectionSpec> =
        ProSettingsRegistry.sectionContents.map { it.section }

    /** 第一个有内容的分栏 id（注册表异常为空时回落「规划中」）。 */
    fun fallbackSectionId(): String =
        sectionsWithContent().firstOrNull()?.id ?: ProSettingsTaxonomy.SECTION_PLAN

    /** 解析分栏：不存在时回落第一个有内容的分栏。 */
    fun resolveSection(preferred: String?): ProSectionSpec {
        val id = if (isValidSection(preferred)) preferred!! else fallbackSectionId()
        return ProSettingsTaxonomy.sectionById[id]
            ?: ProSettingsTaxonomy.sectionById.getValue(ProSettingsTaxonomy.SECTION_PLAN)
    }

    /** 进入某分栏（sectionId 非法时自动回落）。 */
    fun routeForSection(sectionId: String?): ProRoute =
        ProRoute(sectionId = resolveSection(sectionId).id)

    /** 深链到某设置项：分栏由 id 前缀推出，便于「点通知直接打开该开关」。 */
    fun routeForSetting(settingId: String?): ProRoute {
        val id = settingId?.trim().orEmpty()
        if (id.isEmpty()) return routeForSection(null)
        val section = sectionIdOfSetting(id)
        val module = ProSettingsTaxonomy.moduleIdOf(id).takeIf { it.isNotBlank() }
        val known = ProSettingsRegistry.settingById.containsKey(id)
        return ProRoute(sectionId = section, settingId = if (known) id else null, moduleId = module)
    }

    /** 状态恢复：分栏 + 设置项双回落。 */
    fun restore(lastSectionId: String?, lastSettingId: String?): ProRoute {
        val section = resolveSection(lastSectionId)
        val id = lastSettingId?.trim().orEmpty()
        if (id.isEmpty() || sectionIdOfSetting(id) != section.id || !ProSettingsRegistry.settingById.containsKey(id)) {
            return ProRoute(sectionId = section.id)
        }
        return ProRoute(sectionId = section.id, settingId = id, moduleId = ProSettingsTaxonomy.moduleIdOf(id))
    }

    /** 上下分栏翻页（Tab 左右切换）。 */
    fun nextSection(current: String?): ProSectionSpec {
        val list = sectionsWithContent()
        val index = list.indexOfFirst { it.id == resolveSection(current).id }
        if (list.isEmpty()) return resolveSection(null)
        return list[(index + 1).mod(list.size)]
    }

    fun previousSection(current: String?): ProSectionSpec {
        val list = sectionsWithContent()
        val index = list.indexOfFirst { it.id == resolveSection(current).id }
        if (list.isEmpty()) return resolveSection(null)
        return list[(index - 1 + list.size).mod(list.size)]
    }

    /** 面包屑：分栏标题 → 模块名 → 设置项标题。 */
    fun breadcrumb(settingId: String?): List<String> {
        val route = routeForSetting(settingId)
        val sectionTitle = ProSettingsTaxonomy.sectionById[route.sectionId]?.title.orEmpty()
        val moduleTitle = route.moduleId
            ?.let { ProSettingsTaxonomy.moduleById[it]?.displayName }
            .orEmpty()
        val settingTitle = route.settingId
            ?.let { ProSettingsRegistry.settingById[it]?.title }
            .orEmpty()
        return listOf(sectionTitle, moduleTitle, settingTitle).filter { it.isNotBlank() }
    }

    /** 简易搜索（标题 / 说明 / 键名，大小写不敏感）。 */
    fun search(keyword: String?): List<ProSettingSpec> {
        val q = keyword?.trim().orEmpty()
        if (q.isEmpty()) return emptyList()
        return ProSettingsRegistry.editableSettings.filter { setting ->
            setting.title.contains(q, ignoreCase = true) ||
                setting.description.contains(q, ignoreCase = true) ||
                setting.id.contains(q, ignoreCase = true) ||
                setting.storageKey.contains(q, ignoreCase = true) ||
                setting.unit.contains(q, ignoreCase = true)
        }
    }

    /** 该分栏下每个模块的设置项数量（分栏头部展示「N 项 / M 模块」）。 */
    fun moduleCountOfSection(sectionId: String): Int =
        ProSettingsRegistry.settingsOfSection(sectionId).map { it.moduleId }.distinct().size

    /** 该分栏下每个模块的设置项数量明细。 */
    fun moduleBreakdownOfSection(sectionId: String): Map<String, Int> =
        ProSettingsRegistry.settingsOfSection(sectionId)
            .groupBy { it.moduleId }
            .mapValues { (_, list) -> list.count { it.control != ProSettingControl.PLACEHOLDER } }
}
