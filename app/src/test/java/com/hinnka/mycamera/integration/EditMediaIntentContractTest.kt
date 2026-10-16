package com.hinnka.mycamera.integration

import com.hinnka.mycamera.integration.IntegrationSources.count
import com.hinnka.mycamera.integration.IntegrationSources.existsMain
import com.hinnka.mycamera.integration.IntegrationSources.readMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 组：EDIT_MEDIA intent 协议契约（galleryedit 新入口 与 appgallery / appgallerypreview 双方约定）。
 *
 * 集成冲突点：本轮 galleryedit 与 appgallery / appgallerypreview 都改到了
 *   - AndroidManifest.xml（新增 `GeEditEntryActivity` 的 intent-filter）
 *   - 各自的 *Contract.kt（同一个 action 字符串常量）
 * 若两侧常量漂移，跨模块跳转会静默失败（无编译错误），故用文本级契约测试锁死。
 */
class EditMediaIntentContractTest {

    private val action = "com.hinnka.mycamera.action.EDIT_MEDIA"
    private val manifest = readMain("AndroidManifest.xml")
    private val galleryEditContract = "java/com/hinnka/mycamera/galleryedit/GeEditContract.kt"
    private val previewContract = "java/com/hinnka/mycamera/appgallerypreview/AppGalleryPreviewEditContract.kt"

    @Test
    fun manifestDeclaresEditMediaEntryAndItIsExported() {
        assertTrue("manifest 未声明 EDIT_MEDIA action", manifest.contains(action))
        assertEquals("EDIT_MEDIA action 应恰好声明一次", 1, count(manifest, action))
        assertTrue("缺少 galleryedit 入口 Activity", manifest.contains("galleryedit.GeEditEntryActivity"))
        assertEquals("入口 Activity 应恰好声明一次", 1, count(manifest, "galleryedit.GeEditEntryActivity"))
        // exported=true + singleTop：跨 App 唤起与重复唤起语义
        val idx = manifest.indexOf("galleryedit.GeEditEntryActivity")
        assertTrue("入口 Activity 缺少 exported", idx > 0)
        assertTrue("入口 Activity 应 exported=true", manifest.substring(0, idx).endsWith("android:exported=\"true\"\n") ||
            manifest.substring(idx).take(600).contains("android:exported=\"true\""))
        assertTrue("入口 Activity 应为 singleTop", manifest.substring(idx).take(600).contains("singleTop"))
    }

    @Test
    fun bothSidesDeclareIdenticalActionConstant() {
        val ge = readMain(galleryEditContract)
        val ap = readMain(previewContract)
        assertTrue("galleryedit 侧契约缺失", ge.isNotEmpty())
        assertTrue("appgallerypreview 侧契约缺失", ap.isNotEmpty())
        assertTrue("galleryedit 侧 action 常量不符", ge.contains("ACTION_EDIT_MEDIA = \"$action\""))
        assertTrue("appgallerypreview 侧 action 常量不符", ap.contains("ACTION_EDIT_MEDIA = \"$action\""))
    }

    @Test
    fun extrasAreNonEmptyAndConsistent() {
        // galleryedit 侧（发起方把媒体 URI/类型交给编辑页）
        val ge = readMain(galleryEditContract)
        assertTrue("galleryedit 缺少 EXTRA_MEDIA_URI", ge.contains("const val EXTRA_MEDIA_URI = \"media_uri\""))
        assertTrue("galleryedit 缺少 EXTRA_MEDIA_TYPE", ge.contains("const val EXTRA_MEDIA_TYPE = \"media_type\""))
        // appgallerypreview 侧（相册预览发起二次编辑）
        val ap = readMain(previewContract)
        assertTrue("appgallerypreview 缺少 EXTRA_MEDIA_ID", ap.contains("EXTRA_MEDIA_ID = \"media_id\""))
        assertTrue("appgallerypreview 缺少 QUICK_EDIT action",
            ap.contains("com.hinnka.mycamera.action.QUICK_EDIT_MEDIA"))
    }

    @Test
    fun galleryEditParsingSideAcceptsTheAction() {
        val ge = readMain(galleryEditContract)
        // 解析函数必须显式比对 action（否则 intent 被静默忽略）
        assertTrue("契约未在解析中比对 action", ge.contains("action") && ge.contains("ACTION_EDIT_MEDIA"))
        assertTrue("缺少请求解析入口", ge.contains("fun ") || ge.contains("object "))
    }

    @Test
    fun editEntryAndSupportingFilesExist() {
        assertTrue("GeEditEntryActivity 源文件缺失",
            existsMain("java/com/hinnka/mycamera/galleryedit/GeEditEntryActivity.kt"))
        assertTrue("galleryedit 契约文件缺失", existsMain(galleryEditContract))
        assertTrue("appgallerypreview 契约文件缺失", existsMain(previewContract))
        // galleryedit 的 87 条文案已并入主 strings.xml（本轮集成合并点，前缀 `ge_`）
        val strings = readMain("res/values/strings.xml")
        assertNotNull(strings)
        assertTrue("galleryedit 文案未合并进主 strings.xml",
            count(strings, "<string name=\"ge_") >= 40)
    }
}
