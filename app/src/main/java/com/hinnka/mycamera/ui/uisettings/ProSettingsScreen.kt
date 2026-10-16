package com.hinnka.mycamera.ui.uisettings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hinnka.mycamera.ui.settings.SettingsSection
import com.hinnka.mycamera.uisettings.ProRoute
import com.hinnka.mycamera.uisettings.ProSectionContent
import com.hinnka.mycamera.uisettings.ProSettingControl
import com.hinnka.mycamera.uisettings.ProSettingSpec
import com.hinnka.mycamera.uisettings.ProSettingsPlaceholder
import com.hinnka.mycamera.uisettings.ProSettingsRegistry
import com.hinnka.mycamera.uisettings.ProSettingsRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「Pro 设置」独立入口页。
 *
 * 定位：**独立新入口**（不塞进既有 4700+ 行的 `ui/settings/SettingsScreen.kt`）；
 * 既有设置页只通过 [ProSettingsEntryItem] 加一行入口项（见 uisettings_patch 补丁）。
 *
 * 数据流：
 * ```
 * ProSettingsPort（集成层实现：DataStore / SharedPreferences / 模块 Codec）
 *   → ProSettingsValueStore（收敛 + 差异 + 落盘）
 *   → ProSettingsPageState（Compose 状态：选中分栏 / 下拉展开 / 高亮）
 *   → ProSettingRow（复用既有控件）
 * ```
 * 未接线时 [port] 用 [InMemoryProSettingsPort] 兜底：页面可完整操作，只是不落盘。
 *
 * @param onCommitted 提交完成回调（写入的键数），集成层可据此弹一次 Toast。
 */
@Composable
fun ProSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    port: ProSettingsPort? = null,
    initialRoute: ProRoute? = null,
    onNavigate: (String) -> Unit = {},
    onCommitted: ((Int) -> Unit)? = null,
) {
    // 未接线时用内存端口兜底：页面可完整操作，只是不落盘（见 ProSettingsPort 注释）。
    val resolvedPort = port ?: remember { InMemoryProSettingsPort() }
    val state = remember(resolvedPort) { ProSettingsPageState(resolvedPort) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(resolvedPort) { state.load() }
    LaunchedEffect(initialRoute) {
        state.selectSection(initialRoute?.sectionId)
        initialRoute?.settingId?.let { state.highlight(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── 顶部：返回 + 标题 + 汇总行 + 重置本栏 / 提交
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Pro 设置", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = ProSettingsRegistry.summaryLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { state.resetSection() }) { Text("重置本栏") }
            TextButton(
                onClick = {
                    scope.launch {
                        val written = withContext(Dispatchers.IO) { state.commit() }
                        onCommitted?.invoke(written)
                    }
                },
                enabled = state.changedCount > 0,
            ) { Text(if (state.changedCount > 0) "提交 ${state.changedCount}" else "已同步") }
        }

        // ── 分栏选择：横向滚动胶囊（不依赖 Tab 组件的版本差异，视觉与既有设置页一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.sections.forEach { content ->
                SectionPill(
                    title = content.section.title,
                    selected = content.section.id == state.selectedSectionId,
                    onClick = { state.selectSection(content.section.id) },
                )
            }
        }

        // ── 内容：当前分栏
        val content = state.currentContent
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (content == null) {
                Text(
                    text = "暂无可用分栏",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                SectionBody(content = content, state = state, onNavigate = onNavigate)
            }
        }
    }
}

/** 分栏胶囊。 */
@Composable
private fun SectionPill(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 单个分栏的正文：按模块分组渲染设置项，末尾接「待接线」占位行。 */
@Composable
private fun SectionBody(
    content: ProSectionContent,
    state: ProSettingsPageState,
    onNavigate: (String) -> Unit,
) {
    SettingsSection(
        title = content.section.title,
        description = content.section.subtitle,
        showTitle = true,
    ) {
        content.settings.groupBy { it.moduleId }.forEach { (moduleId, settings) ->
            Text(
                text = "${moduleLabel(moduleId)}（${settings.count { it.control != ProSettingControl.PLACEHOLDER }} 项）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
            )
            settings.forEach { spec ->
                ProSettingRow(
                    spec = spec,
                    value = state.valueOf(spec.id),
                    onValueChange = { state.setValue(spec.id, it) },
                    expanded = state.isExpanded(spec.id),
                    onExpandChange = { state.setExpanded(spec.id, it) },
                    onNavigate = onNavigate,
                    onResetValue = { state.resetValue(spec.id) },
                    enabled = spec.control != ProSettingControl.PLACEHOLDER,
                )
            }
        }

        // 「待对应模块落地后接线」的占位行
        if (content.pendingModules.isNotEmpty()) {
            Text(
                text = "待接线模块",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp),
            )
            content.pendingModules.forEach { module ->
                ProSettingRow(
                    spec = ProSettingsPlaceholder.rowFor(module.id),
                    value = null,
                    onValueChange = {},
                    expanded = false,
                    onExpandChange = {},
                    onNavigate = onNavigate,
                    enabled = false,
                )
            }
        }
    }
}

/**
 * 页面状态（Compose 侧）。
 *
 * 与 [ProSettingsValueStore] 的分工：store 负责「收敛 / 差异 / 落盘」，
 * 本类只负责「选中哪一栏 / 哪条下拉展开 / 高亮项 / 触发重组」。
 */
class ProSettingsPageState(private val port: ProSettingsPort) {

    private val store = ProSettingsValueStore(port)

    /** 页面值：设置项 id → 当前字符串。 */
    val values = mutableStateMapOf<String, String>()

    /** 展开中的下拉项（同一时刻只允许一条）。 */
    private var expandedId by mutableStateOf<String?>(null)

    /** 深链进入时高亮的设置项。 */
    var highlightedId by mutableStateOf<String?>(null)
        private set

    var selectedSectionId by mutableStateOf<String?>(null)
        private set

    var changedCount by mutableStateOf(0)
        private set

    /** 有内容的分栏（真实设置项或占位）。 */
    val sections: List<ProSectionContent> get() = ProSettingsRegistry.sectionContents

    val currentContent: ProSectionContent?
        get() = ProSettingsRegistry.sectionContents.firstOrNull { it.section.id == selectedSectionId }
            ?: ProSettingsRegistry.sectionContents.firstOrNull()

    /** 全量加载（扁平键 + 打包容器字段）。 */
    fun load() {
        store.loadAll()
        values.clear()
        values.putAll(store.snapshot())
        changedCount = store.changedCount()
        selectedSectionId = currentContent?.section?.id
    }

    fun selectSection(sectionId: String?) {
        selectedSectionId = ProSettingsRouting.resolveSection(sectionId).id
        expandedId = null
    }

    fun highlight(settingId: String?) {
        highlightedId = settingId?.takeIf { ProSettingsRegistry.settingById.containsKey(it) }
    }

    fun valueOf(settingId: String): String? = values[settingId]

    fun isExpanded(settingId: String): Boolean = expandedId == settingId

    fun setExpanded(settingId: String, expanded: Boolean) {
        expandedId = if (expanded) settingId else null
    }

    fun setValue(settingId: String, raw: String?) {
        store.setValue(settingId, raw)
        values[settingId] = store.displayValueOf(settingId)
        changedCount = store.changedCount()
    }

    fun resetValue(settingId: String) {
        val reset = store.resetValue(settingId) ?: return
        values[settingId] = reset
        changedCount = store.changedCount()
    }

    /** 重置当前分栏。 */
    fun resetSection() {
        val sectionId = currentContent?.section?.id ?: return
        store.resetSection(sectionId)
        ProSettingsRegistry.settingsOfSection(sectionId).forEach { spec ->
            values[spec.id] = store.displayValueOf(spec.id)
        }
        changedCount = store.changedCount()
    }

    /** 提交到 port，返回写出的键数（调用方负责放到 IO 线程）。 */
    fun commit(): Int {
        val written = store.commit()
        changedCount = store.changedCount()
        return written
    }
}

/** 供集成层直接使用的设置项列表（含占位行；导出参数、生成文档等工具用）。 */
fun proSettingsRowsOf(sectionId: String): List<ProSettingSpec> =
    ProSettingsPlaceholder.renderListOf(sectionId)
