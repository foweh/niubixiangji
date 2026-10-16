package com.hinnka.mycamera.galleryedit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hinnka.mycamera.ui.galleryedit.GeQuickEditScreen
import com.hinnka.mycamera.ui.theme.PhotonCameraTheme
import com.hinnka.mycamera.utils.PLog

private const val TAG = "GeEditEntryActivity"

/**
 * 编辑入口 Activity —— 跨代理 `com.hinnka.mycamera.action.EDIT_MEDIA` 协议的接收端。
 *
 * 协议（见 [GeEditContract]，**只依赖本模块符号，不依赖独立相册 V4 的任何类**）：
 * - action：`com.hinnka.mycamera.action.EDIT_MEDIA`
 * - extras：`media_uri`（String / Uri）、`media_type`（`image` | `video`）
 * - 兼容：未带 `media_type` 时按 intent type / uri 扩展名推断；`media_uri` 缺失时读 `intent.data`。
 *
 * manifest 注册（由补丁 `galleryedit_patch.md` 提供，exported=true 才能被 V4 调起）。
 * 解析失败时静默 finish（不崩溃、不弹错）。
 */
class GeEditEntryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = resolveRequest()
        if (request == null) {
            PLog.w(
                TAG,
                "EDIT_MEDIA ignored: action=${intent?.action}, uri=${intent?.data}, type=${intent?.type}",
            )
            finish()
            return
        }
        PLog.i(TAG, "EDIT_MEDIA accepted: type=${request.mediaType} uri=${request.mediaUri}")
        setContent {
            PhotonCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GeQuickEditScreen(
                        mediaUri = request.mediaUri,
                        mediaType = request.mediaType,
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    /** 同时兼容 `media_uri` extra 与 `intent.data`，并允许用 MIME / 扩展名兜底推断类型。 */
    private fun resolveRequest(): GeEditRequest? {
        val current = intent ?: return null
        val extraUri = current.getStringExtra(GeEditContract.EXTRA_MEDIA_URI)
        val dataUri = current.data?.toString()
        return GeEditContract.parse(
            action = current.action,
            mediaUriString = extraUri ?: dataUri,
            mediaTypeString = current.getStringExtra(GeEditContract.EXTRA_MEDIA_TYPE),
            mimeType = current.type,
        )
    }
}
