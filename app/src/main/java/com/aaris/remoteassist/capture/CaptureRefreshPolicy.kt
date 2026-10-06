package com.aaris.remoteassist.capture

/** Keep a still screen observable without polling pixels or duplicating moving frames. */
internal class CaptureRefreshPolicy {
    private var lastSampleAtMs: Long? = null
    private var lastRequestAtMs: Long? = null

    @Synchronized
    fun onSample(nowMs: Long) {
        lastSampleAtMs = nowMs
    }

    /**
     * During remote control, an applied input is stronger evidence that fresh
     * pixels are useful than the producer's sampled FPS. If MediaProjection has
     * not naturally delivered a frame within roughly one 24-fps period, request
     * the current SurfaceTexture. Natural motion remains zero-extra-work.
     */
    @Synchronized
    fun shouldRefreshForInteraction(nowMs: Long): Boolean {
        val sample = lastSampleAtMs ?: return false
        val previous = maxOf(sample, lastRequestAtMs ?: sample)
        if (nowMs - previous < INTERACTION_STALE_MS) return false
        lastRequestAtMs = nowMs
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
        const val INTERACTION_STALE_MS = 45L
    }
}
