package com.aaris.remoteassist.webrtc

import com.aaris.remoteassist.control.ControlPacket

/** One-use, lease-bound feedback. Old generations and buffered/replayed reports cannot tune video. */
class ReceiverVideoFeedback {
    private var identity: Pair<Long, Int>? = null
    private var lastSequence = 0L
    private var receivedAt = 0L
    private var pending: VideoHealthWindow? = null
    private var lastDecodedAtMs: Long? = null

    private var lastPresentationSequence = 0L
    private var lastPresentationHeartbeatSequence = 0L
    private var presentationReceivedAt = 0L
    private var lastPresentedAtMs: Long? = null
    private var pendingPresentation: PresentationCadenceWindow? = null
    private var lastCapabilitiesSequence = 0L
    private var capabilitiesAtMs = 0L
    private var displayRefreshRateHz: Double? = null

    private fun bindIdentity(secret: Long, generation: Int) {
        val key = secret to generation
        if (identity == key) return
        identity = key
        lastSequence = 0L
        receivedAt = 0L
        pending = null
        lastDecodedAtMs = null
        lastPresentationSequence = 0L
        lastPresentationHeartbeatSequence = 0L
        presentationReceivedAt = 0L
        lastPresentedAtMs = null
        pendingPresentation = null
        lastCapabilitiesSequence = 0L
        capabilitiesAtMs = 0L
        displayRefreshRateHz = null
    }

    fun accept(packet: ControlPacket.DisplayCapabilities, secret: Long, generation: Int, nowMs: Long): Boolean {
        if (!packet.valid() || packet.leaseSecret != secret || packet.generation != generation) return false
        bindIdentity(secret, generation)
        if (packet.sequence <= lastCapabilitiesSequence) return false
        lastCapabilitiesSequence = packet.sequence
        capabilitiesAtMs = nowMs
        displayRefreshRateHz = packet.refreshRateHz.toDouble()
        return true
    }

    fun accept(packet: ControlPacket.VideoFeedback, secret: Long, generation: Int, nowMs: Long): Boolean {
        if (!packet.valid() || packet.leaseSecret != secret || packet.generation != generation) return false
        bindIdentity(secret, generation)
        if (packet.sequence <= lastSequence) return false
        lastSequence = packet.sequence
        receivedAt = nowMs
        if (packet.decodedFrames > 0) lastDecodedAtMs = nowMs
        pending = VideoHealthWindow(
            packet.intervalMs,
            packet.decodedFrames,
            packet.decodeMs.takeIf { it >= 0 }?.toDouble(),
            packet.jitterMs.takeIf { it >= 0 }?.toDouble(),
            packet.droppedFrames.takeIf { it >= 0 },
            packet.freezeEvents.takeIf { it >= 0 }
        )
        return true
    }

    /** Consuming a tuning window must not erase evidence of a working decoder. */
    fun hasRecentDecodedFrames(secret: Long, generation: Int, nowMs: Long): Boolean =
        identity == secret to generation &&
            lastDecodedAtMs?.let { nowMs - it in 0L..MAX_AGE_MS } == true

    fun accept(packet: ControlPacket.PresentationFeedback, secret: Long, generation: Int, nowMs: Long): Boolean {
        if (!packet.valid() || packet.leaseSecret != secret || packet.generation != generation) return false
        bindIdentity(secret, generation)
        if (packet.sequence <= lastPresentationSequence) return false
        lastPresentationSequence = packet.sequence

        val incoming = PresentationCadenceWindow(
            intervalMs = packet.intervalMs,
            renderedFrames = packet.renderedFrames,
            maxGapMs = packet.maxGapMs
        )

        /*
         * EGL presentation feedback intentionally arrives more often than the
         * sender's expensive getStats() probe. Accumulate consecutive windows
         * until the host consumes them so an early half-second hitch cannot be
         * overwritten by a later healthy half-second just before the stats tick.
         *
         * FPS remains an aggregate renderedFrames / interval measurement, while
         * maxGap keeps the worst motion-to-photon stall seen in the interval.
         * If a consumer disappears long enough to exceed protocol bounds, start
         * a fresh bounded window rather than manufacturing an oversized packet.
         */
        pendingPresentation = pendingPresentation?.let { previous ->
            val combinedInterval = previous.intervalMs + incoming.intervalMs
            val combinedFrames = previous.renderedFrames + incoming.renderedFrames
            if (
                combinedInterval <= MAX_PRESENTATION_INTERVAL_MS &&
                combinedFrames <= MAX_PRESENTED_FRAMES
            ) {
                PresentationCadenceWindow(
                    intervalMs = combinedInterval,
                    renderedFrames = combinedFrames,
                    maxGapMs = maxOf(previous.maxGapMs, incoming.maxGapMs)
                )
            } else {
                incoming
            }
        } ?: incoming
        presentationReceivedAt = nowMs
        lastPresentedAtMs = nowMs
        return true
    }

    fun accept(
        packet: ControlPacket.PresentationHeartbeat,
        secret: Long,
        generation: Int,
        nowMs: Long
    ): Boolean {
        if (!packet.valid() || packet.leaseSecret != secret || packet.generation != generation) return false
        bindIdentity(secret, generation)
        if (packet.sequence <= lastPresentationHeartbeatSequence) return false
        lastPresentationHeartbeatSequence = packet.sequence
        lastPresentedAtMs = nowMs
        return true
    }

    fun consume(secret: Long, generation: Int, nowMs: Long): VideoHealthWindow? {
        if (identity != secret to generation) return null

        val video = pending?.takeIf { nowMs - receivedAt in 0..MAX_AGE_MS }
        pending = null
        val presentation = pendingPresentation?.takeIf {
            nowMs - presentationReceivedAt in 0..MAX_AGE_MS
        }
        pendingPresentation = null
        val presentationAgeMs = lastPresentedAtMs
            ?.let { nowMs - it }
            ?.takeIf { it in 0L..MAX_PRESENTATION_LIVENESS_AGE_MS }
            ?.toInt()

        if (video == null && presentation == null && presentationAgeMs == null) return null
        val base = video ?: VideoHealthWindow(
            intervalMs = presentation?.intervalMs ?: 1_000,
            frames = 0,
            processingMs = null,
            jitterMs = null,
            dropped = null,
            freezes = null,
            presentationAgeMs = presentationAgeMs
        )
        val refreshRate = displayRefreshRateHz?.takeIf { nowMs - capabilitiesAtMs in 0L..MAX_AGE_MS }
        return if (presentation == null) {
            base.copy(presentationAgeMs = presentationAgeMs, displayRefreshRateHz = refreshRate)
        } else {
            base.copy(
                presentationIntervalMs = presentation.intervalMs,
                presentedFrames = presentation.renderedFrames,
                maxRenderGapMs = presentation.maxGapMs,
                presentationAgeMs = presentationAgeMs,
                displayRefreshRateHz = refreshRate
            )
        }
    }

    private companion object {
        const val MAX_AGE_MS = 3_000L
        const val MAX_PRESENTATION_INTERVAL_MS = 6_000
        const val MAX_PRESENTED_FRAMES = 2_048
        const val MAX_PRESENTATION_LIVENESS_AGE_MS = 30_000L
    }
}
