package com.hinnka.mycamera.uisettings

/**
 * 「Pro 设置」注册表：本工程全部新模块开放参数的**唯一聚合入口**。
 *
 * 结构：
 * ```
 * catalog（5 份，唯一跨包引用点）
 *   ├── ProSettingsCatalogImage      §画质增强 + §镜头畸变校正
 *   ├── ProSettingsCatalogSr         §端侧 AI 超分 + §多帧超分
 *   ├── ProSettingsCatalogVideo      §视频模式 + §Log 视频与 LUT
 *   ├── ProSettingsCatalogFun        §趣味拍摄 + §长曝光与星空（含天文导航）
 *   └── ProSettingsCatalogUtility    §取景与监看 + §相册 + §AI 与计算
 *        ↓ flatten
 * registry.settings → 单测做 id 唯一 / 默认值在范围内 / 键非空 / 占位有理由 的强校验
 *        ↓ 按 sectionId 分栏
 * registry.sectionContents → UI 直接渲染
 * ```
 *
 * 约束（与任务要求一致）：本文件**不修改**任何模块的 Config，只读引用其公开常量；
 * 页面不依赖模块的 DataStore / Repository 实例，只做「键 → 值」读写契约。
 */
object ProSettingsRegistry {

    /**
     * 目录清单（保持分栏内的阅读顺序）。
     *
     * 键仅用于自检与文档索引；真正的归属以 [ProSettingSpec.sectionId] 为准。
     */
    val catalogs: Map<String, List<ProSettingSpec>> = linkedMapOf(
        "macrostack" to com.hinnka.mycamera.focusstack.FocusStackSettingsCatalog.entries,
        "image" to ProSettingsCatalogImage.entries,
        "sr" to ProSettingsCatalogSr.entries,
        "video" to ProSettingsCatalogVideo.entries,
        "fun" to ProSettingsCatalogFun.entries,
        "utility" to ProSettingsCatalogUtility.entries,
    )

    /** 全部设置项（含占位行）。 */
    val settings: List<ProSettingSpec> = catalogs.values.flatten()

    /** 全部可编辑设置项（不含占位与导航）。 */
    val editableSettings: List<ProSettingSpec> =
        settings.filter { it.control != ProSettingControl.PLACEHOLDER && it.control != ProSettingControl.NAV }

    /** 占位行（来自 [ProSettingsPlaceholder]）：页面在「待接线」节末尾渲染。 */
    val placeholderRows: List<ProSettingSpec> = ProSettingsPlaceholder.rows

    val settingById: Map<String, ProSettingSpec> = settings.associateBy { it.id }

    /** 模块 id → 设置项（不含占位行）。 */
    val settingsOfModule: Map<String, List<ProSettingSpec>> =
        settings.filter { it.control != ProSettingControl.PLACEHOLDER }.groupBy { it.moduleId }

    /** 模块 id → 持久化键集合（去重、去空）。 */
    val keysOfModule: Map<String, List<String>> =
        settingsOfModule.mapValues { (_, list) ->
            list.map { it.storageKey }.filter { it.isNotBlank() }.distinct()
        }

    /** 已接线（有真实设置项）的模块数。 */
    val wiredModuleCount: Int = settingsOfModule.size

    /** 已接线（有真实设置项）的分栏数。 */
    val wiredSectionCount: Int = settings.map { it.sectionId }.distinct().size

    /** 该分栏下的设置项（按注册顺序）。 */
    fun settingsOfSection(sectionId: String): List<ProSettingSpec> =
        settings.filter { it.sectionId == sectionId }

    /** 该设置项所属分栏 id。 */
    fun sectionIdOf(setting: ProSettingSpec): String = setting.sectionId

    /**
     * 分栏聚合结果：每节含「有设置项的模块」「占位模块」与设置项列表。
     *
     * 分栏顺序由 [ProSettingsTaxonomy.sections] 的 `order` 决定；未登记前缀回落的「规划中」节排最后。
     */
    val sectionContents: List<ProSectionContent> = ProSettingsTaxonomy.sections
        .sortedBy { it.order }
        .map { section -> sectionContentOf(section) }
        .filter { it.settings.isNotEmpty() || it.pendingModules.isNotEmpty() }

    fun sectionContentOf(section: ProSectionSpec): ProSectionContent {
        val sectionSettings = settingsOfSection(section.id)
        val moduleIds = sectionSettings.map { it.moduleId }.distinct()
        val modules = moduleIds.mapNotNull { ProSettingsTaxonomy.moduleById[it] }
        val pending = ProSettingsTaxonomy.modules.filter { module ->
            module.status == ProModuleStatus.PENDING &&
                ProSettingsTaxonomy.sectionOf(module.id) == section.id
        }
        return ProSectionContent(
            section = section,
            modules = modules,
            settings = sectionSettings,
            pendingModules = pending,
        )
    }

    /** 「规划中」节（占位模块集中展示）。 */
    val plannedSectionContent: ProSectionContent
        get() = sectionContentOf(ProSettingsTaxonomy.sectionById.getValue(ProSettingsTaxonomy.SECTION_PLAN))

    /**
     * 自检：返回问题列表（空 = 通过）。
     *
     * 覆盖 9 类问题，全部可在 JVM 单测里跑：
     * 1. id 全局唯一；
     * 2. id 前缀与 moduleId 一致；
     * 3. 模块已登记（拦笔误前缀）；
     * 4. 分栏存在；
     * 5. 数值项必须有界（SLIDER / NUMBER）；
     * 6. 默认值必须落在范围内；
     * 7. 下拉项必须有选项且默认值命中白名单；
     * 8. 有键容器（DataStore / SharedPreferences）不得出现空键；
     * 9. 占位项必须有 pendingReason，PENDING 模块必须有 pendingReason；
     * 10. AVAILABLE 模块必须已分配真实分栏（不能落在「规划中」）。
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()

        // 1. id 唯一
        settings.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            problems += "id 重复：$it"
        }

        settings.forEach { setting ->
            // 2. id 前缀一致性
            if (ProSettingsTaxonomy.moduleIdOf(setting.id) != setting.moduleId) {
                problems += "${setting.id}：id 前缀与 moduleId(${setting.moduleId}) 不一致"
            }
            // 3. 模块已登记
            if (!ProSettingsTaxonomy.isKnownModule(setting.moduleId)) {
                problems += "${setting.id}：模块未登记（moduleId=${setting.moduleId}）"
            }
            // 4. 分栏存在
            if (!ProSettingsTaxonomy.sectionById.containsKey(setting.sectionId)) {
                problems += "${setting.id}：分栏不存在（sectionId=${setting.sectionId}）"
            }

            when (setting.control) {
                ProSettingControl.SLIDER, ProSettingControl.NUMBER -> {
                    // 5. 数值项必须有界
                    val range = setting.range
                    if (range is ProRange.Unbounded) {
                        problems += "${setting.id}：数值项缺少范围"
                    } else {
                        // 6. 默认值在范围内
                        val default = setting.defaultValue.toDoubleOrNull()
                        if (default == null) {
                            problems += "${setting.id}：默认值不是数值（${setting.defaultValue}）"
                        } else if (!range.contains(default)) {
                            problems += "${setting.id}：默认值 $default 超出 ${setting.rangeText()}"
                        }
                    }
                }

                ProSettingControl.DROPDOWN -> {
                    // 7. 下拉白名单
                    if (setting.options.isEmpty()) {
                        problems += "${setting.id}：下拉项没有选项"
                    } else if (setting.options.none { it.equals(setting.defaultValue, ignoreCase = true) }) {
                        problems += "${setting.id}：下拉默认值 ${setting.defaultValue} 不在白名单内"
                    }
                }

                ProSettingControl.PLACEHOLDER -> {
                    // 9. 占位必须有理由
                    if (setting.pendingReason.isNullOrBlank()) {
                        problems += "${setting.id}：占位项缺少 pendingReason"
                    }
                }

                else -> Unit
            }

            // 8. 有键容器不得空键
            val needsKey = setting.storage.kind == ProStorageKind.DATASTORE ||
                setting.storage.kind == ProStorageKind.SHARED_PREFS
            if (needsKey && setting.storageKey.isBlank() && setting.control != ProSettingControl.PLACEHOLDER) {
                problems += "${setting.id}：容器 ${setting.storage.displayName} 需要键，但键为空"
            }
        }

        // 9/10. 模块级校验
        ProSettingsTaxonomy.modules.forEach { module ->
            when (module.status) {
                ProModuleStatus.PENDING -> if (module.pendingReason.isNullOrBlank()) {
                    problems += "模块 ${module.id}：PENDING 状态缺少 pendingReason"
                }

                ProModuleStatus.AVAILABLE -> if (!ProSettingsTaxonomy.sectionOfModule.containsKey(module.id)) {
                    problems += "模块 ${module.id}：AVAILABLE 但未分配分栏"
                }
            }
        }

        if (editableSettings.isEmpty()) {
            problems += "注册表为空：没有任何可编辑设置项"
        }
        return problems
    }

    /** 自检用的一行摘要（写进 selfcheck 文档）。 */
    fun summaryLine(): String =
        "分栏 ${wiredSectionCount}/${ProSettingsTaxonomy.sections.size} · " +
            "模块 ${wiredModuleCount}/${ProSettingsTaxonomy.modules.size} · " +
            "设置项 ${editableSettings.size}（占位 ${placeholderRows.size}，导航 ${settings.count { it.control == ProSettingControl.NAV }}）"
}

/** [ProRange] 的取值判定（自检与单测用；与 [ProSettingSpec.normalize] 的 clamp 规则同源）。 */
internal fun ProRange.contains(value: Double): Boolean = when (this) {
    ProRange.Unbounded -> true
    is ProRange.Continuous -> value in min..max
    is ProRange.Stepped -> value >= min.toDouble() && value <= max.toDouble()
}
