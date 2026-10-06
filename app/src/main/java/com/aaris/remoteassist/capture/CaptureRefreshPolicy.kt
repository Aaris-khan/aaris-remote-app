package com.aaris.remoteassist.capture

/** Keep a still screen observable without polling pixels or duplicating moving frames. */
internal class CaptureRefreshPolicy {
    private var lastSampleAtMs: Long? = null
    private var lastRequestAtMs: Long? = null
    private var interactionAppliedAtMs: Long? = null

    @Synchronized
    fun onSample(nowMs: Long) {
        lastSampleAtMs = nowMs

        /*
         * A natural producer callback after the input is exactly what we want.
         * Cancel the armed synthetic pull so moving screens remain zero-extra-work.
         */
        val appliedAt = interactionAppliedAtMs
        if (appliedAt != null && nowMs > appliedAt) {
            interactionAppliedAtMs = null
        }
    }

    /** Arms one bounded post-input freshness pull. */
    @Synchronized
    fun onInteractionApplied(nowMs: Long) {
        if (lastSampleAtMs == null) return
        interactionAppliedAtMs = nowMs
    }

    /**
     * After one display tick, pull the current SurfaceTexture only if no natural
     * MediaProjection frame arrived after the applied input. Each interaction
     * arms at most one synthetic sample, so rapid touches cannot build a queue.
     */
    @Synchronized
    fun shouldRefreshForInteraction(nowMs: Long): Boolean {
        val appliedAt = interactionAppliedAtMs ?: return false
        val sample = lastSampleAtMs ?: return false

        if (nowMs < appliedAt) return false
        if (sample > appliedAt) {
            interactionAppliedAtMs = null
            return false
        }
        if (nowMs - appliedAt < INTERACTION_GRACE_MS) return false

        val previousRequest = lastRequestAtMs
        if (previousRequest != null && previousRequest >= appliedAt) {
            interactionAppliedAtMs = null
            return false
        }

        lastRequestAtMs = nowMs
        interactionAppliedAtMs = null
        return true
    }

    @Synchronized
    fun shouldRefresh(nowMs: Long): Boolean {
        // Never manufacture a frame before MediaProjection supplied its first texture.
        val sample = lastSampleAtMs ?: return false
        val previous = maxOf(sample, lastRequestAtMs ?: sample)
        if (nowMs - previous < INTERVAL_MS) return false
        lastRequestAtMs = nowMs
        return true
    }

    companion object {
        const val INTERVAL_MS = 1_000L

        // The caller normally waits one actual display tick. This tiny lower
        // bound only prevents same-timestamp scheduling from duplicating work.
        const val INTERACTION_GRACE_MS = 6L
    }
}
