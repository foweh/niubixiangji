package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4 HDR 视频：10-bit HEVC / BT.2020 能力门控与 SDR 自动回退（不含糊、不硬失败）。 */
class HdrVideoGateTest {

    private val fullCapability = HdrVideoCapability(
        hevcSupported = true,
        tenBitHevcSupported = true,
        tenBitAvcSupported = false,
        hdrDynamicRangeSupported = true,
        bt2020Supported = true,
    )

    @Test
    fun disabledKeepsSdrBaseline() {
        val decision = HdrVideoGate.resolve(HdrVideoConfig(enabled = false), fullCapability)
        assertFalse(decision.hdrEnabled)
        assertEquals(8, decision.effectiveBitDepth)
        assertEquals(VideoCodec.H264, decision.effectiveCodec)
        assertFalse(decision.bt2020)
        assertNull(decision.transfer)
        assertFalse(decision.fallbackApplied)
    }

    @Test
    fun fullCapabilityEnablesTenBitHevcBt2020() {
        val decision = HdrVideoGate.resolve(
            HdrVideoConfig(enabled = true),
            fullCapability,
            requestedCodec = VideoCodec.H264,
        )
        assertTrue(decision.hdrEnabled)
        assertEquals(10, decision.effectiveBitDepth)
        assertEquals(VideoCodec.H265, decision.effectiveCodec)
        assertTrue(decision.bt2020)
        assertEquals(HdrTransfer.HLG, decision.transfer)
        assertFalse(decision.fallbackApplied)
    }

    @Test
    fun missingTenBitProfileFallsBackToSdr() {
        val decision = HdrVideoGate.resolve(
            HdrVideoConfig(enabled = true),
            fullCapability.copy(tenBitHevcSupported = false),
        )
        assertFalse(decision.hdrEnabled)
        assertEquals(8, decision.effectiveBitDepth)
        assertEquals(VideoCodec.H264, decision.effectiveCodec)
        assertTrue(decision.fallbackApplied)
        assertTrue(decision.reason.contains("已自动回退"))
    }

    @Test
    fun hdrWithoutDynamicRangeProfileIsReported() {
        val decision = HdrVideoGate.resolve(
            HdrVideoConfig(enabled = true),
            fullCapability.copy(hdrDynamicRangeSupported = false),
        )
        assertFalse(decision.hdrEnabled)
        assertTrue(decision.reason.contains("HDR 动态范围 profile"))
    }

    @Test
    fun fallbackCanBeTurnedOff() {
        val decision = HdrVideoGate.resolve(
            HdrVideoConfig(enabled = true, fallbackToSdr = false),
            fullCapability.copy(bt2020Supported = false),
        )
        assertFalse(decision.hdrEnabled)
        assertTrue(decision.fallbackApplied)
        assertTrue(decision.reason.contains("手动"))
    }

    @Test
    fun requestedCodecIsKeptOnFallback() {
        val decision = HdrVideoGate.resolve(
            HdrVideoConfig(enabled = true),
            HdrVideoCapability(),
            requestedCodec = VideoCodec.H265,
        )
        assertEquals(VideoCodec.H265, decision.effectiveCodec)
        assertEquals(8, decision.effectiveBitDepth)
    }

    @Test
    fun invalidBitDepthIsRejected() {
        assertEquals(10, HdrVideoGate.normalizeBitDepth(10))
        assertEquals(8, HdrVideoGate.normalizeBitDepth(8))
        assertEquals(8, HdrVideoGate.normalizeBitDepth(12))
    }
}
