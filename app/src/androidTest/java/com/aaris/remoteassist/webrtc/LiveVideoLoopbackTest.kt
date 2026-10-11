package com.aaris.remoteassist.webrtc

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aaris.remoteassist.control.ControlPacket
import com.aaris.remoteassist.control.ControlProtocol
import com.aaris.remoteassist.ui.InlineRemoteControllerView
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*

/**
 * Real native RTP/codec/EGL/display test on Android, with two local peers.
 * Synthetic I420 source pixels replace MediaProjection; this does not claim
 * physical two-phone, OEM hardware-codec or internet/TURN validation.
 */
@RunWith(AndroidJUnit4::class)
class LiveVideoLoopbackTest {
    @Test fun vp8PixelsAndControlChangesReachTheActualControllerDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val factory = WebRtcRuntime.factory(context)
        val source = factory.createVideoSource(false)
        val localTrack = factory.createVideoTrack("live-loopback-screen", source)
        val sourceFrames = AtomicInteger()
        val decodedFrames = AtomicInteger()
        val taps = AtomicInteger()
        val green = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val remoteControl = AtomicReference<DataChannel?>()
        val remoteTrack = AtomicReference<VideoTrack?>()
        val executor = Executors.newSingleThreadScheduledExecutor()
        var host: PeerConnection? = null
        var controller: PeerConnection? = null
        var hostControl: DataChannel? = null
        var viewer: InlineRemoteControllerView? = null
        val decodedProbe = VideoSink { decodedFrames.incrementAndGet() }
        val hostInbox = CandidateInbox()
        val controllerInbox = CandidateInbox()
        val scenario = ActivityScenario.launch(LiveVideoTestActivity::class.java)

        try {
            lateinit var viewerListener: ControllerWebRtcSession.Listener
            scenario.onActivity { activity ->
                val view = InlineRemoteControllerView(activity, "native-loopback-test") {}
                viewer = view
                view.show()
                viewerListener = field(view, "listener") as ControllerWebRtcSession.Listener
                viewerListener.onLive(RemoteGeometry(1, WIDTH, HEIGHT))
            }

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            host = checkNotNull(factory.createPeerConnection(config, object : NoopPeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate) = controllerInbox.offer(candidate)
            }))
            controller = checkNotNull(factory.createPeerConnection(config, object : NoopPeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate) = hostInbox.offer(candidate)
                override fun onDataChannel(channel: DataChannel) {
                    remoteControl.set(channel)
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track() as? VideoTrack ?: return
                    if (remoteTrack.compareAndSet(null, track)) {
                        track.addSink(decodedProbe)
                        viewerListener.onRemoteVideoTrack(track)
                    }
                }
            }))
            hostInbox.peer = host
            controllerInbox.peer = controller

            val sender = host.addTransceiver(localTrack, RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("live-screen")))
            val receiver = controller.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY, emptyList()))
            val vp8Send = ScreenCodecPolicy.order(factory.getRtpSenderCapabilities(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs, "VP8", exclusive = true)
            val vp8Receive = ScreenCodecPolicy.order(factory.getRtpReceiverCapabilities(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs, "VP8", exclusive = true)
            assertTrue("The real native VP8 fallback must be available", vp8Send.isNotEmpty() && vp8Receive.isNotEmpty())
            sender.setCodecPreferences(vp8Send).throwError()
            receiver.setCodecPreferences(vp8Receive).throwError()

            hostControl = host.createDataChannel("control-v1", DataChannel.Init())
            hostControl.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val data = buffer.data.slice()
                    val bytes = ByteArray(data.remaining()).also(data::get)
                    val packet = ControlProtocol.decode(bytes)
                    if (packet is ControlPacket.Tap && packet.leaseSecret == 7L) {
                        green.set(taps.incrementAndGet() % 2 == 1)
                    }
                }
            })

            val offer = createSdp(host, true)
            setSdp(host, offer, true)
            setSdp(controller, offer, false)
            controllerInbox.remoteReady()
            val answer = createSdp(controller, false)
            setSdp(controller, answer, true)
            setSdp(host, answer, false)
            hostInbox.remoteReady()

            source.adaptOutputFormat(WIDTH, HEIGHT, 20)
            source.capturerObserver.onCapturerStarted(true)
            executor.scheduleAtFixedRate({
                try {
                    val buffer = JavaI420Buffer.allocate(WIDTH, HEIGHT)
                    val color = green.get()
                    fill(buffer.dataY, if (color) 145 else 82)
                    fill(buffer.dataU, if (color) 54 else 90)
                    fill(buffer.dataV, if (color) 34 else 240)
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    try {
                        source.capturerObserver.onFrameCaptured(frame)
                        sourceFrames.incrementAndGet()
                    } finally {
                        frame.release()
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }, 0, 50, TimeUnit.MILLISECONDS)

            waitFor("real control DataChannel OPEN") {
                remoteControl.get()?.state() == DataChannel.State.OPEN
            }
            val redPixel = waitForDisplayColor(scenario, checkNotNull(viewer), false)
            assertTrue(checkNotNull(remoteControl.get()).send(DataChannel.Buffer(ByteBuffer.wrap(
                ControlProtocol.encode(ControlPacket.Tap(7L, 1, 1L, .5f, .5f))), true)))
            waitFor("remote tap executed") { taps.get() == 1 }
            val greenPixel = waitForDisplayColor(scenario, checkNotNull(viewer), true)

            // Exercise the production EGL rebuild while the same native RTP
            // receiver and authoritative DataChannel remain alive.
            scenario.onActivity {
                InlineRemoteControllerView::class.java.getDeclaredMethod("rebuildRenderer")
                    .apply { isAccessible = true }.invoke(viewer)
            }
            waitForDisplayColor(scenario, checkNotNull(viewer), true)
            assertTrue(checkNotNull(remoteControl.get()).send(DataChannel.Buffer(ByteBuffer.wrap(
                ControlProtocol.encode(ControlPacket.Tap(7L, 1, 2L, .5f, .5f))), true)))
            waitFor("control survives renderer rebuild") { taps.get() == 2 }
            val recoveredPixel = waitForDisplayColor(scenario, checkNotNull(viewer), false)
            failure.get()?.let { throw AssertionError("Source frame generation failed", it) }
            assertTrue("Native decoder must produce frames", decodedFrames.get() > 0)
            Log.i("AarisLiveVideo", "PASS native VP8: source=${sourceFrames.get()} decoded=${decodedFrames.get()} " +
                "taps=${taps.get()} red=${Integer.toHexString(redPixel)} green=${Integer.toHexString(greenPixel)} " +
                "afterEglRebuild=${Integer.toHexString(recoveredPixel)}")
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            scenario.onActivity { viewer?.hide() }
            remoteTrack.get()?.removeSink(decodedProbe)
            remoteControl.get()?.let { it.close(); it.dispose() }
            hostControl?.let { it.unregisterObserver(); it.close(); it.dispose() }
            controller?.let { it.close(); it.dispose() }
            host?.let { it.close(); it.dispose() }
            source.capturerObserver.onCapturerStopped()
            localTrack.dispose()
            source.dispose()
            scenario.close()
        }
    }

    private fun waitForDisplayColor(scenario: ActivityScenario<LiveVideoTestActivity>,
        viewer: InlineRemoteControllerView, green: Boolean): Int {
        var lastPixel = Color.BLACK
        waitFor("visible ${if (green) "green" else "red"} video pixels, last=${Integer.toHexString(lastPixel)}") {
            val bounds = Rect()
            scenario.onActivity {
                val surface = field(viewer, "surfaceView") as SurfaceView
                val location = IntArray(2)
                surface.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0] + surface.width, location[1] + surface.height)
            }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: return@waitFor false
            try {
                if (bounds.isEmpty) return@waitFor false
                lastPixel = screenshot.getPixel(bounds.centerX().coerceIn(0, screenshot.width - 1),
                    bounds.centerY().coerceIn(0, screenshot.height - 1))
                if (green) Color.green(lastPixel) > 150 && Color.red(lastPixel) < 100
                else Color.red(lastPixel) > 150 && Color.green(lastPixel) < 100
            } finally {
                screenshot.recycle()
            }
        }
        return lastPixel
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun field(viewer: InlineRemoteControllerView, name: String): Any =
        InlineRemoteControllerView::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(viewer)

    private fun fill(buffer: ByteBuffer, value: Int) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(value.toByte())
    }

    private fun createSdp(peer: PeerConnection, offer: Boolean): SessionDescription {
        val future = CompletableFuture<SessionDescription>()
        val observer = object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription?) { future.complete(checkNotNull(description)) }
            override fun onCreateFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
        return future.get(20, TimeUnit.SECONDS)
    }

    private fun setSdp(peer: PeerConnection, description: SessionDescription, local: Boolean) {
        val future = CompletableFuture<Unit>()
        val observer = object : SdpObserverAdapter() {
            override fun onSetSuccess() { future.complete(Unit) }
            override fun onSetFailure(error: String?) { future.completeExceptionally(IllegalStateException(error)) }
        }
        if (local) peer.setLocalDescription(observer, description) else peer.setRemoteDescription(observer, description)
        future.get(20, TimeUnit.SECONDS)
    }

    private class CandidateInbox {
        lateinit var peer: PeerConnection
        private val pending = mutableListOf<IceCandidate>()
        private var ready = false
        @Synchronized fun offer(candidate: IceCandidate) {
            if (ready) check(peer.addIceCandidate(candidate)) else pending.add(candidate)
        }
        @Synchronized fun remoteReady() {
            ready = true
            pending.forEach { check(peer.addIceCandidate(it)) }
            pending.clear()
        }
    }

    private open class NoopPeerObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    companion object { private const val WIDTH = 320; private const val HEIGHT = 640 }
}
