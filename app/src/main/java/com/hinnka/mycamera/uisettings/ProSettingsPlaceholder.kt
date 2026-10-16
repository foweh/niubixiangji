package com.hinnka.mycamera.uisettings

/**
 * 占位节逻辑：给「尚未落盘 / 尚未接线」的模块生成不可编辑的占位行。
 *
 * 三种情形的处理（单测覆盖点）：
 * - **正常**：模块已登记且 `status = PENDING` → 生成一行占位，理由取 `pendingReason`；
 * - **边界**：PENDING 模块没有分栏归属 → 回落「规划中」节；`pendingReason` 为空 → 用默认文案；
 * - **异常**：模块 id 未登记（可能是笔误前缀）→ 不抛异常，仍生成占位并给出「未登记」提示；
 *   模块已 `AVAILABLE` → `rowForOrNull` 返回 null（该模块有真实设置项，不需要占位）。
 */
object ProSettingsPlaceholder {

    /** 默认占位文案。 */
    const val DEFAULT_REASON = "待对应模块落地后接线"

    /** 未登记前缀的提示文案。 */
    const val UNKNOWN_MODULE_REASON = "未登记模块前缀（请检查 id 前缀是否笔误）"

    /** 已落盘模块被要求占位时的提示文案。 */
    const val ALREADY_AVAILABLE_REASON = "模块已落盘，不需要占位"

    /** 占位行的 id 后缀。 */
    private const val ID_SUFFIX = ".placeholder"

    /** 该模块应落在哪个分栏（未登记 → 「规划中」）。 */
    fun resolveSectionId(moduleId: String): String = ProSettingsTaxonomy.sectionOf(moduleId)

    /** 占位理由（永不返回空串）。 */
    fun reasonOf(module: ProModuleSpec?): String = when {
        module == null -> UNKNOWN_MODULE_REASON
        module.status == ProModuleStatus.PENDING -> module.pendingReason?.takeIf { it.isNotBlank() } ?: DEFAULT_REASON
        else -> ALREADY_AVAILABLE_REASON
    }

    /**
     * 生成占位行（永不抛异常）。
     *
     * 页面渲染时把它放在对应分栏的末尾；点击无效（控件为 [ProSettingControl.PLACEHOLDER]）。
     */
    fun rowFor(moduleId: String, note: String? = null): ProSettingSpec {
        val module = ProSettingsTaxonomy.moduleById[moduleId]
        val sectionId = resolveSectionId(moduleId)
        val reason = reasonOf(module)
        val title = module?.displayName ?: moduleId
        val detail = buildString {
            append(reason)
            if (!note.isNullOrBlank()) append("；").append(note)
            val pkg = module?.sourcePackage.orEmpty()
            if (pkg.isNotBlank()) append("（").append(pkg).append("）")
        }
        return ProSettingSpec(
            id = moduleId + ID_SUFFIX,
            moduleId = moduleId,
            sectionId = sectionId,
            title = title,
            description = detail,
            control = ProSettingControl.PLACEHOLDER,
            defaultValue = "",
            range = ProRange.Unbounded,
            storage = ProSettingsTaxonomy.slotOf(moduleId),
            storageKey = "",
            pendingReason = reason,
        )
    }

    /** 只有「模块未登记」或「模块为 PENDING」时才返回占位行，否则 null。 */
    fun rowForOrNull(moduleId: String): ProSettingSpec? {
        val module = ProSettingsTaxonomy.moduleById[moduleId]
        return when {
            module == null -> rowFor(moduleId)
            module.status == ProModuleStatus.PENDING -> rowFor(moduleId)
            else -> null
        }
    }

    /** 全部占位行（按分栏顺序排列）。 */
    val rows: List<ProSettingSpec> = ProSettingsTaxonomy.modules
        .filter { it.status == ProModuleStatus.PENDING }
        .map { rowFor(it.id) }

    /** 某分栏的占位行。 */
    fun rowsForSection(sectionId: String): List<ProSettingSpec> =
        rows.filter { it.sectionId == sectionId }

    /** 是否「整节只有占位」（UI 据此把节渲染为灰态）。 */
    fun isPlaceholderOnly(sectionId: String): Boolean =
        ProSettingsRegistry.settingsOfSection(sectionId).none { it.control != ProSettingControl.PLACEHOLDER } &&
            rowsForSection(sectionId).isNotEmpty()

    /** 页面渲染顺序：真实设置项 + 该节占位行。 */
    fun renderListOf(sectionId: String): List<ProSettingSpec> =
        ProSettingsRegistry.settingsOfSection(sectionId) + rowsForSection(sectionId)

    /** 接线说明（写进 changelog / patch 文档，UI 的「待接线」提示也用它）。 */
    fun wiringNotes(): List<String> = ProSettingsTaxonomy.modules.map { module ->
        when (module.status) {
            ProModuleStatus.PENDING ->
                "${module.id}（${module.displayName}）：$DEFAULT_REASON" +
                    "，sourcePackage=${module.sourcePackage}"
            ProModuleStatus.AVAILABLE ->
                "${module.id}（${module.displayName}）：读 ${ProSettingsTaxonomy.slotOfModule[module.id]?.displayName ?: "-"}" +
                    "，键 ${ProSettingsRegistry.keysOfModule[module.id]?.size ?: 0} 个"
        }
    }
}
