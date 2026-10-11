package com.aaris.remoteassist.webrtc

import com.aaris.remoteassist.capture.CaptureTier

/**
 * Use the native WebRTC degradation actuator for the content that is actually
 * on screen, instead of pinning the whole session to one policy.
 *
 * While interaction or passive screen motion is active, freshness remains the
 * priority: libwebrtc may spend spatial detail inside the app-owned source
 * envelope before it drops temporal cadence. Once the screen becomes quiet,
 * preserve resolution so text/forms/icons recover to the clearest sustainable
 * representation instead of remaining unnecessarily blurred by an old
 * MAINTAIN_FRAMERATE decision.
 *
 * MotionVideoBudget and VideoCadenceGovernor still own the app's explicit
 * spatial/cadence envelopes. This policy only selects libwebrtc's native
 * overuse preference inside those bounds; it adds no queue, encoder, or frame
 * copy.
 */
data class InteractiveVideoPolicy(
    val preserveResolution: Boolean,
    val motionPriority: Boolean
) {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun forState(
            tier: CaptureTier,
            interactionActive: Boolean
        ): InteractiveVideoPolicy =
            if (interactionActive) {
                InteractiveVideoPolicy(
                    preserveResolution = false,
                    motionPriority = true
                )
            } else {
                InteractiveVideoPolicy(
                    preserveResolution = true,
                    motionPriority = false
                )
            }
    }
}
