package com.aaris.remoteassist.capture

/** Keep a still screen observable without polling pixels or duplicating moving frames. */
internal class CaptureRefreshPolicy {
    private var lastSampleAtMs: Long? = null
    private var lastRequestAtMs: Long? = null
    private var interactionAppliedAtMs: Long? = null
    private var sampleGeneration = 0L
    private var interactionSampleGeneration: Long? = null

    @Synchronized
    fun onSample(nowMs: Long) {
        lastSampleAtMs = nowMs
        sampleGeneration =
            if (sampleGeneration == Long.MAX_VALUE) {
                1L
            } else {
                sampleGeneration + 1L
            }

        /*
         * Generation, not millisecond equality, proves that a new capture
         * callback happened after the input. This avoids same-ms ambiguity on
         * fast devices while keeping the hot path allocation-free.
         */
        val armedGeneration = interactionSampleGeneration
        if (
            interactionAppliedAtMs != null &&
            armedGeneration != null &&
            sampleGeneration != armedGeneration
        ) {
            interactionAppliedAtMs = null
            interactionSampleGeneration = null
        }
    }

    /** Arms one bounded post-input freshness pull. */
    @Synchronized
    fun onInteractionApplied(nowMs: Long) {
        if (lastSampleAtMs == null) return
        interactionAppliedAtMs = nowMs
        interactionSampleGeneration = sampleGeneration
    }

    /**
     * After one display tick, pull the current SurfaceTexture only if no natural
     * MediaProjection frame arrived after the applied input. Each interaction
     * arms at most one synthetic sample, so rapid touches cannot build a queue.
     */
    @Synchronized
    fun shouldRefreshForInteraction(nowMs: Long): Boolean {
        val appliedAt = interactionAppliedAtMs ?: return false
        lastSampleAtMs ?: return false
        val armedGeneration = interactionSampleGeneration ?: return false

        if (nowMs < appliedAt) return false
        if (sampleGeneration != armedGeneration) {
            interactionAppliedAtMs = null
            interactionSampleGeneration = null
            return false
        }
        if (nowMs - appliedAt < INTERACTION_GRACE_MS) return false

        val previousRequest = lastRequestAtMs
        if (previousRequest != null && previousRequest >= appliedAt) {
            interactionAppliedAtMs = null
            interactionSampleGeneration = null
            return false
        }

        lastRequestAtMs = nowMs
        interactionAppliedAtMs = null
        interactionSampleGeneration = null
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
