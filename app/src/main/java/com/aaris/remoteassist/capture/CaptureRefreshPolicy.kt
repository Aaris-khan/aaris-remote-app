package com.aaris.remoteassist.capture

/** Keep a still screen observable without polling pixels or duplicating moving frames. */
internal class CaptureRefreshPolicy {
    private var lastSampleAtMs: Long? = null
    private var lastRequestAtMs: Long? = null

    @Synchronized
    fun onSample(nowMs: Long) {
        lastSampleAtMs = nowMs
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
    }
}
