package com.hinnka.mycamera.ui.uisettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hinnka.mycamera.ui.settings.NavigationSettingItem
import com.hinnka.mycamera.uisettings.ProRoute
import com.hinnka.mycamera.uisettings.ProSettingsRegistry
import com.hinnka.mycamera.uisettings.ProSettingsRouting

/**
 * 「Pro 设置」的路由与入口项。
 *
 * 本文件只做两件事：
 * 1) 路由字符串的构造 / 解析（纯字符串运算，不依赖 `android.net.Uri`，便于 JVM 单测）；
 * 2) 供**既有设置页**复用的入口行 [ProSettingsEntryItem] —— 这就是补丁里那一行入口项。
 *
 * 路由格式：`pro_settings?section=sec.video&setting=videomodes.fps.targetFps`
 * （两个参数都可省略；非法值一律由 [ProSettingsRouting] 回落，不会崩。）
 */
object ProSettingsRoute {

    /** 路由基址（navigation-compose 的 route name）。 */
    const val BASE = "pro_settings"

    /** 参数名。 */
    const val ARG_SECTION = "section"
    const val ARG_SETTING = "setting"

    /** 构造路由。 */
    fun build(sectionId: String? = null, settingId: String? = null): String {
        val params = mutableListOf<String>()
        if (!sectionId.isNullOrBlank()) params += "$ARG_SECTION=$sectionId"
        if (!settingId.isNullOrBlank()) params += "$ARG_SETTING=$settingId"
        return if (params.isEmpty()) BASE else "$BASE?" + params.joinToString("&")
    }

    /** 深链到某个设置项（分栏由 id 前缀推出，无需调用方提供）。 */
    fun deepLink(settingId: String): String = build(sectionId = null, settingId = settingId)

    /** 解析路由：任何非法输入都回落到「第一个有内容的分栏」。 */
    fun parse(route: String?): ProRoute {
        val raw = route?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith(BASE)) return ProSettingsRouting.routeForSection(null)
        val query = raw.substringAfter('?', missingDelimiterValue = "")
        val args = query.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
            }
            .toMap()
        return ProSettingsRouting.restore(args[ARG_SECTION], args[ARG_SETTING])
    }

    /** 入口行的副标题：让用户一眼看到「这里聚合了多少东西」。 */
    fun entryDescription(): String =
        "统一专业设置：${ProSettingsRegistry.wiredModuleCount} 个模块 · " +
            "${ProSettingsRegistry.editableSettings.size} 项参数（对标 GCam / ProCam / Blackmagic / MotionCam / OpenCamera）"

    /** 全部可深链的设置项 id（供集成层做「帮助 / 诊断」跳转表）。 */
    fun deepLinks(): List<String> = ProSettingsRegistry.editableSettings.map { deepLink(it.id) }
}

/**
 * 既有设置页里的入口行（补丁只加这一行）。
 *
 * 用法（见 `delivery/patches/uisettings_patch.md`）：
 * ```
 * NavigationSettingItem(
 *     title = "Pro 设置",
 *     description = "统一专业设置：16 个模块 · 470+ 项参数…",
 *     onClick = { openProSettings() },
 * )
 * ```
 * 为避免调用方手写文案与图标，这里直接包一层。
 */
@Composable
fun ProSettingsEntryItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NavigationSettingItem(
        title = "Pro 设置",
        description = ProSettingsRoute.entryDescription(),
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    )
}
