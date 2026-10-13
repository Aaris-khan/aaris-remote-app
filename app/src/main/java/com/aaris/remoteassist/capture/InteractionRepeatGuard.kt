package com.aaris.remoteassist.capture

/**
 * Prevent a causal interaction probe from turning an unchanged SurfaceTexture
 * into a fake fresh RTP frame.
 *
 * SurfaceTextureHelper.forceFrame() deliberately re-emits the last texture when
 * no producer buffer is pending. That is useful for liveness/snapshots, but a
 * post-input probe must not let those stale pixels consume the live video
 * cadence slot immediately before a genuinely new MediaProjection frame.
 *
 * The guard is active only around the interaction forceFrame runnable. Natural
 * frames always pass; an unchanged producer timestamp is suppressed only while
 * that bounded probe is in flight. Idle heartbeat behavior is unchanged.
 */
internal class InteractionRepeatGuard {
    private var lastProducerTimestampNs = Long.MIN_VALUE
    private var interactionProbeActive = false

    fun beginInteractionProbe() {
        interactionProbeActive = true
    }

    fun endInteractionProbe() {
        interactionProbeActive = false
    }

    fun shouldForwardToLiveVideo(producerTimestampNs: Long): Boolean {
        val validTimestamp = producerTimestampNs > 0L
        val repeated =
            validTimestamp &&
                producerTimestampNs == lastProducerTimestampNs

        if (validTimestamp && !repeated) {
            lastProducerTimestampNs = producerTimestampNs
        }

        return !(interactionProbeActive && repeated)
    }
}
