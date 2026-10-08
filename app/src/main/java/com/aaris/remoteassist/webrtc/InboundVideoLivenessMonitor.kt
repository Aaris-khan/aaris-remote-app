package com.aaris.remoteassist.webrtc

enum class InboundVideoLivenessAction {
    NONE,
    REQUEST_RECOVERY,
    CONFIRM_PRIMARY_RECOVERED
}

/**
 * Detects a stopped decoder from monotonic inbound RTP counters.
 *
 * getStats() is already sampled more frequently during interactions than while
 * idle. A sample-count threshold therefore gives wildly different recovery
 * deadlines depending on the current stats cadence. Use elapsed time instead,
 * without adding a poll, timer, frame copy, or media queue.
 *
 * Incoming RTP without decoded frames is strong decoder-stall evidence, so it
 * receives a shorter deadline. When neither counter advances, tolerate a much
 * longer network/quiet-screen gap. Alternating packet bursts never inherit a
 * decoder-stall deadline from an earlier unrelated interval.
 */
class InboundVideoLivenessMonitor(
    private val decoderStallMs: Long = 2_500L,
    private val transportStallMs: Long = 12_000L,
    private val retryCooldownMs: Long = 4_000L
) {
    init {
        require(decoderStallMs >= 1_000L)
        require(transportStallMs > decoderStallMs)
        require(retryCooldownMs >= 1_000L)
    }

    private var lastFramesReceived = -1L
    private var lastFramesDecoded = -1L
    private var lastSampleAtMs = -1L
    private var decoderStallSinceMs: Long? = null
    private var transportStallSinceMs: Long? = null
    private var lastRecoveryAttemptMs: Long? = null
    private var recoveryPending = false

    @Synchronized
    fun observe(
        framesReceived: Long,
        framesDecoded: Long,
        nowMs: Long
    ): InboundVideoLivenessAction {
        if (framesReceived < 0L || framesDecoded < 0L || nowMs < 0L) {
            return InboundVideoLivenessAction.NONE
        }

        if (
            lastSampleAtMs < 0L ||
            nowMs < lastSampleAtMs ||
            framesReceived < lastFramesReceived ||
            framesDecoded < lastFramesDecoded
        ) {
            // First sample, clock discontinuity, or a new RTP counter generation.
            lastFramesReceived = framesReceived
            lastFramesDecoded = framesDecoded
            lastSampleAtMs = nowMs
            decoderStallSinceMs = null
            transportStallSinceMs = null
            lastRecoveryAttemptMs = null
            recoveryPending = false
            return InboundVideoLivenessAction.NONE
        }

        val receivedAdvanced = framesReceived > lastFramesReceived
        val decodedAdvanced = framesDecoded > lastFramesDecoded
        lastFramesReceived = framesReceived
        lastFramesDecoded = framesDecoded
        lastSampleAtMs = nowMs

        if (decodedAdvanced) {
            decoderStallSinceMs = null
            transportStallSinceMs = null
            return if (recoveryPending) {
                InboundVideoLivenessAction.CONFIRM_PRIMARY_RECOVERED
            } else {
                InboundVideoLivenessAction.NONE
            }
        }

        // Do not diagnose a freeze before primary video has ever decoded.
        if (framesReceived == 0L || framesDecoded == 0L) {
            decoderStallSinceMs = null
            transportStallSinceMs = null
            return InboundVideoLivenessAction.NONE
        }

        if (receivedAdvanced) {
            transportStallSinceMs = null
            if (decoderStallSinceMs == null) decoderStallSinceMs = nowMs
        } else {
            // A brief RTP pause must not count as ongoing decoder overload.
            decoderStallSinceMs = null
            if (transportStallSinceMs == null) transportStallSinceMs = nowMs
        }

        if (recoveryPending) return InboundVideoLivenessAction.NONE

        val stalled =
            if (receivedAdvanced) {
                decoderStallSinceMs?.let { nowMs - it >= decoderStallMs } == true
            } else {
                transportStallSinceMs?.let { nowMs - it >= transportStallMs } == true
            }
        val retryAllowed =
            lastRecoveryAttemptMs?.let { nowMs - it >= retryCooldownMs } != false

        if (!stalled || !retryAllowed) return InboundVideoLivenessAction.NONE

        lastRecoveryAttemptMs = nowMs
        recoveryPending = true
        return InboundVideoLivenessAction.REQUEST_RECOVERY
    }

    @Synchronized
    fun markRecoveryRequestFailed() {
        // A failed transport request can retry after the same bounded cooldown.
        // The next getStats() must not spin recovery requests every 500 ms.
        recoveryPending = false
    }

    @Synchronized
    fun markPrimaryRecoveredConfirmed() {
        recoveryPending = false
        decoderStallSinceMs = null
        transportStallSinceMs = null
        lastRecoveryAttemptMs = null
    }

    @Synchronized
    fun reset() {
        lastFramesReceived = -1L
        lastFramesDecoded = -1L
        lastSampleAtMs = -1L
        decoderStallSinceMs = null
        transportStallSinceMs = null
        lastRecoveryAttemptMs = null
        recoveryPending = false
    }
}
