package com.hinnka.mycamera.videomodes

import com.hinnka.mycamera.video.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14 编码控制：码率 1~200Mbps clamp、编码器/位深按能力回退、音频编码参数边界。 */
class VideoEncodePolicyTest {

    @Test
    fun bitrateIsClampedToOpenRange() {
        assertEquals(VideoModesRanges.BITRATE_MBPS_MIN, VideoEncodePolicy.clampBitrateMbps(0))
        assertEquals(VideoModesRanges.BITRATE_MBPS_MAX, VideoEncodePolicy.clampBitrateMbps(201))
        assertEquals(VideoModesRanges.BITRATE_MBPS_MAX, VideoEncodePolicy.clampBitrateMbps(500))
        assertEquals(1, VideoEncodePolicy.clampBitrateMbps(1))
    }

    @Test
    fun sampleRateSnapsToNearestSupportedValue() {
        assertEquals(48_000, VideoEncodePolicy.snapSampleRate(47_000))
        assertEquals(44_100, VideoEncodePolicy.snapSampleRate(44_100))
        assertEquals(VideoModesRanges.DEFAULT_AUDIO_SAMPLE_RATE_HZ, VideoEncodePolicy.snapSampleRate(48_000))
    }

    @Test
    fun tenBitRequiresExplicitCodecProfile() {
        val avcOnly = VideoEncodeCapability(avcSupported = true, hevcSupported = false)
        val full = VideoEncodeCapability(
            avcSupported = true,
            hevcSupported = true,
            tenBitHevcSupported = true,
        )
        assertTrue(avcOnly.supportsBitDepth(VideoCodec.H264, 8))
        assertFalse(avcOnly.supportsBitDepth(VideoCodec.H265, 10))
        assertTrue(full.supportsBitDepth(VideoCodec.H265, 10))
        assertFalse(full.supportsBitDepth(VideoCodec.H264, 10))
    }

    @Test
    fun resolveFallsBackWhenHevcUnavailable() {
        val resolved = VideoEncodePolicy.resolve(
            VideoEncodeConfig(codec = VideoCodec.H265, bitDepth = 10, bitrateMbps = 60),
            VideoEncodeCapability(avcSupported = true, hevcSupported = false),
        )
        assertEquals(VideoCodec.H264, resolved.codec)
        assertEquals(8, resolved.bitDepth)
        assertFalse(resolved.tenBit)
        assertTrue(resolved.corrections.isNotEmpty())
    }

    @Test
    fun resolveKeepsHdrCombinationWhenSupported() {
        val resolved = VideoEncodePolicy.resolve(
            VideoEncodeConfig(codec = VideoCodec.H265, bitDepth = 10, bitrateMbps = 100),
            VideoEncodeCapability(
                avcSupported = true,
                hevcSupported = true,
                tenBitHevcSupported = true,
            ),
        )
        assertEquals(VideoCodec.H265, resolved.codec)
        assertEquals(10, resolved.bitDepth)
        assertEquals(100, resolved.bitrateMbps)
        assertEquals(100_000_000, resolved.bitrateBps)
        assertTrue(resolved.tenBit)
        assertTrue(resolved.corrections.isEmpty())
    }

    @Test
    fun resolveClampsBitrateAndAudioParameters() {
        val resolved = VideoEncodePolicy.resolve(
            VideoEncodeConfig(bitrateMbps = 500, audioChannels = 5, audioSampleRateHz = 47_000),
            VideoEncodeCapability(),
        )
        assertEquals(VideoModesRanges.BITRATE_MBPS_MAX, resolved.bitrateMbps)
        assertEquals(VideoModesRanges.AUDIO_CHANNELS_MAX, resolved.audioChannels)
        assertEquals(48_000, resolved.audioSampleRateHz)
        assertEquals(3, resolved.corrections.size)
    }

    @Test
    fun unresolvedCodecKeepsUserChoiceInsteadOfFailing() {
        val resolved = VideoEncodePolicy.resolve(
            VideoEncodeConfig(codec = VideoCodec.H264),
            VideoEncodeCapability(avcSupported = false, hevcSupported = false),
        )
        assertEquals(VideoCodec.H264, resolved.codec)
        assertTrue(resolved.reason.contains("未上报"))
    }
}
