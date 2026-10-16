package com.hinnka.mycamera.aiplatform.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.BuildConfig
import com.hinnka.mycamera.R
import com.hinnka.mycamera.aiplatform.AiCallResult
import com.hinnka.mycamera.aiplatform.AiCapability
import com.hinnka.mycamera.aiplatform.AiCapabilityDefaults
import com.hinnka.mycamera.aiplatform.AiConfigCodec
import com.hinnka.mycamera.aiplatform.AiConfigPatch
import com.hinnka.mycamera.aiplatform.AiEndpointConfig
import com.hinnka.mycamera.aiplatform.AiFailureReason
import com.hinnka.mycamera.aiplatform.AiPlatform
import com.hinnka.mycamera.aiplatform.AiRequest
import com.hinnka.mycamera.ui.settings.NavigationSettingItem
import com.hinnka.mycamera.ui.settings.SettingsSection
import com.hinnka.mycamera.ui.settings.SwitchSettingItem
import com.hinnka.mycamera.ui.settings.TextInputSettingItem
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.launch

/** 设置页展示顺序（与「常用 → 少见」对齐）。 */
private val SETTINGS_ORDER = listOf(
    AiCapability.PHOTO_EVALUATION,
    AiCapability.LUT_RECIPE,
    AiCapability.IMAGE_RESTORE,
    AiCapability.MODEL_LIST,
    AiCapability.SERVER_ENHANCE,
    AiCapability.LUT_RECOMMEND,
    AiCapability.WHITE_BALANCE,
    AiCapability.SCENE_RECOGNITION,
)

/**
 * AI 能力与端点配置区（**补丁把这个 composable 挂到设置页 `SettingsPage.AI_SERVICE` 分支**）。
 *
 * 每个能力提供：启用开关 / Base URL / 接口路径 / API 密钥 / 模型名 / 鉴权方式 /
 * 连接与读取超时 / 重试次数与基础间隔 / 连通性测试 / 恢复默认。
 *
 * 所有写入都经 [AiConfigCodec] 归一化 + clamp，密钥经 [AiConfigStore] 加密落盘
 * （明文只在本 composable 的输入框里短暂存在）。
 */
@Composable
fun AiPlatformSettingsSection(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    capabilities: List<AiCapability> = SETTINGS_ORDER,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember(appContext) { AiPlatform.get(appContext).configStore }
    val configs = remember { mutableStateMapOf<AiCapability, AiEndpointConfig>() }
    val status = remember { mutableStateMapOf<AiCapability, String>() }
    val testing = remember { mutableStateMapOf<AiCapability, Boolean>() }
    val idleText = stringResource(R.string.ai_platform_status_idle)
    val okText = stringResource(R.string.ai_platform_probe_ok)

    LaunchedEffect(store) {
        capabilities.forEach { capability ->
            configs[capability] = runCatching { store.config(capability) }
                .getOrElse { defaultConfig(capability) }
        }
    }

    /** 立即更新本地展示（配置夹取后回显），再异步落盘。 */
    fun update(capability: AiCapability, patch: AiConfigPatch) {
        val current = configs[capability] ?: return
        configs[capability] = AiConfigCodec.applyPatch(current, patch)
        scope.launch {
            runCatching { store.setUserOverride(capability, patch) }
                .onFailure { PLog.w(TAG, "写入 AI 端点配置失败：${it.message}") }
        }
    }

    fun reset(capability: AiCapability) {
        configs[capability] = defaultConfig(capability)
        status[capability] = idleText
        scope.launch { runCatching { store.clearUserOverride(capability) } }
    }

    fun probe(capability: AiCapability) {
        testing[capability] = true
        status[capability] = idleText
        scope.launch {
            val platform = AiPlatform.get(appContext)
            val result = platform.httpClient.executeJson(
                capability = capability,
                request = AiRequest(method = "GET"),
                parse = { json -> json.take(PROBE_SNIPPET_CHARS) },
            )
            status[capability] = when (result) {
                is AiCallResult.Success -> okText
                is AiCallResult.Failure -> appContext.getString(probeResultResId(result.reason))
            }
            testing[capability] = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        capabilities.forEach { capability ->
            val config = configs[capability] ?: defaultConfig(capability)
            SettingsSection(
                title = stringResource(capabilityTitleRes(capability)),
                description = stringResource(R.string.ai_platform_section_desc),
            ) {
                SwitchSettingItem(
                    title = stringResource(R.string.ai_platform_enabled),
                    description = if (config.isConfigured) {
                        config.baseUrl
                    } else {
                        stringResource(R.string.ai_platform_not_configured)
                    },
                    checked = config.userEnabled,
                    onCheckedChange = { enabled -> update(capability, AiConfigPatch(enabled = enabled)) },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_base_url),
                    description = stringResource(R.string.ai_platform_base_url_desc),
                    value = config.baseUrl,
                    onValueChange = { value -> update(capability, AiConfigPatch(baseUrl = value)) },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_path),
                    description = stringResource(R.string.ai_platform_path_desc),
                    value = config.path,
                    onValueChange = { value -> update(capability, AiConfigPatch(path = value)) },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_api_key),
                    description = stringResource(R.string.ai_platform_api_key_desc),
                    value = config.apiKey,
                    onValueChange = { value -> update(capability, AiConfigPatch(apiKey = value)) },
                    isSecret = true,
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_model),
                    description = null,
                    value = config.model,
                    onValueChange = { value -> update(capability, AiConfigPatch(model = value)) },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_auth_scheme),
                    description = stringResource(R.string.ai_platform_auth_scheme_desc),
                    value = config.authScheme,
                    onValueChange = { value -> update(capability, AiConfigPatch(authScheme = value)) },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_connect_timeout),
                    description = null,
                    value = config.connectTimeoutMs.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { parsed ->
                            update(capability, AiConfigPatch(connectTimeoutMs = parsed))
                        }
                    },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_read_timeout),
                    description = null,
                    value = config.readTimeoutMs.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { parsed ->
                            update(capability, AiConfigPatch(readTimeoutMs = parsed))
                        }
                    },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_max_retries),
                    description = null,
                    value = config.maxRetries.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { parsed ->
                            update(capability, AiConfigPatch(maxRetries = parsed))
                        }
                    },
                )

                divider()

                TextInputSettingItem(
                    title = stringResource(R.string.ai_platform_retry_delay),
                    description = null,
                    value = config.retryBaseDelayMs.toString(),
                    onValueChange = { value ->
                        value.toLongOrNull()?.let { parsed ->
                            update(capability, AiConfigPatch(retryBaseDelayMs = parsed))
                        }
                    },
                )

                divider()

                NavigationSettingItem(
                    title = stringResource(R.string.ai_platform_probe),
                    description = status[capability] ?: idleText,
                    enabled = testing[capability] != true,
                    showProgress = testing[capability] == true,
                    onClick = { probe(capability) },
                )

                NavigationSettingItem(
                    title = stringResource(R.string.ai_platform_reset),
                    description = stringResource(R.string.ai_platform_reset_desc),
                    onClick = { reset(capability) },
                )
            }
        }
    }
}

@Composable
private fun divider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 展示用默认配置（内置族回落到 BuildConfig 内置端点，与运行时口径一致）。 */
private fun defaultConfig(capability: AiCapability): AiEndpointConfig =
    AiCapabilityDefaults.forCapability(
        capability = capability,
        builtInBaseUrl = BuildConfig.BUILT_IN_API_URL,
        builtInApiKey = BuildConfig.BUILT_IN_API_KEY,
    )

/** 失败归因 → 字符串资源 id（纯函数，供 Composable 与非 Composable 的协程复用）。 */
@StringRes
private fun probeResultResId(reason: AiFailureReason): Int = when (reason) {
    AiFailureReason.Disabled -> R.string.ai_error_disabled
    AiFailureReason.MissingCredential -> R.string.ai_error_missing_credential
    AiFailureReason.Offline -> R.string.ai_error_offline
    is AiFailureReason.Timeout -> R.string.ai_error_timeout
    is AiFailureReason.Http -> when (reason.code) {
        401, 403 -> R.string.ai_error_unauthorized
        429 -> R.string.ai_error_rate_limited
        in 500..599 -> R.string.ai_error_server
        else -> R.string.ai_error_http
    }
    is AiFailureReason.Parse -> R.string.ai_error_parse
    is AiFailureReason.CleartextBlocked -> R.string.ai_error_cleartext
    is AiFailureReason.Tls -> R.string.ai_error_tls
    AiFailureReason.Cancelled -> R.string.ai_error_cancelled
    is AiFailureReason.Unknown -> R.string.ai_error_unknown
}

@Composable
private fun probeResultText(reason: AiFailureReason): String =
    stringResource(probeResultResId(reason))

@StringRes
private fun capabilityTitleRes(capability: AiCapability): Int = when (capability) {
    AiCapability.SERVER_ENHANCE -> R.string.ai_platform_cap_server_enhance
    AiCapability.LUT_RECIPE -> R.string.ai_platform_cap_lut_recipe
    AiCapability.IMAGE_RESTORE -> R.string.ai_platform_cap_image_restore
    AiCapability.PHOTO_EVALUATION -> R.string.ai_platform_cap_photo_evaluation
    AiCapability.MODEL_LIST -> R.string.ai_platform_cap_model_list
    AiCapability.LUT_RECOMMEND -> R.string.ai_platform_cap_lut_recommend
    AiCapability.WHITE_BALANCE -> R.string.ai_platform_cap_white_balance
    AiCapability.SCENE_RECOGNITION -> R.string.ai_platform_cap_scene_recognition
    // DECLINATION 不在 SETTINGS_ORDER（设置页不展示），专用标题串未补；借相邻通用串保持穷尽。
    AiCapability.DECLINATION -> R.string.ai_platform_cap_scene_recognition
}

private const val PROBE_SNIPPET_CHARS = 200
private const val TAG = "AiPlatformSettings"
