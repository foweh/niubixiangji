package com.hinnka.mycamera.superres

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置编解码测试：往返一致、非法值被忽略、clamp 后落盘、畸形文本不崩溃。
 *
 * 对应 PATTERN_SPEC 第 5 节「开放度配置对象模板」+ 第 7 节「配置文件读取路径可声明」。
 */
class SuperResConfigCodecTest {

    // ---- 正常 ----

    @Test
    fun encodeThenDecode_roundTripsExactly() {
        val config = SuperResConfig.clamp(
            SuperResConfig(
                enabled = false,
                scale = 4,
                tileSize = 512,
                tileOverlap = 64,
                strength = 0.65f,
                qualityTier = SuperResQualityTier.QUALITY,
                accelMode = SuperResAccelMode.NNAPI,
                threads = 4,
                enableStyleTransfer = true,
                styleTransferStrength = 0.35f,
                styleModelId = SuperResModelCatalog.ID_STYLE_CANDY,
                enableInpainting = true,
                inpaintFeatherPx = 24,
                inpaintMaskThreshold = 0.7f,
                maxWorkingPixels = 8_000_000L,
                memoryBudgetBytes = 256L * 1024 * 1024,
                maxTilesPerChunk = 6,
                keepOutputAlpha = false,
                degradeInsteadOfFail = false,
                jpegQuality = 82,
            )
        )
        val decoded = SuperResConfigCodec.decode(SuperResConfigCodec.encode(config))
        assertEquals(config, decoded.config)
        assertTrue("往返不该产生任何忽略/修正", decoded.ignoredKeys.isEmpty())
        assertTrue(decoded.correctedFields.isEmpty())
        assertFalse(decoded.hadCorrections)
        assertEquals(config, SuperResConfigCodec.roundTrip(config))
    }

    @Test
    fun encode_writesAllOpenParameters() {
        val map = SuperResConfigCodec.encode(SuperResConfig())
        listOf(
            SuperResConfigCodec.KEY_ENABLED,
            SuperResConfigCodec.KEY_SCALE,
            SuperResConfigCodec.KEY_TILE_SIZE,
            SuperResConfigCodec.KEY_TILE_OVERLAP,
            SuperResConfigCodec.KEY_STRENGTH,
            SuperResConfigCodec.KEY_QUALITY_TIER,
            SuperResConfigCodec.KEY_ACCEL_MODE,
            SuperResConfigCodec.KEY_THREADS,
            SuperResConfigCodec.KEY_STYLE_ENABLED,
            SuperResConfigCodec.KEY_STYLE_STRENGTH,
            SuperResConfigCodec.KEY_STYLE_MODEL_ID,
            SuperResConfigCodec.KEY_INPAINT_ENABLED,
            SuperResConfigCodec.KEY_INPAINT_FEATHER,
            SuperResConfigCodec.KEY_INPAINT_THRESHOLD,
            SuperResConfigCodec.KEY_MAX_WORKING_PIXELS,
            SuperResConfigCodec.KEY_MEMORY_BUDGET,
            SuperResConfigCodec.KEY_TILES_PER_CHUNK,
            SuperResConfigCodec.KEY_KEEP_ALPHA,
            SuperResConfigCodec.KEY_DEGRADE,
            SuperResConfigCodec.KEY_JPEG_QUALITY,
        ).forEach { key -> assertTrue("缺字段: $key", map.containsKey(key)) }
    }

    // ---- 边界 ----

    @Test
    fun decode_emptyMap_returnsDefaults() {
        val decoded = SuperResConfigCodec.decode(emptyMap())
        assertEquals(SuperResConfig(), decoded.config)
        assertTrue(decoded.hadCorrections.not())
    }

    @Test
    fun decode_outOfRangeNumbers_areClampedAndReported() {
        val decoded = SuperResConfigCodec.decode(
            mapOf(
                SuperResConfigCodec.KEY_SCALE to "99",
                SuperResConfigCodec.KEY_TILE_SIZE to "1",
                SuperResConfigCodec.KEY_TILE_OVERLAP to "9999",
                SuperResConfigCodec.KEY_THREADS to "0",
                SuperResConfigCodec.KEY_JPEG_QUALITY to "1000",
            )
        )
        assertEquals(SuperResConfig.SCALE_MAX, decoded.config.scale)
        assertEquals(SuperResConfig.TILE_MIN, decoded.config.tileSize)
        assertEquals(decoded.config.tileSize / 2, decoded.config.tileOverlap)
        assertEquals(SuperResConfig.THREADS_MIN, decoded.config.threads)
        assertEquals(SuperResConfig.JPEG_Q_MAX, decoded.config.jpegQuality)
        assertTrue(decoded.correctedFields.isNotEmpty())
        assertTrue(decoded.hadCorrections)
    }

    @Test
    fun decode_enumNamesAreCaseInsensitive_andBlankModelIdBecomesNull() {
        val decoded = SuperResConfigCodec.decode(
            mapOf(
                SuperResConfigCodec.KEY_QUALITY_TIER to "quality",
                SuperResConfigCodec.KEY_ACCEL_MODE to "nnapi",
                SuperResConfigCodec.KEY_STYLE_MODEL_ID to "   ",
            )
        )
        assertEquals(SuperResQualityTier.QUALITY, decoded.config.qualityTier)
        assertEquals(SuperResAccelMode.NNAPI, decoded.config.accelMode)
        assertNull(decoded.config.styleModelId)
    }

    // ---- 异常 ----

    @Test
    fun decode_malformedValuesAreIgnoredAndDefaulted() {
        val decoded = SuperResConfigCodec.decode(
            mapOf(
                SuperResConfigCodec.KEY_SCALE to "two",
                SuperResConfigCodec.KEY_STRENGTH to "NaN",
                SuperResConfigCodec.KEY_MAX_WORKING_PIXELS to "abc",
                SuperResConfigCodec.KEY_QUALITY_TIER to "HYPER",
                SuperResConfigCodec.KEY_ACCEL_MODE to "TPU",
                SuperResConfigCodec.KEY_ENABLED to "maybe",
            )
        )
        val d = SuperResConfig()
        assertEquals(d.scale, decoded.config.scale)
        assertEquals(1f, decoded.config.strength, 0f)
        assertEquals(d.maxWorkingPixels, decoded.config.maxWorkingPixels)
        assertEquals(d.qualityTier, decoded.config.qualityTier)
        assertEquals(d.accelMode, decoded.config.accelMode)
        assertEquals(d.enabled, decoded.config.enabled)
        assertTrue(decoded.ignoredKeys.containsAll(listOf(
            SuperResConfigCodec.KEY_SCALE,
            SuperResConfigCodec.KEY_STRENGTH,
            SuperResConfigCodec.KEY_MAX_WORKING_PIXELS,
            SuperResConfigCodec.KEY_QUALITY_TIER,
            SuperResConfigCodec.KEY_ACCEL_MODE,
            SuperResConfigCodec.KEY_ENABLED,
        )))
    }

    @Test
    fun decode_booleanVariantsAreAccepted() {
        listOf("true", "1", "yes", "TRUE").forEach { raw ->
            val decoded = SuperResConfigCodec.decode(mapOf(SuperResConfigCodec.KEY_ENABLED to raw))
            assertTrue("应识别为 true: $raw", decoded.config.enabled)
            assertTrue(decoded.ignoredKeys.isEmpty())
        }
        listOf("false", "0", "no").forEach { raw ->
            val decoded = SuperResConfigCodec.decode(mapOf(SuperResConfigCodec.KEY_ENABLED to raw))
            assertFalse("应识别为 false: $raw", decoded.config.enabled)
        }
    }

    @Test
    fun textCodec_skipsMalformedLinesAndKeepsValidOnes() {
        val text = "scale=4\ngarbage-line\ntileSize=128\n=emptykey\nthreads=3\n"
        val map = SuperResConfigStore.decodeToMap(text)
        assertEquals(3, map.size)
        assertEquals("4", map[SuperResConfigCodec.KEY_SCALE])
        assertEquals("128", map[SuperResConfigCodec.KEY_TILE_SIZE])
        assertEquals("3", map[SuperResConfigCodec.KEY_THREADS])
        assertTrue(SuperResConfigStore.decodeToMap(null).isEmpty())
        assertTrue(SuperResConfigStore.decodeToMap("").isEmpty())
    }

    @Test
    fun textCodec_isStableForPersistenceRoundTrip() {
        val config = SuperResConfig.clamp(SuperResConfig(scale = 4, qualityTier = SuperResQualityTier.QUALITY))
        val text = SuperResConfigStore.encodeToString(config)
        val decoded = SuperResConfigCodec.decode(SuperResConfigStore.decodeToMap(text))
        assertEquals(config, decoded.config)
    }

    @Test
    fun textCodec_equalsInsideValuesDoesNotBreakParsing() {
        // 传入畸形文本（值里带 = 号）时只能按第一个 = 切分，不得抛异常。
        val map = SuperResConfigStore.decodeToMap("styleModelId=style=a=b\nid=\n")
        assertEquals("style=a=b", map[SuperResConfigCodec.KEY_STYLE_MODEL_ID])
        assertEquals("", map["id"])
    }
}
