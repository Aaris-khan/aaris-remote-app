package com.aaris.remoteassist.webrtc

import android.content.Context
import org.webrtc.AarisLowLatencyVideoDecoderFactory
import org.webrtc.AarisRealtimeVideoEncoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

object WebRtcRuntime {
    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var eglBaseInternal: EglBase? = null

    @Volatile
    private var factoryInternal: PeerConnectionFactory? = null
    @Volatile private var hardwareEncoders: Set<String> = emptySet()
    @Volatile private var hardwareDecoders: Set<String> = emptySet()

    fun preferredScreenCodec(context: Context, sender: Boolean): String {
        initialize(context)
        return ScreenCodecPolicy.preferred(if (sender) hardwareEncoders else hardwareDecoders)
    }

    fun hardwareScreenEncoders(context: Context): Set<String> {
        initialize(context)
        return hardwareEncoders
    }

    fun hardwareScreenDecoders(context: Context): Set<String> {
        initialize(context)
        return hardwareDecoders
    }

    fun initialize(context: Context) {
        if (initialized) return

        synchronized(lock) {
            if (initialized) return

            val appContext = context.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    /*
                     * Enable native timestamp-based smoothing with a 20 ms
                     * floor and a 180 ms remote-control freshness ceiling.
                     * Packet repair and timed decode stay inside libwebrtc, but
                     * the receiver may not trade half a second of stale glass
                     * for call-style smoothness before our governors can react.
                     */
                    .setFieldTrials(RealtimeMediaPolicy.FIELD_TRIALS)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val eglBase = EglBase.create()
            /*
             * Prefer Android's hardware codec path for screen sharing, but do
             * not make hardware success a correctness requirement.
             *
             * AarisRealtimeVideoEncoderFactory preserves WebRTC's hardware-first
             * encoder selection, vendor bitrate workarounds and software fallback,
             * while marking selected H264/VP8 MediaCodec sessions as realtime and
             * requesting one-frame encoder latency. AarisLowLatencyVideoDecoderFactory
             * also gives Android an exact-geometry decoder operating-rate target
             * only when the selected hardware codec publishes measured/guaranteed
             * throughput, and enables official low-latency mode only when the codec
             * advertises FEATURE_LowLatency. Missing OEM metadata keeps stock behavior.
             *
             * The same EGL context is shared by capture, codecs and rendering
             * so texture-backed frames stay on the GPU path without adding a
             * decoded-frame queue or CPU copy.
             *
             * Enable WebRTC's own hardware-gated constrained-high H.264 path.
             * The pinned factory publishes it only for its compatibility-tested
             * encoder set, and SDP selects it only when the remote decoder also
             * advertises the same profile. Baseline H.264 and VP8 remain in the
             * preference list, so unsupported phones and browsers keep their
             * existing path rather than being forced onto High Profile.
             *
             * Intel VP8 acceleration is harmless on ARM and useful for
             * x86/ChromeOS-class devices.
             */
            val encoderFactory =
                AarisRealtimeVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            val decoderFactory =
                AarisLowLatencyVideoDecoderFactory(
                    eglBase.eglBaseContext
                )
            hardwareEncoders = runCatching { HardwareVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                .supportedCodecs.map { it.name.uppercase() }.toSet() }.getOrDefault(emptySet())
            hardwareDecoders = runCatching { HardwareVideoDecoderFactory(eglBase.eglBaseContext)
                .supportedCodecs.map { it.name.uppercase() }.toSet() }.getOrDefault(emptySet())

            val peerFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            eglBaseInternal = eglBase
            factoryInternal = peerFactory
            initialized = true
        }
    }

    fun eglBase(context: Context): EglBase {
        initialize(context)
        return checkNotNull(eglBaseInternal)
    }

    fun factory(context: Context): PeerConnectionFactory {
        initialize(context)
        return checkNotNull(factoryInternal)
    }
}

