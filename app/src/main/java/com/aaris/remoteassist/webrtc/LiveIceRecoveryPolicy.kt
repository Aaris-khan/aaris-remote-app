package com.aaris.remoteassist.webrtc

internal enum class LiveIceRecoveryMode {
    ALL_PATHS,
    RELAY_ONLY
}

/**
 * Keeps interactive recovery latency-first without sacrificing reachability.
 *
 * A one-off transport hiccup should restart ICE with ALL candidates so WebRTC
 * can immediately pick a lower-latency host/srflx UDP path when one exists,
 * while authenticated TURN remains available as a fallback candidate.
 *
 * Repeated recovery requests inside a short window are stronger evidence that
 * the direct path is genuinely unhealthy. Only then escalate one attempt to
 * RELAY-only for deterministic reachability.
 *
 * The policy is time-windowed instead of counter-reset callbacks so a healthy
 * session naturally decays back to direct-first behavior without timers.
 */
internal class LiveIceRecoveryPolicy(
    private val escalationWindowMs: Long = DEFAULT_ESCALATION_WINDOW_MS
) {
    init {
        require(escalationWindowMs > 0L)
    }

    private var lastRequestAtMs = Long.MIN_VALUE / 2
    private var clusteredRequests = 0

    @Synchronized
    fun next(nowMs: Long): LiveIceRecoveryMode {
        require(nowMs >= 0L)

        clusteredRequests =
            if (
                lastRequestAtMs > Long.MIN_VALUE / 4 &&
                nowMs - lastRequestAtMs in 0L..escalationWindowMs
            ) {
                (clusteredRequests + 1).coerceAtMost(MAX_CLUSTERED_REQUESTS)
            } else {
                1
            }
        lastRequestAtMs = nowMs

        return if (clusteredRequests >= REQUESTS_BEFORE_RELAY_ESCALATION) {
            LiveIceRecoveryMode.RELAY_ONLY
        } else {
            LiveIceRecoveryMode.ALL_PATHS
        }
    }

    @Synchronized
    fun reset() {
        lastRequestAtMs = Long.MIN_VALUE / 2
        clusteredRequests = 0
    }

    private companion object {
        const val DEFAULT_ESCALATION_WINDOW_MS = 12_000L
        const val REQUESTS_BEFORE_RELAY_ESCALATION = 2
        const val MAX_CLUSTERED_REQUESTS = 8
    }
}
