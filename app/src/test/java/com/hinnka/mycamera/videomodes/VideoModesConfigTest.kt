package com.hinnka.mycamera.videomodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 聚合配置：默认值即既有行为、持久化 key 往返、脏值回退与统一 clamp 汇报。 */
class VideoModesConfigTest {

    @Test
    fun defaultsAreNeutralAndPersistable() {
        val config = VideoModesConfig()
        val map = config.toMap()
        assertEquals("30", map["video_modes.fps.target_fps"])
        assertEquals("1.0", map["video_modes.eis.crop_factor"])
        assertEquals("10", map["video_modes.loop.buffer_seconds"])
        assertEquals("1440", map["video_modes.snapshot.max_long_edge"])
    }

    @Test
    fun defaultValueClampReportsNothing() {
        val (clamped, corrections) = VideoModesConfig().clampReport()
        assertTrue(corrections.isEmpty())
        assertEquals(VideoModesConfig(), clamped)
    }

    @Test
    fun mapRoundTripKeepsConfiguration() {
        val config = VideoModesConfig(
            fps = FpsModeConfig(targetFps = 120, slowMotionEnabled = true, slowMotionSpeed = 0.5f),
            eis = EisTuningConfig(strength = 0.5f, cropFactor = 1.2f),
            hdr = HdrVideoConfig(enabled = true),
            loop = LoopRecordingConfig(enabled = true, bufferSeconds = 30),
        )
        assertEquals(config, VideoModesConfig.fromMap(config.toMap()))
    }

    @Test
    fun dirtyValuesFallBackOrClampInsteadOfThrowing() {
        val config = VideoModesConfig.fromMap(
            mapOf(
                "video_modes.fps.target_fps" to "abc",
                "video_modes.encode.bitrate_mbps" to "99999",
                "video_modes.audio.gain_percent" to "-100",
                "video_modes.encode.codec" to "NOT_A_CODEC",
                "video_modes.loop.buffer_seconds" to "",
            ),
        )
        assertEquals(VideoModesConfig().fps.targetFps, config.fps.targetFps)
        assertEquals(VideoModesConfig().encode.codec, config.encode.codec)
        assertEquals(VideoModesConfig().loop.bufferSeconds, config.loop.bufferSeconds)

        val clamped = config.clamp()
        assertEquals(VideoModesRanges.BITRATE_MBPS_MAX, clamped.encode.bitrateMbps)
        assertEquals(VideoModesRanges.AUDIO_GAIN_PERCENT_MIN, clamped.audio.gainPercent)
    }

    @Test
    fun clampReportPrefixesEachModule() {
        val (clamped, corrections) = VideoModesConfig(
            fps = FpsModeConfig(targetFps = 1000),
            loop = LoopRecordingConfig(bufferSeconds = 99),
            snapshot = SnapshotDuringRecordingConfig(intervalMs = 1L),
        ).clampReport()
        assertEquals(VideoModesRanges.FPS_MAX, clamped.fps.targetFps)
        assertEquals(VideoModesRanges.LOOP_BUFFER_SECONDS_MAX, clamped.loop.bufferSeconds)
        assertEquals(VideoModesRanges.SNAPSHOT_INTERVAL_MS_MIN, clamped.snapshot.intervalMs)
        assertTrue(corrections.any { it.startsWith("fps.") })
        assertTrue(corrections.any { it.startsWith("loop.") })
        assertTrue(corrections.any { it.startsWith("snapshot.") })
    }

    @Test
    fun everyOpenParameterSurvivesClamp() {
        val clamped = VideoModesConfig(
            fps = FpsModeConfig(targetFps = 1000, slowMotionSpeed = 0.01f),
            timelapse = TimelapseConfig(intervalMs = 1L, totalFrames = 0, outputFps = 9999),
            eis = EisTuningConfig(strength = 9f, cropFactor = 9f, lookaheadFrames = 9999),
            encode = VideoEncodeConfig(bitrateMbps = 9999, bitDepth = 12, audioChannels = 9),
            audio = AudioControlConfig(gainPercent = 9999),
            filter = RealtimeFilterConfig(intensity = 9f),
            lens = MultiLensSwitchConfig(switchSettleMs = 99_999L),
            sr = VideoSrConfig(scale = 99f, frameStride = 0, chunkFrames = 0),
            denoise = VideoDenoiseConfig(strength = 9f, radius = 99),
            night = NightVideoConfig(gainBoostEv = 99f),
            snapshot = SnapshotDuringRecordingConfig(intervalMs = 1L, jpegQuality = 1),
            loop = LoopRecordingConfig(bufferSeconds = 999),
            pip = DualCameraPipConfig(secondaryFps = 9999),
        ).clamp()
        assertTrue(clamped.fps.targetFps <= VideoModesRanges.FPS_MAX)
        assertTrue(clamped.timelapse.intervalMs >= VideoModesRanges.TIMELAPSE_INTERVAL_MS_MIN)
        assertTrue(clamped.eis.cropFactor <= VideoModesRanges.EIS_CROP_FACTOR_MAX)
        assertTrue(clamped.encode.bitrateMbps <= VideoModesRanges.BITRATE_MBPS_MAX)
        assertTrue(clamped.encode.bitDepth <= 10)
        assertTrue(clamped.audio.gainPercent <= VideoModesRanges.AUDIO_GAIN_PERCENT_MAX)
        assertTrue(clamped.filter.intensity <= VideoModesRanges.FILTER_INTENSITY_MAX)
        assertTrue(clamped.lens.switchSettleMs <= VideoModesRanges.LENS_SWITCH_SETTLE_MS_MAX)
        assertTrue(clamped.sr.scale <= VideoModesRanges.SR_SCALE_ABSOLUTE_MAX)
        assertTrue(clamped.denoise.radius <= VideoModesRanges.DENOISE_RADIUS_MAX)
        assertTrue(clamped.night.gainBoostEv <= VideoModesRanges.NIGHT_GAIN_BOOST_EV_MAX)
        assertTrue(clamped.snapshot.jpegQuality >= VideoModesRanges.SNAPSHOT_JPEG_QUALITY_MIN)
        assertTrue(clamped.loop.bufferSeconds <= VideoModesRanges.LOOP_BUFFER_SECONDS_MAX)
        assertTrue(clamped.pip.secondaryFps <= VideoModesRanges.FPS_MAX)
    }
}
