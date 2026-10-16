package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可用性/回退决策测试：覆盖「正常、边界、异常」三分支（PATTERN_SPEC 第 7 节）。
 *
 * 语义要点：
 *  - 能力不足（模型未下载 / 运行时不可用）→ **灰化 + 回退原图**，绝不失败；
 *  - scale=1 不是回退失败，而是「合法直通」（不放大也不算 AI）；
 *  - 输入非法 / 被用户关闭 → 直接原图，不占算力。
 */
class SuperResAvailabilityTest {

    private val x2 = SuperResModelCatalog.ID_REALESRGAN_X2
    private val x4 = SuperResModelCatalog.ID_REALESRGAN_X4

    // ---- 正常 ----

    @Test
    fun modelInstalled_runsInference() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(enabled = true, scale = 2),
            installedModelIds = setOf(x2),
            runtimeAvailable = true,
            inputWidth = 1024,
            inputHeight = 768,
        )
        assertEquals(SuperResAction.RUN_AI_SUPER_RES, d.action)
        assertEquals(x2, d.modelId)
        assertTrue(d.runsInference)
        assertFalse(d.fallsBackToOriginal)
        assertNull("小图无需降工作分辨率", d.degradedConfig)
    }

    @Test
    fun styleTransfer_preferredModelWinsWhenInstalled() {
        val d = SuperResAvailability.decideStyleTransfer(
            config = SuperResConfig(enabled = true, enableStyleTransfer = true, styleModelId = SuperResModelCatalog.ID_STYLE_MOSAIC),
            installedModelIds = setOf(SuperResModelCatalog.ID_STYLE_CANDY, SuperResModelCatalog.ID_STYLE_MOSAIC),
            runtimeAvailable = true,
            inputWidth = 800,
            inputHeight = 600,
        )
        assertEquals(SuperResAction.RUN_STYLE_TRANSFER, d.action)
        assertEquals(SuperResModelCatalog.ID_STYLE_MOSAIC, d.modelId)
    }

    @Test
    fun inpainting_runsWhenEnabledAndInstalled() {
        val d = SuperResAvailability.decideInpainting(
            config = SuperResConfig(enabled = true, enableInpainting = true),
            installedModelIds = setOf(SuperResModelCatalog.ID_INPAINT_LAMA),
            runtimeAvailable = true,
            inputWidth = 512,
            inputHeight = 512,
        )
        assertEquals(SuperResAction.RUN_INPAINTING, d.action)
        assertEquals(SuperResModelCatalog.ID_INPAINT_LAMA, d.modelId)
    }

    @Test
    fun preferredModelIgnoredWhenKindOrScaleMismatch() {
        // 指定 x4 但配置倍率是 2：不能错配模型，应回落 2x。
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(scale = 2),
            installedModelIds = setOf(x2, x4),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
            preferredModelId = x4,
        )
        assertEquals(x2, d.modelId)
    }

    // ---- 边界 ----

    @Test
    fun scaleOne_isPassThroughNotFallback() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(scale = 1),
            installedModelIds = emptySet(),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.PASS_THROUGH_NO_UPSCALE, d.action)
        assertFalse("直通不等于能力不足回退", d.fallsBackToOriginal)
        assertFalse(d.runsInference)
    }

    @Test
    fun oversizeInput_producesDegradedConfigInsteadOfRefusal() {
        val config = SuperResConfig(scale = 2, maxWorkingPixels = 12_000_000L)
        val d = SuperResAvailability.decideSuperRes(
            config = config,
            installedModelIds = setOf(x2),
            runtimeAvailable = true,
            inputWidth = 8000,
            inputHeight = 6000, // 48MP，远超上限
        )
        assertEquals(SuperResAction.RUN_AI_SUPER_RES, d.action)
        assertNotNull("超限时应给出降级配置而不是拒绝", d.degradedConfig)
        assertEquals(12_000_000L, d.degradedConfig!!.maxWorkingPixels)
        assertEquals("run_with_working_resolution_cap", d.reason)
    }

    @Test
    fun exactLimitInput_isNotDegraded() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(maxWorkingPixels = 12_000_000L),
            installedModelIds = setOf(x2),
            runtimeAvailable = true,
            inputWidth = 4000,
            inputHeight = 3000, // 恰好 12MP
        )
        assertNull(d.degradedConfig)
        assertEquals("run", d.reason)
    }

    @Test
    fun illegalConfig_isClampedBeforeDecision() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(scale = 2, maxWorkingPixels = -1L, tileSize = 99999),
            installedModelIds = setOf(x2),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        // tileSize 越界被 clamp；maxWorkingPixels=-1 → 0（不限）→ 不产生降级。
        assertEquals(SuperResAction.RUN_AI_SUPER_RES, d.action)
        assertNull(d.degradedConfig)
    }

    // ---- 异常 / 回退 ----

    @Test
    fun disabled_returnsDisabledAndFallsBack() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(enabled = false),
            installedModelIds = setOf(x2),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.DISABLED_BY_CONFIG, d.action)
        assertTrue(d.fallsBackToOriginal)
        assertFalse(d.runsInference)
    }

    @Test
    fun invalidInput_fallsBackToOriginal() {
        listOf(0 to 100, 100 to 0, -3 to 5).forEach { (w, h) ->
            val d = SuperResAvailability.decideSuperRes(
                config = SuperResConfig(),
                installedModelIds = setOf(x2),
                runtimeAvailable = true,
                inputWidth = w,
                inputHeight = h,
            )
            assertEquals("尺寸 ${w}x$h 应判非法", SuperResAction.INVALID_INPUT, d.action)
            assertTrue(d.fallsBackToOriginal)
        }
    }

    @Test
    fun disabledTakesPrecedenceOverInvalidInput() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(enabled = false),
            installedModelIds = emptySet(),
            runtimeAvailable = false,
            inputWidth = 0,
            inputHeight = 0,
        )
        assertEquals(SuperResAction.DISABLED_BY_CONFIG, d.action)
    }

    @Test
    fun runtimeUnavailable_fallsBackToOriginal() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(),
            installedModelIds = setOf(x2),
            runtimeAvailable = false,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.UNAVAILABLE_RUNTIME, d.action)
        assertTrue(d.fallsBackToOriginal)
    }

    @Test
    fun modelNotInstalled_requiresInstallAndFallsBackWithHint() {
        val d = SuperResAvailability.decideSuperRes(
            config = SuperResConfig(scale = 4),
            installedModelIds = emptySet(),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.INSTALL_MODEL_REQUIRED, d.action)
        assertTrue(d.fallsBackToOriginal)
        assertTrue("提示应指出需要哪个倍率的模型", d.reason.contains("x4"))
    }

    @Test
    fun styleAndInpaint_disabledByConfigWhenFeatureSwitchOff() {
        val style = SuperResAvailability.decideStyleTransfer(
            config = SuperResConfig(enabled = true, enableStyleTransfer = false),
            installedModelIds = setOf(SuperResModelCatalog.ID_STYLE_CANDY),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.DISABLED_BY_CONFIG, style.action)

        val inpaint = SuperResAvailability.decideInpainting(
            config = SuperResConfig(enabled = true, enableInpainting = false),
            installedModelIds = setOf(SuperResModelCatalog.ID_INPAINT_LAMA),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.DISABLED_BY_CONFIG, inpaint.action)
    }

    @Test
    fun styleAndInpaint_requireInstallWhenEnabledButMissing() {
        val style = SuperResAvailability.decideStyleTransfer(
            config = SuperResConfig(enableStyleTransfer = true),
            installedModelIds = emptySet(),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.INSTALL_MODEL_REQUIRED, style.action)

        val inpaint = SuperResAvailability.decideInpainting(
            config = SuperResConfig(enableInpainting = true),
            installedModelIds = emptySet(),
            runtimeAvailable = true,
            inputWidth = 100,
            inputHeight = 100,
        )
        assertEquals(SuperResAction.INSTALL_MODEL_REQUIRED, inpaint.action)
    }

    // ---- 工作分辨率换算 ----

    @Test
    fun workingSizeFor_preservesAspectRatioAndNeverGoesBelowOnePixel() {
        assertEquals(8000 to 6000, SuperResAvailability.workingSizeFor(8000, 6000, 0L))
        val (w, h) = SuperResAvailability.workingSizeFor(8000, 6000, 12_000_000L)
        assertTrue(w <= 8000 && h <= 6000)
        assertTrue(w.toLong() * h <= 12_010_000L)
        assertEquals(4.0 / 3.0, w.toDouble() / h.toDouble(), 0.02)

        val (tinyW, tinyH) = SuperResAvailability.workingSizeFor(10, 10, 1L)
        assertEquals(1, tinyW)
        assertEquals(1, tinyH)
    }

    @Test
    fun workingSizeFor_invalidInputReturnsZero() {
        assertEquals(0 to 0, SuperResAvailability.workingSizeFor(0, 100, 1_000_000L))
        assertEquals(0 to 0, SuperResAvailability.workingSizeFor(100, -1, 1_000_000L))
    }

    // ---- 模型目录选择 ----

    @Test
    fun catalogSelection_requiresExactScaleMatchForSuperResolution() {
        assertNull(SuperResModelCatalog.selectInstalled(SuperResModelKind.SUPER_RESOLUTION, 4, setOf(x2)))
        assertEquals(x4, SuperResModelCatalog.selectInstalled(SuperResModelKind.SUPER_RESOLUTION, 4, setOf(x4))?.id)
        assertNull("不支持 3x", SuperResModelCatalog.superResModel(3))
        assertEquals(x2, SuperResModelCatalog.defaultFor(SuperResModelKind.SUPER_RESOLUTION)?.id)
        assertNotNull(SuperResModelCatalog.byId(SuperResModelCatalog.ID_STYLE_CANDY))
        assertNull(SuperResModelCatalog.byId(null))
        assertNull(SuperResModelCatalog.byId("  "))
    }

    @Test
    fun catalogModels_declareLicenseAndSource() {
        assertTrue(SuperResModelCatalog.models.isNotEmpty())
        SuperResModelCatalog.models.forEach { m ->
            assertTrue("来源必须可溯源: ${m.id}", m.sourceUrl.startsWith("https://github.com/"))
            assertTrue("许可必须标注: ${m.id}", m.license.isNotBlank())
            assertTrue("star 必须标注: ${m.id}", m.starNote.isNotBlank())
            assertTrue(m.scale in 1..4)
            assertTrue(m.fileName.endsWith(".onnx"))
        }
        // 许可不明确的档位不得给直链（只参考思路，不分发权重）。
        val unclear = SuperResModelCatalog.models.filter { it.license.contains("不明确") }
        assertTrue("必须有许可说明不明确的档位（风格迁移）", unclear.isNotEmpty())
        assertTrue("许可不明确者不得提供直链", unclear.none { it.directFileUrl.isNotBlank() })
    }
}
