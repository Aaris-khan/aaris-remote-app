package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
class AarisLowLatencyVideoDecoderFactoryTest {
    private fun decoder(
        name: String,
        lowLatency: Boolean = false,
        supportedFeature: String? = null,
        requiredFeature: String? = null
    ): MediaCodecInfo {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 720, 1280).apply {
            if (lowLatency) setFeatureEnabled(CodecCapabilities.FEATURE_LowLatency, true)
            supportedFeature?.let { setFeatureEnabled(it, true) }
            requiredFeature?.let { setFeatureEnabled(it, true) }
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatYUV420Planar))
            .setRequiredFeatures(requiredFeature?.let { arrayOf(it) } ?: emptyArray())
            .build()
        return MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsHardwareAccelerated(true)
            .setCapabilities(capabilities)
            .build()
    }

    @Test
    @Config(sdk = [30])
    fun lowLatencySecureOnlyDecoderCannotReplaceWorkingRegularDecoder() {
        ShadowMediaCodecList.addCodec(decoder("c2.vendor.avc.decoder"))
        ShadowMediaCodecList.addCodec(decoder(
            "c2.vendor.avc.decoder.secure",
            lowLatency = true,
            requiredFeature = CodecCapabilities.FEATURE_SecurePlayback
        ))

        assertEquals("c2.vendor.avc.decoder", AarisLowLatencyVideoDecoderFactory
            .findHardwareDecoder(VideoCodecMimeType.H264)!!.name)
    }

    @Test
    @Config(sdk = [30])
    fun tunneledOnlyDecoderIsRejectedWithoutDependingOnItsName() {
        ShadowMediaCodecList.addCodec(decoder(
            "c2.vendor.avc.fast.decoder",
            lowLatency = true,
            requiredFeature = CodecCapabilities.FEATURE_TunneledPlayback
        ))

        assertNull(AarisLowLatencyVideoDecoderFactory.findHardwareDecoder(VideoCodecMimeType.H264))
    }

    @Test
    @Config(sdk = [30])
    fun compatibleLowLatencyDecoderIsStillPreferred() {
        ShadowMediaCodecList.addCodec(decoder("c2.vendor.avc.decoder"))
        ShadowMediaCodecList.addCodec(decoder("c2.vendor.avc.lowlatency.decoder", lowLatency = true))

        assertEquals("c2.vendor.avc.lowlatency.decoder", AarisLowLatencyVideoDecoderFactory
            .findHardwareDecoder(VideoCodecMimeType.H264)!!.name)
    }

    @Test
    @Config(sdk = [30])
    fun optionalSecureSupportDoesNotExcludeRegularDecoder() {
        ShadowMediaCodecList.addCodec(decoder(
            "c2.vendor.avc.decoder",
            lowLatency = true,
            supportedFeature = CodecCapabilities.FEATURE_SecurePlayback
        ))

        assertEquals("c2.vendor.avc.decoder", AarisLowLatencyVideoDecoderFactory
            .findHardwareDecoder(VideoCodecMimeType.H264)!!.name)
    }

    @Test
    @Config(sdk = [26])
    fun decoderGetsRealtimePriorityBeforeLowLatencyApi() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisLowLatencyVideoDecoderFactory.applyRealtimeHints(
            format,
            0,
            true,
            45
        )

        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(45, format.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertFalse(format.containsKey(MediaFormat.KEY_LOW_LATENCY))
    }

    @Test
    @Config(sdk = [30])
    fun capableDecoderGetsRealtimePriorityAndLowLatencyMode() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisLowLatencyVideoDecoderFactory.applyRealtimeHints(
            format,
            0,
            true,
            45
        )

        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(45, format.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertEquals(1, format.getInteger(MediaFormat.KEY_LOW_LATENCY))
    }

    @Test
    @Config(sdk = [30])
    fun decoderWithoutLowLatencyFeatureStillGetsRealtimePriority() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisLowLatencyVideoDecoderFactory.applyRealtimeHints(
            format,
            0,
            false,
            30
        )

        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(30, format.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertFalse(format.containsKey(MediaFormat.KEY_LOW_LATENCY))
    }

    @Test
    @Config(sdk = [30])
    fun unknownDecoderThroughputDoesNotInventOperatingRate() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisLowLatencyVideoDecoderFactory.applyRealtimeHints(
            format,
            0,
            false,
            0
        )

        assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertFalse(format.containsKey(MediaFormat.KEY_OPERATING_RATE))
        assertFalse(format.containsKey(MediaFormat.KEY_LOW_LATENCY))
    }

    @Test
    fun operatingRateIsBoundedByRemoteScreenProductCeiling() {
        assertEquals(
            60,
            AarisLowLatencyVideoDecoderFactory.boundedOperatingRate(120.0)
        )
        assertEquals(
            58,
            AarisLowLatencyVideoDecoderFactory.boundedOperatingRate(58.9)
        )
        assertEquals(
            15,
            AarisLowLatencyVideoDecoderFactory.boundedOperatingRate(15.0)
        )
        assertEquals(
            0,
            AarisLowLatencyVideoDecoderFactory.boundedOperatingRate(14.9)
        )
        assertEquals(
            0,
            AarisLowLatencyVideoDecoderFactory.boundedOperatingRate(Double.NaN)
        )
    }

    @Test
    @Config(sdk = [30])
    fun encoderConfigurationIsNeverModifiedByDecoderHints() {
        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                720,
                1280
            )

        AarisLowLatencyVideoDecoderFactory.applyRealtimeHints(
            format,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
            true,
            60
        )

        assertFalse(format.containsKey(MediaFormat.KEY_PRIORITY))
        assertFalse(format.containsKey(MediaFormat.KEY_OPERATING_RATE))
        assertFalse(format.containsKey(MediaFormat.KEY_LOW_LATENCY))
    }
}
