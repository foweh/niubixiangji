package com.hinnka.mycamera.scenerecog

import com.hinnka.mycamera.lut.LutRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 场景识别与既有模块的联动 + 远端协议解析（异常输入分支）。
 *
 * 注：`parseRemoteClassification` 的成功路径依赖 `org.json`，单元测试环境提供的是
 * Android 桩实现，故此处只覆盖「不可解析 → null」的异常分支（成功路径由设备/集成阶段验证）。
 */
class SceneRecognitionLinkageTest {

    @Test
    fun `线上名称大小写与别名容错`() {
        assertEquals(SceneLabel.PORTRAIT, SceneRecognitionService.labelFromWireName("PORTRAIT"))
        assertEquals(SceneLabel.PORTRAIT, SceneRecognitionService.labelFromWireName("portrait"))
        assertEquals(SceneLabel.PORTRAIT, SceneRecognitionService.labelFromWireName("  Person "))
        assertEquals(SceneLabel.LANDSCAPE, SceneRecognitionService.labelFromWireName("scenery"))
        assertEquals(SceneLabel.NIGHT, SceneRecognitionService.labelFromWireName("LOW_LIGHT"))
        assertEquals(SceneLabel.STREET, SceneRecognitionService.labelFromWireName("urban"))
        assertEquals(SceneLabel.TEXT, SceneRecognitionService.labelFromWireName("screenshot"))
        assertEquals(SceneLabel.UNKNOWN, SceneRecognitionService.labelFromWireName(""))
        assertEquals(SceneLabel.UNKNOWN, SceneRecognitionService.labelFromWireName(null))
        assertEquals(SceneLabel.UNKNOWN, SceneRecognitionService.labelFromWireName("banana"))
    }

    @Test
    fun `不可解析的响应返回 null 而不是崩溃`() {
        assertNull(SceneRecognitionService.parseRemoteClassification("not-json"))
        assertNull(SceneRecognitionService.parseRemoteClassification(""))
    }

    @Test
    fun `场景标签映射到 LutRecommender 场景`() {
        assertEquals(LutRecommender.Scene.PORTRAIT, SceneRecognitionService.toLutRecommenderScene(SceneLabel.PORTRAIT))
        assertEquals(LutRecommender.Scene.LANDSCAPE, SceneRecognitionService.toLutRecommenderScene(SceneLabel.LANDSCAPE))
        assertEquals(LutRecommender.Scene.NIGHT, SceneRecognitionService.toLutRecommenderScene(SceneLabel.NIGHT))
        assertEquals(LutRecommender.Scene.STREET, SceneRecognitionService.toLutRecommenderScene(SceneLabel.STREET))
        // LutRecommender 无 TEXT，映射为 NEUTRAL（保持既有行为，不产生非法场景）
        assertEquals(LutRecommender.Scene.NEUTRAL, SceneRecognitionService.toLutRecommenderScene(SceneLabel.TEXT))
        assertEquals(LutRecommender.Scene.NEUTRAL, SceneRecognitionService.toLutRecommenderScene(SceneLabel.UNKNOWN))
    }
}
