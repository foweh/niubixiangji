package com.hinnka.mycamera.uisettings

import java.util.Locale

/**
 * 统一专业设置页（Pro 设置）的数据模型。
 *
 * 设计原则：
 * 1) **纯 Kotlin**：不依赖 Android / Compose，便于 JVM 单测覆盖 clamp 与解析；
 * 2) **单一事实来源**：取值范围与默认值全部来自各模块 Config 的公开常量
 *    （见 [ProSettingsRegistry] 的注释与 `delivery/patches/uisettings_patch.md` 的「范围来源」列），
 *    本文件只负责「把字符串收敛到范围内」，不重复声明任何业务常量；
 * 3) **越界不崩**：任何脏值（空、非数字、NaN、Inf、超范围、非法枚举名）一律回落默认再 clamp。
 */

/**
 * 设置项控件类型。
 *
 * 与工程既有设置组件一一对应（见 `ui/uisettings/ProSettingRow.kt`）：
 * - [SWITCH] → `ui.settings.SwitchSettingItem`
 * - [SLIDER] → `ui.components.SliderSettingItem`（整数项同样走滑杆，用 [ProSettingSpec.decimals] = 0 格式化）
 * - [NUMBER] / [TEXT] → `ui.settings.TextInputSettingItem`
 * - [DROPDOWN] → `ui.settings.DropdownSettingItem`
 * - [NAV] → `ui.settings.NavigationSettingItem`
 * - [PLACEHOLDER] → 占位行（灰态、不可点击），用于「待对应模块落地后接线」的节
 */
enum class ProSettingControl {
    SWITCH,
    SLIDER,
    NUMBER,
    TEXT,
    DROPDOWN,
    NAV,
    PLACEHOLDER,
}

/** 取值范围。[Unbounded] 表示合法性由控件自身的规则（布尔 / 枚举白名单 / 纯文本）保证。 */
sealed interface ProRange {

    /** 无范围（开关、下拉、文本、导航、占位）。 */
    data object Unbounded : ProRange

    /** 连续范围（浮点）。 */
    data class Continuous(val min: Double, val max: Double) : ProRange

    /** 离散范围（整数 / 长整数），[step] 为步长，clamp 时按 min 对齐。 */
    data class Stepped(val min: Long, val max: Long, val step: Long = 1L) : ProRange

    /** 人类可读的范围描述（设置页副标题用）。 */
    fun describe(decimals: Int, unit: String): String {
        val suffix = if (unit.isBlank()) "" else " $unit"
        return when (this) {
            Unbounded -> "—"
            is Continuous -> "${format(min, decimals)} ~ ${format(max, decimals)}$suffix"
            is Stepped -> if (step <= 1L) {
                "$min ~ $max$suffix"
            } else {
                "$min ~ $max$suffix（步长 $step）"
            }
        }
    }

    companion object {

        /** 统一的小数格式化：decimals ≤ 0 时输出整数，否则固定小数位（Locale.US，避免逗号小数点）。 */
        fun format(value: Double, decimals: Int): String =
            if (decimals <= 0) {
                value.toLong().toString()
            } else {
                String.format(Locale.US, "%.${decimals}f", value)
            }
    }
}

/** 持久化位置类别。 */
enum class ProStorageKind {
    /** DataStore Preferences。 */
    DATASTORE,

    /** SharedPreferences。 */
    SHARED_PREFS,

    /** 由集成层注入（模块只声明 provider，不声明键）。 */
    INJECTED,

    /** 无持久化（仅内存态 / 只读展示）。 */
    NONE,
}

/**
 * 读写位置：一条设置项落在哪里。
 *
 * [name] 是容器名（DataStore 名 / 偏好文件名），[note] 记录该容器的补充说明（例如"键前缀统一由模块 Codec 定义"）。
 */
data class ProStorageSlot(
    val kind: ProStorageKind,
    val name: String,
    val note: String = "",
) {
    val displayName: String
        get() = when (kind) {
            ProStorageKind.DATASTORE -> "DataStore:$name"
            ProStorageKind.SHARED_PREFS -> "SharedPreferences:$name"
            ProStorageKind.INJECTED -> "注入式:$name"
            ProStorageKind.NONE -> "内存态"
        }
}

/** 模块落地状态。 */
enum class ProModuleStatus {
    /** 包已落盘，设置项已接线。 */
    AVAILABLE,

    /** 包未落盘 / 尚未接线，页面只出占位节。 */
    PENDING,
}

/**
 * 模块描述。一个模块对应一个新包的 Config/Preferences 体系。
 *
 * @param benchmark 对标来源（GCam / ProCam / Blackmagic / MotionCam / OpenCamera），仅功能对标
 * @param licenseNote 对标来源的许可说明（本工程不采其代码）
 */
data class ProModuleSpec(
    val id: String,
    val displayName: String,
    val sourcePackage: String,
    val status: ProModuleStatus,
    val pendingReason: String? = null,
    val benchmark: String = "",
    val licenseNote: String = "",
)

/** 分栏（页面的一个 Tab / 一组）。 */
data class ProSectionSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val order: Int,
    val iconKey: String = "",
)

/** clamp 结果。 */
data class ProCoerceResult(
    val value: String,
    val corrected: Boolean,
    val reason: String? = null,
) {
    /** 供自检/日志使用的一行描述。 */
    val summary: String
        get() = if (corrected) "$value（已修正：${reason ?: "未知"}）" else value
}

/**
 * 一条设置项的完整定义。
 *
 * [defaultValue] 与 [range] 必须与模块 Config 保持同源；[ProSettingsRegistry.validate] 会在单测里
 * 强制校验「默认值落在范围内」「下拉默认值在选项内」「id 全局唯一」。
 */
data class ProSettingSpec(
    val id: String,
    val moduleId: String,
    val sectionId: String,
    val title: String,
    val description: String,
    val control: ProSettingControl,
    val defaultValue: String,
    val range: ProRange = ProRange.Unbounded,
    val decimals: Int = 2,
    val unit: String = "",
    val options: List<String> = emptyList(),
    val maxChars: Int = 0,
    val storage: ProStorageSlot = ProStorageSlot(ProStorageKind.NONE, "-"),
    /** 该设置项在各模块 Config 里对应的持久化键（无键时为空串）。 */
    val storageKey: String = "",
    val navTarget: String? = null,
    val pendingReason: String? = null,
    val benchmarkRefs: List<String> = emptyList(),
) {

    /** 是否走滑杆（整数项也走滑杆，只是 decimals = 0）。 */
    val isSlider: Boolean
        get() = control == ProSettingControl.SLIDER

    /** 是否走输入框（数值 / 文本）。 */
    val isInput: Boolean
        get() = control == ProSettingControl.NUMBER || control == ProSettingControl.TEXT

    /** 范围描述（设置页副标题）。 */
    fun rangeText(): String = range.describe(decimals, unit)

    /**
     * 把任意原始字符串收敛为合法值。
     *
     * 规则（越界不抛异常，全部回落/修正）：
     * - 空值 / null：回落默认；
     * - 布尔：接受 true/false/1/0/yes/no/on/off（大小写不敏感），否则回落默认；
     * - 下拉：按选项名（大小写不敏感）白名单匹配，未命中回落默认；
     * - 文本：[maxChars] > 0 时超长截断；
     * - 数值：解析失败 / NaN / Inf → 默认；越界 → clamp；[ProRange.Stepped] 额外按 step 吸附到 min 网格。
     */
    fun normalize(raw: String?): ProCoerceResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return ProCoerceResult(defaultValue, true, "空值回落默认")
        }
        return when (control) {
            ProSettingControl.SWITCH -> normalizeSwitch(text)
            ProSettingControl.DROPDOWN -> normalizeDropdown(text)
            ProSettingControl.TEXT -> normalizeText(text)
            ProSettingControl.SLIDER, ProSettingControl.NUMBER -> normalizeNumber(text)
            ProSettingControl.NAV, ProSettingControl.PLACEHOLDER -> ProCoerceResult(
                defaultValue,
                text != defaultValue,
                "该控件不接收用户数值",
            )
        }
    }

    /** 读取路径：从偏好快照取值并收敛（键不存在时直接给默认）。 */
    fun readFrom(preferences: Map<String, String?>): ProCoerceResult =
        normalize(preferences[storageKey])

    /** 写入路径：先收敛再编码，保证落盘的永远是合法值。 */
    fun writeTo(raw: String?): ProCoerceResult = normalize(raw)

    private fun normalizeSwitch(text: String): ProCoerceResult {
        val parsed = when (text.lowercase(Locale.US)) {
            "true", "1", "yes", "on", "是", "开" -> true
            "false", "0", "no", "off", "否", "关" -> false
            else -> null
        }
        if (parsed == null) {
            return ProCoerceResult(defaultValue, true, "非法布尔值")
        }
        val value = parsed.toString()
        return ProCoerceResult(value, false, null)
    }

    private fun normalizeDropdown(text: String): ProCoerceResult {
        val hit = options.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: return ProCoerceResult(defaultValue, true, "不在选项白名单内")
        return ProCoerceResult(hit, false, null)
    }

    private fun normalizeText(text: String): ProCoerceResult {
        if (maxChars > 0 && text.length > maxChars) {
            return ProCoerceResult(text.substring(0, maxChars), true, "超出 $maxChars 字符上限")
        }
        return ProCoerceResult(text, false, null)
    }

    private fun normalizeNumber(text: String): ProCoerceResult {
        val parsed = text.toDoubleOrNull()
            ?: return ProCoerceResult(defaultValue, true, "非法数值")
        if (!parsed.isFinite()) {
            return ProCoerceResult(defaultValue, true, "非有限数值（NaN/Inf）")
        }

        val (clamped, outOfRange) = when (val r = range) {
            ProRange.Unbounded -> parsed to false
            is ProRange.Continuous -> {
                val c = parsed.coerceIn(r.min, r.max)
                c to (c != parsed)
            }
            is ProRange.Stepped -> {
                val c = parsed.coerceIn(r.min.toDouble(), r.max.toDouble())
                val step = if (r.step <= 0L) 1L else r.step
                val snapped = r.min + Math.round((c - r.min) / step.toDouble()) * step
                snapped.coerceIn(r.min.toDouble(), r.max.toDouble()) to (snapped != parsed)
            }
        }

        val formatted = ProRange.format(clamped, decimals)
        val reason = when {
            outOfRange -> "超出 ${range.describe(decimals, unit)}"
            else -> null
        }
        return ProCoerceResult(formatted, reason != null, reason)
    }

    /** 供 UI 做数值控件绑定：把当前值转成 Double（失败回落默认）。 */
    fun valueAsDouble(raw: String?): Double =
        normalize(raw).value.toDoubleOrNull() ?: defaultValue.toDoubleOrNull() ?: 0.0

    /** 供 UI 做开关绑定。 */
    fun valueAsBoolean(raw: String?): Boolean = normalize(raw).value.toBooleanStrictOrNull() ?: false

    companion object {

        /** 数值控件通用格式化的对外别名（UI 层不用重复实现）。 */
        fun formatNumber(value: Double, decimals: Int): String = ProRange.format(value, decimals)
    }
}

/** 设置页的节内容：一个分栏 + 其下的设置项（或占位）。 */
data class ProSectionContent(
    val section: ProSectionSpec,
    val modules: List<ProModuleSpec>,
    val settings: List<ProSettingSpec>,
    val pendingModules: List<ProModuleSpec>,
) {
    /** 该节是否有真实设置项（false = 只出占位）。 */
    val hasSettings: Boolean get() = settings.isNotEmpty()

    /** 是否整体处于「待落地」状态。 */
    val isPlaceholderOnly: Boolean get() = settings.isEmpty() && pendingModules.isNotEmpty()
}
