package com.hinnka.mycamera.integration

import com.hinnka.mycamera.integration.IntegrationSources.count
import com.hinnka.mycamera.integration.IntegrationSources.kotlinSources
import com.hinnka.mycamera.integration.IntegrationSources.readMain
import com.hinnka.mycamera.integration.IntegrationSources.upperSnake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 组：交叉冲突回归——误设参数不崩溃（clamp 全覆盖抽查）＋ 集成期污染自检。
 *
 * 冲突背景：review_patch 把一批评审常量改造成「保留原 const（零回归）+ @Volatile var userX
 * （用户可调）+ 调用点收敛」的三段式；aiplatform / videomodes 又各自引入自己的 clamp 集。
 * 三者共存时最容易出的事故是「用户值越界 → 设备下发参数被拒 → 崩溃」，
 * 以及「补丁文案被误写进源码」（本轮实测确实发生过一次，见 integration.md 校验修复记录）。
 */
class CrossConflictClampTest {

    private val userVarRegex = Regex("""@Volatile\s+var\s+(user[A-Za-z0-9_]+)\s*:\s*[A-Za-z<>?]+(\s*[?]?)\s*=\s*([^\n]+)""")

    // ---------- A. 镜像：越界输入必须收敛而不抛异常 ----------

    private fun clamp(value: Float, min: Float, max: Float): Float =
        if (value.isNaN()) min else value.coerceIn(min, max)

    private fun clampInt(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    private fun clampLong(value: Long, min: Long, max: Long): Long = value.coerceIn(min, max)

    @Test
    fun floatClampSurvivesExtremeAndNan() {
        assertEquals(1f, clamp(Float.NaN, 1f, 8f), 0f)
        assertEquals(1f, clamp(Float.NEGATIVE_INFINITY, 1f, 8f), 0f)
        assertEquals(8f, clamp(Float.POSITIVE_INFINITY, 1f, 8f), 0f)
        assertEquals(1f, clamp(0.2f, 1f, 8f), 0f)
        assertEquals(8f, clamp(99f, 1f, 8f), 0f)
        assertEquals(3f, clamp(3f, 1f, 8f), 0f)
    }

    @Test
    fun intAndLongClampSurviveExtremes() {
        assertEquals(0, clampInt(Int.MIN_VALUE, 0, 4096))
        assertEquals(4096, clampInt(Int.MAX_VALUE, 0, 4096))
        assertEquals(0L, clampLong(Long.MIN_VALUE, 0L, 3_600_000L))
        assertEquals(3_600_000L, clampLong(Long.MAX_VALUE, 0L, 3_600_000L))
    }

    @Test
    fun zeroSentinelMeansAutoAndNeverCrash() {
        // videomodes/review 的哨兵语义：0（或 <0）表示自动，调用方必须能安全落到自动分支
        fun shutterOrAuto(userNs: Long, sensorUpper: Long, autoFallback: Long): Long =
            if (userNs > 0L) userNs.coerceAtMost(sensorUpper) else autoFallback
        assertEquals(50L, shutterOrAuto(0L, 1_000L, 50L))
        assertEquals(50L, shutterOrAuto(-1L, 1_000L, 50L))
        assertEquals(1_000L, shutterOrAuto(9_999L, 1_000L, 50L))
        assertEquals(400L, shutterOrAuto(400L, 1_000L, 50L))
    }

    // ---------- B. 源码：用户可调值的默认值必须等于既有常量（零回归） ----------

    @Test
    fun userOverridableValuesDefaultToExistingConstants() {
        val sources = kotlinSources()
        assertTrue("未扫描到工程源码，路径解析失败", sources.size > 100)
        var pairs = 0
        var constants = 0
        var literals = 0
        val unresolved = ArrayList<String>()
        sources.forEach { rel ->
            val src = readMain(rel)
            userVarRegex.findAll(src).forEach { m ->
                val name = m.groupValues[1]
                val init = m.groupValues[3].trim().trimEnd(',')
                pairs++
                val constName = upperSnake(name.removePrefix("user"))
                when {
                    init == constName -> constants++
                    init.firstOrNull()?.isDigit() == true -> literals++
                    // 允许引用其它常量/对象成员（如 VideoBitrateLimits.maxBps()），但不允许引用其它 user 变量
                    init.contains("user") || init.isEmpty() -> unresolved += "$rel:$name = $init"
                    else -> constants++
                }
            }
        }
        assertTrue("未发现任何 @Volatile var userX（review P1 三段式未落地）", pairs >= 10)
        // 默认值必须来自同名常量或以字面量显式给出，不允许引用其它可变量（否则默认值会漂移）
        assertTrue("默认值引用可疑：$unresolved", unresolved.isEmpty())
        assertTrue("以既有常量作默认值的比例过低：$constants/$pairs", constants >= pairs / 2)
    }

    @Test
    fun clampHelpersExistWhereUserValuesAreConsumed() {
        val c2 = readMain("java/com/hinnka/mycamera/camera/Camera2Controller.kt")
        assertTrue("Camera2Controller 缺少 AF 延迟收敛函数", c2.contains("AF_READY_DEFER_MS"))
        assertTrue("Camera2Controller 缺少高度系数收敛痕迹", c2.contains("heightFraction"))
        val st = readMain("java/com/hinnka/mycamera/camera/CameraState.kt")
        assertTrue("CameraState 未接手动视频参数策略", st.contains("ManualVideoParamPolicy"))
        val vm = readMain("java/com/hinnka/mycamera/viewmodel/CameraViewModel.kt")
        assertTrue("CameraViewModel 缺少多重曝光上限对象", vm.contains("object MultipleExposureLimits"))
        val cs = readMain("java/com/hinnka/mycamera/ui/camera/CameraScreen.kt")
        assertTrue("CameraScreen 缺少预览拍摄上限对象", cs.contains("object PreviewCaptureLimits"))
        assertTrue("CameraScreen 缺少摄影阈值对象", cs.contains("object PhotographyThresholds"))
    }

    @Test
    fun noPatchMarkdownLeakedIntoSourceTree() {
        // 本轮实测事故：补丁说明段被写进 AndroidManifest.xml 的 <application> 内。
        // 该用例把「源码树不含补丁文案」固化为回归约束。
        val markers = listOf("### 创建：", "> 解决 M4", "```kotlin", "锚点（唯一", "插入：在该")
        val offenders = ArrayList<String>()
        kotlinSources().forEach { rel -> if (markers.any { readMain(rel).contains(it) }) offenders += rel }
        listOf("AndroidManifest.xml", "res/values/strings.xml", "res/xml/file_paths.xml",
            "assets/luts/config.json", "assets/luts/downloadable_sources.json").forEach { rel ->
            val src = readMain(rel)
            if (markers.any { src.contains(it) }) offenders += rel
        }
        assertTrue("源码树存在补丁文案残留：$offenders", offenders.isEmpty())
        // 顺带锁定修复结果的完整性
        val mf = readMain("AndroidManifest.xml")
        assertTrue("manifest 缺少 networkSecurityConfig 属性（修复点）",
            mf.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertEquals("manifest 中 application 起始标签应只有一个未闭合的 <application",
            1, count(mf, "<application"))
    }

    @Test
    fun noDuplicatedInsertionsFromPatchApplication() {
        // 本轮实测事故：同一 ops 脚本二次执行导致整段插入重复。此处以「连续重复块」为判据回归。
        val touched = listOf(
            "computational/BgJobRunner.kt", "camera/CameraState.kt", "camera/Camera2Controller.kt",
            "ui/camera/CameraScreen.kt", "ui/camera/CameraGLSurfaceView.kt", "ui/camera/CameraPreviewGL.kt",
            "ui/camera/ComputationalSceneBar.kt", "ui/camera/CameraParameterBar.kt",
            "viewmodel/CameraViewModel.kt", "video/VideoRecorder.kt", "video/VideoEncoderColorConfig.kt",
            "video/VideoTypes.kt", "color/TransferCurve.kt", "raw/ColorSpace.kt",
            "lut/LutRenderer.kt", "lut/LutShaderMappings.kt", "lut/PreviewColorShaderModules.kt",
            "lut/LutRecommender.kt", "lut/LutDownloadManager.kt", "livephoto/LivePhotoRecorder.kt",
            "computational/ServerEnhancementClient.kt", "gallery/db/GalleryDatabase.kt",
            "gallery/db/GalleryMediaStore.kt", "gallery/db/GalleryMediaEntity.kt", "gallery/PhotoProcessor.kt",
            "ui/gallery/GalleryDetailScreen.kt", "ui/settings/SettingsScreen.kt", "MainActivity.kt",
            "data/UserPreferencesRepository.kt", "galleryedit/GeEditContract.kt",
            "appgallerypreview/AppGalleryPreviewEditContract.kt",
        )
        val suspicious = ArrayList<String>()
        kotlinSources().filter { rel -> touched.any { rel.endsWith(it) } }.forEach { rel ->
            val lines = readMain(rel).split("\n")
            for (k in 2..24) {
                for (i in 0..(lines.size - 2 * k)) {
                    val a = lines.subList(i, i + k)
                    val b = lines.subList(i + k, i + 2 * k)
                    if (a == b) {
                        val meaningful = a.filter { it.isNotBlank() }
                        if (meaningful.size >= 2 && meaningful.toSet().size > 1) {
                            suspicious += "$rel:${i + 1}（连续 ${k} 行重复）"
                        }
                    }
                }
            }
        }
        assertTrue("检测到连续重复插入：$suspicious", suspicious.isEmpty())
    }
}
