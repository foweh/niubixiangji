package com.hinnka.mycamera.aiplatform.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hinnka.mycamera.R
import com.hinnka.mycamera.lut.LutDownloadManager
import com.hinnka.mycamera.ui.settings.NavigationSettingItem
import com.hinnka.mycamera.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「可拆卸 LUT 下载包」管理区（**补丁挂到设置页 `SettingsPage.AI_SERVICE` 分支**，
 * 解决审计报告 F3「`LutDownloadManager` 零调用方」）。
 *
 * 行为：
 *  - 进入即 `loadCatalog()`（内置 assets 目录或远端目录覆盖）；
 *  - 每个包一行，点击 `downloadAndInstall(pack)` → 经 `CustomImportManager.importLut`
 *    注册为 custom_* 条目，自动进入 LutManager 查找路径；
 *  - `fileUrl` 为空的包按钮禁用并提示「去源仓库获取」——**不提供不合规直链**。
 */
@Composable
fun AiLutDownloadSection(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val manager = remember(appContext) { LutDownloadManager(appContext) }
    val packs = remember { mutableStateListOf<LutDownloadManager.DownloadablePack>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val status = remember { mutableStateMapOf<String, String>() }
    val idleText = stringResource(R.string.ai_lut_status_idle)
    val downloadingText = stringResource(R.string.ai_lut_status_downloading)
    val installedText = stringResource(R.string.ai_lut_status_installed)
    val failedText = stringResource(R.string.ai_lut_status_failed)
    val needManualText = stringResource(R.string.ai_lut_status_need_manual)

    LaunchedEffect(manager) {
        val loaded = withContext(Dispatchers.IO) { runCatching { manager.loadCatalog() }.getOrDefault(emptyList()) }
        packs.clear()
        packs.addAll(loaded)
        loaded.forEach { pack ->
            status[pack.id] = if (pack.fileUrl.isBlank()) needManualText else idleText
        }
    }

    fun install(pack: LutDownloadManager.DownloadablePack) {
        if (busy[pack.id] == true) return
        busy[pack.id] = true
        status[pack.id] = downloadingText
        scope.launch {
            val lutId = withContext(Dispatchers.IO) { runCatching { manager.downloadAndInstall(pack) }.getOrNull() }
            status[pack.id] = if (lutId != null) installedText else failedText
            busy[pack.id] = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(
            title = stringResource(R.string.ai_lut_download_section),
            description = stringResource(R.string.ai_lut_download_section_desc),
        ) {
            if (packs.isEmpty()) {
                NavigationSettingItem(
                    title = stringResource(R.string.ai_lut_download_empty),
                    description = "",
                    enabled = false,
                    onClick = {},
                )
            }
            packs.forEach { pack ->
                NavigationSettingItem(
                    title = pack.nameZh.ifBlank { pack.nameEn },
                    description = listOfNotNull(
                        pack.license.takeIf { it.isNotBlank() },
                        pack.starNote.takeIf { it.isNotBlank() },
                        status[pack.id] ?: idleText,
                    ).joinToString(" · "),
                    enabled = pack.fileUrl.isNotBlank() && busy[pack.id] != true,
                    showProgress = busy[pack.id] == true,
                    onClick = { install(pack) },
                )
            }
        }
    }
}
