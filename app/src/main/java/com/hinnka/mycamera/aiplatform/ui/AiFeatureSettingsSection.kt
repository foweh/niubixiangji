package com.hinnka.mycamera.aiplatform.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
import com.hinnka.mycamera.R
import com.hinnka.mycamera.aiwb.AiWhiteBalanceConfig
import com.hinnka.mycamera.aiwb.AiWhiteBalanceSettings
import com.hinnka.mycamera.scenerecog.SceneRecognitionConfig
import com.hinnka.mycamera.scenerecog.SceneRecognitionSettings
import com.hinnka.mycamera.ui.settings.NavigationSettingItem
import com.hinnka.mycamera.ui.settings.SettingsSection
import com.hinnka.mycamera.ui.settings.SwitchSettingItem
import com.hinnka.mycamera.ui.settings.TextInputSettingItem
import kotlinx.coroutines.launch

/**
 * AI 白平衡 / AI 场景识别 的参数区（**补丁挂到设置页 `SettingsPage.AI_SERVICE` 分支**）。
 *
 * 每个可调参数都可在 UI 修改，写入前一律 clamp；「恢复默认」清空本模块业务参数。
 * 这些参数通过 [AiWhiteBalanceSettings] / [SceneRecognitionSettings] 落 DataStore，
 * 管线（补丁接线的出片/预览路径）读取同一份配置。
 */
@Composable
fun AiFeatureSettingsSection(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val wbConfigs = remember { mutableStateMapOf<String, AiWhiteBalanceConfig>() }
    val sceneConfigs = remember { mutableStateMapOf<String, SceneRecognitionConfig>() }
    val wbKey = "white_balance"
    val sceneKey = "scene_recognition"

    LaunchedEffect(appContext) {
        wbConfigs[wbKey] = AiWhiteBalanceSettings.load(appContext)
        sceneConfigs[sceneKey] = SceneRecognitionSettings.load(appContext)
    }

    fun updateWhiteBalance(transform: (AiWhiteBalanceConfig) -> AiWhiteBalanceConfig) {
        val current = wbConfigs[wbKey] ?: AiWhiteBalanceConfig.DEFAULT
        val next = AiWhiteBalanceConfig.clamp(transform(current))
        wbConfigs[wbKey] = next
        scope.launch { AiWhiteBalanceSettings.save(appContext, next) }
    }

    fun updateScene(transform: (SceneRecognitionConfig) -> SceneRecognitionConfig) {
        val current = sceneConfigs[sceneKey] ?: SceneRecognitionConfig.DEFAULT
        val next = SceneRecognitionConfig.clamp(transform(current))
        sceneConfigs[sceneKey] = next
        scope.launch { SceneRecognitionSettings.save(appContext, next) }
    }

    val whiteBalance = wbConfigs[wbKey] ?: AiWhiteBalanceConfig.DEFAULT
    val scene = sceneConfigs[sceneKey] ?: SceneRecognitionConfig.DEFAULT

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(
            title = stringResource(R.string.ai_feature_white_balance),
            description = stringResource(R.string.ai_feature_white_balance_desc),
        ) {
            SwitchSettingItem(
                title = stringResource(R.string.ai_feature_enabled),
                description = AiWhiteBalanceSettings.describe(whiteBalance),
                checked = whiteBalance.enabled,
                onCheckedChange = { value -> updateWhiteBalance { it.copy(enabled = value) } },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_wb_strength),
                description = stringResource(R.string.ai_feature_wb_strength_desc),
                value = whiteBalance.strength.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateWhiteBalance { it.copy(strength = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_wb_min_kelvin),
                description = null,
                value = whiteBalance.minKelvin.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { parsed -> updateWhiteBalance { it.copy(minKelvin = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_wb_max_kelvin),
                description = null,
                value = whiteBalance.maxKelvin.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { parsed -> updateWhiteBalance { it.copy(maxKelvin = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_wb_max_gain),
                description = null,
                value = whiteBalance.maxGain.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateWhiteBalance { it.copy(maxGain = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_sample_stride),
                description = stringResource(R.string.ai_feature_sample_stride_desc),
                value = whiteBalance.sampleStride.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { parsed -> updateWhiteBalance { it.copy(sampleStride = parsed) } }
                },
            )

            sectionDivider()

            SwitchSettingItem(
                title = stringResource(R.string.ai_feature_use_remote),
                description = stringResource(R.string.ai_feature_use_remote_desc),
                checked = whiteBalance.useRemoteApi,
                onCheckedChange = { value -> updateWhiteBalance { it.copy(useRemoteApi = value) } },
            )

            sectionDivider()

            NavigationSettingItem(
                title = stringResource(R.string.ai_feature_reset),
                description = stringResource(R.string.ai_feature_reset_desc),
                onClick = {
                    wbConfigs[wbKey] = AiWhiteBalanceConfig.DEFAULT
                    sceneConfigs[sceneKey] = SceneRecognitionConfig.DEFAULT
                    scope.launch {
                        AiWhiteBalanceSettings.save(appContext, AiWhiteBalanceConfig.DEFAULT)
                        SceneRecognitionSettings.save(appContext, SceneRecognitionConfig.DEFAULT)
                    }
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.ai_feature_scene_recognition),
            description = stringResource(R.string.ai_feature_scene_recognition_desc),
        ) {
            SwitchSettingItem(
                title = stringResource(R.string.ai_feature_enabled),
                description = SceneRecognitionSettings.describe(scene),
                checked = scene.enabled,
                onCheckedChange = { value -> updateScene { it.copy(enabled = value) } },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_scene_night_luma),
                description = stringResource(R.string.ai_feature_scene_threshold_desc),
                value = scene.nightMaxLuma.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateScene { it.copy(nightMaxLuma = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_scene_skin_fraction),
                description = stringResource(R.string.ai_feature_scene_threshold_desc),
                value = scene.portraitSkinFraction.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateScene { it.copy(portraitSkinFraction = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_scene_text_threshold),
                description = stringResource(R.string.ai_feature_scene_threshold_desc),
                value = scene.textScoreThreshold.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateScene { it.copy(textScoreThreshold = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_scene_street_warmth),
                description = stringResource(R.string.ai_feature_scene_threshold_desc),
                value = scene.streetMinWarmth.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { parsed -> updateScene { it.copy(streetMinWarmth = parsed) } }
                },
            )

            sectionDivider()

            TextInputSettingItem(
                title = stringResource(R.string.ai_feature_sample_stride),
                description = stringResource(R.string.ai_feature_sample_stride_desc),
                value = scene.sampleStride.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { parsed -> updateScene { it.copy(sampleStride = parsed) } }
                },
            )

            sectionDivider()

            SwitchSettingItem(
                title = stringResource(R.string.ai_feature_use_remote),
                description = stringResource(R.string.ai_feature_use_remote_desc),
                checked = scene.useRemoteApi,
                onCheckedChange = { value -> updateScene { it.copy(useRemoteApi = value) } },
            )
        }
    }
}

@Composable
private fun sectionDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
