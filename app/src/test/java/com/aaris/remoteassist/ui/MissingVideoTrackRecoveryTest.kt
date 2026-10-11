package com.aaris.remoteassist.ui

import android.app.Activity
import android.os.Looper
import com.aaris.remoteassist.webrtc.ControllerWebRtcSession
import com.aaris.remoteassist.webrtc.RemoteGeometry
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MissingVideoTrackRecoveryTest {
    private fun field(view: InlineRemoteControllerView, name: String) =
        InlineRemoteControllerView::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun helloWithoutTrackOrInboundRtpStillStartsBoundedRecovery() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = InlineRemoteControllerView(activity.get(), "missing-video-test") {}
        // Exercise the actual listener/watchdog without loading native EGL or
        // a peer; this is precisely the missing-video-callback startup case.
        field(view, "attached").setBoolean(view, true)
        val listener = field(view, "listener").get(view) as ControllerWebRtcSession.Listener
        val main = shadowOf(Looper.getMainLooper())
        try {
            listener.onLive(RemoteGeometry(1, 320, 640))
            main.idleFor(Duration.ofMillis(3_000))
            // A replayed HELLO must not postpone the first-frame deadline.
            listener.onLive(RemoteGeometry(1, 320, 640))
            main.idleFor(Duration.ofMillis(500))
            assertEquals(1, field(view, "mediaRecoveryAttempts").getInt(view))
            main.idleFor(Duration.ofMillis(4_000))
            assertEquals(2, field(view, "mediaRecoveryAttempts").getInt(view))
            main.idleFor(Duration.ofMillis(20_000))
            assertEquals(2, field(view, "mediaRecoveryAttempts").getInt(view))
        } finally {
            view.hide()
            activity.pause().stop().destroy()
        }
    }
}
