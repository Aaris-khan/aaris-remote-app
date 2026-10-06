package com.aaris.remoteassist.ui

/** Main-thread acknowledgement gate, driven only by actual EGL presentation. */
internal class PrimaryVideoConfirmation {
    private var confirmed = false
    private var lastAttemptAtMs: Long? = null

    fun reset() {
        confirmed = false
        lastAttemptAtMs = null
    }

    fun onPresented(nowMs: Long, send: () -> Boolean) {
        if (confirmed) return
        if (lastAttemptAtMs?.let { nowMs - it < 500L } == true) return
        lastAttemptAtMs = nowMs
        // A temporarily unavailable control channel must not leave JPEG/delta
        // recovery competing with an already working primary video stream.
        confirmed = send()
    }
}
