package com.hinnka.mycamera.uisettings

/**
 * 设置项构建器：把「标题 / 说明 / 控件 / 默认值 / 范围 / 读写键」压成一行，降低目录文件的书写成本，
 * 并保证 moduleId / sectionId / storage / 默认值格式三处自动一致。
 *
 * 命名约定：设置项 id 一律为 `<模块id>.<字段名>`，模块 id 从 id 前缀解析
 * （因此 id 写错时不会静默串模块，而会被 [ProSettingsRegistry.validate] 抓到）。
 */
object ProSettingBuilder {

    /** 数值格式：整数项 decimals = 0，浮点项按需指定。 */
    private fun fmt(value: Double, decimals: Int): String = ProRange.format(value, decimals)

    private fun base(
        id: String,
        key: String,
        title: String,
        description: String,
        control: ProSettingControl,
        defaultValue: String,
        range: ProRange,
        decimals: Int,
        unit: String,
        options: List<String>,
        maxChars: Int,
        navTarget: String?,
        pendingReason: String?,
        refs: List<String>,
    ): ProSettingSpec {
        val moduleId = ProSettingsTaxonomy.moduleIdOf(id)
        return ProSettingSpec(
            id = id,
            moduleId = moduleId,
            sectionId = ProSettingsTaxonomy.sectionOf(moduleId),
            title = title,
            description = description,
            control = control,
            defaultValue = defaultValue,
            range = range,
            decimals = decimals,
            unit = unit,
            options = options,
            maxChars = maxChars,
            storage = ProSettingsTaxonomy.slotOf(moduleId),
            storageKey = key,
            navTarget = navTarget,
            pendingReason = pendingReason,
            benchmarkRefs = refs,
        )
    }

    private fun refs(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 开关。 */
    fun sw(id: String, key: String, title: String, desc: String, def: Boolean, ref: String = ""): ProSettingSpec =
        base(id, key, title, desc, ProSettingControl.SWITCH, def.toString(), ProRange.Unbounded, 0, "", emptyList(), 0, null, null, refs(ref))

    /** 浮点滑杆。 */
    fun fl(
        id: String, key: String, title: String, desc: String,
        def: Float, min: Float, max: Float, decimals: Int = 2, unit: String = "", ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.SLIDER, fmt(def.toDouble(), decimals),
        ProRange.Continuous(min.toDouble(), max.toDouble()), decimals, unit, emptyList(), 0, null, null, refs(ref),
    )

    /** 整数滑杆（控件仍是滑杆，只是不显示小数）。 */
    fun it(
        id: String, key: String, title: String, desc: String,
        def: Int, min: Int, max: Int, step: Int = 1, unit: String = "", ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.SLIDER, def.toString(),
        ProRange.Stepped(min.toLong(), max.toLong(), step.toLong()), 0, unit, emptyList(), 0, null, null, refs(ref),
    )

    /** 大整数（字节 / 纳秒 / 毫秒级）用输入框而非滑杆：滑杆在这些量级上不可用。 */
    fun lt(
        id: String, key: String, title: String, desc: String,
        def: Long, min: Long, max: Long, unit: String = "", ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.NUMBER, def.toString(),
        ProRange.Stepped(min, max, 1L), 0, unit, emptyList(), 0, null, null, refs(ref),
    )

    /** 浮点输入框。 */
    fun num(
        id: String, key: String, title: String, desc: String,
        def: Float, min: Float, max: Float, decimals: Int = 2, unit: String = "", ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.NUMBER, fmt(def.toDouble(), decimals),
        ProRange.Continuous(min.toDouble(), max.toDouble()), decimals, unit, emptyList(), 0, null, null, refs(ref),
    )

    /** 下拉（[options] 为持久化白名单；默认值必须命中其一）。 */
    fun dd(
        id: String, key: String, title: String, desc: String,
        def: String, options: List<String>, ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.DROPDOWN, def, ProRange.Unbounded,
        0, "", options, 0, null, null, refs(ref),
    )

    /** 文本 / 可空数值（留空表示「用默认」）。 */
    fun tx(
        id: String, key: String, title: String, desc: String,
        def: String = "", maxChars: Int = 0, ref: String = "",
    ): ProSettingSpec = base(
        id, key, title, desc, ProSettingControl.TEXT, def, ProRange.Unbounded,
        0, "", emptyList(), maxChars, null, null, refs(ref),
    )

    /** 跳转项（不落盘）。 */
    fun nav(id: String, title: String, desc: String, target: String, ref: String = ""): ProSettingSpec =
        base(id, "", title, desc, ProSettingControl.NAV, "", ProRange.Unbounded, 0, "", emptyList(), 0, target, null, refs(ref))

    /** 占位行（不可编辑，必须给 [reason]）。 */
    fun ph(
        id: String, title: String, desc: String, moduleId: String, sectionId: String, reason: String,
    ): ProSettingSpec = ProSettingSpec(
        id = id,
        moduleId = moduleId,
        sectionId = sectionId,
        title = title,
        description = desc,
        control = ProSettingControl.PLACEHOLDER,
        defaultValue = "",
        pendingReason = reason,
    )
}
