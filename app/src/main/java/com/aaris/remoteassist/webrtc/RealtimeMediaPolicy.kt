package com.aaris.remoteassist.webrtc

import android.os.Process

/**
 * Cross-layer realtime policy for remote-control video.
 *
 * The product is latency-sensitive interactive streaming, not buffered media:
 * keep WebRTC's receiver in its low-latency playout path while leaving a small
 * jitter budget, and temporarily favor capture/render scheduling only while the
 * controller is actively manipulating the remote phone.
 */
internal object RealtimeMediaPolicy {
    const val PLAYOUT_DELAY_MIN_MS = 0
    const val PLAYOUT_DELAY_MAX_MS = 100

    const val FIELD_TRIALS =
        "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:100/"

    fun threadPriority(interactionActive: Boolean): Int =
        if (interactionActive) {
            Process.THREAD_PRIORITY_DISPLAY
        } else {
            Process.THREAD_PRIORITY_DEFAULT
        }
}
