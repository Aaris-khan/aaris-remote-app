package org.webrtc

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AarisRealtimeVideoEncoderFactoryTest {
    @Test
    fun encoderConfigurationGetsRealtimeSchedulingHints() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280)

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(1, format.getInteger(MediaFormat.KEY_LATENCY))
        assertFalse(format.containsKey(MediaFormat.KEY_INTRA_REFRESH_PERIOD))
    }

    @Test
    fun cyclicIntraRefreshIsCapabilityGatedAndH264Only() {
        assertEquals(
            60,
            AarisRealtimeVideoEncoderFactory.intraRefreshPeriodFrames(
                VideoCodecMimeType.H264,
                true
            )
        )
        assertEquals(
            0,
            AarisRealtimeVideoEncoderFactory.intraRefreshPeriodFrames(
                VideoCodecMimeType.H264,
                false
            )
        )
        assertEquals(
            0,
            AarisRealtimeVideoEncoderFactory.intraRefreshPeriodFrames(
                VideoCodecMimeType.VP8,
                true
            )
        )
    }

    @Test
    fun supportedH264EncoderGetsBoundedCyclicIntraRefresh() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
            60
        )

        assertEquals(
            60,
            format.getInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD)
        )
        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(1, format.getInteger(MediaFormat.KEY_LATENCY))
    }

    @Test
    @Config(sdk = [29])
    fun highCadenceSurfaceInputNeverDropsBehindWebRtcOutputBookkeeping() {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_VP8)) {
            for (fps in listOf(45, 60)) {
                for (floatRate in listOf(false, true)) {
                    val format = MediaFormat.createVideoFormat(mime, 720, 1280).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                        // Stock HardwareVideoEncoder writes a float. Cover an
                        // OEM/integer configuration too: neither may hide drops.
                        if (floatRate) setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
                        else setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    }

                    AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
                        format,
                        MediaCodec.CONFIGURE_FLAG_ENCODE
                    )

                    assertFalse("$mime @ $fps, float=$floatRate", format.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
                    assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_LATENCY))
                }
            }
        }
    }

    @Test
    @Config(sdk = [29])
    fun repeatedSurfaceSamplesCannotFillWebRtcQueueWithOrphanedBuilders() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setFloat(MediaFormat.KEY_FRAME_RATE, 60f)
        }
        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(format, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Model the pinned SDK contract: encode() reserves a FIFO builder,
        // MediaCodec output consumes one, and queue size >2 rejects new input.
        // KEY_MAX_FPS_TO_ENCODER can privately discard a Surface sample without
        // consuming its builder. Integer-microsecond presentation timestamps
        // (as emitted by WebRTC) expose this even at the requested frame rate.
        val minCodecGapUs = if (format.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER)) {
            1_000_000.0 / format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER)
        } else 0.0
        var pendingBuilders = 0
        var outputs = 0
        var lastOutputPtsUs = -1_000_000L
        repeat(120) { sample ->
            if (pendingBuilders <= 2) {
                pendingBuilders++
                val ptsUs = sample * (1_000_000L / 60)
                if (ptsUs - lastOutputPtsUs >= minCodecGapUs) {
                    lastOutputPtsUs = ptsUs
                    pendingBuilders--
                    outputs++
                }
            }
        }
        assertEquals("Every admitted sample must remain accounted for", 120, outputs)
        assertEquals(0, pendingBuilders)
    }

    @Test
    @Config(sdk = [29])
    fun legacyThirtyFpsSurfaceConfigDoesNotPinHighCadenceStream() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                // WebRTC's FramerateBitrateAdjuster uses a fixed 30 fps codec
                // configuration on legacy Exynos H.264 while compensating
                // bitrate for a potentially higher real stream cadence.
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            }

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        assertFalse(format.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
    }

    @Test
    @Config(sdk = [29])
    fun bufferInputEncoderDoesNotReceiveSurfaceIngressCap() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            }

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        assertFalse(format.containsKey(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
    }

    @Test
    fun decoderConfigurationIsNeverModified() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280)

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            0,
            60
        )

        assertFalse(format.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(format.containsKey(MediaFormat.KEY_LATENCY))
        assertFalse(format.containsKey(MediaFormat.KEY_INTRA_REFRESH_PERIOD))
    }
}
