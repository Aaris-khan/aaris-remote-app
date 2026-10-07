package com.aaris.remoteassist.webrtc

internal class HighCadencePressureGate(
    private val requiredVotes: Int = DEFAULT_REQUIRED_VOTES,
    private val minVoteSpacingMs: Long = DEFAULT_MIN_VOTE_SPACING_MS,
    private val maxEvidenceGapMs: Long = DEFAULT_MAX_EVIDENCE_GAP_MS
) {
    private data class Lane(
        var votes: Int = 0,
        var lastVoteAtMs: Long = Long.MIN_VALUE / 2
    )

    private val telemetry = Lane()
    private val presentation = Lane()

    init {
        require(requiredVotes >= 1)
        require(minVoteSpacingMs >= 0L)
        require(maxEvidenceGapMs >= minVoteSpacingMs)
    }

    fun observeTelemetry(
        nowMs: Long,
        softPressure: Boolean,
        corroborated: Boolean
    ): Boolean =
        observe(
            lane = telemetry,
            nowMs = nowMs,
            pressure = softPressure,
            voteWeight = if (corroborated) requiredVotes else 1
        )

    fun observePresentation(
        nowMs: Long,
        softPressure: Boolean,
        severePressure: Boolean
    ): Boolean {
        if (severePressure) {
            reset()
            return true
        }
        return observe(
            lane = presentation,
            nowMs = nowMs,
            pressure = softPressure,
            voteWeight = 1
        )
    }

    fun clearTelemetry() {
        clear(telemetry)
    }

    fun clearPresentation() {
        clear(presentation)
    }

    fun reset() {
        clear(telemetry)
        clear(presentation)
    }

    fun compact(nowMs: Long): String =
        "softGate=t${telemetry.votes}/$requiredVotes@" +
            age(telemetry, nowMs) +
            " p${presentation.votes}/$requiredVotes@" +
            age(presentation, nowMs)

    private fun observe(
        lane: Lane,
        nowMs: Long,
        pressure: Boolean,
        voteWeight: Int
    ): Boolean {
        if (nowMs < 0L) return false

        if (!pressure) {
            clear(lane)
            return false
        }

        val ageMs = nowMs - lane.lastVoteAtMs
        if (ageMs > maxEvidenceGapMs) {
            lane.votes = 0
        } else if (ageMs >= 0L && ageMs < minVoteSpacingMs) {
            return false
        }

        lane.lastVoteAtMs = nowMs
        lane.votes =
            (lane.votes + voteWeight)
                .coerceAtMost(requiredVotes)

        if (lane.votes < requiredVotes) return false

        reset()
        return true
    }

    private fun clear(lane: Lane) {
        lane.votes = 0
        lane.lastVoteAtMs = Long.MIN_VALUE / 2
    }

    private fun age(lane: Lane, nowMs: Long): String {
        if (lane.votes == 0 || lane.lastVoteAtMs <= Long.MIN_VALUE / 4) {
            return "-"
        }
        return (nowMs - lane.lastVoteAtMs)
            .coerceAtLeast(0L)
            .let { "${it}ms" }
    }

    private companion object {
        const val DEFAULT_REQUIRED_VOTES = 2
        const val DEFAULT_MIN_VOTE_SPACING_MS = 300L
        const val DEFAULT_MAX_EVIDENCE_GAP_MS = 2_200L
    }
}
