package com.hinnka.mycamera.lut

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hinnka.mycamera.R
import com.hinnka.mycamera.aiplatform.AiParamStore
import com.hinnka.mycamera.ui.settings.SettingsSection
import com.hinnka.mycamera.ui.settings.SwitchSettingItem
import kotlinx.coroutines.launch

/** LUT 场景推荐的开关（aiplatform_patch.md 注入；默认关 = 既有行为）。 */
@Composable
fun LutAiSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        enabled = LutRecommendationBridge.isEnabled(context, default = false)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(
            title = stringResource(R.string.ai_lut_download_section),
            description = stringResource(R.string.ai_lut_recommend_desc),
        ) {
            SwitchSettingItem(
                title = stringResource(R.string.ai_lut_recommend_enabled),
                description = stringResource(R.string.ai_lut_recommend_desc),
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    scope.launch {
                        AiParamStore.writeBoolean(context, LutRecommendationBridge.ENABLED_KEY, checked)
                    }
                },
            )
        }
    }
}
