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
 * A forced delivery can be deferred while SurfaceTextureHelper's single texture
 * is still in use, so this cannot be scoped only to the forceFrame runnable.
 * Arm one short monotonic window instead. The first exact producer-timestamp
 * repeat inside that window is suppressed and consumes the token. Natural new
 * frames always pass. The window is far shorter than the 1 s idle heartbeat.
 */
internal class InteractionRepeatGuard {
    private var lastProducerTimestampNs = Long.MIN_VALUE
    private var suppressRepeatUntilNs = Long.MIN_VALUE

    fun armInteractionProbe(nowNs: Long) {
        suppressRepeatUntilNs =
            nowNs + INTERACTION_REPEAT_WINDOW_NS
    }

    fun shouldForwardToLiveVideo(
        producerTimestampNs: Long,
        nowNs: Long
    ): Boolean {
        val validTimestamp = producerTimestampNs > 0L
        val repeated =
            validTimestamp &&
                producerTimestampNs == lastProducerTimestampNs
        val interactionRepeat =
            repeated &&
                nowNs >= 0L &&
                nowNs <= suppressRepeatUntilNs

        if (validTimestamp && !repeated) {
            lastProducerTimestampNs = producerTimestampNs
        }

        if (interactionRepeat) {
            suppressRepeatUntilNs = Long.MIN_VALUE
            return false
        }

        if (nowNs > suppressRepeatUntilNs) {
            suppressRepeatUntilNs = Long.MIN_VALUE
        }
        return true
    }

    internal companion object {
        const val INTERACTION_REPEAT_WINDOW_NS = 100_000_000L
    }
}
