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
    }

    @Test
    @Config(sdk = [29])
    fun surfaceEncoderIngressIsCappedAtConfiguredRealtimeFps() {
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
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            }

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        assertEquals(
            60.0,
            format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER).toDouble(),
            0.0
        )
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

        AarisRealtimeVideoEncoderFactory.applyRealtimeHints(format, 0)

        assertFalse(format.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(format.containsKey(MediaFormat.KEY_LATENCY))
    }
}
