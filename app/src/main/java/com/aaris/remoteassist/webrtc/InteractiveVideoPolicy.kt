package com.aaris.remoteassist.webrtc

import com.aaris.remoteassist.capture.CaptureTier

/**
 * Keep cadence-first behavior from the first frame even when a struggling
 * codec has not yet produced enough samples to prove passive motion. The
 * source envelope is owned by MotionVideoBudget; native WebRTC can scale
 * DOWN within that envelope on immediate overuse. Never force resolution
 * preservation for the interactive remote-control stream.
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
        ): InteractiveVideoPolicy {
            return InteractiveVideoPolicy(preserveResolution = false, motionPriority = true)
        }
    }
}
