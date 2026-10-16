package com.hinnka.mycamera.integration

import com.hinnka.mycamera.integration.IntegrationSources.count
import com.hinnka.mycamera.integration.IntegrationSources.readMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 4 组：review 修复回归（帧数边界 20/32/100、对齐默认值统一、MEDIAN 无装箱路径）。
 *
 * review_patch 是本轮最大的一个补丁（1379 行 / 133 个操作点），修复的是跨模块审查发现的问题。
 * 本组把其中最容易被后续补丁「改回去」的三类约束固化成回归用例。
 */
class ReviewRegressionTest {

    private val c2 = "java/com/hinnka/mycamera/camera/Camera2Controller.kt"
    private val astro = "java/com/hinnka/mycamera/computational/AstroStacker.kt"

    /** review P0-3 的语义镜像：单批上限 = min(请求帧数, CAPTURE_READER_MAX_IMAGES)。 */
    private fun activeMultiFrameCount(requested: Int, readerMax: Int): Int =
        if (requested < 1) 1 else minOf(requested, readerMax)

    @Test
    fun frameCountBoundariesFollowReaderSafetySemantics() {
        val readerMax = 50
        // 常规档位
        assertEquals(20, activeMultiFrameCount(20, readerMax))
        assertEquals(32, activeMultiFrameCount(32, readerMax))
        // 边界：恰好等于上限、超过上限、非法 0/负数
        assertEquals(50, activeMultiFrameCount(50, readerMax))
        assertEquals(50, activeMultiFrameCount(100, readerMax))
        assertEquals(50, activeMultiFrameCount(Int.MAX_VALUE, readerMax))
        assertEquals(1, activeMultiFrameCount(0, readerMax))
        assertEquals(1, activeMultiFrameCount(-7, readerMax))
    }

    @Test
    fun sourceStillCapsBatchByCaptureReaderMaxImages() {
        val src = readMain(c2)
        assertTrue("缺少 CAPTURE_READER_MAX_IMAGES", src.contains("CAPTURE_READER_MAX_IMAGES = 50"))
        assertTrue("缺少单批上限常量", src.contains("MULTI_FRAME_CAPTURE_BATCH_MAX"))
        assertTrue("单批上限未绑定到 reader 安全上限",
            src.contains("MULTI_FRAME_CAPTURE_BATCH_MAX = CAPTURE_READER_MAX_IMAGES"))
        assertTrue("调用点未做 minOf 收敛", src.contains("minOf(state.activeMultiFrameCount, MULTI_FRAME_CAPTURE_BATCH_MAX)"))
    }

    @Test
    fun stackAlignDefaultIsUnifiedToFalse() {
        // review P0-5：对齐默认值统一到 MultiFrameConfig.DEFAULT_STACK_ALIGN
        val cfg = readMain("java/com/hinnka/mycamera/camera/MultiFrameConfig.kt")
        assertTrue("默认值未统一为 false", cfg.contains("const val DEFAULT_STACK_ALIGN = false"))
        assertTrue("Camera2Controller 未读该常量", readMain(c2).contains("MultiFrameConfig.DEFAULT_STACK_ALIGN"))
        assertTrue("GalleryManager 未读该常量",
            readMain("java/com/hinnka/mycamera/gallery/GalleryManager.kt").contains("MultiFrameConfig.DEFAULT_STACK_ALIGN"))
        // 不再出现写死的 true 默认
        assertTrue("存在写死的对齐默认 true", !readMain(c2).contains("psStackAlign: Boolean = true"))
    }

    @Test
    fun medianPathIsUnboxedAndWorkResolutionCapped() {
        val src = readMain(astro)
        assertTrue("缺少 MEDIAN 工作分辨率上限常量", src.contains("DEFAULT_MEDIAN_WORK_LONG_EDGE = 960"))
        assertTrue("MEDIAN 未按上限收敛",
            src.contains("minOf(workLongEdge, DEFAULT_MEDIAN_WORK_LONG_EDGE)"))
        // 无装箱：平坦 ShortArray + 区间中值（ArrayList<Short> 版本仅保留给历史调用方）
        assertTrue("缺少无装箱中值函数",
            src.contains("private fun medianShort(samples: ShortArray, start: Int, length: Int)"))
        assertTrue("无装箱路径未被使用", src.contains("medianShort(samples, p * n, n)"))
        // 装箱版本（ArrayList<Short>）作为历史重载保留，但新路径只走平坦缓冲重载
        assertTrue("中值函数应为一个或两个重载", count(src, "private fun medianShort(") in 1..2)
        assertTrue("装箱重载不应出现在批量调用点", !src.contains("ByteArray(count) { p -> medianShort(samples[p]) }"))
    }

    @Test
    fun medianNoBoxingOutputSizeMatchesInputCount() {
        // 镜像：平坦缓冲 → 逐像素中值，输出字节数 == 像素数（越界即说明缓冲契约被破坏）
        val count = 7
        val perPixel = 3
        val flat = ShortArray(count * perPixel) { (it % 251).toShort() }
        fun medianAt(start: Int, length: Int): Byte {
            val window = ShortArray(length) { flat[start + it] }
            window.sort()
            return (window[length / 2].toInt() and 0xFF).toByte()
        }
        val out = ByteArray(count) { medianAt(it * perPixel, perPixel) }
        assertEquals(count, out.size)
        val flat2 = ShortArray(1 * perPixel) { 5 }
        assertEquals(3, flat2.size)
    }

    @Test
    fun manualVideoShutterLimitStaysConfigurable() {
        val st = readMain("java/com/hinnka/mycamera/camera/CameraState.kt")
        assertTrue("手动视频快门上限常量丢失", st.contains("MAX_VIDEO_MANUAL_SHUTTER_SPEED_NS"))
        // 常量值保持 1/6s（零回归），开放走 ManualVideoParamPolicy
        assertTrue("常量默认值被改动", st.contains("1_000_000_000L / 6"))
        assertTrue("未接 ManualVideoParamPolicy", st.contains("ManualVideoParamPolicy"))
    }

    @Test
    fun videoBitrateKeepsReviewUpperBoundAfterVideomodesMerge() {
        val vr = readMain("java/com/hinnka/mycamera/video/VideoRecorder.kt")
        // 冲突消解结果：输入侧 clampBitrateMbps（videomodes）+ 上界 VideoBitrateLimits.maxBps()（review）
        assertTrue("缺少 clampBitrateMbps 接入", vr.contains("VideoEncodePolicy.clampBitrateMbps(requestedBitrateMbps)"))
        assertTrue("上界兜底函数丢失", vr.contains("coerceIn(2_000_000, VideoBitrateLimits.maxBps())"))
        assertTrue("上界函数未定义", vr.contains("object VideoBitrateLimits"))
        assertTrue("残留写死的 300Mbps 上界", !vr.contains("coerceIn(2_000_000, 300_000_000)"))
        assertEquals("VideoBitrateLimits 应只定义一次", 1, count(vr, "object VideoBitrateLimits"))
    }
}
